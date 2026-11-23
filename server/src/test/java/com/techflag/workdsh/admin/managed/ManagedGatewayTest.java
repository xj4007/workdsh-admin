package com.techflag.workdsh.admin.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.techflag.workdsh.admin.AccountSpaces;
import com.techflag.workdsh.admin.MemberSecrets;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:managed-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workdsh.bootstrap.admin-email=owner@managed.test",
        "workdsh.bootstrap.admin-password=OwnerPassword123!",
        "workdsh.accounts.expose-verification-code=true",
        "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "workdsh.managed.upstream-timeout-seconds=2",
        "server.error.include-message=always"
})
class ManagedGatewayTest {
    private static final String PASSWORD = "Password12345!";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static HttpServer upstream;
    private static final AtomicReference<String> MODE = new AtomicReference<>("nonstream");
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired MemberSecrets secrets;
    @Autowired AccountSpaces accounts;
    private final HttpClient http = HttpClient.newHttpClient();
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

    record AccountSession(String token, String spaceId) {}

    @BeforeAll
    static void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newCachedThreadPool());
        upstream.createContext("/v1/chat/completions", exchange -> {
            CALLS.incrementAndGet();
            LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String mode = MODE.get();
            if ("timeout".equals(mode)) {
                sleep(3000);
                sendJson(exchange, 200, completion("chatcmpl-timeout", 1, 1));
                return;
            }
            if ("reject".equals(mode)) {
                sendJson(exchange, 429, "{\"error\":{\"message\":\"rate limited\"}}");
                return;
            }
            if ("no-usage".equals(mode)) {
                sendJson(exchange, 200, "{\"id\":\"chatcmpl-nousage\",\"object\":\"chat.completion\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                        + "\"finish_reason\":\"stop\"}]}");
                return;
            }
            if ("stream".equals(mode)) {
                sendStream(exchange);
                return;
            }
            if ("stream-no-usage".equals(mode)) {
                sendStreamWithoutUsage(exchange);
                return;
            }
            if ("slow-stream".equals(mode)) {
                sendSlowStream(exchange);
                return;
            }
            sendJson(exchange, 200, completion("chatcmpl-nonstream", 800, 200));
        });
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) upstream.stop(0);
    }

    @BeforeEach
    void reset() {
        MODE.set("nonstream");
        CALLS.set(0);
        LAST_BODY.set(null);
        LAST_AUTH.set(null);
    }

    @Test
    void nonStreamSettlesFromActualUsageAndForwardsVendorModel() throws Exception {
        AccountSession session = register("nonstream@managed.test");
        insertModel("nonstream-model", upstreamBase(), 15000, 1000, 1000, true, 1);

        HttpResponse<String> reply = managed(session.token(),
                "{\"model\":\"nonstream-model\",\"request_id\":\"req-nonstream-1\",\"price_version\":1,"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"SECRET-CHAT-BODY\"}]}");

        assertEquals(200, reply.statusCode(), reply.body());
        assertTrue(reply.body().contains("chatcmpl-nonstream"));
        assertEquals(1, CALLS.get());
        JsonNode forwarded = JSON.readTree(LAST_BODY.get());
        assertEquals("vendor-nonstream-model", forwarded.get("model").asText());
        assertFalse(forwarded.has("request_id"));
        assertFalse(forwarded.has("price_version"));
        assertEquals("Bearer vendor-key-nonstream-model", LAST_AUTH.get());

        assertEquals(498500L, wallet(session.spaceId()));
        assertEquals(0L, reserved(session.spaceId()));
        Map<String, Object> row = row("req-nonstream-1");
        assertEquals("SETTLED", row.get("status"));
        assertEquals(800L, ((Number) row.get("input_tokens")).longValue());
        assertEquals(200L, ((Number) row.get("output_tokens")).longValue());
        assertEquals(1500L, ((Number) row.get("charged_units")).longValue());
        assertEquals(3000L, ((Number) row.get("reserved_units")).longValue());
        assertEquals(1, ((Number) row.get("price_version")).intValue());
        assertEquals(15000, ((Number) row.get("multiplier_basis_points")).intValue());
        assertEquals(session.spaceId(), row.get("space_id"));
        assertFalse(rowContains(row, "SECRET-CHAT-BODY"));
        assertEquals(-1500L, ((Number) ledger("req-nonstream-1").get("delta_units")).longValue());
        assertEquals("USAGE", ledger("req-nonstream-1").get("reason"));
    }

    @Test
    void streamingPassesSseAndSettlesFinalUsage() throws Exception {
        AccountSession session = register("stream@managed.test");
        insertModel("stream-model", upstreamBase(), 20000, 1000, 1000, true, 1);
        MODE.set("stream");

        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/api/managed/v1/chat/completions"))
                .header("Authorization", "Bearer " + session.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"stream-model\",\"request_id\":\"req-stream-1\",\"stream\":true,\"price_version\":1,"
                                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
                .build();
        HttpResponse<InputStream> reply = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, reply.statusCode());
        assertEquals("text/event-stream", reply.headers().firstValue("Content-Type").orElse(""));
        String body = new String(reply.body().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"content\":\"he\""));
        assertTrue(body.contains("\"content\":\"llo\""));
        assertTrue(body.contains("[DONE]"));

        JsonNode forwarded = JSON.readTree(LAST_BODY.get());
        assertTrue(forwarded.path("stream_options").path("include_usage").asBoolean(false));
        assertEquals(1, CALLS.get());
        assertEquals(499700L, wallet(session.spaceId()));
        assertEquals(0L, reserved(session.spaceId()));
        Map<String, Object> row = row("req-stream-1");
        assertEquals("SETTLED", row.get("status"));
        assertEquals(100L, ((Number) row.get("input_tokens")).longValue());
        assertEquals(50L, ((Number) row.get("output_tokens")).longValue());
        assertEquals(300L, ((Number) row.get("charged_units")).longValue());
    }

    @Test
    void streamWithoutUsageMarksReviewAndKeepsReservation() throws Exception {
        AccountSession session = register("stream-unknown@managed.test");
        insertModel("stream-unknown-model", upstreamBase(), 15000, 1000, 1000, true, 1);
        MODE.set("stream-no-usage");

        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/api/managed/v1/chat/completions"))
                .header("Authorization", "Bearer " + session.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"stream-unknown-model\",\"request_id\":\"req-stream-unknown-1\",\"stream\":true,"
                                + "\"price_version\":1,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
                .build();
        HttpResponse<InputStream> reply = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, reply.statusCode());
        assertTrue(new String(reply.body().readAllBytes(), StandardCharsets.UTF_8).contains("he"));

        Map<String, Object> row = row("req-stream-unknown-1");
        assertEquals("REVIEW", row.get("status"));
        assertEquals(0L, ((Number) row.get("charged_units")).longValue());
        assertEquals(500000L, wallet(session.spaceId()));
        assertEquals(3000L, reserved(session.spaceId()));
        assertEquals(0, count("select count(*) from credit_ledger where reference='managed:req-stream-unknown-1'"));
    }

    @Test
    void clientAbortDuringStreamMarksReviewKeepsReservationAndDoesNotRetry() throws Exception {
        AccountSession session = register("abort@managed.test");
        insertModel("abort-model", upstreamBase(), 15000, 1000, 1000, true, 1);
        MODE.set("slow-stream");

        HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/api/managed/v1/chat/completions"))
                .header("Authorization", "Bearer " + session.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"abort-model\",\"request_id\":\"req-abort-1\",\"stream\":true,\"price_version\":1,"
                                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
                .build();
        HttpResponse<InputStream> reply = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, reply.statusCode());
        sleep(200); // let the gateway start relaying while more chunks are still pending upstream
        reply.body().close(); // client aborts mid-stream; upstream usage never reaches the gateway

        Map<String, Object> row = rowEventually("req-abort-1", "REVIEW");
        assertNotNull(row, "managed request row for req-abort-1");
        assertEquals("REVIEW", row.get("status"));
        assertEquals(0L, ((Number) row.get("charged_units")).longValue());
        assertEquals(500000L, wallet(session.spaceId()));
        assertEquals(3000L, reserved(session.spaceId()));
        assertEquals(0, count("select count(*) from credit_ledger where reference='managed:req-abort-1'"));
        assertEquals(1, CALLS.get()); // executed once, never retried after the abort
    }

    @Test
    void missingPriceVersionIsRejectedBeforeReserveOrForward() throws Exception {
        AccountSession session = register("noprice@managed.test");
        insertModel("noprice-model", upstreamBase(), 15000, 1000, 1000, true, 1);

        HttpResponse<String> reply = managed(session.token(),
                "{\"model\":\"noprice-model\",\"request_id\":\"req-noprice-1\","
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertEquals(400, reply.statusCode(), reply.body());
        assertTrue(reply.body().contains("price_version is required"), reply.body());
        assertEquals(0, CALLS.get());
        assertEquals(0, count("select count(*) from managed_requests where request_id='req-noprice-1'"));
        assertEquals(0, count("select count(*) from credit_ledger where reference='managed:req-noprice-1'"));
        assertEquals(500000L, wallet(session.spaceId()));
        assertEquals(0L, reserved(session.spaceId()));

        HttpResponse<String> nullVersion = managed(session.token(),
                "{\"model\":\"noprice-model\",\"request_id\":\"req-noprice-2\",\"price_version\":null,"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals(400, nullVersion.statusCode(), nullVersion.body());
        assertTrue(nullVersion.body().contains("price_version is required"), nullVersion.body());
        assertEquals(0, CALLS.get());
        assertEquals(0, count("select count(*) from managed_requests where request_id='req-noprice-2'"));
    }

    @Test
    void duplicateRequestIdDoesNotForwardOrChargeTwice() throws Exception {
        AccountSession session = register("duplicate@managed.test");
        insertModel("duplicate-model", upstreamBase(), 15000, 1000, 1000, true, 1);
        String body = "{\"model\":\"duplicate-model\",\"request_id\":\"req-duplicate-1\",\"price_version\":1,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        assertEquals(200, managed(session.token(), body).statusCode());
        HttpResponse<String> second = managed(session.token(), body);
        assertEquals(409, second.statusCode(), second.body());
        assertTrue(second.body().contains("already submitted"));
        assertEquals(1, CALLS.get());
        assertEquals(498500L, wallet(session.spaceId()));
        assertEquals(1, count("select count(*) from managed_requests where request_id='req-duplicate-1'"));
    }

    @Test
    void insufficientBalanceReturns402BeforeUpstream() throws Exception {
        AccountSession session = register("insufficient@managed.test");
        insertModel("insufficient-model", upstreamBase(), 15000, 1000, 1000, true, 1);
        db.update("update credit_wallets set balance_units=100,reserved_units=0 where space_id=?", session.spaceId());

        HttpResponse<String> reply = managed(session.token(),
                "{\"model\":\"insufficient-model\",\"request_id\":\"req-insufficient-1\",\"price_version\":1,"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertEquals(402, reply.statusCode(), reply.body());
        assertEquals(0, CALLS.get());
        assertEquals(0, count("select count(*) from managed_requests where request_id='req-insufficient-1'"));
    }

    @Test
    void stalePriceVersionReturnsRefreshError() throws Exception {
        AccountSession session = register("stale@managed.test");
        insertModel("stale-model", upstreamBase(), 15000, 1000, 1000, true, 2);

        HttpResponse<String> reply = managed(session.token(),
                "{\"model\":\"stale-model\",\"request_id\":\"req-stale-1\",\"price_version\":1,"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertEquals(409, reply.statusCode(), reply.body());
        assertTrue(reply.body().contains("refresh"));
        assertEquals(0, CALLS.get());
        assertEquals(0, count("select count(*) from managed_requests where request_id='req-stale-1'"));
    }

    @Test
    void disabledModelIsRejected() throws Exception {
        AccountSession session = register("disabled@managed.test");
        insertModel("disabled-model", upstreamBase(), 15000, 1000, 1000, false, 1);

        HttpResponse<String> reply = managed(session.token(),
                "{\"model\":\"disabled-model\",\"request_id\":\"req-disabled-1\",\"price_version\":1,"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertEquals(403, reply.statusCode(), reply.body());
        assertEquals(0, CALLS.get());
    }

    @Test
    void enterpriseSessionAndMissingTokenAreRejected() throws Exception {
        assertEquals(401, managed(null, "{\"model\":\"whatever\",\"messages\":[]}").statusCode());

        String email = "enterprise-" + UUID.randomUUID() + "@managed.test";
        String orgId = db.queryForObject("select id from organizations order by id limit 1", String.class);
        String memberId = UUID.randomUUID().toString();
        db.update("""
                insert into members(id,organization_id,email,display_name,role,password_hash,
                  must_change_password,active,revision) values (?,?,?,?,?,?,false,true,1)
                """, memberId, orgId, email, "企业成员", "ADMIN", passwords.encode(PASSWORD));
        accounts.linkMember(memberId);

        HttpResponse<String> login = post("/api/account/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, login.statusCode(), login.body());
        JsonNode loginNode = JSON.readTree(login.body());
        String token = loginNode.get("token").asText();
        String enterpriseSpaceId = null;
        for (JsonNode space : loginNode.get("spaces")) {
            if ("ENTERPRISE".equals(space.get("kind").asText())) enterpriseSpaceId = space.get("id").asText();
        }
        assertNotNull(enterpriseSpaceId);
        HttpResponse<String> switched = post("/api/account/spaces/" + enterpriseSpaceId + "/token", token, "");
        assertEquals(200, switched.statusCode(), switched.body());
        String enterpriseToken = JSON.readTree(switched.body()).get("token").asText();

        HttpResponse<String> reply = managed(enterpriseToken,
                "{\"model\":\"whatever\",\"request_id\":\"req-enterprise-1\","
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals(403, reply.statusCode(), reply.body());
        assertTrue(reply.body().contains("Personal space required"));
    }

    @Test
    void concurrentRequestsCannotOverdrawWallet() throws Exception {
        AccountSession session = register("concurrent@managed.test");
        insertModel("concurrent-model", upstreamBase(), 15000, 1000, 1000, true, 1);
        db.update("update credit_wallets set balance_units=3000,reserved_units=0 where space_id=?", session.spaceId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = CompletableFuture.supplyAsync(() -> managedQuietly(session.token(),
                    "{\"model\":\"concurrent-model\",\"request_id\":\"req-concurrent-1\",\"price_version\":1,"
                            + "\"messages\":[{\"role\":\"user\",\"content\":\"one\"}]}"), pool);
            var second = CompletableFuture.supplyAsync(() -> managedQuietly(session.token(),
                    "{\"model\":\"concurrent-model\",\"request_id\":\"req-concurrent-2\",\"price_version\":1,"
                            + "\"messages\":[{\"role\":\"user\",\"content\":\"two\"}]}"), pool);
            int firstStatus = first.get(20, TimeUnit.SECONDS);
            int secondStatus = second.get(20, TimeUnit.SECONDS);
            assertTrue((firstStatus == 200 && secondStatus == 402) || (firstStatus == 402 && secondStatus == 200),
                    "expected one success and one payment-required, got " + firstStatus + "/" + secondStatus);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, CALLS.get());
        assertEquals(1500L, wallet(session.spaceId()));
        assertEquals(0L, reserved(session.spaceId()));
        assertEquals(1, count("select count(*) from managed_requests where status='SETTLED' and space_id=?",
                session.spaceId()));
    }

    @Test
    void unknownUsageMarksReviewAndKeepsReservation() throws Exception {
        AccountSession session = register("review@managed.test");
        insertModel("review-model", upstreamBase(), 15000, 1000, 1000, true, 1);
        MODE.set("no-usage");

        HttpResponse<String> reply = managed(session.token(),
                "{\"model\":\"review-model\",\"request_id\":\"req-review-1\",\"price_version\":1,"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertEquals(502, reply.statusCode(), reply.body());
        Map<String, Object> row = row("req-review-1");
        assertEquals("REVIEW", row.get("status"));
        assertEquals(0L, ((Number) row.get("charged_units")).longValue());
        assertEquals(500000L, wallet(session.spaceId()));
        assertEquals(3000L, reserved(session.spaceId()));
        assertEquals(0, count("select count(*) from credit_ledger where reference='managed:req-review-1'"));
    }

    @Test
    void connectFailureReleasesReservation() throws Exception {
        AccountSession session = register("connect@managed.test");
        insertModel("connect-model", "http://127.0.0.1:1/v1", 15000, 1000, 1000, true, 1);

        HttpResponse<String> reply = managed(session.token(),
                "{\"model\":\"connect-model\",\"request_id\":\"req-connect-1\",\"price_version\":1,"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertEquals(502, reply.statusCode(), reply.body());
        Map<String, Object> row = row("req-connect-1");
        assertEquals("RELEASED", row.get("status"));
        assertEquals(500000L, wallet(session.spaceId()));
        assertEquals(0L, reserved(session.spaceId()));
        assertEquals(0, count("select count(*) from credit_ledger where reference='managed:req-connect-1'"));
    }

    @Test
    void timeoutWithoutEvidenceReleasesReservation() throws Exception {
        AccountSession session = register("timeout@managed.test");
        insertModel("timeout-model", upstreamBase(), 15000, 1000, 1000, true, 1);
        MODE.set("timeout");

        HttpResponse<String> reply = managed(session.token(),
                "{\"model\":\"timeout-model\",\"request_id\":\"req-timeout-1\",\"price_version\":1,"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertEquals(504, reply.statusCode(), reply.body());
        Map<String, Object> row = row("req-timeout-1");
        assertEquals("RELEASED", row.get("status"));
        assertEquals(500000L, wallet(session.spaceId()));
        assertEquals(0L, reserved(session.spaceId()));
    }

    @Test
    void upstreamRejectionReleasesReservation() throws Exception {
        AccountSession session = register("reject@managed.test");
        insertModel("reject-model", upstreamBase(), 15000, 1000, 1000, true, 1);
        MODE.set("reject");

        HttpResponse<String> reply = managed(session.token(),
                "{\"model\":\"reject-model\",\"request_id\":\"req-reject-1\",\"price_version\":1,"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertEquals(502, reply.statusCode(), reply.body());
        assertEquals("RELEASED", row("req-reject-1").get("status"));
        assertEquals(0L, reserved(session.spaceId()));
        assertEquals(500000L, wallet(session.spaceId()));
    }

    private AccountSession register(String email) throws Exception {
        HttpResponse<String> codeReply = post("/api/account/request-code", null, "{\"email\":\"" + email + "\"}");
        assertEquals(202, codeReply.statusCode(), codeReply.body());
        String code = JSON.readTree(codeReply.body()).get("code").asText();
        HttpResponse<String> reply = post("/api/account/register", null,
                "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"password\":\"" + PASSWORD
                        + "\",\"displayName\":\"测试用户\"}");
        assertEquals(200, reply.statusCode(), reply.body());
        JsonNode node = JSON.readTree(reply.body());
        return new AccountSession(node.get("token").asText(), node.get("space").get("id").asText());
    }

    private HttpResponse<String> managed(String token, String body) throws Exception {
        return post("/api/managed/v1/chat/completions", token, body);
    }

    private int managedQuietly(String token, String body) {
        try {
            return managed(token, body).statusCode();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body == null || body.isEmpty()) builder.POST(HttpRequest.BodyPublishers.noBody());
        else builder.POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void insertModel(String id, String baseUrl, int multiplierBasisPoints, int contextTokens,
                             int maxOutputTokens, boolean enabled, int priceVersion) {
        Timestamp now = Timestamp.from(Instant.now());
        db.update("""
                insert into platform_models(id,display_name,protocol,base_url,vendor_model,encrypted_vendor_key,
                  multiplier_basis_points,capabilities_json,context_tokens,max_output_tokens,enabled,price_version,
                  revision,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)
                """, id, "模型 " + id, "openai-chat-completions", baseUrl, "vendor-" + id,
                secrets.encryptPlatformSecret("model/" + id, "vendor-key-" + id), multiplierBasisPoints,
                "[]", contextTokens, maxOutputTokens, enabled, priceVersion, now, now);
    }

    private long wallet(String spaceId) {
        return db.queryForObject("select balance_units from credit_wallets where space_id=?", Long.class, spaceId);
    }

    private long reserved(String spaceId) {
        return db.queryForObject("select reserved_units from credit_wallets where space_id=?", Long.class, spaceId);
    }

    private Map<String, Object> row(String requestId) {
        return db.queryForMap("select * from managed_requests where request_id=?", requestId);
    }

    /** Polls until the billing row reaches the expected status (or 15s pass) so async settlement is observed. */
    private Map<String, Object> rowEventually(String requestId, String expectedStatus) throws InterruptedException {
        Map<String, Object> row = null;
        for (int i = 0; i < 150; i++) {
            try {
                row = db.queryForMap("select * from managed_requests where request_id=?", requestId);
                if (expectedStatus.equals(row.get("status"))) return row;
            } catch (Exception notYet) {
                row = null;
            }
            sleep(100);
        }
        return row;
    }

    private Map<String, Object> ledger(String requestId) {
        return db.queryForMap("select * from credit_ledger where reference=?", "managed:" + requestId);
    }

    private int count(String sql, Object... args) {
        Integer value = db.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static boolean rowContains(Map<String, Object> row, String needle) {
        for (Object value : row.values()) {
            if (value instanceof String text && text.contains(needle)) return true;
        }
        return false;
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private static String upstreamBase() {
        return "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1";
    }

    private static String completion(String id, int promptTokens, int completionTokens) {
        return "{\"id\":\"" + id + "\",\"object\":\"chat.completion\",\"model\":\"vendor-model\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":" + promptTokens
                + ",\"completion_tokens\":" + completionTokens + ",\"total_tokens\":"
                + (promptTokens + completionTokens) + "}}";
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            write(out, "data: {\"id\":\"chatcmpl-stream\",\"choices\":[{\"delta\":{\"content\":\"he\"}}]}\n\n");
            write(out, "data: {\"id\":\"chatcmpl-stream\",\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}\n\n");
            write(out, "data: {\"id\":\"chatcmpl-stream\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,"
                    + "\"completion_tokens\":50,\"total_tokens\":150}}\n\n");
            write(out, "data: [DONE]\n\n");
        }
    }

    private static void sendStreamWithoutUsage(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            write(out, "data: {\"id\":\"chatcmpl-stream-unknown\",\"choices\":[{\"delta\":{\"content\":\"he\"}}]}\n\n");
            write(out, "data: {\"id\":\"chatcmpl-stream-unknown\",\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}\n\n");
            write(out, "data: [DONE]\n\n");
        }
    }

    /** Streams chunks with pauses so a client abort lands while the gateway is still relaying. */
    private static void sendSlowStream(HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            write(out, "data: {\"id\":\"chatcmpl-abort\",\"choices\":[{\"delta\":{\"content\":\"he\"}}]}\n\n");
            sleep(800);
            write(out, "data: {\"id\":\"chatcmpl-abort\",\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}\n\n");
            sleep(800);
            write(out, "data: {\"id\":\"chatcmpl-abort\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,"
                    + "\"completion_tokens\":50,\"total_tokens\":150}}\n\n");
            write(out, "data: [DONE]\n\n");
            out.close();
        } catch (IOException ignored) {
            // The gateway or its client went away mid-stream; there is nothing left to deliver.
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.techflag.workdsh.admin.managed;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.techflag.workdsh.admin.AccountAuthService;
import com.techflag.workdsh.admin.CreditLedger;
import com.techflag.workdsh.admin.catalog.PlatformCatalog;

/**
 * Managed OpenAI Chat Completions gateway. It authenticates a personal-space session, reserves
 * credits for the worst case before calling the published model, then settles from actual token
 * usage. Billing rows never contain chat bodies.
 *
 * <p>Lifecycle: RESERVED -> SETTLED (usage received), RELEASED (nothing was executed, platform
 * bears it) or REVIEW (execution started but usage is unknown; the reservation is kept for an
 * operator). A request id is single-use: a duplicate is always a conflict and is never forwarded
 * again. There is no automatic paid retry on this route.</p>
 *
 * <p>The client must confirm the current price_version from GET /api/account/models on every call;
 * a missing or stale version is rejected before any reservation or forwarding, so credits are
 * never charged at an unconfirmed price.</p>
 */
@Service
public class ManagedGateway {
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    private static final long UNIT_DIVISOR = 10_000L; // basis points: tokens * bp / 10000 = units

    private final JdbcTemplate db;
    private final AccountAuthService auth;
    private final PlatformCatalog catalog;
    private final CreditLedger ledger;
    private final ObjectMapper json;
    private final HttpClient client;
    private final Duration upstreamTimeout;

    public ManagedGateway(JdbcTemplate db, AccountAuthService auth, PlatformCatalog catalog, CreditLedger ledger,
                          ObjectMapper json,
                          @Value("${workdsh.managed.upstream-timeout-seconds:120}") long upstreamTimeoutSeconds) {
        this.db = db;
        this.auth = auth;
        this.catalog = catalog;
        this.ledger = ledger;
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.upstreamTimeout = Duration.ofSeconds(Math.max(1, upstreamTimeoutSeconds));
    }

    public record Usage(long inputTokens, long outputTokens) {
        long totalTokens() { return inputTokens + outputTokens; }
    }

    public ResponseEntity<StreamingResponseBody> completions(String bearer, byte[] body) {
        AccountAuthService.Space space = auth.requirePersonalSpace(bearer, null);
        if (body == null || body.length == 0) throw bad("Request body is required");
        if (body.length > MAX_BODY_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "Request body is too large");

        JsonNode input;
        try {
            input = json.readTree(body);
        } catch (Exception e) {
            throw bad("Invalid JSON request");
        }
        if (input == null || !input.isObject()) throw bad("JSON object request body is required");
        JsonNode modelNode = input.get("model");
        if (modelNode == null || !modelNode.isTextual() || modelNode.asText().isBlank()) {
            throw bad("Model ID required");
        }
        Integer priceVersion = priceVersion(input.get("price_version"));
        String requestId = requestId(input.get("request_id"));
        boolean stream = input.path("stream").asBoolean(false);

        PlatformCatalog.Resolved model;
        try {
            model = catalog.resolve(modelNode.asText(), priceVersion);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model catalog is unavailable");
        }
        if (!PlatformCatalog.PROTOCOL_OPENAI_CHAT.equals(model.protocol())) {
            throw bad("Only " + PlatformCatalog.PROTOCOL_OPENAI_CHAT + " models are supported");
        }

        if (requestExists(requestId)) throw conflict("Request already submitted");
        long reservedUnits = reservationUnits(model);
        if (!ledger.reserve(space.id(), reservedUnits)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Insufficient credits");
        }
        try {
            insertRequest(requestId, space, model, reservedUnits);
        } catch (DuplicateKeyException e) {
            ledger.release(space.id(), reservedUnits);
            throw conflict("Request already submitted");
        }

        ObjectNode upstream = ((ObjectNode) input).deepCopy();
        upstream.remove("price_version");
        upstream.remove("request_id");
        upstream.put("model", model.vendorModel());
        if (stream) {
            JsonNode existing = upstream.get("stream_options");
            ObjectNode options = existing instanceof ObjectNode ? (ObjectNode) existing : upstream.putObject("stream_options");
            options.put("include_usage", true);
        }
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(upstream);
        } catch (Exception e) {
            release(requestId, space.id(), reservedUnits);
            throw bad("Invalid JSON request");
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint(model.baseUrl()))
                    .timeout(upstreamTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", stream ? "text/event-stream" : "application/json")
                    .header("Authorization", "Bearer " + model.vendorKey())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
        } catch (IllegalArgumentException e) {
            release(requestId, space.id(), reservedUnits);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model provider URL is invalid");
        }
        return stream ? forwardStreaming(request, requestId, space.id(), reservedUnits, model)
                : forwardOnce(request, requestId, space.id(), reservedUnits, model);
    }

    /** Non-stream request: one upstream call, usage from the response body, settle exactly once. */
    private ResponseEntity<StreamingResponseBody> forwardOnce(HttpRequest request, String requestId, String spaceId,
                                                              long reservedUnits, PlatformCatalog.Resolved model) {
        HttpResponse<byte[]> reply;
        try {
            reply = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            release(requestId, spaceId, reservedUnits);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Model provider timed out");
        } catch (IOException e) {
            release(requestId, spaceId, reservedUnits);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model provider connection failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            release(requestId, spaceId, reservedUnits);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model provider connection interrupted");
        }
        if (reply.statusCode() < 200 || reply.statusCode() >= 300) {
            release(requestId, spaceId, reservedUnits);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model provider rejected request");
        }
        byte[] responseBody = reply.body();
        Usage usage = null;
        String upstreamId = reply.headers().firstValue("x-request-id").orElse(null);
        try {
            JsonNode node = json.readTree(responseBody);
            usage = usage(node);
            if (upstreamId == null && node != null && node.hasNonNull("id")) upstreamId = node.get("id").asText();
        } catch (Exception ignored) {
            // Unknown usage is handled below as REVIEW, never as a guessed deduction.
        }
        if (usage == null) {
            review(requestId, upstreamId);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model provider did not return usage");
        }
        settle(requestId, spaceId, reservedUnits, model, usage, upstreamId);
        String contentType = reply.headers().firstValue("Content-Type").orElse("application/json");
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Content-Type", contentType)
                .body(out -> out.write(responseBody));
    }

    /** Streaming request: pass SSE through unchanged and settle after the final usage chunk. */
    private ResponseEntity<StreamingResponseBody> forwardStreaming(HttpRequest request, String requestId, String spaceId,
                                                                   long reservedUnits, PlatformCatalog.Resolved model) {
        HttpResponse<InputStream> reply;
        try {
            reply = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            release(requestId, spaceId, reservedUnits);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Model provider timed out");
        } catch (IOException e) {
            release(requestId, spaceId, reservedUnits);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model provider connection failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            release(requestId, spaceId, reservedUnits);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model provider connection interrupted");
        }
        if (reply.statusCode() < 200 || reply.statusCode() >= 300) {
            close(reply.body());
            release(requestId, spaceId, reservedUnits);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model provider rejected request");
        }
        String headerId = reply.headers().firstValue("x-request-id").orElse(null);
        StreamingResponseBody stream = out -> {
            Usage usage = null;
            String upstreamId = headerId;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(reply.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                    out.flush();
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (!data.isEmpty() && !"[DONE]".equals(data)) {
                            try {
                                JsonNode chunk = json.readTree(data);
                                Usage found = usage(chunk);
                                if (found != null) usage = found;
                                if (upstreamId == null && chunk != null && chunk.hasNonNull("id")) {
                                    upstreamId = chunk.get("id").asText();
                                }
                            } catch (Exception ignored) {
                                // Non-JSON SSE comments/keepalives are passed through untouched.
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // Client disconnected or upstream stream broke: execution may have happened and the
                // final usage is unknown. Keep the reservation for an operator to review.
                review(requestId, upstreamId);
                throw e;
            }
            if (usage == null) {
                review(requestId, upstreamId);
                return;
            }
            try {
                settle(requestId, spaceId, reservedUnits, model, usage, upstreamId);
            } catch (Exception e) {
                review(requestId, upstreamId);
            }
        };
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Content-Type", "text/event-stream")
                .body(stream);
    }

    private long reservationUnits(PlatformCatalog.Resolved model) {
        long maxTokens = (long) model.contextTokens() + model.maxOutputTokens();
        return Math.max(1, ceilUnits(maxTokens * model.multiplierBasisPoints()));
    }

    private static long chargeUnits(long tokens, int multiplierBasisPoints) {
        return ceilUnits(tokens * multiplierBasisPoints);
    }

    private static long ceilUnits(long numerator) {
        return (numerator + UNIT_DIVISOR - 1) / UNIT_DIVISOR;
    }

    private static Usage usage(JsonNode node) {
        if (node == null) return null;
        JsonNode usage = node.get("usage");
        if (usage == null || !usage.isObject()) return null;
        Long input = number(usage.get("prompt_tokens"));
        if (input == null) input = number(usage.get("input_tokens"));
        Long output = number(usage.get("completion_tokens"));
        if (output == null) output = number(usage.get("output_tokens"));
        if (input == null || output == null || input < 0 || output < 0) return null;
        return new Usage(input, output);
    }

    private static Long number(JsonNode node) {
        return node != null && node.isNumber() ? node.asLong() : null;
    }

    private static Integer priceVersion(JsonNode node) {
        if (node == null || node.isNull()) {
            throw bad("price_version is required: fetch GET /api/account/models and resend with the confirmed price version");
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() <= 0) {
            throw bad("Price version must be a positive integer");
        }
        return node.asInt();
    }

    private static String requestId(JsonNode node) {
        if (node == null || node.isNull()) return UUID.randomUUID().toString();
        if (!node.isTextual()) throw bad("Request id must be a string");
        String id = node.asText().trim();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")) {
            throw bad("Request id must be 1-120 letters, digits, '.', '_', ':' or '-'");
        }
        return id;
    }

    private boolean requestExists(String requestId) {
        Long count = db.queryForObject("select count(*) from managed_requests where request_id=?", Long.class, requestId);
        return count != null && count > 0;
    }

    private void insertRequest(String requestId, AccountAuthService.Space space, PlatformCatalog.Resolved model,
                               long reservedUnits) {
        Timestamp now = Timestamp.from(Instant.now());
        db.update("""
                insert into managed_requests(id,request_id,account_id,space_id,model_id,price_version,
                  multiplier_basis_points,status,reserved_units,charged_units,input_tokens,output_tokens,
                  upstream_request_id,created_at,updated_at,settled_at)
                values (?,?,?,?,?,?,?,'RESERVED',?,0,null,null,null,?,?,null)
                """, UUID.randomUUID().toString(), requestId, space.accountId(), space.id(), model.id(),
                model.priceVersion(), model.multiplierBasisPoints(), reservedUnits, now, now);
    }

    private void settle(String requestId, String spaceId, long reservedUnits, PlatformCatalog.Resolved model,
                        Usage usage, String upstreamId) {
        long chargedUnits = chargeUnits(usage.totalTokens(), model.multiplierBasisPoints());
        ledger.settle(spaceId, reservedUnits, chargedUnits, "managed:" + requestId);
        Timestamp now = Timestamp.from(Instant.now());
        db.update("""
                update managed_requests set status='SETTLED',charged_units=?,input_tokens=?,output_tokens=?,
                  upstream_request_id=?,settled_at=?,updated_at=? where request_id=? and status='RESERVED'
                """, chargedUnits, usage.inputTokens(), usage.outputTokens(), upstreamId, now, now, requestId);
    }

    /** Nothing was executed: return the whole reservation and record why. The platform bears it. */
    private void release(String requestId, String spaceId, long reservedUnits) {
        try {
            ledger.release(spaceId, reservedUnits);
        } finally {
            Timestamp now = Timestamp.from(Instant.now());
            db.update("""
                    update managed_requests set status='RELEASED',settled_at=?,updated_at=?
                    where request_id=? and status='RESERVED'
                    """, now, now, requestId);
        }
    }

    /** Usage unknown after execution started: keep the reservation and flag the row for review. */
    private void review(String requestId, String upstreamId) {
        try {
            db.update("""
                    update managed_requests set status='REVIEW',upstream_request_id=?,updated_at=?
                    where request_id=? and status='RESERVED'
                    """, upstreamId, Timestamp.from(Instant.now()), requestId);
        } catch (Exception ignored) {
            // Never lose the reservation because the status update failed; the row stays RESERVED.
        }
    }

    static URI endpoint(String baseUrl) {
        URI base = URI.create(baseUrl);
        String path = base.getPath() == null ? "" : base.getPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (!path.endsWith("/v1")) path += "/v1";
        path += "/chat/completions";
        try {
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(), path, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid model base URL", e);
        }
    }

    private static void close(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

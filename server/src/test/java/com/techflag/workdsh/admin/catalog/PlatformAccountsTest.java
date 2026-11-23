package com.techflag.workdsh.admin.catalog;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.techflag.workdsh.admin.AccountSpaces;
import com.techflag.workdsh.admin.AuthService;
import com.techflag.workdsh.admin.CreditLedger;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:platform-accounts-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workdsh.bootstrap.admin-email=owner@dev.example",
        "workdsh.bootstrap.admin-password=OwnerPassword123!",
        "workdsh.accounts.expose-verification-code=true",
        "workdsh.platform.bootstrap-admin-email=platform@dev.example",
        "workdsh.platform.bootstrap-admin-password=PlatformAdminPassword123!",
        "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc
class PlatformAccountsTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired AccountSpaces spaces;
    @Autowired CreditLedger ledger;

    @Test
    void accountsListCarriesSpacesWalletTrialAndTotal() throws Exception {
        register("alpha@pagetest.test", "AlphaPassword123!");
        register("beta@pagetest.test", "BetaPassword123!");

        MvcResult result = mvc.perform(get("/api/admin/platform/accounts")
                        .header("Authorization", bearer(adminToken()))
                        .param("query", "alpha@pagetest.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.accounts.length()").value(1))
                .andExpect(jsonPath("$.accounts[0].email").value("alpha@pagetest.test"))
                .andExpect(jsonPath("$.accounts[0].accountId").isNotEmpty())
                .andExpect(jsonPath("$.accounts[0].platformRole").value("NONE"))
                .andExpect(jsonPath("$.accounts[0].emailVerified").value(true))
                .andExpect(jsonPath("$.accounts[0].active").value(true))
                .andExpect(jsonPath("$.accounts[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.accounts[0].spaces.length()").value(1))
                .andExpect(jsonPath("$.accounts[0].spaces[0].kind").value("PERSONAL"))
                .andExpect(jsonPath("$.accounts[0].spaces[0].active").value(true))
                .andExpect(jsonPath("$.accounts[0].wallet.spaceId").isNotEmpty())
                .andExpect(jsonPath("$.accounts[0].wallet.balanceUnits").value(500000))
                .andExpect(jsonPath("$.accounts[0].wallet.reservedUnits").value(0))
                .andExpect(jsonPath("$.accounts[0].wallet.availableUnits").value(500000))
                .andExpect(jsonPath("$.accounts[0].wallet.balanceCredits").value("500.000"))
                .andExpect(jsonPath("$.accounts[0].wallet.availableCredits").value("500.000"))
                .andExpect(jsonPath("$.accounts[0].wallet.unit").value("credit"))
                .andExpect(jsonPath("$.accounts[0].wallet.scale").value(1000))
                .andExpect(jsonPath("$.accounts[0].trial.grantedUnits").value(500000))
                .andExpect(jsonPath("$.accounts[0].trial.grantedCredits").value("500.000"))
                .andExpect(jsonPath("$.accounts[0].trial.grantedAt").isNotEmpty())
                .andReturn();
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(content.contains("token"));
        assertFalse(content.contains("password_hash"));

        // Enterprise account: personal + enterprise space with organization resolution. Space order
        // is not guaranteed (both rows may share a created_at), so assert via kind filters.
        String orgId = db.queryForObject("select id from organizations limit 1", String.class);
        String orgName = db.queryForObject("select name from organizations where id=?", String.class, orgId);
        String memberId = UUID.randomUUID().toString();
        String email = "enterprise@pagetest.test";
        String password = "EnterpriseUser123!";
        db.update("""
                insert into members(id,organization_id,email,display_name,role,password_hash,
                  must_change_password,active,revision) values (?,?,?,?,?,?,false,true,1)
                """, memberId, orgId, email, "企业管理员", "ADMIN", auth.encode(password));
        spaces.linkMember(memberId);

        mvc.perform(get("/api/admin/platform/accounts")
                        .header("Authorization", bearer(adminToken()))
                        .param("query", "enterprise@pagetest.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.accounts[0].spaces.length()").value(2))
                .andExpect(jsonPath("$.accounts[0].spaces[?(@.kind=='PERSONAL')].kind").value(hasItem("PERSONAL")))
                .andExpect(jsonPath("$.accounts[0].spaces[?(@.kind=='ENTERPRISE')].memberId").value(hasItem(memberId)))
                .andExpect(jsonPath("$.accounts[0].spaces[?(@.kind=='ENTERPRISE')].role").value(hasItem("ADMIN")))
                .andExpect(jsonPath("$.accounts[0].spaces[?(@.kind=='ENTERPRISE')].active").value(hasItem(true)))
                .andExpect(jsonPath("$.accounts[0].spaces[?(@.kind=='ENTERPRISE')].organizationId").value(hasItem(orgId)))
                .andExpect(jsonPath("$.accounts[0].spaces[?(@.kind=='ENTERPRISE')].organizationName").value(hasItem(orgName)));

        // Filtering matches emails/display names, and pagination slices with an exact total.
        mvc.perform(get("/api/admin/platform/accounts")
                        .header("Authorization", bearer(adminToken()))
                        .param("query", "pagetest.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.accounts.length()").value(3));
        mvc.perform(get("/api/admin/platform/accounts")
                        .header("Authorization", bearer(adminToken()))
                        .param("query", "pagetest.test").param("limit", "1").param("offset", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.accounts.length()").value(1));
        mvc.perform(get("/api/admin/platform/accounts")
                        .header("Authorization", bearer(adminToken()))
                        .param("query", "pagetest.test").param("limit", "1").param("offset", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.accounts").isEmpty());
        mvc.perform(get("/api/admin/platform/accounts")
                        .header("Authorization", bearer(adminToken()))
                        .param("query", "nobody-matches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.accounts").isEmpty());
    }

    @Test
    void ledgerJoinsManagedRequestUsageAndFiltersByAccount() throws Exception {
        String email = "usage@accounts.test";
        register(email, "UsagePassword123!");
        String accountId = db.queryForObject("select id from accounts where email=?", String.class, email);
        String spaceId = db.queryForObject(
                "select id from account_spaces where account_id=? and kind='PERSONAL'", String.class, accountId);
        String requestId = "req-" + UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        db.update("""
                insert into managed_requests(id,request_id,account_id,space_id,model_id,price_version,
                  multiplier_basis_points,status,reserved_units,charged_units,input_tokens,output_tokens,created_at,updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), requestId, accountId, spaceId, "model-x", 2, 15000,
                "SETTLED", 3000, 1500, 120L, 45L, now, now);
        ledger.settle(spaceId, 3000, 1500, "managed:" + requestId);

        MvcResult result = mvc.perform(get("/api/admin/platform/ledger")
                        .header("Authorization", bearer(adminToken()))
                        .param("accountId", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andReturn();
        JsonNode usage = entry(result, "USAGE");
        assertNotNull(usage, "USAGE entry missing");
        assertEquals("managed:" + requestId, usage.path("reference").asText());
        assertEquals(-1500, usage.path("deltaUnits").asLong());
        assertEquals("-1.500", usage.path("deltaCredits").asText());
        assertEquals(498500, usage.path("balanceAfterUnits").asLong());
        assertTrue(usage.path("actorAccountId").isNull());
        assertEquals(requestId, usage.path("request").path("requestId").asText());
        assertEquals("model-x", usage.path("request").path("modelId").asText());
        assertEquals("SETTLED", usage.path("request").path("status").asText());
        assertEquals(2, usage.path("request").path("priceVersion").asInt());
        assertEquals(15000, usage.path("request").path("multiplierBasisPoints").asInt());
        assertEquals(120, usage.path("request").path("inputTokens").asLong());
        assertEquals(45, usage.path("request").path("outputTokens").asLong());
        JsonNode trial = entry(result, "TRIAL");
        assertNotNull(trial, "TRIAL entry missing");
        assertEquals(500000, trial.path("deltaUnits").asLong());
        assertEquals("500.000", trial.path("deltaCredits").asText());
        assertTrue(trial.path("request").isNull(), "TRIAL row must not resolve a managed request");

        // Empty result: an unknown account id has no ledger rows, and pagination keeps the total.
        mvc.perform(get("/api/admin/platform/ledger")
                        .header("Authorization", bearer(adminToken()))
                        .param("accountId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.entries").isEmpty());
        mvc.perform(get("/api/admin/platform/ledger")
                        .header("Authorization", bearer(adminToken()))
                        .param("accountId", accountId).param("limit", "1").param("offset", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.entries.length()").value(1));
    }

    @Test
    void accountAndLedgerReadsArePlatformAdminOnly() throws Exception {
        String owner = login("owner@dev.example", "OwnerPassword123!");
        String plain = register("plain@accounts.test", "PlainPassword123!");
        for (String token : new String[] {owner, plain}) {
            mvc.perform(get("/api/admin/platform/accounts").header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/platform/ledger").header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/platform/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/platform/ledger")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/platform/accounts").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/platform/ledger").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());
    }

    @Test
    void creditIssueRecordsOptionalReasonInAuditDetail() throws Exception {
        String admin = adminToken();
        String email = "reason@accounts.test";
        register(email, "ReasonPassword123!");
        String accountId = db.queryForObject("select id from accounts where email=?", String.class, email);

        mvc.perform(post("/api/admin/platform/credits")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "amountUnits", 100000,
                                "idempotencyKey", "reason-audit-1", "reason", "pilot top-up"))))
                .andExpect(status().isCreated());
        assertEquals("reason=pilot top-up,reference=reason-audit-1,units=100000", auditDetail(accountId, "reason-audit-1"));

        mvc.perform(post("/api/admin/platform/credits")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "amountUnits", 100000,
                                "idempotencyKey", "reason-audit-2", "reason", "   "))))
                .andExpect(status().isCreated());
        assertEquals("reference=reason-audit-2,units=100000", auditDetail(accountId, "reason-audit-2"));

        mvc.perform(post("/api/admin/platform/credits")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "amountUnits", 100000,
                                "idempotencyKey", "reason-audit-3", "reason", "x".repeat(201)))))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/admin/platform/credits")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "amountUnits", 100000,
                                "idempotencyKey", "reason-audit-1", "reason", "pilot top-up"))))
                .andExpect(status().isCreated());
        assertEquals(1, db.queryForObject("select count(*) from credit_ledger where reference='reason-audit-1'",
                Integer.class));
        assertEquals(1, db.queryForObject(
                "select count(*) from platform_audit_events where target_id=? and detail like '%reference=reason-audit-1%'",
                Integer.class, accountId));
    }

    private String auditDetail(String accountId, String reference) {
        return db.queryForObject(
                "select detail from platform_audit_events where target_id=? and detail like ?",
                String.class, accountId, "%reference=" + reference + "%");
    }

    private JsonNode entry(MvcResult result, String reason) throws Exception {
        for (JsonNode entry : json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("entries")) {
            if (reason.equals(entry.path("reason").asText())) return entry;
        }
        return null;
    }

    private String adminToken() throws Exception {
        return login("platform@dev.example", "PlatformAdminPassword123!");
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private String register(String email, String password) throws Exception {
        MvcResult codeResult = mvc.perform(post("/api/account/request-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted()).andReturn();
        String code = json.readTree(codeResult.getResponse().getContentAsString()).path("code").asText();
        MvcResult result = mvc.perform(post("/api/account/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "code", code,
                                "password", password, "displayName", "账户测试"))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private static String bearer(String token) { return "Bearer " + token; }
}

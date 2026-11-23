package com.techflag.workdsh.admin.catalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

import com.techflag.workdsh.admin.AccountSpaces;
import com.techflag.workdsh.admin.AuthService;
import com.techflag.workdsh.admin.MemberSecrets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:catalog-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workdsh.bootstrap.admin-email=owner@catalog.test",
        "workdsh.bootstrap.admin-password=OwnerPassword123!",
        "workdsh.accounts.expose-verification-code=true",
        "workdsh.platform.bootstrap-admin-email=platform@catalog.test",
        "workdsh.platform.bootstrap-admin-password=PlatformAdminPassword123!",
        "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc
class PlatformCatalogTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired AccountSpaces spaces;
    @Autowired MemberSecrets secrets;
    @Autowired PlatformCatalog catalog;

    @BeforeEach
    void clearCatalog() {
        db.update("delete from platform_models");
    }

    @Test
    void catalogCrudEncryptsVendorKeysAndBumpsPriceVersion() throws Exception {
        String admin = adminToken();

        mvc.perform(post("/api/admin/platform/models")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(create("model-a", "模型 A", "sk-secret-a", 15000))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("model-a"))
                .andExpect(jsonPath("$.multiplier").value("1.5"))
                .andExpect(jsonPath("$.priceVersion").value(1))
                .andExpect(jsonPath("$.vendorKey").doesNotExist());

        String ciphertext = db.queryForObject(
                "select encrypted_vendor_key from platform_models where id=?", String.class, "model-a");
        assertNotEquals("sk-secret-a", ciphertext);
        assertTrue(ciphertext.startsWith("v1:"));
        assertEquals("sk-secret-a", secrets.decryptPlatformSecret("model/model-a", ciphertext));

        mvc.perform(get("/api/admin/platform/models").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].vendorKey").doesNotExist());

        String personal = register("buyer@catalog.test", "BuyerPassword123!");
        mvc.perform(get("/api/account/models").header("Authorization", bearer(personal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("model-a"))
                .andExpect(jsonPath("$[0].displayName").value("模型 A · ×1.5"))
                .andExpect(jsonPath("$[0].multiplier").value("1.5"))
                .andExpect(jsonPath("$[0].multiplierBasisPoints").value(15000))
                .andExpect(jsonPath("$[0].contextTokens").value(128000))
                .andExpect(jsonPath("$[0].maxOutputTokens").value(8192))
                .andExpect(jsonPath("$[0].priceVersion").value(1))
                .andExpect(jsonPath("$[0].capabilities.chat").value(true));

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("multiplierBasisPoints", 20000);
        update.put("capabilities", Map.of("chat", true, "vision", true));
        update.put("expectedPriceVersion", 1);
        mvc.perform(put("/api/admin/platform/models/model-a")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.multiplier").value("2"))
                .andExpect(jsonPath("$.priceVersion").value(2));

        mvc.perform(get("/api/account/models").header("Authorization", bearer(personal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("模型 A · ×2"))
                .andExpect(jsonPath("$[0].priceVersion").value(2))
                .andExpect(jsonPath("$[0].capabilities.vision").value(true));

        Map<String, Object> stale = Map.of("multiplierBasisPoints", 30000, "expectedPriceVersion", 1);
        mvc.perform(put("/api/admin/platform/models/model-a")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(stale)))
                .andExpect(status().isConflict());
        ResponseStatusException staleResolve = assertThrows(ResponseStatusException.class,
                () -> catalog.resolve("model-a", 1));
        assertNotNull(staleResolve.getReason());
        assertTrue(staleResolve.getReason().contains("refresh"));

        mvc.perform(post("/api/admin/platform/models/model-a/vendor-key")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("vendorKey", "sk-secret-b"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceVersion").value(2));
        String rotated = db.queryForObject(
                "select encrypted_vendor_key from platform_models where id=?", String.class, "model-a");
        assertNotEquals(ciphertext, rotated);
        assertEquals("sk-secret-b", catalog.resolve("model-a", 2).vendorKey());

        mvc.perform(post("/api/admin/platform/models/model-a/enabled")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/account/models").header("Authorization", bearer(personal)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/api/admin/platform/models/model-a/enabled")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/account/models").header("Authorization", bearer(personal)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void emptyCatalogAndWalletAreExplicit() throws Exception {
        String personal = register("empty@catalog.test", "EmptyCatalog123!");

        mvc.perform(get("/api/account/models").header("Authorization", bearer(personal)))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mvc.perform(get("/api/account/wallet").header("Authorization", bearer(personal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceUnits").value(500000))
                .andExpect(jsonPath("$.availableUnits").value(500000))
                .andExpect(jsonPath("$.balanceCredits").value("500.000"))
                .andExpect(jsonPath("$.availableCredits").value("500.000"))
                .andExpect(jsonPath("$.unit").value("credit"))
                .andExpect(jsonPath("$.scale").value(1000));
    }

    @Test
    void platformAdminIssuesCreditsIdempotently() throws Exception {
        String admin = adminToken();
        String email = "credited@catalog.test";
        register(email, "CreditedUser123!");
        String platformAccountId = db.queryForObject("select id from accounts where email=?",
                String.class, "platform@catalog.test");

        Map<String, Object> issue = Map.of("email", email, "amountUnits", 250000,
                "idempotencyKey", "issue-credited-1");
        mvc.perform(post("/api/admin/platform/credits")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(issue)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountUnits").value(250000))
                .andExpect(jsonPath("$.availableUnits").value(750000))
                .andExpect(jsonPath("$.availableCredits").value("750.000"))
                .andExpect(jsonPath("$.unit").value("credit"));

        mvc.perform(post("/api/admin/platform/credits")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(issue)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.availableUnits").value(750000));

        assertEquals(1, db.queryForObject("select count(*) from credit_ledger where reference=?",
                Integer.class, "issue-credited-1"));
        assertEquals("ISSUE", db.queryForObject("select reason from credit_ledger where reference=?",
                String.class, "issue-credited-1"));
        assertEquals(platformAccountId, db.queryForObject(
                "select actor_account_id from credit_ledger where reference=?", String.class, "issue-credited-1"));

        mvc.perform(post("/api/admin/platform/credits")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "amountUnits", 1,
                                "idempotencyKey", "issue-credited-1"))))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/account/wallet").header("Authorization", bearer(
                        login(email, "CreditedUser123!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableUnits").value(750000));
    }

    @Test
    void enterpriseSpacesDoNotSeeCatalogOrWallet() throws Exception {
        String admin = adminToken();
        mvc.perform(post("/api/admin/platform/models")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(create("model-b", "模型 B", "sk-b", 10000))))
                .andExpect(status().isCreated());

        String orgId = db.queryForObject("select id from organizations limit 1", String.class);
        String memberId = UUID.randomUUID().toString();
        String email = "enterprise@catalog.test";
        String password = "EnterpriseUser123!";
        db.update("""
                insert into members(id,organization_id,email,display_name,role,password_hash,
                  must_change_password,active,revision) values (?,?,?,?,?,?,false,true,1)
                """, memberId, orgId, email, "企业管理员", "ADMIN", auth.encode(password));
        spaces.linkMember(memberId);
        String accountToken = login(email, password);
        String enterpriseSpaceId = db.queryForObject(
                "select id from account_spaces where member_id=? and kind='ENTERPRISE'", String.class, memberId);

        MvcResult switched = mvc.perform(post("/api/account/spaces/" + enterpriseSpaceId + "/token")
                        .header("Authorization", bearer(accountToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.space.kind").value("ENTERPRISE"))
                .andReturn();
        String enterpriseToken = json.readTree(switched.getResponse().getContentAsString()).path("token").asText();

        mvc.perform(get("/api/account/models").header("Authorization", bearer(enterpriseToken)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/account/wallet").header("Authorization", bearer(enterpriseToken)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/platform/models").header("Authorization", bearer(enterpriseToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyPlatformAdminManagesCatalogAndCredits() throws Exception {
        String owner = login("owner@catalog.test", "OwnerPassword123!");
        String personal = register("plain@catalog.test", "PlainAccount123!");

        mvc.perform(get("/api/admin/platform/models").header("Authorization", bearer(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/platform/models").header("Authorization", bearer(personal)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/platform/models")
                        .header("Authorization", bearer(personal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(create("model-c", "模型 C", "sk-c", 10000))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/platform/credits")
                        .header("Authorization", bearer(personal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "plain@catalog.test",
                                "amountUnits", 1, "idempotencyKey", "plain-1"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/platform/models").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());
    }

    private Map<String, Object> create(String id, String displayName, String vendorKey, int multiplier) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("displayName", displayName);
        body.put("protocol", "openai-chat-completions");
        body.put("baseUrl", "https://api.example.com/v1");
        body.put("vendorModel", "vendor-model");
        body.put("vendorKey", vendorKey);
        body.put("multiplierBasisPoints", multiplier);
        body.put("capabilities", Map.of("chat", true));
        body.put("contextTokens", 128000);
        body.put("maxOutputTokens", 8192);
        body.put("enabled", true);
        return body;
    }

    private String adminToken() throws Exception {
        return login("platform@catalog.test", "PlatformAdminPassword123!");
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
                                "password", password, "displayName", "目录测试"))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private static String bearer(String token) { return "Bearer " + token; }
}

package com.techflag.workdsh.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:account-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workdsh.bootstrap.admin-email=owner@account-flow.test",
        "workdsh.bootstrap.admin-password=OwnerPassword123!",
        "workdsh.accounts.expose-verification-code=true",
        "workdsh.platform.bootstrap-admin-email=platform@account-flow.test",
        "workdsh.platform.bootstrap-admin-password=PlatformAdminPassword123!"})
@AutoConfigureMockMvc
class AccountFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired AccountSpaces spaces;
    @Autowired AccountAuthService accounts;
    @Autowired AccountMigration migration;
    @Autowired VerificationCodes codes;
    @Autowired PlatformAudit audit;
    @Autowired TransactionTemplate transactions;
    @Autowired ObjectMapper json;

    @Test
    void registrationLoginAndTrialCreditsAreIdempotent() throws Exception {
        String email = "personal@account-flow.test";
        String password = "PersonalAccount123!";
        String code = code(email);
        String token = token(mvc.perform(register(email, code, password))
                .andExpect(status().isOk()).andReturn());
        String bearer = "Bearer " + token;
        String storedHash = db.queryForObject(
                "select code_hash from verification_codes where email=? order by created_at desc limit 1",
                String.class, email);
        assertNotEquals(code, storedHash);
        assertNotEquals(AuthService.sha256(email + ":REGISTER:" + code), storedHash);

        mvc.perform(get("/api/account/me").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email").value(email))
                .andExpect(jsonPath("$.account.platformRole").value("NONE"))
                .andExpect(jsonPath("$.space.kind").value("PERSONAL"))
                .andExpect(jsonPath("$.spaces.length()").value(1));
        mvc.perform(get("/api/account/spaces").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/account/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationEnabled").value(true));
        assertEquals(500_000L, wallet(email));
        assertEquals(1, db.queryForObject("select count(*) from credit_ledger where reference=?",
                Integer.class, "trial:" + accountId(email)));

        spaces.grantTrial(accountId(email), personalSpaceId(email));
        assertEquals(500_000L, wallet(email));
        assertEquals(1, db.queryForObject("select count(*) from credit_ledger where reference=?",
                Integer.class, "trial:" + accountId(email)));

        mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(post("/api/account/spaces/" + personalSpaceId(email) + "/token")
                        .header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.space.id").value(personalSpaceId(email)));
        // A personal-only account has no enterprise member identity for the old desktop endpoint.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verificationCodesAreSingleUseAndLimited() throws Exception {
        String password = "VerificationCode123!";

        String email = "attempts@account-flow.test";
        String good = code(email);
        for (int i = 0; i < 4; i++) {
            mvc.perform(register(email, "000000", password)).andExpect(status().isBadRequest());
        }
        mvc.perform(register(email, "000000", password)).andExpect(status().isTooManyRequests());
        mvc.perform(register(email, good, password)).andExpect(status().isTooManyRequests());

        String single = "single@account-flow.test";
        String once = code(single);
        mvc.perform(register(single, once, password)).andExpect(status().isOk());
        var reused = assertThrows(ResponseStatusException.class,
                () -> codes.consume(single, VerificationCodes.REGISTER, once));
        assertEquals(400, reused.getStatusCode().value());

        String expired = "expired@account-flow.test";
        db.update("insert into verification_codes(id,email,purpose,code_hash,attempts,max_attempts,created_at,expires_at) values (?,?,?,?,0,5,?,?)",
                UUID.randomUUID().toString(), expired, "REGISTER", codes.hashForTest(expired, "REGISTER", "123456"),
                Timestamp.from(Instant.now().minusSeconds(1200)), Timestamp.from(Instant.now().minusSeconds(600)));
        mvc.perform(register(expired, "123456", password)).andExpect(status().isBadRequest());

        String cooldown = "cooldown@account-flow.test";
        code(cooldown);
        mvc.perform(post("/api/account/request-code").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", cooldown))))
                .andExpect(status().isTooManyRequests());

        String hourly = "hourly@account-flow.test";
        Timestamp past = Timestamp.from(Instant.now().minusSeconds(61));
        for (int i = 0; i < 5; i++) {
            db.update("insert into verification_codes(id,email,purpose,code_hash,attempts,max_attempts,created_at,expires_at) values (?,?,?,?,0,5,?,?)",
                    UUID.randomUUID().toString(), hourly, "REGISTER", codes.hashForTest(hourly, "REGISTER", "123456"),
                    past, Timestamp.from(Instant.now().plusSeconds(600)));
        }
        mvc.perform(post("/api/account/request-code").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", hourly))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void enterpriseEmailsCannotBeRegisteredAsPersonalAccounts() throws Exception {
        String owner = "owner@account-flow.test";
        String code = code(owner);
        mvc.perform(register(owner, code, "OwnerTakeover123!"))
                .andExpect(status().isConflict());

        String email = "duplicate@account-flow.test";
        mvc.perform(register(email, code(email), "DuplicateAccount123!")).andExpect(status().isOk());
        db.update("update verification_codes set created_at=? where email=?",
                Timestamp.from(Instant.now().minusSeconds(120)), email);
        mvc.perform(register(email, code(email), "DuplicateAccount123!"))
                .andExpect(status().isConflict());
    }

    @Test
    void enterpriseMemberKeepsOldLoginAndCanSwitchSpaces() throws Exception {
        String ownerBearer = "Bearer " + auth.login("owner@account-flow.test", "OwnerPassword123!").token();
        MvcResult created = mvc.perform(post("/api/admin/members").header("Authorization", ownerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "enterprise@account-flow.test",
                                "displayName", "Enterprise Member", "role", "MEMBER"))))
                .andExpect(status().isCreated()).andReturn();
        String temporary = json.readTree(created.getResponse().getContentAsString()).path("temporaryPassword").asText();

        MvcResult login = mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "enterprise@account-flow.test",
                                "password", temporary))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.spaces.length()").value(2)).andReturn();
        var body = json.readTree(login.getResponse().getContentAsString());
        String personal = body.path("token").asText();
        String enterpriseSpace = body.path("spaces").get(1).path("id").asText();
        assertEquals("ENTERPRISE", body.path("spaces").get(1).path("kind").asText());

        // A temporary password cannot open the enterprise space until the account password is changed.
        mvc.perform(post("/api/account/spaces/" + enterpriseSpace + "/token")
                        .header("Authorization", "Bearer " + personal))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/account/change-password").header("Authorization", "Bearer " + personal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("currentPassword", temporary,
                                "newPassword", "EnterprisePassword123!"))))
                .andExpect(status().isNoContent());

        MvcResult switched = mvc.perform(post("/api/account/spaces/" + enterpriseSpace + "/token")
                        .header("Authorization", "Bearer " + personal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.space.kind").value("ENTERPRISE")).andReturn();
        String enterprise = json.readTree(switched.getResponse().getContentAsString()).path("token").asText();

        mvc.perform(get("/api/account/me").header("Authorization", "Bearer " + enterprise))
                .andExpect(status().isOk()).andExpect(jsonPath("$.space.kind").value("ENTERPRISE"))
                .andExpect(jsonPath("$.space.organizationId").isNotEmpty());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + enterprise)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "enterprise@account-flow.test",
                                "password", "EnterprisePassword123!"))))
                .andExpect(status().isOk());

        String memberId = db.queryForObject("select id from members where email=?", String.class,
                "enterprise@account-flow.test");
        int revision = db.queryForObject("select revision from members where id=?", Integer.class, memberId);
        mvc.perform(patch("/api/admin/members/" + memberId).header("Authorization", ownerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("active", false, "expectedRevision", revision))))
                .andExpect(status().isOk());
        // Revoking the enterprise membership is not a personal-account ban, and personal sessions survive it.
        MvcResult personalAfterDisable = mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "enterprise@account-flow.test",
                                "password", "EnterprisePassword123!"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.space.kind").value("PERSONAL")).andReturn();
        String personalToken = json.readTree(personalAfterDisable.getResponse().getContentAsString()).path("token").asText();
        mvc.perform(get("/api/account/me").header("Authorization", "Bearer " + personalToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.space.kind").value("PERSONAL"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "enterprise@account-flow.test",
                                "password", "EnterprisePassword123!"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/account/me").header("Authorization", "Bearer " + enterprise))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + enterprise))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void crossOrgSpacesAreDeniedAndBackfillIsIdempotent() throws Exception {
        String orgB = UUID.randomUUID().toString();
        String memberB = UUID.randomUUID().toString();
        String password = "OtherOrganization123!";
        db.update("insert into organizations(id,name) values (?,?)", orgB, "Other Organization");
        db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values (?,?,?,?,?,?,false,true,1)",
                memberB, orgB, "other@account-flow.test", "Other Owner", "OWNER", auth.encode(password));
        assertTrue(spaces.linkMember(memberB));
        String accountB = accountId("other@account-flow.test");
        String personalB = personalSpaceId("other@account-flow.test");
        String bearerB = "Bearer " + accounts.issueSession(accountB, personalB).token();

        String ownerMember = db.queryForObject("select id from members where email=?", String.class,
                "owner@account-flow.test");
        String ownerEnterpriseSpace = db.queryForObject(
                "select id from account_spaces where member_id=? and kind='ENTERPRISE'", String.class, ownerMember);
        mvc.perform(post("/api/account/spaces/" + ownerEnterpriseSpace + "/token")
                        .header("Authorization", bearerB))
                .andExpect(status().isNotFound());
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> accounts.requireSpace(bearerB, ownerEnterpriseSpace)).getStatusCode().value());
        assertNull(accounts.space(accountB, ownerEnterpriseSpace));

        mvc.perform(get("/api/account/me").header("Authorization", bearerB))
                .andExpect(status().isOk()).andExpect(jsonPath("$.spaces.length()").value(2))
                .andExpect(jsonPath("$.spaces[1].organizationId").value(orgB));

        assertEquals(0, migration.backfill());
        assertEquals(0, migration.backfill());
        assertEquals(0, db.queryForObject("""
                select count(*) from members m where m.active=true and not exists
                (select 1 from account_spaces s where s.member_id=m.id and s.kind='ENTERPRISE')
                """, Integer.class));
        assertEquals(0, db.queryForObject("""
                select count(*) from members m join accounts a on a.email=m.email
                where m.active=true and not exists
                (select 1 from account_spaces s where s.account_id=a.id and s.kind='PERSONAL')
                """, Integer.class));
        // Re-running the additive schema is safe on an already initialized database.
        try (var connection = db.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
    }

    @Test
    void backfillLinksPreexistingMembersAndSecondRunChangesNothing() {
        String orgId = db.queryForObject("select id from organizations order by name limit 1", String.class);
        String legacyId = UUID.randomUUID().toString();
        String legacyEmail = "legacy-member@account-flow.test";
        db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values (?,?,?,?,?,?,false,true,1)",
                legacyId, orgId, legacyEmail, "旧成员", "MEMBER", auth.encode("LegacyMember123!"));
        // Simulate the first start of a database that predates the platform tables.
        db.update("delete from platform_migrations where id='platform-accounts-v1'");

        assertEquals(1, migration.backfill());
        assertEquals(0, migration.backfill());

        String accountId = db.queryForObject("select id from accounts where email=?", String.class, legacyEmail);
        assertEquals(1, db.queryForObject("""
                select count(*) from account_spaces where account_id=? and kind='ENTERPRISE'
                and member_id=? and organization_id=? and role='MEMBER' and active=true
                """, Integer.class, accountId, legacyId, orgId));
        assertEquals(1, db.queryForObject(
                "select count(*) from account_spaces where account_id=? and kind='PERSONAL'",
                Integer.class, accountId));
        assertEquals(1, db.queryForObject("select count(*) from credit_ledger where reference=?",
                Integer.class, "trial:" + accountId));
        // The member identity survives the migration and old enterprise login still works.
        AuthService.Login login = auth.login(legacyEmail, "LegacyMember123!");
        assertEquals(legacyId, login.member().id());
        assertEquals(orgId, login.member().organizationId());
        assertEquals("MEMBER", login.member().role());
        assertFalse(login.member().mustChangePassword());
    }

    @Test
    void platformAdminComesOnlyFromExplicitBootstrapConfiguration() {
        assertEquals("SUPERADMIN", db.queryForObject("select platform_role from accounts where email=?",
                String.class, "platform@account-flow.test"));
        assertEquals("NONE", db.queryForObject("select platform_role from accounts where email=?",
                String.class, "owner@account-flow.test"));
        assertFalse(accounts.isPlatformAdmin(accounts.requireAccount(
                "Bearer " + spaces.login("owner@account-flow.test", "OwnerPassword123!").token())));
        var platform = accounts.requireAccount("Bearer " + spaces.login("platform@account-flow.test",
                "PlatformAdminPassword123!").token());
        assertTrue(accounts.isPlatformAdmin(platform));
        assertEquals(1, db.queryForObject("select count(*) from account_spaces where account_id=? and kind='PERSONAL'",
                Integer.class, platform.id()));
        assertThrows(ResponseStatusException.class, () -> accounts.requirePlatformAdmin(
                accounts.requireAccount("Bearer " + spaces.login("owner@account-flow.test",
                        "OwnerPassword123!").token())));
    }

    @Test
    void platformBootstrapResetsConfiguredPasswordAndRejectsPartialConfig() throws Exception {
        String email = "platform@account-flow.test";
        db.update("update accounts set password_hash=? where email=?", auth.encode("ChangedPassword123!"), email);
        assertThrows(ResponseStatusException.class,
                () -> spaces.login(email, "PlatformAdminPassword123!"));

        migration.bootstrapPlatformAdmin();

        assertEquals("SUPERADMIN", db.queryForObject("select platform_role from accounts where email=?",
                String.class, email));
        assertTrue(spaces.login(email, "PlatformAdminPassword123!").account().platformAdmin());
        assertThrows(ResponseStatusException.class, () -> spaces.login(email, "ChangedPassword123!"));
        assertThrows(IllegalStateException.class, () -> new AccountMigration(db, spaces, audit, transactions,
                "ops@account-flow.test", "").bootstrapPlatformAdmin());
    }

    @Test
    void browserLoginIsTokenFreeAndCookieWorks() throws Exception {
        String email = "browser@account-flow.test";
        String password = "BrowserAccount123!";
        mvc.perform(register(email, code(email), password)).andExpect(status().isOk());

        MvcResult result = mvc.perform(post("/api/account/browser-login").contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "http://127.0.0.1:18891")
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email").value(email))
                .andExpect(jsonPath("$.space.kind").value("PERSONAL"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(header().exists("Set-Cookie"))
                .andReturn();
        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertNotNull(cookie);
        assertTrue(cookie.contains("HttpOnly"));
        String token = cookie.split(";")[0].split("=", 2)[1];
        mvc.perform(get("/api/account/me").cookie(new jakarta.servlet.http.Cookie(
                        BrowserSessionFilter.COOKIE_NAME, token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.account.email").value(email));
    }

    @Test
    void personalAccountCanChangeItsPassword() throws Exception {
        String email = "change@account-flow.test";
        String oldPassword = "OldAccountPassword123!";
        String newPassword = "NewAccountPassword123!";
        String bearer = "Bearer " + token(mvc.perform(register(email, code(email), oldPassword))
                .andExpect(status().isOk()).andReturn());
        String otherBearer = "Bearer " + token(mvc.perform(post("/api/account/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", oldPassword))))
                .andExpect(status().isOk()).andReturn());

        mvc.perform(post("/api/account/change-password").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("currentPassword", oldPassword,
                                "newPassword", newPassword))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", oldPassword))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", newPassword))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/account/me").header("Authorization", otherBearer))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/account/me").header("Authorization", bearer))
                .andExpect(status().isOk());
    }

    @Test
    void existingPersonalAccountCanBeLinkedToAnEnterpriseMember() throws Exception {
        String email = "linked@account-flow.test";
        String password = "LinkedAccount123!";
        mvc.perform(register(email, code(email), password)).andExpect(status().isOk());
        String accountId = accountId(email);
        assertEquals(500_000L, wallet(email));

        String ownerBearer = "Bearer " + auth.login("owner@account-flow.test", "OwnerPassword123!").token();
        MvcResult created = mvc.perform(post("/api/admin/members").header("Authorization", ownerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "displayName", "Linked Member",
                                "role", "MEMBER"))))
                .andExpect(status().isCreated()).andReturn();
        String memberId = json.readTree(created.getResponse().getContentAsString()).path("member").path("id").asText();
        assertEquals(2, db.queryForObject("select count(*) from account_spaces where account_id=?",
                Integer.class, accountId));
        assertEquals(memberId, db.queryForObject("select member_id from account_spaces where account_id=? and kind='ENTERPRISE'",
                String.class, accountId));
        assertEquals(500_000L, wallet(email));
        assertEquals(1, db.queryForObject("select count(*) from credit_ledger where reference=?",
                Integer.class, "trial:" + accountId));

        mvc.perform(post("/api/account/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.spaces.length()").value(2));
    }

    @Test
    void inactiveMembersAreNotBackfilledIntoPersonalAccounts() {
        String orgId = db.queryForObject("select id from organizations order by name limit 1", String.class);
        String memberId = UUID.randomUUID().toString();
        String email = "inactive@account-flow.test";
        db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values (?,?,?,?,?,?,false,false,1)",
                memberId, orgId, email, "Inactive Member", "MEMBER", auth.encode("InactiveMember123!"));
        assertFalse(spaces.linkMember(memberId));
        assertEquals(0, db.queryForObject("select count(*) from accounts where email=?", Integer.class, email));
        assertEquals(0, db.queryForObject(
                "select count(*) from account_spaces s join accounts a on a.id=s.account_id where a.email=?",
                Integer.class, email));
    }

    @Test
    void conflictingRegistrationDoesNotConsumeTheVerificationCode() throws Exception {
        String email = "burn@account-flow.test";
        String password = "BurnAccount123!";
        mvc.perform(register(email, code(email), password)).andExpect(status().isOk());
        db.update("update verification_codes set created_at=? where email=?",
                Timestamp.from(Instant.now().minusSeconds(120)), email);
        String secondCode = code(email);
        mvc.perform(register(email, secondCode, "OtherPassword123!")).andExpect(status().isConflict());
        assertNull(db.queryForObject(
                "select consumed_at from verification_codes where email=? order by created_at desc limit 1",
                Timestamp.class, email));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(
            String email, String code, String password) throws Exception {
        return post("/api/account/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "code", code, "password", password,
                        "displayName", "测试用户")));
    }

    private String code(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/account/request-code").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("code").asText();
    }

    private String token(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private String accountId(String email) {
        return db.queryForObject("select id from accounts where email=?", String.class, email);
    }

    private String personalSpaceId(String email) {
        return db.queryForObject("select id from account_spaces where account_id=? and kind='PERSONAL'",
                String.class, accountId(email));
    }

    private long wallet(String email) {
        return db.queryForObject("select balance_units from credit_wallets where space_id=?",
                Long.class, personalSpaceId(email));
    }
}

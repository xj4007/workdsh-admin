package com.techflag.workdsh.admin.catalog;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import com.techflag.workdsh.admin.AccountMigration;
import com.techflag.workdsh.admin.AccountSpaces;
import com.techflag.workdsh.admin.AuthService;
import com.techflag.workdsh.admin.CreditLedger;
import com.techflag.workdsh.admin.VerificationCodes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the same read-only platform views against SQLite to prove the ledger join and wallet/trial
 * lookups stay dialect-safe (substr reference join, no '||' concat, no IN-list edge cases).
 */
@SpringBootTest(properties={
        "spring.datasource.url=jdbc:sqlite:file:platform-accounts-sqlite?mode=memory&cache=shared",
        "workdsh.bootstrap.admin-email=sqlite-owner@example.test",
        "workdsh.bootstrap.admin-password=SqliteOwnerPassword123!",
        "workdsh.accounts.expose-verification-code=true",
        "workdsh.platform.bootstrap-admin-email=sqlite-platform@example.test",
        "workdsh.platform.bootstrap-admin-password=SqlitePlatformPassword123!",
        "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("enterprise-sqlite")
class PlatformAccountsSqliteTest {
    @Autowired PlatformAccountsController controller;
    @Autowired JdbcTemplate db;
    @Autowired AccountSpaces spaces;
    @Autowired VerificationCodes codes;
    @Autowired CreditLedger ledger;
    @Autowired AuthService auth;
    @Autowired AccountMigration migration;

    @Test
    void accountsAndLedgerViewsStayDialectSafeOnSqlite() {
        String email = "sqlite-usage@example.test";
        var issued = codes.issue(email, VerificationCodes.REGISTER);
        var registered = spaces.register(email, issued.code(), "SqliteUsagePassword123!", "SQLite Usage");
        String accountId = registered.account().id();
        String personalId = registered.space().id();

        // The controller expects the raw Authorization header value, "Bearer <token>".
        PlatformAccountsController.AccountsView view = controller.accounts(
                bearer(adminToken()), email, 50, 0);
        assertEquals(1, view.total());
        PlatformAccountsController.AccountView account = view.accounts().get(0);
        assertEquals(accountId, account.accountId());
        assertEquals(email, account.email());
        assertEquals("NONE", account.platformRole());
        assertTrue(account.emailVerified());
        assertTrue(account.active());
        assertNotNull(account.createdAt());
        assertEquals(1, account.spaces().size());
        assertEquals("PERSONAL", account.spaces().get(0).kind());
        assertEquals(personalId, account.wallet().spaceId());
        assertEquals(500_000L, account.wallet().balanceUnits());
        assertEquals(0L, account.wallet().reservedUnits());
        assertEquals(500_000L, account.wallet().availableUnits());
        assertEquals("500.000", account.wallet().balanceCredits());
        assertEquals("500.000", account.wallet().availableCredits());
        assertEquals("credit", account.wallet().unit());
        assertEquals(1000, account.wallet().scale());
        assertEquals(500_000L, account.trial().grantedUnits());
        assertEquals("500.000", account.trial().grantedCredits());
        assertNotNull(account.trial().grantedAt());

        // Enterprise spaces carry organization resolution on SQLite too.
        String orgId = db.queryForObject("select id from organizations limit 1", String.class);
        String orgName = db.queryForObject("select name from organizations where id=?", String.class, orgId);
        String memberId = UUID.randomUUID().toString();
        db.update("""
                insert into members(id,organization_id,email,display_name,role,password_hash,
                  must_change_password,active,revision) values (?,?,?,?,?,?,false,true,1)
                """, memberId, orgId, "sqlite-enterprise@example.test", "企业管理员", "ADMIN",
                auth.encode("SqliteEnterprise123!"));
        spaces.linkMember(memberId);
        PlatformAccountsController.AccountView enterprise = controller.accounts(
                bearer(adminToken()), "sqlite-enterprise@example.test", 50, 0).accounts().get(0);
        assertEquals(2, enterprise.spaces().size());
        var enterpriseSpace = enterprise.spaces().stream()
                .filter(s -> "ENTERPRISE".equals(s.kind())).findFirst().orElseThrow();
        assertEquals(memberId, enterpriseSpace.memberId());
        assertEquals("ADMIN", enterpriseSpace.role());
        assertTrue(enterpriseSpace.active());
        assertEquals(orgId, enterpriseSpace.organizationId());
        assertEquals(orgName, enterpriseSpace.organizationName());

        // Ledger join surfaces managed-request usage on SQLite's substr().
        String requestId = "req-" + UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        db.update("""
                insert into managed_requests(id,request_id,account_id,space_id,model_id,price_version,
                  multiplier_basis_points,status,reserved_units,charged_units,input_tokens,output_tokens,created_at,updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), requestId, accountId, personalId, "model-x", 2, 15000,
                "SETTLED", 3000, 1500, 120L, 45L, now, now);
        ledger.settle(personalId, 3000, 1500, "managed:" + requestId);

        PlatformAccountsController.LedgerView ledgerView = controller.ledger(bearer(adminToken()), accountId, 50, 0);
        assertEquals(2, ledgerView.total());
        var usage = ledgerView.entries().stream()
                .filter(e -> "USAGE".equals(e.reason())).findFirst().orElseThrow();
        assertEquals("managed:" + requestId, usage.reference());
        assertEquals(-1500L, usage.deltaUnits());
        assertEquals("-1.500", usage.deltaCredits());
        assertEquals(498_500L, usage.balanceAfterUnits());
        assertNull(usage.actorAccountId());
        assertNotNull(usage.createdAt());
        assertNotNull(usage.request());
        assertEquals(requestId, usage.request().requestId());
        assertEquals("model-x", usage.request().modelId());
        assertEquals("SETTLED", usage.request().status());
        assertEquals(2, usage.request().priceVersion());
        assertEquals(15000, usage.request().multiplierBasisPoints());
        assertEquals(120L, usage.request().inputTokens());
        assertEquals(45L, usage.request().outputTokens());
        var trial = ledgerView.entries().stream()
                .filter(e -> "TRIAL".equals(e.reason())).findFirst().orElseThrow();
        assertEquals("trial:" + accountId, trial.reference());
        assertEquals(500_000L, trial.deltaUnits());
        assertNull(trial.request());

        PlatformAccountsController.LedgerView paged = controller.ledger(bearer(adminToken()), accountId, 1, 1);
        assertEquals(2, paged.total());
        assertEquals(1, paged.entries().size());
        PlatformAccountsController.LedgerView empty =
                controller.ledger(bearer(adminToken()), UUID.randomUUID().toString(), 50, 0);
        assertEquals(0, empty.total());
        assertTrue(empty.entries().isEmpty());
    }

    @Test
    void sqliteReadsStayPlatformAdminOnly() {
        String owner = spaces.login("sqlite-owner@example.test", "SqliteOwnerPassword123!").token();
        var issued = codes.issue("sqlite-plain@example.test", VerificationCodes.REGISTER);
        var registered = spaces.register("sqlite-plain@example.test", issued.code(),
                "SqlitePlainPassword123!", "SQLite Plain");
        String plain = spaces.login("sqlite-plain@example.test", "SqlitePlainPassword123!").token();
        assertNotNull(registered.account().id());

        for (String token : new String[] {bearer(owner), bearer(plain)}) {
            ResponseStatusException accounts = assertThrows(ResponseStatusException.class,
                    () -> controller.accounts(token, null, 50, 0));
            assertEquals(HttpStatus.FORBIDDEN, accounts.getStatusCode());
            ResponseStatusException ledgerRows = assertThrows(ResponseStatusException.class,
                    () -> controller.ledger(token, null, 50, 0));
            assertEquals(HttpStatus.FORBIDDEN, ledgerRows.getStatusCode());
        }
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.accounts(null, null, 50, 0)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.ledger(null, null, 50, 0)).getStatusCode());

        assertEquals(0, migration.backfill());
        assertEquals(1, db.queryForObject(
                "select count(*) from accounts where email=? and platform_role='SUPERADMIN'",
                Integer.class, "sqlite-platform@example.test"));
        PlatformAccountsController.AccountsView admin = controller.accounts(bearer(adminToken()), null, 50, 0);
        assertTrue(admin.total() >= 3);
    }

    private String adminToken() {
        return spaces.login("sqlite-platform@example.test", "SqlitePlatformPassword123!").token();
    }

    private static String bearer(String token) { return "Bearer " + token; }
}

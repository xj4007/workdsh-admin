package com.techflag.workdsh.admin;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:sqlite:file:account-sqlite?mode=memory&cache=shared",
        "workdsh.bootstrap.admin-email=sqlite-account@example.test",
        "workdsh.bootstrap.admin-password=SqliteAccountPassword123!",
        "workdsh.accounts.expose-verification-code=true",
        "workdsh.platform.bootstrap-admin-email=sqlite-platform@example.test",
        "workdsh.platform.bootstrap-admin-password=SqlitePlatformPassword123!",
        "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("enterprise-sqlite")
class AccountSqliteTest {
    @Autowired JdbcTemplate db;
    @Autowired AccountSpaces spaces;
    @Autowired VerificationCodes codes;
    @Autowired AccountMigration migration;

    @Test
    void sqliteSchemaIsIdempotentAndAccountsWork() throws Exception {
        for (String table : List.of("accounts", "account_spaces", "account_sessions", "verification_codes",
                "credit_wallets", "credit_ledger", "platform_models", "managed_requests",
                "platform_audit_events", "platform_migrations")) {
            assertEquals(1, db.queryForObject("select count(*) from sqlite_master where type='table' and name=?",
                    Integer.class, table), table);
        }
        try (var connection = db.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-sqlite.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-sqlite.sql"));
        }

        String email = "sqlite-user@example.test";
        var issued = codes.issue(email, VerificationCodes.REGISTER);
        var registered = spaces.register(email, issued.code(), "SqliteUserPassword123!", "SQLite User");
        assertEquals("PERSONAL", registered.space().kind());
        assertEquals(500_000L, db.queryForObject("select balance_units from credit_wallets where space_id=?",
                Long.class, registered.space().id()));
        assertEquals(1, db.queryForObject("select count(*) from credit_ledger where reference=?",
                Integer.class, "trial:" + registered.account().id()));

        var login = spaces.login(email, "SqliteUserPassword123!");
        assertNotNull(login.token());
        assertEquals("PERSONAL", login.space().kind());

        assertEquals(0, migration.backfill());
        assertEquals(1, db.queryForObject("""
                select count(*) from account_spaces where kind='ENTERPRISE'
                and member_id=(select id from members where email=?)
                """, Integer.class, "sqlite-account@example.test"));
        assertEquals(1, db.queryForObject("select count(*) from accounts where email=? and platform_role='SUPERADMIN'",
                Integer.class, "sqlite-platform@example.test"));
    }
}

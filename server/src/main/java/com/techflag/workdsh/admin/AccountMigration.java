package com.techflag.workdsh.admin;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Additive migration for existing enterprise databases: links every active member to a global
 * account, then optionally upserts the platform superadmin from explicit operations configuration.
 * Never promotes the first registrant or an existing enterprise OWNER.
 */
@Component
public class AccountMigration implements ApplicationRunner, Ordered {
    static final String MARKER = "platform-accounts-v1";
    private final JdbcTemplate db;
    private final AccountSpaces accounts;
    private final PlatformAudit audit;
    private final TransactionTemplate transactions;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String adminEmail;
    private final String adminPassword;

    public AccountMigration(JdbcTemplate db, AccountSpaces accounts, PlatformAudit audit,
                            TransactionTemplate transactions,
                            @Value("${workdsh.platform.bootstrap-admin-email:}") String adminEmail,
                            @Value("${workdsh.platform.bootstrap-admin-password:}") String adminPassword) {
        this.db = db;
        this.accounts = accounts;
        this.audit = audit;
        this.transactions = transactions;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    /** Runs after the enterprise bootstrap admin, so a fresh database is linked on first start. */
    @Override
    public int getOrder() { return 100; }

    @Override
    public void run(ApplicationArguments args) {
        backfill();
        bootstrapPlatformAdmin();
    }

    /** Idempotent: the marker stops rescanning, and each link is itself repeatable. */
    public int backfill() {
        Integer changed = transactions.execute(status -> backfillOnce());
        return changed == null ? 0 : changed;
    }

    private int backfillOnce() {
        if (applied()) return 0;
        int changed = 0;
        for (String memberId : db.queryForList("select id from members order by id", String.class)) {
            if (accounts.linkMember(memberId)) changed++;
        }
        db.update("insert into platform_migrations(id,applied_at) values (?,?)",
                MARKER, Timestamp.from(Instant.now()));
        return changed;
    }

    public boolean applied() {
        Long count = db.queryForObject("select count(*) from platform_migrations where id=?", Long.class, MARKER);
        return count != null && count > 0;
    }

    public void bootstrapPlatformAdmin() {
        boolean hasEmail = adminEmail != null && !adminEmail.isBlank();
        boolean hasPassword = adminPassword != null && !adminPassword.isBlank();
        if (!hasEmail && !hasPassword) return;
        if (!hasEmail || !hasPassword || adminPassword.length() < 12) {
            throw new IllegalStateException(
                    "Set WORKDSH_PLATFORM_ADMIN_EMAIL and a platform admin password of at least 12 characters");
        }
        String email = AccountAuthService.normalizeEmail(adminEmail);
        var ids = db.queryForList("select id from accounts where email=?", String.class, email);
        String accountId;
        String detail;
        if (ids.isEmpty()) {
            accountId = accounts.createAccount(email, passwords.encode(adminPassword), "平台管理员", true).id();
            detail = "created";
        } else {
            accountId = ids.get(0);
            detail = "reset";
        }
        Instant now = Instant.now();
        // The configured operations password is always written; an existing account is never
        // promoted without resetting its password to the operator-owned value.
        db.update("""
                update accounts set password_hash=?,platform_role=?,email_verified_at=coalesce(email_verified_at,?),
                active=true,revision=revision+1,updated_at=? where id=?
                """, passwords.encode(adminPassword), AccountAuthService.SUPERADMIN, Timestamp.from(now),
                Timestamp.from(now), accountId);
        db.update("delete from account_sessions where account_id=?", accountId);
        audit.record(accountId, "platform.admin.bootstrap", "account", accountId, detail);
        AccountAuthService.Space personal = accounts.ensurePersonalSpace(accountId);
        accounts.grantTrial(accountId, personal.id());
    }
}

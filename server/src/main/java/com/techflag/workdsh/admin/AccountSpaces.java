package com.techflag.workdsh.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registration, login and space switching. Every space decision is resolved server-side from the
 * account, never from a client-supplied organization id.
 */
@Service
public class AccountSpaces {
    private final JdbcTemplate db;
    private final AccountAuthService auth;
    private final CreditLedger ledger;
    private final PlatformAudit audit;
    private final VerificationCodes codes;
    private final TransactionTemplate transactions;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final long trialUnits;

    public AccountSpaces(JdbcTemplate db, AccountAuthService auth, CreditLedger ledger, PlatformAudit audit,
                         VerificationCodes codes, TransactionTemplate transactions,
                         @Value("${workdsh.accounts.trial-credits-units:500000}") long trialUnits) {
        this.db = db;
        this.auth = auth;
        this.ledger = ledger;
        this.audit = audit;
        this.codes = codes;
        this.transactions = transactions;
        this.trialUnits = trialUnits;
    }

    public record LoginResult(String token, AccountAuthService.Account account, AccountAuthService.Space space,
                              List<AccountAuthService.Space> spaces, Instant expiresAt) {}

    public LoginResult login(String email, String password) {
        String normalized = AccountAuthService.normalizeEmail(email);
        var rows = db.query("""
                select id,email,display_name,platform_role,email_verified_at,password_hash,active
                from accounts where email=?
                """, (rs, n) -> new AccountRow(rs.getString("id"), rs.getString("email"), rs.getString("display_name"),
                rs.getString("platform_role"), rs.getTimestamp("email_verified_at") != null,
                rs.getString("password_hash"), rs.getBoolean("active")), normalized);
        if (rows.isEmpty() || !rows.get(0).active() || !passwords.matches(password, rows.get(0).passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        var row = rows.get(0);
        AccountAuthService.Account account = new AccountAuthService.Account(row.id(), row.email(), row.displayName(),
                row.platformRole(), row.verified());
        List<AccountAuthService.Space> spaces = auth.spaces(account.id());
        if (spaces.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No space available for this account");
        var space = spaces.stream().filter(AccountAuthService.Space::personal).findFirst().orElse(spaces.get(0));
        var issued = auth.issueSession(account.id(), space.id());
        return new LoginResult(issued.token(), account, space, spaces, issued.expiresAt());
    }

    public LoginResult register(String email, String code, String password, String displayName) {
        String normalized = AccountAuthService.normalizeEmail(email);
        if (password == null || password.length() < 12 || password.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must have at least 12 characters");
        }
        // Conflict checks happen before the code is consumed so a failed request does not burn it.
        rejectRegistrationConflicts(normalized);
        // Consumed outside the registration transaction so failed attempts are still counted.
        codes.consume(normalized, VerificationCodes.REGISTER, code);
        return transactions.execute(status -> createRegisteredAccount(normalized, password, displayName));
    }

    private void rejectRegistrationConflicts(String normalized) {
        if (exists("select count(*) from members where email=?", normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Email belongs to an enterprise member; sign in with the enterprise account");
        }
        if (exists("select count(*) from accounts where email=?", normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account already exists");
        }
    }

    private LoginResult createRegisteredAccount(String normalized, String password, String displayName) {
        rejectRegistrationConflicts(normalized);
        String name = displayName(normalized, displayName);
        AccountAuthService.Account account = createAccount(normalized, passwords.encode(password), name, true);
        AccountAuthService.Space personal = ensurePersonalSpace(account.id());
        grantTrial(account.id(), personal.id());
        audit.record(account.id(), "account.registered", "account", account.id(), null);
        var issued = auth.issueSession(account.id(), personal.id());
        return new LoginResult(issued.token(), account, personal, auth.spaces(account.id()), issued.expiresAt());
    }

    public LoginResult switchSpace(String bearer, String spaceId) {
        AccountAuthService.Account account = auth.requireAccount(bearer);
        AccountAuthService.Space target = auth.space(account.id(), spaceId);
        if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Space not found");
        var issued = auth.issueSession(account.id(), target.id());
        audit.record(account.id(), "account.space.switched", "space", target.id(), target.kind());
        return new LoginResult(issued.token(), account, target, auth.spaces(account.id()), issued.expiresAt());
    }

    public List<AccountAuthService.Space> spaces(String accountId) { return auth.spaces(accountId); }

    /** Changes the authoritative account password and clears the member's must-change flag. */
    public void changePassword(String bearer, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 12 || newPassword.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must have at least 12 characters");
        }
        AccountAuthService.Session session = auth.requireSession(bearer);
        var hashes = db.queryForList("select password_hash from accounts where id=?", String.class,
                session.account().id());
        if (hashes.isEmpty() || !passwords.matches(currentPassword, hashes.get(0))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        String encoded = passwords.encode(newPassword);
        Instant now = Instant.now();
        db.update("update accounts set password_hash=?,revision=revision+1,updated_at=? where id=?",
                encoded, Timestamp.from(now), session.account().id());
        db.update("update members set password_hash=?,must_change_password=false,revision=revision+1 where email=?",
                encoded, session.account().email());
        String currentHash = AccountAuthService.tokenHashOf(tokenPart(bearer));
        db.update("delete from account_sessions where account_id=? and token_hash<>?",
                session.account().id(), currentHash);
        db.update("delete from auth_sessions where member_id in (select id from members where email=?) and token_hash<>?",
                session.account().email(), currentHash);
    }

    private static String tokenPart(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ") || bearer.length() > 512) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required");
        }
        return bearer.substring(7);
    }

    @Transactional
    public AccountAuthService.Account createAccount(String email, String passwordHash, String displayName,
                                                    boolean verified) {
        String normalized = AccountAuthService.normalizeEmail(email);
        var ids = db.queryForList("select id from accounts where email=?", String.class, normalized);
        if (!ids.isEmpty()) return account(ids.get(0));
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        db.update("""
                insert into accounts(id,email,display_name,password_hash,platform_role,email_verified_at,active,revision,created_at,updated_at)
                values (?,?,?,?,'NONE',?,true,1,?,?)
                """, id, normalized, truncate(displayName, 120), passwordHash,
                verified ? Timestamp.from(now) : null, Timestamp.from(now), Timestamp.from(now));
        return account(id);
    }

    @Transactional
    public AccountAuthService.Space ensurePersonalSpace(String accountId) {
        var existing = db.queryForList("select id from account_spaces where account_id=? and kind=?",
                String.class, accountId, AccountAuthService.PERSONAL);
        if (!existing.isEmpty()) return requireSpace(accountId, existing.get(0));
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        db.update("""
                insert into account_spaces(id,account_id,kind,name,organization_id,member_id,role,active,created_at)
                values (?,?,'PERSONAL',?,null,null,'OWNER',true,?)
                """, id, accountId, "个人空间", Timestamp.from(now));
        db.update("""
                insert into credit_wallets(space_id,account_id,balance_units,reserved_units,unit,scale,revision,updated_at)
                values (?,?,0,0,'credit',1000,0,?)
                """, id, accountId, Timestamp.from(now));
        return requireSpace(accountId, id);
    }

    @Transactional
    public void ensureEnterpriseSpace(String accountId, String memberId) {
        var existing = db.queryForList("select id from account_spaces where account_id=? and kind=?",
                String.class, accountId, AccountAuthService.ENTERPRISE);
        if (!existing.isEmpty()) return;
        var rows = db.query("select organization_id,role,active from members where id=?",
                (rs, n) -> new MemberRow(rs.getString("organization_id"), rs.getString("role"), rs.getBoolean("active")),
                memberId);
        if (rows.isEmpty()) throw new IllegalStateException("Member not found: " + memberId);
        var member = rows.get(0);
        String organizationName = db.queryForObject("select name from organizations where id=?",
                String.class, member.organizationId());
        db.update("""
                insert into account_spaces(id,account_id,kind,name,organization_id,member_id,role,active,created_at)
                values (?,?,'ENTERPRISE',?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), accountId, organizationName, member.organizationId(), memberId,
                member.role(), member.active(), Timestamp.from(Instant.now()));
    }

    /**
     * Links one existing enterprise member to a global account and its personal/enterprise spaces.
     * Returns true when a new account was created.
     */
    @Transactional
    public boolean linkMember(String memberId) {
        var rows = db.query("select id,organization_id,email,display_name,role,password_hash,active from members where id=?",
                (rs, n) -> new MemberLink(rs.getString("id"), rs.getString("organization_id"), rs.getString("email"),
                        rs.getString("display_name"), rs.getString("role"), rs.getString("password_hash"),
                        rs.getBoolean("active")), memberId);
        if (rows.isEmpty()) return false;
        var member = rows.get(0);
        String email = AccountAuthService.normalizeEmail(member.email());
        var accountIds = db.queryForList("select id from accounts where email=?", String.class, email);
        String accountId;
        boolean created = false;
        if (accountIds.isEmpty()) {
            // Inactive members are not backfilled into an active personal account or trial credits.
            if (!member.active()) return false;
            AccountAuthService.Account account = createAccount(email, member.passwordHash(), member.displayName(), true);
            accountId = account.id();
            created = true;
        } else {
            accountId = accountIds.get(0);
        }
        if (member.active()) {
            AccountAuthService.Space personal = ensurePersonalSpace(accountId);
            grantTrial(accountId, personal.id());
        }
        ensureEnterpriseSpace(accountId, memberId);
        return created;
    }

    public void grantTrial(String accountId, String spaceId) {
        if (trialUnits <= 0) return;
        ledger.grant(accountId, spaceId, trialUnits, "TRIAL", "trial:" + accountId, null);
    }

    private AccountAuthService.Space requireSpace(String accountId, String spaceId) {
        AccountAuthService.Space space = auth.space(accountId, spaceId);
        if (space == null) throw new IllegalStateException("Space could not be created: " + spaceId);
        return space;
    }

    private AccountAuthService.Account account(String id) {
        var rows = db.query("select id,email,display_name,platform_role,email_verified_at from accounts where id=?",
                (rs, n) -> new AccountAuthService.Account(rs.getString("id"), rs.getString("email"),
                        rs.getString("display_name"), rs.getString("platform_role"),
                        rs.getTimestamp("email_verified_at") != null), id);
        if (rows.isEmpty()) throw new IllegalStateException("Account not found: " + id);
        return rows.get(0);
    }

    private boolean exists(String sql, String value) {
        Long count = db.queryForObject(sql, Long.class, value);
        return count != null && count > 0;
    }

    private static String displayName(String email, String requested) {
        String name = requested == null ? "" : requested.trim();
        if (name.isEmpty()) name = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        return truncate(name, 120);
    }

    private static String truncate(String value, int max) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private record AccountRow(String id, String email, String displayName, String platformRole, boolean verified,
                              String passwordHash, boolean active) {}

    private record MemberRow(String organizationId, String role, boolean active) {}

    private record MemberLink(String id, String organizationId, String email, String displayName, String role,
                              String passwordHash, boolean active) {}
}

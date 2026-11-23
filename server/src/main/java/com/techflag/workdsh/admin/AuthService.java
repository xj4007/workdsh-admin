package com.techflag.workdsh.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate db;
    private final AccountSpaces accounts;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);

    public AuthService(JdbcTemplate db, AccountSpaces accounts) {
        this.db = db;
        this.accounts = accounts;
    }

    public record Actor(String id, String organizationId, String email, String displayName,
                        String role, boolean mustChangePassword) {
        public boolean admin() { return role.equals("ADMIN") || role.equals("OWNER"); }
    }
    public record Login(String token, Actor member) {}

    @Bean
    ApplicationRunner bootstrap(
            @Value("${workdsh.bootstrap.admin-email:}") String email,
            @Value("${workdsh.bootstrap.admin-password:}") String password,
            @Value("${workdsh.bootstrap.organization-name:三云Buddy 企业}") String orgName) {
        /** Order 0: the platform account backfill (order 100) links this admin afterwards. */
        class Bootstrap implements ApplicationRunner, Ordered {
            @Override
            public int getOrder() { return 0; }

            @Override
            public void run(ApplicationArguments args) {
                Long count = db.queryForObject("select count(*) from organizations", Long.class);
                if (count != null && count > 0) return;
                if (email.isBlank() || password.length() < 12) {
                    throw new IllegalStateException("Set WORKDSH_BOOTSTRAP_ADMIN_EMAIL and a password of at least 12 characters before first start");
                }
                String orgId = UUID.randomUUID().toString();
                String adminId = UUID.randomUUID().toString();
                db.update("insert into organizations(id,name) values (?,?)", orgId, orgName);
                db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values (?,?,?,?,?,?,false,true,1)",
                        adminId, orgId, AccountAuthService.normalizeEmail(email), "管理员", "OWNER", passwords.encode(password));
            }
        }
        return new Bootstrap();
    }

    /**
     * Enterprise-compatible login. Existing members without a platform account are linked on first
     * login; the account password hash is the single authoritative source after that.
     */
    public Login login(String email, String password) {
        String normalized = resolveEmail(email);
        Map<String, Object> account = accountByEmail(normalized);
        if (account == null) {
            Map<String, Object> legacy = memberByEmail(normalized);
            if (legacy == null || !Boolean.TRUE.equals(legacy.get("active"))
                    || !passwords.matches(password, (String) legacy.get("hash"))) {
                throw unauthorized();
            }
            accounts.linkMember((String) legacy.get("id"));
            account = accountByEmail(normalized);
        }
        if (account == null || !Boolean.TRUE.equals(account.get("active"))
                || !passwords.matches(password, (String) account.get("hash"))) {
            throw unauthorized();
        }
        var users = db.query("select id,organization_id,email,display_name,role,password_hash,must_change_password,active from members where email=? and active=true",
                (rs, n) -> Map.<String,Object>of(
                        "id", rs.getString("id"), "org", rs.getString("organization_id"),
                        "email", rs.getString("email"), "name", rs.getString("display_name"),
                        "role", rs.getString("role"), "hash", rs.getString("password_hash"),
                        "change", rs.getBoolean("must_change_password"), "active", rs.getBoolean("active")),
                normalized);
        if (users.size() != 1) throw unauthorized();
        var u = users.get(0);
        String token = newToken();
        db.update("insert into auth_sessions(token_hash,member_id,expires_at) values (?,?,?)",
                sha256(token), u.get("id"), Timestamp.from(Instant.now().plus(12, ChronoUnit.HOURS)));
        return new Login(token, new Actor((String) u.get("id"), (String) u.get("org"),
                (String) u.get("email"), (String) u.get("name"), (String) u.get("role"),
                (Boolean) u.get("change")));
    }

    public Actor actor(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ") || bearer.length() > 512) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required");
        }
        String token = bearer.substring(7);
        var found = db.query("""
                select m.id,m.organization_id,m.email,m.display_name,m.role,m.must_change_password
                from auth_sessions s join members m on m.id=s.member_id
                where s.token_hash=? and s.expires_at>? and m.active=true
                """, (rs, n) -> new Actor(rs.getString("id"), rs.getString("organization_id"),
                rs.getString("email"), rs.getString("display_name"), rs.getString("role"),
                rs.getBoolean("must_change_password")), sha256(token), Timestamp.from(Instant.now()));
        if (found.size() != 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login expired");
        return found.get(0);
    }

    public void requireAdmin(Actor actor) {
        if (!actor.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
    }

    public void requireReady(Actor actor) {
        if (actor.mustChangePassword()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Password change required");
        }
    }

    public void changePassword(Actor actor, String current, String replacement) {
        if (replacement == null || replacement.length() < 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must have at least 12 characters");
        }
        var accountIds = db.queryForList("select distinct account_id from account_spaces where member_id=?",
                String.class, actor.id());
        String hash = null;
        if (!accountIds.isEmpty()) {
            var hashes = db.queryForList("select password_hash from accounts where id=?", String.class, accountIds.get(0));
            if (!hashes.isEmpty()) hash = hashes.get(0);
        }
        if (hash == null) hash = db.queryForObject("select password_hash from members where id=?", String.class, actor.id());
        if (!passwords.matches(current, hash)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current password is incorrect");
        }
        String encoded = passwords.encode(replacement);
        db.update("update members set password_hash=?,must_change_password=false,revision=revision+1 where id=?",
                encoded, actor.id());
        for (String accountId : accountIds) {
            db.update("update accounts set password_hash=?,revision=revision+1,updated_at=? where id=?",
                    encoded, Timestamp.from(Instant.now()), accountId);
            db.update("delete from account_sessions where account_id=?", accountId);
        }
        db.update("delete from auth_sessions where member_id=?", actor.id());
    }

    public String newTemporaryPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String encode(String password) { return passwords.encode(password); }

    public void logout(String bearer) {
        if (bearer != null && bearer.startsWith("Bearer ")) {
            String hash = sha256(bearer.substring(7));
            db.update("delete from auth_sessions where token_hash=?", hash);
            db.update("delete from account_sessions where token_hash=?", hash);
        }
    }

    String authenticatedSessionKey(String bearer) { actor(bearer); return sha256(bearer.substring(7)); }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String resolveEmail(String email) {
        if (!email.contains("@")) {
            var matches = db.queryForList("select m.email from member_profiles p join members m on m.id=p.member_id where p.username=? and m.active=true",
                    String.class, AccountAuthService.normalizeEmail(email));
            if (matches.size() == 1) email = matches.get(0);
        }
        return AccountAuthService.normalizeEmail(email);
    }

    private Map<String, Object> accountByEmail(String email) {
        var rows = db.query("select id,email,display_name,platform_role,password_hash,active from accounts where email=?",
                (rs, n) -> Map.<String,Object>of(
                        "id", rs.getString("id"), "email", rs.getString("email"),
                        "name", rs.getString("display_name"), "role", rs.getString("platform_role"),
                        "hash", rs.getString("password_hash"), "active", rs.getBoolean("active")), email);
        return rows.size() == 1 ? rows.get(0) : null;
    }

    private Map<String, Object> memberByEmail(String email) {
        var rows = db.query("select id,organization_id,email,display_name,role,password_hash,must_change_password,active from members where email=?",
                (rs, n) -> Map.<String,Object>of(
                        "id", rs.getString("id"), "org", rs.getString("organization_id"),
                        "email", rs.getString("email"), "name", rs.getString("display_name"),
                        "role", rs.getString("role"), "hash", rs.getString("password_hash"),
                        "change", rs.getBoolean("must_change_password"), "active", rs.getBoolean("active")), email);
        return rows.size() == 1 ? rows.get(0) : null;
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }
}

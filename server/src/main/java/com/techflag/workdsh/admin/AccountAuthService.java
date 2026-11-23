package com.techflag.workdsh.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared platform-account authorization. The space always comes from the session token:
 * callers must never trust a client-supplied organization or space id.
 */
@Service
public class AccountAuthService {
    public static final String PERSONAL = "PERSONAL";
    public static final String ENTERPRISE = "ENTERPRISE";
    public static final String SUPERADMIN = "SUPERADMIN";
    private static final int SESSION_DAYS = 30;
    private final JdbcTemplate db;

    public AccountAuthService(JdbcTemplate db) { this.db = db; }

    public record Account(String id, String email, String displayName, String platformRole, boolean emailVerified) {
        public boolean platformAdmin() { return SUPERADMIN.equals(platformRole); }
    }

    public record Space(String id, String accountId, String kind, String name, String organizationId,
                        String memberId, String role, boolean active) {
        public boolean personal() { return PERSONAL.equals(kind); }
    }

    public record Session(Account account, Space space, Instant expiresAt) {}

    public record Issued(String token, Instant expiresAt) {}

    public Session requireSession(String bearer) {
        String hash = tokenHash(bearer);
        var rows = db.query("""
                select a.id as account_id,a.email,a.display_name,a.platform_role,a.email_verified_at,
                       s.id as space_id,s.kind,s.name as space_name,s.organization_id,s.member_id,s.role as space_role,
                       s.active as space_active,ss.expires_at,
                       m.active as member_active,m.role as member_role,m.must_change_password as member_must_change,
                       o.name as organization_name
                from account_sessions ss
                join accounts a on a.id=ss.account_id
                join account_spaces s on s.id=ss.space_id and s.account_id=ss.account_id
                left join members m on m.id=s.member_id
                left join organizations o on o.id=s.organization_id
                where ss.token_hash=? and ss.expires_at>? and a.active=true
                """, (rs, n) -> new SessionRow(rs.getString("account_id"), rs.getString("email"),
                rs.getString("display_name"), rs.getString("platform_role"), rs.getTimestamp("email_verified_at") != null,
                rs.getString("space_id"), rs.getString("kind"), rs.getString("space_name"),
                rs.getString("organization_id"), rs.getString("member_id"), rs.getString("space_role"),
                rs.getBoolean("space_active"), rs.getObject("member_active") == null ? null : rs.getBoolean("member_active"),
                rs.getObject("member_must_change") == null ? null : rs.getBoolean("member_must_change"),
                rs.getString("member_role"), rs.getString("organization_name"),
                rs.getTimestamp("expires_at")), hash, Timestamp.from(Instant.now()));
        if (rows.size() != 1) throw unauthorized("Login expired");
        var row = rows.get(0);
        if (!row.spaceActive()) throw unauthorized("Space is not available");
        if (ENTERPRISE.equals(row.kind()) && !Boolean.TRUE.equals(row.memberActive())) {
            throw unauthorized("Space is not available");
        }
        if (ENTERPRISE.equals(row.kind()) && Boolean.TRUE.equals(row.memberMustChange())) {
            throw unauthorized("Change your password before using the enterprise space");
        }
        return new Session(new Account(row.accountId(), row.email(), row.displayName(), row.platformRole(), row.verified()),
                space(row.spaceId(), row.accountId(), row.kind(), row.spaceName(), row.organizationId(), row.memberId(),
                        row.spaceRole(), row.memberRole(), row.organizationName(), row.spaceActive(), row.memberActive()),
                row.expiresAt().toInstant());
    }

    public Account requireAccount(String bearer) { return requireSession(bearer).account(); }

    public Space requireSpace(String bearer, String spaceId) {
        Session session = requireSession(bearer);
        if (spaceId == null || spaceId.isBlank()) return session.space();
        if (!session.space().id().equals(spaceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Space does not match this session");
        }
        return session.space();
    }

    public Space requirePersonalSpace(String bearer, String spaceId) {
        Space space = requireSpace(bearer, spaceId);
        if (!space.personal()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Personal space required");
        return space;
    }

    /** Server-side lookup; returns null when the account is not entitled to the space. */
    public Space space(String accountId, String spaceId) {
        var rows = db.query("""
                select s.id as space_id,s.account_id,s.kind,s.name as space_name,s.organization_id,s.member_id,
                       s.role as space_role,s.active as space_active,m.active as member_active,m.role as member_role,
                       o.name as organization_name
                from account_spaces s
                left join members m on m.id=s.member_id
                left join organizations o on o.id=s.organization_id
                where s.account_id=? and s.id=? and s.active=true and (s.kind='PERSONAL' or m.active=true)
                """, (rs, n) -> new SpaceRow(rs.getString("space_id"), rs.getString("account_id"), rs.getString("kind"),
                rs.getString("space_name"), rs.getString("organization_id"), rs.getString("member_id"),
                rs.getString("space_role"), rs.getBoolean("space_active"), rs.getObject("member_active") == null ? null : rs.getBoolean("member_active"),
                rs.getString("member_role"), rs.getString("organization_name")), accountId, spaceId);
        return rows.isEmpty() ? null : rows.get(0).toSpace();
    }

    public List<Space> spaces(String accountId) {
        return db.query("""
                select s.id as space_id,s.account_id,s.kind,s.name as space_name,s.organization_id,s.member_id,
                       s.role as space_role,s.active as space_active,m.active as member_active,m.role as member_role,
                       o.name as organization_name
                from account_spaces s
                left join members m on m.id=s.member_id
                left join organizations o on o.id=s.organization_id
                where s.account_id=? and s.active=true and (s.kind='PERSONAL' or m.active=true)
                order by case when s.kind='PERSONAL' then 0 else 1 end,s.created_at
                """, (rs, n) -> new SpaceRow(rs.getString("space_id"), rs.getString("account_id"), rs.getString("kind"),
                rs.getString("space_name"), rs.getString("organization_id"), rs.getString("member_id"),
                rs.getString("space_role"), rs.getBoolean("space_active"), rs.getObject("member_active") == null ? null : rs.getBoolean("member_active"),
                rs.getString("member_role"), rs.getString("organization_name")), accountId)
                .stream().map(SpaceRow::toSpace).toList();
    }

    public boolean isPlatformAdmin(Account account) { return account != null && account.platformAdmin(); }

    public void requirePlatformAdmin(Account account) {
        if (!isPlatformAdmin(account)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform admin required");
    }

    /**
     * Issues one token for one space. Enterprise spaces also mirror the token into the legacy
     * member session table so existing enterprise APIs accept the same bearer.
     */
    public Issued issueSession(String accountId, String spaceId) {
        Space space = space(accountId, spaceId);
        if (space == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Space not found");
        if (ENTERPRISE.equals(space.kind()) && mustChangePassword(space.memberId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Change your password before using the enterprise space");
        }
        String token = AuthService.newToken();
        String hash = tokenHashOf(token);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(SESSION_DAYS, ChronoUnit.DAYS);
        db.update("insert into account_sessions(token_hash,account_id,space_id,created_at,expires_at) values (?,?,?,?,?)",
                hash, accountId, spaceId, Timestamp.from(now), Timestamp.from(expiresAt));
        if (ENTERPRISE.equals(space.kind())) {
            db.update("insert into auth_sessions(token_hash,member_id,expires_at) values (?,?,?)",
                    hash, space.memberId(), Timestamp.from(expiresAt));
        }
        return new Issued(token, expiresAt);
    }

    public void logout(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ") || bearer.length() > 512) return;
        String hash = tokenHashOf(bearer.substring(7));
        db.update("delete from account_sessions where token_hash=?", hash);
        db.update("delete from auth_sessions where token_hash=?", hash);
    }

    private boolean mustChangePassword(String memberId) {
        if (memberId == null) return false;
        Boolean value = db.queryForObject("select must_change_password from members where id=?", Boolean.class, memberId);
        return Boolean.TRUE.equals(value);
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    static String tokenHashOf(String token) { return AuthService.sha256(token); }

    private static String tokenHash(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ") || bearer.length() > 512) {
            throw unauthorized("Login required");
        }
        String token = bearer.substring(7);
        if (token.isBlank()) throw unauthorized("Login required");
        return tokenHashOf(token);
    }

    private static ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }

    private Space space(String id, String accountId, String kind, String name, String organizationId, String memberId,
                        String role, String memberRole, String organizationName, boolean active, Boolean memberActive) {
        String effectiveRole = ENTERPRISE.equals(kind) ? memberRole : role;
        String effectiveName = ENTERPRISE.equals(kind) && organizationName != null ? organizationName : name;
        boolean effectiveActive = active && (!ENTERPRISE.equals(kind) || Boolean.TRUE.equals(memberActive));
        return new Space(id, accountId, kind, effectiveName, organizationId, memberId, effectiveRole, effectiveActive);
    }

    private record SessionRow(String accountId, String email, String displayName, String platformRole, boolean verified,
                              String spaceId, String kind, String spaceName, String organizationId, String memberId,
                              String spaceRole, boolean spaceActive, Boolean memberActive, Boolean memberMustChange,
                              String memberRole, String organizationName, Timestamp expiresAt) {}

    private record SpaceRow(String id, String accountId, String kind, String name, String organizationId, String memberId,
                            String role, boolean active, Boolean memberActive, String memberRole, String organizationName) {
        Space toSpace() {
            String effectiveRole = ENTERPRISE.equals(kind) ? memberRole : role;
            String effectiveName = ENTERPRISE.equals(kind) && organizationName != null ? organizationName : name;
            boolean effectiveActive = active && (!ENTERPRISE.equals(kind) || Boolean.TRUE.equals(memberActive));
            return new Space(id, accountId, kind, effectiveName, organizationId, memberId, effectiveRole, effectiveActive);
        }
    }
}

package com.techflag.workdsh.admin.catalog;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.techflag.workdsh.admin.AccountAuthService;
import com.techflag.workdsh.admin.CreditLedger;

/**
 * Read-only platform-admin views over platform accounts, their spaces, wallets and the credit
 * ledger. SQL stays dialect-safe (H2, SQLite, MySQL, PostgreSQL): no <code>||</code> concat, the
 * managed-request join uses the portable <code>substr(reference, 9)</code> to strip "managed:".
 */
@RestController
@RequestMapping("/api/admin/platform")
public class PlatformAccountsController {
    private static final int MAX_LIMIT = 200;
    private final AccountAuthService auth;
    private final JdbcTemplate db;

    public PlatformAccountsController(AccountAuthService auth, JdbcTemplate db) {
        this.auth = auth;
        this.db = db;
    }

    @GetMapping("/accounts")
    public AccountsView accounts(@RequestHeader(value = "Authorization", required = false) String bearer,
                                 @RequestParam(required = false) String query,
                                 @RequestParam(defaultValue = "50") int limit,
                                 @RequestParam(defaultValue = "0") int offset) {
        admin(bearer);
        int page = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int from = Math.max(offset, 0);
        String filter = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        boolean filtered = !filter.isEmpty();
        String like = "%" + filter + "%";
        String where = filtered ? " where lower(a.email) like ? or lower(a.display_name) like ?" : "";
        Long total = db.queryForObject("select count(*) from accounts a" + where, Long.class,
                filtered ? new Object[] {like, like} : new Object[] {});
        var rows = db.query("""
                select a.id,a.email,a.display_name,a.platform_role,a.email_verified_at,a.active,a.created_at
                from accounts a""" + where + " order by a.created_at,a.id limit ? offset ?",
                (rs, n) -> new AccountRow(rs.getString("id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("platform_role"), rs.getTimestamp("email_verified_at") != null,
                        rs.getBoolean("active"), rs.getTimestamp("created_at")),
                filtered ? new Object[] {like, like, page, from} : new Object[] {page, from});
        return new AccountsView(total == null ? 0 : total, views(rows));
    }

    @GetMapping("/ledger")
    public LedgerView ledger(@RequestHeader(value = "Authorization", required = false) String bearer,
                             @RequestParam(required = false) String accountId,
                             @RequestParam(defaultValue = "50") int limit,
                             @RequestParam(defaultValue = "0") int offset) {
        admin(bearer);
        int page = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int from = Math.max(offset, 0);
        String account = accountId == null ? "" : accountId.strip();
        boolean scoped = !account.isEmpty();
        String where = scoped ? " where l.account_id=?" : "";
        Long total = db.queryForObject("select count(*) from credit_ledger l" + where, Long.class,
                scoped ? new Object[] {account} : new Object[] {});
        // substr(l.reference, 9) strips the fixed "managed:" prefix; short references yield an
        // empty/null value on every dialect and simply never match a request id.
        List<LedgerEntryView> entries = db.query("""
                select l.id,l.account_id,l.space_id,l.delta_units,l.balance_after_units,l.reason,l.reference,
                       l.actor_account_id,l.created_at,m.request_id,m.model_id,m.status,m.price_version,
                       m.multiplier_basis_points,m.input_tokens,m.output_tokens,coalesce(w.scale,1000) as wallet_scale
                from credit_ledger l
                left join managed_requests m on l.reason='USAGE' and substr(l.reference,9)=m.request_id
                left join credit_wallets w on w.space_id=l.space_id
                """ + where + " order by l.created_at desc,l.id desc limit ? offset ?",
                (rs, n) -> {
                    String requestId = rs.getString("request_id");
                    ManagedRequestView request = requestId == null ? null : new ManagedRequestView(requestId,
                            rs.getString("model_id"), rs.getString("status"), rs.getObject("price_version") == null
                                    ? null : rs.getInt("price_version"),
                            rs.getObject("multiplier_basis_points") == null ? null : rs.getInt("multiplier_basis_points"),
                            longOrNull(rs, "input_tokens"), longOrNull(rs, "output_tokens"));
                    int scale = rs.getInt("wallet_scale");
                    long delta = rs.getLong("delta_units");
                    return new LedgerEntryView(rs.getString("id"), rs.getString("account_id"), rs.getString("space_id"),
                            rs.getString("reason"), rs.getString("reference"), delta,
                            FixedPoint.credits(delta, scale), rs.getLong("balance_after_units"),
                            rs.getString("actor_account_id"), instant(rs.getTimestamp("created_at")), request);
                },
                scoped ? new Object[] {account, page, from} : new Object[] {page, from});
        return new LedgerView(total == null ? 0 : total, entries);
    }

    private List<AccountView> views(List<AccountRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<String> ids = rows.stream().map(AccountRow::accountId).toList();
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<String, List<SpaceView>> spaces = new LinkedHashMap<>();
        db.query("""
                select s.account_id,s.id,s.kind,s.name,s.organization_id,s.member_id,s.role,s.active,
                       s.created_at,o.name as organization_name
                from account_spaces s left join organizations o on o.id=s.organization_id
                where s.account_id in (""" + in + ") order by s.account_id,s.created_at,s.id",
                (rs, n) -> {
                    spaces.computeIfAbsent(rs.getString("account_id"), k -> new ArrayList<>()).add(
                            new SpaceView(rs.getString("id"), rs.getString("kind"), rs.getString("name"),
                                    rs.getString("organization_id"), rs.getString("member_id"), rs.getString("role"),
                                    rs.getBoolean("active"), rs.getString("organization_name")));
                    return null;
                }, ids.toArray());
        // Wallets and trial grants hang off the personal space id, not the account id; resolve the
        // personal space per account first, then fetch wallet/trial rows by space id.
        Map<String, String> personalSpaceIds = new LinkedHashMap<>();
        for (AccountRow row : rows) {
            spaces.getOrDefault(row.accountId(), List.of()).stream()
                    .filter(s -> AccountAuthService.PERSONAL.equals(s.kind())).findFirst().map(SpaceView::id)
                    .ifPresent(id -> personalSpaceIds.put(row.accountId(), id));
        }
        Map<String, CreditLedger.Wallet> wallets = new LinkedHashMap<>();
        Map<String, TrialRow> trials = new LinkedHashMap<>();
        if (!personalSpaceIds.isEmpty()) {
            List<String> spaceIds = List.copyOf(personalSpaceIds.values());
            String spaceIn = String.join(",", Collections.nCopies(spaceIds.size(), "?"));
            db.query("select space_id,balance_units,reserved_units,unit,scale from credit_wallets where space_id in (" + spaceIn + ")",
                    (rs, n) -> {
                        wallets.put(rs.getString("space_id"), new CreditLedger.Wallet(rs.getString("space_id"), null,
                                rs.getLong("balance_units"), rs.getLong("reserved_units"), rs.getString("unit"),
                                rs.getInt("scale"), 0));
                        return null;
                    }, spaceIds.toArray());
            db.query("select space_id,delta_units,created_at from credit_ledger where reason='TRIAL' and space_id in (" + spaceIn + ")",
                    (rs, n) -> {
                        trials.putIfAbsent(rs.getString("space_id"),
                                new TrialRow(rs.getLong("delta_units"), rs.getTimestamp("created_at")));
                        return null;
                    }, spaceIds.toArray());
        }
        List<AccountView> views = new ArrayList<>(rows.size());
        for (AccountRow row : rows) {
            String personalId = personalSpaceIds.get(row.accountId());
            CreditLedger.Wallet wallet = personalId == null ? null : wallets.get(personalId);
            int scale = wallet == null ? 1000 : wallet.scale();
            String unit = wallet == null ? "credit" : wallet.unit();
            WalletSummary summary = personalId == null ? null : new WalletSummary(personalId,
                    wallet == null ? 0 : wallet.balanceUnits(), wallet == null ? 0 : wallet.reservedUnits(),
                    wallet == null ? 0 : wallet.availableUnits(), FixedPoint.credits(wallet == null ? 0
                            : wallet.balanceUnits(), scale), FixedPoint.credits(wallet == null ? 0
                            : wallet.availableUnits(), scale), unit, scale);
            TrialRow trial = personalId == null ? null : trials.get(personalId);
            TrialView trialView = trial == null ? null : new TrialView(trial.units(),
                    FixedPoint.credits(trial.units(), scale), instant(trial.at()));
            views.add(new AccountView(row.accountId(), row.email(), row.displayName(), row.platformRole(),
                    row.emailVerified(), row.active(), instant(row.createdAt()),
                    spaces.getOrDefault(row.accountId(), List.of()), summary, trialView));
        }
        return views;
    }

    private static Long longOrNull(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant().toString(); }

    private void admin(String bearer) {
        auth.requirePlatformAdmin(auth.requireAccount(bearer));
    }

    private record AccountRow(String accountId, String email, String displayName, String platformRole,
                              boolean emailVerified, boolean active, Timestamp createdAt) {}

    private record TrialRow(long units, Timestamp at) {}

    public record AccountsView(long total, List<AccountView> accounts) {}

    public record AccountView(String accountId, String email, String displayName, String platformRole,
                              boolean emailVerified, boolean active, String createdAt, List<SpaceView> spaces,
                              WalletSummary wallet, TrialView trial) {}

    public record SpaceView(String id, String kind, String name, String organizationId, String memberId,
                            String role, boolean active, String organizationName) {}

    public record WalletSummary(String spaceId, long balanceUnits, long reservedUnits, long availableUnits,
                                String balanceCredits, String availableCredits, String unit, int scale) {}

    public record TrialView(long grantedUnits, String grantedCredits, String grantedAt) {}

    public record LedgerView(long total, List<LedgerEntryView> entries) {}

    public record LedgerEntryView(String id, String accountId, String spaceId, String reason, String reference,
                                  long deltaUnits, String deltaCredits, long balanceAfterUnits,
                                  String actorAccountId, String createdAt, ManagedRequestView request) {}

    public record ManagedRequestView(String requestId, String modelId, String status, Integer priceVersion,
                                     Integer multiplierBasisPoints, Long inputTokens, Long outputTokens) {}
}

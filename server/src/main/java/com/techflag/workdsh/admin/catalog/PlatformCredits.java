package com.techflag.workdsh.admin.catalog;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.techflag.workdsh.admin.AccountAuthService;
import com.techflag.workdsh.admin.CreditLedger;
import com.techflag.workdsh.admin.PlatformAudit;

/** Platform-admin manual credit issuance. The idempotency key becomes the ledger reference. */
@Service
public class PlatformCredits {
    private final JdbcTemplate db;
    private final CreditLedger ledger;
    private final AccountAuthService auth;
    private final PlatformAudit audit;

    public PlatformCredits(JdbcTemplate db, CreditLedger ledger, AccountAuthService auth, PlatformAudit audit) {
        this.db = db;
        this.ledger = ledger;
        this.auth = auth;
        this.audit = audit;
    }

    public record Issue(String email, Long amountUnits, String idempotencyKey, String reason) {}

    public record Issued(String accountId, String spaceId, long amountUnits, String reference,
                         long balanceUnits, long availableUnits, String availableCredits, String unit, int scale) {}

    private record LedgerRow(String spaceId, long deltaUnits) {}

    public Issued issue(AccountAuthService.Account actor, Issue request) {
        auth.requirePlatformAdmin(actor);
        if (request == null) bad("Credit issue is required");
        String email = AccountAuthService.normalizeEmail(request.email());
        if (email.isEmpty()) bad("Account email is required");
        if (request.amountUnits() == null || request.amountUnits() <= 0 || request.amountUnits() > 1_000_000_000_000L) {
            bad("Amount must be a positive number of fixed-point units");
        }
        String reference = request.idempotencyKey();
        if (reference == null || !reference.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            bad("Idempotency key must be 1-128 letters, digits, '.', '_', ':' or '-'");
        }
        String reason = request.reason() == null ? "" : request.reason().strip();
        if (reason.length() > 200) bad("Reason must be at most 200 characters");
        var accountIds = db.queryForList("select id from accounts where email=?", String.class, email);
        if (accountIds.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        String accountId = accountIds.get(0);
        AccountAuthService.Space personal = auth.spaces(accountId).stream()
                .filter(AccountAuthService.Space::personal).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Personal space not found"));
        long amount = request.amountUnits();
        LedgerRow existing = ledgerRow(reference);
        if (existing != null) {
            verifySame(existing, personal.id(), amount);
            return issued(accountId, personal.id(), amount, reference);
        }
        try {
            ledger.grant(accountId, personal.id(), amount, "ISSUE", reference, actor.id());
        } catch (DuplicateKeyException e) {
            LedgerRow raced = ledgerRow(reference);
            if (raced == null) throw e;
            verifySame(raced, personal.id(), amount);
            return issued(accountId, personal.id(), amount, reference);
        }
        audit.record(actor.id(), "platform.credits.issued", "account", accountId, detail(reason, reference, amount));
        return issued(accountId, personal.id(), amount, reference);
    }

    private static String detail(String reason, String reference, long amount) {
        return reason.isEmpty()
                ? "reference=" + reference + ",units=" + amount
                : "reason=" + reason + ",reference=" + reference + ",units=" + amount;
    }

    private Issued issued(String accountId, String spaceId, long amountUnits, String reference) {
        CreditLedger.Wallet wallet = ledger.wallet(spaceId);
        return new Issued(accountId, spaceId, amountUnits, reference, wallet.balanceUnits(),
                wallet.availableUnits(), FixedPoint.credits(wallet.availableUnits(), wallet.scale()),
                wallet.unit(), wallet.scale());
    }

    private LedgerRow ledgerRow(String reference) {
        var rows = db.query("select space_id,delta_units from credit_ledger where reference=?",
                (rs, n) -> new LedgerRow(rs.getString("space_id"), rs.getLong("delta_units")), reference);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void verifySame(LedgerRow row, String spaceId, long amountUnits) {
        if (!row.spaceId().equals(spaceId) || row.deltaUnits() != amountUnits) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key already used with different parameters");
        }
    }

    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}

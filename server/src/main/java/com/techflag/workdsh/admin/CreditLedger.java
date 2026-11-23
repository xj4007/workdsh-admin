package com.techflag.workdsh.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fixed-point credit wallet: 1000 units = 1 credit. Amounts stay in integer units so settlement
 * never depends on floating point. Every balance change is written to the ledger.
 */
@Service
public class CreditLedger {
    private final JdbcTemplate db;
    private final DatabaseDialect dialect;

    public CreditLedger(JdbcTemplate db, DatabaseDialect dialect) {
        this.db = db;
        this.dialect = dialect;
    }

    public record Wallet(String spaceId, String accountId, long balanceUnits, long reservedUnits, String unit,
                         int scale, long revision) {
        public long availableUnits() { return balanceUnits - reservedUnits; }
    }

    public Wallet wallet(String spaceId) {
        var rows = db.query("""
                select space_id,account_id,balance_units,reserved_units,unit,scale,revision
                from credit_wallets where space_id=?
                """, (rs, n) -> new Wallet(rs.getString("space_id"), rs.getString("account_id"),
                rs.getLong("balance_units"), rs.getLong("reserved_units"), rs.getString("unit"),
                rs.getInt("scale"), rs.getLong("revision")), spaceId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found");
        return rows.get(0);
    }

    /** Adds credits once per reference. Repeating the same reference is a no-op. */
    @Transactional
    public void grant(String accountId, String spaceId, long deltaUnits, String reason, String reference,
                      String actorAccountId) {
        if (deltaUnits <= 0) throw new IllegalArgumentException("Credit grant must be positive");
        if (reference == null || reference.isBlank() || reference.length() > 128) {
            throw new IllegalArgumentException("Credit reference is required");
        }
        // Lock first, then check the unique reference: serialises concurrent idempotent grants.
        Wallet wallet = lockWallet(spaceId);
        Long existing = db.queryForObject("select count(*) from credit_ledger where reference=?", Long.class, reference);
        if (existing != null && existing > 0) return;
        long balanceAfter = wallet.balanceUnits() + deltaUnits;
        db.update("update credit_wallets set balance_units=?,revision=revision+1,updated_at=? where space_id=?",
                balanceAfter, Timestamp.from(Instant.now()), spaceId);
        insertLedger(accountId, spaceId, deltaUnits, balanceAfter, reason, reference, actorAccountId);
    }

    /** Reserves units for an in-flight managed request; false means the available balance is too low. */
    @Transactional
    public boolean reserve(String spaceId, long units) {
        if (units <= 0) return true;
        return db.update("""
                update credit_wallets set reserved_units=reserved_units+?,revision=revision+1,updated_at=?
                where space_id=? and balance_units-reserved_units>=?
                """, units, Timestamp.from(Instant.now()), spaceId, units) == 1;
    }

    /** Releases a reservation without charging. Clamps at zero so retries stay safe. */
    @Transactional
    public void release(String spaceId, long units) {
        if (units <= 0) return;
        Wallet wallet = lockWallet(spaceId);
        db.update("update credit_wallets set reserved_units=?,revision=revision+1,updated_at=? where space_id=?",
                Math.max(0, wallet.reservedUnits() - units), Timestamp.from(Instant.now()), spaceId);
    }

    /** Settles actual usage: releases the reservation and deducts the charge. */
    @Transactional
    public void settle(String spaceId, long reservedUnits, long chargedUnits, String reference) {
        if (reservedUnits < 0 || chargedUnits < 0) throw new IllegalArgumentException("Credit amounts must not be negative");
        if (reference == null || reference.isBlank() || reference.length() > 128) {
            throw new IllegalArgumentException("Credit reference is required");
        }
        // Lock first, then check the unique reference: serialises concurrent idempotent settlements.
        Wallet wallet = lockWallet(spaceId);
        Long existing = db.queryForObject("select count(*) from credit_ledger where reference=?", Long.class, reference);
        if (existing != null && existing > 0) return;
        if (wallet.balanceUnits() < chargedUnits) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Insufficient credits");
        }
        long balanceAfter = wallet.balanceUnits() - chargedUnits;
        db.update("update credit_wallets set balance_units=?,reserved_units=?,revision=revision+1,updated_at=? where space_id=?",
                balanceAfter, Math.max(0, wallet.reservedUnits() - reservedUnits), Timestamp.from(Instant.now()), spaceId);
        if (chargedUnits > 0) {
            insertLedger(wallet.accountId(), spaceId, -chargedUnits, balanceAfter, "USAGE", reference, null);
        }
    }

    private Wallet lockWallet(String spaceId) {
        var rows = db.query(dialect.lock("""
                select space_id,account_id,balance_units,reserved_units,unit,scale,revision
                from credit_wallets where space_id=?
                """), (rs, n) -> new Wallet(rs.getString("space_id"), rs.getString("account_id"),
                rs.getLong("balance_units"), rs.getLong("reserved_units"), rs.getString("unit"),
                rs.getInt("scale"), rs.getLong("revision")), spaceId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found");
        return rows.get(0);
    }

    private void insertLedger(String accountId, String spaceId, long deltaUnits, long balanceAfter, String reason,
                              String reference, String actorAccountId) {
        db.update("""
                insert into credit_ledger(id,account_id,space_id,delta_units,balance_after_units,reason,reference,actor_account_id,created_at)
                values (?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), accountId, spaceId, deltaUnits, balanceAfter, reason, reference,
                actorAccountId, Timestamp.from(Instant.now()));
    }
}

package com.techflag.workdsh.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Platform-level audit trail, separate from the per-organization enterprise audit table. */
@Service
public class PlatformAudit {
    private final JdbcTemplate db;

    public PlatformAudit(JdbcTemplate db) { this.db = db; }

    public void record(String actorAccountId, String action, String targetType, String targetId, String detail) {
        db.update("""
                insert into platform_audit_events(id,actor_account_id,action,target_type,target_id,detail,occurred_at)
                values (?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), actorAccountId, action, targetType, targetId,
                detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000)), Timestamp.from(Instant.now()));
    }
}

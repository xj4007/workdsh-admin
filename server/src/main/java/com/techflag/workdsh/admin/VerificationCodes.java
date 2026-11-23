package com.techflag.workdsh.admin;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Single-use email verification codes with resend cooldown, hourly send cap and attempt cap. */
@Service
public class VerificationCodes {
    public static final String REGISTER = "REGISTER";
    static final int TTL_SECONDS = 600;
    static final int MAX_ATTEMPTS = 5;
    static final int SENDS_PER_HOUR = 5;
    static final int RESEND_SECONDS = 60;

    private final JdbcTemplate db;
    private final SecureRandom random = new SecureRandom();
    private final boolean expose;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String mailFrom;
    private final byte[] codeKey;

    public VerificationCodes(JdbcTemplate db, ObjectProvider<JavaMailSender> mailSender,
                             @Value("${workdsh.accounts.expose-verification-code:false}") boolean expose,
                             @Value("${WORKDSH_ACCOUNTS_MAIL_FROM:${spring.mail.username:}}") String mailFrom,
                             @Value("${workdsh.secrets.encryption-key:}") String secret) {
        this.db = db;
        this.mailSender = mailSender;
        this.expose = expose;
        this.mailFrom = mailFrom;
        byte[] key = null;
        if (secret != null && !secret.isBlank()) {
            try {
                byte[] decoded = Base64.getDecoder().decode(secret.trim());
                if (decoded.length >= 16) key = decoded;
            } catch (IllegalArgumentException ignored) {
                // Fall through to a process-local pepper.
            }
        }
        if (key == null) {
            key = new byte[32];
            random.nextBytes(key);
        }
        // ponytail: process-local pepper when no encryption key is configured; 10-minute codes
        // do not need to survive a restart. Set WORKDSH_SECRETS_ENCRYPTION_KEY for shared deployments.
        this.codeKey = key;
    }

    /** {@code code} is only returned when the beta explicitly exposes it for testing. */
    public record Issued(Instant expiresAt, String code) {}

    public Issued issue(String email, String purpose) {
        String normalized = AccountAuthService.normalizeEmail(email);
        Instant now = Instant.now();
        var latest = db.queryForList("select created_at from verification_codes where email=? and purpose=? order by created_at desc limit 1",
                Timestamp.class, normalized, purpose);
        if (!latest.isEmpty() && latest.get(0).toInstant().plusSeconds(RESEND_SECONDS).isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Verification code was sent recently");
        }
        Long recent = db.queryForObject("select count(*) from verification_codes where email=? and purpose=? and created_at>?",
                Long.class, normalized, purpose, Timestamp.from(now.minusSeconds(3600)));
        if (recent != null && recent >= SENDS_PER_HOUR) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many verification codes requested");
        }
        String code = "%06d".formatted(random.nextInt(1_000_000));
        Instant expiresAt = now.plusSeconds(TTL_SECONDS);
        if (!expose) send(normalized, code);
        db.update("""
                insert into verification_codes(id,email,purpose,code_hash,attempts,max_attempts,created_at,expires_at,consumed_at)
                values (?,?,?,?,0,?,?,?,null)
                """, UUID.randomUUID().toString(), normalized, purpose, codeHash(normalized, purpose, code),
                MAX_ATTEMPTS, Timestamp.from(now), Timestamp.from(expiresAt));
        return new Issued(expiresAt, expose ? code : null);
    }

    /** Consumes the latest code for the email; throws when it is unknown, used, expired or out of attempts. */
    public void consume(String email, String purpose, String code) {
        String normalized = AccountAuthService.normalizeEmail(email);
        if (code == null || !code.matches("\\d{6}")) {
            throw bad("Enter the 6-digit verification code");
        }
        var rows = db.query("""
                select id,code_hash,attempts,max_attempts,expires_at,consumed_at
                from verification_codes where email=? and purpose=? order by created_at desc limit 1
                """, (rs, n) -> new CodeRow(rs.getString("id"), rs.getString("code_hash"), rs.getInt("attempts"),
                rs.getInt("max_attempts"), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant()),
                normalized, purpose);
        if (rows.isEmpty()) throw bad("Request a verification code first");
        var row = rows.get(0);
        Instant now = Instant.now();
        if (row.consumedAt() != null) throw bad("Verification code was already used");
        if (!row.expiresAt().isAfter(now)) throw bad("Verification code expired");
        if (row.attempts() >= row.maxAttempts()) throw tooMany("Too many verification attempts");
        if (!row.codeHash().equals(codeHash(normalized, purpose, code))) {
            int next = row.attempts() + 1;
            db.update("update verification_codes set attempts=? where id=?", next, row.id());
            if (next >= row.maxAttempts()) throw tooMany("Too many verification attempts");
            throw bad("Incorrect verification code");
        }
        int changed = db.update("update verification_codes set consumed_at=? where id=? and consumed_at is null",
                Timestamp.from(now), row.id());
        if (changed != 1) throw bad("Verification code was already used");
    }

    /** Package-private test helper: the hash actually stored for a code. */
    String hashForTest(String email, String purpose, String code) {
        return codeHash(AccountAuthService.normalizeEmail(email), purpose, code);
    }

    private void send(String email, String code) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || mailFrom == null || mailFrom.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Verification email is not configured; set SPRING_MAIL_HOST and WORKDSH_ACCOUNTS_MAIL_FROM");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(email);
        message.setSubject("三云Buddy verification code");
        message.setText("Your 三云Buddy verification code is " + code + ". It expires in 10 minutes.");
        try {
            sender.send(message);
        } catch (MailException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Verification email could not be sent; check the mail configuration");
        }
    }

    private String codeHash(String email, String purpose, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(codeKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((email + ":" + purpose + ":" + code).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Verification hash unavailable", e);
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException tooMany(String message) {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    private record CodeRow(String id, String codeHash, int attempts, int maxAttempts, Instant expiresAt,
                           Instant consumedAt) {}
}

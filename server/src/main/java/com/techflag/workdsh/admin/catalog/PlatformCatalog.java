package com.techflag.workdsh.admin.catalog;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.techflag.workdsh.admin.AccountAuthService;
import com.techflag.workdsh.admin.MemberSecrets;
import com.techflag.workdsh.admin.PlatformAudit;

/**
 * Public platform model catalog. Vendor keys are encrypted with the platform reference and are
 * never included in any response record. Price changes are versioned: a multiplier change bumps
 * price_version and old version callers are rejected instead of silently repriced.
 */
@Service
public class PlatformCatalog {
    public static final String PROTOCOL_OPENAI_CHAT = "openai-chat-completions";

    private static final String SELECT = """
            select id,display_name,protocol,base_url,vendor_model,encrypted_vendor_key,
                   multiplier_basis_points,capabilities_json,context_tokens,max_output_tokens,
                   enabled,price_version,revision,created_at,updated_at
            from platform_models
            """;

    private final JdbcTemplate db;
    private final MemberSecrets secrets;
    private final PlatformAudit audit;
    private final ObjectMapper json;

    public PlatformCatalog(JdbcTemplate db, MemberSecrets secrets, PlatformAudit audit, ObjectMapper json) {
        this.db = db;
        this.secrets = secrets;
        this.audit = audit;
        this.json = json;
    }

    public record AdminModel(String id, String displayName, String protocol, String baseUrl, String vendorModel,
                             int multiplierBasisPoints, String multiplier, JsonNode capabilities,
                             int contextTokens, int maxOutputTokens, boolean enabled, int priceVersion,
                             long revision, Instant createdAt, Instant updatedAt) {}

    public record Create(String id, String displayName, String protocol, String baseUrl, String vendorModel,
                         String vendorKey, Integer multiplierBasisPoints, JsonNode capabilities,
                         Integer contextTokens, Integer maxOutputTokens, Boolean enabled) {}

    public record Update(String displayName, String protocol, String baseUrl, String vendorModel,
                         Integer multiplierBasisPoints, JsonNode capabilities, Integer contextTokens,
                         Integer maxOutputTokens, Boolean enabled, Integer expectedPriceVersion) {}

    public record RotateKey(String vendorKey) {}

    public record Enabled(Boolean enabled) {}

    /** Account/desktop view. displayName already carries the human multiplier suffix. */
    public record ModelView(String id, String displayName, String multiplier, int multiplierBasisPoints,
                            JsonNode capabilities, int contextTokens, int maxOutputTokens, int priceVersion) {}

    /** Resolved upstream target for the managed gateway. Never serialized to a client. */
    public record Resolved(String id, String protocol, String baseUrl, String vendorModel, String vendorKey,
                           int multiplierBasisPoints, int priceVersion, int contextTokens, int maxOutputTokens) {}

    private record Stored(String id, String displayName, String protocol, String baseUrl, String vendorModel,
                          String encryptedVendorKey, int multiplierBasisPoints, String capabilitiesJson,
                          int contextTokens, int maxOutputTokens, boolean enabled, int priceVersion, long revision,
                          Timestamp createdAt, Timestamp updatedAt) {}

    public List<AdminModel> list() {
        return db.query(SELECT + " order by id", (rs, n) -> toAdmin(row(rs))).stream().toList();
    }

    public AdminModel get(String id) { return toAdmin(stored(id)); }

    public AdminModel create(AccountAuthService.Account actor, Create request) {
        if (request == null) bad("Model is required");
        validateId(request.id());
        String displayName = text(request.displayName(), "Display name", 160);
        String protocol = protocol(request.protocol());
        String baseUrl = baseUrl(request.baseUrl());
        String vendorModel = text(request.vendorModel(), "Vendor model", 160);
        String vendorKey = vendorKey(request.vendorKey());
        int multiplier = multiplier(request.multiplierBasisPoints());
        JsonNode capabilities = capabilities(request.capabilities());
        int contextTokens = limit(request.contextTokens(), "Context tokens");
        int maxOutputTokens = limit(request.maxOutputTokens(), "Max output tokens");
        if (maxOutputTokens > contextTokens) bad("Max output tokens cannot exceed context tokens");
        Instant now = Instant.now();
        try {
            db.update("""
                    insert into platform_models(id,display_name,protocol,base_url,vendor_model,encrypted_vendor_key,
                      multiplier_basis_points,capabilities_json,context_tokens,max_output_tokens,enabled,price_version,
                      revision,created_at,updated_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,1,0,?,?)
                    """, request.id(), displayName, protocol, baseUrl, vendorModel,
                    secrets.encryptPlatformSecret(secretRef(request.id()), vendorKey), multiplier,
                    serialize(capabilities), contextTokens, maxOutputTokens,
                    Boolean.TRUE.equals(request.enabled()), Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            throw conflict("Model already exists");
        }
        audit.record(actor.id(), "platform.model.created", "model", request.id(), null);
        return get(request.id());
    }

    public AdminModel update(AccountAuthService.Account actor, String id, Update request) {
        if (request == null) bad("Model update is required");
        Stored current = stored(id);
        if (request.expectedPriceVersion() == null) bad("Expected price version is required");
        if (request.expectedPriceVersion() != current.priceVersion()) {
            throw conflict("Price version changed; refresh the catalog before saving");
        }
        String displayName = request.displayName() == null ? current.displayName()
                : text(request.displayName(), "Display name", 160);
        String protocol = request.protocol() == null ? current.protocol() : protocol(request.protocol());
        String baseUrl = request.baseUrl() == null ? current.baseUrl() : baseUrl(request.baseUrl());
        String vendorModel = request.vendorModel() == null ? current.vendorModel()
                : text(request.vendorModel(), "Vendor model", 160);
        int multiplier = request.multiplierBasisPoints() == null ? current.multiplierBasisPoints()
                : multiplier(request.multiplierBasisPoints());
        String capabilitiesJson = request.capabilities() == null ? current.capabilitiesJson()
                : serialize(capabilities(request.capabilities()));
        int contextTokens = request.contextTokens() == null ? current.contextTokens()
                : limit(request.contextTokens(), "Context tokens");
        int maxOutputTokens = request.maxOutputTokens() == null ? current.maxOutputTokens()
                : limit(request.maxOutputTokens(), "Max output tokens");
        boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
        if (maxOutputTokens > contextTokens) bad("Max output tokens cannot exceed context tokens");
        int priceVersion = multiplier != current.multiplierBasisPoints()
                ? current.priceVersion() + 1 : current.priceVersion();
        int updated = db.update("""
                update platform_models set display_name=?,protocol=?,base_url=?,vendor_model=?,
                  multiplier_basis_points=?,capabilities_json=?,context_tokens=?,max_output_tokens=?,
                  enabled=?,price_version=?,revision=revision+1,updated_at=?
                where id=? and revision=?
                """, displayName, protocol, baseUrl, vendorModel, multiplier, capabilitiesJson, contextTokens,
                maxOutputTokens, enabled, priceVersion, Timestamp.from(Instant.now()), id, current.revision());
        if (updated != 1) throw conflict("Model changed; refresh before saving");
        audit.record(actor.id(), "platform.model.updated", "model", id, "priceVersion=" + priceVersion);
        return get(id);
    }

    public AdminModel setEnabled(AccountAuthService.Account actor, String id, Boolean enabled) {
        if (enabled == null) bad("Enabled flag is required");
        Stored current = stored(id);
        if (enabled == current.enabled()) return get(id);
        int updated = db.update("""
                update platform_models set enabled=?,revision=revision+1,updated_at=?
                where id=? and revision=?
                """, enabled, Timestamp.from(Instant.now()), id, current.revision());
        if (updated != 1) throw conflict("Model changed; refresh before saving");
        audit.record(actor.id(), enabled ? "platform.model.enabled" : "platform.model.disabled",
                "model", id, null);
        return get(id);
    }

    public AdminModel rotateKey(AccountAuthService.Account actor, String id, RotateKey request) {
        if (request == null) bad("Vendor key is required");
        Stored current = stored(id);
        String vendorKey = vendorKey(request.vendorKey());
        int updated = db.update("""
                update platform_models set encrypted_vendor_key=?,revision=revision+1,updated_at=?
                where id=? and revision=?
                """, secrets.encryptPlatformSecret(secretRef(id), vendorKey), Timestamp.from(Instant.now()),
                id, current.revision());
        if (updated != 1) throw conflict("Model changed; refresh before saving");
        audit.record(actor.id(), "platform.model.key.rotated", "model", id, null);
        return get(id);
    }

    /** Enabled catalog for a personal space. The caller decides which space may see it. */
    public List<ModelView> enabledModels() {
        return db.query(SELECT + " where enabled=true order by display_name,id",
                (rs, n) -> row(rs)).stream().map(stored -> new ModelView(stored.id(),
                stored.displayName() + " · ×" + FixedPoint.multiplier(stored.multiplierBasisPoints()),
                FixedPoint.multiplier(stored.multiplierBasisPoints()), stored.multiplierBasisPoints(),
                parseCapabilities(stored.capabilitiesJson()), stored.contextTokens(), stored.maxOutputTokens(),
                stored.priceVersion())).toList();
    }

    /**
     * Resolves one enabled model at the price version the caller displayed. A stale version is a
     * conflict with an explicit refresh instruction, never a silent repricing.
     */
    public Resolved resolve(String id, Integer expectedPriceVersion) {
        Stored stored = stored(id);
        if (!stored.enabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Model is not available");
        if (expectedPriceVersion != null && expectedPriceVersion != stored.priceVersion()) {
            throw conflict("Price version changed; refresh the model catalog before retrying");
        }
        return new Resolved(stored.id(), stored.protocol(), stored.baseUrl(), stored.vendorModel(),
                secrets.decryptPlatformSecret(secretRef(stored.id()), stored.encryptedVendorKey()),
                stored.multiplierBasisPoints(), stored.priceVersion(), stored.contextTokens(),
                stored.maxOutputTokens());
    }

    private Stored stored(String id) {
        var rows = db.query(SELECT + " where id=?", (rs, n) -> row(rs), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found");
        return rows.get(0);
    }

    private static Stored row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Stored(rs.getString("id"), rs.getString("display_name"), rs.getString("protocol"),
                rs.getString("base_url"), rs.getString("vendor_model"), rs.getString("encrypted_vendor_key"),
                rs.getInt("multiplier_basis_points"), rs.getString("capabilities_json"),
                rs.getInt("context_tokens"), rs.getInt("max_output_tokens"), rs.getBoolean("enabled"),
                rs.getInt("price_version"), rs.getLong("revision"), rs.getTimestamp("created_at"),
                rs.getTimestamp("updated_at"));
    }

    private AdminModel toAdmin(Stored stored) {
        return new AdminModel(stored.id(), stored.displayName(), stored.protocol(), stored.baseUrl(),
                stored.vendorModel(), stored.multiplierBasisPoints(),
                FixedPoint.multiplier(stored.multiplierBasisPoints()), parseCapabilities(stored.capabilitiesJson()),
                stored.contextTokens(), stored.maxOutputTokens(), stored.enabled(), stored.priceVersion(),
                stored.revision(), stored.createdAt().toInstant(), stored.updatedAt().toInstant());
    }

    private JsonNode parseCapabilities(String value) {
        try {
            JsonNode node = json.readTree(value);
            return node != null && (node.isArray() || node.isObject()) ? node : json.createArrayNode();
        } catch (Exception e) {
            return json.createArrayNode();
        }
    }

    private String serialize(JsonNode capabilities) {
        try {
            String value = json.writeValueAsString(capabilities);
            if (value.length() > 8192) bad("Capabilities are too large");
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize capabilities", e);
        }
    }

    private static String secretRef(String id) { return "model/" + id; }

    private static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            bad("Model id must be 1-128 letters, digits, '.', '_', ':' or '-'");
        }
    }

    private static String text(String value, String label, int max) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > max) {
            bad(label + " is required and must be at most " + max + " characters");
        }
        return value.trim();
    }

    private static String protocol(String value) {
        if (!PROTOCOL_OPENAI_CHAT.equals(value)) bad("Only " + PROTOCOL_OPENAI_CHAT + " is supported");
        return value;
    }

    private static String baseUrl(String value) {
        if (value == null || value.length() > 500) bad("HTTPS model base URL is required");
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                bad("HTTPS model base URL is required");
            }
        } catch (IllegalArgumentException e) {
            bad("HTTPS model base URL is required");
        }
        return value;
    }

    private static String vendorKey(String value) {
        if (value == null || value.isEmpty() || value.length() > 8192
                || value.chars().anyMatch(Character::isISOControl)) {
            bad("Vendor key is required and must be at most 8192 characters");
        }
        return value;
    }

    private static int multiplier(Integer value) {
        if (value == null || value <= 0 || value > 100_000) bad("Multiplier must be between 1 and 100000 basis points");
        return value;
    }

    private static JsonNode capabilities(JsonNode value) {
        if (value == null || value.isNull() || (!value.isArray() && !value.isObject())) {
            bad("Capabilities must be a JSON object or array");
        }
        return value;
    }

    private static int limit(Integer value, String label) {
        if (value == null || value <= 0 || value > 10_000_000) bad(label + " must be between 1 and 10000000");
        return value;
    }

    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}

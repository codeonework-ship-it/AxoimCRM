package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.common.UnauthorizedException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bearer tokens for the SCIM 2.0 surface (FR-TEN-007).
 *
 * <p>SCIM is authenticated by its own credential, never by a user's access token.
 * A directory connector holding a user JWT would inherit that user's session,
 * lifetime and revocation — and would break the moment the person who set it up
 * left. It is also the wrong blast radius: a SCIM token can manage users and
 * nothing else.
 *
 * <p>The token is minted as {@code axm_scim.<workspace-slug>.<secret>}, so the
 * tenant is derived from the credential the client presents rather than from a
 * header the client could change. Presenting another workspace's slug with your
 * own secret binds that workspace and then fails the hash comparison — it reads
 * nothing, because the comparison happens inside that tenant's rows under RLS.
 */
@Service
public class ScimTokenService {

    private static final String PREFIX = "axm_scim";
    private static final List<String> KNOWN_SCOPES = List.of(
            "users:read", "users:write", "groups:read", "groups:write");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public ScimTokenService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record TokenRow(UUID id, String name, List<String> scopes, Instant expiresAt,
                           Instant lastUsedAt, Instant revokedAt, String state) {}

    /** Returned once at issue; the token value is never retrievable again. */
    public record IssuedToken(UUID id, String name, String token, List<String> scopes,
                              Instant expiresAt, String baseUrl, String warning) {}

    public record IssueRequest(String name, List<String> scopes, Integer expiresInDays) {}

    /** What the SCIM filter needs after authenticating a bearer token. */
    public record AuthenticatedToken(UUID id, UUID tenantId, String name, List<String> scopes) {}

    @Transactional(readOnly = true)
    public List<TokenRow> list() {
        return jdbc.query("""
                select id, name, scopes, expires_at, last_used_at, revoked_at
                from identity.scim_token where tenant_id = ?
                order by created_at desc
                """, (rs, i) -> {
            Instant expiresAt = rs.getTimestamp("expires_at") == null
                    ? null : rs.getTimestamp("expires_at").toInstant();
            Instant revokedAt = rs.getTimestamp("revoked_at") == null
                    ? null : rs.getTimestamp("revoked_at").toInstant();
            String state = revokedAt != null ? "REVOKED"
                    : expiresAt != null && expiresAt.isBefore(Instant.now()) ? "EXPIRED" : "ACTIVE";
            return new TokenRow(rs.getObject("id", UUID.class), rs.getString("name"),
                    scopesOf(rs.getArray("scopes")), expiresAt,
                    rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant(),
                    revokedAt, state);
        }, TenantContext.get().tenantId());
    }

    @Transactional
    public IssuedToken issue(IssueRequest request) {
        requireAdmin();
        ImpersonationService.assertNotImpersonating("Issuing a directory provisioning token");
        TenantContext.Principal principal = TenantContext.get();
        String name = request.name() == null || request.name().isBlank()
                ? null : request.name().trim();
        if (name == null) {
            throw new IllegalArgumentException("Name the token after the directory that will use it, "
                    + "for example \"Entra ID provisioning\".");
        }
        List<String> scopes = normaliseScopes(request.scopes());
        String slug = jdbc.queryForObject("select slug from platform.tenant where id = ?",
                String.class, principal.tenantId());
        String secret = randomToken(32);
        String token = PREFIX + "." + slug + "." + secret;
        Instant expiresAt = request.expiresInDays() == null || request.expiresInDays() <= 0
                ? null : Instant.now().plus(Duration.ofDays(request.expiresInDays()));
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into identity.scim_token(id, tenant_id, name, token_hash, scopes, expires_at, created_by)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, id, principal.tenantId(), name, bcrypt.encode(secret), scopes.toArray(String[]::new),
                    expiresAt == null ? null : Timestamp.from(expiresAt), principal.userId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new com.axiom.common.ConflictException("A provisioning token named \"" + name
                    + "\" already exists in this workspace. Revoke it first, or choose another name.");
        }
        audit.record("SCIM_TOKEN_ISSUE", "SCIM_TOKEN", id,
                "Directory provisioning token issued: " + name,
                Map.of("scopes", scopes, "expiresAt", expiresAt == null ? "never" : expiresAt.toString()));
        return new IssuedToken(id, name, token, scopes, expiresAt, "/scim/v2",
                "Copy the token now — only a hash is stored and it cannot be shown again. "
                        + "Configure it as the bearer token in your directory's provisioning settings.");
    }

    @Transactional
    public void revoke(UUID id, String reason) {
        requireAdmin();
        TenantContext.Principal principal = TenantContext.get();
        String cleaned = reason == null || reason.isBlank() ? null : reason.trim();
        if (cleaned == null) {
            throw new IllegalArgumentException("Give a reason for revoking this token — it is audited.");
        }
        int updated = jdbc.update("""
                update identity.scim_token set revoked_at = now()
                where tenant_id = ? and id = ? and revoked_at is null
                """, principal.tenantId(), id);
        if (updated == 0) throw new NotFoundException("That token does not exist or is already revoked");
        audit.recordWithReason("SCIM_TOKEN_REVOKE", "SCIM_TOKEN", id,
                "Directory provisioning token revoked", cleaned, Map.of());
    }

    /**
     * Verifies a presented bearer token and binds the workspace it belongs to.
     * Updates {@code last_used_at}, which is how an administrator can tell whether
     * a directory connector is still running.
     */
    @Transactional
    public AuthenticatedToken authenticate(String presented) {
        String[] parts = presented == null ? new String[0] : presented.split("\\.");
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw new UnauthorizedException("Invalid provisioning token");
        }
        UUID tenantId;
        try {
            tenantId = jdbc.queryForObject("select id from platform.tenant where lower(slug) = lower(?)",
                    UUID.class, parts[1]);
        } catch (EmptyResultDataAccessException e) {
            throw new UnauthorizedException("Invalid provisioning token");
        }
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                select id, name, token_hash, scopes, expires_at, revoked_at
                from identity.scim_token where tenant_id = ? and revoked_at is null
                """, tenantId);
        for (Map<String, Object> candidate : candidates) {
            if (!bcrypt.matches(parts[2], (String) candidate.get("token_hash"))) continue;
            if (candidate.get("expires_at") != null
                    && ((Timestamp) candidate.get("expires_at")).toInstant().isBefore(Instant.now())) {
                throw new UnauthorizedException("This provisioning token has expired. Issue a new one.");
            }
            jdbc.update("update identity.scim_token set last_used_at = now() where tenant_id = ? and id = ?",
                    tenantId, candidate.get("id"));
            return new AuthenticatedToken((UUID) candidate.get("id"), tenantId,
                    (String) candidate.get("name"), scopesOf(candidate.get("scopes")));
        }
        throw new UnauthorizedException("Invalid provisioning token");
    }

    private static List<String> scopesOf(Object array) {
        if (array == null) return List.of();
        try {
            Object raw = array instanceof java.sql.Array sqlArray ? sqlArray.getArray() : array;
            if (raw instanceof Object[] values) {
                return java.util.Arrays.stream(values).map(String::valueOf).toList();
            }
        } catch (java.sql.SQLException e) {
            return List.of();
        }
        return List.of();
    }

    private List<String> normaliseScopes(List<String> requested) {
        if (requested == null || requested.isEmpty()) return KNOWN_SCOPES;
        List<String> cleaned = requested.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        List<String> unknown = cleaned.stream().filter(scope -> !KNOWN_SCOPES.contains(scope)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown scope(s): " + String.join(", ", unknown)
                    + ". Available scopes are " + String.join(", ", KNOWN_SCOPES) + ".");
        }
        return cleaned;
    }

    private String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static void requireAdmin() {
        CrmRole role = CrmRole.current(TenantContext.get().role());
        if (role != CrmRole.SUPER_ADMIN && role != CrmRole.TENANT_ADMIN) {
            throw new ForbiddenException("Managing directory provisioning tokens requires "
                    + "Super Admin or Tenant Admin");
        }
    }
}

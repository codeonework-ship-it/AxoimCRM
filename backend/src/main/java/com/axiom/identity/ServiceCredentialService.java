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
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Machine credentials for integrations (FR-TEN-013): scoped, expiring, rotatable,
 * revocable, with last-used telemetry.
 *
 * <p><b>The credential names its own tenant.</b> Identifiers are minted as
 * {@code axm_svc.<workspace-slug>.<random>}, so the server derives the tenant
 * from the presented credential and never from a header or parameter (ADR-001
 * rule 4). Presenting a well-formed identifier for another workspace resolves
 * that workspace and then fails the secret comparison — it cannot be used to read
 * anything, because the tenant binding happens before the lookup and the hash
 * lives inside that tenant's rows.
 *
 * <p><b>The secret is shown exactly once.</b> Only a bcrypt hash is stored. A
 * lost secret is rotated, not recovered — there is no endpoint that can return
 * it, by design.
 */
@Service
public class ServiceCredentialService {

    private static final String PREFIX = "axm_svc";
    private static final List<String> KNOWN_SCOPES =
            List.of("api:read", "api:write", "reports:read", "export:read");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public ServiceCredentialService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record CredentialRow(UUID id, String name, String clientId, List<String> scopes,
                                Instant expiresAt, Instant lastUsedAt, Instant rotatedAt,
                                Instant revokedAt, String state) {}

    /** Returned once at issue or rotation; {@code clientSecret} is never retrievable again. */
    public record IssuedCredential(UUID id, String name, String clientId, String clientSecret,
                                   List<String> scopes, Instant expiresAt, String warning) {}

    public record IssueRequest(String name, List<String> scopes, Integer expiresInDays) {}

    /** What authentication yields: enough to mint a scoped service token. */
    public record AuthenticatedCredential(UUID id, UUID tenantId, String name, List<String> scopes) {}

    // ------------------------------------------------------------------
    // Administration
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<CredentialRow> list() {
        return jdbc.query("""
                select id, name, client_id, scopes, expires_at, last_used_at, rotated_at, revoked_at
                from identity.service_credential
                where tenant_id = ?
                order by created_at desc
                """, (rs, i) -> mapRow(rs), TenantContext.get().tenantId());
    }

    @Transactional
    public IssuedCredential issue(IssueRequest request) {
        requireAdmin();
        ImpersonationService.assertNotImpersonating("Issuing a service credential");
        TenantContext.Principal principal = TenantContext.get();
        String name = clean(request.name());
        if (name == null) {
            throw new IllegalArgumentException("Give the credential a name that says which integration uses it.");
        }
        List<String> scopes = normaliseScopes(request.scopes());
        String slug = slug(principal.tenantId());
        String clientId = PREFIX + "." + slug + "." + randomToken(12);
        String secret = randomToken(32);
        Instant expiresAt = request.expiresInDays() == null || request.expiresInDays() <= 0
                ? null : Instant.now().plus(java.time.Duration.ofDays(request.expiresInDays()));
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into identity.service_credential
                      (id, tenant_id, name, client_id, secret_hash, scopes, expires_at, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, principal.tenantId(), name, clientId, bcrypt.encode(secret),
                    scopes.toArray(String[]::new), expiresAt == null ? null : Timestamp.from(expiresAt),
                    principal.userId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new com.axiom.common.ConflictException("A credential named \"" + name
                    + "\" already exists in this workspace. Rotate that one instead, or choose another name — "
                    + "names are how an administrator tells credentials apart later.");
        }
        audit.record("SERVICE_CREDENTIAL_ISSUE", "SERVICE_CREDENTIAL", id,
                "Service credential issued: " + name,
                Map.of("clientId", clientId, "scopes", scopes,
                        "expiresAt", expiresAt == null ? "never" : expiresAt.toString()));
        return new IssuedCredential(id, name, clientId, secret, scopes, expiresAt,
                "Copy the client secret now. It is not stored in a recoverable form and cannot be shown again. "
                        + "If it is lost, rotate the credential.");
    }

    @Transactional
    public IssuedCredential rotate(UUID id) {
        requireAdmin();
        ImpersonationService.assertNotImpersonating("Rotating a service credential");
        TenantContext.Principal principal = TenantContext.get();
        Map<String, Object> existing;
        try {
            existing = jdbc.queryForMap("""
                    select name, client_id, scopes, expires_at from identity.service_credential
                    where tenant_id = ? and id = ? and revoked_at is null
                    """, principal.tenantId(), id);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("That service credential does not exist or is already revoked");
        }
        String secret = randomToken(32);
        jdbc.update("""
                update identity.service_credential
                set secret_hash = ?, rotated_at = now()
                where tenant_id = ? and id = ?
                """, bcrypt.encode(secret), principal.tenantId(), id);
        audit.record("SERVICE_CREDENTIAL_ROTATE", "SERVICE_CREDENTIAL", id,
                "Service credential secret rotated: " + existing.get("name"),
                Map.of("clientId", String.valueOf(existing.get("client_id"))));
        Instant expiresAt = existing.get("expires_at") == null
                ? null : ((Timestamp) existing.get("expires_at")).toInstant();
        return new IssuedCredential(id, (String) existing.get("name"), (String) existing.get("client_id"),
                secret, scopesOf(existing.get("scopes")), expiresAt,
                "The previous secret stopped working immediately. Update the integration before its next call.");
    }

    @Transactional
    public void revoke(UUID id, String reason) {
        requireAdmin();
        ImpersonationService.assertNotImpersonating("Revoking a service credential");
        TenantContext.Principal principal = TenantContext.get();
        String cleaned = clean(reason);
        if (cleaned == null) {
            throw new IllegalArgumentException("Give a reason for revoking this credential — it is audited.");
        }
        int updated = jdbc.update("""
                update identity.service_credential set revoked_at = now()
                where tenant_id = ? and id = ? and revoked_at is null
                """, principal.tenantId(), id);
        if (updated == 0) {
            throw new NotFoundException("That service credential does not exist or is already revoked");
        }
        audit.recordWithReason("SERVICE_CREDENTIAL_REVOKE", "SERVICE_CREDENTIAL", id,
                "Service credential revoked", cleaned, Map.of());
    }

    // ------------------------------------------------------------------
    // Authentication
    // ------------------------------------------------------------------

    /**
     * OAuth 2.0 client-credentials style verification. Updates {@code last_used_at}
     * on success, which is the telemetry FR-TEN-013 asks for and the only way an
     * administrator can tell a live integration from an abandoned one.
     */
    @Transactional
    public AuthenticatedCredential authenticate(String clientId, String clientSecret) {
        String[] parts = clientId == null ? new String[0] : clientId.split("\\.");
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw new UnauthorizedException("Invalid client credentials");
        }
        UUID tenantId;
        try {
            tenantId = jdbc.queryForObject("select id from platform.tenant where lower(slug) = lower(?)",
                    UUID.class, parts[1]);
        } catch (EmptyResultDataAccessException e) {
            throw new UnauthorizedException("Invalid client credentials");
        }
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    select id, name, secret_hash, scopes, expires_at, revoked_at
                    from identity.service_credential
                    where tenant_id = ? and client_id = ?
                    """, tenantId, clientId);
        } catch (EmptyResultDataAccessException e) {
            throw new UnauthorizedException("Invalid client credentials");
        }
        if (row.get("revoked_at") != null) {
            throw new UnauthorizedException("This credential has been revoked");
        }
        if (row.get("expires_at") != null
                && ((Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())) {
            throw new UnauthorizedException("This credential expired. Rotate it or issue a new one.");
        }
        if (clientSecret == null || !bcrypt.matches(clientSecret, (String) row.get("secret_hash"))) {
            throw new UnauthorizedException("Invalid client credentials");
        }
        jdbc.update("update identity.service_credential set last_used_at = now() where tenant_id = ? and id = ?",
                tenantId, row.get("id"));
        return new AuthenticatedCredential((UUID) row.get("id"), tenantId, (String) row.get("name"),
                scopesOf(row.get("scopes")));
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private CredentialRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Instant expiresAt = rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant();
        Instant revokedAt = rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant();
        String state = revokedAt != null ? "REVOKED"
                : expiresAt != null && expiresAt.isBefore(Instant.now()) ? "EXPIRED" : "ACTIVE";
        return new CredentialRow(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("client_id"), scopesOf(rs.getArray("scopes")), expiresAt,
                rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant(),
                rs.getTimestamp("rotated_at") == null ? null : rs.getTimestamp("rotated_at").toInstant(),
                revokedAt, state);
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
        if (requested == null || requested.isEmpty()) return List.of("api:read");
        List<String> cleaned = requested.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        List<String> unknown = cleaned.stream().filter(scope -> !KNOWN_SCOPES.contains(scope)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown scope(s): " + String.join(", ", unknown)
                    + ". Available scopes are " + String.join(", ", KNOWN_SCOPES) + ".");
        }
        return cleaned;
    }

    private String slug(UUID tenantId) {
        return jdbc.queryForObject("select slug from platform.tenant where id = ?", String.class, tenantId);
    }

    private String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static void requireAdmin() {
        CrmRole role = CrmRole.current(TenantContext.get().role());
        if (role != CrmRole.SUPER_ADMIN && role != CrmRole.TENANT_ADMIN) {
            throw new ForbiddenException("Managing service credentials requires Super Admin or Tenant Admin");
        }
    }
}

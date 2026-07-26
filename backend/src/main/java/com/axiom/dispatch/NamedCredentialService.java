package com.axiom.dispatch;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.common.SecretCipher;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Named credentials for outbound integration (FR-INT-007).
 *
 * <p>Four properties, all of them enforced here rather than by convention:
 * <ul>
 *   <li><b>Encrypted at rest</b> — via {@link SecretCipher}, the cipher already
 *       used for TOTP and OIDC client secrets. A second encryption helper would
 *       be a second key-management problem and a second thing to get wrong.</li>
 *   <li><b>Never displayed after entry</b> — {@link CredentialRow} has no field
 *       that could carry it. Not masked-on-the-way-out; absent from the type.</li>
 *   <li><b>Rotatable</b> — {@link #rotate} replaces the value in place, so every
 *       connector referencing the name picks the new value up with no edit.</li>
 *   <li><b>Referenced by name</b> — connectors store {@code credential_ref}, a
 *       name. The plaintext is resolved at dispatch time and held only for the
 *       duration of the call.</li>
 * </ul>
 */
@Service
public class NamedCredentialService {

    /** What a read endpoint shows instead of a secret. Constant, never derived from the value. */
    public static final String MASK = "********";

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final AuditService audit;

    public NamedCredentialService(JdbcTemplate jdbc, SecretCipher cipher, AuditService audit) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.audit = audit;
    }

    /**
     * The read shape. There is deliberately no {@code secret} field: a DTO that
     * carries a secret "but the controller never populates it" is one refactor
     * away from leaking, and the test for this asserts on the record's
     * components, not on a controller's behaviour.
     */
    public record CredentialRow(UUID id, String name, String credentialType, String description,
                                String secretMasked, Instant rotatedAt, Instant lastUsedAt,
                                Instant createdAt, boolean inUse) {}

    @Transactional(readOnly = true)
    public List<CredentialRow> list() {
        return jdbc.query("""
                select c.id, c.name, c.credential_type, c.description, c.rotated_at, c.last_used_at, c.created_at,
                       exists (select 1 from dispatch.connector k
                                where k.tenant_id = c.tenant_id and k.credential_ref = c.name) as in_use
                from dispatch.named_credential c
                where c.tenant_id = ?
                order by c.name
                """, (rs, i) -> new CredentialRow(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("credential_type"),
                        rs.getString("description"), MASK,
                        instant(rs.getTimestamp("rotated_at")), instant(rs.getTimestamp("last_used_at")),
                        instant(rs.getTimestamp("created_at")), rs.getBoolean("in_use")),
                TenantContext.get().tenantId());
    }

    @Transactional
    public CredentialRow create(CredentialRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        UUID tenantId = TenantContext.get().tenantId();
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into dispatch.named_credential
                      (tenant_id, name, credential_type, secret_cipher, description, created_by)
                    values (?, ?, ?, ?, ?, ?)
                    returning id
                    """, UUID.class, tenantId, request.name().trim(), request.credentialType(),
                    cipher.encrypt(request.secret()), request.description(), TenantContext.get().userId());
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("A credential named '" + request.name() + "' already exists");
        }
        // The audit event records THAT a credential was stored, never the value.
        audit.record("INTEGRATION_CREDENTIAL_CREATED", "NAMED_CREDENTIAL", id,
                "Stored integration credential " + request.name(),
                Map.of("name", request.name(), "credentialType", request.credentialType()));
        return byName(request.name().trim());
    }

    @Transactional
    public CredentialRow rotate(String name, SecretRotation rotation) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        int updated = jdbc.update("""
                update dispatch.named_credential
                   set secret_cipher = ?, rotated_at = now(), updated_at = now()
                 where tenant_id = ? and name = ?
                """, cipher.encrypt(rotation.secret()), TenantContext.get().tenantId(), name);
        if (updated == 0) {
            throw new NotFoundException("No credential named '" + name + "'");
        }
        CredentialRow row = byName(name);
        audit.record("INTEGRATION_CREDENTIAL_ROTATED", "NAMED_CREDENTIAL", row.id(),
                "Rotated integration credential " + name, Map.of("name", name));
        return row;
    }

    @Transactional
    public void delete(String name) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        CredentialRow row = byName(name);
        if (row.inUse()) {
            throw new ConflictException("Credential '" + name
                    + "' is referenced by a connector. Repoint the connector before deleting it.");
        }
        jdbc.update("delete from dispatch.named_credential where tenant_id = ? and name = ?",
                TenantContext.get().tenantId(), name);
        audit.record("INTEGRATION_CREDENTIAL_DELETED", "NAMED_CREDENTIAL", row.id(),
                "Deleted integration credential " + name, Map.of("name", name));
    }

    @Transactional(readOnly = true)
    public CredentialRow byName(String name) {
        return list().stream()
                .filter(row -> row.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("No credential named '" + name + "'"));
    }

    /**
     * Resolve a credential to plaintext for one outbound call. Package-visible
     * on purpose — no controller can reach it, so no request path can return it.
     *
     * @return null when the name is unset or unknown; the caller decides whether
     *         a missing credential is fatal, because "unsigned webhook" and
     *         "unauthenticated ERP posting" are not equally severe.
     */
    String resolveSecret(String name) {
        if (name == null || name.isBlank()) return null;
        List<String> ciphertexts = jdbc.queryForList("""
                select secret_cipher from dispatch.named_credential
                where tenant_id = ? and name = ?
                """, String.class, TenantContext.get().tenantId(), name.trim());
        if (ciphertexts.isEmpty()) return null;
        return cipher.decrypt(ciphertexts.get(0));
    }

    /** Recorded separately from resolution so a failed dispatch still shows the credential was reached for. */
    void markUsed(String name) {
        if (name == null || name.isBlank()) return;
        jdbc.update("update dispatch.named_credential set last_used_at = ? where tenant_id = ? and name = ?",
                Timestamp.from(Instant.now()), TenantContext.get().tenantId(), name.trim());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.notifications.NotificationWriter;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emergency administrative access (FR-TEN-012).
 *
 * <p>This is the path that stays open when SSO is misconfigured and nobody can
 * sign in — which is why {@code FR-TEN-004}'s on-failure clause points at it. It
 * is also, by construction, the most dangerous capability in the product, so
 * every use costs the operator something:
 *
 * <ul>
 *   <li>a case reference and a substantive justification, both mandatory at the
 *       database level, not just in the DTO;</li>
 *   <li>a time box, enforced on use rather than by a sweeper — an expired grant
 *       cannot be used even if no scheduled job has run;</li>
 *   <li>a notification to every administrator of the affected tenant, so use is
 *       visible to the customer and not only to us;</li>
 *   <li>an audit event naming the case, the justification and the window.</li>
 * </ul>
 *
 * <p>Expiry is evaluated at use time on purpose. A cleanup job that marks grants
 * expired is a convenience; relying on one for the security property means a
 * stalled scheduler silently extends every outstanding grant.
 */
@Service
public class BreakGlassService {

    private static final int MIN_JUSTIFICATION_LENGTH = 20;
    private static final int MAX_DURATION_MINUTES = 240;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final NotificationWriter notifications;

    public BreakGlassService(JdbcTemplate jdbc, AuditService audit, NotificationWriter notifications) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
    }

    public record Grant(UUID id, String actorEmail, String caseReference, String justification,
                        Instant grantedAt, Instant expiresAt, Instant usedAt, Instant revokedAt,
                        String revokeReason, String state) {}

    public record GrantRequest(String caseReference, String justification, int durationMinutes) {}

    @Transactional
    public Grant request(GrantRequest request) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requirePlatform(principal.role());
        String caseRef = clean(request.caseReference());
        String justification = clean(request.justification());
        if (caseRef == null) {
            throw new IllegalArgumentException("Give the support or incident case reference this access is for.");
        }
        if (justification == null || justification.length() < MIN_JUSTIFICATION_LENGTH) {
            throw new IllegalArgumentException("Give a justification of at least " + MIN_JUSTIFICATION_LENGTH
                    + " characters describing what you need to do and why normal access is not sufficient.");
        }
        int minutes = request.durationMinutes() <= 0 ? 60 : request.durationMinutes();
        if (minutes > MAX_DURATION_MINUTES) {
            throw new IllegalArgumentException("Emergency access is limited to " + MAX_DURATION_MINUTES
                    + " minutes. Request a shorter window and renew it if the incident is still open.");
        }
        UUID id = UUID.randomUUID();
        Instant expires = Instant.now().plus(Duration.ofMinutes(minutes));
        jdbc.update("""
                insert into identity.break_glass_grant
                  (id, tenant_id, actor_id, actor_email, case_reference, justification, expires_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, principal.tenantId(), principal.userId(), principal.email(),
                caseRef, justification, Timestamp.from(expires));
        audit.recordWithReason("BREAK_GLASS_REQUEST", "BREAK_GLASS_GRANT", id,
                "Emergency access granted for case " + caseRef + ", expiring in " + minutes + " minutes",
                justification, Map.of("caseReference", caseRef, "durationMinutes", minutes,
                        "expiresAt", expires.toString()));
        notifyTenantAdmins(principal.tenantId(),
                "Emergency access granted",
                principal.email() + " has been granted emergency administrative access to this workspace for "
                        + minutes + " minutes under case " + caseRef + ".",
                justification);
        return get(id);
    }

    /**
     * Consumes a grant. Refuses an expired, revoked or already-used grant, and
     * tells the caller which of those it was.
     */
    @Transactional
    public Grant use(UUID id) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requirePlatform(principal.role());
        Grant grant = get(id);
        if (grant.revokedAt() != null) {
            throw new ConflictException("That emergency access grant was revoked"
                    + (grant.revokeReason() == null ? "" : ": " + grant.revokeReason()));
        }
        if (grant.usedAt() != null) {
            throw new ConflictException("That emergency access grant has already been used. "
                    + "Request a new one, with its own case reference.");
        }
        if (!grant.expiresAt().isAfter(Instant.now())) {
            audit.recordWithReason("BREAK_GLASS_EXPIRED", "BREAK_GLASS_GRANT", id,
                    "Expired emergency access grant was presented and refused",
                    grant.justification(), Map.of("caseReference", grant.caseReference(),
                            "expiredAt", grant.expiresAt().toString()));
            throw new ConflictException("That emergency access grant expired at " + grant.expiresAt()
                    + ". Request a new one.");
        }
        jdbc.update("update identity.break_glass_grant set used_at = now() where tenant_id = ? and id = ?",
                principal.tenantId(), id);
        audit.recordWithReason("BREAK_GLASS_USE", "BREAK_GLASS_GRANT", id,
                "Emergency administrative access used under case " + grant.caseReference(),
                grant.justification(), Map.of("caseReference", grant.caseReference()));
        notifyTenantAdmins(principal.tenantId(), "Emergency access used",
                principal.email() + " used emergency administrative access under case " + grant.caseReference()
                        + ". Review the audit log for what was done.",
                grant.justification());
        return get(id);
    }

    @Transactional
    public void revoke(UUID id, String reason) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requirePlatform(principal.role());
        String cleaned = clean(reason);
        if (cleaned == null) {
            throw new IllegalArgumentException("Give a reason for revoking this emergency access grant.");
        }
        int updated = jdbc.update("""
                update identity.break_glass_grant
                set revoked_at = now(), revoke_reason = ?
                where tenant_id = ? and id = ? and revoked_at is null
                """, cleaned, principal.tenantId(), id);
        if (updated == 0) throw new NotFoundException("That emergency access grant is not open");
        audit.recordWithReason("BREAK_GLASS_REVOKE", "BREAK_GLASS_GRANT", id,
                "Emergency access grant revoked", cleaned, Map.of());
    }

    @Transactional(readOnly = true)
    public List<Grant> list() {
        return jdbc.query("""
                select id, actor_email, case_reference, justification, granted_at, expires_at,
                       used_at, revoked_at, revoke_reason
                from identity.break_glass_grant
                where tenant_id = ?
                order by granted_at desc
                limit 100
                """, (rs, i) -> mapGrant(rs), TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public Grant get(UUID id) {
        try {
            return jdbc.queryForObject("""
                    select id, actor_email, case_reference, justification, granted_at, expires_at,
                           used_at, revoked_at, revoke_reason
                    from identity.break_glass_grant
                    where tenant_id = ? and id = ?
                    """, (rs, i) -> mapGrant(rs), TenantContext.get().tenantId(), id);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("That emergency access grant does not exist in this workspace");
        }
    }

    /**
     * The single place a grant's state is decided, so the list view, the read and
     * the use-time refusal can never disagree about whether a grant is still open.
     * Visible for testing.
     */
    static String stateOf(Instant expiresAt, Instant usedAt, Instant revokedAt) {
        if (revokedAt != null) return "REVOKED";
        if (usedAt != null) return "USED";
        return expiresAt != null && expiresAt.isAfter(Instant.now()) ? "OPEN" : "EXPIRED";
    }

    private static Grant mapGrant(java.sql.ResultSet rs) throws java.sql.SQLException {
        Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
        Instant usedAt = rs.getTimestamp("used_at") == null ? null : rs.getTimestamp("used_at").toInstant();
        Instant revokedAt = rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant();
        String state = stateOf(expiresAt, usedAt, revokedAt);
        return new Grant(rs.getObject("id", UUID.class), rs.getString("actor_email"),
                rs.getString("case_reference"), rs.getString("justification"),
                rs.getTimestamp("granted_at").toInstant(), expiresAt, usedAt, revokedAt,
                rs.getString("revoke_reason"), state);
    }

    /**
     * Tells the tenant's own administrators. FR-TEN-012 requires notification of
     * <em>tenant</em> administrators, not just the audit channel: a control that
     * only informs the operator who used it is not a control.
     */
    private void notifyTenantAdmins(UUID tenantId, String title, String body, String reason) {
        List<UUID> admins = jdbc.queryForList("""
                select id from identity.app_user
                where tenant_id = ? and active = true and role in ('TENANT_ADMIN','OPERATIONS')
                """, UUID.class, tenantId);
        for (UUID admin : admins) {
            notifications.notifyUser(tenantId, admin, "SYSTEM", "URGENT", title, body,
                    "/security", reason, true);
        }
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}

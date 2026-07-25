package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.auth.JwtService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Support impersonation (FR-TEN-011).
 *
 * <p>Four rules, each of which exists because the obvious implementation gets it
 * wrong:
 *
 * <ol>
 *   <li><b>Tenant consent is required.</b> {@code platform.tenant.impersonation_consent}
 *       defaults to false. A support capability that is on by default is a
 *       capability the customer never agreed to.</li>
 *   <li><b>The token carries both identities.</b> The impersonated user is the
 *       principal — so tenant scoping, RLS and record ownership all behave exactly
 *       as they do for that user — while the operator rides along in
 *       {@code imp_uid}/{@code imp_email} claims and is written onto every audit
 *       event by {@link AuditService}.</li>
 *   <li><b>The operator cannot grant itself permissions.</b> Enforced by
 *       {@link #assertNotImpersonating}, called from the administration choke
 *       points, and by a path guard in the authentication filter.</li>
 *   <li><b>The session is visibly an impersonation.</b> {@code kind =
 *       'IMPERSONATION'} on the session row, surfaced in the sessions list and in
 *       {@code /auth/me}, so the UI can indicate it throughout.</li>
 * </ol>
 */
@Service
public class ImpersonationService {

    private final JdbcTemplate jdbc;
    private final JwtService jwtService;
    private final SessionService sessions;
    private final AuditService audit;

    public ImpersonationService(JdbcTemplate jdbc, JwtService jwtService, SessionService sessions,
                                AuditService audit) {
        this.jdbc = jdbc;
        this.jwtService = jwtService;
        this.sessions = sessions;
        this.audit = audit;
    }

    public record StartRequest(UUID userId, String caseReference, String reason) {}
    public record StartResult(String token, UUID impersonationId, String impersonatedEmail,
                              String impersonatedName, String role, Instant startedAt, String notice) {}
    public record ImpersonationRow(UUID id, String impersonatorEmail, String impersonatedEmail,
                                   String caseReference, String reason, Instant startedAt, Instant endedAt) {}

    @Transactional
    public StartResult start(StartRequest request) {
        TenantContext.Principal operator = TenantContext.get();
        CrmRole.requirePlatform(operator.role());
        if (CrmRole.current(operator.role()).readOnly()) {
            throw new ForbiddenException("A read-only platform role cannot impersonate a user");
        }
        if (TenantContext.isImpersonating()) {
            throw new ConflictException("You are already impersonating a user. End that session first.");
        }
        String caseRef = clean(request.caseReference());
        String reason = clean(request.reason());
        if (caseRef == null) {
            throw new IllegalArgumentException("Give the support case reference this impersonation is for.");
        }
        if (reason == null || reason.length() < 10) {
            throw new IllegalArgumentException(
                    "Give a reason of at least 10 characters describing what you need to reproduce.");
        }
        Boolean consented = jdbc.queryForObject(
                "select impersonation_consent from platform.tenant where id = ?",
                Boolean.class, operator.tenantId());
        if (!Boolean.TRUE.equals(consented)) {
            audit.recordWithReason("IMPERSONATION_REFUSED", "TENANT", operator.tenantId(),
                    "Impersonation refused: this workspace has not consented", reason,
                    Map.of("caseReference", caseRef));
            throw new ForbiddenException("This workspace has not consented to support impersonation. "
                    + "Ask a workspace administrator to enable it before trying again.");
        }
        Map<String, Object> target;
        try {
            target = jdbc.queryForMap("""
                    select id, email, display_name, role from identity.app_user
                    where tenant_id = ? and id = ? and active = true
                    """, operator.tenantId(), request.userId());
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("That user does not exist or is not active in this workspace");
        }
        UUID impersonationId = UUID.randomUUID();
        jdbc.update("""
                insert into identity.impersonation_session
                  (id, tenant_id, impersonator_id, impersonator_email, impersonated_user_id,
                   impersonated_email, case_reference, reason)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, impersonationId, operator.tenantId(), operator.userId(), operator.email(),
                target.get("id"), target.get("email"), caseRef, reason);

        UUID targetId = (UUID) target.get("id");
        String targetEmail = (String) target.get("email");
        String targetName = (String) target.get("display_name");
        String targetRole = (String) target.get("role");
        JwtService.IssuedToken issued = jwtService.issueAccessToken(operator.tenantId(), targetId,
                targetRole, targetName, targetEmail, false,
                operator.userId(), operator.email(), operator.displayName());
        sessions.createSession(operator.tenantId(), targetId, false, targetEmail, targetName, targetRole,
                issued.jti(), "IMPERSONATION", issued.issuedAt(), issued.expiresAt(),
                TenantContext.clientIp(), "impersonation:" + operator.email(),
                operator.userId(), operator.email());
        audit.recordWithReason("IMPERSONATION_START", "APP_USER", targetId,
                operator.email() + " started acting as " + targetEmail, reason,
                Map.of("caseReference", caseRef, "impersonationId", impersonationId.toString(),
                        "impersonatedEmail", targetEmail));
        return new StartResult(issued.token(), impersonationId, targetEmail, targetName, targetRole,
                Instant.now(),
                "You are acting as " + targetEmail + ". Every action is recorded against both identities. "
                        + "Permission and role changes are refused while impersonating.");
    }

    @Transactional
    public void stop(UUID impersonationId, String reason) {
        TenantContext.Principal principal = TenantContext.get();
        TenantContext.Impersonator operator = TenantContext.impersonator();
        if (operator == null) {
            throw new ConflictException("This session is not an impersonation");
        }
        int updated = jdbc.update("""
                update identity.impersonation_session set ended_at = now()
                where tenant_id = ? and id = ? and ended_at is null and impersonator_id = ?
                """, principal.tenantId(), impersonationId, operator.userId());
        if (updated == 0) {
            throw new NotFoundException("That impersonation is not open");
        }
        String jti = TenantContext.sessionJti();
        if (jti != null) {
            jdbc.update("""
                    update identity.user_session
                    set revoked_at = now(), revoked_by = ?, revoke_reason = ?
                    where tenant_id = ? and jti = ? and revoked_at is null
                    """, operator.userId(), "Impersonation ended", principal.tenantId(), jti);
        }
        audit.recordWithReason("IMPERSONATION_STOP", "APP_USER", principal.userId(),
                operator.email() + " stopped acting as " + principal.email(), reason,
                Map.of("impersonationId", impersonationId.toString()));
    }

    @Transactional(readOnly = true)
    public List<ImpersonationRow> list() {
        return jdbc.query("""
                select id, impersonator_email, impersonated_email, case_reference, reason,
                       started_at, ended_at
                from identity.impersonation_session
                where tenant_id = ?
                order by started_at desc
                limit 100
                """, (rs, i) -> new ImpersonationRow(
                rs.getObject("id", UUID.class), rs.getString("impersonator_email"),
                rs.getString("impersonated_email"), rs.getString("case_reference"),
                rs.getString("reason"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant()),
                TenantContext.get().tenantId());
    }

    /**
     * The self-escalation guard. Called from the administration choke points so a
     * new admin endpoint inherits it rather than having to remember it.
     *
     * @throws ForbiddenException when the current request is an impersonation
     */
    public static void assertNotImpersonating(String action) {
        TenantContext.Impersonator operator = TenantContext.impersonator();
        if (operator != null) {
            throw new ForbiddenException(action + " is not permitted while impersonating a user. "
                    + "End the impersonation and act as yourself, so the change is attributed to your own identity.");
        }
    }

    /** Sets or clears a workspace's consent to support impersonation. */
    @Transactional
    public boolean setConsent(boolean consented, String reason) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole role = CrmRole.current(principal.role());
        if (role != CrmRole.TENANT_ADMIN && role != CrmRole.SUPER_ADMIN) {
            throw new ForbiddenException("Changing the impersonation consent setting requires "
                    + "Super Admin or Tenant Admin");
        }
        assertNotImpersonating("Changing the impersonation consent setting");
        jdbc.update("update platform.tenant set impersonation_consent = ? where id = ?",
                consented, principal.tenantId());
        audit.recordWithReason("IMPERSONATION_CONSENT", "TENANT", principal.tenantId(),
                consented ? "Support impersonation consent granted" : "Support impersonation consent withdrawn",
                reason, Map.of("consented", consented));
        return consented;
    }

    @Transactional(readOnly = true)
    public boolean consent() {
        Boolean consented = jdbc.queryForObject(
                "select impersonation_consent from platform.tenant where id = ?",
                Boolean.class, TenantContext.get().tenantId());
        return Boolean.TRUE.equals(consented);
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}

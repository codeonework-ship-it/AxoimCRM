package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
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
 * Server-side session state (FR-TEN-010).
 *
 * <p>The point of this class is that revocation is <em>effective</em>. A JWT is
 * self-contained and cannot be un-issued, so an administrator revoking a session
 * would otherwise have to wait for the token to expire — up to eight hours in
 * this deployment. Instead every access token carries a {@code jti} matching a
 * row here, and {@code JwtAuthFilter} probes {@link #validate} on every request:
 * a revoked or expired row refuses the token immediately.
 *
 * <p>That probe is on the hot path for every authenticated request, so it is one
 * indexed lookup on {@code (tenant_id, jti)} joined to the tenant row and the
 * session policy, plus a heartbeat write that is skipped unless the last one is
 * stale.
 */
@Service
public class SessionService {

    /** Don't rewrite last_seen_at on every request; a stale-enough heartbeat is enough for idle timeout. */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public SessionService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Why a token was refused, so the filter can say something actionable. */
    public enum Verdict { VALID, UNKNOWN, REVOKED, EXPIRED, IDLE_TIMEOUT }

    public record SessionState(Verdict verdict, UUID sessionId, String tenantStatus,
                               Instant stepUpAt, int stepUpMaxAgeSeconds) {
        public boolean valid() { return verdict == Verdict.VALID; }
    }

    public record SessionRow(UUID id, UUID userId, String subjectEmail, String subjectName, String role,
                             String kind, boolean platformUser, String impersonatorEmail,
                             Instant issuedAt, Instant expiresAt, Instant lastSeenAt,
                             String ip, String userAgent, Instant stepUpAt,
                             Instant revokedAt, String revokeReason, boolean current) {}

    public record SessionPolicy(int absoluteLifetimeMinutes, int idleTimeoutMinutes,
                                int maxConcurrentSessions, String concurrentStrategy,
                                int stepUpMaxAgeSeconds) {}

    // ------------------------------------------------------------------
    // Creation
    // ------------------------------------------------------------------

    /**
     * Records the session backing a freshly issued token. Called inside the login
     * transaction, which has already bound {@code app.tenant_id}.
     *
     * @return a message when the concurrent-session policy ended another session,
     *         so the user can be told which policy applied (US-E01-08); null otherwise
     */
    @Transactional
    public String createSession(UUID tenantId, UUID userId, boolean platformUser, String email,
                                String displayName, String role, String jti, String kind,
                                Instant issuedAt, Instant expiresAt, String ip, String userAgent,
                                UUID impersonatorId, String impersonatorEmail) {
        // A session is created on paths where no principal is bound yet — interactive
        // sign-in, MFA completion and the service-credential exchange. Bind explicitly
        // rather than relying on TenantSessionAspect, which has nothing to read.
        bind(tenantId);
        SessionPolicy policy = policy(tenantId);
        bind(tenantId);
        String concurrencyNote = enforceConcurrencyLimit(tenantId, userId, policy);
        // The absolute lifetime is a tenant policy and may be shorter than the
        // token TTL; the session, not the token, is the authority.
        Instant absolute = issuedAt.plus(Duration.ofMinutes(policy.absoluteLifetimeMinutes()));
        Instant effectiveExpiry = absolute.isBefore(expiresAt) ? absolute : expiresAt;
        jdbc.update("""
                insert into identity.user_session
                  (tenant_id, user_id, platform_user, subject_email, subject_name, role, jti, kind,
                   impersonator_id, impersonator_email, issued_at, expires_at, last_seen_at, ip, user_agent)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, userId, platformUser, email, displayName, role, jti, kind,
                impersonatorId, impersonatorEmail, Timestamp.from(issuedAt), Timestamp.from(effectiveExpiry),
                Timestamp.from(issuedAt), ip, userAgent);
        return concurrencyNote;
    }

    /**
     * Applies the tenant's concurrent-session policy. {@code END_OLDEST} revokes
     * the oldest sessions until there is room; {@code REFUSE_NEW} rejects the new
     * sign-in. Either way the user is told which rule applied rather than being
     * silently logged out somewhere else.
     */
    private String enforceConcurrencyLimit(UUID tenantId, UUID userId, SessionPolicy policy) {
        List<UUID> live = jdbc.queryForList("""
                select id from identity.user_session
                where tenant_id = ? and user_id = ? and revoked_at is null and expires_at > now()
                order by issued_at asc
                """, UUID.class, tenantId, userId);
        int excess = live.size() - (policy.maxConcurrentSessions() - 1);
        if (excess <= 0) return null;
        if ("REFUSE_NEW".equals(policy.concurrentStrategy())) {
            throw new ForbiddenException("You already have " + live.size() + " active sessions, which is this "
                    + "workspace's limit of " + policy.maxConcurrentSessions() + ". Sign out of another device, "
                    + "or ask an administrator to revoke a session, then try again.");
        }
        List<UUID> toEnd = live.subList(0, Math.min(excess, live.size()));
        for (UUID id : toEnd) {
            jdbc.update("""
                    update identity.user_session
                    set revoked_at = now(), revoked_by = ?, revoke_reason = ?
                    where tenant_id = ? and id = ? and revoked_at is null
                    """, userId, "Concurrent-session limit of " + policy.maxConcurrentSessions()
                    + " reached; oldest session ended", tenantId, id);
        }
        return "Your oldest " + toEnd.size() + " session(s) were ended because this workspace allows "
                + policy.maxConcurrentSessions() + " concurrent sessions.";
    }

    // ------------------------------------------------------------------
    // Validation — the hot path
    // ------------------------------------------------------------------

    /**
     * Single indexed probe used by the authentication filter. Also returns the
     * tenant lifecycle status, because the suspended-tenant write block
     * (FR-TEN-002) needs it on exactly the same request and a second round trip
     * for it would be wasteful.
     */
    @Transactional
    public SessionState validate(UUID tenantId, String jti) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    select s.id, s.revoked_at, s.expires_at, s.last_seen_at, s.step_up_at,
                           t.status as tenant_status,
                           coalesce(p.idle_timeout_minutes, 120) as idle_minutes,
                           coalesce(p.step_up_max_age_seconds, 300) as step_up_seconds
                    from identity.user_session s
                    join platform.tenant t on t.id = s.tenant_id
                    left join identity.session_policy p on p.tenant_id = s.tenant_id
                    where s.tenant_id = ? and s.jti = ?
                    """, tenantId, jti);
        } catch (EmptyResultDataAccessException e) {
            return new SessionState(Verdict.UNKNOWN, null, null, null, 300);
        }
        UUID id = (UUID) row.get("id");
        String tenantStatus = (String) row.get("tenant_status");
        int stepUpSeconds = ((Number) row.get("step_up_seconds")).intValue();
        Instant stepUpAt = instant(row.get("step_up_at"));
        if (row.get("revoked_at") != null) {
            return new SessionState(Verdict.REVOKED, id, tenantStatus, stepUpAt, stepUpSeconds);
        }
        Instant expiresAt = instant(row.get("expires_at"));
        Instant now = Instant.now();
        if (expiresAt != null && expiresAt.isBefore(now)) {
            return new SessionState(Verdict.EXPIRED, id, tenantStatus, stepUpAt, stepUpSeconds);
        }
        Instant lastSeen = instant(row.get("last_seen_at"));
        int idleMinutes = ((Number) row.get("idle_minutes")).intValue();
        if (lastSeen != null && lastSeen.plus(Duration.ofMinutes(idleMinutes)).isBefore(now)) {
            jdbc.update("""
                    update identity.user_session
                    set revoked_at = now(), revoke_reason = ?
                    where tenant_id = ? and id = ? and revoked_at is null
                    """, "Idle timeout of " + idleMinutes + " minutes elapsed", tenantId, id);
            return new SessionState(Verdict.IDLE_TIMEOUT, id, tenantStatus, stepUpAt, stepUpSeconds);
        }
        if (lastSeen == null || lastSeen.plus(HEARTBEAT_INTERVAL).isBefore(now)) {
            jdbc.update("update identity.user_session set last_seen_at = now() where tenant_id = ? and id = ?",
                    tenantId, id);
        }
        return new SessionState(Verdict.VALID, id, tenantStatus, stepUpAt, stepUpSeconds);
    }

    // ------------------------------------------------------------------
    // Administration
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SessionRow> list(boolean includeEnded) {
        TenantContext.Principal principal = TenantContext.get();
        String currentJti = TenantContext.sessionJti();
        return jdbc.query("""
                select id, user_id, subject_email, subject_name, role, kind, platform_user,
                       impersonator_email, issued_at, expires_at, last_seen_at, ip, user_agent,
                       step_up_at, revoked_at, revoke_reason, jti
                from identity.user_session
                where tenant_id = ?
                  and (? or (revoked_at is null and expires_at > now()))
                order by last_seen_at desc
                limit 200
                """, (rs, i) -> new SessionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("subject_email"),
                rs.getString("subject_name"),
                rs.getString("role"),
                rs.getString("kind"),
                rs.getBoolean("platform_user"),
                rs.getString("impersonator_email"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                rs.getString("ip"),
                rs.getString("user_agent"),
                rs.getTimestamp("step_up_at") == null ? null : rs.getTimestamp("step_up_at").toInstant(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                rs.getString("revoke_reason"),
                currentJti != null && currentJti.equals(rs.getString("jti"))
        ), principal.tenantId(), includeEnded);
    }

    @Transactional
    public void revoke(UUID sessionId, String reason) {
        requireSessionAdmin();
        String cleanedReason = requireReason(reason);
        TenantContext.Principal principal = TenantContext.get();
        Map<String, Object> target;
        try {
            target = jdbc.queryForMap("""
                    select subject_email, user_id from identity.user_session
                    where tenant_id = ? and id = ?
                    """, principal.tenantId(), sessionId);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("That session no longer exists in this workspace");
        }
        int updated = jdbc.update("""
                update identity.user_session
                set revoked_at = now(), revoked_by = ?, revoke_reason = ?
                where tenant_id = ? and id = ? and revoked_at is null
                """, principal.userId(), cleanedReason, principal.tenantId(), sessionId);
        if (updated == 0) {
            throw new NotFoundException("That session was already ended");
        }
        audit.recordWithReason("SESSION_REVOKE", "USER_SESSION", sessionId,
                "Session revoked for " + target.get("subject_email"), cleanedReason,
                Map.of("sessionId", sessionId.toString(), "subject", String.valueOf(target.get("subject_email"))));
    }

    @Transactional
    public int revokeAllForUser(UUID userId, String reason) {
        requireSessionAdmin();
        String cleanedReason = requireReason(reason);
        TenantContext.Principal principal = TenantContext.get();
        int updated = jdbc.update("""
                update identity.user_session
                set revoked_at = now(), revoked_by = ?, revoke_reason = ?
                where tenant_id = ? and user_id = ? and revoked_at is null
                """, principal.userId(), cleanedReason, principal.tenantId(), userId);
        audit.recordWithReason("SESSION_REVOKE_ALL", "APP_USER", userId,
                "All sessions revoked for one user (" + updated + " ended)", cleanedReason,
                Map.of("userId", userId.toString(), "endedCount", updated));
        return updated;
    }

    /** Used by SCIM deprovisioning and by user deactivation; no interactive actor required. */
    @Transactional
    public int revokeAllForUserSystem(UUID tenantId, UUID userId, String reason) {
        return jdbc.update("""
                update identity.user_session
                set revoked_at = now(), revoke_reason = ?
                where tenant_id = ? and user_id = ? and revoked_at is null
                """, reason, tenantId, userId);
    }

    @Transactional(readOnly = true)
    public SessionPolicy policy(UUID tenantId) {
        bind(tenantId);
        try {
            return jdbc.queryForObject("""
                    select absolute_lifetime_minutes, idle_timeout_minutes, max_concurrent_sessions,
                           concurrent_strategy, step_up_max_age_seconds
                    from identity.session_policy where tenant_id = ?
                    """, (rs, i) -> new SessionPolicy(
                    rs.getInt("absolute_lifetime_minutes"),
                    rs.getInt("idle_timeout_minutes"),
                    rs.getInt("max_concurrent_sessions"),
                    rs.getString("concurrent_strategy"),
                    rs.getInt("step_up_max_age_seconds")), tenantId);
        } catch (EmptyResultDataAccessException e) {
            // A tenant provisioned before V11 (or mid-provisioning) has no row yet.
            return new SessionPolicy(480, 120, 5, "END_OLDEST", 300);
        }
    }

    /**
     * SET LOCAL of the RLS session variable. Note the failure mode this prevents:
     * {@code current_setting('app.tenant_id', true)} returns an empty string — not
     * NULL — once any earlier transaction on the pooled connection has set it, and
     * {@code ''::uuid} raises rather than yielding NULL. An unbound path therefore
     * fails loudly instead of returning zero rows, so every path that can run
     * without a principal binds the tenant it already knows.
     */
    private void bind(UUID tenantId) {
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
    }

    private static Instant instant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant();
    }

    private static String requireReason(String reason) {
        String cleaned = reason == null ? "" : reason.trim();
        if (cleaned.length() < 5) {
            throw new IllegalArgumentException(
                    "Give a reason of at least 5 characters for ending this session — it is recorded in the audit log.");
        }
        return cleaned;
    }

    private static void requireSessionAdmin() {
        CrmRole role = CrmRole.current(TenantContext.get().role());
        if (role != CrmRole.SUPER_ADMIN && role != CrmRole.TENANT_ADMIN) {
            throw new ForbiddenException("Ending someone else's session requires Super Admin or Tenant Admin");
        }
    }
}

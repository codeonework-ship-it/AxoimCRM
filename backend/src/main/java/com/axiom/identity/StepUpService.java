package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.audit.IndependentAuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.common.UnauthorizedException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Step-up authentication for controlled actions (FR-TEN-009).
 *
 * <p>Freshness is a property of the <em>session</em>, not the token: a token
 * issued eight hours ago is still valid, and that is precisely why a privileged
 * action needs a separate, recent proof of presence. {@code step_up_at} on the
 * session row is that proof, and {@link #requireStepUp} compares it to the
 * tenant's configured window on every controlled action.
 *
 * <p>A refusal is audited through {@link IndependentAuditService} so the record
 * survives the rollback caused by refusing the action.
 */
@Service
public class StepUpService {

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditService audit;
    private final IndependentAuditService independentAudit;
    private final MfaService mfa;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public StepUpService(JdbcTemplate jdbc, SessionService sessions, AuditService audit,
                         IndependentAuditService independentAudit, MfaService mfa) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
        this.independentAudit = independentAudit;
        this.mfa = mfa;
    }

    public record StepUpResult(Instant steppedUpAt, int validForSeconds) {}

    /**
     * Refuses the current request unless this session re-authenticated inside the
     * tenant's configured freshness window.
     *
     * @param action a business description of what is being attempted, used in
     *               both the refusal message and the audit record
     */
    @Transactional
    public void requireStepUp(String action) {
        requireStepUp(action, sessions.policy(TenantContext.get().tenantId()).stepUpMaxAgeSeconds());
    }

    @Transactional
    public void requireStepUp(String action, int maxAgeSeconds) {
        String jti = TenantContext.sessionJti();
        if (jti == null) {
            // No server-side session backs this request (a service credential, or
            // a token minted before session governance existed). A controlled
            // action cannot be proven fresh, so it is refused rather than allowed.
            refuse(action, maxAgeSeconds, "no interactive session");
        }
        Instant stepUpAt = stepUpAt(jti);
        if (stepUpAt == null || stepUpAt.plus(Duration.ofSeconds(maxAgeSeconds)).isBefore(Instant.now())) {
            refuse(action, maxAgeSeconds, stepUpAt == null ? "never" : stepUpAt.toString());
        }
    }

    /** @return true when the current session is already fresh, without refusing. */
    @Transactional(readOnly = true)
    public boolean isFresh(int maxAgeSeconds) {
        String jti = TenantContext.sessionJti();
        if (jti == null) return false;
        Instant stepUpAt = stepUpAt(jti);
        return stepUpAt != null && !stepUpAt.plus(Duration.ofSeconds(maxAgeSeconds)).isBefore(Instant.now());
    }

    /**
     * Re-authenticates the signed-in user and stamps the session as fresh. The
     * password is checked against the same hash used at sign-in; when the user has
     * an active second factor, a valid code is required too — otherwise step-up
     * would be weaker than the sign-in it is meant to reinforce.
     */
    @Transactional
    public StepUpResult stepUp(String password, String totpCode) {
        TenantContext.Principal principal = TenantContext.get();
        String jti = TenantContext.sessionJti();
        if (jti == null) {
            throw new ForbiddenException("This request has no active session to re-authenticate");
        }
        String hash = passwordHash(principal);
        if (hash == null || !bcrypt.matches(password, hash)) {
            independentAudit.record("STEP_UP_FAILED", "APP_USER", principal.userId(),
                    "Step-up re-authentication failed", "Password did not match",
                    Map.of("reason", "BAD_PASSWORD"));
            throw new UnauthorizedException("That password is not correct. The action was not performed.");
        }
        if (mfa.isActiveFor(principal.tenantId(), principal.userId()) && !mfa.verifyCode(
                principal.tenantId(), principal.userId(), totpCode)) {
            independentAudit.record("STEP_UP_FAILED", "APP_USER", principal.userId(),
                    "Step-up re-authentication failed", "Second-factor code did not match",
                    Map.of("reason", "BAD_MFA_CODE"));
            throw new UnauthorizedException(
                    "That authenticator code is not valid. Open your authenticator app and enter the current code.");
        }
        Instant now = Instant.now();
        jdbc.update("update identity.user_session set step_up_at = ? where tenant_id = ? and jti = ?",
                Timestamp.from(now), principal.tenantId(), jti);
        int window = sessions.policy(principal.tenantId()).stepUpMaxAgeSeconds();
        audit.record("STEP_UP", "USER_SESSION", null,
                "Re-authenticated for a controlled action",
                Map.of("validForSeconds", window));
        return new StepUpResult(now, window);
    }

    private Instant stepUpAt(String jti) {
        try {
            Timestamp stamp = jdbc.queryForObject(
                    "select step_up_at from identity.user_session where tenant_id = ? and jti = ?",
                    Timestamp.class, TenantContext.get().tenantId(), jti);
            return stamp == null ? null : stamp.toInstant();
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private String passwordHash(TenantContext.Principal principal) {
        try {
            return jdbc.queryForObject("""
                    select password_hash from identity.app_user
                    where tenant_id = ? and id = ? and active = true
                    """, String.class, principal.tenantId(), principal.userId());
        } catch (EmptyResultDataAccessException e) {
            // Platform operators live in platform.platform_user, which has no RLS.
            try {
                return jdbc.queryForObject(
                        "select password_hash from platform.platform_user where id = ? and active = true",
                        String.class, principal.userId());
            } catch (EmptyResultDataAccessException inner) {
                return null;
            }
        }
    }

    private void refuse(String action, int maxAgeSeconds, String lastStepUp) {
        independentAudit.record("STEP_UP_REQUIRED", "USER_SESSION", null,
                "Controlled action refused for want of a fresh re-authentication: " + action,
                "Step-up older than " + maxAgeSeconds + " seconds",
                Map.of("action", action, "maxAgeSeconds", maxAgeSeconds, "lastStepUpAt", lastStepUp));
        throw new ForbiddenException(action + " needs you to confirm your password first. "
                + "Re-authenticate at POST /api/v1/auth/step-up, then repeat the request within "
                + maxAgeSeconds + " seconds.");
    }
}

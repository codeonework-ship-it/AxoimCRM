package com.axiom.auth;

import com.axiom.common.ForbiddenException;
import com.axiom.common.UnauthorizedException;
import com.axiom.identity.MfaService;
import com.axiom.identity.NetworkRuleService;
import com.axiom.identity.PasswordPolicyService;
import com.axiom.identity.SessionService;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authenticates tenant users and platform users. Platform identities never bypass
 * RLS: selecting another tenant issues a newly signed JWT carrying that tenant id,
 * after which the normal tenant session binding applies.
 *
 * <p>Sign-in now runs the controls E01 requires, in this order:
 * network restriction (FR-TEN-014) → credential check (FR-TEN-003) → password
 * expiry (FR-TEN-003) → second factor (FR-TEN-008) → server-side session
 * creation (FR-TEN-010). Progressive delay, lockout and attempt recording live in
 * {@link PasswordPolicyService} and are driven by {@link AuthController}, which is
 * outside the transaction that a failed sign-in rolls back.
 */
@Service
public class AuthService {

    /** How long the intermediate token from a successful password step is valid. */
    private static final Duration MFA_CHALLENGE_TTL = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final JwtService jwtService;
    private final SessionService sessions;
    private final MfaService mfa;
    private final NetworkRuleService networkRules;
    private final PasswordPolicyService passwordPolicy;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public AuthService(JdbcTemplate jdbc, JwtService jwtService, SessionService sessions, MfaService mfa,
                       NetworkRuleService networkRules, PasswordPolicyService passwordPolicy) {
        this.jdbc = jdbc;
        this.jwtService = jwtService;
        this.sessions = sessions;
        this.mfa = mfa;
        this.networkRules = networkRules;
        this.passwordPolicy = passwordPolicy;
    }

    public record AuthResult(String token, UUID userId, String displayName, String email,
                             String role, boolean platformUser, UUID tenantId,
                             String tenantSlug, String tenantName,
                             boolean mfaRequired, String mfaChallengeToken, String notice) {}

    public record TenantOption(UUID id, String slug, String name) {}

    /** Thrown by the login path so the controller can record the right outcome. */
    public static class LoginOutcome extends RuntimeException {
        private final String outcome;
        private final UUID tenantId;
        private final UUID userId;
        private final RuntimeException cause;

        LoginOutcome(String outcome, UUID tenantId, UUID userId, RuntimeException cause) {
            super(cause.getMessage(), cause);
            this.outcome = outcome;
            this.tenantId = tenantId;
            this.userId = userId;
            this.cause = cause;
        }

        public String outcome() { return outcome; }
        public UUID tenantId() { return tenantId; }
        public UUID userId() { return userId; }
        public RuntimeException unwrap() { return cause; }
    }

    /**
     * Resolves a workspace slug to its id for the pre-authentication throttle read.
     * Returns null for an unknown slug so the caller stays on the same refusal path
     * as a bad credential.
     */
    @Transactional(readOnly = true)
    public UUID tenantIdForSlug(String tenantSlug) {
        try {
            return jdbc.queryForObject("select id from tenant where lower(slug) = lower(?)",
                    UUID.class, tenantSlug);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Transactional
    public AuthResult login(String tenantSlug, String email, String password, String ip, String userAgent) {
        Map<String, Object> tenant = signInTenant(tenantSlug);
        UUID tenantId = (UUID) tenant.get("id");
        String status = (String) tenant.get("status");
        if ("terminated".equals(status)) {
            throw new LoginOutcome("TENANT_UNAVAILABLE", tenantId, null,
                    new UnauthorizedException("This workspace has been closed. Contact your administrator."));
        }

        Map<String, Object> platform = optionalPlatformUser(email);
        if (platform != null && bcrypt.matches(password, (String) platform.get("password_hash"))) {
            // A platform operator is deliberately NOT subject to a tenant's own IP
            // allowlist: a customer misconfiguring theirs must not be able to lock
            // support out of every workspace, which is the path that fixes it.
            return result(platform, true, tenant, ip, userAgent, null);
        }

        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());

        if (!networkRules.isPermitted(tenantId, ip)) {
            throw new LoginOutcome("BLOCKED_NETWORK", tenantId, null, new ForbiddenException(
                    "Sign-in from this network is not permitted for this workspace. "
                            + "Connect through an approved network, or ask an administrator to add "
                            + (ip == null ? "your address" : ip) + " to the allowed ranges."));
        }
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());

        Map<String, Object> user;
        try {
            user = jdbc.queryForMap(
                    "select id, email, password_hash, display_name, role, password_changed_at, must_change_password "
                            + "from app_user where tenant_id = ? and lower(email) = lower(?) and active = true",
                    tenantId, email);
        } catch (EmptyResultDataAccessException e) {
            // Identical refusal whether or not the address exists (FR-TEN-003).
            throw new LoginOutcome("BAD_CREDENTIALS", tenantId, null,
                    new UnauthorizedException("Invalid credentials"));
        }
        if (!bcrypt.matches(password, (String) user.get("password_hash"))) {
            throw new LoginOutcome("BAD_CREDENTIALS", tenantId, (UUID) user.get("id"),
                    new UnauthorizedException("Invalid credentials"));
        }

        PasswordPolicyService.Policy policy = passwordPolicy.policy(tenantId);
        java.sql.Timestamp changedAt = (java.sql.Timestamp) user.get("password_changed_at");
        if (passwordPolicy.isExpired(policy, changedAt == null ? null : changedAt.toInstant())) {
            throw new LoginOutcome("PASSWORD_EXPIRED", tenantId, (UUID) user.get("id"),
                    new UnauthorizedException("Your password has expired after " + policy.expiryDays()
                            + " days. Ask an administrator to reset it before signing in again."));
        }
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());

        UUID userId = (UUID) user.get("id");
        String role = (String) user.get("role");
        if (mfa.isActiveFor(tenantId, userId)) {
            // The password step succeeded, so issue a token that can do exactly one
            // thing: complete the second factor.
            String challenge = jwtService.issueMfaChallenge(tenantId, userId,
                    (String) user.get("email"), MFA_CHALLENGE_TTL);
            return new AuthResult(null, userId, (String) user.get("display_name"),
                    (String) user.get("email"), role, false, tenantId,
                    (String) tenant.get("slug"), (String) tenant.get("name"),
                    true, challenge,
                    "Enter the 6-digit code from your authenticator app, or one of your recovery codes.");
        }
        if (mfa.isRequiredForRole(tenantId, role)) {
            // Policy demands a factor this user has not enrolled. Let them in far
            // enough to enrol and no further: refusing outright would need an
            // out-of-band enrolment path that does not exist.
            jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
            return result(user, false, tenant, ip, userAgent,
                    "This workspace requires a second factor for the " + role
                            + " role. Set up your authenticator app now — go to Sessions & Security.");
        }
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
        return result(user, false, tenant, ip, userAgent,
                Boolean.TRUE.equals(user.get("must_change_password"))
                        ? "Your administrator has asked you to change your password." : null);
    }

    /** Completes a sign-in whose password step already succeeded (FR-TEN-008). */
    @Transactional
    public AuthResult completeMfa(UUID tenantId, UUID userId, String code, String recoveryCode,
                                  String ip, String userAgent) {
        Map<String, Object> tenant = signInTenant(tenantIdSlug(tenantId));
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
        Map<String, Object> user;
        try {
            user = jdbc.queryForMap("""
                    select id, email, password_hash, display_name, role from app_user
                    where tenant_id = ? and id = ? and active = true
                    """, tenantId, userId);
        } catch (EmptyResultDataAccessException e) {
            throw new UnauthorizedException("This account is no longer active");
        }
        boolean verified;
        String notice = null;
        if (recoveryCode != null && !recoveryCode.isBlank()) {
            verified = mfa.consumeRecoveryCode(tenantId, userId, recoveryCode);
            if (verified) {
                notice = "That recovery code has now been used and will not work again. "
                        + "Generate a new set from Sessions & Security.";
            }
        } else {
            verified = mfa.verifyCode(tenantId, userId, code);
        }
        if (!verified) {
            throw new LoginOutcome("MFA_FAILED", tenantId, userId, new UnauthorizedException(
                    "That code is not valid. Enter the current 6-digit code from your authenticator app, "
                            + "or one of your unused recovery codes."));
        }
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
        return result(user, false, tenant, ip, userAgent, notice);
    }

    @Transactional(readOnly = true)
    public List<TenantOption> tenants() {
        TenantContext.Principal principal = TenantContext.get();
        if (!CrmRole.current(principal.role()).platform()) {
            return jdbc.query("select id, slug, name from tenant where id = ? and status = 'active'",
                    (rs, i) -> new TenantOption(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name")),
                    principal.tenantId());
        }
        return jdbc.query("select id, slug, name from tenant where status = 'active' order by name",
                (rs, i) -> new TenantOption(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name")));
    }

    @Transactional
    public AuthResult switchTenant(String tenantSlug, String ip, String userAgent) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requirePlatform(principal.role());
        Map<String, Object> tenant = activeTenant(tenantSlug);
        Map<String, Object> platform = optionalPlatformUser(principal.email());
        if (platform == null || !platform.get("id").equals(principal.userId())) {
            throw new UnauthorizedException("Platform identity is no longer active");
        }
        return result(platform, true, tenant, ip, userAgent, null);
    }

    /** Ends the session backing the current request, so the token stops working. */
    @Transactional
    public void logout() {
        String jti = TenantContext.sessionJti();
        if (jti == null) return;
        TenantContext.Principal principal = TenantContext.get();
        jdbc.update("""
                update identity.user_session
                set revoked_at = now(), revoked_by = ?, revoke_reason = 'Signed out'
                where tenant_id = ? and jti = ? and revoked_at is null
                """, principal.userId(), principal.tenantId(), jti);
    }

    private Map<String, Object> activeTenant(String tenantSlug) {
        try {
            return jdbc.queryForMap(
                    "select id, slug, name, status from tenant where lower(slug) = lower(?) and status = 'active'",
                    tenantSlug);
        } catch (EmptyResultDataAccessException e) {
            throw new UnauthorizedException("Invalid credentials");
        }
    }

    /**
     * A suspended or terminating workspace still permits sign-in — FR-TEN-002
     * requires administrator access and data export to keep working. The write
     * block is enforced centrally in {@link JwtAuthFilter}, not by refusing the
     * credential here.
     */
    private Map<String, Object> signInTenant(String tenantSlug) {
        try {
            return jdbc.queryForMap("""
                    select id, slug, name, status from tenant
                    where lower(slug) = lower(?) and status in ('active','suspended','terminating')
                    """, tenantSlug);
        } catch (EmptyResultDataAccessException e) {
            throw new UnauthorizedException("Invalid credentials");
        }
    }

    private String tenantIdSlug(UUID tenantId) {
        return jdbc.queryForObject("select slug from tenant where id = ?", String.class, tenantId);
    }

    private Map<String, Object> optionalPlatformUser(String email) {
        try {
            return jdbc.queryForMap(
                    "select id, email, password_hash, display_name, role from platform_user "
                            + "where lower(email) = lower(?) and active = true", email);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private AuthResult result(Map<String, Object> user, boolean platform, Map<String, Object> tenant,
                              String ip, String userAgent, String notice) {
        UUID userId = (UUID) user.get("id");
        String email = (String) user.get("email");
        String role = (String) user.get("role");
        String displayName = (String) user.get("display_name");
        UUID tenantId = (UUID) tenant.get("id");
        JwtService.IssuedToken issued = jwtService.issueAccessToken(tenantId, userId, role, displayName,
                email, platform, null, null, null);
        String concurrencyNotice = sessions.createSession(tenantId, userId, platform, email, displayName, role,
                issued.jti(), "INTERACTIVE", issued.issuedAt(), issued.expiresAt(), ip, userAgent, null, null);
        String combined = notice == null ? concurrencyNotice
                : concurrencyNotice == null ? notice : notice + " " + concurrencyNotice;
        String suspended = "suspended".equals(tenant.get("status")) || "terminating".equals(tenant.get("status"))
                ? "This workspace is " + tenant.get("status") + ": you can read and export data, "
                  + "but changes are blocked."
                : null;
        if (suspended != null) {
            combined = combined == null ? suspended : combined + " " + suspended;
        }
        return new AuthResult(issued.token(), userId, displayName, email, role, platform,
                tenantId, (String) tenant.get("slug"), (String) tenant.get("name"),
                false, null, combined);
    }
}

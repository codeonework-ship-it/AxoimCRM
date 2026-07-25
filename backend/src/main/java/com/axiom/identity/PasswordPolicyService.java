package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.common.UnauthorizedException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local-authentication policy: composition rules, reuse history, expiry, and the
 * progressive-delay-then-lockout response to repeated failures (FR-TEN-003).
 *
 * <p>Two decisions are worth stating.
 *
 * <p><b>Messages name what is missing.</b> "Password does not meet policy" is
 * useless — the user has no way to comply with it, so they try variations until
 * one sticks. Every refusal here lists the specific unmet rules, which is also
 * what the Definition of Done point 2 requires.
 *
 * <p><b>Failure counting is keyed on the submitted address, not on a user row.</b>
 * If lockout only applied to addresses that exist, the difference in behaviour
 * between "locked" and "wrong password" would reveal which addresses are real —
 * exactly the disclosure {@code FR-TEN-003}'s on-failure clause forbids.
 */
@Service
public class PasswordPolicyService {

    /**
     * A local denylist of the passwords that dominate every credential-stuffing
     * list. <b>Stated honestly:</b> this is not breached-password rejection. Real
     * coverage means checking a breach corpus — Have I Been Pwned's k-anonymity
     * range API is the usual choice — which is a live external dependency and is
     * therefore deferred with the rest of the vendor-dependent work. This list
     * stops the worst offenders and nothing more.
     */
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "password123", "passw0rd", "qwerty", "qwerty123",
            "123456", "1234567", "12345678", "123456789", "1234567890", "letmein",
            "welcome", "welcome1", "admin", "admin123", "iloveyou", "monkey",
            "abc123", "changeme", "trustno1", "sunshine", "princess", "dragon",
            "football", "baseball", "master", "shadow", "superman", "starwars");

    /** Delay applied per prior consecutive failure, capped so a request never hangs. */
    private static final long DELAY_STEP_MILLIS = 400;
    private static final long DELAY_CAP_MILLIS = 3_000;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final SessionService sessions;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public PasswordPolicyService(JdbcTemplate jdbc, AuditService audit, SessionService sessions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.sessions = sessions;
    }

    public record Policy(int minLength, boolean requireUpper, boolean requireLower, boolean requireDigit,
                         boolean requireSymbol, int historyCount, int expiryDays,
                         int maxFailedAttempts, int lockoutMinutes, boolean rejectBreached) {}

    /** What the login path must do before checking a credential. */
    public record ThrottleState(boolean lockedOut, long delayMillis, int consecutiveFailures,
                                Instant lockedUntil) {}

    public static final Policy DEFAULT_POLICY =
            new Policy(12, true, true, true, true, 5, 0, 5, 15, true);

    // ------------------------------------------------------------------
    // Policy read
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Policy policy(UUID tenantId) {
        bind(tenantId);
        try {
            return jdbc.queryForObject("""
                    select min_length, require_upper, require_lower, require_digit, require_symbol,
                           history_count, expiry_days, max_failed_attempts, lockout_minutes, reject_breached
                    from identity.password_policy where tenant_id = ?
                    """, (rs, i) -> new Policy(
                    rs.getInt("min_length"), rs.getBoolean("require_upper"), rs.getBoolean("require_lower"),
                    rs.getBoolean("require_digit"), rs.getBoolean("require_symbol"),
                    rs.getInt("history_count"), rs.getInt("expiry_days"),
                    rs.getInt("max_failed_attempts"), rs.getInt("lockout_minutes"),
                    rs.getBoolean("reject_breached")), tenantId);
        } catch (EmptyResultDataAccessException e) {
            return DEFAULT_POLICY;
        }
    }

    @Transactional
    public Policy updatePolicy(Policy requested) {
        TenantContext.Principal principal = TenantContext.get();
        com.axiom.auth.CrmRole role = com.axiom.auth.CrmRole.current(principal.role());
        if (role != com.axiom.auth.CrmRole.SUPER_ADMIN && role != com.axiom.auth.CrmRole.TENANT_ADMIN) {
            throw new com.axiom.common.ForbiddenException(
                    "Changing the password policy requires Super Admin or Tenant Admin");
        }
        jdbc.update("""
                insert into identity.password_policy
                  (tenant_id, min_length, require_upper, require_lower, require_digit, require_symbol,
                   history_count, expiry_days, max_failed_attempts, lockout_minutes, reject_breached)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id) do update set
                  min_length = excluded.min_length,
                  require_upper = excluded.require_upper,
                  require_lower = excluded.require_lower,
                  require_digit = excluded.require_digit,
                  require_symbol = excluded.require_symbol,
                  history_count = excluded.history_count,
                  expiry_days = excluded.expiry_days,
                  max_failed_attempts = excluded.max_failed_attempts,
                  lockout_minutes = excluded.lockout_minutes,
                  reject_breached = excluded.reject_breached,
                  updated_at = now()
                """, principal.tenantId(), requested.minLength(), requested.requireUpper(),
                requested.requireLower(), requested.requireDigit(), requested.requireSymbol(),
                requested.historyCount(), requested.expiryDays(), requested.maxFailedAttempts(),
                requested.lockoutMinutes(), requested.rejectBreached());
        audit.record("PASSWORD_POLICY_UPDATE", "PASSWORD_POLICY", principal.tenantId(),
                "Password policy updated", Map.of(
                        "minLength", requested.minLength(),
                        "historyCount", requested.historyCount(),
                        "expiryDays", requested.expiryDays(),
                        "maxFailedAttempts", requested.maxFailedAttempts()));
        return policy(principal.tenantId());
    }

    // ------------------------------------------------------------------
    // Composition and reuse validation
    // ------------------------------------------------------------------

    /**
     * @return every unmet rule, in the order a user would fix them. Empty means
     *         the candidate is acceptable on composition grounds.
     */
    public List<String> compositionFailures(Policy policy, String candidate, String email) {
        List<String> failures = new ArrayList<>();
        String value = candidate == null ? "" : candidate;
        if (value.length() < policy.minLength()) {
            failures.add("be at least " + policy.minLength() + " characters long (yours is " + value.length() + ")");
        }
        if (policy.requireUpper() && value.chars().noneMatch(Character::isUpperCase)) {
            failures.add("include at least one upper-case letter");
        }
        if (policy.requireLower() && value.chars().noneMatch(Character::isLowerCase)) {
            failures.add("include at least one lower-case letter");
        }
        if (policy.requireDigit() && value.chars().noneMatch(Character::isDigit)) {
            failures.add("include at least one digit");
        }
        if (policy.requireSymbol() && value.chars().allMatch(Character::isLetterOrDigit)) {
            failures.add("include at least one symbol, for example ! ? # or -");
        }
        if (policy.rejectBreached() && COMMON_PASSWORDS.contains(value.toLowerCase())) {
            failures.add("not be one of the widely-breached passwords attackers try first");
        }
        if (email != null && !email.isBlank()) {
            String local = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
            if (!local.isBlank() && value.toLowerCase().contains(local.toLowerCase())) {
                failures.add("not contain your email name (\"" + local + "\")");
            }
        }
        return failures;
    }

    /**
     * Validates composition and no-reuse together, throwing a single message that
     * names every unmet rule.
     *
     * @param userId may be null when the account does not exist yet, in which case
     *               only composition is checked
     */
    @Transactional(readOnly = true)
    public void validate(UUID tenantId, UUID userId, String email, String candidate) {
        Policy policy = policy(tenantId);
        List<String> failures = new ArrayList<>(compositionFailures(policy, candidate, email));
        if (userId != null && policy.historyCount() > 0 && reusesRecentPassword(tenantId, userId, candidate, policy)) {
            failures.add("be different from your last " + policy.historyCount() + " passwords");
        }
        if (!failures.isEmpty()) {
            throw new IllegalArgumentException("Choose a different password. It must " + String.join("; ", failures) + ".");
        }
    }

    @Transactional(readOnly = true)
    public boolean reusesRecentPassword(UUID tenantId, UUID userId, String candidate, Policy policy) {
        bind(tenantId);
        List<String> recent = jdbc.queryForList("""
                select password_hash from identity.password_history
                where tenant_id = ? and user_id = ?
                order by created_at desc
                limit ?
                """, String.class, tenantId, userId, policy.historyCount());
        return recent.stream().anyMatch(hash -> bcrypt.matches(candidate, hash));
    }

    /**
     * Changes the signed-in user's own password. Every other session for that user
     * is revoked: a password change is the standard response to "I think someone
     * else has my credential", and leaving their other sessions alive would defeat
     * the point.
     */
    @Transactional
    public void changeOwnPassword(String currentPassword, String newPassword) {
        TenantContext.Principal principal = TenantContext.get();
        String currentHash;
        try {
            currentHash = jdbc.queryForObject("""
                    select password_hash from identity.app_user
                    where tenant_id = ? and id = ? and active = true
                    """, String.class, principal.tenantId(), principal.userId());
        } catch (EmptyResultDataAccessException e) {
            throw new UnauthorizedException("This account is no longer active");
        }
        if (currentHash == null || !bcrypt.matches(currentPassword, currentHash)) {
            throw new UnauthorizedException("Your current password is not correct");
        }
        validate(principal.tenantId(), principal.userId(), principal.email(), newPassword);
        Policy policy = policy(principal.tenantId());
        String newHash = bcrypt.encode(newPassword);
        jdbc.update("""
                update identity.app_user
                set password_hash = ?, password_changed_at = now(), must_change_password = false, updated_at = now()
                where tenant_id = ? and id = ?
                """, newHash, principal.tenantId(), principal.userId());
        recordHistory(principal.tenantId(), principal.userId(), newHash, policy);
        int revoked = sessions.revokeAllForUserSystem(principal.tenantId(), principal.userId(),
                "Password changed by the account holder");
        audit.record("PASSWORD_CHANGE", "APP_USER", principal.userId(),
                "Password changed; other sessions ended", Map.of("sessionsEnded", revoked));
    }

    /** Appends the new hash and prunes history beyond the configured depth. */
    @Transactional
    public void recordHistory(UUID tenantId, UUID userId, String passwordHash, Policy policy) {
        bind(tenantId);
        jdbc.update("""
                insert into identity.password_history(tenant_id, user_id, password_hash)
                values (?, ?, ?)
                """, tenantId, userId, passwordHash);
        jdbc.update("""
                delete from identity.password_history
                where tenant_id = ? and user_id = ? and id not in (
                  select id from identity.password_history
                  where tenant_id = ? and user_id = ?
                  order by created_at desc
                  limit ?
                )
                """, tenantId, userId, tenantId, userId, Math.max(policy.historyCount(), 1));
    }

    /** True when the policy sets an expiry and the stored password is older than it. */
    public boolean isExpired(Policy policy, Instant passwordChangedAt) {
        if (policy.expiryDays() <= 0 || passwordChangedAt == null) return false;
        return passwordChangedAt.plus(Duration.ofDays(policy.expiryDays())).isBefore(Instant.now());
    }

    // ------------------------------------------------------------------
    // Progressive delay and lockout
    // ------------------------------------------------------------------

    /**
     * Reads the recent failure streak for an address and decides what the login
     * path owes the caller: a delay, or a refusal.
     */
    @Transactional(readOnly = true)
    public ThrottleState throttleState(UUID tenantId, String email) {
        Policy policy = policy(tenantId);
        bind(tenantId);
        Instant window = Instant.now().minus(Duration.ofMinutes(policy.lockoutMinutes()));
        List<Map<String, Object>> recent = jdbc.queryForList("""
                select outcome, at from identity.login_attempt
                where tenant_id = ? and lower(email) = lower(?) and at >= ?
                order by at desc
                limit 50
                """, tenantId, email, java.sql.Timestamp.from(window));
        int failures = 0;
        Instant lastFailure = null;
        for (Map<String, Object> attempt : recent) {
            String outcome = (String) attempt.get("outcome");
            if ("SUCCESS".equals(outcome)) break;
            if ("BAD_CREDENTIALS".equals(outcome) || "MFA_FAILED".equals(outcome)) {
                failures++;
                if (lastFailure == null) {
                    lastFailure = ((java.sql.Timestamp) attempt.get("at")).toInstant();
                }
            }
        }
        if (failures >= policy.maxFailedAttempts() && lastFailure != null) {
            Instant until = lastFailure.plus(Duration.ofMinutes(policy.lockoutMinutes()));
            if (until.isAfter(Instant.now())) {
                return new ThrottleState(true, 0, failures, until);
            }
        }
        long delay = Math.min(failures * DELAY_STEP_MILLIS, DELAY_CAP_MILLIS);
        return new ThrottleState(false, delay, failures, null);
    }

    @Transactional
    public void recordAttempt(UUID tenantId, UUID userId, String email, String outcome,
                              String ip, String userAgent) {
        bind(tenantId);
        jdbc.update("""
                insert into identity.login_attempt(tenant_id, user_id, email, outcome, ip, user_agent)
                values (?, ?, ?, ?, ?, ?)
                """, tenantId, userId, email, outcome, ip, userAgent);
    }

    /**
     * Binds the tenant for calls that arrive before any authenticated principal
     * exists (the sign-in path). {@code set_config(..., true)} is SET LOCAL: it
     * dies with the transaction and cannot leak onto a pooled connection.
     */
    private void bind(UUID tenantId) {
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
    }
}

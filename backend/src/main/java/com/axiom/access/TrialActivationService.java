package com.axiom.access;

import com.axiom.common.NotFoundException;
import com.axiom.identity.PasswordPolicyService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redeems a one-time activation link.
 *
 * <p>This is the other end of "we do not email passwords". The link carries a
 * 256-bit token; only its SHA-256 is stored, so the row cannot be turned back
 * into a working link. Redemption is single-use and time-boxed, and the account
 * has no usable password until it happens.
 *
 * <p>The tenant's own password policy applies to the password chosen here. A
 * workspace whose first two credentials skipped the policy is a workspace whose
 * policy is decorative — the same reasoning
 * {@link com.axiom.identity.TenantLifecycleService} applies to its initial
 * administrator.
 *
 * <p>Every failure returns the same wording. "Expired", "already used" and "never
 * existed" are distinguishable states internally and are recorded as such, but
 * telling an anonymous caller which one they hit turns the endpoint into an
 * oracle for guessing tokens.
 */
@Service
public class TrialActivationService {

    private static final String REFUSAL =
            "That activation link is not valid. Links can be used once and expire after two weeks. "
                    + "Ask your Axiom contact to issue a new one.";

    private final JdbcTemplate jdbc;
    private final PasswordPolicyService passwordPolicy;
    private final TrialRequestService requests;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public TrialActivationService(JdbcTemplate jdbc, PasswordPolicyService passwordPolicy,
                                  TrialRequestService requests) {
        this.jdbc = jdbc;
        this.passwordPolicy = passwordPolicy;
        this.requests = requests;
    }

    public record ActivationOutcome(String email, String role, String tenantSlug, String message) {}

    @Transactional
    public ActivationOutcome redeem(String token, String password, String sourceIp) {
        requests.intakeSession();
        if (token == null || token.isBlank() || password == null || password.isBlank()) {
            throw new NotFoundException(REFUSAL);
        }
        List<Map<String, Object>> found = jdbc.queryForList("""
                select a.id, a.tenant_id, a.user_id, a.email, a.role, a.trial_request_id,
                       a.redeemed_at, a.expires_at, t.slug
                from platform.trial_activation a
                join platform.tenant t on t.id = a.tenant_id
                where a.token_hash = ?
                """, TrialProvisioningService.sha256(token));
        if (found.isEmpty()) throw new NotFoundException(REFUSAL);
        Map<String, Object> row = found.get(0);
        if (row.get("redeemed_at") != null) throw new NotFoundException(REFUSAL);
        Object expiresAt = row.get("expires_at");
        if (expiresAt instanceof java.sql.Timestamp ts && ts.toInstant().isBefore(java.time.Instant.now())) {
            throw new NotFoundException(REFUSAL);
        }

        UUID tenantId = (UUID) row.get("tenant_id");
        UUID userId = (UUID) row.get("user_id");
        String email = (String) row.get("email");
        bind(tenantId);
        passwordPolicy.validate(tenantId, null, email, password);
        bind(tenantId);

        String hash = bcrypt.encode(password);
        int updated = jdbc.update("""
                update identity.app_user
                set password_hash = ?, password_changed_at = now(), must_change_password = false,
                    updated_at = now()
                where tenant_id = ? and id = ?
                """, hash, tenantId, userId);
        if (updated == 0) throw new NotFoundException(REFUSAL);
        jdbc.update("insert into identity.password_history(tenant_id, user_id, password_hash) values (?, ?, ?)",
                tenantId, userId, hash);
        jdbc.update("""
                update platform.trial_activation set redeemed_at = now(), redeemed_ip = ? where id = ?
                """, sourceIp, row.get("id"));
        requests.logEvent((UUID) row.get("trial_request_id"), "ACTIVATION_REDEEMED", null, null, email,
                TrialRequestService.domainOf(email), null, "Account holder",
                "Activation link redeemed for the " + row.get("role") + " account.", null, sourceIp, null);

        return new ActivationOutcome(email, (String) row.get("role"), (String) row.get("slug"),
                "Your account is ready. Sign in with " + email + " on workspace " + row.get("slug") + ".");
    }

    private void bind(UUID tenantId) {
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
    }
}

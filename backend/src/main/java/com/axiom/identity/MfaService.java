package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.common.SecretCipher;
import com.axiom.common.UnauthorizedException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TOTP second factor and recovery codes (FR-TEN-008).
 *
 * <p>Three properties are load-bearing:
 *
 * <ol>
 *   <li><b>Enrolment is two-phase.</b> A secret is generated and stored inactive;
 *       the factor only becomes active once the user proves they can produce a
 *       code from it. Activating on generation is how people lock themselves out
 *       of their own account with a mistyped QR scan.</li>
 *   <li><b>Recovery codes are issued once, stored hashed, single use.</b> They are
 *       returned exactly once — at the moment the factor is confirmed — and never
 *       again by any endpoint.</li>
 *   <li><b>The shared secret is encrypted at rest</b> ({@link SecretCipher}) and
 *       is returned only in the enrolment response, because that response is the
 *       one place the user legitimately needs it.</li>
 * </ol>
 */
@Service
public class MfaService {

    private static final String ISSUER = "Axiom CRM";
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public MfaService(JdbcTemplate jdbc, SecretCipher cipher, AuditService audit) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.audit = audit;
    }

    public record EnrolmentResult(String method, String secretBase32, String provisioningUri,
                                 int digits, int periodSeconds, String instructions) {}
    public record ConfirmationResult(boolean active, List<String> recoveryCodes, String warning) {}
    public record MfaStatus(boolean enrolled, boolean active, boolean requiredByPolicy,
                            int unusedRecoveryCodes, Instant confirmedAt) {}

    // ------------------------------------------------------------------
    // Status and policy
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public MfaStatus status() {
        TenantContext.Principal p = TenantContext.get();
        Map<String, Object> row = optionalFactor(p.tenantId(), p.userId());
        int unused = jdbc.queryForObject("""
                select count(*) from identity.mfa_recovery_code
                where tenant_id = ? and user_id = ? and used_at is null
                """, Integer.class, p.tenantId(), p.userId());
        boolean required = isRequiredForRole(p.tenantId(), p.role());
        if (row == null) {
            return new MfaStatus(false, false, required, unused, null);
        }
        return new MfaStatus(true, (Boolean) row.get("active"), required, unused,
                row.get("confirmed_at") == null ? null : ((java.sql.Timestamp) row.get("confirmed_at")).toInstant());
    }

    /** True when tenant policy demands a second factor for this role (FR-TEN-008). */
    @Transactional(readOnly = true)
    public boolean isRequiredForRole(UUID tenantId, String role) {
        Integer required = jdbc.queryForObject("""
                select coalesce(max(case when required then 1 else 0 end), 0)
                from identity.mfa_policy
                where tenant_id = ? and target_role in ('*', ?)
                """, Integer.class, tenantId, role);
        return required != null && required == 1;
    }

    @Transactional(readOnly = true)
    public boolean isActiveFor(UUID tenantId, UUID userId) {
        Integer active = jdbc.queryForObject("""
                select coalesce(max(case when active then 1 else 0 end), 0)
                from identity.user_mfa where tenant_id = ? and user_id = ?
                """, Integer.class, tenantId, userId);
        return active != null && active == 1;
    }

    // ------------------------------------------------------------------
    // Enrolment
    // ------------------------------------------------------------------

    @Transactional
    public EnrolmentResult enrol() {
        TenantContext.Principal p = TenantContext.get();
        Map<String, Object> existing = optionalFactor(p.tenantId(), p.userId());
        if (existing != null && Boolean.TRUE.equals(existing.get("active"))) {
            throw new ConflictException("A second factor is already active on this account. "
                    + "Remove the existing one before enrolling a new authenticator.");
        }
        String secret = TotpGenerator.newSecretBase32();
        if (existing == null) {
            jdbc.update("""
                    insert into identity.user_mfa(tenant_id, user_id, method, secret_cipher, active)
                    values (?, ?, 'TOTP', ?, false)
                    """, p.tenantId(), p.userId(), cipher.encrypt(secret));
        } else {
            // Restarting an unconfirmed enrolment replaces the pending secret.
            jdbc.update("""
                    update identity.user_mfa
                    set secret_cipher = ?, confirmed_at = null, active = false, updated_at = now()
                    where tenant_id = ? and user_id = ? and method = 'TOTP'
                    """, cipher.encrypt(secret), p.tenantId(), p.userId());
        }
        audit.record("MFA_ENROL_START", "APP_USER", p.userId(),
                "Started enrolling an authenticator app", Map.of("method", "TOTP"));
        return new EnrolmentResult("TOTP", secret,
                TotpGenerator.provisioningUri(ISSUER, p.email(), secret),
                TotpGenerator.DIGITS, TotpGenerator.STEP_SECONDS,
                "Scan the code in your authenticator app, then enter the 6-digit code it shows to finish. "
                        + "The second factor is not active until you do.");
    }

    @Transactional
    public ConfirmationResult confirm(String code) {
        TenantContext.Principal p = TenantContext.get();
        Map<String, Object> factor = optionalFactor(p.tenantId(), p.userId());
        if (factor == null) {
            throw new NotFoundException("Start enrolment first — there is no pending authenticator to confirm.");
        }
        if (Boolean.TRUE.equals(factor.get("active"))) {
            throw new ConflictException("This authenticator is already active.");
        }
        String secret = cipher.decrypt((String) factor.get("secret_cipher"));
        if (!TotpGenerator.verify(secret, code, Instant.now())) {
            audit.record("MFA_ENROL_FAILED", "APP_USER", p.userId(),
                    "Authenticator confirmation code was rejected", Map.of("method", "TOTP"));
            throw new UnauthorizedException("That code is not valid. Check your authenticator app is showing "
                    + "the code for " + p.email() + " and enter the current one.");
        }
        jdbc.update("""
                update identity.user_mfa
                set active = true, confirmed_at = now(), updated_at = now()
                where tenant_id = ? and user_id = ? and method = 'TOTP'
                """, p.tenantId(), p.userId());
        List<String> codes = issueRecoveryCodes(p.tenantId(), p.userId());
        audit.record("MFA_ENROL_CONFIRM", "APP_USER", p.userId(),
                "Authenticator app activated as a second factor",
                Map.of("method", "TOTP", "recoveryCodesIssued", codes.size()));
        return new ConfirmationResult(true, codes,
                "These recovery codes are shown once and cannot be retrieved again. "
                        + "Each one works a single time. Store them somewhere safe and offline.");
    }

    /**
     * Replaces any unused recovery codes with a fresh set. Existing codes are
     * destroyed rather than added to: a user who thinks they have ten codes and
     * actually has twenty scattered across two printouts has a leak, not a backup.
     */
    @Transactional
    public ConfirmationResult regenerateRecoveryCodes() {
        TenantContext.Principal p = TenantContext.get();
        if (!isActiveFor(p.tenantId(), p.userId())) {
            throw new ConflictException("Recovery codes only apply once a second factor is active.");
        }
        List<String> codes = issueRecoveryCodes(p.tenantId(), p.userId());
        audit.record("MFA_RECOVERY_REISSUE", "APP_USER", p.userId(),
                "Recovery codes reissued; previous codes invalidated",
                Map.of("issued", codes.size()));
        return new ConfirmationResult(true, codes,
                "The previous recovery codes no longer work. Destroy any old copies.");
    }

    @Transactional
    public void disable(String reason) {
        TenantContext.Principal p = TenantContext.get();
        if (isRequiredForRole(p.tenantId(), p.role())) {
            throw new ConflictException("This workspace requires a second factor for the " + p.role()
                    + " role, so it cannot be removed. Enrol a different authenticator instead.");
        }
        int updated = jdbc.update("""
                update identity.user_mfa set active = false, confirmed_at = null, updated_at = now()
                where tenant_id = ? and user_id = ?
                """, p.tenantId(), p.userId());
        if (updated == 0) throw new NotFoundException("No second factor is enrolled on this account");
        jdbc.update("""
                update identity.mfa_recovery_code set used_at = now()
                where tenant_id = ? and user_id = ? and used_at is null
                """, p.tenantId(), p.userId());
        audit.recordWithReason("MFA_DISABLE", "APP_USER", p.userId(),
                "Second factor removed and recovery codes invalidated", reason, Map.of());
    }

    // ------------------------------------------------------------------
    // Verification
    // ------------------------------------------------------------------

    /** Verifies a TOTP code against the user's active factor, with a ±1 step window. */
    @Transactional(readOnly = true)
    public boolean verifyCode(UUID tenantId, UUID userId, String code) {
        Map<String, Object> factor = optionalFactor(tenantId, userId);
        if (factor == null || !Boolean.TRUE.equals(factor.get("active"))) return false;
        return TotpGenerator.verify(cipher.decrypt((String) factor.get("secret_cipher")), code, Instant.now());
    }

    /**
     * Spends a recovery code. Marking {@code used_at} in the same statement that
     * matches the hash is what makes it single-use even under two concurrent
     * requests: the second update matches zero rows.
     */
    @Transactional
    public boolean consumeRecoveryCode(UUID tenantId, UUID userId, String presented) {
        if (presented == null || presented.isBlank()) return false;
        String normalised = presented.trim().toUpperCase().replace(" ", "");
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                select id, code_hash from identity.mfa_recovery_code
                where tenant_id = ? and user_id = ? and used_at is null
                """, tenantId, userId);
        for (Map<String, Object> candidate : candidates) {
            if (bcrypt.matches(normalised, (String) candidate.get("code_hash"))) {
                int spent = jdbc.update("""
                        update identity.mfa_recovery_code set used_at = now()
                        where tenant_id = ? and id = ? and used_at is null
                        """, tenantId, candidate.get("id"));
                return spent == 1;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private List<String> issueRecoveryCodes(UUID tenantId, UUID userId) {
        jdbc.update("""
                update identity.mfa_recovery_code set used_at = now()
                where tenant_id = ? and user_id = ? and used_at is null
                """, tenantId, userId);
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = randomBlock(4) + "-" + randomBlock(4);
            codes.add(code);
            jdbc.update("""
                    insert into identity.mfa_recovery_code(tenant_id, user_id, code_hash)
                    values (?, ?, ?)
                    """, tenantId, userId, bcrypt.encode(code));
        }
        return codes;
    }

    private String randomBlock(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
        }
        return out.toString();
    }

    private Map<String, Object> optionalFactor(UUID tenantId, UUID userId) {
        try {
            return jdbc.queryForMap("""
                    select id, secret_cipher, active, confirmed_at
                    from identity.user_mfa
                    where tenant_id = ? and user_id = ? and method = 'TOTP'
                    """, tenantId, userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}

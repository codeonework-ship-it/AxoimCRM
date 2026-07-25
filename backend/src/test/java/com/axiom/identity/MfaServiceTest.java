package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.common.SecretCipher;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FR-TEN-008: TOTP verification with clock skew, and single-use recovery codes. */
class MfaServiceTest {

    private static final String TEST_KEY = "unit-test-identity-secret-key-32-bytes-minimum";

    private JdbcTemplate jdbc;
    private SecretCipher cipher;
    private MfaService service;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        cipher = new SecretCipher(TEST_KEY);
        service = new MfaService(jdbc, cipher, mock(AuditService.class));
        TenantContext.set(new TenantContext.Principal(tenantId, userId, "SALES", "Priya", "priya@example.com"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    private String enrolActiveFactor() {
        String secret = TotpGenerator.newSecretBase32();
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
                "id", UUID.randomUUID(),
                "secret_cipher", cipher.encrypt(secret),
                "active", Boolean.TRUE));
        return secret;
    }

    @Test void theCurrentCodeIsAccepted() {
        String secret = enrolActiveFactor();
        String code = TotpGenerator.codeAt(secret, Instant.now());
        assertTrue(service.verifyCode(tenantId, userId, code));
    }

    @Test void aWrongCodeIsRejected() {
        String secret = enrolActiveFactor();
        String correct = TotpGenerator.codeAt(secret, Instant.now());
        String wrong = correct.equals("000000") ? "111111" : "000000";
        assertNotEquals(correct, wrong);
        assertFalse(service.verifyCode(tenantId, userId, wrong));
    }

    @Test void aCodeFromOneStepAgoIsStillAcceptedForClockSkew() {
        String secret = enrolActiveFactor();
        String previousStep = TotpGenerator.codeAt(secret,
                Instant.now().minus(Duration.ofSeconds(TotpGenerator.STEP_SECONDS)));
        assertTrue(service.verifyCode(tenantId, userId, previousStep),
                "a code one 30-second step old must still work; phones drift");
    }

    @Test void aCodeFromOneStepAheadIsAcceptedForClockSkew() {
        String secret = enrolActiveFactor();
        String nextStep = TotpGenerator.codeAt(secret,
                Instant.now().plus(Duration.ofSeconds(TotpGenerator.STEP_SECONDS)));
        assertTrue(service.verifyCode(tenantId, userId, nextStep));
    }

    @Test void aCodeWellOutsideTheSkewWindowIsRejected() {
        String secret = enrolActiveFactor();
        String stale = TotpGenerator.codeAt(secret, Instant.now().minus(Duration.ofMinutes(5)));
        assertFalse(service.verifyCode(tenantId, userId, stale),
                "the window must stay narrow; every extra step widens replay");
    }

    @Test void anInactiveFactorNeverVerifies() {
        String secret = TotpGenerator.newSecretBase32();
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
                "id", UUID.randomUUID(),
                "secret_cipher", cipher.encrypt(secret),
                "active", Boolean.FALSE));
        assertFalse(service.verifyCode(tenantId, userId, TotpGenerator.codeAt(secret, Instant.now())),
                "an unconfirmed enrolment must not satisfy a second factor");
    }

    @Test void aRecoveryCodeWorksOnce() {
        String code = "ABCD-2345";
        String hash = new BCryptPasswordEncoder().encode(code);
        UUID codeId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", codeId, "code_hash", hash)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertTrue(service.consumeRecoveryCode(tenantId, userId, code));
    }

    @Test void aRecoveryCodeCannotBeReplayed() {
        String code = "ABCD-2345";
        String hash = new BCryptPasswordEncoder().encode(code);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", UUID.randomUUID(), "code_hash", hash)));
        // The update matches zero rows the second time, because used_at is already
        // set — this is what makes single use hold under a concurrent replay too.
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertFalse(service.consumeRecoveryCode(tenantId, userId, code),
                "a code whose used_at is already set must not authenticate");
    }

    @Test void anUnknownRecoveryCodeIsRejected() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", UUID.randomUUID(),
                        "code_hash", new BCryptPasswordEncoder().encode("ZZZZ-9999"))));
        assertFalse(service.consumeRecoveryCode(tenantId, userId, "ABCD-2345"));
    }

    @Test void recoveryCodeMatchingIgnoresCaseAndSpacing() {
        String code = "ABCD-2345";
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", UUID.randomUUID(),
                        "code_hash", new BCryptPasswordEncoder().encode(code))));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertTrue(service.consumeRecoveryCode(tenantId, userId, " abcd-2345 "));
    }

    @Test void theStoredSecretIsNotReadableWithoutTheKey() {
        String secret = TotpGenerator.newSecretBase32();
        String encrypted = cipher.encrypt(secret);
        assertFalse(encrypted.contains(secret), "the column must not hold the base32 secret in the clear");
        assertEquals(secret, cipher.decrypt(encrypted));
    }
}

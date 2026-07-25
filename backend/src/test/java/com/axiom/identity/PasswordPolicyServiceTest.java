package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FR-TEN-003: composition rules, reuse history and expiry. */
class PasswordPolicyServiceTest {

    private JdbcTemplate jdbc;
    private PasswordPolicyService service;

    private static final PasswordPolicyService.Policy STRICT =
            new PasswordPolicyService.Policy(12, true, true, true, true, 5, 90, 5, 15, true);

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new PasswordPolicyService(jdbc, mock(AuditService.class), mock(SessionService.class));
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "TENANT_ADMIN", "Admin", "admin@example.com"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void acceptsAPasswordThatMeetsEveryRule() {
        assertTrue(service.compositionFailures(STRICT, "Fj7!qmVzx2Lp", "raj.malhotra@meridianfab.com").isEmpty());
    }

    @Test void tooShortIsRejectedNamingTheRequiredLength() {
        List<String> failures = service.compositionFailures(STRICT, "Ab3!x", "user@example.com");
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().contains("at least 12 characters"),
                "message should state the required length: " + failures);
    }

    @Test void missingUpperCaseIsNamed() {
        List<String> failures = service.compositionFailures(STRICT, "fj7!qmvzx2lp", "user@example.com");
        assertEquals(List.of("include at least one upper-case letter"), failures);
    }

    @Test void missingLowerCaseIsNamed() {
        List<String> failures = service.compositionFailures(STRICT, "FJ7!QMVZX2LP", "user@example.com");
        assertEquals(List.of("include at least one lower-case letter"), failures);
    }

    @Test void missingDigitIsNamed() {
        List<String> failures = service.compositionFailures(STRICT, "Fjq!qmVzxwLp", "user@example.com");
        assertEquals(List.of("include at least one digit"), failures);
    }

    @Test void missingSymbolIsNamed() {
        List<String> failures = service.compositionFailures(STRICT, "Fj7qmVzx2Lpw", "user@example.com");
        assertEquals(List.of("include at least one symbol, for example ! ? # or -"), failures);
    }

    @Test void widelyBreachedPasswordIsRejected() {
        PasswordPolicyService.Policy relaxed =
                new PasswordPolicyService.Policy(8, false, false, false, false, 5, 0, 5, 15, true);
        List<String> failures = service.compositionFailures(relaxed, "password123", "user@example.com");
        assertTrue(failures.stream().anyMatch(f -> f.contains("widely-breached")), failures.toString());
    }

    @Test void passwordContainingTheEmailNameIsRejected() {
        List<String> failures = service.compositionFailures(STRICT, "Raj7!qmVzx2Lp", "raj@meridianfab.com");
        assertTrue(failures.stream().anyMatch(f -> f.contains("email name")), failures.toString());
    }

    @Test void everyUnmetRuleIsReportedTogetherRatherThanOneAtATime() {
        List<String> failures = service.compositionFailures(STRICT, "abc", "user@example.com");
        assertEquals(4, failures.size(), "expected length, upper-case, digit and symbol: " + failures);
    }

    @Test void relaxedPolicyDoesNotDemandComplexityItDoesNotRequire() {
        PasswordPolicyService.Policy relaxed =
                new PasswordPolicyService.Policy(8, false, false, false, false, 0, 0, 5, 15, false);
        assertTrue(service.compositionFailures(relaxed, "simplepass", "user@example.com").isEmpty());
    }

    @Test void reusingAPasswordFromHistoryIsDetected() {
        String previous = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .encode("Fj7!qmVzx2Lp");
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of(previous));
        assertTrue(service.reusesRecentPassword(UUID.randomUUID(), UUID.randomUUID(), "Fj7!qmVzx2Lp", STRICT));
    }

    @Test void aFreshPasswordIsNotFlaggedAsReuse() {
        String previous = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .encode("Fj7!qmVzx2Lp");
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of(previous));
        assertFalse(service.reusesRecentPassword(UUID.randomUUID(), UUID.randomUUID(), "Different9#zzQ", STRICT));
    }

    @Test void expiryIsNotEnforcedWhenThePolicyDisablesIt() {
        PasswordPolicyService.Policy noExpiry =
                new PasswordPolicyService.Policy(12, true, true, true, true, 5, 0, 5, 15, true);
        assertFalse(service.isExpired(noExpiry, Instant.now().minus(Duration.ofDays(3650))));
    }

    @Test void expiryIsEnforcedOncePastTheConfiguredWindow() {
        assertTrue(service.isExpired(STRICT, Instant.now().minus(Duration.ofDays(91))));
        assertFalse(service.isExpired(STRICT, Instant.now().minus(Duration.ofDays(89))));
    }
}

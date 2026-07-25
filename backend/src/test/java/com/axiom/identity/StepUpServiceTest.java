package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.audit.IndependentAuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FR-TEN-009: a controlled action needs a re-authentication inside the window. */
class StepUpServiceTest {

    private JdbcTemplate jdbc;
    private SessionService sessions;
    private IndependentAuditService independentAudit;
    private StepUpService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        sessions = mock(SessionService.class);
        independentAudit = mock(IndependentAuditService.class);
        service = new StepUpService(jdbc, sessions, mock(AuditService.class), independentAudit,
                mock(MfaService.class));
        when(sessions.policy(any())).thenReturn(
                new SessionService.SessionPolicy(480, 120, 5, "END_OLDEST", 300));
        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(),
                "TENANT_ADMIN", "Admin", "admin@example.com"));
        TenantContext.setSessionJti("jti-under-test");
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    private void stepUpStamp(Instant at) {
        when(jdbc.queryForObject(anyString(), eq(Timestamp.class), any(Object[].class)))
                .thenReturn(at == null ? null : Timestamp.from(at));
    }

    @Test void aFreshStepUpLetsTheControlledActionProceed() {
        stepUpStamp(Instant.now().minus(Duration.ofSeconds(30)));
        assertDoesNotThrow(() -> service.requireStepUp("Exporting accounts"));
    }

    @Test void aStaleStepUpRefusesTheAction() {
        stepUpStamp(Instant.now().minus(Duration.ofMinutes(20)));
        ForbiddenException error = assertThrows(ForbiddenException.class,
                () -> service.requireStepUp("Exporting accounts"));
        assertTrue(error.getMessage().contains("confirm your password"),
                "the refusal must say what to do next: " + error.getMessage());
    }

    @Test void aSessionThatNeverSteppedUpRefusesTheAction() {
        stepUpStamp(null);
        assertThrows(ForbiddenException.class, () -> service.requireStepUp("Deleting an account record"));
    }

    @Test void aRefusalIsAuditedOutsideTheRolledBackTransaction() {
        stepUpStamp(null);
        assertThrows(ForbiddenException.class, () -> service.requireStepUp("Granting a role"));
        // The independent (REQUIRES_NEW) path is the only one that survives the
        // rollback the refusal causes.
        verify(independentAudit).record(eq("STEP_UP_REQUIRED"), eq("USER_SESSION"), eq(null),
                anyString(), anyString(), any());
    }

    @Test void aRequestWithNoServerSideSessionCannotProveFreshness() {
        TenantContext.setSessionJti(null);
        assertThrows(ForbiddenException.class, () -> service.requireStepUp("Rotating a service credential"));
        assertFalse(service.isFresh(300));
    }

    @Test void freshnessIsEvaluatedAgainstTheBoundaryNotADefaultGuess() {
        stepUpStamp(Instant.now().minus(Duration.ofSeconds(299)));
        assertTrue(service.isFresh(300));
        stepUpStamp(Instant.now().minus(Duration.ofSeconds(301)));
        assertFalse(service.isFresh(300));
    }
}

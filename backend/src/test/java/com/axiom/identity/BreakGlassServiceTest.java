package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.notifications.NotificationWriter;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FR-TEN-012: emergency access is time-boxed and expires without a sweeper. */
class BreakGlassServiceTest {

    private JdbcTemplate jdbc;
    private BreakGlassService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new BreakGlassService(jdbc, mock(AuditService.class), mock(NotificationWriter.class));
        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(),
                "SUPER_ADMIN", "Operator", "ops@axiomcrm.com"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    private void grantIs(BreakGlassService.Grant grant) {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(grant);
    }

    private BreakGlassService.Grant grant(Instant expiresAt, Instant usedAt, Instant revokedAt) {
        return new BreakGlassService.Grant(UUID.randomUUID(), "ops@axiomcrm.com", "INC-4471",
                "Customer cannot sign in after an identity provider change and needs data recovered",
                Instant.now().minus(Duration.ofMinutes(30)), expiresAt, usedAt, revokedAt, null,
                BreakGlassService.stateOf(expiresAt, usedAt, revokedAt));
    }

    @Test void stateIsOpenInsideTheWindow() {
        assertEquals("OPEN", BreakGlassService.stateOf(Instant.now().plus(Duration.ofMinutes(10)), null, null));
    }

    @Test void stateIsExpiredOnceTheWindowPasses() {
        assertEquals("EXPIRED", BreakGlassService.stateOf(Instant.now().minus(Duration.ofSeconds(1)), null, null));
    }

    @Test void useOfAnAlreadyUsedGrantOutranksExpiryInTheReportedState() {
        assertEquals("USED", BreakGlassService.stateOf(Instant.now().plus(Duration.ofMinutes(5)),
                Instant.now(), null));
    }

    @Test void revocationOutranksEverythingElse() {
        assertEquals("REVOKED", BreakGlassService.stateOf(Instant.now().plus(Duration.ofMinutes(5)),
                Instant.now(), Instant.now()));
    }

    @Test void anExpiredGrantCannotBeUsed() {
        UUID id = UUID.randomUUID();
        grantIs(grant(Instant.now().minus(Duration.ofMinutes(1)), null, null));
        ConflictException error = assertThrows(ConflictException.class, () -> service.use(id));
        assertTrue(error.getMessage().contains("expired"), error.getMessage());
        // Nothing is marked used: expiry is evaluated at use time, not by a job that
        // might not have run.
        verify(jdbc, never()).update(anyString(), any(), any());
    }

    @Test void aRevokedGrantCannotBeUsed() {
        UUID id = UUID.randomUUID();
        grantIs(grant(Instant.now().plus(Duration.ofMinutes(30)), null, Instant.now()));
        assertThrows(ConflictException.class, () -> service.use(id));
    }

    @Test void aGrantCannotBeUsedTwice() {
        UUID id = UUID.randomUUID();
        grantIs(grant(Instant.now().plus(Duration.ofMinutes(30)), Instant.now(), null));
        ConflictException error = assertThrows(ConflictException.class, () -> service.use(id));
        assertTrue(error.getMessage().contains("already been used"), error.getMessage());
    }

    @Test void aTenantRoleCannotRequestEmergencyAccess() {
        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(),
                "TENANT_ADMIN", "Admin", "admin@example.com"));
        assertThrows(ForbiddenException.class, () -> service.request(new BreakGlassService.GrantRequest(
                "INC-1", "Needed to investigate a sign-in failure reported today", 30)));
    }

    @Test void aCaseReferenceIsMandatory() {
        assertThrows(IllegalArgumentException.class, () -> service.request(new BreakGlassService.GrantRequest(
                "  ", "Needed to investigate a sign-in failure reported today", 30)));
    }

    @Test void aSubstantiveJustificationIsMandatory() {
        assertThrows(IllegalArgumentException.class,
                () -> service.request(new BreakGlassService.GrantRequest("INC-1", "urgent", 30)));
    }

    @Test void theWindowIsCapped() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.request(new BreakGlassService.GrantRequest("INC-1",
                        "Needed to investigate a sign-in failure reported today", 10_000)));
        assertTrue(error.getMessage().contains("minutes"), error.getMessage());
    }
}

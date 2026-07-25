package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.PlatformSession;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FR-TEN-001/002: provisioning idempotency, permitted transitions, suspended writes. */
class TenantLifecycleServiceTest {

    private JdbcTemplate jdbc;
    private TenantLifecycleService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new TenantLifecycleService(jdbc, new PlatformSession(mock(JdbcTemplate.class)),
                mock(PasswordPolicyService.class), mock(AuditService.class));
        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(),
                "SUPER_ADMIN", "Operator", "ops@axiomcrm.com"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    private void tenantIs(String status, LocalDate retentionUntil) {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(new TenantLifecycleService.TenantRow(tenantId, "meridian",
                        "Meridian Fabrication Group", status, false, null, null, retentionUntil));
    }

    // ---------------------------------------------------------------- transitions

    @Test void anActiveWorkspaceMaySuspend() {
        tenantIs("active", null);
        // The transition itself writes through the mock; reaching the write without a
        // ConflictException is the assertion.
        service.transition(tenantId, "suspended", "Non-payment after 60 days", false, null);
    }

    @Test void anIllegalTransitionIsRefusedAndNamesWhatIsPermitted() {
        tenantIs("active", null);
        ConflictException error = assertThrows(ConflictException.class,
                () -> service.transition(tenantId, "provisioning", "Trying to rewind", false, null));
        assertTrue(error.getMessage().contains("Permitted next states are"), error.getMessage());
        assertTrue(error.getMessage().contains("suspended"), error.getMessage());
    }

    @Test void aTerminatedWorkspaceHasNoFurtherTransitions() {
        tenantIs("terminated", null);
        ConflictException error = assertThrows(ConflictException.class,
                () -> service.transition(tenantId, "active", "Customer came back", true, null));
        assertTrue(error.getMessage().contains("No further transitions"), error.getMessage());
    }

    @Test void terminationRequiresExplicitConfirmation() {
        tenantIs("active", null);
        ConflictException error = assertThrows(ConflictException.class,
                () -> service.transition(tenantId, "terminating", "Contract ended", false, null));
        assertTrue(error.getMessage().contains("explicit confirmation"), error.getMessage());
    }

    @Test void dataCannotBeDestroyedBeforeTheRetentionPeriodEnds() {
        tenantIs("terminating", LocalDate.now().plusDays(14));
        ConflictException error = assertThrows(ConflictException.class,
                () -> service.transition(tenantId, "terminated", "Cleanup", true, null));
        assertTrue(error.getMessage().contains("retention period"), error.getMessage());
    }

    @Test void aReasonIsMandatoryForEveryLifecycleChange() {
        tenantIs("active", null);
        assertThrows(IllegalArgumentException.class,
                () -> service.transition(tenantId, "suspended", "   ", false, null));
    }

    @Test void aTenantRoleCannotChangeAWorkspaceLifecycleState() {
        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(),
                "TENANT_ADMIN", "Admin", "admin@example.com"));
        assertThrows(ForbiddenException.class,
                () -> service.transition(tenantId, "suspended", "Trying it on", false, null));
    }

    @Test void aReadOnlyPlatformRoleCannotChangeAWorkspaceLifecycleState() {
        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(),
                "SUPER_AUDIT", "Auditor", "audit@axiomcrm.com"));
        assertThrows(ForbiddenException.class,
                () -> service.transition(tenantId, "suspended", "Trying it on", false, null));
    }

    // ---------------------------------------------------------------- suspended writes

    @Test void aSuspendedWorkspaceBlocksBusinessWrites() {
        assertTrue(TenantLifecycleService.blocksBusinessWrites("suspended"));
    }

    @Test void terminatingAndTerminatedWorkspacesAlsoBlockBusinessWrites() {
        assertTrue(TenantLifecycleService.blocksBusinessWrites("terminating"));
        assertTrue(TenantLifecycleService.blocksBusinessWrites("terminated"));
    }

    @Test void activeAndProvisioningWorkspacesDoNotBlockWrites() {
        assertFalse(TenantLifecycleService.blocksBusinessWrites("active"));
        assertFalse(TenantLifecycleService.blocksBusinessWrites("provisioning"));
        assertFalse(TenantLifecycleService.blocksBusinessWrites(null));
    }

    // ---------------------------------------------------------------- provisioning

    @Test void provisioningRequiresARequestKeySoARetryCannotDuplicate() {
        assertThrows(IllegalArgumentException.class, () -> service.provision(
                new TenantLifecycleService.ProvisionRequest(" ", "acme", "Acme", "Acme Ltd",
                        "admin@acme.example", "Acme Admin", "Fj7!qmVzx2Lp", "TRIAL")));
    }

    @Test void anInvalidSlugIsRefusedWithTheRule() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.provision(
                new TenantLifecycleService.ProvisionRequest("key-1", "A!", "Acme", "Acme Ltd",
                        "admin@acme.example", "Acme Admin", "Fj7!qmVzx2Lp", "TRIAL")));
        assertTrue(error.getMessage().contains("lower-case"), error.getMessage());
    }

    @Test void aTenantRoleCannotProvisionAWorkspace() {
        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(),
                "TENANT_ADMIN", "Admin", "admin@example.com"));
        assertThrows(ForbiddenException.class, () -> service.provision(
                new TenantLifecycleService.ProvisionRequest("key-1", "acme", "Acme", "Acme Ltd",
                        "admin@acme.example", "Acme Admin", "Fj7!qmVzx2Lp", "TRIAL")));
    }
}

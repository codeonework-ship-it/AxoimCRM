package com.axiom.security;

import com.axiom.activity.UserActivityService;
import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The administrator/auditor invariant, at the layer that produces the message a
 * human reads. The database backstop is verified separately against a real
 * PostgreSQL (V310, {@code security.assert_tenant_role_floor}) because a mocked
 * JdbcTemplate cannot execute a trigger.
 */
class TenantRoleFloorServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AUDITOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private JdbcTemplate jdbc;
    private AuditService audit;
    private AccessGrantLog grantLog;
    private UserActivityService activity;
    private TenantRoleFloorService service;

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        grantLog = mock(AccessGrantLog.class);
        activity = mock(UserActivityService.class);
        service = new TenantRoleFloorService(jdbc, audit, grantLog, activity);
        signInAs("TENANT_ADMIN");
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    private void signInAs(String role) {
        TenantContext.set(new TenantContext.Principal(TENANT, ADMIN_ID, role,
                "Raj Malhotra", "raj.malhotra@meridianfab.com"));
    }

    /** The user lookup, whichever RowMapper overload the service happens to use. */
    @SuppressWarnings("unchecked")
    private void userIs(UUID id, String email, String name, String role, boolean active) {
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(TENANT), eq(id)))
                .thenReturn(new TenantRoleFloorService.TenantUser(id, email, name, role, active));
    }

    private void otherActiveHoldersOfRole(String role, int count) {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(TENANT), eq(role), any(UUID.class)))
                .thenReturn(count);
    }

    // ---------------------------------------------------------------- refusals

    @Test void deactivatingTheLastTenantAdminIsRefusedAndTheMessageNamesTheConstraint() {
        userIs(ADMIN_ID, "raj.malhotra@meridianfab.com", "Raj Malhotra", "TENANT_ADMIN", true);
        otherActiveHoldersOfRole("TENANT_ADMIN", 0);

        ConflictException refusal = assertThrows(ConflictException.class,
                () -> service.setUserActive(new TenantRoleFloorService.SetActiveRequest(
                        ADMIN_ID, false, "offboarding")));

        assertTrue(refusal.getMessage().contains("TENANT_ADMIN"),
                "the refusal must name the role: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("tenant administrator/auditor floor"),
                "the refusal must name the constraint: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("promote another user to Tenant Admin first"),
                "the refusal must say what to do instead: " + refusal.getMessage());
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test void deactivatingTheLastAuditorIsRefusedAndTheMessageNamesTheConstraint() {
        userIs(AUDITOR_ID, "asha.rao@meridianfab.com", "Asha Rao", "AUDITOR", true);
        otherActiveHoldersOfRole("AUDITOR", 0);

        ConflictException refusal = assertThrows(ConflictException.class,
                () -> service.setUserActive(new TenantRoleFloorService.SetActiveRequest(
                        AUDITOR_ID, false, "offboarding")));

        assertTrue(refusal.getMessage().contains("AUDITOR"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("auditor with complete read and view"),
                "the refusal must describe what the seat is for: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("promote another user to Auditor first"),
                refusal.getMessage());
    }

    @Test void roleChangingTheLastTenantAdminIsRefused() {
        userIs(ADMIN_ID, "raj.malhotra@meridianfab.com", "Raj Malhotra", "TENANT_ADMIN", true);
        otherActiveHoldersOfRole("TENANT_ADMIN", 0);

        ConflictException refusal = assertThrows(ConflictException.class,
                () -> service.changeRole(new TenantRoleFloorService.ChangeRoleRequest(
                        ADMIN_ID, "SALES", "reorg")));

        assertTrue(refusal.getMessage().contains("moved to another role"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("tenant administrator/auditor floor"), refusal.getMessage());
    }

    @Test void deletingTheLastTenantAdminIsRefused() {
        userIs(ADMIN_ID, "raj.malhotra@meridianfab.com", "Raj Malhotra", "TENANT_ADMIN", true);
        otherActiveHoldersOfRole("TENANT_ADMIN", 0);

        ConflictException refusal = assertThrows(ConflictException.class,
                () -> service.assertCanDelete(ADMIN_ID));
        assertTrue(refusal.getMessage().contains("deleted"), refusal.getMessage());
    }

    // ---------------------------------------------------------------- allowed

    @Test void deactivatingAnAdminIsAllowedOnceASecondAdminExists() {
        userIs(ADMIN_ID, "raj.malhotra@meridianfab.com", "Raj Malhotra", "TENANT_ADMIN", true);
        otherActiveHoldersOfRole("TENANT_ADMIN", 1);

        service.assertCanDeactivate(ADMIN_ID);   // no throw is the assertion
    }

    @Test void aNonFloorRoleIsNotProtected() {
        UUID sales = UUID.randomUUID();
        userIs(sales, "priya.nair@meridianfab.com", "Priya Nair", "SALES", true);
        otherActiveHoldersOfRole("SALES", 0);

        service.assertCanDeactivate(sales);
    }

    @Test void anAlreadyInactiveAdminDoesNotTripTheFloorAgain() {
        userIs(ADMIN_ID, "raj.malhotra@meridianfab.com", "Raj Malhotra", "TENANT_ADMIN", false);
        otherActiveHoldersOfRole("TENANT_ADMIN", 0);

        service.assertCanDeactivate(ADMIN_ID);
    }

    @Test void movingAUserOntoAFloorRoleIsAlwaysAllowed() {
        UUID sales = UUID.randomUUID();
        userIs(sales, "priya.nair@meridianfab.com", "Priya Nair", "SALES", true);
        otherActiveHoldersOfRole("SALES", 0);

        service.assertCanChangeRole(sales, "TENANT_ADMIN");
    }

    // ------------------------------------------------------------ the refusal is evidence

    @Test void aRefusedDeactivationIsWrittenToTheActivityLogAsDenied() {
        userIs(ADMIN_ID, "raj.malhotra@meridianfab.com", "Raj Malhotra", "TENANT_ADMIN", true);
        otherActiveHoldersOfRole("TENANT_ADMIN", 0);

        assertThrows(ConflictException.class, () -> service.assertCanDeactivate(ADMIN_ID));

        org.mockito.ArgumentCaptor<UserActivityService.ActivityEvent> captured =
                org.mockito.ArgumentCaptor.forClass(UserActivityService.ActivityEvent.class);
        verify(activity).record(captured.capture());
        UserActivityService.ActivityEvent event = captured.getValue();
        assertEquals(UserActivityService.DENIED, event.outcome());
        assertEquals("SECURITY ROLE_FLOOR_REFUSED", event.action());
        assertTrue(event.denialReason().contains("tenant administrator/auditor floor"));
        assertEquals("tenant_administrator_auditor_floor", event.detail().get("constraintName"));
        assertEquals(ADMIN_ID.toString(), event.detail().get("targetUserId"));
    }

    /**
     * FR-AUD-014 applies to the refusal too. The 409 an administrator reads names
     * the person, because that is what makes it actionable; the row written to the
     * activity log identifies the same person by opaque id, because the log has a
     * different audience and the target user's work email is the personal data of
     * somebody who is not even the actor.
     */
    @Test void theLoggedRefusalCarriesNoEmailOrDisplayNameOfTheTargetUser() {
        userIs(ADMIN_ID, "raj.malhotra@meridianfab.com", "Raj Malhotra", "TENANT_ADMIN", true);
        otherActiveHoldersOfRole("TENANT_ADMIN", 0);

        ConflictException shownToTheAdministrator = assertThrows(ConflictException.class,
                () -> service.assertCanDeactivate(ADMIN_ID));
        assertTrue(shownToTheAdministrator.getMessage().contains("raj.malhotra@meridianfab.com"),
                "the response a human reads must name who is blocking the change");

        org.mockito.ArgumentCaptor<UserActivityService.ActivityEvent> captured =
                org.mockito.ArgumentCaptor.forClass(UserActivityService.ActivityEvent.class);
        verify(activity).record(captured.capture());
        UserActivityService.ActivityEvent event = captured.getValue();

        assertFalse(event.denialReason().contains("raj.malhotra@meridianfab.com"),
                "the target user's email reached the activity log: " + event.denialReason());
        assertFalse(event.denialReason().contains("Raj Malhotra"),
                "the target user's name reached the activity log: " + event.denialReason());
        assertFalse(String.valueOf(event.detail()).contains("@"),
                "an email address reached the activity detail: " + event.detail());
        assertTrue(event.denialReason().contains("TENANT_ADMIN"),
                "the log must still say which seat was protected");
    }

    // ------------------------------------------------------------------- AUDITOR

    @Test void auditorCannotDeactivateAUser() {
        signInAs("AUDITOR");
        ForbiddenException refusal = assertThrows(ForbiddenException.class,
                () -> service.setUserActive(new TenantRoleFloorService.SetActiveRequest(
                        ADMIN_ID, false, "why not")));
        assertTrue(refusal.getMessage().contains("read-only"), refusal.getMessage());
    }

    @Test void auditorCannotChangeARole() {
        signInAs("AUDITOR");
        assertThrows(ForbiddenException.class,
                () -> service.changeRole(new TenantRoleFloorService.ChangeRoleRequest(
                        ADMIN_ID, "SALES", "why not")));
    }

    @Test void auditorCannotRepairTheFloor() {
        signInAs("AUDITOR");
        assertThrows(ForbiddenException.class,
                () -> service.repair(new TenantRoleFloorService.RepairRequest(
                        ADMIN_ID, "AUDITOR", "closing the gap")));
    }

    @Test void auditorMayStillReadTheIntegrityReport() {
        signInAs("AUDITOR");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        service.report();   // no throw: the auditor's whole job is to read this
    }

    // -------------------------------------------------------------- the report

    @Test void theIntegrityReportListsAViolationWithoutRepairingIt() {
        var violation = new TenantRoleFloorService.FloorFinding(
                TENANT, "meridian", "Meridian Fabrication Group", "AUDITOR",
                "auditor with complete read and view", 0, false,
                "No active AUDITOR. This tenant has no auditor with complete read and view.",
                "Grant the AUDITOR role to a named, existing user of this tenant.");
        var healthy = new TenantRoleFloorService.FloorFinding(
                TENANT, "meridian", "Meridian Fabrication Group", "TENANT_ADMIN",
                "administrator with complete read and write", 1, true,
                "Exactly one active TENANT_ADMIN. Removing it will be refused.", null);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(healthy, violation));

        TenantRoleFloorService.FloorReport report = service.report();

        assertEquals(1, report.violations());
        assertEquals(1, report.tenantsInspected());
        assertFalse(report.crossTenant(), "a tenant admin sees only their own workspace");
        assertTrue(report.findings().stream().anyMatch(f -> !f.compliant()));
        // Nothing was written: reporting a gap must not silently close it.
        verify(jdbc, never()).update(anyString(), any(), any(), any());
        verify(audit, never()).recordWithReason(anyString(), anyString(), any(), anyString(),
                anyString(), any());
    }

    @Test void aPlatformOperatorGetsTheCrossTenantReport() {
        TenantContext.set(new TenantContext.Principal(TENANT, ADMIN_ID, "SUPER_ADMIN",
                "Platform", "ops@axiom.internal"));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        assertTrue(service.report().crossTenant());
    }

    @Test void repairRefusesARoleThatIsNotAFloorRole() {
        userIs(ADMIN_ID, "raj.malhotra@meridianfab.com", "Raj Malhotra", "TENANT_ADMIN", true);
        ConflictException refusal = assertThrows(ConflictException.class,
                () -> service.repair(new TenantRoleFloorService.RepairRequest(
                        ADMIN_ID, "SALES", "not a floor role")));
        assertTrue(refusal.getMessage().contains("floor role"), refusal.getMessage());
    }

    @Test void repairRefusesAnInactiveAccount() {
        UUID candidate = UUID.randomUUID();
        userIs(candidate, "asha.rao@meridianfab.com", "Asha Rao", "SALES", false);
        ConflictException refusal = assertThrows(ConflictException.class,
                () -> service.repair(new TenantRoleFloorService.RepairRequest(
                        candidate, "AUDITOR", "closing the gap")));
        assertTrue(refusal.getMessage().contains("Re-enable"), refusal.getMessage());
    }
}

package com.axiom.access;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.identity.TenantLifecycleService;
import com.axiom.tenancy.PlatformSession;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Approval → provisioning: the invariants that make a trial workspace safe to
 * hand to a stranger (FR-TEN-001).
 *
 * <p><b>On the rollback test.</b> These tests mock {@link JdbcTemplate}, so they
 * cannot exercise PostgreSQL's rollback — nothing here proves the database
 * behaves. What they DO prove is the half of the contract that lives in Java:
 * when any step fails, the method throws instead of continuing, and the steps
 * that would leave a usable-but-wrong workspace behind (marking the request
 * provisioned, issuing activation links) are never reached. The rollback itself
 * is the transaction boundary and was verified against the live database.
 */
class TrialProvisioningServiceTest {

    private JdbcTemplate jdbc;
    private TenantLifecycleService lifecycle;
    private TrialRequestService requests;
    private TrialProvisioningService service;

    private final UUID requestId = UUID.randomUUID();
    private final UUID newTenantId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        lifecycle = mock(TenantLifecycleService.class);
        requests = mock(TrialRequestService.class);
        service = new TrialProvisioningService(jdbc, new PlatformSession(mock(JdbcTemplate.class)),
                lifecycle, requests, mock(AuditService.class), "https://app.axiomcrm.test");
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SUPER_ADMIN", "Platform Operator", "ops@axiomcrm.com"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    private TrialRequestService.TrialRequestRow row(String status, UUID tenantId) {
        return new TrialRequestService.TrialRequestRow(requestId, "TRL-2026-0001", "Halden Marine Works",
                "ingrid@haldenmarine.example", "haldenmarine.example", "Ingrid Halvorsen",
                "Director of Operations", "251–1000", "Norway", null, status, 30, null, null, null,
                tenantId, tenantId == null ? null : "halden-marine-works", null, "203.0.113.7");
    }

    private void provisionSucceeds() {
        when(lifecycle.provision(any())).thenReturn(new TenantLifecycleService.ProvisionResult(
                newTenantId, "halden-marine-works", "active", adminUserId, true, List.of(), "ok"));
    }

    private void roleCountsAre(int admins, int auditors) {
        when(jdbc.queryForList(contains("group by role"), any(Object[].class))).thenReturn(List.of(
                Map.of("role", "TENANT_ADMIN", "n", admins),
                Map.of("role", "AUDITOR", "n", auditors)));
    }

    // ---------------------------------------------------------------- the invariant

    @Test void aProvisionedTrialGetsExactlyOneTenantAdminAndOneAuditor() {
        when(requests.load(requestId)).thenReturn(row("PENDING", null));
        provisionSucceeds();
        roleCountsAre(1, 1);

        TrialProvisioningService.ApprovalResult result = service.approve(requestId, null);

        assertTrue(result.created());
        assertEquals(1, result.tenantAdminCount());
        assertEquals(1, result.auditorCount());
        assertEquals(newTenantId, result.tenantId());
        assertEquals(2, result.activationLinks().size());
        assertEquals(List.of("TENANT_ADMIN", "AUDITOR"),
                result.activationLinks().stream().map(TrialProvisioningService.ActivationLink::role).toList());
        // The auditor account is created here, not conjured by the lifecycle service.
        verify(jdbc).update(contains("'AUDITOR', true, true"), any(Object[].class));
    }

    @Test void theTrialWindowIsThirtyDaysAndIsWrittenToTheCompanyAccount() {
        when(requests.load(requestId)).thenReturn(row("PENDING", null));
        provisionSucceeds();
        roleCountsAre(1, 1);

        TrialProvisioningService.ApprovalResult result = service.approve(requestId, null);

        assertEquals(30, result.trialDays());
        assertEquals(30, java.time.temporal.ChronoUnit.DAYS.between(result.trialStartAt(), result.trialEndsAt()));
        verify(jdbc).update(contains("update platform.company_account"), any(Object[].class));
    }

    @Test void activationLinksAreIssuedInsteadOfPasswords() {
        when(requests.load(requestId)).thenReturn(row("PENDING", null));
        provisionSucceeds();
        roleCountsAre(1, 1);

        TrialProvisioningService.ApprovalResult result = service.approve(requestId, null);

        for (TrialProvisioningService.ActivationLink link : result.activationLinks()) {
            assertTrue(link.url().startsWith("https://app.axiomcrm.test/activate/"), link.url());
        }
        assertTrue(result.note().contains("single-use"), result.note());
        // Nothing in the response, anywhere, is a password.
        assertFalse(result.note().toLowerCase().contains("password: "), result.note());
    }

    // ---------------------------------------------------------------- failure leaves nothing

    @Test void aWrongRoleCountAbortsBeforeTheWorkspaceIsUsable() {
        when(requests.load(requestId)).thenReturn(row("PENDING", null));
        provisionSucceeds();
        roleCountsAre(2, 1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.approve(requestId, null));

        assertTrue(error.getMessage().contains("exactly one TENANT_ADMIN and one AUDITOR"), error.getMessage());
        assertTrue(error.getMessage().contains("Nothing was kept"), error.getMessage());
        // The two writes that would make a broken workspace look finished.
        verify(jdbc, never()).update(contains("insert into platform.trial_activation"), any(Object[].class));
        verify(jdbc, never()).update(contains("set status = 'PROVISIONED'"), any(Object[].class));
    }

    @Test void aMissingAuditorAbortsToo() {
        when(requests.load(requestId)).thenReturn(row("PENDING", null));
        provisionSucceeds();
        roleCountsAre(1, 0);

        assertThrows(IllegalStateException.class, () -> service.approve(requestId, null));
        verify(jdbc, never()).update(contains("set status = 'PROVISIONED'"), any(Object[].class));
    }

    @Test void aFailureInsideTenantProvisioningStopsEverythingDownstream() {
        when(requests.load(requestId)).thenReturn(row("PENDING", null));
        when(lifecycle.provision(any())).thenThrow(new IllegalStateException("slug collision at commit"));

        assertThrows(IllegalStateException.class, () -> service.approve(requestId, null));

        // No auditor, no demo data, no activation link, no status change: every
        // later step is downstream of the throw, so a partial workspace is not
        // merely cleaned up afterwards — it is never built.
        verify(jdbc, never()).update(contains("'AUDITOR', true, true"), any(Object[].class));
        verify(jdbc, never()).update(contains("insert into crm.account"), any(Object[].class));
        verify(jdbc, never()).update(contains("insert into platform.trial_activation"), any(Object[].class));
        verify(jdbc, never()).update(contains("set status = 'PROVISIONED'"), any(Object[].class));
    }

    // ---------------------------------------------------------------- idempotency

    @Test void reApprovingAnAlreadyProvisionedRequestIsARead() {
        when(requests.load(requestId)).thenReturn(row("PROVISIONED", newTenantId));
        roleCountsAre(1, 1);

        TrialProvisioningService.ApprovalResult result = service.approve(requestId, null);

        assertFalse(result.created());
        assertEquals(newTenantId, result.tenantId());
        assertTrue(result.activationLinks().isEmpty());
        assertTrue(result.note().contains("already provisioned"), result.note());
        verify(lifecycle, never()).provision(any());
        verify(jdbc, never()).update(contains("insert into platform.trial_activation"), any(Object[].class));
        verify(jdbc, never()).update(contains("set status = 'PROVISIONED'"), any(Object[].class));
    }

    @Test void theRequestKeyIsDerivedFromTheReferenceSoARetryCannotDuplicateTheTenant() {
        when(requests.load(requestId)).thenReturn(row("PENDING", null));
        provisionSucceeds();
        roleCountsAre(1, 1);

        service.approve(requestId, null);

        org.mockito.ArgumentCaptor<TenantLifecycleService.ProvisionRequest> captor =
                org.mockito.ArgumentCaptor.forClass(TenantLifecycleService.ProvisionRequest.class);
        verify(lifecycle).provision(captor.capture());
        assertEquals("trial-request:TRL-2026-0001", captor.getValue().requestKey());
        assertEquals("TRIAL", captor.getValue().planCode());
    }

    // ---------------------------------------------------------------- refusals

    @Test void aRejectedRequestCannotBeApproved() {
        when(requests.load(requestId)).thenReturn(row("REJECTED", null));
        ConflictException error = assertThrows(ConflictException.class,
                () -> service.approve(requestId, null));
        assertTrue(error.getMessage().contains("REJECTED"), error.getMessage());
        verify(lifecycle, never()).provision(any());
    }

    @Test void anExpiredRequestCannotBeApproved() {
        when(requests.load(requestId)).thenReturn(row("EXPIRED", null));
        assertThrows(ConflictException.class, () -> service.approve(requestId, null));
    }

    @Test void aTenantAdministratorCannotApproveATrialRequest() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "TENANT_ADMIN", "Tenant Admin", "admin@meridianfab.com"));
        assertThrows(ForbiddenException.class, () -> service.approve(requestId, null));
        verify(lifecycle, never()).provision(any());
    }

    @Test void aReadOnlyPlatformRoleCannotApproveATrialRequest() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SUPER_AUDIT", "Platform Auditor", "audit@axiomcrm.com"));
        assertThrows(ForbiddenException.class, () -> service.approve(requestId, null));
        verify(lifecycle, never()).provision(any());
    }

    // ---------------------------------------------------------------- helpers

    @Test void aSlugIsDerivedFromTheCompanyNameAndAlwaysMatchesTheTenancyRule() {
        assertEquals("halden-marine-works", TrialProvisioningService.slugify("Halden Marine Works"));
        assertEquals("corvid-energy-systems", TrialProvisioningService.slugify("Corvid Energy Systems"));
        // A name starting with a digit cannot be a slug, so it is prefixed rather
        // than rejected — the operator did not choose the company's name.
        assertEquals("trial-2024-metals", TrialProvisioningService.slugify("2024 Metals"));
        assertEquals("axx", TrialProvisioningService.slugify("a"));
        for (String name : List.of("Halden Marine Works", "2024 Metals", "!!!", "a",
                "An Extremely Long Company Name That Goes Well Past The Slug Limit Indeed")) {
            String slug = TrialProvisioningService.slugify(name);
            assertTrue(slug.matches("^[a-z][a-z0-9-]{2,40}$"), name + " -> " + slug);
        }
    }

    @Test void aCollidingSlugIsSuffixedRatherThanFailingTheApproval() {
        when(jdbc.queryForList(contains("platform.tenant where lower(slug)"), eq(Integer.class),
                any(Object[].class)))
                .thenReturn(List.of(1)).thenReturn(List.of());

        assertEquals("halden-marine-works-2", service.uniqueSlug("Halden Marine Works"));
    }

    @Test void theGeneratedPasswordMeetsTheDefaultCompositionPolicy() {
        for (int i = 0; i < 50; i++) {
            String password = TrialProvisioningService.generatedPassword();
            assertTrue(password.length() >= 12, password);
            assertTrue(password.chars().anyMatch(Character::isUpperCase));
            assertTrue(password.chars().anyMatch(Character::isLowerCase));
            assertTrue(password.chars().anyMatch(Character::isDigit));
            assertTrue(password.chars().anyMatch(c -> !Character.isLetterOrDigit(c)));
        }
    }

    @Test void activationTokensAreStoredOnlyAsAHash() {
        String hash = TrialProvisioningService.sha256("some-token");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("^[0-9a-f]{64}$"));
        assertEquals(hash, TrialProvisioningService.sha256("some-token"));
        org.junit.jupiter.api.Assertions.assertNotEquals(hash,
                TrialProvisioningService.sha256("some-token "));
    }
}

package com.axiom.security;

import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RbacChangeApprovalServiceTest {

    private final MakerCheckerService approvals = mock(MakerCheckerService.class);
    private final PermissionAdminService permissions = mock(PermissionAdminService.class);
    private final RoleHierarchyService roles = mock(RoleHierarchyService.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final ObjectMapper json = new ObjectMapper();
    private RbacChangeApprovalService service;

    @BeforeEach
    void setUp() {
        service = new RbacChangeApprovalService(approvals, permissions, roles, authorization, json);
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SUPER_ADMIN", "Axiom Super Admin", "superadmin@axiomcrm.com"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void permissionAssignmentIsSubmittedInsteadOfAppliedImmediately() {
        UUID userId = UUID.randomUUID();
        UUID setId = UUID.randomUUID();
        PermissionAdminService.AssignRequest grant = new PermissionAdminService.AssignRequest(
                userId, setId, null, null, "Required for sales duties");
        MakerCheckerService.ApprovalRequest queued = request(UUID.randomUUID(), "PENDING", "{}");
        when(approvals.submit(argThat(request -> request.actionCode().equals("PERMISSION_GRANT")
                && request.entityId().equals(userId)
                && request.payload().get("permissionSetId").equals(setId.toString())))).thenReturn(queued);

        assertSame(queued, service.submitPermissionAssignment(grant));

        verify(approvals).submit(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(permissions);
    }

    @Test
    void approvalAppliesRoleGrantOnlyAfterApprovalPermissionAndFourEyesDecision() {
        UUID approvalId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        Instant expiry = Instant.parse("2027-01-01T00:00:00Z");
        String payload = json.createObjectNode()
                .put("changeType", "ROLE_ASSIGNMENT")
                .put("userId", userId.toString())
                .put("roleNodeId", roleId.toString())
                .put("expiresAt", expiry.toString()).toString();
        MakerCheckerService.ApprovalRequest pending = request(approvalId, "PENDING", payload);
        MakerCheckerService.ApprovalRequest approved = request(approvalId, "APPROVED", payload);
        when(approvals.find(approvalId)).thenReturn(pending);
        when(approvals.approve(approvalId, "Reviewed")).thenReturn(approved);

        assertSame(approved, service.approveAndApply(approvalId, "Reviewed"));

        verify(authorization).requirePermission("SYS.APPROVE_PERMISSION_GRANT", "approve a permission grant");
        verify(approvals).approve(approvalId, "Reviewed");
        verify(roles).assignUser(userId, roleId, expiry);
    }

    private static MakerCheckerService.ApprovalRequest request(UUID id, String status, String payload) {
        UUID initiator = UUID.randomUUID();
        return new MakerCheckerService.ApprovalRequest(id, "PERMISSION_GRANT", "APP_USER",
                UUID.randomUUID(), "Grant access", payload, initiator, "maker@example.com",
                Instant.now(), status, null, null, null, null);
    }
}

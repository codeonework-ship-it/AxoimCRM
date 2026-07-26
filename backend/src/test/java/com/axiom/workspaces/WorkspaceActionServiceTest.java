package com.axiom.workspaces;

import com.axiom.audit.AuditService;
import com.axiom.automation.WorkflowGateService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class WorkspaceActionServiceTest {
    private WorkspaceActionService service;
    private final UUID id = UUID.randomUUID();

    @BeforeEach void setUp() {
        service = new WorkspaceActionService(
                mock(JdbcTemplate.class), mock(AuditService.class), mock(OutboxWriter.class),
                mock(WorkflowGateService.class));
        TenantContext.set(new TenantContext.Principal(
                UUID.randomUUID(), UUID.randomUUID(), "SUPER_AUDIT", "Auditor", "audit@example.test"));
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    @Test void readOnlyRoleCannotSubmitForecast() {
        assertThrows(ForbiddenException.class,
                () -> service.submitForecast(id, new WorkspaceActionService.ForecastSubmitRequest("reviewed")));
    }

    @Test void readOnlyRoleCannotResolveCases() {
        assertThrows(ForbiddenException.class,
                () -> service.resolveCase(id, new WorkspaceActionService.CaseResolveRequest("resolved")));
    }

    @Test void readOnlyRoleCannotSimulateAutomation() {
        assertThrows(ForbiddenException.class,
                () -> service.simulateAutomation(id, new WorkspaceActionService.AutomationSimulateRequest(25)));
    }

    @Test void readOnlyRoleCannotValidateMigration() {
        assertThrows(ForbiddenException.class, () -> service.validateMigration(id));
    }

    @Test void readOnlyRoleCannotAcknowledgeMobileSync() {
        assertThrows(ForbiddenException.class, () -> service.acknowledgeMobileSync(id));
    }

    @Test void readOnlyRoleCannotActivateContract() {
        assertThrows(ForbiddenException.class,
                () -> service.activateContract(id, new WorkspaceActionService.ContractActivateRequest("signed://x")));
    }

    @Test void readOnlyRoleCannotCompleteCampaign() {
        assertThrows(ForbiddenException.class,
                () -> service.completeCampaign(id, new WorkspaceActionService.CampaignCompleteRequest("complete")));
    }

    @Test void readOnlyRoleCannotActivatePartner() {
        assertThrows(ForbiddenException.class, () -> service.activatePartner(id));
    }

    @Test void readOnlyRoleCannotAcceptCopilotRecommendation() {
        assertThrows(ForbiddenException.class,
                () -> service.acceptCopilotRecommendation(id, new WorkspaceActionService.CopilotDecisionRequest("accept")));
    }

    @Test void readOnlyRoleCannotClearBfsiOnboarding() {
        assertThrows(ForbiddenException.class,
                () -> service.clearBfsiOnboarding(id, new WorkspaceActionService.BfsiClearRequest("clear")));
    }

    @Test void readOnlyRoleCannotRefreshDashboard() {
        assertThrows(ForbiddenException.class,
                () -> service.refreshDashboard(id, new WorkspaceActionService.DashboardRefreshRequest("refresh")));
    }

    @Test void readOnlyRoleCannotVerifyIntegrationContract() {
        assertThrows(ForbiddenException.class, () -> service.verifyIntegrationContract(id));
    }

    @Test void readOnlyRoleCannotRefreshSandbox() {
        assertThrows(ForbiddenException.class,
                () -> service.refreshSandbox(id, new WorkspaceActionService.SandboxRefreshRequest("refresh")));
    }

    @Test void readOnlyRoleCannotExportAuditPack() {
        assertThrows(ForbiddenException.class,
                () -> service.exportAuditPack(id, new WorkspaceActionService.AuditPackExportRequest("SECURE_DOWNLOAD")));
    }

    @Test void readOnlyRoleCannotOfferCommodityEnquiry() {
        assertThrows(ForbiddenException.class, () -> service.offerCommodityEnquiry(id));
    }

    @Test void blockedWorkflowGateRefusesTheBusinessCommandWithNextStep() {
        WorkflowGateService.GateStatus blocked = new WorkflowGateService.GateStatus(
                UUID.randomUUID(), "CONTRACT", id, "PRC-CONTRACT-LIFECYCLE", "DRAFT",
                "BLOCKED", 1, "Add the signed document reference.", List.of(), Instant.now());

        assertThrows(ConflictException.class,
                () -> WorkspaceActionService.assertWorkflowGateReady(blocked));
    }

    @Test void readyAndNoProcessGateStatesRemainCompatible() {
        WorkflowGateService.GateStatus ready = new WorkflowGateService.GateStatus(
                UUID.randomUUID(), "CONTRACT", id, "PRC-CONTRACT-LIFECYCLE", "DRAFT",
                "READY", 0, "Ready to activate.", List.of(), Instant.now());
        WorkflowGateService.GateStatus noProcess = new WorkflowGateService.GateStatus(
                UUID.randomUUID(), "CONTRACT", id, null, "DRAFT",
                "NO_PROCESS", 0, "No process configured.", List.of(), Instant.now());

        WorkspaceActionService.assertWorkflowGateReady(ready);
        WorkspaceActionService.assertWorkflowGateReady(noProcess);
    }
}

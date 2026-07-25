package com.axiom.workspaces;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class WorkspaceActionServiceTest {
    private WorkspaceActionService service;
    private final UUID id = UUID.randomUUID();

    @BeforeEach void setUp() {
        service = new WorkspaceActionService(
                mock(JdbcTemplate.class), mock(AuditService.class), mock(OutboxWriter.class));
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
}

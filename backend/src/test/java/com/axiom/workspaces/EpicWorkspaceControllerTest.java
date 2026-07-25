package com.axiom.workspaces;

import com.axiom.api.PageResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpicWorkspaceControllerTest {
    @Test void delegatesForecastPaginationAndFilters() {
        EpicWorkspaceService service = mock(EpicWorkspaceService.class);
        EpicWorkspaceController controller = new EpicWorkspaceController(service, mock(WorkspaceExportService.class));
        EpicWorkspaceService.WorkspacePage page = new EpicWorkspaceService.WorkspacePage(
                "FORECASTING", "Forecast", "desc", List.of(), PageResult.of(List.of(), 2, 100, 0));
        when(service.forecast("commit", "SUBMITTED", 2)).thenReturn(page);

        assertEquals(page, controller.forecast("commit", "SUBMITTED", 2));
        verify(service).forecast("commit", "SUBMITTED", 2);
    }

    @Test void delegatesMigrationPaginationAndFilters() {
        EpicWorkspaceService service = mock(EpicWorkspaceService.class);
        EpicWorkspaceController controller = new EpicWorkspaceController(service, mock(WorkspaceExportService.class));
        EpicWorkspaceService.WorkspacePage page = new EpicWorkspaceService.WorkspacePage(
                "MIGRATION", "Migration", "desc", List.of(), PageResult.of(List.of(), 1, 100, 0));
        when(service.migrations("contacts", "READY_TO_IMPORT", 1)).thenReturn(page);

        assertEquals(page, controller.migrations("contacts", "READY_TO_IMPORT", 1));
        verify(service).migrations("contacts", "READY_TO_IMPORT", 1);
    }

    @Test void delegatesNextFiveEpicWorkspaces() {
        EpicWorkspaceService service = mock(EpicWorkspaceService.class);
        EpicWorkspaceController controller = new EpicWorkspaceController(service, mock(WorkspaceExportService.class));
        EpicWorkspaceService.WorkspacePage page = new EpicWorkspaceService.WorkspacePage(
                "CHANNEL", "Partners", "desc", List.of(), PageResult.of(List.of(), 0, 100, 0));

        when(service.partners("gold", "ACTIVE", 0)).thenReturn(page);
        when(service.automation("sla", "ACTIVE", 1)).thenReturn(page);
        when(service.analytics("revenue", "ACTIVE", 2)).thenReturn(page);
        when(service.copilot("risk", "READY", 3)).thenReturn(page);
        when(service.mobile("ios", "ACTIVE", 4)).thenReturn(page);

        assertEquals(page, controller.partners("gold", "ACTIVE", 0));
        assertEquals(page, controller.automation("sla", "ACTIVE", 1));
        assertEquals(page, controller.analytics("revenue", "ACTIVE", 2));
        assertEquals(page, controller.copilot("risk", "READY", 3));
        assertEquals(page, controller.mobile("ios", "ACTIVE", 4));
        verify(service).partners("gold", "ACTIVE", 0);
        verify(service).automation("sla", "ACTIVE", 1);
        verify(service).analytics("revenue", "ACTIVE", 2);
        verify(service).copilot("risk", "READY", 3);
        verify(service).mobile("ios", "ACTIVE", 4);
    }

    @Test void delegatesFinalFiveEpicWorkspaces() {
        EpicWorkspaceService service = mock(EpicWorkspaceService.class);
        EpicWorkspaceController controller = new EpicWorkspaceController(service, mock(WorkspaceExportService.class));
        EpicWorkspaceService.WorkspacePage page = new EpicWorkspaceService.WorkspacePage(
                "INTEGRATION", "Integrations", "desc", List.of(), PageResult.of(List.of(), 0, 100, 0));

        when(service.integrations("outbox", "ACTIVE", 0)).thenReturn(page);
        when(service.sandbox("uat", "ACTIVE", 1)).thenReturn(page);
        when(service.audit("access", "READY", 2)).thenReturn(page);
        when(service.bfsi("kyc", "CLEARED", 3)).thenReturn(page);
        when(service.commodity("copper", "OFFERED", 4)).thenReturn(page);

        assertEquals(page, controller.integrations("outbox", "ACTIVE", 0));
        assertEquals(page, controller.sandbox("uat", "ACTIVE", 1));
        assertEquals(page, controller.audit("access", "READY", 2));
        assertEquals(page, controller.bfsi("kyc", "CLEARED", 3));
        assertEquals(page, controller.commodity("copper", "OFFERED", 4));
        verify(service).integrations("outbox", "ACTIVE", 0);
        verify(service).sandbox("uat", "ACTIVE", 1);
        verify(service).audit("access", "READY", 2);
        verify(service).bfsi("kyc", "CLEARED", 3);
        verify(service).commodity("copper", "OFFERED", 4);
    }

    @Test void exportsFilteredWorkspacePageAsAttachment() {
        EpicWorkspaceService service = mock(EpicWorkspaceService.class);
        WorkspaceExportService exports = mock(WorkspaceExportService.class);
        EpicWorkspaceController controller = new EpicWorkspaceController(service, exports);
        WorkspaceExportService.FilePayload file = new WorkspaceExportService.FilePayload(
                "ok".getBytes(StandardCharsets.UTF_8), "text/plain", "forecast-workspace-page-1.txt");
        when(exports.export("forecast", WorkspaceExportService.ExportFormat.XLSX, "commit", "SUBMITTED", 0)).thenReturn(file);

        ResponseEntity<byte[]> response = controller.export(
                "forecast", WorkspaceExportService.ExportFormat.XLSX, "commit", "SUBMITTED", 0);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("attachment; filename=\"forecast-workspace-page-1.txt\"",
                response.getHeaders().getFirst("Content-Disposition"));
        assertEquals("ok", new String(response.getBody(), StandardCharsets.UTF_8));
        verify(exports).export("forecast", WorkspaceExportService.ExportFormat.XLSX, "commit", "SUBMITTED", 0);
    }
}

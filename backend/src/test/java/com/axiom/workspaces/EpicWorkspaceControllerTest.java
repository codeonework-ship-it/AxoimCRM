package com.axiom.workspaces;

import com.axiom.api.PageResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpicWorkspaceControllerTest {
    @Test void delegatesForecastPaginationAndFilters() {
        EpicWorkspaceService service = mock(EpicWorkspaceService.class);
        EpicWorkspaceController controller = new EpicWorkspaceController(service);
        EpicWorkspaceService.WorkspacePage page = new EpicWorkspaceService.WorkspacePage(
                "FORECASTING", "Forecast", "desc", List.of(), PageResult.of(List.of(), 2, 100, 0));
        when(service.forecast("commit", "SUBMITTED", 2)).thenReturn(page);

        assertEquals(page, controller.forecast("commit", "SUBMITTED", 2));
        verify(service).forecast("commit", "SUBMITTED", 2);
    }

    @Test void delegatesMigrationPaginationAndFilters() {
        EpicWorkspaceService service = mock(EpicWorkspaceService.class);
        EpicWorkspaceController controller = new EpicWorkspaceController(service);
        EpicWorkspaceService.WorkspacePage page = new EpicWorkspaceService.WorkspacePage(
                "MIGRATION", "Migration", "desc", List.of(), PageResult.of(List.of(), 1, 100, 0));
        when(service.migrations("contacts", "READY_TO_IMPORT", 1)).thenReturn(page);

        assertEquals(page, controller.migrations("contacts", "READY_TO_IMPORT", 1));
        verify(service).migrations("contacts", "READY_TO_IMPORT", 1);
    }
}

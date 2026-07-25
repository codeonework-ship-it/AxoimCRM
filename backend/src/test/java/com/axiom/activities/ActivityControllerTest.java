package com.axiom.activities;

import com.axiom.api.PageResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityControllerTest {
    @Test void listDelegatesFilterAndPaginationContract() {
        ActivityService service = mock(ActivityService.class);
        ActivityController controller = new ActivityController(service);
        PageResult<ActivityService.ActivityRow> page = PageResult.of(List.of(), 2, 100, 0);
        when(service.list("renewal", "TASK", "OPEN", null, null, 2)).thenReturn(page);

        assertEquals(page, controller.list("renewal", "TASK", "OPEN", null, null, 2));
        verify(service).list("renewal", "TASK", "OPEN", null, null, 2);
    }
}

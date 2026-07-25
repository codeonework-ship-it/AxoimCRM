package com.axiom.api;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadControllerTest {
    @Test void delegatesDisqualificationWithReasonAndRecycleDate() {
        QueryService queries = mock(QueryService.class);
        LeadService service = mock(LeadService.class);
        LeadController controller = new LeadController(queries, service);
        UUID id = UUID.randomUUID();
        LocalDate recycle = LocalDate.now().plusDays(30);
        LeadService.DisqualificationResult result = new LeadService.DisqualificationResult(
                id, "DISQUALIFIED", "NOT_A_FIT", recycle);

        when(service.disqualify(id, "NOT_A_FIT", "Out of ICP", recycle)).thenReturn(result);

        assertEquals(result, controller.disqualify(id,
                new LeadController.DisqualifyRequest("NOT_A_FIT", "Out of ICP", recycle)));
        verify(service).disqualify(id, "NOT_A_FIT", "Out of ICP", recycle);
    }
}

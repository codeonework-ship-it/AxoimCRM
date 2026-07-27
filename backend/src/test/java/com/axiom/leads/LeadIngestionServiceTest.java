package com.axiom.leads;

import com.axiom.audit.AuditService;
import com.axiom.security.AuthorizationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeadIngestionServiceTest {
    @Test void oneBadRowDoesNotRollBackAcceptedRows() {
        LeadIngestionWorker worker = mock(LeadIngestionWorker.class);
        LeadBatchStore store = mock(LeadBatchStore.class);
        AuditService audit = mock(AuditService.class);
        LeadIngestionService service = new LeadIngestionService(worker, store, audit,
                mock(AuthorizationService.class));
        UUID batchId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();
        when(store.open("BULK_API", 2)).thenReturn(batchId);

        LeadIngestRequest valid = request("Maya", "Torres", "Meridian", "maya@meridian.example");
        LeadIngestRequest invalid = request("", "NoFirstName", "Meridian", "bad-email");
        when(worker.ingest(eq(valid), eq("BULK_API"), eq(batchId))).thenReturn(
                new LeadIngestionWorker.Outcome("CREATED", leadId, "LEAD", 72,
                        "Assigned to Sales", "Lead captured and routed successfully."));

        LeadIngestionService.BatchResult result = service.bulk(List.of(valid, invalid));

        assertEquals(2, result.submitted());
        assertEquals(1, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals("CREATED", result.rows().get(0).status());
        assertEquals("REJECTED", result.rows().get(1).status());
        assertTrue(result.rows().get(1).errors().stream().anyMatch(message -> message.contains("First name")));
        verify(store).finish(batchId, 1, 1);
        verify(store).record(eq(batchId), eq(1), eq("CREATED"), eq(leadId), any(), eq(valid));
        verify(store).record(eq(batchId), eq(2), eq("REJECTED"), eq(null), any(), eq(invalid));
    }

    @Test void validatorReportsEveryProblemOnTheSameRow() {
        List<String> problems = LeadValidation.problems(request("", "", "", "not-an-email"));
        assertEquals(4, problems.size());
    }

    private static LeadIngestRequest request(String first, String last, String company, String email) {
        return new LeadIngestRequest(first, last, company, email, null, null, "API", null,
                null, null, null, null, null, null, null, Map.of(), Map.of());
    }
}

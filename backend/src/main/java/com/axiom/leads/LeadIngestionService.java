package com.axiom.leads;

import com.axiom.audit.AuditService;
import com.axiom.security.AuthorizationService;
import com.axiom.security.SecurableObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Partial-success bulk orchestration for US-E05-01. */
@Service
public class LeadIngestionService {
    public static final int MAX_BATCH = 1000;

    private final LeadIngestionWorker worker;
    private final LeadBatchStore batches;
    private final AuditService audit;
    private final AuthorizationService authorization;

    public LeadIngestionService(LeadIngestionWorker worker, LeadBatchStore batches, AuditService audit,
                                AuthorizationService authorization) {
        this.worker = worker;
        this.batches = batches;
        this.audit = audit;
        this.authorization = authorization;
    }

    public record RowResult(int rowNumber, String status, UUID recordId, String recordType,
                            Integer score, String assignment, List<String> errors, String message) {}
    public record BatchResult(UUID batchId, int submitted, int accepted, int rejected,
                              List<RowResult> rows, String note) {}

    public RowResult single(LeadIngestRequest request) {
        authorization.requireCreate(SecurableObject.LEAD);
        LeadIngestionWorker.Outcome outcome = worker.ingest(request, "API", null);
        return row(1, outcome);
    }

    public BatchResult bulk(List<LeadIngestRequest> requests) {
        authorization.requireCreate(SecurableObject.LEAD);
        List<LeadIngestRequest> safe = requests == null ? List.of() : requests;
        if (safe.isEmpty()) throw new IllegalArgumentException("Provide at least one lead row.");
        if (safe.size() > MAX_BATCH) throw new IllegalArgumentException("A bulk request can contain at most 1,000 lead rows.");
        UUID batchId = batches.open("BULK_API", safe.size());
        List<RowResult> results = new ArrayList<>();
        int accepted = 0;
        for (int index = 0; index < safe.size(); index++) {
            int rowNumber = index + 1;
            LeadIngestRequest request = safe.get(index);
            try {
                LeadIngestionWorker.Outcome outcome = worker.ingest(request, "BULK_API", batchId);
                RowResult result = row(rowNumber, outcome);
                results.add(result);
                batches.record(batchId, rowNumber, result.status(),
                        "LEAD".equals(result.recordType()) ? result.recordId() : null, result.message(), request);
                accepted++;
            } catch (RuntimeException ex) {
                List<String> errors = LeadValidation.problems(request);
                if (errors.isEmpty()) errors = List.of(ex.getMessage() == null ? "The row could not be processed." : ex.getMessage());
                RowResult result = new RowResult(rowNumber, "REJECTED", null, null, null, null,
                        errors, String.join(" ", errors));
                results.add(result);
                batches.record(batchId, rowNumber, "REJECTED", null, result.message(), request);
            }
        }
        int rejected = safe.size() - accepted;
        batches.finish(batchId, accepted, rejected);
        audit.record("LEAD_BULK_INGEST", "LEAD_INGESTION_BATCH", batchId,
                "Processed " + safe.size() + " lead rows",
                Map.of("submitted", safe.size(), "accepted", accepted, "rejected", rejected));
        return new BatchResult(batchId, safe.size(), accepted, rejected, List.copyOf(results),
                rejected == 0 ? "Every lead row was accepted."
                        : rejected + " row(s) need correction; accepted rows were not rolled back.");
    }

    private static RowResult row(int number, LeadIngestionWorker.Outcome outcome) {
        return new RowResult(number, outcome.status(), outcome.recordId(), outcome.recordType(),
                outcome.score(), outcome.assignment(), List.of(), outcome.message());
    }
}

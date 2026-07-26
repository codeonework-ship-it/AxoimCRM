package com.axiom.leads;

import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Small transaction boundary used by the partial-success batch orchestrator. */
@Component
public class LeadBatchStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public LeadBatchStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public UUID open(String source, int submitted) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into leads.ingestion_batch
                  (id, tenant_id, source, submitted_count, accepted_count, rejected_count, created_by)
                values (?, ?, ?, ?, 0, ?, ?)
                """, id, TenantContext.get().tenantId(), source, submitted, submitted,
                TenantContext.get().userId());
        return id;
    }

    @Transactional
    public void record(UUID batchId, int rowNumber, String status, UUID leadId,
                       String message, LeadIngestRequest request) {
        String payload;
        try {
            payload = json.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            payload = "{}";
        }
        jdbc.update("""
                insert into leads.ingestion_record
                  (tenant_id, batch_id, row_number, status, lead_id, message, payload)
                values (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, TenantContext.get().tenantId(), batchId, rowNumber, status, leadId, message, payload);
    }

    @Transactional
    public void finish(UUID batchId, int accepted, int rejected) {
        jdbc.update("""
                update leads.ingestion_batch set accepted_count = ?, rejected_count = ?
                where tenant_id = ? and id = ?
                """, accepted, rejected, TenantContext.get().tenantId(), batchId);
    }
}

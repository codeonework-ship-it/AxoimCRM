package com.axiom.audit;

import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record AuditRow(UUID id, String actorName, String actorRole, String action,
                           String entityType, UUID entityId, String summary,
                           Map<String, Object> details, String correlationId, Instant occurredAt) {}

    @Transactional
    public void record(String action, String entityType, UUID entityId, String summary,
                       Map<String, ?> details) {
        TenantContext.Principal p = TenantContext.get();
        try {
            jdbc.update("""
                    insert into audit_event
                      (tenant_id, actor_id, actor_name, actor_role, action, entity_type,
                       entity_id, summary, details, correlation_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, p.tenantId(), p.userId(), p.displayName(), p.role(), action,
                    entityType, entityId, summary, json.writeValueAsString(details), MDC.get("correlationId"));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Audit details could not be serialized", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<AuditRow> list(String entityType, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        String type = entityType == null || entityType.isBlank() ? null : entityType.toUpperCase();
        return jdbc.query("""
                select id, actor_name, actor_role, action, entity_type, entity_id, summary,
                       details::text, correlation_id, occurred_at
                from audit_event
                where tenant_id = ? and (? is null or entity_type = ?)
                order by occurred_at desc
                limit ?
                """, (rs, i) -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> details = json.readValue(rs.getString("details"), Map.class);
                        return new AuditRow(rs.getObject("id", UUID.class), rs.getString("actor_name"),
                                rs.getString("actor_role"), rs.getString("action"), rs.getString("entity_type"),
                                rs.getObject("entity_id", UUID.class), rs.getString("summary"), details,
                                rs.getString("correlation_id"), rs.getTimestamp("occurred_at").toInstant());
                    } catch (JsonProcessingException ex) {
                        throw new IllegalStateException(ex);
                    }
                }, TenantContext.get().tenantId(), type, type, limit);
    }
}

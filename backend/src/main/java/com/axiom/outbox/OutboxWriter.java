package com.axiom.outbox;

import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Writes a domain event into the transactional outbox (ADR-003). Must be
 * called INSIDE the business transaction — that is the entire guarantee: the
 * event row commits or rolls back atomically with the business change, so
 * there are never phantom events nor lost events.
 *
 * Uses the primary (axiom_app) datasource: the insert passes the tenant RLS
 * WITH CHECK because TenantSessionAspect has already bound app.tenant_id on
 * this transaction's connection.
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void write(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        UUID tenantId = TenantContext.get().tenantId();
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Outbox payload is not serializable", e);
        }
        jdbc.update(
                "insert into outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload) "
                        + "values (?, ?, ?, ?, ?::jsonb)",
                tenantId, aggregateType, aggregateId, eventType, json);
    }
}

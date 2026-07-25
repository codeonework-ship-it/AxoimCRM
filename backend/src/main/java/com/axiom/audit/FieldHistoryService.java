package com.axiom.audit;

import com.axiom.tenancy.TenantContext;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * FR-AUD-002 — field change history: before value, after value, actor, timestamp
 * and source, per changed field.
 *
 * <p>Most rows arrive here automatically: {@link AuditService} decomposes any audit
 * event whose details carry {@code before}/{@code after} maps. This service exists
 * for the explicit path — a module that knows exactly which fields changed and
 * wants them recorded without inventing an audit action for it — and for reading
 * the history back ("who changed the close date").
 */
@Service
public class FieldHistoryService {

    public record FieldChange(UUID id, String entityType, UUID entityId, String fieldName,
                              String oldValue, String newValue, String source,
                              String changedByName, Instant changedAt, String correlationId) {}

    private final JdbcTemplate jdbc;

    public FieldHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records one row per differing field between {@code before} and {@code after}.
     *
     * @return the number of fields recorded; zero when nothing actually changed,
     *         which is a legitimate outcome and not an error
     */
    @Transactional
    public int recordChanges(String entityType, UUID entityId, UUID auditEventId,
                             Map<String, ?> before, Map<String, ?> after) {
        TenantContext.Principal p = TenantContext.get();
        String source = AuditSourceContext.get();
        Set<String> fields = new TreeSet<>();
        if (before != null) fields.addAll(before.keySet());
        if (after != null) fields.addAll(after.keySet());
        int written = 0;
        for (String field : fields) {
            String oldValue = value(before, field);
            String newValue = value(after, field);
            if (Objects.equals(oldValue, newValue)) continue;
            jdbc.update("""
                    insert into governance.field_history
                      (tenant_id, audit_event_id, entity_type, entity_id, field_name,
                       old_value, new_value, source, changed_by, changed_by_name, correlation_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, p.tenantId(), auditEventId, entityType, entityId, field,
                    oldValue, newValue, source, p.userId(), p.displayName(), MDC.get("correlationId"));
            written++;
        }
        return written;
    }

    private static String value(Map<String, ?> map, String field) {
        Object raw = map == null ? null : map.get(field);
        return raw == null ? null : String.valueOf(raw);
    }

    @Transactional(readOnly = true)
    public List<FieldChange> history(String entityType, UUID entityId, String fieldName, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        StringBuilder sql = new StringBuilder("""
                select id, entity_type, entity_id, field_name, old_value, new_value, source,
                       changed_by_name, changed_at, correlation_id
                from governance.field_history
                where tenant_id = ?
                """);
        if (entityType != null && !entityType.isBlank()) {
            sql.append(" and upper(entity_type) = ?");
            args.add(entityType.trim().toUpperCase(Locale.ROOT));
        }
        if (entityId != null) {
            sql.append(" and entity_id = ?");
            args.add(entityId);
        }
        if (fieldName != null && !fieldName.isBlank()) {
            sql.append(" and field_name = ?");
            args.add(fieldName.trim());
        }
        sql.append(" order by changed_at desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> new FieldChange(
                rs.getObject("id", UUID.class), rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class), rs.getString("field_name"),
                rs.getString("old_value"), rs.getString("new_value"), rs.getString("source"),
                rs.getString("changed_by_name"), rs.getTimestamp("changed_at").toInstant(),
                rs.getString("correlation_id")), args.toArray());
    }
}

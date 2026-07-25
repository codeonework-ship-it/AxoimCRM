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

/**
 * FR-AUD-005 — export auditing with actor, object, filter criteria, row count and
 * destination. US-E20-03 is explicit that recording "an export happened" is not
 * enough: the criteria and the count are the evidence.
 *
 * <p>Two ways in. Modules that already write an EXPORT audit event are projected
 * here automatically by {@link AuditService} — no change was required in the six
 * modules that ship exports today. Modules that want to be precise call
 * {@link #recordExport} directly and get an exact count instead of whatever the
 * details map happened to contain.
 */
@Service
public class ExportAuditService {

    public record ExportEvent(UUID id, String actorName, String objectType,
                              Map<String, Object> filterCriteria, Long rowCount, boolean rowCountKnown,
                              String destination, String format, String correlationId, Instant at) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;

    public ExportAuditService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    /**
     * Records an export with an exact row count. Also writes the audit event, so a
     * caller cannot record the export evidence and forget the trail entry.
     *
     * @param rowCount exact number of rows that left the system; null only when the
     *                 producing path genuinely cannot count them, which is recorded
     *                 as such rather than as zero
     */
    @Transactional
    public void recordExport(String objectType, Map<String, ?> filterCriteria, Long rowCount,
                             String destination, String format) {
        TenantContext.Principal p = TenantContext.get();
        Map<String, ?> criteria = filterCriteria == null ? Map.of() : filterCriteria;
        UUID eventId = audit.recordEvent("DATA_EXPORT", objectType, null,
                "Exported " + (rowCount == null ? "an unreported number of" : rowCount) + " " + objectType + " record(s)",
                null, Map.of("criteria", criteria, "destination", destination == null ? "FILE_DOWNLOAD" : destination,
                        "format", format == null ? "" : format,
                        "rowCountReported", rowCount != null));
        try {
            jdbc.update("""
                    insert into governance.export_audit
                      (tenant_id, audit_event_id, actor_id, actor_name, object_type,
                       filter_criteria, row_count, row_count_known, destination, format, correlation_id)
                    values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                    """, p.tenantId(), eventId, p.userId(), p.displayName(), objectType,
                    json.writeValueAsString(criteria), rowCount, rowCount != null,
                    destination == null ? "FILE_DOWNLOAD" : destination, format,
                    MDC.get("correlationId"));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Export filter criteria could not be serialized", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<ExportEvent> list(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        return jdbc.query("""
                select id, actor_name, object_type, filter_criteria::text, row_count, row_count_known,
                       destination, format, correlation_id, at
                from governance.export_audit
                where tenant_id = ?
                order by at desc
                limit ?
                """, (rs, i) -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> criteria = json.readValue(rs.getString("filter_criteria"), Map.class);
                Long count = (Long) rs.getObject("row_count");
                return new ExportEvent(rs.getObject("id", UUID.class), rs.getString("actor_name"),
                        rs.getString("object_type"), criteria, count, rs.getBoolean("row_count_known"),
                        rs.getString("destination"), rs.getString("format"),
                        rs.getString("correlation_id"), rs.getTimestamp("at").toInstant());
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException(ex);
            }
        }, TenantContext.get().tenantId(), limit);
    }
}

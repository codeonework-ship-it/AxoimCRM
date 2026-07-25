package com.axiom.audit;

import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The single writer of {@code governance.audit_event}.
 *
 * <p>Three properties are deliberately NOT implemented here:
 * <ul>
 *   <li>the per-tenant {@code sequence_no}</li>
 *   <li>{@code prev_hash} / {@code hash}</li>
 *   <li>rejection of UPDATE and DELETE</li>
 * </ul>
 * All three live in the database (V70). Six modules write audit events; a chain
 * maintained in application code is racy under concurrent inserts and is bypassed
 * entirely by the first caller that writes SQL directly. {@code FR-AUD-001} and
 * US-E20-01 require that alteration be <em>impossible</em>, not merely refused by
 * this class.
 *
 * <p>What this class does add is <b>projection</b>: an audit event that carries
 * before/after values also lands in {@code governance.field_history}
 * ({@code FR-AUD-002}), and an export-shaped event also lands in
 * {@code governance.export_audit} ({@code FR-AUD-005}). Deriving those from the
 * event the calling module already writes means the other modules did not have to
 * change to be covered — and a module that adds an export tomorrow is covered
 * without knowing this file exists.
 */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Actions whose audit event is also export evidence (FR-AUD-005). */
    private static final Set<String> EXPORT_ACTIONS = Set.of(
            "EXPORT", "BULK_EXPORT", "DATA_EXPORT", "TENANT_EXPORT", "REPORT_DOWNLOAD",
            "REPORT_EXPORT", "PRINT", "DSR_PORTABILITY", "DSR_ACCESS", "EVIDENCE_PACK");

    private static final Set<String> COUNT_KEYS = Set.of("rowCount", "row_count", "records", "recordCount");
    private static final Set<String> CRITERIA_EXCLUDED = Set.of(
            "rowCount", "row_count", "records", "recordCount", "destination", "format", "before", "after");

    public record AuditRow(UUID id, String actorName, String actorRole, String action,
                           String entityType, UUID entityId, String summary,
                           Map<String, Object> details, String correlationId, Instant occurredAt,
                           String impersonatorEmail, String reason) {}

    /** Audit row with its tamper-evidence position, for the compliance workspace. */
    public record ChainedAuditRow(UUID id, long sequenceNo, String hash, String prevHash,
                                  String actorName, String actorRole, String action, String source,
                                  String entityType, UUID entityId, String summary,
                                  Map<String, Object> details, String correlationId, Instant occurredAt,
                                  String impersonatorEmail, String reason) {}

    @Transactional
    public void record(String action, String entityType, UUID entityId, String summary,
                       Map<String, ?> details) {
        recordWithReason(action, entityType, entityId, summary, null, details);
    }

    /**
     * Writes an audit event carrying a recorded reason and, when the request is
     * an impersonated support session, <em>both</em> identities.
     *
     * <p>The impersonator is read from ambient context rather than passed by the
     * caller on purpose (FR-TEN-011: "every action records both the impersonator
     * and the impersonated identity"). A caller that has to remember to pass it
     * is a caller that will eventually forget, and the one event that matters
     * most is the one somebody wrote in a hurry.
     */
    @Transactional
    public void recordWithReason(String action, String entityType, UUID entityId, String summary,
                                String reason, Map<String, ?> details) {
        recordEvent(action, entityType, entityId, summary, reason, details);
    }

    /**
     * As {@link #recordWithReason} but returns the new event id, so a caller that
     * needs to reference the event — a field-history row, an export record, a DSR
     * outcome — can link to it instead of correlating on timestamp.
     *
     * @return the new event id, or null when the insert did not report one
     */
    @Transactional
    public UUID recordEvent(String action, String entityType, UUID entityId, String summary,
                            String reason, Map<String, ?> details) {
        TenantContext.Principal p = TenantContext.get();
        TenantContext.Impersonator impersonator = TenantContext.impersonator();
        Map<String, ?> safeDetails = details == null ? Map.of() : details;
        String source = AuditSourceContext.get();
        UUID eventId;
        try {
            eventId = jdbc.queryForObject("""
                    insert into governance.audit_event
                      (tenant_id, actor_id, actor_name, actor_role, action, entity_type,
                       entity_id, summary, details, correlation_id,
                       impersonator_id, impersonator_email, reason, source)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                    returning id
                    """, UUID.class, p.tenantId(), p.userId(), p.displayName(), p.role(), action,
                    entityType, entityId, summary, json.writeValueAsString(safeDetails), MDC.get("correlationId"),
                    impersonator == null ? null : impersonator.userId(),
                    impersonator == null ? null : impersonator.email(),
                    reason, source);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Audit details could not be serialized", ex);
        }
        projectFieldHistory(eventId, entityType, entityId, source, safeDetails);
        projectExportAudit(eventId, action, entityType, safeDetails);
        return eventId;
    }

    /**
     * FR-AUD-002. Any audit event whose details carry {@code before} and
     * {@code after} maps is decomposed into one field-history row per changed
     * field, with before value, after value, actor, timestamp and source.
     */
    private void projectFieldHistory(UUID eventId, String entityType, UUID entityId,
                                     String source, Map<String, ?> details) {
        Map<String, Object> before = asMap(details.get("before"));
        Map<String, Object> after = asMap(details.get("after"));
        if (before == null && after == null) return;
        TenantContext.Principal p = TenantContext.get();
        Set<String> fields = new TreeSet<>();
        if (before != null) fields.addAll(before.keySet());
        if (after != null) fields.addAll(after.keySet());
        for (String field : fields) {
            String oldValue = text(before == null ? null : before.get(field));
            String newValue = text(after == null ? null : after.get(field));
            if (java.util.Objects.equals(oldValue, newValue)) continue;
            jdbc.update("""
                    insert into governance.field_history
                      (tenant_id, audit_event_id, entity_type, entity_id, field_name,
                       old_value, new_value, source, changed_by, changed_by_name, correlation_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, p.tenantId(), eventId, entityType, entityId, field,
                    oldValue, newValue, source, p.userId(), p.displayName(), MDC.get("correlationId"));
        }
    }

    /**
     * FR-AUD-005. Export-shaped events are also written to
     * {@code governance.export_audit} with the filter criteria and row count the
     * producing module reported. When no count was reported that fact is recorded
     * as {@code row_count_known = false} rather than defaulted to zero: "we do
     * not know how many rows left" and "no rows left" are different findings.
     */
    private void projectExportAudit(UUID eventId, String action, String entityType, Map<String, ?> details) {
        String upper = action == null ? "" : action.toUpperCase(Locale.ROOT);
        boolean isExport = EXPORT_ACTIONS.contains(upper) || upper.endsWith("_EXPORT") || upper.startsWith("EXPORT_");
        if (!isExport) return;
        TenantContext.Principal p = TenantContext.get();
        Long rowCount = null;
        for (String key : COUNT_KEYS) {
            Object value = details.get(key);
            if (value instanceof Number number) { rowCount = number.longValue(); break; }
        }
        Map<String, Object> criteria = new LinkedHashMap<>();
        details.forEach((key, value) -> { if (!CRITERIA_EXCLUDED.contains(key)) criteria.put(key, value); });
        Object destination = details.get("destination");
        Object format = details.get("format");
        try {
            jdbc.update("""
                    insert into governance.export_audit
                      (tenant_id, audit_event_id, actor_id, actor_name, object_type,
                       filter_criteria, row_count, row_count_known, destination, format, correlation_id)
                    values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                    """, p.tenantId(), eventId, p.userId(), p.displayName(), entityType,
                    json.writeValueAsString(criteria), rowCount, rowCount != null,
                    destination == null ? "FILE_DOWNLOAD" : String.valueOf(destination),
                    format == null ? null : String.valueOf(format), MDC.get("correlationId"));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Export criteria could not be serialized", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Transactional(readOnly = true)
    public List<AuditRow> list(String entityType, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        String type = entityType == null || entityType.isBlank() ? null : entityType.toUpperCase();
        return jdbc.query("""
                select id, actor_name, actor_role, action, entity_type, entity_id, summary,
                       details::text, correlation_id, occurred_at, impersonator_email, reason
                from governance.audit_event
                where tenant_id = ? and (?::text is null or entity_type = ?::text)
                order by occurred_at desc
                limit ?
                """, (rs, i) -> {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> details = json.readValue(rs.getString("details"), Map.class);
                        return new AuditRow(rs.getObject("id", UUID.class), rs.getString("actor_name"),
                                rs.getString("actor_role"), rs.getString("action"), rs.getString("entity_type"),
                                rs.getObject("entity_id", UUID.class), rs.getString("summary"), details,
                                rs.getString("correlation_id"), rs.getTimestamp("occurred_at").toInstant(),
                                rs.getString("impersonator_email"), rs.getString("reason"));
                    } catch (JsonProcessingException ex) {
                        throw new IllegalStateException(ex);
                    }
                }, TenantContext.get().tenantId(), type, type, limit);
    }

    /**
     * Filterable trail for the compliance workspace, including each event's
     * position in the tamper-evidence chain so an auditor can quote a sequence
     * number rather than a timestamp.
     */
    @Transactional(readOnly = true)
    public List<ChainedAuditRow> search(String entityType, String action, String actor,
                                        String source, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        String type = blankToNull(entityType);
        String act = blankToNull(action);
        String who = blankToNull(actor);
        String src = blankToNull(source);
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        StringBuilder sql = new StringBuilder("""
                select id, sequence_no, hash, prev_hash, actor_name, actor_role, action, source,
                       entity_type, entity_id, summary, details::text, correlation_id, occurred_at,
                       impersonator_email, reason
                from governance.audit_event
                where tenant_id = ?
                """);
        if (type != null) { sql.append(" and upper(entity_type) = ?"); args.add(type.toUpperCase(Locale.ROOT)); }
        if (act != null) { sql.append(" and upper(action) like ?"); args.add("%" + act.toUpperCase(Locale.ROOT) + "%"); }
        if (who != null) { sql.append(" and lower(actor_name) like ?"); args.add("%" + who.toLowerCase(Locale.ROOT) + "%"); }
        if (src != null) { sql.append(" and source = ?"); args.add(AuditSourceContext.normalize(src)); }
        sql.append(" order by sequence_no desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> details = json.readValue(rs.getString("details"), Map.class);
                return new ChainedAuditRow(rs.getObject("id", UUID.class), rs.getLong("sequence_no"),
                        rs.getString("hash"), rs.getString("prev_hash"), rs.getString("actor_name"),
                        rs.getString("actor_role"), rs.getString("action"), rs.getString("source"),
                        rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                        rs.getString("summary"), details, rs.getString("correlation_id"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("impersonator_email"),
                        rs.getString("reason"));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException(ex);
            }
        }, args.toArray());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

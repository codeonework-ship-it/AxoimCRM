package com.axiom.audit;

import com.axiom.tenancy.TenantContext;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * FR-AUD-003 — read auditing for designated sensitive objects and fields.
 *
 * <p>"Designated" is the operative word: auditing every read of every field would
 * multiply the audit volume by the read/write ratio of a CRM (roughly two orders
 * of magnitude) and bury the events that matter. What is designated lives in
 * {@code governance.sensitive_field_registry}, which is tenant-scoped because one
 * customer's ordinary field is another's regulated one.
 *
 * <p>Every event records actor, record, timestamp and <b>access path</b> — the last
 * of these is what distinguishes "opened the contact record" from "pulled 4,000
 * contacts through the API", which is the distinction an investigation cares about.
 */
@Service
public class ReadAuditService {

    public record SensitiveField(String entityType, String fieldName, String classification,
                                 boolean readAudited, boolean maskInLogs, boolean active) {}

    public record ReadEvent(UUID id, String actorName, String actorRole, String entityType,
                            UUID entityId, List<String> fieldNames, String accessPath,
                            String purpose, int recordCount, String correlationId, Instant at) {}

    private final JdbcTemplate jdbc;

    public ReadAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a read of sensitive data. Writes unconditionally: the caller has
     * already decided this path exposes personal data.
     *
     * @param accessPath how the data was reached, e.g. {@code GET /api/v1/compliance/subjects/CONTACT/{id}}
     */
    @Transactional
    public void recordRead(String entityType, UUID entityId, List<String> fieldNames,
                           String accessPath, String purpose, int recordCount) {
        TenantContext.Principal p = TenantContext.get();
        jdbc.update("""
                insert into governance.read_audit
                  (tenant_id, actor_id, actor_name, actor_role, entity_type, entity_id,
                   field_names, access_path, purpose, record_count, correlation_id, ip)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, p.tenantId(), p.userId(), p.displayName(), p.role(),
                entityType, entityId,
                fieldNames == null ? new String[0] : fieldNames.toArray(String[]::new),
                accessPath, purpose, Math.max(recordCount, 0), MDC.get("correlationId"),
                TenantContext.clientIp());
    }

    /**
     * Records a read only if at least one of the fields is designated read-audited
     * for this tenant. Use this on a general-purpose read path where the caller
     * does not know whether the payload is sensitive.
     *
     * @return true when an event was written
     */
    @Transactional
    public boolean recordDesignatedRead(String entityType, UUID entityId, List<String> fieldNames,
                                        String accessPath, String purpose, int recordCount) {
        List<String> designated = designatedFields(entityType);
        List<String> matched = fieldNames == null ? List.of()
                : fieldNames.stream().filter(designated::contains).toList();
        if (matched.isEmpty()) return false;
        recordRead(entityType, entityId, matched, accessPath, purpose, recordCount);
        return true;
    }

    @Transactional(readOnly = true)
    public List<String> designatedFields(String entityType) {
        return jdbc.queryForList("""
                select field_name from governance.sensitive_field_registry
                where tenant_id = ? and upper(entity_type) = ? and active = true and read_audited = true
                """, String.class, TenantContext.get().tenantId(),
                entityType == null ? "" : entityType.toUpperCase(Locale.ROOT));
    }

    @Transactional(readOnly = true)
    public List<SensitiveField> registry() {
        return jdbc.query("""
                select entity_type, field_name, classification, read_audited, mask_in_logs, active
                from governance.sensitive_field_registry
                where tenant_id = ?
                order by entity_type, field_name
                """, (rs, i) -> new SensitiveField(rs.getString("entity_type"), rs.getString("field_name"),
                rs.getString("classification"), rs.getBoolean("read_audited"),
                rs.getBoolean("mask_in_logs"), rs.getBoolean("active")),
                TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public List<ReadEvent> list(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        return jdbc.query("""
                select id, actor_name, actor_role, entity_type, entity_id, field_names,
                       access_path, purpose, record_count, correlation_id, at
                from governance.read_audit
                where tenant_id = ?
                order by at desc
                limit ?
                """, (rs, i) -> {
            String[] fields = rs.getArray("field_names") == null ? new String[0]
                    : Arrays.stream((Object[]) rs.getArray("field_names").getArray())
                        .map(String::valueOf).toArray(String[]::new);
            return new ReadEvent(rs.getObject("id", UUID.class), rs.getString("actor_name"),
                    rs.getString("actor_role"), rs.getString("entity_type"),
                    rs.getObject("entity_id", UUID.class), List.of(fields),
                    rs.getString("access_path"), rs.getString("purpose"), rs.getInt("record_count"),
                    rs.getString("correlation_id"), rs.getTimestamp("at").toInstant());
        }, TenantContext.get().tenantId(), limit);
    }
}

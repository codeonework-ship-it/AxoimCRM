package com.axiom.migration;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.migration.MigrationConnectionService.ConnectionRow;
import com.axiom.migration.MigrationModel.ObjectPlan;
import com.axiom.migration.MigrationModel.PlanContext;
import com.axiom.migration.SourceContract.SourceField;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Migration plans and the mapping review (FR-MIG-002).
 *
 * <h2>The acknowledgement gate</h2>
 * The FRD rule is short: "unmapped source fields must be listed explicitly.
 * Silent omission of source data is not acceptable." A list nobody has to read
 * is not much better than no list, so the plan carries
 * {@code unmapped_acknowledged_at/by/count} and {@link MigrationRunService}
 * refuses a real import until a named person has acknowledged the exact number
 * of fields that will be lost. The count is stored with the acknowledgement: if
 * a re-discovery adds a new unmapped field afterwards, the acknowledgement no
 * longer covers the current list and the gate closes again.
 *
 * <p>A dry run is deliberately NOT gated. Iterating mapping to dry run and back
 * is the workflow the module is built around, and forcing an acknowledgement
 * before every iteration would train the operator to click through it.
 */
@Service
public class MigrationPlanService {

    private final JdbcTemplate jdbc;
    private final MigrationConnectionService connections;
    private final SourceAdapterRegistry adapters;
    private final AuditService audit;
    private final ObjectMapper json;

    public MigrationPlanService(JdbcTemplate jdbc, MigrationConnectionService connections,
                                SourceAdapterRegistry adapters, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.adapters = adapters;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ requests and rows

    public record CreatePlanRequest(@NotNull UUID connectionId,
                                    @NotBlank @Size(max = 120) String name,
                                    @Min(1) @Max(365) Integer retentionDays,
                                    Boolean sampleData) {}

    public record MappingEdit(@NotBlank String sourceObject,
                              @NotBlank String sourceField,
                              @Size(max = 32) String targetEntity,
                              @Size(max = 64) String targetField,
                              @NotBlank @Size(max = 16) String status,
                              @Size(max = 500) String note) {}

    public record PlanRow(UUID id, String name, UUID connectionId, String connectionName, String vendor,
                          String status, int retentionDays, boolean sampleData,
                          Instant unmappedAcknowledgedAt, int unmappedAcknowledgedCount,
                          Instant deltaWatermark, Instant importedAt, Instant createdAt,
                          long mappedFields, long unmappedFields, int mappingVersion) {}

    public record MappingRow(UUID id, String sourceObject, String sourceField, String sourceDataType,
                             boolean custom, String targetEntity, String targetField, String status,
                             String origin, String note) {}

    /**
     * The mapping review payload. {@code unmapped} is a first-class list rather
     * than something the caller has to derive by filtering — the whole point is
     * that it cannot be overlooked.
     */
    public record MappingReview(UUID planId, String planName, List<MappingRow> mappings,
                                List<MappingRow> unmapped, List<String> unmappedObjects,
                                int mappedCount, int unmappedCount,
                                boolean acknowledgementCurrent, Instant acknowledgedAt,
                                String acknowledgementStatement) {}

    public record MappingRevisionRow(UUID id, int versionNo, String reason, UUID createdBy,
                                     Instant createdAt, int fieldCount) {}

    private record MappingSnapshotRow(String sourceObject, String sourceField, String targetEntity,
                                      String targetField, String status, String origin, String note) {}

    // ------------------------------------------------------------------ create

    @Transactional
    public PlanRow create(CreatePlanRequest request) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requireImport(principal.role());
        ConnectionRow connection = connections.connection(request.connectionId());

        UUID planId;
        try {
            planId = jdbc.queryForObject("""
                    insert into migration.plan (tenant_id, connection_id, name, retention_days,
                                                is_sample_data, created_by)
                    values (?, ?, ?, ?, ?, ?)
                    returning id
                    """, UUID.class, principal.tenantId(), connection.id(), request.name().trim(),
                    request.retentionDays() == null ? 30 : request.retentionDays(),
                    Boolean.TRUE.equals(request.sampleData()), principal.userId());
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("A migration plan named \"" + request.name().trim()
                    + "\" already exists in this tenant");
        }

        proposeMapping(planId, connection);
        snapshotMapping(planId, "Initial proposed mapping");
        audit.record("MIGRATION_PLAN_CREATED", "MIGRATION_PLAN", planId,
                "Created migration plan \"" + request.name().trim() + "\" over " + connection.vendor()
                + " source \"" + connection.name() + "\"",
                Map.of("connectionId", connection.id().toString(), "vendor", connection.vendor()));
        return plan(planId);
    }

    /**
     * Propose a mapping for every discovered field, and store the unmapped ones
     * as rows. Re-runnable: a re-discovery followed by this call refreshes
     * proposals while leaving USER-origin corrections in place.
     */
    @Transactional
    public MappingReview propose(UUID planId) {
        CrmRole.requireImport(TenantContext.get().role());
        PlanRow plan = plan(planId);
        proposeMapping(planId, connections.connection(plan.connectionId()));
        snapshotMapping(planId, "Mapping proposal refreshed after schema discovery");
        return review(planId);
    }

    private void proposeMapping(UUID planId, ConnectionRow connection) {
        UUID tenantId = TenantContext.get().tenantId();

        List<Object[]> objects = jdbc.query("""
                select api_name, proposed_target from migration.source_object
                where tenant_id = ? and connection_id = ? order by api_name
                """, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2)},
                tenantId, connection.id());
        if (objects.isEmpty()) {
            throw new ConflictException("Source \"" + connection.name() + "\" has not been discovered yet. "
                    + "Run schema discovery before proposing a mapping.");
        }

        for (Object[] object : objects) {
            String objectApiName = (String) object[0];
            String target = (String) object[1];
            List<SourceField> fields = jdbc.query("""
                    select api_name, label, data_type, is_custom, nullable, sample_value
                    from migration.source_field
                    where tenant_id = ? and connection_id = ? and object_api_name = ?
                    order by api_name
                    """, (rs, i) -> new SourceField(rs.getString("api_name"), rs.getString("label"),
                            rs.getString("data_type"), rs.getBoolean("is_custom"),
                            rs.getBoolean("nullable"), rs.getString("sample_value")),
                    tenantId, connection.id(), objectApiName);

            Map<String, String> references = references(connection, objectApiName);

            for (MappingProposer.ProposedMapping proposal :
                    MappingProposer.propose(objectApiName, target, fields, references)) {
                jdbc.update("""
                        insert into migration.field_mapping
                          (tenant_id, plan_id, source_object, source_field, source_data_type, is_custom,
                           target_entity, target_field, status, origin, note)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED', ?)
                        on conflict (tenant_id, plan_id, source_object, source_field) do update
                           set source_data_type = excluded.source_data_type,
                               is_custom = excluded.is_custom,
                               target_entity = case when field_mapping.origin = 'USER'
                                                    then field_mapping.target_entity else excluded.target_entity end,
                               target_field  = case when field_mapping.origin = 'USER'
                                                    then field_mapping.target_field else excluded.target_field end,
                               status        = case when field_mapping.origin = 'USER'
                                                    then field_mapping.status else excluded.status end,
                               note          = case when field_mapping.origin = 'USER'
                                                    then field_mapping.note else excluded.note end,
                               updated_at = now()
                        """, tenantId, planId, proposal.sourceObject(), proposal.sourceField(),
                        proposal.sourceDataType(), proposal.custom(), proposal.targetEntity(),
                        proposal.targetField(), proposal.status(), proposal.note());
            }
        }
        jdbc.update("update migration.plan set status = case when status = 'DRAFT' then 'MAPPED' else status end, "
                + "updated_at = now() where tenant_id = ? and id = ?", tenantId, planId);
    }

    /** Reference fields, from the fixture adapter when the source is one. */
    private Map<String, String> references(ConnectionRow connection, String objectApiName) {
        if (adapters.require(connection.vendor()) instanceof FixtureSourceAdapter fixture) {
            return fixture.references(connections.session(connection), objectApiName);
        }
        return Map.of();
    }

    // ------------------------------------------------------------------ mapping review

    @Transactional(readOnly = true)
    public MappingReview review(UUID planId) {
        PlanRow plan = plan(planId);
        List<MappingRow> mappings = jdbc.query("""
                select id, source_object, source_field, source_data_type, is_custom, target_entity,
                       target_field, status, origin, note
                from migration.field_mapping
                where tenant_id = ? and plan_id = ?
                order by source_object, status desc, source_field
                """, MAPPING_MAPPER, TenantContext.get().tenantId(), planId);

        List<MappingRow> unmapped = mappings.stream().filter(m -> "UNMAPPED".equals(m.status())).toList();
        Set<String> unmappedObjects = new LinkedHashSet<>(
                unmapped.stream().filter(m -> m.targetEntity() == null).map(MappingRow::sourceObject).toList());
        int mappedCount = (int) mappings.stream().filter(m -> "MAPPED".equals(m.status())).count();

        boolean current = plan.unmappedAcknowledgedAt() != null
                && plan.unmappedAcknowledgedCount() == unmapped.size();

        String statement = unmapped.isEmpty()
                ? "Every discovered source field has an Axiom destination. Nothing will be lost."
                : unmapped.size() + " source field(s) have no Axiom destination and will NOT be migrated: "
                  + String.join(", ", unmapped.stream()
                        .map(m -> m.sourceObject() + "." + m.sourceField()).toList())
                  + ". Acknowledge this list before importing.";

        return new MappingReview(planId, plan.name(), mappings, unmapped, List.copyOf(unmappedObjects),
                mappedCount, unmapped.size(), current, plan.unmappedAcknowledgedAt(), statement);
    }

    @Transactional
    public MappingReview edit(UUID planId, List<MappingEdit> edits) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requireImport(principal.role());
        plan(planId);

        for (MappingEdit edit : edits) {
            String status = edit.status().toUpperCase(Locale.ROOT);
            if (!Set.of("MAPPED", "UNMAPPED", "IGNORED").contains(status)) {
                throw new IllegalArgumentException("Mapping status must be MAPPED, UNMAPPED or IGNORED");
            }
            if ("MAPPED".equals(status)) {
                TargetSchema.entity(edit.targetEntity())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown Axiom entity " + edit.targetEntity()))
                        .field(edit.targetField())
                        .orElseThrow(() -> new IllegalArgumentException("Axiom entity " + edit.targetEntity()
                                + " has no field " + edit.targetField()));
            }
            int updated = jdbc.update("""
                    update migration.field_mapping
                       set target_entity = ?, target_field = ?, status = ?, note = ?,
                           origin = 'USER', updated_at = now()
                     where tenant_id = ? and plan_id = ? and source_object = ? and source_field = ?
                    """, "MAPPED".equals(status) ? edit.targetEntity().toUpperCase(Locale.ROOT) : null,
                    "MAPPED".equals(status) ? edit.targetField() : null, status, edit.note(),
                    principal.tenantId(), planId, edit.sourceObject(), edit.sourceField());
            if (updated == 0) {
                throw new NotFoundException("Plan has no mapping row for " + edit.sourceObject()
                        + "." + edit.sourceField());
            }
        }

        // Any edit invalidates a prior acknowledgement: the operator signed off a
        // specific list of losses, and the list has changed.
        invalidateAcknowledgement(planId, principal.tenantId());

        audit.record("MIGRATION_MAPPING_EDITED", "MIGRATION_PLAN", planId,
                edits.size() + " field mapping(s) corrected by the operator",
                Map.of("edits", String.valueOf(edits.size())));
        snapshotMapping(planId, "Operator mapping changes");
        return review(planId);
    }

    @Transactional(readOnly = true)
    public List<MappingRevisionRow> revisions(UUID planId) {
        plan(planId);
        return jdbc.query("""
                select id, version_no, reason, created_by, created_at, jsonb_array_length(mappings) field_count
                from migration.mapping_revision
                where tenant_id = ? and plan_id = ? order by version_no desc
                """, (rs, i) -> new MappingRevisionRow(rs.getObject("id", UUID.class),
                        rs.getInt("version_no"), rs.getString("reason"),
                        rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("field_count")), TenantContext.get().tenantId(), planId);
    }

    /** Restore is append-only: the restored state becomes a new revision. */
    @Transactional
    public MappingReview restore(UUID planId, int versionNo) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requireImport(principal.role());
        plan(planId);
        List<String> payloads = jdbc.query("""
                select mappings::text from migration.mapping_revision
                where tenant_id = ? and plan_id = ? and version_no = ?
                """, (rs, i) -> rs.getString(1), principal.tenantId(), planId, versionNo);
        if (payloads.isEmpty()) throw new NotFoundException("No mapping revision " + versionNo);

        List<MappingSnapshotRow> snapshot;
        try {
            snapshot = json.readValue(payloads.get(0), new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored mapping revision " + versionNo + " is unreadable", ex);
        }

        jdbc.update("""
                update migration.field_mapping set target_entity = null, target_field = null,
                    status = 'UNMAPPED', origin = 'USER', note = 'Not present in restored revision', updated_at = now()
                where tenant_id = ? and plan_id = ?
                """, principal.tenantId(), planId);
        for (MappingSnapshotRow row : snapshot) {
            jdbc.update("""
                    update migration.field_mapping set target_entity = ?, target_field = ?, status = ?,
                        origin = ?, note = ?, updated_at = now()
                    where tenant_id = ? and plan_id = ? and source_object = ? and source_field = ?
                    """, row.targetEntity(), row.targetField(), row.status(), row.origin(), row.note(),
                    principal.tenantId(), planId, row.sourceObject(), row.sourceField());
        }
        invalidateAcknowledgement(planId, principal.tenantId());
        snapshotMapping(planId, "Restored mapping revision " + versionNo);
        audit.record("MIGRATION_MAPPING_RESTORED", "MIGRATION_PLAN", planId,
                "Restored mapping revision " + versionNo,
                Map.of("restoredVersion", String.valueOf(versionNo), "fieldCount", String.valueOf(snapshot.size())));
        return review(planId);
    }

    private void snapshotMapping(UUID planId, String reason) {
        TenantContext.Principal principal = TenantContext.get();
        List<MappingSnapshotRow> rows = jdbc.query("""
                select source_object, source_field, target_entity, target_field, status, origin, note
                from migration.field_mapping where tenant_id = ? and plan_id = ?
                order by source_object, source_field
                """, (rs, i) -> new MappingSnapshotRow(rs.getString("source_object"),
                        rs.getString("source_field"), rs.getString("target_entity"),
                        rs.getString("target_field"), rs.getString("status"),
                        rs.getString("origin"), rs.getString("note")), principal.tenantId(), planId);
        final String payload;
        try {
            payload = json.writeValueAsString(rows);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not record the mapping revision", ex);
        }
        Integer version = jdbc.queryForObject("""
                update migration.plan set mapping_version = mapping_version + 1, updated_at = now()
                where tenant_id = ? and id = ? returning mapping_version
                """, Integer.class, principal.tenantId(), planId);
        jdbc.update("""
                insert into migration.mapping_revision
                  (tenant_id, plan_id, version_no, reason, mappings, created_by)
                values (?, ?, ?, ?, cast(? as jsonb), ?)
                """, principal.tenantId(), planId, version, reason, payload, principal.userId());
    }

    private void invalidateAcknowledgement(UUID planId, UUID tenantId) {
        jdbc.update("""
                update migration.plan set unmapped_acknowledged_at = null, unmapped_acknowledged_by = null,
                                          unmapped_acknowledged_count = 0, updated_at = now()
                where tenant_id = ? and id = ?
                """, tenantId, planId);
    }

    @Transactional
    public MappingReview acknowledgeUnmapped(UUID planId) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requireImport(principal.role());
        MappingReview review = review(planId);

        jdbc.update("""
                update migration.plan
                   set unmapped_acknowledged_at = now(), unmapped_acknowledged_by = ?,
                       unmapped_acknowledged_count = ?, status = 'ACKNOWLEDGED', updated_at = now()
                 where tenant_id = ? and id = ?
                """, principal.userId(), review.unmappedCount(), principal.tenantId(), planId);

        audit.record("MIGRATION_UNMAPPED_ACKNOWLEDGED", "MIGRATION_PLAN", planId,
                principal.displayName() + " acknowledged that " + review.unmappedCount()
                + " source field(s) will not be migrated",
                Map.of("unmappedCount", String.valueOf(review.unmappedCount()),
                        "fields", String.join(", ", review.unmapped().stream()
                                .map(m -> m.sourceObject() + "." + m.sourceField()).toList())));
        return review(planId);
    }

    // ------------------------------------------------------------------ executable plan

    /**
     * Reduce a stored plan to the executable shape the engine runs on. Called on
     * the worker tier, so it takes no request context beyond the bound tenant.
     */
    @Transactional(readOnly = true)
    public PlanContext context(UUID planId) {
        UUID tenantId = TenantContext.get().tenantId();
        PlanRow plan = plan(planId);
        ConnectionRow connection = connections.connection(plan.connectionId());

        List<Object[]> objects = jdbc.query("""
                select api_name, proposed_target from migration.source_object
                where tenant_id = ? and connection_id = ? order by api_name
                """, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2)}, tenantId, connection.id());

        List<MappingRow> mappings = jdbc.query("""
                select id, source_object, source_field, source_data_type, is_custom, target_entity,
                       target_field, status, origin, note
                from migration.field_mapping where tenant_id = ? and plan_id = ?
                """, MAPPING_MAPPER, tenantId, planId);

        List<ObjectPlan> objectPlans = new ArrayList<>();
        for (Object[] object : objects) {
            String apiName = (String) object[0];
            String target = (String) object[1];
            Map<String, String> mapped = new LinkedHashMap<>();
            List<String> unmapped = new ArrayList<>();
            List<String> ignored = new ArrayList<>();
            for (MappingRow row : mappings) {
                if (!row.sourceObject().equals(apiName)) continue;
                switch (row.status()) {
                    case "MAPPED" -> mapped.put(row.sourceField(), row.targetField());
                    case "IGNORED" -> ignored.add(row.sourceField());
                    default -> unmapped.add(row.sourceField());
                }
                if ("MAPPED".equals(row.status()) && row.targetEntity() != null) target = row.targetEntity();
            }
            objectPlans.add(new ObjectPlan(apiName, mapped.isEmpty() ? null : target, mapped,
                    List.copyOf(unmapped), List.copyOf(ignored),
                    references(connection, apiName), moneyFields(connection, apiName)));
        }

        return new PlanContext(planId, plan.name(), connection.id(), connection.vendor(),
                plan.sampleData(), plan.deltaWatermark(), List.copyOf(objectPlans));
    }

    private List<String> moneyFields(ConnectionRow connection, String objectApiName) {
        if (adapters.require(connection.vendor()) instanceof FixtureSourceAdapter fixture) {
            return fixture.moneyFields(connections.session(connection), objectApiName);
        }
        return List.of();
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<PlanRow> list() {
        return jdbc.query(PLAN_SELECT + " where p.tenant_id = ? order by p.created_at desc",
                PLAN_MAPPER, TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public PlanRow plan(UUID planId) {
        List<PlanRow> rows = jdbc.query(PLAN_SELECT + " where p.tenant_id = ? and p.id = ?",
                PLAN_MAPPER, TenantContext.get().tenantId(), planId);
        if (rows.isEmpty()) throw new NotFoundException("No migration plan " + planId);
        return rows.get(0);
    }

    private static final String PLAN_SELECT = """
            select p.id, p.name, p.connection_id, c.name as connection_name, c.vendor, p.status,
                   p.retention_days, p.is_sample_data, p.unmapped_acknowledged_at,
                   p.unmapped_acknowledged_count, p.delta_watermark, p.imported_at, p.created_at,
                   p.mapping_version,
                   (select count(*) from migration.field_mapping m
                     where m.tenant_id = p.tenant_id and m.plan_id = p.id and m.status = 'MAPPED') as mapped_fields,
                   (select count(*) from migration.field_mapping m
                     where m.tenant_id = p.tenant_id and m.plan_id = p.id and m.status = 'UNMAPPED') as unmapped_fields
            from migration.plan p
            join migration.source_connection c on c.tenant_id = p.tenant_id and c.id = p.connection_id
            """;

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, i) -> new PlanRow(
            rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getObject("connection_id", UUID.class), rs.getString("connection_name"), rs.getString("vendor"),
            rs.getString("status"), rs.getInt("retention_days"), rs.getBoolean("is_sample_data"),
            rs.getTimestamp("unmapped_acknowledged_at") == null ? null
                    : rs.getTimestamp("unmapped_acknowledged_at").toInstant(),
            rs.getInt("unmapped_acknowledged_count"),
            rs.getTimestamp("delta_watermark") == null ? null : rs.getTimestamp("delta_watermark").toInstant(),
            rs.getTimestamp("imported_at") == null ? null : rs.getTimestamp("imported_at").toInstant(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getLong("mapped_fields"), rs.getLong("unmapped_fields"), rs.getInt("mapping_version"));

    private static final RowMapper<MappingRow> MAPPING_MAPPER = (rs, i) -> new MappingRow(
            rs.getObject("id", UUID.class), rs.getString("source_object"), rs.getString("source_field"),
            rs.getString("source_data_type"), rs.getBoolean("is_custom"), rs.getString("target_entity"),
            rs.getString("target_field"), rs.getString("status"), rs.getString("origin"), rs.getString("note"));
}

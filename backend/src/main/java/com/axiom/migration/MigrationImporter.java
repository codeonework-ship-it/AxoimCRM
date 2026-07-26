package com.axiom.migration;

import com.axiom.migration.MigrationAnalyzer.MigratedIndex;
import com.axiom.migration.MigrationAnalyzer.TenantIndex;
import com.axiom.migration.MigrationAssembly.Assembled;
import com.axiom.migration.MigrationAssembly.Reference;
import com.axiom.migration.MigrationModel.Issue;
import com.axiom.migration.MigrationModel.ObjectOutcome;
import com.axiom.migration.MigrationModel.ObjectPlan;
import com.axiom.migration.MigrationModel.PlanContext;
import com.axiom.migration.SourceContract.SourceAdapter;
import com.axiom.migration.SourceContract.SourceAttachment;
import com.axiom.migration.SourceContract.SourceRecord;
import com.axiom.migration.SourceContract.SourceSession;
import com.axiom.migration.TargetSchema.TargetEntity;
import com.axiom.migration.TargetSchema.TargetField;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The write half of the engine: turns validated source records into Axiom
 * records, and records exactly what it created.
 *
 * <h2>Writing into other epics' tables</h2>
 * This class inserts into {@code crm.account}, {@code crm.contact},
 * {@code crm.lead}, {@code sales.opportunity},
 * {@code sales.opportunity_contact_role} and {@code engagement.activity} —
 * tables owned by E03/E04/E05/E07 — through SQL, without importing or touching a
 * single class from those packages. That is the deliberate coupling choice for a
 * migration engine: it is a batch writer on the worker tier, and routing tens of
 * thousands of records through per-record request-shaped services would take
 * their validation as a gift and their transaction boundaries, audit writes and
 * outbox events as a cost. Column names come from {@link TargetSchema}, never
 * from user input, so the mapping editor cannot reach a column the schema does
 * not allow.
 *
 * <h2>The ledger is written in the same transaction as the record</h2>
 * Every insert is followed by a {@code migration.record_map} row inside the same
 * transaction. Rollback can only remove what the ledger proves the migration
 * created, so a record written without its ledger row would be unremovable — and
 * "we created it but cannot take it back" is the failure this module exists to
 * make impossible.
 *
 * <h2>Delta re-sync</h2>
 * A source record whose ledger row already exists is UPDATED in place, never
 * inserted (FR-MIG-008). Identity is the stable source id, not a name or an
 * email, so a record renamed in the source still matches its target.
 */
@Service
public class MigrationImporter {

    private final JdbcTemplate jdbc;
    private final MigrationAnalyzer analyzer;
    private final OutboxWriter outbox;

    public MigrationImporter(JdbcTemplate jdbc, MigrationAnalyzer analyzer, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.analyzer = analyzer;
        this.outbox = outbox;
    }

    public record Outcome(List<ObjectOutcome> outcomes, List<Issue> issues,
                          long created, long updated, long skipped, Instant sourceWatermark) {}

    private record Written(SourceRecord record, Assembled assembled, UUID targetId) {}

    @Transactional
    public Outcome execute(UUID runId, PlanContext plan, SourceAdapter adapter,
                           SourceSession session, Instant deltaSince) {
        UUID tenantId = TenantContext.get().tenantId();
        UUID actor = TenantContext.get().userId();
        TenantIndex tenant = analyzer.readTenantIndex(tenantId);
        MigratedIndex migrated = analyzer.readMigratedIndex(tenantId, plan.planId());
        Map<String, UUID> stages = stageIndex(tenantId);

        Map<String, UUID> resolved = new HashMap<>(migrated.targetBySourceKey());
        Map<String, Map<String, String>> claimed = new HashMap<>();
        List<Issue> issues = new ArrayList<>();
        List<ObjectOutcome> outcomes = new ArrayList<>();
        long created = 0;
        long updated = 0;
        long skipped = 0;
        Instant watermark = plan.deltaWatermark();

        for (String entity : TargetSchema.writeOrder()) {
            for (ObjectPlan object : plan.objects()) {
                if (!entity.equals(object.targetEntity())) continue;

                List<SourceRecord> records = adapter.records(session, object.sourceObject(), deltaSince);
                List<Written> written = new ArrayList<>();
                long objectCreated = 0;
                long objectUpdated = 0;
                long objectSkipped = 0;
                BigDecimal sourceSum = BigDecimal.ZERO;

                if (TargetSchema.ACTIVITY.equals(entity) && !records.isEmpty()) {
                    issues.add(Issue.info("SKIPPED", object.sourceObject(), null, null,
                            "History from " + object.sourceObject() + " is recorded as NOTE activities with the "
                            + "source's own timestamp and actor preserved. Axiom's TASK and CALL activity types "
                            + "require a due date and call disposition respectively, which a historical record "
                            + "does not carry; inventing them to satisfy a constraint would misstate the history."));
                }

                for (SourceRecord record : records) {
                    for (String moneyField : object.moneyFields()) {
                        sourceSum = sourceSum.add(MigrationAssembly.money(record.values().get(moneyField)));
                    }
                    if (record.modifiedAt() != null && (watermark == null || record.modifiedAt().isAfter(watermark))) {
                        watermark = record.modifiedAt();
                    }

                    String key = object.sourceObject() + ":" + record.sourceId();
                    Assembled assembled = MigrationAssembly.assemble(object, record);
                    if (!assembled.valid()) {
                        issues.addAll(assembled.failures());
                        issues.add(Issue.skipped(object.sourceObject(), record.sourceId(), record.label(),
                                "Record failed validation and was NOT migrated"));
                        objectSkipped++;
                        continue;
                    }

                    Map<String, UUID> crossRefs = new LinkedHashMap<>();
                    boolean blocked = false;
                    for (Reference reference : assembled.references()) {
                        if (MigrationAnalyzer.isSelfReference(object, reference)) continue;
                        UUID target = resolved.get(reference.sourceObject() + ":" + reference.sourceRecordId());
                        if (target == null) {
                            issues.add(MigrationAnalyzer.gap(object, record, reference));
                            if (reference.required()) blocked = true;
                        } else {
                            crossRefs.put(reference.targetField(), target);
                        }
                    }
                    if (blocked) {
                        issues.add(Issue.skipped(object.sourceObject(), record.sourceId(), record.label(),
                                "Record had an unresolvable required relationship and was NOT migrated"));
                        objectSkipped++;
                        continue;
                    }

                    UUID existingTarget = migrated.targetBySourceKey().get(key);
                    if (existingTarget != null) {
                        update(entity, existingTarget, tenantId, assembled, crossRefs, stages, actor);
                        touchLedger(tenantId, plan.planId(), runId, object.sourceObject(), record,
                                assembled, existingTarget);
                        resolved.put(key, existingTarget);
                        written.add(new Written(record, assembled, existingTarget));
                        objectUpdated++;
                        continue;
                    }

                    String duplicateKey = MigrationAnalyzer.duplicateKey(entity, assembled.values());
                    if (duplicateKey != null) {
                        Map<String, String> existing = tenant.keysByEntity().getOrDefault(entity, Map.of());
                        Map<String, String> mine = claimed.computeIfAbsent(entity, k -> new HashMap<>());
                        String hit = existing.get(duplicateKey);
                        String own = mine.get(duplicateKey);
                        if (hit != null || own != null) {
                            issues.add(Issue.duplicate(object.sourceObject(), record.sourceId(), record.label(),
                                    MigrationAnalyzer.duplicateField(entity), hit != null ? hit : own,
                                    hit != null ? "existing " + entity.toLowerCase(Locale.ROOT)
                                                : "another record in this same import",
                                    "Matches " + (hit != null ? "an existing tenant record" : "another record in this import")
                                    + " on " + MigrationAnalyzer.duplicateField(entity) + " = \"" + duplicateKey
                                    + "\". The record was NOT migrated."));
                            issues.add(Issue.skipped(object.sourceObject(), record.sourceId(), record.label(),
                                    "Duplicate of existing tenant data"));
                            objectSkipped++;
                            continue;
                        }
                        mine.put(duplicateKey, record.sourceId());
                    }

                    UUID targetId = insert(entity, tenantId, plan, assembled, crossRefs, stages, actor, record);
                    ledger(tenantId, plan.planId(), runId, object.sourceObject(), record, assembled, entity, targetId);
                    attachments(tenantId, plan.planId(), runId, record, entity, targetId);
                    resolved.put(key, targetId);
                    written.add(new Written(record, assembled, targetId));
                    objectCreated++;

                    // Opportunity primary contact is a role row, not a column.
                    UUID primaryContact = crossRefs.get("primaryContactId");
                    if (TargetSchema.OPPORTUNITY.equals(entity) && primaryContact != null) {
                        // do-nothing on conflict returns no row, so this reads as a
                        // list rather than queryForObject, which would throw on zero rows.
                        UUID roleId = jdbc.query("""
                                insert into sales.opportunity_contact_role
                                  (tenant_id, opportunity_id, contact_id, role)
                                values (?, ?, ?, 'CHAMPION')
                                on conflict (opportunity_id, contact_id, role) do nothing
                                returning id
                                """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, targetId, primaryContact)
                                .stream().findFirst().orElse(null);
                        if (roleId != null) {
                            ledgerRow(tenantId, plan.planId(), runId, object.sourceObject(),
                                    record.sourceId() + "#primaryContact", record.label(),
                                    "OPPORTUNITY_CONTACT_ROLE", roleId, null,
                                    record.modifiedAt(), record.createdAt(), record.actor());
                        }
                        issues.add(Issue.info("SKIPPED", object.sourceObject(), record.sourceId(), record.label(),
                                "The source's \"primary contact\" has no exact Axiom equivalent; Axiom models "
                                + "opportunity contacts as buying-group roles. It was recorded as a CHAMPION "
                                + "contact role and is listed here so the difference is visible rather than silent."));
                    }
                }

                // Second pass: hierarchy links, now that every record of this
                // object exists (FR-MIG-004 — account hierarchies preserved).
                for (Written entry : written) {
                    for (Reference reference : entry.assembled().references()) {
                        if (!MigrationAnalyzer.isSelfReference(object, reference)) continue;
                        UUID target = resolved.get(reference.sourceObject() + ":" + reference.sourceRecordId());
                        if (target == null) {
                            issues.add(MigrationAnalyzer.gap(object, entry.record(), reference));
                            continue;
                        }
                        if (target.equals(entry.targetId())) continue;
                        TargetField field = TargetSchema.entity(entity).orElseThrow()
                                .field(reference.targetField()).orElseThrow();
                        jdbc.update("update " + TargetSchema.entity(entity).orElseThrow().table()
                                        + " set " + field.column() + " = ? where tenant_id = ? and id = ?",
                                target, tenantId, entry.targetId());
                    }
                }

                outcomes.add(new ObjectOutcome(object.sourceObject(), entity, records.size(),
                        objectCreated, objectUpdated, objectSkipped, sourceSum, object.unmappedFields()));
                created += objectCreated;
                updated += objectUpdated;
                skipped += objectSkipped;
            }
        }

        // ADR-003: one domain event per run, into the transactional outbox. With
        // Kafka down the row simply queues — the documented degraded mode.
        outbox.write("MIGRATION_RUN", runId, "migration.records-imported", Map.of(
                "runId", runId.toString(),
                "planId", plan.planId().toString(),
                "created", created,
                "updated", updated,
                "skipped", skipped));

        return new Outcome(List.copyOf(outcomes), List.copyOf(issues), created, updated, skipped, watermark);
    }

    // ------------------------------------------------------------------ writes

    private UUID insert(String entity, UUID tenantId, PlanContext plan, Assembled assembled,
                        Map<String, UUID> refs, Map<String, UUID> stages, UUID actor, SourceRecord record) {
        TargetEntity target = TargetSchema.entity(entity).orElseThrow();
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("tenant_id", tenantId);

        for (Map.Entry<String, String> value : assembled.values().entrySet()) {
            TargetField field = target.field(value.getKey()).orElse(null);
            if (field == null || field.column() == null) continue;   // provenance-only field
            if ("stage_id".equals(field.column())) continue;         // resolved below
            columns.put(field.column(), coerce(field, value.getValue()));
        }

        switch (entity) {
            case TargetSchema.ACCOUNT -> {
                columns.put("owner_id", actor);
                columns.put("created_by", actor);
                columns.put("updated_by", actor);
                columns.put("source_system", sourceSystem(plan));
                columns.put("external_ref", record.sourceId());
                columns.putIfAbsent("created_at", timestamp(record.createdAt()));
            }
            case TargetSchema.CONTACT -> {
                columns.put("owner_id", actor);
                columns.put("created_by", actor);
                columns.put("updated_by", actor);
                columns.put("source_system", sourceSystem(plan));
                columns.put("external_ref", record.sourceId());
                columns.put("account_id", refs.get("accountId"));
                columns.putIfAbsent("created_at", timestamp(record.createdAt()));
            }
            case TargetSchema.LEAD -> {
                columns.put("owner_id", actor);
                columns.put("capture_source", "IMPORT");
                columns.putIfAbsent("created_at", timestamp(record.createdAt()));
            }
            case TargetSchema.OPPORTUNITY -> {
                columns.put("owner_id", actor);
                columns.put("account_id", refs.get("accountId"));
                columns.put("stage_id", stage(stages, assembled.values().get("stageName")));
                columns.putIfAbsent("created_at", timestamp(record.createdAt()));
                columns.put("stage_entered_at", timestamp(record.createdAt()));
            }
            case TargetSchema.ACTIVITY -> {
                Object occurred = columns.getOrDefault("occurred_at", timestamp(record.createdAt()));
                columns.put("activity_type", "NOTE");
                columns.put("status", "COMPLETED");
                columns.put("completed_at", occurred);
                columns.put("occurred_at", occurred);
                columns.put("related_entity_type", TargetSchema.ACCOUNT);
                columns.put("related_entity_id", refs.get("relatedAccountId"));
                columns.put("owner_id", actor);
                columns.put("created_by", actor);
                columns.put("source", "IMPORT");
                columns.put("capture_source", "API");
                columns.putIfAbsent("created_at", timestamp(record.createdAt()));
            }
            default -> throw new IllegalStateException("No writer for target entity " + entity);
        }
        // A reference field that resolved to nothing must be an explicit null,
        // not an absent column, so the row shape is the same either way.
        columns.values().removeIf(java.util.Objects::isNull);

        String names = String.join(", ", columns.keySet());
        String placeholders = String.join(", ", columns.keySet().stream().map(k -> "?").toList());
        return jdbc.queryForObject("insert into " + target.table() + " (" + names + ") values ("
                + placeholders + ") returning id", UUID.class, columns.values().toArray());
    }

    private void update(String entity, UUID targetId, UUID tenantId, Assembled assembled,
                        Map<String, UUID> refs, Map<String, UUID> stages, UUID actor) {
        TargetEntity target = TargetSchema.entity(entity).orElseThrow();
        Map<String, Object> columns = new LinkedHashMap<>();
        for (Map.Entry<String, String> value : assembled.values().entrySet()) {
            TargetField field = target.field(value.getKey()).orElse(null);
            if (field == null || field.column() == null) continue;
            if ("stage_id".equals(field.column())) {
                UUID stageId = stage(stages, value.getValue());
                if (stageId != null) columns.put("stage_id", stageId);
                continue;
            }
            columns.put(field.column(), coerce(field, value.getValue()));
        }
        if (refs.containsKey("accountId")) columns.put("account_id", refs.get("accountId"));
        if (TargetSchema.ACCOUNT.equals(entity) || TargetSchema.CONTACT.equals(entity)) {
            columns.put("updated_by", actor);
        }
        if (columns.isEmpty()) return;

        String assignments = String.join(", ", columns.keySet().stream().map(c -> c + " = ?").toList());
        List<Object> args = new ArrayList<>(columns.values());
        args.add(tenantId);
        args.add(targetId);
        // updated_at moves with the write so the rollback preview keeps telling
        // the truth about which rows a user has touched since the migration.
        jdbc.update("update " + target.table() + " set " + assignments + ", updated_at = now()"
                        + " where tenant_id = ? and id = ?",
                args.toArray());
    }

    // ------------------------------------------------------------------ the ledger

    private void ledger(UUID tenantId, UUID planId, UUID runId, String sourceObject, SourceRecord record,
                        Assembled assembled, String entity, UUID targetId) {
        ledgerRow(tenantId, planId, runId, sourceObject, record.sourceId(), record.label(), entity, targetId,
                MigrationAssembly.fingerprint(assembled.values()),
                record.modifiedAt(), record.createdAt(), record.actor());
    }

    private void ledgerRow(UUID tenantId, UUID planId, UUID runId, String sourceObject, String sourceId,
                           String sourceLabel, String entity, UUID targetId, String fingerprint,
                           Instant modifiedAt, Instant createdAt, String sourceActor) {
        // target_version 0 and target_updated_at now(): the row was written by
        // this same transaction, so now() is identical to the value the default
        // put on the business row. Any later divergence is a user edit.
        jdbc.update("""
                insert into migration.record_map
                  (tenant_id, plan_id, source_object, source_record_id, source_label, target_entity,
                   target_id, target_fingerprint, target_version, target_updated_at,
                   source_modified_at, source_created_at, source_actor, created_run_id, last_run_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, 0, now(), ?, ?, ?, ?, ?)
                on conflict (tenant_id, plan_id, source_object, source_record_id) do update
                   set target_fingerprint = excluded.target_fingerprint,
                       target_updated_at = now(),
                       source_modified_at = excluded.source_modified_at,
                       last_run_id = excluded.last_run_id,
                       state = 'LIVE',
                       updated_at = now()
                """, tenantId, planId, sourceObject, sourceId, sourceLabel, entity, targetId, fingerprint,
                timestamp(modifiedAt), timestamp(createdAt), sourceActor, runId, runId);
    }

    private void touchLedger(UUID tenantId, UUID planId, UUID runId, String sourceObject,
                             SourceRecord record, Assembled assembled, UUID targetId) {
        jdbc.update("""
                update migration.record_map
                   set target_fingerprint = ?, source_modified_at = ?, source_actor = ?,
                       target_updated_at = now(), last_run_id = ?, updated_at = now()
                 where tenant_id = ? and plan_id = ? and source_object = ? and source_record_id = ?
                """, MigrationAssembly.fingerprint(assembled.values()), timestamp(record.modifiedAt()),
                record.actor(), runId, tenantId, planId, sourceObject, record.sourceId());
    }

    private void attachments(UUID tenantId, UUID planId, UUID runId, SourceRecord record,
                             String entity, UUID targetId) {
        for (SourceAttachment attachment : record.attachments()) {
            jdbc.update("""
                    insert into migration.migrated_attachment
                      (tenant_id, plan_id, run_id, source_record_id, target_entity, target_id,
                       file_name, content_type, byte_size, external_ref, original_author, original_created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, planId, runId, record.sourceId(), entity, targetId,
                    attachment.fileName(), attachment.contentType(), attachment.byteSize(),
                    attachment.externalRef(), attachment.author(), timestamp(attachment.createdAt()));
        }
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, UUID> stageIndex(UUID tenantId) {
        Map<String, UUID> stages = new LinkedHashMap<>();
        List<Object[]> rows = jdbc.query("""
                select id, name from crm.pipeline_stage
                where tenant_id = ? and deleted_at is null
                order by sort_order
                """, (rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("name")}, tenantId);
        for (Object[] row : rows) {
            stages.putIfAbsent(((String) row[1]).toLowerCase(Locale.ROOT), (UUID) row[0]);
            stages.putIfAbsent("__first__", (UUID) row[0]);
        }
        return stages;
    }

    /** An unrecognised source stage lands on the first stage rather than nowhere. */
    private UUID stage(Map<String, UUID> stages, String stageName) {
        if (stageName != null) {
            UUID hit = stages.get(stageName.toLowerCase(Locale.ROOT));
            if (hit != null) return hit;
        }
        return stages.get("__first__");
    }

    private String sourceSystem(PlanContext plan) {
        return "MIGRATION:" + plan.vendor();
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Object coerce(TargetField field, String value) {
        if (value == null) return null;
        return switch (field.type()) {
            case "MONEY", "NUMBER" -> new BigDecimal(value.replace(",", ""));
            case "INTEGER" -> Integer.valueOf(value.replace(",", "").trim());
            case "DATE" -> LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
            case "DATETIME" -> Timestamp.from(Instant.parse(value));
            case "CURRENCY_CODE" -> value.toUpperCase(Locale.ROOT);
            case "SENIORITY" -> value.toUpperCase(Locale.ROOT);
            default -> value;
        };
    }
}

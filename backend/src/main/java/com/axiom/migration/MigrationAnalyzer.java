package com.axiom.migration;

import com.axiom.migration.MigrationAssembly.Assembled;
import com.axiom.migration.MigrationAssembly.Reference;
import com.axiom.migration.MigrationModel.Issue;
import com.axiom.migration.MigrationModel.ObjectOutcome;
import com.axiom.migration.MigrationModel.ObjectPlan;
import com.axiom.migration.MigrationModel.PlanContext;
import com.axiom.migration.MigrationModel.PreFlightReport;
import com.axiom.migration.SourceContract.SourceAdapter;
import com.axiom.migration.SourceContract.SourceRecord;
import com.axiom.migration.SourceContract.SourceSession;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The dry run (FR-MIG-003) — a full validation pass that writes nothing.
 *
 * <h2>The zero-write property, and how it is kept</h2>
 * This class holds a {@link JdbcTemplate} and never calls a mutating method on
 * it. Not "tries not to" — the class contains no {@code update},
 * {@code batchUpdate} or {@code execute} call at all, and
 * {@code MigrationAnalyzerTest} asserts that with {@code verify(jdbc, never())}
 * on every mutator. The transaction is {@code readOnly = true} as a second
 * layer, so an accidental write would fail against PostgreSQL rather than
 * succeed quietly.
 *
 * <p>Persisting the resulting report is somebody else's job — {@link
 * MigrationWorker} writes it to {@code migration.run_issue} and
 * {@code migration.reconciliation_line} after this returns. The distinction is
 * exact and worth stating: the dry run creates no business records; the report
 * about it is an artefact of the run and is stored under {@code migration.*}.
 *
 * <h2>Why the analysis is ordered</h2>
 * Objects are analysed in {@link TargetSchema#writeOrder()}, and each object's
 * accepted ids join the set of "records that will exist". That is what lets a
 * contact analysed in pass two resolve an account that pass one only intends to
 * create — the dry run predicts the state the import will build, rather than
 * reporting every forward reference as a gap.
 */
@Service
public class MigrationAnalyzer {

    private final JdbcTemplate jdbc;

    public MigrationAnalyzer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records already in the tenant, keyed for duplicate detection. */
    record TenantIndex(Map<String, Map<String, String>> keysByEntity,
                       Map<String, Long> countsByEntity,
                       Set<String> stageNames) {}

    /** What the record map already holds for this plan: source id to target id. */
    record MigratedIndex(Map<String, UUID> targetBySourceKey,
                         Map<String, String> entityBySourceKey) {}

    /**
     * @param deltaSince null for a full pass; otherwise only source records the
     *                   source says changed after this instant (FR-MIG-008)
     */
    @Transactional(readOnly = true)
    public PreFlightReport analyse(PlanContext plan, SourceAdapter adapter, SourceSession session, Instant deltaSince) {
        UUID tenantId = TenantContext.get().tenantId();
        TenantIndex tenant = readTenantIndex(tenantId);
        MigratedIndex migrated = readMigratedIndex(tenantId, plan.planId());

        List<Issue> issues = new ArrayList<>();
        List<ObjectOutcome> outcomes = new ArrayList<>();
        List<String> allUnmapped = new ArrayList<>();

        // Source ids that will exist in Axiom once this run finishes: already
        // migrated, plus everything this pass accepts. Keyed "OBJECT:id".
        Set<String> willExist = new HashSet<>(migrated.targetBySourceKey().keySet());
        // Duplicate keys claimed within this run, so two source records that
        // collide with each other are caught as well as ones that collide with
        // existing tenant data.
        Map<String, Map<String, String>> claimed = new HashMap<>();

        for (String entity : TargetSchema.writeOrder()) {
            for (ObjectPlan object : plan.objects()) {
                if (!entity.equals(object.targetEntity())) continue;
                outcomes.add(analyseObject(plan, object, adapter, session, deltaSince,
                        tenant, migrated, willExist, claimed, issues));
                for (String field : object.unmappedFields()) {
                    allUnmapped.add(object.sourceObject() + "." + field);
                }
            }
        }

        // Objects the plan carries with no target entity at all: every field is
        // lost, and the object is named rather than omitted from the report.
        for (ObjectPlan object : plan.objects()) {
            if (object.targetEntity() != null) continue;
            issues.add(Issue.skipped(object.sourceObject(), null, null,
                    "Source object " + object.sourceObject() + " is not mapped to any Axiom entity. "
                    + object.unmappedFields().size() + " field(s) and every record of this object will NOT be migrated."));
            for (String field : object.unmappedFields()) {
                allUnmapped.add(object.sourceObject() + "." + field);
            }
        }

        for (String field : allUnmapped) {
            int dot = field.indexOf('.');
            issues.add(Issue.unmappedField(dot < 0 ? null : field.substring(0, dot),
                    dot < 0 ? field : field.substring(dot + 1),
                    "Source field " + field + " has no Axiom destination and will NOT be migrated."));
        }

        long create = outcomes.stream().mapToLong(ObjectOutcome::toCreate).sum();
        long update = outcomes.stream().mapToLong(ObjectOutcome::toUpdate).sum();
        long skip = outcomes.stream().mapToLong(ObjectOutcome::toSkip).sum();

        return new PreFlightReport(plan.planId(), plan.planName(), Instant.now(), deltaSince,
                List.copyOf(outcomes), List.copyOf(issues), List.copyOf(allUnmapped),
                create, update, skip,
                issues.stream().filter(i -> "VALIDATION".equals(i.category())).count(),
                issues.stream().filter(i -> "DUPLICATE".equals(i.category())).count(),
                issues.stream().filter(i -> "REFERENTIAL_GAP".equals(i.category())).count());
    }

    // ------------------------------------------------------------------ per object

    private ObjectOutcome analyseObject(PlanContext plan, ObjectPlan object, SourceAdapter adapter,
                                        SourceSession session, Instant deltaSince,
                                        TenantIndex tenant, MigratedIndex migrated,
                                        Set<String> willExist, Map<String, Map<String, String>> claimed,
                                        List<Issue> issues) {
        List<SourceRecord> records = adapter.records(session, object.sourceObject(), deltaSince);
        long create = 0;
        long update = 0;
        long skip = 0;
        BigDecimal sourceSum = BigDecimal.ZERO;

        // Phase one: everything that does not depend on this object's own
        // records. Phase two then checks self-references (account hierarchy,
        // contact manager chain) against the ids phase one actually accepted —
        // so a parent appearing after its child in the export resolves, and a
        // parent that was rejected is reported rather than assumed.
        List<Accepted> accepted = new ArrayList<>();

        for (SourceRecord record : records) {
            for (String moneyField : object.moneyFields()) {
                sourceSum = sourceSum.add(MigrationAssembly.money(record.values().get(moneyField)));
            }

            String key = object.sourceObject() + ":" + record.sourceId();
            boolean alreadyMigrated = migrated.targetBySourceKey().containsKey(key);

            Assembled assembled = MigrationAssembly.assemble(object, record);
            if (!assembled.valid()) {
                issues.addAll(assembled.failures());
                issues.add(Issue.skipped(object.sourceObject(), record.sourceId(), record.label(),
                        "Record fails validation and will NOT be migrated"));
                skip++;
                continue;
            }

            boolean blocked = false;
            for (Reference reference : assembled.references()) {
                if (isSelfReference(object, reference)) continue;
                String refKey = reference.sourceObject() + ":" + reference.sourceRecordId();
                if (willExist.contains(refKey)) continue;
                issues.add(gap(object, record, reference));
                if (reference.required()) blocked = true;
            }
            if (blocked) {
                issues.add(Issue.skipped(object.sourceObject(), record.sourceId(), record.label(),
                        "Record has an unresolvable required relationship and will NOT be migrated"));
                skip++;
                continue;
            }

            if (alreadyMigrated) {
                // Delta re-sync: matched by stable source id, applied to the
                // previously migrated target. Never a second record.
                update++;
                willExist.add(key);
                accepted.add(new Accepted(record, assembled, true, key));
                continue;
            }

            String duplicateKey = duplicateKey(object.targetEntity(), assembled.values());
            if (duplicateKey != null) {
                Map<String, String> existing = tenant.keysByEntity()
                        .getOrDefault(object.targetEntity(), Map.of());
                Map<String, String> mine = claimed.computeIfAbsent(object.targetEntity(), k -> new HashMap<>());
                String hit = existing.get(duplicateKey);
                String own = mine.get(duplicateKey);
                if (hit != null || own != null) {
                    issues.add(Issue.duplicate(object.sourceObject(), record.sourceId(), record.label(),
                            duplicateField(object.targetEntity()),
                            hit != null ? hit : own,
                            hit != null ? "existing " + object.targetEntity().toLowerCase(Locale.ROOT)
                                        : "another record in this same import",
                            "Matches " + (hit != null ? "an existing tenant record" : "another record in this import")
                            + " on " + duplicateField(object.targetEntity()) + " = \"" + duplicateKey
                            + "\". The record will NOT be migrated; merge or correct the source before importing."));
                    issues.add(Issue.skipped(object.sourceObject(), record.sourceId(), record.label(),
                            "Duplicate of existing tenant data"));
                    skip++;
                    continue;
                }
                mine.put(duplicateKey, record.sourceId());
            }

            create++;
            willExist.add(key);
            accepted.add(new Accepted(record, assembled, false, key));
        }

        Set<String> invalidSelfReferences = invalidSelfReferences(object, accepted, willExist);
        for (Accepted entry : accepted) {
            if (invalidSelfReferences.contains(entry.key())) {
                for (Reference reference : entry.assembled().references()) {
                    if (!isSelfReference(object, reference)) continue;
                    String refKey = reference.sourceObject() + ":" + reference.sourceRecordId();
                    if (willExist.contains(refKey)) continue;
                    issues.add(gap(object, entry.record(), reference));
                    break;
                }
                issues.add(Issue.skipped(object.sourceObject(), entry.record().sourceId(), entry.record().label(),
                        "Record has an unresolvable required self-reference and will NOT be migrated"));
                if (entry.update()) update--;
                else create--;
                skip++;
                continue;
            }
            for (Reference reference : entry.assembled().references()) {
                if (!isSelfReference(object, reference)) continue;
                String refKey = reference.sourceObject() + ":" + reference.sourceRecordId();
                if (willExist.contains(refKey)) continue;
                issues.add(gap(object, entry.record(), reference));
            }
        }

        return new ObjectOutcome(object.sourceObject(), object.targetEntity(), records.size(),
                create, update, skip, sourceSum, object.unmappedFields());
    }

    private Set<String> invalidSelfReferences(ObjectPlan object, List<Accepted> accepted, Set<String> willExist) {
        Set<String> valid = new HashSet<>(willExist);
        Set<String> invalid = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (Accepted entry : accepted) {
                if (invalid.contains(entry.key())) continue;
                boolean broken = entry.assembled().references().stream()
                        .filter(reference -> isSelfReference(object, reference))
                        .map(reference -> reference.sourceObject() + ":" + reference.sourceRecordId())
                        .anyMatch(refKey -> !valid.contains(refKey));
                if (broken) {
                    invalid.add(entry.key());
                    valid.remove(entry.key());
                    changed = true;
                }
            }
        } while (changed);
        willExist.removeAll(invalid);
        return invalid;
    }

    private record Accepted(SourceRecord record, Assembled assembled, boolean update, String key) {}

    /** A hierarchy link: parent account, reporting manager. Resolved in a second pass. */
    static boolean isSelfReference(ObjectPlan object, Reference reference) {
        return reference.targetEntity() != null && reference.targetEntity().equals(object.targetEntity());
    }

    /**
     * FR-MIG-004 made concrete: both endpoints named, every time. Shared by the
     * analyzer and the importer so the two never phrase the same defect
     * differently.
     */
    static Issue gap(ObjectPlan object, SourceRecord record, Reference reference) {
        return Issue.referentialGap(reference.required() ? "ERROR" : "WARNING",
                object.sourceObject(), record.sourceId(), record.label(), reference.targetField(),
                reference.sourceObject(), reference.sourceRecordId(),
                (reference.required() ? "Required" : "Optional") + " relationship "
                + reference.targetField() + ": source record " + object.sourceObject() + " "
                + record.sourceId() + " (\"" + record.label() + "\") references "
                + reference.sourceObject() + " " + reference.sourceRecordId()
                + ", which is not present in the source export and has not been migrated. "
                + (reference.required()
                    ? "The record will NOT be migrated."
                    : "The record is migrated with this relationship left unset; the gap is reported, not dropped."));
    }

    // ------------------------------------------------------------------ duplicate keys

    /**
     * The natural key each entity is deduplicated on.
     *
     * <p>These are the same keys the database enforces (account name is uniquely
     * indexed per tenant), so a duplicate the dry run misses is a duplicate the
     * import discovers as a constraint violation — which is exactly the surprise
     * a pre-flight report exists to prevent.
     */
    static String duplicateKey(String targetEntity, Map<String, String> values) {
        String raw = switch (targetEntity) {
            case TargetSchema.ACCOUNT, TargetSchema.OPPORTUNITY -> values.get("name");
            case TargetSchema.CONTACT, TargetSchema.LEAD -> values.get("email");
            default -> null;
        };
        return raw == null || raw.isBlank() ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    static String duplicateField(String targetEntity) {
        return switch (targetEntity) {
            case TargetSchema.ACCOUNT, TargetSchema.OPPORTUNITY -> "name";
            case TargetSchema.CONTACT, TargetSchema.LEAD -> "email";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ reads (and only reads)

    TenantIndex readTenantIndex(UUID tenantId) {
        Map<String, Map<String, String>> keys = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();

        keys.put(TargetSchema.ACCOUNT, keyed("""
                select id, name from crm.account where tenant_id = ? and deleted_at is null
                """, tenantId));
        keys.put(TargetSchema.CONTACT, keyed("""
                select id, email from crm.contact
                where tenant_id = ? and deleted_at is null and email is not null and email <> ''
                """, tenantId));
        keys.put(TargetSchema.LEAD, keyed("""
                select id, email from crm.lead
                where tenant_id = ? and deleted_at is null and email is not null and email <> ''
                """, tenantId));
        keys.put(TargetSchema.OPPORTUNITY, keyed("""
                select id, name from sales.opportunity where tenant_id = ?
                """, tenantId));

        counts.put(TargetSchema.ACCOUNT, count("select count(*) from crm.account where tenant_id = ? and deleted_at is null", tenantId));
        counts.put(TargetSchema.CONTACT, count("select count(*) from crm.contact where tenant_id = ? and deleted_at is null", tenantId));
        counts.put(TargetSchema.LEAD, count("select count(*) from crm.lead where tenant_id = ? and deleted_at is null", tenantId));
        counts.put(TargetSchema.OPPORTUNITY, count("select count(*) from sales.opportunity where tenant_id = ?", tenantId));
        counts.put(TargetSchema.ACTIVITY, count("select count(*) from engagement.activity where tenant_id = ? and deleted_at is null", tenantId));

        Set<String> stages = new LinkedHashSet<>(jdbc.query(
                "select name from crm.pipeline_stage where tenant_id = ? and deleted_at is null",
                (rs, i) -> rs.getString(1), tenantId));

        return new TenantIndex(keys, counts, stages);
    }

    private Map<String, String> keyed(String sql, UUID tenantId) {
        Map<String, String> out = new HashMap<>();
        for (Object[] row : jdbc.query(sql, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2)}, tenantId)) {
            String value = (String) row[1];
            if (value == null || value.isBlank()) continue;
            out.put(value.trim().toLowerCase(Locale.ROOT), (String) row[0]);
        }
        return out;
    }

    private long count(String sql, UUID tenantId) {
        Long value = jdbc.queryForObject(sql, Long.class, tenantId);
        return value == null ? 0L : value;
    }

    MigratedIndex readMigratedIndex(UUID tenantId, UUID planId) {
        Map<String, UUID> targets = new HashMap<>();
        Map<String, String> entities = new HashMap<>();
        List<Object[]> rows = jdbc.query("""
                select source_object, source_record_id, target_entity, target_id
                from migration.record_map
                where tenant_id = ? and plan_id = ? and state = 'LIVE'
                """, (rs, i) -> new Object[]{
                        rs.getString("source_object") + ":" + rs.getString("source_record_id"),
                        rs.getString("target_entity"),
                        rs.getObject("target_id", UUID.class)},
                tenantId, planId);
        for (Object[] row : rows) {
            targets.put((String) row[0], (UUID) row[2]);
            entities.put((String) row[0], (String) row[1]);
        }
        return new MigratedIndex(targets, entities);
    }
}

package com.axiom.migration;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.migration.MigrationModel.Issue;
import com.axiom.migration.MigrationModel.PreExistingGuard;
import com.axiom.migration.MigrationModel.RollbackPreview;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Rollback (FR-MIG-007, F-291) — the property that turns a migration from a bet
 * into a trial.
 *
 * <h2>What "removes exactly what it created" rests on</h2>
 * Only {@code migration.record_map} decides what gets deleted. Not a
 * {@code source_system} marker on the business row — any user with edit rights
 * can set that column, and a rollback that trusted it would delete records a
 * user mis-tagged. The ledger is written by {@link MigrationImporter} in the
 * same transaction as the record it describes, is owned by this module, and is
 * the sole input here. Records that existed before the migration have no ledger
 * row and are therefore not reachable by this code at all — which is why the
 * seeded tenant's pre-existing accounts survive a rollback as a structural
 * consequence rather than as a filter someone remembered to write.
 *
 * <h2>Blocked deletions are reported, not forced</h2>
 * A migrated account that a user has since attached a quote or a case to cannot
 * be deleted without destroying the user's own work. Rather than cascade — which
 * would silently delete data the migration did NOT create — this service asks
 * the PostgreSQL catalogue which tables reference the target, counts the
 * references, and reports each blocked record with the table that holds it.
 * The operator gets a list of what remains and why.
 *
 * <h2>The retention window</h2>
 * FR-MIG-007 makes the window configurable; {@code migration.plan.retention_days}
 * holds it. Past the window the ledger is still readable — the history of what
 * was migrated does not evaporate — but rollback refuses, because a tenant that
 * has been operating on migrated data for months no longer has a "pre-migration
 * state" to return to and pretending otherwise would destroy months of work.
 */
@Service
public class MigrationRollbackService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final OutboxWriter outbox;

    public MigrationRollbackService(JdbcTemplate jdbc, AuditService audit, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
    }

    /** Entities in the only order that satisfies every foreign key. */
    private static final List<String> DELETE_ORDER = List.of(
            "ACTIVITY", "OPPORTUNITY_CONTACT_ROLE", "OPPORTUNITY", "LEAD", "CONTACT", "ACCOUNT");

    private static String table(String entity) {
        return switch (entity) {
            case "OPPORTUNITY_CONTACT_ROLE" -> "sales.opportunity_contact_role";
            default -> TargetSchema.entity(entity)
                    .orElseThrow(() -> new IllegalStateException("No table for " + entity))
                    .table();
        };
    }

    record PlanRow(UUID id, String name, String status, int retentionDays, Instant importedAt, boolean sampleData) {}

    // ------------------------------------------------------------------ preview

    @Transactional(readOnly = true)
    public RollbackPreview preview(UUID planId) {
        UUID tenantId = TenantContext.get().tenantId();
        PlanRow plan = plan(tenantId, planId);

        Map<String, Long> counts = new LinkedHashMap<>();
        for (String entity : DELETE_ORDER) {
            Long n = jdbc.queryForObject("""
                    select count(*) from migration.record_map
                    where tenant_id = ? and plan_id = ? and target_entity = ? and state = 'LIVE'
                    """, Long.class, tenantId, planId, entity);
            if (n != null && n > 0) counts.put(entity, n);
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();

        List<Issue> modified = modifiedSinceMigration(tenantId, planId);

        // The other half of the promise, stated in numbers the operator can
        // check against the screen they are already on.
        List<PreExistingGuard> untouched = List.of(
                new PreExistingGuard("ACCOUNT", preExisting(tenantId, planId, "crm.account", "ACCOUNT", true)),
                new PreExistingGuard("CONTACT", preExisting(tenantId, planId, "crm.contact", "CONTACT", true)),
                new PreExistingGuard("LEAD", preExisting(tenantId, planId, "crm.lead", "LEAD", true)),
                new PreExistingGuard("OPPORTUNITY", preExisting(tenantId, planId, "sales.opportunity", "OPPORTUNITY", false)),
                new PreExistingGuard("ACTIVITY", preExisting(tenantId, planId, "engagement.activity", "ACTIVITY", true)));

        Instant expires = plan.importedAt() == null ? null
                : plan.importedAt().plus(plan.retentionDays(), ChronoUnit.DAYS);
        boolean within = plan.importedAt() != null && expires != null && Instant.now().isBefore(expires);

        return new RollbackPreview(planId, plan.name(), plan.importedAt(), plan.retentionDays(),
                expires, within, counts, total, modified, untouched);
    }

    /**
     * Records the migration created that a user has edited since. Compared
     * against the version and timestamp captured on the ledger at write time, so
     * this is a fact about the row rather than a guess from its shape.
     */
    private List<Issue> modifiedSinceMigration(UUID tenantId, UUID planId) {
        List<Issue> out = new ArrayList<>();
        for (Map.Entry<String, String> entity : Map.of(
                "ACCOUNT", "crm.account", "CONTACT", "crm.contact",
                "LEAD", "crm.lead", "OPPORTUNITY", "sales.opportunity",
                "ACTIVITY", "engagement.activity").entrySet()) {
            out.addAll(jdbc.query("""
                    select m.source_object, m.source_record_id, m.source_label, t.id::text as target_id
                    from migration.record_map m
                    join %s t on t.tenant_id = m.tenant_id and t.id = m.target_id
                    where m.tenant_id = ? and m.plan_id = ? and m.target_entity = ? and m.state = 'LIVE'
                      and m.target_updated_at is not null and t.updated_at > m.target_updated_at
                    """.formatted(entity.getValue()),
                    (rs, i) -> new Issue("WARNING", "MODIFIED_SINCE_MIGRATION",
                            rs.getString("source_object"), rs.getString("source_record_id"),
                            rs.getString("source_label"), null,
                            entity.getKey(), rs.getString("target_id"), rs.getString("source_label"),
                            "This record was created by the migration and has been edited in Axiom since. "
                            + "Rolling back will remove it, including the edit."),
                    tenantId, planId, entity.getKey()));
        }
        return out;
    }

    private long preExisting(UUID tenantId, UUID planId, String table, String entity, boolean softDelete) {
        Long n = jdbc.queryForObject("""
                select count(*) from %s t
                where t.tenant_id = ? %s
                  and not exists (select 1 from migration.record_map m
                                  where m.tenant_id = t.tenant_id and m.plan_id = ?
                                    and m.target_entity = ? and m.target_id = t.id and m.state = 'LIVE')
                """.formatted(table, softDelete ? "and t.deleted_at is null" : ""),
                Long.class, tenantId, planId, entity);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ execute

    public record RollbackResult(UUID planId, long removed, Map<String, Long> removedByEntity,
                                 List<Issue> removedRecords, List<Issue> blocked) {}

    /**
     * Called by {@link MigrationWorker} on the worker tier. The role gate is
     * applied when the run is QUEUED, not here — by the time this executes there
     * is no request principal to check.
     */
    @Transactional
    public RollbackResult execute(UUID runId, UUID planId) {
        UUID tenantId = TenantContext.get().tenantId();
        PlanRow plan = plan(tenantId, planId);
        requireWithinRetention(plan);

        Map<String, Long> removedByEntity = new LinkedHashMap<>();
        List<Issue> removed = new ArrayList<>();
        List<Issue> blocked = new ArrayList<>();
        long total = 0;

        for (String entity : DELETE_ORDER) {
            List<Ledger> rows = jdbc.query("""
                    select id, source_object, source_record_id, source_label, target_id
                    from migration.record_map
                    where tenant_id = ? and plan_id = ? and target_entity = ? and state = 'LIVE'
                    """, (rs, i) -> new Ledger(rs.getObject("id", UUID.class), rs.getString("source_object"),
                            rs.getString("source_record_id"), rs.getString("source_label"),
                            rs.getObject("target_id", UUID.class)),
                    tenantId, planId, entity);
            if (rows.isEmpty()) continue;

            Set<UUID> targets = new LinkedHashSet<>(rows.stream().map(Ledger::targetId).toList());
            Map<UUID, String> blockers = blockers(planId, entity, tenantId, targets);

            List<UUID> deletable = new ArrayList<>();
            for (Ledger row : rows) {
                String blocker = blockers.get(row.targetId());
                if (blocker != null) {
                    blocked.add(new Issue("ERROR", "ROLLBACK_BLOCKED", row.sourceObject(), row.sourceRecordId(),
                            row.sourceLabel(), null, entity, row.targetId().toString(), row.sourceLabel(),
                            "NOT removed: " + blocker + " created after the migration still references this "
                            + entity.toLowerCase() + ". Rollback will not destroy records the migration did not "
                            + "create, so this row is left in place and reported."));
                    continue;
                }
                deletable.add(row.targetId());
                removed.add(new Issue("INFO", "ROLLBACK_REMOVED", row.sourceObject(), row.sourceRecordId(),
                        row.sourceLabel(), null, entity, row.targetId().toString(), row.sourceLabel(),
                        "Removed " + entity.toLowerCase() + " created by this migration"));
            }
            if (deletable.isEmpty()) continue;

            long deleted = 0;
            for (List<UUID> chunk : chunks(deletable)) {
                Long removedCount = jdbc.queryForObject(
                        "select migration.delete_owned_targets(?, ?, ?::uuid[])", Long.class,
                        tenantId, entity, chunk.toArray(UUID[]::new));
                deleted += removedCount == null ? 0 : removedCount;

                List<Object> ledgerArgs = new ArrayList<>(List.of(tenantId, planId, entity));
                ledgerArgs.addAll(chunk);
                jdbc.update("update migration.record_map set state = 'ROLLED_BACK', updated_at = now() "
                        + "where tenant_id = ? and plan_id = ? and target_entity = ? and target_id in ("
                        + placeholders(chunk.size()) + ")", ledgerArgs.toArray());
            }

            removedByEntity.put(entity, deleted);
            total += deleted;
        }

        jdbc.update("""
                delete from migration.migrated_attachment
                where tenant_id = ? and plan_id = ?
                  and not exists (select 1 from migration.record_map m
                                  where m.tenant_id = migrated_attachment.tenant_id
                                    and m.plan_id = migrated_attachment.plan_id
                                    and m.target_id = migrated_attachment.target_id
                                    and m.state = 'LIVE')
                """, tenantId, planId);

        jdbc.update("""
                update migration.plan
                   set status = case when exists (select 1 from migration.record_map m
                                                  where m.tenant_id = plan.tenant_id and m.plan_id = plan.id
                                                    and m.state = 'LIVE')
                                     then 'IMPORTED' else 'ROLLED_BACK' end,
                       updated_at = now()
                 where tenant_id = ? and id = ?
                """, tenantId, planId);

        jdbc.update("""
                delete from migration.delta_checkpoint d
                where d.tenant_id = ? and d.plan_id = ?
                  and not exists (select 1 from migration.record_map m
                                  where m.tenant_id = d.tenant_id and m.plan_id = d.plan_id
                                    and m.state = 'LIVE')
                """, tenantId, planId);

        // FR-MIG-007: "rollback is itself audited and reports exactly what it
        // removed". Counts in the summary, per-record evidence in run_issue.
        audit.record("MIGRATION_ROLLBACK", "MIGRATION_PLAN", planId,
                "Rolled back migration plan \"" + plan.name() + "\": removed " + total + " record(s)"
                + (blocked.isEmpty() ? "" : ", " + blocked.size() + " blocked and left in place"),
                Map.of("runId", String.valueOf(runId),
                        "planId", planId.toString(),
                        "recordsRemoved", String.valueOf(total),
                        "recordsBlocked", String.valueOf(blocked.size()),
                        "removedByEntity", removedByEntity.toString(),
                        "sampleData", String.valueOf(plan.sampleData())));

        outbox.write("MIGRATION_PLAN", planId, "migration.rolled-back", Map.of(
                "runId", runId.toString(), "planId", planId.toString(),
                "recordsRemoved", total, "recordsBlocked", blocked.size()));

        return new RollbackResult(planId, total, removedByEntity, removed, blocked);
    }

    private record Ledger(UUID id, String sourceObject, String sourceRecordId, String sourceLabel, UUID targetId) {}

    void requireWithinRetention(PlanRow plan) {
        if (plan.importedAt() == null) {
            throw new ConflictException("Plan \"" + plan.name() + "\" has not imported anything, so there is "
                    + "nothing to roll back.");
        }
        Instant expires = plan.importedAt().plus(plan.retentionDays(), ChronoUnit.DAYS);
        if (!Instant.now().isBefore(expires)) {
            throw new ConflictException("The " + plan.retentionDays() + "-day rollback window for plan \""
                    + plan.name() + "\" closed on " + expires + ". The migration ledger remains readable, but "
                    + "removing records a tenant has been operating on since " + plan.importedAt()
                    + " would destroy work done after the migration.");
        }
    }

    /**
     * Ask PostgreSQL which tables point at this one, then count the pointers.
     *
     * <p>Derived from the catalogue rather than a hard-coded list because the
     * list is not this module's to keep current: eleven other epics add tables
     * that reference {@code crm.account}, and a rollback that knew about nine of
     * them would fail on the tenth with a constraint name instead of a sentence.
     */
    private Map<UUID, String> blockers(UUID planId, String entity, UUID tenantId, Set<UUID> targets) {
        String[] parts = table(entity).split("\\.");
        List<String[]> children = jdbc.query("""
                select distinct child_ns.nspname || '.' || child_rel.relname as child_table,
                                att.attname as child_column
                from pg_constraint con
                join pg_class rel on rel.oid = con.confrelid
                join pg_namespace ns on ns.oid = rel.relnamespace
                join pg_class child_rel on child_rel.oid = con.conrelid
                join pg_namespace child_ns on child_ns.oid = child_rel.relnamespace
                cross join lateral generate_subscripts(con.confkey, 1) as i
                join pg_attribute pa on pa.attrelid = con.confrelid and pa.attnum = con.confkey[i]
                join pg_attribute att on att.attrelid = con.conrelid and att.attnum = con.conkey[i]
                where con.contype = 'f' and ns.nspname = ? and rel.relname = ? and pa.attname = 'id'
                """, (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, parts[0], parts[1]);

        Set<String> alsoDeleted = new HashSet<>(DELETE_ORDER.stream().map(MigrationRollbackService::table).toList());
        Set<String> ownedDerivatives = "OPPORTUNITY".equals(entity)
                ? Set.of("sales.stage_history", "sales.opportunity_state_history") : Set.of();
        Map<UUID, String> blocked = new HashMap<>();

        for (String[] child : children) {
            String childTable = child[0];
            String childColumn = child[1];
            // The two opportunity histories are generated by the opportunity
            // lifecycle itself and are removed by the scoped DB executor. They
            // are not independent operator data. Our own ledger is evidence,
            // never a blocker.
            if (ownedDerivatives.contains(childTable) || childTable.startsWith("migration.")) continue;

            for (List<UUID> chunk : chunks(new ArrayList<>(targets))) {
                List<Object> args = new ArrayList<>();
                args.add(tenantId);
                args.addAll(chunk);
                String migratedChildExclusion = "";
                if (alsoDeleted.contains(childTable)) {
                    // A migrated child is part of this exact rollback scope and
                    // is deleted earlier in DELETE_ORDER (or in the same
                    // statement for a self-reference). A pre-existing child is
                    // deliberately *not* excluded and therefore blocks.
                    migratedChildExclusion = " and not exists (select 1 from migration.record_map m"
                            + " where m.tenant_id = " + childTable + ".tenant_id and m.plan_id = ?"
                            + " and m.target_id = " + childTable + ".id and m.state = 'LIVE')";
                    args.add(planId);
                }
                List<Object[]> hits = jdbc.query("select " + childColumn + "::text, count(*) from " + childTable
                                + " where tenant_id = ? and " + childColumn + " in (" + placeholders(chunk.size())
                                + ")" + migratedChildExclusion + " group by 1",
                        (rs, i) -> new Object[]{rs.getString(1), rs.getLong(2)}, args.toArray());
                for (Object[] hit : hits) {
                    blocked.merge(UUID.fromString((String) hit[0]),
                            hit[1] + " row(s) in " + childTable + "." + childColumn,
                            (a, b) -> a + "; " + b);
                }
            }
        }
        return blocked;
    }

    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    /** Bounded IN lists: PostgreSQL accepts long ones, but bind-parameter limits are real. */
    private static List<List<UUID>> chunks(List<UUID> ids) {
        List<List<UUID>> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 500) {
            out.add(ids.subList(i, Math.min(ids.size(), i + 500)));
        }
        return out;
    }

    // ------------------------------------------------------------------ plan read

    PlanRow plan(UUID tenantId, UUID planId) {
        List<PlanRow> rows = jdbc.query("""
                select id, name, status, retention_days, imported_at, is_sample_data
                from migration.plan where tenant_id = ? and id = ?
                """, (rs, i) -> new PlanRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("status"), rs.getInt("retention_days"),
                        rs.getTimestamp("imported_at") == null ? null : rs.getTimestamp("imported_at").toInstant(),
                        rs.getBoolean("is_sample_data")),
                tenantId, planId);
        if (rows.isEmpty()) throw new NotFoundException("No migration plan " + planId);
        return rows.get(0);
    }

    /** The role gate, applied at request time by {@link MigrationRunService}. */
    static void requireRollbackRole() {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
    }
}

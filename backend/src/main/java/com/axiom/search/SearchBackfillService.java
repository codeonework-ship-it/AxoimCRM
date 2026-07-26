package com.axiom.search;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.NotFoundException;
import com.axiom.security.SystemTaskRunner;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Backfill and reindex — rebuilding the index for a tenant, or for one entity type
 * within it, without waiting for organic events.
 *
 * <h2>Why this exists at all</h2>
 * An event-fed index is only as complete as the event history it has seen. Three
 * ordinary situations leave it incomplete, and none of them are failures: a new
 * deployment starts with an empty index; a bug in the projection is fixed and every
 * document written under the old code is wrong; a tenant is restored from a backup.
 * "Wait for organic events" is not an answer to any of them — a record nobody edits
 * again would never become findable.
 *
 * <h2>Resumable, bounded, and never in the way of a business write</h2>
 * Data model §8 sets the rule for the sharing recompute and it applies verbatim
 * here: rebuilds must be incremental and asynchronous, with visible progress, and
 * must never block business writes. So a request writes one row and returns; a
 * poller drains it in bounded batches; the cursor is written after every batch, so
 * an API restart mid-run resumes at the next record rather than starting again; and
 * paging is keyset-on-primary-key, which takes no table-wide lock and no long
 * snapshot. {@code processed_units} against {@code total_units} is a count of real
 * records, not a spinner pretending to know.
 *
 * <h2>Two phases</h2>
 * <b>INDEX</b> walks the source table and upserts. <b>PRUNE</b> walks the index and
 * removes documents whose source record has gone. A rebuild that only ever wrote
 * would leave a record hard-deleted during an outage findable forever, so the prune
 * phase is part of the run rather than a separate chore someone has to remember.
 */
@Service
public class SearchBackfillService {

    private static final Logger log = LoggerFactory.getLogger(SearchBackfillService.class);

    private static final String PHASE_INDEX = "INDEX";
    private static final String PHASE_PRUNE = "PRUNE";

    public record ReindexRequest(@Size(max = 32) String entityType, @Size(max = 240) String reason) {}

    public record ReindexRun(UUID id, String entityType, String status, String reason,
                             long totalUnits, long processedUnits, long documentsWritten,
                             long documentsRemoved, String phase, Instant queuedAt, Instant startedAt,
                             Instant finishedAt, String message, int percentComplete) {}

    private final JdbcTemplate jdbc;
    private final SearchIndex index;
    private final SearchProjector projector;
    private final SystemTaskRunner tasks;
    private final AuditService audit;
    private final int batchSize;
    private final int maxBatchesPerTick;
    private final boolean enabled;

    public SearchBackfillService(JdbcTemplate jdbc, SearchIndex index, SearchProjector projector,
                                 SystemTaskRunner tasks, AuditService audit,
                                 @Value("${axiom.search.backfill-batch-size:250}") int batchSize,
                                 @Value("${axiom.search.backfill-batches-per-tick:25}") int maxBatchesPerTick,
                                 @Value("${axiom.search.backfill-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.index = index;
        this.projector = projector;
        this.tasks = tasks;
        this.audit = audit;
        this.batchSize = Math.max(25, batchSize);
        this.maxBatchesPerTick = Math.max(1, maxBatchesPerTick);
        this.enabled = enabled;
    }

    // ------------------------------------------------------------------ request and read

    /**
     * Queue a run. Administrator-gated and audited: a reindex touches every record a
     * tenant can search and is exactly the kind of operational action that should be
     * attributable afterwards. The ordinary query path audits nothing — a search box
     * that writes an audit row per keystroke buries the events that matter.
     */
    @Transactional
    public ReindexRun request(ReindexRequest request) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requireMasterAdmin(principal.role());
        String entityType = request == null || request.entityType() == null || request.entityType().isBlank()
                ? null : IndexedEntity.of(request.entityType()).name();
        String reason = request == null ? null : request.reason();

        UUID id = jdbc.queryForObject("""
                insert into search.reindex_run (tenant_id, entity_type, status, reason, requested_by)
                values (?, ?, 'QUEUED', ?, ?)
                returning id
                """, UUID.class, principal.tenantId(), entityType, reason, principal.userId());

        audit.record("SEARCH_REINDEX_REQUESTED", "SEARCH_INDEX", id,
                "Search reindex queued for " + (entityType == null ? "all searchable objects" : entityType),
                Map.of("runId", String.valueOf(id),
                        "entityType", entityType == null ? "ALL" : entityType,
                        "reason", reason == null ? "" : reason));
        return run(id);
    }

    @Transactional(readOnly = true)
    public ReindexRun run(UUID id) {
        ReindexRun run = jdbc.query(RUN_SELECT + " where tenant_id = ? and id = ?",
                rs -> rs.next() ? mapRun(rs) : null, TenantContext.get().tenantId(), id);
        if (run == null) throw new NotFoundException("No reindex run with that id is available to you");
        return run;
    }

    @Transactional(readOnly = true)
    public List<ReindexRun> recentRuns(int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        return jdbc.query(RUN_SELECT + " where tenant_id = ? order by queued_at desc limit ?",
                (rs, i) -> mapRun(rs), TenantContext.get().tenantId(), capped);
    }

    /** The run currently in flight for this tenant, if any — drives the UI progress bar. */
    @Transactional(readOnly = true)
    public ReindexRun activeRun() {
        return jdbc.query(RUN_SELECT + " where tenant_id = ? and status in ('QUEUED','RUNNING')"
                        + " order by queued_at limit 1",
                rs -> rs.next() ? mapRun(rs) : null, TenantContext.get().tenantId());
    }

    // ------------------------------------------------------------------ draining

    @Scheduled(fixedDelayString = "${axiom.search.backfill-poll-fixed-delay-ms:2000}")
    public void drainTick() {
        if (!enabled) return;
        try {
            tasks.forEachTenant("search-backfill", this::drainTenant);
        } catch (RuntimeException ex) {
            log.warn("Search backfill tick failed; will retry: {}", ex.getMessage());
        }
    }

    /** Advance the oldest pending run for one tenant by a bounded amount of work. */
    public int drainTenant(UUID tenantId) {
        RunState state = pending(tenantId);
        if (state == null) return 0;
        try {
            if ("QUEUED".equals(state.status())) state = start(tenantId, state);
            int processed = 0;
            for (int batch = 0; batch < maxBatchesPerTick && state.phase() != null; batch++) {
                Step step = advance(tenantId, state);
                processed += step.processed();
                state = step.state();
                persist(tenantId, state);
            }
            if (state.phase() == null) finish(tenantId, state);
            return processed;
        } catch (RuntimeException ex) {
            log.error("Search reindex run {} failed for tenant {}", state.id(), tenantId, ex);
            jdbc.update("""
                    update search.reindex_run set status = 'FAILED', finished_at = now(), message = ?
                    where tenant_id = ? and id = ?
                    """, truncate(ex.getMessage()), tenantId, state.id());
            return 0;
        }
    }

    private record RunState(UUID id, String status, String entityType, List<IndexedEntity> targets,
                            String phase, IndexedEntity phaseEntity, UUID cursorId,
                            long totalUnits, long processed, long written, long removed) {}

    private record Step(RunState state, int processed) {}

    private RunState pending(UUID tenantId) {
        return jdbc.query("""
                select id, status, entity_type, total_units, processed_units, documents_written,
                       documents_removed, cursor_entity_type, cursor_entity_id
                from search.reindex_run
                where tenant_id = ? and status in ('QUEUED','RUNNING')
                order by queued_at
                limit 1
                for update skip locked
                """, rs -> {
            if (!rs.next()) return null;
            String entityType = rs.getString("entity_type");
            List<IndexedEntity> targets = entityType == null
                    ? List.of(IndexedEntity.values()) : List.of(IndexedEntity.of(entityType));
            String cursor = rs.getString("cursor_entity_type");
            String phase = cursor == null ? null : cursor.substring(0, cursor.indexOf(':'));
            IndexedEntity phaseEntity = cursor == null
                    ? null : IndexedEntity.of(cursor.substring(cursor.indexOf(':') + 1));
            return new RunState(rs.getObject("id", UUID.class), rs.getString("status"), entityType,
                    targets, phase, phaseEntity, rs.getObject("cursor_entity_id", UUID.class),
                    rs.getLong("total_units"), rs.getLong("processed_units"),
                    rs.getLong("documents_written"), rs.getLong("documents_removed"));
        }, tenantId);
    }

    private RunState start(UUID tenantId, RunState state) {
        long total = 0;
        for (IndexedEntity entity : state.targets()) total += projector.count(tenantId, entity);
        IndexedEntity first = state.targets().get(0);
        jdbc.update("""
                update search.reindex_run
                set status = 'RUNNING', started_at = now(), total_units = ?,
                    cursor_entity_type = ?, cursor_entity_id = null
                where tenant_id = ? and id = ?
                """, total, PHASE_INDEX + ":" + first.name(), tenantId, state.id());
        return new RunState(state.id(), "RUNNING", state.entityType(), state.targets(),
                PHASE_INDEX, first, null, total, state.processed(), state.written(), state.removed());
    }

    private Step advance(UUID tenantId, RunState state) {
        if (PHASE_INDEX.equals(state.phase())) return indexBatch(tenantId, state);
        return pruneBatch(tenantId, state);
    }

    private Step indexBatch(UUID tenantId, RunState state) {
        IndexedEntity entity = state.phaseEntity();
        List<UUID> ids = projector.idsAfter(tenantId, entity, state.cursorId(), batchSize);
        if (ids.isEmpty()) return new Step(nextPhase(state), 0);

        long written = state.written();
        for (SearchDocument document : projector.project(tenantId, entity, ids)) {
            if (index.upsert(tenantId, document)) written++;
        }
        UUID cursor = ids.get(ids.size() - 1);
        return new Step(new RunState(state.id(), state.status(), state.entityType(), state.targets(),
                PHASE_INDEX, entity, cursor, state.totalUnits(), state.processed() + ids.size(),
                written, state.removed()), ids.size());
    }

    private Step pruneBatch(UUID tenantId, RunState state) {
        IndexedEntity entity = state.phaseEntity();
        List<UUID> stored = index.storedIds(tenantId, entity, state.cursorId(), batchSize);
        if (stored.isEmpty()) return new Step(nextPhase(state), 0);

        Set<UUID> live = projector.existing(tenantId, entity, stored);
        long removed = state.removed();
        for (UUID id : stored) {
            if (!live.contains(id)) removed += index.delete(tenantId, entity, id);
        }
        UUID cursor = stored.get(stored.size() - 1);
        return new Step(new RunState(state.id(), state.status(), state.entityType(), state.targets(),
                PHASE_PRUNE, entity, cursor, state.totalUnits(), state.processed(),
                state.written(), removed), stored.size());
    }

    /** INDEX over every target type, then PRUNE over every target type, then done. */
    private RunState nextPhase(RunState state) {
        List<IndexedEntity> targets = state.targets();
        int position = targets.indexOf(state.phaseEntity());
        String phase = state.phase();
        IndexedEntity next;
        if (position + 1 < targets.size()) {
            next = targets.get(position + 1);
        } else if (PHASE_INDEX.equals(phase)) {
            phase = PHASE_PRUNE;
            next = targets.get(0);
        } else {
            phase = null;
            next = null;
        }
        return new RunState(state.id(), state.status(), state.entityType(), targets, phase, next,
                null, state.totalUnits(), state.processed(), state.written(), state.removed());
    }

    private void persist(UUID tenantId, RunState state) {
        jdbc.update("""
                update search.reindex_run
                set processed_units = ?, documents_written = ?, documents_removed = ?,
                    cursor_entity_type = ?, cursor_entity_id = ?
                where tenant_id = ? and id = ?
                """, state.processed(), state.written(), state.removed(),
                state.phase() == null ? null : state.phase() + ":" + state.phaseEntity().name(),
                state.cursorId(), tenantId, state.id());
    }

    private void finish(UUID tenantId, RunState state) {
        jdbc.update("""
                update search.reindex_run
                set status = 'COMPLETED', finished_at = now(), processed_units = ?,
                    documents_written = ?, documents_removed = ?, cursor_entity_type = null,
                    cursor_entity_id = null, message = ?
                where tenant_id = ? and id = ?
                """, state.processed(), state.written(), state.removed(),
                "Indexed " + state.written() + " document(s), removed " + state.removed(),
                tenantId, state.id());

        audit.record("SEARCH_REINDEX_COMPLETED", "SEARCH_INDEX", state.id(),
                "Search reindex completed for "
                        + (state.entityType() == null ? "all searchable objects" : state.entityType()),
                Map.of("runId", String.valueOf(state.id()),
                        "entityType", state.entityType() == null ? "ALL" : state.entityType(),
                        "recordsScanned", state.processed(),
                        "documentsWritten", state.written(),
                        "documentsRemoved", state.removed()));
    }

    // ------------------------------------------------------------------ mapping

    private static final String RUN_SELECT = """
            select id, entity_type, status, reason, total_units, processed_units, documents_written,
                   documents_removed, cursor_entity_type, queued_at, started_at, finished_at, message
            from search.reindex_run
            """;

    private static ReindexRun mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        long total = rs.getLong("total_units");
        long processed = rs.getLong("processed_units");
        String status = rs.getString("status");
        int percent = "COMPLETED".equals(status) ? 100
                : total <= 0 ? 0 : (int) Math.min(99, Math.round(100.0 * processed / total));
        String cursor = rs.getString("cursor_entity_type");
        return new ReindexRun(
                rs.getObject("id", UUID.class),
                rs.getString("entity_type") == null ? "ALL" : rs.getString("entity_type"),
                status,
                rs.getString("reason"),
                total, processed,
                rs.getLong("documents_written"),
                rs.getLong("documents_removed"),
                cursor == null ? null : cursor.toUpperCase(Locale.ROOT),
                instant(rs.getTimestamp("queued_at")),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("message"),
                percent);
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String truncate(String message) {
        if (message == null) return "Reindex failed";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    /** Entity types this engine can reindex — surfaced to the admin control. */
    public List<String> indexableTypes() {
        return new ArrayList<>(IndexedEntity.names());
    }
}

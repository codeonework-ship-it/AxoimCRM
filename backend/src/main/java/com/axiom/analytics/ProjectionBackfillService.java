package com.axiom.analytics;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backfill and replay — rebuilding the read model without waiting for organic
 * events.
 *
 * <h2>Why this is not optional</h2>
 * An event-fed projection is only as complete as the event history it has seen,
 * and three ordinary situations leave it incomplete, none of which is a failure:
 * a fresh deployment starts with an empty read model; a projection bug is fixed
 * and every row written under the old code is wrong; a tenant is restored from a
 * backup. "Wait for organic events" answers none of them — an opportunity nobody
 * edits again would never appear in a report.
 *
 * <h2>And it is the broker-independent entry point</h2>
 * ADR-003 permits a degraded mode where the outbox queues because no broker is
 * reachable. This environment is in exactly that state. A projection that could
 * only be driven by a consumer would be untestable and unrecoverable there, so
 * the projection path is deliberately reachable through a plain API call as well.
 *
 * <h2>Synchronous, and honest about why</h2>
 * The search module's reindex is queued and drained by a poller because a search
 * corpus is unbounded. The read model is bounded by the tenant's own record count
 * and each dataset is a single set-based upsert, so a synchronous run is simpler
 * and its result is a number the caller can act on rather than a run id they have
 * to poll. If a tenant ever grows past the point where that holds, this class
 * grows a cursor — the run table already carries the columns for it.
 */
@Service
public class ProjectionBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionBackfillService.class);

    public record BackfillRequest(@Size(max = 32) String dataset, @Size(max = 240) String reason) {}

    public record DatasetResult(String dataset, long sourceRows, int rowsWritten, int rowsRemoved) {}

    public record BackfillRun(UUID id, String dataset, String status, String reason,
                              long totalUnits, long processedUnits, long rowsWritten, long rowsRemoved,
                              Instant queuedAt, Instant startedAt, Instant finishedAt, String message,
                              List<DatasetResult> results) {}

    private final JdbcTemplate jdbc;
    private final ReadModelProjector projector;
    private final AuditService audit;

    public ProjectionBackfillService(JdbcTemplate jdbc, ReadModelProjector projector, AuditService audit) {
        this.jdbc = jdbc;
        this.projector = projector;
        this.audit = audit;
    }

    /**
     * Rebuild one dataset, or every dataset when {@code dataset} is absent.
     *
     * <p>Administrator-gated and audited. A rebuild is not a read: it rewrites
     * every number the tenant's reports show, and an unattributed one is a change
     * nobody can explain afterwards.
     */
    @Transactional
    public BackfillRun run(BackfillRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        UUID tenantId = TenantContext.get().tenantId();
        List<AnalyticsDataset> datasets = resolve(request == null ? null : request.dataset());
        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? "Manual read-model backfill" : request.reason().trim();

        UUID runId = jdbc.queryForObject("""
                insert into analytics.projection_backfill_run
                  (tenant_id, dataset, status, reason, requested_by, started_at)
                values (?, ?, 'RUNNING', ?, ?, now())
                returning id
                """, UUID.class, tenantId,
                datasets.size() == AnalyticsDataset.values().length ? null : datasets.get(0).name(),
                reason, TenantContext.get().userId());

        List<DatasetResult> results = new ArrayList<>();
        long totalSource = 0;
        int written = 0;
        int removed = 0;
        try {
            for (AnalyticsDataset dataset : datasets) {
                long sourceRows = projector.sourceCount(tenantId, dataset);
                int w = projector.projectAll(tenantId, dataset);
                if (dataset == AnalyticsDataset.OPPORTUNITY) {
                    w += projector.projectStageTransitions(tenantId, null);
                }
                int r = projector.prune(tenantId, dataset);
                results.add(new DatasetResult(dataset.name(), sourceRows, w, r));
                totalSource += sourceRows;
                written += w;
                removed += r;
            }
            // Account facts carry rolled-up child measures, so they are only correct
            // once the child datasets have been rebuilt.
            if (datasets.contains(AnalyticsDataset.ACCOUNT)) projector.refreshAccountRollups(tenantId);

            // Park the consumer cursor at the head of the outbox for the datasets we
            // just rebuilt. Without this, the next tick would replay history the
            // backfill has already superseded — harmless because the projection is
            // idempotent, but a pointless scan on every restart.
            for (AnalyticsDataset dataset : datasets) fastForward(tenantId, dataset);

            jdbc.update("""
                    update analytics.projection_backfill_run
                       set status = 'COMPLETED', total_units = ?, processed_units = ?,
                           rows_written = ?, rows_removed = ?, finished_at = now(),
                           message = ?
                     where tenant_id = ? and id = ?
                    """, totalSource, totalSource, (long) written, (long) removed,
                    "Rebuilt " + datasets.size() + " dataset(s)", tenantId, runId);
        } catch (RuntimeException ex) {
            log.error("Read-model backfill failed for tenant {}", tenantId, ex);
            jdbc.update("""
                    update analytics.projection_backfill_run
                       set status = 'FAILED', finished_at = now(), message = ?
                     where tenant_id = ? and id = ?
                    """, ex.getMessage(), tenantId, runId);
            throw ex;
        }

        audit.record("ANALYTICS_PROJECTION_BACKFILL", "PROJECTION", runId,
                "Read model rebuilt: " + written + " row(s) written, " + removed + " removed",
                Map.of("datasets", datasets.stream().map(Enum::name).toList(),
                        "rowsWritten", written, "rowsRemoved", removed, "reason", reason));

        return run(runId, results);
    }

    /**
     * Rewind one dataset's cursor so the consumer replays it from the beginning.
     *
     * <p>The recovery path ADR-008 describes: "replay from the outbox makes it
     * possible; it does not make it fast." Separate from {@link #run} because a
     * replay reprocesses history through the consumer, which is the right tool
     * when the projection code changed, whereas a backfill reads current state,
     * which is the right tool when the data changed underneath it.
     */
    @Transactional
    public Map<String, Object> replay(String datasetName) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        UUID tenantId = TenantContext.get().tenantId();
        List<AnalyticsDataset> datasets = resolve(datasetName);
        int reset = 0;
        for (AnalyticsDataset dataset : datasets) {
            reset += jdbc.update("""
                    update analytics.projection_checkpoint
                       set last_event_at = '-infinity', last_event_id = null, updated_at = now()
                     where tenant_id = ? and consumer = ? and dataset = ?
                    """, tenantId, ProjectionConsumer.CONSUMER, dataset.name());
        }
        audit.record("ANALYTICS_PROJECTION_REPLAY", "PROJECTION", tenantId,
                "Projection cursor rewound for " + datasets.size() + " dataset(s)",
                Map.of("datasets", datasets.stream().map(Enum::name).toList()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasets", datasets.stream().map(Enum::name).toList());
        result.put("cursorsReset", reset);
        result.put("note", "The consumer replays from the head of the outbox on its next tick. "
                + "Replay is idempotent; it is not instantaneous.");
        return result;
    }

    @Transactional(readOnly = true)
    public List<BackfillRun> recentRuns(int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        return jdbc.query("""
                select id, dataset, status, reason, total_units, processed_units, rows_written,
                       rows_removed, queued_at, started_at, finished_at, message
                  from analytics.projection_backfill_run
                 where tenant_id = ?
                 order by queued_at desc
                 limit ?
                """, (rs, i) -> new BackfillRun(
                rs.getObject("id", UUID.class), rs.getString("dataset"), rs.getString("status"),
                rs.getString("reason"), rs.getLong("total_units"), rs.getLong("processed_units"),
                rs.getLong("rows_written"), rs.getLong("rows_removed"),
                instant(rs.getTimestamp("queued_at")), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")), rs.getString("message"), List.of()),
                TenantContext.get().tenantId(), capped);
    }

    private BackfillRun run(UUID runId, List<DatasetResult> results) {
        BackfillRun row = jdbc.queryForObject("""
                select id, dataset, status, reason, total_units, processed_units, rows_written,
                       rows_removed, queued_at, started_at, finished_at, message
                  from analytics.projection_backfill_run
                 where tenant_id = ? and id = ?
                """, (rs, i) -> new BackfillRun(
                rs.getObject("id", UUID.class), rs.getString("dataset"), rs.getString("status"),
                rs.getString("reason"), rs.getLong("total_units"), rs.getLong("processed_units"),
                rs.getLong("rows_written"), rs.getLong("rows_removed"),
                instant(rs.getTimestamp("queued_at")), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")), rs.getString("message"), results),
                TenantContext.get().tenantId(), runId);
        return row;
    }

    private void fastForward(UUID tenantId, AnalyticsDataset dataset) {
        jdbc.update("""
                insert into analytics.projection_checkpoint
                  (tenant_id, consumer, dataset, last_event_at, last_event_id, updated_at)
                select ?, ?, ?, coalesce(max(e.created_at), '-infinity'::timestamptz),
                       (select id from integration.outbox_event
                         where tenant_id = ? and lower(aggregate_type) = ?
                         order by created_at desc, id desc limit 1),
                       now()
                  from integration.outbox_event e
                 where e.tenant_id = ? and lower(e.aggregate_type) = ?
                on conflict (tenant_id, consumer, dataset) do update set
                  last_event_at = excluded.last_event_at,
                  last_event_id = excluded.last_event_id,
                  updated_at = now()
                """, tenantId, ProjectionConsumer.CONSUMER, dataset.name(),
                tenantId, dataset.aggregateType(), tenantId, dataset.aggregateType());
    }

    static List<AnalyticsDataset> resolve(String datasetName) {
        if (datasetName == null || datasetName.isBlank()) return List.of(AnalyticsDataset.values());
        return List.of(AnalyticsDataset.of(datasetName));
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

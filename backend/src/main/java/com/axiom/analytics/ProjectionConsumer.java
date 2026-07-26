package com.axiom.analytics;

import com.axiom.security.SystemTaskRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The read-model projection worker (ADR-003, ADR-008 decision 1).
 *
 * <h2>It consumes the outbox, not the broker</h2>
 * ADR-003 names the transactional outbox — not Kafka — as the source of truth,
 * precisely so a consumer can be fed this way. Reading the outbox directly means
 * the read model stays correct in a sovereign install that never deploys a
 * broker, and in the degraded mode this environment is actually in today (the
 * outbox queues, the relay cannot reach Kafka). A Kafka-listener variant would be
 * a new class calling the same {@link #applyTouched} method, because the work is
 * keyed on record ids rather than on delivery mechanics.
 *
 * <h2>Idempotence is a property of the design, not a check</h2>
 * An event is used for exactly one thing: <em>which record changed</em>. The fact
 * row is then re-projected from the authoritative row as it stands now, through
 * an upsert carrying a watermark guard ({@link ReadModelProjector}). Duplicate
 * delivery therefore produces one row with identical values; an older event
 * changes nothing at all. Neither case needs a de-duplication table, which would
 * itself have to be pruned and would become a second thing to get wrong.
 *
 * <h2>Cursor per dataset</h2>
 * {@code (created_at, id)} strict row comparison, stored per
 * {@code (tenant, consumer, dataset)}. Two events committed in the same
 * microsecond cannot make the consumer skip one or replay forever, and one bad
 * projection can be rewound without rewinding the other three.
 */
@Component
public class ProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProjectionConsumer.class);

    /** Consumer-group name in {@code analytics.projection_checkpoint}. */
    public static final String CONSUMER = "read-model";

    private final JdbcTemplate jdbc;
    private final ReadModelProjector projector;
    private final SystemTaskRunner tasks;
    private final int batchSize;
    private final boolean enabled;

    /**
     * Annotated because this bean has a second, package-private constructor used
     * by the unit tests. Without the annotation Spring sees two candidates, finds
     * no no-arg constructor, and the whole application context fails to start.
     */
    @Autowired
    public ProjectionConsumer(JdbcTemplate jdbc, ReadModelProjector projector, SystemTaskRunner tasks,
                              @Value("${axiom.analytics.consumer-batch-size:250}") int batchSize,
                              @Value("${axiom.analytics.consumer-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.projector = projector;
        this.tasks = tasks;
        this.batchSize = Math.max(10, batchSize);
        this.enabled = enabled;
    }

    /** Test seam: defaults for the tuning knobs, so a test does not restate them. */
    ProjectionConsumer(JdbcTemplate jdbc, ReadModelProjector projector, SystemTaskRunner tasks) {
        this(jdbc, projector, tasks, 250, true);
    }

    @Scheduled(fixedDelayString = "${axiom.analytics.consumer-poll-fixed-delay-ms:5000}")
    public void consumeTick() {
        if (!enabled) return;
        try {
            tasks.forEachTenant("analytics-projection", this::consumeTenant);
        } catch (RuntimeException ex) {
            log.warn("Read-model projection tick failed; will retry: {}", ex.getMessage());
        }
    }

    /** One bounded batch across every dataset for one tenant. @return events consumed. */
    public int consumeTenant(UUID tenantId) {
        int consumed = 0;
        boolean touchedRollupSource = false;
        for (AnalyticsDataset dataset : AnalyticsDataset.values()) {
            int applied = consume(tenantId, dataset);
            consumed += applied;
            if (applied > 0 && (dataset == AnalyticsDataset.OPPORTUNITY || dataset == AnalyticsDataset.ACTIVITY)) {
                touchedRollupSource = true;
            }
        }
        // Account rollups are derived from the other projections, so they are
        // refreshed once per tick rather than once per event — a stage change must
        // not cost a full account scan.
        if (touchedRollupSource) projector.refreshAccountRollups(tenantId);
        return consumed;
    }

    record Event(UUID id, UUID aggregateId, Instant createdAt) {}

    record Cursor(Instant at, UUID id) {}

    @Transactional
    public int consume(UUID tenantId, AnalyticsDataset dataset) {
        Cursor cursor = cursor(tenantId, dataset);
        List<Event> events = jdbc.query("""
                select id, aggregate_id, created_at
                  from integration.outbox_event
                 where tenant_id = ?
                   and lower(aggregate_type) = ?
                   and (created_at, id) > (coalesce(?, '-infinity'::timestamptz),
                                           coalesce(?, '00000000-0000-0000-0000-000000000000'::uuid))
                 order by created_at, id
                 limit ?
                """, ps -> {
            ps.setObject(1, tenantId);
            ps.setString(2, dataset.aggregateType());
            ps.setTimestamp(3, cursor.at() == null ? null : Timestamp.from(cursor.at()));
            ps.setObject(4, cursor.id());
            ps.setInt(5, batchSize);
        }, (rs, i) -> new Event(rs.getObject("id", UUID.class),
                rs.getObject("aggregate_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()));

        if (events.isEmpty()) return 0;

        // Collapse the batch to the distinct records it touched. Ten stage changes
        // on one opportunity are one re-projection, not ten — and because the
        // projection reads current state, collapsing cannot lose an intermediate
        // value that any report could observe.
        Set<UUID> touched = new LinkedHashSet<>();
        for (Event event : events) touched.add(event.aggregateId());

        Applied applied = applyTouched(tenantId, dataset, new ArrayList<>(touched));
        Event last = events.get(events.size() - 1);
        advance(tenantId, dataset, last, events.size(), applied);
        log.debug("Read-model projection: tenant {} dataset {} consumed {} event(s), {} written, {} removed",
                tenantId, dataset, events.size(), applied.written(), applied.removed());
        return events.size();
    }

    record Applied(int written, int removed) {}

    /**
     * Re-project the given records and drop any whose source row has gone.
     *
     * <p>Public and delivery-agnostic on purpose: this is what a Kafka listener, a
     * backfill, or a test with no broker at all would call. ADR-003's degraded mode
     * is only survivable if the projection path can be driven without one.
     */
    @Transactional
    public Applied applyTouched(UUID tenantId, AnalyticsDataset dataset, List<UUID> ids) {
        if (ids.isEmpty()) return new Applied(0, 0);
        int written = projector.project(tenantId, dataset, ids);
        // Stage occupancy rides along with the opportunity projection — a stage
        // transition never exists without an opportunity event to carry it.
        if (dataset == AnalyticsDataset.OPPORTUNITY) {
            written += projector.projectStageTransitions(tenantId, ids);
        }
        int removed = jdbc.update("""
                delete from %s f
                 where f.tenant_id = ? and f.%s = any(?)
                   and not exists (select 1 from %s t
                                    where t.tenant_id = f.tenant_id and t.id = f.%s%s)
                """.formatted(dataset.factTable(), dataset.idColumn(), dataset.sourceTable(),
                dataset.idColumn(), dataset.sourceSoftDeleted() ? " and t.deleted_at is null" : ""),
                ps -> {
                    ps.setObject(1, tenantId);
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
                });
        return new Applied(written, removed);
    }

    // ------------------------------------------------------------------ cursor

    Cursor cursor(UUID tenantId, AnalyticsDataset dataset) {
        Cursor cursor = jdbc.query("""
                select last_event_at, last_event_id from analytics.projection_checkpoint
                 where tenant_id = ? and consumer = ? and dataset = ?
                """, rs -> {
            if (!rs.next()) return new Cursor(null, null);
            Timestamp at = rs.getTimestamp("last_event_at");
            // '-infinity' round-trips as a sentinel far outside the useful range.
            boolean unset = at == null || at.getTime() <= Long.MIN_VALUE + 1000L;
            return new Cursor(unset ? null : at.toInstant(), rs.getObject("last_event_id", UUID.class));
        }, tenantId, CONSUMER, dataset.name());
        return cursor == null ? new Cursor(null, null) : cursor;
    }

    private void advance(UUID tenantId, AnalyticsDataset dataset, Event last, int consumed, Applied applied) {
        jdbc.update("""
                insert into analytics.projection_checkpoint
                  (tenant_id, consumer, dataset, last_event_at, last_event_id, events_applied,
                   rows_written, rows_removed, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (tenant_id, consumer, dataset) do update set
                  last_event_at = excluded.last_event_at,
                  last_event_id = excluded.last_event_id,
                  events_applied = projection_checkpoint.events_applied + excluded.events_applied,
                  rows_written = projection_checkpoint.rows_written + excluded.rows_written,
                  rows_removed = projection_checkpoint.rows_removed + excluded.rows_removed,
                  last_error = null,
                  updated_at = now()
                """, tenantId, CONSUMER, dataset.name(), Timestamp.from(last.createdAt()), last.id(),
                (long) consumed, (long) applied.written(), (long) applied.removed());
    }
}

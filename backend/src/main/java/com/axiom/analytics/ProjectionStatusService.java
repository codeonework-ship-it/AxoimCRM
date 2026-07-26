package com.axiom.analytics;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Projection staleness — the number ADR-008 decision 5 requires to be
 * <em>displayed</em>, not merely measured.
 *
 * <p>The reasoning is a support-cost argument rather than a purity one, and doc
 * 14 §1 states it plainly: "a report thirty seconds behind is fine; a report
 * <i>silently</i> thirty seconds behind generates a support ticket and erodes
 * trust in every number the product shows." So every projected figure this module
 * returns carries a {@link Staleness} with it, and the front end has no way to
 * render a figure without one.
 *
 * <h2>What each number actually means</h2>
 * <ul>
 *   <li>{@code lagSeconds} — how far behind the SOURCE the projection is:
 *       now minus the newest {@code source_updated_at} in the fact table. This is
 *       the honest figure. Using {@code projected_at} instead would make the lag
 *       read zero every time the worker ran, whether or not it had anything
 *       correct to say.</li>
 *   <li>{@code pendingEvents} — outbox events for this dataset that the consumer
 *       has not reached. Non-zero lag with zero backlog is a quiet system; lag
 *       with a growing backlog is an incident, and the two must be
 *       distinguishable.</li>
 *   <li>{@code behindSourceRows} — fact rows minus live source rows. A projection
 *       can be perfectly current on everything it knows about and still be missing
 *       records entirely, which no timestamp would reveal.</li>
 * </ul>
 *
 * <p>ADR-008 lists projection lag as "a monitored service-level indicator with its
 * own incident class". {@code degraded} is that SLI evaluated against a configured
 * threshold, returned rather than left for a dashboard to re-derive.
 */
@Service
public class ProjectionStatusService {

    /** Above this the UI is expected to warn rather than merely state the lag. */
    private static final long DEGRADED_LAG_SECONDS = 300;

    public record DatasetStaleness(String dataset, long rowCount, long sourceRowCount,
                                   long behindSourceRows, Instant newestSourceUpdatedAt,
                                   Instant lastProjectedAt, Instant checkpointAt,
                                   Long lagSeconds, long pendingEvents, boolean degraded) {}

    /**
     * The tenant-level roll-up carried on every projected figure. {@code asOf} is
     * the oldest source watermark across the datasets a figure was built from —
     * deliberately the worst case, because a figure is only as current as its
     * stalest input.
     */
    public record Staleness(Instant asOf, Long lagSeconds, long pendingEvents, boolean degraded,
                            String statement) {}

    private final JdbcTemplate jdbc;

    public ProjectionStatusService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<DatasetStaleness> status() {
        UUID tenantId = TenantContext.get().tenantId();
        List<DatasetStaleness> out = new ArrayList<>();
        for (AnalyticsDataset dataset : AnalyticsDataset.values()) out.add(status(tenantId, dataset));
        return out;
    }

    @Transactional(readOnly = true)
    public DatasetStaleness status(UUID tenantId, AnalyticsDataset dataset) {
        Row row = jdbc.queryForObject("""
                select (select count(*) from %s where tenant_id = ?) as row_count,
                       (select max(source_updated_at) from %s where tenant_id = ?) as newest_source,
                       (select max(projected_at) from %s where tenant_id = ?) as last_projected,
                       (select count(*) from %s t where t.tenant_id = ?%s) as source_rows
                """.formatted(dataset.factTable(), dataset.factTable(), dataset.factTable(),
                dataset.sourceTable(), dataset.sourceSoftDeleted() ? " and t.deleted_at is null" : ""),
                (rs, i) -> new Row(rs.getLong("row_count"), instant(rs.getTimestamp("newest_source")),
                        instant(rs.getTimestamp("last_projected")), rs.getLong("source_rows")),
                tenantId, tenantId, tenantId, tenantId);
        if (row == null) row = new Row(0, null, null, 0);

        Checkpoint checkpoint = checkpoint(tenantId, dataset);
        Long lag = row.newestSource() == null ? null
                : Math.max(0L, Duration.between(row.newestSource(), Instant.now()).toSeconds());
        long behind = Math.max(0L, row.sourceRows() - row.rowCount());
        boolean degraded = (lag != null && lag > DEGRADED_LAG_SECONDS)
                || checkpoint.pending() > 0 || behind > 0;

        return new DatasetStaleness(dataset.name(), row.rowCount(), row.sourceRows(), behind,
                row.newestSource(), row.lastProjected(), checkpoint.at(), lag,
                checkpoint.pending(), degraded);
    }

    /** The roll-up to attach to a figure built from these datasets. */
    @Transactional(readOnly = true)
    public Staleness stalenessFor(UUID tenantId, AnalyticsDataset... datasets) {
        Instant asOf = null;
        long pending = 0;
        boolean degraded = false;
        for (AnalyticsDataset dataset : datasets) {
            DatasetStaleness status = status(tenantId, dataset);
            if (status.newestSourceUpdatedAt() != null
                    && (asOf == null || status.newestSourceUpdatedAt().isBefore(asOf))) {
                asOf = status.newestSourceUpdatedAt();
            }
            pending += status.pendingEvents();
            degraded = degraded || status.degraded();
        }
        Long lag = asOf == null ? null : Math.max(0L, Duration.between(asOf, Instant.now()).toSeconds());
        return new Staleness(asOf, lag, pending, degraded, statement(lag, pending));
    }

    /**
     * The sentence the UI shows. Composed server-side so that every surface —
     * report viewer, KPI tile, exported header — says the same thing about the same
     * projection, rather than each inventing its own wording for "how old is this".
     */
    static String statement(Long lagSeconds, long pendingEvents) {
        if (lagSeconds == null) return "No projected data yet — run a backfill to populate the read model.";
        String age = lagSeconds < 60 ? lagSeconds + "s"
                : lagSeconds < 3600 ? (lagSeconds / 60) + "m"
                : lagSeconds < 86400 ? (lagSeconds / 3600) + "h"
                : (lagSeconds / 86400) + "d";
        String base = "Projected data as of " + age + " ago";
        return pendingEvents > 0 ? base + "; " + pendingEvents + " event(s) still to apply" : base + ".";
    }

    private record Row(long rowCount, Instant newestSource, Instant lastProjected, long sourceRows) {}

    private record Checkpoint(Instant at, long pending) {}

    private Checkpoint checkpoint(UUID tenantId, AnalyticsDataset dataset) {
        Checkpoint found = jdbc.query("""
                select c.last_event_at,
                       (select count(*) from integration.outbox_event e
                         where e.tenant_id = ? and lower(e.aggregate_type) = ?
                           and (e.created_at, e.id) > (coalesce(c.last_event_at, '-infinity'::timestamptz),
                                                       coalesce(c.last_event_id,
                                                                '00000000-0000-0000-0000-000000000000'::uuid))
                       ) as pending
                  from analytics.projection_checkpoint c
                 where c.tenant_id = ? and c.consumer = ? and c.dataset = ?
                """, rs -> {
            if (!rs.next()) return null;
            java.sql.Timestamp at = rs.getTimestamp("last_event_at");
            boolean unset = at == null || at.getTime() <= Long.MIN_VALUE + 1000L;
            return new Checkpoint(unset ? null : at.toInstant(), rs.getLong("pending"));
        }, tenantId, dataset.aggregateType(), tenantId, ProjectionConsumer.CONSUMER, dataset.name());

        if (found != null) return found;
        // No checkpoint yet: every event for this dataset is pending by definition.
        Long pending = jdbc.queryForObject("""
                select count(*) from integration.outbox_event
                 where tenant_id = ? and lower(aggregate_type) = ?
                """, Long.class, tenantId, dataset.aggregateType());
        return new Checkpoint(null, pending == null ? 0 : pending);
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

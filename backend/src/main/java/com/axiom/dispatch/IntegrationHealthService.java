package com.axiom.dispatch;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Per-connector integration health (FR-INT-009).
 *
 * <p>Reports the four numbers an administrator actually needs to answer "is this
 * working?": when it last succeeded, when it last failed, how deep the pending
 * queue is, and how many messages have been lost to the dead-letter list. A
 * health view that shows only a green tick is the silent failure the requirement
 * calls a defect.
 *
 * <p>{@code IDLE} is reported distinctly from {@code HEALTHY}: a connector that
 * has never delivered anything is not healthy, it is untested, and conflating
 * the two is how a misconfigured endpoint sits green for a month.
 */
@Service
public class IntegrationHealthService {

    private final JdbcTemplate jdbc;

    public IntegrationHealthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record HealthRow(UUID connectorId, String connectorName, String connectorType, String vendor,
                            boolean enabled, String breakerState, int consecutiveFailures,
                            long totalSuccess, long totalFailure, Instant lastSuccessAt, Instant lastFailureAt,
                            String lastError, Instant breakerOpenedAt, long pendingDepth, long deadLetterDepth,
                            Instant oldestPendingAt, String status) {}

    public record HealthSummary(int connectors, int enabledConnectors, int openBreakers,
                                long pendingDepth, long deadLetterDepth, long succeededLast24h,
                                long failedLast24h, Instant generatedAt) {}

    @Transactional(readOnly = true)
    public List<HealthRow> connectors() {
        return jdbc.query("""
                select k.id, k.display_name, k.connector_type, k.vendor, k.enabled,
                       coalesce(h.breaker_state, 'CLOSED') as breaker_state,
                       coalesce(h.consecutive_failures, 0) as consecutive_failures,
                       coalesce(h.total_success, 0) as total_success,
                       coalesce(h.total_failure, 0) as total_failure,
                       h.last_success_at, h.last_failure_at, h.last_error, h.opened_at,
                       (select count(*) from dispatch.dispatch_delivery d
                         where d.tenant_id = k.tenant_id and d.connector_id = k.id
                           and d.status in ('PENDING','IN_FLIGHT')) as pending_depth,
                       (select count(*) from dispatch.dispatch_dead_letter l
                         where l.tenant_id = k.tenant_id and l.connector_id = k.id
                           and l.replayed_at is null) as dead_letter_depth,
                       (select min(d.created_at) from dispatch.dispatch_delivery d
                         where d.tenant_id = k.tenant_id and d.connector_id = k.id
                           and d.status in ('PENDING','IN_FLIGHT')) as oldest_pending_at
                from dispatch.connector k
                left join dispatch.connector_health h
                       on h.tenant_id = k.tenant_id and h.connector_id = k.id
                where k.tenant_id = ?
                order by k.display_name
                """, (rs, i) -> {
                    boolean enabled = rs.getBoolean("enabled");
                    String breaker = rs.getString("breaker_state");
                    long deadLetters = rs.getLong("dead_letter_depth");
                    long success = rs.getLong("total_success");
                    long failure = rs.getLong("total_failure");
                    int consecutive = rs.getInt("consecutive_failures");
                    String status;
                    if (!enabled) status = "DISABLED";
                    else if ("OPEN".equals(breaker)) status = "PAUSED";
                    else if ("HALF_OPEN".equals(breaker)) status = "PROBING";
                    else if (deadLetters > 0 || consecutive > 0) status = "DEGRADED";
                    else if (success == 0 && failure == 0) status = "IDLE";
                    else status = "HEALTHY";
                    return new HealthRow(
                            rs.getObject("id", UUID.class), rs.getString("display_name"),
                            rs.getString("connector_type"), rs.getString("vendor"), enabled, breaker,
                            consecutive, success, failure,
                            instant(rs.getTimestamp("last_success_at")), instant(rs.getTimestamp("last_failure_at")),
                            rs.getString("last_error"), instant(rs.getTimestamp("opened_at")),
                            rs.getLong("pending_depth"), deadLetters,
                            instant(rs.getTimestamp("oldest_pending_at")), status);
                }, TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public HealthSummary summary() {
        List<HealthRow> rows = connectors();
        long pending = rows.stream().mapToLong(HealthRow::pendingDepth).sum();
        long dead = rows.stream().mapToLong(HealthRow::deadLetterDepth).sum();
        int open = (int) rows.stream().filter(r -> "OPEN".equals(r.breakerState())).count();
        int enabled = (int) rows.stream().filter(HealthRow::enabled).count();
        UUID tenantId = TenantContext.get().tenantId();
        Long succeeded = jdbc.queryForObject("""
                select count(*) from dispatch.dispatch_delivery
                where tenant_id = ? and status = 'SUCCEEDED' and succeeded_at > now() - interval '24 hours'
                """, Long.class, tenantId);
        Long failed = jdbc.queryForObject("""
                select count(*) from dispatch.dispatch_attempt
                where tenant_id = ? and status in ('RETRYABLE_FAILURE','PERMANENT_FAILURE')
                  and attempted_at > now() - interval '24 hours'
                """, Long.class, tenantId);
        return new HealthSummary(rows.size(), enabled, open, pending, dead,
                succeeded == null ? 0 : succeeded, failed == null ? 0 : failed, Instant.now());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

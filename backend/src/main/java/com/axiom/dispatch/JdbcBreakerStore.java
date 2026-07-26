package com.axiom.dispatch;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable breaker state in {@code dispatch.connector_health}, one row per connector. */
@Component
public class JdbcBreakerStore implements BreakerStore {

    private final JdbcTemplate jdbc;

    public JdbcBreakerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BreakerState load(UUID connectorId) {
        List<BreakerState> rows = jdbc.query("""
                select breaker_state, consecutive_failures, opened_at, last_success_at, last_failure_at, last_error
                from dispatch.connector_health
                where tenant_id = ? and connector_id = ?
                """, (rs, i) -> new BreakerState(
                        BreakerState.Phase.valueOf(rs.getString("breaker_state")),
                        rs.getInt("consecutive_failures"),
                        instant(rs.getTimestamp("opened_at")),
                        instant(rs.getTimestamp("last_success_at")),
                        instant(rs.getTimestamp("last_failure_at")),
                        rs.getString("last_error")),
                TenantContext.get().tenantId(), connectorId);
        return rows.isEmpty() ? BreakerState.closed() : rows.get(0);
    }

    @Override
    public void save(UUID connectorId, BreakerState state, boolean countedSuccess, boolean countedFailure) {
        jdbc.update("""
                insert into dispatch.connector_health
                  (connector_id, tenant_id, breaker_state, consecutive_failures, total_success, total_failure,
                   last_success_at, last_failure_at, last_error, opened_at, half_open_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (connector_id) do update set
                  breaker_state = excluded.breaker_state,
                  consecutive_failures = excluded.consecutive_failures,
                  total_success = dispatch.connector_health.total_success + excluded.total_success,
                  total_failure = dispatch.connector_health.total_failure + excluded.total_failure,
                  last_success_at = coalesce(excluded.last_success_at, dispatch.connector_health.last_success_at),
                  last_failure_at = coalesce(excluded.last_failure_at, dispatch.connector_health.last_failure_at),
                  last_error = excluded.last_error,
                  opened_at = excluded.opened_at,
                  half_open_at = excluded.half_open_at,
                  updated_at = now()
                """,
                connectorId, TenantContext.get().tenantId(), state.phase().name(), state.consecutiveFailures(),
                countedSuccess ? 1 : 0, countedFailure ? 1 : 0,
                timestamp(state.lastSuccessAt()), timestamp(state.lastFailureAt()), state.lastError(),
                timestamp(state.openedAt()),
                state.phase() == BreakerState.Phase.HALF_OPEN ? Timestamp.from(Instant.now()) : null);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}

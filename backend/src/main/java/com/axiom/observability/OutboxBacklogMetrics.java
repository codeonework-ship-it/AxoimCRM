package com.axiom.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Cross-tenant outbox backlog gauges read through the least-privilege relay role. */
@Component
public class OutboxBacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxBacklogMetrics.class);

    private final JdbcTemplate relayJdbc;
    private final AtomicLong backlogEvents = new AtomicLong();
    private final AtomicLong oldestEventAgeSeconds = new AtomicLong();
    private final Counter refreshFailures;

    public OutboxBacklogMetrics(@Qualifier("relayJdbcTemplate") JdbcTemplate relayJdbc,
                                MeterRegistry registry) {
        this.relayJdbc = relayJdbc;
        Gauge.builder("axiom.outbox.backlog.events", backlogEvents, AtomicLong::get)
                .description("Undispatched transactional outbox events across the platform")
                .register(registry);
        Gauge.builder("axiom.outbox.oldest.event.age.seconds", oldestEventAgeSeconds, AtomicLong::get)
                .description("Age in seconds of the oldest undispatched outbox event")
                .baseUnit("seconds")
                .register(registry);
        this.refreshFailures = Counter.builder("axiom.outbox.metrics.refresh.failures")
                .description("Failures while refreshing outbox backlog gauges")
                .register(registry);
    }

    @Scheduled(initialDelayString = "${axiom.observability.outbox.initial-delay-ms:15000}",
            fixedDelayString = "${axiom.observability.outbox.refresh-ms:15000}")
    public void refresh() {
        try {
            Map<String, Object> row = relayJdbc.queryForMap("""
                    select count(*) as backlog,
                           coalesce(extract(epoch from (now() - min(created_at))), 0)::bigint as oldest_age_seconds
                    from outbox_event
                    where dispatched_at is null
                    """);
            backlogEvents.set(number(row.get("backlog")));
            oldestEventAgeSeconds.set(number(row.get("oldest_age_seconds")));
        } catch (RuntimeException failure) {
            refreshFailures.increment();
            log.warn("Outbox metric refresh failed; retaining the last known gauge values: {}", failure.getMessage());
        }
    }

    private static long number(Object value) {
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }
}

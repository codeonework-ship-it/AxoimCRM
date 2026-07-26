package com.axiom.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxBacklogMetricsTest {

    @Test
    void exposesBacklogAndAgeWithoutTenantOrEventLabels() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Map.of("backlog", 17L, "oldest_age_seconds", 83L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        OutboxBacklogMetrics metrics = new OutboxBacklogMetrics(jdbc, registry);
        metrics.refresh();

        assertThat(registry.get("axiom.outbox.backlog.events").gauge().value()).isEqualTo(17);
        assertThat(registry.get("axiom.outbox.oldest.event.age.seconds").gauge().value()).isEqualTo(83);
        assertThat(registry.get("axiom.outbox.metrics.refresh.failures").counter().count()).isZero();
    }

    @Test
    void refreshFailureKeepsLastKnownValuesAndIncrementsFailureCounter() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Map.of("backlog", 4L, "oldest_age_seconds", 9L))
                .thenThrow(new IllegalStateException("database unavailable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxBacklogMetrics metrics = new OutboxBacklogMetrics(jdbc, registry);

        metrics.refresh();
        metrics.refresh();

        assertThat(registry.get("axiom.outbox.backlog.events").gauge().value()).isEqualTo(4);
        assertThat(registry.get("axiom.outbox.metrics.refresh.failures").counter().count()).isEqualTo(1);
    }
}

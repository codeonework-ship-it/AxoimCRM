package com.axiom.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CrmMetricsTest {

    @Test
    void recordsOnlyBoundedOperationalLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CrmMetrics metrics = new CrmMetrics(registry);

        metrics.record(metrics.start(), "record_lock", "acquire", "conflict");
        metrics.record(metrics.start(), "tenant-28f36-user@example.com", "record-uuid", "secret-value");

        assertThat(registry.get("axiom.crm.operations").tags(
                "module", "record_lock", "operation", "acquire", "outcome", "conflict").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("axiom.crm.operations").tags(
                "module", "error", "operation", "error", "outcome", "error").counter().count())
                .isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags().toString())
                        .doesNotContain("example.com", "28f36", "secret-value", "record-uuid"));
    }
}

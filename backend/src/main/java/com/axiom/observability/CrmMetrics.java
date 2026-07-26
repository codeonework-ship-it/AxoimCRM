package com.axiom.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** Low-cardinality operational metrics for governed CRM workflows. */
@Component
public class CrmMetrics {

    private static final Set<String> MODULES = Set.of("automation", "reporting", "record_lock", "approval");
    private static final Set<String> OPERATIONS = Set.of(
            "execute", "dry_run", "export_pdf", "export_xlsx", "export_docx", "document_preview",
            "acquire", "heartbeat", "release", "release_all_for_current_user", "force_release", "status",
            "submit", "approve", "reject", "delegate", "revoke_delegation");
    private static final Set<String> OUTCOMES = Set.of(
            "succeeded", "generated", "skipped", "failed", "halted", "conflict", "denied", "error");

    private final MeterRegistry registry;

    public CrmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void record(Timer.Sample sample, String module, String operation, String outcome) {
        String safeModule = bounded(module, MODULES);
        String safeOperation = bounded(operation, OPERATIONS);
        String safeOutcome = bounded(outcome, OUTCOMES);
        Counter.builder("axiom.crm.operations")
                .description("Governed CRM operations by bounded module, operation and outcome")
                .tags("module", safeModule, "operation", safeOperation, "outcome", safeOutcome)
                .register(registry)
                .increment();
        sample.stop(Timer.builder("axiom.crm.operation.duration")
                .description("Duration of governed CRM operations")
                .tags("module", safeModule, "operation", safeOperation, "outcome", safeOutcome)
                .serviceLevelObjectives(Duration.ofMillis(100), Duration.ofMillis(500),
                        Duration.ofSeconds(2), Duration.ofSeconds(10))
                .register(registry));
    }

    static String bounded(String value, Set<String> allowed) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : "error";
    }
}

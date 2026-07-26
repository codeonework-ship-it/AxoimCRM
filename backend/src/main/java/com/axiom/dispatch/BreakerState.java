package com.axiom.dispatch;

import java.time.Instant;

/**
 * The circuit-breaker state for ONE connector. Per-connector by construction —
 * there is no global state anywhere in this engine, which is what makes
 * "connector A is dead" unable to stop connector B (ADR-003 rule 5).
 */
public record BreakerState(Phase phase, int consecutiveFailures, Instant openedAt,
                           Instant lastSuccessAt, Instant lastFailureAt, String lastError) {

    public enum Phase { CLOSED, OPEN, HALF_OPEN }

    public static BreakerState closed() {
        return new BreakerState(Phase.CLOSED, 0, null, null, null, null);
    }

    public boolean open() {
        return phase == Phase.OPEN;
    }
}

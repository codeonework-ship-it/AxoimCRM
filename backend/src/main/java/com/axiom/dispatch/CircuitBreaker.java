package com.axiom.dispatch;

import java.time.Duration;
import java.time.Instant;

/**
 * The breaker state machine, as a pure function of state and time.
 *
 * <pre>
 *   CLOSED --(N consecutive failures)--> OPEN
 *   OPEN --(cooldown elapsed)--> HALF_OPEN   (one probe delivery allowed)
 *   HALF_OPEN --(probe succeeds)--> CLOSED
 *   HALF_OPEN --(probe fails)--> OPEN        (cooldown restarts)
 * </pre>
 *
 * <p>Nothing here reads a clock or a database: {@code now} is a parameter, so
 * the transitions are testable without sleeping and without a running stack.
 * The persistence of the state is {@link ConnectorBreakerService}'s job.
 *
 * <p>Half-open admits exactly ONE probe. Admitting several would mean a
 * receiver that is still down takes a burst of traffic every cooldown, which is
 * the failure mode a breaker exists to prevent.
 */
public final class CircuitBreaker {

    private final int failureThreshold;
    private final Duration cooldown;

    public CircuitBreaker(int failureThreshold, Duration cooldown) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be at least 1");
        this.failureThreshold = failureThreshold;
        this.cooldown = cooldown;
    }

    public int failureThreshold() {
        return failureThreshold;
    }

    public Duration cooldown() {
        return cooldown;
    }

    /**
     * Move an OPEN breaker whose cooldown has elapsed to HALF_OPEN. Call this
     * before {@link #allows} so the probe is admitted on the same tick.
     */
    public BreakerState advance(BreakerState state, Instant now) {
        if (state.phase() != BreakerState.Phase.OPEN) return state;
        Instant openedAt = state.openedAt();
        if (openedAt == null || !now.isBefore(openedAt.plus(cooldown))) {
            return new BreakerState(BreakerState.Phase.HALF_OPEN, state.consecutiveFailures(), state.openedAt(),
                    state.lastSuccessAt(), state.lastFailureAt(), state.lastError());
        }
        return state;
    }

    /** True when a delivery may be attempted through this connector right now. */
    public boolean allows(BreakerState state, Instant now) {
        return switch (advance(state, now).phase()) {
            case CLOSED, HALF_OPEN -> true;
            case OPEN -> false;
        };
    }

    public BreakerState onSuccess(BreakerState state, Instant now) {
        return new BreakerState(BreakerState.Phase.CLOSED, 0, null, now, state.lastFailureAt(), null);
    }

    public BreakerState onFailure(BreakerState state, Instant now, String error) {
        BreakerState current = advance(state, now);
        // A failed half-open probe re-opens immediately and restarts the
        // cooldown; it does not need to re-earn the whole failure threshold.
        if (current.phase() == BreakerState.Phase.HALF_OPEN) {
            return new BreakerState(BreakerState.Phase.OPEN, current.consecutiveFailures() + 1, now,
                    current.lastSuccessAt(), now, error);
        }
        int failures = current.consecutiveFailures() + 1;
        if (failures >= failureThreshold) {
            Instant openedAt = current.phase() == BreakerState.Phase.OPEN && current.openedAt() != null
                    ? current.openedAt() : now;
            return new BreakerState(BreakerState.Phase.OPEN, failures, openedAt,
                    current.lastSuccessAt(), now, error);
        }
        return new BreakerState(BreakerState.Phase.CLOSED, failures, null,
                current.lastSuccessAt(), now, error);
    }

    /** True when this failure is the one that opened the breaker — the moment a human must be told. */
    public boolean opensOn(BreakerState before, BreakerState after) {
        return after.phase() == BreakerState.Phase.OPEN && before.phase() != BreakerState.Phase.OPEN;
    }
}

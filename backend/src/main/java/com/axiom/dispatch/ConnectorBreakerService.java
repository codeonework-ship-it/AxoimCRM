package com.axiom.dispatch;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-connector circuit breaker over a durable store.
 *
 * <p>Every method takes a connector id and touches only that connector's row.
 * There is no shared counter, no shared lock and no global state, so a
 * permanently failing connector cannot slow or stop any other connector's
 * deliveries — the property ADR-003 rule 5 requires and the one that is easiest
 * to lose by adding a convenient static field.
 */
@Service
public class ConnectorBreakerService {

    private final BreakerStore store;
    private final CircuitBreaker breaker;

    /**
     * With two constructors and none marked, Spring falls back to a no-arg
     * constructor that does not exist and the context fails to start. This is
     * the injection point; the other is for tests.
     */
    @Autowired
    public ConnectorBreakerService(BreakerStore store,
                                   @Value("${axiom.dispatch.breaker.failure-threshold:3}") int failureThreshold,
                                   @Value("${axiom.dispatch.breaker.cooldown-ms:30000}") long cooldownMs) {
        this(store, new CircuitBreaker(failureThreshold, Duration.ofMillis(cooldownMs)));
    }

    public ConnectorBreakerService(BreakerStore store, CircuitBreaker breaker) {
        this.store = store;
        this.breaker = breaker;
    }

    public CircuitBreaker policy() {
        return breaker;
    }

    public BreakerState state(UUID connectorId) {
        return store.load(connectorId);
    }

    /**
     * True when a delivery may be attempted. Persists the OPEN to HALF_OPEN
     * transition so the health API and the UI show "probing" rather than
     * continuing to claim the connector is hard down.
     */
    public boolean allows(UUID connectorId, Instant now) {
        BreakerState current = store.load(connectorId);
        BreakerState advanced = breaker.advance(current, now);
        if (advanced.phase() != current.phase()) {
            store.save(connectorId, advanced, false, false);
        }
        return breaker.allows(advanced, now);
    }

    public void recordSuccess(UUID connectorId, Instant now) {
        BreakerState next = breaker.onSuccess(store.load(connectorId), now);
        store.save(connectorId, next, true, false);
    }

    /** @return true when THIS failure opened the breaker — the moment to tell a human. */
    public boolean recordFailure(UUID connectorId, Instant now, String error) {
        BreakerState before = store.load(connectorId);
        BreakerState after = breaker.onFailure(before, now, error);
        store.save(connectorId, after, false, true);
        return breaker.opensOn(before, after);
    }
}

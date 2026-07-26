package com.axiom.dispatch;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The breaker contract from ADR-007's failure table and ADR-003 rule 5.
 *
 * <p>Time is a parameter throughout, so the cooldown and half-open behaviour are
 * tested at the transition rather than by sleeping and hoping.
 */
class CircuitBreakerTest {

    private static final Duration COOLDOWN = Duration.ofSeconds(30);
    private final Instant t0 = Instant.parse("2026-07-25T10:00:00Z");

    private ConnectorBreakerService service(InMemoryBreakerStore store, int threshold) {
        return new ConnectorBreakerService(store, new CircuitBreaker(threshold, COOLDOWN));
    }

    @Test void opensAfterConfiguredConsecutiveFailuresAndBlocksFurtherCalls() {
        InMemoryBreakerStore store = new InMemoryBreakerStore();
        ConnectorBreakerService breakers = service(store, 3);
        UUID connector = UUID.randomUUID();

        assertTrue(breakers.allows(connector, t0), "a fresh connector must be allowed");
        assertFalse(breakers.recordFailure(connector, t0, "boom"), "one failure must not open it");
        assertFalse(breakers.recordFailure(connector, t0.plusSeconds(1), "boom"));
        assertTrue(breakers.allows(connector, t0.plusSeconds(1)), "still closed below the threshold");

        assertTrue(breakers.recordFailure(connector, t0.plusSeconds(2), "boom"),
                "the third consecutive failure must open the breaker and report that it did");
        assertEquals(BreakerState.Phase.OPEN, breakers.state(connector).phase());
        assertFalse(breakers.allows(connector, t0.plusSeconds(3)), "an open breaker must block further calls");
    }

    @Test void halfOpensAfterCooldownAndClosesOnASuccessfulProbe() {
        InMemoryBreakerStore store = new InMemoryBreakerStore();
        ConnectorBreakerService breakers = service(store, 2);
        UUID connector = UUID.randomUUID();

        breakers.recordFailure(connector, t0, "down");
        breakers.recordFailure(connector, t0, "down");
        assertFalse(breakers.allows(connector, t0.plusSeconds(29)), "still open inside the cooldown");

        assertTrue(breakers.allows(connector, t0.plus(COOLDOWN)), "cooldown elapsed: one probe is admitted");
        assertEquals(BreakerState.Phase.HALF_OPEN, breakers.state(connector).phase());

        breakers.recordSuccess(connector, t0.plus(COOLDOWN));
        assertEquals(BreakerState.Phase.CLOSED, breakers.state(connector).phase());
        assertEquals(0, breakers.state(connector).consecutiveFailures());
        assertTrue(breakers.allows(connector, t0.plus(COOLDOWN)));
    }

    @Test void aFailedProbeReopensImmediatelyAndRestartsTheCooldown() {
        InMemoryBreakerStore store = new InMemoryBreakerStore();
        ConnectorBreakerService breakers = service(store, 2);
        UUID connector = UUID.randomUUID();

        breakers.recordFailure(connector, t0, "down");
        breakers.recordFailure(connector, t0, "down");
        assertTrue(breakers.allows(connector, t0.plus(COOLDOWN)));

        breakers.recordFailure(connector, t0.plus(COOLDOWN), "still down");
        assertEquals(BreakerState.Phase.OPEN, breakers.state(connector).phase());
        assertFalse(breakers.allows(connector, t0.plus(COOLDOWN).plusSeconds(1)),
                "the cooldown must restart from the failed probe, not from the original opening");
    }

    @Test void aSuccessResetsTheConsecutiveFailureCount() {
        InMemoryBreakerStore store = new InMemoryBreakerStore();
        ConnectorBreakerService breakers = service(store, 3);
        UUID connector = UUID.randomUUID();

        breakers.recordFailure(connector, t0, "blip");
        breakers.recordFailure(connector, t0, "blip");
        breakers.recordSuccess(connector, t0);
        assertEquals(0, breakers.state(connector).consecutiveFailures());

        assertFalse(breakers.recordFailure(connector, t0, "blip"), "the counter restarts, so this must not open it");
        assertTrue(breakers.allows(connector, t0));
    }

    /** ADR-003 rule 5: a permanently failing consumer must not block the stream. */
    @Test void anOpenBreakerOnConnectorADoesNotBlockConnectorB() {
        InMemoryBreakerStore store = new InMemoryBreakerStore();
        ConnectorBreakerService breakers = service(store, 2);
        UUID connectorA = UUID.randomUUID();
        UUID connectorB = UUID.randomUUID();

        breakers.recordFailure(connectorA, t0, "A is down");
        breakers.recordFailure(connectorA, t0, "A is down");

        assertFalse(breakers.allows(connectorA, t0), "A is open");
        assertTrue(breakers.allows(connectorB, t0), "B has its own state and must still deliver");
        assertEquals(BreakerState.Phase.CLOSED, breakers.state(connectorB).phase());
        assertEquals(0, breakers.state(connectorB).consecutiveFailures());
        assertEquals(0, store.failures(connectorB), "B must not accumulate A's failures");

        breakers.recordSuccess(connectorB, t0);
        assertEquals(1, store.successes(connectorB));
        assertFalse(breakers.allows(connectorA, t0), "B succeeding must not close A");
    }
}

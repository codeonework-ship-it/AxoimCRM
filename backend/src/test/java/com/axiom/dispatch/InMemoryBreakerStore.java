package com.axiom.dispatch;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real breaker state, held in a map instead of a table.
 *
 * <p>Deliberately not a Mockito mock: a breaker test driven by a mocked store
 * proves only that the mock returned what the test told it to. Here the
 * transitions are the real ones, and "connector A does not block connector B"
 * is a genuine property of the keyed state rather than an assumption.
 */
class InMemoryBreakerStore implements BreakerStore {

    private final Map<UUID, BreakerState> states = new ConcurrentHashMap<>();
    private final Map<UUID, int[]> counters = new ConcurrentHashMap<>();

    @Override
    public BreakerState load(UUID connectorId) {
        return states.getOrDefault(connectorId, BreakerState.closed());
    }

    @Override
    public void save(UUID connectorId, BreakerState state, boolean countedSuccess, boolean countedFailure) {
        states.put(connectorId, state);
        int[] tally = counters.computeIfAbsent(connectorId, k -> new int[2]);
        if (countedSuccess) tally[0]++;
        if (countedFailure) tally[1]++;
    }

    int successes(UUID connectorId) {
        return counters.getOrDefault(connectorId, new int[2])[0];
    }

    int failures(UUID connectorId) {
        return counters.getOrDefault(connectorId, new int[2])[1];
    }
}

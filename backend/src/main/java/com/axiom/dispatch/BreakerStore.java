package com.axiom.dispatch;

import java.util.UUID;

/**
 * Where breaker state is kept.
 *
 * <p>An interface rather than a direct JdbcTemplate call so the breaker's
 * behaviour can be tested against real state transitions instead of against a
 * mocked query returning whatever the test wanted to prove. A breaker whose
 * only test is "the mock said OPEN so we returned OPEN" has not been tested.
 */
public interface BreakerStore {

    BreakerState load(UUID connectorId);

    void save(UUID connectorId, BreakerState state, boolean countedSuccess, boolean countedFailure);
}

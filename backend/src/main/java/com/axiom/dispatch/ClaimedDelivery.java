package com.axiom.dispatch;

import com.axiom.integration.ConnectorTarget;
import com.axiom.integration.DispatchEnvelope;

/**
 * A delivery that has been leased for one attempt, together with the resolved
 * connector it is going to.
 *
 * <p>The resolution — including the decrypted credential — happens inside the
 * claiming transaction, but the external call happens OUTSIDE it. Holding a
 * database connection open across a five-second HTTP timeout is how a slow
 * receiver becomes a database outage; the whole point of the outbox is that a
 * slow endpoint cannot slow a record save.
 */
public record ClaimedDelivery(DispatchEnvelope envelope, ConnectorTarget target,
                              String connectorName, String credentialRef, int attemptsMade) {
}

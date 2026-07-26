package com.axiom.integration;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The outbound unit of work, expressed entirely in Axiom's vocabulary
 * (ADR-007 rule 1). No adapter's request shape, no vendor field name and no
 * vendor identifier appears here; translation to a vendor's model is the
 * adapter's job and nothing outside the adapter ever sees it.
 *
 * <p>{@link #idempotencyKey()} is {@code subscriptionId:eventId} — the same key
 * the delivery table is unique on. It is sent to the receiver on every attempt
 * so a retry after an ambiguous timeout is recognisable as a duplicate by the
 * far side too, not only by us (ADR-007 rule 4).
 */
public record DispatchEnvelope(
        UUID deliveryId,
        UUID tenantId,
        UUID subscriptionId,
        UUID connectorId,
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        Instant occurredAt,
        int attemptNo,
        Map<String, Object> payload) {

    public String idempotencyKey() {
        return subscriptionId + ":" + eventId;
    }
}

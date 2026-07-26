package com.axiom.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single place an envelope becomes bytes.
 *
 * <p>One serialisation, used for the signature, the request body and the
 * dead-letter record, so a replayed dead letter reproduces the same body as the
 * original attempt and a stored envelope is exactly what was (or would have
 * been) sent. Two serialisers would eventually disagree, and the disagreement
 * would only be discovered by a receiver rejecting a signature.
 */
@Component
public class EnvelopeCodec {

    private final ObjectMapper json;

    public EnvelopeCodec(ObjectMapper json) {
        this.json = json;
    }

    public Map<String, Object> asMap(DispatchEnvelope envelope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("specVersion", "axiom.dispatch.v1");
        body.put("deliveryId", str(envelope.deliveryId()));
        body.put("eventId", str(envelope.eventId()));
        body.put("idempotencyKey", envelope.idempotencyKey());
        body.put("eventType", envelope.eventType());
        body.put("aggregateType", envelope.aggregateType());
        body.put("aggregateId", str(envelope.aggregateId()));
        body.put("tenantId", str(envelope.tenantId()));
        body.put("occurredAt", envelope.occurredAt() == null ? null : envelope.occurredAt().toString());
        body.put("attempt", envelope.attemptNo());
        body.put("payload", envelope.payload() == null ? Map.of() : envelope.payload());
        return body;
    }

    public String toJson(DispatchEnvelope envelope) {
        try {
            return json.writeValueAsString(asMap(envelope));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Dispatch envelope could not be serialised", ex);
        }
    }

    /** Timestamp header value, separated so a test can pin it. */
    public static String timestamp(Instant at) {
        return String.valueOf(at.getEpochSecond());
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

package com.axiom.integration;

import java.util.Map;
import java.util.UUID;

/**
 * A resolved connector, ready to be dispatched through.
 *
 * <p>{@code secret} is the DECRYPTED value of the named credential the connector
 * references, resolved once per tick and never persisted, logged or returned
 * from an API. It is null when the connector references no credential, or when
 * the referenced name does not exist — the adapter decides whether that is a
 * permanent failure, because "unsigned webhook" and "unauthenticated ERP call"
 * are not the same severity.
 */
public record ConnectorTarget(UUID connectorId, String connectorType, String vendor,
                              String displayName, Map<String, Object> config, String secret) {

    public String configText(String key, String fallback) {
        Object value = config == null ? null : config.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    public int configInt(String key, int fallback) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** Never let a secret reach a log line or a toString() in a stack trace. */
    @Override
    public String toString() {
        return "ConnectorTarget[" + connectorId + " " + connectorType + "/" + vendor
                + " secret=" + (secret == null ? "none" : "present") + "]";
    }
}

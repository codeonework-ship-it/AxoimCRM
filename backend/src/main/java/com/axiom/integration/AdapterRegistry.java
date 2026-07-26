package com.axiom.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves a connector to the adapter that serves it.
 *
 * <p>Resolution order: exact {@code TYPE/VENDOR}, then the first adapter
 * registered for the type, then — for the transport-shaped connector types that
 * have no dedicated vendor adapter (ESIGN, MARKETING, ENRICHMENT) — the generic
 * webhook adapter, provided the connector actually carries a URL.
 *
 * <p>An unresolved connector produces a PERMANENT failure carrying the reason,
 * never a silent success and never a silent drop. That single behaviour is what
 * stops a typo'd vendor name from becoming an integration that appears healthy
 * and delivers nothing.
 */
@Component
public class AdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(AdapterRegistry.class);

    /** Types with no dedicated adapter that a signed webhook can legitimately serve. */
    private static final List<String> WEBHOOK_CAPABLE_TYPES = List.of("ESIGN", "MARKETING", "ENRICHMENT", "WEBHOOK");

    private final Map<String, OutboundAdapter> byTypeAndVendor = new LinkedHashMap<>();
    private final Map<String, OutboundAdapter> byType = new LinkedHashMap<>();
    private final WebhookAdapter webhookAdapter;

    public AdapterRegistry(List<OutboundAdapter> adapters, WebhookAdapter webhookAdapter) {
        this.webhookAdapter = webhookAdapter;
        for (OutboundAdapter adapter : adapters) {
            String type = upper(adapter.connectorType());
            byTypeAndVendor.put(key(type, adapter.vendor()), adapter);
            byType.putIfAbsent(type, adapter);
        }
        log.info("Integration adapters registered: {}", byTypeAndVendor.keySet());
    }

    /** @return the adapter, or null when nothing serves this connector. */
    public OutboundAdapter resolve(ConnectorTarget target) {
        String type = upper(target.connectorType());
        OutboundAdapter exact = byTypeAndVendor.get(key(type, target.vendor()));
        if (exact != null) return exact;
        OutboundAdapter typed = byType.get(type);
        if (typed != null) return typed;
        if (WEBHOOK_CAPABLE_TYPES.contains(type) && target.configText("url", null) != null) {
            return webhookAdapter;
        }
        return null;
    }

    /**
     * Dispatch through the resolved adapter, converting an unresolved connector
     * and any escaping adapter exception into a {@link DispatchResult}. The
     * engine never has to reason about an adapter throwing.
     */
    public DispatchResult dispatch(DispatchEnvelope envelope, ConnectorTarget target) {
        OutboundAdapter adapter = resolve(target);
        if (adapter == null) {
            return DispatchResult.permanent(null, null,
                    "No adapter is registered for connector type " + target.connectorType()
                            + " vendor " + target.vendor(), 0);
        }
        try {
            DispatchResult result = adapter.dispatch(envelope, target);
            return result == null
                    ? DispatchResult.retryable(null, null, "Adapter returned no result", 0)
                    : result;
        } catch (RuntimeException ex) {
            // An adapter that throws has thrown away its own knowledge of whether
            // the failure is retryable; assume retryable so it surfaces in the
            // dead-letter queue after the bounded attempts rather than vanishing.
            log.warn("Adapter {} threw for delivery {}", adapter.getClass().getSimpleName(), envelope.deliveryId(), ex);
            return DispatchResult.retryable(null, null,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(), 0);
        }
    }

    /** Catalogue for the connector-catalogue API (FR-INT-008). */
    public record AdapterDescriptor(String connectorType, String vendor, boolean live) {}

    public List<AdapterDescriptor> catalogue() {
        return byTypeAndVendor.values().stream()
                .map(a -> new AdapterDescriptor(upper(a.connectorType()), a.vendor(), a.live()))
                .toList();
    }

    private static String key(String type, String vendor) {
        return upper(type) + "/" + upper(vendor);
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

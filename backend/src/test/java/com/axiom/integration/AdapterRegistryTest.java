package com.axiom.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The anti-corruption layer: resolution, determinism and fail-closed behaviour. */
class AdapterRegistryTest {

    private final WebhookAdapter webhook = new WebhookAdapter(new EnvelopeCodec(new ObjectMapper()));
    private final LocalErpAdapter erp = new LocalErpAdapter();
    private final LocalCtrmAdapter ctrm = new LocalCtrmAdapter();
    private final AdapterRegistry registry =
            new AdapterRegistry(List.of(webhook, erp, ctrm), webhook);

    private DispatchEnvelope envelope(Map<String, Object> payload) {
        return new DispatchEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "commodity.enquiry.offered", "commodity_enquiry", UUID.randomUUID(),
                Instant.parse("2026-07-25T09:00:00Z"), 1, payload);
    }

    private ConnectorTarget target(String type, String vendor, Map<String, Object> config) {
        return new ConnectorTarget(UUID.randomUUID(), type, vendor, type + " connector", config, "secret");
    }

    @Test void resolvesByTypeAndVendor() {
        assertEquals(erp, registry.resolve(target("ERP", "AXIOM_LOCAL_ERP", Map.of())));
        assertEquals(ctrm, registry.resolve(target("CTRM", "AXIOM_LOCAL_CTRM", Map.of())));
        assertEquals(webhook, registry.resolve(target("WEBHOOK", "GENERIC_WEBHOOK", Map.of())));
    }

    @Test void aWebhookCapableTypeWithAUrlFallsBackToTheSignedWebhookAdapter() {
        assertEquals(webhook, registry.resolve(
                target("ESIGN", "SOME_ESIGN_VENDOR", Map.of("url", "https://esign.example.com/hook"))));
        assertNull(registry.resolve(target("ESIGN", "SOME_ESIGN_VENDOR", Map.of())),
                "with no url there is nothing to fall back to");
    }

    @Test void anUnresolvedConnectorFailsPermanentlyRatherThanSilently() {
        DispatchResult result = registry.dispatch(envelope(Map.of()),
                target("MARKETING", "UNKNOWN_VENDOR", Map.of()));

        assertEquals(DispatchResult.Outcome.PERMANENT_FAILURE, result.outcome());
        assertTrue(result.error().contains("No adapter is registered"));
    }

    @Test void anAdapterThatThrowsBecomesARetryableResultRatherThanAnEscapingException() {
        OutboundAdapter exploding = new OutboundAdapter() {
            @Override public String vendor() { return "EXPLODING"; }
            @Override public String connectorType() { return "MARKETING"; }
            @Override public DispatchResult dispatch(DispatchEnvelope envelope, ConnectorTarget target) {
                throw new IllegalStateException("vendor SDK blew up");
            }
        };
        AdapterRegistry withExploding = new AdapterRegistry(List.of(exploding, webhook), webhook);

        DispatchResult result = withExploding.dispatch(envelope(Map.of()), target("MARKETING", "EXPLODING", Map.of()));

        assertTrue(result.retryable());
        assertTrue(result.error().contains("vendor SDK blew up"));
    }

    @Test void theLocalErpStandInIsDeterministicAndCanSimulateFailureModes() {
        DispatchEnvelope envelope = envelope(Map.of("invoiceId", "INV-1"));
        ConnectorTarget ok = target("ERP", "AXIOM_LOCAL_ERP", Map.of());

        DispatchResult first = erp.dispatch(envelope, ok);
        DispatchResult second = erp.dispatch(envelope, ok);
        assertTrue(first.success());
        assertEquals(first.responseExcerpt(), second.responseExcerpt(),
                "the same event must always yield the same document reference");

        assertTrue(erp.dispatch(envelope, target("ERP", "AXIOM_LOCAL_ERP",
                Map.of("simulate", "FAIL_RETRYABLE"))).retryable());
        assertEquals(DispatchResult.Outcome.PERMANENT_FAILURE,
                erp.dispatch(envelope, target("ERP", "AXIOM_LOCAL_ERP",
                        Map.of("simulate", "FAIL_PERMANENT"))).outcome());
        assertFalse(erp.live(), "the catalogue must state plainly that this is a stand-in");
    }

    @Test void theCtrmHandOffFailsClosedWithNoOriginationReference() {
        DispatchEnvelope noReference = new DispatchEnvelope(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "commodity.enquiry.offered",
                "commodity_enquiry", null, Instant.parse("2026-07-25T09:00:00Z"), 1, Map.of());

        DispatchResult result = ctrm.dispatch(noReference, target("CTRM", "AXIOM_LOCAL_CTRM", Map.of()));

        assertEquals(DispatchResult.Outcome.PERMANENT_FAILURE, result.outcome());
        assertTrue(result.error().contains("origination reference"));
    }

    @Test void theCtrmHandOffReturnsAStableTradeReference() {
        DispatchEnvelope envelope = envelope(Map.of("originationId", "ORIG-4471"));
        ConnectorTarget connector = target("CTRM", "AXIOM_LOCAL_CTRM", Map.of());

        DispatchResult first = ctrm.dispatch(envelope, connector);
        DispatchResult second = ctrm.dispatch(envelope, connector);

        assertTrue(first.success());
        assertNotNull(first.responseExcerpt());
        assertTrue(first.responseExcerpt().contains("TRD-"));
        assertTrue(first.responseExcerpt().contains("ORIG-4471"));
        assertEquals(first.responseExcerpt(), second.responseExcerpt(),
                "a redelivered hand-off must not create a second trade");
    }

    @Test void anUnacknowledgedHandOffIsRetriedRatherThanReportedComplete() {
        DispatchResult result = ctrm.dispatch(envelope(Map.of("originationId", "ORIG-9")),
                target("CTRM", "AXIOM_LOCAL_CTRM", Map.of("simulate", "NO_ACK")));

        assertTrue(result.retryable(), "ADR-007: not handed off until acknowledged");
    }

    @Test void theCatalogueDistinguishesLiveAdaptersFromStandIns() {
        List<AdapterRegistry.AdapterDescriptor> catalogue = registry.catalogue();

        assertEquals(3, catalogue.size());
        assertTrue(catalogue.stream().anyMatch(d -> d.vendor().equals("GENERIC_WEBHOOK") && d.live()));
        assertTrue(catalogue.stream().anyMatch(d -> d.vendor().equals("AXIOM_LOCAL_ERP") && !d.live()));
        assertTrue(catalogue.stream().anyMatch(d -> d.vendor().equals("AXIOM_LOCAL_CTRM") && !d.live()));
    }
}

package com.axiom.integration;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Deterministic local stand-in for an ERP/billing system (FR-INT-008).
 *
 * <p><b>DEFERRED, stated plainly:</b> this is not an ERP integration. Live
 * interop with SAP, Oracle, NetSuite or Dynamics needs vendor accounts, sandbox
 * tenants and per-vendor field mapping, none of which are in scope. What is real
 * is the <em>seam</em>: this class implements the same {@link OutboundAdapter}
 * contract a live vendor adapter would, so adding one is a new class in this
 * package and a row in the connector registry — no change to the contract, the
 * engine, the queue, the breaker or the dead-letter path.
 *
 * <p>Deterministic on purpose: the same event always yields the same document
 * reference, so a redelivery that slipped past the idempotency guard would be
 * visibly the same posting rather than a second one.
 *
 * <p>{@code config.simulate} drives the failure modes so the retry, dead-letter
 * and breaker paths can be exercised without an external endpoint:
 * {@code OK} (default), {@code FAIL_RETRYABLE}, {@code FAIL_PERMANENT},
 * {@code SLOW}.
 */
@Component
public class LocalErpAdapter implements OutboundAdapter {

    public static final String VENDOR = "AXIOM_LOCAL_ERP";

    @Override public String vendor() { return VENDOR; }
    @Override public String connectorType() { return "ERP"; }
    @Override public boolean live() { return false; }

    @Override
    public DispatchResult dispatch(DispatchEnvelope envelope, ConnectorTarget target) {
        long started = System.nanoTime();
        String mode = target.configText("simulate", "OK").toUpperCase(Locale.ROOT);
        if (mode.equals("SLOW")) {
            try {
                Thread.sleep(Math.min(target.configInt("simulateDelayMs", 250), 2000));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return DispatchResult.retryable(null, null, "Interrupted", millis(started));
            }
        }
        if (mode.equals("FAIL_RETRYABLE")) {
            return DispatchResult.retryable(503, null, "Local ERP stand-in is simulating an unavailable ERP",
                    millis(started));
        }
        if (mode.equals("FAIL_PERMANENT")) {
            return DispatchResult.permanent(422, null, "Local ERP stand-in rejected the posting as unmappable",
                    millis(started));
        }
        String reference = "ERP-" + shortHash(envelope.idempotencyKey());
        return DispatchResult.success(200,
                "{\"accepted\":true,\"documentReference\":\"" + reference + "\",\"stub\":true}",
                millis(started));
    }

    static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                out.append(String.format("%02X", digest[i]));
            }
            return out.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", ex);
        }
    }

    private static long millis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}

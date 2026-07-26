package com.axiom.integration;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Deterministic local stand-in for a CTRM/ETRM system, implementing capability
 * <b>C4 — deal-agreed hand-off</b> from the ADR-007 contract.
 *
 * <p><b>DEFERRED, stated plainly:</b> Octane or any other trading system is a
 * separate adapter that needs a vendor sandbox. This class exists so the
 * hand-off path is real end to end — async through the outbox, idempotent,
 * acknowledged with a trade reference — while the vendor call itself is stood
 * in for. C1/C2/C3/C5 are inbound capabilities and are NOT implemented here;
 * this engine is the outbound half only.
 *
 * <p>Fail-closed behaviour that is not simulated and is not optional: an
 * envelope with no origination reference is a PERMANENT failure. Posting a
 * hand-off the trading system cannot tie back to an origination would create a
 * trade nobody can reconcile, and ADR-007 is explicit that the hand-off is not
 * reported as complete until acknowledged against a reference.
 */
@Component
public class LocalCtrmAdapter implements OutboundAdapter {

    public static final String VENDOR = "AXIOM_LOCAL_CTRM";

    @Override public String vendor() { return VENDOR; }
    @Override public String connectorType() { return "CTRM"; }
    @Override public boolean live() { return false; }

    @Override
    public DispatchResult dispatch(DispatchEnvelope envelope, ConnectorTarget target) {
        long started = System.nanoTime();
        String origination = originationReference(envelope);
        if (origination == null) {
            return DispatchResult.permanent(400, null,
                    "Hand-off carries no origination reference; the trading system could not reconcile it", 0);
        }
        String mode = target.configText("simulate", "OK").toUpperCase(Locale.ROOT);
        if (mode.equals("FAIL_RETRYABLE")) {
            return DispatchResult.retryable(504, null,
                    "Local CTRM stand-in is simulating an unreachable trading system", millis(started));
        }
        if (mode.equals("NO_ACK")) {
            // ADR-007: an unacknowledged hand-off is queued and retried, never
            // reported as handed off.
            return DispatchResult.retryable(202, "{\"acknowledged\":false}",
                    "Hand-off accepted but not acknowledged", millis(started));
        }
        // Idempotency key of the contract is (origination, version) — here the
        // envelope's own idempotency key carries exactly that pairing.
        String tradeReference = "TRD-" + LocalErpAdapter.shortHash(origination + "|" + envelope.idempotencyKey());
        return DispatchResult.success(200,
                "{\"acknowledged\":true,\"tradeReference\":\"" + tradeReference
                        + "\",\"originationRef\":\"" + origination + "\",\"stub\":true}",
                millis(started));
    }

    private static String originationReference(DispatchEnvelope envelope) {
        Map<String, Object> payload = envelope.payload();
        if (payload != null) {
            for (String key : new String[]{"originationId", "origination_id", "enquiryId", "termSheetId", "entityId"}) {
                Object value = payload.get(key);
                if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
            }
        }
        return envelope.aggregateId() == null ? null : envelope.aggregateId().toString();
    }

    private static long millis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}

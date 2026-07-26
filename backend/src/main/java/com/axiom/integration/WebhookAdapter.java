package com.axiom.integration;

import org.springframework.beans.factory.annotation.Autowired;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one adapter here that really talks to an external system: HTTP POST of a
 * signed JSON envelope (FR-INT-005).
 *
 * <p>Configuration ({@code connector.config}):
 * <ul>
 *   <li>{@code url} — required, {@code http}/{@code https} only</li>
 *   <li>{@code timeoutMs} — per-attempt timeout, default 5000, capped at 30000</li>
 *   <li>{@code headers} — optional static headers merged into the request</li>
 *   <li>{@code requireSignature} — default true; when true, a connector with no
 *       resolvable signing credential fails PERMANENTLY rather than sending an
 *       unsigned payload. Fail closed (ADR-007): a receiver that verifies
 *       signatures would reject it anyway, and one that does not should not be
 *       silently trained to accept unsigned traffic.</li>
 * </ul>
 *
 * <p>Classification: 2xx succeeds; 408/425/429 and 5xx are retryable; every
 * other 4xx is permanent, because retrying a request the receiver has judged
 * malformed only delays the dead letter that makes it visible.
 */
@Component
public class WebhookAdapter implements OutboundAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebhookAdapter.class);

    public static final String VENDOR = "GENERIC_WEBHOOK";
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int MAX_TIMEOUT_MS = 30000;
    private static final Set<Integer> RETRYABLE_4XX = Set.of(408, 425, 429);
    /** Headers a connector may not override — they carry the delivery's identity. */
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "x-axiom-signature", "x-axiom-event-id", "x-axiom-delivery-id",
            "x-axiom-idempotency-key", "x-axiom-timestamp", "content-type",
            "host", "content-length", "connection", "upgrade");

    private final EnvelopeCodec codec;
    private final HttpClient http;

@Autowired
    public WebhookAdapter(EnvelopeCodec codec) {
        this(codec, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /** Test seam: inject a client whose behaviour a test controls. */
    WebhookAdapter(EnvelopeCodec codec, HttpClient http) {
        this.codec = codec;
        this.http = http;
    }

    @Override public String vendor() { return VENDOR; }
    @Override public String connectorType() { return "WEBHOOK"; }

    @Override
    public DispatchResult dispatch(DispatchEnvelope envelope, ConnectorTarget target) {
        long started = System.nanoTime();
        String url = target.configText("url", null);
        if (url == null) {
            return DispatchResult.permanent(null, null, "Connector has no 'url' in its configuration", 0);
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            return DispatchResult.permanent(null, null, "Connector url is not a valid URI", 0);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return DispatchResult.permanent(null, null, "Connector url must be http or https", 0);
        }

        boolean requireSignature = !"false".equalsIgnoreCase(target.configText("requireSignature", "true"));
        String secret = target.secret();
        if (requireSignature && (secret == null || secret.isBlank())) {
            return DispatchResult.permanent(null, null,
                    "No signing credential resolved for credentialRef '" + target.configText("credentialRef", "")
                            + "'; refusing to send an unsigned webhook", 0);
        }

        // Signature is computed over these exact bytes and nothing else.
        byte[] rawBody = codec.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        int timeoutMs = Math.min(Math.max(target.configInt("timeoutMs", DEFAULT_TIMEOUT_MS), 250), MAX_TIMEOUT_MS);

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("X-Axiom-Event-Id", String.valueOf(envelope.eventId()))
                .header("X-Axiom-Delivery-Id", String.valueOf(envelope.deliveryId()))
                .header("X-Axiom-Idempotency-Key", envelope.idempotencyKey())
                .header("X-Axiom-Timestamp", EnvelopeCodec.timestamp(Instant.now()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(rawBody));
        if (secret != null && !secret.isBlank()) {
            request.header(WebhookSignature.HEADER, WebhookSignature.sign(secret, rawBody));
        }
        staticHeaders(target).forEach((name, value) -> {
            if (!RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                request.header(name, value);
            }
        });

        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            long ms = millis(started);
            int status = response.statusCode();
            String body = response.body();
            if (status >= 200 && status < 300) {
                return DispatchResult.success(status, body, ms);
            }
            if (status >= 500 || RETRYABLE_4XX.contains(status)) {
                return DispatchResult.retryable(status, body, "Endpoint returned HTTP " + status, ms);
            }
            return DispatchResult.permanent(status, body, "Endpoint rejected the delivery with HTTP " + status, ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return DispatchResult.retryable(null, null, "Dispatch thread interrupted", millis(started));
        } catch (IOException ex) {
            // Connection refused, DNS failure, TLS failure, read timeout — all
            // transient as far as we can honestly tell from here.
            log.debug("Webhook delivery {} failed at the transport layer", envelope.deliveryId(), ex);
            return DispatchResult.retryable(null, null,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(), millis(started));
        } catch (RuntimeException ex) {
            return DispatchResult.retryable(null, null,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(), millis(started));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> staticHeaders(ConnectorTarget target) {
        Object headers = target.config() == null ? null : target.config().get("headers");
        if (!(headers instanceof Map<?, ?> map)) return Map.of();
        return (Map<String, String>) map.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null)
                .collect(java.util.stream.Collectors.toMap(
                        e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue()), (a, b) -> b));
    }

    private static long millis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}

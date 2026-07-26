package com.axiom.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The webhook adapter against a real HTTP server on a loopback port.
 *
 * <p>A mocked HttpClient would prove the adapter calls a mock. What has to be
 * proven is that the signature the receiver reads off the wire verifies against
 * the bytes the receiver actually received — which needs real bytes on a real
 * socket.
 */
class WebhookAdapterTest {

    private static final String SECRET = "a-shared-webhook-signing-secret";

    private HttpServer server;
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedSignature = new AtomicReference<>();
    private final AtomicReference<String> receivedIdempotencyKey = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicInteger requestCount = new AtomicInteger();

    private WebhookAdapter adapter;

    @BeforeEach void startServer() throws IOException {
        adapter = new WebhookAdapter(new EnvelopeCodec(new ObjectMapper()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            requestCount.incrementAndGet();
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            receivedSignature.set(exchange.getRequestHeaders().getFirst(WebhookSignature.HEADER));
            receivedIdempotencyKey.set(exchange.getRequestHeaders().getFirst("X-Axiom-Idempotency-Key"));
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus.get(), response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
    }

    @AfterEach void stopServer() {
        if (server != null) server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private DispatchEnvelope envelope() {
        return new DispatchEnvelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "opportunity.stage-changed", "opportunity", UUID.randomUUID(),
                Instant.parse("2026-07-25T09:00:00Z"), 1,
                Map.of("stage", "CLOSED_WON", "amount", 125000));
    }

    private ConnectorTarget target(String secret) {
        return new ConnectorTarget(UUID.randomUUID(), "WEBHOOK", WebhookAdapter.VENDOR, "Ops webhook",
                Map.of("url", url(), "timeoutMs", 4000), secret);
    }

    @Test void signsTheRawBodyWithHmacSha256AndTheReceiverCanVerifyIt() {
        DispatchEnvelope envelope = envelope();

        DispatchResult result = adapter.dispatch(envelope, target(SECRET));

        assertTrue(result.success(), "a 200 must be reported as success");
        assertEquals(200, result.httpStatus());
        byte[] raw = receivedBody.get();
        assertNotNull(raw);

        // The receiver has ONLY the bytes and the shared secret — no object, no
        // re-serialisation. This is exactly the check a real receiver performs.
        assertTrue(WebhookSignature.verify(SECRET, raw, receivedSignature.get()),
                "the signature must verify against the raw body the receiver read");
        assertFalse(WebhookSignature.verify("a-different-secret", raw, receivedSignature.get()),
                "a wrong key must not verify");
        assertFalse(WebhookSignature.verify(SECRET, "{\"tampered\":true}".getBytes(StandardCharsets.UTF_8),
                receivedSignature.get()), "a tampered body must not verify");

        assertTrue(receivedSignature.get().startsWith("sha256="));
        assertEquals(envelope.idempotencyKey(), receivedIdempotencyKey.get(),
                "the receiver must be able to deduplicate a redelivery for itself");
        String body = new String(raw, StandardCharsets.UTF_8);
        assertTrue(body.contains(envelope.eventId().toString()));
        assertTrue(body.contains("CLOSED_WON"));
    }

    @Test void serverErrorsAreRetryableAndClientErrorsArePermanent() {
        responseStatus.set(503);
        assertTrue(adapter.dispatch(envelope(), target(SECRET)).retryable(), "5xx is transient");

        responseStatus.set(429);
        assertTrue(adapter.dispatch(envelope(), target(SECRET)).retryable(), "429 asks us to slow down, not stop");

        responseStatus.set(400);
        DispatchResult permanent = adapter.dispatch(envelope(), target(SECRET));
        assertEquals(DispatchResult.Outcome.PERMANENT_FAILURE, permanent.outcome(),
                "a 4xx rejection will not be fixed by sending it again");
        assertEquals(400, permanent.httpStatus());
    }

    @Test void anUnreachableEndpointIsRetryableRatherThanLost() {
        ConnectorTarget dead = new ConnectorTarget(UUID.randomUUID(), "WEBHOOK", WebhookAdapter.VENDOR,
                "Dead endpoint", Map.of("url", "http://127.0.0.1:1/nowhere", "timeoutMs", 1000), SECRET);

        DispatchResult result = adapter.dispatch(envelope(), dead);

        assertTrue(result.retryable());
        assertNotNull(result.error());
        assertEquals(0, requestCount.get());
    }

    @Test void refusesToSendUnsignedWhenNoCredentialResolves() {
        DispatchResult result = adapter.dispatch(envelope(), target(null));

        assertEquals(DispatchResult.Outcome.PERMANENT_FAILURE, result.outcome());
        assertTrue(result.error().contains("unsigned"));
        assertEquals(0, requestCount.get(), "nothing may be put on the wire without a signature");
    }

    @Test void rejectsAConnectorWithNoUsableUrl() {
        ConnectorTarget noUrl = new ConnectorTarget(UUID.randomUUID(), "WEBHOOK", WebhookAdapter.VENDOR,
                "Misconfigured", Map.of(), SECRET);
        assertEquals(DispatchResult.Outcome.PERMANENT_FAILURE, adapter.dispatch(envelope(), noUrl).outcome());

        ConnectorTarget badScheme = new ConnectorTarget(UUID.randomUUID(), "WEBHOOK", WebhookAdapter.VENDOR,
                "File scheme", Map.of("url", "file:///etc/passwd"), SECRET);
        assertEquals(DispatchResult.Outcome.PERMANENT_FAILURE, adapter.dispatch(envelope(), badScheme).outcome());
    }
}

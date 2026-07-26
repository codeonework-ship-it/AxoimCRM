package com.axiom.dispatch;

import com.axiom.audit.AuditService;
import com.axiom.integration.AdapterRegistry;
import com.axiom.integration.ConnectorTarget;
import com.axiom.integration.DispatchEnvelope;
import com.axiom.integration.DispatchResult;
import com.axiom.integration.EnvelopeCodec;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The delivery guarantees, exercised against a JdbcTemplate whose answers model
 * the constraints the real schema enforces.
 *
 * <p>In particular the delivery insert is answered the way PostgreSQL answers
 * {@code on conflict do nothing}: one row for the first insert of a
 * {@code (subscription, event)} pair, zero for every repeat. That is what makes
 * the redelivery test a test of the design rather than of the mock.
 */
class DispatchServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private ObjectMapper json;
    private ConnectorService connectors;
    private AdapterRegistry adapters;
    private IntegrationNotifier notifier;
    private AuditService audit;
    private InMemoryBreakerStore breakerStore;
    private ConnectorBreakerService breakers;
    private DispatchService service;

    /** Rows the fake "insert ... on conflict do nothing" has accepted. */
    private final Set<String> insertedDeliveryKeys = new HashSet<>();
    private final List<String> executedSql = new ArrayList<>();
    private final List<Object[]> deadLetterInserts = new ArrayList<>();

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        json = new ObjectMapper();
        connectors = mock(ConnectorService.class);
        adapters = mock(AdapterRegistry.class);
        notifier = mock(IntegrationNotifier.class);
        audit = mock(AuditService.class);
        breakerStore = new InMemoryBreakerStore();
        breakers = new ConnectorBreakerService(breakerStore, new CircuitBreaker(2, Duration.ofSeconds(30)));
        service = newService(new RetryPolicy(3, 1000, 2.0, 60_000, 0.0));

        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(), "TENANT_ADMIN",
                "Ops Admin", "ops@example.com"));
    }

    private DispatchService newService(RetryPolicy retryPolicy) {
        return new DispatchService(jdbc, json, connectors, adapters, new EnvelopeCodec(json), breakers,
                notifier, audit, retryPolicy, 100, 25, 5000, 60_000, 5);
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    /* ------------------------------------------------------------------ */
    /* Idempotency                                                         */
    /* ------------------------------------------------------------------ */

    /** Map.of rejects null values, and a subscription with no filter is the common case. */
    private Map<String, Object> subscriptionRow(String pattern) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", subscriptionId);
        values.put("connector_id", connectorId);
        values.put("event_type_pattern", pattern);
        values.put("filter_expression", null);
        return values;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubIngest() {
        when(jdbc.queryForList(anyString(), eq(Timestamp.class), any(Object[].class)))
                .thenReturn(List.of(Timestamp.from(Instant.parse("2026-07-25T08:00:00Z"))));

        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper mapper = invocation.getArgument(1);
            if (sql.contains("event_subscription")) {
                return List.of(mapper.mapRow(FakeRows.row(subscriptionRow("opportunity.*")), 0));
            }
            if (sql.contains("outbox_event")) {
                return List.of(mapper.mapRow(FakeRows.row(Map.of(
                        "id", eventId,
                        "aggregate_type", "opportunity",
                        "aggregate_id", UUID.randomUUID(),
                        "event_type", "opportunity.stage-changed",
                        "payload", "{\"stage\":\"CLOSED_WON\"}",
                        "created_at", Instant.parse("2026-07-25T09:00:00Z"))), 0));
            }
            return List.of();
        });

        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            executedSql.add(sql);
            if (sql.contains("insert into dispatch.dispatch_delivery")) {
                Object[] args = invocation.getArguments();
                // args: sql, tenantId, subscriptionId, connectorId, eventId, ...
                String key = args[2] + ":" + args[4];
                return insertedDeliveryKeys.add(key) ? 1 : 0;
            }
            return 1;
        });
    }

    @Test void aRedeliveredEventProducesExactlyOneDispatch() {
        stubIngest();

        int first = service.ingest();
        int second = service.ingest();
        int third = service.ingest();

        assertEquals(1, first, "the first sighting queues one delivery");
        assertEquals(0, second, "the at-least-once backbone redelivering must queue nothing");
        assertEquals(0, third);
        assertEquals(1, insertedDeliveryKeys.size(), "exactly one delivery exists for this (subscription, event)");

        String insertSql = executedSql.stream()
                .filter(sql -> sql.contains("insert into dispatch.dispatch_delivery"))
                .findFirst().orElseThrow();
        assertTrue(insertSql.contains("on conflict (tenant_id, subscription_id, event_id) do nothing"),
                "the guarantee must be a database constraint, not an application-side check that races");
    }

    @Test void anEventNoSubscriptionMatchesIsNotQueued() {
        stubIngest();
        // Re-stub the subscription to a pattern the event does not match.
        @SuppressWarnings({"unchecked", "rawtypes"})
        var ignored = when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper mapper = invocation.getArgument(1);
                    if (sql.contains("event_subscription")) {
                        return List.of(mapper.mapRow(FakeRows.row(subscriptionRow("lead.*")), 0));
                    }
                    if (sql.contains("outbox_event")) {
                        return List.of(mapper.mapRow(FakeRows.row(Map.of(
                                "id", eventId, "aggregate_type", "opportunity",
                                "aggregate_id", UUID.randomUUID(), "event_type", "opportunity.stage-changed",
                                "payload", "{}", "created_at", Instant.parse("2026-07-25T09:00:00Z"))), 0));
                    }
                    return List.of();
                });

        assertEquals(0, service.ingest());
        assertTrue(insertedDeliveryKeys.isEmpty());
    }

    /* ------------------------------------------------------------------ */
    /* Retry, dead-letter                                                  */
    /* ------------------------------------------------------------------ */

    private void stubOutcomeRecording() {
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            executedSql.add(sql);
            if (sql.contains("insert into dispatch.dispatch_dead_letter")) {
                deadLetterInserts.add(invocation.getArguments());
            }
            return 1;
        });
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    }

    private ClaimedDelivery claimed(int attemptsMade) {
        DispatchEnvelope envelope = new DispatchEnvelope(UUID.randomUUID(), tenantId, subscriptionId,
                connectorId, eventId, "opportunity.stage-changed", "opportunity", UUID.randomUUID(),
                Instant.parse("2026-07-25T09:00:00Z"), attemptsMade + 1,
                new LinkedHashMap<>(Map.of("stage", "CLOSED_WON")));
        ConnectorTarget target = new ConnectorTarget(connectorId, "WEBHOOK", "GENERIC_WEBHOOK",
                "Ops webhook", Map.of("url", "http://receiver.invalid/hook"), "secret-value");
        return new ClaimedDelivery(envelope, target, "Ops webhook", "ops-webhook-secret", attemptsMade);
    }

    @Test void aRetryableFailureIsRescheduledRatherThanDeadLettered() {
        stubOutcomeRecording();
        ClaimedDelivery delivery = claimed(0);

        service.recordOutcome(delivery, DispatchResult.retryable(503, null, "Endpoint returned HTTP 503", 12));

        assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("status = 'PENDING'")),
                "attempt 1 of 3 must be rescheduled");
        assertTrue(deadLetterInserts.isEmpty(), "nothing may be dead-lettered while attempts remain");
    }

    @Test void exhaustedRetriesLandInTheDeadLetterQueueWithTheEnvelopeIntact() {
        stubOutcomeRecording();
        ClaimedDelivery delivery = claimed(2); // third and final attempt

        service.recordOutcome(delivery, DispatchResult.retryable(503, null, "Endpoint returned HTTP 503", 12));

        assertEquals(1, deadLetterInserts.size(), "the delivery must be dead-lettered, never dropped");
        Object[] args = deadLetterInserts.get(0);
        String envelopeJson = String.valueOf(args[7]);
        assertTrue(envelopeJson.contains(eventId.toString()), "the stored envelope must carry the event id");
        assertTrue(envelopeJson.contains("CLOSED_WON"), "the stored envelope must carry the original payload");
        assertTrue(envelopeJson.contains(delivery.envelope().idempotencyKey()),
                "the stored envelope must carry the idempotency key so replay stays idempotent");
        assertTrue(String.valueOf(args[8]).contains("Exhausted 3 attempts"));
        assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("status = 'DEAD_LETTERED'")));
        verify(audit).record(eq("INTEGRATION_DEAD_LETTERED"), eq("DISPATCH_DELIVERY"), any(), anyString(), any());
    }

    @Test void aPermanentFailureIsDeadLetteredImmediatelyWithoutBurningAttempts() {
        stubOutcomeRecording();

        service.recordOutcome(claimed(0), DispatchResult.permanent(400, "bad request", "Endpoint rejected it", 8));

        assertEquals(1, deadLetterInserts.size());
        assertTrue(String.valueOf(deadLetterInserts.get(0)[8]).startsWith("Permanent failure"));
    }

    @Test void aSuccessMarksTheDeliverySucceededAndClosesTheBreaker() {
        stubOutcomeRecording();
        breakers.recordFailure(connectorId, Instant.now(), "earlier blip");

        service.recordOutcome(claimed(0), DispatchResult.success(200, "{\"ok\":true}", 30));

        assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("status = 'SUCCEEDED'")));
        assertEquals(BreakerState.Phase.CLOSED, breakers.state(connectorId).phase());
        assertEquals(0, breakers.state(connectorId).consecutiveFailures());
        assertTrue(deadLetterInserts.isEmpty());
    }

    /* ------------------------------------------------------------------ */
    /* Breaker opening must reach a human (FR-INT-009)                     */
    /* ------------------------------------------------------------------ */

    @Test void openingTheBreakerRaisesANotification() {
        stubOutcomeRecording();

        service.recordOutcome(claimed(0), DispatchResult.retryable(503, null, "Endpoint returned HTTP 503", 12));
        verify(notifier, never()).breakerOpened(any(), anyString(), org.mockito.ArgumentMatchers.anyInt(), any());

        service.recordOutcome(claimed(1), DispatchResult.retryable(503, null, "Endpoint returned HTTP 503", 12));

        assertEquals(BreakerState.Phase.OPEN, breakers.state(connectorId).phase());
        verify(notifier, times(1)).breakerOpened(eq(connectorId), eq("Ops webhook"),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test void theDeadLetterThresholdRaisesANotificationOnlyOnceItIsCrossed() {
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            executedSql.add(invocation.getArgument(0));
            return 1;
        });
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(9L);

        service.recordOutcome(claimed(0), DispatchResult.permanent(400, null, "rejected", 5));

        verify(notifier).deadLetterThresholdCrossed(eq(connectorId), eq("Ops webhook"), eq(9L), eq(5));
    }

    /* ------------------------------------------------------------------ */
    /* Replay                                                              */
    /* ------------------------------------------------------------------ */

    @Test void replayFromTheDeadLetterQueueIsItselfIdempotent() {
        UUID deadLetterId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        AtomicInteger requeueCalls = new AtomicInteger();

        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class)))
                .thenReturn(List.of(deliveryId));
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            executedSql.add(sql);
            if (sql.contains("status = 'DEAD_LETTERED'")) {
                // Models the WHERE clause: only the first replay finds the row
                // in the dead-lettered state.
                return requeueCalls.getAndIncrement() == 0 ? 1 : 0;
            }
            return 1;
        });

        List<DispatchService.ReplayOutcome> first = service.replay(List.of(deadLetterId));
        List<DispatchService.ReplayOutcome> second = service.replay(List.of(deadLetterId));

        assertTrue(first.get(0).requeued(), "the first replay requeues the existing delivery");
        assertFalse(second.get(0).requeued(), "a second replay must not create a second dispatch");
        assertTrue(second.get(0).detail().contains("Already requeued"));
        assertEquals(deliveryId, first.get(0).deliveryId());
        assertEquals(deliveryId, second.get(0).deliveryId(),
                "replay reuses the same delivery row, so the (subscription, event) guard still holds");

        assertTrue(executedSql.stream().noneMatch(sql -> sql.contains("insert into dispatch.dispatch_delivery")),
                "replay must never insert a second delivery for the same event");
        verify(audit, times(1)).record(eq("INTEGRATION_DEAD_LETTER_REPLAYED"), anyString(), any(),
                anyString(), any());
    }

    @Test void replayIsRefusedForARoleThatMayNotAdministerMasters() {
        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(), "AUDITOR",
                "Read Only", "auditor@example.com"));

        org.junit.jupiter.api.Assertions.assertThrows(com.axiom.common.ForbiddenException.class,
                () -> service.replay(List.of(UUID.randomUUID())));
    }

    /* ------------------------------------------------------------------ */
    /* Claim-time breaker isolation                                        */
    /* ------------------------------------------------------------------ */

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void anOpenBreakerHoldsOneConnectorWhileAnotherKeepsDelivering() {
        UUID connectorA = UUID.randomUUID();
        UUID connectorB = UUID.randomUUID();
        Instant now = Instant.now();
        breakers.recordFailure(connectorA, now, "A is down");
        breakers.recordFailure(connectorA, now, "A is down");
        assertEquals(BreakerState.Phase.OPEN, breakers.state(connectorA).phase());

        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(
                    mapper.mapRow(dueRow(connectorA, "Connector A"), 0),
                    mapper.mapRow(dueRow(connectorB, "Connector B"), 1));
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            executedSql.add(invocation.getArgument(0));
            return 1;
        });
        when(connectors.resolveTarget(any())).thenAnswer(invocation -> new ConnectorTarget(
                invocation.getArgument(0), "WEBHOOK", "GENERIC_WEBHOOK", "Connector",
                Map.of("url", "http://receiver.invalid/hook"), "secret"));

        List<ClaimedDelivery> claimed = service.claimDue();

        assertEquals(1, claimed.size(), "only the healthy connector's delivery may be claimed");
        assertEquals(connectorB, claimed.get(0).envelope().connectorId());
        assertTrue(executedSql.stream().anyMatch(sql -> sql.contains("dispatch_attempt")),
                "the held delivery must leave a visible BLOCKED_BY_BREAKER trace, not disappear");
    }

    private java.sql.ResultSet dueRow(UUID connector, String name) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", UUID.randomUUID());
        values.put("subscription_id", subscriptionId);
        values.put("connector_id", connector);
        values.put("event_id", UUID.randomUUID());
        values.put("event_type", "opportunity.stage-changed");
        values.put("aggregate_type", "opportunity");
        values.put("aggregate_id", UUID.randomUUID());
        values.put("payload", "{\"stage\":\"CLOSED_WON\"}");
        values.put("event_occurred_at", Instant.parse("2026-07-25T09:00:00Z"));
        values.put("attempt_count", 0);
        values.put("display_name", name);
        values.put("credential_ref", "ops-webhook-secret");
        return FakeRows.row(values);
    }

    @Test void retryPolicyIsExposedForOperationalReporting() {
        assertNotNull(service.retryPolicy());
        assertEquals(3, service.retryPolicy().maxAttempts());
    }
}

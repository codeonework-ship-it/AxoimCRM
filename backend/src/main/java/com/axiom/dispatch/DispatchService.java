package com.axiom.dispatch;

import org.springframework.beans.factory.annotation.Autowired;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.NotFoundException;
import com.axiom.integration.AdapterRegistry;
import com.axiom.integration.ConnectorTarget;
import com.axiom.integration.DispatchEnvelope;
import com.axiom.integration.DispatchResult;
import com.axiom.integration.EnvelopeCodec;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The integration dispatch engine.
 *
 * <h2>Why it tails the outbox table rather than subscribing to Kafka</h2>
 *
 * ADR-003 rule 7 is explicit that <b>the outbox is the source of truth, not the
 * broker</b>, and this engine takes that literally. Three concrete reasons, in
 * order of weight:
 *
 * <ol>
 *   <li><b>The broker message does not carry what routing needs.</b>
 *       {@code OutboxRelay} publishes {@code row.payload()} as the message value
 *       with a key of {@code tenantId:aggregateId}. The <em>event type</em> and
 *       the <em>event id</em> are not in the message at all. Subscription
 *       matching needs the type, and the idempotency key needs the id. Getting
 *       them from Kafka would mean changing the relay, which is not ours to
 *       change.</li>
 *   <li><b>Idempotency needs a stable identity.</b> {@code outbox_event.id} is
 *       that identity and it survives broker retention, topic recreation and a
 *       consumer-group reset. A Kafka offset does not.</li>
 *   <li><b>Replay.</b> ADR-003 promises recovery by replay from the outbox
 *       within its retention window. An engine already reading the outbox can do
 *       that with a cursor rewind; a broker consumer depends on the broker still
 *       holding the messages.</li>
 * </ol>
 *
 * <p><b>The cost, stated honestly:</b> this is a second poller against the same
 * table, adding read load the broker was supposed to absorb, and dispatch
 * latency is bounded by the poll interval rather than by broker delivery. If the
 * relay is later changed to publish a structured envelope carrying event id and
 * type, moving this engine onto a Kafka consumer group is a change to
 * {@link #ingest} alone — everything downstream of the delivery table is
 * transport-agnostic.
 *
 * <h2>Delivery semantics</h2>
 * <ul>
 *   <li>Fan-out inserts one {@code dispatch_delivery} per (subscription, event)
 *       with {@code on conflict do nothing}. The unique constraint means a
 *       redelivered event produces exactly one dispatch.</li>
 *   <li>A claim leases the row ({@code IN_FLIGHT} + a lease deadline) so a
 *       process that dies mid-attempt retries rather than losing the delivery.</li>
 *   <li>Failures retry with bounded exponential backoff and jitter, then
 *       dead-letter with the full envelope.</li>
 *   <li>The breaker is consulted per connector at claim time, so an open
 *       breaker on one connector never delays another's deliveries.</li>
 * </ul>
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ConnectorService connectors;
    private final AdapterRegistry adapters;
    private final EnvelopeCodec codec;
    private final ConnectorBreakerService breakers;
    private final IntegrationNotifier notifier;
    private final AuditService audit;
    private final RetryPolicy retryPolicy;
    private final int ingestBatch;
    private final int drainBatch;
    private final long overlapMs;
    private final long leaseMs;
    private final int deadLetterAlertThreshold;

    @SuppressWarnings("checkstyle:ParameterNumber")
@Autowired
    public DispatchService(JdbcTemplate jdbc, ObjectMapper json, ConnectorService connectors,
                           AdapterRegistry adapters, EnvelopeCodec codec, ConnectorBreakerService breakers,
                           IntegrationNotifier notifier, AuditService audit,
                           @Value("${axiom.dispatch.ingest-batch:200}") int ingestBatch,
                           @Value("${axiom.dispatch.drain-batch:25}") int drainBatch,
                           @Value("${axiom.dispatch.overlap-ms:5000}") long overlapMs,
                           @Value("${axiom.dispatch.lease-ms:60000}") long leaseMs,
                           @Value("${axiom.dispatch.retry.max-attempts:5}") int maxAttempts,
                           @Value("${axiom.dispatch.retry.base-delay-ms:2000}") long baseDelayMs,
                           @Value("${axiom.dispatch.retry.multiplier:3.0}") double multiplier,
                           @Value("${axiom.dispatch.retry.max-delay-ms:300000}") long maxDelayMs,
                           @Value("${axiom.dispatch.retry.jitter:0.2}") double jitter,
                           @Value("${axiom.dispatch.dead-letter.alert-threshold:5}") int deadLetterAlertThreshold) {
        this(jdbc, json, connectors, adapters, codec, breakers, notifier, audit,
                new RetryPolicy(maxAttempts, baseDelayMs, multiplier, maxDelayMs, jitter),
                ingestBatch, drainBatch, overlapMs, leaseMs, deadLetterAlertThreshold);
    }

    public DispatchService(JdbcTemplate jdbc, ObjectMapper json, ConnectorService connectors,
                           AdapterRegistry adapters, EnvelopeCodec codec, ConnectorBreakerService breakers,
                           IntegrationNotifier notifier, AuditService audit, RetryPolicy retryPolicy,
                           int ingestBatch, int drainBatch, long overlapMs, long leaseMs,
                           int deadLetterAlertThreshold) {
        this.jdbc = jdbc;
        this.json = json;
        this.connectors = connectors;
        this.adapters = adapters;
        this.codec = codec;
        this.breakers = breakers;
        this.notifier = notifier;
        this.audit = audit;
        this.retryPolicy = retryPolicy;
        this.ingestBatch = ingestBatch;
        this.drainBatch = drainBatch;
        this.overlapMs = overlapMs;
        this.leaseMs = leaseMs;
        this.deadLetterAlertThreshold = deadLetterAlertThreshold;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    /* ------------------------------------------------------------------ */
    /* Phase 1 — ingest: outbox events become deliveries                    */
    /* ------------------------------------------------------------------ */

    private record SubscriptionMatch(UUID subscriptionId, UUID connectorId, String pattern, String filter) {}

    private record OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType,
                               String payloadJson, Instant createdAt) {}

    /**
     * @return the number of deliveries newly queued. Redeliveries of an event
     *         already fanned out add nothing, which is the point.
     */
    @Transactional
    public int ingest() {
        UUID tenantId = TenantContext.get().tenantId();
        Instant cursor = cursor(tenantId);
        if (cursor == null) {
            // First tick for this tenant: start from now. Fanning the whole
            // outbox history at a freshly configured endpoint would be a
            // surprise nobody asked for, and possibly a very large one.
            jdbc.update("""
                    insert into dispatch.ingest_cursor (tenant_id, last_event_at)
                    values (?, now()) on conflict (tenant_id) do nothing
                    """, tenantId);
            return 0;
        }

        List<SubscriptionMatch> subscriptions = jdbc.query("""
                select s.id, s.connector_id, s.event_type_pattern, s.filter_expression
                from dispatch.event_subscription s
                join dispatch.connector k on k.tenant_id = s.tenant_id and k.id = s.connector_id
                where s.tenant_id = ? and s.active = true and k.enabled = true
                """, (rs, i) -> new SubscriptionMatch(rs.getObject("id", UUID.class),
                        rs.getObject("connector_id", UUID.class), rs.getString("event_type_pattern"),
                        rs.getString("filter_expression")), tenantId);

        // Re-read a small overlap window: a transaction can commit an outbox row
        // whose created_at is earlier than one already read. Duplicates the
        // overlap produces are absorbed by the unique (subscription, event)
        // constraint, so the overlap costs a few redundant inserts and buys
        // "no silent gaps" (FR-INT-006).
        Instant from = cursor.minusMillis(overlapMs);
        List<OutboxEvent> events = jdbc.query("""
                select id, aggregate_type, aggregate_id, event_type, payload::text as payload, created_at
                from integration.outbox_event
                where tenant_id = ? and created_at > ?
                order by created_at, id
                limit ?
                """, (rs, i) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"),
                        rs.getString("payload"), rs.getTimestamp("created_at").toInstant()),
                tenantId, Timestamp.from(from), ingestBatch);
        if (events.isEmpty()) {
            return 0;
        }

        int queued = 0;
        Instant highWater = cursor;
        for (OutboxEvent event : events) {
            if (event.createdAt().isAfter(highWater)) highWater = event.createdAt();
            if (subscriptions.isEmpty()) continue;
            Map<String, Object> payload = readMap(event.payloadJson());
            for (SubscriptionMatch subscription : subscriptions) {
                if (!SubscriptionMatcher.matches(subscription.pattern(), subscription.filter(),
                        event.eventType(), payload)) {
                    continue;
                }
                queued += jdbc.update("""
                        insert into dispatch.dispatch_delivery
                          (tenant_id, subscription_id, connector_id, event_id, event_type,
                           aggregate_type, aggregate_id, payload, event_occurred_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        on conflict (tenant_id, subscription_id, event_id) do nothing
                        """, tenantId, subscription.subscriptionId(), subscription.connectorId(), event.id(),
                        event.eventType(), event.aggregateType(), event.aggregateId(),
                        event.payloadJson(), Timestamp.from(event.createdAt()));
            }
        }

        jdbc.update("""
                insert into dispatch.ingest_cursor (tenant_id, last_event_at, events_seen, updated_at)
                values (?, ?, ?, now())
                on conflict (tenant_id) do update set
                  last_event_at = greatest(dispatch.ingest_cursor.last_event_at, excluded.last_event_at),
                  events_seen = dispatch.ingest_cursor.events_seen + excluded.events_seen,
                  updated_at = now()
                """, tenantId, Timestamp.from(highWater), (long) events.size());
        return queued;
    }

    private Instant cursor(UUID tenantId) {
        List<Timestamp> rows = jdbc.queryForList(
                "select last_event_at from dispatch.ingest_cursor where tenant_id = ?",
                Timestamp.class, tenantId);
        return rows.isEmpty() ? null : rows.get(0).toInstant();
    }

    /* ------------------------------------------------------------------ */
    /* Phase 2 — claim: lease due deliveries, consult the breaker           */
    /* ------------------------------------------------------------------ */

    private record DueRow(UUID id, UUID subscriptionId, UUID connectorId, UUID eventId, String eventType,
                          String aggregateType, UUID aggregateId, String payloadJson, Instant occurredAt,
                          int attemptCount, String connectorName, String credentialRef) {}

    @Transactional
    public List<ClaimedDelivery> claimDue() {
        UUID tenantId = TenantContext.get().tenantId();
        Instant now = Instant.now();
        List<DueRow> due = jdbc.query("""
                select d.id, d.subscription_id, d.connector_id, d.event_id, d.event_type, d.aggregate_type,
                       d.aggregate_id, d.payload::text as payload, d.event_occurred_at, d.attempt_count,
                       k.display_name, k.credential_ref
                from dispatch.dispatch_delivery d
                join dispatch.connector k on k.tenant_id = d.tenant_id and k.id = d.connector_id
                where d.tenant_id = ? and d.status in ('PENDING','IN_FLIGHT')
                  and d.next_attempt_at <= now() and k.enabled = true
                order by d.next_attempt_at, d.created_at
                limit ?
                for update of d skip locked
                """, this::mapDue, tenantId, drainBatch);

        List<ClaimedDelivery> claimed = new ArrayList<>();
        for (DueRow row : due) {
            if (!breakers.allows(row.connectorId(), now)) {
                // Held, not dropped, and visibly held: the trace records why.
                recordAttempt(row.id(), row.connectorId(), "BLOCKED_BY_BREAKER", null, null,
                        "Circuit breaker is open for connector " + row.connectorName(), 0);
                jdbc.update("""
                        update dispatch.dispatch_delivery
                           set status = 'PENDING', next_attempt_at = ?, last_error = ?, updated_at = now()
                         where tenant_id = ? and id = ?
                        """, Timestamp.from(now.plus(breakers.policy().cooldown())),
                        "Held: circuit breaker open", tenantId, row.id());
                continue;
            }
            ConnectorTarget target = connectors.resolveTarget(row.connectorId());
            if (target == null) {
                continue;
            }
            jdbc.update("""
                    update dispatch.dispatch_delivery
                       set status = 'IN_FLIGHT', next_attempt_at = ?, updated_at = now()
                     where tenant_id = ? and id = ?
                    """, Timestamp.from(now.plusMillis(leaseMs)), tenantId, row.id());
            DispatchEnvelope envelope = new DispatchEnvelope(row.id(), tenantId, row.subscriptionId(),
                    row.connectorId(), row.eventId(), row.eventType(), row.aggregateType(), row.aggregateId(),
                    row.occurredAt(), row.attemptCount() + 1, readMap(row.payloadJson()));
            claimed.add(new ClaimedDelivery(envelope, target, row.connectorName(), row.credentialRef(),
                    row.attemptCount()));
        }
        return claimed;
    }

    /* ------------------------------------------------------------------ */
    /* Phase 3 — dispatch (no transaction) and record (transaction)         */
    /* ------------------------------------------------------------------ */

    /** Runs the adapter. Deliberately NOT transactional — no database connection is held. */
    public DispatchResult attempt(ClaimedDelivery claimed) {
        return adapters.dispatch(claimed.envelope(), claimed.target());
    }

    @Transactional
    public void recordOutcome(ClaimedDelivery claimed, DispatchResult result) {
        UUID tenantId = TenantContext.get().tenantId();
        Instant now = Instant.now();
        DispatchEnvelope envelope = claimed.envelope();
        int attemptsMade = claimed.attemptsMade() + 1;

        recordAttempt(envelope.deliveryId(), envelope.connectorId(), result.outcome().name(),
                result.httpStatus(), result.responseExcerpt(), result.error(), result.durationMs());

        if (result.success()) {
            jdbc.update("""
                    update dispatch.dispatch_delivery
                       set status = 'SUCCEEDED', attempt_count = ?, succeeded_at = ?, last_error = null,
                           last_http_status = ?, next_attempt_at = ?, updated_at = now()
                     where tenant_id = ? and id = ?
                    """, attemptsMade, Timestamp.from(now), result.httpStatus(), Timestamp.from(now),
                    tenantId, envelope.deliveryId());
            breakers.recordSuccess(envelope.connectorId(), now);
            connectors.markCredentialUsed(claimed.credentialRef());
            return;
        }

        boolean retry = result.retryable() && retryPolicy.canRetry(attemptsMade);
        if (retry) {
            Duration backoff = retryPolicy.backoff(attemptsMade);
            jdbc.update("""
                    update dispatch.dispatch_delivery
                       set status = 'PENDING', attempt_count = ?, next_attempt_at = ?,
                           last_error = ?, last_http_status = ?, updated_at = now()
                     where tenant_id = ? and id = ?
                    """, attemptsMade, Timestamp.from(now.plus(backoff)), result.error(), result.httpStatus(),
                    tenantId, envelope.deliveryId());
        } else {
            String reason = result.retryable()
                    ? "Exhausted " + retryPolicy.maxAttempts() + " attempts. Last error: " + result.error()
                    : "Permanent failure: " + result.error();
            deadLetter(claimed, attemptsMade, reason);
        }

        boolean opened = breakers.recordFailure(envelope.connectorId(), now, result.error());
        if (opened) {
            BreakerState state = breakers.state(envelope.connectorId());
            notifier.breakerOpened(envelope.connectorId(), claimed.connectorName(),
                    state.consecutiveFailures(), result.error());
        }
    }

    private void deadLetter(ClaimedDelivery claimed, int attempts, String reason) {
        UUID tenantId = TenantContext.get().tenantId();
        DispatchEnvelope envelope = claimed.envelope();
        jdbc.update("""
                update dispatch.dispatch_delivery
                   set status = 'DEAD_LETTERED', attempt_count = ?, last_error = ?, updated_at = now()
                 where tenant_id = ? and id = ?
                """, attempts, reason, tenantId, envelope.deliveryId());
        // The envelope is stored whole: a replay must not need the original
        // outbox row, which may have been purged by then.
        jdbc.update("""
                insert into dispatch.dispatch_dead_letter
                  (tenant_id, delivery_id, connector_id, subscription_id, event_id, event_type,
                   envelope, failure_reason, attempts)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                on conflict (tenant_id, delivery_id) do update set
                  failure_reason = excluded.failure_reason,
                  attempts = excluded.attempts,
                  replayed_at = null
                """, tenantId, envelope.deliveryId(), envelope.connectorId(), envelope.subscriptionId(),
                envelope.eventId(), envelope.eventType(), codec.toJson(envelope), reason, attempts);
        audit.record("INTEGRATION_DEAD_LETTERED", "DISPATCH_DELIVERY", envelope.deliveryId(),
                "Delivery to " + claimed.connectorName() + " dead-lettered after " + attempts + " attempt(s)",
                Map.of("connector", claimed.connectorName(), "eventType", envelope.eventType(),
                        "eventId", envelope.eventId().toString(), "reason", reason));
        maybeAlertOnDeadLetterDepth(envelope.connectorId(), claimed.connectorName());
    }

    private void maybeAlertOnDeadLetterDepth(UUID connectorId, String connectorName) {
        UUID tenantId = TenantContext.get().tenantId();
        Long depth = jdbc.queryForObject("""
                select count(*) from dispatch.dispatch_dead_letter
                where tenant_id = ? and connector_id = ? and replayed_at is null
                """, Long.class, tenantId, connectorId);
        if (depth == null || depth < deadLetterAlertThreshold) return;
        // One alert per connector per cooldown window, not one per dead letter.
        int claimedAlert = jdbc.update("""
                update dispatch.connector_health
                   set dlq_alert_at = now(), updated_at = now()
                 where tenant_id = ? and connector_id = ?
                   and (dlq_alert_at is null or dlq_alert_at < now() - interval '1 hour')
                """, tenantId, connectorId);
        if (claimedAlert > 0) {
            notifier.deadLetterThresholdCrossed(connectorId, connectorName, depth, deadLetterAlertThreshold);
        }
    }

    /**
     * Appends one row to the delivery's trace.
     *
     * <p>The attempt number is read first and inserted as a value rather than
     * computed inside an {@code insert ... select}: PostgreSQL cannot infer a
     * parameter's type from a bare select list, so a null {@code http_status}
     * in that form fails with "could not determine data type". Two statements
     * inside the same transaction, which is where the row lock already is.
     *
     * <p>{@code attempt_no} counts every recorded event including
     * {@code BLOCKED_BY_BREAKER} — it is the trace index, not the retry budget.
     * The retry budget is {@code dispatch_delivery.attempt_count}, which a
     * breaker hold deliberately does not consume.
     */
    private void recordAttempt(UUID deliveryId, UUID connectorId, String status, Integer httpStatus,
                               String excerpt, String error, long durationMs) {
        UUID tenantId = TenantContext.get().tenantId();
        Integer previous = jdbc.queryForObject("""
                select coalesce(max(attempt_no), 0) from dispatch.dispatch_attempt
                where tenant_id = ? and delivery_id = ?
                """, Integer.class, tenantId, deliveryId);
        jdbc.update("""
                insert into dispatch.dispatch_attempt
                  (tenant_id, delivery_id, connector_id, attempt_no, status, http_status,
                   response_excerpt, error, duration_ms)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, deliveryId, connectorId, (previous == null ? 0 : previous) + 1, status,
                httpStatus, DispatchResult.excerpt(excerpt), error,
                (int) Math.min(durationMs, Integer.MAX_VALUE));
    }

    /* ------------------------------------------------------------------ */
    /* Replay (FR-INT-005)                                                  */
    /* ------------------------------------------------------------------ */

    public record ReplayOutcome(UUID deadLetterId, UUID deliveryId, boolean requeued, String detail) {}

    /**
     * Requeue dead letters.
     *
     * <p>Replay goes through the SAME idempotency guard as the original: it
     * resets the EXISTING delivery row rather than inserting a new one, so
     * {@code (subscription_id, event_id)} still admits exactly one dispatch no
     * matter how many times replay is pressed. A dead letter that is not
     * currently dead-lettered — because someone already replayed it — reports
     * "already requeued" and changes nothing.
     */
    @Transactional
    public List<ReplayOutcome> replay(List<UUID> deadLetterIds) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        UUID tenantId = TenantContext.get().tenantId();
        List<ReplayOutcome> outcomes = new ArrayList<>();
        for (UUID deadLetterId : deadLetterIds) {
            List<UUID> deliveryIds = jdbc.queryForList("""
                    select delivery_id from dispatch.dispatch_dead_letter
                    where tenant_id = ? and id = ?
                    """, UUID.class, tenantId, deadLetterId);
            if (deliveryIds.isEmpty()) {
                throw new NotFoundException("No dead letter " + deadLetterId);
            }
            UUID deliveryId = deliveryIds.get(0);
            int requeued = jdbc.update("""
                    update dispatch.dispatch_delivery
                       set status = 'PENDING', attempt_count = 0, next_attempt_at = now(),
                           last_error = null, updated_at = now()
                     where tenant_id = ? and id = ? and status = 'DEAD_LETTERED'
                    """, tenantId, deliveryId);
            if (requeued == 0) {
                outcomes.add(new ReplayOutcome(deadLetterId, deliveryId, false,
                        "Already requeued; no second dispatch was created"));
                continue;
            }
            jdbc.update("""
                    update dispatch.dispatch_dead_letter
                       set replayed_at = now(), replayed_by = ?, replay_count = replay_count + 1
                     where tenant_id = ? and id = ?
                    """, TenantContext.get().userId(), tenantId, deadLetterId);
            audit.record("INTEGRATION_DEAD_LETTER_REPLAYED", "DISPATCH_DELIVERY", deliveryId,
                    "Replayed dead-lettered delivery", Map.of("deadLetterId", deadLetterId.toString()));
            outcomes.add(new ReplayOutcome(deadLetterId, deliveryId, true, "Requeued for delivery"));
        }
        return outcomes;
    }

    /**
     * Replay every open dead letter for a connector, or for the whole tenant
     * when {@code connectorId} is null.
     */
    @Transactional
    public List<ReplayOutcome> replayAll(UUID connectorId, int limit) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        List<UUID> ids = jdbc.queryForList("""
                select id from dispatch.dispatch_dead_letter
                where tenant_id = ? and replayed_at is null
                  and (?::uuid is null or connector_id = ?::uuid)
                order by created_at
                limit ?
                """, UUID.class, TenantContext.get().tenantId(), connectorId, connectorId,
                Math.max(1, Math.min(limit, 500)));
        return replay(ids);
    }

    /* ------------------------------------------------------------------ */
    /* Read side                                                            */
    /* ------------------------------------------------------------------ */

    public record DeliveryRow(UUID id, UUID connectorId, String connectorName, UUID subscriptionId,
                              UUID eventId, String eventType, String aggregateType, UUID aggregateId,
                              String status, int attemptCount, Instant nextAttemptAt, Instant succeededAt,
                              String lastError, Integer lastHttpStatus, Instant createdAt) {}

    public record AttemptRow(UUID id, int attemptNo, String status, Integer httpStatus,
                             String responseExcerpt, String error, int durationMs, Instant attemptedAt) {}

    public record DeadLetterRow(UUID id, UUID deliveryId, UUID connectorId, String connectorName,
                                UUID eventId, String eventType, Map<String, Object> envelope,
                                String failureReason, int attempts, Instant createdAt,
                                Instant replayedAt, int replayCount) {}

    @Transactional(readOnly = true)
    public List<DeliveryRow> deliveries(UUID connectorId, String status, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        String statusFilter = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return jdbc.query("""
                select d.id, d.connector_id, k.display_name, d.subscription_id, d.event_id, d.event_type,
                       d.aggregate_type, d.aggregate_id, d.status, d.attempt_count, d.next_attempt_at,
                       d.succeeded_at, d.last_error, d.last_http_status, d.created_at
                from dispatch.dispatch_delivery d
                join dispatch.connector k on k.tenant_id = d.tenant_id and k.id = d.connector_id
                where d.tenant_id = ?
                  and (?::uuid is null or d.connector_id = ?::uuid)
                  and (?::text is null or d.status = ?::text)
                order by d.created_at desc
                limit ?
                """, (rs, i) -> new DeliveryRow(
                        rs.getObject("id", UUID.class), rs.getObject("connector_id", UUID.class),
                        rs.getString("display_name"), rs.getObject("subscription_id", UUID.class),
                        rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                        rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                        rs.getString("status"), rs.getInt("attempt_count"),
                        instant(rs.getTimestamp("next_attempt_at")), instant(rs.getTimestamp("succeeded_at")),
                        rs.getString("last_error"), (Integer) rs.getObject("last_http_status"),
                        instant(rs.getTimestamp("created_at"))),
                TenantContext.get().tenantId(), connectorId, connectorId, statusFilter, statusFilter, limit);
    }

    @Transactional(readOnly = true)
    public List<AttemptRow> attempts(UUID deliveryId) {
        return jdbc.query("""
                select id, attempt_no, status, http_status, response_excerpt, error, duration_ms, attempted_at
                from dispatch.dispatch_attempt
                where tenant_id = ? and delivery_id = ?
                order by attempt_no
                """, (rs, i) -> new AttemptRow(rs.getObject("id", UUID.class), rs.getInt("attempt_no"),
                        rs.getString("status"), (Integer) rs.getObject("http_status"),
                        rs.getString("response_excerpt"), rs.getString("error"), rs.getInt("duration_ms"),
                        instant(rs.getTimestamp("attempted_at"))),
                TenantContext.get().tenantId(), deliveryId);
    }

    @Transactional(readOnly = true)
    public List<DeadLetterRow> deadLetters(UUID connectorId, boolean includeReplayed, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        return jdbc.query("""
                select l.id, l.delivery_id, l.connector_id, k.display_name, l.event_id, l.event_type,
                       l.envelope::text as envelope, l.failure_reason, l.attempts, l.created_at,
                       l.replayed_at, l.replay_count
                from dispatch.dispatch_dead_letter l
                join dispatch.connector k on k.tenant_id = l.tenant_id and k.id = l.connector_id
                where l.tenant_id = ?
                  and (?::uuid is null or l.connector_id = ?::uuid)
                  and (? = true or l.replayed_at is null)
                order by l.created_at desc
                limit ?
                """, (rs, i) -> new DeadLetterRow(
                        rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class),
                        rs.getObject("connector_id", UUID.class), rs.getString("display_name"),
                        rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                        readMap(rs.getString("envelope")), rs.getString("failure_reason"),
                        rs.getInt("attempts"), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("replayed_at")), rs.getInt("replay_count")),
                TenantContext.get().tenantId(), connectorId, connectorId, includeReplayed, limit);
    }

    /* ------------------------------------------------------------------ */

    private DueRow mapDue(ResultSet rs, int index) throws SQLException {
        return new DueRow(rs.getObject("id", UUID.class), rs.getObject("subscription_id", UUID.class),
                rs.getObject("connector_id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getString("event_type"), rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class), rs.getString("payload"),
                rs.getTimestamp("event_occurred_at").toInstant(), rs.getInt("attempt_count"),
                rs.getString("display_name"), rs.getString("credential_ref"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        try {
            return json.readValue(text, Map.class);
        } catch (JsonProcessingException ex) {
            log.warn("Dispatch payload is not readable JSON; treating it as empty");
            return new LinkedHashMap<>();
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

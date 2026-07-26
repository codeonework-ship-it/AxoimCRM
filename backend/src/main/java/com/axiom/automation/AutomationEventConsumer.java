package com.axiom.automation;

import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The automation module as an outbox consumer (ADR-003).
 *
 * <h2>Idempotency is the whole design</h2>
 * ADR-003 states delivery is at-least-once, which means the platform WILL deliver
 * the same event twice and the handler is responsible for the consequence. The
 * receipt insert is that responsibility discharged in one statement: the unique
 * constraint on {@code (tenant_id, event_key)} makes the second delivery insert
 * zero rows, the handler sees zero and returns {@code DUPLICATE} without touching
 * a record. Deduplicating in application memory would work until the process
 * restarts or a second instance starts consuming, which is to say it would work
 * in exactly the environment where duplicates do not happen.
 *
 * <h2>Why there is a direct invocation path</h2>
 * Kafka is not running in this environment and ADR-003's documented degraded mode
 * is that the outbox queues. {@link #drain(int)} reads the outbox directly (it
 * never writes to it — {@code dispatched_at} belongs to the relay) and feeds the
 * same {@link #handle} the broker path would call, so the handlers are
 * exercisable, testable and demonstrable with no broker at all.
 */
@Service
public class AutomationEventConsumer {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectMetadataService metadata;
    private final RecordChangeDispatcher dispatcher;

    @Autowired
    public AutomationEventConsumer(JdbcTemplate jdbc, ObjectMapper json,
                                   ObjectMetadataService metadata,
                                   RecordChangeDispatcher dispatcher) {
        this.jdbc = jdbc;
        this.json = json;
        this.metadata = metadata;
        this.dispatcher = dispatcher;
    }

    /**
     * @param outcome PROCESSED, DUPLICATE or IGNORED
     */
    public record HandleResult(String eventKey, String eventType, String outcome, String detail,
                               RecordChangeDispatcher.DispatchResult dispatch) {}

    public record DrainResult(int read, int processed, int duplicates, int ignored,
                              Instant cursorAt, List<HandleResult> results) {}

    /**
     * Handle one delivered event. Safe to call any number of times with the same
     * {@code eventKey}: only the first call has an effect.
     */
    @Transactional
    public HandleResult handle(String eventKey, String aggregateType, UUID aggregateId,
                               String eventType, Map<String, Object> payload) {
        int inserted = jdbc.update("""
                insert into automation.event_receipt (tenant_id, event_key, event_type)
                values (?, ?, ?)
                on conflict (tenant_id, event_key) do nothing
                """, TenantContext.get().tenantId(), eventKey, eventType);
        if (inserted == 0) {
            return new HandleResult(eventKey, eventType, "DUPLICATE",
                    "This event was already processed; at-least-once delivery means the second "
                            + "copy is expected and is deliberately a no-op.", null);
        }

        String objectType = objectTypeFor(aggregateType, payload);
        if (objectType == null) {
            return new HandleResult(eventKey, eventType, "IGNORED",
                    "No automatable object is registered for aggregate type " + aggregateType + ".", null);
        }
        String event = eventFor(eventType);
        Map<String, Object> after = metadata.readRecord(objectType, aggregateId);
        if (after.isEmpty() && !"DELETE".equals(event)) {
            return new HandleResult(eventKey, eventType, "IGNORED",
                    "The record this event refers to is not visible to this tenant.", null);
        }
        Map<String, Object> before = beforeValues(payload);

        RecordChangeDispatcher.DispatchResult dispatch =
                dispatcher.dispatch(objectType, aggregateId, event, before, after, 0);
        return new HandleResult(eventKey, eventType, "PROCESSED",
                dispatch.rulesConsidered() + " rule(s) considered.", dispatch);
    }

    /**
     * Read undispatched outbox events for this tenant and feed them through
     * {@link #handle}. Read-only against {@code integration.outbox_event}: this
     * consumer never marks an event dispatched, because that flag belongs to the
     * relay and a consumer that clears it would hide the event from every other
     * subscriber.
     */
    @Transactional
    public DrainResult drain(int limit) {
        AutomationAccess.requireRead();
        UUID tenantId = TenantContext.get().tenantId();
        Instant cursor = cursor();
        int capped = Math.max(1, Math.min(limit, 500));

        List<Map<String, Object>> events = jdbc.queryForList("""
                select id, aggregate_type, aggregate_id, event_type, payload::text as payload, created_at
                from integration.outbox_event
                where tenant_id = ? and created_at > ?
                order by created_at, id
                limit """ + capped, tenantId, java.sql.Timestamp.from(cursor));

        List<HandleResult> results = new ArrayList<>();
        int processed = 0;
        int duplicates = 0;
        int ignored = 0;
        Instant latest = cursor;
        UUID latestId = null;

        for (Map<String, Object> event : events) {
            UUID eventId = (UUID) event.get("id");
            HandleResult result = handle(eventId.toString(),
                    String.valueOf(event.get("aggregate_type")),
                    (UUID) event.get("aggregate_id"),
                    String.valueOf(event.get("event_type")),
                    readMap(String.valueOf(event.get("payload"))));
            results.add(result);
            switch (result.outcome()) {
                case "PROCESSED" -> processed++;
                case "DUPLICATE" -> duplicates++;
                default -> ignored++;
            }
            latest = ((java.sql.Timestamp) event.get("created_at")).toInstant();
            latestId = eventId;
        }

        if (latestId != null) {
            jdbc.update("""
                    insert into automation.event_cursor
                      (tenant_id, consumer, last_event_at, last_event_id, updated_at)
                    values (?, 'automation', ?, ?, now())
                    on conflict (tenant_id, consumer) do update
                      set last_event_at = excluded.last_event_at,
                          last_event_id = excluded.last_event_id, updated_at = now()
                    """, tenantId, java.sql.Timestamp.from(latest), latestId);
        }
        return new DrainResult(events.size(), processed, duplicates, ignored, latest, results);
    }

    @Transactional(readOnly = true)
    public Instant cursor() {
        List<Instant> rows = jdbc.query("""
                select last_event_at from automation.event_cursor
                where tenant_id = ? and consumer = 'automation'
                """, (rs, i) -> rs.getTimestamp(1).toInstant(), TenantContext.get().tenantId());
        return rows.isEmpty() ? Instant.EPOCH : rows.getFirst();
    }

    /**
     * Map an aggregate type onto a registered automatable object. Driven by the
     * registry, never by a switch over other epics' class names.
     */
    private String objectTypeFor(String aggregateType, Map<String, Object> payload) {
        String candidate = aggregateType == null ? "" : aggregateType.toUpperCase(Locale.ROOT);
        if (payload != null && payload.get("objectType") != null) {
            candidate = String.valueOf(payload.get("objectType")).toUpperCase(Locale.ROOT);
        }
        String finalCandidate = candidate;
        return metadata.list().stream()
                .map(ObjectMetadataService.ObjectDescriptor::objectType)
                .filter(t -> t.equals(finalCandidate))
                .findFirst().orElse(null);
    }

    /** {@code crm.lead.updated} → UPDATE; anything unrecognised is treated as an update. */
    static String eventFor(String eventType) {
        String lower = eventType == null ? "" : eventType.toLowerCase(Locale.ROOT);
        if (lower.endsWith("undeleted") || lower.contains(".undelete")
                || lower.endsWith("restored")) return "UNDELETE";
        if (lower.endsWith("created") || lower.contains(".create")) return "CREATE";
        if (lower.endsWith("deleted") || lower.contains(".delete")) return "DELETE";
        return "UPDATE";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> beforeValues(Map<String, Object> payload) {
        if (payload == null) return Map.of();
        Object before = payload.get("before");
        if (before instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) return Map.of();
        try {
            return json.readValue(value, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return Map.of();
        }
    }
}

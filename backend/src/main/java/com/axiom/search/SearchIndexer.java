package com.axiom.search;

import com.axiom.security.SystemTaskRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The search indexing consumer (ADR-003).
 *
 * <h2>Where it reads from</h2>
 * The transactional outbox itself, forward from a per-tenant cursor, rather than
 * from the Kafka topic {@code OutboxRelay} publishes to. ADR-003 names the outbox —
 * not the broker — as the source of truth precisely so a consumer can be fed this
 * way, and ADR-005 leaves the broker choice open (Kafka is recommended, not
 * ratified). Reading the outbox means the index stays correct in a sovereign
 * install that has not deployed a broker at all, and a Kafka-listener variant of
 * this class can be added later without any other component changing, because the
 * work it would do — {@link #apply} — is already keyed on ids rather than on
 * delivery mechanics.
 *
 * <h2>Why it is idempotent, and why redelivery is not a special case</h2>
 * An event is used for exactly one piece of information: <em>which record
 * changed</em>. The document is then re-projected from the authoritative row as it
 * stands now. So:
 * <ul>
 *   <li><b>Duplicate delivery</b> re-reads the same row, produces the same document,
 *       and the unique constraint on {@code (tenant_id, entity_type, entity_id)}
 *       turns the write into an update of a row that is already identical. One row,
 *       every time.</li>
 *   <li><b>Out-of-order delivery</b> cannot move the index backwards: the upsert
 *       refuses a document whose source {@code updated_at} is older than the stored
 *       one. ADR-003 guarantees ordering per {@code (tenant_id, entity_id)} but not
 *       globally, and a redelivery after a relay restart can legitimately arrive
 *       behind a newer write.</li>
 *   <li><b>Deletion</b> needs no special event handling at all: if the record is
 *       gone or soft-deleted, the projection yields nothing and the document is
 *       removed. A hard delete and a soft delete therefore behave identically, and
 *       an event type this class has never heard of still leaves a correct index.</li>
 * </ul>
 *
 * <h2>Cursor, not offset</h2>
 * The cursor is {@code (created_at, id)} — a strict row comparison, so two events
 * committed in the same microsecond cannot make the consumer skip one or replay
 * forever.
 */
@Component
public class SearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexer.class);

    /** Consumer-group name in {@code search.index_checkpoint}. */
    public static final String CONSUMER = "postgres-fts";

    private final JdbcTemplate jdbc;
    private final SearchIndex index;
    private final SearchProjector projector;
    private final SystemTaskRunner tasks;
    private final int batchSize;
    private final boolean enabled;

    public SearchIndexer(JdbcTemplate jdbc, SearchIndex index, SearchProjector projector,
                         SystemTaskRunner tasks,
                         @Value("${axiom.search.consumer-batch-size:200}") int batchSize,
                         @Value("${axiom.search.consumer-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.index = index;
        this.projector = projector;
        this.tasks = tasks;
        this.batchSize = Math.max(10, batchSize);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${axiom.search.consumer-poll-fixed-delay-ms:5000}")
    public void consumeTick() {
        if (!enabled) return;
        try {
            tasks.forEachTenant("search-indexer", this::consumeTenant);
        } catch (RuntimeException ex) {
            log.warn("Search indexer tick failed; will retry: {}", ex.getMessage());
        }
    }

    /** One bounded batch for one tenant. Returns the number of events consumed. */
    public int consumeTenant(UUID tenantId) {
        Cursor cursor = cursor(tenantId);
        List<Event> events = jdbc.query("""
                select id, aggregate_type, aggregate_id, created_at
                from integration.outbox_event
                where tenant_id = ?
                  and lower(aggregate_type) = any(?)
                  and (created_at, id) > (coalesce(?, '-infinity'::timestamptz),
                                          coalesce(?, '00000000-0000-0000-0000-000000000000'::uuid))
                order by created_at, id
                limit ?
                """, ps -> {
            ps.setObject(1, tenantId);
            ps.setArray(2, ps.getConnection().createArrayOf("text",
                    java.util.Arrays.stream(IndexedEntity.values())
                            .map(IndexedEntity::aggregateType).toArray()));
            ps.setTimestamp(3, cursor.at() == null ? null : Timestamp.from(cursor.at()));
            ps.setObject(4, cursor.id());
            ps.setInt(5, batchSize);
        }, (rs, i) -> new Event(
                rs.getObject("id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()));

        if (events.isEmpty()) return 0;

        // Collapse the batch to the distinct records it touched. Ten stage changes on
        // one opportunity are one re-projection, not ten — and because the projection
        // reads current state, collapsing cannot lose an intermediate value that
        // anyone could observe.
        Map<IndexedEntity, Set<UUID>> touched = new LinkedHashMap<>();
        for (Event event : events) {
            IndexedEntity.forAggregateType(event.aggregateType()).ifPresent(entity ->
                    touched.computeIfAbsent(entity, x -> new LinkedHashSet<>()).add(event.aggregateId()));
        }

        Applied applied = apply(tenantId, touched);
        Event last = events.get(events.size() - 1);
        advance(tenantId, last, events.size(), applied);
        log.debug("Search indexer: tenant {} consumed {} event(s), {} written, {} removed",
                tenantId, events.size(), applied.written(), applied.removed());
        return events.size();
    }

    record Applied(int written, int removed) {}

    /**
     * Re-project and store the given records. Anything with no live source row is
     * removed from the index — see the class comment on why deletion needs no event
     * of its own.
     */
    public Applied apply(UUID tenantId, Map<IndexedEntity, Set<UUID>> touched) {
        int written = 0;
        int removed = 0;
        for (Map.Entry<IndexedEntity, Set<UUID>> entry : touched.entrySet()) {
            IndexedEntity entity = entry.getKey();
            List<UUID> ids = new ArrayList<>(entry.getValue());
            List<SearchDocument> documents = projector.project(tenantId, entity, ids);
            Set<UUID> live = new LinkedHashSet<>();
            for (SearchDocument document : documents) {
                live.add(document.entityId());
                if (index.upsert(tenantId, document)) written++;
            }
            for (UUID id : ids) {
                if (!live.contains(id)) removed += index.delete(tenantId, entity, id);
            }
        }
        return new Applied(written, removed);
    }

    // ------------------------------------------------------------------ cursor

    record Cursor(Instant at, UUID id) {}

    private record Event(UUID id, String aggregateType, UUID aggregateId, Instant createdAt) {}

    private Cursor cursor(UUID tenantId) {
        Cursor cursor = jdbc.query("""
                select last_event_at, last_event_id from search.index_checkpoint
                where tenant_id = ? and consumer = ?
                """, rs -> {
            if (!rs.next()) return new Cursor(null, null);
            Timestamp at = rs.getTimestamp("last_event_at");
            // '-infinity' round-trips as a sentinel far outside the useful range.
            boolean unset = at == null || at.getTime() <= Long.MIN_VALUE + 1000L;
            return new Cursor(unset ? null : at.toInstant(), rs.getObject("last_event_id", UUID.class));
        }, tenantId, CONSUMER);
        return cursor == null ? new Cursor(null, null) : cursor;
    }

    private void advance(UUID tenantId, Event last, int consumed, Applied applied) {
        jdbc.update("""
                insert into search.index_checkpoint
                  (tenant_id, consumer, last_event_at, last_event_id, events_applied,
                   documents_written, documents_removed, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, now())
                on conflict (tenant_id, consumer) do update set
                  last_event_at = excluded.last_event_at,
                  last_event_id = excluded.last_event_id,
                  events_applied = search.index_checkpoint.events_applied + excluded.events_applied,
                  documents_written = search.index_checkpoint.documents_written + excluded.documents_written,
                  documents_removed = search.index_checkpoint.documents_removed + excluded.documents_removed,
                  last_error = null,
                  updated_at = now()
                """, tenantId, CONSUMER, Timestamp.from(last.createdAt()), last.id(),
                (long) consumed, (long) applied.written(), (long) applied.removed());
    }
}

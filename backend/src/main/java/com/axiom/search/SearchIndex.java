package com.axiom.search;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The provider seam. Everything above this interface — the indexer, the backfill,
 * the query service, the controller — is written against these six operations and
 * knows nothing about PostgreSQL full-text search.
 *
 * <p>ADR-005 leaves the search engine explicitly open ("PostgreSQL full-text ·
 * OpenSearch · Elasticsearch … Start with PostgreSQL; escalate on measured need")
 * and requires every component to be self-hostable for sovereign deployment. This
 * interface is what makes "escalate" a new class rather than a rewrite:
 * {@link PostgresSearchIndex} is the first implementation; an OpenSearch one would
 * implement the same six methods and change nothing else.
 *
 * <p><b>What deliberately is not on this interface: authorization.</b>
 * {@link #query} takes an {@link IndexFilter} and applies it, but the result is
 * named {@link Candidate} rather than "hit" on purpose. system-design §8.2 is
 * explicit that the index is not authoritative for access, so no implementation of
 * this interface is permitted to be the last word on what a user may see. The
 * authoritative recheck lives in {@link SearchService}, above the seam, so it
 * cannot be lost by swapping the engine.
 */
public interface SearchIndex {

    /**
     * Insert or replace one document, keyed on (tenant, entityType, entityId).
     *
     * <p>Contract, and the reason the indexer can be an at-least-once consumer
     * (ADR-003 rule 3): this call is <b>idempotent</b>, and it <b>ignores a
     * document whose {@code updatedAt} is older than the one already stored</b>.
     * Duplicate delivery therefore converges on one row, and out-of-order delivery
     * cannot make the index go backwards.
     *
     * @return true if the stored document changed, false if it was ignored as stale
     */
    boolean upsert(UUID tenantId, SearchDocument document);

    /** Remove one document. Returns the number of rows removed (0 or 1); safe to call repeatedly. */
    int delete(UUID tenantId, IndexedEntity entity, UUID entityId);

    /**
     * Candidates matching the query text, already narrowed by tenant, type and the
     * caller's owner/sharing keys, ranked best first.
     *
     * <p>The filter is a narrowing device, never an authorization decision. See the
     * invariant in V240: the filter must never be narrower than true access.
     */
    List<Candidate> query(UUID tenantId, IndexQuery query, IndexFilter filter);

    /**
     * Snippets for the surviving page, computed over the text <em>this caller may
     * read</em> and nothing else.
     *
     * @param readableText per-candidate text the caller is cleared to see
     * @return per-candidate match flag and highlighted snippet, in input order
     */
    List<Snippet> snippets(List<String> readableText, String queryText);

    /** Newest indexed source timestamp, document count and last checkpoint, for the staleness display. */
    IndexFreshness freshness(UUID tenantId);

    /** Documents held for a tenant, by entity type — used by the reindex progress display. */
    Map<IndexedEntity, Long> documentCounts(UUID tenantId);

    /**
     * Entity ids this index currently holds for one type, paged by id.
     *
     * <p>Exists so the backfill can prune documents whose source record has gone
     * without the index needing to know what a source record is. A rebuild that only
     * ever writes is not a rebuild: a record hard-deleted while the indexer was down
     * would stay findable forever.
     */
    List<UUID> storedIds(UUID tenantId, IndexedEntity entity, UUID afterId, int limit);

    // ------------------------------------------------------------------ value types

    /**
     * What the caller asked for.
     *
     * @param text  the raw query string
     * @param types entity types to search; empty means every indexed type
     * @param limit maximum candidates to pull from the index before the recheck
     */
    record IndexQuery(String text, List<IndexedEntity> types, int limit) {}

    /**
     * The index-level narrowing for one caller.
     *
     * @param unrestrictedTypes types where the caller's access cannot be expressed
     *                          as owner/sharing keys (view-all, a permissive
     *                          org-wide default, or an active sharing rule whose
     *                          criteria the index cannot evaluate). Widened rather
     *                          than guessed — a filter that is too narrow loses
     *                          records the caller is entitled to find.
     * @param principalKeys     the caller's own key set: user id, role node,
     *                          groups, territories.
     */
    record IndexFilter(List<IndexedEntity> unrestrictedTypes, List<UUID> principalKeys) {}

    /**
     * One index match, before the authoritative recheck. Carries the raw stored
     * text so the query path can rebuild the caller-readable subset of it.
     */
    record Candidate(IndexedEntity entity, UUID entityId, String title, String subtitle, String body,
                     Map<String, String> securedFields, String urlPath, Instant updatedAt, double rank) {}

    record Snippet(boolean matched, String text) {}

    /**
     * @param documentCount        documents held for this tenant
     * @param newestSourceUpdatedAt newest source {@code updated_at} present in the index
     * @param lastIndexedAt        when the index last accepted a write
     * @param checkpointAt         how far the outbox consumer has read
     * @param pendingEvents        indexable outbox events behind the checkpoint
     */
    record IndexFreshness(long documentCount, Instant newestSourceUpdatedAt, Instant lastIndexedAt,
                          Instant checkpointAt, long pendingEvents) {}
}

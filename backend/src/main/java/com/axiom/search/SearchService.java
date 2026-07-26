package com.axiom.search;

import com.axiom.security.AccessContext;
import com.axiom.security.AuthorizationService;
import com.axiom.security.SecurableObject;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Global search (FR-ADM-004), and the place where this engine either is or is not
 * correct.
 *
 * <h2>The rule, stated before the code</h2>
 * system-design §8.2: <i>"Access can change faster than an index can be rebuilt, so
 * the index is not treated as authoritative for authorization. The approach: index
 * tenant_id, owner and sharing keys; filter on them at query time; then re-check the
 * returned page against the authoritative store before display. This costs a little
 * latency on the result page and is the only version that is correct. Indexing a
 * materialized ACL and trusting it would be faster and would eventually show someone
 * a record they had just been removed from."</i>
 *
 * <p>So this class does four things in a fixed order, and the order is the design:
 * <ol>
 *   <li><b>Narrow at the index.</b> Tenant, requested types, and the caller's own
 *       key set (user, role node, groups, territories) against the document's owner
 *       and sharing keys. This is a performance device. Where the caller's access
 *       cannot be expressed as keys — view-all, a permissive org-wide default, or an
 *       active sharing rule whose criteria only the live evaluator can decide — the
 *       filter is <b>widened</b>, never guessed. A filter that is too wide costs a
 *       dropped row; a filter that is too narrow loses a record the caller was
 *       entitled to find, and nothing downstream can recover it.</li>
 *   <li><b>Re-check against the authoritative store.</b> One query per entity type on
 *       the page, using the very same {@link AuthorizationService} predicate the
 *       record list pages use — ownership, role roll-up, live sharing-rule criteria,
 *       teams, territories and manual shares, all evaluated now. Anything that fails
 *       is dropped. This is the step that makes a stale sharing key in the index
 *       harmless: {@code SearchIndexSqlIT} constructs exactly that case.</li>
 *   <li><b>Apply field-level security</b> (FR-SEC-007). A withheld field is
 *       <em>absent</em> from the hit, never null. And a hit that matched the index
 *       only through a field this caller may not read is dropped entirely — otherwise
 *       the existence of the hit leaks the hidden value's content.</li>
 *   <li><b>Report the count honestly.</b> {@code indexMatches}, {@code returned} and
 *       the two drop counts are all in the response. The page is deliberately
 *       <em>not</em> back-filled from the index to hide the drops: padding would
 *       reintroduce the index as an authority on who may see what, one row further
 *       down.</li>
 * </ol>
 *
 * <h2>What it costs</h2>
 * One extra round trip per entity type present on the page — at most four, bounded
 * by the page size and not by the corpus size. That is the "little latency on the
 * result page" §8.2 accepts, and {@code recheckMillis} in the response reports the
 * measured figure rather than asking anyone to take it on trust.
 */
@Service
public class SearchService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    /**
     * One result. A map rather than a record because FR-SEC-007 requires a withheld
     * field to be <b>absent</b> from the response — "absence and emptiness must not
     * be conflated" — and a record serializes its components unconditionally.
     */
    public record SearchResponse(String query, List<String> types, int limit,
                                 List<Map<String, Object>> hits,
                                 int indexMatches, int returned,
                                 int droppedByRecheck, int droppedByFieldSecurity,
                                 List<String> typesDenied,
                                 long indexQueryMillis, long recheckMillis,
                                 IndexStatus index) {}

    /**
     * The staleness contract, surfaced rather than hidden. ADR-003 is explicit that
     * where a user can observe the lag "it must be shown to them rather than left to
     * look like a bug", and §8.1 says the same of the reporting projection. A search
     * box that silently omits a record saved four seconds ago looks broken; one that
     * says the index is four seconds behind is merely eventually consistent.
     */
    public record IndexStatus(long documentCount, Map<String, Long> documentsByType,
                              Instant newestIndexedUpdatedAt, Instant lastIndexedAt,
                              Instant consumerCheckpointAt, Long lagSeconds, long pendingEvents,
                              SearchBackfillService.ReindexRun activeRun) {}

    private final JdbcTemplate jdbc;
    private final SearchIndex index;
    private final AuthorizationService authorization;
    private final SearchBackfillService backfill;

    public SearchService(JdbcTemplate jdbc, SearchIndex index, AuthorizationService authorization,
                         SearchBackfillService backfill) {
        this.jdbc = jdbc;
        this.index = index;
        this.authorization = authorization;
        this.backfill = backfill;
    }

    // ------------------------------------------------------------------ query

    @Transactional(readOnly = true)
    public SearchResponse search(String queryText, List<String> requestedTypes, Integer requestedLimit) {
        UUID tenantId = TenantContext.get().tenantId();
        int limit = requestedLimit == null ? DEFAULT_LIMIT : Math.min(Math.max(requestedLimit, 1), MAX_LIMIT);
        String text = queryText == null ? "" : queryText.trim();
        List<IndexedEntity> asked = resolveTypes(requestedTypes);

        AccessContext ctx = authorization.context();
        List<IndexedEntity> searchable = new ArrayList<>();
        List<IndexedEntity> unrestricted = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        Map<IndexedEntity, AuthorizationService.RecordPredicate> predicates =
                new EnumMap<>(IndexedEntity.class);

        for (IndexedEntity entity : asked) {
            AuthorizationService.RecordPredicate predicate =
                    authorization.visibleRecordPredicate(entity.securable(), "t");
            if (predicate.deniesEverything()) {
                // No object-level read at all. Excluded from the index query outright:
                // there is nothing to gain by matching rows we would drop, and naming
                // the type in the response is more useful than a silent zero.
                denied.add(entity.name());
                continue;
            }
            searchable.add(entity);
            predicates.put(entity, predicate);
            if (predicate.allowsEverything() || hasActiveSharingRules(tenantId, entity.securable())) {
                unrestricted.add(entity);
            }
        }

        if (text.isEmpty() || searchable.isEmpty()) {
            return new SearchResponse(text, asked.stream().map(Enum::name).toList(), limit,
                    List.of(), 0, 0, 0, 0, denied, 0L, 0L, status(tenantId));
        }

        SearchIndex.IndexFilter filter =
                new SearchIndex.IndexFilter(unrestricted, principalKeys(ctx));

        long indexStart = System.nanoTime();
        List<SearchIndex.Candidate> candidates = index.query(tenantId,
                new SearchIndex.IndexQuery(text, searchable, limit), filter);
        long indexMillis = millisSince(indexStart);

        long recheckStart = System.nanoTime();
        List<SearchIndex.Candidate> permitted = recheck(tenantId, candidates, predicates);
        long recheckMillis = millisSince(recheckStart);

        Rendered rendered = render(ctx, permitted, text);

        return new SearchResponse(text, searchable.stream().map(Enum::name).toList(), limit,
                rendered.hits(), candidates.size(), rendered.hits().size(),
                candidates.size() - permitted.size(), permitted.size() - rendered.hits().size(),
                denied, indexMillis, recheckMillis, status(tenantId));
    }

    // ------------------------------------------------------------------ step 2: the recheck

    /**
     * Re-read the candidate ids from the authoritative tables under the live
     * authorization predicate, one query per entity type on the page.
     *
     * <p>Per-type batch rather than per-record: a page of twenty hits spanning three
     * object types costs three queries, not twenty. The predicate is the identical
     * one the list pages use, so search cannot drift away from the rest of the
     * product's idea of who may read what.
     */
    private List<SearchIndex.Candidate> recheck(UUID tenantId, List<SearchIndex.Candidate> candidates,
                                                Map<IndexedEntity, AuthorizationService.RecordPredicate> predicates) {
        if (candidates.isEmpty()) return List.of();
        Map<IndexedEntity, List<UUID>> byType = new EnumMap<>(IndexedEntity.class);
        for (SearchIndex.Candidate candidate : candidates) {
            byType.computeIfAbsent(candidate.entity(), x -> new ArrayList<>()).add(candidate.entityId());
        }

        Set<UUID> allowed = new LinkedHashSet<>();
        byType.forEach((entity, ids) -> {
            AuthorizationService.RecordPredicate predicate = predicates.get(entity);
            if (predicate == null) return;
            SecurableObject object = entity.securable();
            String sql = "select t.id from " + object.qualifiedTable() + " t"
                    + " where t.tenant_id = ? and t.id = any(?)"
                    + (object.softDeleted() ? " and t.deleted_at is null" : "")
                    + " and (" + predicate.sql() + ")";
            allowed.addAll(jdbc.query(sql, ps -> {
                ps.setObject(1, tenantId);
                ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
                bind(ps, 3, predicate.args());
            }, (rs, i) -> rs.getObject(1, UUID.class)));
        });

        return candidates.stream().filter(candidate -> allowed.contains(candidate.entityId())).toList();
    }

    // ------------------------------------------------------------------ step 3: field security

    private record Rendered(List<Map<String, Object>> hits) {}

    private Rendered render(AccessContext ctx, List<SearchIndex.Candidate> candidates, String text) {
        if (candidates.isEmpty()) return new Rendered(List.of());

        List<String> readableText = new ArrayList<>(candidates.size());
        List<List<String>> withheld = new ArrayList<>(candidates.size());
        List<Boolean> titleReadable = new ArrayList<>(candidates.size());
        List<Boolean> subtitleReadable = new ArrayList<>(candidates.size());

        for (SearchIndex.Candidate candidate : candidates) {
            IndexedEntity entity = candidate.entity();
            Set<String> hidden = ctx.unreadable(entity.securable());
            boolean showTitle = entity.titleFields().stream().noneMatch(hidden::contains);
            boolean showSubtitle = entity.subtitleFields().stream().noneMatch(hidden::contains);
            List<String> hiddenHere = new ArrayList<>();
            StringBuilder visible = new StringBuilder();

            if (showTitle) append(visible, candidate.title());
            else hiddenHere.addAll(entity.titleFields());
            if (showSubtitle) append(visible, candidate.subtitle());
            else hiddenHere.addAll(entity.subtitleFields());
            append(visible, candidate.body());
            candidate.securedFields().forEach((field, value) -> {
                if (hidden.contains(field)) hiddenHere.add(field);
                else append(visible, value);
            });

            readableText.add(visible.toString());
            withheld.add(hiddenHere);
            titleReadable.add(showTitle);
            subtitleReadable.add(showSubtitle);
        }

        List<SearchIndex.Snippet> snippets = index.snippets(readableText, text);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            SearchIndex.Candidate candidate = candidates.get(i);
            SearchIndex.Snippet snippet = i < snippets.size() ? snippets.get(i) : null;

            // Matched only on hidden text, or the headline itself is unreadable:
            // this caller did not, in any sense they are entitled to, find anything.
            if (snippet == null || !snippet.matched()) continue;
            if (!titleReadable.get(i)) continue;

            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("entityType", candidate.entity().name());
            hit.put("entityId", candidate.entityId().toString());
            hit.put("title", candidate.title());
            // ABSENT, not null, when withheld — FR-SEC-007.
            if (subtitleReadable.get(i) && candidate.subtitle() != null) {
                hit.put("subtitle", candidate.subtitle());
            }
            hit.put("snippet", snippet.text());
            hit.put("urlPath", candidate.urlPath());
            hit.put("rank", candidate.rank());
            hit.put("updatedAt", candidate.updatedAt().toString());
            if (!withheld.get(i).isEmpty()) hit.put("withheldFields", withheld.get(i));
            hits.add(hit);
        }
        return new Rendered(List.copyOf(hits));
    }

    // ------------------------------------------------------------------ staleness

    @Transactional(readOnly = true)
    public IndexStatus status() {
        return status(TenantContext.get().tenantId());
    }

    private IndexStatus status(UUID tenantId) {
        SearchIndex.IndexFreshness freshness = index.freshness(tenantId);
        Map<String, Long> byType = new LinkedHashMap<>();
        index.documentCounts(tenantId).forEach((entity, count) -> byType.put(entity.name(), count));
        Long lagSeconds = freshness.newestSourceUpdatedAt() == null
                ? null : Math.max(0L, Duration.between(freshness.newestSourceUpdatedAt(), Instant.now()).toSeconds());
        return new IndexStatus(freshness.documentCount(), byType, freshness.newestSourceUpdatedAt(),
                freshness.lastIndexedAt(), freshness.checkpointAt(), lagSeconds,
                freshness.pendingEvents(), backfill.activeRun());
    }

    // ------------------------------------------------------------------ helpers

    private List<IndexedEntity> resolveTypes(List<String> requested) {
        if (requested == null || requested.isEmpty()) return List.of(IndexedEntity.values());
        List<IndexedEntity> resolved = new ArrayList<>();
        for (String type : requested) {
            if (type == null || type.isBlank()) continue;
            for (String part : type.split(",")) {
                if (part.isBlank()) continue;
                IndexedEntity entity = IndexedEntity.of(part);
                if (!resolved.contains(entity)) resolved.add(entity);
            }
        }
        return resolved.isEmpty() ? List.of(IndexedEntity.values()) : resolved;
    }

    /**
     * The caller's own key set. Deliberately the mirror image of what
     * {@link SearchProjector} writes into a document's {@code sharing_keys}: user id,
     * role node, group memberships and territory memberships.
     */
    private static List<UUID> principalKeys(AccessContext ctx) {
        List<UUID> keys = new ArrayList<>();
        keys.add(ctx.userId());
        if (ctx.roleNodeId() != null) keys.add(ctx.roleNodeId());
        keys.addAll(ctx.groupIds());
        keys.addAll(ctx.territoryIds());
        keys.removeIf(java.util.Objects::isNull);
        return keys;
    }

    /**
     * Does any active sharing rule exist on this object? If so the index filter is
     * widened for that type, because a criteria rule is a predicate over a live field
     * value and no static key set can stand in for it. The alternative — evaluating
     * the criteria at index time and storing the result — is the materialized ACL
     * §8.2 rejects, and it would go stale the moment the criteria field changed.
     */
    private boolean hasActiveSharingRules(UUID tenantId, SecurableObject object) {
        Boolean found = jdbc.queryForObject("""
                select exists (select 1 from security.sharing_rule
                               where tenant_id = ? and object_type = ? and active = true)
                """, Boolean.class, tenantId, object.name());
        return Boolean.TRUE.equals(found);
    }

    private static void bind(PreparedStatement ps, int firstIndex, List<Object> args)
            throws java.sql.SQLException {
        int position = firstIndex;
        for (Object arg : args) ps.setObject(position++, arg);
    }

    private static void append(StringBuilder target, String value) {
        if (value == null || value.isBlank()) return;
        if (target.length() > 0) target.append(' ');
        target.append(value);
    }

    private static long millisSince(long startNanos) {
        return Math.round((System.nanoTime() - startNanos) / 1_000_000.0);
    }
}

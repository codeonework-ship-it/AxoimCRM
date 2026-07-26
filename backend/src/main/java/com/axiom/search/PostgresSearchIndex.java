package com.axiom.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The PostgreSQL full-text implementation of {@link SearchIndex} — ADR-005's
 * "start with PostgreSQL full-text", which is also the only option in the shortlist
 * that a sovereign customer can run without adding a second piece of infrastructure
 * to their estate.
 *
 * <h2>Relevance</h2>
 * The stored {@code tsvector} is built with {@code setweight}: title A, subtitle B,
 * body C, secured terms D. Ranking uses {@code ts_rank_cd}, which honours those
 * weights and also rewards term proximity — so "Meridian Fabrication" matching a
 * company <em>name</em> outranks the same words scattered through another record's
 * description. Without the weights, a title hit and a body hit would score
 * identically and the ordering of a global search box would be arbitrary, which is
 * precisely what FR-ADM-004's "relevance ranking" forbids.
 *
 * <h2>Idempotency</h2>
 * {@link #upsert} is {@code INSERT … ON CONFLICT DO UPDATE … WHERE d.updated_at
 * &lt;= excluded.updated_at}. Two consequences, both required by ADR-003:
 * redelivering the same event converges on one row rather than duplicating it, and
 * an event carrying an older version of the record is <em>ignored</em> rather than
 * allowed to move the index backwards. Both are asserted in
 * {@code SearchIndexSqlIT}, because ADR-003 says idempotency that has never been
 * tested under duplicate delivery is a hope rather than a property.
 */
@Component
public class PostgresSearchIndex implements SearchIndex {

    /** Text search configuration. One constant: the generated column, the query and the headline must agree. */
    static final String FTS_CONFIG = "english";

    /**
     * Snippet highlight markers. Deliberately not HTML: the API returns plain text
     * with these markers and the UI splits on them, so a record whose name contains
     * {@code <script>} cannot become markup on the way to a browser.
     */
    public static final String HIGHLIGHT_START = "[[";
    public static final String HIGHLIGHT_END = "]]";

    private static final String HEADLINE_OPTIONS =
            "StartSel=\"" + HIGHLIGHT_START + "\", StopSel=\"" + HIGHLIGHT_END + "\", "
                    + "MaxFragments=1, MaxWords=24, MinWords=6, ShortWord=2, HighlightAll=false";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresSearchIndex(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ write

    @Override
    public boolean upsert(UUID tenantId, SearchDocument doc) {
        String securedJson = writeJson(doc.securedFields());
        int changed = jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into search.search_document as d
                      (tenant_id, entity_type, entity_id, title, subtitle, body, secured_terms,
                       secured_fields, owner_id, sharing_keys, url_path, updated_at, indexed_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, now())
                    on conflict (tenant_id, entity_type, entity_id) do update set
                      title = excluded.title,
                      subtitle = excluded.subtitle,
                      body = excluded.body,
                      secured_terms = excluded.secured_terms,
                      secured_fields = excluded.secured_fields,
                      owner_id = excluded.owner_id,
                      sharing_keys = excluded.sharing_keys,
                      url_path = excluded.url_path,
                      updated_at = excluded.updated_at,
                      indexed_at = now()
                    where d.updated_at <= excluded.updated_at
                    """);
            ps.setObject(1, tenantId);
            ps.setString(2, doc.entity().name());
            ps.setObject(3, doc.entityId());
            ps.setString(4, doc.title());
            ps.setString(5, doc.subtitle());
            ps.setString(6, doc.body());
            ps.setString(7, doc.securedTerms());
            ps.setString(8, securedJson);
            ps.setObject(9, doc.ownerId());
            ps.setArray(10, uuidArray(ps, doc.sharingKeys()));
            ps.setString(11, doc.urlPath());
            ps.setTimestamp(12, Timestamp.from(doc.updatedAt()));
            return ps;
        });
        return changed > 0;
    }

    @Override
    public int delete(UUID tenantId, IndexedEntity entity, UUID entityId) {
        return jdbc.update("""
                delete from search.search_document
                where tenant_id = ? and entity_type = ? and entity_id = ?
                """, tenantId, entity.name(), entityId);
    }

    // ------------------------------------------------------------------ read

    @Override
    public List<Candidate> query(UUID tenantId, IndexQuery query, IndexFilter filter) {
        List<IndexedEntity> types = query.types().isEmpty()
                ? List.of(IndexedEntity.values()) : query.types();
        String sql = """
                select d.entity_type, d.entity_id, d.title, d.subtitle, d.body,
                       d.secured_fields::text as secured_fields, d.url_path, d.updated_at,
                       ts_rank_cd(d.document, q.query) as rank
                from search.search_document d,
                     plainto_tsquery(?::regconfig, ?) as q(query)
                where d.tenant_id = ?
                  and d.document @@ q.query
                  and d.entity_type = any(?)
                  and (
                        d.entity_type = any(?)
                     or d.owner_id = any(?)
                     or d.sharing_keys && ?
                  )
                order by rank desc, d.updated_at desc
                limit ?
                """;
        return jdbc.query(sql, ps -> {
            ps.setString(1, FTS_CONFIG);
            ps.setString(2, query.text());
            ps.setObject(3, tenantId);
            ps.setArray(4, textArray(ps, types.stream().map(Enum::name).toList()));
            ps.setArray(5, textArray(ps, filter.unrestrictedTypes().stream().map(Enum::name).toList()));
            ps.setArray(6, uuidArray(ps, filter.principalKeys()));
            ps.setArray(7, uuidArray(ps, filter.principalKeys()));
            ps.setInt(8, query.limit());
        }, (rs, i) -> new Candidate(
                IndexedEntity.valueOf(rs.getString("entity_type")),
                rs.getObject("entity_id", UUID.class),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("body"),
                readJson(rs.getString("secured_fields")),
                rs.getString("url_path"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getDouble("rank")));
    }

    /**
     * One round trip for the whole page rather than one per hit. The text is passed
     * <em>in</em> because only the caller's own field permissions decide what it may
     * contain — the index cannot know, and must not decide.
     *
     * <p>The {@code matched} flag is the other half of FR-SEC-007 in search: a record
     * that matched the index only because of a field this caller may not read did not,
     * as far as this caller is concerned, match at all. Returning it would leak the
     * hidden value's content through the fact of the hit.
     */
    @Override
    public List<Snippet> snippets(List<String> readableText, String queryText) {
        if (readableText.isEmpty()) return List.of();
        String sql = """
                select (q.query @@ to_tsvector(?::regconfig, t.txt)) as matched,
                       ts_headline(?::regconfig, t.txt, q.query, ?) as snippet
                from unnest(?) with ordinality as t(txt, ord),
                     plainto_tsquery(?::regconfig, ?) as q(query)
                order by t.ord
                """;
        return jdbc.query(sql, ps -> {
            ps.setString(1, FTS_CONFIG);
            ps.setString(2, FTS_CONFIG);
            ps.setString(3, HEADLINE_OPTIONS);
            ps.setArray(4, textArray(ps, readableText));
            ps.setString(5, FTS_CONFIG);
            ps.setString(6, queryText);
        }, (rs, i) -> new Snippet(rs.getBoolean("matched"), rs.getString("snippet")));
    }

    @Override
    public IndexFreshness freshness(UUID tenantId) {
        Object[] head = jdbc.queryForObject("""
                select count(*) as documents, max(updated_at) as newest_source, max(indexed_at) as last_indexed
                from search.search_document where tenant_id = ?
                """, (rs, i) -> new Object[]{
                rs.getLong("documents"),
                rs.getTimestamp("newest_source"),
                rs.getTimestamp("last_indexed")}, tenantId);

        Object[] cursor = jdbc.query("""
                select last_event_at, last_event_id from search.index_checkpoint
                where tenant_id = ? and consumer = ?
                """, rs -> rs.next()
                ? new Object[]{rs.getTimestamp("last_event_at"), rs.getObject("last_event_id", UUID.class)}
                : new Object[]{null, null}, tenantId, SearchIndexer.CONSUMER);

        Timestamp checkpointAt = (Timestamp) cursor[0];
        UUID checkpointId = (UUID) cursor[1];
        Long pending = jdbc.query("""
                select count(*) from integration.outbox_event
                where tenant_id = ?
                  and lower(aggregate_type) = any(?)
                  and (created_at, id) > (coalesce(?, '-infinity'::timestamptz),
                                          coalesce(?, '00000000-0000-0000-0000-000000000000'::uuid))
                """, ps -> {
            ps.setObject(1, tenantId);
            ps.setArray(2, textArray(ps, java.util.Arrays.stream(IndexedEntity.values())
                    .map(IndexedEntity::aggregateType).toList()));
            ps.setTimestamp(3, checkpointAt);
            ps.setObject(4, checkpointId);
        }, rs -> rs.next() ? rs.getLong(1) : 0L);

        assert head != null;
        return new IndexFreshness(
                (Long) head[0],
                instantOf((Timestamp) head[1]),
                instantOf((Timestamp) head[2]),
                instantOf(checkpointAt),
                pending == null ? 0L : pending);
    }

    @Override
    public Map<IndexedEntity, Long> documentCounts(UUID tenantId) {
        Map<IndexedEntity, Long> counts = new EnumMap<>(IndexedEntity.class);
        for (IndexedEntity entity : IndexedEntity.values()) counts.put(entity, 0L);
        jdbc.query("""
                select entity_type, count(*) as documents from search.search_document
                where tenant_id = ? group by entity_type
                """, rs -> {
            counts.put(IndexedEntity.valueOf(rs.getString("entity_type")), rs.getLong("documents"));
        }, tenantId);
        return counts;
    }

    @Override
    public List<UUID> storedIds(UUID tenantId, IndexedEntity entity, UUID afterId, int limit) {
        return jdbc.query("""
                select entity_id from search.search_document
                where tenant_id = ? and entity_type = ?
                  and entity_id > coalesce(?, '00000000-0000-0000-0000-000000000000'::uuid)
                order by entity_id
                limit ?
                """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, entity.name(), afterId, limit);
    }

    // ------------------------------------------------------------------ helpers

    private static Instant instantOf(Timestamp timestamp) {
        // '-infinity' arrives as a Timestamp far outside the useful range; treat it as absent.
        if (timestamp == null) return null;
        long millis = timestamp.getTime();
        if (millis <= Long.MIN_VALUE + 1000L || millis >= Long.MAX_VALUE - 1000L) return null;
        return timestamp.toInstant();
    }

    private static Array uuidArray(PreparedStatement ps, List<UUID> values) throws SQLException {
        return ps.getConnection().createArrayOf("uuid", values.toArray());
    }

    private static Array textArray(PreparedStatement ps, List<String> values) throws SQLException {
        return ps.getConnection().createArrayOf("text", values.toArray());
    }

    private String writeJson(Map<String, String> value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Search document field map is not serializable", e);
        }
    }

    private Map<String, String> readJson(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Map<String, String> parsed = json.readValue(value, new TypeReference<LinkedHashMap<String, String>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of();
        }
    }

    /** Exposed for the projector's batch helpers, which need the same array plumbing. */
    static List<UUID> distinct(List<UUID> values) {
        List<UUID> out = new ArrayList<>();
        for (UUID value : values) {
            if (value != null && !out.contains(value)) out.add(value);
        }
        return out;
    }
}

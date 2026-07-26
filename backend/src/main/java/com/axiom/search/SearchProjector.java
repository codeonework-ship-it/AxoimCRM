package com.axiom.search;

import com.axiom.security.SecurableObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns authoritative business records into {@link SearchDocument}s.
 *
 * <h2>Read-only, and deliberately outside the owning packages</h2>
 * This class reads {@code crm.account}, {@code crm.contact}, {@code crm.lead} and
 * {@code sales.opportunity} with plain SQL and never writes to them, never imports
 * their services and never depends on their DTOs. A search index that reached into
 * the domain packages would couple the release cycle of search to the release cycle
 * of every object it indexes, which is the coupling ADR-006's module boundaries
 * exist to prevent.
 *
 * <h2>Projecting from the table, not from the event payload</h2>
 * The indexer hands this class an id and gets back the record as it is <em>now</em>.
 * That choice does most of the work of ADR-003 compliance for free: redelivering an
 * event re-reads the same row and produces the same document (idempotent), and an
 * event that arrives late re-reads the current row rather than resurrecting the
 * state the payload described. The payload is used only to learn <em>which</em>
 * record changed.
 *
 * <h2>Sharing keys are a superset, on purpose</h2>
 * The keys written here are owner, the owner's role node and its ancestors (role
 * hierarchy roll-up), active manual shares, record team members and record
 * territories. They are <b>not</b> a materialized ACL and must never be read as
 * one — {@link com.axiom.security.AuthorizationService} evaluates criteria-based
 * sharing rules live, and no static key set can represent a predicate over a field
 * that may change a millisecond from now. The rule this class holds to is the one
 * stated in V240: the index filter must never be narrower than true access. Where
 * this class cannot represent a grant, {@link SearchService} widens the filter for
 * that entity type instead, and the authoritative recheck does the narrowing.
 */
@Component
@Transactional(readOnly = true)
public class SearchProjector {

    private final JdbcTemplate jdbc;

    public SearchProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Raw projection of one source record, before sharing keys are attached. */
    private record SourceRow(UUID id, String title, String subtitle, String body,
                             Map<String, String> securedFields, UUID ownerId, UUID parentAccountId,
                             Instant updatedAt) {}

    // ------------------------------------------------------------------ counting and paging

    /** Live source count, for reindex progress that is a real number rather than a guess. */
    public long count(UUID tenantId, IndexedEntity entity) {
        Long count = jdbc.queryForObject(
                "select count(*) from " + entity.qualifiedTable() + " t where t.tenant_id = ?"
                        + (entity.softDeleted() ? " and t.deleted_at is null" : ""),
                Long.class, tenantId);
        return count == null ? 0L : count;
    }

    /**
     * The next page of source ids, ordered by id.
     *
     * <p>Keyset paging on the primary key rather than OFFSET: it is what makes a
     * backfill resumable from a stored cursor after a restart, and it does not take
     * a lock or a snapshot that a concurrent business write would have to wait
     * behind. Data model §8 requires exactly that of the sharing recompute for the
     * same reason — "a full rebuild must never block business writes".
     */
    public List<UUID> idsAfter(UUID tenantId, IndexedEntity entity, UUID afterId, int limit) {
        return jdbc.query(
                "select t.id from " + entity.qualifiedTable() + " t where t.tenant_id = ?"
                        + (entity.softDeleted() ? " and t.deleted_at is null" : "")
                        + " and t.id > coalesce(?, '00000000-0000-0000-0000-000000000000'::uuid)"
                        + " order by t.id limit ?",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, afterId, limit);
    }

    /** Which of these ids still exist as live source records — the prune check. */
    public Set<UUID> existing(UUID tenantId, IndexedEntity entity, List<UUID> ids) {
        if (ids.isEmpty()) return Set.of();
        List<UUID> found = jdbc.query(
                "select t.id from " + entity.qualifiedTable() + " t where t.tenant_id = ?"
                        + (entity.softDeleted() ? " and t.deleted_at is null" : "")
                        + " and t.id = any(?)",
                ps -> {
                    ps.setObject(1, tenantId);
                    ps.setArray(2, uuidArray(ps, ids));
                }, (rs, i) -> rs.getObject(1, UUID.class));
        return new LinkedHashSet<>(found);
    }

    // ------------------------------------------------------------------ projection

    /**
     * Project the given ids. Ids with no live source record are simply absent from
     * the result — the caller treats that as "delete the document", which is how a
     * soft delete and a hard delete end up with the same effect on the index.
     */
    public List<SearchDocument> project(UUID tenantId, IndexedEntity entity, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        List<SourceRow> rows = readSource(tenantId, entity, ids);
        if (rows.isEmpty()) return List.of();

        Map<UUID, Set<UUID>> keys = sharingKeys(tenantId, entity, rows);
        List<SearchDocument> documents = new ArrayList<>(rows.size());
        for (SourceRow row : rows) {
            documents.add(new SearchDocument(
                    entity, row.id(), row.title(), row.subtitle(), row.body(), row.securedFields(),
                    row.ownerId(), List.copyOf(keys.getOrDefault(row.id(), Set.of())),
                    entity.urlPath(row.id()), row.updatedAt()));
        }
        return documents;
    }

    private List<SourceRow> readSource(UUID tenantId, IndexedEntity entity, List<UUID> ids) {
        String sql = switch (entity) {
            case ACCOUNT -> """
                    select a.id, a.name as title, a.industry as subtitle,
                           concat_ws(' ', a.legal_name, a.account_number, a.website, a.email_domain,
                                     a.phone, a.segment, a.territory, a.business_unit, a.status,
                                     a.record_type) as body,
                           null::text as secured_email, null::text as secured_status,
                           a.owner_id, null::uuid as parent_account_id, a.updated_at
                    from crm.account a
                    where a.tenant_id = ? and a.deleted_at is null and a.id = any(?)
                    """;
            case CONTACT -> """
                    select c.id, concat_ws(' ', c.first_name, c.last_name) as title,
                           c.title as subtitle,
                           concat_ws(' ', c.department, c.seniority, c.phone, c.mobile, c.status,
                                     acc.name) as body,
                           c.email as secured_email, null::text as secured_status,
                           coalesce(acc.owner_id, c.owner_id) as owner_id,
                           c.account_id as parent_account_id, c.updated_at
                    from crm.contact c
                    left join crm.account acc on acc.tenant_id = c.tenant_id and acc.id = c.account_id
                    where c.tenant_id = ? and c.deleted_at is null and c.id = any(?)
                    """;
            case LEAD -> """
                    select l.id, concat_ws(' ', l.first_name, l.last_name) as title,
                           l.company as subtitle,
                           concat_ws(' ', l.title, l.phone, l.rating, l.source, l.campaign_code,
                                     l.territory, l.segment, l.product_interest) as body,
                           l.email as secured_email, l.status as secured_status,
                           l.owner_id, null::uuid as parent_account_id, l.updated_at
                    from crm.lead l
                    where l.tenant_id = ? and l.deleted_at is null and l.id = any(?)
                    """;
            case OPPORTUNITY -> """
                    select o.id, o.name as title, acc.name as subtitle,
                           concat_ws(' ', o.next_step, o.forecast_category, o.record_type,
                                     st.name) as body,
                           null::text as secured_email, null::text as secured_status,
                           o.owner_id, o.account_id as parent_account_id, o.updated_at
                    from sales.opportunity o
                    left join crm.account acc on acc.tenant_id = o.tenant_id and acc.id = o.account_id
                    left join crm.pipeline_stage st on st.tenant_id = o.tenant_id and st.id = o.stage_id
                    where o.tenant_id = ? and o.id = any(?)
                    """;
        };
        return jdbc.query(sql, ps -> {
            ps.setObject(1, tenantId);
            ps.setArray(2, uuidArray(ps, ids));
        }, (rs, i) -> {
            Map<String, String> secured = new LinkedHashMap<>();
            putIfPresent(secured, entity, "email", rs.getString("secured_email"));
            putIfPresent(secured, entity, "status", rs.getString("secured_status"));
            String title = rs.getString("title");
            return new SourceRow(
                    rs.getObject("id", UUID.class),
                    title == null || title.isBlank() ? "(untitled)" : title,
                    blankToNull(rs.getString("subtitle")),
                    blankToNull(rs.getString("body")),
                    secured,
                    rs.getObject("owner_id", UUID.class),
                    rs.getObject("parent_account_id", UUID.class),
                    rs.getTimestamp("updated_at").toInstant());
        });
    }

    private static void putIfPresent(Map<String, String> target, IndexedEntity entity,
                                     String field, String value) {
        if (value != null && !value.isBlank() && entity.securedFields().contains(field)) {
            target.put(field, value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ------------------------------------------------------------------ sharing keys

    private Map<UUID, Set<UUID>> sharingKeys(UUID tenantId, IndexedEntity entity, List<SourceRow> rows) {
        Map<UUID, Set<UUID>> keys = new HashMap<>();
        List<UUID> recordIds = new ArrayList<>();
        List<UUID> ownerIds = new ArrayList<>();
        List<UUID> parentIds = new ArrayList<>();
        for (SourceRow row : rows) {
            recordIds.add(row.id());
            keys.put(row.id(), new LinkedHashSet<>());
            if (row.ownerId() != null) ownerIds.add(row.ownerId());
            if (row.parentAccountId() != null) parentIds.add(row.parentAccountId());
        }

        Map<UUID, Set<UUID>> ownRecordKeys = recordKeys(tenantId, entity.securable(), recordIds);
        Map<UUID, Set<UUID>> roleKeys = ownerRoleKeys(tenantId, PostgresSearchIndex.distinct(ownerIds));

        // A contact has no owner column: its visibility follows its account
        // (SecurableObject.CONTACT.parent()). So the account's keys are the contact's
        // keys, and the contact's own team/share rows are added on top.
        Map<UUID, Set<UUID>> parentRecordKeys = entity == IndexedEntity.CONTACT
                ? recordKeys(tenantId, SecurableObject.ACCOUNT, PostgresSearchIndex.distinct(parentIds))
                : Map.of();

        for (SourceRow row : rows) {
            Set<UUID> target = keys.get(row.id());
            if (row.ownerId() != null) {
                target.add(row.ownerId());
                target.addAll(roleKeys.getOrDefault(row.ownerId(), Set.of()));
            }
            target.addAll(ownRecordKeys.getOrDefault(row.id(), Set.of()));
            if (row.parentAccountId() != null) {
                target.addAll(parentRecordKeys.getOrDefault(row.parentAccountId(), Set.of()));
            }
            target.remove(null);
        }
        return keys;
    }

    /** Manual shares, record teams and record territories currently in force. */
    private Map<UUID, Set<UUID>> recordKeys(UUID tenantId, SecurableObject object, List<UUID> recordIds) {
        if (recordIds.isEmpty()) return Map.of();
        Map<UUID, Set<UUID>> keys = new HashMap<>();
        jdbc.query("""
                select record_id, key from (
                  select rs.record_id, rs.grantee_user_id as key
                    from security.record_share rs
                   where rs.tenant_id = ? and rs.object_type = ? and rs.record_id = any(?)
                     and rs.revoked_at is null and (rs.expires_at is null or rs.expires_at > now())
                  union
                  select tm.record_id, tm.user_id
                    from security.record_team_member tm
                   where tm.tenant_id = ? and tm.object_type = ? and tm.record_id = any(?)
                     and (tm.expires_at is null or tm.expires_at > now())
                  union
                  select rt.record_id, rt.territory_id
                    from security.record_territory rt
                   where rt.tenant_id = ? and rt.object_type = ? and rt.record_id = any(?)
                ) k
                """, ps -> {
            Array ids = uuidArray(ps, recordIds);
            ps.setObject(1, tenantId); ps.setString(2, object.name()); ps.setArray(3, ids);
            ps.setObject(4, tenantId); ps.setString(5, object.name()); ps.setArray(6, ids);
            ps.setObject(7, tenantId); ps.setString(8, object.name()); ps.setArray(9, ids);
        }, rs -> {
            UUID recordId = rs.getObject("record_id", UUID.class);
            UUID key = rs.getObject("key", UUID.class);
            if (key != null) keys.computeIfAbsent(recordId, x -> new LinkedHashSet<>()).add(key);
        });
        return keys;
    }

    /**
     * The owner's role node and every ancestor of it.
     *
     * <p>This is what lets the index pre-filter honour the role-hierarchy roll-up:
     * a manager's own role node id appears in the keys of every record owned below
     * them, so their query narrows to their branch instead of degenerating to a full
     * scan. It is still only a narrowing — whether the roll-up actually applies
     * depends on the org-wide default, which the recheck evaluates.
     */
    private Map<UUID, Set<UUID>> ownerRoleKeys(UUID tenantId, List<UUID> ownerIds) {
        if (ownerIds.isEmpty()) return Map.of();
        Map<UUID, Set<UUID>> keys = new HashMap<>();
        jdbc.query("""
                select ura.user_id, ancestor.id as role_node_id
                from security.user_role_assignment ura
                join security.role_node own
                  on own.tenant_id = ura.tenant_id and own.id = ura.role_node_id and own.active
                join security.role_node ancestor
                  on ancestor.tenant_id = ura.tenant_id and ancestor.active
                 and own.path like ancestor.path || '%'
                where ura.tenant_id = ? and ura.user_id = any(?)
                  and (ura.expires_at is null or ura.expires_at > now())
                """, ps -> {
            ps.setObject(1, tenantId);
            ps.setArray(2, uuidArray(ps, ownerIds));
        }, rs -> {
            UUID userId = rs.getObject("user_id", UUID.class);
            UUID roleNodeId = rs.getObject("role_node_id", UUID.class);
            if (roleNodeId != null) keys.computeIfAbsent(userId, x -> new LinkedHashSet<>()).add(roleNodeId);
        });
        return keys;
    }

    private static Array uuidArray(PreparedStatement ps, List<UUID> values) throws SQLException {
        return ps.getConnection().createArrayOf("uuid", values.toArray());
    }
}

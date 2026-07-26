package com.axiom.analytics;

import com.axiom.common.NotFoundException;
import com.axiom.security.AccessContext;
import com.axiom.security.AuthorizationService;
import com.axiom.security.SecurableObject;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Drill-through (FR-RPT-006, ADR-008 decision 4, doc 14 §5).
 *
 * <h2>The rule this class exists to hold</h2>
 * <i>"The projection aggregates; it is never the authority on what a user may see.
 * Access changes faster than a projection updates, so materialized permissions in
 * the read model would eventually show someone a record they were removed from an
 * hour ago. Re-checking against the authoritative store costs latency on one page
 * of results and is the only version that is correct."</i>
 *
 * <p>So drilling into an aggregate happens in two steps that must not be collapsed:
 * <ol>
 *   <li>the read model says which record ids contributed to the figure;</li>
 *   <li>the <b>authoritative</b> tables, under a predicate evaluated <b>now</b>,
 *       say which of those this caller may actually see — and the record data
 *       returned comes from those tables, not from the projection.</li>
 * </ol>
 *
 * <p>Step 2 is not a formality. Between the projection run and the drill-through
 * a share can be revoked, a role reassigned, a territory reshuffled or a sharing
 * rule's criteria field edited. Every one of those changes the answer, and none of
 * them touches the fact table. {@code droppedByRecheck} in the response is the
 * count of exactly that happening; it is reported rather than padded over, because
 * back-filling the page from the projection would reintroduce the read model as an
 * authority on visibility one row further down.
 *
 * <h2>Why the single-record path throws 404 and not 403</h2>
 * {@link AuthorizationService#requireRead} answers "no record with that id is
 * available to you" for both "does not exist" and "exists but not for you". A 403
 * on a record the caller may not read confirms that it exists, which is the leak
 * the drill-through re-check is there to prevent in the first place.
 */
@Service
public class DrillThroughService {

    /** How many contributing records one drill-through page may return. */
    private static final int MAX_PAGE = 200;

    public record DrillRow(Map<String, Object> record, String urlPath) {}

    public record DrillResult(String dataset, int projectedCandidates, int returned,
                              int droppedByRecheck, List<DrillRow> rows,
                              List<String> withheldFields, String note,
                              ProjectionStatusService.Staleness staleness) {}

    private final JdbcTemplate jdbc;
    private final ReportQueryService queries;
    private final AuthorizationService authorization;
    private final ProjectionStatusService status;

    public DrillThroughService(JdbcTemplate jdbc, ReportQueryService queries,
                               AuthorizationService authorization, ProjectionStatusService status) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.authorization = authorization;
        this.status = status;
    }

    // ------------------------------------------------------------------ aggregate -> records

    /**
     * The contributing records behind an aggregate.
     *
     * <p>The request is the same {@link ReportQueryService.ReportRequest} that
     * produced the figure, with whatever the clicked cell adds as extra filters.
     * Reusing the identical predicate builder is deliberate: a drill-through that
     * assembled its own filters would be a second implementation of the same
     * question and could quietly disagree with the number it drilled from.
     */
    @Transactional(readOnly = true)
    public DrillResult records(ReportQueryService.ReportRequest request, Integer limit) {
        UUID tenantId = TenantContext.get().tenantId();
        AnalyticsDataset dataset = AnalyticsDataset.of(request == null ? null : request.dataset());
        int page = limit == null ? 50 : Math.min(Math.max(limit, 1), MAX_PAGE);

        Set<String> withheld = queries.withheldFields(dataset);
        ReportQueryService.Where where = queries.buildWhere(tenantId, dataset, request, withheld);

        // Step 1 — the read model names the candidates.
        List<UUID> candidates = jdbc.query(
                "select f." + dataset.idColumn() + " from " + dataset.factTable() + " f where "
                        + where.sql() + " order by f." + dataset.idColumn() + " limit " + (page + 1),
                where.args().toArray(), (rs, i) -> rs.getObject(1, UUID.class));
        boolean more = candidates.size() > page;
        if (more) candidates = candidates.subList(0, page);

        // Step 2 — the authoritative store decides, now.
        List<Map<String, Object>> permitted = recheckAndRead(dataset, candidates, withheld);

        List<DrillRow> rows = new ArrayList<>(permitted.size());
        for (Map<String, Object> record : permitted) {
            rows.add(new DrillRow(record, dataset.routePrefix() + record.get("id")));
        }

        String note = "Records read from the authoritative store with a permission check taken now, "
                + "not from the projection (ADR-008 decision 4)."
                + (more ? " More contributing records exist than this page shows." : "");

        return new DrillResult(dataset.name(), candidates.size(), rows.size(),
                candidates.size() - rows.size(), rows, List.copyOf(withheld), note,
                status.stalenessFor(tenantId, dataset));
    }

    // ------------------------------------------------------------------ one record

    /**
     * One record, re-checked. This is the call the record-page link makes, and the
     * one the {@code SEC-} acceptance case exercises: a user who could see the
     * aggregate a moment ago but has since lost access to a contributing record
     * gets nothing here, not a stale copy from the read model.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> record(String datasetName, UUID recordId) {
        AnalyticsDataset dataset = AnalyticsDataset.of(datasetName);
        SecurableObject object = dataset.securable().orElseThrow(() ->
                new NotFoundException("Drill-through is not available for " + dataset.name() + " records"));

        // Throws before a single column is read. The projection is not consulted at
        // all on this path — it cannot be, or it would be the authority.
        authorization.requireRead(object, recordId);

        Set<String> withheld = queries.withheldFields(dataset);
        List<Map<String, Object>> found = readAuthoritative(dataset, object, List.of(recordId), withheld);
        if (found.isEmpty()) throw new NotFoundException("No record with that id is available to you");
        Map<String, Object> record = new LinkedHashMap<>(found.get(0));
        record.put("_source", "AUTHORITATIVE");
        record.put("_urlPath", dataset.routePrefix() + recordId);
        if (!withheld.isEmpty()) record.put("_withheldFields", List.copyOf(withheld));
        return record;
    }

    // ------------------------------------------------------------------ internals

    private List<Map<String, Object>> recheckAndRead(AnalyticsDataset dataset, List<UUID> ids,
                                                     Set<String> withheld) {
        if (ids.isEmpty()) return List.of();
        SecurableObject object = dataset.securable().orElse(null);
        if (object == null) {
            // No securable object: there is no authoritative predicate to re-evaluate,
            // so nothing may be drilled to. Refusing is the conservative answer;
            // returning projected rows here would be exactly the design ADR-008 rejects.
            return List.of();
        }
        return readAuthoritative(dataset, object, ids, withheld);
    }

    /**
     * Read the records from the authoritative table under the live read predicate,
     * one query for the page rather than one per row.
     */
    private List<Map<String, Object>> readAuthoritative(AnalyticsDataset dataset, SecurableObject object,
                                                        List<UUID> ids, Set<String> withheld) {
        AuthorizationService.RecordPredicate predicate = authorization.visibleRecordPredicate(object, "t");
        if (predicate.deniesEverything()) return List.of();

        List<String> columns = authoritativeColumns(dataset, withheld);
        String sql = "select " + String.join(", ", columns) + " from " + object.qualifiedTable() + " t"
                + " where t.tenant_id = ? and t.id = any(?)"
                + (object.softDeleted() ? " and t.deleted_at is null" : "")
                + (predicate.allowsEverything() ? "" : " and (" + predicate.sql() + ")");

        return jdbc.query(sql, ps -> {
            ps.setObject(1, TenantContext.get().tenantId());
            ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
            int position = 3;
            if (!predicate.allowsEverything()) {
                for (Object arg : predicate.args()) ps.setObject(position++, arg);
            }
        }, (rs, i) -> {
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 1; c <= meta.getColumnCount(); c++) {
                Object value = rs.getObject(c);
                if (value instanceof java.sql.Timestamp ts) value = ts.toInstant().toString();
                else if (value instanceof java.sql.Date d) value = d.toLocalDate().toString();
                else if (value instanceof UUID id) value = id.toString();
                row.put(meta.getColumnLabel(c), value);
            }
            return row;
        });
    }

    /**
     * The authoritative columns a drill-through returns: the registered securable
     * fields of the object, minus the ones this caller's profile hides.
     *
     * <p>Withheld fields are absent from the projection list, so the value never
     * leaves the database — as opposed to being fetched and nulled, which puts it
     * in a heap dump and a query log for no benefit.
     */
    private List<String> authoritativeColumns(AnalyticsDataset dataset, Set<String> withheld) {
        SecurableObject object = dataset.securable().orElseThrow();
        AccessContext ctx = authorization.context();
        Set<String> hidden = new LinkedHashSet<>(ctx.unreadable(object));
        hidden.addAll(withheld);
        List<String> columns = new ArrayList<>();
        for (String field : object.fieldNames()) {
            if (hidden.contains(field) && !object.alwaysReadable(field)) continue;
            columns.add("t." + object.column(field) + " as " + '"' + field + '"');
        }
        if (columns.isEmpty()) columns.add("t.id as \"id\"");
        return columns;
    }
}

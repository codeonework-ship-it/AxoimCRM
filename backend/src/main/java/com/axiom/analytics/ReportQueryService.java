package com.axiom.analytics;

import com.axiom.security.AccessContext;
import com.axiom.security.AuthorizationService;
import com.axiom.security.SecurableObject;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The report query engine (FR-RPT-001 … FR-RPT-003, FR-RPT-005, FR-RPT-011).
 *
 * <h2>Everything it reads is the read model</h2>
 * Every {@code from} clause built here names a fact table. No report query touches
 * an OLTP table (ADR-008 decision 3) with exactly one exception, which is the
 * whole point of decision 4: {@link ReportAccessScope} semi-joins into the
 * authoritative table to decide <em>visibility</em>, because the projection is
 * never the authority on what a user may see.
 *
 * <h2>User-authored SQL, without user-authored SQL</h2>
 * Filters, groupings and summaries are structure, not text. Every identifier is
 * resolved through {@link AnalyticsDataset}, every operator through a closed enum,
 * and every value is bound as a parameter typed from the field's declared kind. A
 * field name that is not in the registry throws, naming the fields that are. There
 * is no path from a request body to a SQL identifier that does not pass through
 * that registry.
 *
 * <h2>Access is applied twice, and the second time is what counts</h2>
 * Results are narrowed to the viewer's record access (FR-RPT-005) and columns for
 * fields their profile hides are <em>absent</em> from the response rather than
 * null (FR-SEC-007 — "absence and emptiness must not be conflated"). A filter on a
 * hidden field is refused outright: allowing one would let a caller binary-search
 * a value they are not permitted to read, which leaks it just as surely as
 * displaying it. Drill-through, where the records themselves are returned, is a
 * separate class with its own fresh check ({@link DrillThroughService}).
 */
@Service
public class ReportQueryService {

    // ------------------------------------------------------------------ request

    public record Filter(@Size(max = 64) String field, @Size(max = 24) String operator,
                         List<String> values) {}

    public record Summary(@Size(max = 64) String field, @Size(max = 24) String function,
                          @Size(max = 120) String label) {}

    /**
     * Cross-object "with" / "without" related-record semantics (FR-RPT-002).
     * "Accounts without activity this quarter" is a first-class query here rather
     * than an export-and-VLOOKUP exercise.
     */
    public record RelatedFilter(@Size(max = 32) String related, @Size(max = 16) String mode,
                                Integer withinDays) {}

    public record ReportRequest(@Size(max = 32) String dataset,
                                @Size(max = 16) String format,
                                List<String> columns,
                                List<Filter> filters,
                                List<String> groupBy,
                                @Size(max = 80) String columnGroup,
                                List<Summary> summaries,
                                @Size(max = 80) String sortBy,
                                @Size(max = 8) String sortDirection,
                                Integer limit,
                                RelatedFilter related) {}

    // ------------------------------------------------------------------ response

    public record Column(String field, String label, String kind, String role) {}

    public record ReportResult(String dataset, String format, List<Column> columns,
                               List<Map<String, Object>> rows, Map<String, Object> grandTotals,
                               int rowCount, boolean truncated, int rowLimit, String guidance,
                               boolean accessRestricted, List<String> withheldFields,
                               String drillField, long elapsedMs,
                               ProjectionStatusService.Staleness staleness) {}

    private enum Format { TABULAR, SUMMARY, MATRIX }

    private final JdbcTemplate jdbc;
    private final ReportAccessScope accessScope;
    private final QueryGuardrails guardrails;
    private final ProjectionStatusService status;
    private final AuthorizationService authorization;

    public ReportQueryService(JdbcTemplate jdbc, ReportAccessScope accessScope, QueryGuardrails guardrails,
                              ProjectionStatusService status, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.accessScope = accessScope;
        this.guardrails = guardrails;
        this.status = status;
        this.authorization = authorization;
    }

    // ------------------------------------------------------------------ entry point

    @Transactional
    public ReportResult run(ReportRequest request) {
        UUID tenantId = TenantContext.get().tenantId();
        AnalyticsDataset dataset = AnalyticsDataset.of(request == null ? null : request.dataset());
        Format format = parseFormat(request == null ? null : request.format());
        int limit = guardrails.effectiveRowLimit(request == null ? null : request.limit());
        long started = System.nanoTime();

        try (QueryGuardrails.Permit ignored = guardrails.acquire(tenantId)) {
            // SET LOCAL: reverts with this transaction rather than leaking the ceiling
            // onto the next caller of a pooled connection.
            jdbc.execute("set local statement_timeout = " + guardrails.statementTimeoutMs());

            Set<String> withheld = withheldFields(dataset);
            Where where = buildWhere(tenantId, dataset, request, withheld);

            ReportResult result = switch (format) {
                case TABULAR -> tabular(dataset, request, where, limit, withheld);
                case SUMMARY -> summary(dataset, request, where, limit, withheld);
                case MATRIX -> matrix(dataset, request, where, limit, withheld);
            };

            long elapsed = Math.round((System.nanoTime() - started) / 1_000_000.0);
            ReportResult finished = new ReportResult(result.dataset(), result.format(), result.columns(),
                    result.rows(), result.grandTotals(), result.rowCount(), result.truncated(),
                    limit, result.guidance(), where.scope().restricted(), List.copyOf(withheld),
                    result.drillField(), elapsed,
                    status.stalenessFor(tenantId, stalenessInputs(dataset, request)));

            logExecution(tenantId, dataset, format, finished,
                    finished.truncated() ? "TRUNCATED" : "OK", finished.guidance());
            return finished;
        } catch (DataAccessException ex) {
            long elapsed = Math.round((System.nanoTime() - started) / 1_000_000.0);
            if (isStatementTimeout(ex)) {
                String message = "This report exceeded the "
                        + (guardrails.statementTimeoutMs() / 1000) + "-second query limit and was stopped"
                        + " so it could not slow the rest of the product. Narrow it and run it again:"
                        + " add a date range, filter by owner or stage, or group the report instead of"
                        + " listing every row.";
                logTimeout(tenantId, dataset, format, elapsed, message);
                throw new com.axiom.common.ConflictException(message);
            }
            throw ex;
        }
    }

    // ------------------------------------------------------------------ formats

    private ReportResult tabular(AnalyticsDataset dataset, ReportRequest request, Where where,
                                 int limit, Set<String> withheld) {
        List<AnalyticsDataset.Field> selected = selectedColumns(dataset, request, withheld);
        String order = orderBy(dataset, request, selected, withheld);

        StringBuilder sql = new StringBuilder("select ");
        for (int i = 0; i < selected.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("f.").append(selected.get(i).column())
                    .append(" as ").append(quoted(selected.get(i).apiName()));
        }
        sql.append(" from ").append(dataset.factTable()).append(" f where ").append(where.sql())
                .append(order).append(" limit ").append(limit + 1);

        List<Map<String, Object>> rows = jdbc.query(sql.toString(), where.args().toArray(), rowMapper());
        boolean truncated = rows.size() > limit;
        if (truncated) rows = rows.subList(0, limit);

        List<Column> columns = new ArrayList<>();
        for (AnalyticsDataset.Field field : selected) {
            columns.add(new Column(field.apiName(), humanize(field.apiName()), field.kind().name(), "DETAIL"));
        }

        // Totals over the WHOLE matching set, not over the page — a truncated page's
        // own sum would look like a total and be wrong by an unknown amount.
        Map<String, Object> totals = grandTotals(dataset, request, where, selected, withheld);

        return new ReportResult(dataset.name(), Format.TABULAR.name(), columns, rows, totals,
                rows.size(), truncated, limit,
                truncated ? guardrails.truncationGuidance(dataset, limit) : null,
                false, List.of(), idApiName(dataset), 0, null);
    }

    private ReportResult summary(AnalyticsDataset dataset, ReportRequest request, Where where,
                                 int limit, Set<String> withheld) {
        List<Group> groups = groups(dataset, request == null ? null : request.groupBy(), withheld, 1, 3);
        List<Agg> aggs = aggregates(dataset, request, withheld);

        StringBuilder sql = new StringBuilder("select ");
        for (Group group : groups) sql.append(group.expression()).append(" as ").append(quoted(group.alias())).append(", ");
        sql.append("count(*) as \"recordCount\"");
        for (Agg agg : aggs) sql.append(", ").append(agg.expression()).append(" as ").append(quoted(agg.alias()));
        sql.append(" from ").append(dataset.factTable()).append(" f where ").append(where.sql())
                .append(" group by ");
        for (int i = 0; i < groups.size(); i++) sql.append(i > 0 ? ", " : "").append(i + 1);
        sql.append(" order by ");
        for (int i = 0; i < groups.size(); i++) sql.append(i > 0 ? ", " : "").append(i + 1);
        sql.append(" limit ").append(limit + 1);

        List<Map<String, Object>> rows = jdbc.query(sql.toString(), where.args().toArray(), rowMapper());
        boolean truncated = rows.size() > limit;
        if (truncated) rows = rows.subList(0, limit);

        List<Column> columns = new ArrayList<>();
        for (Group group : groups) columns.add(new Column(group.alias(), group.label(), "TEXT", "GROUP"));
        columns.add(new Column("recordCount", "Records", "NUMBER", "MEASURE"));
        for (Agg agg : aggs) columns.add(new Column(agg.alias(), agg.label(), agg.kind(), "MEASURE"));

        Map<String, Object> totals = grandTotals(dataset, request, where, List.of(), withheld);

        return new ReportResult(dataset.name(), Format.SUMMARY.name(), columns, rows, totals,
                rows.size(), truncated, limit,
                truncated ? guardrails.truncationGuidance(dataset, limit) : null,
                false, List.of(), groups.get(0).alias(), 0, null);
    }

    /**
     * Matrix format: one grouping down the rows, one across the columns, one
     * measure in the cells. Pivoted in Java rather than with {@code crosstab}
     * because the column set is data-dependent and a SQL pivot would need the
     * column values interpolated — which is precisely the thing this class does
     * not do with values that came from a user.
     */
    private ReportResult matrix(AnalyticsDataset dataset, ReportRequest request, Where where,
                                int limit, Set<String> withheld) {
        List<Group> rowGroups = groups(dataset, request == null ? null : request.groupBy(), withheld, 1, 1);
        Group columnGroup = group(dataset, request == null ? null : request.columnGroup(), withheld);
        List<Agg> aggs = aggregates(dataset, request, withheld);
        Agg measure = aggs.isEmpty() ? Agg.count() : aggs.get(0);

        String sql = "select " + rowGroups.get(0).expression() + " as row_key, "
                + columnGroup.expression() + " as col_key, "
                + measure.expression() + " as measure"
                + " from " + dataset.factTable() + " f where " + where.sql()
                + " group by 1, 2 order by 1, 2 limit " + (limit * 4 + 1);

        List<Object[]> cells = jdbc.query(sql, where.args().toArray(), (rs, i) ->
                new Object[]{rs.getString("row_key"), rs.getString("col_key"), rs.getObject("measure")});

        Set<String> columnKeys = new LinkedHashSet<>();
        Map<String, Map<String, Object>> pivot = new LinkedHashMap<>();
        for (Object[] cell : cells) {
            String rowKey = cell[0] == null ? "(none)" : (String) cell[0];
            String colKey = cell[1] == null ? "(none)" : (String) cell[1];
            columnKeys.add(colKey);
            pivot.computeIfAbsent(rowKey, k -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put(rowGroups.get(0).alias(), k);
                return created;
            }).put(colKey, cell[2]);
        }

        List<Column> columns = new ArrayList<>();
        columns.add(new Column(rowGroups.get(0).alias(), rowGroups.get(0).label(), "TEXT", "GROUP"));
        for (String key : columnKeys) columns.add(new Column(key, key, measure.kind(), "MEASURE"));

        List<Map<String, Object>> rows = new ArrayList<>(pivot.values());
        boolean truncated = rows.size() > limit;
        if (truncated) rows = rows.subList(0, limit);

        Map<String, Object> totals = grandTotals(dataset, request, where, List.of(), withheld);
        return new ReportResult(dataset.name(), Format.MATRIX.name(), columns, rows, totals,
                rows.size(), truncated, limit,
                truncated ? guardrails.truncationGuidance(dataset, limit) : null,
                false, List.of(), rowGroups.get(0).alias(), 0, null);
    }

    // ------------------------------------------------------------------ where

    record Where(String sql, List<Object> args, ReportAccessScope.Scope scope) {}

    /**
     * Builds the shared predicate: tenant, access scope, user filters, and the
     * optional cross-object "with"/"without" clause. Package-private so the
     * drill-through path can reuse the identical predicate — a drill-through that
     * built its own filters would be a second implementation of the same question
     * and could disagree with the aggregate it drilled from.
     */
    Where buildWhere(UUID tenantId, AnalyticsDataset dataset, ReportRequest request, Set<String> withheld) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("f.tenant_id = ?");
        args.add(tenantId);

        ReportAccessScope.Scope scope = accessScope.scopeFor(dataset, "f");
        if (scope.hasClause()) {
            sql.append(" and ").append(scope.sql());
            args.addAll(scope.args());
        }

        List<Filter> filters = request == null || request.filters() == null ? List.of() : request.filters();
        for (Filter filter : filters) {
            if (filter == null || filter.field() == null) continue;
            AnalyticsDataset.Field field = dataset.field(filter.field());
            if (withheld.contains(field.apiName())) {
                // Filtering on a field the caller may not read would let them
                // binary-search its value. Refused, and named, rather than ignored.
                throw new com.axiom.common.ForbiddenException(
                        "Your profile does not permit reading '" + field.apiName()
                                + "', so a report cannot be filtered on it.");
            }
            Predicate predicate = predicate(field, filter);
            sql.append(" and ").append(predicate.sql());
            args.addAll(predicate.args());
        }

        RelatedFilter related = request == null ? null : request.related();
        if (related != null && related.related() != null && !related.related().isBlank()) {
            Predicate predicate = relatedPredicate(dataset, related);
            sql.append(" and ").append(predicate.sql());
            args.addAll(predicate.args());
        }

        return new Where(sql.toString(), List.copyOf(args), scope);
    }

    private record Predicate(String sql, List<Object> args) {}

    /**
     * FR-RPT-002 "with" / "without" related-record semantics.
     *
     * <p>Both directions run against the read model, so "accounts without activity
     * in 90 days" is an anti-join over two fact tables rather than a join back into
     * the transactional store.
     */
    private Predicate relatedPredicate(AnalyticsDataset dataset, RelatedFilter related) {
        AnalyticsDataset target = AnalyticsDataset.of(related.related());
        boolean without = "WITHOUT".equalsIgnoreCase(related.mode());
        String join = switch (dataset) {
            case ACCOUNT -> switch (target) {
                case ACTIVITY -> "r.account_id = f.account_id";
                case OPPORTUNITY -> "r.account_id = f.account_id";
                default -> throw new IllegalArgumentException(
                        "ACCOUNT reports can only use a related filter on ACTIVITY or OPPORTUNITY");
            };
            case OPPORTUNITY -> switch (target) {
                case ACTIVITY -> "r.account_id = f.account_id";
                default -> throw new IllegalArgumentException(
                        "OPPORTUNITY reports can only use a related filter on ACTIVITY");
            };
            default -> throw new IllegalArgumentException(
                    "Related-record filters are available on ACCOUNT and OPPORTUNITY reports");
        };

        List<Object> args = new ArrayList<>();
        StringBuilder inner = new StringBuilder("select 1 from ").append(target.factTable())
                .append(" r where r.tenant_id = f.tenant_id and ").append(join);
        if (related.withinDays() != null && related.withinDays() > 0) {
            String dateColumn = target == AnalyticsDataset.ACTIVITY ? "occurred_on" : "close_date";
            inner.append(" and r.").append(dateColumn).append(" >= current_date - ?::int");
            args.add(related.withinDays());
        }
        return new Predicate((without ? "not exists (" : "exists (") + inner + ")", args);
    }

    // ------------------------------------------------------------------ filters

    private Predicate predicate(AnalyticsDataset.Field field, Filter filter) {
        String column = "f." + field.column();
        String operator = filter.operator() == null ? "EQ" : filter.operator().trim().toUpperCase(Locale.ROOT);
        List<String> values = filter.values() == null ? List.of() : filter.values();

        return switch (operator) {
            case "EQ" -> new Predicate(column + " = ?", List.of(value(field, first(values, operator))));
            case "NE" -> new Predicate("(" + column + " is distinct from ?)",
                    List.of(value(field, first(values, operator))));
            case "GT" -> new Predicate(column + " > ?", List.of(value(field, first(values, operator))));
            case "GTE" -> new Predicate(column + " >= ?", List.of(value(field, first(values, operator))));
            case "LT" -> new Predicate(column + " < ?", List.of(value(field, first(values, operator))));
            case "LTE" -> new Predicate(column + " <= ?", List.of(value(field, first(values, operator))));
            case "CONTAINS" -> {
                requireText(field, operator);
                yield new Predicate(column + " ilike ?", List.of("%" + first(values, operator) + "%"));
            }
            case "STARTS_WITH" -> {
                requireText(field, operator);
                yield new Predicate(column + " ilike ?", List.of(first(values, operator) + "%"));
            }
            case "IN", "NOT_IN" -> {
                if (values.isEmpty()) throw new IllegalArgumentException(operator + " needs at least one value");
                List<Object> bound = values.stream().map(v -> value(field, v)).toList();
                String placeholders = String.join(", ", java.util.Collections.nCopies(bound.size(), "?"));
                yield new Predicate(column + ("IN".equals(operator) ? " in (" : " not in (") + placeholders + ")",
                        bound);
            }
            case "IS_NULL" -> new Predicate(column + " is null", List.of());
            case "NOT_NULL" -> new Predicate(column + " is not null", List.of());
            case "BETWEEN" -> {
                if (values.size() < 2) throw new IllegalArgumentException("BETWEEN needs two values");
                yield new Predicate(column + " between ? and ?",
                        List.of(value(field, values.get(0)), value(field, values.get(1))));
            }
            case "LAST_N_DAYS" -> {
                requireDateLike(field, operator);
                yield new Predicate(column + " >= current_date - ?::int",
                        List.of(Integer.parseInt(first(values, operator))));
            }
            case "NEXT_N_DAYS" -> {
                requireDateLike(field, operator);
                yield new Predicate(column + " between current_date and current_date + ?::int",
                        List.of(Integer.parseInt(first(values, operator))));
            }
            default -> throw new IllegalArgumentException("Unsupported filter operator: " + operator
                    + ". Supported: EQ, NE, GT, GTE, LT, LTE, CONTAINS, STARTS_WITH, IN, NOT_IN,"
                    + " IS_NULL, NOT_NULL, BETWEEN, LAST_N_DAYS, NEXT_N_DAYS");
        };
    }

    /** Bind values typed from the field's declared kind — never as free text. */
    private static Object value(AnalyticsDataset.Field field, String raw) {
        if (raw == null) return null;
        try {
            return switch (field.kind()) {
                case ID -> UUID.fromString(raw.trim());
                case NUMBER, MONEY -> new BigDecimal(raw.trim());
                case DATE -> LocalDate.parse(raw.trim());
                case TIMESTAMP -> OffsetDateTime.parse(raw.trim());
                case BOOLEAN -> Boolean.parseBoolean(raw.trim());
                case TEXT -> raw;
            };
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid "
                    + field.kind().name().toLowerCase(Locale.ROOT) + " value for " + field.apiName());
        }
    }

    private static String first(List<String> values, String operator) {
        if (values.isEmpty()) throw new IllegalArgumentException(operator + " needs a value");
        return values.get(0);
    }

    private static void requireText(AnalyticsDataset.Field field, String operator) {
        if (field.kind() != AnalyticsDataset.Kind.TEXT) {
            throw new IllegalArgumentException(operator + " applies to text fields; "
                    + field.apiName() + " is " + field.kind().name().toLowerCase(Locale.ROOT));
        }
    }

    private static void requireDateLike(AnalyticsDataset.Field field, String operator) {
        if (field.kind() != AnalyticsDataset.Kind.DATE && field.kind() != AnalyticsDataset.Kind.TIMESTAMP) {
            throw new IllegalArgumentException(operator + " applies to date fields; "
                    + field.apiName() + " is " + field.kind().name().toLowerCase(Locale.ROOT));
        }
    }

    // ------------------------------------------------------------------ groupings and aggregates

    private record Group(String alias, String label, String expression) {}

    /**
     * Groupings, with optional date bucketing: {@code closeDate:MONTH} groups by
     * calendar month. Bucketing a date in the report rather than materializing a
     * "close month" column is the difference between one reportable field and four
     * (FR-RPT-003).
     */
    private List<Group> groups(AnalyticsDataset dataset, List<String> requested, Set<String> withheld,
                               int min, int max) {
        List<String> names = requested == null ? List.of() : requested.stream()
                .filter(v -> v != null && !v.isBlank()).toList();
        if (names.size() < min) {
            throw new IllegalArgumentException("This report format needs at least " + min
                    + " grouping field(s). Groupable fields: " + groupableNames(dataset));
        }
        List<Group> groups = new ArrayList<>();
        for (String name : names.subList(0, Math.min(names.size(), max))) {
            groups.add(group(dataset, name, withheld));
        }
        return groups;
    }

    private Group group(AnalyticsDataset dataset, String requested, Set<String> withheld) {
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException("A grouping field is required. Groupable fields: "
                    + groupableNames(dataset));
        }
        String[] parts = requested.split(":", 2);
        AnalyticsDataset.Field field = dataset.field(parts[0].trim());
        if (withheld.contains(field.apiName())) {
            throw new com.axiom.common.ForbiddenException("Your profile does not permit reading '"
                    + field.apiName() + "', so a report cannot be grouped by it.");
        }
        if (!field.kind().groupable() && parts.length == 1) {
            throw new IllegalArgumentException(field.apiName() + " cannot be grouped. Groupable fields: "
                    + groupableNames(dataset));
        }
        if (parts.length == 1) {
            return new Group(field.apiName(), humanize(field.apiName()),
                    "coalesce(f." + field.column() + "::text, '(none)')");
        }
        String granularity = parts[1].trim().toUpperCase(Locale.ROOT);
        requireDateLike(field, "Date bucketing");
        String trunc = switch (granularity) {
            case "DAY", "WEEK", "MONTH", "QUARTER", "YEAR" -> granularity.toLowerCase(Locale.ROOT);
            default -> throw new IllegalArgumentException(
                    "Unsupported date bucket: " + granularity + ". Use DAY, WEEK, MONTH, QUARTER or YEAR");
        };
        return new Group(field.apiName() + ":" + granularity,
                humanize(field.apiName()) + " (" + granularity.toLowerCase(Locale.ROOT) + ")",
                "coalesce(to_char(date_trunc('" + trunc + "', f." + field.column()
                        + "::timestamptz), 'YYYY-MM-DD'), '(none)')");
    }

    private record Agg(String alias, String label, String expression, String kind) {
        static Agg count() {
            return new Agg("recordCount", "Records", "count(*)", "NUMBER");
        }
    }

    private List<Agg> aggregates(AnalyticsDataset dataset, ReportRequest request, Set<String> withheld) {
        List<Summary> summaries = request == null || request.summaries() == null
                ? List.of() : request.summaries();
        List<Agg> aggs = new ArrayList<>();
        for (Summary summary : summaries) {
            if (summary == null || summary.field() == null) continue;
            AnalyticsDataset.Field field = dataset.field(summary.field());
            if (withheld.contains(field.apiName())) {
                throw new com.axiom.common.ForbiddenException("Your profile does not permit reading '"
                        + field.apiName() + "', so it cannot be summarised.");
            }
            String function = summary.function() == null ? "SUM"
                    : summary.function().trim().toUpperCase(Locale.ROOT);
            String expression = switch (function) {
                case "SUM", "AVG" -> {
                    if (!field.kind().summable()) {
                        throw new IllegalArgumentException(field.apiName() + " is not numeric and cannot be "
                                + function + "-ed. Numeric fields: " + summableNames(dataset));
                    }
                    yield function.toLowerCase(Locale.ROOT) + "(f." + field.column() + ")";
                }
                case "MIN", "MAX" -> function.toLowerCase(Locale.ROOT) + "(f." + field.column() + ")";
                case "COUNT" -> "count(f." + field.column() + ")";
                case "COUNT_DISTINCT" -> "count(distinct f." + field.column() + ")";
                default -> throw new IllegalArgumentException("Unsupported summary function: " + function
                        + ". Supported: SUM, AVG, MIN, MAX, COUNT, COUNT_DISTINCT");
            };
            String alias = function.toLowerCase(Locale.ROOT) + "_" + field.apiName();
            String label = summary.label() == null || summary.label().isBlank()
                    ? humanize(field.apiName()) + " (" + function.toLowerCase(Locale.ROOT) + ")"
                    : summary.label();
            String kind = switch (function) {
                case "COUNT", "COUNT_DISTINCT" -> "NUMBER";
                default -> field.kind().name();
            };
            aggs.add(new Agg(alias, label, expression, kind));
        }
        return aggs;
    }

    /**
     * Totals over every matching row, computed with a second query rather than by
     * adding up the page. A truncated page's own sum looks exactly like a total and
     * is wrong by an amount nobody can see.
     */
    private Map<String, Object> grandTotals(AnalyticsDataset dataset, ReportRequest request, Where where,
                                            List<AnalyticsDataset.Field> detailColumns, Set<String> withheld) {
        List<Agg> aggs = aggregates(dataset, request, withheld);
        StringBuilder sql = new StringBuilder("select count(*) as \"recordCount\"");
        for (Agg agg : aggs) sql.append(", ").append(agg.expression()).append(" as ").append(quoted(agg.alias()));
        // A tabular report with no explicit summaries still totals its money columns:
        // an amount column whose total is not shown is the first thing a manager asks for.
        if (aggs.isEmpty()) {
            for (AnalyticsDataset.Field field : detailColumns) {
                if (field.kind() == AnalyticsDataset.Kind.MONEY) {
                    sql.append(", sum(f.").append(field.column()).append(") as ")
                            .append(quoted("sum_" + field.apiName()));
                }
            }
        }
        sql.append(" from ").append(dataset.factTable()).append(" f where ").append(where.sql());
        List<Map<String, Object>> rows = jdbc.query(sql.toString(), where.args().toArray(), rowMapper());
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    // ------------------------------------------------------------------ field security

    /**
     * Field API names this caller may not read on the dataset's underlying object.
     *
     * <p>The dataset's field names deliberately match {@link SecurableObject}'s API
     * names wherever they describe the same field, so one profile setting hides a
     * field on the record page, in search, and in every report at once. A dataset
     * field with no counterpart in the securable registry — a projected roll-up such
     * as {@code openPipelineAmount} — is not a securable field and is not withheld.
     */
    Set<String> withheldFields(AnalyticsDataset dataset) {
        SecurableObject object = dataset.securable().orElse(null);
        if (object == null) return Set.of();
        AccessContext ctx = authorization.context();
        Set<String> hidden = ctx.unreadable(object);
        if (hidden.isEmpty()) return Set.of();
        Set<String> withheld = new LinkedHashSet<>();
        for (String name : dataset.fieldNames()) {
            if (hidden.contains(name)) withheld.add(name);
        }
        return withheld;
    }

    private List<AnalyticsDataset.Field> selectedColumns(AnalyticsDataset dataset, ReportRequest request,
                                                         Set<String> withheld) {
        List<String> requested = request == null || request.columns() == null ? List.of()
                : request.columns().stream().filter(v -> v != null && !v.isBlank()).toList();
        List<AnalyticsDataset.Field> selected = new ArrayList<>();
        if (requested.isEmpty()) {
            for (AnalyticsDataset.Field field : dataset.fields()) {
                if (!withheld.contains(field.apiName())) selected.add(field);
                if (selected.size() >= 12) break;
            }
        } else {
            for (String name : requested) {
                AnalyticsDataset.Field field = dataset.field(name);
                // Withheld columns are ABSENT, not null — FR-SEC-007. The response's
                // withheldFields names them so the caller knows why, without leaking
                // the value.
                if (!withheld.contains(field.apiName())) selected.add(field);
            }
        }
        // The business key is always present: a detail row a user cannot drill from
        // is a dead end, and the id is never a securable field.
        AnalyticsDataset.Field key = dataset.field(idApiName(dataset));
        if (selected.stream().noneMatch(f -> f.apiName().equals(key.apiName()))) selected.add(0, key);
        return selected;
    }

    private String orderBy(AnalyticsDataset dataset, ReportRequest request,
                           List<AnalyticsDataset.Field> selected, Set<String> withheld) {
        String requested = request == null ? null : request.sortBy();
        String direction = request != null && "DESC".equalsIgnoreCase(request.sortDirection()) ? "desc" : "asc";
        if (requested == null || requested.isBlank()) {
            return " order by f." + selected.get(0).column() + " " + direction;
        }
        AnalyticsDataset.Field field = dataset.field(requested);
        if (withheld.contains(field.apiName())) {
            throw new com.axiom.common.ForbiddenException("Your profile does not permit reading '"
                    + field.apiName() + "', so a report cannot be sorted by it.");
        }
        return " order by f." + field.column() + " " + direction + " nulls last";
    }

    // ------------------------------------------------------------------ plumbing

    private static org.springframework.jdbc.core.RowMapper<Map<String, Object>> rowMapper() {
        return (rs, rowNum) -> {
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                Object value = rs.getObject(i);
                if (value instanceof java.sql.Timestamp ts) value = ts.toInstant().toString();
                else if (value instanceof java.sql.Date d) value = d.toLocalDate().toString();
                else if (value instanceof UUID id) value = id.toString();
                row.put(meta.getColumnLabel(i), value);
            }
            return row;
        };
    }

    private AnalyticsDataset[] stalenessInputs(AnalyticsDataset dataset, ReportRequest request) {
        RelatedFilter related = request == null ? null : request.related();
        if (related == null || related.related() == null || related.related().isBlank()) {
            return new AnalyticsDataset[]{dataset};
        }
        return new AnalyticsDataset[]{dataset, AnalyticsDataset.of(related.related())};
    }

    private void logExecution(UUID tenantId, AnalyticsDataset dataset, Format format,
                              ReportResult result, String status, String message) {
        jdbc.update("""
                insert into analytics.query_execution
                  (tenant_id, executed_by, dataset, format, row_count, truncated, elapsed_ms, status, message)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, TenantContext.get().userId(), dataset.name(), format.name(),
                result.rowCount(), result.truncated(), (int) result.elapsedMs(), status, message);
    }

    private void logTimeout(UUID tenantId, AnalyticsDataset dataset, Format format, long elapsed,
                            String message) {
        try {
            jdbc.update("""
                    insert into analytics.query_execution
                      (tenant_id, executed_by, dataset, format, row_count, truncated, elapsed_ms, status, message)
                    values (?, ?, ?, ?, 0, false, ?, 'TIMEOUT', ?)
                    """, tenantId, TenantContext.get().userId(), dataset.name(), format.name(),
                    (int) elapsed, message);
        } catch (RuntimeException ignored) {
            // The transaction that timed out is aborted; failing to log the timeout
            // must not replace the actionable message with a plumbing error.
        }
    }

    private static boolean isStatementTimeout(DataAccessException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sql && "57014".equals(sql.getSQLState())) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private static Format parseFormat(String requested) {
        if (requested == null || requested.isBlank()) return Format.TABULAR;
        try {
            return Format.valueOf(requested.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported report format: " + requested
                    + ". Supported: TABULAR, SUMMARY, MATRIX");
        }
    }

    private static String idApiName(AnalyticsDataset dataset) {
        return switch (dataset) {
            case OPPORTUNITY -> "opportunityId";
            case LEAD -> "leadId";
            case ACTIVITY -> "activityId";
            case ACCOUNT -> "accountId";
        };
    }

    private String groupableNames(AnalyticsDataset dataset) {
        return dataset.fields().stream().filter(f -> f.kind().groupable())
                .map(AnalyticsDataset.Field::apiName).reduce((a, b) -> a + ", " + b).orElse("(none)");
    }

    private String summableNames(AnalyticsDataset dataset) {
        return dataset.fields().stream().filter(f -> f.kind().summable())
                .map(AnalyticsDataset.Field::apiName).reduce((a, b) -> a + ", " + b).orElse("(none)");
    }

    /** Double-quoted so a camelCase alias survives PostgreSQL's lower-casing. */
    private static String quoted(String alias) {
        if (!alias.matches("[A-Za-z][A-Za-z0-9_:]*")) {
            throw new IllegalArgumentException("Unsafe result alias: " + alias);
        }
        return "\"" + alias + "\"";
    }

    static String humanize(String apiName) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < apiName.length(); i++) {
            char c = apiName.charAt(i);
            if (i == 0) out.append(Character.toUpperCase(c));
            else if (Character.isUpperCase(c)) out.append(' ').append(Character.toLowerCase(c));
            else out.append(c);
        }
        return out.toString();
    }
}

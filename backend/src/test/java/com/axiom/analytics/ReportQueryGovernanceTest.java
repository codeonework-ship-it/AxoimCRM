package com.axiom.analytics;

import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.security.AccessContext;
import com.axiom.security.AuthorizationService;
import com.axiom.security.SecurableObject;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Access-aware results (FR-RPT-005), field security (FR-SEC-007) and the query
 * guardrails (FR-RPT-011).
 *
 * <p>These are the tests that decide whether the read model is safe to ship. The
 * mocked {@link AuthorizationService} is the same one the record list pages use, so
 * what is asserted here is not "the report engine has its own idea of access" —
 * it is that the report engine has <em>no</em> idea of its own and defers entirely.
 */
class ReportQueryGovernanceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SALES_USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /**
     * {@code RecordPredicate.ALLOW_ALL} / {@code DENY_ALL} are package-private in
     * {@code com.axiom.security}. Restated here rather than widening their
     * visibility for a test in another module.
     */
    private static final AuthorizationService.RecordPredicate ALLOW_ALL =
            new AuthorizationService.RecordPredicate("true", List.of());
    private static final AuthorizationService.RecordPredicate DENY_ALL =
            new AuthorizationService.RecordPredicate("false", List.of());

    private JdbcTemplate jdbc;
    private AuthorizationService authorization;
    private ProjectionStatusService status;
    private ReportAccessScope scope;
    private ReportQueryService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        authorization = mock(AuthorizationService.class);
        status = mock(ProjectionStatusService.class);
        scope = new ReportAccessScope(authorization);
        service = new ReportQueryService(jdbc, scope, new QueryGuardrails(), status, authorization);

        when(status.stalenessFor(any(UUID.class), any(AnalyticsDataset[].class))).thenReturn(
                new ProjectionStatusService.Staleness(null, 4L, 0, false, "Projected data as of 4s ago."));
        when(authorization.context()).thenReturn(context(Map.of()));

        TenantContext.set(new TenantContext.Principal(TENANT, SALES_USER, "SALES",
                "Priya", "priya@example.com"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ access-aware results

    @Test
    @DisplayName("results are narrowed by a semi-join into the AUTHORITATIVE table, not by a projected ACL")
    void resultsExcludeRecordsTheViewerCannotRead() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.OPPORTUNITY), anyString()))
                .thenReturn(new AuthorizationService.RecordPredicate("(auth.owner_id = ?)",
                        List.of(SALES_USER)));
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class))).thenReturn(List.of());

        ReportQueryService.ReportResult result = service.run(new ReportQueryService.ReportRequest(
                "OPPORTUNITY", "TABULAR", List.of("name", "amount"), null, null, null, null,
                null, null, null, null));

        String sql = firstQuery();
        // The narrowing is a semi-join into sales.opportunity under the live predicate.
        assertThat(sql).contains("f.opportunity_id in (select auth.id from sales.opportunity auth");
        assertThat(sql).contains("auth.owner_id = ?");
        // And no fact table carries a permission column that could have been used instead.
        assertThat(sql).doesNotContain("sharing_keys").doesNotContain("f.can_read");
        // The restriction is reported rather than presented as the tenant's full total.
        assertThat(result.accessRestricted()).isTrue();
    }

    @Test
    @DisplayName("an administrator with view-all skips the semi-join entirely — no OLTP table is touched")
    void viewAllTouchesNoTransactionalTable() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.OPPORTUNITY), anyString()))
                .thenReturn(ALLOW_ALL);
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class))).thenReturn(List.of());

        ReportQueryService.ReportResult result = service.run(new ReportQueryService.ReportRequest(
                "OPPORTUNITY", "TABULAR", List.of("name"), null, null, null, null,
                null, null, null, null));

        String sql = firstQuery();
        assertThat(sql).contains("from analytics.opportunity_fact f");
        assertThat(sql).doesNotContain("sales.opportunity");
        assertThat(result.accessRestricted()).isFalse();
    }

    @Test
    @DisplayName("no object-level read at all yields a zero-row query rather than an unfiltered one")
    void deniedObjectYieldsNoRows() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.LEAD), anyString()))
                .thenReturn(DENY_ALL);
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class))).thenReturn(List.of());

        service.run(new ReportQueryService.ReportRequest("LEAD", "TABULAR", List.of("fullName"),
                null, null, null, null, null, null, null, null));

        assertThat(firstQuery()).contains("and false");
    }

    // ------------------------------------------------------------------ field security

    @Test
    @DisplayName("a field the profile hides is ABSENT from the columns, not returned as null")
    void hiddenFieldIsAbsent() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.OPPORTUNITY), anyString()))
                .thenReturn(ALLOW_ALL);
        when(authorization.context()).thenReturn(context(Map.of(SecurableObject.OPPORTUNITY, Set.of("amount"))));
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class))).thenReturn(List.of());

        ReportQueryService.ReportResult result = service.run(new ReportQueryService.ReportRequest(
                "OPPORTUNITY", "TABULAR", List.of("name", "amount"), null, null, null, null,
                null, null, null, null));

        assertThat(result.columns()).extracting(ReportQueryService.Column::field)
                .contains("name").doesNotContain("amount");
        // Named, so the reader knows something was withheld and why.
        assertThat(result.withheldFields()).contains("amount");
        assertThat(firstQuery()).doesNotContain("f.amount");
    }

    @Test
    @DisplayName("filtering on a hidden field is REFUSED — otherwise the value can be binary-searched")
    void filterOnHiddenFieldIsRefused() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.OPPORTUNITY), anyString()))
                .thenReturn(ALLOW_ALL);
        when(authorization.context()).thenReturn(context(Map.of(SecurableObject.OPPORTUNITY, Set.of("amount"))));

        assertThatThrownBy(() -> service.run(new ReportQueryService.ReportRequest(
                "OPPORTUNITY", "TABULAR", List.of("name"),
                List.of(new ReportQueryService.Filter("amount", "GT", List.of("100000"))),
                null, null, null, null, null, null, null)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("does not permit reading 'amount'");
    }

    // ------------------------------------------------------------------ guardrails

    @Test
    @DisplayName("a query over the row cap returns the rows it has AND guidance on narrowing")
    void rowCapReturnsGuidance() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.OPPORTUNITY), anyString()))
                .thenReturn(ALLOW_ALL);
        // The engine asks for limit + 1; returning that many is how "there are more"
        // becomes distinguishable from "that was all".
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class)))
                .thenReturn(rows(11)).thenReturn(List.of(Map.of("recordCount", 5000)));

        ReportQueryService.ReportResult result = service.run(new ReportQueryService.ReportRequest(
                "OPPORTUNITY", "TABULAR", List.of("name"), null, null, null, null,
                null, null, 10, null));

        assertThat(result.truncated()).isTrue();
        assertThat(result.rowCount()).isEqualTo(10);
        assertThat(result.guidance())
                .contains("10-row limit")
                .contains("date range")
                .contains("SUMMARY");
        // Totals come from a second query over the whole matching set, so a truncated
        // page cannot present its own partial sum as a total.
        assertThat(result.grandTotals()).containsEntry("recordCount", 5000);
    }

    @Test
    @DisplayName("a requested limit above the ceiling is clamped rather than rejected")
    void requestedLimitIsClamped() {
        QueryGuardrails guardrails = new QueryGuardrails();
        assertThat(guardrails.effectiveRowLimit(1_000_000)).isEqualTo(guardrails.maxRowLimit());
        assertThat(guardrails.effectiveRowLimit(null)).isPositive();
        assertThat(guardrails.effectiveRowLimit(-5)).isEqualTo(1);
    }

    @Test
    @DisplayName("per-tenant concurrency is refused immediately, with a message that names the limit")
    void concurrencyLimitRefusesWithGuidance() {
        QueryGuardrails guardrails = new QueryGuardrails(100, 100, 5000, 2);
        List<QueryGuardrails.Permit> held = new ArrayList<>();
        held.add(guardrails.acquire(TENANT));
        held.add(guardrails.acquire(TENANT));

        assertThatThrownBy(() -> guardrails.acquire(TENANT))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("2 reports running")
                .hasMessageContaining("narrow this report");

        // A different tenant is unaffected: the limit bounds the blast radius, it does
        // not queue the whole fleet behind one expensive report.
        QueryGuardrails.Permit other = guardrails.acquire(UUID.randomUUID());
        other.close();

        held.forEach(QueryGuardrails.Permit::close);
        guardrails.acquire(TENANT).close();
    }

    @Test
    @DisplayName("the statement timeout is set LOCAL so it cannot leak onto a pooled connection")
    void statementTimeoutIsTransactionScoped() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.OPPORTUNITY), anyString()))
                .thenReturn(ALLOW_ALL);
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class))).thenReturn(List.of());

        service.run(new ReportQueryService.ReportRequest("OPPORTUNITY", "TABULAR", List.of("name"),
                null, null, null, null, null, null, null, null));

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).execute(statements.capture());
        assertThat(statements.getValue()).startsWith("set local statement_timeout =");
    }

    // ------------------------------------------------------------------ builder semantics

    @Test
    @DisplayName("an unknown field is refused with the list of fields the author CAN use")
    void unknownFieldNamesTheAlternatives() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.OPPORTUNITY), anyString()))
                .thenReturn(ALLOW_ALL);

        assertThatThrownBy(() -> service.run(new ReportQueryService.ReportRequest(
                "OPPORTUNITY", "TABULAR", List.of("marginPercent"), null, null, null, null,
                null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a reportable field of OPPORTUNITY")
                .hasMessageContaining("Reportable fields:");
    }

    @Test
    @DisplayName("'accounts WITHOUT activity in 90 days' is an anti-join over the read model")
    void withoutRelatedRecordsIsFirstClass() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.ACCOUNT), anyString()))
                .thenReturn(ALLOW_ALL);
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class))).thenReturn(List.of());

        service.run(new ReportQueryService.ReportRequest("ACCOUNT", "TABULAR", List.of("name"),
                null, null, null, null, null, null, null,
                new ReportQueryService.RelatedFilter("ACTIVITY", "WITHOUT", 90)));

        String sql = firstQuery();
        assertThat(sql).contains("not exists (select 1 from analytics.activity_fact r");
        assertThat(sql).contains("r.occurred_on >= current_date - ?::int");
    }

    @Test
    @DisplayName("a SUMMARY report groups and totals without listing rows")
    void summaryFormatGroupsAndAggregates() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.OPPORTUNITY), anyString()))
                .thenReturn(ALLOW_ALL);
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class))).thenReturn(List.of());

        ReportQueryService.ReportResult result = service.run(new ReportQueryService.ReportRequest(
                "OPPORTUNITY", "SUMMARY", null, null, List.of("stageName"), null,
                List.of(new ReportQueryService.Summary("amount", "SUM", "Pipeline")),
                null, null, null, null));

        String sql = firstQuery();
        assertThat(sql).contains("sum(f.amount) as \"sum_amount\"").contains("group by 1");
        assertThat(result.columns()).extracting(ReportQueryService.Column::role)
                .contains("GROUP", "MEASURE");
    }

    @Test
    @DisplayName("date bucketing groups a date field by calendar period without a materialized column")
    void dateBucketing() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.OPPORTUNITY), anyString()))
                .thenReturn(ALLOW_ALL);
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class))).thenReturn(List.of());

        service.run(new ReportQueryService.ReportRequest("OPPORTUNITY", "SUMMARY", null, null,
                List.of("closeDate:MONTH"), null, null, null, null, null, null));

        assertThat(firstQuery()).contains("date_trunc('month', f.close_date");
    }

    @Test
    @DisplayName("summing a non-numeric field is refused, naming the numeric fields")
    void nonNumericSummaryIsRefused() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.OPPORTUNITY), anyString()))
                .thenReturn(ALLOW_ALL);

        assertThatThrownBy(() -> service.run(new ReportQueryService.ReportRequest(
                "OPPORTUNITY", "SUMMARY", null, null, List.of("stageName"), null,
                List.of(new ReportQueryService.Summary("name", "SUM", null)),
                null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not numeric");
    }

    // ------------------------------------------------------------------ helpers

    private String firstQuery() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(Object[].class), any(RowMapper.class));
        return sql.getAllValues().get(0);
    }

    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("opportunityId", UUID.randomUUID().toString());
            row.put("name", "Deal " + i);
            rows.add(row);
        }
        return rows;
    }

    private static AccessContext context(Map<SecurableObject, Set<String>> unreadable) {
        return new AccessContext(TENANT, SALES_USER, "SALES", false, false, null, null, null,
                List.of(), Set.of(), Map.of(), unreadable, Map.of(), null, null, null,
                List.of(), List.of());
    }
}

package com.axiom.analytics;

import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Each governed formula, checked against the value doc 14 §3 documents, on fixed
 * fixtures.
 *
 * <p>The fixtures are deliberately small and arithmetically obvious — 3 won and 1
 * lost is 75%, not "approximately three quarters" — so a failure points at the
 * formula rather than at the fixture. Half of these tests assert that <b>no
 * number</b> is produced: doc 14's standard is that a metric with a missing input
 * displays as not computable with the input named, because "a number built from
 * partial data would be confidently wrong in the direction that flatters", and a
 * zero denominator silently rendered as 0% is the same failure wearing a
 * percentage sign.
 */
class KpiCalculationServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    private JdbcTemplate jdbc;
    private ReportAccessScope accessScope;
    private MetricRegistryService registry;
    private ProjectionStatusService status;
    private KpiCalculationService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        accessScope = mock(ReportAccessScope.class);
        registry = mock(MetricRegistryService.class);
        status = mock(ProjectionStatusService.class);
        service = new KpiCalculationService(jdbc, accessScope, registry, status);

        when(accessScope.scopeFor(any(), anyString())).thenReturn(ReportAccessScope.Scope.UNRESTRICTED);
        when(registry.activeOrNull(any(), anyString())).thenReturn(new MetricRegistryService.MetricDefinition(
                UUID.randomUUID(), "X", "X", 3, "documented formula", "documented basis", "PERCENT",
                null, null, null, "ACTIVE", null, null, null, null));
        when(status.stalenessFor(any(UUID.class), any(AnalyticsDataset[].class))).thenReturn(
                new ProjectionStatusService.Staleness(null, 12L, 0, false, "Projected data as of 12s ago."));

        TenantContext.set(new TenantContext.Principal(TENANT,
                UUID.fromString("22222222-2222-2222-2222-222222222221"),
                "TENANT_ADMIN", "Raj", "raj@example.com"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private KpiCalculationService.KpiValue compute(String code) {
        return service.compute(code, new KpiCalculationService.KpiScope(START, END, null));
    }

    private void aggregate(Map<String, Object> row) {
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(row);
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    // ------------------------------------------------------------------ win rate

    @Test
    @DisplayName("WIN_RATE = won / (won + lost) — 3 won, 1 lost is 0.75")
    void winRate() {
        aggregate(row("won", 3L, "lost", 1L, "still_open", 5L));

        KpiCalculationService.KpiValue value = compute("WIN_RATE");

        assertThat(value.computable()).isTrue();
        assertThat(value.value()).isEqualByComparingTo("0.75");
        assertThat(value.inputs()).containsEntry("closedWon", new BigDecimal("3"))
                .containsEntry("closedLost", new BigDecimal("1"));
        // The definition version travels with the figure — FR-RPT-009.
        assertThat(value.definitionVersion()).isEqualTo(3);
        assertThat(value.formula()).isEqualTo("documented formula");
    }

    @Test
    @DisplayName("WIN_RATE with nothing closed is NOT COMPUTABLE, not 0%")
    void winRateWithNoClosedDealsIsWithheld() {
        aggregate(row("won", 0L, "lost", 0L, "still_open", 9L));

        KpiCalculationService.KpiValue value = compute("WIN_RATE");

        assertThat(value.computable()).isFalse();
        assertThat(value.value()).isNull();
        assertThat(value.missingInputs()).containsExactly("closed opportunities in the period");
        // A losing quarter and an unfinished quarter must not look the same.
        assertThat(value.note()).contains("not").contains("0%");
    }

    // ------------------------------------------------------------------ average deal size

    @Test
    @DisplayName("AVERAGE_DEAL_SIZE = closed-won amount / closed-won count")
    void averageDealSize() {
        aggregate(row("won", 4L, "won_amount", new BigDecimal("1000000")));

        KpiCalculationService.KpiValue value = compute("AVERAGE_DEAL_SIZE");

        assertThat(value.computable()).isTrue();
        assertThat(value.value()).isEqualByComparingTo("250000");
    }

    @Test
    @DisplayName("AVERAGE_DEAL_SIZE with no closed-won deal is withheld")
    void averageDealSizeWithoutWins() {
        aggregate(row("won", 0L, "won_amount", BigDecimal.ZERO));
        assertThat(compute("AVERAGE_DEAL_SIZE").computable()).isFalse();
    }

    // ------------------------------------------------------------------ coverage and attainment

    @Test
    @DisplayName("PIPELINE_COVERAGE = open pipeline / (quota - closed won credited)")
    void pipelineCoverage() {
        aggregate(row("open_pipeline", new BigDecimal("2500800"), "closed_won", new BigDecimal("500000")));
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(new BigDecimal("1500000"));

        KpiCalculationService.KpiValue value = compute("PIPELINE_COVERAGE");

        // remaining quota = 1,500,000 - 500,000 = 1,000,000 → coverage 2.5008x
        assertThat(value.computable()).isTrue();
        assertThat(value.value()).isEqualByComparingTo("2.5008");
        assertThat(value.inputs()).containsEntry("remainingQuota", new BigDecimal("1000000"));
    }

    @Test
    @DisplayName("PIPELINE_COVERAGE without a configured quota names the missing input")
    void pipelineCoverageWithoutQuota() {
        aggregate(row("open_pipeline", new BigDecimal("2500800"), "closed_won", BigDecimal.ZERO));
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(BigDecimal.ZERO);

        KpiCalculationService.KpiValue value = compute("PIPELINE_COVERAGE");

        assertThat(value.computable()).isFalse();
        assertThat(value.missingInputs()).containsExactly("assigned quota for the period (orgdata.quota)");
    }

    @Test
    @DisplayName("QUOTA_ATTAINMENT = credited closed revenue / assigned quota, with the basis stated")
    void quotaAttainment() {
        aggregate(row("credited", new BigDecimal("750000")));
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(new BigDecimal("1000000"));

        KpiCalculationService.KpiValue value = compute("QUOTA_ATTAINMENT");

        assertThat(value.value()).isEqualByComparingTo("0.75");
        assertThat(value.inputs()).containsEntry("creditBasis", "CLOSED_REVENUE");
    }

    // ------------------------------------------------------------------ velocity

    @Test
    @DisplayName("SALES_VELOCITY = (open qualified x avg deal size x win rate) / avg cycle days")
    void salesVelocity() {
        aggregate(row("open_qualified", 10L, "won_count", 3L, "lost_count", 1L,
                "won_amount", new BigDecimal("300000"), "avg_cycle_days", new BigDecimal("50")));

        KpiCalculationService.KpiValue value = compute("SALES_VELOCITY");

        // 10 x 100000 x 0.75 / 50 = 15000 per day
        assertThat(value.computable()).isTrue();
        assertThat(value.value()).isEqualByComparingTo("15000");
        // Doc 14 §3 requires the four inputs to be displayed with the result.
        assertThat(value.inputs())
                .containsKeys("openQualifiedOpportunities", "averageDealSize", "winRate",
                        "averageSalesCycleDays");
    }

    @Test
    @DisplayName("SALES_VELOCITY names every missing input rather than substituting a zero")
    void salesVelocityWithoutHistory() {
        aggregate(row("open_qualified", 9L, "won_count", 0L, "lost_count", 0L,
                "won_amount", BigDecimal.ZERO, "avg_cycle_days", null));

        KpiCalculationService.KpiValue value = compute("SALES_VELOCITY");

        assertThat(value.computable()).isFalse();
        assertThat(value.missingInputs()).hasSize(3);
        assertThat(value.inputs()).containsEntry("openQualifiedOpportunities", new BigDecimal("9"));
    }

    // ------------------------------------------------------------------ contract value

    @Test
    @DisplayName("ACV sums the projected per-deal annualized value over closed-won deals")
    void acv() {
        aggregate(row("total", new BigDecimal("480000"), "with_value", 2L, "won", 3L));

        KpiCalculationService.KpiValue value = compute("ACV");

        assertThat(value.computable()).isTrue();
        assertThat(value.value()).isEqualByComparingTo("480000");
        assertThat(value.inputs()).containsEntry("closedWonWithContractTerms", new BigDecimal("2"));
    }

    @Test
    @DisplayName("ARR is a point-in-time stock and ignores the period filter")
    void arr() {
        aggregate(row("arr", new BigDecimal("960000"), "contributing", 4L));

        KpiCalculationService.KpiValue value = compute("ARR");

        assertThat(value.value()).isEqualByComparingTo("960000");
        assertThat(value.inputs()).containsEntry("measurementDate", END);
        assertThat(value.note()).contains("point-in-time stock");
    }

    // ------------------------------------------------------------------ stage conversion

    @Test
    @DisplayName("STAGE_CONVERSION is a cohort measure: exited forward / entered, per stage")
    void stageConversion() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                row("stage", "Qualify", "stage_order", 10, "entered", 8L, "exited_forward", 6L),
                row("stage", "Propose", "stage_order", 20, "entered", 4L, "exited_forward", 2L)));

        KpiCalculationService.KpiValue value = compute("STAGE_CONVERSION");

        // 8 in / 6 forward, 4 in / 2 forward → 8 of 12 overall
        assertThat(value.value()).isEqualByComparingTo("0.666667");
        assertThat(value.inputs()).containsEntry("cohortEntered", new BigDecimal("12"));
        assertThat(value.note()).contains("Cohort basis").contains("double-count");
    }

    @Test
    @DisplayName("STAGE_CONVERSION with an empty cohort is withheld, not reported as zero conversion")
    void stageConversionEmptyCohort() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        KpiCalculationService.KpiValue value = compute("STAGE_CONVERSION");

        assertThat(value.computable()).isFalse();
        assertThat(value.missingInputs())
                .containsExactly("opportunities entering a stage within the period");
    }

    // ------------------------------------------------------------------ funnel

    @Test
    @DisplayName("MQL_SQL_CONVERSION = accepted / handed off, with rejections reported alongside")
    void mqlToSql() {
        aggregate(row("accepted", 6L, "handed_off", 10L, "rejected", 4L, "leads_in_cohort", 25L));

        KpiCalculationService.KpiValue value = compute("MQL_SQL_CONVERSION");

        assertThat(value.value()).isEqualByComparingTo("0.6");
        // "Rejections are reported alongside, not hidden in the denominator."
        assertThat(value.inputs()).containsEntry("rejectedWithReason", new BigDecimal("4"));
    }

    // ------------------------------------------------------------------ slippage

    @Test
    @DisplayName("SLIPPAGE_RATE is withheld until an opening snapshot exists to anchor the denominator")
    void slippageNeedsAnOpeningSnapshot() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class))).thenReturn(null);

        KpiCalculationService.KpiValue value = compute("SLIPPAGE_RATE");

        assertThat(value.computable()).isFalse();
        assertThat(value.missingInputs())
                .containsExactly("an immutable forecast snapshot at or before the period start");
        assertThat(value.note()).contains("flatter");
    }

    @Test
    @DisplayName("SLIPPAGE_RATE = slipped out of period / population forecast at the opening snapshot")
    void slippageRate() {
        Map<String, Object> opening = row("snapshotId", UUID.randomUUID(),
                "capturedOn", LocalDate.of(2026, 7, 1), "openCount", 20L,
                "forecastAmount", new BigDecimal("2500800"));
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(opening);
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(new BigDecimal("5"));

        KpiCalculationService.KpiValue value = compute("SLIPPAGE_RATE");

        assertThat(value.value()).isEqualByComparingTo("0.25");
        assertThat(value.note()).contains("anchored to the opening snapshot");
    }

    // ------------------------------------------------------------------ honestly withheld

    @Test
    @DisplayName("CAC_PAYBACK is never computed from partial cost data — doc 14's own worked example")
    void cacPayback() {
        KpiCalculationService.KpiValue value = compute("CAC_PAYBACK");

        assertThat(value.computable()).isFalse();
        assertThat(value.value()).isNull();
        assertThat(value.missingInputs()).containsExactly(
                "sales and marketing cost from the finance system",
                "cost-of-service / gross margin %");
        assertThat(value.note()).contains("flatter");
    }

    @Test
    @DisplayName("CAMPAIGN_ROI without a named attribution model is not a valid output")
    void campaignRoi() {
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(new BigDecimal("41500"));

        KpiCalculationService.KpiValue value = compute("CAMPAIGN_ROI");

        assertThat(value.computable()).isFalse();
        assertThat(value.inputs()).containsEntry("campaignCostInPeriod", new BigDecimal("41500"));
        assertThat(value.missingInputs()).hasSize(2);
    }

    @Test
    @DisplayName("FORECAST_ACCURACY without a locked submission is withheld")
    void forecastAccuracyNeedsALockedSubmission() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        KpiCalculationService.KpiValue value = compute("FORECAST_ACCURACY");

        assertThat(value.computable()).isFalse();
        assertThat(value.missingInputs())
                .containsExactly("a locked forecast submission for the period (FR-FCT-004)");
    }

    @Test
    @DisplayName("every catalogued metric has a computation registered — no silent gaps")
    void everyCataloguedMetricIsRoutable() {
        aggregate(row("won", 0L, "lost", 0L, "still_open", 0L, "open_pipeline", BigDecimal.ZERO,
                "closed_won", BigDecimal.ZERO, "credited", BigDecimal.ZERO, "open_qualified", 0L,
                "won_count", 0L, "lost_count", 0L, "won_amount", BigDecimal.ZERO,
                "avg_cycle_days", null, "total", BigDecimal.ZERO, "with_value", 0L, "won", 0L,
                "arr", BigDecimal.ZERO, "contributing", 0L, "accepted", 0L, "handed_off", 0L,
                "rejected", 0L, "leads_in_cohort", 0L));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class))).thenReturn(null);
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(BigDecimal.ZERO);

        List<KpiCalculationService.KpiValue> values =
                service.computeAll(new KpiCalculationService.KpiScope(START, END, null));

        assertThat(values).hasSameSizeAs(KpiCalculationService.COMPUTABLE_METRICS);
        assertThat(values).allSatisfy(v -> assertThat(v.metricCode()).isNotBlank());
    }
}

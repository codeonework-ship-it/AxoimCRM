package com.axiom.analytics;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes the governed KPIs of doc 14 §3 — the published formulas, not
 * approximations of them.
 *
 * <h2>One implementation per metric, deliberately</h2>
 * FR-RPT-009 says two reports showing the same named metric must compute it
 * identically. The only reliable way to guarantee that is for there to be exactly
 * one piece of code that computes it, and for every surface — dashboard tile, KPI
 * catalogue, threshold alert, exported figure — to call that one piece. So this
 * class is the single computation site, and each result carries the definition
 * version it was computed under.
 *
 * <h2>"Not computable" is a first-class answer</h2>
 * The hardest discipline in this file is refusing to produce a number. Doc 14 sets
 * the standard on CAC payback: without configured finance inputs it "displays as
 * <i>not computable with its missing inputs named</i> — it does not display a
 * number built from partial cost data, which would be confidently wrong in the
 * direction that flatters."
 *
 * <p>That rule is applied uniformly here, not only where the doc spells it out.
 * Coverage with no configured quota, win rate with nothing closed, slippage with
 * no opening snapshot to anchor the denominator, campaign ROI with no attribution
 * model — each returns {@code computable=false} with the missing input named,
 * because a zero denominator quietly rendered as 0%, or 100%, is worse than a
 * blank: a blank prompts a question, and a wrong number ends one.
 *
 * <h2>Inputs travel with the result</h2>
 * The product principle is "explain every number" — a number a manager cannot
 * decompose is a defect. So every figure returns the raw counts and sums it was
 * built from. Sales velocity in particular returns all four of its inputs, as doc
 * 14 §3 requires, because a velocity figure whose four drivers are invisible
 * cannot be argued with, only believed.
 */
@Service
public class KpiCalculationService {

    public record KpiScope(LocalDate periodStart, LocalDate periodEnd, UUID ownerId) {}

    public record KpiValue(String metricCode, String name, Integer definitionVersion, String formula,
                           String basis, String unit, String requirementRef, String notes,
                           BigDecimal value, boolean computable, List<String> missingInputs,
                           Map<String, Object> inputs, String note, boolean accessRestricted,
                           LocalDate periodStart, LocalDate periodEnd,
                           ProjectionStatusService.Staleness staleness) {}

    /** All the metric codes this service can compute, in catalogue order. */
    public static final List<String> COMPUTABLE_METRICS = List.of(
            "PIPELINE_COVERAGE", "SALES_VELOCITY", "STAGE_CONVERSION", "WIN_RATE",
            "AVERAGE_DEAL_SIZE", "ACV", "ARR", "TCV", "QUOTA_ATTAINMENT",
            "FORECAST_ACCURACY", "FORECAST_BIAS", "SLIPPAGE_RATE",
            "MQL_SQL_CONVERSION", "CAMPAIGN_ROI", "CAC_PAYBACK");

    private final JdbcTemplate jdbc;
    private final ReportAccessScope accessScope;
    private final MetricRegistryService registry;
    private final ProjectionStatusService status;

    public KpiCalculationService(JdbcTemplate jdbc, ReportAccessScope accessScope,
                                 MetricRegistryService registry, ProjectionStatusService status) {
        this.jdbc = jdbc;
        this.accessScope = accessScope;
        this.registry = registry;
        this.status = status;
    }

    // ------------------------------------------------------------------ entry points

    @Transactional(readOnly = true)
    public List<KpiValue> computeAll(KpiScope requested) {
        List<KpiValue> values = new ArrayList<>();
        for (String code : COMPUTABLE_METRICS) values.add(compute(code, requested));
        return values;
    }

    @Transactional(readOnly = true)
    public KpiValue compute(String metricCode, KpiScope requested) {
        UUID tenantId = TenantContext.get().tenantId();
        String code = MetricRegistryService.normalise(metricCode);
        KpiScope scope = defaults(requested);
        ReportAccessScope.Scope access = accessScope.scopeFor(AnalyticsDataset.OPPORTUNITY, "f");

        Computed computed = switch (code) {
            case "PIPELINE_COVERAGE" -> pipelineCoverage(tenantId, scope, access);
            case "SALES_VELOCITY" -> salesVelocity(tenantId, scope, access);
            case "STAGE_CONVERSION" -> stageConversion(tenantId, scope, access);
            case "WIN_RATE" -> winRate(tenantId, scope, access);
            case "AVERAGE_DEAL_SIZE" -> averageDealSize(tenantId, scope, access);
            case "ACV" -> contractValue(tenantId, scope, access, "acv", "annualized");
            case "ARR" -> annualRecurringRevenue(tenantId, scope, access);
            case "TCV" -> contractValue(tenantId, scope, access, "tcv", "total contracted");
            case "QUOTA_ATTAINMENT" -> quotaAttainment(tenantId, scope, access);
            case "FORECAST_ACCURACY" -> forecastAccuracy(tenantId, scope, false);
            case "FORECAST_BIAS" -> forecastAccuracy(tenantId, scope, true);
            case "SLIPPAGE_RATE" -> slippageRate(tenantId, scope, access);
            case "MQL_SQL_CONVERSION" -> mqlToSql(tenantId, scope);
            case "CAMPAIGN_ROI" -> campaignRoi(tenantId, scope);
            case "CAC_PAYBACK" -> cacPayback();
            default -> throw new com.axiom.common.NotFoundException(
                    "No computation is registered for metric " + code + ". Computable metrics: "
                            + String.join(", ", COMPUTABLE_METRICS));
        };

        MetricRegistryService.MetricDefinition definition = registry.activeOrNull(tenantId, code);
        AnalyticsDataset[] inputs = switch (code) {
            case "MQL_SQL_CONVERSION" -> new AnalyticsDataset[]{AnalyticsDataset.LEAD};
            default -> new AnalyticsDataset[]{AnalyticsDataset.OPPORTUNITY};
        };

        return new KpiValue(code,
                definition == null ? code : definition.name(),
                definition == null ? null : definition.version(),
                definition == null ? null : definition.formula(),
                definition == null ? null : definition.basis(),
                definition == null ? "NUMBER" : definition.unit(),
                definition == null ? null : definition.requirementRef(),
                definition == null ? null : definition.notes(),
                computed.value(), computed.computable(), computed.missing(), computed.inputs(),
                definition == null
                        ? "This metric has no published definition, so the figure is ungoverned"
                          + " (FR-RPT-009). Publish a definition before quoting it."
                        : computed.note(),
                access.restricted(), scope.periodStart(), scope.periodEnd(),
                status.stalenessFor(tenantId, inputs));
    }

    // ------------------------------------------------------------------ the formulas

    private record Computed(BigDecimal value, boolean computable, List<String> missing,
                            Map<String, Object> inputs, String note) {

        static Computed of(BigDecimal value, Map<String, Object> inputs, String note) {
            return new Computed(value, true, List.of(), inputs, note);
        }

        static Computed missing(Map<String, Object> inputs, String note, String... missing) {
            return new Computed(null, false, List.of(missing), inputs, note);
        }
    }

    /** open pipeline value in period / remaining quota for period. */
    private Computed pipelineCoverage(UUID tenantId, KpiScope scope, ReportAccessScope.Scope access) {
        Map<String, Object> row = one("""
                select coalesce(sum(f.amount) filter (
                         where f.is_closed = false
                           and f.forecast_category in ('PIPELINE','BEST_CASE','COMMIT')), 0) as open_pipeline,
                       coalesce(sum(f.amount) filter (where f.is_won = true), 0) as closed_won
                  from analytics.opportunity_fact f
                 where %s
                """, tenantId, scope, access);

        BigDecimal openPipeline = number(row.get("open_pipeline"));
        BigDecimal closedWon = number(row.get("closed_won"));
        BigDecimal quota = assignedQuota(tenantId, scope);

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("openPipelineInPeriod", openPipeline);
        inputs.put("closedWonCreditedToPeriod", closedWon);
        inputs.put("assignedQuota", quota);
        inputs.put("forecastCategories", List.of("PIPELINE", "BEST_CASE", "COMMIT"));

        if (quota == null || quota.signum() == 0) {
            return Computed.missing(inputs,
                    "Coverage needs a quota for the period. No quota is configured, so the ratio is"
                            + " withheld rather than divided by zero.",
                    "assigned quota for the period (orgdata.quota)");
        }
        BigDecimal remaining = quota.subtract(closedWon);
        inputs.put("remainingQuota", remaining);
        if (remaining.signum() <= 0) {
            return Computed.missing(inputs,
                    "Quota for the period is already fully attained, so coverage has no meaningful"
                            + " denominator. Attainment is the figure to read here, not coverage.",
                    "remaining quota greater than zero");
        }
        return Computed.of(divide(openPipeline, remaining), inputs,
                "Open pipeline in the configured forecast categories over remaining quota.");
    }

    /** (open qualified count * average deal size * win rate) / average sales cycle days. */
    private Computed salesVelocity(UUID tenantId, KpiScope scope, ReportAccessScope.Scope access) {
        Map<String, Object> row = one("""
                select count(*) filter (where f.is_closed = false
                                          and f.forecast_category in ('PIPELINE','BEST_CASE','COMMIT'))
                         as open_qualified,
                       count(*) filter (where f.is_won = true) as won_count,
                       count(*) filter (where f.is_closed = true and f.is_won = false) as lost_count,
                       coalesce(sum(f.amount) filter (where f.is_won = true), 0) as won_amount,
                       avg(f.cycle_days) filter (where f.is_won = true) as avg_cycle_days
                  from analytics.opportunity_fact f
                 where %s
                """, tenantId, scope, access);

        BigDecimal openQualified = number(row.get("open_qualified"));
        BigDecimal won = number(row.get("won_count"));
        BigDecimal lost = number(row.get("lost_count"));
        BigDecimal wonAmount = number(row.get("won_amount"));
        BigDecimal avgCycle = number(row.get("avg_cycle_days"));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("openQualifiedOpportunities", openQualified);
        inputs.put("closedWon", won);
        inputs.put("closedLost", lost);

        List<String> missing = new ArrayList<>();
        if (won.signum() == 0 && lost.signum() == 0) missing.add("closed opportunities (win rate has no denominator)");
        if (won.signum() == 0) missing.add("closed-won opportunities (average deal size has no denominator)");
        if (avgCycle == null || avgCycle.signum() == 0) missing.add("average sales cycle days from closed-won deals");

        BigDecimal averageDealSize = won.signum() == 0 ? null : divide(wonAmount, won);
        BigDecimal winRate = (won.add(lost)).signum() == 0 ? null : divide(won, won.add(lost));
        inputs.put("averageDealSize", averageDealSize);
        inputs.put("winRate", winRate);
        inputs.put("averageSalesCycleDays", avgCycle);

        if (!missing.isEmpty()) {
            return Computed.missing(inputs,
                    "Velocity is the product of four governed inputs; the ones named are not yet"
                            + " computable, so no velocity figure is shown.",
                    missing.toArray(String[]::new));
        }
        BigDecimal value = openQualified.multiply(averageDealSize).multiply(winRate)
                .divide(avgCycle, 4, RoundingMode.HALF_UP);
        return Computed.of(value, inputs,
                "Currency per day. All four inputs are returned with the figure, per doc 14 §3.");
    }

    /** exited stage n forward / entered stage n, on a cohort basis from stage history. */
    private Computed stageConversion(UUID tenantId, KpiScope scope, ReportAccessScope.Scope access) {
        // Aliased `f` so the opportunity access scope — which narrows on
        // `f.opportunity_id` — applies to the cohort unchanged. A rep's stage
        // conversion is computed over their own deals, not the team's.
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        StringBuilder where = new StringBuilder("f.tenant_id = ?");
        if (access.hasClause()) {
            where.append(" and ").append(access.sql());
            args.addAll(access.args());
        }
        where.append(" and f.entered_on between ? and ?");
        args.add(scope.periodStart());
        args.add(scope.periodEnd());

        List<Map<String, Object>> stages = jdbc.queryForList("""
                select f.to_stage_name as stage, f.to_stage_order as stage_order,
                       count(*) as entered,
                       count(*) filter (where f.exited_forward) as exited_forward
                  from analytics.stage_transition_fact f
                 where """ + where + """
                 group by 1, 2
                 order by 2
                """, args.toArray());

        Map<String, Object> inputs = new LinkedHashMap<>();
        List<Map<String, Object>> byStage = new ArrayList<>();
        BigDecimal totalEntered = BigDecimal.ZERO;
        BigDecimal totalForward = BigDecimal.ZERO;
        for (Map<String, Object> stage : stages) {
            BigDecimal entered = number(stage.get("entered"));
            BigDecimal forward = number(stage.get("exited_forward"));
            totalEntered = totalEntered.add(entered);
            totalForward = totalForward.add(forward);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("stage", stage.get("stage"));
            line.put("entered", entered);
            line.put("exitedForward", forward);
            line.put("conversion", entered.signum() == 0 ? null : divide(forward, entered));
            byStage.add(line);
        }
        inputs.put("byStage", byStage);
        inputs.put("cohortEntered", totalEntered);
        inputs.put("cohortExitedForward", totalForward);

        if (totalEntered.signum() == 0) {
            return Computed.missing(inputs,
                    "No opportunity entered any stage inside the period, so there is no cohort to"
                            + " measure. Widen the period rather than reading this as zero conversion.",
                    "opportunities entering a stage within the period");
        }
        return Computed.of(divide(totalForward, totalEntered), inputs,
                "Cohort basis over opportunities entering a stage within the period — not a"
                        + " point-in-time census, which would double-count stalled deals.");
    }

    /** closed won / (closed won + closed lost). */
    private Computed winRate(UUID tenantId, KpiScope scope, ReportAccessScope.Scope access) {
        Map<String, Object> row = one("""
                select count(*) filter (where f.is_won = true) as won,
                       count(*) filter (where f.is_closed = true and f.is_won = false) as lost,
                       count(*) filter (where f.is_closed = false) as still_open
                  from analytics.opportunity_fact f
                 where %s
                """, tenantId, scope, access);
        BigDecimal won = number(row.get("won"));
        BigDecimal lost = number(row.get("lost"));
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("closedWon", won);
        inputs.put("closedLost", lost);
        inputs.put("stillOpen", number(row.get("still_open")));
        if (won.add(lost).signum() == 0) {
            return Computed.missing(inputs,
                    "Nothing closed in the period, so the win-rate denominator is zero. Shown as not"
                            + " computable rather than as 0%, which would read as a losing quarter.",
                    "closed opportunities in the period");
        }
        return Computed.of(divide(won, won.add(lost)), inputs,
                "Count basis, the published basis. Deals closed as disqualified or no-decision are"
                        + " excluded. A value-weighted win rate is a separately named metric.");
    }

    /** sum of closed won amount / closed won count. */
    private Computed averageDealSize(UUID tenantId, KpiScope scope, ReportAccessScope.Scope access) {
        Map<String, Object> row = one("""
                select count(*) filter (where f.is_won = true) as won,
                       coalesce(sum(f.amount) filter (where f.is_won = true), 0) as won_amount
                  from analytics.opportunity_fact f
                 where %s
                """, tenantId, scope, access);
        BigDecimal won = number(row.get("won"));
        BigDecimal amount = number(row.get("won_amount"));
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("closedWon", won);
        inputs.put("closedWonAmount", amount);
        if (won.signum() == 0) {
            return Computed.missing(inputs,
                    "No closed-won opportunity in the period, so there is no average to take.",
                    "closed-won opportunities in the period");
        }
        return Computed.of(divide(amount, won), inputs,
                "Corporate currency at the stored conversion rate; never recomputed at today's rate.");
    }

    /** ACV and TCV: sums of the projected per-deal values over closed-won deals. */
    private Computed contractValue(UUID tenantId, KpiScope scope, ReportAccessScope.Scope access,
                                   String column, String label) {
        Map<String, Object> row = one("""
                select coalesce(sum(f.%s) filter (where f.is_won = true), 0) as total,
                       count(*) filter (where f.is_won = true and f.%s is not null) as with_value,
                       count(*) filter (where f.is_won = true) as won
                """.formatted(column, column) + """
                  from analytics.opportunity_fact f
                 where %s
                """, tenantId, scope, access);
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("closedWon", number(row.get("won")));
        inputs.put("closedWonWithContractTerms", number(row.get("with_value")));
        return Computed.of(number(row.get("total")), inputs,
                "Sum of " + label + " value over closed-won deals. Deals without recurring terms"
                        + " contribute nothing rather than being counted at their total amount.");
    }

    /** ARR: a point-in-time stock at the measurement date, not a period flow. */
    private Computed annualRecurringRevenue(UUID tenantId, KpiScope scope, ReportAccessScope.Scope access) {
        // Measured AT a date: the period filter is deliberately not applied to the
        // stock. Summing ARR over a period is a category error and is not offered.
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        StringBuilder where = new StringBuilder("f.tenant_id = ?");
        if (access.hasClause()) {
            where.append(" and ").append(access.sql());
            args.addAll(access.args());
        }
        Map<String, Object> row = jdbc.queryForMap("""
                select coalesce(sum(f.arr) filter (where f.is_won = true), 0) as arr,
                       count(*) filter (where f.is_won = true and f.arr is not null) as contributing
                  from analytics.opportunity_fact f
                 where """ + where, args.toArray());
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("measurementDate", scope.periodEnd());
        inputs.put("contributingAgreements", number(row.get("contributing")));
        return Computed.of(number(row.get("arr")), inputs,
                "A point-in-time stock at the measurement date, not a period flow — so the period"
                        + " filter is deliberately not applied.");
    }

    /** credited closed revenue in period / assigned quota for period. */
    private Computed quotaAttainment(UUID tenantId, KpiScope scope, ReportAccessScope.Scope access) {
        Map<String, Object> row = one("""
                select coalesce(sum(f.amount) filter (where f.is_won = true), 0) as credited
                  from analytics.opportunity_fact f
                 where %s
                """, tenantId, scope, access);
        BigDecimal credited = number(row.get("credited"));
        BigDecimal quota = assignedQuota(tenantId, scope);
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("creditedClosedRevenue", credited);
        inputs.put("assignedQuota", quota);
        inputs.put("creditBasis", "CLOSED_REVENUE");
        if (quota == null || quota.signum() == 0) {
            return Computed.missing(inputs,
                    "Attainment needs an assigned quota. None is configured for this period, so the"
                            + " percentage is withheld rather than implied.",
                    "assigned quota for the period (orgdata.quota)");
        }
        return Computed.of(divide(credited, quota), inputs,
                "Credit basis: closed revenue (not split-credited revenue). The basis is stated on"
                        + " the figure, per FR-FCT-012.");
    }

    /**
     * Forecast accuracy and bias, both computed against the LOCKED submission —
     * never against a number that was edited after the fact.
     */
    private Computed forecastAccuracy(UUID tenantId, KpiScope scope, boolean bias) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.owner_id, sum(s.submitted_amount) as submitted
                  from forecasting.forecast_submission s
                  join forecasting.forecast_period p
                    on p.tenant_id = s.tenant_id and p.id = s.period_id
                 where s.tenant_id = ? and p.period_start <= ? and p.period_end >= ?
                 group by s.owner_id
                """, tenantId, scope.periodEnd(), scope.periodStart());

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("submissionsFound", rows.size());
        if (rows.isEmpty()) {
            return Computed.missing(inputs,
                    (bias ? "Bias" : "Accuracy") + " is measured against a locked forecast submission."
                            + " No submission covers this period, so nothing is compared — a figure"
                            + " computed against a retroactively edited number would be worse than none.",
                    "a locked forecast submission for the period (FR-FCT-004)");
        }

        BigDecimal total = BigDecimal.ZERO;
        int counted = 0;
        List<Map<String, Object>> perOwner = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID ownerId = (UUID) row.get("owner_id");
            BigDecimal submitted = number(row.get("submitted"));
            BigDecimal actual = number(jdbc.queryForObject("""
                    select coalesce(sum(amount), 0) from analytics.opportunity_fact
                     where tenant_id = ? and owner_id = ? and is_won = true
                       and close_date between ? and ?
                    """, BigDecimal.class, tenantId, ownerId, scope.periodStart(), scope.periodEnd()));
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("ownerId", ownerId == null ? null : ownerId.toString());
            line.put("submitted", submitted);
            line.put("actual", actual);
            if (actual.signum() == 0) {
                // Both formulas divide by actual. An owner with nothing closed is
                // excluded and said so, rather than contributing a division by zero
                // dressed up as a 0% or a 100%.
                line.put("excluded", "no actual closed revenue in the period");
            } else {
                BigDecimal figure = bias
                        ? divide(submitted.subtract(actual), actual)
                        : BigDecimal.ONE.subtract(divide(submitted.subtract(actual).abs(), actual));
                line.put(bias ? "bias" : "accuracy", figure);
                total = total.add(figure);
                counted++;
            }
            perOwner.add(line);
        }
        inputs.put("perOwner", perOwner);
        inputs.put("ownersMeasured", counted);
        if (counted == 0) {
            return Computed.missing(inputs,
                    "Every submitting owner has zero actual closed revenue in the period, and both"
                            + " formulas divide by actual. No figure is produced.",
                    "actual closed revenue for at least one submitting owner");
        }
        return Computed.of(total.divide(BigDecimal.valueOf(counted), 6, RoundingMode.HALF_UP), inputs,
                bias ? "Signed: positive means habitual over-forecasting. Published together with"
                        + " accuracy, because accuracy alone hides direction."
                     : "Computed against the locked submission, never against a retroactively edited"
                        + " number. Published together with bias.");
    }

    /**
     * Slippage rate, with the denominator anchored to the period's OPENING
     * snapshot — which is the whole point of the metric and the reason snapshots
     * are immutable.
     */
    private Computed slippageRate(UUID tenantId, KpiScope scope, ReportAccessScope.Scope access) {
        Map<String, Object> opening = jdbc.query("""
                select id, captured_on, open_count, commit_amount + best_case_amount + pipeline_amount as forecast_amount
                  from analytics.forecast_snapshot
                 where tenant_id = ? and captured_on <= ?
                 order by captured_on asc
                 limit 1
                """, rs -> {
            if (!rs.next()) return null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("snapshotId", rs.getObject("id", UUID.class));
            row.put("capturedOn", rs.getDate("captured_on").toLocalDate());
            row.put("openCount", rs.getLong("open_count"));
            row.put("forecastAmount", rs.getBigDecimal("forecast_amount"));
            return row;
        }, tenantId, scope.periodStart());

        Map<String, Object> inputs = new LinkedHashMap<>();
        if (opening == null || number(opening.get("openCount")).signum() == 0) {
            inputs.put("openingSnapshot", opening);
            return Computed.missing(inputs,
                    "Slippage is measured against the population forecast at the period's OPENING"
                            + " snapshot. No snapshot exists at or before the period start, so the"
                            + " denominator cannot be anchored and no rate is shown. Reconstructing it"
                            + " from today's population would silently exclude deals that have already"
                            + " slipped — the error would flatter.",
                    "an immutable forecast snapshot at or before the period start");
        }

        BigDecimal denominator = number(opening.get("openCount"));
        // Deliberately NOT the shared period filter: the deals this metric counts are
        // exactly the ones whose close date has moved OUT of the period, so filtering
        // on the current close date would exclude every one of them and the rate would
        // always read zero.
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        StringBuilder where = new StringBuilder("f.tenant_id = ?");
        if (access.hasClause()) {
            where.append(" and ").append(access.sql());
            args.addAll(access.args());
        }
        where.append(" and f.original_close_date between ? and ?")
             .append(" and f.close_date is not null and f.close_date > ?");
        args.add(scope.periodStart());
        args.add(scope.periodEnd());
        args.add(scope.periodEnd());
        BigDecimal slipped = number(jdbc.queryForObject("""
                select count(*) from analytics.opportunity_fact f
                 where """ + where, BigDecimal.class, args.toArray()));

        inputs.put("openingSnapshot", opening);
        inputs.put("slippedOutOfPeriod", slipped);
        inputs.put("forecastAtOpening", denominator);
        return Computed.of(divide(slipped, denominator), inputs,
                "Denominator anchored to the opening snapshot, not to today's population. A"
                        + " value-weighted variant is a separately named metric.");
    }

    /** MQLs accepted by sales / MQLs handed off, cohorted by hand-off date. */
    private Computed mqlToSql(UUID tenantId, KpiScope scope) {
        ReportAccessScope.Scope leadAccess = accessScope.scopeFor(AnalyticsDataset.LEAD, "f");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        StringBuilder where = new StringBuilder("f.tenant_id = ?");
        if (leadAccess.hasClause()) {
            where.append(" and ").append(leadAccess.sql());
            args.addAll(leadAccess.args());
        }
        where.append(" and f.created_on between ? and ?");
        args.add(scope.periodStart());
        args.add(scope.periodEnd());

        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) filter (where f.status in ('QUALIFIED','CONVERTED')
                                           or f.is_converted) as accepted,
                       count(*) filter (where f.status in ('QUALIFIED','CONVERTED','DISQUALIFIED')
                                           or f.is_converted or f.is_disqualified) as handed_off,
                       count(*) filter (where f.is_disqualified) as rejected,
                       count(*) as leads_in_cohort
                  from analytics.lead_fact f
                 where """ + where, args.toArray());

        BigDecimal accepted = number(row.get("accepted"));
        BigDecimal handedOff = number(row.get("handed_off"));
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("acceptedBySales", accepted);
        inputs.put("handedOff", handedOff);
        inputs.put("rejectedWithReason", number(row.get("rejected")));
        inputs.put("leadsInCohort", number(row.get("leads_in_cohort")));
        if (handedOff.signum() == 0) {
            return Computed.missing(inputs,
                    "No lead in this cohort has been handed off to sales yet, so there is no"
                            + " denominator.",
                    "leads handed off to sales in the cohort period");
        }
        return Computed.of(divide(accepted, handedOff), inputs,
                "Cohorted by hand-off date. Rejections are reported alongside rather than hidden in"
                        + " the denominator.");
    }

    /** (attributed closed revenue - actual campaign cost) / actual campaign cost. */
    private Computed campaignRoi(UUID tenantId, KpiScope scope) {
        BigDecimal cost = number(jdbc.queryForObject("""
                select coalesce(sum(budget_amount), 0) from marketing.campaign
                 where tenant_id = ? and deleted_at is null
                   and coalesce(start_date, ?) <= ? and coalesce(end_date, ?) >= ?
                """, BigDecimal.class, tenantId, scope.periodStart(), scope.periodEnd(),
                scope.periodEnd(), scope.periodStart()));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("campaignCostInPeriod", cost);
        inputs.put("attributionModel", null);
        // Doc 14 §3: "ROI without its model named is not a valid output." No
        // attribution model is configured in this system, so the number is withheld
        // rather than computed under an unnamed model the reader would assume.
        return Computed.missing(inputs,
                "Campaign ROI requires a named attribution model and a sourced-versus-influenced"
                        + " definition. Neither is configured, and ROI without its model named is not"
                        + " a valid output — so the figure is withheld rather than computed under an"
                        + " assumption the reader cannot see.",
                "a configured attribution model (FR-CMP-005)",
                "sourced versus influenced revenue definition (FR-CMP-007)");
    }

    /** The doc's own worked example of an honestly withheld number. */
    private Computed cacPayback() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("campaignCost", "available");
        inputs.put("salesAndMarketingPayroll", null);
        inputs.put("toolingCost", null);
        inputs.put("costOfService", null);
        inputs.put("grossMarginPct", null);
        return Computed.missing(inputs,
                "Axiom holds campaign cost but not payroll, tooling or cost-of-service. Without those"
                        + " finance inputs this KPI is not computable, and it does not display a number"
                        + " built from partial cost data — which would be confidently wrong in the"
                        + " direction that flatters.",
                "sales and marketing cost from the finance system",
                "cost-of-service / gross margin %");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Assigned quota for the period, from the current version of the quota records
     * overlapping it. Restricted to the scoped owner when one is given, so a rep's
     * coverage is measured against their quota and not the team's.
     */
    private BigDecimal assignedQuota(UUID tenantId, KpiScope scope) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(scope.periodEnd());
        args.add(scope.periodStart());
        String ownerClause = "";
        if (scope.ownerId() != null) {
            ownerClause = " and q.subject_type = 'USER' and q.subject_id = ?";
            args.add(scope.ownerId());
        }
        return jdbc.queryForObject("""
                select coalesce(sum(q.target_amount), 0)
                  from orgdata.quota q
                  join orgdata.fiscal_period p on p.tenant_id = q.tenant_id and p.id = q.fiscal_period_id
                 where q.tenant_id = ? and q.is_current and q.measure = 'REVENUE'
                   and p.start_date <= ? and p.end_date >= ?
                """ + ownerClause, BigDecimal.class, args.toArray());
    }

    /**
     * Run an aggregate over {@code analytics.opportunity_fact} with the shared
     * where clause: tenant, access scope, period on close date, optional owner.
     *
     * <p>{@code %s} in the template is replaced with the composed predicate, so
     * every formula in this class filters identically — a metric that quietly used
     * a different period boundary would be a second definition by the back door.
     */
    private Map<String, Object> one(String template, UUID tenantId, KpiScope scope,
                                    ReportAccessScope.Scope access, Object... leadingArgs) {
        List<Object> args = new ArrayList<>(Arrays.asList(leadingArgs));
        args.add(tenantId);
        StringBuilder where = new StringBuilder("f.tenant_id = ?");
        if (access.hasClause()) {
            where.append(" and ").append(access.sql());
            args.addAll(access.args());
        }
        where.append(" and (f.close_date is null or f.close_date between ? and ?)");
        args.add(scope.periodStart());
        args.add(scope.periodEnd());
        if (scope.ownerId() != null) {
            where.append(" and f.owner_id = ?");
            args.add(scope.ownerId());
        }
        return jdbc.queryForMap(template.formatted(where.toString()), args.toArray());
    }

    private static KpiScope defaults(KpiScope requested) {
        LocalDate today = LocalDate.now();
        LocalDate start = requested != null && requested.periodStart() != null
                ? requested.periodStart()
                : today.withDayOfMonth(1).withMonth(((today.getMonthValue() - 1) / 3) * 3 + 1);
        LocalDate end = requested != null && requested.periodEnd() != null
                ? requested.periodEnd()
                : start.plusMonths(3).minusDays(1);
        return new KpiScope(start, end, requested == null ? null : requested.ownerId());
    }

    private static BigDecimal number(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(value.toString());
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return null;
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }
}

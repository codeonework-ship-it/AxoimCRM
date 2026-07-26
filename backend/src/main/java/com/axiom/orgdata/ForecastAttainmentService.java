package com.axiom.orgdata;

import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Forecast attainment: targets against actuals, rolled up the territory
 * hierarchy (FR-FCT-020..034).
 *
 * <h2>What already existed, and what was missing</h2>
 * Targets exist — {@link QuotaService} writes {@code orgdata.quota} with a
 * governance gate, versioning and supersede history, and it is already exposed.
 * Territories exist with a materialised {@code path} for hierarchy. Fiscal periods
 * exist. What did not exist was the thing that makes any of it a forecast: joining
 * a target to what has actually been sold against it, and aggregating that up the
 * territory tree. That join is this service and nothing else.
 *
 * <h2>Actuals, commit and pipeline are three different numbers</h2>
 * Reporting one "achieved" figure hides the question a forecast exists to answer.
 * <ul>
 *   <li><b>Closed won</b> — banked. The only number that is not an opinion.</li>
 *   <li><b>Commit</b> — open deals whose stage is classified COMMIT. What the
 *       owner is standing behind.</li>
 *   <li><b>Weighted pipeline</b> — every open deal at its stage probability.
 *       Useful for coverage, and deliberately not added to the others: adding a
 *       weighted number to a banked one produces a figure that is neither.</li>
 * </ul>
 *
 * <h2>Roll-up uses the stored path, not recursion in Java</h2>
 * {@code orgdata.territory.path} is materialised precisely so a subtree is a
 * prefix match. Walking the tree in application code would issue one query per
 * node and could disagree with the path the database maintains.
 */
@Service
public class ForecastAttainmentService {

    private final JdbcTemplate jdbc;

    public ForecastAttainmentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --------------------------------------------------------------- contracts

    /**
     * @param attainmentPct closed won as a percentage of target; null when there
     *                      is no target, because 0% and "no target set" are
     *                      different findings and a chart must not conflate them
     * @param coverageRatio open weighted pipeline against the remaining gap —
     *                      the number that says whether the gap is reachable
     */
    public record Attainment(String subjectType, UUID subjectId, String subjectLabel,
                             String territoryCode, String territoryPath, int depth,
                             BigDecimal target, BigDecimal closedWon, BigDecimal commit,
                             BigDecimal weightedPipeline, BigDecimal openTotal,
                             BigDecimal gap, BigDecimal attainmentPct, BigDecimal coverageRatio,
                             int openDealCount, String currencyCode, String note) {}

    public record PeriodAttainment(UUID fiscalPeriodId, String periodLabel,
                                   LocalDate periodStart, LocalDate periodEnd,
                                   BigDecimal totalTarget, BigDecimal totalClosedWon,
                                   BigDecimal totalCommit, BigDecimal totalWeightedPipeline,
                                   BigDecimal attainmentPct, BigDecimal coverageRatio,
                                   List<Attainment> byOwner, List<Attainment> byTerritory,
                                   String note) {}

    // ------------------------------------------------------------------- entry

    /**
     * Attainment for one fiscal period, by owner and by territory.
     *
     * <p>Read-only and computed on demand rather than stored. A stored attainment
     * row is stale the moment a deal moves, and a number that is sometimes stale is
     * worse than one that is always computed — nobody can tell which they are
     * looking at.
     */
    @Transactional(readOnly = true)
    public PeriodAttainment forPeriod(UUID fiscalPeriodId) {
        UUID tenant = TenantContext.get().tenantId();
        Period period = period(tenant, fiscalPeriodId);

        List<Attainment> byOwner = ownerAttainment(tenant, period);
        List<Attainment> byTerritory = territoryAttainment(tenant, period);

        BigDecimal target = sum(byOwner, Attainment::target);
        BigDecimal won = sum(byOwner, Attainment::closedWon);
        BigDecimal commit = sum(byOwner, Attainment::commit);
        BigDecimal weighted = sum(byOwner, Attainment::weightedPipeline);

        return new PeriodAttainment(period.id(), period.label(), period.start(), period.end(),
                target, won, commit, weighted,
                pct(won, target), coverage(weighted, target.subtract(won)),
                byOwner, byTerritory,
                "Closed won is banked. Commit and weighted pipeline are open and deliberately not "
                        + "added to it — mixing a banked number with a probability-weighted one produces "
                        + "a figure that is neither.");
    }

    // ------------------------------------------------------------------ by owner

    private List<Attainment> ownerAttainment(UUID tenant, Period period) {
        /*
         * One pass over the period's opportunities, split by what the stage says
         * the deal is. Doing this as three separate queries would let them disagree
         * if a deal moved between them.
         */
        Map<UUID, Deal> deals = new LinkedHashMap<>();
        jdbc.query("""
                select o.owner_id,
                       coalesce(sum(case when s.is_won then o.amount else 0 end), 0)            as won,
                       coalesce(sum(case when not s.is_closed and s.forecast_category = 'COMMIT'
                                         then o.amount else 0 end), 0)                          as commit_amount,
                       coalesce(sum(case when not s.is_closed
                                         then o.amount * (s.probability / 100.0) else 0 end), 0) as weighted,
                       coalesce(sum(case when not s.is_closed then o.amount else 0 end), 0)      as open_total,
                       count(case when not s.is_closed then 1 end)                              as open_count
                from sales.opportunity o
                join crm.pipeline_stage s on s.tenant_id = o.tenant_id and s.id = o.stage_id
                where o.tenant_id = ?
                    and o.close_date between ? and ?
                group by o.owner_id
                """, (org.springframework.jdbc.core.RowCallbackHandler) (rs) -> {
            deals.put(rs.getObject("owner_id", UUID.class), new Deal(
                    rs.getBigDecimal("won"), rs.getBigDecimal("commit_amount"),
                    rs.getBigDecimal("weighted"), rs.getBigDecimal("open_total"),
                    rs.getInt("open_count")));
        }, tenant, period.start(), period.end());

        // Targets for this period, current version only.
        Map<UUID, Target> targets = new LinkedHashMap<>();
        jdbc.query("""
                select q.subject_id, q.subject_label, q.target_amount, q.currency_code
                from orgdata.quota q
                where q.tenant_id = ? and q.fiscal_period_id = ? and q.is_current
                  and q.subject_type = 'USER' and q.measure = 'REVENUE'
                """, (org.springframework.jdbc.core.RowCallbackHandler) (rs) -> targets.put(rs.getObject("subject_id", UUID.class),
                        new Target(rs.getString("subject_label"), rs.getBigDecimal("target_amount"),
                                rs.getString("currency_code"))),
                tenant, period.id());

        // Names for everyone who appears on either side.
        Map<UUID, String> names = new LinkedHashMap<>();
        jdbc.query("select id, display_name from identity.app_user where tenant_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) (rs) -> names.put(rs.getObject("id", UUID.class), rs.getString("display_name")), tenant);

        /*
         * The union of both sides, not just the targets. A rep with a target and no
         * deals is the most important row on the page, and a rep selling with no
         * target set is a governance gap worth surfacing rather than hiding.
         */
        List<UUID> subjects = new ArrayList<>(targets.keySet());
        deals.keySet().forEach((id) -> { if (id != null && !subjects.contains(id)) subjects.add(id); });

        List<Attainment> out = new ArrayList<>();
        for (UUID subject : subjects) {
            Target target = targets.get(subject);
            Deal deal = deals.getOrDefault(subject, Deal.EMPTY);
            String label = target != null && target.label() != null ? target.label()
                    : names.getOrDefault(subject, "Unassigned");
            out.add(build("USER", subject, label, null, null, 0, target, deal));
        }
        out.sort((a, b) -> b.closedWon().compareTo(a.closedWon()));
        return out;
    }

    // -------------------------------------------------------------- by territory

    /**
     * Territory attainment, rolled up.
     *
     * <p>Every territory reports its own subtree: the path prefix match means a
     * region's number includes every district beneath it, which is what a regional
     * manager expects to see. A parent whose only target is on its children still
     * reports their sum, so the hierarchy adds up.
     */
    private List<Attainment> territoryAttainment(UUID tenant, Period period) {
        List<Map<String, Object>> territories = jdbc.queryForList("""
                select t.id, t.code, t.name, t.path,
                       coalesce(array_length(string_to_array(t.path, '.'), 1), 1) as depth
                from orgdata.territory t
                where t.tenant_id = ? and t.active
                order by t.path
                """, tenant);
        if (territories.isEmpty()) return List.of();

        Map<UUID, Target> targets = new LinkedHashMap<>();
        jdbc.query("""
                select q.subject_id, q.subject_label, q.target_amount, q.currency_code
                from orgdata.quota q
                where q.tenant_id = ? and q.fiscal_period_id = ? and q.is_current
                  and q.subject_type = 'TERRITORY' and q.measure = 'REVENUE'
                """, (org.springframework.jdbc.core.RowCallbackHandler) (rs) -> {
            targets.put(rs.getObject("subject_id", UUID.class),
                    new Target(rs.getString("subject_label"), rs.getBigDecimal("target_amount"),
                            rs.getString("currency_code")));
        }, tenant, period.id());

        List<Attainment> out = new ArrayList<>();
        for (Map<String, Object> territory : territories) {
            UUID id = (UUID) territory.get("id");
            String path = String.valueOf(territory.get("path"));

            /*
             * Subtree by path prefix. `path = ?` OR `path LIKE ? || '.%'` rather than
             * a bare LIKE, so "EMEA" cannot also match "EMEA_LEGACY" — a prefix
             * match without the separator silently merges unrelated territories.
             */
            Deal deal = jdbc.query("""
                    select coalesce(sum(case when s.is_won then o.amount else 0 end), 0)            as won,
                           coalesce(sum(case when not s.is_closed and s.forecast_category = 'COMMIT'
                                             then o.amount else 0 end), 0)                          as commit_amount,
                           coalesce(sum(case when not s.is_closed
                                             then o.amount * (s.probability / 100.0) else 0 end), 0) as weighted,
                           coalesce(sum(case when not s.is_closed then o.amount else 0 end), 0)      as open_total,
                           count(case when not s.is_closed then 1 end)                              as open_count
                    from sales.opportunity o
                    join crm.pipeline_stage s on s.tenant_id = o.tenant_id and s.id = o.stage_id
                    join orgdata.territory_member m
                          on m.tenant_id = o.tenant_id and m.user_id = o.owner_id
                    join orgdata.territory tt
                          on tt.tenant_id = m.tenant_id and tt.id = m.territory_id
                where o.tenant_id = ?
                        and o.close_date between ? and ?
                      and (tt.path = ? or tt.path like ? || '.%')
                    """, (org.springframework.jdbc.core.ResultSetExtractor<Deal>) (rs) -> rs.next() ? new Deal(rs.getBigDecimal("won"),
                            rs.getBigDecimal("commit_amount"), rs.getBigDecimal("weighted"),
                            rs.getBigDecimal("open_total"), rs.getInt("open_count")) : Deal.EMPTY,
                    tenant, period.start(), period.end(), path, path);

            out.add(build("TERRITORY", id, String.valueOf(territory.get("name")),
                    String.valueOf(territory.get("code")), path,
                    ((Number) territory.get("depth")).intValue(), targets.get(id),
                    deal == null ? Deal.EMPTY : deal));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Attainment build(String subjectType, UUID subjectId, String label, String territoryCode,
                             String path, int depth, Target target, Deal deal) {
        BigDecimal targetAmount = target == null ? BigDecimal.ZERO : target.amount();
        BigDecimal gap = targetAmount.subtract(deal.won()).max(BigDecimal.ZERO);
        String note = target == null
                ? "No target is set for this period, so attainment cannot be calculated."
                : deal.won().compareTo(targetAmount) >= 0
                        ? "Target met."
                        : "Open weighted pipeline covers "
                          + coverage(deal.weighted(), gap).toPlainString() + "x the remaining gap.";
        return new Attainment(subjectType, subjectId, label, territoryCode, path, depth,
                targetAmount, scale(deal.won()), scale(deal.commit()), scale(deal.weighted()),
                scale(deal.openTotal()), gap,
                // null, not zero: "no target" and "0% of target" are different findings.
                target == null ? null : pct(deal.won(), targetAmount),
                coverage(deal.weighted(), gap), deal.openCount(),
                target == null ? "INR" : target.currency(), note);
    }

    /**
     * The period, read from {@code orgdata.fiscal_period}.
     *
     * <p>Not {@code forecasting.forecast_period}, which also exists and also has a
     * label and a date range. {@code orgdata.quota.fiscal_period_id} has a foreign
     * key to the orgdata one, so a target can only ever be set against a fiscal
     * period — reading the forecasting table here would have produced an attainment
     * report where no target ever matched, and it would have looked like "no quotas
     * set" rather than a wrong join.
     */
    private Period period(UUID tenant, UUID fiscalPeriodId) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                select id, label, period_type, start_date, end_date from orgdata.fiscal_period
                where tenant_id = ? and id = ?
                """, tenant, fiscalPeriodId);
        if (found.isEmpty()) {
            throw new NotFoundException("That fiscal period does not exist in this workspace. "
                    + "Targets are set against orgdata fiscal periods; list them from the "
                    + "fiscal-calendar endpoint.");
        }
        Map<String, Object> row = found.get(0);
        return new Period((UUID) row.get("id"),
                String.valueOf(row.get("label")) + " (" + row.get("period_type") + ")",
                ((java.sql.Date) row.get("start_date")).toLocalDate(),
                ((java.sql.Date) row.get("end_date")).toLocalDate());
    }

    /** Attainment against a zero target is undefined, not infinite. */
    private static BigDecimal pct(BigDecimal achieved, BigDecimal target) {
        if (target == null || target.signum() == 0) return BigDecimal.ZERO;
        return achieved.multiply(new BigDecimal("100")).divide(target, 2, RoundingMode.HALF_UP);
    }

    /**
     * Coverage against a closed gap is reported as zero rather than infinity.
     * A tile showing "∞x coverage" for a target already met is noise.
     */
    private static BigDecimal coverage(BigDecimal pipeline, BigDecimal gap) {
        if (gap == null || gap.signum() <= 0) return BigDecimal.ZERO;
        return pipeline.divide(gap, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<Attainment> rows,
                                  java.util.function.Function<Attainment, BigDecimal> pick) {
        return rows.stream().map(pick).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private record Period(UUID id, String label, LocalDate start, LocalDate end) {}

    private record Target(String label, BigDecimal amount, String currency) {}

    private record Deal(BigDecimal won, BigDecimal commit, BigDecimal weighted,
                        BigDecimal openTotal, int openCount) {
        static final Deal EMPTY = new Deal(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0);
    }
}

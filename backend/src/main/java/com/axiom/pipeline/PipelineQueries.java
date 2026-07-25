package com.axiom.pipeline;

import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read access to pipeline configuration and the facts a stage gate is evaluated
 * against. Separated from the services that mutate so the gate's inputs are
 * loaded in exactly one place.
 */
@Component
public class PipelineQueries {

    private final JdbcTemplate jdbc;

    public PipelineQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static UUID tenantId() {
        return TenantContext.get().tenantId();
    }

    /**
     * A stage with its position expressed as a dense {@code rank} within its
     * pipeline. Rank — not {@code sortOrder} — decides what counts as a skip:
     * tenants number their stages 1,2,3 or 10,20,30 and both are legitimate.
     */
    public record StageRow(UUID id, UUID pipelineId, String pipelineName, String name, int sortOrder, int rank,
                           boolean closed, boolean won, boolean requiresEconomicBuyer,
                           boolean allowsBackward, boolean allowsSkip,
                           BigDecimal probability, String forecastCategory, int stalledAfterDays) {}

    public record PipelineRow(UUID id, String apiName, String name, String description,
                              boolean isDefault, boolean active, int stageCount) {}

    private static final String STAGE_SELECT = """
            select s.id, s.pipeline_id, p.name as pipeline_name, s.name, s.sort_order,
                   (select count(*) + 1 from crm.pipeline_stage s2
                     where s2.tenant_id = s.tenant_id and s2.pipeline_id = s.pipeline_id
                       and s2.deleted_at is null and s2.sort_order < s.sort_order)::int as rank,
                   s.is_closed, s.is_won, s.requires_economic_buyer, s.allows_backward, s.allows_skip,
                   s.probability, s.forecast_category, s.stalled_after_days
            from crm.pipeline_stage s
            join pipeline.pipeline p on p.tenant_id = s.tenant_id and p.id = s.pipeline_id
            where s.tenant_id = ? and s.deleted_at is null
            """;

    private static StageRow mapStage(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new StageRow(
                rs.getObject("id", UUID.class),
                rs.getObject("pipeline_id", UUID.class),
                rs.getString("pipeline_name"),
                rs.getString("name"),
                rs.getInt("sort_order"),
                rs.getInt("rank"),
                rs.getBoolean("is_closed"),
                rs.getBoolean("is_won"),
                rs.getBoolean("requires_economic_buyer"),
                rs.getBoolean("allows_backward"),
                rs.getBoolean("allows_skip"),
                rs.getBigDecimal("probability"),
                rs.getString("forecast_category"),
                rs.getInt("stalled_after_days"));
    }

    public StageRow stage(UUID stageId) {
        List<StageRow> rows = jdbc.query(STAGE_SELECT + " and s.id = ?", PipelineQueries::mapStage,
                tenantId(), stageId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Pipeline stage not found: " + stageId);
        }
        return rows.get(0);
    }

    public List<StageRow> stages(UUID pipelineId) {
        return jdbc.query(STAGE_SELECT + " and s.pipeline_id = ? order by s.sort_order",
                PipelineQueries::mapStage, tenantId(), pipelineId);
    }

    public List<PipelineRow> pipelines() {
        return jdbc.query("""
                select p.id, p.api_name, p.name, p.description, p.is_default, p.active,
                       (select count(*) from crm.pipeline_stage s
                         where s.tenant_id = p.tenant_id and s.pipeline_id = p.id and s.deleted_at is null)::int as stage_count
                from pipeline.pipeline p
                where p.tenant_id = ?
                order by p.is_default desc, p.name
                """, (rs, i) -> new PipelineRow(
                rs.getObject("id", UUID.class),
                rs.getString("api_name"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("is_default"),
                rs.getBoolean("active"),
                rs.getInt("stage_count")), tenantId());
    }

    public UUID defaultPipelineId() {
        List<UUID> ids = jdbc.queryForList(
                "select id from pipeline.pipeline where tenant_id = ? and is_default and active limit 1",
                UUID.class, tenantId());
        if (ids.isEmpty()) {
            throw new NotFoundException("No default pipeline is configured for this workspace");
        }
        return ids.get(0);
    }

    // ------------------------------------------------------------ gate criteria

    /** The version pinned on the opportunity's open stage-history row, if any. */
    public Optional<UUID> pinnedExitVersionId(UUID opportunityId) {
        List<UUID> ids = jdbc.queryForList("""
                select criteria_version_id from sales.stage_history
                where tenant_id = ? and opportunity_id = ? and exited_at is null
                order by entered_at desc limit 1
                """, UUID.class, tenantId(), opportunityId);
        return ids.isEmpty() || ids.get(0) == null ? Optional.empty() : Optional.of(ids.get(0));
    }

    /** The newest published version for a stage and gate — what a new entry pins. */
    public Optional<UUID> currentVersionId(UUID stageId, String gate) {
        List<UUID> ids = jdbc.queryForList("""
                select id from pipeline.stage_criteria_version
                where tenant_id = ? and stage_id = ? and gate = ? and effective_from <= now()
                order by version_number desc limit 1
                """, UUID.class, tenantId(), stageId, gate);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    public StageGate.Version version(UUID versionId, UUID stageId, String gate) {
        if (versionId == null) {
            return StageGate.Version.empty(stageId, gate);
        }
        List<StageGate.Version> heads = jdbc.query("""
                select id, stage_id, gate, version_number
                from pipeline.stage_criteria_version
                where tenant_id = ? and id = ?
                """, (rs, i) -> new StageGate.Version(
                rs.getObject("id", UUID.class),
                rs.getObject("stage_id", UUID.class),
                rs.getString("gate"),
                rs.getInt("version_number"),
                List.of()), tenantId(), versionId);
        if (heads.isEmpty()) {
            return StageGate.Version.empty(stageId, gate);
        }
        StageGate.Version head = heads.get(0);
        List<StageGate.Criterion> criteria = jdbc.query("""
                select id, code, label, criterion_type, expression::text as expression, message, remediation, sort_order
                from pipeline.stage_exit_criterion
                where tenant_id = ? and criteria_version_id = ?
                order by sort_order, code
                """, (rs, i) -> new StageGate.Criterion(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("label"),
                rs.getString("criterion_type"),
                rs.getString("expression"),
                rs.getString("message"),
                rs.getString("remediation"),
                rs.getInt("sort_order")), tenantId(), versionId);
        return new StageGate.Version(head.id(), head.stageId(), head.gate(), head.versionNumber(), criteria);
    }

    /** All published versions for a stage, newest first — the audit view. */
    public record VersionSummary(UUID id, String gate, int versionNumber, Instant publishedAt,
                                 String notes, List<StageGate.Criterion> criteria) {}

    public List<VersionSummary> versionHistory(UUID stageId) {
        List<VersionSummary> heads = jdbc.query("""
                select id, gate, version_number, published_at, notes
                from pipeline.stage_criteria_version
                where tenant_id = ? and stage_id = ?
                order by gate, version_number desc
                """, (rs, i) -> new VersionSummary(
                rs.getObject("id", UUID.class),
                rs.getString("gate"),
                rs.getInt("version_number"),
                rs.getTimestamp("published_at").toInstant(),
                rs.getString("notes"),
                List.of()), tenantId(), stageId);
        List<VersionSummary> out = new ArrayList<>(heads.size());
        for (VersionSummary head : heads) {
            out.add(new VersionSummary(head.id(), head.gate(), head.versionNumber(), head.publishedAt(),
                    head.notes(), version(head.id(), stageId, head.gate()).criteria()));
        }
        return out;
    }

    // -------------------------------------------------------------------- facts

    public StageGate.Facts facts(UUID opportunityId) {
        UUID tid = tenantId();
        Map<String, Object> row = jdbc.queryForMap("""
                select o.id, o.name, o.amount, o.close_date, o.next_step, o.recurring_amount,
                       o.term_months, o.qualification_score
                from sales.opportunity o
                where o.tenant_id = ? and o.id = ?
                """, tid, opportunityId);

        Set<String> roles = new HashSet<>(jdbc.queryForList(
                "select distinct role from sales.opportunity_contact_role where tenant_id = ? and opportunity_id = ?",
                String.class, tid, opportunityId));

        int lineCount = count("select count(*) from sales.opportunity_line where tenant_id = ? and opportunity_id = ?",
                tid, opportunityId);
        int competitorCount = count(
                "select count(*) from sales.opportunity_competitor where tenant_id = ? and opportunity_id = ?",
                tid, opportunityId);

        Map<String, String> approvals = new HashMap<>();
        jdbc.query("select approval_type, state from sales.opportunity_approval where tenant_id = ? and opportunity_id = ?",
                rs -> { approvals.put(rs.getString("approval_type"), rs.getString("state")); },
                tid, opportunityId);

        Map<String, Integer> activityCounts = new HashMap<>();
        Map<String, Instant> activityLast = new HashMap<>();
        jdbc.query("""
                select activity_type, count(*)::int as cnt, max(completed_at) as last_at
                from engagement.activity
                where tenant_id = ? and related_entity_type = 'OPPORTUNITY' and related_entity_id = ?
                  and status = 'COMPLETED' and deleted_at is null
                group by activity_type
                """, rs -> {
            activityCounts.put(rs.getString("activity_type"), rs.getInt("cnt"));
            Timestamp last = rs.getTimestamp("last_at");
            if (last != null) activityLast.put(rs.getString("activity_type"), last.toInstant());
        }, tid, opportunityId);

        return new StageGate.Facts(
                (UUID) row.get("id"),
                (String) row.get("name"),
                (BigDecimal) row.get("amount"),
                row.get("close_date") == null ? null : ((java.sql.Date) row.get("close_date")).toLocalDate(),
                (String) row.get("next_step"),
                (BigDecimal) row.get("recurring_amount"),
                row.get("term_months") == null ? null : ((Number) row.get("term_months")).intValue(),
                (BigDecimal) row.get("qualification_score"),
                roles, lineCount, competitorCount, approvals, activityCounts, activityLast, Instant.now());
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // --------------------------------------------------------- reference lookups

    public record CloseReasonRow(UUID id, String code, String label, String outcome,
                                 boolean requiresCompetitor, int sortOrder) {}

    public List<CloseReasonRow> closeReasons(String outcome) {
        String filter = outcome == null || outcome.isBlank() ? null : outcome.trim().toUpperCase(java.util.Locale.ROOT);
        return jdbc.query("""
                select id, code, label, outcome, requires_competitor, sort_order
                from pipeline.close_reason
                where tenant_id = ? and active and (? is null or outcome = ?)
                order by outcome, sort_order
                """, (rs, i) -> new CloseReasonRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("label"),
                rs.getString("outcome"),
                rs.getBoolean("requires_competitor"),
                rs.getInt("sort_order")), tenantId(), filter, filter);
    }

    public record CompetitorRow(UUID id, String name, String notes, boolean active) {}

    public List<CompetitorRow> competitors() {
        return jdbc.query("""
                select id, name, notes, active from pipeline.competitor
                where tenant_id = ? and active order by name
                """, (rs, i) -> new CompetitorRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("notes"),
                rs.getBoolean("active")), tenantId());
    }

    public record PriceBookEntryRow(UUID id, String productCode, String productName, String unitOfMeasure,
                                    BigDecimal listPrice, BigDecimal unitCost, boolean recurring) {}

    public List<PriceBookEntryRow> priceBookEntries() {
        return jdbc.query("""
                select e.id, e.product_code, e.product_name, e.unit_of_measure, e.list_price, e.unit_cost, e.recurring
                from pipeline.price_book_entry e
                join pipeline.price_book b on b.tenant_id = e.tenant_id and b.id = e.price_book_id
                where e.tenant_id = ? and e.active and b.active
                order by e.product_name
                """, (rs, i) -> new PriceBookEntryRow(
                rs.getObject("id", UUID.class),
                rs.getString("product_code"),
                rs.getString("product_name"),
                rs.getString("unit_of_measure"),
                rs.getBigDecimal("list_price"),
                rs.getBigDecimal("unit_cost"),
                rs.getBoolean("recurring")), tenantId());
    }

    public record ClosedStagePair(UUID wonStageId, UUID lostStageId) {}

    public ClosedStagePair closedStages(UUID pipelineId) {
        UUID won = null;
        UUID lost = null;
        for (StageRow s : stages(pipelineId)) {
            if (!s.closed()) continue;
            if (s.won() && won == null) won = s.id();
            if (!s.won() && lost == null) lost = s.id();
        }
        if (won == null || lost == null) {
            throw new NotFoundException(
                    "This pipeline has no closed-won and closed-lost stages configured; closure has nowhere to land");
        }
        return new ClosedStagePair(won, lost);
    }

    /** Convenience: the first (lowest-ranked) open stage, used when reopening. */
    public StageRow firstOpenStage(UUID pipelineId) {
        for (StageRow s : stages(pipelineId)) {
            if (!s.closed()) return s;
        }
        throw new NotFoundException("This pipeline has no open stages configured");
    }
}

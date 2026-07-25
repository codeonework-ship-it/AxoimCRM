package com.axiom.pipeline;

import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deal risk signals (FR-OPP-009).
 *
 * <p>Every signal states three things: the observation, why it matters, and a
 * recommended action. A dashboard that shows a red dot and nothing else moves
 * the diagnosis onto the user; the requirement exists to stop that.
 *
 * <p>Each signal also carries {@code evidence} naming the records — or the
 * absence of records — that produced it, so a rep can check the system's work.
 */
@Service
public class RiskSignalService {

    /** Days without a completed activity before engagement counts as a gap. */
    static final int ENGAGEMENT_GAP_DAYS = 14;

    private final JdbcTemplate jdbc;

    public RiskSignalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Signal(String code, String title, String severity, String observation,
                         String whyItMatters, String recommendedAction, String evidence) {}

    /** The inputs a signal is derived from — flat, so tests need no database. */
    public record Inputs(String opportunityName,
                         String stageName,
                         int daysInStage,
                         int stalledAfterDays,
                         Integer daysSinceLastActivity,
                         int completedActivityCount,
                         int engagedContactCount,
                         boolean hasEconomicBuyer,
                         int slipCount,
                         int cumulativeSlipDays,
                         LocalDate originalCloseDate,
                         LocalDate closeDate,
                         List<String> activeCompetitors,
                         List<String> threateningCompetitors,
                         BigDecimal amount) {}

    @Transactional(readOnly = true)
    public List<Signal> signalsFor(UUID opportunityId) {
        return evaluate(load(opportunityId));
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<Signal>> signalsForPipeline(UUID pipelineId) {
        Map<UUID, List<Signal>> out = new LinkedHashMap<>();
        List<UUID> ids = jdbc.queryForList("""
                select id from sales.opportunity
                where tenant_id = ? and pipeline_id = ? and is_closed = false
                """, UUID.class, TenantContext.get().tenantId(), pipelineId);
        for (UUID id : ids) {
            out.put(id, evaluate(load(id)));
        }
        return out;
    }

    Inputs load(UUID opportunityId) {
        UUID tid = TenantContext.get().tenantId();
        List<Inputs> rows = jdbc.query("""
                select o.name,
                       s.name as stage_name,
                       greatest(0, extract(day from (now() - o.stage_entered_at))::int) as days_in_stage,
                       s.stalled_after_days,
                       (select extract(day from (now() - max(a.completed_at)))::int
                          from engagement.activity a
                         where a.tenant_id = o.tenant_id and a.related_entity_type = 'OPPORTUNITY'
                           and a.related_entity_id = o.id and a.status = 'COMPLETED'
                           and a.deleted_at is null) as days_since_activity,
                       (select count(*)::int from engagement.activity a
                         where a.tenant_id = o.tenant_id and a.related_entity_type = 'OPPORTUNITY'
                           and a.related_entity_id = o.id and a.status = 'COMPLETED'
                           and a.deleted_at is null) as completed_activities,
                       (select count(distinct r.contact_id)::int from sales.opportunity_contact_role r
                         where r.tenant_id = o.tenant_id and r.opportunity_id = o.id) as engaged_contacts,
                       exists (select 1 from sales.opportunity_contact_role r
                                where r.tenant_id = o.tenant_id and r.opportunity_id = o.id
                                  and r.role = 'ECONOMIC_BUYER') as has_economic_buyer,
                       o.slip_count, o.cumulative_slip_days, o.original_close_date, o.close_date, o.amount
                from sales.opportunity o
                join crm.pipeline_stage s on s.tenant_id = o.tenant_id and s.id = o.stage_id
                where o.tenant_id = ? and o.id = ?
                """, (rs, i) -> new Inputs(
                rs.getString("name"),
                rs.getString("stage_name"),
                rs.getInt("days_in_stage"),
                rs.getInt("stalled_after_days"),
                rs.getObject("days_since_activity") == null ? null : rs.getInt("days_since_activity"),
                rs.getInt("completed_activities"),
                rs.getInt("engaged_contacts"),
                rs.getBoolean("has_economic_buyer"),
                rs.getInt("slip_count"),
                rs.getInt("cumulative_slip_days"),
                rs.getObject("original_close_date", LocalDate.class),
                rs.getObject("close_date", LocalDate.class),
                List.of(), List.of(),
                rs.getBigDecimal("amount")), tid, opportunityId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Opportunity not found: " + opportunityId);
        }
        List<String> active = jdbc.queryForList("""
                select c.name from sales.opportunity_competitor oc
                join pipeline.competitor c on c.tenant_id = oc.tenant_id and c.id = oc.competitor_id
                where oc.tenant_id = ? and oc.opportunity_id = ? and oc.position <> 'ELIMINATED'
                order by c.name
                """, String.class, tid, opportunityId);
        List<String> threatening = jdbc.queryForList("""
                select c.name from sales.opportunity_competitor oc
                join pipeline.competitor c on c.tenant_id = oc.tenant_id and c.id = oc.competitor_id
                where oc.tenant_id = ? and oc.opportunity_id = ? and oc.position in ('LEADING', 'THREAT')
                order by c.name
                """, String.class, tid, opportunityId);
        Inputs base = rows.get(0);
        return new Inputs(base.opportunityName(), base.stageName(), base.daysInStage(), base.stalledAfterDays(),
                base.daysSinceLastActivity(), base.completedActivityCount(), base.engagedContactCount(),
                base.hasEconomicBuyer(), base.slipCount(), base.cumulativeSlipDays(), base.originalCloseDate(),
                base.closeDate(), active, threatening, base.amount());
    }

    /** Pure evaluation, so each signal's wording is pinned by a unit test. */
    public List<Signal> evaluate(Inputs in) {
        List<Signal> signals = new ArrayList<>();

        if (in.daysSinceLastActivity() == null) {
            signals.add(new Signal("ENGAGEMENT_GAP", "No recorded engagement", "HIGH",
                    "No completed activity has ever been logged against this opportunity.",
                    "A deal with no recorded contact cannot be forecast honestly — there is no evidence the customer "
                            + "is still engaged, and nobody covering for you could pick it up.",
                    "Log the last call or meeting you had, then book the next one.",
                    "0 completed activities related to this opportunity."));
        } else if (in.daysSinceLastActivity() > ENGAGEMENT_GAP_DAYS) {
            signals.add(new Signal("ENGAGEMENT_GAP", "Engagement gap", "MEDIUM",
                    "The last completed activity was " + in.daysSinceLastActivity() + " days ago, beyond the "
                            + ENGAGEMENT_GAP_DAYS + "-day threshold.",
                    "Deals go quiet before they are lost. The longer the silence, the more likely the customer's "
                            + "priorities have moved and the forecast is stale.",
                    "Contact the buying committee this week and record the outcome, or move the close date to "
                            + "something you actually believe.",
                    "Most recent completed activity: " + in.daysSinceLastActivity() + " days ago ("
                            + in.completedActivityCount() + " completed in total)."));
        }

        if (in.engagedContactCount() <= 1) {
            signals.add(new Signal("SINGLE_THREADED", "Single-threaded relationship", "HIGH",
                    in.engagedContactCount() == 0
                            ? "No contact roles are recorded on this opportunity at all."
                            : "Only one contact is recorded on this opportunity.",
                    "If your one relationship changes job, goes on leave or loses the internal argument, the deal "
                            + "goes with them. Multi-threaded deals close at materially higher rates.",
                    "Identify and meet at least two more people in the buying committee — typically a technical "
                            + "evaluator and the economic buyer — and record them as contact roles.",
                    in.engagedContactCount() + " distinct contact(s) with a role on this opportunity."));
        }

        if (in.slipCount() > 0) {
            signals.add(new Signal("CLOSE_DATE_SLIPPAGE", "Close date slippage", in.slipCount() >= 2 ? "HIGH" : "MEDIUM",
                    "The close date has slipped " + in.slipCount() + " time(s), by " + in.cumulativeSlipDays()
                            + " days in total"
                            + (in.originalCloseDate() == null ? "" : " from an original date of " + in.originalCloseDate())
                            + ".",
                    "Repeated slippage almost always means an unresolved blocker nobody has named — a missing "
                            + "approval, an unfunded budget or a decision process you do not yet understand.",
                    "Name the specific event that will move this deal forward and date it. If you cannot, the deal "
                            + "belongs in an earlier stage or out of the forecast.",
                    "slip_count = " + in.slipCount() + ", cumulative_slip_days = " + in.cumulativeSlipDays()
                            + ", current close date " + in.closeDate() + "."));
        }

        if (in.daysInStage() > in.stalledAfterDays()) {
            signals.add(new Signal("STALLED_STAGE", "Stalled in stage", "MEDIUM",
                    "This opportunity has been in " + in.stageName() + " for " + in.daysInStage()
                            + " days; the stage is configured to flag after " + in.stalledAfterDays() + ".",
                    "Time in stage is the earliest reliable warning that a deal has stopped progressing, and it "
                            + "shows up long before the close date does.",
                    "Either complete the exit criteria for " + in.stageName()
                            + " and advance it, or move it back to the stage that reflects reality.",
                    in.daysInStage() + " days in " + in.stageName() + " (threshold " + in.stalledAfterDays() + ")."));
        }

        if (!in.hasEconomicBuyer()) {
            signals.add(new Signal("MISSING_DECISION_MAKER", "No economic buyer identified", "HIGH",
                    "No contact on this opportunity holds the Economic Buyer role.",
                    "Without the person who can release the budget, you are selling to people who can say no but "
                            + "not yes. This is the single most common cause of late-stage no-decisions.",
                    "Ask your champion who signs off the spend, meet them, and record them with the "
                            + "ECONOMIC_BUYER contact role.",
                    "0 contact roles of type ECONOMIC_BUYER on this opportunity."));
        }

        if (!in.threateningCompetitors().isEmpty()) {
            signals.add(new Signal("COMPETITOR_PRESENCE", "Competitor in a strong position", "MEDIUM",
                    "Competitors leading or threatening this deal: " + String.join(", ", in.threateningCompetitors()) + ".",
                    "A competitor in a leading position means the customer has an alternative they prefer. Discount "
                            + "pressure and stalled timelines usually follow.",
                    "Write down what the customer says this competitor does better, and get your champion to test "
                            + "that claim against the decision criteria.",
                    "opportunity_competitor rows with position LEADING or THREAT: "
                            + String.join(", ", in.threateningCompetitors()) + "."));
        } else if (!in.activeCompetitors().isEmpty()) {
            signals.add(new Signal("COMPETITOR_PRESENCE", "Competitor present", "LOW",
                    "Competitors are present on this deal: " + String.join(", ", in.activeCompetitors()) + ".",
                    "Even a trailing competitor sets the customer's price expectation and shapes their decision "
                            + "criteria.",
                    "Confirm where you stand against each competitor and keep the positions current.",
                    "opportunity_competitor rows not eliminated: " + String.join(", ", in.activeCompetitors()) + "."));
        }

        return signals;
    }
}

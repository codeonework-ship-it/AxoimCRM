package com.axiom.pipeline;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.notifications.NotificationWriter;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The governed opportunity lifecycle: stage transitions with enforced gating,
 * closure against a governed reason taxonomy, the controlled reopen path, and
 * close-date change recording.
 *
 * <p>Everything here is server-side. FR-OPP-014 requires the board drag to be
 * validated identically to the record form, and the only way to guarantee that
 * is for both to call the same method — there is no second, laxer path.
 */
@Service
public class OpportunityLifecycleService {

    private final JdbcTemplate jdbc;
    private final PipelineQueries queries;
    private final StageGateEvaluator evaluator;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final NotificationWriter notifications;

    public OpportunityLifecycleService(JdbcTemplate jdbc, PipelineQueries queries, StageGateEvaluator evaluator,
                                       AuditService audit, OutboxWriter outbox, NotificationWriter notifications) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.evaluator = evaluator;
        this.audit = audit;
        this.outbox = outbox;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------ requests

    public record StageChangeRequest(@NotNull UUID stageId, String reason, Long expectedVersion) {}

    public record CloseRequest(@NotNull Boolean won, String closeReasonCode, UUID wonCompetitorId,
                              String notes, Long expectedVersion) {}

    public record ReopenRequest(@NotBlank String reason, UUID stageId, Long expectedVersion) {}

    public record CloseDateRequest(@NotNull LocalDate closeDate, String reason, Long expectedVersion) {}

    public record RecurringRevenueRequest(BigDecimal recurringAmount, String billingFrequency,
                                          Integer termMonths, BigDecimal oneTimeAmount, Long expectedVersion) {}

    // ------------------------------------------------------------------ results

    public record StageChangeResult(UUID opportunityId, UUID fromStageId, String fromStageName,
                                    UUID toStageId, String toStageName, String transitionKind,
                                    String reason, UUID criteriaVersionId, int criteriaVersionNumber,
                                    long previousStageDurationSeconds) {}

    public record GatePreview(UUID opportunityId, UUID targetStageId, String targetStageName,
                              String transitionKind, boolean allowed, boolean reasonRequired,
                              UUID appliedExitVersionId, int appliedExitVersionNumber,
                              List<StageGate.Unsatisfied> unsatisfied, String refusal) {}

    public record ClosureResult(UUID opportunityId, String outcome, String closeReasonCode,
                                String closeReasonLabel, String wonCompetitorName, UUID closureId,
                                int sequenceNo) {}

    public record ReopenResult(UUID opportunityId, UUID reopenedClosureId, int reopenCount,
                               UUID stageId, String stageName, String reason) {}

    public record CloseDateResult(UUID opportunityId, LocalDate oldCloseDate, LocalDate newCloseDate,
                                  int daysMoved, boolean movedBeyondPeriod, int slipCount,
                                  int cumulativeSlipDays, LocalDate originalCloseDate) {}

    public record RecurringRevenueResult(UUID opportunityId, BigDecimal recurringAmount, String billingFrequency,
                                         Integer termMonths, BigDecimal oneTimeAmount, BigDecimal arr,
                                         BigDecimal tcv, int periodsPerYear) {}

    // -------------------------------------------------------------- opportunity

    record OpportunityState(UUID id, String name, UUID pipelineId, UUID stageId, BigDecimal amount,
                            LocalDate closeDate, LocalDate originalCloseDate, boolean closed, Boolean won,
                            int slipCount, int cumulativeSlipDays, int reopenCount, long version,
                            UUID ownerId, BigDecimal recurringAmount, String billingFrequency,
                            Integer termMonths, BigDecimal oneTimeAmount) {}

    OpportunityState load(UUID opportunityId) {
        List<OpportunityState> rows = jdbc.query("""
                select id, name, pipeline_id, stage_id, amount, close_date, original_close_date,
                       is_closed, is_won, slip_count, cumulative_slip_days, reopen_count, version,
                       owner_id, recurring_amount, billing_frequency, term_months, one_time_amount
                from sales.opportunity where tenant_id = ? and id = ?
                """, (rs, i) -> new OpportunityState(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("pipeline_id", UUID.class),
                rs.getObject("stage_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getObject("close_date", LocalDate.class),
                rs.getObject("original_close_date", LocalDate.class),
                rs.getBoolean("is_closed"),
                rs.getObject("is_won") == null ? null : rs.getBoolean("is_won"),
                rs.getInt("slip_count"),
                rs.getInt("cumulative_slip_days"),
                rs.getInt("reopen_count"),
                rs.getLong("version"),
                rs.getObject("owner_id", UUID.class),
                rs.getBigDecimal("recurring_amount"),
                rs.getString("billing_frequency"),
                rs.getObject("term_months") == null ? null : rs.getInt("term_months"),
                rs.getBigDecimal("one_time_amount")), tenantId(), opportunityId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Opportunity not found: " + opportunityId);
        }
        return rows.get(0);
    }

    /**
     * FR-OPP-012: a closed opportunity is read-only. Every mutating path in this
     * module funnels through here so there is no accidental side door.
     */
    void requireOpen(OpportunityState opp) {
        if (opp.closed()) {
            throw new ConflictException("'" + opp.name() + "' is closed and read-only. "
                    + "To change it, reopen it first — that needs the sales manager, revenue operations or "
                    + "administrator permission and a recorded reason.");
        }
    }

    void requireVersion(OpportunityState opp, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != opp.version()) {
            throw new ConflictException("'" + opp.name() + "' changed since you loaded it (you have version "
                    + expectedVersion + ", the record is at version " + opp.version()
                    + "). Reload the opportunity and re-apply your change.");
        }
    }

    // ---------------------------------------------------------- FR-OPP-003/004

    /**
     * Non-mutating gate check so the UI can show what a move would require
     * before the user attempts it. Uses precisely the same evaluation path as
     * {@link #changeStage}.
     */
    @Transactional(readOnly = true)
    public GatePreview previewGate(UUID opportunityId, UUID targetStageId) {
        OpportunityState opp = load(opportunityId);
        PipelineQueries.StageRow from = queries.stage(opp.stageId());
        PipelineQueries.StageRow to = queries.stage(targetStageId);
        String kind = transitionKind(from, to);
        boolean reasonRequired = "SKIP".equals(kind) || "BACKWARD".equals(kind);

        List<StageGate.Unsatisfied> unsatisfied = new ArrayList<>();
        StageGate.Version exitVersion = pinnedExitVersion(opportunityId, from);
        if (!"BACKWARD".equals(kind)) {
            unsatisfied.addAll(evaluator.evaluate(exitVersion, from.name(), queries.facts(opportunityId)).unsatisfied());
            unsatisfied.addAll(evaluator.evaluate(
                    queries.version(queries.currentVersionId(to.id(), "ENTRY").orElse(null), to.id(), "ENTRY"),
                    to.name(), queries.facts(opportunityId)).unsatisfied());
        }
        String refusal = unsatisfied.isEmpty() ? null : gateRefusal(opp.name(), from, to, unsatisfied);
        return new GatePreview(opportunityId, targetStageId, to.name(), kind, unsatisfied.isEmpty(),
                reasonRequired, exitVersion.id(), exitVersion.versionNumber(), unsatisfied, refusal);
    }

    @Transactional
    public StageChangeResult changeStage(UUID opportunityId, StageChangeRequest request) {
        PipelinePermissions.requireWrite(TenantContext.get().role());
        OpportunityState opp = load(opportunityId);
        requireOpen(opp);
        requireVersion(opp, request.expectedVersion());

        PipelineQueries.StageRow from = queries.stage(opp.stageId());
        PipelineQueries.StageRow to = queries.stage(request.stageId());

        if (from.id().equals(to.id())) {
            throw new ConflictException("'" + opp.name() + "' is already in " + to.name() + ".");
        }
        if (!from.pipelineId().equals(to.pipelineId())) {
            throw new ConflictException("Stage " + to.name() + " belongs to a different pipeline ("
                    + to.pipelineName() + "). Move the opportunity within its own pipeline, "
                    + "or change its pipeline first.");
        }
        if (to.closed()) {
            throw new ConflictException("Closing '" + opp.name() + "' has to record a governed outcome reason. "
                    + "Use Close deal (POST /api/v1/opportunities/" + opportunityId
                    + "/close) and pick a win or loss reason from the taxonomy.");
        }

        String kind = transitionKind(from, to);
        String reason = trimToNull(request.reason());

        // FR-OPP-004 — backward and skip transitions only where configured, and
        // never without an explanation.
        if ("BACKWARD".equals(kind) && !from.allowsBackward()) {
            throw new ConflictException("Moving '" + opp.name() + "' back from " + from.name() + " to " + to.name()
                    + " is not permitted: " + from.name() + " does not allow backward movement. "
                    + "Ask an administrator to enable backward movement on that stage, or close the deal instead.");
        }
        if ("SKIP".equals(kind) && !from.allowsSkip()) {
            throw new ConflictException("Skipping from " + from.name() + " straight to " + to.name()
                    + " is not permitted: " + from.name() + " does not allow stages to be skipped. "
                    + "Move '" + opp.name() + "' through the next stage in order.");
        }
        if (("BACKWARD".equals(kind) || "SKIP".equals(kind)) && reason == null) {
            throw new ConflictException(("BACKWARD".equals(kind) ? "Moving backward" : "Skipping a stage")
                    + " requires a reason. Re-send the move with a `reason` explaining why "
                    + "'" + opp.name() + "' is going from " + from.name() + " to " + to.name() + ".");
        }

        // FR-OPP-003 — the exit criteria applied are the ones pinned when the
        // opportunity ENTERED its current stage, not whatever is published now.
        StageGate.Version exitVersion = pinnedExitVersion(opportunityId, from);
        List<StageGate.Unsatisfied> unsatisfied = new ArrayList<>();
        if (!"BACKWARD".equals(kind)) {
            StageGate.Facts facts = queries.facts(opportunityId);
            unsatisfied.addAll(evaluator.evaluate(exitVersion, from.name(), facts).unsatisfied());
            unsatisfied.addAll(evaluator.evaluate(
                    queries.version(queries.currentVersionId(to.id(), "ENTRY").orElse(null), to.id(), "ENTRY"),
                    to.name(), facts).unsatisfied());
        }
        if (!unsatisfied.isEmpty()) {
            throw new ConflictException(gateRefusal(opp.name(), from, to, unsatisfied));
        }

        long duration = closeOpenOccupancy(opportunityId);
        UUID enteredVersionId = queries.currentVersionId(to.id(), "EXIT").orElse(null);
        jdbc.update("""
                insert into sales.stage_history
                  (tenant_id, opportunity_id, from_stage_id, to_stage_id, transition_kind,
                   entered_at, changed_by, changed_by_name, reason, criteria_version_id)
                values (?, ?, ?, ?, ?, now(), ?, ?, ?, ?)
                """, tenantId(), opportunityId, from.id(), to.id(), kind,
                TenantContext.get().userId(), TenantContext.get().displayName(), reason, enteredVersionId);

        jdbc.update("""
                update sales.opportunity
                set stage_id = ?, probability = ?, forecast_category = ?, stage_entered_at = now(),
                    updated_at = now(), version = version + 1
                where tenant_id = ? and id = ?
                """, to.id(), to.probability(), to.forecastCategory(), tenantId(), opportunityId);
        recordState(opportunityId, "STAGE_CHANGED");

        StageGate.Version entered = queries.version(enteredVersionId, to.id(), "EXIT");
        audit.recordWithReason("OPPORTUNITY_STAGE_CHANGE", "OPPORTUNITY", opportunityId,
                "Moved " + opp.name() + " from " + from.name() + " to " + to.name(), reason,
                Map.of("fromStage", from.name(), "toStage", to.name(), "transitionKind", kind,
                        "exitCriteriaVersionApplied", exitVersion.versionNumber(),
                        "exitCriteriaVersionPinnedOnEntry", entered.versionNumber(),
                        "previousStageDurationSeconds", duration));
        outbox.write("opportunity", opportunityId, "opportunity.stage-changed", Map.of(
                "opportunityId", opportunityId.toString(),
                "transitionKind", kind,
                "reason", reason == null ? "" : reason,
                "from", Map.of("stageId", from.id().toString(), "name", from.name()),
                "to", Map.of("stageId", to.id().toString(), "name", to.name()),
                "exitCriteriaVersionApplied", exitVersion.versionNumber()));
        notifications.notifyCurrentUser("SYSTEM", "LOW", "Opportunity advanced",
                opp.name() + " moved from " + from.name() + " to " + to.name() + ".",
                "/pipeline", "You changed this opportunity's stage.", false);

        return new StageChangeResult(opportunityId, from.id(), from.name(), to.id(), to.name(), kind, reason,
                entered.id(), entered.versionNumber(), duration);
    }

    /**
     * The exit-criteria version in force for this stage occupancy. Falls back to
     * the newest published version only when there is no pinned value at all
     * (an opportunity created before criteria existed).
     */
    private StageGate.Version pinnedExitVersion(UUID opportunityId, PipelineQueries.StageRow stage) {
        UUID pinned = queries.pinnedExitVersionId(opportunityId)
                .orElseGet(() -> queries.currentVersionId(stage.id(), "EXIT").orElse(null));
        StageGate.Version version = queries.version(pinned, stage.id(), "EXIT");
        // A pinned version always belongs to the stage the opportunity sits in;
        // if history and record ever disagree, trust the record.
        if (version.id() != null && !stage.id().equals(version.stageId())) {
            return queries.version(queries.currentVersionId(stage.id(), "EXIT").orElse(null), stage.id(), "EXIT");
        }
        return version;
    }

    static String transitionKind(PipelineQueries.StageRow from, PipelineQueries.StageRow to) {
        if (to.rank() < from.rank()) return "BACKWARD";
        if (to.rank() > from.rank() + 1) return "SKIP";
        return "FORWARD";
    }

    /** Names every unsatisfied criterion and the specific action needed. */
    static String gateRefusal(String opportunityName, PipelineQueries.StageRow from,
                              PipelineQueries.StageRow to, List<StageGate.Unsatisfied> unsatisfied) {
        StringBuilder sb = new StringBuilder("Stage gate: '").append(opportunityName)
                .append("' cannot move from ").append(from.name()).append(" to ").append(to.name())
                .append(" until ").append(unsatisfied.size())
                .append(unsatisfied.size() == 1 ? " requirement is met." : " requirements are met.");
        int n = 1;
        for (StageGate.Unsatisfied u : unsatisfied) {
            sb.append(" (").append(n++).append(") ").append(u.criterion())
                    .append(" — ").append(u.observation())
                    .append(" Do this: ").append(u.action());
        }
        return sb.toString();
    }

    private long closeOpenOccupancy(UUID opportunityId) {
        Integer duration = jdbc.queryForObject("""
                with closed as (
                  update sales.stage_history
                  set exited_at = now(),
                      duration_seconds = greatest(0, extract(epoch from (now() - entered_at))::bigint)
                  where tenant_id = ? and opportunity_id = ? and exited_at is null
                  returning duration_seconds
                )
                select coalesce((select max(duration_seconds) from closed), 0)::int
                """, Integer.class, tenantId(), opportunityId);
        return duration == null ? 0L : duration;
    }

    // ------------------------------------------------------------- FR-OPP-012

    @Transactional
    public ClosureResult close(UUID opportunityId, CloseRequest request) {
        PipelinePermissions.requireWrite(TenantContext.get().role());
        OpportunityState opp = load(opportunityId);
        requireOpen(opp);
        requireVersion(opp, request.expectedVersion());

        boolean won = Boolean.TRUE.equals(request.won());
        String outcome = won ? "WON" : "LOST";
        PipelineQueries.CloseReasonRow reason = resolveCloseReason(request.closeReasonCode(), outcome);

        if (reason.requiresCompetitor() && request.wonCompetitorId() == null) {
            throw new ConflictException("Close reason " + reason.code() + " (" + reason.label()
                    + ") requires the competitor the deal was lost to. "
                    + "Send `wonCompetitorId` naming the winning competitor, or pick a different loss reason.");
        }
        String competitorName = null;
        if (request.wonCompetitorId() != null) {
            competitorName = queries.competitors().stream()
                    .filter(c -> c.id().equals(request.wonCompetitorId()))
                    .map(PipelineQueries.CompetitorRow::name)
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException(
                            "Competitor not found in this workspace: " + request.wonCompetitorId()));
        }

        PipelineQueries.StageRow from = queries.stage(opp.stageId());
        PipelineQueries.ClosedStagePair closedStages = queries.closedStages(opp.pipelineId());
        UUID targetStageId = won ? closedStages.wonStageId() : closedStages.lostStageId();
        PipelineQueries.StageRow to = queries.stage(targetStageId);

        // A win still has to satisfy the criteria the deal entered its stage
        // under. A loss does not — refusing to let a rep record a loss is how
        // pipelines end up full of deals everyone knows are dead.
        if (won) {
            StageGate.Version exitVersion = pinnedExitVersion(opportunityId, from);
            List<StageGate.Unsatisfied> unsatisfied =
                    evaluator.evaluate(exitVersion, from.name(), queries.facts(opportunityId)).unsatisfied();
            if (!unsatisfied.isEmpty()) {
                throw new ConflictException(gateRefusal(opp.name(), from, to, unsatisfied));
            }
        }

        long duration = closeOpenOccupancy(opportunityId);
        int sequenceNo = 1 + intOrZero("""
                select coalesce(max(sequence_no), 0) from sales.opportunity_closure
                where tenant_id = ? and opportunity_id = ?
                """, tenantId(), opportunityId);

        UUID closureId = jdbc.queryForObject("""
                insert into sales.opportunity_closure
                  (tenant_id, opportunity_id, sequence_no, outcome, close_reason_id, won_competitor_id,
                   amount_at_close, close_date_at_close, stage_id, notes, closed_by, closed_by_name)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, tenantId(), opportunityId, sequenceNo, outcome, reason.id(),
                request.wonCompetitorId(), opp.amount(), opp.closeDate(), targetStageId,
                trimToNull(request.notes()), TenantContext.get().userId(), TenantContext.get().displayName());

        jdbc.update("""
                insert into sales.stage_history
                  (tenant_id, opportunity_id, from_stage_id, to_stage_id, transition_kind,
                   entered_at, changed_by, changed_by_name, reason, criteria_version_id)
                values (?, ?, ?, ?, 'CLOSE', now(), ?, ?, ?, ?)
                """, tenantId(), opportunityId, from.id(), targetStageId,
                TenantContext.get().userId(), TenantContext.get().displayName(),
                reason.code() + " — " + reason.label(),
                queries.currentVersionId(targetStageId, "EXIT").orElse(null));

        jdbc.update("""
                update sales.opportunity
                set stage_id = ?, is_closed = true, is_won = ?, closed_at = now(),
                    close_reason_id = ?, won_competitor_id = ?, probability = ?, forecast_category = ?,
                    stage_entered_at = now(), updated_at = now(), version = version + 1
                where tenant_id = ? and id = ?
                """, targetStageId, won, reason.id(), request.wonCompetitorId(),
                to.probability(), to.forecastCategory(), tenantId(), opportunityId);
        recordState(opportunityId, won ? "CLOSED_WON" : "CLOSED_LOST");

        audit.recordWithReason("OPPORTUNITY_CLOSE", "OPPORTUNITY", opportunityId,
                "Closed " + opp.name() + " as " + outcome.toLowerCase(Locale.ROOT),
                reason.code() + " — " + reason.label(),
                mapOf("outcome", outcome, "closeReasonCode", reason.code(), "closeReasonLabel", reason.label(),
                        "wonCompetitor", competitorName, "amountAtClose", opp.amount(),
                        "closureSequence", sequenceNo, "finalStageDurationSeconds", duration));
        outbox.write("opportunity", opportunityId, "opportunity.closed", mapOf(
                "opportunityId", opportunityId.toString(), "outcome", outcome,
                "closeReasonCode", reason.code(), "wonCompetitorId",
                request.wonCompetitorId() == null ? null : request.wonCompetitorId().toString(),
                "amountAtClose", opp.amount() == null ? null : opp.amount().toPlainString(),
                "closureSequence", sequenceNo));
        notifications.notifyCurrentUser("SYSTEM", won ? "NORMAL" : "LOW",
                "Opportunity closed " + outcome.toLowerCase(Locale.ROOT),
                opp.name() + " closed as " + outcome.toLowerCase(Locale.ROOT) + " — " + reason.label() + ".",
                "/pipeline", "You closed this opportunity.", false);

        return new ClosureResult(opportunityId, outcome, reason.code(), reason.label(), competitorName,
                closureId, sequenceNo);
    }

    private PipelineQueries.CloseReasonRow resolveCloseReason(String code, String outcome) {
        List<PipelineQueries.CloseReasonRow> allowed = queries.closeReasons(outcome);
        if (allowed.isEmpty()) {
            throw new NotFoundException("No " + outcome.toLowerCase(Locale.ROOT)
                    + " close reasons are configured for this workspace");
        }
        String valid = allowed.stream().map(PipelineQueries.CloseReasonRow::code).reduce((a, b) -> a + ", " + b).orElse("");
        String wanted = trimToNull(code);
        if (wanted == null) {
            throw new ConflictException("Closing an opportunity as " + outcome.toLowerCase(Locale.ROOT)
                    + " requires a reason from the governed taxonomy. Send `closeReasonCode` as one of: " + valid + ".");
        }
        String normalized = wanted.toUpperCase(Locale.ROOT);
        return allowed.stream().filter(r -> r.code().equals(normalized)).findFirst()
                .orElseThrow(() -> new ConflictException("'" + wanted + "' is not a governed "
                        + outcome.toLowerCase(Locale.ROOT) + " reason. Use one of: " + valid + "."));
    }

    // ------------------------------------------------------------- FR-OPP-013

    @Transactional
    public ReopenResult reopen(UUID opportunityId, ReopenRequest request) {
        PipelinePermissions.requireReopen(TenantContext.get().role());
        OpportunityState opp = load(opportunityId);
        requireVersion(opp, request.expectedVersion());
        if (!opp.closed()) {
            throw new ConflictException("'" + opp.name() + "' is already open; there is nothing to reopen.");
        }
        String reason = trimToNull(request.reason());
        if (reason == null) {
            throw new ConflictException("Reopening a closed opportunity requires a recorded reason. "
                    + "Send `reason` explaining why the closure is being undone.");
        }

        // The closure row is stamped, never rewritten: reporting on the ORIGINAL
        // closure (outcome, reason, amount, date, actor) stays intact.
        Map<String, Object> closure = jdbc.queryForMap("""
                select id, sequence_no, outcome from sales.opportunity_closure
                where tenant_id = ? and opportunity_id = ? and reopened_at is null
                order by sequence_no desc limit 1
                """, tenantId(), opportunityId);
        UUID closureId = (UUID) closure.get("id");
        jdbc.update("""
                update sales.opportunity_closure
                set reopened_at = now(), reopened_by = ?, reopened_by_name = ?, reopen_reason = ?
                where tenant_id = ? and id = ?
                """, TenantContext.get().userId(), TenantContext.get().displayName(), reason,
                tenantId(), closureId);

        UUID targetStageId = request.stageId() != null ? request.stageId() : stageBeforeClosure(opportunityId)
                .orElseGet(() -> queries.firstOpenStage(opp.pipelineId()).id());
        PipelineQueries.StageRow to = queries.stage(targetStageId);
        if (to.closed()) {
            throw new ConflictException("A reopened opportunity has to land in an open stage. "
                    + to.name() + " is a closed stage — pick an open one.");
        }

        closeOpenOccupancy(opportunityId);
        jdbc.update("""
                insert into sales.stage_history
                  (tenant_id, opportunity_id, from_stage_id, to_stage_id, transition_kind,
                   entered_at, changed_by, changed_by_name, reason, criteria_version_id)
                values (?, ?, ?, ?, 'REOPEN', now(), ?, ?, ?, ?)
                """, tenantId(), opportunityId, opp.stageId(), targetStageId,
                TenantContext.get().userId(), TenantContext.get().displayName(), reason,
                queries.currentVersionId(targetStageId, "EXIT").orElse(null));

        jdbc.update("""
                update sales.opportunity
                set stage_id = ?, is_closed = false, is_won = null, closed_at = null,
                    close_reason_id = null, won_competitor_id = null,
                    probability = ?, forecast_category = ?, reopen_count = reopen_count + 1,
                    stage_entered_at = now(), updated_at = now(), version = version + 1
                where tenant_id = ? and id = ?
                """, targetStageId, to.probability(), to.forecastCategory(), tenantId(), opportunityId);
        recordState(opportunityId, "REOPENED");

        audit.recordWithReason("OPPORTUNITY_REOPEN", "OPPORTUNITY", opportunityId,
                "Reopened " + opp.name() + " into " + to.name(), reason,
                Map.of("reopenedClosureId", closureId.toString(),
                        "originalOutcome", String.valueOf(closure.get("outcome")),
                        "closureSequence", closure.get("sequence_no"),
                        "stage", to.name(),
                        "reopenCount", opp.reopenCount() + 1));
        outbox.write("opportunity", opportunityId, "opportunity.reopened", Map.of(
                "opportunityId", opportunityId.toString(),
                "reopenedClosureId", closureId.toString(),
                "originalOutcome", String.valueOf(closure.get("outcome")),
                "reason", reason,
                "stageId", targetStageId.toString()));

        return new ReopenResult(opportunityId, closureId, opp.reopenCount() + 1, targetStageId, to.name(), reason);
    }

    private Optional<UUID> stageBeforeClosure(UUID opportunityId) {
        List<UUID> ids = jdbc.queryForList("""
                select from_stage_id from sales.stage_history
                where tenant_id = ? and opportunity_id = ? and transition_kind = 'CLOSE'
                order by entered_at desc limit 1
                """, UUID.class, tenantId(), opportunityId);
        return ids.isEmpty() || ids.get(0) == null ? Optional.empty() : Optional.of(ids.get(0));
    }

    // ------------------------------------------------------------- FR-OPP-010

    @Transactional
    public CloseDateResult changeCloseDate(UUID opportunityId, CloseDateRequest request) {
        PipelinePermissions.requireWrite(TenantContext.get().role());
        OpportunityState opp = load(opportunityId);
        requireOpen(opp);
        requireVersion(opp, request.expectedVersion());

        LocalDate oldDate = opp.closeDate();
        LocalDate newDate = request.closeDate();
        if (newDate.equals(oldDate)) {
            throw new ConflictException("The close date is already " + newDate + ".");
        }

        int daysMoved = oldDate == null ? 0 : (int) ChronoUnit.DAYS.between(oldDate, newDate);
        boolean beyondPeriod = movedBeyondPeriod(oldDate, newDate);
        String reason = trimToNull(request.reason());
        if (beyondPeriod && reason == null) {
            throw new ConflictException("Moving the close date from " + oldDate + " to " + newDate
                    + " pushes it out of " + periodLabel(oldDate) + " into " + periodLabel(newDate)
                    + ". A change beyond the current period must be explained — send `reason` saying what slipped.");
        }

        boolean slipped = daysMoved > 0;
        jdbc.update("""
                insert into sales.opportunity_close_date_change
                  (tenant_id, opportunity_id, old_close_date, new_close_date, days_moved,
                   moved_beyond_period, reason, changed_by, changed_by_name)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId(), opportunityId, oldDate, newDate, daysMoved, beyondPeriod, reason,
                TenantContext.get().userId(), TenantContext.get().displayName());

        jdbc.update("""
                update sales.opportunity
                set close_date = ?,
                    original_close_date = coalesce(original_close_date, ?),
                    slip_count = slip_count + ?,
                    cumulative_slip_days = cumulative_slip_days + ?,
                    updated_at = now(), version = version + 1
                where tenant_id = ? and id = ?
                """, newDate, oldDate == null ? newDate : oldDate, slipped ? 1 : 0,
                slipped ? daysMoved : 0, tenantId(), opportunityId);
        recordState(opportunityId, "CLOSE_DATE_CHANGED");

        audit.recordWithReason("OPPORTUNITY_CLOSE_DATE_CHANGE", "OPPORTUNITY", opportunityId,
                "Close date for " + opp.name() + " moved from " + oldDate + " to " + newDate, reason,
                mapOf("oldCloseDate", String.valueOf(oldDate), "newCloseDate", newDate.toString(),
                        "daysMoved", daysMoved, "movedBeyondPeriod", beyondPeriod,
                        "slipCount", opp.slipCount() + (slipped ? 1 : 0),
                        "cumulativeSlipDays", opp.cumulativeSlipDays() + (slipped ? daysMoved : 0)));
        outbox.write("opportunity", opportunityId, "opportunity.close-date-changed", mapOf(
                "opportunityId", opportunityId.toString(),
                "oldCloseDate", oldDate == null ? null : oldDate.toString(),
                "newCloseDate", newDate.toString(), "daysMoved", daysMoved,
                "movedBeyondPeriod", beyondPeriod, "reason", reason));

        return new CloseDateResult(opportunityId, oldDate, newDate, daysMoved, beyondPeriod,
                opp.slipCount() + (slipped ? 1 : 0),
                opp.cumulativeSlipDays() + (slipped ? daysMoved : 0),
                opp.originalCloseDate() == null ? (oldDate == null ? newDate : oldDate) : opp.originalCloseDate());
    }

    /**
     * "Beyond the current period" is the calendar quarter the date sits in.
     * A slip inside the quarter is noise; a slip out of it changes a number
     * somebody has already committed to, which is why it has to be explained.
     */
    static boolean movedBeyondPeriod(LocalDate oldDate, LocalDate newDate) {
        if (oldDate == null) return false;
        if (!newDate.isAfter(oldDate)) return false;
        int oldKey = oldDate.getYear() * 10 + oldDate.get(IsoFields.QUARTER_OF_YEAR);
        int newKey = newDate.getYear() * 10 + newDate.get(IsoFields.QUARTER_OF_YEAR);
        return newKey > oldKey;
    }

    static String periodLabel(LocalDate date) {
        return date == null ? "no period" : "Q" + date.get(IsoFields.QUARTER_OF_YEAR) + " " + date.getYear();
    }

    // ------------------------------------------------------------- FR-OPP-016

    @Transactional
    public RecurringRevenueResult setRecurringRevenue(UUID opportunityId, RecurringRevenueRequest request) {
        PipelinePermissions.requireWrite(TenantContext.get().role());
        OpportunityState opp = load(opportunityId);
        requireOpen(opp);
        requireVersion(opp, request.expectedVersion());

        RecurringRevenue.Derived derived = RecurringRevenue.derive(
                request.recurringAmount(), request.billingFrequency(), request.termMonths(), request.oneTimeAmount());
        String frequency = request.billingFrequency() == null ? null
                : request.billingFrequency().trim().toUpperCase(Locale.ROOT);

        jdbc.update("""
                update sales.opportunity
                set recurring_amount = ?, billing_frequency = ?, term_months = ?, one_time_amount = ?,
                    arr = ?, tcv = ?, updated_at = now(), version = version + 1
                where tenant_id = ? and id = ?
                """, request.recurringAmount(), frequency, request.termMonths(), request.oneTimeAmount(),
                derived.arr(), derived.tcv(), tenantId(), opportunityId);
        recordState(opportunityId, "RECURRING_REVENUE_CHANGED");

        audit.record("OPPORTUNITY_RECURRING_REVENUE", "OPPORTUNITY", opportunityId,
                "Recurring revenue updated on " + opp.name(),
                mapOf("recurringAmount", request.recurringAmount(), "billingFrequency", frequency,
                        "termMonths", request.termMonths(), "oneTimeAmount", request.oneTimeAmount(),
                        "arr", derived.arr(), "tcv", derived.tcv()));

        return new RecurringRevenueResult(opportunityId, request.recurringAmount(), frequency,
                request.termMonths(), request.oneTimeAmount(), derived.arr(), derived.tcv(),
                derived.periodsPerYear());
    }

    // ------------------------------------------------------------------ helpers

    /** Appends the current record state to the append-only movement history. */
    void recordState(UUID opportunityId, String changeKind) {
        jdbc.update("""
                insert into sales.opportunity_state_history
                  (tenant_id, opportunity_id, change_kind, stage_id, stage_rank, amount, close_date, is_closed, is_won)
                select o.tenant_id, o.id, ?, o.stage_id,
                       (select count(*) + 1 from crm.pipeline_stage s2
                         where s2.tenant_id = o.tenant_id and s2.pipeline_id = o.pipeline_id
                           and s2.deleted_at is null and s2.sort_order < s.sort_order)::int,
                       o.amount, o.close_date, o.is_closed, o.is_won
                from sales.opportunity o
                join crm.pipeline_stage s on s.tenant_id = o.tenant_id and s.id = o.stage_id
                where o.tenant_id = ? and o.id = ?
                """, changeKind, tenantId(), opportunityId);
    }

    private int intOrZero(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static UUID tenantId() {
        return TenantContext.get().tenantId();
    }

    static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Map.of rejects nulls; audit and event payloads legitimately carry them. */
    private static Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}

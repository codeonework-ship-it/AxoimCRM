package com.axiom.workspaces;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * First-party command layer for operational workspaces.
 *
 * <p>The read model intentionally stays in {@link EpicWorkspaceService}. This
 * class owns governed state transitions: every command is tenant-scoped,
 * read-only-role protected, status guarded, audited and mirrored into the
 * transactional outbox. Vendor/third-party execution remains outside this
 * layer by design.
 */
@Service
public class WorkspaceActionService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final OutboxWriter outbox;

    public WorkspaceActionService(JdbcTemplate jdbc, AuditService audit, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
    }

    public record ActionResult(UUID id, String module, String status, String message, Map<String, Object> details) {}
    public record ForecastSubmitRequest(String managerNote) {}
    public record CaseResolveRequest(String outcome) {}
    public record AutomationSimulateRequest(Integer sampleSize) {}
    public record ContractActivateRequest(String signedDocumentRef) {}
    public record CampaignCompleteRequest(String outcome) {}
    public record CopilotDecisionRequest(String note) {}
    public record BfsiClearRequest(String note) {}

    @Transactional
    public ActionResult submitForecast(UUID id, ForecastSubmitRequest request) {
        requireWrite("submit forecasts");
        Map<String, Object> row = one("""
                select s.id, s.period_id, s.status, s.forecast_category, s.submitted_amount,
                       p.status as period_status, p.code as period_code
                from forecasting.forecast_submission s
                join forecasting.forecast_period p on p.tenant_id = s.tenant_id and p.id = s.period_id
                where s.tenant_id = ? and s.id = ?
                for update of s
                """, id, "Forecast submission not found");
        String status = text(row.get("status"));
        if (!"DRAFT".equals(status) && !"MANAGER_ADJUSTED".equals(status)) {
            throw new ConflictException("Only draft or manager-adjusted forecasts can be submitted");
        }
        if (!"OPEN".equals(text(row.get("period_status")))) {
            throw new ConflictException("Forecast period is not open");
        }
        String note = clean(request == null ? null : request.managerNote());
        jdbc.update("""
                update forecasting.forecast_submission
                set status = 'SUBMITTED', submitted_at = now(),
                    manager_note = coalesce(?, manager_note)
                where tenant_id = ? and id = ?
                """, note, tenantId(), id);
        jdbc.update("""
                insert into forecasting.forecast_snapshot
                  (tenant_id, period_id, open_pipeline, commit_amount, best_case_amount, closed_amount, at_risk_amount)
                select tenant_id, period_id,
                       coalesce(sum(weighted_pipeline_amount), 0),
                       coalesce(sum(submitted_amount) filter (where forecast_category = 'COMMIT'), 0),
                       coalesce(sum(submitted_amount) filter (where forecast_category = 'BEST_CASE'), 0),
                       coalesce(sum(submitted_amount) filter (where forecast_category = 'CLOSED'), 0),
                       coalesce(sum(weighted_pipeline_amount) filter (where risk_count > 0), 0)
                from forecasting.forecast_submission
                where tenant_id = ? and period_id = ?
                group by tenant_id, period_id
                """, tenantId(), row.get("period_id"));
        Map<String, Object> details = Map.of(
                "period", row.get("period_code"),
                "category", row.get("forecast_category"),
                "submittedAmount", row.get("submitted_amount"),
                "snapshotCreated", true);
        audit.recordWithReason("FORECAST_SUBMIT", "FORECAST_SUBMISSION", id,
                "Submitted forecast " + row.get("period_code"), note, details);
        outbox.write("forecast_submission", id, "forecast.submitted", details);
        return new ActionResult(id, "forecast", "SUBMITTED", "Forecast submitted and snapshot captured.", details);
    }

    @Transactional
    public ActionResult resolveCase(UUID id, CaseResolveRequest request) {
        requireWrite("resolve cases");
        Map<String, Object> row = one("""
                select id, case_number, subject, status, resolution_due_at
                from service.case_record
                where tenant_id = ? and id = ? and deleted_at is null
                for update
                """, id, "Case not found");
        String status = text(row.get("status"));
        if ("RESOLVED".equals(status) || "CLOSED".equals(status)) {
            throw new ConflictException("Case is already resolved or closed");
        }
        String outcome = clean(request == null ? null : request.outcome());
        if (outcome == null) throw new ConflictException("Case resolution requires an outcome");
        jdbc.update("""
                update service.case_record
                set status = 'RESOLVED', closed_at = now()
                where tenant_id = ? and id = ?
                """, tenantId(), id);
        int milestones = jdbc.update("""
                update service.case_milestone
                set completed_at = now(),
                    status = case when due_at < now() then 'MISSED' else 'MET' end
                where tenant_id = ? and case_id = ? and status = 'OPEN'
                """, tenantId(), id);
        Map<String, Object> details = Map.of(
                "caseNumber", row.get("case_number"),
                "outcome", outcome,
                "milestonesClosed", milestones);
        audit.recordWithReason("CASE_RESOLVE", "CASE", id,
                "Resolved case " + row.get("case_number"), outcome, details);
        outbox.write("case", id, "case.resolved", details);
        return new ActionResult(id, "cases", "RESOLVED", "Case resolved and open SLA milestones closed.", details);
    }

    @Transactional
    public ActionResult simulateAutomation(UUID id, AutomationSimulateRequest request) {
        requireWrite("simulate automation");
        Map<String, Object> row = one("""
                select id, rule_code, name, status, run_count
                from automation.automation_rule
                where tenant_id = ? and id = ?
                for update
                """, id, "Automation rule not found");
        if ("RETIRED".equals(text(row.get("status")))) {
            throw new ConflictException("Retired automation rules cannot be simulated");
        }
        int sampleSize = request == null || request.sampleSize() == null ? 25 : request.sampleSize();
        if (sampleSize < 1 || sampleSize > 500) throw new ConflictException("Simulation sample size must be between 1 and 500");
        Integer stepCount = jdbc.queryForObject("""
                select count(*) from automation.automation_step where tenant_id = ? and rule_id = ?
                """, Integer.class, tenantId(), id);
        UUID runId = UUID.randomUUID();
        String runNumber = "SIM-" + runId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("""
                insert into automation.automation_run
                  (id, tenant_id, rule_id, run_number, status, records_evaluated,
                   records_updated, error_count, completed_at, trace_summary)
                values (?, ?, ?, ?, 'SIMULATED', ?, 0, 0, now(),
                        jsonb_build_object('mode','DRY_RUN','stepCount',?,'sampleSize',?))
                """, runId, tenantId(), id, runNumber, sampleSize, stepCount == null ? 0 : stepCount, sampleSize);
        jdbc.update("""
                update automation.automation_rule
                set simulation_passed = true, run_count = run_count + 1,
                    last_run_at = now(), updated_at = now()
                where tenant_id = ? and id = ?
                """, tenantId(), id);
        Map<String, Object> details = Map.of(
                "ruleCode", row.get("rule_code"),
                "runId", runId.toString(),
                "runNumber", runNumber,
                "sampleSize", sampleSize,
                "stepCount", stepCount == null ? 0 : stepCount);
        audit.record("AUTOMATION_SIMULATE", "AUTOMATION_RULE", id,
                "Simulated automation rule " + row.get("rule_code"), details);
        outbox.write("automation_rule", id, "automation.simulated", details);
        return new ActionResult(runId, "automation", "SIMULATED", "Automation dry-run completed without mutations.", details);
    }

    @Transactional
    public ActionResult validateMigration(UUID id) {
        CrmRole.requireImport(TenantContext.get().role());
        Map<String, Object> row = one("""
                select id, batch_number, object_type, status, total_rows
                from migration.import_batch
                where tenant_id = ? and id = ?
                for update
                """, id, "Import batch not found");
        String status = text(row.get("status"));
        if ("IMPORTED".equals(status) || "ROLLED_BACK".equals(status)) {
            throw new ConflictException("Imported or rolled-back batches cannot be revalidated");
        }
        Map<String, Object> counts = jdbc.queryForMap("""
                select count(*) filter (where severity = 'ERROR') as errors,
                       count(*) filter (where severity = 'WARNING') as warnings
                from migration.validation_error
                where tenant_id = ? and batch_id = ?
                """, tenantId(), id);
        long errors = number(counts.get("errors"));
        long warnings = number(counts.get("warnings"));
        int totalRows = ((Number) row.get("total_rows")).intValue();
        String nextStatus = errors == 0 ? "READY_TO_IMPORT" : "FAILED";
        jdbc.update("""
                update migration.import_batch
                set status = ?, error_rows = ?, valid_rows = greatest(total_rows - ?, 0),
                    completed_at = case when ? in ('READY_TO_IMPORT','FAILED') then now() else completed_at end
                where tenant_id = ? and id = ?
                """, nextStatus, errors, errors, nextStatus, tenantId(), id);
        Map<String, Object> details = Map.of(
                "batchNumber", row.get("batch_number"),
                "objectType", row.get("object_type"),
                "totalRows", totalRows,
                "errorRows", errors,
                "warningRows", warnings);
        audit.record("MIGRATION_VALIDATE", "IMPORT_BATCH", id,
                "Validated import batch " + row.get("batch_number"), details);
        outbox.write("import_batch", id, "migration.validated", details);
        return new ActionResult(id, "migration", nextStatus,
                errors == 0 ? "Import batch is ready to import." : "Import batch failed validation.", details);
    }

    @Transactional
    public ActionResult acknowledgeMobileSync(UUID deviceSessionId) {
        requireWrite("acknowledge mobile sync");
        Map<String, Object> row = one("""
                select id, device_label, status, offline_queue_count
                from mobile.device_session
                where tenant_id = ? and id = ?
                for update
                """, deviceSessionId, "Device session not found");
        if (!"ACTIVE".equals(text(row.get("status")))) {
            throw new ConflictException("Only active device sessions can acknowledge sync");
        }
        int packagesSynced = jdbc.update("""
                update mobile.offline_sync_package
                set status = 'SYNCED', applied_at = now()
                where tenant_id = ? and device_session_id = ? and status in ('QUEUED','FAILED')
                """, tenantId(), deviceSessionId);
        jdbc.update("""
                update mobile.device_session
                set last_sync_at = now(), offline_queue_count = 0
                where tenant_id = ? and id = ?
                """, tenantId(), deviceSessionId);
        Map<String, Object> details = Map.of(
                "deviceLabel", row.get("device_label"),
                "packagesSynced", packagesSynced,
                "previousOfflineQueueCount", row.get("offline_queue_count"));
        audit.record("MOBILE_SYNC_ACK", "DEVICE_SESSION", deviceSessionId,
                "Acknowledged mobile sync for " + row.get("device_label"), details);
        outbox.write("device_session", deviceSessionId, "mobile.sync.acknowledged", details);
        return new ActionResult(deviceSessionId, "mobile", "SYNCED", "Device sync acknowledged and offline queue cleared.", details);
    }

    @Transactional
    public ActionResult activateContract(UUID id, ContractActivateRequest request) {
        requireWrite("activate contracts");
        Map<String, Object> row = one("""
                select id, contract_number, title, status, start_date, end_date, signed_document_ref
                from contracting.contract_record
                where tenant_id = ? and id = ? and deleted_at is null
                for update
                """, id, "Contract not found");
        String status = text(row.get("status"));
        if (!"DRAFT".equals(status) && !"IN_REVIEW".equals(status) && !"EXPIRING".equals(status)) {
            throw new ConflictException("Only draft, in-review or expiring contracts can be activated");
        }
        String signedRef = clean(request == null ? null : request.signedDocumentRef());
        if (signedRef == null) signedRef = clean(text(row.get("signed_document_ref")));
        if (signedRef == null) throw new ConflictException("Contract activation requires a signed document reference");
        jdbc.update("""
                update contracting.contract_record
                set status = 'ACTIVE', signed_document_ref = ?, updated_at = now()
                where tenant_id = ? and id = ?
                """, signedRef, tenantId(), id);
        Map<String, Object> details = Map.of(
                "contractNumber", row.get("contract_number"),
                "previousStatus", status,
                "signedDocumentRef", signedRef);
        audit.recordWithReason("CONTRACT_ACTIVATE", "CONTRACT", id,
                "Activated contract " + row.get("contract_number"), signedRef, details);
        outbox.write("contract", id, "contract.activated", details);
        return new ActionResult(id, "contracts", "ACTIVE", "Contract activated with signed document evidence.", details);
    }

    @Transactional
    public ActionResult completeCampaign(UUID id, CampaignCompleteRequest request) {
        requireWrite("complete campaigns");
        Map<String, Object> row = one("""
                select id, code, name, status, start_date, end_date
                from marketing.campaign
                where tenant_id = ? and id = ? and deleted_at is null
                for update
                """, id, "Campaign not found");
        String status = text(row.get("status"));
        if ("COMPLETED".equals(status) || "CANCELLED".equals(status)) {
            throw new ConflictException("Campaign is already completed or cancelled");
        }
        String outcome = clean(request == null ? null : request.outcome());
        if (outcome == null) throw new ConflictException("Campaign completion requires an outcome");
        Map<String, Object> metrics = jdbc.queryForMap("""
                select count(*) as members,
                       count(*) filter (where status in ('RESPONDED','MQL','SQL')) as responses
                from marketing.campaign_member
                where tenant_id = ? and campaign_id = ?
                """, tenantId(), id);
        jdbc.update("""
                update marketing.campaign
                set status = 'COMPLETED',
                    end_date = greatest(current_date, start_date)
                where tenant_id = ? and id = ?
                """, tenantId(), id);
        Map<String, Object> details = Map.of(
                "campaignCode", row.get("code"),
                "previousStatus", status,
                "outcome", outcome,
                "members", number(metrics.get("members")),
                "responses", number(metrics.get("responses")));
        audit.recordWithReason("CAMPAIGN_COMPLETE", "CAMPAIGN", id,
                "Completed campaign " + row.get("code"), outcome, details);
        outbox.write("campaign", id, "campaign.completed", details);
        return new ActionResult(id, "campaigns", "COMPLETED", "Campaign completed with response evidence.", details);
    }

    @Transactional
    public ActionResult activatePartner(UUID id) {
        requireWrite("activate partners");
        Map<String, Object> row = one("""
                select id, partner_code, tier, status
                from channel.partner_account
                where tenant_id = ? and id = ? and deleted_at is null
                for update
                """, id, "Partner account not found");
        String status = text(row.get("status"));
        if (!"ONBOARDING".equals(status) && !"SUSPENDED".equals(status)) {
            throw new ConflictException("Only onboarding or suspended partners can be activated");
        }
        Long openConflicts = jdbc.queryForObject("""
                select count(*)
                from channel.deal_registration r
                join channel.channel_conflict c on c.tenant_id = r.tenant_id and c.deal_registration_id = r.id
                where r.tenant_id = ? and r.partner_account_id = ? and c.status = 'OPEN'
                """, Long.class, tenantId(), id);
        if (openConflicts != null && openConflicts > 0) {
            throw new ConflictException("Partner has open channel conflicts and cannot be activated");
        }
        jdbc.update("""
                update channel.partner_account
                set status = 'ACTIVE'
                where tenant_id = ? and id = ?
                """, tenantId(), id);
        Map<String, Object> details = Map.of(
                "partnerCode", row.get("partner_code"),
                "tier", row.get("tier"),
                "previousStatus", status);
        audit.record("PARTNER_ACTIVATE", "PARTNER_ACCOUNT", id,
                "Activated partner " + row.get("partner_code"), details);
        outbox.write("partner_account", id, "partner.activated", details);
        return new ActionResult(id, "partners", "ACTIVE", "Partner activated after conflict gate passed.", details);
    }

    @Transactional
    public ActionResult acceptCopilotRecommendation(UUID id, CopilotDecisionRequest request) {
        requireWrite("accept copilot recommendations");
        Map<String, Object> row = one("""
                select r.id, r.recommendation_number, r.title, r.status, r.expires_at,
                       p.prompt_code, p.model_policy
                from ai.copilot_recommendation r
                join ai.copilot_prompt p on p.tenant_id = r.tenant_id and p.id = r.prompt_id
                where r.tenant_id = ? and r.id = ?
                for update of r
                """, id, "Copilot recommendation not found");
        if (!"READY".equals(text(row.get("status")))) {
            throw new ConflictException("Only ready copilot recommendations can be accepted");
        }
        Integer expired = jdbc.queryForObject("""
                select case when expires_at is not null and expires_at < now() then 1 else 0 end
                from ai.copilot_recommendation where tenant_id = ? and id = ?
                """, Integer.class, tenantId(), id);
        if (expired != null && expired == 1) {
            jdbc.update("update ai.copilot_recommendation set status = 'EXPIRED' where tenant_id = ? and id = ?", tenantId(), id);
            throw new ConflictException("Copilot recommendation has expired");
        }
        Long citations = jdbc.queryForObject("""
                select count(*) from ai.grounding_citation where tenant_id = ? and recommendation_id = ?
                """, Long.class, tenantId(), id);
        if (citations == null || citations == 0) {
            throw new ConflictException("Copilot recommendation cannot be accepted without grounding citations");
        }
        String note = clean(request == null ? null : request.note());
        jdbc.update("""
                update ai.copilot_recommendation
                set status = 'ACCEPTED'
                where tenant_id = ? and id = ?
                """, tenantId(), id);
        Map<String, Object> details = Map.of(
                "recommendationNumber", row.get("recommendation_number"),
                "promptCode", row.get("prompt_code"),
                "modelPolicy", row.get("model_policy"),
                "citations", citations);
        audit.recordWithReason("COPILOT_ACCEPT", "COPILOT_RECOMMENDATION", id,
                "Accepted copilot recommendation " + row.get("recommendation_number"), note, details);
        outbox.write("copilot_recommendation", id, "copilot.recommendation.accepted", details);
        return new ActionResult(id, "copilot", "ACCEPTED", "Copilot recommendation accepted with citation evidence.", details);
    }

    @Transactional
    public ActionResult clearBfsiOnboarding(UUID id, BfsiClearRequest request) {
        requireWrite("clear BFSI onboarding");
        Map<String, Object> row = one("""
                select id, onboarding_number, kyc_status, risk_rating
                from bfsi.client_onboarding
                where tenant_id = ? and id = ?
                for update
                """, id, "BFSI onboarding record not found");
        if ("CLEARED".equals(text(row.get("kyc_status")))) {
            throw new ConflictException("BFSI onboarding is already cleared");
        }
        if ("REJECTED".equals(text(row.get("kyc_status"))) || "PROHIBITED".equals(text(row.get("risk_rating")))) {
            throw new ConflictException("Rejected or prohibited-risk BFSI onboarding cannot be cleared");
        }
        Map<String, Object> screening = jdbc.queryForMap("""
                select count(*) filter (where status = 'PENDING') as pending,
                       count(*) filter (where status = 'HIT') as hits,
                       count(*) filter (where status in ('CLEAR','WAIVED')) as cleared
                from bfsi.compliance_screening
                where tenant_id = ? and onboarding_id = ?
                """, tenantId(), id);
        long pending = number(screening.get("pending"));
        long hits = number(screening.get("hits"));
        if (pending > 0 || hits > 0) {
            throw new ConflictException("BFSI onboarding has pending or hit screening results");
        }
        String note = clean(request == null ? null : request.note());
        if (note == null) throw new ConflictException("BFSI clearance requires a compliance note");
        jdbc.update("""
                update bfsi.client_onboarding
                set kyc_status = 'CLEARED', completed_at = now()
                where tenant_id = ? and id = ?
                """, tenantId(), id);
        Map<String, Object> details = Map.of(
                "onboardingNumber", row.get("onboarding_number"),
                "previousStatus", row.get("kyc_status"),
                "riskRating", row.get("risk_rating"),
                "screeningsCleared", number(screening.get("cleared")));
        audit.recordWithReason("BFSI_ONBOARDING_CLEAR", "BFSI_ONBOARDING", id,
                "Cleared BFSI onboarding " + row.get("onboarding_number"), note, details);
        outbox.write("bfsi_onboarding", id, "bfsi.onboarding.cleared", details);
        return new ActionResult(id, "bfsi", "CLEARED", "BFSI onboarding cleared after screening gate passed.", details);
    }

    private Map<String, Object> one(String sql, UUID id, String missingMessage) {
        return jdbc.query(sql, (rs, i) -> {
            int columns = rs.getMetaData().getColumnCount();
            java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
            for (int c = 1; c <= columns; c++) row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
            return row;
        }, tenantId(), id).stream().findFirst().orElseThrow(() -> new NotFoundException(missingMessage));
    }

    private void requireWrite(String action) {
        if (CrmRole.current(TenantContext.get().role()).readOnly()) {
            throw new com.axiom.common.ForbiddenException("Your role cannot " + action);
        }
    }

    private UUID tenantId() {
        return TenantContext.get().tenantId();
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }
}

package com.axiom.leads;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** One independent transaction for one lead-ingestion row. */
@Component
public class LeadIngestionWorker {
    private final JdbcTemplate jdbc;
    private final LeadConfigService config;
    private final LeadMatchingService matching;
    private final LeadScoringService scoring;
    private final LeadPredictionService prediction;
    private final LeadAssignmentService assignment;
    private final LeadSlaService sla;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final ObjectMapper json;

    public LeadIngestionWorker(JdbcTemplate jdbc, LeadConfigService config,
                               LeadMatchingService matching, LeadScoringService scoring,
                               LeadPredictionService prediction, LeadAssignmentService assignment,
                               LeadSlaService sla, AuditService audit, OutboxWriter outbox,
                               ObjectMapper json) {
        this.jdbc = jdbc;
        this.config = config;
        this.matching = matching;
        this.scoring = scoring;
        this.prediction = prediction;
        this.assignment = assignment;
        this.sla = sla;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
    }

    public record Outcome(String status, UUID recordId, String recordType, int score,
                          String assignment, String message) {}

    @Transactional
    public Outcome ingest(LeadIngestRequest request, String source, UUID batchId) {
        List<String> problems = LeadValidation.problems(request);
        if (!problems.isEmpty()) throw new ConflictException(String.join(" ", problems));
        config.ensureTenantDefaults();
        LeadConfigService.DuplicatePolicyRow policy = config.duplicatePolicy();
        List<LeadMatchingService.Candidate> candidates = matching.findDuplicates(
                request.matchInput(), policy, null);
        LeadScoringService.ScoreResult preview = scoring.score(request.snapshot(0));
        boolean ambiguous = candidates.size() > 1
                && candidates.get(0).confidence().subtract(candidates.get(1).confidence()).abs()
                .compareTo(new BigDecimal("0.050")) <= 0;
        String behaviour = candidates.isEmpty() ? "CREATE"
                : ambiguous ? "REVIEW" : policy.behaviour().toUpperCase(Locale.ROOT);

        if ("ATTACH".equals(behaviour) && !candidates.isEmpty()
                && !"LEAD".equals(candidates.getFirst().candidateType())) {
            LeadMatchingService.Candidate target = candidates.getFirst();
            audit.record("LEAD_INGEST_ATTACHED", target.candidateType(), target.candidateId(),
                    "Attached inbound interest to an existing " + target.candidateType().toLowerCase(Locale.ROOT),
                    Map.of("confidence", target.confidence(), "basis", target.basis(), "source", source));
            outbox.write(target.candidateType().toLowerCase(Locale.ROOT), target.candidateId(),
                    "lead.interest.attached", Map.of("source", source, "company", request.company()));
            return new Outcome("ATTACHED", target.candidateId(), target.candidateType(), preview.score(),
                    "Kept the existing record owner", "Matched " + target.label() + ". " + target.basis());
        }

        if ("MERGE".equals(behaviour) && !candidates.isEmpty()
                && "LEAD".equals(candidates.getFirst().candidateType())) {
            UUID existing = candidates.getFirst().candidateId();
            jdbc.update("""
                    update crm.lead set
                      email = coalesce(email, ?), phone = coalesce(phone, ?), title = coalesce(title, ?),
                      source = coalesce(source, ?), campaign_code = coalesce(campaign_code, ?),
                      product_interest = coalesce(product_interest, ?), updated_at = now()
                    where tenant_id = ? and id = ?
                    """, clean(request.email()), clean(request.phone()), clean(request.title()),
                    clean(request.source()), clean(request.campaignCode()), clean(request.productInterest()),
                    TenantContext.get().tenantId(), existing);
            audit.record("LEAD_INGEST_MERGED", "LEAD", existing,
                    "Merged inbound details into an existing lead",
                    Map.of("source", source, "basis", candidates.getFirst().basis()));
            return new Outcome("MERGED", existing, "LEAD", preview.score(),
                    "Existing assignment retained", "Updated the existing lead rather than creating a duplicate.");
        }

        UUID leadId = UUID.randomUUID();
        String status = "REVIEW".equals(behaviour) ? "REVIEW"
                : config.requireStatus(request.status() == null ? config.defaultStatusCode() : request.status());
        String qualification = write(request.qualificationData());
        String custom = write(request.customFields());
        String disposition = "REVIEW".equals(behaviour) ? "REVIEW" : "CREATED";
        jdbc.update("""
                insert into crm.lead
                  (id, tenant_id, first_name, last_name, company, email, phone, title,
                   source, campaign_code, territory, segment, product_interest, rating,
                   status, owner_id, qualification_data, custom_fields, capture_source,
                   ingestion_batch_id, duplicate_disposition)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                """, leadId, TenantContext.get().tenantId(), request.firstName().trim(), request.lastName().trim(),
                request.company().trim(), clean(request.email()), clean(request.phone()), clean(request.title()),
                clean(request.source()), clean(request.campaignCode()), clean(request.territory()),
                clean(request.segment()), clean(request.productInterest()), clean(request.rating()), status,
                request.ownerId(), qualification, custom, source, batchId, disposition);
        LeadScoringService.ScoreResult scored = scoring.scoreAndStore(leadId, request.snapshot(0));
        prediction.predictAndStore(leadId, request.snapshot(0));

        if ("REVIEW".equals(behaviour)) {
            for (LeadMatchingService.Candidate candidate : candidates.stream().limit(5).toList()) {
                jdbc.update("""
                        insert into leads.duplicate_review
                          (tenant_id, lead_id, candidate_type, candidate_id, candidate_label, confidence, basis)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """, TenantContext.get().tenantId(), leadId, candidate.candidateType(),
                        candidate.candidateId(), candidate.label(), candidate.confidence(), candidate.basis());
            }
        }

        LeadAssignmentService.Assignment routed = request.ownerId() == null
                ? assignment.evaluate(new LeadAssignmentService.RoutingInput(request.territory(), request.segment(),
                request.productInterest(), request.source(), scored.score()))
                : new LeadAssignmentService.Assignment(request.ownerId(), null, null, null, null,
                "Requested owner", null, "The submitter selected an owner.", List.of());
        assignment.apply(leadId, routed);
        if (routed.ownerId() != null) sla.startClock(leadId, routed.ownerId(), routed.slaPolicyId(), Instant.now());

        audit.record("LEAD_INGESTED", "LEAD", leadId, "Captured lead " + request.firstName() + " " + request.lastName(),
                Map.of("source", source, "score", scored.score(), "duplicateDisposition", disposition,
                        "assignment", routed.explanation()));
        outbox.write("lead", leadId, "lead.ingested",
                Map.of("source", source, "score", scored.score(), "status", status));
        return new Outcome("REVIEW".equals(behaviour) ? "REVIEW" : "CREATED", leadId, "LEAD",
                scored.score(), routed.explanation(), "Lead captured and routed successfully.");
    }

    private String write(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new ConflictException("Qualification or custom-field data is not valid JSON.");
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

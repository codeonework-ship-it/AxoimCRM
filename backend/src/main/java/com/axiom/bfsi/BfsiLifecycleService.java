package com.axiom.bfsi;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.security.AuthorizationService;
import com.axiom.security.MakerCheckerService;
import com.axiom.security.SecurableObject;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** E22 regulated first-party lifecycle and exception-control boundary. */
@Service
public class BfsiLifecycleService {
    public static final String SUITABILITY_ACTION = "BFSI_SUITABILITY_OVERRIDE";
    public static final String EXCEPTION_ACTION = "BFSI_EXCEPTION";
    private static final Set<String> READ_ROLES = Set.of("SUPER_ADMIN","SUPER_AUDIT","TENANT_ADMIN","AUDITOR",
            "OPERATIONS","FINANCE","DATA_STEWARD","SALES_MANAGER");

    public record OnboardingRequest(@NotNull UUID accountId, @NotNull UUID ownerId,
                                    @NotBlank String clientType, @NotNull LocalDate dueAt) {}
    public record KycItemRequest(@NotBlank String status, String evidenceReference,
                                 LocalDate expiresAt, String rejectionReason) {}
    public record ScreeningRequest(@NotBlank String screeningType, int hitCount,
                                   String sourceSystem, JsonNode result) {}
    public record DispositionRequest(@NotBlank String disposition,
                                     @NotBlank @Size(max = 1000) String rationale) {}
    public record RiskFactor(@NotBlank String factor, @NotNull BigDecimal weight,
                             @NotNull BigDecimal score, @NotBlank String evidence) {}
    public record RiskRequest(@NotEmpty List<RiskFactor> factors,
                              @NotBlank @Size(max = 1000) String rationale) {}
    public record HoldingRequest(@NotNull UUID productId, @NotBlank String status,
                                 @NotNull BigDecimal balanceAmount, LocalDate openedAt) {}
    public record SuitabilityRequest(@NotBlank String level, @NotNull JsonNode factors,
                                     @NotNull @Future Instant expiresAt) {}
    public record RecommendationRequest(@NotNull UUID productId, String overrideReason) {}
    public record ExceptionRequest(@NotBlank String exceptionType, @NotBlank @Size(max = 1000) String reason) {}
    public record DecisionRequest(@NotNull UUID approvalRequestId, @NotBlank String note) {}

    public record OnboardingSummary(UUID id, String number, UUID accountId, String accountName,
                                    String clientType, String kycStatus, String relationshipStatus,
                                    String riskRating, BigDecimal riskScore, String owner,
                                    LocalDate dueAt, long missingKyc, long openHits,
                                    int holdings, int whitespace, int openExceptions) {}
    public record KycItem(UUID id, String code, String name, String status, String owner,
                          String evidenceReference, LocalDate expiresAt, String rejectionReason) {}
    public record Screening(UUID id, String type, String status, int hitCount, String source,
                            String disposition, String dispositionReason, Instant screenedAt) {}
    public record Holding(UUID id, UUID productId, String productCode, String productName,
                          String family, String status, BigDecimal balanceAmount) {}
    public record ProductGap(UUID productId, String productCode, String productName,
                             String family, String minimumSuitability) {}
    public record Recommendation(UUID id, String productName, String status, boolean outsideSuitability,
                                 String overrideReason, UUID approvalRequestId, Instant createdAt) {}
    public record ExceptionView(UUID id, String type, String status, String reason,
                                String resolution, UUID approvalRequestId, String owner, Instant createdAt) {}
    public record OnboardingDetail(OnboardingSummary onboarding, List<KycItem> kycItems,
                                   List<Screening> screenings, List<Holding> holdings,
                                   List<ProductGap> whitespace, List<Recommendation> recommendations,
                                   List<ExceptionView> exceptions, List<Map<String,Object>> riskFactors) {}
    public record ActionResult(UUID id, String status, String message, Map<String,Object> evidence) {}
    record RiskOutcome(BigDecimal score, String rating) {}

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final MakerCheckerService approvals;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final ObjectMapper json;

    public BfsiLifecycleService(JdbcTemplate jdbc, AuthorizationService authorization,
                                MakerCheckerService approvals, AuditService audit,
                                OutboxWriter outbox, ObjectMapper json) {
        this.jdbc=jdbc; this.authorization=authorization; this.approvals=approvals;
        this.audit=audit; this.outbox=outbox; this.json=json;
    }

    @Transactional(readOnly = true)
    public List<OnboardingSummary> onboardings(String status, int page) {
        requireRead();
        String filter = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        return jdbc.query("""
                select o.id,o.onboarding_number,o.account_id,a.name account_name,o.client_type,o.kyc_status,
                       o.relationship_status,o.risk_rating,o.risk_score,u.display_name owner_name,o.due_at,
                       count(distinct k.id) filter (where k.status<>'VERIFIED') missing_kyc,
                       count(distinct s.id) filter (where s.status='HIT' and s.disposition is null) open_hits,
                       count(distinct h.id) filter (where h.status in ('ACTIVE','PROPOSED')) holdings,
                       (select count(*) from bfsi.product_catalog p where p.tenant_id=o.tenant_id and p.active
                         and not exists (select 1 from bfsi.product_holding ph where ph.tenant_id=o.tenant_id
                           and ph.onboarding_id=o.id and ph.product_id=p.id and ph.status in ('ACTIVE','PROPOSED'))) whitespace,
                       count(distinct x.id) filter (where x.status in ('OPEN','PENDING_APPROVAL')) open_exceptions
                  from bfsi.client_onboarding o join crm.account a on a.tenant_id=o.tenant_id and a.id=o.account_id
                  join identity.app_user u on u.tenant_id=o.tenant_id and u.id=o.owner_id
                  left join bfsi.kyc_item k on k.tenant_id=o.tenant_id and k.onboarding_id=o.id
                  left join bfsi.compliance_screening s on s.tenant_id=o.tenant_id and s.onboarding_id=o.id
                  left join bfsi.product_holding h on h.tenant_id=o.tenant_id and h.onboarding_id=o.id
                  left join bfsi.exception_case x on x.tenant_id=o.tenant_id and x.onboarding_id=o.id
                 where o.tenant_id=? and (?::text is null or o.kyc_status=?)
                 group by o.id,a.name,u.display_name order by o.due_at limit 100 offset ?
                """, (rs,i)->summary(rs), tenant(), filter, filter, Math.max(page,0)*100);
    }

    @Transactional(readOnly = true)
    public OnboardingDetail detail(UUID id) {
        requireRead();
        OnboardingSummary summary = summaryById(id);
        authorization.requireRead(SecurableObject.ACCOUNT, summary.accountId());
        List<KycItem> items = jdbc.query("""
                select k.id,r.requirement_code,r.name,k.status,u.display_name,k.evidence_reference,
                       k.expires_at,k.rejection_reason from bfsi.kyc_item k
                  join bfsi.kyc_requirement r on r.tenant_id=k.tenant_id and r.id=k.requirement_id
                  join identity.app_user u on u.tenant_id=k.tenant_id and u.id=k.owner_id
                 where k.tenant_id=? and k.onboarding_id=? order by r.requirement_code
                """, (rs,i)->new KycItem(rs.getObject("id",UUID.class),rs.getString("requirement_code"),
                        rs.getString("name"),rs.getString("status"),rs.getString("display_name"),
                        rs.getString("evidence_reference"),rs.getObject("expires_at",LocalDate.class),
                        rs.getString("rejection_reason")), tenant(), id);
        List<Screening> screenings = jdbc.query("""
                select id,screening_type,status,hit_count,source_system,disposition,disposition_reason,screened_at
                  from bfsi.compliance_screening where tenant_id=? and onboarding_id=? order by screened_at desc nulls last
                """, (rs,i)->new Screening(rs.getObject("id",UUID.class),rs.getString("screening_type"),
                        rs.getString("status"),rs.getInt("hit_count"),rs.getString("source_system"),
                        rs.getString("disposition"),rs.getString("disposition_reason"),instant(rs.getTimestamp("screened_at"))), tenant(),id);
        List<Holding> holdings = jdbc.query("""
                select h.id,p.id product_id,p.product_code,p.name,p.product_family,h.status,h.balance_amount
                  from bfsi.product_holding h left join bfsi.product_catalog p
                    on p.tenant_id=h.tenant_id and p.id=h.product_id
                 where h.tenant_id=? and h.onboarding_id=? order by p.product_family,p.name
                """, (rs,i)->new Holding(rs.getObject("id",UUID.class),rs.getObject("product_id",UUID.class),
                        rs.getString("product_code"),rs.getString("name"),coalesce(rs.getString("product_family"),"Legacy"),
                        rs.getString("status"),rs.getBigDecimal("balance_amount")),tenant(),id);
        List<ProductGap> whitespace = jdbc.query("""
                select p.id,p.product_code,p.name,p.product_family,p.minimum_suitability_level
                  from bfsi.product_catalog p where p.tenant_id=? and p.active and not exists
                    (select 1 from bfsi.product_holding h where h.tenant_id=p.tenant_id and h.onboarding_id=?
                      and h.product_id=p.id and h.status in ('ACTIVE','PROPOSED')) order by p.product_family,p.name
                """, (rs,i)->new ProductGap(rs.getObject("id",UUID.class),rs.getString("product_code"),
                        rs.getString("name"),rs.getString("product_family"),rs.getString("minimum_suitability_level")),tenant(),id);
        List<Recommendation> recommendations = jdbc.query("""
                select r.id,p.name,r.status,r.outside_suitability,r.override_reason,r.approval_request_id,r.created_at
                  from bfsi.product_recommendation r join bfsi.product_catalog p
                    on p.tenant_id=r.tenant_id and p.id=r.product_id
                 where r.tenant_id=? and r.onboarding_id=? order by r.created_at desc
                """, (rs,i)->new Recommendation(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("status"),
                        rs.getBoolean("outside_suitability"),rs.getString("override_reason"),
                        rs.getObject("approval_request_id",UUID.class),rs.getTimestamp("created_at").toInstant()),tenant(),id);
        List<ExceptionView> exceptions = jdbc.query("""
                select x.id,x.exception_type,x.status,x.reason,x.resolution,x.approval_request_id,u.display_name,x.created_at
                  from bfsi.exception_case x join identity.app_user u on u.tenant_id=x.tenant_id and u.id=x.owner_id
                 where x.tenant_id=? and x.onboarding_id=? order by x.created_at desc
                """, (rs,i)->new ExceptionView(rs.getObject("id",UUID.class),rs.getString("exception_type"),
                        rs.getString("status"),rs.getString("reason"),rs.getString("resolution"),
                        rs.getObject("approval_request_id",UUID.class),rs.getString("display_name"),
                        rs.getTimestamp("created_at").toInstant()),tenant(),id);
        return new OnboardingDetail(summary,items,screenings,holdings,whitespace,recommendations,exceptions,
                readMapList(jsonText("select risk_factors::text from bfsi.client_onboarding where tenant_id=? and id=?",id)));
    }

    @Transactional
    public OnboardingDetail create(OnboardingRequest request) {
        requireWrite();
        authorization.requireRead(SecurableObject.ACCOUNT, request.accountId());
        requireUser(request.ownerId());
        String clientType=enumValue(request.clientType(),Set.of("RETAIL","SME","CORPORATE","INSTITUTIONAL"),"client type");
        if (request.dueAt().isBefore(LocalDate.now())) throw new ConflictException("KYC due date cannot be in the past");
        UUID id=UUID.randomUUID();
        String number="KYC-"+LocalDate.now().getYear()+"-"+id.toString().substring(0,8).toUpperCase(Locale.ROOT);
        jdbc.update("""
                insert into bfsi.client_onboarding(id,tenant_id,onboarding_number,account_id,client_type,kyc_status,
                  risk_rating,owner_id,due_at,relationship_status,version)
                values (?,?,?,?,?,'NOT_STARTED','MEDIUM',?,?,'PENDING',0)
                """,id,tenant(),number,request.accountId(),clientType,request.ownerId(),request.dueAt());
        jdbc.update("""
                insert into bfsi.kyc_item(tenant_id,onboarding_id,requirement_id,status,owner_id)
                select tenant_id,?,id,'MISSING',? from bfsi.kyc_requirement where tenant_id=? and active
                """,id,request.ownerId(),tenant());
        Map<String,Object> evidence=Map.of("number",number,"accountId",request.accountId(),"clientType",clientType,"dueAt",request.dueAt());
        audit.record("BFSI_ONBOARDING_CREATED","BFSI_ONBOARDING",id,"Created governed BFSI onboarding "+number,evidence);
        outbox.write("bfsi_onboarding",id,"bfsi.onboarding.created",evidence);
        return detail(id);
    }

    @Transactional
    public ActionResult updateKycItem(UUID onboardingId, UUID itemId, KycItemRequest request) {
        requireWrite();
        summaryById(onboardingId);
        Map<String,Object> item=one("select id,status,evidence_reference from bfsi.kyc_item where tenant_id=? and onboarding_id=? and id=? for update",
                onboardingId,itemId,"KYC item not found");
        String status=enumValue(request.status(),Set.of("MISSING","REQUESTED","RECEIVED","VERIFIED","REJECTED","EXPIRED"),"KYC status");
        String evidence=clean(request.evidenceReference());
        if (("RECEIVED".equals(status)||"VERIFIED".equals(status))&&evidence==null) throw new ConflictException("Received or verified KYC requires an evidence reference");
        if ("VERIFIED".equals(status)&&request.expiresAt()==null) throw new ConflictException("Verified KYC requires a document expiry date");
        if ("REJECTED".equals(status)&&clean(request.rejectionReason())==null) throw new ConflictException("Rejected KYC requires a reason");
        jdbc.update("""
                update bfsi.kyc_item set status=?,evidence_reference=?,expires_at=?,rejection_reason=?,
                  verified_by=case when ?='VERIFIED' then ? end,verified_at=case when ?='VERIFIED' then now() end,updated_at=now()
                 where tenant_id=? and id=?
                """,status,evidence,request.expiresAt(),clean(request.rejectionReason()),status,actor(),status,tenant(),itemId);
        jdbc.update("update bfsi.client_onboarding set kyc_status='IN_PROGRESS',version=version+1 where tenant_id=? and id=? and kyc_status='NOT_STARTED'",tenant(),onboardingId);
        Map<String,Object> ev=Map.of("itemId",itemId,"previousStatus",item.get("status"),"status",status,"evidence",coalesce(evidence,""));
        audit.recordWithReason("BFSI_KYC_ITEM_UPDATED","BFSI_ONBOARDING",onboardingId,"Updated KYC prerequisite",clean(request.rejectionReason()),ev);
        outbox.write("bfsi_onboarding",onboardingId,"bfsi.kyc.updated",ev);
        return new ActionResult(onboardingId,status,"KYC item updated with evidence.",ev);
    }

    @Transactional
    public Screening runScreening(UUID onboardingId, ScreeningRequest request) {
        requireWrite(); summaryById(onboardingId);
        String type=enumValue(request.screeningType(),Set.of("SANCTIONS","PEP","ADVERSE_MEDIA","SUITABILITY"),"screening type");
        if(request.hitCount()<0) throw new ConflictException("Screening hit count cannot be negative");
        UUID id=UUID.randomUUID(); String status=request.hitCount()>0?"HIT":"CLEAR";
        jdbc.update("""
                insert into bfsi.compliance_screening(id,tenant_id,onboarding_id,screening_type,status,hit_count,
                  screened_at,source_system,result_payload) values (?,?,?,?,?,?,now(),?,?::jsonb)
                """,id,tenant(),onboardingId,type,status,request.hitCount(),coalesce(clean(request.sourceSystem()),"FIRST_PARTY_MANUAL"),write(request.result()));
        Map<String,Object> ev=Map.of("screeningType",type,"status",status,"hitCount",request.hitCount());
        audit.record("BFSI_SCREENING_COMPLETED","BFSI_SCREENING",id,"Recorded "+type+" screening run",ev);
        outbox.write("bfsi_screening",id,"bfsi.screening.completed",ev);
        return new Screening(id,type,status,request.hitCount(),coalesce(clean(request.sourceSystem()),"FIRST_PARTY_MANUAL"),null,null,Instant.now());
    }

    @Transactional
    public Screening disposition(UUID screeningId, DispositionRequest request) {
        requireApprovalRole();
        Map<String,Object> row=one("select id,onboarding_id,screening_type,status,hit_count,source_system from bfsi.compliance_screening where tenant_id=? and id=? for update",
                screeningId,"Screening not found");
        if(!"HIT".equals(row.get("status"))) throw new ConflictException("Only a screening hit requires disposition");
        String disposition=enumValue(request.disposition(),Set.of("FALSE_POSITIVE","CONFIRMED","ACCEPTED_RISK","NOT_APPLICABLE"),"disposition");
        String next=Set.of("FALSE_POSITIVE","NOT_APPLICABLE").contains(disposition)?"WAIVED":"HIT";
        jdbc.update("""
                update bfsi.compliance_screening set status=?,disposition=?,disposition_reason=?,dispositioned_by=?,dispositioned_at=now()
                 where tenant_id=? and id=?
                """,next,disposition,request.rationale().trim(),actor(),tenant(),screeningId);
        Map<String,Object> ev=Map.of("disposition",disposition,"status",next,"rationale",request.rationale().trim());
        audit.recordWithReason("BFSI_SCREENING_DISPOSITIONED","BFSI_SCREENING",screeningId,
                "Dispositioned compliance screening hit",request.rationale().trim(),ev);
        outbox.write("bfsi_screening",screeningId,"bfsi.screening.dispositioned",ev);
        return new Screening(screeningId,String.valueOf(row.get("screening_type")),next,((Number)row.get("hit_count")).intValue(),
                String.valueOf(row.get("source_system")),disposition,request.rationale().trim(),Instant.now());
    }

    @Transactional
    public ActionResult rateRisk(UUID onboardingId, RiskRequest request) {
        requireWrite(); summaryById(onboardingId);
        RiskOutcome outcome=calculateRisk(request.factors());
        BigDecimal score=outcome.score();
        String rating=outcome.rating();
        jdbc.update("""
                update bfsi.client_onboarding set risk_score=?,risk_rating=?,risk_factors=?::jsonb,risk_rationale=?,
                  risk_updated_by=?,risk_updated_at=now(),version=version+1 where tenant_id=? and id=?
                """,score,rating,writeValue(request.factors()),request.rationale().trim(),actor(),tenant(),onboardingId);
        Map<String,Object> ev=Map.of("score",score,"rating",rating,"factors",request.factors(),"rationale",request.rationale().trim());
        audit.recordWithReason("BFSI_RISK_RATED","BFSI_ONBOARDING",onboardingId,"Calculated defensible BFSI risk rating",request.rationale().trim(),ev);
        outbox.write("bfsi_onboarding",onboardingId,"bfsi.risk.rated",ev);
        return new ActionResult(onboardingId,rating,"Risk rating calculated from visible weighted factors.",ev);
    }

    static RiskOutcome calculateRisk(List<RiskFactor> factors) {
        if (factors == null || factors.isEmpty()) throw new ConflictException("At least one risk factor is required");
        BigDecimal totalWeight=factors.stream().map(RiskFactor::weight).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(totalWeight.compareTo(new BigDecimal("100"))!=0) throw new ConflictException("Risk factor weights must total exactly 100");
        BigDecimal score=BigDecimal.ZERO;
        for(RiskFactor factor:factors) {
            if(factor.weight().signum()<0||factor.score().compareTo(BigDecimal.ZERO)<0||factor.score().compareTo(new BigDecimal("100"))>0)
                throw new ConflictException("Every risk factor weight and score must be between 0 and 100");
            score=score.add(factor.score().multiply(factor.weight()).divide(new BigDecimal("100"),4,RoundingMode.HALF_UP));
        }
        score=score.setScale(2,RoundingMode.HALF_UP);
        String rating=score.compareTo(new BigDecimal("80"))>=0?"PROHIBITED":score.compareTo(new BigDecimal("60"))>=0?"HIGH":score.compareTo(new BigDecimal("30"))>=0?"MEDIUM":"LOW";
        return new RiskOutcome(score,rating);
    }

    @Transactional
    public ActionResult activate(UUID onboardingId, String note) {
        requireApprovalRole(); OnboardingSummary summary=summaryById(onboardingId);
        List<String> blockers=new ArrayList<>();
        jdbc.query("""
                select r.name,u.display_name,k.status from bfsi.kyc_item k
                 join bfsi.kyc_requirement r on r.tenant_id=k.tenant_id and r.id=k.requirement_id
                 join identity.app_user u on u.tenant_id=k.tenant_id and u.id=k.owner_id
                 where k.tenant_id=? and k.onboarding_id=? and (k.status<>'VERIFIED' or k.expires_at<=current_date)
                """, rs->{blockers.add(rs.getString("name")+" is "+rs.getString("status")+" (owner: "+rs.getString("display_name")+")");},tenant(),onboardingId);
        jdbc.query("""
                select screening_type,hit_count from bfsi.compliance_screening where tenant_id=? and onboarding_id=?
                 and (status='PENDING' or (status='HIT' and disposition is null) or disposition in ('CONFIRMED','ACCEPTED_RISK'))
                """,rs->{blockers.add(rs.getString("screening_type")+" screening is not cleared (hits: "+rs.getInt("hit_count")+")");},tenant(),onboardingId);
        if("PROHIBITED".equals(summary.riskRating())) blockers.add("Risk rating is PROHIBITED");
        if(!blockers.isEmpty()) throw new ConflictException("Relationship activation blocked: "+String.join("; ",blockers));
        jdbc.update("update bfsi.client_onboarding set kyc_status='CLEARED',relationship_status='ACTIVE',completed_at=now(),version=version+1 where tenant_id=? and id=?",tenant(),onboardingId);
        Map<String,Object> ev=Map.of("previousStatus",summary.kycStatus(),"riskRating",summary.riskRating(),"note",coalesce(clean(note),"All governed checks passed"));
        audit.recordWithReason("BFSI_RELATIONSHIP_ACTIVATED","BFSI_ONBOARDING",onboardingId,"Activated BFSI relationship",clean(note),ev);
        outbox.write("bfsi_onboarding",onboardingId,"bfsi.relationship.activated",ev);
        return new ActionResult(onboardingId,"ACTIVE","Relationship activated after all KYC, screening and risk gates passed.",ev);
    }

    @Transactional
    public ActionResult addHolding(UUID onboardingId, HoldingRequest request) {
        requireWrite(); summaryById(onboardingId); product(request.productId());
        String status=enumValue(request.status(),Set.of("PROPOSED","ACTIVE","SUSPENDED","CLOSED"),"holding status");
        if(request.balanceAmount().signum()<0) throw new ConflictException("Holding balance cannot be negative");
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into bfsi.product_holding(id,tenant_id,onboarding_id,product_id,product_family,status,balance_amount,opened_at)
                select ?,?,?,id,product_family,?,?,? from bfsi.product_catalog where tenant_id=? and id=?
                """,id,tenant(),onboardingId,status,request.balanceAmount(),request.openedAt(),tenant(),request.productId());
        Map<String,Object> ev=Map.of("productId",request.productId(),"status",status,"balance",request.balanceAmount());
        audit.record("BFSI_HOLDING_ADDED","BFSI_ONBOARDING",onboardingId,"Added product holding",ev);
        outbox.write("bfsi_onboarding",onboardingId,"bfsi.holding.added",ev);
        return new ActionResult(id,status,"Holding added; whitespace recalculated from the governed catalogue.",ev);
    }

    @Transactional
    public ActionResult assessSuitability(UUID onboardingId, SuitabilityRequest request) {
        requireWrite(); summaryById(onboardingId);
        String level=enumValue(request.level(),Set.of("BASIC","STANDARD","COMPLEX","PROFESSIONAL"),"suitability level");
        jdbc.update("update bfsi.suitability_assessment set status='SUPERSEDED' where tenant_id=? and onboarding_id=? and status='ACTIVE'",tenant(),onboardingId);
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into bfsi.suitability_assessment(id,tenant_id,onboarding_id,level,factors,status,assessed_by,expires_at)
                values (?,?,?,?,?::jsonb,'ACTIVE',?,?)
                """,id,tenant(),onboardingId,level,write(request.factors()),actor(),Timestamp.from(request.expiresAt()));
        Map<String,Object> ev=Map.of("level",level,"expiresAt",request.expiresAt(),"factors",request.factors());
        audit.record("BFSI_SUITABILITY_ASSESSED","BFSI_ONBOARDING",onboardingId,"Recorded suitability assessment",ev);
        outbox.write("bfsi_onboarding",onboardingId,"bfsi.suitability.assessed",ev);
        return new ActionResult(id,"ACTIVE","Suitability assessment saved with an explicit expiry.",ev);
    }

    @Transactional
    public Recommendation recommend(UUID onboardingId, RecommendationRequest request) {
        requireWrite(); summaryById(onboardingId);
        Map<String,Object> assessment=jdbc.queryForList("""
                select id,level,expires_at from bfsi.suitability_assessment
                 where tenant_id=? and onboarding_id=? and status='ACTIVE' order by assessed_at desc limit 1
                """,tenant(),onboardingId).stream().findFirst().orElseThrow(()->new ConflictException("A current suitability assessment is required"));
        if(((Timestamp)assessment.get("expires_at")).toInstant().isBefore(Instant.now())) throw new ConflictException("Suitability assessment expired; reassessment is required");
        Map<String,Object> product=product(request.productId());
        boolean outside=rank(String.valueOf(product.get("minimum_suitability_level")))>rank(String.valueOf(assessment.get("level")));
        String reason=clean(request.overrideReason());
        if(outside&&reason==null) throw new ConflictException("Product is outside assessed suitability; an override reason and independent approval are required");
        UUID id=UUID.randomUUID();
        String status=outside?"PENDING_APPROVAL":"APPROVED";
        jdbc.update("""
                insert into bfsi.product_recommendation(id,tenant_id,onboarding_id,product_id,suitability_assessment_id,
                  status,outside_suitability,override_reason,created_by,approved_at)
                values (?,?,?,?,?,?,?,?,?,case when ?='APPROVED' then now() end)
                """,id,tenant(),onboardingId,request.productId(),assessment.get("id"),status,outside,reason,actor(),status);
        UUID approvalId=null;
        if(outside) {
            MakerCheckerService.ApprovalRequest approval=approvals.submit(new MakerCheckerService.SubmitRequest(
                    SUITABILITY_ACTION,"BFSI_RECOMMENDATION",id,"Approve suitability override for "+product.get("name"),
                    Map.of("onboardingId",onboardingId,"productId",request.productId(),"reason",reason)));
            approvalId=approval.id();
            jdbc.update("update bfsi.product_recommendation set approval_request_id=? where tenant_id=? and id=?",approvalId,tenant(),id);
        }
        Map<String,Object> ev=new LinkedHashMap<>(); ev.put("product",product.get("name"));ev.put("outsideSuitability",outside);ev.put("approvalRequestId",approvalId);
        audit.recordWithReason("BFSI_RECOMMENDATION_CREATED","BFSI_RECOMMENDATION",id,"Created governed product recommendation",reason,ev);
        outbox.write("bfsi_recommendation",id,"bfsi.recommendation.created",ev);
        return new Recommendation(id,String.valueOf(product.get("name")),status,outside,reason,approvalId,Instant.now());
    }

    @Transactional
    public Recommendation decideRecommendation(UUID id, DecisionRequest request, boolean approve) {
        requireApprovalRole();
        Map<String,Object> row=one("select id,status,approval_request_id from bfsi.product_recommendation where tenant_id=? and id=? for update",id,"Recommendation not found");
        if(!"PENDING_APPROVAL".equals(row.get("status"))||!request.approvalRequestId().equals(row.get("approval_request_id"))) throw new ConflictException("Recommendation approval request does not match the pending recommendation");
        if(approve) approvals.approve(request.approvalRequestId(),request.note()); else approvals.reject(request.approvalRequestId(),request.note());
        String status=approve?"APPROVED":"REJECTED";
        jdbc.update("update bfsi.product_recommendation set status=?,approved_at=case when ?='APPROVED' then now() end where tenant_id=? and id=?",status,status,tenant(),id);
        audit.recordWithReason("BFSI_RECOMMENDATION_"+status,"BFSI_RECOMMENDATION",id,status+" suitability override",request.note(),Map.of("approvalRequestId",request.approvalRequestId()));
        outbox.write("bfsi_recommendation",id,"bfsi.recommendation."+status.toLowerCase(Locale.ROOT),Map.of("approvalRequestId",request.approvalRequestId()));
        return recommendation(id);
    }

    @Transactional
    public ExceptionView createException(UUID onboardingId, ExceptionRequest request) {
        requireWrite(); OnboardingSummary summary=summaryById(onboardingId);
        String type=enumValue(request.exceptionType(),Set.of("KYC","SCREENING","RISK","SUITABILITY","HOLDING"),"exception type");
        UUID id=UUID.randomUUID();
        jdbc.update("""
                insert into bfsi.exception_case(id,tenant_id,onboarding_id,exception_type,status,reason,owner_id,created_by)
                select ?,tenant_id,id,?,'OPEN',?,owner_id,? from bfsi.client_onboarding
                 where tenant_id=? and id=?
                """,id,type,request.reason().trim(),actor(),tenant(),onboardingId);
        MakerCheckerService.ApprovalRequest approval=approvals.submit(new MakerCheckerService.SubmitRequest(
                EXCEPTION_ACTION,"BFSI_EXCEPTION",id,"Approve "+type+" exception for "+summary.number(),
                Map.of("onboardingId",onboardingId,"reason",request.reason().trim())));
        jdbc.update("update bfsi.exception_case set status='PENDING_APPROVAL',approval_request_id=? where tenant_id=? and id=?",approval.id(),tenant(),id);
        audit.recordWithReason("BFSI_EXCEPTION_SUBMITTED","BFSI_EXCEPTION",id,"Submitted governed BFSI exception",request.reason().trim(),Map.of("approvalRequestId",approval.id()));
        outbox.write("bfsi_exception",id,"bfsi.exception.submitted",Map.of("approvalRequestId",approval.id(),"onboardingId",onboardingId));
        return exceptionView(id);
    }

    @Transactional
    public ExceptionView decideException(UUID id, DecisionRequest request, boolean approve) {
        requireApprovalRole();
        Map<String,Object> row=one("select id,status,approval_request_id from bfsi.exception_case where tenant_id=? and id=? for update",id,"BFSI exception not found");
        if(!"PENDING_APPROVAL".equals(row.get("status"))||!request.approvalRequestId().equals(row.get("approval_request_id"))) throw new ConflictException("Exception approval request does not match");
        if(approve) approvals.approve(request.approvalRequestId(),request.note()); else approvals.reject(request.approvalRequestId(),request.note());
        String status=approve?"APPROVED":"REJECTED";
        jdbc.update("update bfsi.exception_case set status=?,resolution=?,resolved_at=now() where tenant_id=? and id=?",status,request.note(),tenant(),id);
        audit.recordWithReason("BFSI_EXCEPTION_"+status,"BFSI_EXCEPTION",id,status+" BFSI exception",request.note(),Map.of("approvalRequestId",request.approvalRequestId()));
        outbox.write("bfsi_exception",id,"bfsi.exception."+status.toLowerCase(Locale.ROOT),Map.of("approvalRequestId",request.approvalRequestId()));
        return exceptionView(id);
    }

    private OnboardingSummary summaryById(UUID id) {
        return jdbc.query("""
                select o.id,o.onboarding_number,o.account_id,a.name account_name,o.client_type,o.kyc_status,
                       o.relationship_status,o.risk_rating,o.risk_score,u.display_name owner_name,o.due_at,
                       (select count(*) from bfsi.kyc_item k where k.tenant_id=o.tenant_id and k.onboarding_id=o.id and k.status<>'VERIFIED') missing_kyc,
                       (select count(*) from bfsi.compliance_screening s where s.tenant_id=o.tenant_id and s.onboarding_id=o.id and s.status='HIT' and s.disposition is null) open_hits,
                       (select count(*) from bfsi.product_holding h where h.tenant_id=o.tenant_id and h.onboarding_id=o.id and h.status in ('ACTIVE','PROPOSED')) holdings,
                       (select count(*) from bfsi.product_catalog p where p.tenant_id=o.tenant_id and p.active and not exists
                         (select 1 from bfsi.product_holding h where h.tenant_id=o.tenant_id and h.onboarding_id=o.id and h.product_id=p.id and h.status in ('ACTIVE','PROPOSED'))) whitespace,
                       (select count(*) from bfsi.exception_case x where x.tenant_id=o.tenant_id and x.onboarding_id=o.id and x.status in ('OPEN','PENDING_APPROVAL')) open_exceptions
                  from bfsi.client_onboarding o join crm.account a on a.tenant_id=o.tenant_id and a.id=o.account_id
                  join identity.app_user u on u.tenant_id=o.tenant_id and u.id=o.owner_id
                 where o.tenant_id=? and o.id=?
                """,(rs,i)->summary(rs),tenant(),id).stream().findFirst().orElseThrow(()->new NotFoundException("BFSI onboarding not found"));
    }

    private OnboardingSummary summary(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new OnboardingSummary(rs.getObject("id",UUID.class),rs.getString("onboarding_number"),
                rs.getObject("account_id",UUID.class),rs.getString("account_name"),rs.getString("client_type"),
                rs.getString("kyc_status"),rs.getString("relationship_status"),rs.getString("risk_rating"),
                rs.getBigDecimal("risk_score"),rs.getString("owner_name"),rs.getObject("due_at",LocalDate.class),
                rs.getLong("missing_kyc"),rs.getLong("open_hits"),rs.getInt("holdings"),rs.getInt("whitespace"),rs.getInt("open_exceptions"));
    }

    private Map<String,Object> product(UUID id){return one("select id,product_code,name,product_family,minimum_suitability_level from bfsi.product_catalog where tenant_id=? and id=? and active",id,"BFSI product not found");}
    private Recommendation recommendation(UUID id){return jdbc.query("""
            select r.id,p.name,r.status,r.outside_suitability,r.override_reason,r.approval_request_id,r.created_at
              from bfsi.product_recommendation r join bfsi.product_catalog p on p.tenant_id=r.tenant_id and p.id=r.product_id
             where r.tenant_id=? and r.id=?
            """,(rs,i)->new Recommendation(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("status"),
                    rs.getBoolean("outside_suitability"),rs.getString("override_reason"),rs.getObject("approval_request_id",UUID.class),
                    rs.getTimestamp("created_at").toInstant()),tenant(),id).stream().findFirst().orElseThrow(()->new NotFoundException("Recommendation not found"));}
    private ExceptionView exceptionView(UUID id){return jdbc.query("""
            select x.id,x.exception_type,x.status,x.reason,x.resolution,x.approval_request_id,u.display_name,x.created_at
              from bfsi.exception_case x join identity.app_user u on u.tenant_id=x.tenant_id and u.id=x.owner_id
             where x.tenant_id=? and x.id=?
            """,(rs,i)->new ExceptionView(rs.getObject("id",UUID.class),rs.getString("exception_type"),rs.getString("status"),
                    rs.getString("reason"),rs.getString("resolution"),rs.getObject("approval_request_id",UUID.class),
                    rs.getString("display_name"),rs.getTimestamp("created_at").toInstant()),tenant(),id).stream().findFirst().orElseThrow(()->new NotFoundException("BFSI exception not found"));}

    private Map<String,Object> one(String sql,UUID id,String message){return one(sql,null,id,message);}
    private Map<String,Object> one(String sql,UUID parent,UUID id,String message){
        Object[] args=parent==null?new Object[]{tenant(),id}:new Object[]{tenant(),parent,id};
        return jdbc.query(sql,(rs,i)->{Map<String,Object> m=new LinkedHashMap<>();for(int c=1;c<=rs.getMetaData().getColumnCount();c++)m.put(rs.getMetaData().getColumnLabel(c),rs.getObject(c));return m;},args)
                .stream().findFirst().orElseThrow(()->new NotFoundException(message));}
    private void requireUser(UUID id){Boolean exists=jdbc.queryForObject("select exists(select 1 from identity.app_user where tenant_id=? and id=? and active)",Boolean.class,tenant(),id);if(!Boolean.TRUE.equals(exists))throw new NotFoundException("Active owner not found");}
    private void requireRead(){if(!READ_ROLES.contains(TenantContext.get().role()))throw new ForbiddenException("BFSI records require a governed finance, operations, administrator or auditor role");}
    private void requireWrite(){requireRead();CrmRole.requireWrite(TenantContext.get().role());}
    private void requireApprovalRole(){requireWrite();String r=TenantContext.get().role();if(!(CrmRole.current(r).masterAdmin()||Set.of("OPERATIONS","FINANCE").contains(r)))throw new ForbiddenException("A finance, operations or administrator approver is required");}
    private int rank(String value){return switch(value){case "BASIC"->1;case "STANDARD"->2;case "COMPLEX"->3;case "PROFESSIONAL"->4;default->0;};}
    private String enumValue(String value,Set<String> allowed,String label){String v=value==null?"":value.trim().toUpperCase(Locale.ROOT);if(!allowed.contains(v))throw new ConflictException("Unsupported "+label+": "+value);return v;}
    private String clean(String v){return v==null||v.isBlank()?null:v.trim();}
    private String coalesce(String v,String fallback){return v==null?fallback:v;}
    private Instant instant(Timestamp t){return t==null?null:t.toInstant();}
    private String write(JsonNode value){try{return json.writeValueAsString(value==null?json.createObjectNode():value);}catch(JsonProcessingException e){throw new IllegalArgumentException("Invalid JSON evidence",e);}}
    private String writeValue(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalArgumentException("Invalid evidence",e);}}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> readMapList(String value){try{return json.readValue(value,List.class);}catch(JsonProcessingException e){throw new IllegalStateException("Stored risk evidence invalid",e);}}
    private String jsonText(String sql,UUID id){return jdbc.queryForObject(sql,String.class,tenant(),id);}
    private UUID tenant(){return TenantContext.get().tenantId();} private UUID actor(){return TenantContext.get().userId();}
}

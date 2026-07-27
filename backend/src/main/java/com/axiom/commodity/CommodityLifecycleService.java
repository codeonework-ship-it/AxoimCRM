package com.axiom.commodity;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.security.MakerCheckerService;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** E23 origination system with fail-closed trading-data gates and reliable handoff evidence. */
@Service
public class CommodityLifecycleService {
    public static final String TERM_APPROVAL_ACTION="COMMODITY_TERM_APPROVAL";
    private static final Duration CREDIT_FRESHNESS=Duration.ofMinutes(60);
    private static final Set<String> READ_ROLES=Set.of("SUPER_ADMIN","SUPER_AUDIT","TENANT_ADMIN","AUDITOR",
            "OPERATIONS","FINANCE","DATA_STEWARD","SALES_MANAGER","SALES");

    public record CounterpartyStateRequest(@NotBlank String sourceSystem,@NotNull Instant sourceAsOf,
                                           @NotNull BigDecimal creditLimit,@NotNull BigDecimal exposure,
                                           @NotNull BigDecimal headroom,@NotBlank String agreementStatus,
                                           String agreementReference,LocalDate agreementExpiresAt){}
    public record EnquiryRequest(@NotNull UUID counterpartyId,@NotBlank String originationType,
                                 @NotBlank String commodity,@NotBlank String grade,@Positive BigDecimal quantity,
                                 @NotBlank String unit,@NotNull BigDecimal tolerancePct,@NotNull BigDecimal notionalAmount,
                                 LocalDate deliveryStart,LocalDate deliveryEnd,@NotBlank String locationFrom,
                                 @NotBlank String locationTo,@NotBlank String incoterm,Instant tenderDeadline){}
    public record PriceRequest(@NotBlank String indexName,@NotBlank String differential,
                               @NotBlank String quotationPeriod,@NotBlank String settlementConvention){}
    public record TermSheetRequest(@NotBlank String incoterm,@NotBlank String pricingBasis,@NotNull JsonNode terms){}
    public record DecisionRequest(@NotNull UUID approvalRequestId,@NotBlank String note){}
    public record NoteRequest(@NotBlank @Size(max=1000) String note){}
    public record HandoffAttemptRequest(boolean delivered,String error){}
    public record AcknowledgeRequest(@NotBlank String tradeReference){}

    public record Counterparty(UUID id,String code,String accountName,String status,
                               String agreementStatus,String agreementReference,LocalDate agreementExpiresAt,
                               BigDecimal creditLimit,BigDecimal exposure,BigDecimal headroom,
                               String source,Instant asOf,Instant lastSync,boolean creditFresh){}
    public record Enquiry(UUID id,String number,String type,String commodity,String grade,String status,
                          BigDecimal quantity,String unit,BigDecimal tolerancePct,BigDecimal notional,
                          LocalDate deliveryStart,LocalDate deliveryEnd,String locationFrom,String locationTo,
                          String incoterm,Instant tenderDeadline,String lapseReason,int version,
                          String executionStatus,String tradeReference,Counterparty counterparty){}
    public record IndicativePrice(UUID id,String indexName,String differential,String quotationPeriod,
                                  String settlementConvention,String expression,String label,String status,Instant createdAt){}
    public record TermSheet(UUID id,String number,int version,String status,String incoterm,String pricingBasis,
                            JsonNode terms,UUID approvalRequestId,Instant approvedAt){}
    public record Handoff(UUID id,int enquiryVersion,String idempotencyKey,String status,int attempts,int maxAttempts,
                          JsonNode payload,String tradeReference,String lastError,Instant createdAt){}
    public record ExceptionView(UUID id,String type,String status,String reason,String owner,Instant createdAt){}
    public record EnquiryDetail(Enquiry enquiry,List<IndicativePrice> prices,List<TermSheet> termSheets,
                                List<Handoff> handoffs,List<ExceptionView> exceptions,List<String> gates){}
    public record ActionResult(UUID id,String status,String message,Map<String,Object> evidence){}

    private final JdbcTemplate jdbc; private final MakerCheckerService approvals; private final AuditService audit;
    private final OutboxWriter outbox; private final ObjectMapper json;
    public CommodityLifecycleService(JdbcTemplate jdbc,MakerCheckerService approvals,AuditService audit,OutboxWriter outbox,ObjectMapper json){
        this.jdbc=jdbc;this.approvals=approvals;this.audit=audit;this.outbox=outbox;this.json=json;}

    @Transactional(readOnly=true)
    public List<Enquiry> enquiries(String status,int page){requireRead();String filter=clean(status);if(filter!=null)filter=filter.toUpperCase(Locale.ROOT);
        return jdbc.query(baseEnquirySql()+" where e.tenant_id=? and (?::text is null or e.status=?) order by e.created_at desc limit 100 offset ?",
                (rs,i)->enquiry(rs),tenant(),filter,filter,Math.max(0,page)*100);}

    @Transactional(readOnly=true)
    public EnquiryDetail detail(UUID id){requireRead();Enquiry e=enquiryById(id,false);
        List<IndicativePrice> prices=jdbc.query("""
                select id,index_name,differential_text,quotation_period,settlement_convention,expression,label,status,created_at
                  from commodity.indicative_price where tenant_id=? and trade_enquiry_id=? order by created_at desc
                """,(rs,i)->new IndicativePrice(rs.getObject("id",UUID.class),rs.getString("index_name"),
                        rs.getString("differential_text"),rs.getString("quotation_period"),rs.getString("settlement_convention"),
                        rs.getString("expression"),rs.getString("label"),rs.getString("status"),rs.getTimestamp("created_at").toInstant()),tenant(),id);
        List<TermSheet> terms=jdbc.query("""
                select id,term_sheet_number,version,status,incoterm,pricing_basis,terms::text,approval_request_id,approved_at
                  from commodity.contract_term_sheet where tenant_id=? and trade_enquiry_id=? order by created_at desc
                """,(rs,i)->term(rs),tenant(),id);
        List<Handoff> handoffs=jdbc.query("""
                select id,enquiry_version,idempotency_key,status,attempt_count,max_attempts,payload::text,
                       external_trade_reference,last_error,created_at from commodity.execution_handoff
                 where tenant_id=? and trade_enquiry_id=? order by created_at desc
                """,(rs,i)->handoff(rs),tenant(),id);
        List<ExceptionView> exceptions=jdbc.query("""
                select x.id,x.exception_type,x.status,x.reason,u.display_name,x.created_at
                  from commodity.origination_exception x join identity.app_user u
                    on u.tenant_id=x.tenant_id and u.id=x.owner_id
                 where x.tenant_id=? and x.trade_enquiry_id=? order by x.created_at desc
                """,(rs,i)->new ExceptionView(rs.getObject("id",UUID.class),rs.getString("exception_type"),
                        rs.getString("status"),rs.getString("reason"),rs.getString("display_name"),
                        rs.getTimestamp("created_at").toInstant()),tenant(),id);
        return new EnquiryDetail(e,prices,terms,handoffs,exceptions,gates(e));}

    @Transactional
    public Counterparty ingestCounterpartyState(UUID id,CounterpartyStateRequest request){requireIntegrationOrAdmin();
        Counterparty before=counterparty(id,true);String agreement=enumValue(request.agreementStatus(),Set.of("MISSING","PENDING","EXECUTED","EXPIRED"),"agreement status");
        if(request.creditLimit().signum()<0||request.exposure().signum()<0||request.headroom().signum()<0)throw new ConflictException("Received credit values cannot be negative");
        if("EXECUTED".equals(agreement)&&clean(request.agreementReference())==null)throw new ConflictException("Executed master agreement requires its source reference");
        jdbc.update("""
                update commodity.counterparty_profile set credit_limit=?,exposure_amount=?,credit_headroom=?,credit_source=?,
                  credit_as_of=?,source_system=?,source_synced_at=now(),master_agreement_status=?,
                  master_agreement_reference=?,master_agreement_expires_at=?,updated_at=now()
                 where tenant_id=? and id=?
                """,request.creditLimit(),request.exposure(),request.headroom(),request.sourceSystem().trim(),
                Timestamp.from(request.sourceAsOf()),request.sourceSystem().trim(),agreement,clean(request.agreementReference()),
                request.agreementExpiresAt(),tenant(),id);
        Map<String,Object> ev=new LinkedHashMap<>();ev.put("source",request.sourceSystem());ev.put("sourceAsOf",request.sourceAsOf());
        ev.put("before",counterpartyEvidence(before));ev.put("after",Map.of("creditLimit",request.creditLimit(),"exposure",request.exposure(),"headroom",request.headroom(),"agreementStatus",agreement));
        audit.record("COMMODITY_COUNTERPARTY_SYNCED","COMMODITY_COUNTERPARTY",id,"Recorded source-mastered agreement and credit data",ev);
        outbox.write("commodity_counterparty",id,"commodity.counterparty.synced",ev);return counterparty(id,false);}

    @Transactional
    public EnquiryDetail createEnquiry(EnquiryRequest request){requireWrite();Counterparty cp=counterparty(request.counterpartyId(),false);
        String type=enumValue(request.originationType(),Set.of("TERM","SPOT_CARGO","TENDER","STRUCTURED"),"origination type");
        if(request.tolerancePct().signum()<0||request.tolerancePct().compareTo(new BigDecimal("100"))>0)throw new ConflictException("Quantity tolerance must be between 0 and 100 percent");
        if(request.notionalAmount().signum()<0)throw new ConflictException("Notional amount cannot be negative");
        if(request.deliveryStart()!=null&&request.deliveryEnd()!=null&&request.deliveryEnd().isBefore(request.deliveryStart()))throw new ConflictException("Delivery window end cannot be before start");
        if("TENDER".equals(type)&&(request.tenderDeadline()==null||request.tenderDeadline().isBefore(Instant.now())))throw new ConflictException("Tender requires a future submission deadline");
        UUID id=UUID.randomUUID();String number=type.substring(0,Math.min(type.length(),4))+"-"+Instant.now().toEpochMilli()+"-"+id.toString().substring(0,5).toUpperCase(Locale.ROOT);
        jdbc.update("""
                insert into commodity.trade_enquiry(id,tenant_id,enquiry_number,counterparty_profile_id,commodity_name,status,
                  quantity,unit,notional_amount,delivery_window_start,delivery_window_end,origination_type,grade,
                  quantity_tolerance_pct,delivery_location_from,delivery_location_to,incoterm,tender_submission_deadline,version,updated_at)
                values (?,?,?,?,?,'RECEIVED',?,?,?,?,?,?,?,?,?,?,?,?,1,now())
                """,id,tenant(),number,request.counterpartyId(),request.commodity().trim(),request.quantity(),request.unit().trim(),
                request.notionalAmount(),request.deliveryStart(),request.deliveryEnd(),type,request.grade().trim(),request.tolerancePct(),
                request.locationFrom().trim(),request.locationTo().trim(),request.incoterm().trim(),timestamp(request.tenderDeadline()));
        Map<String,Object> ev=Map.of("number",number,"type",type,"counterparty",cp.code(),"commodity",request.commodity(),
                "quantity",request.quantity(),"tolerancePct",request.tolerancePct(),"incoterm",request.incoterm());
        audit.record("COMMODITY_ENQUIRY_CREATED","COMMODITY_ENQUIRY",id,"Created "+type+" origination enquiry",ev);
        outbox.write("commodity_enquiry",id,"commodity.enquiry.created",ev);return detail(id);}

    @Transactional
    public IndicativePrice price(UUID enquiryId,PriceRequest request){requireWrite();Enquiry e=enquiryById(enquiryId,true);
        if(Set.of("WON","LOST","EXPIRED").contains(e.status()))throw new ConflictException("Closed enquiry cannot receive a price indication");
        jdbc.update("update commodity.indicative_price set status='SUPERSEDED' where tenant_id=? and trade_enquiry_id=? and status='ACTIVE'",tenant(),enquiryId);
        String expression=request.indexName().trim()+" "+request.differential().trim()+" | quotation: "+request.quotationPeriod().trim()+" | settlement: "+request.settlementConvention().trim();
        UUID id=UUID.randomUUID();jdbc.update("""
                insert into commodity.indicative_price(id,tenant_id,trade_enquiry_id,index_name,differential_text,
                  quotation_period,settlement_convention,expression,label,status,created_by)
                values (?,?,?,?,?,?,?,?,'INDICATIVE - NON-BINDING','ACTIVE',?)
                """,id,tenant(),enquiryId,request.indexName().trim(),request.differential().trim(),request.quotationPeriod().trim(),
                request.settlementConvention().trim(),expression,actor());
        jdbc.update("update commodity.trade_enquiry set status='PRICING',version=version+1,updated_at=now() where tenant_id=? and id=? and status='RECEIVED'",tenant(),enquiryId);
        Map<String,Object> ev=Map.of("expression",expression,"classification","INDICATIVE_NON_BINDING","settlementPriceComputed",false);
        audit.record("COMMODITY_INDICATIVE_PRICE_CREATED","COMMODITY_ENQUIRY",enquiryId,"Recorded non-binding indicative price expression",ev);
        outbox.write("commodity_enquiry",enquiryId,"commodity.indicative_price.created",ev);
        return new IndicativePrice(id,request.indexName(),request.differential(),request.quotationPeriod(),request.settlementConvention(),expression,"INDICATIVE - NON-BINDING","ACTIVE",Instant.now());}

    @Transactional
    public TermSheet createTermSheet(UUID enquiryId,TermSheetRequest request){requireWrite();Enquiry e=enquiryById(enquiryId,true);
        if(Set.of("WON","LOST","EXPIRED").contains(e.status()))throw new ConflictException("Closed enquiry cannot receive a term sheet");
        UUID id=UUID.randomUUID();String number="TS-"+e.number()+"-"+id.toString().substring(0,5).toUpperCase(Locale.ROOT);
        jdbc.update("""
                insert into commodity.contract_term_sheet(id,tenant_id,trade_enquiry_id,term_sheet_number,status,
                  incoterm,pricing_basis,version,terms,updated_at) values (?,?,?,?,'DRAFT',?,?,1,?::jsonb,now())
                """,id,tenant(),enquiryId,number,request.incoterm().trim(),request.pricingBasis().trim(),write(request.terms()));
        Map<String,Object> ev=Map.of("enquiryId",enquiryId,"number",number);
        audit.record("COMMODITY_TERM_CREATED","COMMODITY_TERM_SHEET",id,"Created draft commodity term sheet",ev);
        outbox.write("commodity_term_sheet",id,"commodity.term.created",ev);
        return termById(id);}

    @Transactional
    public TermSheet submitTermApproval(UUID termId,NoteRequest request){requireWrite();TermSheet term=termById(termId);
        if(!"DRAFT".equals(term.status())&&!"IN_REVIEW".equals(term.status()))throw new ConflictException("Only draft or in-review terms can be submitted");
        MakerCheckerService.ApprovalRequest approval=approvals.submit(new MakerCheckerService.SubmitRequest(
                TERM_APPROVAL_ACTION,"COMMODITY_TERM_SHEET",termId,"Approve commodity term sheet "+term.number(),
                Map.of("pricingBasis",term.pricingBasis(),"incoterm",term.incoterm(),"note",request.note())));
        jdbc.update("update commodity.contract_term_sheet set status='IN_REVIEW',approval_request_id=?,updated_at=now() where tenant_id=? and id=?",approval.id(),tenant(),termId);
        audit.recordWithReason("COMMODITY_TERM_SUBMITTED","COMMODITY_TERM_SHEET",termId,"Submitted term sheet for independent approval",request.note(),Map.of("approvalRequestId",approval.id()));
        outbox.write("commodity_term_sheet",termId,"commodity.term.submitted",Map.of("approvalRequestId",approval.id()));
        return termById(termId);}

    @Transactional
    public TermSheet decideTerm(UUID termId,DecisionRequest request,boolean approve){requireApprover();TermSheet term=termById(termId);
        if(!"IN_REVIEW".equals(term.status())||!request.approvalRequestId().equals(term.approvalRequestId()))throw new ConflictException("Term-sheet approval request does not match");
        if(approve)approvals.approve(request.approvalRequestId(),request.note());else approvals.reject(request.approvalRequestId(),request.note());
        String status=approve?"APPROVED":"REJECTED";jdbc.update("""
                update commodity.contract_term_sheet set status=?,approved_by=case when ?='APPROVED' then ? end,
                  approved_at=case when ?='APPROVED' then now() end,updated_at=now() where tenant_id=? and id=?
                """,status,status,actor(),status,tenant(),termId);
        audit.recordWithReason("COMMODITY_TERM_"+status,"COMMODITY_TERM_SHEET",termId,status+" commodity term sheet",request.note(),Map.of("approvalRequestId",request.approvalRequestId()));
        outbox.write("commodity_term_sheet",termId,"commodity.term."+status.toLowerCase(Locale.ROOT),Map.of("approvalRequestId",request.approvalRequestId()));return termById(termId);}

    @Transactional
    public ActionResult offer(UUID enquiryId){requireWrite();Enquiry e=enquiryById(enquiryId,true);
        if(!Set.of("RECEIVED","PRICING").contains(e.status())) throw new ConflictException("Only a received or pricing enquiry can be released as an offer");
        List<String> blockers=gates(e);
        Long approved=jdbc.queryForObject("select count(*) from commodity.contract_term_sheet where tenant_id=? and trade_enquiry_id=? and status='APPROVED'",Long.class,tenant(),enquiryId);
        if(approved==null||approved==0)blockers.add("An independently approved term sheet is required");
        Long prices=jdbc.queryForObject("select count(*) from commodity.indicative_price where tenant_id=? and trade_enquiry_id=? and status='ACTIVE'",Long.class,tenant(),enquiryId);
        if(prices==null||prices==0)blockers.add("An active indicative, non-binding price expression is required");
        if(!blockers.isEmpty()){
            String reason=String.join("; ",blockers);
            recordException(enquiryId,"APPROVAL",reason);
            Map<String,Object> ev=Map.of("enquiryNumber",e.number(),"blockers",List.copyOf(blockers),"offerReleased",false);
            audit.record("COMMODITY_OFFER_BLOCKED","COMMODITY_ENQUIRY",enquiryId,"Refused commodity offer because governed gates are incomplete",ev);
            outbox.write("commodity_enquiry",enquiryId,"commodity.offer.blocked",ev);
            return new ActionResult(enquiryId,"BLOCKED","Offer blocked: "+reason,ev);
        }
        jdbc.update("update commodity.trade_enquiry set status='OFFERED',version=version+1,updated_at=now() where tenant_id=? and id=? and status in ('RECEIVED','PRICING')",tenant(),enquiryId);
        jdbc.update("update commodity.contract_term_sheet set status='SENT',updated_at=now() where tenant_id=? and trade_enquiry_id=? and status='APPROVED'",tenant(),enquiryId);
        Map<String,Object> ev=Map.of("enquiryNumber",e.number(),"creditSource",e.counterparty().source(),"creditAsOf",e.counterparty().asOf(),"approvedTerms",approved);
        audit.record("COMMODITY_OFFER_RELEASED","COMMODITY_ENQUIRY",enquiryId,"Released commodity offer after agreement, credit, price and approval gates",ev);
        outbox.write("commodity_enquiry",enquiryId,"commodity.offer.released",ev);return new ActionResult(enquiryId,"OFFERED","Offer released after every governed gate passed.",ev);}

    @Transactional
    public Handoff closeWonAndQueue(UUID enquiryId,NoteRequest request){requireWrite();Enquiry e=enquiryById(enquiryId,true);
        if(!"OFFERED".equals(e.status()))throw new ConflictException("Only an offered enquiry can be agreed and queued for execution");
        TermSheet term=jdbc.query("""
                select id,term_sheet_number,version,status,incoterm,pricing_basis,terms::text,approval_request_id,approved_at
                  from commodity.contract_term_sheet where tenant_id=? and trade_enquiry_id=? and status in ('SENT','ACCEPTED')
                 order by approved_at desc limit 1
                """,(rs,i)->term(rs),tenant(),enquiryId).stream().findFirst().orElseThrow(()->new ConflictException("A sent approved term sheet is required"));
        int nextVersion=e.version()+1;String key=enquiryId+":"+nextVersion;
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("originationId",enquiryId);payload.put("version",nextVersion);
        payload.put("counterparty",e.counterparty().code());payload.put("commodity",e.commodity());payload.put("grade",e.grade());
        payload.put("quantity",e.quantity());payload.put("tolerancePct",e.tolerancePct());payload.put("unit",e.unit());
        payload.put("deliveryStart",e.deliveryStart());payload.put("deliveryEnd",e.deliveryEnd());payload.put("locationFrom",e.locationFrom());
        payload.put("locationTo",e.locationTo());payload.put("incoterm",term.incoterm());payload.put("pricingBasis",term.pricingBasis());
        payload.put("originatingReference",e.number());payload.put("operatorNote",request.note());
        UUID id=UUID.randomUUID();jdbc.update("""
                insert into commodity.execution_handoff(id,tenant_id,trade_enquiry_id,enquiry_version,idempotency_key,
                  payload,status,attempt_count,max_attempts,next_attempt_at,created_by)
                values (?,?,?,?,?,?::jsonb,'QUEUED',0,5,now(),?) on conflict (tenant_id,trade_enquiry_id,enquiry_version) do nothing
                """,id,tenant(),enquiryId,nextVersion,key,writeValue(payload),actor());
        jdbc.update("update commodity.trade_enquiry set status='WON',execution_status='QUEUED',version=?,updated_at=now() where tenant_id=? and id=?",nextVersion,tenant(),enquiryId);
        Map<String,Object> ev=Map.of("idempotencyKey",key,"payload",payload,"vendorDelivery","PENDING_VENDOR");
        audit.recordWithReason("COMMODITY_DEAL_AGREED","COMMODITY_ENQUIRY",enquiryId,"Agreed deal queued for idempotent CTRM handoff",request.note(),ev);
        outbox.write("commodity_enquiry",enquiryId,"commodity.deal.agreed",ev);return handoffByKey(enquiryId,nextVersion);}

    @Transactional
    public Handoff recordAttempt(UUID handoffId,HandoffAttemptRequest request){requireIntegrationOrAdmin();Handoff h=handoffById(handoffId,true);
        if("ACKNOWLEDGED".equals(h.status()))return h;
        int attempts=h.attempts()+1;String status=request.delivered()?"DELIVERED":attempts>=h.maxAttempts()?"EXCEPTION":"QUEUED";
        jdbc.update("""
                update commodity.execution_handoff set status=?,attempt_count=?,last_error=?,
                  delivered_at=case when ?='DELIVERED' then now() else delivered_at end,
                  next_attempt_at=case when ?='QUEUED' then now()+interval '5 minutes' end where tenant_id=? and id=?
                """,status,attempts,clean(request.error()),status,status,tenant(),handoffId);
        jdbc.update("update commodity.trade_enquiry set execution_status=?,updated_at=now() where tenant_id=? and id=(select trade_enquiry_id from commodity.execution_handoff where tenant_id=? and id=?)",status,tenant(),tenant(),handoffId);
        if("EXCEPTION".equals(status))recordException((UUID)one("select trade_enquiry_id from commodity.execution_handoff where tenant_id=? and id=?",handoffId,"Handoff not found").get("trade_enquiry_id"),"HANDOFF","CTRM handoff exceeded "+h.maxAttempts()+" attempts: "+clean(request.error()));
        Map<String,Object> ev=new LinkedHashMap<>();ev.put("status",status);ev.put("attempt",attempts);ev.put("error",clean(request.error()));
        audit.record("COMMODITY_HANDOFF_ATTEMPT","COMMODITY_HANDOFF",handoffId,"Recorded connector-neutral handoff attempt",ev);
        outbox.write("commodity_handoff",handoffId,"commodity.handoff.attempted",ev);
        return handoffById(handoffId,false);}

    @Transactional
    public Handoff acknowledge(UUID handoffId,AcknowledgeRequest request){requireIntegrationOrAdmin();Handoff h=handoffById(handoffId,true);
        if("ACKNOWLEDGED".equals(h.status())){if(!request.tradeReference().equals(h.tradeReference()))throw new ConflictException("Idempotency key is already acknowledged with a different trade reference");return h;}
        if(!"DELIVERED".equals(h.status()))throw new ConflictException("Only a delivered handoff can be acknowledged");
        UUID enquiryId=(UUID)one("select trade_enquiry_id from commodity.execution_handoff where tenant_id=? and id=?",handoffId,"Handoff not found").get("trade_enquiry_id");
        jdbc.update("update commodity.execution_handoff set status='ACKNOWLEDGED',external_trade_reference=?,acknowledged_at=now() where tenant_id=? and id=?",request.tradeReference().trim(),tenant(),handoffId);
        jdbc.update("update commodity.trade_enquiry set execution_status='ACKNOWLEDGED',trade_reference=?,updated_at=now() where tenant_id=? and id=?",request.tradeReference().trim(),tenant(),enquiryId);
        Map<String,Object> ev=Map.of("idempotencyKey",h.idempotencyKey(),"tradeReference",request.tradeReference().trim());
        audit.record("COMMODITY_HANDOFF_ACKNOWLEDGED","COMMODITY_ENQUIRY",enquiryId,"Stored external trade acknowledgement",ev);
        outbox.write("commodity_enquiry",enquiryId,"commodity.handoff.acknowledged",ev);return handoffById(handoffId,false);}

    @Transactional
    public ActionResult sweepTenders(){requireWrite();List<Map<String,Object>> expired=jdbc.queryForList("""
            select id,enquiry_number from commodity.trade_enquiry where tenant_id=? and origination_type='TENDER'
             and status in ('RECEIVED','PRICING','OFFERED') and tender_submission_deadline<now() for update
            """,tenant());
        for(Map<String,Object> row:expired){UUID id=(UUID)row.get("id");String reason="Tender submission deadline passed without accepted submission";
            jdbc.update("update commodity.trade_enquiry set status='EXPIRED',lapse_reason=?,version=version+1,updated_at=now() where tenant_id=? and id=?",reason,tenant(),id);
            recordException(id,"TENDER",reason);audit.record("COMMODITY_TENDER_LAPSED","COMMODITY_ENQUIRY",id,"Auto-closed lapsed tender",Map.of("reason",reason));
            outbox.write("commodity_enquiry",id,"commodity.tender.lapsed",Map.of("reason",reason));}
        return new ActionResult(UUID.randomUUID(),"COMPLETED","Tender deadline sweep completed.",Map.of("lapsed",expired.size()));}

    @Transactional(readOnly=true)
    public List<ExceptionView> exceptions(String status){requireRead();String s=clean(status);if(s!=null)s=s.toUpperCase(Locale.ROOT);return jdbc.query("""
            select x.id,x.exception_type,x.status,x.reason,u.display_name,x.created_at from commodity.origination_exception x
             join identity.app_user u on u.tenant_id=x.tenant_id and u.id=x.owner_id
             where x.tenant_id=? and (?::text is null or x.status=?) order by x.created_at desc limit 100
            """,(rs,i)->new ExceptionView(rs.getObject("id",UUID.class),rs.getString("exception_type"),rs.getString("status"),
                    rs.getString("reason"),rs.getString("display_name"),rs.getTimestamp("created_at").toInstant()),tenant(),s,s);}

    static List<String> gates(Enquiry e){List<String>b=new ArrayList<>();Counterparty c=e.counterparty();
        if(!"ACTIVE".equals(c.status()))b.add("Counterparty is not ACTIVE");
        if(!"EXECUTED".equals(c.agreementStatus())||c.agreementExpiresAt()==null||c.agreementExpiresAt().isBefore(LocalDate.now()))b.add("An unexpired executed master agreement is required");
        if(!c.creditFresh())b.add("Credit data is unavailable, future-dated, or older than 60 minutes");
        if(c.headroom()==null)b.add("Source-provided credit headroom is unavailable");else if(c.headroom().compareTo(e.notional())<0)b.add("Source-provided credit headroom is below enquiry notional");return b;}
    private void recordException(UUID enquiryId,String type,String reason){Long exists=jdbc.queryForObject("select count(*) from commodity.origination_exception where tenant_id=? and trade_enquiry_id=? and exception_type=? and status='OPEN'",Long.class,tenant(),enquiryId,type);if(exists!=null&&exists>0)return;
        jdbc.update("""
                insert into commodity.origination_exception(tenant_id,trade_enquiry_id,exception_type,status,reason,owner_id)
                select e.tenant_id,e.id,?,'OPEN',?,p.owner_id from commodity.trade_enquiry e
                  join commodity.counterparty_profile p on p.tenant_id=e.tenant_id and p.id=e.counterparty_profile_id
                 where e.tenant_id=? and e.id=?
                """,type,reason,tenant(),enquiryId);}

    private String baseEnquirySql(){return """
            select e.id,e.enquiry_number,e.origination_type,e.commodity_name,e.grade,e.status,e.quantity,e.unit,
                   e.quantity_tolerance_pct,e.notional_amount,e.delivery_window_start,e.delivery_window_end,
                   e.delivery_location_from,e.delivery_location_to,e.incoterm,e.tender_submission_deadline,e.lapse_reason,
                   e.version,e.execution_status,e.trade_reference,p.id cp_id,p.counterparty_code,p.status cp_status,
                   a.name account_name,p.master_agreement_status,p.master_agreement_reference,p.master_agreement_expires_at,
                   p.credit_limit,p.exposure_amount,p.credit_headroom,p.credit_source,p.credit_as_of,p.source_synced_at
              from commodity.trade_enquiry e join commodity.counterparty_profile p on p.tenant_id=e.tenant_id and p.id=e.counterparty_profile_id
              join crm.account a on a.tenant_id=p.tenant_id and a.id=p.account_id
            """;}
    private Enquiry enquiryById(UUID id,boolean lock){return jdbc.query(baseEnquirySql()+" where e.tenant_id=? and e.id=?"+(lock?" for update of e":""),(rs,i)->enquiry(rs),tenant(),id).stream().findFirst().orElseThrow(()->new NotFoundException("Commodity enquiry not found"));}
    private Enquiry enquiry(java.sql.ResultSet rs)throws java.sql.SQLException{Counterparty c=new Counterparty(rs.getObject("cp_id",UUID.class),rs.getString("counterparty_code"),rs.getString("account_name"),rs.getString("cp_status"),rs.getString("master_agreement_status"),rs.getString("master_agreement_reference"),rs.getObject("master_agreement_expires_at",LocalDate.class),rs.getBigDecimal("credit_limit"),rs.getBigDecimal("exposure_amount"),rs.getBigDecimal("credit_headroom"),rs.getString("credit_source"),instant(rs.getTimestamp("credit_as_of")),instant(rs.getTimestamp("source_synced_at")),fresh(instant(rs.getTimestamp("credit_as_of"))));
        return new Enquiry(rs.getObject("id",UUID.class),rs.getString("enquiry_number"),rs.getString("origination_type"),rs.getString("commodity_name"),rs.getString("grade"),rs.getString("status"),rs.getBigDecimal("quantity"),rs.getString("unit"),rs.getBigDecimal("quantity_tolerance_pct"),rs.getBigDecimal("notional_amount"),rs.getObject("delivery_window_start",LocalDate.class),rs.getObject("delivery_window_end",LocalDate.class),rs.getString("delivery_location_from"),rs.getString("delivery_location_to"),rs.getString("incoterm"),instant(rs.getTimestamp("tender_submission_deadline")),rs.getString("lapse_reason"),rs.getInt("version"),rs.getString("execution_status"),rs.getString("trade_reference"),c);}
    private Counterparty counterparty(UUID id,boolean lock){return jdbc.query("""
            select p.id,p.counterparty_code,a.name,p.status,p.master_agreement_status,p.master_agreement_reference,
                   p.master_agreement_expires_at,p.credit_limit,p.exposure_amount,p.credit_headroom,p.credit_source,
                   p.credit_as_of,p.source_synced_at from commodity.counterparty_profile p
              join crm.account a on a.tenant_id=p.tenant_id and a.id=p.account_id where p.tenant_id=? and p.id=?
            """+(lock?" for update of p":""),(rs,i)->new Counterparty(rs.getObject("id",UUID.class),rs.getString("counterparty_code"),rs.getString("name"),rs.getString("status"),rs.getString("master_agreement_status"),rs.getString("master_agreement_reference"),rs.getObject("master_agreement_expires_at",LocalDate.class),rs.getBigDecimal("credit_limit"),rs.getBigDecimal("exposure_amount"),rs.getBigDecimal("credit_headroom"),rs.getString("credit_source"),instant(rs.getTimestamp("credit_as_of")),instant(rs.getTimestamp("source_synced_at")),fresh(instant(rs.getTimestamp("credit_as_of")))),tenant(),id).stream().findFirst().orElseThrow(()->new NotFoundException("Commodity counterparty not found"));}
    private Map<String,Object> counterpartyEvidence(Counterparty c){Map<String,Object>m=new LinkedHashMap<>();m.put("creditLimit",c.creditLimit());m.put("exposure",c.exposure());m.put("headroom",c.headroom());m.put("agreementStatus",c.agreementStatus());m.put("source",c.source());m.put("asOf",c.asOf());return m;}
    private TermSheet termById(UUID id){return jdbc.query("select id,term_sheet_number,version,status,incoterm,pricing_basis,terms::text,approval_request_id,approved_at from commodity.contract_term_sheet where tenant_id=? and id=?",(rs,i)->term(rs),tenant(),id).stream().findFirst().orElseThrow(()->new NotFoundException("Commodity term sheet not found"));}
    private TermSheet term(java.sql.ResultSet rs)throws java.sql.SQLException{return new TermSheet(rs.getObject("id",UUID.class),rs.getString("term_sheet_number"),rs.getInt("version"),rs.getString("status"),rs.getString("incoterm"),rs.getString("pricing_basis"),read(rs.getString("terms")),rs.getObject("approval_request_id",UUID.class),instant(rs.getTimestamp("approved_at")));}
    private Handoff handoffByKey(UUID enquiryId,int version){return jdbc.query("select id,enquiry_version,idempotency_key,status,attempt_count,max_attempts,payload::text,external_trade_reference,last_error,created_at from commodity.execution_handoff where tenant_id=? and trade_enquiry_id=? and enquiry_version=?",(rs,i)->handoff(rs),tenant(),enquiryId,version).stream().findFirst().orElseThrow(()->new NotFoundException("Execution handoff not found"));}
    private Handoff handoffById(UUID id,boolean lock){return jdbc.query("select id,enquiry_version,idempotency_key,status,attempt_count,max_attempts,payload::text,external_trade_reference,last_error,created_at from commodity.execution_handoff where tenant_id=? and id=?"+(lock?" for update":""),(rs,i)->handoff(rs),tenant(),id).stream().findFirst().orElseThrow(()->new NotFoundException("Execution handoff not found"));}
    private Handoff handoff(java.sql.ResultSet rs)throws java.sql.SQLException{return new Handoff(rs.getObject("id",UUID.class),rs.getInt("enquiry_version"),rs.getString("idempotency_key"),rs.getString("status"),rs.getInt("attempt_count"),rs.getInt("max_attempts"),read(rs.getString("payload")),rs.getString("external_trade_reference"),rs.getString("last_error"),rs.getTimestamp("created_at").toInstant());}
    private Map<String,Object> one(String sql,UUID id,String message){return jdbc.query(sql,(rs,i)->{Map<String,Object>m=new LinkedHashMap<>();for(int c=1;c<=rs.getMetaData().getColumnCount();c++)m.put(rs.getMetaData().getColumnLabel(c),rs.getObject(c));return m;},tenant(),id).stream().findFirst().orElseThrow(()->new NotFoundException(message));}
    private void requireRead(){if(!READ_ROLES.contains(TenantContext.get().role()))throw new ForbiddenException("Commodity origination requires an authorized sales, finance, operations, administrator or auditor role");}
    private void requireWrite(){requireRead();CrmRole.requireWrite(TenantContext.get().role());}
    private void requireApprover(){requireWrite();String r=TenantContext.get().role();if(!(CrmRole.current(r).masterAdmin()||Set.of("FINANCE","OPERATIONS","SALES_MANAGER").contains(r)))throw new ForbiddenException("Commodity term approval requires finance, operations, sales manager or administrator authority");}
    private void requireIntegrationOrAdmin(){String r=TenantContext.get().role();if("INTEGRATION".equals(r)||CrmRole.current(r).masterAdmin())return;throw new ForbiddenException("Trading-source updates require an integration identity or administrator");}
    private String enumValue(String value,Set<String>a,String label){String v=value==null?"":value.trim().toUpperCase(Locale.ROOT);if(!a.contains(v))throw new ConflictException("Unsupported "+label+": "+value);return v;}
    private boolean fresh(Instant t){return t!=null&&!t.isAfter(Instant.now())&&Duration.between(t,Instant.now()).compareTo(CREDIT_FRESHNESS)<=0;}
    private Timestamp timestamp(Instant v){return v==null?null:Timestamp.from(v);}private Instant instant(Timestamp v){return v==null?null:v.toInstant();}
    private String clean(String v){return v==null||v.isBlank()?null:v.trim();}
    private String write(JsonNode v){try{return json.writeValueAsString(v);}catch(JsonProcessingException e){throw new IllegalArgumentException("Invalid term JSON",e);}}
    private String writeValue(Object v){try{return json.writeValueAsString(v);}catch(JsonProcessingException e){throw new IllegalArgumentException("Invalid handoff payload",e);}}
    private JsonNode read(String v){try{return json.readTree(v);}catch(JsonProcessingException e){throw new IllegalStateException("Stored commodity evidence invalid",e);}}
    private UUID tenant(){return TenantContext.get().tenantId();}private UUID actor(){return TenantContext.get().userId();}
}

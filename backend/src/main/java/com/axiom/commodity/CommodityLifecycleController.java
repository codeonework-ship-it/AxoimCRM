package com.axiom.commodity;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/commodity")
public class CommodityLifecycleController {
    private final CommodityLifecycleService commodity;
    public CommodityLifecycleController(CommodityLifecycleService commodity){this.commodity=commodity;}
    @GetMapping("/enquiries") public List<CommodityLifecycleService.Enquiry> enquiries(@RequestParam(required=false)String status,@RequestParam(defaultValue="0")int page){return commodity.enquiries(status,page);}
    @GetMapping("/enquiries/{id}") public CommodityLifecycleService.EnquiryDetail detail(@PathVariable UUID id){return commodity.detail(id);}
    @PostMapping("/counterparties/{id}/source-state") public CommodityLifecycleService.Counterparty state(@PathVariable UUID id,@RequestBody @Valid CommodityLifecycleService.CounterpartyStateRequest r){return commodity.ingestCounterpartyState(id,r);}
    @PostMapping("/enquiries") @ResponseStatus(HttpStatus.CREATED) public CommodityLifecycleService.EnquiryDetail create(@RequestBody @Valid CommodityLifecycleService.EnquiryRequest r){return commodity.createEnquiry(r);}
    @PostMapping("/enquiries/{id}/prices") @ResponseStatus(HttpStatus.CREATED) public CommodityLifecycleService.IndicativePrice price(@PathVariable UUID id,@RequestBody @Valid CommodityLifecycleService.PriceRequest r){return commodity.price(id,r);}
    @PostMapping("/enquiries/{id}/term-sheets") @ResponseStatus(HttpStatus.CREATED) public CommodityLifecycleService.TermSheet term(@PathVariable UUID id,@RequestBody @Valid CommodityLifecycleService.TermSheetRequest r){return commodity.createTermSheet(id,r);}
    @PostMapping("/term-sheets/{id}/submit") public CommodityLifecycleService.TermSheet submit(@PathVariable UUID id,@RequestBody @Valid CommodityLifecycleService.NoteRequest r){return commodity.submitTermApproval(id,r);}
    @PostMapping("/term-sheets/{id}/approve") public CommodityLifecycleService.TermSheet approve(@PathVariable UUID id,@RequestBody @Valid CommodityLifecycleService.DecisionRequest r){return commodity.decideTerm(id,r,true);}
    @PostMapping("/term-sheets/{id}/reject") public CommodityLifecycleService.TermSheet reject(@PathVariable UUID id,@RequestBody @Valid CommodityLifecycleService.DecisionRequest r){return commodity.decideTerm(id,r,false);}
    @PostMapping("/enquiries/{id}/offer") public CommodityLifecycleService.ActionResult offer(@PathVariable UUID id){return commodity.offer(id);}
    @PostMapping("/enquiries/{id}/close-won") public CommodityLifecycleService.Handoff closeWon(@PathVariable UUID id,@RequestBody @Valid CommodityLifecycleService.NoteRequest r){return commodity.closeWonAndQueue(id,r);}
    @PostMapping("/handoffs/{id}/attempt") public CommodityLifecycleService.Handoff attempt(@PathVariable UUID id,@RequestBody CommodityLifecycleService.HandoffAttemptRequest r){return commodity.recordAttempt(id,r);}
    @PostMapping("/handoffs/{id}/acknowledge") public CommodityLifecycleService.Handoff acknowledge(@PathVariable UUID id,@RequestBody @Valid CommodityLifecycleService.AcknowledgeRequest r){return commodity.acknowledge(id,r);}
    @PostMapping("/tenders/sweep") public CommodityLifecycleService.ActionResult sweep(){return commodity.sweepTenders();}
    @GetMapping("/exceptions") public List<CommodityLifecycleService.ExceptionView> exceptions(@RequestParam(required=false)String status){return commodity.exceptions(status);}
}

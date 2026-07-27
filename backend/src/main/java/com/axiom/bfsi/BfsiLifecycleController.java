package com.axiom.bfsi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bfsi")
public class BfsiLifecycleController {
    public record NoteRequest(@NotBlank String note) {}
    private final BfsiLifecycleService bfsi;
    public BfsiLifecycleController(BfsiLifecycleService bfsi){this.bfsi=bfsi;}

    @GetMapping("/onboardings") public List<BfsiLifecycleService.OnboardingSummary> list(@RequestParam(required=false)String status,@RequestParam(defaultValue="0")int page){return bfsi.onboardings(status,page);}
    @GetMapping("/onboardings/{id}") public BfsiLifecycleService.OnboardingDetail detail(@PathVariable UUID id){return bfsi.detail(id);}
    @PostMapping("/onboardings") @ResponseStatus(HttpStatus.CREATED) public BfsiLifecycleService.OnboardingDetail create(@RequestBody @Valid BfsiLifecycleService.OnboardingRequest r){return bfsi.create(r);}
    @PostMapping("/onboardings/{id}/kyc-items/{itemId}") public BfsiLifecycleService.ActionResult kyc(@PathVariable UUID id,@PathVariable UUID itemId,@RequestBody @Valid BfsiLifecycleService.KycItemRequest r){return bfsi.updateKycItem(id,itemId,r);}
    @PostMapping("/onboardings/{id}/screenings") @ResponseStatus(HttpStatus.CREATED) public BfsiLifecycleService.Screening screen(@PathVariable UUID id,@RequestBody @Valid BfsiLifecycleService.ScreeningRequest r){return bfsi.runScreening(id,r);}
    @PostMapping("/screenings/{id}/disposition") public BfsiLifecycleService.Screening disposition(@PathVariable UUID id,@RequestBody @Valid BfsiLifecycleService.DispositionRequest r){return bfsi.disposition(id,r);}
    @PostMapping("/onboardings/{id}/risk") public BfsiLifecycleService.ActionResult risk(@PathVariable UUID id,@RequestBody @Valid BfsiLifecycleService.RiskRequest r){return bfsi.rateRisk(id,r);}
    @PostMapping("/onboardings/{id}/activate") public BfsiLifecycleService.ActionResult activate(@PathVariable UUID id,@RequestBody @Valid NoteRequest r){return bfsi.activate(id,r.note());}
    @PostMapping("/onboardings/{id}/holdings") @ResponseStatus(HttpStatus.CREATED) public BfsiLifecycleService.ActionResult holding(@PathVariable UUID id,@RequestBody @Valid BfsiLifecycleService.HoldingRequest r){return bfsi.addHolding(id,r);}
    @PostMapping("/onboardings/{id}/suitability") @ResponseStatus(HttpStatus.CREATED) public BfsiLifecycleService.ActionResult suitability(@PathVariable UUID id,@RequestBody @Valid BfsiLifecycleService.SuitabilityRequest r){return bfsi.assessSuitability(id,r);}
    @PostMapping("/onboardings/{id}/recommendations") @ResponseStatus(HttpStatus.CREATED) public BfsiLifecycleService.Recommendation recommend(@PathVariable UUID id,@RequestBody @Valid BfsiLifecycleService.RecommendationRequest r){return bfsi.recommend(id,r);}
    @PostMapping("/recommendations/{id}/approve") public BfsiLifecycleService.Recommendation approveRecommendation(@PathVariable UUID id,@RequestBody @Valid BfsiLifecycleService.DecisionRequest r){return bfsi.decideRecommendation(id,r,true);}
    @PostMapping("/recommendations/{id}/reject") public BfsiLifecycleService.Recommendation rejectRecommendation(@PathVariable UUID id,@RequestBody @Valid BfsiLifecycleService.DecisionRequest r){return bfsi.decideRecommendation(id,r,false);}
    @PostMapping("/onboardings/{id}/exceptions") @ResponseStatus(HttpStatus.CREATED) public BfsiLifecycleService.ExceptionView exception(@PathVariable UUID id,@RequestBody @Valid BfsiLifecycleService.ExceptionRequest r){return bfsi.createException(id,r);}
    @PostMapping("/exceptions/{id}/approve") public BfsiLifecycleService.ExceptionView approveException(@PathVariable UUID id,@RequestBody @Valid BfsiLifecycleService.DecisionRequest r){return bfsi.decideException(id,r,true);}
    @PostMapping("/exceptions/{id}/reject") public BfsiLifecycleService.ExceptionView rejectException(@PathVariable UUID id,@RequestBody @Valid BfsiLifecycleService.DecisionRequest r){return bfsi.decideException(id,r,false);}
}

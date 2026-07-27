package com.axiom.api;

import com.axiom.pipeline.OpportunityLifecycleService;
import com.axiom.pipeline.OpportunityCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/opportunities")
public class OpportunityController {

    private final OpportunityService opportunityService;
    private final OpportunityLifecycleService lifecycle;
    private final OpportunityCrudService crud;
    private final OpportunityCommandService commands;

    public OpportunityController(OpportunityService opportunityService,
                                 OpportunityLifecycleService lifecycle,
                                 OpportunityCrudService crud,
                                 OpportunityCommandService commands) {
        this.opportunityService = opportunityService;
        this.lifecycle = lifecycle;
        this.crud = crud;
        this.commands = commands;
    }

    @GetMapping
    public PageResult<OpportunityCrudService.OpportunityDetail> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID pipelineId,
            @RequestParam(required = false) UUID stageId,
            @RequestParam(defaultValue = "0") int page) {
        return crud.list(search, pipelineId, stageId, page);
    }

    @GetMapping("/{id}")
    public OpportunityCrudService.OpportunityDetail detail(@PathVariable UUID id) { return crud.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OpportunityCrudService.OpportunityDetail create(
            @RequestBody @Valid OpportunityCrudService.OpportunityRequest request) { return crud.create(request); }

    @PutMapping("/{id}")
    public OpportunityCrudService.OpportunityDetail update(@PathVariable UUID id,
            @RequestBody @Valid OpportunityCrudService.OpportunityUpdateRequest request) {
        return crud.update(id, request);
    }

    public record StageChangeRequest(@NotNull UUID stageId, String reason, Long expectedVersion) {}

    @GetMapping("/{id}/stage-gate")
    public OpportunityLifecycleService.GatePreview previewStage(
            @PathVariable UUID id, @RequestParam UUID targetStageId) {
        return lifecycle.previewGate(id, targetStageId);
    }

    @PostMapping("/{id}/stage")
    public OpportunityLifecycleService.StageChangeResult changeStage(
            @PathVariable UUID id, @RequestBody @Valid StageChangeRequest request) {
        return lifecycle.changeStage(id, new OpportunityLifecycleService.StageChangeRequest(
                request.stageId(), request.reason(), request.expectedVersion()));
    }

    public record ContactRoleRequest(@NotNull UUID contactId, @NotBlank String role) {}

    @PostMapping("/{id}/contact-roles")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addContactRole(@PathVariable UUID id,
                                              @RequestBody @Valid ContactRoleRequest request) {
        UUID roleId = commands.addContactRole(id, request.contactId(), request.role());
        return Map.of("id", roleId, "opportunityId", id,
                "contactId", request.contactId(), "role", request.role());
    }

    @PostMapping("/{id}/close")
    public OpportunityLifecycleService.ClosureResult close(@PathVariable UUID id,
            @RequestBody @Valid OpportunityLifecycleService.CloseRequest request) {
        return lifecycle.close(id, request);
    }

    @PostMapping("/{id}/reopen")
    public OpportunityLifecycleService.ReopenResult reopen(@PathVariable UUID id,
            @RequestBody @Valid OpportunityLifecycleService.ReopenRequest request) {
        return lifecycle.reopen(id, request);
    }

    @PatchMapping("/{id}/close-date")
    public OpportunityLifecycleService.CloseDateResult closeDate(@PathVariable UUID id,
            @RequestBody @Valid OpportunityLifecycleService.CloseDateRequest request) {
        return lifecycle.changeCloseDate(id, request);
    }

    @PatchMapping("/{id}/recurring-revenue")
    public OpportunityLifecycleService.RecurringRevenueResult recurringRevenue(@PathVariable UUID id,
            @RequestBody @Valid OpportunityLifecycleService.RecurringRevenueRequest request) {
        return lifecycle.setRecurringRevenue(id, request);
    }

    @PostMapping("/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    public OpportunityCommandService.LineRow addLine(@PathVariable UUID id,
            @RequestBody @Valid OpportunityCommandService.LineRequest request) {
        return commands.addLine(id, request);
    }

    @PutMapping("/{id}/lines/{lineId}")
    public OpportunityCommandService.LineRow updateLine(@PathVariable UUID id, @PathVariable UUID lineId,
            @RequestBody @Valid OpportunityCommandService.LineRequest request) {
        return commands.updateLine(id, lineId, request);
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeLine(@PathVariable UUID id, @PathVariable UUID lineId) { commands.deleteLine(id, lineId); }

    @GetMapping("/{id}/splits")
    public List<OpportunityCommandService.SplitRow> splits(@PathVariable UUID id,
            @RequestParam(defaultValue = "REVENUE") String type) { return commands.splits(id, type); }

    @PutMapping("/{id}/splits")
    public List<OpportunityCommandService.SplitRow> replaceSplits(@PathVariable UUID id,
            @RequestBody @Valid OpportunityCommandService.SplitRequest request) {
        return commands.replaceSplits(id, request);
    }

    @PutMapping("/{id}/competitors")
    public OpportunityCommandService.OpportunityCompetitorRow competitor(@PathVariable UUID id,
            @RequestBody @Valid OpportunityCommandService.CompetitorRequest request) {
        return commands.upsertCompetitor(id, request);
    }

    @DeleteMapping("/{id}/competitors/{competitorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCompetitor(@PathVariable UUID id, @PathVariable UUID competitorId) {
        commands.removeCompetitor(id, competitorId);
    }

    @PutMapping("/{id}/qualification")
    public OpportunityCommandService.QualificationResult qualification(@PathVariable UUID id,
            @RequestBody @Valid OpportunityCommandService.QualificationRequest request) {
        return commands.saveQualification(id, request);
    }

    @DeleteMapping("/{id}/contact-roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeContactRole(@PathVariable UUID id, @PathVariable UUID roleId) {
        commands.removeContactRole(id, roleId);
    }

    @PostMapping("/{id}/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public void approval(@PathVariable UUID id,
            @RequestBody @Valid OpportunityCommandService.ApprovalRequest request) {
        commands.recordApproval(id, request);
    }
}

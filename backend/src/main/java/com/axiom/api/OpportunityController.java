package com.axiom.api;

import com.axiom.pipeline.OpportunityLifecycleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/opportunities")
public class OpportunityController {

    private final OpportunityService opportunityService;
    private final OpportunityLifecycleService lifecycle;

    public OpportunityController(OpportunityService opportunityService,
                                 OpportunityLifecycleService lifecycle) {
        this.opportunityService = opportunityService;
        this.lifecycle = lifecycle;
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
        UUID roleId = opportunityService.addContactRole(id, request.contactId(), request.role());
        return Map.of("id", roleId, "opportunityId", id,
                "contactId", request.contactId(), "role", request.role());
    }
}

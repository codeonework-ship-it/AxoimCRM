package com.axiom.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/opportunities")
public class OpportunityController {

    private final OpportunityService opportunityService;

    public OpportunityController(OpportunityService opportunityService) {
        this.opportunityService = opportunityService;
    }

    public record StageChangeRequest(@NotNull UUID stageId) {}

    @PostMapping("/{id}/stage")
    public Map<String, Object> changeStage(@PathVariable UUID id,
                                           @RequestBody @Valid StageChangeRequest request) {
        opportunityService.changeStage(id, request.stageId());
        return Map.of("opportunityId", id, "stageId", request.stageId(), "status", "moved");
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

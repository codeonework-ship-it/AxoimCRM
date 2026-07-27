package com.axiom.sandbox;

import com.axiom.security.MakerCheckerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** HTTP boundary for E19 sandbox, release, rollback and recovery governance. */
@RestController
@RequestMapping("/api/v1/release")
@Validated
public class ReleaseManagementController {
    public record DecisionRequest(@NotNull UUID approvalRequestId, @NotBlank String note) {}
    public record RollbackRequest(@NotBlank String reason) {}

    private final ReleaseManagementService releases;

    public ReleaseManagementController(ReleaseManagementService releases) {
        this.releases = releases;
    }

    @GetMapping("/sandboxes")
    public List<ReleaseManagementService.SandboxView> sandboxes() {
        return releases.sandboxes();
    }

    @PostMapping("/sandboxes")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseManagementService.SandboxView createSandbox(
            @RequestBody @Valid ReleaseManagementService.SandboxRequest request) {
        return releases.createSandbox(request);
    }

    @PostMapping("/sandboxes/{id}/outbound")
    public ReleaseManagementService.SandboxView configureOutbound(
            @PathVariable UUID id,
            @RequestBody @Valid ReleaseManagementService.OutboundRequest request) {
        return releases.configureOutbound(id, request);
    }

    @GetMapping("/packages")
    public List<ReleaseManagementService.ReleasePackage> packages() {
        return releases.packages();
    }

    @PostMapping("/packages")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseManagementService.ReleasePackage createPackage(
            @RequestBody @Valid ReleaseManagementService.PackageRequest request) {
        return releases.createPackage(request);
    }

    @GetMapping("/packages/{id}/components")
    public List<ReleaseManagementService.ReleaseComponent> components(@PathVariable UUID id) {
        return releases.components(id);
    }

    @PostMapping("/packages/{id}/components")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseManagementService.ReleaseComponent addComponent(
            @PathVariable UUID id,
            @RequestBody @Valid ReleaseManagementService.ComponentRequest request) {
        return releases.addComponent(id, request);
    }

    @PostMapping("/packages/{id}/validate")
    public ReleaseManagementService.ValidationResult validate(@PathVariable UUID id) {
        return releases.validate(id);
    }

    @PostMapping("/packages/{id}/submit-approval")
    public MakerCheckerService.ApprovalRequest submitApproval(@PathVariable UUID id) {
        return releases.submitForApproval(id);
    }

    @PostMapping("/packages/{id}/approve")
    public ReleaseManagementService.ReleasePackage approve(
            @PathVariable UUID id, @RequestBody @Valid DecisionRequest request) {
        return releases.approve(id, request.approvalRequestId(), request.note());
    }

    @PostMapping("/packages/{id}/reject")
    public ReleaseManagementService.ReleasePackage reject(
            @PathVariable UUID id, @RequestBody @Valid DecisionRequest request) {
        return releases.reject(id, request.approvalRequestId(), request.note());
    }

    @PostMapping("/packages/{id}/deploy")
    public ReleaseManagementService.DeploymentResult deploy(@PathVariable UUID id) {
        return releases.deploy(id);
    }

    @GetMapping("/deployments/{id}/rollback-preview")
    public ReleaseManagementService.RollbackPreview rollbackPreview(@PathVariable UUID id) {
        return releases.rollbackPreview(id);
    }

    @PostMapping("/deployments/{id}/rollback")
    public ReleaseManagementService.RollbackResult rollback(
            @PathVariable UUID id, @RequestBody @Valid RollbackRequest request) {
        return releases.rollback(id, request.reason());
    }

    @GetMapping("/dr/baseline")
    public ReleaseManagementService.RecoveryBaseline recoveryBaseline() {
        return releases.recoveryBaseline();
    }

    @PostMapping("/dr/validate")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseManagementService.DrValidation validateRecovery(
            @RequestBody @Valid ReleaseManagementService.DrValidationRequest request) {
        return releases.validateRecovery(request);
    }

    @GetMapping("/dr/validations")
    public List<ReleaseManagementService.DrValidation> recoveryHistory(
            @RequestParam(defaultValue = "20") int limit) {
        return releases.recoveryHistory(limit);
    }
}

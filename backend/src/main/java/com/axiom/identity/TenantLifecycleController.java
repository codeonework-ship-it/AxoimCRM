package com.axiom.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Tenant provisioning and lifecycle for platform operators (FR-TEN-001, FR-TEN-002).
 *
 * <p>Both mutations demand a fresh step-up: provisioning creates a new data scope
 * and termination starts a clock that ends in data destruction. Neither is something
 * a stolen session should be able to do.
 */
@RestController
@RequestMapping("/api/v1/platform/tenants")
public class TenantLifecycleController {

    private final TenantLifecycleService lifecycle;
    private final StepUpService stepUp;

    public TenantLifecycleController(TenantLifecycleService lifecycle, StepUpService stepUp) {
        this.lifecycle = lifecycle;
        this.stepUp = stepUp;
    }

    public record TransitionRequest(@NotBlank String status, @NotBlank String reason,
                                    boolean confirmed, Integer retentionDays) {}

    @GetMapping
    public List<TenantLifecycleService.TenantRow> list() {
        return lifecycle.list();
    }

    @GetMapping("/{tenantId}")
    public TenantLifecycleService.TenantRow get(@PathVariable UUID tenantId) {
        return lifecycle.get(tenantId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantLifecycleService.ProvisionResult provision(
            @RequestBody @Valid TenantLifecycleService.ProvisionRequest request) {
        stepUp.requireStepUp("Provisioning a workspace");
        return lifecycle.provision(request);
    }

    @PostMapping("/{tenantId}/status")
    public TenantLifecycleService.TenantRow transition(@PathVariable UUID tenantId,
                                                       @RequestBody @Valid TransitionRequest request) {
        stepUp.requireStepUp("Changing a workspace lifecycle state");
        return lifecycle.transition(tenantId, request.status(), request.reason(),
                request.confirmed(), request.retentionDays());
    }
}

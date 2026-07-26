package com.axiom.access;

import com.axiom.identity.StepUpService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The authenticated half of the access layer: the trial-request review queue that
 * the Trial accounts master drives.
 *
 * <p>Everything here is platform-operator only, enforced in the service against
 * {@code CrmRole} rather than here, so a future caller that bypasses this
 * controller inherits the check. Approval additionally requires a step-up
 * re-authentication: it creates a tenant, seeds it and issues credentials, which
 * is the class of action a stolen session should not be able to perform in
 * silence.
 *
 * <p>Company-account actions the master also needs — extend a trial, convert to
 * paid — are NOT duplicated here. They already exist on {@code /api/v1/admin} and
 * the UI calls them there; a second endpoint for the same state change is a
 * second place for the audit rules to be forgotten.
 */
@RestController
@RequestMapping("/api/v1/access")
public class AccessAdminController {

    private final TrialRequestService trials;
    private final TrialProvisioningService provisioning;
    private final StepUpService stepUp;

    public AccessAdminController(TrialRequestService trials, TrialProvisioningService provisioning,
                                 StepUpService stepUp) {
        this.trials = trials;
        this.provisioning = provisioning;
        this.stepUp = stepUp;
    }

    public record RejectRequest(String reason) {}

    @GetMapping("/trial-requests")
    public List<TrialRequestService.TrialRequestRow> list(
            @RequestParam(required = false) String status) {
        return trials.list(status);
    }

    @GetMapping("/trial-requests/{id}")
    public TrialRequestService.TrialRequestRow get(@PathVariable UUID id) {
        return trials.get(id);
    }

    @PostMapping("/trial-requests/{id}/approve")
    public TrialProvisioningService.ApprovalResult approve(
            @PathVariable UUID id,
            @RequestBody(required = false) TrialProvisioningService.ApprovalRequest request) {
        stepUp.requireStepUp("Approving a trial request and provisioning a workspace");
        return provisioning.approve(id, request);
    }

    @PostMapping("/trial-requests/{id}/reject")
    public TrialRequestService.TrialRequestRow reject(@PathVariable UUID id,
                                                      @RequestBody(required = false) RejectRequest request) {
        return trials.reject(id, request == null ? null : request.reason());
    }

    /** Sweeps requests nobody acted on within the pending window. */
    @PostMapping("/trial-requests/expire-stale")
    public Map<String, Object> expireStale() {
        int expired = trials.expireStale();
        return Map.of("expired", expired,
                "message", expired == 0
                        ? "No requests had been sitting unactioned for longer than "
                          + TrialRequestService.PENDING_EXPIRY_DAYS + " days."
                        : expired + " request(s) older than " + TrialRequestService.PENDING_EXPIRY_DAYS
                          + " days were marked expired.");
    }
}

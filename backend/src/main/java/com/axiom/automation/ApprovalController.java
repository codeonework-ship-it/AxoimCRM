package com.axiom.automation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Approvals: the designer, the inbox and the decision routes (FR-AUT-007,
 * FR-AUT-008, FR-SEC-010).
 */
@RestController
@RequestMapping("/api/v1/automation/approvals")
@Validated
public class ApprovalController {

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping("/processes")
    public List<ApprovalService.ProcessView> processes() {
        return approvals.processes();
    }

    @PostMapping("/submit")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalService.ApprovalInstanceView submit(
            @RequestBody @Valid ApprovalService.SubmitRequest request) {
        return approvals.submit(request);
    }

    /** What a submission would route to, without creating anything. */
    @GetMapping("/preview")
    public ApprovalService.ApprovalPreview preview(@RequestParam String processCode,
                                                   @RequestParam String objectType,
                                                   @RequestParam UUID recordId) {
        AutomationAccess.requireRead();
        return approvals.preview(processCode, objectType, recordId);
    }

    @GetMapping
    public List<ApprovalService.ApprovalInstanceView> instances(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID recordId) {
        return approvals.instances(status, recordId);
    }

    @GetMapping("/{id}")
    public ApprovalService.ApprovalInstanceView get(@PathVariable UUID id) {
        AutomationAccess.requireRead();
        return approvals.get(id);
    }

    /** The signed-in user's outstanding approval steps. */
    @GetMapping("/inbox")
    public List<ApprovalService.TaskView> inbox() {
        return approvals.inbox();
    }

    @PostMapping("/tasks/{taskId}/approve")
    public ApprovalService.ApprovalInstanceView approve(@PathVariable UUID taskId,
                                                        @RequestBody(required = false)
                                                        ApprovalService.DecisionRequest request) {
        return approvals.approve(taskId, request == null ? null : request.comment());
    }

    /** Rejection requires a reason; the service refuses a blank one. */
    @PostMapping("/tasks/{taskId}/reject")
    public ApprovalService.ApprovalInstanceView reject(@PathVariable UUID taskId,
                                                       @RequestBody(required = false)
                                                       ApprovalService.DecisionRequest request) {
        return approvals.reject(taskId, request == null ? null : request.comment());
    }

    @PostMapping("/{id}/recall")
    public ApprovalService.ApprovalInstanceView recall(@PathVariable UUID id,
                                                       @RequestBody(required = false)
                                                       Map<String, String> body) {
        return approvals.recall(id, body == null ? null : body.get("reason"));
    }

    @PostMapping("/{id}/resubmit")
    public ApprovalService.ApprovalInstanceView resubmit(@PathVariable UUID id,
                                                         @RequestBody(required = false)
                                                         Map<String, String> body) {
        return approvals.resubmit(id, body == null ? null : body.get("comment"));
    }

    // ------------------------------------------------------------------ delegation (FR-AUT-008)

    @GetMapping("/delegations")
    public List<ApprovalService.DelegationView> delegations() {
        return approvals.delegations();
    }

    @PostMapping("/delegations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalService.DelegationView delegate(
            @RequestBody @Valid ApprovalService.DelegationRequest request) {
        return approvals.delegate(request);
    }

    @DeleteMapping("/delegations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        approvals.revokeDelegation(id);
    }
}

package com.axiom.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Reviewed queue, decision and delegation boundary for maker-checker. */
@RestController
@RequestMapping("/api/v1/security/approvals")
public class MakerCheckerController {

    private final MakerCheckerService approvals;
    private final RbacChangeApprovalService rbacChanges;

    public MakerCheckerController(MakerCheckerService approvals, RbacChangeApprovalService rbacChanges) {
        this.approvals = approvals;
        this.rbacChanges = rbacChanges;
    }

    public record DecisionRequest(@NotBlank String note) {}
    public record DelegateRequest(@NotNull UUID delegateId, Instant expiresAt) {}

    @GetMapping
    public List<MakerCheckerService.ApprovalRequest> list(@RequestParam(required = false) String status) {
        RbacAccess.requireRead();
        return approvals.list(status);
    }

    @GetMapping("/{id}")
    public MakerCheckerService.ApprovalRequest find(@PathVariable UUID id) {
        RbacAccess.requireRead();
        return approvals.find(id);
    }

    @PostMapping("/{id}/approve")
    public MakerCheckerService.ApprovalRequest approve(@PathVariable UUID id,
                                                       @RequestBody @Valid DecisionRequest request) {
        RbacAccess.requireWrite("approve an RBAC grant");
        return rbacChanges.approveAndApply(id, request.note());
    }

    @PostMapping("/{id}/reject")
    public MakerCheckerService.ApprovalRequest reject(@PathVariable UUID id,
                                                      @RequestBody @Valid DecisionRequest request) {
        RbacAccess.requireWrite("reject an RBAC grant");
        return rbacChanges.reject(id, request.note());
    }

    @GetMapping("/delegations")
    public List<MakerCheckerService.Delegation> delegations() {
        RbacAccess.requireRead();
        return approvals.delegations();
    }

    @PostMapping("/delegations")
    public MakerCheckerService.Delegation delegate(@RequestBody @Valid DelegateRequest request) {
        RbacAccess.requireWrite("delegate approval authority");
        return approvals.delegate(request.delegateId(), request.expiresAt());
    }

    @DeleteMapping("/delegations/{id}")
    public void revokeDelegation(@PathVariable UUID id) {
        RbacAccess.requireWrite("revoke approval delegation");
        approvals.revokeDelegation(id);
    }
}

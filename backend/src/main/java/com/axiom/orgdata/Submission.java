package com.axiom.orgdata;

import java.util.UUID;

/**
 * Result of a mutation against governed master data (FR-MDM-010).
 *
 * <p>Two outcomes are both success: the change was applied, or the change was
 * accepted and is waiting for approval. The caller is told which, and given the
 * change-request id in the second case, rather than being left to infer it from
 * an empty body.
 *
 * @param status  {@code APPLIED} or {@code PENDING_APPROVAL}
 * @param payload the stored record when applied, otherwise null
 */
public record Submission<T>(String status, T payload, UUID changeRequestId, String message) {

    public static final String APPLIED = "APPLIED";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";

    public static <T> Submission<T> applied(T payload) {
        return new Submission<>(APPLIED, payload, null, "Change applied.");
    }

    public static <T> Submission<T> pending(UUID changeRequestId, String masterLabel) {
        return new Submission<>(PENDING_APPROVAL, null, changeRequestId,
                masterLabel + " changes require approval before taking effect. "
                        + "The request has been queued — ask an administrator to approve it.");
    }

    public boolean isApplied() {
        return APPLIED.equals(status);
    }
}

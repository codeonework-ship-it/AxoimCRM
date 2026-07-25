package com.axiom.pipeline;

import com.axiom.common.ForbiddenException;

import java.util.Set;

/**
 * Server-side authorization for opportunity and pipeline actions (FR-GLOBAL-002).
 *
 * <p>Deliberately a small policy object rather than annotations scattered over
 * controllers: FR-OPP-013 makes "reopening requires a permission" a functional
 * requirement, so the set of roles that may reopen a closed deal has to be
 * stated in one readable place that a test can pin down.
 */
public final class PipelinePermissions {

    /** Roles that may not write anything, anywhere. */
    private static final Set<String> READ_ONLY = Set.of("SUPER_AUDIT", "AUDITOR", "INTEGRATION", "FINANCE");

    /** Roles that may edit an opportunity and move it through the pipeline. */
    private static final Set<String> CAN_WRITE = Set.of(
            "SUPER_ADMIN", "TENANT_ADMIN", "SALES_MANAGER", "SALES", "OPERATIONS", "DATA_STEWARD");

    /**
     * Roles that may reopen a closed opportunity (FR-OPP-013). Reopening
     * rewrites what a closed period looks like, so it sits with management and
     * revenue operations — never with the deal owner alone.
     */
    private static final Set<String> CAN_REOPEN = Set.of(
            "SUPER_ADMIN", "TENANT_ADMIN", "SALES_MANAGER", "OPERATIONS");

    /** Roles that may define pipelines, stages and publish gate criteria. */
    private static final Set<String> CAN_CONFIGURE = Set.of(
            "SUPER_ADMIN", "TENANT_ADMIN", "SALES_MANAGER", "OPERATIONS");

    private PipelinePermissions() {}

    public static boolean readOnly(String role) {
        return role == null || READ_ONLY.contains(role) || !CAN_WRITE.contains(role);
    }

    public static void requireWrite(String role) {
        if (readOnly(role)) {
            throw new ForbiddenException(
                    "Your role cannot change opportunities. Ask a sales manager or administrator to make this change.");
        }
    }

    public static boolean canReopen(String role) {
        return role != null && CAN_REOPEN.contains(role);
    }

    public static void requireReopen(String role) {
        if (!canReopen(role)) {
            throw new ForbiddenException(
                    "Reopening a closed opportunity requires the sales manager, revenue operations or administrator "
                            + "permission. Ask one of them to reopen it, and record why.");
        }
    }

    public static boolean canConfigure(String role) {
        return role != null && CAN_CONFIGURE.contains(role);
    }

    public static void requireConfigure(String role) {
        if (!canConfigure(role)) {
            throw new ForbiddenException(
                    "Defining pipelines, stages and stage gate criteria requires the sales manager, revenue "
                            + "operations or administrator permission.");
        }
    }
}

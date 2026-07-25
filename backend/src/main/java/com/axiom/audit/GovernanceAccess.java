package com.axiom.audit;

import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;

import java.util.Set;

/**
 * Who may see and who may act in the governance workspace.
 *
 * <p>Separate from {@link com.axiom.auth.CrmRole}'s existing gates on purpose. The
 * closest existing gate, {@code requireMasterAdmin}, admits {@code DATA_STEWARD} —
 * correct for reference data, wrong for placing a legal hold or revoking a
 * customer-managed key. Reusing it would have quietly granted a data steward the
 * ability to block a court-ordered deletion.
 *
 * <p>Read is broad: an auditor who cannot read the audit trail is not an auditor.
 * Write is narrow: two roles, both of which already carry tenant-administrative
 * responsibility. Read-only roles are refused writes even when they are otherwise
 * privileged, which is the point of {@code SUPER_AUDIT} existing at all.
 */
public final class GovernanceAccess {

    private static final Set<String> READ = Set.of(
            "SUPER_ADMIN", "SUPER_AUDIT", "TENANT_ADMIN", "AUDITOR", "DATA_STEWARD", "OPERATIONS");

    private static final Set<String> WRITE = Set.of("SUPER_ADMIN", "TENANT_ADMIN");

    private GovernanceAccess() {}

    public static void requireRead() {
        String role = TenantContext.get().role();
        if (!READ.contains(role)) {
            throw new ForbiddenException("Your role does not permit access to audit and compliance records");
        }
    }

    public static void requireWrite() {
        String role = TenantContext.get().role();
        if (!WRITE.contains(role)) {
            throw new ForbiddenException(
                    "Only a tenant administrator or platform administrator may change compliance configuration");
        }
    }

    public static boolean canWrite() {
        return TenantContext.isBound() && WRITE.contains(TenantContext.get().role());
    }
}

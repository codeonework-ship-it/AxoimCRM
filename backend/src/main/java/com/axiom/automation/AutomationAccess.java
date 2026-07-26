package com.axiom.automation;

import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;

import java.util.Set;

/**
 * Role gate for the automation surface, mirroring {@code com.axiom.security.RbacAccess}
 * and {@code com.axiom.audit.GovernanceAccess}.
 *
 * <h2>Read is wider than write</h2>
 * An execution log is evidence. An auditor whose job is to explain why a record
 * changed has to be able to read the trace that changed it, so AUDITOR reads
 * everything here and writes nothing.
 *
 * <h2>Approving is not administering</h2>
 * Deciding an approval task is deliberately open to every non-read-only role:
 * the whole point of dynamic approver determination is that the approver is
 * whoever the model names, and requiring an administrator role to approve would
 * make hierarchy and queue approvers undeliverable.
 */
public final class AutomationAccess {

    private static final Set<String> READ = Set.of(
            "SUPER_ADMIN", "SUPER_AUDIT", "TENANT_ADMIN", "AUDITOR", "OPERATIONS",
            "DATA_STEWARD", "SALES_MANAGER", "SALES");

    private static final Set<String> ADMIN = Set.of("SUPER_ADMIN", "TENANT_ADMIN");

    private static final Set<String> READ_ONLY_ROLES = Set.of("AUDITOR", "SUPER_AUDIT");

    private AutomationAccess() {}

    public static void requireRead() {
        if (!READ.contains(TenantContext.get().role())) {
            throw new ForbiddenException("Viewing automation requires an administrator, auditor, "
                    + "operations, steward or sales role.");
        }
    }

    /** Defining, versioning, activating or simulating a rule, process or approval model. */
    public static void requireAdmin(String what) {
        String role = TenantContext.get().role();
        if (ADMIN.contains(role)) return;
        if (READ_ONLY_ROLES.contains(role)) {
            throw new ForbiddenException("The audit role is read-only across every surface, so it cannot "
                    + what + ". Sign in as a Tenant Admin to make this change.");
        }
        throw new ForbiddenException("Only a Tenant Admin may " + what + ".");
    }

    /** Submitting for approval, deciding a task, recalling, delegating. */
    public static void requireParticipant(String what) {
        String role = TenantContext.get().role();
        if (READ_ONLY_ROLES.contains(role)) {
            throw new ForbiddenException("The audit role is read-only across every surface, so it cannot "
                    + what + ".");
        }
        if (!READ.contains(role)) {
            throw new ForbiddenException("Your role may not " + what + ".");
        }
    }

    public static boolean isAdmin() {
        return ADMIN.contains(TenantContext.get().role());
    }
}

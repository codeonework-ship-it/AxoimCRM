package com.axiom.security;

import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;

import java.util.Set;

/**
 * Role gate for the RBAC administration surface, mirroring
 * {@code com.axiom.audit.GovernanceAccess}.
 *
 * <h2>Read is wider than write, deliberately</h2>
 * An auditor whose entire job is to review who can see what must be able to open
 * every one of these screens — including the access explainer, which is the only
 * tool that answers "why does this person have this access". Denying the auditor
 * read here would make the audit role decorative.
 *
 * <h2>Write is narrow, and AUDITOR is not in it</h2>
 * {@code JwtAuthFilter} already refuses every mutation from a {@code readOnly()}
 * role before a controller is reached, which is the enforcement that actually
 * holds. This class repeats it at the service boundary anyway, because that
 * filter guards HTTP and these services are also reachable from schedulers and
 * from other services. A control that only exists at one layer is a control that
 * disappears the first time someone calls the service directly.
 */
public final class RbacAccess {

    private static final Set<String> READ = Set.of(
            "SUPER_ADMIN", "SUPER_AUDIT", "TENANT_ADMIN", "AUDITOR", "OPERATIONS", "DATA_STEWARD");

    private static final Set<String> WRITE = Set.of("SUPER_ADMIN", "TENANT_ADMIN");

    private RbacAccess() {}

    public static void requireRead() {
        if (!READ.contains(TenantContext.get().role())) {
            throw new ForbiddenException("Viewing the authorization model requires an administrator, "
                    + "auditor, operations or data steward role.");
        }
    }

    public static void requireWrite(String what) {
        String role = TenantContext.get().role();
        if (WRITE.contains(role)) return;
        if ("AUDITOR".equals(role) || "SUPER_AUDIT".equals(role)) {
            throw new ForbiddenException("The audit role is read-only across every surface, so it cannot "
                    + what + ". Sign in as a Tenant Admin to make this change.");
        }
        throw new ForbiddenException("Only a Tenant Admin may " + what + ".");
    }

    public static boolean canWrite() {
        return WRITE.contains(TenantContext.get().role());
    }
}

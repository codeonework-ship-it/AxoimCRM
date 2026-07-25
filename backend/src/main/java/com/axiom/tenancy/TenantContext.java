package com.axiom.tenancy;

import java.util.UUID;

/**
 * Ambient tenant/user context for the current request thread.
 *
 * Bound exclusively from the authenticated principal in AuthFilter — never from
 * any client-supplied header or parameter (ADR-001, FR-GLOBAL-001).
 */
public final class TenantContext {

    public record Principal(UUID tenantId, UUID userId, String role, String displayName) {}

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    public static Principal get() {
        Principal p = CURRENT.get();
        if (p == null) {
            throw new IllegalStateException("No tenant context bound to this thread");
        }
        return p;
    }

    public static boolean isBound() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }
}

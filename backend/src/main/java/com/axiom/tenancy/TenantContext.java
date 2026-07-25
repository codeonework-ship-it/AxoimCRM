package com.axiom.tenancy;

import java.util.UUID;

/**
 * Ambient tenant/user context for the current request thread.
 *
 * Bound exclusively from the authenticated principal in AuthFilter — never from
 * any client-supplied header or parameter (ADR-001, FR-GLOBAL-001).
 */
public final class TenantContext {

    public record Principal(UUID tenantId, UUID userId, String role, String displayName, String email) {}

    /**
     * The operator behind an impersonated principal (FR-TEN-011). Present only
     * while a support session is acting as a tenant user. Held separately from
     * {@link Principal} so the impersonated identity remains the one every
     * existing query and RLS binding uses, while audit can still name both.
     */
    public record Impersonator(UUID userId, String email, String displayName) {}

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Impersonator> IMPERSONATOR = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION_JTI = new ThreadLocal<>();
    private static final ThreadLocal<String> CLIENT_IP = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    /** Set by JwtAuthFilter from the verified token; never from a client field. */
    public static void setImpersonator(Impersonator impersonator) {
        IMPERSONATOR.set(impersonator);
    }

    /** @return the operator behind an impersonated session, or null. */
    public static Impersonator impersonator() {
        return IMPERSONATOR.get();
    }

    public static boolean isImpersonating() {
        return IMPERSONATOR.get() != null;
    }

    /** The server-side session id (JWT {@code jti}) backing this request. */
    public static void setSessionJti(String jti) {
        SESSION_JTI.set(jti);
    }

    public static String sessionJti() {
        return SESSION_JTI.get();
    }

    public static void setClientIp(String ip) {
        CLIENT_IP.set(ip);
    }

    public static String clientIp() {
        return CLIENT_IP.get();
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
        IMPERSONATOR.remove();
        SESSION_JTI.remove();
        CLIENT_IP.remove();
    }
}

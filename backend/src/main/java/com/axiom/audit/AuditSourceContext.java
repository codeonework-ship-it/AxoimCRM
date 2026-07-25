package com.axiom.audit;

import java.util.Set;

/**
 * The origin of the action being audited — {@code FR-GLOBAL-005} requires it on
 * every material action, and data model §7 lists it on {@code AUDIT_EVENT}.
 *
 * <p>Held in ambient context rather than passed by callers for the same reason the
 * impersonator is: a parameter that has to be remembered is a parameter that will
 * be wrong on the one event a regulator reads. The web layer binds it per request
 * ({@code RequestSourceFilter}); anything running outside a request — a scheduled
 * retention sweep, a projection rebuild — sees the default {@link #AUTOMATION}.
 */
public final class AuditSourceContext {

    public static final String UI = "UI";
    public static final String API = "API";
    public static final String AUTOMATION = "AUTOMATION";
    public static final String AI = "AI";
    public static final String MIGRATION = "MIGRATION";
    public static final String SYSTEM = "SYSTEM";

    private static final Set<String> KNOWN = Set.of(UI, API, AUTOMATION, AI, MIGRATION, SYSTEM);

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private AuditSourceContext() {}

    public static void set(String source) {
        CURRENT.set(normalize(source));
    }

    /** @return the bound source, or {@link #AUTOMATION} outside a request. */
    public static String get() {
        String source = CURRENT.get();
        return source == null ? AUTOMATION : source;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Unknown values collapse to SYSTEM rather than failing an audit write. */
    public static String normalize(String candidate) {
        if (candidate == null) return SYSTEM;
        String upper = candidate.trim().toUpperCase(java.util.Locale.ROOT);
        return KNOWN.contains(upper) ? upper : SYSTEM;
    }
}

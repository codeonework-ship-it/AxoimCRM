package com.axiom.accounts;

import java.util.Map;
import java.util.Set;

/**
 * Field-level read permissions for the 360 timeline and account payloads
 * (FR-ACC-012: "honouring record and field permissions").
 *
 * <p>Commercially sensitive fields are hidden from roles that have no business
 * reason to read them. A hidden field-change entry is omitted from the timeline
 * entirely — not rendered as a redacted row and not counted — because a gap the
 * user can see is still a disclosure that something happened.
 */
public final class FieldVisibility {

    /** Fields readable only by commercial/administrative roles. */
    private static final Map<String, Set<String>> RESTRICTED_FIELDS = Map.of(
            "annualRevenue", Set.of("SUPER_ADMIN", "SUPER_AUDIT", "TENANT_ADMIN", "SALES_MANAGER",
                    "FINANCE", "OPERATIONS", "AUDITOR"),
            "employeeCount", Set.of("SUPER_ADMIN", "SUPER_AUDIT", "TENANT_ADMIN", "SALES_MANAGER",
                    "FINANCE", "OPERATIONS", "AUDITOR", "SALES", "MARKETING", "SERVICE", "DATA_STEWARD"));

    private FieldVisibility() {}

    public static boolean canRead(String role, String field) {
        Set<String> allowed = RESTRICTED_FIELDS.get(field);
        return allowed == null || allowed.contains(role);
    }
}

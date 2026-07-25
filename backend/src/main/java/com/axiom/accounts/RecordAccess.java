package com.axiom.accounts;

import com.axiom.tenancy.TenantContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The viewer's record-level reach, used by roll-ups (FR-ACC-004) and the 360
 * timeline (FR-ACC-012).
 *
 * <p>E02's materialized {@code RECORD_SHARE} model is not built yet. Until it
 * is, this module applies the honest interim rule: broad-visibility roles see
 * every record in their tenant; an individual-contributor sales role sees the
 * records it owns. What matters for FR-ACC-004 is not which rule is in force but
 * that an aggregate never leaks the existence of a record the viewer cannot
 * read — so {@link Scope#restricted()} is surfaced to the caller and the count
 * of hidden records is deliberately never computed, let alone returned.
 */
public final class RecordAccess {

    private static final Set<String> FULL_VISIBILITY = Set.of(
            "SUPER_ADMIN", "SUPER_AUDIT", "TENANT_ADMIN", "SALES_MANAGER",
            "OPERATIONS", "FINANCE", "AUDITOR", "DATA_STEWARD", "SERVICE", "MARKETING");

    private RecordAccess() {}

    public record Scope(UUID tenantId, UUID userId, String role, boolean full) {

        /** True when the viewer sees only a subset of the tenant's records. */
        public boolean restricted() {
            return !full;
        }

        /**
         * Appends an owner predicate to a query. The pair of bind values is
         * {@code (full, userId)} so a full-visibility viewer short-circuits the
         * comparison instead of the SQL being rewritten per role.
         */
        public String ownerPredicate(String column, List<Object> args) {
            args.add(full);
            args.add(userId);
            return " and (?::boolean or " + column + " = ?)";
        }

        /** The plain-English reason a figure is narrower than the tenant's truth. */
        public String restrictionNote() {
            return "Totals cover only the records your access permits. Records outside your access "
                    + "are excluded and are not counted anywhere on this page.";
        }
    }

    public static Scope current() {
        TenantContext.Principal p = TenantContext.get();
        return new Scope(p.tenantId(), p.userId(), p.role(), FULL_VISIBILITY.contains(p.role()));
    }
}

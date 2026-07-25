package com.axiom.security;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the authorization layer knows about one principal at one moment,
 * resolved in full before any decision is taken.
 *
 * <p>It is a snapshot, not a cache. {@link PermissionResolver} rebuilds it on
 * every call, which is what makes FR-SEC-012 true without a login cycle: an
 * assignment that expires between two requests is gone from the second one. A
 * cached context with a time-to-live would silently reintroduce exactly the
 * "permission survives its expiry until you sign in again" behaviour the
 * requirement exists to forbid.
 */
public record AccessContext(
        UUID tenantId,
        UUID userId,
        String crmRole,
        /** Platform operators (SUPER_ADMIN / SUPER_AUDIT) are cross-tenant by construction. */
        boolean platformOperator,
        boolean readOnlyRole,
        UUID profileId,
        String profileCode,
        /** Null means no export volume ceiling for this profile (FR-SEC-015). */
        Integer exportRowLimit,
        List<UUID> permissionSetIds,
        Set<String> permissionCodes,
        Map<SecurableObject, ObjectAccess> objectAccess,
        /** Field API names this principal may not read, per object. */
        Map<SecurableObject, Set<String>> unreadableFields,
        /** Field API names this principal may read but not write, per object. */
        Map<SecurableObject, Set<String>> readOnlyFields,
        UUID roleNodeId,
        String roleCode,
        /** Materialized role path, {@code /EXEC/SALES_LEADERSHIP/APAC_WEST/}, or null if unassigned. */
        String rolePath,
        List<UUID> groupIds,
        List<UUID> territoryIds) {

    /** Per-object effective permission after union of profile and sets, minus mutes. */
    public record ObjectAccess(boolean canCreate, boolean canRead, boolean canEdit, boolean canDelete,
                               boolean viewAll, boolean modifyAll, boolean canExport, boolean canReveal) {

        public static final ObjectAccess NONE =
                new ObjectAccess(false, false, false, false, false, false, false, false);

        public static final ObjectAccess ALL =
                new ObjectAccess(true, true, true, true, true, true, true, true);

        public static final ObjectAccess READ_ALL =
                new ObjectAccess(false, true, false, false, true, false, true, false);

        public ObjectAccess union(ObjectAccess other) {
            return new ObjectAccess(
                    canCreate || other.canCreate,
                    canRead || other.canRead,
                    canEdit || other.canEdit,
                    canDelete || other.canDelete,
                    viewAll || other.viewAll,
                    modifyAll || other.modifyAll,
                    canExport || other.canExport,
                    canReveal || other.canReveal);
        }
    }

    public ObjectAccess access(SecurableObject object) {
        return objectAccess.getOrDefault(object, ObjectAccess.NONE);
    }

    public Set<String> unreadable(SecurableObject object) {
        return unreadableFields.getOrDefault(object, Set.of());
    }

    public Set<String> readOnly(SecurableObject object) {
        return readOnlyFields.getOrDefault(object, Set.of());
    }

    public boolean holds(String permissionCode) {
        return permissionCodes.contains(permissionCode);
    }
}

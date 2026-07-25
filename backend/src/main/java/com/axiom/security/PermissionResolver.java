package com.axiom.security;

import com.axiom.auth.CrmRole;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Computes <b>effective permission = profile ∪ assigned permission sets −
 * explicit mutes</b> (FR-SEC-003) for one user.
 *
 * <p>Union, never intersection. A permission granted by any assignment is held,
 * unless the permission-set group through which the set was assigned explicitly
 * mutes it. Muting is per membership rather than per set on purpose: the same set
 * can be muted in one bundle and not another, which is the whole reason groups
 * exist.
 *
 * <p><b>Mute vocabulary.</b> A mute entry is either a permission code from
 * {@code security.permission_catalog} ({@code ACCOUNT.DELETE},
 * {@code SYS.MANAGE_USERS}) or a field mute written as
 * {@code FIELD:ACCOUNT:industry}. The two share one array because they share one
 * question — "which of the things this bundle would grant does it not grant here".
 *
 * <p><b>Expiry is in the WHERE clause, not in a sweep.</b> Every assignment read
 * here is filtered by {@code expires_at > now()}. That is what makes FR-SEC-012's
 * "ceases to be effective without requiring a login cycle or administrator
 * action" literally true: the next request after the expiry instant resolves a
 * context without the grant. {@link AccessExpiryService} then tidies the rows and
 * writes the audit trail, but it is not what enforces the expiry — if it never
 * ran, access would still stop on time.
 */
@Service
@Transactional(readOnly = true)
public class PermissionResolver {

    private static final String FIELD_MUTE_PREFIX = "FIELD:";

    private final JdbcTemplate jdbc;

    public PermissionResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Resolve the caller. */
    public AccessContext current() {
        TenantContext.Principal p = TenantContext.get();
        return resolve(p.tenantId(), p.userId(), p.role());
    }

    /** Resolve any user in the caller's tenant — used by the access explainer and the SoD sweep. */
    public AccessContext forUser(UUID userId) {
        UUID tenantId = TenantContext.get().tenantId();
        String role = jdbc.query("select role from identity.app_user where tenant_id = ? and id = ?",
                        rs -> rs.next() ? rs.getString(1) : null, tenantId, userId);
        return resolve(tenantId, userId, role == null ? "SALES" : role);
    }

    public AccessContext resolve(UUID tenantId, UUID userId, String crmRoleCode) {
        CrmRole crmRole = safeRole(crmRoleCode);
        boolean platform = crmRole != null && crmRole.platform();
        boolean readOnly = crmRole != null && crmRole.readOnly();

        // Platform operators hold no tenant profile: they are the operator identity
        // of the platform itself, authorized by CrmRole.requirePlatform at the entry
        // point. Giving them a synthetic tenant profile would mean every tenant's
        // authorization model contained a back door row.
        if (platform) {
            Map<SecurableObject, AccessContext.ObjectAccess> all = new EnumMap<>(SecurableObject.class);
            for (SecurableObject object : SecurableObject.values()) {
                all.put(object, readOnly ? AccessContext.ObjectAccess.READ_ALL : AccessContext.ObjectAccess.ALL);
            }
            return new AccessContext(tenantId, userId, crmRoleCode, true, readOnly,
                    null, "PLATFORM_OPERATOR", null, List.of(),
                    Set.of("SYS.VIEW_ACCESS_EXPLAINER"), all,
                    Map.of(), Map.of(), null, null, null, List.of(), List.of());
        }

        Profile profile = resolveProfile(tenantId, userId, crmRoleCode);
        List<Holder> holders = resolveHolders(tenantId, userId, profile);

        Map<SecurableObject, AccessContext.ObjectAccess> objectAccess = new EnumMap<>(SecurableObject.class);
        Set<String> codes = new LinkedHashSet<>();
        Map<SecurableObject, Map<String, FieldRule>> fieldRules = new EnumMap<>(SecurableObject.class);

        if (!holders.isEmpty()) {
            readObjectPermissions(tenantId, holders, objectAccess, codes);
            readFieldPermissions(tenantId, holders, fieldRules);
            readSystemPermissions(tenantId, holders, codes);
        }

        Map<SecurableObject, Set<String>> unreadable = new EnumMap<>(SecurableObject.class);
        Map<SecurableObject, Set<String>> readOnlyFields = new EnumMap<>(SecurableObject.class);
        fieldRules.forEach((object, rules) -> {
            Set<String> hidden = new LinkedHashSet<>();
            Set<String> locked = new LinkedHashSet<>();
            rules.forEach((field, rule) -> {
                if (!rule.readable && !object.alwaysReadable(field)) hidden.add(field);
                else if (!rule.editable) locked.add(field);
            });
            if (!hidden.isEmpty()) unreadable.put(object, Set.copyOf(hidden));
            if (!locked.isEmpty()) readOnlyFields.put(object, Set.copyOf(locked));
        });

        RolePlacement placement = resolveRole(tenantId, userId);

        return new AccessContext(tenantId, userId, crmRoleCode, false, readOnly,
                profile.id(), profile.code(), profile.exportRowLimit(),
                holders.stream().filter(h -> h.type == HolderType.PERMISSION_SET).map(h -> h.id).toList(),
                Set.copyOf(codes), Map.copyOf(objectAccess),
                Map.copyOf(unreadable), Map.copyOf(readOnlyFields),
                placement.roleNodeId(), placement.roleCode(), placement.rolePath(),
                readIds(tenantId, userId,
                        "select group_id from security.user_group_member where tenant_id = ? and user_id = ?"),
                readIds(tenantId, userId,
                        "select territory_id from security.territory_member where tenant_id = ? and user_id = ?"));
    }

    // ------------------------------------------------------------------ profile

    private record Profile(UUID id, String code, Integer exportRowLimit) {}

    /**
     * Exactly one profile per user (FR-SEC-002). When no explicit assignment
     * exists the profile whose code equals the user's CRM role is used, because
     * the migration seeds one profile per role code. The fallback is deterministic
     * and visible rather than a silent grant of nothing: a user with no profile at
     * all would be indistinguishable from a user who has been deliberately locked
     * out, and support cannot tell those apart from a bug report.
     */
    private Profile resolveProfile(UUID tenantId, UUID userId, String crmRoleCode) {
        Profile assigned = jdbc.query("""
                select p.id, p.code, p.export_row_limit
                from security.user_profile up
                join security.profile p on p.tenant_id = up.tenant_id and p.id = up.profile_id
                where up.tenant_id = ? and up.user_id = ? and p.active = true
                """, rs -> rs.next()
                        ? new Profile(rs.getObject(1, UUID.class), rs.getString(2), (Integer) rs.getObject(3))
                        : null,
                tenantId, userId);
        if (assigned != null) return assigned;

        Profile byRole = jdbc.query("""
                select id, code, export_row_limit from security.profile
                where tenant_id = ? and code = ? and active = true
                """, rs -> rs.next()
                        ? new Profile(rs.getObject(1, UUID.class), rs.getString(2), (Integer) rs.getObject(3))
                        : null,
                tenantId, crmRoleCode);
        return byRole != null ? byRole : new Profile(null, "UNASSIGNED", 0);
    }

    // ------------------------------------------------------------------ holders

    private enum HolderType { PROFILE, PERMISSION_SET }

    private record Holder(HolderType type, UUID id, Set<String> mutes) {}

    private List<Holder> resolveHolders(UUID tenantId, UUID userId, Profile profile) {
        List<Holder> holders = new ArrayList<>();
        if (profile.id() != null) holders.add(new Holder(HolderType.PROFILE, profile.id(), Set.of()));

        // Direct set assignments carry no mutes; assignments made through a group
        // carry that membership's mute list. Both are filtered on expiry.
        jdbc.query("""
                select ups.permission_set_id as set_id, null::text[] as mutes
                from security.user_permission_set ups
                join security.permission_set ps
                  on ps.tenant_id = ups.tenant_id and ps.id = ups.permission_set_id and ps.active = true
                where ups.tenant_id = ? and ups.user_id = ? and ups.revoked_at is null
                  and ups.permission_set_id is not null
                  and (ups.expires_at is null or ups.expires_at > now())
                union all
                select m.permission_set_id as set_id, m.muted_permissions as mutes
                from security.user_permission_set ups
                join security.permission_set_group g
                  on g.tenant_id = ups.tenant_id and g.id = ups.permission_set_group_id and g.active = true
                join security.permission_set_group_member m
                  on m.tenant_id = ups.tenant_id and m.group_id = ups.permission_set_group_id
                join security.permission_set ps
                  on ps.tenant_id = m.tenant_id and ps.id = m.permission_set_id and ps.active = true
                where ups.tenant_id = ? and ups.user_id = ? and ups.revoked_at is null
                  and ups.permission_set_group_id is not null
                  and (ups.expires_at is null or ups.expires_at > now())
                """, rs -> {
                    holders.add(new Holder(HolderType.PERMISSION_SET,
                            rs.getObject("set_id", UUID.class), mutes(rs.getArray("mutes"))));
                }, tenantId, userId, tenantId, userId);
        return holders;
    }

    private Set<String> mutes(Array array) {
        if (array == null) return Set.of();
        try {
            Object value = array.getArray();
            if (value instanceof String[] items) return new HashSet<>(Arrays.asList(items));
            if (value instanceof Object[] items) {
                Set<String> out = new HashSet<>();
                for (Object item : items) if (item != null) out.add(item.toString());
                return out;
            }
            return Set.of();
        } catch (Exception ex) {
            throw new IllegalStateException("Permission-set mute list could not be read", ex);
        }
    }

    // ------------------------------------------------------------- permissions

    private void readObjectPermissions(UUID tenantId, List<Holder> holders,
                                       Map<SecurableObject, AccessContext.ObjectAccess> out,
                                       Collection<String> codes) {
        Map<UUID, Holder> byId = new HashMap<>();
        holders.forEach(h -> byId.put(h.id, h));
        jdbc.query("""
                select holder_id, object_type, can_create, can_read, can_edit, can_delete,
                       view_all, modify_all, can_export, can_reveal
                from security.object_permission
                where tenant_id = ? and holder_id in (%s)
                """.formatted(placeholders(holders.size())),
                rs -> {
                    SecurableObject object;
                    try {
                        object = SecurableObject.valueOf(rs.getString("object_type"));
                    } catch (IllegalArgumentException ex) {
                        return; // an object the running build does not know about yet
                    }
                    Holder holder = byId.get(rs.getObject("holder_id", UUID.class));
                    Set<String> mute = holder == null ? Set.of() : holder.mutes;
                    String prefix = object.name() + ".";
                    AccessContext.ObjectAccess granted = new AccessContext.ObjectAccess(
                            flag(rs, "can_create", mute, prefix + "CREATE"),
                            flag(rs, "can_read", mute, prefix + "READ"),
                            flag(rs, "can_edit", mute, prefix + "EDIT"),
                            flag(rs, "can_delete", mute, prefix + "DELETE"),
                            flag(rs, "view_all", mute, prefix + "VIEW_ALL"),
                            flag(rs, "modify_all", mute, prefix + "MODIFY_ALL"),
                            flag(rs, "can_export", mute, prefix + "EXPORT"),
                            flag(rs, "can_reveal", mute, prefix + "REVEAL"));
                    out.merge(object, granted, AccessContext.ObjectAccess::union);
                },
                args(tenantId, holders));

        // Project the effective object flags back into the shared permission-code
        // space so segregation of duties can compare an object right against a
        // system right without two vocabularies.
        out.forEach((object, access) -> {
            if (access.canCreate()) codes.add(object.name() + ".CREATE");
            if (access.canRead()) codes.add(object.name() + ".READ");
            if (access.canEdit()) codes.add(object.name() + ".EDIT");
            if (access.canDelete()) codes.add(object.name() + ".DELETE");
            if (access.viewAll()) codes.add(object.name() + ".VIEW_ALL");
            if (access.modifyAll()) codes.add(object.name() + ".MODIFY_ALL");
            if (access.canExport()) codes.add(object.name() + ".EXPORT");
            if (access.canReveal()) codes.add(object.name() + ".REVEAL");
        });
    }

    private record FieldRule(boolean readable, boolean editable) {}

    private void readFieldPermissions(UUID tenantId, List<Holder> holders,
                                      Map<SecurableObject, Map<String, FieldRule>> out) {
        Map<UUID, Holder> byId = new HashMap<>();
        holders.forEach(h -> byId.put(h.id, h));
        jdbc.query("""
                select holder_id, object_type, field_name, readable, editable
                from security.field_permission
                where tenant_id = ? and holder_id in (%s)
                """.formatted(placeholders(holders.size())),
                rs -> {
                    SecurableObject object;
                    try {
                        object = SecurableObject.valueOf(rs.getString("object_type"));
                    } catch (IllegalArgumentException ex) {
                        return;
                    }
                    String field = rs.getString("field_name");
                    Holder holder = byId.get(rs.getObject("holder_id", UUID.class));
                    Set<String> mute = holder == null ? Set.of() : holder.mutes;
                    boolean muted = mute.contains(FIELD_MUTE_PREFIX + object.name() + ":" + field);
                    boolean readable = rs.getBoolean("readable") && !muted;
                    boolean editable = rs.getBoolean("editable") && !muted;
                    out.computeIfAbsent(object, k -> new HashMap<>())
                            .merge(field, new FieldRule(readable, editable),
                                    (a, b) -> new FieldRule(a.readable || b.readable, a.editable || b.editable));
                },
                args(tenantId, holders));
    }

    private void readSystemPermissions(UUID tenantId, List<Holder> holders, Collection<String> codes) {
        Map<UUID, Holder> byId = new HashMap<>();
        holders.forEach(h -> byId.put(h.id, h));
        jdbc.query("""
                select holder_id, permission_code from security.holder_permission
                where tenant_id = ? and holder_id in (%s)
                """.formatted(placeholders(holders.size())),
                rs -> {
                    Holder holder = byId.get(rs.getObject("holder_id", UUID.class));
                    String code = rs.getString("permission_code");
                    if (holder == null || !holder.mutes.contains(code)) codes.add(code);
                },
                args(tenantId, holders));
    }

    // ------------------------------------------------------------------ role

    private record RolePlacement(UUID roleNodeId, String roleCode, String rolePath) {}

    private RolePlacement resolveRole(UUID tenantId, UUID userId) {
        RolePlacement placement = jdbc.query("""
                select rn.id, rn.code, rn.path
                from security.user_role_assignment ura
                join security.role_node rn on rn.tenant_id = ura.tenant_id and rn.id = ura.role_node_id
                where ura.tenant_id = ? and ura.user_id = ? and rn.active = true
                  and (ura.expires_at is null or ura.expires_at > now())
                """, rs -> rs.next()
                        ? new RolePlacement(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))
                        : null,
                tenantId, userId);
        return placement != null ? placement : new RolePlacement(null, null, null);
    }

    private List<UUID> readIds(UUID tenantId, UUID userId, String sql) {
        return jdbc.query(sql, (rs, i) -> rs.getObject(1, UUID.class), tenantId, userId);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean flag(java.sql.ResultSet rs, String column, Set<String> mutes, String code)
            throws java.sql.SQLException {
        return rs.getBoolean(column) && !mutes.contains(code);
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(Math.max(count, 1), "?"));
    }

    private static Object[] args(UUID tenantId, List<Holder> holders) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (holders.isEmpty()) args.add(null);
        else holders.forEach(h -> args.add(h.id));
        return args.toArray();
    }

    private static CrmRole safeRole(String code) {
        try {
            return CrmRole.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }
}

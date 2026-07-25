package com.axiom.security;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Administration of profiles, permission sets, permission-set groups and their
 * assignment to users (FR-SEC-002, FR-SEC-003), plus org-wide defaults
 * (FR-SEC-004) and field permissions (FR-SEC-007).
 *
 * <p>Every write here passes through four gates, in this order, and the order
 * matters:
 * <ol>
 *   <li>the administrative permission for the operation,</li>
 *   <li>delegated-admin scope and self-escalation (FR-SEC-011) — a delegate must be
 *       stopped before their input is evaluated at all,</li>
 *   <li>segregation of duties (FR-SEC-009) — the grant is blocked naming the
 *       conflict,</li>
 *   <li>the write, then the audit event and the access-grant-log entry, both with
 *       before and after state.</li>
 * </ol>
 *
 * <p>Assignments are never deleted. {@code revoked_at} is set instead, because an
 * access review or an incident investigation needs to see that a grant existed and
 * when it stopped — a deleted row answers neither question.
 */
@Service
public class PermissionAdminService {

    // ------------------------------------------------------------------ DTOs

    public record ProfileRow(UUID id, String code, String name, String description,
                             boolean systemManaged, Integer exportRowLimit, boolean active,
                             int userCount) {}

    public record PermissionSetRow(UUID id, String code, String name, String description,
                                   boolean active, int assignmentCount) {}

    public record PermissionSetGroupRow(UUID id, String code, String name, String description,
                                        boolean active, List<GroupMember> members) {}

    public record GroupMember(UUID permissionSetId, String permissionSetCode, List<String> mutedPermissions) {}

    public record ObjectPermissionRow(String holderType, UUID holderId, String objectType,
                                      boolean canCreate, boolean canRead, boolean canEdit, boolean canDelete,
                                      boolean viewAll, boolean modifyAll, boolean canExport, boolean canReveal) {}

    public record FieldPermissionRow(String holderType, UUID holderId, String objectType,
                                     String fieldName, boolean readable, boolean editable) {}

    public record AssignmentRow(UUID id, UUID userId, String userEmail, UUID permissionSetId,
                                String permissionSetCode, UUID groupId, String groupCode,
                                Instant expiresAt, Instant grantedAt, String grantedByEmail,
                                Instant revokedAt) {}

    public record PermissionSetRequest(@NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$") String code,
                                       @NotBlank String name, String description) {}

    public record AssignRequest(@NotNull UUID userId, UUID permissionSetId, UUID permissionSetGroupId,
                               Instant expiresAt, String reason) {}

    public record ObjectPermissionRequest(@NotBlank String holderType, @NotNull UUID holderId,
                                          @NotBlank String objectType,
                                          boolean canCreate, boolean canRead, boolean canEdit,
                                          boolean canDelete, boolean viewAll, boolean modifyAll,
                                          boolean canExport, boolean canReveal) {}

    public record FieldPermissionRequest(@NotBlank String holderType, @NotNull UUID holderId,
                                         @NotBlank String objectType, @NotBlank String fieldName,
                                         boolean readable, boolean editable) {}

    public record OrgWideDefaultRequest(@NotBlank String objectType, @NotBlank String defaultAccess,
                                        boolean roleHierarchyRollup) {}

    public record OrgWideDefaultRow(String objectType, String defaultAccess,
                                    boolean roleHierarchyRollup, Instant updatedAt) {}

    public record EffectivePermissionView(UUID userId, String userEmail, String profileCode,
                                          List<String> permissionSets, String roleCode, String rolePath,
                                          List<String> permissionCodes,
                                          Map<String, AccessContext.ObjectAccess> objectAccess,
                                          Map<String, List<String>> unreadableFields,
                                          Integer exportRowLimit) {}

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final PermissionResolver resolver;
    private final SodService sod;
    private final DelegatedAdminService delegatedAdmin;
    private final AuditService audit;
    private final AccessGrantLog grantLog;

    public PermissionAdminService(JdbcTemplate jdbc, AuthorizationService authorization,
                                 PermissionResolver resolver, SodService sod,
                                 DelegatedAdminService delegatedAdmin, AuditService audit,
                                 AccessGrantLog grantLog) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.resolver = resolver;
        this.sod = sod;
        this.delegatedAdmin = delegatedAdmin;
        this.audit = audit;
        this.grantLog = grantLog;
    }

    // ------------------------------------------------------------------ catalogue

    public record PermissionDescriptor(String code, String category, String label, String description) {}

    @Transactional(readOnly = true)
    public List<PermissionDescriptor> permissionCatalogue() {
        return jdbc.query("""
                select code, category, label, description from security.permission_catalog
                order by category, code
                """, (rs, i) -> new PermissionDescriptor(rs.getString("code"), rs.getString("category"),
                        rs.getString("label"), rs.getString("description")));
    }

    @Transactional(readOnly = true)
    public List<String> securableObjects() {
        return java.util.Arrays.stream(SecurableObject.values()).map(Enum::name).toList();
    }

    @Transactional(readOnly = true)
    public List<String> fieldsOf(String objectType) {
        return List.copyOf(SecurableObject.of(objectType).fieldNames());
    }

    // ------------------------------------------------------------------ profiles

    @Transactional(readOnly = true)
    public List<ProfileRow> profiles() {
        return jdbc.query("""
                select p.id, p.code, p.name, p.description, p.system_managed, p.export_row_limit, p.active,
                       (select count(*) from security.user_profile up
                        where up.tenant_id = p.tenant_id and up.profile_id = p.id) as user_count
                from security.profile p where p.tenant_id = ? order by p.code
                """, (rs, i) -> new ProfileRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("description"), rs.getBoolean("system_managed"),
                        (Integer) rs.getObject("export_row_limit"), rs.getBoolean("active"),
                        rs.getInt("user_count")),
                TenantContext.get().tenantId());
    }

    /** Exactly one profile per user (FR-SEC-002), so this replaces rather than adds. */
    @Transactional
    public void assignProfile(UUID userId, UUID profileId, String reason) {
        authorization.requirePermission("SYS.MANAGE_PROFILES", "change a user's profile");
        UUID tenantId = TenantContext.get().tenantId();
        ProfileRow target = profiles().stream().filter(p -> p.id().equals(profileId)).findFirst()
                .orElseThrow(() -> new NotFoundException("No profile with that id in this tenant"));

        delegatedAdmin.assertCanAdministerUser(userId);
        Set<String> incoming = codesOfHolder("PROFILE", profileId);
        delegatedAdmin.assertNoSelfEscalation(userId, incoming);
        // A profile change is a grant by another name — FR-SEC-009's second evaluation point.
        sod.assertGrantIsPermitted(userId, incoming);

        Map<String, Object> before = currentProfile(tenantId, userId);
        jdbc.update("""
                insert into security.user_profile (tenant_id, user_id, profile_id, assigned_by)
                values (?, ?, ?, ?)
                on conflict (tenant_id, user_id) do update
                set profile_id = excluded.profile_id, assigned_by = excluded.assigned_by, assigned_at = now()
                """, tenantId, userId, profileId, TenantContext.get().userId());

        Map<String, Object> after = Map.of("profile", target.code());
        grantLog.write(userId, "PROFILE", profileId, AccessGrantLog.MODIFY, before, after,
                null, null, reason);
        audit.recordWithReason("PROFILE_ASSIGNED", "APP_USER", userId,
                "Profile changed to " + target.code(), reason,
                Map.of("before", before, "after", after));
    }

    private Map<String, Object> currentProfile(UUID tenantId, UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select p.code from security.user_profile up
                join security.profile p on p.tenant_id = up.tenant_id and p.id = up.profile_id
                where up.tenant_id = ? and up.user_id = ?
                """, tenantId, userId);
        return rows.isEmpty() ? Map.of("profile", "")
                : Map.of("profile", String.valueOf(rows.getFirst().get("code")));
    }

    // ------------------------------------------------------------------ permission sets

    @Transactional(readOnly = true)
    public List<PermissionSetRow> permissionSets() {
        return jdbc.query("""
                select ps.id, ps.code, ps.name, ps.description, ps.active,
                       (select count(*) from security.user_permission_set ups
                        where ups.tenant_id = ps.tenant_id and ups.permission_set_id = ps.id
                          and ups.revoked_at is null) as assignment_count
                from security.permission_set ps where ps.tenant_id = ? order by ps.code
                """, (rs, i) -> new PermissionSetRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("description"), rs.getBoolean("active"),
                        rs.getInt("assignment_count")),
                TenantContext.get().tenantId());
    }

    @Transactional
    public PermissionSetRow createPermissionSet(PermissionSetRequest request) {
        authorization.requirePermission("SYS.MANAGE_PROFILES", "create a permission set");
        UUID tenantId = TenantContext.get().tenantId();
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (permissionSets().stream().anyMatch(ps -> ps.code().equals(code))) {
            throw new ConflictException("A permission set with code " + code + " already exists.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into security.permission_set (id, tenant_id, code, name, description, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, code, request.name(), request.description(),
                TenantContext.get().userId(), TenantContext.get().userId());
        audit.record("PERMISSION_SET_CREATED", "PERMISSION_SET", id, "Created permission set " + code,
                Map.of("code", code, "name", request.name()));
        return permissionSets().stream().filter(ps -> ps.id().equals(id)).findFirst().orElseThrow();
    }

    /** Add or replace a system permission on a holder. Also the SoD "on grant" point. */
    @Transactional
    public void setHolderPermissions(String holderType, UUID holderId, List<String> codes) {
        authorization.requirePermission("SYS.MANAGE_PROFILES", "change the permissions of a profile or set");
        UUID tenantId = TenantContext.get().tenantId();
        String type = holderType.toUpperCase(Locale.ROOT);
        Set<String> requested = new LinkedHashSet<>();
        codes.forEach(c -> requested.add(c.trim().toUpperCase(Locale.ROOT)));

        Set<String> before = codesOfHolder(type, holderId);
        Set<String> added = new LinkedHashSet<>(requested);
        added.removeAll(before);

        // Anyone already holding this profile/set would receive `added` — check each of
        // them before the write, not after, so the conflict is prevented rather than
        // reported.
        if (!added.isEmpty()) {
            for (UUID userId : holdersOf(type, holderId)) {
                delegatedAdmin.assertNoSelfEscalation(userId, added);
                sod.assertGrantIsPermitted(userId, added);
            }
        }

        jdbc.update("delete from security.holder_permission where tenant_id = ? and holder_type = ? and holder_id = ?",
                tenantId, type, holderId);
        for (String code : requested) {
            jdbc.update("""
                    insert into security.holder_permission (tenant_id, holder_type, holder_id, permission_code)
                    values (?, ?, ?, ?) on conflict do nothing
                    """, tenantId, type, holderId, code);
        }

        audit.record("HOLDER_PERMISSIONS_CHANGED", type, holderId,
                "Changed system permissions on a " + type.toLowerCase(Locale.ROOT).replace('_', ' '),
                Map.of("before", new ArrayList<>(before), "after", new ArrayList<>(requested)));
        grantLog.write(null, type, holderId, AccessGrantLog.MODIFY,
                Map.of("permissions", new ArrayList<>(before)),
                Map.of("permissions", new ArrayList<>(requested)), null, null,
                "System permissions changed");
    }

    /** Every permission code a holder conveys, object-derived codes included. */
    @Transactional(readOnly = true)
    public Set<String> codesOfHolder(String holderType, UUID holderId) {
        UUID tenantId = TenantContext.get().tenantId();
        Set<String> codes = new LinkedHashSet<>(jdbc.query("""
                select permission_code from security.holder_permission
                where tenant_id = ? and holder_type = ? and holder_id = ?
                """, (rs, i) -> rs.getString(1), tenantId, holderType, holderId));
        jdbc.query("""
                select object_type, can_create, can_read, can_edit, can_delete,
                       view_all, modify_all, can_export, can_reveal
                from security.object_permission
                where tenant_id = ? and holder_type = ? and holder_id = ?
                """, rs -> {
                    String object = rs.getString("object_type");
                    if (rs.getBoolean("can_create")) codes.add(object + ".CREATE");
                    if (rs.getBoolean("can_read")) codes.add(object + ".READ");
                    if (rs.getBoolean("can_edit")) codes.add(object + ".EDIT");
                    if (rs.getBoolean("can_delete")) codes.add(object + ".DELETE");
                    if (rs.getBoolean("view_all")) codes.add(object + ".VIEW_ALL");
                    if (rs.getBoolean("modify_all")) codes.add(object + ".MODIFY_ALL");
                    if (rs.getBoolean("can_export")) codes.add(object + ".EXPORT");
                    if (rs.getBoolean("can_reveal")) codes.add(object + ".REVEAL");
                }, tenantId, holderType, holderId);
        return codes;
    }

    private List<UUID> holdersOf(String holderType, UUID holderId) {
        UUID tenantId = TenantContext.get().tenantId();
        if ("PROFILE".equals(holderType)) {
            return jdbc.query("select user_id from security.user_profile where tenant_id = ? and profile_id = ?",
                    (rs, i) -> rs.getObject(1, UUID.class), tenantId, holderId);
        }
        return jdbc.query("""
                select distinct ups.user_id
                from security.user_permission_set ups
                left join security.permission_set_group_member m
                       on m.tenant_id = ups.tenant_id and m.group_id = ups.permission_set_group_id
                where ups.tenant_id = ? and ups.revoked_at is null
                  and (ups.expires_at is null or ups.expires_at > now())
                  and (ups.permission_set_id = ? or m.permission_set_id = ?)
                """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, holderId, holderId);
    }

    // ------------------------------------------------------------------ groups and mutes

    @Transactional(readOnly = true)
    public List<PermissionSetGroupRow> permissionSetGroups() {
        UUID tenantId = TenantContext.get().tenantId();
        Map<UUID, List<GroupMember>> members = new LinkedHashMap<>();
        jdbc.query("""
                select m.group_id, m.permission_set_id, ps.code, m.muted_permissions
                from security.permission_set_group_member m
                join security.permission_set ps on ps.tenant_id = m.tenant_id and ps.id = m.permission_set_id
                where m.tenant_id = ?
                """, rs -> {
                    List<String> muted = new ArrayList<>();
                    java.sql.Array array = rs.getArray("muted_permissions");
                    if (array != null) {
                        Object value = array.getArray();
                        if (value instanceof Object[] items) {
                            for (Object item : items) if (item != null) muted.add(item.toString());
                        }
                    }
                    members.computeIfAbsent(rs.getObject("group_id", UUID.class), k -> new ArrayList<>())
                            .add(new GroupMember(rs.getObject("permission_set_id", UUID.class),
                                    rs.getString("code"), muted));
                }, tenantId);

        return jdbc.query("""
                select id, code, name, description, active from security.permission_set_group
                where tenant_id = ? order by code
                """, (rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new PermissionSetGroupRow(id, rs.getString("code"), rs.getString("name"),
                            rs.getString("description"), rs.getBoolean("active"),
                            members.getOrDefault(id, List.of()));
                }, tenantId);
    }

    @Transactional
    public PermissionSetGroupRow createGroup(PermissionSetRequest request) {
        authorization.requirePermission("SYS.MANAGE_PROFILES", "create a permission set group");
        UUID id = UUID.randomUUID();
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        jdbc.update("""
                insert into security.permission_set_group (id, tenant_id, code, name, description, created_by)
                values (?, ?, ?, ?, ?, ?)
                """, id, TenantContext.get().tenantId(), code, request.name(), request.description(),
                TenantContext.get().userId());
        audit.record("PERMISSION_SET_GROUP_CREATED", "PERMISSION_SET_GROUP", id,
                "Created permission set group " + code, Map.of("code", code));
        return permissionSetGroups().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Put a set in a group, optionally muting some of what it would grant.
     *
     * <p>The mute list is the mechanism FR-SEC-003 asks for. It is stored on the
     * membership rather than the set so the same set can be muted in one bundle and
     * whole in another — the only reason to have groups at all.
     */
    @Transactional
    public void setGroupMember(UUID groupId, UUID permissionSetId, List<String> mutedPermissions) {
        authorization.requirePermission("SYS.MANAGE_PROFILES", "change a permission set group");
        List<String> muted = mutedPermissions == null ? List.of()
                : mutedPermissions.stream().map(m -> m.trim().toUpperCase(Locale.ROOT)).filter(m -> !m.isEmpty()).toList();
        jdbc.update("""
                insert into security.permission_set_group_member
                  (tenant_id, group_id, permission_set_id, muted_permissions)
                values (?, ?, ?, ?)
                on conflict (tenant_id, group_id, permission_set_id) do update
                set muted_permissions = excluded.muted_permissions
                """, TenantContext.get().tenantId(), groupId, permissionSetId,
                muted.toArray(String[]::new));
        audit.record("PERMISSION_SET_GROUP_MEMBER_SET", "PERMISSION_SET_GROUP", groupId,
                "Set a permission set in a group",
                Map.of("permissionSetId", permissionSetId.toString(), "mutedPermissions", muted));
    }

    // ------------------------------------------------------------------ assignments

    @Transactional(readOnly = true)
    public List<AssignmentRow> assignments(UUID userId) {
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        String filter = "";
        if (userId != null) {
            filter = " and ups.user_id = ?";
            args.add(userId);
        }
        return jdbc.query("""
                select ups.id, ups.user_id, u.email, ups.permission_set_id, ps.code as set_code,
                       ups.permission_set_group_id, g.code as group_code, ups.expires_at, ups.granted_at,
                       gb.email as granted_by_email, ups.revoked_at
                from security.user_permission_set ups
                join identity.app_user u on u.tenant_id = ups.tenant_id and u.id = ups.user_id
                left join security.permission_set ps on ps.tenant_id = ups.tenant_id and ps.id = ups.permission_set_id
                left join security.permission_set_group g on g.tenant_id = ups.tenant_id and g.id = ups.permission_set_group_id
                left join identity.app_user gb on gb.tenant_id = ups.tenant_id and gb.id = ups.granted_by
                where ups.tenant_id = ?""" + filter + """

                order by ups.granted_at desc
                """, (rs, i) -> new AssignmentRow(rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("email"),
                        rs.getObject("permission_set_id", UUID.class), rs.getString("set_code"),
                        rs.getObject("permission_set_group_id", UUID.class), rs.getString("group_code"),
                        ts(rs, "expires_at"), ts(rs, "granted_at"), rs.getString("granted_by_email"),
                        ts(rs, "revoked_at")),
                args.toArray());
    }

    private static Instant ts(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    @Transactional
    public AssignmentRow assign(AssignRequest request) {
        authorization.requirePermission("SYS.MANAGE_PROFILES", "assign a permission set");
        UUID tenantId = TenantContext.get().tenantId();
        if ((request.permissionSetId() == null) == (request.permissionSetGroupId() == null)) {
            throw new ConflictException("Assign exactly one of a permission set or a permission set group.");
        }
        delegatedAdmin.assertCanAdministerUser(request.userId());

        Set<String> incoming = request.permissionSetId() != null
                ? codesOfHolder("PERMISSION_SET", request.permissionSetId())
                : codesOfGroup(request.permissionSetGroupId());

        delegatedAdmin.assertNoSelfEscalation(request.userId(), incoming);
        sod.assertGrantIsPermitted(request.userId(), incoming);

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into security.user_permission_set
                  (id, tenant_id, user_id, permission_set_id, permission_set_group_id,
                   expires_at, granted_by, granted_reason)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, request.userId(), request.permissionSetId(),
                request.permissionSetGroupId(),
                request.expiresAt() == null ? null : java.sql.Timestamp.from(request.expiresAt()),
                TenantContext.get().userId(), request.reason());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("permissions", new ArrayList<>(incoming));
        after.put("expiresAt", request.expiresAt() == null ? "never" : request.expiresAt().toString());
        grantLog.write(request.userId(), request.permissionSetId() != null ? "PERMISSION_SET" : "PERMISSION_SET_GROUP",
                request.permissionSetId() != null ? request.permissionSetId() : request.permissionSetGroupId(),
                AccessGrantLog.GRANT, null, after, null, null, request.reason());
        audit.recordWithReason("PERMISSION_SET_ASSIGNED", "APP_USER", request.userId(),
                "Assigned " + incoming.size() + " permission(s)", request.reason(),
                Map.of("before", Map.of(), "after", after,
                        "approver", TenantContext.get().email()));
        return assignments(request.userId()).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    private Set<String> codesOfGroup(UUID groupId) {
        Set<String> codes = new LinkedHashSet<>();
        permissionSetGroups().stream().filter(g -> g.id().equals(groupId)).findFirst()
                .ifPresent(group -> group.members().forEach(member -> {
                    Set<String> setCodes = codesOfHolder("PERMISSION_SET", member.permissionSetId());
                    setCodes.removeAll(new LinkedHashSet<>(member.mutedPermissions()));
                    codes.addAll(setCodes);
                }));
        return codes;
    }

    /**
     * Revocation takes effect on the next request — the resolver reads
     * {@code revoked_at is null}, so no login cycle is needed (US-E02-02).
     */
    @Transactional
    public void revoke(UUID assignmentId, String reason) {
        authorization.requirePermission("SYS.MANAGE_PROFILES", "revoke a permission set");
        UUID tenantId = TenantContext.get().tenantId();
        List<AssignmentRow> existing = assignments(null).stream()
                .filter(a -> a.id().equals(assignmentId)).toList();
        if (existing.isEmpty()) throw new NotFoundException("No permission set assignment with that id");
        AssignmentRow row = existing.getFirst();
        delegatedAdmin.assertCanAdministerUser(row.userId());

        jdbc.update("""
                update security.user_permission_set
                set revoked_at = now(), revoked_by = ?, revoked_reason = ?
                where tenant_id = ? and id = ? and revoked_at is null
                """, TenantContext.get().userId(), reason, tenantId, assignmentId);

        grantLog.write(row.userId(),
                row.permissionSetId() != null ? "PERMISSION_SET" : "PERMISSION_SET_GROUP",
                row.permissionSetId() != null ? row.permissionSetId() : row.groupId(),
                AccessGrantLog.REVOKE,
                Map.of("assignment", row.permissionSetCode() == null ? row.groupCode() : row.permissionSetCode()),
                null, null, null, reason);
        audit.recordWithReason("PERMISSION_SET_REVOKED", "APP_USER", row.userId(),
                "Revoked " + (row.permissionSetCode() == null ? row.groupCode() : row.permissionSetCode()),
                reason, Map.of("assignmentId", assignmentId.toString()));
    }

    // ------------------------------------------------------------------ object / field permissions

    @Transactional(readOnly = true)
    public List<ObjectPermissionRow> objectPermissions(String holderType, UUID holderId) {
        return jdbc.query("""
                select holder_type, holder_id, object_type, can_create, can_read, can_edit, can_delete,
                       view_all, modify_all, can_export, can_reveal
                from security.object_permission
                where tenant_id = ? and (? is null or holder_type = ?) and (?::uuid is null or holder_id = ?::uuid)
                order by object_type
                """, (rs, i) -> new ObjectPermissionRow(rs.getString("holder_type"),
                        rs.getObject("holder_id", UUID.class), rs.getString("object_type"),
                        rs.getBoolean("can_create"), rs.getBoolean("can_read"), rs.getBoolean("can_edit"),
                        rs.getBoolean("can_delete"), rs.getBoolean("view_all"), rs.getBoolean("modify_all"),
                        rs.getBoolean("can_export"), rs.getBoolean("can_reveal")),
                TenantContext.get().tenantId(), holderType, holderType,
                holderId == null ? null : holderId.toString(), holderId == null ? null : holderId.toString());
    }

    @Transactional
    public void setObjectPermission(ObjectPermissionRequest request) {
        authorization.requirePermission("SYS.MANAGE_PROFILES", "change object permissions");
        SecurableObject object = SecurableObject.of(request.objectType());
        String type = request.holderType().toUpperCase(Locale.ROOT);
        UUID tenantId = TenantContext.get().tenantId();

        Set<String> before = codesOfHolder(type, request.holderId());
        jdbc.update("""
                insert into security.object_permission
                  (tenant_id, holder_type, holder_id, object_type, can_create, can_read, can_edit,
                   can_delete, view_all, modify_all, can_export, can_reveal)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, holder_type, holder_id, object_type) do update
                set can_create = excluded.can_create, can_read = excluded.can_read,
                    can_edit = excluded.can_edit, can_delete = excluded.can_delete,
                    view_all = excluded.view_all, modify_all = excluded.modify_all,
                    can_export = excluded.can_export, can_reveal = excluded.can_reveal,
                    updated_at = now()
                """, tenantId, type, request.holderId(), object.name(),
                request.canCreate(), request.canRead(), request.canEdit(), request.canDelete(),
                request.viewAll(), request.modifyAll(), request.canExport(), request.canReveal());
        Set<String> after = codesOfHolder(type, request.holderId());

        Set<String> added = new LinkedHashSet<>(after);
        added.removeAll(before);
        if (!added.isEmpty()) {
            for (UUID userId : holdersOf(type, request.holderId())) {
                sod.assertGrantIsPermitted(userId, added);
            }
        }

        audit.record("OBJECT_PERMISSION_CHANGED", type, request.holderId(),
                "Changed " + object.name() + " permissions",
                Map.of("objectType", object.name(), "before", new ArrayList<>(before),
                        "after", new ArrayList<>(after)));
        grantLog.write(null, type, request.holderId(), AccessGrantLog.MODIFY,
                Map.of("permissions", new ArrayList<>(before)),
                Map.of("permissions", new ArrayList<>(after)), null, null,
                "Object permissions changed on " + object.name());
    }

    @Transactional(readOnly = true)
    public List<FieldPermissionRow> fieldPermissions(String holderType, UUID holderId) {
        return jdbc.query("""
                select holder_type, holder_id, object_type, field_name, readable, editable
                from security.field_permission
                where tenant_id = ? and (? is null or holder_type = ?) and (?::uuid is null or holder_id = ?::uuid)
                order by object_type, field_name
                """, (rs, i) -> new FieldPermissionRow(rs.getString("holder_type"),
                        rs.getObject("holder_id", UUID.class), rs.getString("object_type"),
                        rs.getString("field_name"), rs.getBoolean("readable"), rs.getBoolean("editable")),
                TenantContext.get().tenantId(), holderType, holderType,
                holderId == null ? null : holderId.toString(), holderId == null ? null : holderId.toString());
    }

    @Transactional
    public void setFieldPermission(FieldPermissionRequest request) {
        authorization.requirePermission("SYS.MANAGE_PROFILES", "change field permissions");
        SecurableObject object = SecurableObject.of(request.objectType());
        if (!object.hasColumn(request.fieldName())) {
            throw new NotFoundException("'" + request.fieldName() + "' is not a field of " + object.name()
                    + ". Known fields: " + String.join(", ", object.fieldNames()));
        }
        if (object.alwaysReadable(request.fieldName()) && !request.readable()) {
            throw new ConflictException("The " + request.fieldName() + " field cannot be hidden — a record "
                    + "without its identifier cannot be referenced, explained or requested access to.");
        }
        jdbc.update("""
                insert into security.field_permission
                  (tenant_id, holder_type, holder_id, object_type, field_name, readable, editable)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, holder_type, holder_id, object_type, field_name) do update
                set readable = excluded.readable, editable = excluded.editable, updated_at = now()
                """, TenantContext.get().tenantId(), request.holderType().toUpperCase(Locale.ROOT),
                request.holderId(), object.name(), request.fieldName(),
                request.readable(), request.editable());
        audit.record("FIELD_PERMISSION_CHANGED", request.holderType().toUpperCase(Locale.ROOT),
                request.holderId(), "Changed field security on " + object.name() + "." + request.fieldName(),
                Map.of("objectType", object.name(), "field", request.fieldName(),
                        "readable", request.readable(), "editable", request.editable()));
    }

    // ------------------------------------------------------------------ org-wide defaults

    @Transactional(readOnly = true)
    public List<OrgWideDefaultRow> orgWideDefaults() {
        return jdbc.query("""
                select object_type, default_access, role_hierarchy_rollup, updated_at
                from security.org_wide_default where tenant_id = ? order by object_type
                """, (rs, i) -> new OrgWideDefaultRow(rs.getString("object_type"),
                        rs.getString("default_access"), rs.getBoolean("role_hierarchy_rollup"),
                        rs.getTimestamp("updated_at").toInstant()),
                TenantContext.get().tenantId());
    }

    /**
     * The only way to <em>narrow</em> access (FR-SEC-004). Every other mechanism in
     * the model adds; lowering this default is the single lever that takes access
     * away, which is why the change is audited with before and after.
     */
    @Transactional
    public void setOrgWideDefault(OrgWideDefaultRequest request) {
        authorization.requirePermission("SYS.MANAGE_SHARING", "change an org-wide default");
        SecurableObject object = SecurableObject.of(request.objectType());
        String access = request.defaultAccess().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("private", "read_only", "read_write").contains(access)) {
            throw new ConflictException("Org-wide default must be private, read_only or read_write.");
        }
        AuthorizationService.OrgWideDefault before =
                authorization.orgWideDefault(TenantContext.get().tenantId(), object);
        jdbc.update("""
                insert into security.org_wide_default
                  (tenant_id, object_type, default_access, role_hierarchy_rollup, updated_by)
                values (?, ?, ?, ?, ?)
                on conflict (tenant_id, object_type) do update
                set default_access = excluded.default_access,
                    role_hierarchy_rollup = excluded.role_hierarchy_rollup,
                    updated_at = now(), updated_by = excluded.updated_by
                """, TenantContext.get().tenantId(), object.name(), access,
                request.roleHierarchyRollup(), TenantContext.get().userId());
        audit.record("ORG_WIDE_DEFAULT_CHANGED", object.name(), null,
                "Org-wide default for " + object.name() + " set to " + access,
                Map.of("before", Map.of("defaultAccess", before.defaultAccess(),
                                "roleHierarchyRollup", before.roleHierarchyRollup()),
                        "after", Map.of("defaultAccess", access,
                                "roleHierarchyRollup", request.roleHierarchyRollup())));
    }

    // ------------------------------------------------------------------ effective view

    /** What a given user effectively holds — the screen an administrator actually needs. */
    @Transactional(readOnly = true)
    public EffectivePermissionView effectivePermissions(UUID userId) {
        authorization.requirePermission("SYS.VIEW_ACCESS_EXPLAINER", "inspect another user's permissions");
        AccessContext ctx = resolver.forUser(userId);
        String email = jdbc.query("select email from identity.app_user where tenant_id = ? and id = ?",
                rs -> rs.next() ? rs.getString(1) : null, ctx.tenantId(), userId);
        List<String> setCodes = ctx.permissionSetIds().isEmpty() ? List.of()
                : jdbc.query("select code from security.permission_set where tenant_id = ? and id in ("
                        + String.join(",", java.util.Collections.nCopies(ctx.permissionSetIds().size(), "?"))
                        + ") order by code",
                (rs, i) -> rs.getString(1), argsWith(ctx.tenantId(), ctx.permissionSetIds()));

        Map<String, AccessContext.ObjectAccess> objectAccess = new LinkedHashMap<>();
        ctx.objectAccess().forEach((object, access) -> objectAccess.put(object.name(), access));
        Map<String, List<String>> unreadable = new LinkedHashMap<>();
        ctx.unreadableFields().forEach((object, fields) -> unreadable.put(object.name(), new ArrayList<>(fields)));

        return new EffectivePermissionView(userId, email, ctx.profileCode(), setCodes,
                ctx.roleCode(), ctx.rolePath(), new ArrayList<>(ctx.permissionCodes()),
                objectAccess, unreadable, ctx.exportRowLimit());
    }

    private static Object[] argsWith(UUID tenantId, List<UUID> ids) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(ids);
        return args.toArray();
    }
}

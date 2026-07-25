package com.axiom.security;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The role hierarchy (FR-SEC-001): acyclic, unbounded depth, traversed by
 * materialized path.
 *
 * <h2>Cycle rejection</h2>
 * Rejected here <em>and</em> by a trigger in V13. The service check exists to
 * produce the message the requirement asks for — one that names the conflicting
 * roles by their codes, so an administrator knows which reporting line to change.
 * The trigger exists because FR-SEC-001 requires the constraint "regardless of
 * entry point (UI, API, bulk import)", and a direct SQL load does not pass through
 * this class. Duplication is the point: the service gives the good message, the
 * trigger gives the guarantee.
 *
 * <h2>Why a materialized path rather than a recursive query</h2>
 * Data model §8 requires hierarchy traversal by materialized path, not recursion at
 * read time. The role path appears in the {@code WHERE} clause of every list query
 * a user with subordinates runs; a recursive CTE there would put the hierarchy walk
 * on the hottest path in the product. The cost is that a re-parent has to rewrite
 * the paths of the whole subtree, which happens rarely and is done in one UPDATE.
 *
 * <p>Depth is not limited. FR-SEC-001 says so explicitly, and the trigger's 512-hop
 * ceiling is a runaway guard on malformed ancestry, not a modelling limit.
 */
@Service
public class RoleHierarchyService {

    public record RoleNode(UUID id, String code, String name, String description,
                           UUID parentId, String parentCode, String path, int depth,
                           boolean active, int memberCount) {}

    public record RoleRequest(@NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$",
                                      message = "must be a code such as APAC_WEST") String code,
                              @NotBlank String name, String description, UUID parentId) {}

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final DelegatedAdminService delegatedAdmin;
    private final AccessGrantLog grantLog;

    public RoleHierarchyService(JdbcTemplate jdbc, AuthorizationService authorization, AuditService audit,
                                DelegatedAdminService delegatedAdmin, AccessGrantLog grantLog) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.delegatedAdmin = delegatedAdmin;
        this.grantLog = grantLog;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<RoleNode> hierarchy() {
        return jdbc.query("""
                select r.id, r.code, r.name, r.description, r.parent_id, p.code as parent_code,
                       r.path, r.depth, r.active,
                       (select count(*) from security.user_role_assignment ura
                        where ura.tenant_id = r.tenant_id and ura.role_node_id = r.id) as member_count
                from security.role_node r
                left join security.role_node p on p.tenant_id = r.tenant_id and p.id = r.parent_id
                where r.tenant_id = ?
                order by r.path
                """, (rs, i) -> new RoleNode(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("description"),
                        rs.getObject("parent_id", UUID.class), rs.getString("parent_code"),
                        rs.getString("path"), rs.getInt("depth"), rs.getBoolean("active"),
                        rs.getInt("member_count")),
                TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public RoleNode find(UUID id) {
        return hierarchy().stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("No role with that id in this tenant"));
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public RoleNode create(RoleRequest request) {
        authorization.requirePermission("SYS.MANAGE_ROLES", "create a role");
        UUID tenantId = TenantContext.get().tenantId();
        String code = request.code().trim().toUpperCase(Locale.ROOT);

        Map<UUID, RoleNode> byId = index();
        if (byId.values().stream().anyMatch(r -> r.code().equals(code))) {
            throw new ConflictException("A role with code " + code + " already exists in this tenant. "
                    + "Pick a different code or rename the existing role.");
        }
        RoleNode parent = request.parentId() == null ? null : require(byId, request.parentId());
        if (parent != null) delegatedAdmin.assertWithinScope(parent.path(), "create a role under " + parent.code());

        String path = (parent == null ? "/" : parent.path()) + code + "/";
        int depth = parent == null ? 0 : parent.depth() + 1;
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into security.role_node
                  (id, tenant_id, code, name, description, parent_id, path, depth, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, code, request.name(), request.description(),
                request.parentId(), path, depth,
                TenantContext.get().userId(), TenantContext.get().userId());

        audit.record("ROLE_CREATED", "ROLE_NODE", id, "Created role " + code,
                Map.of("code", code, "parent", parent == null ? "" : parent.code(), "path", path));
        return find(id);
    }

    /**
     * Re-parent or rename. The cycle check runs before the write and names both
     * roles, per the FR-SEC-001 on-failure clause.
     */
    @Transactional
    public RoleNode update(UUID id, RoleRequest request) {
        authorization.requirePermission("SYS.MANAGE_ROLES", "change a role");
        UUID tenantId = TenantContext.get().tenantId();
        Map<UUID, RoleNode> byId = index();
        RoleNode existing = require(byId, id);
        delegatedAdmin.assertWithinScope(existing.path(), "change role " + existing.code());

        UUID parentId = request.parentId();
        RoleNode parent = parentId == null ? null : require(byId, parentId);
        if (parent != null) {
            assertNoCycle(byId, existing, parent);
            delegatedAdmin.assertWithinScope(parent.path(), "move " + existing.code() + " under " + parent.code());
        }

        String code = request.code().trim().toUpperCase(Locale.ROOT);
        String newPath = (parent == null ? "/" : parent.path()) + code + "/";
        int newDepth = parent == null ? 0 : parent.depth() + 1;

        jdbc.update("""
                update security.role_node
                set code = ?, name = ?, description = ?, parent_id = ?, path = ?, depth = ?,
                    updated_at = now(), updated_by = ?
                where tenant_id = ? and id = ?
                """, code, request.name(), request.description(), parentId, newPath, newDepth,
                TenantContext.get().userId(), tenantId, id);

        // Rewrite the subtree in one statement. Path prefixes are unambiguous because
        // every path is slash-terminated, so /APAC/ cannot match /APAC_WEST/.
        int moved = 0;
        if (!existing.path().equals(newPath)) {
            moved = jdbc.update("""
                    update security.role_node
                    set path = ? || substring(path from ?), depth = depth + ?, updated_at = now()
                    where tenant_id = ? and path like ? and id <> ?
                    """, newPath, existing.path().length() + 1, newDepth - existing.depth(),
                    tenantId, existing.path() + "%", id);
        }

        audit.record("ROLE_UPDATED", "ROLE_NODE", id, "Updated role " + code,
                Map.of("before", Map.of("code", existing.code(), "path", existing.path(),
                                "parent", existing.parentCode() == null ? "" : existing.parentCode()),
                        "after", Map.of("code", code, "path", newPath,
                                "parent", parent == null ? "" : parent.code()),
                        "descendantsRepathed", moved));
        return find(id);
    }

    /**
     * @throws ConflictException naming both roles, exactly as FR-SEC-001 requires
     */
    void assertNoCycle(Map<UUID, RoleNode> byId, RoleNode node, RoleNode proposedParent) {
        if (proposedParent.id().equals(node.id())) {
            throw new ConflictException("Role hierarchy cycle rejected: " + node.code()
                    + " cannot report to itself.");
        }
        // Walking down is the same test as walking up and is bounded by the subtree.
        // A proposed parent inside the node's own subtree is exactly a cycle.
        Set<String> subtree = new LinkedHashSet<>();
        for (RoleNode candidate : byId.values()) {
            if (candidate.path().startsWith(node.path())) subtree.add(candidate.code());
        }
        if (subtree.contains(proposedParent.code())) {
            throw new ConflictException("Role hierarchy cycle rejected: " + node.code()
                    + " cannot report to " + proposedParent.code() + " because " + proposedParent.code()
                    + " is already beneath " + node.code() + " (" + proposedParent.path() + "). "
                    + "Move " + proposedParent.code() + " out from under " + node.code() + " first.");
        }
        // Belt and braces for a subtree whose paths are stale: walk the parent chain.
        UUID cursor = proposedParent.parentId();
        int hops = 0;
        while (cursor != null && hops++ < 512) {
            if (cursor.equals(node.id())) {
                throw new ConflictException("Role hierarchy cycle rejected: " + node.code()
                        + " cannot report to " + proposedParent.code() + " because " + node.code()
                        + " is already an ancestor of " + proposedParent.code() + ".");
            }
            RoleNode next = byId.get(cursor);
            cursor = next == null ? null : next.parentId();
        }
    }

    @Transactional
    public void deactivate(UUID id) {
        authorization.requirePermission("SYS.MANAGE_ROLES", "deactivate a role");
        RoleNode role = find(id);
        delegatedAdmin.assertWithinScope(role.path(), "deactivate role " + role.code());
        if (role.memberCount() > 0) {
            throw new ConflictException("Role " + role.code() + " still has " + role.memberCount()
                    + " member(s). Reassign them to another role before deactivating it, so nobody "
                    + "silently loses the visibility this role gives them.");
        }
        jdbc.update("update security.role_node set active = false, updated_at = now() where tenant_id = ? and id = ?",
                TenantContext.get().tenantId(), id);
        audit.record("ROLE_DEACTIVATED", "ROLE_NODE", id, "Deactivated role " + role.code(), Map.of());
    }

    // ------------------------------------------------------------------ assignment

    @Transactional
    public void assignUser(UUID userId, UUID roleNodeId, java.time.Instant expiresAt) {
        authorization.requirePermission("SYS.MANAGE_ROLES", "assign a user to a role");
        UUID tenantId = TenantContext.get().tenantId();
        RoleNode role = find(roleNodeId);
        delegatedAdmin.assertCanAdministerUser(userId);
        delegatedAdmin.assertWithinScope(role.path(), "assign a user into " + role.code());

        Map<String, Object> before = currentAssignment(tenantId, userId);
        jdbc.update("""
                insert into security.user_role_assignment (tenant_id, user_id, role_node_id, expires_at, granted_by)
                values (?, ?, ?, ?, ?)
                on conflict (tenant_id, user_id) do update
                set role_node_id = excluded.role_node_id, expires_at = excluded.expires_at,
                    granted_by = excluded.granted_by, granted_at = now()
                """, tenantId, userId, roleNodeId,
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt),
                TenantContext.get().userId());

        Map<String, Object> after = Map.of("role", role.code(), "path", role.path(),
                "expiresAt", expiresAt == null ? "never" : expiresAt.toString());
        grantLog.write(userId, "ROLE", roleNodeId, AccessGrantLog.MODIFY, before, after, null, null,
                "Role assignment changed");
        audit.record("ROLE_ASSIGNED", "APP_USER", userId,
                "Assigned to role " + role.code(),
                Map.of("before", before, "after", after));
    }

    private Map<String, Object> currentAssignment(UUID tenantId, UUID userId) {
        Map<String, Object> before = new HashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select rn.code, rn.path, ura.expires_at
                from security.user_role_assignment ura
                join security.role_node rn on rn.tenant_id = ura.tenant_id and rn.id = ura.role_node_id
                where ura.tenant_id = ? and ura.user_id = ?
                """, tenantId, userId);
        if (rows.isEmpty()) return Map.of("role", "", "path", "", "expiresAt", "");
        Map<String, Object> row = rows.getFirst();
        before.put("role", String.valueOf(row.get("code")));
        before.put("path", String.valueOf(row.get("path")));
        before.put("expiresAt", row.get("expires_at") == null ? "never" : String.valueOf(row.get("expires_at")));
        return before;
    }

    // ------------------------------------------------------------------ helpers

    private Map<UUID, RoleNode> index() {
        Map<UUID, RoleNode> byId = new HashMap<>();
        hierarchy().forEach(r -> byId.put(r.id(), r));
        return byId;
    }

    private RoleNode require(Map<UUID, RoleNode> byId, UUID id) {
        RoleNode role = byId.get(id);
        if (role == null) throw new NotFoundException("No role with id " + id + " in this tenant");
        return role;
    }

    /** Users placed strictly beneath the given role path — the roll-up population. */
    @Transactional(readOnly = true)
    public List<UUID> subordinateUserIds(String rolePath) {
        if (rolePath == null) return List.of();
        return jdbc.query("""
                select ura.user_id from security.user_role_assignment ura
                join security.role_node rn on rn.tenant_id = ura.tenant_id and rn.id = ura.role_node_id
                where ura.tenant_id = ? and rn.path <> ? and rn.path like ?
                """, (rs, i) -> rs.getObject(1, UUID.class),
                TenantContext.get().tenantId(), rolePath, rolePath + "%");
    }

    /** Every code from the root down to this role, for the access explainer. */
    @Transactional(readOnly = true)
    public List<String> ancestry(String rolePath) {
        if (rolePath == null) return List.of();
        List<String> codes = new ArrayList<>();
        for (String part : rolePath.split("/")) if (!part.isBlank()) codes.add(part);
        return codes;
    }
}

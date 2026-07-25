package com.axiom.security;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Delegated administration (FR-SEC-011): manage users, roles and configuration only
 * within an assigned branch of the org hierarchy, and never escalate your own
 * privileges.
 *
 * <p>The model document names two properties that have to hold, and both are
 * enforced here rather than by convention:
 *
 * <ol>
 *   <li><b>Scope is structural, not a permission the delegate could edit.</b> A
 *       delegated administrator's reach is derived from their
 *       {@code delegated_admin_scope} rows, and every write path asks this service
 *       whether the target sits inside that branch. A delegate cannot widen their own
 *       branch because widening it is itself an administrative act on a role above
 *       them, which fails the same check.</li>
 *   <li><b>No self-escalation.</b> A delegate cannot grant a permission they do not
 *       themselves hold, and cannot grant anything to themselves at all. This is the
 *       classic bug in every homegrown admin-delegation feature: the delegate is
 *       trusted to administer <em>others</em> and the code never notices when the
 *       target is the actor.</li>
 * </ol>
 *
 * <p>A <b>full</b> tenant administrator — one holding {@code SYS.MANAGE_ROLES} with no
 * scope rows — is unrestricted by design. Delegation only ever narrows.
 */
@Service
public class DelegatedAdminService {

    public record Scope(UUID id, UUID userId, String userEmail, UUID roleNodeId, String roleCode,
                        String rolePath, boolean canManageUsers, boolean canManageRoles,
                        boolean canManageConfig, Instant expiresAt) {}

    public record ScopeRequest(UUID userId, UUID roleNodeId, boolean canManageUsers,
                               boolean canManageRoles, boolean canManageConfig, Instant expiresAt) {}

    private final JdbcTemplate jdbc;
    private final PermissionResolver resolver;
    private final AuditService audit;
    private final AccessGrantLog grantLog;

    public DelegatedAdminService(JdbcTemplate jdbc, PermissionResolver resolver, AuditService audit,
                                 AccessGrantLog grantLog) {
        this.jdbc = jdbc;
        this.resolver = resolver;
        this.audit = audit;
        this.grantLog = grantLog;
    }

    // ------------------------------------------------------------------ scope reads

    @Transactional(readOnly = true)
    public List<Scope> scopes() {
        return jdbc.query("""
                select s.id, s.user_id, u.email, s.role_node_id, r.code, r.path,
                       s.can_manage_users, s.can_manage_roles, s.can_manage_config, s.expires_at
                from security.delegated_admin_scope s
                join identity.app_user u on u.tenant_id = s.tenant_id and u.id = s.user_id
                join security.role_node r on r.tenant_id = s.tenant_id and r.id = s.role_node_id
                where s.tenant_id = ?
                order by u.email, r.path
                """, (rs, i) -> map(rs), TenantContext.get().tenantId());
    }

    /** The branches the current caller administers. Empty means unrestricted-or-nothing. */
    @Transactional(readOnly = true)
    public List<Scope> scopesFor(UUID userId) {
        return jdbc.query("""
                select s.id, s.user_id, u.email, s.role_node_id, r.code, r.path,
                       s.can_manage_users, s.can_manage_roles, s.can_manage_config, s.expires_at
                from security.delegated_admin_scope s
                join identity.app_user u on u.tenant_id = s.tenant_id and u.id = s.user_id
                join security.role_node r on r.tenant_id = s.tenant_id and r.id = s.role_node_id
                where s.tenant_id = ? and s.user_id = ?
                  and (s.expires_at is null or s.expires_at > now())
                """, (rs, i) -> map(rs), TenantContext.get().tenantId(), userId);
    }

    private Scope map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Scope(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("email"), rs.getObject("role_node_id", UUID.class), rs.getString("code"),
                rs.getString("path"), rs.getBoolean("can_manage_users"), rs.getBoolean("can_manage_roles"),
                rs.getBoolean("can_manage_config"),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant());
    }

    public boolean isDelegated() {
        return !scopesFor(TenantContext.get().userId()).isEmpty();
    }

    // ------------------------------------------------------------------ the checks

    /**
     * Is the given role branch inside the caller's administrative scope?
     *
     * @throws ForbiddenException naming the branches the caller does administer, so the
     *                           message says what to do rather than only what failed
     */
    @Transactional(readOnly = true)
    public void assertWithinScope(String targetRolePath, String what) {
        AccessContext ctx = resolver.current();
        if (ctx.platformOperator()) return;
        List<Scope> scopes = scopesFor(ctx.userId());
        if (scopes.isEmpty()) return; // full administrator, or no admin rights at all (checked elsewhere)
        boolean inside = scopes.stream().anyMatch(s -> targetRolePath != null
                && targetRolePath.startsWith(s.rolePath()));
        if (!inside) {
            throw new ForbiddenException("Your delegated administration is limited to "
                    + scopes.stream().map(Scope::roleCode).distinct().reduce((a, b) -> a + ", " + b).orElse("")
                    + ". You cannot " + what + " because it is outside that branch.");
        }
    }

    /** Is the given user inside the caller's administrative scope? */
    @Transactional(readOnly = true)
    public void assertCanAdministerUser(UUID targetUserId) {
        AccessContext ctx = resolver.current();
        if (ctx.platformOperator()) return;
        List<Scope> scopes = scopesFor(ctx.userId());
        if (scopes.isEmpty()) return;
        if (!scopes.stream().anyMatch(Scope::canManageUsers)) {
            throw new ForbiddenException("Your delegated administration does not include user management.");
        }
        String path = jdbc.query("""
                select rn.path from security.user_role_assignment ura
                join security.role_node rn on rn.tenant_id = ura.tenant_id and rn.id = ura.role_node_id
                where ura.tenant_id = ? and ura.user_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, ctx.tenantId(), targetUserId);
        if (path == null) {
            throw new ForbiddenException("That user is not placed in the role hierarchy, so a delegated "
                    + "administrator cannot establish whether they fall inside your branch. Ask a full "
                    + "tenant administrator to assign them a role first.");
        }
        assertWithinScope(path, "administer that user");
    }

    /**
     * No self-escalation (FR-SEC-011). Two independent refusals:
     * <ul>
     *   <li>A delegated administrator cannot grant anything <b>to themselves</b>. Even a
     *       permission they already hold, because a self-grant is how a time-bound grant
     *       becomes permanent.</li>
     *   <li>A delegated administrator cannot grant a permission they <b>do not hold</b>.
     *       Administering a branch is authority over people, not the ability to mint
     *       rights that were never delegated.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public void assertNoSelfEscalation(UUID targetUserId, Set<String> permissionCodes) {
        AccessContext ctx = resolver.current();
        if (ctx.platformOperator()) return;
        List<Scope> scopes = scopesFor(ctx.userId());
        if (scopes.isEmpty()) return; // full tenant administrator

        if (ctx.userId().equals(targetUserId)) {
            audit.record("SELF_ESCALATION_REFUSED", "APP_USER", targetUserId,
                    "Delegated administrator attempted to grant permissions to themselves",
                    Map.of("permissions", new ArrayList<>(permissionCodes), "control", "FR-SEC-011"));
            throw new ForbiddenException("A delegated administrator cannot grant permissions to "
                    + "themselves. Ask a tenant administrator to make this change.");
        }

        Set<String> missing = new LinkedHashSet<>();
        for (String code : permissionCodes) if (!ctx.holds(code)) missing.add(code);
        if (!missing.isEmpty()) {
            audit.record("SELF_ESCALATION_REFUSED", "APP_USER", targetUserId,
                    "Delegated administrator attempted to grant permissions they do not hold",
                    Map.of("permissions", new ArrayList<>(missing), "control", "FR-SEC-011"));
            throw new ForbiddenException("You cannot grant " + String.join(", ", missing)
                    + " because you do not hold " + (missing.size() == 1 ? "it" : "them") + " yourself. "
                    + "A delegated administrator can only pass on rights they have.");
        }
    }

    // ------------------------------------------------------------------ scope admin

    @Transactional
    public Scope grantScope(ScopeRequest request) {
        AccessContext ctx = resolver.current();
        if (!ctx.platformOperator() && !ctx.holds("SYS.MANAGE_ROLES")) {
            throw new ForbiddenException("You do not hold SYS.MANAGE_ROLES, which is required to "
                    + "delegate administration.");
        }
        // A delegate cannot create or widen a delegation: that is administration of a
        // role above their own branch, and the scope check refuses it.
        String path = jdbc.query("select path from security.role_node where tenant_id = ? and id = ?",
                rs -> rs.next() ? rs.getString(1) : null, ctx.tenantId(), request.roleNodeId());
        assertWithinScope(path, "delegate administration of that branch");
        if (ctx.userId().equals(request.userId()) && isDelegated()) {
            throw new ForbiddenException("A delegated administrator cannot widen their own scope.");
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into security.delegated_admin_scope
                  (id, tenant_id, user_id, role_node_id, can_manage_users, can_manage_roles,
                   can_manage_config, expires_at, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, user_id, role_node_id) do update
                set can_manage_users = excluded.can_manage_users,
                    can_manage_roles = excluded.can_manage_roles,
                    can_manage_config = excluded.can_manage_config,
                    expires_at = excluded.expires_at
                """, id, ctx.tenantId(), request.userId(), request.roleNodeId(),
                request.canManageUsers(), request.canManageRoles(), request.canManageConfig(),
                request.expiresAt() == null ? null : java.sql.Timestamp.from(request.expiresAt()),
                ctx.userId());

        grantLog.write(request.userId(), "DELEGATED_ADMIN", request.roleNodeId(), AccessGrantLog.GRANT,
                null, Map.of("branch", path == null ? "" : path,
                        "manageUsers", request.canManageUsers(),
                        "manageRoles", request.canManageRoles(),
                        "manageConfig", request.canManageConfig(),
                        "expiresAt", request.expiresAt() == null ? "never" : request.expiresAt().toString()),
                null, null, "Delegated administration granted");
        audit.record("DELEGATED_ADMIN_GRANTED", "APP_USER", request.userId(),
                "Granted delegated administration of " + path,
                Map.of("branch", path == null ? "" : path,
                        "manageUsers", request.canManageUsers(),
                        "manageRoles", request.canManageRoles(),
                        "manageConfig", request.canManageConfig()));

        return scopesFor(request.userId()).stream()
                .filter(s -> s.roleNodeId().equals(request.roleNodeId()))
                .findFirst().orElseThrow();
    }

    @Transactional
    public void revokeScope(UUID scopeId) {
        AccessContext ctx = resolver.current();
        if (!ctx.platformOperator() && !ctx.holds("SYS.MANAGE_ROLES")) {
            throw new ForbiddenException("You do not hold SYS.MANAGE_ROLES, which is required to "
                    + "revoke delegated administration.");
        }
        List<Scope> matching = scopes().stream().filter(s -> s.id().equals(scopeId)).toList();
        jdbc.update("delete from security.delegated_admin_scope where tenant_id = ? and id = ?",
                ctx.tenantId(), scopeId);
        if (!matching.isEmpty()) {
            Scope scope = matching.getFirst();
            grantLog.write(scope.userId(), "DELEGATED_ADMIN", scope.roleNodeId(), AccessGrantLog.REVOKE,
                    Map.of("branch", scope.rolePath()), null, null, null,
                    "Delegated administration revoked");
            audit.record("DELEGATED_ADMIN_REVOKED", "APP_USER", scope.userId(),
                    "Revoked delegated administration of " + scope.rolePath(),
                    Map.of("branch", scope.rolePath()));
        }
    }
}

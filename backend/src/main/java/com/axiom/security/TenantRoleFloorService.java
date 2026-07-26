package com.axiom.security;

import com.axiom.activity.UserActivityService;
import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The tenant administrator / auditor invariant.
 *
 * <p><b>Every tenant must have one administrator with complete read and write,
 * and one auditor with complete read and view.</b> {@code TENANT_ADMIN} and
 * {@code AUDITOR} in {@link CrmRole} are those two roles — {@code AUDITOR} is
 * declared {@code readOnly}, which is what makes "read and view" literally true
 * rather than a matter of configuration.
 *
 * <h2>Enforced as a floor, in three places</h2>
 * <ol>
 *   <li><b>Here, in the service layer</b>, before the write. This is the only
 *       layer that can produce a usable refusal: it names the constraint and
 *       says what to do instead. A database error message cannot do the second
 *       part.</li>
 *   <li><b>In the database</b> (V310, {@code security.assert_tenant_role_floor}),
 *       as a statement-level trigger on {@code identity.app_user}. That table is
 *       written by user administration, SCIM provisioning, seed migrations and
 *       psql. A check only one of those honours is not a constraint. The trigger
 *       is what makes the invariant true for the paths this class never sees —
 *       including the ones that do not exist yet.</li>
 *   <li><b>At the read-only role level</b>: {@code AUDITOR} cannot mutate
 *       anything, so an auditor cannot dismantle the floor even where it holds
 *       the last seat.</li>
 * </ol>
 *
 * <h2>Report, do not repair</h2>
 * {@link #report()} lists tenants currently in breach. It does not fix them.
 * Auto-granting {@code TENANT_ADMIN} to whoever happens to be first in the user
 * list would produce an administrator nobody appointed and no record of why —
 * an unexplained role grant is worse than a visible gap, because a gap can still
 * be seen. {@link #repair} exists, requires a named user, and is audited.
 */
@Service
public class TenantRoleFloorService {

    /** SQLSTATE raised by the V310 trigger. Mapped to 409 by {@link SecurityConstraintAdvice}. */
    public static final String FLOOR_SQLSTATE = "AX001";

    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    public static final String AUDITOR = "AUDITOR";

    private static final Map<String, String> FLOOR_ROLES = new LinkedHashMap<>();
    static {
        FLOOR_ROLES.put(TENANT_ADMIN, "administrator with complete read and write");
        FLOOR_ROLES.put(AUDITOR, "auditor with complete read and view");
    }

    private static final Map<String, String> REMEDY = Map.of(
            TENANT_ADMIN, "promote another user to Tenant Admin first",
            AUDITOR, "promote another user to Auditor first");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final AccessGrantLog grantLog;
    private final UserActivityService activity;

    public TenantRoleFloorService(JdbcTemplate jdbc, AuditService audit, AccessGrantLog grantLog,
                                  UserActivityService activity) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.grantLog = grantLog;
        this.activity = activity;
    }

    // -----------------------------------------------------------------------
    // Types
    // -----------------------------------------------------------------------

    public record TenantUser(UUID id, String email, String displayName, String crmRole, boolean active) {}

    /** One tenant × one floor role. {@code compliant} false is a violation to act on. */
    public record FloorFinding(UUID tenantId, String tenantSlug, String tenantName, String roleCode,
                               String requirement, int activeHolders, boolean compliant,
                               String finding, String remedy) {}

    public record FloorReport(List<FloorFinding> findings, int tenantsInspected, int violations,
                              boolean crossTenant) {}

    public record SetActiveRequest(@NotNull UUID userId, boolean active, String reason) {}

    public record ChangeRoleRequest(@NotNull UUID userId, @NotBlank String role, String reason) {}

    public record RepairRequest(@NotNull UUID userId, @NotBlank String role, @NotBlank String reason) {}

    // -----------------------------------------------------------------------
    // The invariant
    // -----------------------------------------------------------------------

    /**
     * Refuse if deactivating this user would empty a floor role.
     *
     * <p>Public and separate from {@link #setUserActive} so any future user
     * administration path can call it without going through this service's own
     * write. It is the check, not the write, that is the control.
     */
    @Transactional(readOnly = true)
    public void assertCanDeactivate(UUID userId) {
        TenantUser user = requireUser(userId);
        if (!user.active()) return;                      // already inactive: nothing is being removed
        assertNotLastHolder(user, user.crmRole(), "deactivated");
    }

    /** Refuse if deleting this user would empty a floor role. */
    @Transactional(readOnly = true)
    public void assertCanDelete(UUID userId) {
        TenantUser user = requireUser(userId);
        if (!user.active()) return;
        assertNotLastHolder(user, user.crmRole(), "deleted");
    }

    /**
     * Refuse if moving this user off their current role would empty a floor
     * role. Moving a user <i>onto</i> a floor role is always allowed — that is
     * how a gap gets closed.
     */
    @Transactional(readOnly = true)
    public void assertCanChangeRole(UUID userId, String newRole) {
        TenantUser user = requireUser(userId);
        String target = normaliseRole(newRole);
        if (!user.active()) return;
        if (target.equals(user.crmRole())) return;
        assertNotLastHolder(user, user.crmRole(), "moved to another role");
    }

    private void assertNotLastHolder(TenantUser user, String roleCode, String verb) {
        if (!FLOOR_ROLES.containsKey(roleCode)) return;
        int others = jdbc.queryForObject("""
                select count(*) from identity.app_user
                 where tenant_id = ? and role = ? and active and id <> ?
                """, Integer.class, TenantContext.get().tenantId(), roleCode, user.id());
        if (others > 0) return;

        String message = "This workspace must keep at least one active " + roleCode + " — "
                + "the " + FLOOR_ROLES.get(roleCode) + ". "
                + user.displayName() + " (" + user.email() + ") is the last one, so the account cannot be "
                + verb + ". To proceed, " + REMEDY.get(roleCode) + ", then repeat this change. "
                + "Constraint: tenant administrator/auditor floor.";

        // The SAME refusal, addressed by id rather than by name and email.
        //
        // The message above is for the administrator reading a 409: naming the
        // person is what makes it actionable. The activity log is a different
        // audience with a different rule — FR-AUD-014 forbids unmasked personal
        // data, and the target user's work email is the personal data of someone
        // who is not even the actor. The opaque id says the same thing to anyone
        // entitled to resolve it, and nothing to anyone who is not.
        String logged = "The last active " + roleCode + " of this workspace cannot be " + verb
                + ". Refused by the tenant administrator/auditor floor.";

        // Recorded before the throw so it survives the rollback of whatever
        // transaction the caller is in — a denial erased by its own rollback is
        // the one row a security review most needed.
        activity.markDenied(logged, "USER", user.id());
        activity.record(new UserActivityService.ActivityEvent(
                TenantContext.get().tenantId(), TenantContext.get().userId(),
                TenantContext.get().email(), TenantContext.get().role(),
                null, null,
                "SECURITY ROLE_FLOOR_REFUSED", null, null,
                "USER", user.id(), "API", UserActivityService.DENIED, 409, logged,
                org.slf4j.MDC.get(com.axiom.common.CorrelationIdFilter.MDC_KEY),
                TenantContext.clientIp(), null,
                Map.of("targetUserId", user.id().toString(),
                       "targetRole", roleCode,
                       "constraintName", "tenant_administrator_auditor_floor",
                       "denialReason", logged)));

        throw new ConflictException(message);
    }

    // -----------------------------------------------------------------------
    // Governed writes
    // -----------------------------------------------------------------------

    /**
     * Activate or deactivate a user, floor-checked.
     *
     * <p>This does not replace {@code /api/v1/admin/users/{id}/active} — that
     * endpoint belongs to another module. It is the governed equivalent on the
     * security surface, and the database trigger covers the other one.
     */
    @Transactional
    public TenantUser setUserActive(SetActiveRequest request) {
        RbacAccess.requireWrite("change whether a user is active");
        TenantUser before = requireUser(request.userId());
        if (!request.active()) assertCanDeactivate(request.userId());

        jdbc.update("""
                update identity.app_user set active = ?, updated_at = now()
                 where tenant_id = ? and id = ?
                """, request.active(), TenantContext.get().tenantId(), request.userId());

        TenantUser after = requireUser(request.userId());
        audit.recordWithReason(request.active() ? "USER_ACTIVATED" : "USER_DEACTIVATED",
                "APP_USER", after.id(),
                (request.active() ? "Enabled " : "Disabled ") + after.email(),
                request.reason(),
                Map.of("before", Map.of("active", before.active()),
                       "after", Map.of("active", after.active())));
        grantLog.write(after.id(), "USER_STATUS", after.id(),
                request.active() ? AccessGrantLog.GRANT : AccessGrantLog.REVOKE,
                Map.of("active", before.active()), Map.of("active", after.active()),
                null, null, request.reason());
        return after;
    }

    /** Change a user's CRM role, floor-checked. */
    @Transactional
    public TenantUser changeRole(ChangeRoleRequest request) {
        RbacAccess.requireWrite("change a user's role");
        String target = normaliseRole(request.role());
        if (CrmRole.current(target).platform()) {
            throw new ForbiddenException("Platform roles are managed outside tenant user administration.");
        }
        TenantUser before = requireUser(request.userId());
        assertCanChangeRole(request.userId(), target);

        jdbc.update("""
                update identity.app_user set role = ?, updated_at = now()
                 where tenant_id = ? and id = ?
                """, target, TenantContext.get().tenantId(), request.userId());

        TenantUser after = requireUser(request.userId());
        audit.recordWithReason("USER_ROLE_CHANGED", "APP_USER", after.id(),
                after.email() + " moved from " + before.crmRole() + " to " + after.crmRole(),
                request.reason(),
                Map.of("before", Map.of("role", before.crmRole()),
                       "after", Map.of("role", after.crmRole())));
        grantLog.write(after.id(), "CRM_ROLE", after.id(), AccessGrantLog.MODIFY,
                Map.of("role", before.crmRole()), Map.of("role", after.crmRole()),
                null, null, request.reason());
        return after;
    }

    // -----------------------------------------------------------------------
    // Integrity report and repair
    // -----------------------------------------------------------------------

    /**
     * Which tenants currently satisfy the invariant, and which do not.
     *
     * <p>Cross-tenant for a platform operator, because "which tenants are in
     * breach" is not a question a single tenant's data can answer, and the
     * operator who has to act on it is not signed in to the offending
     * workspace. Tenant-scoped for everyone else — a tenant administrator has no
     * business reading another tenant's posture.
     */
    @Transactional(readOnly = true)
    public FloorReport report() {
        RbacAccess.requireRead();
        boolean crossTenant = CrmRole.current(TenantContext.get().role()).platform();
        UUID scope = crossTenant ? null : TenantContext.get().tenantId();
        List<FloorFinding> findings = jdbc.query(
                "select * from security.tenant_role_floor_report(?)",
                (rs, i) -> new FloorFinding(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_slug"),
                        rs.getString("tenant_name"),
                        rs.getString("role_code"),
                        rs.getString("requirement"),
                        rs.getInt("active_holders"),
                        rs.getBoolean("compliant"),
                        rs.getString("finding"),
                        rs.getString("remedy")),
                scope);
        long tenants = findings.stream().map(FloorFinding::tenantId).distinct().count();
        int violations = (int) findings.stream().filter(f -> !f.compliant()).count();
        return new FloorReport(findings, (int) tenants, violations, crossTenant);
    }

    /**
     * Close a gap by granting the missing floor role to a <b>named</b> user.
     *
     * <p>Deliberately not a one-click "fix all". The reason is mandatory and the
     * grant is audited, because the whole value of the report is that a gap is
     * visible; a repair that nobody can attribute simply converts a visible gap
     * into an invisible one.
     */
    @Transactional
    public TenantUser repair(RepairRequest request) {
        RbacAccess.requireWrite("repair the administrator/auditor floor");
        String role = normaliseRole(request.role());
        if (!FLOOR_ROLES.containsKey(role)) {
            throw new ConflictException("Only " + String.join(" and ", FLOOR_ROLES.keySet())
                    + " are floor roles; " + role + " is not one of them.");
        }
        TenantUser user = requireUser(request.userId());
        if (!user.active()) {
            throw new ConflictException("An inactive account cannot hold the " + role
                    + " seat. Re-enable " + user.email() + " first.");
        }
        // Moving this user may itself empty the role they hold today.
        assertCanChangeRole(user.id(), role);

        jdbc.update("""
                update identity.app_user set role = ?, updated_at = now()
                 where tenant_id = ? and id = ?
                """, role, TenantContext.get().tenantId(), user.id());

        TenantUser after = requireUser(user.id());
        audit.recordWithReason("TENANT_ROLE_FLOOR_REPAIRED", "APP_USER", after.id(),
                "Granted " + role + " to " + after.email() + " to satisfy the tenant "
                        + "administrator/auditor floor",
                request.reason(),
                Map.of("before", Map.of("role", user.crmRole()),
                       "after", Map.of("role", after.crmRole())));
        grantLog.write(after.id(), "CRM_ROLE", after.id(), AccessGrantLog.GRANT,
                Map.of("role", user.crmRole()), Map.of("role", after.crmRole()),
                null, null, request.reason());
        return after;
    }

    /** Users of the current tenant, for the repair picker and the activity filter. */
    @Transactional(readOnly = true)
    public List<TenantUser> tenantUsers() {
        RbacAccess.requireRead();
        return jdbc.query("""
                select id, email, display_name, role, active
                  from identity.app_user
                 where tenant_id = ?
                 order by active desc, display_name
                """, this::mapUser, TenantContext.get().tenantId());
    }

    // -----------------------------------------------------------------------

    private TenantUser requireUser(UUID userId) {
        try {
            return jdbc.queryForObject("""
                    select id, email, display_name, role, active
                      from identity.app_user
                     where tenant_id = ? and id = ?
                    """, this::mapUser, TenantContext.get().tenantId(), userId);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("No such user in this workspace");
        }
    }

    private TenantUser mapUser(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new TenantUser(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("display_name"), rs.getString("role"), rs.getBoolean("active"));
    }

    private static String normaliseRole(String role) {
        return role == null ? "" : role.trim().toUpperCase(java.util.Locale.ROOT);
    }
}

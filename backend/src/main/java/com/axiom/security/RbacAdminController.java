package com.axiom.security;

import com.axiom.common.ConflictException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The RBAC administration surface — one HTTP front for an engine that already
 * existed with no way to reach it.
 *
 * <h2>Deliberately thin</h2>
 * Every method here delegates. The permission checks, the segregation-of-duties
 * evaluation, the delegated-admin scoping, the audit writes and the grant log
 * all live in the services below and are not repeated: a rule enforced in two
 * places is a rule that will one day be enforced differently in two places. What
 * this class adds is the role gate for <i>reading</i> (the services gate writes
 * via {@code SYS.*} permissions but mostly leave reads open) and validation of
 * the few string parameters the services pass straight through to a database
 * CHECK constraint.
 *
 * <h2>Path choice</h2>
 * {@code /api/v1/security/**} is already in {@code JwtAuthFilter}'s
 * {@code IMPERSONATION_FORBIDDEN} list, so an impersonating operator cannot use
 * any of this to escalate, and in {@code SUSPENDED_WRITE_ALLOWED}, so an
 * administrator can still fix authorization in a suspended workspace. Both
 * behaviours are inherited rather than re-implemented.
 */
@RestController
@RequestMapping("/api/v1/security/rbac")
@Validated
public class RbacAdminController {

    private static final Set<String> FINDING_STATUSES =
            Set.of("OPEN", "ACKNOWLEDGED", "REMEDIATED", "ACCEPTED");

    private final RoleHierarchyService roles;
    private final PermissionAdminService permissions;
    private final SharingRuleService sharing;
    private final SodService sod;
    private final DelegatedAdminService delegatedAdmin;
    private final AccessExplainerService explainer;
    private final TenantRoleFloorService floor;
    private final SensitiveFieldService sensitiveFields;
    private final AccessReviewService accessReviews;

    public RbacAdminController(RoleHierarchyService roles, PermissionAdminService permissions,
                               SharingRuleService sharing, SodService sod,
                               DelegatedAdminService delegatedAdmin, AccessExplainerService explainer,
                               TenantRoleFloorService floor, SensitiveFieldService sensitiveFields,
                               AccessReviewService accessReviews) {
        this.roles = roles;
        this.permissions = permissions;
        this.sharing = sharing;
        this.sod = sod;
        this.delegatedAdmin = delegatedAdmin;
        this.explainer = explainer;
        this.floor = floor;
        this.sensitiveFields = sensitiveFields;
        this.accessReviews = accessReviews;
    }

    // ---------------------------------------------------------------- requests

    public record RoleAssignmentRequest(@NotNull UUID userId, @NotNull UUID roleNodeId, Instant expiresAt) {}
    public record ProfileAssignmentRequest(@NotNull UUID userId, @NotNull UUID profileId, String reason) {}
    public record GroupMemberRequest(@NotNull UUID permissionSetId, List<String> mutedPermissions) {}
    public record HolderPermissionsRequest(@NotNull List<String> permissionCodes) {}
    public record RevokeRequest(String reason) {}
    public record UserGroupRequest(@NotBlank String code, @NotBlank String name) {}
    public record MembershipRequest(@NotNull UUID userId) {}
    public record FindingResolution(@NotBlank String status, String note) {}
    public record AccessReviewDecision(@NotBlank String decision, String note) {}

    /** What the screen needs to render every picker in one round trip. */
    public record RbacOverview(List<PermissionAdminService.PermissionDescriptor> permissions,
                               List<String> objects,
                               List<RoleHierarchyService.RoleNode> roles,
                               List<PermissionAdminService.ProfileRow> profiles,
                               List<PermissionAdminService.PermissionSetRow> permissionSets,
                               List<SharingRuleService.NamedRow> userGroups,
                               List<SharingRuleService.NamedRow> territories,
                               List<TenantRoleFloorService.TenantUser> users,
                               List<SensitiveFieldService.SensitiveField> sensitiveFields,
                               boolean canWrite) {}

    // ------------------------------------------------------------------ shared

    @GetMapping("/overview")
    public RbacOverview overview() {
        RbacAccess.requireRead();
        return new RbacOverview(
                permissions.permissionCatalogue(),
                permissions.securableObjects(),
                roles.hierarchy(),
                permissions.profiles(),
                permissions.permissionSets(),
                sharing.userGroups(),
                sharing.territories(),
                floor.tenantUsers(),
                sensitiveFields.all(),
                RbacAccess.canWrite());
    }

    @GetMapping("/objects/{objectType}/fields")
    public List<String> fields(@PathVariable String objectType) {
        RbacAccess.requireRead();
        return permissions.fieldsOf(objectType);
    }

    // ------------------------------------------------------------------- roles

    @GetMapping("/roles")
    public List<RoleHierarchyService.RoleNode> roleHierarchy() {
        RbacAccess.requireRead();
        return roles.hierarchy();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleHierarchyService.RoleNode createRole(
            @RequestBody @Valid RoleHierarchyService.RoleRequest request) {
        return roles.create(request);
    }

    /** Rename or re-parent. A re-parent that would form a cycle is refused naming both roles. */
    @PutMapping("/roles/{id}")
    public RoleHierarchyService.RoleNode updateRole(@PathVariable UUID id,
                                                    @RequestBody @Valid RoleHierarchyService.RoleRequest request) {
        return roles.update(id, request);
    }

    @PostMapping("/roles/{id}/deactivate")
    public void deactivateRole(@PathVariable UUID id) {
        roles.deactivate(id);
    }

    @PostMapping("/roles/assignments")
    public void assignRole(@RequestBody @Valid RoleAssignmentRequest request) {
        roles.assignUser(request.userId(), request.roleNodeId(), request.expiresAt());
    }

    // -------------------------------------------------- profiles and permissions

    @GetMapping("/permissions")
    public List<PermissionAdminService.PermissionDescriptor> permissionCatalogue() {
        RbacAccess.requireRead();
        return permissions.permissionCatalogue();
    }

    @GetMapping("/profiles")
    public List<PermissionAdminService.ProfileRow> profiles() {
        RbacAccess.requireRead();
        return permissions.profiles();
    }

    @PostMapping("/profiles/assignments")
    public void assignProfile(@RequestBody @Valid ProfileAssignmentRequest request) {
        permissions.assignProfile(request.userId(), request.profileId(), request.reason());
    }

    @GetMapping("/permission-sets")
    public List<PermissionAdminService.PermissionSetRow> permissionSets() {
        RbacAccess.requireRead();
        return permissions.permissionSets();
    }

    @PostMapping("/permission-sets")
    @ResponseStatus(HttpStatus.CREATED)
    public PermissionAdminService.PermissionSetRow createPermissionSet(
            @RequestBody @Valid PermissionAdminService.PermissionSetRequest request) {
        return permissions.createPermissionSet(request);
    }

    @GetMapping("/permission-set-groups")
    public List<PermissionAdminService.PermissionSetGroupRow> permissionSetGroups() {
        RbacAccess.requireRead();
        return permissions.permissionSetGroups();
    }

    @PostMapping("/permission-set-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public PermissionAdminService.PermissionSetGroupRow createGroup(
            @RequestBody @Valid PermissionAdminService.PermissionSetRequest request) {
        return permissions.createGroup(request);
    }

    /** Add a set to a group, with the permissions muted inside that bundle (FR-SEC-003). */
    @PutMapping("/permission-set-groups/{groupId}/members")
    public void setGroupMember(@PathVariable UUID groupId,
                               @RequestBody @Valid GroupMemberRequest request) {
        permissions.setGroupMember(groupId, request.permissionSetId(),
                request.mutedPermissions() == null ? List.of() : request.mutedPermissions());
    }

    @GetMapping("/holders/{holderType}/{holderId}/permissions")
    public Set<String> holderPermissions(@PathVariable String holderType, @PathVariable UUID holderId) {
        RbacAccess.requireRead();
        return permissions.codesOfHolder(holderType, holderId);
    }

    @PutMapping("/holders/{holderType}/{holderId}/permissions")
    public void setHolderPermissions(@PathVariable String holderType, @PathVariable UUID holderId,
                                     @RequestBody @Valid HolderPermissionsRequest request) {
        permissions.setHolderPermissions(holderType, holderId, request.permissionCodes());
    }

    @GetMapping("/object-permissions")
    public List<PermissionAdminService.ObjectPermissionRow> objectPermissions(
            @RequestParam(required = false) String holderType,
            @RequestParam(required = false) UUID holderId) {
        RbacAccess.requireRead();
        return permissions.objectPermissions(holderType, holderId);
    }

    @PutMapping("/object-permissions")
    public void setObjectPermission(
            @RequestBody @Valid PermissionAdminService.ObjectPermissionRequest request) {
        permissions.setObjectPermission(request);
    }

    @GetMapping("/field-permissions")
    public List<PermissionAdminService.FieldPermissionRow> fieldPermissions(
            @RequestParam(required = false) String holderType,
            @RequestParam(required = false) UUID holderId) {
        RbacAccess.requireRead();
        return permissions.fieldPermissions(holderType, holderId);
    }

    @PutMapping("/field-permissions")
    public void setFieldPermission(
            @RequestBody @Valid PermissionAdminService.FieldPermissionRequest request) {
        permissions.setFieldPermission(request);
    }

    @GetMapping("/assignments")
    public List<PermissionAdminService.AssignmentRow> assignments(
            @RequestParam(required = false) UUID userId) {
        RbacAccess.requireRead();
        return permissions.assignments(userId);
    }

    /** Assign a permission set or a group to a user, optionally with an expiry (FR-SEC-012). */
    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public PermissionAdminService.AssignmentRow assign(
            @RequestBody @Valid PermissionAdminService.AssignRequest request) {
        return permissions.assign(request);
    }

    @PostMapping("/assignments/{id}/revoke")
    public void revokeAssignment(@PathVariable UUID id, @RequestBody(required = false) RevokeRequest body) {
        permissions.revoke(id, body == null ? null : body.reason());
    }

    @GetMapping("/users/{userId}/effective-permissions")
    public PermissionAdminService.EffectivePermissionView effectivePermissions(@PathVariable UUID userId) {
        RbacAccess.requireRead();
        return permissions.effectivePermissions(userId);
    }

    // ------------------------------------------------------ org-wide defaults

    @GetMapping("/org-wide-defaults")
    public List<PermissionAdminService.OrgWideDefaultRow> orgWideDefaults() {
        RbacAccess.requireRead();
        return permissions.orgWideDefaults();
    }

    @PutMapping("/org-wide-defaults")
    public void setOrgWideDefault(
            @RequestBody @Valid PermissionAdminService.OrgWideDefaultRequest request) {
        permissions.setOrgWideDefault(request);
    }

    // ------------------------------------------------------------ sharing rules

    @GetMapping("/sharing-rules")
    public List<SharingRuleService.SharingRule> sharingRules() {
        RbacAccess.requireRead();
        return sharing.rules();
    }

    @PostMapping("/sharing-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public SharingRuleService.SharingRule defineSharingRule(
            @RequestBody @Valid SharingRuleService.SharingRuleRequest request) {
        return sharing.define(request);
    }

    @PostMapping("/sharing-rules/{id}/activation")
    public SharingRuleService.SharingRule activate(@PathVariable UUID id,
                                                   @RequestParam boolean active) {
        return sharing.activate(id, active);
    }

    /** Recompute progress — the "visible progress" the data model requires. */
    @GetMapping("/sharing-jobs")
    public List<SharingRuleService.RecomputeJob> sharingJobs(@RequestParam(defaultValue = "25") int limit) {
        RbacAccess.requireRead();
        return sharing.jobs(limit);
    }

    @PostMapping("/sharing-jobs/drain")
    public Map<String, Integer> drain() {
        return Map.of("processed", sharing.drainNow());
    }

    @GetMapping("/shares")
    public List<SharingRuleService.RecordShareRow> shares(@RequestParam String objectType,
                                                          @RequestParam UUID recordId) {
        RbacAccess.requireRead();
        return sharing.sharesOf(objectType, recordId);
    }

    @PostMapping("/shares/manual")
    @ResponseStatus(HttpStatus.CREATED)
    public SharingRuleService.RecordShareRow shareManually(
            @RequestBody @Valid SharingRuleService.ManualShareRequest request) {
        return sharing.shareManually(request);
    }

    @DeleteMapping("/shares/{id}")
    public void revokeShare(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        sharing.revokeManualShare(id, reason);
    }

    @PostMapping("/record-teams")
    public void addTeamMember(@RequestBody @Valid SharingRuleService.TeamMemberRequest request) {
        sharing.addTeamMember(request);
    }

    @GetMapping("/user-groups")
    public List<SharingRuleService.NamedRow> userGroups() {
        RbacAccess.requireRead();
        return sharing.userGroups();
    }

    @PostMapping("/user-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public SharingRuleService.NamedRow createUserGroup(@RequestBody @Valid UserGroupRequest request) {
        return sharing.createUserGroup(request.code(), request.name());
    }

    @PostMapping("/user-groups/{groupId}/members")
    public void addUserGroupMember(@PathVariable UUID groupId, @RequestBody @Valid MembershipRequest body) {
        sharing.addUserGroupMember(groupId, body.userId());
    }

    @GetMapping("/territories")
    public List<SharingRuleService.NamedRow> territories() {
        RbacAccess.requireRead();
        return sharing.territories();
    }

    @PostMapping("/territories/{territoryId}/members")
    public void addTerritoryMember(@PathVariable UUID territoryId,
                                   @RequestBody @Valid MembershipRequest body) {
        sharing.addTerritoryMember(territoryId, body.userId());
    }

    // ------------------------------------------------------ segregation of duties

    @GetMapping("/sod/conflicts")
    public List<SodService.Conflict> sodConflicts() {
        RbacAccess.requireRead();
        return sod.conflicts();
    }

    @PostMapping("/sod/conflicts")
    @ResponseStatus(HttpStatus.CREATED)
    public SodService.Conflict declareConflict(@RequestBody @Valid SodService.ConflictRequest request) {
        return sod.declare(request);
    }

    @PostMapping("/sod/conflicts/{id}/retire")
    public void retireConflict(@PathVariable UUID id) {
        sod.retire(id);
    }

    /** Pre-existing violations, which FR-SEC-009 requires be reported rather than tolerated. */
    @GetMapping("/sod/findings")
    public List<SodService.Finding> sodFindings() {
        RbacAccess.requireRead();
        return sod.findings();
    }

    @PostMapping("/sod/sweep")
    public Map<String, Integer> sweep() {
        return Map.of("openFindings", sod.sweep());
    }

    @PostMapping("/sod/findings/{id}/resolution")
    public void resolveFinding(@PathVariable UUID id, @RequestBody @Valid FindingResolution body) {
        String status = body.status().trim().toUpperCase(java.util.Locale.ROOT);
        // SodService passes the status straight to a CHECK constraint; catching
        // it here keeps a typo a 409 with a usable message instead of a 500.
        if (!FINDING_STATUSES.contains(status)) {
            throw new ConflictException("Finding status must be one of "
                    + String.join(", ", FINDING_STATUSES) + ".");
        }
        sod.resolveFinding(id, status, body.note());
    }

    // ------------------------------------------------------ delegated administration

    @GetMapping("/delegated-admin")
    public List<DelegatedAdminService.Scope> delegatedScopes() {
        RbacAccess.requireRead();
        return delegatedAdmin.scopes();
    }

    @PostMapping("/delegated-admin")
    @ResponseStatus(HttpStatus.CREATED)
    public DelegatedAdminService.Scope grantScope(
            @RequestBody @Valid DelegatedAdminService.ScopeRequest request) {
        return delegatedAdmin.grantScope(request);
    }

    @DeleteMapping("/delegated-admin/{id}")
    public void revokeScope(@PathVariable UUID id) {
        delegatedAdmin.revokeScope(id);
    }

    // ------------------------------------------------------------- the explainer

    /** FR-SEC-013. Every rule that grants or denies, plus the reason when it denies. */
    @GetMapping("/access-explainer")
    public AccessExplainerService.AccessExplanation explain(@RequestParam UUID userId,
                                                            @RequestParam String objectType,
                                                            @RequestParam UUID recordId) {
        return explainer.explain(userId, objectType, recordId);
    }

    // ------------------------------------------- administrator / auditor floor

    @GetMapping("/tenant-floor")
    public TenantRoleFloorService.FloorReport tenantFloor() {
        return floor.report();
    }

    @GetMapping("/users")
    public List<TenantRoleFloorService.TenantUser> users() {
        return floor.tenantUsers();
    }

    @PostMapping("/users/active")
    public TenantRoleFloorService.TenantUser setUserActive(
            @RequestBody @Valid TenantRoleFloorService.SetActiveRequest request) {
        return floor.setUserActive(request);
    }

    @PostMapping("/users/role")
    public TenantRoleFloorService.TenantUser changeUserRole(
            @RequestBody @Valid TenantRoleFloorService.ChangeRoleRequest request) {
        return floor.changeRole(request);
    }

    @PostMapping("/tenant-floor/repair")
    public TenantRoleFloorService.TenantUser repairFloor(
            @RequestBody @Valid TenantRoleFloorService.RepairRequest request) {
        return floor.repair(request);
    }

    // --------------------------------------------------------- access reviews

    @GetMapping("/access-reviews")
    public List<AccessReviewService.Campaign> accessReviews() {
        return accessReviews.campaigns();
    }

    @PostMapping("/access-reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public AccessReviewService.Campaign createAccessReview(
            @RequestBody @Valid AccessReviewService.CreateRequest request) {
        return accessReviews.create(request);
    }

    @GetMapping("/access-reviews/{campaignId}/items")
    public List<AccessReviewService.Item> accessReviewItems(@PathVariable UUID campaignId) {
        return accessReviews.items(campaignId);
    }

    @PostMapping("/access-reviews/items/{itemId}/decision")
    public AccessReviewService.Item decideAccessReviewItem(
            @PathVariable UUID itemId, @RequestBody @Valid AccessReviewDecision request) {
        return accessReviews.decide(itemId,
                new AccessReviewService.DecisionRequest(request.decision(), request.note()));
    }
}

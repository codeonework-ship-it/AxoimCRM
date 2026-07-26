-- Make the governed action catalogue describe the complete grant boundary now
-- enforced by RbacAdminController. Existing deployments receive the correction
-- through Flyway instead of relying on a changed historical migration.
update security.controlled_action
   set label = 'RBAC access grant',
       description = 'Assigning a role, profile, permission set, or permission-set group to a user.'
 where action_code = 'PERMISSION_GRANT';

-- Axiom has tenant users and platform operators. The original foreign keys
-- accepted only tenant users, so a SUPER_ADMIN could open RBAC administration
-- but could not submit an approval or delegate authority. These UUIDs are
-- deliberately polymorphic, like activity.user_activity.actor_id. Tenant
-- isolation remains enforced by tenant_id and RLS, and the service validates
-- delegates against both principal tables before writing.
alter table security.approval_request
  drop constraint if exists fk_approval_initiator_same_tenant;

alter table security.approval_delegation
  drop constraint if exists fk_delegation_delegator_same_tenant,
  drop constraint if exists fk_delegation_delegate_same_tenant;

comment on column security.approval_request.initiated_by is
  'Principal id from identity.app_user or platform.platform_user. Deliberately polymorphic.';
comment on column security.approval_request.decided_by is
  'Principal id from identity.app_user or platform.platform_user. Deliberately polymorphic.';
comment on column security.approval_delegation.delegator_id is
  'Principal id from identity.app_user or platform.platform_user. Deliberately polymorphic.';
comment on column security.approval_delegation.delegate_id is
  'Principal id from identity.app_user or platform.platform_user. Validated by MakerCheckerService.';

comment on table security.approval_request is
  'Tenant-scoped maker-checker queue. RBAC grants are applied in the same transaction as the APPROVED decision.';

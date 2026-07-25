-- Axiom CRM — E02: RBAC, record sharing and segregation of duties.
--
-- Everything authorization-related lands in one new module schema, `security`,
-- because the enforcement layer is a distinct architectural concern from the
-- governance metadata already living in `governance` (audit, groups, screen
-- policy). Mixing "who may see this row" with "what did they do" makes the
-- former impossible to reason about in isolation.
--
-- FIVE ADDITIVE LAYERS (docs/product/08-rbac-and-sharing-model.md §1)
--   1 org_wide_default        the floor: private | read_only | read_write
--   2 role_node hierarchy     upward roll-up, per-object configurable
--   3 sharing_rule            criteria- or owner-based
--   4 record_share            manual / team / territory / materialized rule
--   5 field_permission        what of a visible record is visible
--
-- WHY record_share CARRIES `cause`
-- The access explainer (FR-SEC-013) has to answer "why can this user see this
-- row" without re-deriving the whole calculation. `cause` + `cause_ref` is that
-- answer, stored at the moment the grant is made. Retrofitting it would mean
-- recomputing every share in every tenant. See data model §3.3.
--
-- NOTE ON `nullif(current_setting('app.tenant_id', true), '')::uuid`
-- Repeated deliberately from V10. TenantSessionAspect uses set_config(..., true)
-- (== SET LOCAL); when that transaction ends PostgreSQL restores the placeholder
-- GUC to the EMPTY STRING, not NULL. A bare cast then raises
--   ERROR: invalid input syntax for type uuid: ""
-- on any pooled connection that previously served an authenticated request.
-- nullif() turns that into NULL, the comparison yields NULL, and the row is
-- filtered out — which is the correct outcome for an unbound connection.

create schema if not exists security;
grant usage on schema security to axiom_app;

alter role axiom_app set search_path to platform, identity, crm, sales, engagement,
  governance, reference, billing, reporting, integration, i18n, security, public;

-- ---------------------------------------------------------------------------
-- Permission catalogue — platform reference data, no tenant_id, no RLS.
-- The vocabulary of the product's own permissions. Identical for every tenant,
-- shipped by us; giving it a tenant_id would copy it per tenant for no benefit.
-- SoD conflict pairs and permission-set grants both reference these codes, so a
-- typo in a conflict declaration is a foreign-key error rather than a control
-- that silently never fires.
-- ---------------------------------------------------------------------------
create table security.permission_catalog (
  code        text primary key,
  category    text not null check (category in ('OBJECT','SYSTEM','EXPORT','ADMIN')),
  label       text not null,
  description text not null,
  created_at  timestamptz not null default now(),
  constraint permission_code_format check (code ~ '^[A-Z][A-Z0-9_]*\.[A-Z][A-Z0-9_]*$')
);

-- ---------------------------------------------------------------------------
-- Securable object registry — platform reference data.
-- Names the tables the authorization layer may build predicates against, and
-- the owner column on each. The Java-side registry (com.axiom.security.
-- SecurableObject) is authoritative at runtime; this table exists so the model
-- is inspectable in SQL and so the SoD/expiry sweeps can enumerate objects
-- without a deployment.
-- ---------------------------------------------------------------------------
create table security.securable_object (
  object_type   text primary key,
  schema_name   text not null,
  table_name    text not null,
  owner_column  text,
  parent_column text,
  parent_object text references security.securable_object(object_type),
  soft_deleted  boolean not null default false,
  role_rollup   boolean not null default true,
  created_at    timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- 1. Role hierarchy (FR-SEC-001)
--
-- Acyclic, unbounded depth, traversed by materialized path rather than a
-- recursive CTE at read time (data model §8: "hierarchy traversal via
-- materialized paths"). `path` is /ROOT/CHILD/LEAF/ — leading and trailing
-- slash so a prefix match cannot straddle a code boundary (/APAC/ never
-- prefix-matches /APAC_WEST/).
-- ---------------------------------------------------------------------------
create table security.role_node (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  code        text not null,
  name        text not null,
  description text,
  parent_id   uuid,
  path        text not null,
  depth       int not null default 0,
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  created_by  uuid,
  updated_at  timestamptz not null default now(),
  updated_by  uuid,
  unique (tenant_id, code),
  unique (tenant_id, id),
  constraint role_code_format check (code ~ '^[A-Z][A-Z0-9_]*$'),
  constraint role_not_own_parent check (parent_id is null or parent_id <> id),
  constraint fk_role_parent_same_tenant
    foreign key (tenant_id, parent_id) references security.role_node(tenant_id, id)
);

create index idx_role_node_path on security.role_node(tenant_id, path);
create index idx_role_node_parent on security.role_node(tenant_id, parent_id);

-- Cycle prevention as a save-time database constraint, not a UI convenience.
-- FR-SEC-001 requires it "regardless of entry point (UI, API, bulk import)" —
-- a trigger is the only place that holds for a direct SQL load too. The message
-- names both conflicting roles, as the on-failure clause demands.
create or replace function security.role_node_reject_cycle() returns trigger as $$
declare
  cursor_id uuid := new.parent_id;
  hops int := 0;
  conflicting text;
begin
  while cursor_id is not null loop
    if cursor_id = new.id then
      select code into conflicting from security.role_node
        where tenant_id = new.tenant_id and id = new.parent_id;
      raise exception
        'Role hierarchy cycle rejected: % cannot report to % because % is already beneath it',
        new.code, coalesce(conflicting, new.parent_id::text), coalesce(conflicting, new.parent_id::text)
        using errcode = '23514';
    end if;
    hops := hops + 1;
    if hops > 512 then
      raise exception 'Role hierarchy cycle rejected: unresolvable ancestry above %', new.code
        using errcode = '23514';
    end if;
    select parent_id into cursor_id from security.role_node
      where tenant_id = new.tenant_id and id = cursor_id;
  end loop;
  return new;
end;
$$ language plpgsql;

create trigger trg_role_node_reject_cycle
  before insert or update of parent_id on security.role_node
  for each row execute function security.role_node_reject_cycle();

-- One role per user for sharing purposes. Deliberately a separate table rather
-- than a column on identity.app_user: role is the *sharing* hierarchy and
-- manager_id is the *forecast* hierarchy (data model §3.2 — "conflating them is
-- a common and expensive modelling error"), and keeping it here also keeps the
-- E02 module from reaching into the identity module's table shape.
create table security.user_role_assignment (
  tenant_id     uuid not null references platform.tenant(id),
  user_id       uuid not null,
  role_node_id  uuid not null,
  expires_at    timestamptz,
  granted_by    uuid,
  granted_at    timestamptz not null default now(),
  primary key (tenant_id, user_id),
  constraint fk_ura_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id),
  constraint fk_ura_role_same_tenant
    foreign key (tenant_id, role_node_id) references security.role_node(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- 2. Profiles, permission sets, groups (FR-SEC-002, FR-SEC-003)
-- ---------------------------------------------------------------------------
create table security.profile (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  code            text not null,
  name            text not null,
  description     text,
  system_managed  boolean not null default false,
  export_row_limit int,                      -- null == unlimited (FR-SEC-015)
  active          boolean not null default true,
  created_at      timestamptz not null default now(),
  created_by      uuid,
  updated_at      timestamptz not null default now(),
  updated_by      uuid,
  unique (tenant_id, code),
  unique (tenant_id, id),
  constraint profile_code_format check (code ~ '^[A-Z][A-Z0-9_]*$'),
  constraint profile_export_limit_positive check (export_row_limit is null or export_row_limit > 0)
);

-- Exactly one profile per user (FR-SEC-002). The primary key is the constraint.
create table security.user_profile (
  tenant_id  uuid not null references platform.tenant(id),
  user_id    uuid not null,
  profile_id uuid not null,
  assigned_by uuid,
  assigned_at timestamptz not null default now(),
  primary key (tenant_id, user_id),
  constraint fk_user_profile_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id),
  constraint fk_user_profile_profile_same_tenant
    foreign key (tenant_id, profile_id) references security.profile(tenant_id, id)
);

create table security.permission_set (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  code        text not null,
  name        text not null,
  description text,
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  created_by  uuid,
  updated_at  timestamptz not null default now(),
  updated_by  uuid,
  unique (tenant_id, code),
  unique (tenant_id, id),
  constraint permission_set_code_format check (code ~ '^[A-Z][A-Z0-9_]*$')
);

create table security.permission_set_group (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  code        text not null,
  name        text not null,
  description text,
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  created_by  uuid,
  unique (tenant_id, code),
  unique (tenant_id, id),
  constraint permission_set_group_code_format check (code ~ '^[A-Z][A-Z0-9_]*$')
);

-- `muted_permissions` is the per-permission mute of FR-SEC-003: the set is
-- included in the group, minus these codes. Muting lives on the membership and
-- not on the set, so the same set can be muted in one bundle and not another.
create table security.permission_set_group_member (
  tenant_id         uuid not null references platform.tenant(id),
  group_id          uuid not null,
  permission_set_id uuid not null,
  muted_permissions text[] not null default '{}',
  created_at        timestamptz not null default now(),
  primary key (tenant_id, group_id, permission_set_id),
  constraint fk_psgm_group_same_tenant
    foreign key (tenant_id, group_id) references security.permission_set_group(tenant_id, id),
  constraint fk_psgm_set_same_tenant
    foreign key (tenant_id, permission_set_id) references security.permission_set(tenant_id, id)
);

-- A user assignment points at exactly one of a set or a group, and may expire
-- (FR-SEC-012). `revoked_at` keeps the row for the access-review audit trail
-- rather than deleting the evidence that the grant ever existed.
create table security.user_permission_set (
  id                      uuid primary key default gen_random_uuid(),
  tenant_id               uuid not null references platform.tenant(id),
  user_id                 uuid not null,
  permission_set_id       uuid,
  permission_set_group_id uuid,
  expires_at              timestamptz,
  granted_by              uuid,
  granted_at              timestamptz not null default now(),
  granted_reason          text,
  revoked_at              timestamptz,
  revoked_by              uuid,
  revoked_reason          text,
  constraint ups_exactly_one_target check (
    (permission_set_id is not null)::int + (permission_set_group_id is not null)::int = 1),
  constraint fk_ups_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id),
  constraint fk_ups_set_same_tenant
    foreign key (tenant_id, permission_set_id) references security.permission_set(tenant_id, id),
  constraint fk_ups_group_same_tenant
    foreign key (tenant_id, permission_set_group_id) references security.permission_set_group(tenant_id, id)
);

create index idx_ups_user on security.user_permission_set(tenant_id, user_id) where revoked_at is null;
create index idx_ups_expiry on security.user_permission_set(tenant_id, expires_at) where revoked_at is null and expires_at is not null;

-- Object permissions per holder (a profile OR a permission set).
create table security.object_permission (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  holder_type text not null check (holder_type in ('PROFILE','PERMISSION_SET')),
  holder_id   uuid not null,
  object_type text not null references security.securable_object(object_type),
  can_create  boolean not null default false,
  can_read    boolean not null default false,
  can_edit    boolean not null default false,
  can_delete  boolean not null default false,
  view_all    boolean not null default false,
  modify_all  boolean not null default false,
  can_export  boolean not null default false,   -- FR-SEC-015: distinct from read
  can_reveal  boolean not null default false,   -- FR-SEC-008: full reveal of masked fields
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (tenant_id, holder_type, holder_id, object_type),
  -- Additive-only semantics: a wider flag implies the narrower one, so a holder
  -- can never be configured into a state the effective-permission union cannot
  -- express (view_all without read would be silently meaningless).
  constraint objperm_view_all_implies_read check ((not view_all) or can_read),
  constraint objperm_modify_all_implies_edit check ((not modify_all) or can_edit),
  constraint objperm_edit_implies_read check ((not can_edit) or can_read),
  constraint objperm_delete_implies_read check ((not can_delete) or can_read),
  constraint objperm_export_implies_read check ((not can_export) or can_read)
);

create index idx_object_permission_holder on security.object_permission(tenant_id, holder_type, holder_id);

-- Field permissions per holder per field (FR-SEC-007).
create table security.field_permission (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  holder_type text not null check (holder_type in ('PROFILE','PERMISSION_SET')),
  holder_id   uuid not null,
  object_type text not null references security.securable_object(object_type),
  field_name  text not null,
  readable    boolean not null default true,
  editable    boolean not null default false,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (tenant_id, holder_type, holder_id, object_type, field_name),
  constraint fieldperm_editable_implies_readable check ((not editable) or readable)
);

create index idx_field_permission_holder on security.field_permission(tenant_id, holder_type, holder_id, object_type);

-- System / administrative permissions held by a profile or set. Object-level
-- rights are derived into the same code space (ACCOUNT.DELETE and friends) so
-- SoD can compare apples with apples.
create table security.holder_permission (
  tenant_id       uuid not null references platform.tenant(id),
  holder_type     text not null check (holder_type in ('PROFILE','PERMISSION_SET')),
  holder_id       uuid not null,
  permission_code text not null references security.permission_catalog(code),
  created_at      timestamptz not null default now(),
  primary key (tenant_id, holder_type, holder_id, permission_code)
);

-- ---------------------------------------------------------------------------
-- 3. Org-wide defaults (FR-SEC-004) and sharing rules (FR-SEC-005)
-- ---------------------------------------------------------------------------
create table security.org_wide_default (
  tenant_id      uuid not null references platform.tenant(id),
  object_type    text not null references security.securable_object(object_type),
  default_access text not null check (default_access in ('private','read_only','read_write')),
  -- FR-SEC-001 "configurable per object": CASE_RECORD-style objects can opt out
  -- of upward roll-up so a manager does not inherit every ticket their team
  -- touches.
  role_hierarchy_rollup boolean not null default true,
  updated_at     timestamptz not null default now(),
  updated_by     uuid,
  primary key (tenant_id, object_type)
);

create table security.sharing_rule (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  code              text not null,
  name              text not null,
  object_type       text not null references security.securable_object(object_type),
  rule_type         text not null check (rule_type in ('CRITERIA','OWNER')),
  -- CRITERIA
  criteria_field    text,
  criteria_operator text check (criteria_operator in ('EQUALS','NOT_EQUALS','CONTAINS','STARTS_WITH','IN')),
  criteria_value    text,
  -- OWNER: records whose owner sits in this role branch / group / territory
  source_type       text check (source_type in ('ROLE','GROUP','TERRITORY')),
  source_id         uuid,
  -- Grantee
  target_type       text not null check (target_type in ('ROLE','GROUP','TERRITORY','USER')),
  target_id         uuid not null,
  -- Role targets include the branch beneath them unless this is false.
  target_includes_subordinates boolean not null default true,
  access_level      text not null check (access_level in ('READ','READ_WRITE')),
  active            boolean not null default false,
  created_at        timestamptz not null default now(),
  created_by        uuid,
  activated_at      timestamptz,
  activated_by      uuid,
  last_recomputed_at timestamptz,
  unique (tenant_id, code),
  unique (tenant_id, id),
  constraint sharing_rule_code_format check (code ~ '^[A-Z][A-Z0-9_]*$'),
  constraint sharing_rule_criteria_complete check (
    rule_type <> 'CRITERIA' or (criteria_field is not null and criteria_operator is not null and criteria_value is not null)),
  constraint sharing_rule_owner_complete check (
    rule_type <> 'OWNER' or (source_type is not null and source_id is not null))
);

create index idx_sharing_rule_active on security.sharing_rule(tenant_id, object_type, active);

-- ---------------------------------------------------------------------------
-- 4. Materialized record shares (FR-SEC-006, and the explainer's evidence)
--
-- `cause` is the whole point of this table. Without it the explainer would have
-- to re-derive the entire five-layer calculation per question.
-- ---------------------------------------------------------------------------
create table security.record_share (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  object_type     text not null references security.securable_object(object_type),
  record_id       uuid not null,
  grantee_user_id uuid not null,
  access_level    text not null check (access_level in ('READ','READ_WRITE')),
  cause           text not null check (cause in
                    ('owner','role_hierarchy','sharing_rule','team','territory','manual')),
  cause_ref       text,
  cause_detail    text,
  expires_at      timestamptz,
  created_at      timestamptz not null default now(),
  created_by      uuid,
  revoked_at      timestamptz,
  revoked_by      uuid,
  constraint fk_record_share_grantee_same_tenant
    foreign key (tenant_id, grantee_user_id) references identity.app_user(tenant_id, id)
);

-- One live grant per (record, grantee, cause, cause_ref): recomputation is then
-- an idempotent upsert rather than a delete-and-reinsert that would blink access
-- off mid-transaction.
create unique index uq_record_share_live on security.record_share
  (tenant_id, object_type, record_id, grantee_user_id, cause, coalesce(cause_ref, ''))
  where revoked_at is null;

create index idx_record_share_lookup on security.record_share
  (tenant_id, object_type, record_id, grantee_user_id) where revoked_at is null;
create index idx_record_share_grantee on security.record_share
  (tenant_id, grantee_user_id, object_type) where revoked_at is null;
create index idx_record_share_expiry on security.record_share
  (tenant_id, expires_at) where revoked_at is null and expires_at is not null;

-- Record teams (FR-SEC-006 "team"): an explicit standing roster on one record.
create table security.record_team_member (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  object_type  text not null references security.securable_object(object_type),
  record_id    uuid not null,
  user_id      uuid not null,
  team_role    text not null default 'MEMBER',
  access_level text not null check (access_level in ('READ','READ_WRITE')),
  expires_at   timestamptz,
  created_at   timestamptz not null default now(),
  created_by   uuid,
  unique (tenant_id, object_type, record_id, user_id),
  constraint fk_team_member_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

-- Public groups of users. governance.crm_group already exists but its member
-- table constrains entity_type to ACCOUNT/CONTACT/LEAD, so it cannot hold users;
-- widening that constraint would reach into another module's schema for a
-- different purpose. A dedicated user group is the smaller change.
create table security.user_group (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  code        text not null,
  name        text not null,
  description text,
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  created_by  uuid,
  unique (tenant_id, code),
  unique (tenant_id, id),
  constraint user_group_code_format check (code ~ '^[A-Z][A-Z0-9_]*$')
);

create table security.user_group_member (
  tenant_id  uuid not null references platform.tenant(id),
  group_id   uuid not null,
  user_id    uuid not null,
  created_at timestamptz not null default now(),
  primary key (tenant_id, group_id, user_id),
  constraint fk_user_group_member_group_same_tenant
    foreign key (tenant_id, group_id) references security.user_group(tenant_id, id),
  constraint fk_user_group_member_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

-- Territories. E03/E13 own the full territory model with assignment rules and
-- model versions; this is the minimum needed for `territory` to be a real
-- sharing cause rather than an enum value nothing can produce.
create table security.territory (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null references platform.tenant(id),
  code       text not null,
  name       text not null,
  active     boolean not null default true,
  created_at timestamptz not null default now(),
  unique (tenant_id, code),
  unique (tenant_id, id),
  constraint territory_code_format check (code ~ '^[A-Z][A-Z0-9_]*$')
);

create table security.territory_member (
  tenant_id    uuid not null references platform.tenant(id),
  territory_id uuid not null,
  user_id      uuid not null,
  created_at   timestamptz not null default now(),
  primary key (tenant_id, territory_id, user_id),
  constraint fk_territory_member_territory_same_tenant
    foreign key (tenant_id, territory_id) references security.territory(tenant_id, id),
  constraint fk_territory_member_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

create table security.record_territory (
  tenant_id    uuid not null references platform.tenant(id),
  object_type  text not null references security.securable_object(object_type),
  record_id    uuid not null,
  territory_id uuid not null,
  created_at   timestamptz not null default now(),
  primary key (tenant_id, object_type, record_id, territory_id),
  constraint fk_record_territory_territory_same_tenant
    foreign key (tenant_id, territory_id) references security.territory(tenant_id, id)
);

-- Recompute jobs. Data model §8: "Recomputation on role or rule change must be
-- incremental and asynchronous, with progress visible; a full rebuild must
-- never block business writes." This table is where that progress is visible.
create table security.sharing_recompute_job (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  object_type     text not null references security.securable_object(object_type),
  scope           text not null check (scope in ('RULE','RECORD','OWNER_CHANGE','ROLE_MEMBERSHIP','FULL')),
  scope_ref       uuid,
  trigger_reason  text not null,
  status          text not null default 'QUEUED' check (status in ('QUEUED','RUNNING','COMPLETE','FAILED')),
  total_units     int not null default 0,
  processed_units int not null default 0,
  shares_written  int not null default 0,
  shares_revoked  int not null default 0,
  queued_at       timestamptz not null default now(),
  started_at      timestamptz,
  finished_at     timestamptz,
  message         text,
  requested_by    uuid
);

create index idx_recompute_job_pending on security.sharing_recompute_job(tenant_id, status, queued_at);

-- ---------------------------------------------------------------------------
-- 5. Field-level security support: sensitive registry + read audit
-- ---------------------------------------------------------------------------
create table security.sensitive_field (
  tenant_id          uuid not null references platform.tenant(id),
  object_type        text not null references security.securable_object(object_type),
  field_name         text not null,
  mask_style         text not null check (mask_style in ('LAST4','FIRST_LAST','EMAIL','FULL')),
  reveal_permission  text not null references security.permission_catalog(code),
  reason             text,
  created_at         timestamptz not null default now(),
  primary key (tenant_id, object_type, field_name)
);

-- Append-only. FR-SEC-008: every full reveal produces a read-audit event.
create table security.field_read_audit (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  actor_id      uuid not null,
  actor_email   text not null,
  object_type   text not null,
  record_id     uuid not null,
  field_name    text not null,
  access_kind   text not null check (access_kind in ('REVEAL','MASKED','DENIED')),
  correlation_id text,
  occurred_at   timestamptz not null default now()
);

create index idx_field_read_audit_record on security.field_read_audit
  (tenant_id, object_type, record_id, occurred_at desc);
create index idx_field_read_audit_actor on security.field_read_audit(tenant_id, actor_id, occurred_at desc);

-- ---------------------------------------------------------------------------
-- 6. Segregation of duties (FR-SEC-009)
-- ---------------------------------------------------------------------------
create table security.sod_conflict (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  code         text not null,
  name         text not null,
  permission_a text not null references security.permission_catalog(code),
  permission_b text not null references security.permission_catalog(code),
  severity     text not null default 'HIGH' check (severity in ('LOW','MEDIUM','HIGH','CRITICAL')),
  rationale    text,
  active       boolean not null default true,
  created_at   timestamptz not null default now(),
  created_by   uuid,
  unique (tenant_id, code),
  unique (tenant_id, id),
  -- The pair is unordered: declaring (A,B) and (B,A) as two conflicts would
  -- make the sweep report every violation twice.
  constraint sod_pair_ordered check (permission_a < permission_b),
  constraint sod_conflict_code_format check (code ~ '^[A-Z][A-Z0-9_]*$')
);

create unique index uq_sod_conflict_pair on security.sod_conflict(tenant_id, permission_a, permission_b);

-- Findings from the scheduled sweep. FR-SEC-009: "existing violations are
-- reported rather than silently tolerated" — a conflict declared today may
-- already be violated by a grant made last year, and those have to surface as
-- remediable findings rather than be grandfathered in.
create table security.sod_finding (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  conflict_id    uuid not null,
  user_id        uuid not null,
  holder_a       text not null,
  holder_b       text not null,
  status         text not null default 'OPEN' check (status in ('OPEN','ACKNOWLEDGED','REMEDIATED','ACCEPTED')),
  detected_at    timestamptz not null default now(),
  resolved_at    timestamptz,
  resolved_by    uuid,
  resolution_note text,
  constraint fk_sod_finding_conflict_same_tenant
    foreign key (tenant_id, conflict_id) references security.sod_conflict(tenant_id, id),
  constraint fk_sod_finding_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

create unique index uq_sod_finding_open on security.sod_finding(tenant_id, conflict_id, user_id)
  where status = 'OPEN';

-- ---------------------------------------------------------------------------
-- 7. Maker-checker (FR-SEC-010) and delegation
-- ---------------------------------------------------------------------------
create table security.controlled_action (
  action_code  text primary key,
  label        text not null,
  description  text not null,
  created_at   timestamptz not null default now(),
  constraint controlled_action_format check (action_code ~ '^[A-Z][A-Z0-9_]*$')
);

create table security.approval_request (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  action_code   text not null references security.controlled_action(action_code),
  entity_type   text not null,
  entity_id     uuid,
  summary       text not null,
  payload       jsonb not null default '{}'::jsonb,
  initiated_by  uuid not null,
  initiated_at  timestamptz not null default now(),
  status        text not null default 'PENDING' check (status in ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
  decided_by    uuid,
  decided_at    timestamptz,
  decision_note text,
  constraint fk_approval_initiator_same_tenant
    foreign key (tenant_id, initiated_by) references identity.app_user(tenant_id, id),
  -- The database says it too, not only the service: the initiator can never be
  -- the approver. Belt and braces on the product's most consequential control.
  constraint approval_initiator_is_not_approver check (decided_by is null or decided_by <> initiated_by)
);

create index idx_approval_pending on security.approval_request(tenant_id, status, initiated_at);

-- Approval delegation. FR-SEC-010 applies transitively through this table: if
-- the initiator is a delegate of the would-be approver, approval is refused.
-- Without transitivity, delegation is a one-hop bypass of the whole control.
create table security.approval_delegation (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  delegator_id uuid not null,
  delegate_id  uuid not null,
  starts_at    timestamptz not null default now(),
  expires_at   timestamptz,
  active       boolean not null default true,
  created_at   timestamptz not null default now(),
  created_by   uuid,
  constraint delegation_not_self check (delegator_id <> delegate_id),
  constraint fk_delegation_delegator_same_tenant
    foreign key (tenant_id, delegator_id) references identity.app_user(tenant_id, id),
  constraint fk_delegation_delegate_same_tenant
    foreign key (tenant_id, delegate_id) references identity.app_user(tenant_id, id)
);

create index idx_delegation_active on security.approval_delegation(tenant_id, delegator_id) where active = true;

-- ---------------------------------------------------------------------------
-- 8. Delegated administration (FR-SEC-011)
-- ---------------------------------------------------------------------------
create table security.delegated_admin_scope (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  user_id          uuid not null,
  role_node_id     uuid not null,
  can_manage_users boolean not null default true,
  can_manage_roles boolean not null default false,
  can_manage_config boolean not null default false,
  expires_at       timestamptz,
  created_at       timestamptz not null default now(),
  created_by       uuid,
  unique (tenant_id, user_id, role_node_id),
  constraint fk_das_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id),
  constraint fk_das_role_same_tenant
    foreign key (tenant_id, role_node_id) references security.role_node(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- 9. Access recertification (FR-SEC-014)
-- ---------------------------------------------------------------------------
create table security.access_review_campaign (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  code         text not null,
  name         text not null,
  scope_note   text,
  deadline_at  timestamptz not null,
  status       text not null default 'OPEN' check (status in ('DRAFT','OPEN','CLOSED')),
  created_at   timestamptz not null default now(),
  created_by   uuid,
  closed_at    timestamptz,
  closed_by    uuid,
  unique (tenant_id, code),
  unique (tenant_id, id),
  constraint campaign_code_format check (code ~ '^[A-Z][A-Z0-9_]*$')
);

create table security.access_review_item (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  campaign_id   uuid not null,
  grant_type    text not null check (grant_type in ('PERMISSION_SET','RECORD_SHARE','ROLE','DELEGATED_ADMIN')),
  grant_ref     uuid not null,
  subject_user_id uuid not null,
  description   text not null,
  reviewer_id   uuid,
  decision      text not null default 'PENDING'
                  check (decision in ('PENDING','CONFIRMED','REVOKED','AUTO_REVOKED')),
  decided_by    uuid,
  decided_at    timestamptz,
  note          text,
  created_at    timestamptz not null default now(),
  constraint fk_review_item_campaign_same_tenant
    foreign key (tenant_id, campaign_id) references security.access_review_campaign(tenant_id, id),
  constraint fk_review_item_subject_same_tenant
    foreign key (tenant_id, subject_user_id) references identity.app_user(tenant_id, id)
);

create index idx_review_item_campaign on security.access_review_item(tenant_id, campaign_id, decision);

-- ---------------------------------------------------------------------------
-- 10. Export as a distinct permission (FR-SEC-015)
-- ---------------------------------------------------------------------------
create table security.export_event (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  actor_id       uuid not null,
  actor_email    text not null,
  object_type    text not null,
  format         text not null,
  filter_criteria text not null default '',
  row_count      int not null,
  destination    text not null default 'DOWNLOAD',
  approval_id    uuid,
  correlation_id text,
  occurred_at    timestamptz not null default now()
);

create index idx_export_event_actor on security.export_event(tenant_id, actor_id, occurred_at desc);

-- Append-only history of every grant and revocation (data model §3.3
-- ACCESS_GRANT_LOG). Kept separate from governance.audit_event so a compliance
-- reviewer can read the access story without filtering the whole audit stream.
create table security.access_grant_log (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  subject_user_id uuid,
  grant_type    text not null,
  grant_ref     uuid,
  operation     text not null check (operation in ('GRANT','REVOKE','MODIFY','EXPIRE','AUTO_REVOKE')),
  before_state  jsonb,
  after_state   jsonb,
  actor_id      uuid not null,
  actor_email   text not null,
  approver_id   uuid,
  approver_email text,
  reason        text,
  correlation_id text,
  occurred_at   timestamptz not null default now()
);

create index idx_access_grant_log_subject on security.access_grant_log(tenant_id, subject_user_id, occurred_at desc);

-- ---------------------------------------------------------------------------
-- Business-object columns E02 needs
--
-- crm.account.tax_id exists so sensitive-field masking (FR-SEC-008) has a real
-- field to protect. The model document names "a client's tax ID" as the
-- motivating case; demonstrating masking on a synthetic column nobody stores
-- would prove nothing about the enforcement point.
-- ---------------------------------------------------------------------------
alter table crm.account add column if not exists tax_id text;

-- ---------------------------------------------------------------------------
-- Row-level security. Every tenant-scoped table above, matching V1/V6/V10.
-- ---------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array[
    'security.role_node','security.user_role_assignment','security.profile',
    'security.user_profile','security.permission_set','security.permission_set_group',
    'security.permission_set_group_member','security.user_permission_set',
    'security.object_permission','security.field_permission','security.holder_permission',
    'security.org_wide_default','security.sharing_rule','security.record_share',
    'security.record_team_member','security.user_group','security.user_group_member',
    'security.territory','security.territory_member',
    'security.record_territory','security.sharing_recompute_job','security.sensitive_field',
    'security.field_read_audit','security.sod_conflict','security.sod_finding',
    'security.approval_request','security.approval_delegation','security.delegated_admin_scope',
    'security.access_review_campaign','security.access_review_item','security.export_event',
    'security.access_grant_log'
  ]
  loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format(
      'create policy tenant_isolation on %s '
      'using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
      t);
  end loop;
end
$$;

-- Grants. Append-only tables get insert but not update or delete: an audit row
-- the application can rewrite is not an audit row.
grant select, insert, update, delete on
  security.role_node, security.user_role_assignment, security.profile,
  security.user_profile, security.permission_set, security.permission_set_group,
  security.permission_set_group_member, security.user_permission_set,
  security.object_permission, security.field_permission, security.holder_permission,
  security.org_wide_default, security.sharing_rule, security.record_share,
  security.record_team_member, security.user_group, security.user_group_member,
  security.territory, security.territory_member,
  security.record_territory, security.sharing_recompute_job, security.sensitive_field,
  security.sod_conflict, security.sod_finding, security.approval_request,
  security.approval_delegation, security.delegated_admin_scope,
  security.access_review_campaign, security.access_review_item
  to axiom_app;

grant select, insert on
  security.field_read_audit, security.export_event, security.access_grant_log
  to axiom_app;

grant select on security.permission_catalog, security.securable_object, security.controlled_action
  to axiom_app;

-- ---------------------------------------------------------------------------
-- Reference data: securable objects
-- ---------------------------------------------------------------------------
insert into security.securable_object
  (object_type, schema_name, table_name, owner_column, parent_column, parent_object, soft_deleted, role_rollup) values
  ('ACCOUNT',     'crm',   'account',     'owner_id', null,         null,       true,  true),
  ('LEAD',        'crm',   'lead',        'owner_id', null,         null,       true,  true),
  ('OPPORTUNITY', 'sales', 'opportunity', 'owner_id', 'account_id', 'ACCOUNT',  false, true),
  ('CONTACT',     'crm',   'contact',     null,       'account_id', 'ACCOUNT',  true,  true);

-- CONTACT has no owner column: a contact's visibility follows its account, which
-- is both the correct CRM semantic and the honest description of the schema. It
-- is recorded here rather than special-cased in code.

-- ---------------------------------------------------------------------------
-- Reference data: permission catalogue
-- ---------------------------------------------------------------------------
insert into security.permission_catalog(code, category, label, description) values
  ('ACCOUNT.CREATE','OBJECT','Create accounts','Create account records.'),
  ('ACCOUNT.READ','OBJECT','Read accounts','Read account records within sharing.'),
  ('ACCOUNT.EDIT','OBJECT','Edit accounts','Edit account records within sharing.'),
  ('ACCOUNT.DELETE','OBJECT','Delete accounts','Soft-delete account records.'),
  ('ACCOUNT.VIEW_ALL','OBJECT','View all accounts','Read every account regardless of sharing.'),
  ('ACCOUNT.MODIFY_ALL','OBJECT','Modify all accounts','Edit every account regardless of sharing.'),
  ('ACCOUNT.EXPORT','EXPORT','Export accounts','Export or print account records.'),
  ('ACCOUNT.REVEAL','OBJECT','Reveal account sensitive fields','See the full value of masked account fields.'),
  ('CONTACT.CREATE','OBJECT','Create contacts','Create contact records.'),
  ('CONTACT.READ','OBJECT','Read contacts','Read contact records within sharing.'),
  ('CONTACT.EDIT','OBJECT','Edit contacts','Edit contact records within sharing.'),
  ('CONTACT.DELETE','OBJECT','Delete contacts','Soft-delete contact records.'),
  ('CONTACT.VIEW_ALL','OBJECT','View all contacts','Read every contact regardless of sharing.'),
  ('CONTACT.MODIFY_ALL','OBJECT','Modify all contacts','Edit every contact regardless of sharing.'),
  ('CONTACT.EXPORT','EXPORT','Export contacts','Export or print contact records.'),
  ('CONTACT.REVEAL','OBJECT','Reveal contact sensitive fields','See the full value of masked contact fields.'),
  ('LEAD.CREATE','OBJECT','Create leads','Create lead records.'),
  ('LEAD.READ','OBJECT','Read leads','Read lead records within sharing.'),
  ('LEAD.EDIT','OBJECT','Edit leads','Edit lead records within sharing.'),
  ('LEAD.DELETE','OBJECT','Delete leads','Soft-delete lead records.'),
  ('LEAD.VIEW_ALL','OBJECT','View all leads','Read every lead regardless of sharing.'),
  ('LEAD.MODIFY_ALL','OBJECT','Modify all leads','Edit every lead regardless of sharing.'),
  ('LEAD.EXPORT','EXPORT','Export leads','Export or print lead records.'),
  ('LEAD.REVEAL','OBJECT','Reveal lead sensitive fields','See the full value of masked lead fields.'),
  ('OPPORTUNITY.CREATE','OBJECT','Create opportunities','Create opportunity records.'),
  ('OPPORTUNITY.READ','OBJECT','Read opportunities','Read opportunity records within sharing.'),
  ('OPPORTUNITY.EDIT','OBJECT','Edit opportunities','Edit opportunity records within sharing.'),
  ('OPPORTUNITY.DELETE','OBJECT','Delete opportunities','Soft-delete opportunity records.'),
  ('OPPORTUNITY.VIEW_ALL','OBJECT','View all opportunities','Read every opportunity regardless of sharing.'),
  ('OPPORTUNITY.MODIFY_ALL','OBJECT','Modify all opportunities','Edit every opportunity regardless of sharing.'),
  ('OPPORTUNITY.EXPORT','EXPORT','Export opportunities','Export or print opportunity records.'),
  ('OPPORTUNITY.REVEAL','OBJECT','Reveal opportunity sensitive fields','See the full value of masked opportunity fields.'),
  ('SYS.MANAGE_USERS','ADMIN','Manage users','Create, deactivate and reassign tenant users.'),
  ('SYS.MANAGE_ROLES','ADMIN','Manage roles','Edit the role hierarchy and role assignments.'),
  ('SYS.MANAGE_PROFILES','ADMIN','Manage profiles','Edit profiles, permission sets and field permissions.'),
  ('SYS.MANAGE_SHARING','ADMIN','Manage sharing','Define org-wide defaults, sharing rules and manual shares.'),
  ('SYS.MANAGE_SOD','ADMIN','Manage segregation of duties','Declare and retire conflicting permission pairs.'),
  ('SYS.VIEW_ACCESS_EXPLAINER','ADMIN','Explain access','Inspect why a user can or cannot see a record.'),
  ('SYS.RUN_ACCESS_REVIEW','ADMIN','Run access reviews','Create and decide recertification campaigns.'),
  ('SYS.APPROVE_PERMISSION_GRANT','SYSTEM','Approve permission grants','Approve a pending permission grant.'),
  ('SYS.SUBMIT_DISCOUNT','SYSTEM','Submit discount','Submit a discount for approval.'),
  ('SYS.APPROVE_DISCOUNT','SYSTEM','Approve discount','Approve a submitted discount.'),
  ('SYS.CREATE_VENDOR','SYSTEM','Create vendor','Create a vendor or counterparty record.'),
  ('SYS.APPROVE_VENDOR_PAYMENT','SYSTEM','Approve vendor payment','Approve a payment to a vendor.'),
  ('SYS.BULK_EXPORT','EXPORT','Bulk export','Export a whole master file or report.');

insert into security.controlled_action(action_code, label, description) values
  ('PERMISSION_GRANT','Permission grant','Assigning a permission set or profile to a user.'),
  ('DISCOUNT_APPROVAL','Discount approval','Approving a non-standard discount on a deal.'),
  ('MASTER_DATA_CHANGE','Master data change','Changing governed master or reference data.'),
  ('HIGH_VOLUME_EXPORT','High-volume export','An export above the configured row threshold.'),
  ('SHARING_RULE_ACTIVATION','Sharing rule activation','Activating a rule that widens record visibility.'),
  ('TENANT_TERMINATION','Tenant termination','Terminating a tenant workspace.');

-- ---------------------------------------------------------------------------
-- Per-tenant seed: roles, profiles, org-wide defaults, sensitive fields.
--
-- Seeded for EVERY existing tenant so no tenant is left with an empty
-- authorization model, which would be indistinguishable from deny-everything.
-- ---------------------------------------------------------------------------

-- Roles: EXEC > SALES_LEADERSHIP > {APAC_WEST, APAC_EAST}, plus OPERATIONS.
insert into security.role_node(tenant_id, code, name, description, parent_id, path, depth)
select t.id, 'EXEC', 'Executive', 'Tenant leadership; sees the whole book of business.',
       null, '/EXEC/', 0
from platform.tenant t;

insert into security.role_node(tenant_id, code, name, description, parent_id, path, depth)
select t.id, 'SALES_LEADERSHIP', 'Sales leadership', 'Sales management; rolls up both field teams.',
       p.id, '/EXEC/SALES_LEADERSHIP/', 1
from platform.tenant t
join security.role_node p on p.tenant_id = t.id and p.code = 'EXEC';

insert into security.role_node(tenant_id, code, name, description, parent_id, path, depth)
select t.id, 'APAC_WEST', 'Field team — West', 'Field sellers, western territory.',
       p.id, '/EXEC/SALES_LEADERSHIP/APAC_WEST/', 2
from platform.tenant t
join security.role_node p on p.tenant_id = t.id and p.code = 'SALES_LEADERSHIP';

insert into security.role_node(tenant_id, code, name, description, parent_id, path, depth)
select t.id, 'APAC_EAST', 'Field team — East', 'Field sellers, eastern territory.',
       p.id, '/EXEC/SALES_LEADERSHIP/APAC_EAST/', 2
from platform.tenant t
join security.role_node p on p.tenant_id = t.id and p.code = 'SALES_LEADERSHIP';

insert into security.role_node(tenant_id, code, name, description, parent_id, path, depth)
select t.id, 'OPERATIONS_TEAM', 'Operations', 'Revenue operations and data stewardship.',
       p.id, '/EXEC/OPERATIONS_TEAM/', 1
from platform.tenant t
join security.role_node p on p.tenant_id = t.id and p.code = 'EXEC';

-- Profiles: one per CRM role code, so every existing user has a baseline the
-- moment this migration lands. The codes match com.axiom.auth.CrmRole, which is
-- what lets AuthorizationService fall back deterministically for a user who has
-- no explicit profile assignment yet.
insert into security.profile(tenant_id, code, name, description, system_managed, export_row_limit)
select t.id, r.code, r.name, r.description, true, r.row_limit
from platform.tenant t
cross join (values
  ('TENANT_ADMIN','Tenant administrator','Full administration inside one tenant.', null::int),
  ('SALES_MANAGER','Sales manager','Team pipeline, forecast and coaching.', 25000),
  ('SALES','Sales representative','Owned book of business.', 2000),
  ('MARKETING','Marketing','Campaign and lead operations.', 5000),
  ('SERVICE','Service','Cases, accounts and contacts.', 2000),
  ('OPERATIONS','Operations','Revenue operations and governed reporting.', 25000),
  ('FINANCE','Finance','Commercial and financial review.', 25000),
  ('DATA_STEWARD','Data steward','Governed master-data lifecycle.', null),
  ('AUDITOR','Auditor','Read-only audit and reporting.', 5000),
  ('INTEGRATION','Integration','Non-interactive API identity.', 1000)
) as r(code, name, description, row_limit);

-- Object permissions per profile. Deny by default: a profile gets nothing it is
-- not listed for here.
insert into security.object_permission
  (tenant_id, holder_type, holder_id, object_type,
   can_create, can_read, can_edit, can_delete, view_all, modify_all, can_export, can_reveal)
select p.tenant_id, 'PROFILE', p.id, o.object_type,
       g.can_create, g.can_read, g.can_edit, g.can_delete, g.view_all, g.modify_all, g.can_export, g.can_reveal
from security.profile p
cross join security.securable_object o
join (values
  -- profile_code,      create, read, edit, delete, view_all, modify_all, export, reveal
  ('TENANT_ADMIN',      true,  true, true, true,  true,  true,  true,  true),
  ('SALES_MANAGER',     true,  true, true, false, false, false, true,  false),
  ('SALES',             true,  true, true, false, false, false, false, false),
  ('MARKETING',         true,  true, true, false, false, false, true,  false),
  ('SERVICE',           true,  true, true, false, false, false, false, false),
  ('OPERATIONS',        true,  true, true, false, true,  false, true,  false),
  ('FINANCE',           false, true, false,false, true,  false, true,  false),
  ('DATA_STEWARD',      true,  true, true, true,  true,  false, true,  false),
  ('AUDITOR',           false, true, false,false, true,  false, true,  false),
  ('INTEGRATION',       true,  true, true, false, false, false, false, false)
) as g(profile_code, can_create, can_read, can_edit, can_delete, view_all, modify_all, can_export, can_reveal)
  on g.profile_code = p.code;

-- Administrative permissions per profile.
insert into security.holder_permission(tenant_id, holder_type, holder_id, permission_code)
select p.tenant_id, 'PROFILE', p.id, c.code
from security.profile p
join (values
  ('TENANT_ADMIN','SYS.MANAGE_USERS'),
  ('TENANT_ADMIN','SYS.MANAGE_ROLES'),
  ('TENANT_ADMIN','SYS.MANAGE_PROFILES'),
  ('TENANT_ADMIN','SYS.MANAGE_SHARING'),
  ('TENANT_ADMIN','SYS.MANAGE_SOD'),
  ('TENANT_ADMIN','SYS.VIEW_ACCESS_EXPLAINER'),
  ('TENANT_ADMIN','SYS.RUN_ACCESS_REVIEW'),
  ('TENANT_ADMIN','SYS.APPROVE_PERMISSION_GRANT'),
  ('TENANT_ADMIN','SYS.BULK_EXPORT'),
  ('DATA_STEWARD','SYS.BULK_EXPORT'),
  ('OPERATIONS','SYS.BULK_EXPORT'),
  ('AUDITOR','SYS.VIEW_ACCESS_EXPLAINER'),
  ('SALES_MANAGER','SYS.APPROVE_DISCOUNT'),
  ('SALES','SYS.SUBMIT_DISCOUNT'),
  ('FINANCE','SYS.APPROVE_VENDOR_PAYMENT')
) as c(profile_code, code) on c.profile_code = p.code;

-- Field-level security: the SALES profile cannot read an account's industry.
-- That is the FR-SEC-007 "absent, not null" case on a real field the UI shows,
-- rather than a synthetic example.
--
-- taxId is deliberately NOT denied to SALES: it is a *sensitive* field, so a
-- seller sees the masked form (FR-SEC-008) while SERVICE is denied the field
-- outright — the two mechanisms are independent and both need to be visible.
insert into security.field_permission
  (tenant_id, holder_type, holder_id, object_type, field_name, readable, editable)
select p.tenant_id, 'PROFILE', p.id, f.object_type, f.field_name, false, false
from security.profile p
join (values
  ('SALES','ACCOUNT','industry'),
  ('SERVICE','ACCOUNT','taxId')
) as f(profile_code, object_type, field_name) on f.profile_code = p.code;

-- Org-wide defaults. Private is the baseline the model document requires:
-- "Every layer is additive from a private baseline."
insert into security.org_wide_default(tenant_id, object_type, default_access, role_hierarchy_rollup)
select t.id, o.object_type, 'private', o.role_rollup
from platform.tenant t
cross join security.securable_object o;

-- Sensitive fields. Account tax id masks to its last four characters.
insert into security.sensitive_field
  (tenant_id, object_type, field_name, mask_style, reveal_permission, reason)
select t.id, 'ACCOUNT', 'taxId', 'LAST4', 'ACCOUNT.REVEAL',
       'Tax registration identifier; partial display is sufficient for record matching.'
from platform.tenant t;

-- Assign every existing user the profile matching their CRM role, and place
-- them in the role hierarchy: administrators at EXEC, managers at
-- SALES_LEADERSHIP, everyone else split across the two field teams so the
-- hierarchy has both an upward roll-up and a sibling boundary to demonstrate.
insert into security.user_profile(tenant_id, user_id, profile_id)
select u.tenant_id, u.id, p.id
from identity.app_user u
join security.profile p on p.tenant_id = u.tenant_id and p.code = u.role;

insert into security.user_role_assignment(tenant_id, user_id, role_node_id)
select u.tenant_id, u.id, r.id
from identity.app_user u
join security.role_node r on r.tenant_id = u.tenant_id and r.code = case
  when u.role in ('TENANT_ADMIN') then 'EXEC'
  when u.role in ('SALES_MANAGER','FINANCE','AUDITOR') then 'SALES_LEADERSHIP'
  when u.role in ('OPERATIONS','DATA_STEWARD','INTEGRATION') then 'OPERATIONS_TEAM'
  -- Deterministic split of field sellers by email so the demo tenant has one
  -- seller in each sibling team: siblings must NOT see each other's records.
  when u.email like 'priya%' or u.email like 'ana%' or u.email like 'chen%' then 'APAC_WEST'
  else 'APAC_EAST'
end;

-- Owner shares are not materialized (see AuthorizationService: ownership is an
-- O(1) column comparison, so writing a share row per record per owner would be
-- pure write amplification). Territories, teams and rules are.
insert into security.territory(tenant_id, code, name)
select t.id, 'APAC_DEALS', 'APAC deals' from platform.tenant t;

-- Demo tax ids so masking has something to mask.
update crm.account set tax_id = 'GSTIN' || upper(substr(md5(id::text), 1, 8))
where tax_id is null;

-- ---------------------------------------------------------------------------
-- Module catalogue registration (governance.module_table_catalog, V6)
-- ---------------------------------------------------------------------------
insert into governance.module_catalog(module_code, schema_name, display_name, description, owner_role) values
  ('SECURITY', 'security', 'Authorization', 'Roles, profiles, permission sets, sharing, segregation of duties and access review.', 'TENANT_ADMIN')
on conflict (module_code) do nothing;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('security','permission_catalog','SECURITY','code',false,'ACTIVE'),
  ('security','securable_object','SECURITY','object_type',false,'ACTIVE'),
  ('security','controlled_action','SECURITY','action_code',false,'ACTIVE'),
  ('security','role_node','SECURITY','id',true,'ACTIVE'),
  ('security','user_role_assignment','SECURITY','user_id',true,'ACTIVE'),
  ('security','profile','SECURITY','id',true,'ACTIVE'),
  ('security','user_profile','SECURITY','user_id',true,'ACTIVE'),
  ('security','permission_set','SECURITY','id',true,'ACTIVE'),
  ('security','permission_set_group','SECURITY','id',true,'ACTIVE'),
  ('security','permission_set_group_member','SECURITY','group_id',true,'ACTIVE'),
  ('security','user_permission_set','SECURITY','id',true,'ACTIVE'),
  ('security','object_permission','SECURITY','id',true,'ACTIVE'),
  ('security','field_permission','SECURITY','id',true,'ACTIVE'),
  ('security','holder_permission','SECURITY','holder_id',true,'ACTIVE'),
  ('security','org_wide_default','SECURITY','object_type',true,'ACTIVE'),
  ('security','sharing_rule','SECURITY','id',true,'ACTIVE'),
  ('security','record_share','SECURITY','id',true,'ACTIVE'),
  ('security','record_team_member','SECURITY','id',true,'ACTIVE'),
  ('security','user_group','SECURITY','id',true,'ACTIVE'),
  ('security','user_group_member','SECURITY','group_id',true,'ACTIVE'),
  ('security','territory','SECURITY','id',true,'ACTIVE'),
  ('security','territory_member','SECURITY','territory_id',true,'ACTIVE'),
  ('security','record_territory','SECURITY','record_id',true,'ACTIVE'),
  ('security','sharing_recompute_job','SECURITY','id',true,'ACTIVE'),
  ('security','sensitive_field','SECURITY','field_name',true,'ACTIVE'),
  ('security','field_read_audit','SECURITY','id',true,'APPEND_ONLY'),
  ('security','sod_conflict','SECURITY','id',true,'ACTIVE'),
  ('security','sod_finding','SECURITY','id',true,'ACTIVE'),
  ('security','approval_request','SECURITY','id',true,'ACTIVE'),
  ('security','approval_delegation','SECURITY','id',true,'ACTIVE'),
  ('security','delegated_admin_scope','SECURITY','id',true,'ACTIVE'),
  ('security','access_review_campaign','SECURITY','id',true,'ACTIVE'),
  ('security','access_review_item','SECURITY','id',true,'ACTIVE'),
  ('security','export_event','SECURITY','id',true,'APPEND_ONLY'),
  ('security','access_grant_log','SECURITY','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

-- E19 - governed sandbox promotion, release approval, rollback and DR validation.

alter table platform.sandbox_environment
  add column if not exists outbound_email_enabled boolean not null default false,
  add column if not exists outbound_webhooks_enabled boolean not null default false,
  add column if not exists outbound_integrations_enabled boolean not null default false,
  add column if not exists data_scope text not null default 'CONFIGURATION_ONLY'
    check (data_scope in ('CONFIGURATION_ONLY','SAMPLE_DATA','FULL_COPY')),
  add column if not exists configuration_snapshot jsonb not null default '[]'::jsonb,
  add column if not exists refreshed_by uuid;

-- Existing READY packages have passed the old coarse readiness gate.
alter table platform.release_package drop constraint if exists release_package_status_check;
update platform.release_package set status = 'VALIDATED' where status = 'READY';
alter table platform.release_package add constraint release_package_status_check check
  (status in ('DRAFT','VALIDATED','PENDING_APPROVAL','APPROVED','DEPLOYED','FAILED','ROLLED_BACK','REJECTED'));
alter table platform.release_package
  add column if not exists description text,
  add column if not exists submitted_by uuid,
  add column if not exists approval_request_id uuid references security.approval_request(id),
  add column if not exists approved_at timestamptz,
  add column if not exists current_fingerprint text,
  add column if not exists updated_at timestamptz not null default now();

-- Platform super-admins are valid approvers and intentionally do not have an
-- identity.app_user row inside every tenant. Tenant isolation is still enforced
-- by the linked maker-checker request and the release-package tenant.
alter table platform.release_package drop constraint if exists fk_release_approver_same_tenant;
comment on column platform.release_package.approved_by is
  'Polymorphic actor id: tenant identity.app_user or platform.platform_user.';

create table platform.environment_configuration (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  environment text not null check (environment in ('DEV','QA','UAT','PROD')),
  component_type text not null check (component_type ~ '^[A-Z][A-Z0-9_]*$'),
  component_key text not null check (component_key ~ '^[A-Za-z0-9_.:-]+$'),
  payload jsonb not null,
  active boolean not null default true,
  checksum text not null check (checksum ~ '^[a-f0-9]{64}$'),
  version integer not null default 1 check (version > 0),
  promoted_from_package_id uuid,
  updated_by uuid,
  updated_at timestamptz not null default now(),
  unique (tenant_id, environment, component_type, component_key),
  unique (tenant_id, id),
  constraint fk_environment_release_same_tenant foreign key (tenant_id, promoted_from_package_id)
    references platform.release_package(tenant_id, id)
);

create table platform.release_component (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  release_package_id uuid not null,
  sequence_no integer not null check (sequence_no > 0),
  component_type text not null check (component_type ~ '^[A-Z][A-Z0-9_]*$'),
  component_key text not null check (component_key ~ '^[A-Za-z0-9_.:-]+$'),
  operation text not null check (operation in ('UPSERT','REMOVE')),
  before_payload jsonb,
  after_payload jsonb,
  created_at timestamptz not null default now(),
  unique (tenant_id, release_package_id, sequence_no),
  unique (tenant_id, release_package_id, component_type, component_key),
  constraint release_component_payload check
    ((operation = 'UPSERT' and after_payload is not null) or (operation = 'REMOVE' and after_payload is null)),
  constraint fk_release_component_package_same_tenant foreign key (tenant_id, release_package_id)
    references platform.release_package(tenant_id, id)
);

create table platform.release_validation_run (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  release_package_id uuid not null,
  target_environment text not null check (target_environment in ('DEV','QA','UAT','PROD')),
  status text not null check (status in ('VALID','BLOCKED')),
  package_fingerprint text not null check (package_fingerprint ~ '^[a-f0-9]{64}$'),
  component_count integer not null check (component_count >= 0),
  blocking_issue_count integer not null check (blocking_issue_count >= 0),
  diff jsonb not null default '[]'::jsonb,
  issues jsonb not null default '[]'::jsonb,
  validated_by uuid not null,
  validated_at timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_release_validation_package_same_tenant foreign key (tenant_id, release_package_id)
    references platform.release_package(tenant_id, id)
);

alter table platform.deployment_run drop constraint if exists deployment_run_status_check;
alter table platform.deployment_run add constraint deployment_run_status_check check
  (status in ('QUEUED','VALIDATING','SUCCEEDED','FAILED','ROLLED_BACK','ROLLBACK_BLOCKED'));
alter table platform.deployment_run
  add column if not exists validation_run_id uuid,
  add column if not exists package_fingerprint text,
  add column if not exists baseline_snapshot jsonb not null default '[]'::jsonb,
  add column if not exists deployed_snapshot jsonb not null default '[]'::jsonb,
  add column if not exists initiated_by uuid,
  add column if not exists rolled_back_at timestamptz,
  add constraint fk_deployment_validation_same_tenant foreign key (tenant_id, validation_run_id)
    references platform.release_validation_run(tenant_id, id);

create table platform.release_rollback_run (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  deployment_run_id uuid not null,
  status text not null check (status in ('SUCCEEDED','BLOCKED')),
  reason text not null,
  restored_components integer not null default 0 check (restored_components >= 0),
  blockers jsonb not null default '[]'::jsonb,
  requested_by uuid not null,
  completed_at timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_rollback_deployment_same_tenant foreign key (tenant_id, deployment_run_id)
    references platform.deployment_run(tenant_id, id)
);

create table platform.dr_validation_run (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  scenario text not null check (scenario in ('SINGLE_AZ','REGIONAL_LOSS','POINT_IN_TIME','TENANT_RESTORE')),
  restore_environment text not null,
  backup_reference text not null,
  backup_checksum text not null check (backup_checksum ~ '^[a-fA-F0-9]{64}$'),
  status text not null check (status in ('PASS','FAIL')),
  target_rto_seconds integer not null check (target_rto_seconds > 0),
  observed_rto_seconds integer not null check (observed_rto_seconds >= 0),
  target_rpo_seconds integer not null check (target_rpo_seconds >= 0),
  observed_rpo_seconds integer not null check (observed_rpo_seconds >= 0),
  expected_counts jsonb not null,
  observed_counts jsonb not null,
  checks jsonb not null,
  blockers jsonb not null default '[]'::jsonb,
  validated_by uuid not null,
  validated_at timestamptz not null default now(),
  unique (tenant_id, id)
);

create index idx_release_component_package on platform.release_component(tenant_id, release_package_id, sequence_no);
create index idx_release_validation_recent on platform.release_validation_run(tenant_id, release_package_id, validated_at desc);
create index idx_environment_configuration_lookup on platform.environment_configuration(tenant_id, environment, component_type);
create index idx_release_rollback_recent on platform.release_rollback_run(tenant_id, completed_at desc);
create index idx_dr_validation_recent on platform.dr_validation_run(tenant_id, validated_at desc);

-- Components become immutable as soon as a package leaves DRAFT.
create or replace function platform.guard_release_component_mutation() returns trigger language plpgsql as $$
declare package_status text;
begin
  select status into package_status from platform.release_package
   where tenant_id = coalesce(new.tenant_id, old.tenant_id)
     and id = coalesce(new.release_package_id, old.release_package_id);
  if package_status <> 'DRAFT' then
    raise exception 'Release components are immutable after validation' using errcode = '23514';
  end if;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end $$;
create trigger trg_release_component_immutable
before insert or update or delete on platform.release_component
for each row execute function platform.guard_release_component_mutation();

insert into security.controlled_action(action_code, label, description) values
  ('RELEASE_PROMOTION','Production release promotion','Approve an atomic configuration promotion to production.')
on conflict (action_code) do update set label=excluded.label, description=excluded.description;

do $$
declare t text;
begin
  foreach t in array array[
    'platform.environment_configuration','platform.release_component',
    'platform.release_validation_run','platform.release_rollback_run','platform.dr_validation_run'
  ] loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format('create policy tenant_isolation on %s using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
  end loop;
end $$;

grant select, insert, update on platform.environment_configuration, platform.release_component to axiom_app;
grant select, insert on platform.release_validation_run, platform.release_rollback_run, platform.dr_validation_run to axiom_app;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('platform','environment_configuration','PLATFORM','id',true,'ACTIVE'),
  ('platform','release_component','PLATFORM','id',true,'ACTIVE'),
  ('platform','release_validation_run','PLATFORM','id',true,'APPEND_ONLY'),
  ('platform','release_rollback_run','PLATFORM','id',true,'APPEND_ONLY'),
  ('platform','dr_validation_run','PLATFORM','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

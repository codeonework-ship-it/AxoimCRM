-- E18 first-party closure: durable operator recovery, mapping revisions and
-- per-object delta checkpoints. Vendor credentials/adapters remain outside
-- this migration; these structures are deliberately vendor-neutral.

alter table migration.plan
  add column if not exists mapping_version integer not null default 0;

alter table migration.run drop constraint if exists run_mode_check;
alter table migration.run add constraint run_mode_check
  check (mode in ('DRY_RUN','IMPORT','DELTA','ROLLBACK','RECONCILE'));

alter table migration.run drop constraint if exists run_status_check;
alter table migration.run add constraint run_status_check
  check (status in ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED'));

alter table migration.run
  add column if not exists retry_of_run uuid references migration.run(id),
  add column if not exists attempt_no integer not null default 1,
  add column if not exists cancelled_at timestamptz,
  add column if not exists cancellation_reason text;

create table if not exists migration.mapping_revision (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  plan_id     uuid not null references migration.plan(id) on delete cascade,
  version_no  integer not null check (version_no > 0),
  reason      text not null,
  mappings    jsonb not null,
  created_by  uuid,
  created_at  timestamptz not null default now(),
  constraint uq_mapping_revision unique (tenant_id, plan_id, version_no),
  constraint mapping_revision_is_array check (jsonb_typeof(mappings) = 'array')
);
create index if not exists idx_mapping_revision_plan
  on migration.mapping_revision (tenant_id, plan_id, version_no desc);

create table if not exists migration.delta_checkpoint (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  plan_id            uuid not null references migration.plan(id) on delete cascade,
  source_object      text not null,
  watermark          timestamptz,
  last_success_run_id uuid references migration.run(id),
  records_created    bigint not null default 0,
  records_updated    bigint not null default 0,
  updated_at         timestamptz not null default now(),
  constraint uq_delta_checkpoint unique (tenant_id, plan_id, source_object)
);
create index if not exists idx_delta_checkpoint_plan
  on migration.delta_checkpoint (tenant_id, plan_id, source_object);

create table if not exists migration.recovery_action (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  plan_id       uuid not null references migration.plan(id) on delete cascade,
  run_id        uuid references migration.run(id),
  action        text not null check (action in ('RETRY','CANCEL','RECONCILE','ROLLBACK')),
  status        text not null check (status in ('ACCEPTED','COMPLETED','REJECTED')),
  reason        text not null,
  requested_by  uuid,
  result_run_id uuid references migration.run(id),
  detail        text,
  created_at    timestamptz not null default now(),
  completed_at  timestamptz
);
create index if not exists idx_recovery_action_plan
  on migration.recovery_action (tenant_id, plan_id, created_at desc);

do $$
declare t text;
begin
  foreach t in array array[
    'migration.mapping_revision',
    'migration.delta_checkpoint',
    'migration.recovery_action'
  ]
  loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    if not exists (
      select 1 from pg_policies
      where schemaname = split_part(t, '.', 1)
        and tablename = split_part(t, '.', 2)
        and policyname = 'tenant_isolation'
    ) then
      execute format(
        'create policy tenant_isolation on %s '
        'using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
        'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
    end if;
  end loop;
end
$$;

grant select, insert, update, delete on
  migration.mapping_revision, migration.delta_checkpoint, migration.recovery_action
  to axiom_app;


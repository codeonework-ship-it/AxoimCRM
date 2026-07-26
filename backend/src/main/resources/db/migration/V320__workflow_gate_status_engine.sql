-- ---------------------------------------------------------------------------
-- Workflow gate status engine
--
-- Purpose:
-- The existing process engine already refuses invalid transitions. This layer
-- keeps a per-record trail of what is missing before the user attempts the
-- transition, and stores the latest "what now?" guidance for every evaluated
-- record.
-- ---------------------------------------------------------------------------

create table if not exists automation.workflow_gate_status (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  object_type    text not null,
  record_id      uuid not null,
  process_id     uuid,
  process_code   text,
  current_state  text,
  gate_status    text not null check (gate_status in ('NO_PROCESS','READY','BLOCKED','COMPLETED','UNKNOWN_STATE')),
  missing_count  int not null default 0 check (missing_count >= 0),
  next_step      text not null,
  issues         jsonb not null default '[]'::jsonb,
  evaluated_at   timestamptz not null default now(),
  resolved_at    timestamptz,
  unique (tenant_id, object_type, record_id)
);

create table if not exists automation.workflow_gate_observation (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  object_type    text not null,
  record_id      uuid not null,
  process_id     uuid,
  process_code   text,
  current_state  text,
  gate_status    text not null check (gate_status in ('NO_PROCESS','READY','BLOCKED','COMPLETED','UNKNOWN_STATE')),
  missing_count  int not null default 0 check (missing_count >= 0),
  next_step      text not null,
  issues         jsonb not null default '[]'::jsonb,
  observed_by    uuid,
  observed_at    timestamptz not null default now()
);

create index if not exists idx_workflow_gate_status_status
  on automation.workflow_gate_status(tenant_id, gate_status, evaluated_at desc);

create index if not exists idx_workflow_gate_status_record
  on automation.workflow_gate_status(tenant_id, object_type, record_id);

create index if not exists idx_workflow_gate_observation_record
  on automation.workflow_gate_observation(tenant_id, object_type, record_id, observed_at desc);

do $$
declare
  t text;
begin
  foreach t in array array[
    'automation.workflow_gate_status',
    'automation.workflow_gate_observation'
  ] loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format($p$create policy tenant_isolation on %s
        using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
        with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
    execute format('grant select, insert, update, delete on %s to axiom_app', t);
  end loop;
end $$;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('automation','workflow_gate_status','AUTOMATION','id',true,'ACTIVE'),
  ('automation','workflow_gate_observation','AUTOMATION','id',true,'APPEND_ONLY')
on conflict do nothing;

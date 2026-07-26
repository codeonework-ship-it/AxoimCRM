-- E06-E10 first-party closure controls.

-- E08: complete the bidirectional quote-version relationship. It is deferred
-- because revisioning must retire the old active row before the new active row
-- can satisfy the one-active-version partial unique index.
alter table cpq.quote
  add constraint fk_quote_superseded_by_same_tenant
  foreign key (tenant_id, superseded_by_quote_id)
  references cpq.quote(tenant_id, id)
  deferrable initially deferred;

-- E09: one idempotent renewal plan produces at most one successor contract.
create table contracting.renewal_plan (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references platform.tenant(id),
  source_contract_id    uuid not null,
  generated_contract_id uuid,
  status                text not null default 'PLANNED'
                        check (status in ('PLANNED','GENERATED','DISMISSED')),
  proposed_start_date   date not null,
  proposed_end_date     date not null,
  proposed_value        numeric(14,2) not null check (proposed_value >= 0),
  owner_id              uuid not null,
  rationale             text not null,
  generated_at          timestamptz,
  created_at            timestamptz not null default now(),
  created_by            uuid not null,
  unique (tenant_id, id),
  unique (tenant_id, source_contract_id),
  unique (tenant_id, generated_contract_id),
  constraint renewal_plan_dates check (proposed_end_date >= proposed_start_date),
  constraint fk_renewal_plan_source_same_tenant
    foreign key (tenant_id, source_contract_id)
    references contracting.contract_record(tenant_id, id),
  constraint fk_renewal_plan_generated_same_tenant
    foreign key (tenant_id, generated_contract_id)
    references contracting.contract_record(tenant_id, id)
    deferrable initially deferred,
  constraint fk_renewal_plan_owner_same_tenant
    foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id),
  constraint renewal_plan_generated_consistent check (
    (status = 'GENERATED' and generated_contract_id is not null and generated_at is not null)
    or (status <> 'GENERATED' and generated_contract_id is null and generated_at is null))
);

alter table contracting.contract_record
  add column predecessor_contract_id uuid,
  add column renewal_plan_id uuid,
  add constraint fk_contract_predecessor_same_tenant
    foreign key (tenant_id, predecessor_contract_id)
    references contracting.contract_record(tenant_id, id),
  add constraint fk_contract_renewal_plan_same_tenant
    foreign key (tenant_id, renewal_plan_id)
    references contracting.renewal_plan(tenant_id, id)
    deferrable initially deferred,
  add constraint uq_contract_one_successor unique (tenant_id, predecessor_contract_id),
  add constraint uq_contract_one_renewal_plan unique (tenant_id, renewal_plan_id);

create index idx_renewal_plan_status
  on contracting.renewal_plan(tenant_id, status, proposed_start_date);

-- E10: saved scenarios are immutable evidence. A scenario never changes the
-- submitted forecast; managers compare it and submit a separate governed edit.
create table forecasting.forecast_scenario (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references platform.tenant(id),
  submission_id         uuid not null,
  name                  text not null,
  amount_adjustment_pct numeric(7,2) not null
                        check (amount_adjustment_pct between -100 and 500),
  confidence_pct        numeric(5,2) not null check (confidence_pct between 0 and 100),
  risk_count            int not null check (risk_count >= 0),
  baseline_amount       numeric(14,2) not null check (baseline_amount >= 0),
  scenario_amount       numeric(14,2) not null check (scenario_amount >= 0),
  weighted_amount       numeric(14,2) not null check (weighted_amount >= 0),
  explanation           jsonb not null default '[]'::jsonb
                        check (jsonb_typeof(explanation) = 'array'),
  created_at            timestamptz not null default now(),
  created_by            uuid not null,
  unique (tenant_id, id),
  constraint fk_forecast_scenario_submission_same_tenant
    foreign key (tenant_id, submission_id)
    references forecasting.forecast_submission(tenant_id, id)
);

create index idx_forecast_scenario_submission
  on forecasting.forecast_scenario(tenant_id, submission_id, created_at desc);

create or replace function forecasting.reject_scenario_mutation() returns trigger
language plpgsql as $$
begin
  raise exception 'Forecast scenarios are immutable. Create a new scenario instead.'
    using errcode = '42501';
end;
$$;

create trigger trg_forecast_scenario_immutable
  before update or delete on forecasting.forecast_scenario
  for each row execute function forecasting.reject_scenario_mutation();

alter table contracting.renewal_plan enable row level security;
alter table contracting.renewal_plan force row level security;
create policy tenant_isolation on contracting.renewal_plan
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table forecasting.forecast_scenario enable row level security;
alter table forecasting.forecast_scenario force row level security;
create policy tenant_isolation on forecasting.forecast_scenario
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

grant select, insert, update on contracting.renewal_plan to axiom_app;
grant select, insert on forecasting.forecast_scenario to axiom_app;

insert into governance.module_table_catalog
  (schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle)
values
  ('contracting','renewal_plan','CONTRACTING','id',true,'ACTIVE'),
  ('forecasting','forecast_scenario','FORECASTING','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

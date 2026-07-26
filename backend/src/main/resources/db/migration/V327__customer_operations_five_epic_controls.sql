-- E11-E15 governed customer-operations controls.

-- E11: immutable campaign performance evidence. Recalculation creates a new
-- snapshot so historic ROI cannot silently change when member data changes.
create table marketing.campaign_performance_snapshot (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  campaign_id uuid not null,
  member_count int not null check (member_count >= 0),
  response_count int not null check (response_count >= 0),
  mql_count int not null check (mql_count >= 0),
  sql_count int not null check (sql_count >= 0),
  budget_amount numeric(14,2) not null check (budget_amount >= 0),
  influenced_pipeline numeric(14,2) not null check (influenced_pipeline >= 0),
  roi_percent numeric(9,2),
  captured_at timestamptz not null default now(),
  captured_by uuid not null,
  unique (tenant_id, id),
  constraint fk_campaign_snapshot_campaign_same_tenant foreign key (tenant_id, campaign_id)
    references marketing.campaign(tenant_id, id),
  constraint fk_campaign_snapshot_user_same_tenant foreign key (tenant_id, captured_by)
    references identity.app_user(tenant_id, id)
);
create index idx_campaign_snapshot_latest
  on marketing.campaign_performance_snapshot(tenant_id, campaign_id, captured_at desc);

create or replace function marketing.reject_campaign_snapshot_mutation() returns trigger
language plpgsql as $$
begin
  raise exception 'Campaign performance snapshots are immutable. Capture a new snapshot instead.'
    using errcode = '42501';
end;
$$;
create trigger trg_campaign_snapshot_immutable before update or delete
  on marketing.campaign_performance_snapshot for each row
  execute function marketing.reject_campaign_snapshot_mutation();

-- E12: one escalation per milestone and level. The unique key makes repeated
-- SLA sweeps safe and the case remains recoverable through acknowledgement.
create table service.case_escalation (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  case_id uuid not null,
  milestone_id uuid not null,
  escalation_level int not null default 1 check (escalation_level between 1 and 3),
  status text not null default 'OPEN' check (status in ('OPEN','ACKNOWLEDGED','RESOLVED')),
  reason text not null,
  opened_at timestamptz not null default now(),
  acknowledged_at timestamptz,
  acknowledged_by uuid,
  unique (tenant_id, id),
  unique (tenant_id, milestone_id, escalation_level),
  constraint fk_case_escalation_case_same_tenant foreign key (tenant_id, case_id)
    references service.case_record(tenant_id, id),
  constraint fk_case_escalation_milestone_same_tenant foreign key (tenant_id, milestone_id)
    references service.case_milestone(tenant_id, id),
  constraint fk_case_escalation_user_same_tenant foreign key (tenant_id, acknowledged_by)
    references identity.app_user(tenant_id, id),
  constraint case_escalation_ack_consistent check (
    (status = 'OPEN' and acknowledged_at is null and acknowledged_by is null)
    or (status <> 'OPEN' and acknowledged_at is not null and acknowledged_by is not null))
);
create index idx_case_escalation_open
  on service.case_escalation(tenant_id, status, opened_at desc);

-- E13: request-level idempotency and explicit conflict-check outcome prevent a
-- deal from being registered twice by a retry or approved without a review.
alter table channel.deal_registration
  add column idempotency_key text,
  add column conflict_checked_at timestamptz,
  add column conflict_status text not null default 'PENDING'
    check (conflict_status in ('PENDING','CLEAR','CONFLICT')),
  add constraint uq_deal_registration_idempotency unique (tenant_id, idempotency_key);

-- E15: internal report scheduling. Generation is first-party; external email
-- delivery remains an adapter concern and is intentionally not represented as
-- successful here.
create table reporting.report_subscription (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  report_definition_id uuid not null,
  name text not null,
  format text not null check (format in ('PDF','XLSX','DOCX')),
  frequency text not null check (frequency in ('DAILY','WEEKLY','MONTHLY')),
  recipients text[] not null check (cardinality(recipients) > 0),
  enabled boolean not null default true,
  next_run_at timestamptz not null,
  last_run_at timestamptz,
  created_at timestamptz not null default now(),
  created_by uuid not null,
  unique (tenant_id, id),
  unique (tenant_id, name),
  constraint fk_report_subscription_definition_same_tenant foreign key (tenant_id, report_definition_id)
    references reporting.report_definition(tenant_id, id),
  constraint fk_report_subscription_user_same_tenant foreign key (tenant_id, created_by)
    references identity.app_user(tenant_id, id)
);

create table reporting.report_subscription_run (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  subscription_id uuid not null,
  status text not null check (status in ('GENERATED','FAILED')),
  filename text,
  error_message text,
  generated_at timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_report_subscription_run_same_tenant foreign key (tenant_id, subscription_id)
    references reporting.report_subscription(tenant_id, id)
);
create index idx_report_subscription_due
  on reporting.report_subscription(tenant_id, enabled, next_run_at);

alter table marketing.campaign_performance_snapshot enable row level security;
alter table marketing.campaign_performance_snapshot force row level security;
create policy tenant_isolation on marketing.campaign_performance_snapshot
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table service.case_escalation enable row level security;
alter table service.case_escalation force row level security;
create policy tenant_isolation on service.case_escalation
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table reporting.report_subscription enable row level security;
alter table reporting.report_subscription force row level security;
create policy tenant_isolation on reporting.report_subscription
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table reporting.report_subscription_run enable row level security;
alter table reporting.report_subscription_run force row level security;
create policy tenant_isolation on reporting.report_subscription_run
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

grant select, insert on marketing.campaign_performance_snapshot to axiom_app;
grant select, insert, update on service.case_escalation to axiom_app;
grant select, insert, update on reporting.report_subscription to axiom_app;
grant select, insert on reporting.report_subscription_run to axiom_app;

insert into governance.module_table_catalog
  (schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle)
values
  ('marketing','campaign_performance_snapshot','MARKETING','id',true,'APPEND_ONLY'),
  ('service','case_escalation','SERVICE','id',true,'ACTIVE'),
  ('reporting','report_subscription','REPORTING','id',true,'ACTIVE'),
  ('reporting','report_subscription_run','REPORTING','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

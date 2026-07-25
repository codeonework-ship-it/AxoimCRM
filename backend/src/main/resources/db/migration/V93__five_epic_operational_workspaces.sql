-- Five-epic operational workspace increment:
--   E09 contracts/orders/subscriptions, E10 forecasting, E11 campaigns,
--   E12 cases/entitlements/SLA, E18 migration/onboarding.
--
-- This is the same honest preview pattern used for CPQ: real module schemas,
-- tenant-scoped tables, constraints, seed transactions, RBAC/screen catalogue
-- registration and read APIs. External/vendor pieces remain explicitly outside
-- this migration.

create schema if not exists contracting;
create schema if not exists forecasting;
create schema if not exists marketing;
create schema if not exists service;
create schema if not exists migration;

grant usage on schema contracting, forecasting, marketing, service, migration to axiom_app;
alter role axiom_app set search_path to platform, identity, crm, sales, engagement, governance, reference, billing, reporting, cpq, contracting, forecasting, marketing, service, migration, integration, i18n, public;

-- ---------------------------------------------------------------------------
-- E09 — contracts, orders, subscriptions and renewals
-- ---------------------------------------------------------------------------
create table contracting.contract_record (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  contract_number text not null,
  account_id uuid not null,
  opportunity_id uuid,
  quote_id uuid,
  owner_id uuid not null,
  title text not null,
  status text not null check (status in ('DRAFT','IN_REVIEW','ACTIVE','EXPIRING','EXPIRED','TERMINATED')),
  start_date date not null,
  end_date date not null,
  renewal_notice_date date,
  total_contract_value numeric(14,2) not null default 0 check (total_contract_value >= 0),
  auto_renew boolean not null default false,
  signed_document_ref text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, contract_number),
  constraint contract_date_range check (end_date >= start_date),
  constraint fk_contract_account_same_tenant foreign key (tenant_id, account_id) references crm.account(tenant_id, id),
  constraint fk_contract_opportunity_same_tenant foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id),
  constraint fk_contract_quote_same_tenant foreign key (tenant_id, quote_id) references cpq.quote(tenant_id, id),
  constraint fk_contract_owner_same_tenant foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id)
);

create table contracting.order_record (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  order_number text not null,
  contract_id uuid,
  account_id uuid not null,
  status text not null check (status in ('DRAFT','BOOKED','FULFILMENT','PARTIALLY_FULFILLED','FULFILLED','CANCELLED')),
  order_date date not null default current_date,
  currency_code char(3) not null default 'USD',
  total_amount numeric(14,2) not null check (total_amount >= 0),
  fulfilment_due_at date,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, order_number),
  constraint fk_order_contract_same_tenant foreign key (tenant_id, contract_id) references contracting.contract_record(tenant_id, id),
  constraint fk_order_account_same_tenant foreign key (tenant_id, account_id) references crm.account(tenant_id, id)
);

create table contracting.subscription (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  contract_id uuid not null,
  account_id uuid not null,
  product_code text not null,
  product_name text not null,
  status text not null check (status in ('ACTIVE','PENDING_RENEWAL','SUSPENDED','CANCELLED','EXPIRED')),
  start_date date not null,
  end_date date not null,
  quantity numeric(14,4) not null check (quantity > 0),
  recurring_amount numeric(14,2) not null check (recurring_amount >= 0),
  billing_frequency text not null check (billing_frequency in ('MONTHLY','QUARTERLY','SEMIANNUAL','ANNUAL')),
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  constraint subscription_date_range check (end_date >= start_date),
  constraint fk_subscription_contract_same_tenant foreign key (tenant_id, contract_id) references contracting.contract_record(tenant_id, id),
  constraint fk_subscription_account_same_tenant foreign key (tenant_id, account_id) references crm.account(tenant_id, id)
);

create index idx_contract_status on contracting.contract_record(tenant_id, status, end_date) where deleted_at is null;
create index idx_order_status on contracting.order_record(tenant_id, status, order_date);
create index idx_subscription_renewal on contracting.subscription(tenant_id, status, end_date);

-- ---------------------------------------------------------------------------
-- E10 — forecasting and revenue intelligence
-- ---------------------------------------------------------------------------
create table forecasting.forecast_period (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  code text not null,
  label text not null,
  period_start date not null,
  period_end date not null,
  status text not null check (status in ('OPEN','LOCKED','CLOSED')),
  unique (tenant_id, id),
  unique (tenant_id, code),
  constraint forecast_period_range check (period_end >= period_start)
);

create table forecasting.forecast_submission (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  period_id uuid not null,
  owner_id uuid not null,
  forecast_category text not null check (forecast_category in ('PIPELINE','BEST_CASE','COMMIT','CLOSED')),
  submitted_amount numeric(14,2) not null check (submitted_amount >= 0),
  weighted_pipeline_amount numeric(14,2) not null default 0 check (weighted_pipeline_amount >= 0),
  confidence_pct numeric(5,2) not null check (confidence_pct between 0 and 100),
  status text not null check (status in ('DRAFT','SUBMITTED','MANAGER_ADJUSTED','LOCKED')),
  risk_count int not null default 0 check (risk_count >= 0),
  submitted_at timestamptz,
  manager_note text,
  unique (tenant_id, id),
  unique (tenant_id, period_id, owner_id, forecast_category),
  constraint fk_forecast_period_same_tenant foreign key (tenant_id, period_id) references forecasting.forecast_period(tenant_id, id),
  constraint fk_forecast_owner_same_tenant foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id)
);

create table forecasting.forecast_snapshot (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  period_id uuid not null,
  snapshot_at timestamptz not null default now(),
  open_pipeline numeric(14,2) not null default 0,
  commit_amount numeric(14,2) not null default 0,
  best_case_amount numeric(14,2) not null default 0,
  closed_amount numeric(14,2) not null default 0,
  at_risk_amount numeric(14,2) not null default 0,
  unique (tenant_id, id),
  constraint fk_forecast_snapshot_period_same_tenant foreign key (tenant_id, period_id) references forecasting.forecast_period(tenant_id, id)
);

create index idx_forecast_submission_status on forecasting.forecast_submission(tenant_id, status, forecast_category);

-- ---------------------------------------------------------------------------
-- E11 — campaigns, segments and marketing alignment
-- ---------------------------------------------------------------------------
create table marketing.campaign (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  code text not null,
  name text not null,
  campaign_type text not null check (campaign_type in ('EMAIL','WEBINAR','EVENT','ABM','NURTURE','DIGITAL')),
  status text not null check (status in ('PLANNED','ACTIVE','PAUSED','COMPLETED','CANCELLED')),
  owner_id uuid not null,
  start_date date not null,
  end_date date,
  budget_amount numeric(14,2) not null default 0 check (budget_amount >= 0),
  pipeline_influenced numeric(14,2) not null default 0 check (pipeline_influenced >= 0),
  created_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, code),
  constraint campaign_date_range check (end_date is null or end_date >= start_date),
  constraint fk_campaign_owner_same_tenant foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id)
);

create table marketing.segment (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  code text not null,
  name text not null,
  criteria jsonb not null default '{}'::jsonb,
  estimated_size int not null default 0 check (estimated_size >= 0),
  active boolean not null default true,
  unique (tenant_id, id),
  unique (tenant_id, code)
);

create table marketing.campaign_member (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  campaign_id uuid not null,
  lead_id uuid,
  contact_id uuid,
  status text not null check (status in ('TARGETED','SENT','RESPONDED','MQL','SQL','DISQUALIFIED')),
  responded_at timestamptz,
  score_delta numeric(8,2) not null default 0,
  unique (tenant_id, id),
  constraint campaign_member_one_person check ((lead_id is not null) <> (contact_id is not null)),
  constraint fk_member_campaign_same_tenant foreign key (tenant_id, campaign_id) references marketing.campaign(tenant_id, id),
  constraint fk_member_lead_same_tenant foreign key (tenant_id, lead_id) references crm.lead(tenant_id, id),
  constraint fk_member_contact_same_tenant foreign key (tenant_id, contact_id) references crm.contact(tenant_id, id)
);

create index idx_campaign_status on marketing.campaign(tenant_id, status, start_date) where deleted_at is null;
create index idx_campaign_member_status on marketing.campaign_member(tenant_id, campaign_id, status);

-- ---------------------------------------------------------------------------
-- E12 — cases, entitlements and SLA management
-- ---------------------------------------------------------------------------
create table service.entitlement (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  account_id uuid not null,
  name text not null,
  tier text not null check (tier in ('STANDARD','PREMIUM','MISSION_CRITICAL')),
  status text not null check (status in ('ACTIVE','SUSPENDED','EXPIRED')),
  response_minutes int not null check (response_minutes > 0),
  resolution_minutes int not null check (resolution_minutes > 0),
  start_date date not null,
  end_date date,
  unique (tenant_id, id),
  constraint entitlement_date_range check (end_date is null or end_date >= start_date),
  constraint fk_entitlement_account_same_tenant foreign key (tenant_id, account_id) references crm.account(tenant_id, id)
);

create table service.case_record (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  case_number text not null,
  account_id uuid not null,
  contact_id uuid,
  entitlement_id uuid,
  owner_id uuid not null,
  subject text not null,
  status text not null check (status in ('NEW','WORKING','WAITING_ON_CUSTOMER','ESCALATED','RESOLVED','CLOSED')),
  priority text not null check (priority in ('LOW','NORMAL','HIGH','URGENT')),
  origin text not null check (origin in ('PHONE','EMAIL','WEB','CHAT','INTERNAL')),
  opened_at timestamptz not null default now(),
  first_response_due_at timestamptz,
  resolution_due_at timestamptz,
  closed_at timestamptz,
  created_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, case_number),
  constraint fk_case_account_same_tenant foreign key (tenant_id, account_id) references crm.account(tenant_id, id),
  constraint fk_case_contact_same_tenant foreign key (tenant_id, contact_id) references crm.contact(tenant_id, id),
  constraint fk_case_entitlement_same_tenant foreign key (tenant_id, entitlement_id) references service.entitlement(tenant_id, id),
  constraint fk_case_owner_same_tenant foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id)
);

create table service.case_milestone (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  case_id uuid not null,
  milestone_type text not null check (milestone_type in ('FIRST_RESPONSE','RESOLUTION','ESCALATION_REVIEW')),
  due_at timestamptz not null,
  completed_at timestamptz,
  status text not null check (status in ('OPEN','MET','MISSED','WAIVED')),
  unique (tenant_id, id),
  unique (tenant_id, case_id, milestone_type),
  constraint fk_milestone_case_same_tenant foreign key (tenant_id, case_id) references service.case_record(tenant_id, id)
);

create index idx_case_status on service.case_record(tenant_id, status, priority) where deleted_at is null;
create index idx_case_milestone_due on service.case_milestone(tenant_id, status, due_at);

-- ---------------------------------------------------------------------------
-- E18 — migration and onboarding
-- ---------------------------------------------------------------------------
create table migration.import_template (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  object_type text not null check (object_type in ('ACCOUNT','CONTACT','LEAD','OPPORTUNITY','PRODUCT','QUOTE')),
  template_name text not null,
  required_columns text[] not null,
  optional_columns text[] not null default '{}',
  active boolean not null default true,
  unique (tenant_id, id),
  unique (tenant_id, object_type, template_name)
);

create table migration.import_batch (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  batch_number text not null,
  object_type text not null,
  file_name text not null,
  status text not null check (status in ('UPLOADED','VALIDATING','READY_TO_IMPORT','IMPORTED','FAILED','ROLLED_BACK')),
  total_rows int not null default 0 check (total_rows >= 0),
  valid_rows int not null default 0 check (valid_rows >= 0),
  error_rows int not null default 0 check (error_rows >= 0),
  duplicate_rows int not null default 0 check (duplicate_rows >= 0),
  imported_rows int not null default 0 check (imported_rows >= 0),
  uploaded_by uuid not null,
  uploaded_at timestamptz not null default now(),
  completed_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, batch_number),
  constraint import_row_counts_consistent check (valid_rows + error_rows <= total_rows),
  constraint fk_import_uploaded_by_same_tenant foreign key (tenant_id, uploaded_by) references identity.app_user(tenant_id, id)
);

create table migration.validation_error (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  batch_id uuid not null,
  row_number int not null check (row_number > 0),
  column_name text not null,
  error_code text not null,
  message text not null,
  severity text not null check (severity in ('ERROR','WARNING')),
  unique (tenant_id, id),
  constraint fk_validation_batch_same_tenant foreign key (tenant_id, batch_id) references migration.import_batch(tenant_id, id)
);

create index idx_import_batch_status on migration.import_batch(tenant_id, status, uploaded_at desc);
create index idx_validation_error_batch on migration.validation_error(tenant_id, batch_id, severity);

-- ---------------------------------------------------------------------------
-- RLS, grants and governance registration
-- ---------------------------------------------------------------------------
do $$
declare
  table_name text;
begin
  foreach table_name in array array[
    'contracting.contract_record','contracting.order_record','contracting.subscription',
    'forecasting.forecast_period','forecasting.forecast_submission','forecasting.forecast_snapshot',
    'marketing.campaign','marketing.segment','marketing.campaign_member',
    'service.entitlement','service.case_record','service.case_milestone',
    'migration.import_template','migration.import_batch','migration.validation_error'
  ] loop
    execute format('alter table %s enable row level security', table_name);
    execute format('alter table %s force row level security', table_name);
    execute format('create policy tenant_isolation on %s using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', table_name);
    execute format('grant select, insert, update on %s to axiom_app', table_name);
  end loop;
end $$;

insert into governance.module_catalog(module_code, schema_name, display_name, description, owner_role) values
  ('CONTRACTING', 'contracting', 'Contracts and orders', 'Contracts, orders, subscriptions and renewal operations.', 'FINANCE'),
  ('FORECASTING', 'forecasting', 'Forecasting', 'Forecast periods, submissions and revenue snapshots.', 'SALES_MANAGER'),
  ('MARKETING', 'marketing', 'Campaigns', 'Campaigns, segments, members and marketing alignment.', 'MARKETING'),
  ('SERVICE', 'service', 'Service', 'Cases, entitlements and SLA milestones.', 'SERVICE'),
  ('MIGRATION', 'migration', 'Migration', 'Import templates, batches, validation errors and onboarding evidence.', 'DATA_STEWARD')
on conflict (module_code) do nothing;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('contracting','contract_record','CONTRACTING','id',true,'SOFT_DELETE'),
  ('contracting','order_record','CONTRACTING','id',true,'ACTIVE'),
  ('contracting','subscription','CONTRACTING','id',true,'ACTIVE'),
  ('forecasting','forecast_period','FORECASTING','id',true,'ACTIVE'),
  ('forecasting','forecast_submission','FORECASTING','id',true,'ACTIVE'),
  ('forecasting','forecast_snapshot','FORECASTING','id',true,'APPEND_ONLY'),
  ('marketing','campaign','MARKETING','id',true,'SOFT_DELETE'),
  ('marketing','segment','MARKETING','id',true,'ACTIVE'),
  ('marketing','campaign_member','MARKETING','id',true,'ACTIVE'),
  ('service','entitlement','SERVICE','id',true,'ACTIVE'),
  ('service','case_record','SERVICE','id',true,'SOFT_DELETE'),
  ('service','case_milestone','SERVICE','id',true,'ACTIVE'),
  ('migration','import_template','MIGRATION','id',true,'ACTIVE'),
  ('migration','import_batch','MIGRATION','id',true,'ACTIVE'),
  ('migration','validation_error','MIGRATION','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

insert into governance.screen_catalog(screen_code, module_code, route, display_name, description, sort_order) values
  ('CONTRACTS', 'CONTRACTING', '/contracts', 'Contracts', 'Contracts, orders, subscriptions and renewal risk.', 43),
  ('FORECAST', 'FORECASTING', '/forecast', 'Forecast', 'Forecast rollups, submissions, confidence and risk.', 44),
  ('CAMPAIGNS', 'MARKETING', '/campaigns', 'Campaigns', 'Campaign performance, members and influenced pipeline.', 45),
  ('CASES', 'SERVICE', '/cases', 'Cases', 'Customer service cases, entitlements and SLA milestones.', 46),
  ('MIGRATION', 'MIGRATION', '/migration', 'Migration', 'Data import batches, validation errors and onboarding readiness.', 130)
on conflict (screen_code) do update
  set module_code = excluded.module_code,
      route = excluded.route,
      display_name = excluded.display_name,
      description = excluded.description,
      sort_order = excluded.sort_order;

insert into governance.rbac_policy(role_code, screen_code, can_read, can_write, can_export, can_admin, scope)
select role_code, screen_code,
       role_code <> 'INTEGRATION',
       role_code not in ('SUPER_AUDIT','AUDITOR','INTEGRATION'),
       role_code <> 'INTEGRATION',
       role_code in ('SUPER_ADMIN','TENANT_ADMIN','FINANCE','DATA_STEWARD'),
       case when role_code in ('SUPER_ADMIN','SUPER_AUDIT') then 'PLATFORM' else 'TENANT' end
from (values
  ('SUPER_ADMIN'),('SUPER_AUDIT'),('TENANT_ADMIN'),('SALES_MANAGER'),('SALES'),
  ('MARKETING'),('SERVICE'),('OPERATIONS'),('FINANCE'),('DATA_STEWARD'),('AUDITOR'),('INTEGRATION')
) roles(role_code)
cross join (values ('CONTRACTS'),('FORECAST'),('CAMPAIGNS'),('CASES'),('MIGRATION')) screens(screen_code)
on conflict (role_code, screen_code) do update
  set can_read = excluded.can_read,
      can_write = excluded.can_write,
      can_export = excluded.can_export,
      can_admin = excluded.can_admin,
      scope = excluded.scope;

-- ---------------------------------------------------------------------------
-- Seed data: deterministic where IDs are useful, idempotent everywhere.
-- ---------------------------------------------------------------------------
insert into contracting.contract_record
  (tenant_id, contract_number, account_id, opportunity_id, quote_id, owner_id, title, status,
   start_date, end_date, renewal_notice_date, total_contract_value, auto_renew, signed_document_ref)
select o.tenant_id, seed.contract_number, o.account_id, o.id, q.id, o.owner_id, seed.title, seed.status,
       seed.start_date, seed.end_date, seed.renewal_notice_date, seed.tcv, seed.auto_renew, seed.document_ref
from (values
  ('CTR-2026-0001', 'Northbrook enterprise master subscription', '66666666-6666-6666-6666-666666666608'::uuid, 'ACTIVE', date '2026-08-01', date '2027-07-31', date '2027-05-31', 513000.00::numeric, true, 'local://contracts/CTR-2026-0001.pdf'),
  ('CTR-2026-0002', 'Kestrel renewal agreement', '66666666-6666-6666-6666-666666666601'::uuid, 'IN_REVIEW', date '2026-08-15', date '2027-08-14', date '2027-06-15', 82500.00::numeric, true, null),
  ('CTR-2026-0003', 'Fenmore framework agreement', '66666666-6666-6666-6666-666666666607'::uuid, 'DRAFT', date '2026-09-01', date '2027-08-31', date '2027-07-01', 328000.00::numeric, false, null)
) as seed(contract_number, title, opportunity_id, status, start_date, end_date, renewal_notice_date, tcv, auto_renew, document_ref)
join sales.opportunity o on o.id = seed.opportunity_id
left join cpq.quote q on q.tenant_id = o.tenant_id and q.opportunity_id = o.id and q.is_active_version
on conflict (tenant_id, contract_number) do nothing;

insert into contracting.order_record
  (tenant_id, order_number, contract_id, account_id, status, order_date, total_amount, fulfilment_due_at)
select c.tenant_id, 'ORD-' || right(c.contract_number, 9), c.id, c.account_id,
       case when c.status = 'ACTIVE' then 'BOOKED' else 'DRAFT' end,
       c.start_date, c.total_contract_value, c.start_date + 30
from contracting.contract_record c
on conflict (tenant_id, order_number) do nothing;

insert into contracting.subscription
  (tenant_id, contract_id, account_id, product_code, product_name, status, start_date, end_date, quantity, recurring_amount, billing_frequency)
select c.tenant_id, c.id, c.account_id, 'PLT-ADV', 'Axiom Platform — Advanced licence',
       case when c.status = 'ACTIVE' then 'ACTIVE' else 'PENDING_RENEWAL' end,
       c.start_date, c.end_date, 50, round(c.total_contract_value / 12, 2), 'ANNUAL'
from contracting.contract_record c
on conflict do nothing;

insert into forecasting.forecast_period(tenant_id, code, label, period_start, period_end, status)
select t.id, seed.code, seed.label, seed.period_start, seed.period_end, seed.status
from platform.tenant t
cross join (values
  ('FY26-Q3', 'FY26 Q3', date '2026-07-01', date '2026-09-30', 'OPEN'),
  ('FY26-Q4', 'FY26 Q4', date '2026-10-01', date '2026-12-31', 'OPEN')
) as seed(code, label, period_start, period_end, status)
on conflict (tenant_id, code) do nothing;

insert into forecasting.forecast_submission
  (tenant_id, period_id, owner_id, forecast_category, submitted_amount, weighted_pipeline_amount,
   confidence_pct, status, risk_count, submitted_at, manager_note)
select p.tenant_id, p.id, u.id, seed.category, seed.amount, seed.weighted_amount, seed.confidence,
       seed.status, seed.risk_count, case when seed.status <> 'DRAFT' then now() else null end, seed.note
from forecasting.forecast_period p
join (values
  ('FY26-Q3', 'priya.nair@meridianfab.com', 'COMMIT', 625000.00::numeric, 512000.00::numeric, 82.00::numeric, 'SUBMITTED', 2, 'Commit includes Northbrook and Kestrel renewals.'),
  ('FY26-Q3', 'maya.torres@meridianfab.com', 'BEST_CASE', 438000.00::numeric, 286000.00::numeric, 64.00::numeric, 'SUBMITTED', 3, 'Fenmore approval remains the largest swing factor.'),
  ('FY26-Q3', 'ava.chen@northstar.example', 'PIPELINE', 120000.00::numeric, 60000.00::numeric, 50.00::numeric, 'DRAFT', 1, 'Northstar trial workspace forecast seed.')
) as seed(period_code, email, category, amount, weighted_amount, confidence, status, risk_count, note)
  on seed.period_code = p.code
join identity.app_user u on u.tenant_id = p.tenant_id and u.email = seed.email
on conflict (tenant_id, period_id, owner_id, forecast_category) do nothing;

insert into forecasting.forecast_snapshot(tenant_id, period_id, open_pipeline, commit_amount, best_case_amount, closed_amount, at_risk_amount)
select p.tenant_id, p.id,
       coalesce((select sum(amount) from sales.opportunity o where o.tenant_id = p.tenant_id and o.is_closed = false), 0),
       coalesce((select sum(submitted_amount) from forecasting.forecast_submission s where s.tenant_id = p.tenant_id and s.period_id = p.id and s.forecast_category = 'COMMIT'), 0),
       coalesce((select sum(submitted_amount) from forecasting.forecast_submission s where s.tenant_id = p.tenant_id and s.period_id = p.id and s.forecast_category = 'BEST_CASE'), 0),
       coalesce((select sum(grand_total) from cpq.quote q where q.tenant_id = p.tenant_id and q.status in ('ACCEPTED','ORDERED')), 0),
       coalesce((select sum(amount) from sales.opportunity o where o.tenant_id = p.tenant_id and o.is_closed = false and o.close_date < current_date + interval '14 days'), 0)
from forecasting.forecast_period p
where p.code = 'FY26-Q3'
  and not exists (select 1 from forecasting.forecast_snapshot s where s.tenant_id = p.tenant_id and s.period_id = p.id);

insert into marketing.campaign
  (tenant_id, code, name, campaign_type, status, owner_id, start_date, end_date, budget_amount, pipeline_influenced)
select u.tenant_id, seed.code, seed.name, seed.campaign_type, seed.status, u.id,
       seed.start_date, seed.end_date, seed.budget, seed.pipeline
from (values
  ('FY26-RENEWAL-ABM', 'FY26 Renewal ABM', 'ABM', 'ACTIVE', 'priya.nair@meridianfab.com', date '2026-07-01', date '2026-09-30', 28000.00::numeric, 392000.00::numeric),
  ('WEBINAR-OPS-AUTOMATION', 'Operations automation webinar', 'WEBINAR', 'PLANNED', 'maya.torres@meridianfab.com', date '2026-08-12', date '2026-08-12', 9500.00::numeric, 140000.00::numeric),
  ('NORTHSTAR-TRIAL-NURTURE', 'Northstar trial nurture', 'NURTURE', 'ACTIVE', 'ava.chen@northstar.example', date '2026-07-15', date '2026-08-31', 4000.00::numeric, 25000.00::numeric)
) as seed(code, name, campaign_type, status, owner_email, start_date, end_date, budget, pipeline)
join identity.app_user u on u.email = seed.owner_email
on conflict (tenant_id, code) do nothing;

insert into marketing.segment(tenant_id, code, name, criteria, estimated_size)
select t.id, seed.code, seed.name, seed.criteria::jsonb, seed.size
from platform.tenant t
cross join (values
  ('INDUSTRIAL-RENEWALS', 'Industrial renewals due in 90 days', '{"industry":"Industrial","renewalWindowDays":90}', 42),
  ('HIGH-VALUE-OPEN-PIPELINE', 'High-value open pipeline', '{"minOpportunityAmount":250000}', 18)
) as seed(code, name, criteria, size)
on conflict (tenant_id, code) do nothing;

insert into marketing.campaign_member(tenant_id, campaign_id, lead_id, contact_id, status, responded_at, score_delta)
select c.tenant_id, c.id, l.id, null, 'MQL', now() - interval '2 days', 18
from marketing.campaign c
join crm.lead l on l.tenant_id = c.tenant_id
where c.code in ('FY26-RENEWAL-ABM','NORTHSTAR-TRIAL-NURTURE')
on conflict do nothing;

insert into marketing.campaign_member(tenant_id, campaign_id, lead_id, contact_id, status, responded_at, score_delta)
select c.tenant_id, c.id, null, ct.id, 'RESPONDED', now() - interval '1 day', 12
from marketing.campaign c
join crm.contact ct on ct.tenant_id = c.tenant_id
where c.code = 'FY26-RENEWAL-ABM'
  and ct.email in ('d.farrow@kestrelmfg.com','s.okonkwo@northbrookhs.org')
on conflict do nothing;

insert into service.entitlement
  (tenant_id, account_id, name, tier, status, response_minutes, resolution_minutes, start_date, end_date)
select a.tenant_id, a.id, seed.name, seed.tier, 'ACTIVE', seed.response_minutes, seed.resolution_minutes,
       date '2026-01-01', date '2026-12-31'
from (values
  ('Kestrel Manufacturing', 'Premium support entitlement', 'PREMIUM', 120, 2880),
  ('Northbrook Health Systems', 'Mission critical entitlement', 'MISSION_CRITICAL', 30, 720),
  ('Northstar Test Account', 'Trial support entitlement', 'STANDARD', 240, 4320)
) as seed(account_name, name, tier, response_minutes, resolution_minutes)
join crm.account a on a.name = seed.account_name
on conflict do nothing;

insert into service.case_record
  (tenant_id, case_number, account_id, contact_id, entitlement_id, owner_id, subject, status,
   priority, origin, opened_at, first_response_due_at, resolution_due_at)
select e.tenant_id, seed.case_number, e.account_id, ct.id, e.id, u.id, seed.subject, seed.status,
       seed.priority, seed.origin, now() - seed.open_age,
       now() - seed.open_age + (e.response_minutes || ' minutes')::interval,
       now() - seed.open_age + (e.resolution_minutes || ' minutes')::interval
from (values
  ('CAS-2026-0001', 'Kestrel renewal billing portal question', 'Kestrel Manufacturing', 'd.farrow@kestrelmfg.com', 'WORKING', 'NORMAL', 'EMAIL', interval '4 hours', 'priya.nair@meridianfab.com'),
  ('CAS-2026-0002', 'Northbrook integration outage escalation', 'Northbrook Health Systems', 's.okonkwo@northbrookhs.org', 'ESCALATED', 'URGENT', 'PHONE', interval '2 hours', 'raj.malhotra@meridianfab.com'),
  ('CAS-2026-0003', 'Northstar trial import mapping help', 'Northstar Test Account', null, 'NEW', 'HIGH', 'WEB', interval '30 minutes', 'ava.chen@northstar.example')
) as seed(case_number, subject, account_name, contact_email, status, priority, origin, open_age, owner_email)
join service.entitlement e on e.name like '%' || split_part(seed.account_name, ' ', 1) || '%' or e.account_id = (select id from crm.account where name = seed.account_name limit 1)
join crm.account a on a.tenant_id = e.tenant_id and a.id = e.account_id and a.name = seed.account_name
join identity.app_user u on u.tenant_id = e.tenant_id and u.email = seed.owner_email
left join crm.contact ct on ct.tenant_id = e.tenant_id and ct.email = seed.contact_email
on conflict (tenant_id, case_number) do nothing;

insert into service.case_milestone(tenant_id, case_id, milestone_type, due_at, completed_at, status)
select c.tenant_id, c.id, 'FIRST_RESPONSE', c.first_response_due_at,
       case when c.status <> 'NEW' then c.opened_at + interval '45 minutes' else null end,
       case when c.status = 'NEW' then 'OPEN' when c.opened_at + interval '45 minutes' <= c.first_response_due_at then 'MET' else 'MISSED' end
from service.case_record c
on conflict (tenant_id, case_id, milestone_type) do nothing;

insert into service.case_milestone(tenant_id, case_id, milestone_type, due_at, status)
select c.tenant_id, c.id, 'RESOLUTION', c.resolution_due_at,
       case when c.resolution_due_at < now() then 'MISSED' else 'OPEN' end
from service.case_record c
on conflict (tenant_id, case_id, milestone_type) do nothing;

insert into migration.import_template(tenant_id, object_type, template_name, required_columns, optional_columns)
select t.id, seed.object_type, seed.template_name, seed.required_columns, seed.optional_columns
from platform.tenant t
cross join (values
  ('ACCOUNT', 'Account bulk upload', array['name','industry','owner_email'], array['external_id','phone','website']),
  ('CONTACT', 'Contact bulk upload', array['account_name','first_name','last_name','email'], array['title','phone']),
  ('LEAD', 'Lead bulk upload', array['first_name','last_name','company','email'], array['source','score','owner_email'])
) as seed(object_type, template_name, required_columns, optional_columns)
on conflict (tenant_id, object_type, template_name) do nothing;

insert into migration.import_batch
  (tenant_id, batch_number, object_type, file_name, status, total_rows, valid_rows,
   error_rows, duplicate_rows, imported_rows, uploaded_by, uploaded_at, completed_at)
select u.tenant_id, seed.batch_number, seed.object_type, seed.file_name, seed.status,
       seed.total_rows, seed.valid_rows, seed.error_rows, seed.duplicate_rows, seed.imported_rows,
       u.id, now() - seed.age,
       case when seed.status in ('IMPORTED','FAILED','ROLLED_BACK') then now() - seed.age + interval '20 minutes' else null end
from (values
  ('IMP-2026-0001', 'ACCOUNT', 'industrial-accounts-july.csv', 'IMPORTED', 250, 247, 3, 12, 247, 'raj.malhotra@meridianfab.com', interval '3 days'),
  ('IMP-2026-0002', 'CONTACT', 'northbrook-contacts.csv', 'READY_TO_IMPORT', 88, 86, 2, 5, 0, 'priya.nair@meridianfab.com', interval '1 day'),
  ('IMP-2026-0003', 'LEAD', 'trial-leads.csv', 'VALIDATING', 45, 0, 0, 0, 0, 'ava.chen@northstar.example', interval '2 hours')
) as seed(batch_number, object_type, file_name, status, total_rows, valid_rows, error_rows, duplicate_rows, imported_rows, owner_email, age)
join identity.app_user u on u.email = seed.owner_email
on conflict (tenant_id, batch_number) do nothing;

insert into migration.validation_error(tenant_id, batch_id, row_number, column_name, error_code, message, severity)
select b.tenant_id, b.id, seed.row_number, seed.column_name, seed.error_code, seed.message, seed.severity
from migration.import_batch b
join (values
  ('IMP-2026-0001', 17, 'owner_email', 'UNKNOWN_OWNER', 'Owner email does not resolve to an active user.', 'ERROR'),
  ('IMP-2026-0001', 93, 'name', 'POSSIBLE_DUPLICATE', 'Account resembles Kestrel Manufacturing.', 'WARNING'),
  ('IMP-2026-0002', 12, 'email', 'INVALID_EMAIL', 'Email address is not valid.', 'ERROR')
) as seed(batch_number, row_number, column_name, error_code, message, severity)
  on seed.batch_number = b.batch_number
on conflict do nothing;

-- I18n keys for the five now-navigable modules.
insert into i18n.translation_key(key_path, module_code, description) values
  ('nav.module.contracts', 'SHELL', 'Module: contracts, orders and subscriptions'),
  ('nav.module.forecast', 'SHELL', 'Module: forecasting and revenue intelligence'),
  ('nav.module.campaigns', 'SHELL', 'Module: campaigns and marketing alignment'),
  ('nav.module.cases', 'SHELL', 'Module: service cases and entitlements'),
  ('nav.module.migration', 'SHELL', 'Module: data migration and onboarding')
on conflict (key_path) do nothing;

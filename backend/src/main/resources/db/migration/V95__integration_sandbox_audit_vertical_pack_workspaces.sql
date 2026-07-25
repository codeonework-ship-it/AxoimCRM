-- Final five-surface workspace increment:
--   E17 integration operations, E19 sandbox/release, E20 audit/compliance,
--   E22 BFSI vertical pack, E23 commodity trading vertical pack.
--
-- The implementation remains first-party and preview-safe: connector contracts,
-- release evidence, audit packs and vertical-pack registers are real tenant data;
-- vendor adapters and external execution are intentionally still deferred.

create schema if not exists bfsi;
create schema if not exists commodity;

grant usage on schema bfsi, commodity to axiom_app;
alter role axiom_app set search_path to platform, identity, crm, sales, engagement, governance, reference, billing, reporting, cpq, contracting, forecasting, marketing, service, migration, channel, automation, ai, mobile, bfsi, commodity, integration, i18n, public;

-- ---------------------------------------------------------------------------
-- E17 - integration platform, APIs, webhooks and events
-- ---------------------------------------------------------------------------
create table integration.endpoint_contract (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  contract_code text not null,
  name text not null,
  status text not null check (status in ('DRAFT','ACTIVE','DEPRECATED','RETIRED')),
  direction text not null check (direction in ('INBOUND','OUTBOUND','BIDIRECTIONAL')),
  auth_type text not null check (auth_type in ('SERVICE_TOKEN','OAUTH_STUB','SIGNED_WEBHOOK','NONE')),
  owner_id uuid not null,
  last_verified_at timestamptz,
  failure_count int not null default 0 check (failure_count >= 0),
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, contract_code),
  constraint fk_endpoint_contract_owner_same_tenant foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id)
);

create table integration.integration_job (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  endpoint_contract_id uuid not null,
  job_number text not null,
  object_type text not null,
  status text not null check (status in ('QUEUED','RUNNING','SUCCEEDED','FAILED','RETRYING','PAUSED')),
  records_processed int not null default 0 check (records_processed >= 0),
  records_failed int not null default 0 check (records_failed >= 0),
  started_at timestamptz not null default now(),
  completed_at timestamptz,
  last_error text,
  unique (tenant_id, id),
  unique (tenant_id, job_number),
  constraint fk_integration_job_contract_same_tenant foreign key (tenant_id, endpoint_contract_id) references integration.endpoint_contract(tenant_id, id)
);

create table integration.webhook_subscription_stub (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  endpoint_contract_id uuid not null,
  event_name text not null,
  status text not null check (status in ('DRAFT','ACTIVE','PAUSED','FAILED')),
  target_url text not null,
  last_delivery_at timestamptz,
  delivery_failures int not null default 0 check (delivery_failures >= 0),
  unique (tenant_id, id),
  unique (tenant_id, endpoint_contract_id, event_name),
  constraint fk_webhook_contract_same_tenant foreign key (tenant_id, endpoint_contract_id) references integration.endpoint_contract(tenant_id, id)
);

create index idx_endpoint_contract_status on integration.endpoint_contract(tenant_id, status, direction);
create index idx_integration_job_status on integration.integration_job(tenant_id, status, started_at desc);
create index idx_webhook_subscription_status on integration.webhook_subscription_stub(tenant_id, status, event_name);

-- ---------------------------------------------------------------------------
-- E19 - administration, sandbox and release
-- ---------------------------------------------------------------------------
create table platform.sandbox_environment (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  sandbox_code text not null,
  name text not null,
  sandbox_type text not null check (sandbox_type in ('DEV','QA','UAT','FULL_COPY')),
  status text not null check (status in ('REQUESTED','PROVISIONING','ACTIVE','REFRESHING','FAILED','ARCHIVED')),
  source_environment text not null default 'PROD',
  owner_id uuid not null,
  last_refreshed_at timestamptz,
  expires_at timestamptz,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, sandbox_code),
  constraint fk_sandbox_owner_same_tenant foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id)
);

create table platform.release_package (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  package_code text not null,
  name text not null,
  status text not null check (status in ('DRAFT','READY','APPROVED','DEPLOYED','FAILED','ROLLED_BACK')),
  source_sandbox_id uuid,
  target_environment text not null check (target_environment in ('DEV','QA','UAT','PROD')),
  component_count int not null default 0 check (component_count >= 0),
  approved_by uuid,
  created_at timestamptz not null default now(),
  deployed_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, package_code),
  constraint fk_release_sandbox_same_tenant foreign key (tenant_id, source_sandbox_id) references platform.sandbox_environment(tenant_id, id),
  constraint fk_release_approver_same_tenant foreign key (tenant_id, approved_by) references identity.app_user(tenant_id, id)
);

create table platform.deployment_run (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  release_package_id uuid not null,
  run_number text not null,
  status text not null check (status in ('QUEUED','VALIDATING','SUCCEEDED','FAILED','ROLLED_BACK')),
  started_at timestamptz not null default now(),
  completed_at timestamptz,
  validation_errors int not null default 0 check (validation_errors >= 0),
  summary text not null,
  unique (tenant_id, id),
  unique (tenant_id, run_number),
  constraint fk_deployment_release_same_tenant foreign key (tenant_id, release_package_id) references platform.release_package(tenant_id, id)
);

create index idx_sandbox_status on platform.sandbox_environment(tenant_id, status, sandbox_type);
create index idx_release_status on platform.release_package(tenant_id, status, target_environment);
create index idx_deployment_status on platform.deployment_run(tenant_id, status, started_at desc);

-- ---------------------------------------------------------------------------
-- E20 - audit, compliance, observability and governance
-- ---------------------------------------------------------------------------
create table governance.audit_evidence_pack (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  pack_code text not null,
  name text not null,
  status text not null check (status in ('DRAFT','GENERATING','READY','EXPORTED','FAILED')),
  scope text not null,
  generated_by uuid not null,
  event_count int not null default 0 check (event_count >= 0),
  control_count int not null default 0 check (control_count >= 0),
  generated_at timestamptz,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, pack_code),
  constraint fk_evidence_pack_user_same_tenant foreign key (tenant_id, generated_by) references identity.app_user(tenant_id, id)
);

create table governance.control_review (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  review_code text not null,
  control_name text not null,
  status text not null check (status in ('SCHEDULED','IN_PROGRESS','PASSED','FAILED','WAIVED')),
  owner_id uuid not null,
  due_at timestamptz not null,
  completed_at timestamptz,
  evidence_pack_id uuid,
  finding_count int not null default 0 check (finding_count >= 0),
  unique (tenant_id, id),
  unique (tenant_id, review_code),
  constraint fk_control_review_owner_same_tenant foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id),
  constraint fk_control_review_pack_same_tenant foreign key (tenant_id, evidence_pack_id) references governance.audit_evidence_pack(tenant_id, id)
);

create table governance.observability_signal (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  signal_code text not null,
  name text not null,
  status text not null check (status in ('GREEN','AMBER','RED')),
  service_name text not null,
  current_value numeric(14,2) not null,
  threshold_value numeric(14,2) not null,
  observed_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, signal_code)
);

create index idx_evidence_pack_status on governance.audit_evidence_pack(tenant_id, status, created_at desc);
create index idx_control_review_status on governance.control_review(tenant_id, status, due_at);
create index idx_observability_signal_status on governance.observability_signal(tenant_id, status, observed_at desc);

-- ---------------------------------------------------------------------------
-- E22 - BFSI vertical pack
-- ---------------------------------------------------------------------------
create table bfsi.client_onboarding (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  onboarding_number text not null,
  account_id uuid not null,
  client_type text not null check (client_type in ('RETAIL','SME','CORPORATE','INSTITUTIONAL')),
  kyc_status text not null check (kyc_status in ('NOT_STARTED','IN_PROGRESS','CLEARED','ENHANCED_DUE_DILIGENCE','REJECTED')),
  risk_rating text not null check (risk_rating in ('LOW','MEDIUM','HIGH','PROHIBITED')),
  owner_id uuid not null,
  due_at date not null,
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, onboarding_number),
  constraint fk_bfsi_onboarding_account_same_tenant foreign key (tenant_id, account_id) references crm.account(tenant_id, id),
  constraint fk_bfsi_onboarding_owner_same_tenant foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id)
);

create table bfsi.product_holding (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  onboarding_id uuid not null,
  product_family text not null,
  status text not null check (status in ('PROPOSED','ACTIVE','SUSPENDED','CLOSED')),
  balance_amount numeric(14,2) not null default 0 check (balance_amount >= 0),
  opened_at date,
  unique (tenant_id, id),
  constraint fk_product_holding_onboarding_same_tenant foreign key (tenant_id, onboarding_id) references bfsi.client_onboarding(tenant_id, id)
);

create table bfsi.compliance_screening (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  onboarding_id uuid not null,
  screening_type text not null check (screening_type in ('SANCTIONS','PEP','ADVERSE_MEDIA','SUITABILITY')),
  status text not null check (status in ('PENDING','CLEAR','HIT','WAIVED')),
  hit_count int not null default 0 check (hit_count >= 0),
  screened_at timestamptz,
  unique (tenant_id, id),
  constraint fk_screening_onboarding_same_tenant foreign key (tenant_id, onboarding_id) references bfsi.client_onboarding(tenant_id, id)
);

create index idx_bfsi_onboarding_status on bfsi.client_onboarding(tenant_id, kyc_status, risk_rating);
create index idx_bfsi_screening_status on bfsi.compliance_screening(tenant_id, status, screening_type);

-- ---------------------------------------------------------------------------
-- E23 - commodity trading vertical pack
-- ---------------------------------------------------------------------------
create table commodity.counterparty_profile (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  account_id uuid not null,
  counterparty_code text not null,
  status text not null check (status in ('PROSPECT','ACTIVE','WATCHLIST','SUSPENDED','CLOSED')),
  credit_limit numeric(14,2) not null default 0 check (credit_limit >= 0),
  exposure_amount numeric(14,2) not null default 0 check (exposure_amount >= 0),
  owner_id uuid not null,
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, counterparty_code),
  constraint fk_counterparty_account_same_tenant foreign key (tenant_id, account_id) references crm.account(tenant_id, id),
  constraint fk_counterparty_owner_same_tenant foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id)
);

create table commodity.trade_enquiry (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  enquiry_number text not null,
  counterparty_profile_id uuid not null,
  commodity_name text not null,
  status text not null check (status in ('RECEIVED','PRICING','OFFERED','WON','LOST','EXPIRED')),
  quantity numeric(14,4) not null check (quantity > 0),
  unit text not null,
  notional_amount numeric(14,2) not null default 0 check (notional_amount >= 0),
  delivery_window_start date,
  delivery_window_end date,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, enquiry_number),
  constraint trade_enquiry_delivery_range check (delivery_window_end is null or delivery_window_start is null or delivery_window_end >= delivery_window_start),
  constraint fk_trade_enquiry_counterparty_same_tenant foreign key (tenant_id, counterparty_profile_id) references commodity.counterparty_profile(tenant_id, id)
);

create table commodity.contract_term_sheet (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  trade_enquiry_id uuid not null,
  term_sheet_number text not null,
  status text not null check (status in ('DRAFT','IN_REVIEW','APPROVED','SENT','ACCEPTED','REJECTED')),
  incoterm text not null,
  pricing_basis text not null,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, term_sheet_number),
  constraint fk_term_sheet_enquiry_same_tenant foreign key (tenant_id, trade_enquiry_id) references commodity.trade_enquiry(tenant_id, id)
);

create index idx_counterparty_status on commodity.counterparty_profile(tenant_id, status, updated_at desc);
create index idx_trade_enquiry_status on commodity.trade_enquiry(tenant_id, status, created_at desc);
create index idx_term_sheet_status on commodity.contract_term_sheet(tenant_id, status, created_at desc);

-- ---------------------------------------------------------------------------
-- RLS, grants and governance registration
-- ---------------------------------------------------------------------------
do $$
declare
  table_name text;
begin
  foreach table_name in array array[
    'integration.endpoint_contract','integration.integration_job','integration.webhook_subscription_stub',
    'platform.sandbox_environment','platform.release_package','platform.deployment_run',
    'governance.audit_evidence_pack','governance.control_review','governance.observability_signal',
    'bfsi.client_onboarding','bfsi.product_holding','bfsi.compliance_screening',
    'commodity.counterparty_profile','commodity.trade_enquiry','commodity.contract_term_sheet'
  ] loop
    execute format('alter table %s enable row level security', table_name);
    execute format('alter table %s force row level security', table_name);
    execute format('create policy tenant_isolation on %s using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', table_name);
    execute format('grant select, insert, update on %s to axiom_app', table_name);
  end loop;
end $$;

insert into governance.module_catalog(module_code, schema_name, display_name, description, owner_role) values
  ('BFSI', 'bfsi', 'BFSI vertical pack', 'Financial-services onboarding, holdings and compliance screening.', 'OPERATIONS'),
  ('COMMODITY', 'commodity', 'Commodity trading vertical pack', 'Counterparties, enquiries and commodity term sheets.', 'OPERATIONS')
on conflict (module_code) do nothing;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('integration','endpoint_contract','INTEGRATION','id',true,'ACTIVE'),
  ('integration','integration_job','INTEGRATION','id',true,'APPEND_ONLY'),
  ('integration','webhook_subscription_stub','INTEGRATION','id',true,'ACTIVE'),
  ('platform','sandbox_environment','PLATFORM','id',true,'ACTIVE'),
  ('platform','release_package','PLATFORM','id',true,'ACTIVE'),
  ('platform','deployment_run','PLATFORM','id',true,'APPEND_ONLY'),
  ('governance','audit_evidence_pack','GOVERNANCE','id',true,'ACTIVE'),
  ('governance','control_review','GOVERNANCE','id',true,'ACTIVE'),
  ('governance','observability_signal','GOVERNANCE','id',true,'APPEND_ONLY'),
  ('bfsi','client_onboarding','BFSI','id',true,'ACTIVE'),
  ('bfsi','product_holding','BFSI','id',true,'ACTIVE'),
  ('bfsi','compliance_screening','BFSI','id',true,'APPEND_ONLY'),
  ('commodity','counterparty_profile','COMMODITY','id',true,'ACTIVE'),
  ('commodity','trade_enquiry','COMMODITY','id',true,'ACTIVE'),
  ('commodity','contract_term_sheet','COMMODITY','id',true,'ACTIVE')
on conflict (schema_name, table_name) do nothing;

insert into governance.screen_catalog(screen_code, module_code, route, display_name, description, sort_order) values
  ('INTEGRATIONS', 'INTEGRATION', '/integrations', 'Integrations', 'Endpoint contracts, jobs and webhook subscription stubs.', 121),
  ('SANDBOX', 'PLATFORM', '/sandbox', 'Sandbox & Release', 'Sandbox environments, release packages and deployment evidence.', 122),
  ('AUDIT_COMPLIANCE', 'GOVERNANCE', '/audit', 'Audit & Compliance', 'Evidence packs, control reviews and observability signals.', 123),
  ('BFSI', 'BFSI', '/packs/bfsi', 'BFSI', 'Financial-services onboarding, product holdings and compliance screening.', 151),
  ('COMMODITY', 'COMMODITY', '/packs/commodity', 'Commodity', 'Commodity counterparties, enquiries and term sheets.', 152)
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
       role_code in ('SUPER_ADMIN','TENANT_ADMIN','OPERATIONS','DATA_STEWARD'),
       case when role_code in ('SUPER_ADMIN','SUPER_AUDIT') then 'PLATFORM' else 'TENANT' end
from (values
  ('SUPER_ADMIN'),('SUPER_AUDIT'),('TENANT_ADMIN'),('SALES_MANAGER'),('SALES'),
  ('MARKETING'),('SERVICE'),('OPERATIONS'),('FINANCE'),('DATA_STEWARD'),('AUDITOR'),('INTEGRATION')
) roles(role_code)
cross join (values ('INTEGRATIONS'),('SANDBOX'),('AUDIT_COMPLIANCE'),('BFSI'),('COMMODITY')) screens(screen_code)
on conflict (role_code, screen_code) do update
  set can_read = excluded.can_read,
      can_write = excluded.can_write,
      can_export = excluded.can_export,
      can_admin = excluded.can_admin,
      scope = excluded.scope;

-- ---------------------------------------------------------------------------
-- Seed data
-- ---------------------------------------------------------------------------
insert into integration.endpoint_contract
  (tenant_id, contract_code, name, status, direction, auth_type, owner_id, last_verified_at, failure_count)
select u.tenant_id, seed.contract_code, seed.name, seed.status, seed.direction, seed.auth_type, u.id, now() - seed.age, seed.failures
from (values
  ('INT-OUTBOX-CRM','CRM event outbox contract','ACTIVE','OUTBOUND','SERVICE_TOKEN','raj.malhotra@meridianfab.com',interval '20 minutes',0),
  ('INT-BULK-IMPORT','Bulk import API contract','ACTIVE','INBOUND','SERVICE_TOKEN','priya.nair@meridianfab.com',interval '1 hour',1),
  ('INT-CTRM-STUB','Generic CTRM adapter contract','DRAFT','BIDIRECTIONAL','SIGNED_WEBHOOK','ava.chen@northstar.example',interval '1 day',0)
) as seed(contract_code, name, status, direction, auth_type, owner_email, age, failures)
join identity.app_user u on u.email = seed.owner_email
on conflict (tenant_id, contract_code) do nothing;

insert into integration.integration_job
  (tenant_id, endpoint_contract_id, job_number, object_type, status, records_processed, records_failed, started_at, completed_at, last_error)
select c.tenant_id, c.id, seed.job_number, seed.object_type, seed.status, seed.processed, seed.failed,
       now() - seed.age, case when seed.status in ('SUCCEEDED','FAILED') then now() - seed.age + interval '4 minutes' else null end, seed.error
from integration.endpoint_contract c
join (values
  ('INT-OUTBOX-CRM','JOB-2026-0001','OUTBOX_EVENT','SUCCEEDED',318,0,interval '15 minutes',null),
  ('INT-BULK-IMPORT','JOB-2026-0002','IMPORT_BATCH','RETRYING',88,2,interval '55 minutes','Two rows failed validation; retry is queued.'),
  ('INT-CTRM-STUB','JOB-2026-0003','COUNTERPARTY','PAUSED',0,0,interval '1 day','Adapter intentionally pending.')
) as seed(contract_code, job_number, object_type, status, processed, failed, age, error) on seed.contract_code = c.contract_code
on conflict (tenant_id, job_number) do nothing;

insert into integration.webhook_subscription_stub(tenant_id, endpoint_contract_id, event_name, status, target_url, last_delivery_at, delivery_failures)
select c.tenant_id, c.id, seed.event_name, seed.status, seed.target_url, now() - seed.age, seed.failures
from integration.endpoint_contract c
join (values
  ('INT-OUTBOX-CRM','OpportunityClosedWon','ACTIVE','https://example.invalid/webhooks/opportunity',interval '30 minutes',0),
  ('INT-BULK-IMPORT','ImportBatchReady','ACTIVE','https://example.invalid/webhooks/import',interval '1 hour',1),
  ('INT-CTRM-STUB','CounterpartyExposureChanged','DRAFT','https://example.invalid/webhooks/ctrm',interval '1 day',0)
) as seed(contract_code, event_name, status, target_url, age, failures) on seed.contract_code = c.contract_code
on conflict (tenant_id, endpoint_contract_id, event_name) do nothing;

insert into platform.sandbox_environment
  (tenant_id, sandbox_code, name, sandbox_type, status, source_environment, owner_id, last_refreshed_at, expires_at)
select u.tenant_id, seed.sandbox_code, seed.name, seed.sandbox_type, seed.status, seed.source_env, u.id,
       now() - seed.refreshed_age, now() + seed.expiry
from (values
  ('SBX-DEV-01','Developer configuration sandbox','DEV','ACTIVE','PROD','raj.malhotra@meridianfab.com',interval '2 days',interval '28 days'),
  ('SBX-UAT-01','UAT release rehearsal','UAT','ACTIVE','PROD','priya.nair@meridianfab.com',interval '6 hours',interval '14 days'),
  ('SBX-TRIAL-01','Northstar trial preview','QA','REFRESHING','PROD','ava.chen@northstar.example',interval '1 day',interval '21 days')
) as seed(sandbox_code, name, sandbox_type, status, source_env, owner_email, refreshed_age, expiry)
join identity.app_user u on u.email = seed.owner_email
on conflict (tenant_id, sandbox_code) do nothing;

insert into platform.release_package
  (tenant_id, package_code, name, status, source_sandbox_id, target_environment, component_count, approved_by, created_at, deployed_at)
select s.tenant_id, seed.package_code, seed.name, seed.status, s.id, seed.target_env, seed.components, u.id,
       now() - seed.age, case when seed.status = 'DEPLOYED' then now() - seed.age + interval '2 hours' else null end
from platform.sandbox_environment s
join (values
  ('SBX-UAT-01','REL-2026-0001','CPQ and workspace navigation release','DEPLOYED','PROD',37,'raj.malhotra@meridianfab.com',interval '1 day'),
  ('SBX-DEV-01','REL-2026-0002','Automation policy pack','APPROVED','UAT',18,'raj.malhotra@meridianfab.com',interval '4 hours'),
  ('SBX-TRIAL-01','REL-2026-0003','Trial tenant configuration','READY','QA',11,'ava.chen@northstar.example',interval '8 hours')
) as seed(sandbox_code, package_code, name, status, target_env, components, approver_email, age) on seed.sandbox_code = s.sandbox_code
join identity.app_user u on u.tenant_id = s.tenant_id and u.email = seed.approver_email
on conflict (tenant_id, package_code) do nothing;

insert into platform.deployment_run(tenant_id, release_package_id, run_number, status, started_at, completed_at, validation_errors, summary)
select p.tenant_id, p.id, seed.run_number, seed.status, now() - seed.age,
       case when seed.status in ('SUCCEEDED','FAILED','ROLLED_BACK') then now() - seed.age + interval '12 minutes' else null end,
       seed.errors, seed.summary
from platform.release_package p
join (values
  ('REL-2026-0001','DEP-2026-0001','SUCCEEDED',interval '22 hours',0,'Production deployment completed with schema and web assets aligned.'),
  ('REL-2026-0002','DEP-2026-0002','VALIDATING',interval '25 minutes',0,'UAT validation is running.'),
  ('REL-2026-0003','DEP-2026-0003','FAILED',interval '7 hours',2,'Trial configuration requires two missing reference values.')
) as seed(package_code, run_number, status, age, errors, summary) on seed.package_code = p.package_code
on conflict (tenant_id, run_number) do nothing;

insert into governance.audit_evidence_pack
  (tenant_id, pack_code, name, status, scope, generated_by, event_count, control_count, generated_at)
select u.tenant_id, seed.pack_code, seed.name, seed.status, seed.scope, u.id, seed.events, seed.controls,
       case when seed.status in ('READY','EXPORTED') then now() - seed.age else null end
from (values
  ('AUD-2026-0001','Quarterly access evidence pack','READY','RBAC, exports and tenant switching','raj.malhotra@meridianfab.com',1842,12,interval '3 hours'),
  ('AUD-2026-0002','Data migration evidence pack','GENERATING','Imports, validation errors and rollback readiness','priya.nair@meridianfab.com',612,6,interval '30 minutes'),
  ('AUD-2026-0003','Northstar trial evidence pack','EXPORTED','Trial access, billing status and admin changes','ava.chen@northstar.example',128,4,interval '1 day')
) as seed(pack_code, name, status, scope, user_email, events, controls, age)
join identity.app_user u on u.email = seed.user_email
on conflict (tenant_id, pack_code) do nothing;

insert into governance.control_review
  (tenant_id, review_code, control_name, status, owner_id, due_at, completed_at, evidence_pack_id, finding_count)
select u.tenant_id, seed.review_code, seed.control_name, seed.status, u.id, now() + seed.due_in,
       case when seed.status in ('PASSED','FAILED','WAIVED') then now() - interval '1 hour' else null end,
       p.id, seed.findings
from (values
  ('GRC-2026-0001','Super-audit read-only verification','PASSED','raj.malhotra@meridianfab.com','AUD-2026-0001',interval '7 days',0),
  ('GRC-2026-0002','Large export approval threshold','IN_PROGRESS','priya.nair@meridianfab.com','AUD-2026-0001',interval '2 days',1),
  ('GRC-2026-0003','Trial tenant expiry governance','SCHEDULED','ava.chen@northstar.example','AUD-2026-0003',interval '14 days',0)
) as seed(review_code, control_name, status, owner_email, pack_code, due_in, findings)
join identity.app_user u on u.email = seed.owner_email
left join governance.audit_evidence_pack p on p.tenant_id = u.tenant_id and p.pack_code = seed.pack_code
on conflict (tenant_id, review_code) do nothing;

insert into governance.observability_signal
  (tenant_id, signal_code, name, status, service_name, current_value, threshold_value, observed_at)
select t.id, seed.signal_code, seed.name, seed.status, seed.service_name, seed.current_value, seed.threshold_value, now() - seed.age
from platform.tenant t
cross join (values
  ('OBS-API-LATENCY','API p95 latency','GREEN','axiom-api',185.00::numeric,500.00::numeric,interval '5 minutes'),
  ('OBS-OUTBOX-LAG','Kafka outbox lag','AMBER','outbox-relay',42.00::numeric,30.00::numeric,interval '5 minutes'),
  ('OBS-WEB-ERRORS','Web client error rate','GREEN','axiom-web',0.20::numeric,1.00::numeric,interval '5 minutes')
) as seed(signal_code, name, status, service_name, current_value, threshold_value, age)
on conflict (tenant_id, signal_code) do nothing;

insert into bfsi.client_onboarding
  (tenant_id, onboarding_number, account_id, client_type, kyc_status, risk_rating, owner_id, due_at, completed_at)
select a.tenant_id, seed.onboarding_number, a.id, seed.client_type, seed.kyc_status, seed.risk_rating, u.id,
       current_date + seed.due_days, case when seed.kyc_status = 'CLEARED' then now() - interval '1 day' else null end
from (values
  ('BFSI-2026-0001','Kestrel Manufacturing','CORPORATE','CLEARED','MEDIUM','priya.nair@meridianfab.com',30),
  ('BFSI-2026-0002','Northbrook Health Systems','INSTITUTIONAL','ENHANCED_DUE_DILIGENCE','HIGH','raj.malhotra@meridianfab.com',10),
  ('BFSI-2026-0003','Northstar Test Account','SME','IN_PROGRESS','LOW','ava.chen@northstar.example',14)
) as seed(onboarding_number, account_name, client_type, kyc_status, risk_rating, owner_email, due_days)
join crm.account a on a.name = seed.account_name
join identity.app_user u on u.tenant_id = a.tenant_id and u.email = seed.owner_email
on conflict (tenant_id, onboarding_number) do nothing;

insert into bfsi.product_holding(tenant_id, onboarding_id, product_family, status, balance_amount, opened_at)
select o.tenant_id, o.id, seed.product_family, seed.status, seed.balance_amount, current_date - seed.age_days
from bfsi.client_onboarding o
join (values
  ('BFSI-2026-0001','Treasury services','ACTIVE',1250000.00::numeric,90),
  ('BFSI-2026-0002','Working capital facility','PROPOSED',5000000.00::numeric,0),
  ('BFSI-2026-0003','Trial advisory pack','PROPOSED',25000.00::numeric,0)
) as seed(onboarding_number, product_family, status, balance_amount, age_days) on seed.onboarding_number = o.onboarding_number
on conflict do nothing;

insert into bfsi.compliance_screening(tenant_id, onboarding_id, screening_type, status, hit_count, screened_at)
select o.tenant_id, o.id, seed.screening_type, seed.status, seed.hit_count, now() - seed.age
from bfsi.client_onboarding o
join (values
  ('BFSI-2026-0001','SANCTIONS','CLEAR',0,interval '1 day'),
  ('BFSI-2026-0002','ADVERSE_MEDIA','HIT',2,interval '4 hours'),
  ('BFSI-2026-0003','SUITABILITY','PENDING',0,interval '2 hours')
) as seed(onboarding_number, screening_type, status, hit_count, age) on seed.onboarding_number = o.onboarding_number
on conflict do nothing;

insert into commodity.counterparty_profile
  (tenant_id, account_id, counterparty_code, status, credit_limit, exposure_amount, owner_id, updated_at)
select a.tenant_id, a.id, seed.counterparty_code, seed.status, seed.credit_limit, seed.exposure_amount, u.id, now() - seed.age
from (values
  ('Castellan Freight Co.','CP-CASTELLAN','ACTIVE',1500000.00::numeric,640000.00::numeric,'maya.torres@meridianfab.com',interval '2 hours'),
  ('Bramwell Logistics','CP-BRAMWELL','WATCHLIST',800000.00::numeric,760000.00::numeric,'priya.nair@meridianfab.com',interval '5 hours'),
  ('Northstar Test Account','CP-NORTHSTAR','PROSPECT',250000.00::numeric,0.00::numeric,'ava.chen@northstar.example',interval '1 day')
) as seed(account_name, counterparty_code, status, credit_limit, exposure_amount, owner_email, age)
join crm.account a on a.name = seed.account_name
join identity.app_user u on u.tenant_id = a.tenant_id and u.email = seed.owner_email
on conflict (tenant_id, counterparty_code) do nothing;

insert into commodity.trade_enquiry
  (tenant_id, enquiry_number, counterparty_profile_id, commodity_name, status, quantity, unit, notional_amount, delivery_window_start, delivery_window_end)
select p.tenant_id, seed.enquiry_number, p.id, seed.commodity_name, seed.status, seed.quantity, seed.unit,
       seed.notional_amount, current_date + seed.start_days, current_date + seed.end_days
from commodity.counterparty_profile p
join (values
  ('CP-CASTELLAN','CTR-ENQ-2026-0001','Aluminium billet','PRICING',500.0000::numeric,'MT',1125000.00::numeric,20,45),
  ('CP-BRAMWELL','CTR-ENQ-2026-0002','Copper cathode','OFFERED',120.0000::numeric,'MT',985000.00::numeric,12,28),
  ('CP-NORTHSTAR','CTR-ENQ-2026-0003','Steel coil','RECEIVED',80.0000::numeric,'MT',180000.00::numeric,30,60)
) as seed(counterparty_code, enquiry_number, commodity_name, status, quantity, unit, notional_amount, start_days, end_days) on seed.counterparty_code = p.counterparty_code
on conflict (tenant_id, enquiry_number) do nothing;

insert into commodity.contract_term_sheet(tenant_id, trade_enquiry_id, term_sheet_number, status, incoterm, pricing_basis)
select e.tenant_id, e.id, seed.term_sheet_number, seed.status, seed.incoterm, seed.pricing_basis
from commodity.trade_enquiry e
join (values
  ('CTR-ENQ-2026-0001','TS-2026-0001','IN_REVIEW','CIF','LME 3M plus premium'),
  ('CTR-ENQ-2026-0002','TS-2026-0002','SENT','FOB','Fixed price valid 48 hours'),
  ('CTR-ENQ-2026-0003','TS-2026-0003','DRAFT','DAP','Index average month of shipment')
) as seed(enquiry_number, term_sheet_number, status, incoterm, pricing_basis) on seed.enquiry_number = e.enquiry_number
on conflict (tenant_id, term_sheet_number) do nothing;

insert into i18n.translation_key(key_path, module_code, description) values
  ('nav.module.integrations', 'SHELL', 'Module: integration operations'),
  ('nav.module.sandbox', 'SHELL', 'Module: sandbox and release'),
  ('nav.module.audit', 'SHELL', 'Module: audit and compliance'),
  ('nav.module.bfsi', 'SHELL', 'Module: BFSI vertical pack'),
  ('nav.module.commodity', 'SHELL', 'Module: commodity trading vertical pack')
on conflict (key_path) do nothing;

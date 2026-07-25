-- Second five-epic operational workspace increment:
--   E13 partner/channel, E14 automation, E15 analytics dashboards,
--   E16 AI copilot foundations, E21 mobile/offline readiness.
--
-- This migration deliberately implements first-party foundations only. External
-- partner portals, vendor AI providers, native mobile-store builds and webhook
-- execution adapters stay outside this slice until their integration epics are
-- started.

create schema if not exists channel;
create schema if not exists automation;
create schema if not exists ai;
create schema if not exists mobile;

grant usage on schema channel, automation, ai, mobile to axiom_app;
alter role axiom_app set search_path to platform, identity, crm, sales, engagement, governance, reference, billing, reporting, cpq, contracting, forecasting, marketing, service, migration, channel, automation, ai, mobile, integration, i18n, public;

-- ---------------------------------------------------------------------------
-- E13 - partner, channel and territory management
-- ---------------------------------------------------------------------------
create table channel.partner_account (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  account_id uuid not null,
  partner_code text not null,
  tier text not null check (tier in ('REGISTERED','SILVER','GOLD','PLATINUM')),
  status text not null check (status in ('ONBOARDING','ACTIVE','SUSPENDED','TERMINATED')),
  manager_id uuid not null,
  sourced_pipeline numeric(14,2) not null default 0 check (sourced_pipeline >= 0),
  influenced_pipeline numeric(14,2) not null default 0 check (influenced_pipeline >= 0),
  active_deal_count int not null default 0 check (active_deal_count >= 0),
  territory_scope text not null default 'GLOBAL',
  created_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, partner_code),
  unique (tenant_id, account_id),
  constraint fk_partner_account_same_tenant foreign key (tenant_id, account_id) references crm.account(tenant_id, id),
  constraint fk_partner_manager_same_tenant foreign key (tenant_id, manager_id) references identity.app_user(tenant_id, id)
);

create table channel.deal_registration (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  partner_account_id uuid not null,
  opportunity_id uuid,
  registration_number text not null,
  customer_name text not null,
  deal_name text not null,
  status text not null check (status in ('SUBMITTED','APPROVED','REJECTED','EXPIRED','CONVERTED')),
  amount numeric(14,2) not null default 0 check (amount >= 0),
  submitted_at timestamptz not null default now(),
  approved_at timestamptz,
  protection_expires_at timestamptz,
  approval_sla_due_at timestamptz not null,
  unique (tenant_id, id),
  unique (tenant_id, registration_number),
  constraint fk_deal_registration_partner_same_tenant foreign key (tenant_id, partner_account_id) references channel.partner_account(tenant_id, id),
  constraint fk_deal_registration_opp_same_tenant foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id)
);

create table channel.channel_conflict (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  deal_registration_id uuid not null,
  conflict_type text not null check (conflict_type in ('DUPLICATE_CUSTOMER','DIRECT_SALES_OVERLAP','PARTNER_OVERLAP','TERRITORY_MISMATCH')),
  status text not null check (status in ('OPEN','RESOLVED','WAIVED')),
  reason text not null,
  resolved_at timestamptz,
  unique (tenant_id, id),
  constraint fk_channel_conflict_registration_same_tenant foreign key (tenant_id, deal_registration_id) references channel.deal_registration(tenant_id, id)
);

create index idx_partner_status on channel.partner_account(tenant_id, status, tier) where deleted_at is null;
create index idx_deal_registration_status on channel.deal_registration(tenant_id, status, submitted_at desc);
create index idx_channel_conflict_status on channel.channel_conflict(tenant_id, status);

-- ---------------------------------------------------------------------------
-- E14 - workflow automation, approvals and rules engine
-- ---------------------------------------------------------------------------
create table automation.automation_rule (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  rule_code text not null,
  name text not null,
  trigger_type text not null check (trigger_type in ('RECORD_CHANGE','SCHEDULED','DATE_RELATIVE','MANUAL')),
  object_type text not null,
  status text not null check (status in ('DRAFT','ACTIVE','PAUSED','RETIRED')),
  active_version int not null default 1 check (active_version > 0),
  owner_id uuid not null,
  simulation_passed boolean not null default false,
  run_count int not null default 0 check (run_count >= 0),
  last_run_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, rule_code),
  constraint fk_automation_rule_owner_same_tenant foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id)
);

create table automation.automation_step (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  rule_id uuid not null,
  step_order int not null check (step_order > 0),
  step_type text not null check (step_type in ('CONDITION','UPDATE_FIELD','CREATE_TASK','SEND_NOTIFICATION','APPROVAL','WEBHOOK_STUB')),
  label text not null,
  configuration jsonb not null default '{}'::jsonb,
  unique (tenant_id, id),
  unique (tenant_id, rule_id, step_order),
  constraint fk_automation_step_rule_same_tenant foreign key (tenant_id, rule_id) references automation.automation_rule(tenant_id, id)
);

create table automation.automation_run (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  rule_id uuid not null,
  run_number text not null,
  status text not null check (status in ('QUEUED','RUNNING','SUCCEEDED','FAILED','SIMULATED')),
  records_evaluated int not null default 0 check (records_evaluated >= 0),
  records_updated int not null default 0 check (records_updated >= 0),
  error_count int not null default 0 check (error_count >= 0),
  started_at timestamptz not null default now(),
  completed_at timestamptz,
  trace_summary jsonb not null default '{}'::jsonb,
  unique (tenant_id, id),
  unique (tenant_id, run_number),
  constraint fk_automation_run_rule_same_tenant foreign key (tenant_id, rule_id) references automation.automation_rule(tenant_id, id)
);

create index idx_automation_rule_status on automation.automation_rule(tenant_id, status, object_type);
create index idx_automation_run_status on automation.automation_run(tenant_id, status, started_at desc);

-- ---------------------------------------------------------------------------
-- E15 - reporting, dashboards and analytics
-- ---------------------------------------------------------------------------
create table reporting.analytics_dashboard (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  dashboard_code text not null,
  name text not null,
  status text not null check (status in ('DRAFT','ACTIVE','ARCHIVED')),
  owner_id uuid not null,
  refresh_interval_minutes int not null default 60 check (refresh_interval_minutes > 0),
  last_refreshed_at timestamptz,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, dashboard_code),
  constraint fk_dashboard_owner_same_tenant foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id)
);

create table reporting.dashboard_widget (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  dashboard_id uuid not null,
  title text not null,
  visualization_type text not null check (visualization_type in ('KPI','BAR','LINE','FUNNEL','TABLE')),
  source_module text not null,
  metric_code text not null,
  metric_value numeric(14,2) not null default 0,
  sort_order int not null default 10,
  unique (tenant_id, id),
  constraint fk_dashboard_widget_dashboard_same_tenant foreign key (tenant_id, dashboard_id) references reporting.analytics_dashboard(tenant_id, id)
);

create table reporting.kpi_definition (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  kpi_code text not null,
  name text not null,
  formula text not null,
  status text not null check (status in ('DRAFT','ACTIVE','RETIRED')),
  owner_id uuid not null,
  current_value numeric(14,2) not null default 0,
  target_value numeric(14,2),
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, kpi_code),
  constraint fk_kpi_owner_same_tenant foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id)
);

create index idx_dashboard_status on reporting.analytics_dashboard(tenant_id, status, last_refreshed_at desc);
create index idx_kpi_status on reporting.kpi_definition(tenant_id, status, updated_at desc);

-- ---------------------------------------------------------------------------
-- E16 - AI copilot and agentic assistance
-- ---------------------------------------------------------------------------
create table ai.copilot_prompt (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  prompt_code text not null,
  title text not null,
  use_case text not null check (use_case in ('SUMMARY','NEXT_BEST_ACTION','DRAFT','QUERY','SCORE_EXPLANATION')),
  status text not null check (status in ('DRAFT','ACTIVE','DISABLED')),
  model_policy text not null default 'PROVIDER_PENDING',
  grounding_scope text not null default 'TENANT_RBAC',
  owner_id uuid not null,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, prompt_code),
  constraint fk_copilot_prompt_owner_same_tenant foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id)
);

create table ai.copilot_recommendation (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  recommendation_number text not null,
  prompt_id uuid not null,
  related_entity_type text not null,
  related_entity_id uuid,
  title text not null,
  status text not null check (status in ('READY','ACCEPTED','DISMISSED','EXPIRED')),
  confidence_pct numeric(5,2) not null check (confidence_pct between 0 and 100),
  explanation text not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, recommendation_number),
  constraint fk_recommendation_prompt_same_tenant foreign key (tenant_id, prompt_id) references ai.copilot_prompt(tenant_id, id)
);

create table ai.grounding_citation (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  recommendation_id uuid not null,
  source_entity_type text not null,
  source_entity_id uuid,
  source_label text not null,
  relevance_score numeric(5,2) not null check (relevance_score between 0 and 100),
  unique (tenant_id, id),
  constraint fk_citation_recommendation_same_tenant foreign key (tenant_id, recommendation_id) references ai.copilot_recommendation(tenant_id, id)
);

create index idx_copilot_prompt_status on ai.copilot_prompt(tenant_id, status, use_case);
create index idx_recommendation_status on ai.copilot_recommendation(tenant_id, status, created_at desc);

-- ---------------------------------------------------------------------------
-- E21 - mobile and offline field access
-- ---------------------------------------------------------------------------
create table mobile.mobile_profile (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  profile_code text not null,
  name text not null,
  status text not null check (status in ('DRAFT','ACTIVE','RETIRED')),
  role_code text not null,
  offline_object_set text[] not null default '{}',
  max_offline_days int not null default 7 check (max_offline_days between 1 and 30),
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, profile_code)
);

create table mobile.device_session (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  user_id uuid not null,
  device_label text not null,
  platform text not null check (platform in ('IOS','ANDROID','WEB_RESPONSIVE','DESKTOP')),
  status text not null check (status in ('ACTIVE','LOCKED','WIPED','EXPIRED')),
  last_sync_at timestamptz,
  offline_queue_count int not null default 0 check (offline_queue_count >= 0),
  app_version text not null,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_device_session_user_same_tenant foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

create table mobile.offline_sync_package (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  device_session_id uuid not null,
  package_number text not null,
  status text not null check (status in ('QUEUED','SYNCED','CONFLICT','FAILED')),
  object_count int not null default 0 check (object_count >= 0),
  payload_bytes int not null default 0 check (payload_bytes >= 0),
  generated_at timestamptz not null default now(),
  applied_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, package_number),
  constraint fk_sync_package_device_same_tenant foreign key (tenant_id, device_session_id) references mobile.device_session(tenant_id, id)
);

create index idx_mobile_profile_status on mobile.mobile_profile(tenant_id, status, role_code);
create index idx_device_session_status on mobile.device_session(tenant_id, status, last_sync_at desc);
create index idx_sync_package_status on mobile.offline_sync_package(tenant_id, status, generated_at desc);

-- ---------------------------------------------------------------------------
-- RLS, grants and governance registration
-- ---------------------------------------------------------------------------
do $$
declare
  table_name text;
begin
  foreach table_name in array array[
    'channel.partner_account','channel.deal_registration','channel.channel_conflict',
    'automation.automation_rule','automation.automation_step','automation.automation_run',
    'reporting.analytics_dashboard','reporting.dashboard_widget','reporting.kpi_definition',
    'ai.copilot_prompt','ai.copilot_recommendation','ai.grounding_citation',
    'mobile.mobile_profile','mobile.device_session','mobile.offline_sync_package'
  ] loop
    execute format('alter table %s enable row level security', table_name);
    execute format('alter table %s force row level security', table_name);
    execute format('create policy tenant_isolation on %s using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', table_name);
    execute format('grant select, insert, update on %s to axiom_app', table_name);
  end loop;
end $$;

insert into governance.module_catalog(module_code, schema_name, display_name, description, owner_role) values
  ('CHANNEL', 'channel', 'Partners and channel', 'Partner accounts, deal registrations and channel conflict evidence.', 'SALES_MANAGER'),
  ('AUTOMATION', 'automation', 'Automation', 'Rules, steps, simulation evidence and execution traces.', 'OPERATIONS'),
  ('AI', 'ai', 'AI Copilot', 'Grounded prompts, recommendations and citation evidence.', 'OPERATIONS'),
  ('MOBILE', 'mobile', 'Mobile and offline', 'Responsive/mobile profiles, device sessions and offline sync evidence.', 'OPERATIONS')
on conflict (module_code) do nothing;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('channel','partner_account','CHANNEL','id',true,'SOFT_DELETE'),
  ('channel','deal_registration','CHANNEL','id',true,'ACTIVE'),
  ('channel','channel_conflict','CHANNEL','id',true,'APPEND_ONLY'),
  ('automation','automation_rule','AUTOMATION','id',true,'ACTIVE'),
  ('automation','automation_step','AUTOMATION','id',true,'ACTIVE'),
  ('automation','automation_run','AUTOMATION','id',true,'APPEND_ONLY'),
  ('reporting','analytics_dashboard','REPORTING','id',true,'ACTIVE'),
  ('reporting','dashboard_widget','REPORTING','id',true,'ACTIVE'),
  ('reporting','kpi_definition','REPORTING','id',true,'ACTIVE'),
  ('ai','copilot_prompt','AI','id',true,'ACTIVE'),
  ('ai','copilot_recommendation','AI','id',true,'ACTIVE'),
  ('ai','grounding_citation','AI','id',true,'APPEND_ONLY'),
  ('mobile','mobile_profile','MOBILE','id',true,'ACTIVE'),
  ('mobile','device_session','MOBILE','id',true,'ACTIVE'),
  ('mobile','offline_sync_package','MOBILE','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

insert into governance.screen_catalog(screen_code, module_code, route, display_name, description, sort_order) values
  ('PARTNERS', 'CHANNEL', '/partners', 'Partners', 'Partner accounts, registered deals and channel conflicts.', 47),
  ('AUTOMATION', 'AUTOMATION', '/automation', 'Automation', 'Rules, simulations and execution trace health.', 120),
  ('ANALYTICS', 'REPORTING', '/analytics', 'Analytics', 'Dashboards, KPI definitions and refresh health.', 61),
  ('COPILOT', 'AI', '/copilot', 'AI Copilot', 'Grounded AI recommendations, prompts and citations.', 62),
  ('MOBILE', 'MOBILE', '/mobile', 'Mobile', 'Responsive profiles, device sessions and offline sync packages.', 140)
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
cross join (values ('PARTNERS'),('AUTOMATION'),('ANALYTICS'),('COPILOT'),('MOBILE')) screens(screen_code)
on conflict (role_code, screen_code) do update
  set can_read = excluded.can_read,
      can_write = excluded.can_write,
      can_export = excluded.can_export,
      can_admin = excluded.can_admin,
      scope = excluded.scope;

-- ---------------------------------------------------------------------------
-- Seed data
-- ---------------------------------------------------------------------------
insert into channel.partner_account
  (tenant_id, account_id, partner_code, tier, status, manager_id, sourced_pipeline, influenced_pipeline, active_deal_count, territory_scope)
select a.tenant_id, a.id, seed.partner_code, seed.tier, seed.status, u.id, seed.sourced, seed.influenced, seed.deals, seed.territory
from (values
  ('Kestrel Manufacturing', 'PTR-KESTREL', 'GOLD', 'ACTIVE', 'priya.nair@meridianfab.com', 285000.00::numeric, 410000.00::numeric, 3, 'EMEA Industrial'),
  ('Castellan Freight Co.', 'PTR-CASTELLAN', 'SILVER', 'ONBOARDING', 'maya.torres@meridianfab.com', 148000.00::numeric, 198000.00::numeric, 2, 'Logistics'),
  ('Northstar Test Account', 'PTR-NORTHSTAR', 'REGISTERED', 'ACTIVE', 'ava.chen@northstar.example', 25000.00::numeric, 45000.00::numeric, 1, 'Trial')
) as seed(account_name, partner_code, tier, status, manager_email, sourced, influenced, deals, territory)
join crm.account a on a.name = seed.account_name
join identity.app_user u on u.tenant_id = a.tenant_id and u.email = seed.manager_email
on conflict (tenant_id, partner_code) do nothing;

insert into channel.deal_registration
  (tenant_id, partner_account_id, opportunity_id, registration_number, customer_name, deal_name, status,
   amount, submitted_at, approved_at, protection_expires_at, approval_sla_due_at)
select p.tenant_id, p.id, o.id, seed.registration_number, seed.customer_name, seed.deal_name, seed.status,
       seed.amount, now() - seed.age,
       case when seed.status in ('APPROVED','CONVERTED') then now() - seed.age + interval '4 hours' else null end,
       case when seed.status in ('APPROVED','CONVERTED') then now() + interval '60 days' else null end,
       now() - seed.age + interval '2 days'
from (values
  ('PTR-KESTREL','REG-2026-0001','Kestrel Manufacturing','Kestrel shop-floor expansion','APPROVED',186000.00::numeric,interval '5 days','66666666-6666-6666-6666-666666666602'::uuid),
  ('PTR-CASTELLAN','REG-2026-0002','Castellan Freight Co.','Fleet operations command center','SUBMITTED',148000.00::numeric,interval '18 hours',null::uuid),
  ('PTR-NORTHSTAR','REG-2026-0003','Northstar Test Account','Trial partner onboarding','CONVERTED',25000.00::numeric,interval '2 days',null::uuid)
) as seed(partner_code, registration_number, customer_name, deal_name, status, amount, age, opportunity_id)
join channel.partner_account p on p.partner_code = seed.partner_code
left join sales.opportunity o on o.tenant_id = p.tenant_id and o.id = seed.opportunity_id
on conflict (tenant_id, registration_number) do nothing;

insert into channel.channel_conflict(tenant_id, deal_registration_id, conflict_type, status, reason, resolved_at)
select r.tenant_id, r.id, seed.conflict_type, seed.status, seed.reason,
       case when seed.status in ('RESOLVED','WAIVED') then now() - interval '1 day' else null end
from channel.deal_registration r
join (values
  ('REG-2026-0001','DIRECT_SALES_OVERLAP','RESOLVED','Direct opportunity owner accepted partner sourced influence.'),
  ('REG-2026-0002','TERRITORY_MISMATCH','OPEN','Registered region does not match Castellan assigned territory.')
) as seed(registration_number, conflict_type, status, reason) on seed.registration_number = r.registration_number
on conflict do nothing;

insert into automation.automation_rule
  (tenant_id, rule_code, name, trigger_type, object_type, status, active_version, owner_id,
   simulation_passed, run_count, last_run_at)
select u.tenant_id, seed.rule_code, seed.name, seed.trigger_type, seed.object_type, seed.status,
       seed.version, u.id, seed.simulation_passed, seed.run_count, now() - seed.last_age
from (values
  ('AUT-RENEWAL-RISK','Renewal risk notification','DATE_RELATIVE','CONTRACT','ACTIVE',3,'raj.malhotra@meridianfab.com',true,42,interval '6 hours'),
  ('AUT-CASE-ESCALATION','Case SLA escalation','RECORD_CHANGE','CASE','ACTIVE',2,'raj.malhotra@meridianfab.com',true,27,interval '2 hours'),
  ('AUT-CAMPAIGN-MQL','Campaign MQL handoff','RECORD_CHANGE','CAMPAIGN_MEMBER','PAUSED',1,'priya.nair@meridianfab.com',false,8,interval '4 days')
) as seed(rule_code, name, trigger_type, object_type, status, version, owner_email, simulation_passed, run_count, last_age)
join identity.app_user u on u.email = seed.owner_email
on conflict (tenant_id, rule_code) do nothing;

insert into automation.automation_step(tenant_id, rule_id, step_order, step_type, label, configuration)
select r.tenant_id, r.id, seed.step_order, seed.step_type, seed.label, seed.configuration::jsonb
from automation.automation_rule r
join (values
  ('AUT-RENEWAL-RISK',1,'CONDITION','Contract renewal window reached','{"daysBeforeEnd":60}'),
  ('AUT-RENEWAL-RISK',2,'SEND_NOTIFICATION','Notify owner and manager','{"priority":"HIGH"}'),
  ('AUT-CASE-ESCALATION',1,'CONDITION','SLA milestone missed','{"milestone":"RESOLUTION"}'),
  ('AUT-CASE-ESCALATION',2,'APPROVAL','Escalation approval trace','{"approverRole":"SERVICE_MANAGER"}')
) as seed(rule_code, step_order, step_type, label, configuration) on seed.rule_code = r.rule_code
on conflict (tenant_id, rule_id, step_order) do nothing;

insert into automation.automation_run
  (tenant_id, rule_id, run_number, status, records_evaluated, records_updated, error_count, started_at, completed_at, trace_summary)
select r.tenant_id, r.id, seed.run_number, seed.status, seed.evaluated, seed.updated, seed.errors,
       now() - seed.age, now() - seed.age + interval '90 seconds', seed.trace::jsonb
from automation.automation_rule r
join (values
  ('AUT-RENEWAL-RISK','RUN-2026-0001','SUCCEEDED',18,4,0,interval '6 hours','{"branches":["windowMatched","notificationQueued"]}'),
  ('AUT-CASE-ESCALATION','RUN-2026-0002','SUCCEEDED',7,2,0,interval '2 hours','{"branches":["slaMissed","approvalSubmitted"]}'),
  ('AUT-CAMPAIGN-MQL','RUN-2026-0003','SIMULATED',23,0,1,interval '1 day','{"branches":["mqlMatched"],"warning":"rulePaused"}')
) as seed(rule_code, run_number, status, evaluated, updated, errors, age, trace) on seed.rule_code = r.rule_code
on conflict (tenant_id, run_number) do nothing;

insert into reporting.analytics_dashboard
  (tenant_id, dashboard_code, name, status, owner_id, refresh_interval_minutes, last_refreshed_at)
select u.tenant_id, seed.dashboard_code, seed.name, seed.status, u.id, seed.refresh_minutes, now() - seed.age
from (values
  ('REV-COMMAND','Revenue command dashboard','ACTIVE','raj.malhotra@meridianfab.com',30,interval '20 minutes'),
  ('SERVICE-HEALTH','Service health dashboard','ACTIVE','priya.nair@meridianfab.com',60,interval '45 minutes'),
  ('TRIAL-ONBOARDING','Trial onboarding dashboard','ACTIVE','ava.chen@northstar.example',120,interval '1 hour')
) as seed(dashboard_code, name, status, owner_email, refresh_minutes, age)
join identity.app_user u on u.email = seed.owner_email
on conflict (tenant_id, dashboard_code) do nothing;

insert into reporting.dashboard_widget
  (tenant_id, dashboard_id, title, visualization_type, source_module, metric_code, metric_value, sort_order)
select d.tenant_id, d.id, seed.title, seed.visualization_type, seed.source_module, seed.metric_code, seed.metric_value, seed.sort_order
from reporting.analytics_dashboard d
join (values
  ('REV-COMMAND','Open pipeline','KPI','SALES','OPEN_PIPELINE',2490800.00::numeric,10),
  ('REV-COMMAND','Forecast commit','FUNNEL','FORECASTING','COMMIT',625000.00::numeric,20),
  ('SERVICE-HEALTH','Escalated cases','KPI','SERVICE','ESCALATED_CASES',1.00::numeric,10),
  ('TRIAL-ONBOARDING','Import readiness','BAR','MIGRATION','READY_IMPORTS',1.00::numeric,10)
) as seed(dashboard_code, title, visualization_type, source_module, metric_code, metric_value, sort_order) on seed.dashboard_code = d.dashboard_code
on conflict do nothing;

insert into reporting.kpi_definition
  (tenant_id, kpi_code, name, formula, status, owner_id, current_value, target_value, updated_at)
select u.tenant_id, seed.kpi_code, seed.name, seed.formula, 'ACTIVE', u.id, seed.current_value, seed.target_value, now() - seed.age
from (values
  ('PIPELINE_COVERAGE','Pipeline coverage','open_pipeline / quota',2.80::numeric,3.00::numeric,'raj.malhotra@meridianfab.com',interval '15 minutes'),
  ('SLA_MISS_RATE','SLA miss rate','missed_milestones / total_milestones',6.70::numeric,3.00::numeric,'raj.malhotra@meridianfab.com',interval '45 minutes'),
  ('TRIAL_ACTIVATION','Trial activation','completed_onboarding_steps / required_steps',72.00::numeric,85.00::numeric,'ava.chen@northstar.example',interval '1 hour')
) as seed(kpi_code, name, formula, current_value, target_value, owner_email, age)
join identity.app_user u on u.email = seed.owner_email
on conflict (tenant_id, kpi_code) do nothing;

insert into ai.copilot_prompt
  (tenant_id, prompt_code, title, use_case, status, model_policy, grounding_scope, owner_id)
select u.tenant_id, seed.prompt_code, seed.title, seed.use_case, seed.status, 'PROVIDER_PENDING', 'TENANT_RBAC', u.id
from (values
  ('AIX-ACCOUNT-SUMMARY','Account 360 executive summary','SUMMARY','ACTIVE','priya.nair@meridianfab.com'),
  ('AIX-NEXT-BEST-ACTION','Next best action for at-risk deals','NEXT_BEST_ACTION','ACTIVE','raj.malhotra@meridianfab.com'),
  ('AIX-MOBILE-DRAFT','Mobile meeting follow-up draft','DRAFT','DISABLED','ava.chen@northstar.example')
) as seed(prompt_code, title, use_case, status, owner_email)
join identity.app_user u on u.email = seed.owner_email
on conflict (tenant_id, prompt_code) do nothing;

insert into ai.copilot_recommendation
  (tenant_id, recommendation_number, prompt_id, related_entity_type, related_entity_id, title, status, confidence_pct, explanation, created_at, expires_at)
select p.tenant_id, seed.recommendation_number, p.id, seed.entity_type, seed.entity_id, seed.title, seed.status,
       seed.confidence, seed.explanation, now() - seed.age, now() + interval '14 days'
from ai.copilot_prompt p
join (values
  ('AIX-ACCOUNT-SUMMARY','REC-2026-0001','ACCOUNT','44444444-4444-4444-4444-444444444406'::uuid,'Summarise Northbrook renewal risk','READY',86.00::numeric,'Grounded summary uses active contract, open case and commit-stage opportunity.',interval '3 hours'),
  ('AIX-NEXT-BEST-ACTION','REC-2026-0002','OPPORTUNITY','66666666-6666-6666-6666-666666666607'::uuid,'Schedule executive alignment for Fenmore','READY',79.00::numeric,'Close date is near and the forecast risk count is elevated.',interval '5 hours'),
  ('AIX-MOBILE-DRAFT','REC-2026-0003','ACCOUNT','33333333-3333-3333-3333-333333333310'::uuid,'Draft Northstar onboarding recap','EXPIRED',55.00::numeric,'AI-off/trial policy keeps this as a disabled preview recommendation.',interval '4 days')
) as seed(prompt_code, recommendation_number, entity_type, entity_id, title, status, confidence, explanation, age) on seed.prompt_code = p.prompt_code
on conflict (tenant_id, recommendation_number) do nothing;

insert into ai.grounding_citation(tenant_id, recommendation_id, source_entity_type, source_entity_id, source_label, relevance_score)
select r.tenant_id, r.id, seed.source_entity_type, seed.source_entity_id, seed.source_label, seed.relevance_score
from ai.copilot_recommendation r
join (values
  ('REC-2026-0001','CONTRACT',null::uuid,'Northbrook enterprise contract',94.00::numeric),
  ('REC-2026-0001','CASE',null::uuid,'Northbrook integration outage escalation',88.00::numeric),
  ('REC-2026-0002','OPPORTUNITY','66666666-6666-6666-6666-666666666607','Fenmore master agreement opportunity',91.00::numeric)
) as seed(recommendation_number, source_entity_type, source_entity_id, source_label, relevance_score) on seed.recommendation_number = r.recommendation_number
on conflict do nothing;

insert into mobile.mobile_profile
  (tenant_id, profile_code, name, status, role_code, offline_object_set, max_offline_days)
select t.id, seed.profile_code, seed.name, seed.status, seed.role_code, seed.object_set, seed.max_days
from platform.tenant t
cross join (values
  ('MOB-SALES','Sales field profile','ACTIVE','SALES',array['ACCOUNT','CONTACT','OPPORTUNITY','ACTIVITY'],7),
  ('MOB-SERVICE','Service field profile','ACTIVE','SERVICE',array['ACCOUNT','CASE','ENTITLEMENT','ACTIVITY'],5),
  ('MOB-ADMIN','Admin responsive profile','DRAFT','TENANT_ADMIN',array['REPORT','AUDIT','USER'],3)
) as seed(profile_code, name, status, role_code, object_set, max_days)
on conflict (tenant_id, profile_code) do nothing;

insert into mobile.device_session
  (tenant_id, user_id, device_label, platform, status, last_sync_at, offline_queue_count, app_version)
select u.tenant_id, u.id, seed.device_label, seed.platform, seed.status, now() - seed.sync_age, seed.queue_count, seed.app_version
from (values
  ('priya.nair@meridianfab.com','Priya iPhone 15','IOS','ACTIVE',interval '12 minutes',0,'0.1.0-preview'),
  ('raj.malhotra@meridianfab.com','Raj Surface browser','WEB_RESPONSIVE','ACTIVE',interval '8 minutes',1,'0.1.0-preview'),
  ('ava.chen@northstar.example','Ava Android tablet','ANDROID','LOCKED',interval '1 day',4,'0.1.0-preview')
) as seed(owner_email, device_label, platform, status, sync_age, queue_count, app_version)
join identity.app_user u on u.email = seed.owner_email
on conflict do nothing;

insert into mobile.offline_sync_package
  (tenant_id, device_session_id, package_number, status, object_count, payload_bytes, generated_at, applied_at)
select d.tenant_id, d.id, seed.package_number, seed.status, seed.object_count, seed.payload_bytes,
       now() - seed.age, case when seed.status = 'SYNCED' then now() - seed.age + interval '2 minutes' else null end
from mobile.device_session d
join (values
  ('Priya iPhone 15','MOB-PKG-2026-0001','SYNCED',128,524288,interval '12 minutes'),
  ('Raj Surface browser','MOB-PKG-2026-0002','CONFLICT',42,102400,interval '8 minutes'),
  ('Ava Android tablet','MOB-PKG-2026-0003','FAILED',18,40960,interval '1 day')
) as seed(device_label, package_number, status, object_count, payload_bytes, age) on seed.device_label = d.device_label
on conflict (tenant_id, package_number) do nothing;

insert into i18n.translation_key(key_path, module_code, description) values
  ('nav.module.partners', 'SHELL', 'Module: partners and channel'),
  ('nav.module.automation', 'SHELL', 'Module: automation'),
  ('nav.module.analytics', 'SHELL', 'Module: analytics dashboards'),
  ('nav.module.copilot', 'SHELL', 'Module: AI copilot'),
  ('nav.module.mobile', 'SHELL', 'Module: mobile and offline')
on conflict (key_path) do nothing;

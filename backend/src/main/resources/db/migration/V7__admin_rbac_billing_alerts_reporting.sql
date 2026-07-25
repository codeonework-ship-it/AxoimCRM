-- Administration, RBAC, billing, alerting and reporting module foundations.
-- This migration keeps vendor/third-party delivery deferred while making the
-- product screens real, governed, seeded and auditable.

create schema if not exists billing;
create schema if not exists reporting;

grant usage on schema billing, reporting to axiom_app;

alter role axiom_app set search_path to platform, identity, crm, sales, engagement, governance, reference, billing, reporting, integration, public;

alter table identity.app_user
  add column if not exists active boolean not null default true,
  add column if not exists updated_at timestamptz not null default now();

create index if not exists idx_app_user_active_role on identity.app_user(tenant_id, role, active);

-- Module and screen catalogue. The existing module_catalog remains the data
-- architecture inventory; screen_catalog is the UI/RBAC inventory.
insert into governance.module_catalog(module_code, schema_name, display_name, description, owner_role) values
  ('BILLING', 'billing', 'Billing', 'Trial, subscription, invoice and payment operations.', 'SUPER_ADMIN'),
  ('REPORTING', 'reporting', 'Reports', 'Jasper-backed reporting definitions and report run evidence.', 'OPERATIONS')
on conflict (module_code) do nothing;

create table governance.screen_catalog (
  screen_code text primary key,
  module_code text not null references governance.module_catalog(module_code),
  route text not null unique,
  display_name text not null,
  description text not null,
  sort_order int not null,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  constraint screen_code_format check (screen_code ~ '^[A-Z][A-Z0-9_]*$'),
  constraint screen_route_internal check (route like '/%')
);

create table governance.rbac_policy (
  id uuid primary key default gen_random_uuid(),
  role_code text not null check (role_code in (
    'SUPER_ADMIN','SUPER_AUDIT','TENANT_ADMIN','SALES_MANAGER','SALES','MARKETING',
    'SERVICE','OPERATIONS','FINANCE','DATA_STEWARD','AUDITOR','INTEGRATION'
  )),
  screen_code text not null references governance.screen_catalog(screen_code),
  can_read boolean not null default false,
  can_write boolean not null default false,
  can_export boolean not null default false,
  can_admin boolean not null default false,
  scope text not null check (scope in ('PLATFORM','TENANT')),
  created_at timestamptz not null default now(),
  unique (role_code, screen_code),
  constraint rbac_write_requires_read check ((not can_write) or can_read),
  constraint rbac_export_requires_read check ((not can_export) or can_read),
  constraint rbac_admin_requires_write check ((not can_admin) or can_write)
);

grant select on governance.screen_catalog, governance.rbac_policy to axiom_app;

insert into governance.screen_catalog(screen_code, module_code, route, display_name, description, sort_order) values
  ('HOME', 'CRM', '/', 'Home', 'Revenue command center and exception queue.', 10),
  ('PIPELINE', 'SALES', '/pipeline', 'Pipeline', 'Opportunity board and stage governance.', 20),
  ('ACCOUNTS', 'CRM', '/accounts', 'Accounts', 'Account master data and customer ownership.', 30),
  ('LEADS', 'CRM', '/leads', 'Leads', 'Lead qualification and conversion.', 40),
  ('REFERENCE_DATA', 'REFERENCE', '/reference-data', 'Reference Data', 'Governed value sets and tenant lookup codes.', 50),
  ('REPORTS', 'REPORTING', '/reports', 'Reports', 'Operational reports with PDF, Excel and Word export.', 60),
  ('USER_MANAGEMENT', 'IDENTITY', '/admin/users', 'User Management', 'Tenant user lifecycle and role assignment.', 70),
  ('RBAC', 'GOVERNANCE', '/admin/rbac', 'RBAC Policies', 'Screen-level read/write/export/admin permissions.', 80),
  ('TRIAL_ACCOUNTS', 'PLATFORM', '/admin/trials', 'Trial Accounts', 'Trial tenant setup, extension and suspension.', 90),
  ('COMPANY_SETUP', 'PLATFORM', '/admin/companies', 'Company Setup', 'Company account status and payment-due inactivation.', 100),
  ('BILLING', 'BILLING', '/admin/billing', 'Billing', 'Billing accounts, invoices and payment transactions.', 110),
  ('ALERTS', 'ENGAGEMENT', '/admin/alerts', 'Alerts', 'Email and report alert configuration.', 120)
on conflict (screen_code) do nothing;

insert into governance.rbac_policy(role_code, screen_code, can_read, can_write, can_export, can_admin, scope)
select role_code, screen_code,
       can_read,
       can_write,
       can_export,
       can_admin,
       case when role_code in ('SUPER_ADMIN','SUPER_AUDIT') then 'PLATFORM' else 'TENANT' end
from (
  values
    ('SUPER_ADMIN', true,  true,  true,  true),
    ('SUPER_AUDIT', true,  false, true,  false),
    ('TENANT_ADMIN', true, true,  true,  true),
    ('SALES_MANAGER', true, true, true, false),
    ('SALES', true, true, true, false),
    ('MARKETING', true, true, true, false),
    ('SERVICE', true, true, true, false),
    ('OPERATIONS', true, true, true, false),
    ('FINANCE', true, false, true, false),
    ('DATA_STEWARD', true, true, true, false),
    ('AUDITOR', true, false, true, false),
    ('INTEGRATION', false, false, false, false)
) roles(role_code, can_read, can_write, can_export, can_admin)
cross join governance.screen_catalog s
where
  role_code in ('SUPER_ADMIN','SUPER_AUDIT')
  or (
    s.screen_code not in ('TRIAL_ACCOUNTS','COMPANY_SETUP','BILLING')
    and not (role_code in ('SALES','MARKETING','SERVICE') and s.screen_code in ('USER_MANAGEMENT','RBAC','ALERTS'))
    and not (role_code = 'FINANCE' and s.screen_code in ('USER_MANAGEMENT','RBAC','ALERTS'))
  )
on conflict (role_code, screen_code) do update
  set can_read = excluded.can_read,
      can_write = excluded.can_write,
      can_export = excluded.can_export,
      can_admin = excluded.can_admin,
      scope = excluded.scope;

-- Platform/company lifecycle and trial governance.
create table platform.company_account (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null unique references platform.tenant(id),
  legal_name text not null,
  account_status text not null default 'ACTIVE' check (account_status in ('TRIAL','ACTIVE','PAST_DUE','INACTIVE','SUSPENDED')),
  trial_start_at date,
  trial_ends_at date,
  trial_extension_count int not null default 0 check (trial_extension_count >= 0),
  max_trial_extension_days int not null default 90 check (max_trial_extension_days between 0 and 365),
  inactive_reason text,
  inactive_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint company_trial_range check (trial_ends_at is null or trial_start_at is null or trial_ends_at >= trial_start_at)
);

create table platform.trial_account_event (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  action text not null check (action in ('CREATED','EXTENDED','ACTIVATED','INACTIVATED','SUSPENDED')),
  days_delta int not null default 0,
  months_delta int not null default 0,
  actor_id uuid,
  note text,
  occurred_at timestamptz not null default now()
);

grant select, insert, update on platform.company_account, platform.trial_account_event to axiom_app;

-- Billing is platform governed because super-admin/super-auditor operate across
-- tenants; app services enforce role policy before writes.
create table billing.billing_account (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null unique references platform.tenant(id),
  plan_code text not null check (plan_code in ('TRIAL','STARTER','PROFESSIONAL','ENTERPRISE')),
  billing_email text not null,
  payment_status text not null check (payment_status in ('TRIAL','CURRENT','PAST_DUE','SUSPENDED')),
  current_period_start date,
  current_period_end date,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint billing_period_range check (current_period_end is null or current_period_start is null or current_period_end >= current_period_start)
);

create table billing.invoice (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  billing_account_id uuid not null references billing.billing_account(id),
  invoice_number text not null unique,
  amount numeric(14,2) not null check (amount >= 0),
  currency char(3) not null default 'USD',
  status text not null check (status in ('DRAFT','OPEN','PAID','PAST_DUE','VOID')),
  due_at date,
  paid_at timestamptz,
  created_at timestamptz not null default now(),
  unique (tenant_id, id)
);

create table billing.payment_transaction (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  invoice_id uuid not null references billing.invoice(id),
  amount numeric(14,2) not null check (amount >= 0),
  status text not null check (status in ('AUTHORIZED','CAPTURED','FAILED','REFUNDED','PENDING')),
  provider_reference text,
  created_at timestamptz not null default now()
);

create index idx_invoice_tenant_status on billing.invoice(tenant_id, status, due_at);
create index idx_payment_tenant_invoice on billing.payment_transaction(tenant_id, invoice_id);
grant select, insert, update on billing.billing_account, billing.invoice, billing.payment_transaction to axiom_app;

-- Jasper-backed reporting definitions and run evidence.
create table reporting.report_definition (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  code text not null,
  label text not null,
  description text not null,
  template_path text not null,
  allowed_formats text[] not null default array['PDF','XLSX','DOCX'],
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, code),
  unique (tenant_id, id),
  constraint report_code_format check (code ~ '^[a-z][a-z0-9_]*$'),
  constraint report_formats_allowed check (allowed_formats <@ array['PDF','XLSX','DOCX'])
);

create table reporting.report_run (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  report_definition_id uuid not null,
  format text not null check (format in ('PDF','XLSX','DOCX')),
  status text not null check (status in ('GENERATED','FAILED','QUEUED')),
  row_count int not null default 0 check (row_count >= 0),
  generated_by uuid not null,
  created_at timestamptz not null default now(),
  constraint fk_report_run_definition_same_tenant
    foreign key (tenant_id, report_definition_id) references reporting.report_definition(tenant_id, id)
);

create index idx_report_definition_active on reporting.report_definition(tenant_id, code) where active = true;
create index idx_report_run_feed on reporting.report_run(tenant_id, created_at desc);

-- Alert configuration and dispatch audit. SMTP/report distribution providers
-- remain deferred; dispatch rows/outbox events are the internal handoff.
create table engagement.email_alert_config (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  name text not null,
  subject text not null,
  body_html text not null,
  to_list text[] not null,
  cc_list text[] not null default '{}',
  bcc_list text[] not null default '{}',
  attachment_optional boolean not null default true,
  active boolean not null default true,
  created_by uuid not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, name),
  unique (tenant_id, id),
  constraint email_alert_to_required check (cardinality(to_list) > 0)
);

create table engagement.email_alert_dispatch (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  config_id uuid not null,
  status text not null check (status in ('QUEUED','SENT','FAILED')),
  attachment_name text,
  error_message text,
  created_at timestamptz not null default now(),
  sent_at timestamptz,
  constraint fk_email_dispatch_config_same_tenant
    foreign key (tenant_id, config_id) references engagement.email_alert_config(tenant_id, id)
);

create table engagement.report_alert_config (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  report_definition_id uuid not null,
  name text not null,
  subject text not null,
  body_html text not null,
  to_list text[] not null,
  cc_list text[] not null default '{}',
  bcc_list text[] not null default '{}',
  formats text[] not null default array['PDF'],
  active boolean not null default true,
  created_by uuid not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, name),
  unique (tenant_id, id),
  constraint fk_report_alert_definition_same_tenant
    foreign key (tenant_id, report_definition_id) references reporting.report_definition(tenant_id, id),
  constraint report_alert_to_required check (cardinality(to_list) > 0),
  constraint report_alert_formats_allowed check (formats <@ array['PDF','XLSX','DOCX'])
);

create table engagement.report_alert_dispatch (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  config_id uuid not null,
  status text not null check (status in ('QUEUED','SENT','FAILED')),
  generated_formats text[] not null default '{}',
  error_message text,
  created_at timestamptz not null default now(),
  sent_at timestamptz,
  constraint fk_report_dispatch_config_same_tenant
    foreign key (tenant_id, config_id) references engagement.report_alert_config(tenant_id, id)
);

create index idx_email_alert_active on engagement.email_alert_config(tenant_id, active);
create index idx_report_alert_active on engagement.report_alert_config(tenant_id, active);
grant select, insert, update on reporting.report_definition, reporting.report_run,
  engagement.email_alert_config, engagement.email_alert_dispatch,
  engagement.report_alert_config, engagement.report_alert_dispatch to axiom_app;

do $$
declare
  t text;
begin
  foreach t in array array[
    'reporting.report_definition','reporting.report_run',
    'engagement.email_alert_config','engagement.email_alert_dispatch',
    'engagement.report_alert_config','engagement.report_alert_dispatch'
  ]
  loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format(
      'create policy tenant_isolation on %s using (tenant_id = current_setting(''app.tenant_id'', true)::uuid) with check (tenant_id = current_setting(''app.tenant_id'', true)::uuid)',
      t
    );
  end loop;
end
$$;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('governance','screen_catalog','GOVERNANCE','screen_code',false,'ACTIVE'),
  ('governance','rbac_policy','GOVERNANCE','id',false,'ACTIVE'),
  ('platform','company_account','PLATFORM','id',false,'PLATFORM'),
  ('platform','trial_account_event','PLATFORM','id',false,'APPEND_ONLY'),
  ('billing','billing_account','BILLING','id',false,'ACTIVE'),
  ('billing','invoice','BILLING','id',false,'APPEND_ONLY'),
  ('billing','payment_transaction','BILLING','id',false,'APPEND_ONLY'),
  ('reporting','report_definition','REPORTING','id',true,'ACTIVE'),
  ('reporting','report_run','REPORTING','id',true,'APPEND_ONLY'),
  ('engagement','email_alert_config','ENGAGEMENT','id',true,'ACTIVE'),
  ('engagement','email_alert_dispatch','ENGAGEMENT','id',true,'APPEND_ONLY'),
  ('engagement','report_alert_config','ENGAGEMENT','id',true,'ACTIVE'),
  ('engagement','report_alert_dispatch','ENGAGEMENT','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

-- Seed platform/company/billing records for every tenant already created by V2/V5.
insert into platform.company_account(tenant_id, legal_name, account_status, trial_start_at, trial_ends_at, max_trial_extension_days)
select t.id, t.name,
       case when t.slug = 'northstar' then 'TRIAL' else 'ACTIVE' end,
       current_date - interval '10 days',
       case when t.slug = 'northstar' then current_date + interval '20 days' else null end,
       90
from platform.tenant t
on conflict (tenant_id) do nothing;

insert into billing.billing_account(tenant_id, plan_code, billing_email, payment_status, current_period_start, current_period_end)
select t.id,
       case when t.slug = 'northstar' then 'TRIAL' else 'ENTERPRISE' end,
       case when t.slug = 'northstar' then 'billing@northstar.example' else 'billing@meridianfab.com' end,
       case when t.slug = 'northstar' then 'TRIAL' else 'CURRENT' end,
       current_date - interval '15 days',
       current_date + interval '15 days'
from platform.tenant t
on conflict (tenant_id) do nothing;

insert into billing.invoice(tenant_id, billing_account_id, invoice_number, amount, currency, status, due_at, paid_at)
select ba.tenant_id, ba.id, 'AX-' || upper(t.slug) || '-2026-07', 
       case when ba.plan_code = 'TRIAL' then 0 else 4200 end,
       'USD',
       case when ba.plan_code = 'TRIAL' then 'DRAFT' else 'PAID' end,
       current_date + interval '7 days',
       case when ba.plan_code = 'TRIAL' then null else now() - interval '2 days' end
from billing.billing_account ba
join platform.tenant t on t.id = ba.tenant_id
on conflict (invoice_number) do nothing;

insert into billing.payment_transaction(tenant_id, invoice_id, amount, status, provider_reference)
select i.tenant_id, i.id, i.amount, 'CAPTURED', 'seed-payment-' || i.invoice_number
from billing.invoice i
where i.status = 'PAID'
on conflict do nothing;

insert into reporting.report_definition(tenant_id, code, label, description, template_path)
select t.id, seed.code, seed.label, seed.description, seed.template_path
from platform.tenant t
cross join (values
  ('tenant_summary', 'Tenant Summary', 'Counts, pipeline value and lifecycle posture for the selected tenant.', 'reports/tenant-summary.jrxml'),
  ('pipeline_snapshot', 'Pipeline Snapshot', 'Stage, value and deal-count telemetry for sales reviews.', 'reports/tenant-summary.jrxml')
) as seed(code, label, description, template_path)
on conflict (tenant_id, code) do nothing;

insert into engagement.email_alert_config
  (tenant_id, name, subject, body_html, to_list, cc_list, bcc_list, attachment_optional, created_by)
select t.id, 'Payment due reminder',
       'Axiom CRM payment reminder',
       '<p>Your Axiom CRM workspace has a payment action pending.</p>',
       array['billing@example.com'], '{}', '{}', true,
       coalesce((select id from identity.app_user u where u.tenant_id = t.id and u.role = 'TENANT_ADMIN' limit 1),
                '00000000-0000-0000-0000-000000000000'::uuid)
from platform.tenant t
on conflict (tenant_id, name) do nothing;

insert into engagement.report_alert_config
  (tenant_id, report_definition_id, name, subject, body_html, to_list, cc_list, bcc_list, formats, created_by)
select rd.tenant_id, rd.id,
       'Weekly tenant summary',
       'Weekly Axiom CRM summary',
       '<p>The latest Axiom CRM summary report is attached.</p>',
       array['operations@example.com'], '{}', '{}', array['PDF','XLSX'],
       coalesce((select id from identity.app_user u where u.tenant_id = rd.tenant_id and u.role = 'TENANT_ADMIN' limit 1),
                '00000000-0000-0000-0000-000000000000'::uuid)
from reporting.report_definition rd
where rd.code = 'tenant_summary'
on conflict (tenant_id, name) do nothing;

insert into platform.trial_account_event(tenant_id, action, days_delta, note)
select tenant_id, 'CREATED', 30, 'Seeded trial/company lifecycle record'
from platform.company_account
where account_status = 'TRIAL';

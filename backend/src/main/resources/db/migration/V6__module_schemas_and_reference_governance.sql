-- Physical module schemas and tenant-consistent relational constraints.
-- The application keeps using unqualified SQL; runtime roles get a controlled
-- search_path so module ownership is enforced in PostgreSQL.

create schema if not exists platform;
create schema if not exists identity;
create schema if not exists crm;
create schema if not exists sales;
create schema if not exists engagement;
create schema if not exists governance;
create schema if not exists integration;
create schema if not exists reference;

alter table if exists tenant set schema platform;
alter table if exists platform_user set schema platform;
alter table if exists app_user set schema identity;
alter table if exists account set schema crm;
alter table if exists contact set schema crm;
alter table if exists lead set schema crm;
alter table if exists pipeline_stage set schema crm;
alter table if exists opportunity set schema sales;
alter table if exists opportunity_contact_role set schema sales;
alter table if exists notification set schema engagement;
alter table if exists audit_event set schema governance;
alter table if exists crm_group set schema governance;
alter table if exists crm_group_member set schema governance;
alter table if exists outbox_event set schema integration;

grant usage on schema platform, identity, crm, sales, engagement, governance, integration, reference to axiom_app;
grant usage on schema integration, platform to axiom_relay;

alter role axiom_app set search_path to platform, identity, crm, sales, engagement, governance, reference, integration, public;
alter role axiom_relay set search_path to integration, platform, public;

-- Composite keys are the target for tenant-consistent foreign keys.
alter table identity.app_user add constraint uq_app_user_tenant_id unique (tenant_id, id);
alter table crm.account add constraint uq_account_tenant_id unique (tenant_id, id);
alter table crm.contact add constraint uq_contact_tenant_id unique (tenant_id, id);
alter table crm.lead add constraint uq_lead_tenant_id unique (tenant_id, id);
alter table crm.pipeline_stage add constraint uq_pipeline_stage_tenant_id unique (tenant_id, id);
alter table sales.opportunity add constraint uq_opportunity_tenant_id unique (tenant_id, id);
alter table governance.crm_group add constraint uq_crm_group_tenant_id unique (tenant_id, id);

-- Tenant-consistency constraints: a child row can only point at a record in the
-- same tenant. These supplement the single-column foreign keys from V1-V5.
alter table crm.account
  add constraint fk_account_owner_same_tenant
  foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id);

alter table crm.contact
  add constraint fk_contact_account_same_tenant
  foreign key (tenant_id, account_id) references crm.account(tenant_id, id);

alter table sales.opportunity
  add constraint fk_opportunity_account_same_tenant
  foreign key (tenant_id, account_id) references crm.account(tenant_id, id),
  add constraint fk_opportunity_stage_same_tenant
  foreign key (tenant_id, stage_id) references crm.pipeline_stage(tenant_id, id),
  add constraint fk_opportunity_owner_same_tenant
  foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id);

alter table crm.lead
  add constraint fk_lead_owner_same_tenant
  foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id),
  add constraint fk_lead_converted_account_same_tenant
  foreign key (tenant_id, converted_account_id) references crm.account(tenant_id, id),
  add constraint fk_lead_converted_contact_same_tenant
  foreign key (tenant_id, converted_contact_id) references crm.contact(tenant_id, id),
  add constraint fk_lead_converted_opportunity_same_tenant
  foreign key (tenant_id, converted_opportunity_id) references sales.opportunity(tenant_id, id);

alter table sales.opportunity_contact_role
  add constraint fk_ocr_opportunity_same_tenant
  foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id),
  add constraint fk_ocr_contact_same_tenant
  foreign key (tenant_id, contact_id) references crm.contact(tenant_id, id);

alter table engagement.notification
  add constraint fk_notification_recipient_same_tenant
  foreign key (tenant_id, recipient_user_id) references identity.app_user(tenant_id, id);

alter table governance.crm_group_member
  add constraint fk_group_member_group_same_tenant
  foreign key (tenant_id, group_id) references governance.crm_group(tenant_id, id);

create unique index uq_account_active_name on crm.account(tenant_id, lower(name)) where deleted_at is null;
create unique index uq_contact_active_account_email on crm.contact(tenant_id, account_id, lower(email))
  where deleted_at is null and email is not null and email <> '';
create unique index uq_lead_active_email_company on crm.lead(tenant_id, lower(email), lower(company))
  where deleted_at is null and email is not null and email <> '';

-- Governed reference data slice for E03. Business statuses should be references
-- over time; these tables are tenant-scoped, soft-deactivated, auditable inputs.
create table reference.value_set (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  api_name    text not null,
  label       text not null,
  module      text not null check (module in ('CRM','SALES','ENGAGEMENT','GOVERNANCE','REFERENCE')),
  description text,
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (tenant_id, api_name),
  unique (tenant_id, id),
  constraint value_set_api_name_format check (api_name ~ '^[a-z][a-z0-9_]*$')
);

create table reference.value_set_entry (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  value_set_id   uuid not null,
  code           text not null,
  label          text not null,
  sort_order     integer not null default 0,
  active         boolean not null default true,
  system_managed boolean not null default false,
  effective_from date,
  effective_to   date,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  unique (tenant_id, value_set_id, code),
  unique (tenant_id, value_set_id, sort_order),
  constraint value_set_entry_code_format check (code ~ '^[A-Z][A-Z0-9_]*$'),
  constraint value_set_entry_effective_range check (effective_to is null or effective_from is null or effective_to >= effective_from),
  constraint fk_value_set_entry_parent_same_tenant
    foreign key (tenant_id, value_set_id) references reference.value_set(tenant_id, id)
);

create index idx_value_set_active on reference.value_set(tenant_id, module, api_name) where active = true;
create index idx_value_set_entry_active on reference.value_set_entry(tenant_id, value_set_id, sort_order) where active = true;

alter table reference.value_set enable row level security;
alter table reference.value_set force row level security;
create policy tenant_isolation on reference.value_set
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

alter table reference.value_set_entry enable row level security;
alter table reference.value_set_entry force row level security;
create policy tenant_isolation on reference.value_set_entry
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

grant select, insert, update on reference.value_set, reference.value_set_entry to axiom_app;

insert into reference.value_set (id, tenant_id, api_name, label, module, description)
select gen_random_uuid(), t.id, seed.api_name, seed.label, seed.module, seed.description
from platform.tenant t
cross join (values
  ('lead_status', 'Lead status', 'CRM', 'Governed lead lifecycle statuses'),
  ('pipeline_stage_lifecycle', 'Pipeline stage lifecycle', 'SALES', 'Open, won and lost stage classifications'),
  ('master_delete_reason', 'Master delete reason', 'GOVERNANCE', 'Governed reasons for master-data retirement')
) as seed(api_name, label, module, description);

insert into reference.value_set_entry (tenant_id, value_set_id, code, label, sort_order, system_managed)
select vs.tenant_id, vs.id, seed.code, seed.label, seed.sort_order, true
from reference.value_set vs
join (values
  ('lead_status', 'NEW', 'New', 10),
  ('lead_status', 'QUALIFIED', 'Qualified', 20),
  ('lead_status', 'CONVERTED', 'Converted', 30),
  ('lead_status', 'DISQUALIFIED', 'Disqualified', 40),
  ('pipeline_stage_lifecycle', 'OPEN', 'Open', 10),
  ('pipeline_stage_lifecycle', 'WON', 'Won', 20),
  ('pipeline_stage_lifecycle', 'LOST', 'Lost', 30),
  ('master_delete_reason', 'DUPLICATE', 'Duplicate', 10),
  ('master_delete_reason', 'OBSOLETE', 'Obsolete', 20),
  ('master_delete_reason', 'DATA_QUALITY', 'Data quality correction', 30)
) as seed(api_name, code, label, sort_order) on seed.api_name = vs.api_name;

create table governance.module_catalog (
  module_code text primary key,
  schema_name text not null unique,
  display_name text not null,
  description text not null,
  owner_role text not null,
  created_at timestamptz not null default now(),
  constraint module_catalog_schema_format check (schema_name ~ '^[a-z][a-z0-9_]*$')
);

create table governance.module_table_catalog (
  schema_name text not null,
  table_name text not null,
  module_code text not null references governance.module_catalog(module_code),
  primary_key text not null,
  tenant_scoped boolean not null,
  lifecycle text not null check (lifecycle in ('PLATFORM','ACTIVE','SOFT_DELETE','APPEND_ONLY','OUTBOX')),
  constraint pk_module_table_catalog primary key (schema_name, table_name)
);

grant select on governance.module_catalog, governance.module_table_catalog to axiom_app;

insert into governance.module_catalog(module_code, schema_name, display_name, description, owner_role) values
  ('PLATFORM', 'platform', 'Platform tenancy', 'Tenant and platform-operator identity records.', 'SUPER_ADMIN'),
  ('IDENTITY', 'identity', 'Tenant identity', 'Tenant users and authentication-facing profile records.', 'TENANT_ADMIN'),
  ('CRM', 'crm', 'CRM core', 'Accounts, contacts, leads and pipeline-stage master data.', 'TENANT_ADMIN'),
  ('SALES', 'sales', 'Sales execution', 'Opportunities and buying-group participation.', 'SALES_MANAGER'),
  ('ENGAGEMENT', 'engagement', 'Engagement', 'Notifications and future activity timelines.', 'OPERATIONS'),
  ('GOVERNANCE', 'governance', 'Governance', 'Audit, grouping and compliance metadata.', 'AUDITOR'),
  ('INTEGRATION', 'integration', 'Integration', 'Transactional outbox and future connector state.', 'INTEGRATION'),
  ('REFERENCE', 'reference', 'Reference data', 'Governed value sets and tenant-specific lookup entries.', 'DATA_STEWARD');

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('platform','tenant','PLATFORM','id',false,'PLATFORM'),
  ('platform','platform_user','PLATFORM','id',false,'PLATFORM'),
  ('identity','app_user','IDENTITY','id',true,'ACTIVE'),
  ('crm','account','CRM','id',true,'SOFT_DELETE'),
  ('crm','contact','CRM','id',true,'SOFT_DELETE'),
  ('crm','lead','CRM','id',true,'SOFT_DELETE'),
  ('crm','pipeline_stage','CRM','id',true,'SOFT_DELETE'),
  ('sales','opportunity','SALES','id',true,'ACTIVE'),
  ('sales','opportunity_contact_role','SALES','id',true,'ACTIVE'),
  ('engagement','notification','ENGAGEMENT','id',true,'ACTIVE'),
  ('governance','audit_event','GOVERNANCE','id',true,'APPEND_ONLY'),
  ('governance','crm_group','GOVERNANCE','id',true,'SOFT_DELETE'),
  ('governance','crm_group_member','GOVERNANCE','id',true,'ACTIVE'),
  ('integration','outbox_event','INTEGRATION','id',true,'OUTBOX'),
  ('reference','value_set','REFERENCE','id',true,'ACTIVE'),
  ('reference','value_set_entry','REFERENCE','id',true,'ACTIVE');

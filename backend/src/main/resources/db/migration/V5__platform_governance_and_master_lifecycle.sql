-- Platform administration, immutable audit, groups, and soft-delete lifecycle.

alter table app_user drop constraint app_user_role_check;
update app_user set role = 'TENANT_ADMIN' where role = 'ADMIN';
alter table app_user add constraint app_user_role_check check (role in (
  'TENANT_ADMIN','SALES_MANAGER','SALES','MARKETING','SERVICE','OPERATIONS',
  'FINANCE','DATA_STEWARD','AUDITOR','INTEGRATION'
));

create table platform_user (
  id             uuid primary key default gen_random_uuid(),
  email          text not null unique,
  password_hash  text not null,
  display_name   text not null,
  role           text not null check (role in ('SUPER_ADMIN','SUPER_AUDIT')),
  active         boolean not null default true,
  created_at     timestamptz not null default now()
);

grant select on platform_user to axiom_app;

insert into platform_user (id, email, password_hash, display_name, role) values
  ('99999999-9999-9999-9999-999999999901', 'superadmin@axiomcrm.com', crypt('axiom-demo', gen_salt('bf', 10)), 'Axiom Super Admin', 'SUPER_ADMIN'),
  ('99999999-9999-9999-9999-999999999902', 'superaudit@axiomcrm.com', crypt('axiom-demo', gen_salt('bf', 10)), 'Axiom Super Auditor', 'SUPER_AUDIT');

-- A second workspace makes cross-tenant switching and isolation testable.
insert into tenant (id, slug, name, status) values
  ('11111111-1111-1111-1111-111111111102', 'northstar', 'Northstar Industrial Systems', 'active');

insert into app_user (id, tenant_id, email, password_hash, display_name, role) values
  ('22222222-2222-2222-2222-222222222204', '11111111-1111-1111-1111-111111111102',
   'ava.chen@northstar.example', crypt('axiom-demo', gen_salt('bf', 10)), 'Ava Chen', 'TENANT_ADMIN');

insert into account (id, tenant_id, name, industry, owner_id) values
  ('33333333-3333-3333-3333-333333333310', '11111111-1111-1111-1111-111111111102',
   'Northstar Test Account', 'Industrial automation', '22222222-2222-2222-2222-222222222204');

insert into pipeline_stage (id, tenant_id, name, sort_order, is_closed, is_won, requires_economic_buyer) values
  ('55555555-5555-5555-5555-555555555510', '11111111-1111-1111-1111-111111111102', 'Qualifying', 10, false, false, false),
  ('55555555-5555-5555-5555-555555555511', '11111111-1111-1111-1111-111111111102', 'Proposal', 20, false, false, false),
  ('55555555-5555-5555-5555-555555555512', '11111111-1111-1111-1111-111111111102', 'Negotiation', 30, false, false, true);

alter table account add column deleted_at timestamptz;
alter table account add column deleted_by uuid;
alter table contact add column deleted_at timestamptz;
alter table contact add column deleted_by uuid;
alter table lead add column deleted_at timestamptz;
alter table lead add column deleted_by uuid;
alter table pipeline_stage add column deleted_at timestamptz;
alter table pipeline_stage add column deleted_by uuid;

create index idx_account_active on account(tenant_id, name) where deleted_at is null;
create index idx_contact_active on contact(tenant_id, last_name, first_name) where deleted_at is null;
create index idx_lead_active on lead(tenant_id, created_at desc) where deleted_at is null;
create index idx_pipeline_stage_active on pipeline_stage(tenant_id, sort_order) where deleted_at is null;

-- Runtime masters cannot be hard-deleted; lifecycle operations are audited UPDATEs.
revoke delete on account, contact, lead, pipeline_stage from axiom_app;

create table audit_event (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references tenant(id),
  actor_id        uuid not null,
  actor_name      text not null,
  actor_role      text not null,
  action          text not null,
  entity_type     text not null,
  entity_id       uuid,
  summary         text not null,
  details         jsonb not null default '{}'::jsonb,
  correlation_id  text,
  occurred_at     timestamptz not null default now()
);

create index idx_audit_event_feed on audit_event(tenant_id, occurred_at desc);
alter table audit_event enable row level security;
alter table audit_event force row level security;
create policy tenant_isolation on audit_event
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);
grant select, insert on audit_event to axiom_app;

create table crm_group (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  name        text not null,
  description text,
  created_by  uuid not null,
  created_at  timestamptz not null default now(),
  deleted_at  timestamptz,
  unique (tenant_id, name)
);

create table crm_group_member (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  group_id    uuid not null references crm_group(id),
  entity_type text not null check (entity_type in ('ACCOUNT','CONTACT','LEAD')),
  entity_id   uuid not null,
  created_at  timestamptz not null default now(),
  unique (group_id, entity_type, entity_id)
);

create index idx_crm_group_active on crm_group(tenant_id, name) where deleted_at is null;
create index idx_crm_group_member_group on crm_group_member(tenant_id, group_id);

alter table crm_group enable row level security;
alter table crm_group force row level security;
create policy tenant_isolation on crm_group
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

alter table crm_group_member enable row level security;
alter table crm_group_member force row level security;
create policy tenant_isolation on crm_group_member
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

grant select, insert, update on crm_group, crm_group_member to axiom_app;

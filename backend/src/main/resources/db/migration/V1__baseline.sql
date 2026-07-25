-- Axiom CRM — baseline schema (skeleton scope: E01 tenancy, E04 accounts/contacts,
-- E05 leads, E06 opportunities, ADR-003 outbox).
--
-- Run by Flyway connected as a superuser (see spring.flyway.* in application.yml).
-- Runtime traffic connects as the least-privilege `axiom_app` role created below,
-- which is subject to the row-level security policies — this is what makes tenant
-- isolation a database-enforced property, not just an application convention.
-- See docs/architecture/adr/ADR-001-tenancy-isolation.md.

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------------
-- Least-privilege runtime role
-- ---------------------------------------------------------------------------
do $$
begin
  if not exists (select from pg_roles where rolname = 'axiom_app') then
    create role axiom_app login password 'axiom_app_dev_password';
  end if;
end
$$;

do $$
begin
  execute format('grant connect on database %I to axiom_app', current_database());
end
$$;

grant usage on schema public to axiom_app;

-- ---------------------------------------------------------------------------
-- Platform table — not itself tenant-scoped data, so no RLS.
-- Login must be able to resolve a tenant by slug before a tenant context exists.
-- ---------------------------------------------------------------------------
create table tenant (
  id          uuid primary key default gen_random_uuid(),
  slug        text not null unique,
  name        text not null,
  status      text not null default 'active' check (status in ('provisioning','active','suspended','terminating','terminated')),
  created_at  timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Tenant-scoped tables
-- ---------------------------------------------------------------------------
create table app_user (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references tenant(id),
  email          text not null,
  password_hash  text not null,
  display_name   text not null,
  role           text not null default 'SALES' check (role in ('ADMIN','SALES')),
  created_at     timestamptz not null default now(),
  unique (tenant_id, email)
);

create table account (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  name        text not null,
  industry    text,
  owner_id    uuid references app_user(id),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  version     bigint not null default 0
);

create table contact (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  account_id  uuid references account(id),
  first_name  text not null,
  last_name   text not null,
  email       text,
  title       text,
  created_at  timestamptz not null default now()
);

create table pipeline_stage (
  id                        uuid primary key default gen_random_uuid(),
  tenant_id                 uuid not null references tenant(id),
  name                      text not null,
  sort_order                int not null,
  is_closed                 boolean not null default false,
  is_won                    boolean not null default false,
  requires_economic_buyer   boolean not null default false,
  unique (tenant_id, name),
  unique (tenant_id, sort_order)
);

create table opportunity (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references tenant(id),
  name        text not null,
  account_id  uuid not null references account(id),
  stage_id    uuid not null references pipeline_stage(id),
  amount      numeric(14,2) not null default 0,
  owner_id    uuid references app_user(id),
  close_date  date,
  is_closed   boolean not null default false,
  is_won      boolean,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  version     bigint not null default 0
);

create table lead (
  id                        uuid primary key default gen_random_uuid(),
  tenant_id                 uuid not null references tenant(id),
  first_name                text not null,
  last_name                 text not null,
  company                   text not null,
  email                     text,
  status                    text not null default 'NEW' check (status in ('NEW','QUALIFIED','CONVERTED','DISQUALIFIED')),
  owner_id                  uuid references app_user(id),
  converted_account_id      uuid references account(id),
  converted_contact_id      uuid references contact(id),
  converted_opportunity_id  uuid references opportunity(id),
  disqualify_reason         text,
  created_at                timestamptz not null default now()
);

create table opportunity_contact_role (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references tenant(id),
  opportunity_id   uuid not null references opportunity(id),
  contact_id       uuid not null references contact(id),
  role             text not null check (role in ('ECONOMIC_BUYER','CHAMPION','TECHNICAL_EVALUATOR','INFLUENCER','BLOCKER')),
  created_at       timestamptz not null default now(),
  unique (opportunity_id, contact_id, role)
);

-- Transactional outbox — see ADR-003-event-backbone.md. Written in the same
-- transaction as the business change; relayed to Kafka by OutboxRelay.
create table outbox_event (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references tenant(id),
  aggregate_type  text not null,
  aggregate_id    uuid not null,
  event_type      text not null,
  payload         jsonb not null,
  created_at      timestamptz not null default now(),
  dispatched_at   timestamptz
);

-- ---------------------------------------------------------------------------
-- Indexes — tenant-prefixed, per docs/product/09-data-model.md §8.
-- ---------------------------------------------------------------------------
create index idx_app_user_tenant       on app_user(tenant_id);
create index idx_account_tenant        on account(tenant_id);
create index idx_contact_tenant_acct   on contact(tenant_id, account_id);
create index idx_lead_tenant_status    on lead(tenant_id, status);
create index idx_opportunity_tenant_stage on opportunity(tenant_id, stage_id);
create index idx_opp_contact_role_opp  on opportunity_contact_role(tenant_id, opportunity_id);
create index idx_outbox_undispatched   on outbox_event(tenant_id, dispatched_at) where dispatched_at is null;

-- ---------------------------------------------------------------------------
-- Row-level security — the second, independent enforcement layer.
-- current_setting('app.tenant_id', true) returns NULL rather than erroring
-- when unset, so a connection with no tenant context set sees zero rows
-- rather than failing open. See system-design.md §5.1 (defence in depth).
-- ---------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array['app_user','account','contact','pipeline_stage','opportunity','lead','opportunity_contact_role','outbox_event']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format(
      'create policy tenant_isolation on %I using (tenant_id = current_setting(''app.tenant_id'', true)::uuid) with check (tenant_id = current_setting(''app.tenant_id'', true)::uuid)',
      t
    );
    execute format('grant select, insert, update, delete on %I to axiom_app', t);
  end loop;
end
$$;

-- axiom_app needs to resolve tenant-by-slug at login, before tenant context exists.
grant select on tenant to axiom_app;

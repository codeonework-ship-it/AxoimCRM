-- E20 / FR-AUD-014, FR-AUD-015, FR-GLOBAL-010
--
-- Observability. Request metrics are aggregated per tenant/day/endpoint rather
-- than stored per request: a row per request would become the largest table in
-- the product inside a week, and none of the questions being asked of it
-- ("is the API healthy", "what is this tenant consuming") need request grain.
--
-- Nothing in this schema stores a URL query string, request body, header or
-- principal email. The endpoint column holds a TEMPLATED path (/api/v1/accounts/{id})
-- so an identifier cannot leak into a metric dimension.

create table observability.request_metric (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  day               date not null,
  endpoint          text not null,
  method            text not null,
  status_class      text not null check (status_class in ('2xx','3xx','4xx','5xx')),
  request_count     bigint not null default 0,
  total_duration_ms bigint not null default 0,
  max_duration_ms   integer not null default 0,
  updated_at        timestamptz not null default now(),
  unique (tenant_id, day, endpoint, method, status_class)
);

create index idx_request_metric_day on observability.request_metric(tenant_id, day desc);

-- Service-level indicators. The catalogue is a platform fact (not tenant config)
-- so it carries no tenant_id; evaluations and alerts are tenant-scoped.
create table observability.sli_definition (
  code            text primary key,
  label           text not null,
  description     text not null,
  unit            text not null,
  target_value    numeric(14,4) not null,
  comparator      text not null check (comparator in ('LTE','GTE')),
  window_minutes  integer not null default 1440 check (window_minutes >= 1),
  severity        text not null default 'WARNING' check (severity in ('INFO','WARNING','CRITICAL')),
  active          boolean not null default true,
  sort_order      integer not null default 100
);

insert into observability.sli_definition
  (code, label, description, unit, target_value, comparator, window_minutes, severity, sort_order) values
  ('API_AVAILABILITY', 'API availability',
   'Share of requests in the window that did not fail with a server error.', 'percent', 99.5000, 'GTE', 1440, 'CRITICAL', 10),
  ('API_LATENCY_AVG_MS', 'API average latency',
   'Mean server-side handling time across all recorded requests in the window.', 'milliseconds', 800.0000, 'LTE', 1440, 'WARNING', 20),
  ('AUDIT_CHAIN_INTEGRITY', 'Audit chain integrity',
   'The tenant audit hash chain verifies with no gap and no altered event.', 'boolean', 1.0000, 'GTE', 1440, 'CRITICAL', 30),
  ('DSR_WITHIN_WINDOW', 'Data subject requests inside the service window',
   'Share of open data subject requests that are still inside their configured service window.', 'percent', 100.0000, 'GTE', 1440, 'CRITICAL', 40),
  ('ERASURE_STORE_COVERAGE', 'Erasure store coverage',
   'Share of registered erasable stores that the erasure process can actually reach.', 'percent', 100.0000, 'GTE', 1440, 'WARNING', 50),
  ('OUTBOX_BACKLOG', 'Outbox dispatch backlog',
   'Undispatched domain events older than five minutes.', 'events', 0.0000, 'LTE', 1440, 'WARNING', 60);

grant select on observability.sli_definition to axiom_app;

create table observability.sli_evaluation (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  sli_code       text not null references observability.sli_definition(code),
  observed_value numeric(14,4),
  target_value   numeric(14,4) not null,
  comparator     text not null,
  breached       boolean not null,
  detail         text not null,
  evaluated_at   timestamptz not null default now()
);

create index idx_sli_evaluation_feed on observability.sli_evaluation(tenant_id, evaluated_at desc);

create table observability.sli_alert (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  sli_code       text not null references observability.sli_definition(code),
  severity       text not null,
  observed_value numeric(14,4),
  target_value   numeric(14,4) not null,
  detail         text not null,
  raised_at      timestamptz not null default now(),
  resolved_at    timestamptz,
  acknowledged_at timestamptz,
  acknowledged_by uuid
);

create unique index uq_sli_alert_open on observability.sli_alert(tenant_id, sli_code)
  where resolved_at is null;

-- FR-AUD-015 — tenant-visible usage telemetry, captured as dated snapshots so
-- adoption can be trended rather than only observed at this instant.
create table observability.usage_snapshot (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  day              date not null,
  active_users     integer not null default 0,
  licensed_users   integer not null default 0,
  business_writes  bigint not null default 0,
  api_requests     bigint not null default 0,
  automation_runs  bigint not null default 0,
  storage_bytes    bigint not null default 0,
  record_count     bigint not null default 0,
  feature_usage    jsonb not null default '{}'::jsonb,
  captured_at      timestamptz not null default now(),
  unique (tenant_id, day)
);

create index idx_usage_snapshot_day on observability.usage_snapshot(tenant_id, day desc);

do $$
declare
  t text;
begin
  foreach t in array array[
    'observability.request_metric',
    'observability.sli_evaluation',
    'observability.sli_alert',
    'observability.usage_snapshot'
  ] loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format($p$create policy tenant_isolation on %s
        using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
        with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
  end loop;
end $$;

grant select, insert, update on observability.request_metric to axiom_app;
grant select, insert on observability.sli_evaluation to axiom_app;
grant select, insert, update on observability.sli_alert to axiom_app;
grant select, insert, update on observability.usage_snapshot to axiom_app;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('observability','request_metric','OBSERVABILITY','id',true,'ACTIVE'),
  ('observability','sli_definition','OBSERVABILITY','code',false,'PLATFORM'),
  ('observability','sli_evaluation','OBSERVABILITY','id',true,'APPEND_ONLY'),
  ('observability','sli_alert','OBSERVABILITY','id',true,'ACTIVE'),
  ('observability','usage_snapshot','OBSERVABILITY','id',true,'ACTIVE')
on conflict do nothing;

insert into governance.screen_catalog(screen_code, module_code, route, display_name, description, sort_order) values
  ('AUDIT_COMPLIANCE', 'GOVERNANCE', '/audit', 'Audit & Compliance',
   'Audit trail with chain verification, data subject requests, consent register, retention and legal hold, tenant export and usage telemetry.', 130)
on conflict (screen_code) do nothing;

insert into governance.rbac_policy(role_code, screen_code, can_read, can_write, can_export, can_admin, scope)
select r.role_code, 'AUDIT_COMPLIANCE', r.can_read, r.can_write, r.can_export, r.can_admin,
       case when r.role_code in ('SUPER_ADMIN','SUPER_AUDIT') then 'PLATFORM' else 'TENANT' end
from (values
  ('SUPER_ADMIN',  true,  true,  true,  true),
  ('SUPER_AUDIT',  true,  false, true,  false),
  ('TENANT_ADMIN', true,  true,  true,  true),
  ('AUDITOR',      true,  false, true,  false),
  ('DATA_STEWARD', true,  false, true,  false),
  ('OPERATIONS',   true,  false, true,  false)
) as r(role_code, can_read, can_write, can_export, can_admin)
on conflict (role_code, screen_code) do nothing;

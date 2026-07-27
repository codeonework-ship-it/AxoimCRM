-- P0E15 - durable reporting reconciliation and production certification evidence.
--
-- A certificate is an observation, never mutable configuration.  The application
-- inserts one row per run and the RLS boundary makes the evidence tenant-local.

create table analytics.kpi_reconciliation_run (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  run_group_id    uuid not null,
  metric_code     text not null,
  projected       numeric(30,8),
  authoritative   numeric(30,8),
  drift           numeric(30,8),
  status          text not null check (status in ('MATCH','DRIFT','ERROR')),
  detail          text not null,
  duration_ms     integer not null default 0 check (duration_ms >= 0),
  run_at          timestamptz not null default now()
);

create index idx_kpi_reconciliation_recent
  on analytics.kpi_reconciliation_run (tenant_id, run_at desc, metric_code);
create index idx_kpi_reconciliation_drift
  on analytics.kpi_reconciliation_run (tenant_id, status, run_at desc)
  where status <> 'MATCH';

create table analytics.performance_certification_run (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references platform.tenant(id),
  profile               text not null check (profile in ('PRODUCTION')),
  status                text not null check (status in ('PASS','FAIL','INSUFFICIENT_EVIDENCE')),
  projected_rows        bigint not null check (projected_rows >= 0),
  minimum_rows          bigint not null check (minimum_rows > 0),
  executions            bigint not null check (executions >= 0),
  minimum_executions    integer not null check (minimum_executions > 0),
  p95_ms                integer,
  maximum_p95_ms        integer not null check (maximum_p95_ms > 0),
  maximum_ms            integer,
  timeouts              bigint not null default 0 check (timeouts >= 0),
  projection_drifts     integer not null default 0 check (projection_drifts >= 0),
  kpi_drifts            integer not null default 0 check (kpi_drifts >= 0),
  evidence_window_days  integer not null check (evidence_window_days > 0),
  detail                jsonb not null default '{}'::jsonb,
  certified_by          uuid,
  started_at            timestamptz not null default now(),
  finished_at           timestamptz not null default now()
);

create index idx_performance_certification_recent
  on analytics.performance_certification_run (tenant_id, finished_at desc);

alter table analytics.kpi_reconciliation_run enable row level security;
alter table analytics.kpi_reconciliation_run force row level security;
create policy tenant_isolation on analytics.kpi_reconciliation_run
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table analytics.performance_certification_run enable row level security;
alter table analytics.performance_certification_run force row level security;
create policy tenant_isolation on analytics.performance_certification_run
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

grant select, insert on analytics.kpi_reconciliation_run,
  analytics.performance_certification_run to axiom_app;


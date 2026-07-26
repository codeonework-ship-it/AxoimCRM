-- ---------------------------------------------------------------------------
-- E15 — reporting read model (ADR-008, docs/product/14-reporting-and-analytics.md).
--
-- WHAT THIS IS
-- The *query* half of ADR-008. Document rendering (Jasper) already exists in
-- `reporting`; what did not exist was a read model, so every analytical query
-- ran against the OLTP tables — the exact contention ADR-008 decision 1 exists
-- to prevent. This migration creates the separate storage: denormalized fact
-- tables maintained by outbox consumers, immutable snapshot tables, a governed
-- KPI registry, and the bookkeeping for staleness, guardrails and drift.
--
-- WHAT IT DELIBERATELY IS NOT
-- It is NOT an authorization store. ADR-008 decision 4 is unambiguous: "the
-- projection aggregates; it is never the authority on what a user may see."
-- So no fact table here carries a materialized ACL, sharing key or permission
-- bit. `owner_id` and `account_id` are present because they are *reportable
-- attributes* (group by owner, filter by account), not because anything is
-- allowed to authorize on them. Every access decision — for aggregates and for
-- drill-through alike — is taken by AuthorizationService against crm/sales.
--
-- ADR-008 decision 7: same engine, separate storage. A dedicated columnar store
-- is deferred until measurement justifies it, so this is a schema, not a
-- cluster. The Java side reads only through the fact tables, so swapping the
-- storage later is a new implementation rather than a rewrite of the callers.
--
-- NOTE ON `nullif(current_setting('app.tenant_id', true), '')::uuid`
-- Repeated from V10/V13/V240 because it is load-bearing. TenantSessionAspect
-- uses set_config(..., true) == SET LOCAL; when that transaction ends
-- PostgreSQL restores the placeholder GUC to the EMPTY STRING, not NULL, and a
-- bare ''::uuid cast raises `invalid input syntax for type uuid: ""` on the
-- next pooled connection. nullif() turns it into NULL, the comparison is NULL,
-- and the row is filtered out — the correct outcome for an unbound connection.
-- ---------------------------------------------------------------------------

create schema if not exists analytics;
grant usage on schema analytics to axiom_app;

-- The runtime search_path is a shared resource: other module migrations land in
-- an order this file cannot know. Merge rather than replace (the V70/V90/V240
-- pattern) so a schema added by a migration that ran earlier is not dropped.
do $$
declare
  existing text;
  parts text[];
  merged text[];
begin
  select split_part(cfg, '=', 2) into existing
    from pg_db_role_setting s
    join pg_roles r on r.oid = s.setrole
    cross join unnest(s.setconfig) as cfg
   where r.rolname = 'axiom_app'
     and cfg like 'search_path=%'
   limit 1;

  parts := coalesce(
    (select array_agg(btrim(p)) from unnest(string_to_array(coalesce(existing, 'public'), ',')) p
      where btrim(p) <> ''),
    array['public']::text[]);

  merged := coalesce((select array_agg(p) from unnest(parts) p where p <> 'public'), array[]::text[]);
  if not ('analytics' = any(merged)) then
    merged := array_append(merged, 'analytics');
  end if;
  merged := array_append(merged, 'public');
  execute format('alter role axiom_app set search_path to %s', array_to_string(merged, ', '));
end $$;

-- ---------------------------------------------------------------------------
-- PROJECTIONS
--
-- One row per source record, denormalized for query shape rather than write
-- integrity: the stage name, account name and owner name are copied in so a
-- "pipeline by stage by owner" report is one scan of one table instead of a
-- four-way join against tables that transactional writers are holding locks on.
--
-- `source_updated_at` is the SOURCE record's updated_at — it is the watermark,
-- and it is what makes the consumer safe under ADR-003's at-least-once,
-- per-key-ordered delivery. The upsert refuses a row whose watermark is not
-- newer than the stored one, so a redelivered or late event can never move a
-- projection backwards. `projected_at` is ours and feeds only the staleness
-- display; conflating the two is how a lag indicator ends up always reading
-- zero.
-- ---------------------------------------------------------------------------

create table analytics.opportunity_fact (
  opportunity_id       uuid primary key,
  tenant_id            uuid not null references platform.tenant(id),
  name                 text not null,
  account_id           uuid,
  account_name         text,
  account_industry     text,
  account_segment      text,
  account_territory    text,
  owner_id             uuid,
  owner_name           text,
  pipeline_id          uuid,
  stage_id             uuid,
  stage_name           text,
  stage_sort_order     integer,
  stage_is_closed      boolean not null default false,
  stage_is_won         boolean not null default false,
  forecast_category    text,
  record_type          text,
  currency_code        text,
  amount               numeric(20,4) not null default 0,
  weighted_amount      numeric(20,4) not null default 0,
  recurring_amount     numeric(20,4),
  one_time_amount      numeric(20,4),
  term_months          integer,
  -- ACV per doc 14 §3: total recurring value / term in years. Materialized here
  -- rather than recomputed per query so two reports cannot disagree about it.
  acv                  numeric(20,4),
  arr                  numeric(20,4),
  tcv                  numeric(20,4),
  probability          numeric(5,2),
  close_date           date,
  original_close_date  date,
  created_on           date,
  closed_at            timestamptz,
  is_closed            boolean not null default false,
  is_won               boolean,
  close_outcome        text,
  slip_count           integer not null default 0,
  cumulative_slip_days integer not null default 0,
  stage_entered_at     timestamptz,
  age_days             integer,
  cycle_days           integer,
  source_updated_at    timestamptz not null,
  projected_at         timestamptz not null default now(),
  constraint uq_opportunity_fact_tenant unique (tenant_id, opportunity_id)
);

create index idx_opportunity_fact_tenant on analytics.opportunity_fact (tenant_id, close_date);
create index idx_opportunity_fact_owner on analytics.opportunity_fact (tenant_id, owner_id);
create index idx_opportunity_fact_stage on analytics.opportunity_fact (tenant_id, stage_sort_order);
create index idx_opportunity_fact_fresh on analytics.opportunity_fact (tenant_id, source_updated_at desc);

create table analytics.lead_fact (
  lead_id                  uuid primary key,
  tenant_id                uuid not null references platform.tenant(id),
  full_name                text not null,
  company                  text,
  owner_id                 uuid,
  owner_name               text,
  status                   text,
  status_category          text,
  rating                   text,
  source                   text,
  campaign_code            text,
  territory                text,
  segment                  text,
  score                    numeric(10,2),
  created_on               date,
  converted_at             timestamptz,
  converted_opportunity_id uuid,
  disqualified_at          timestamptz,
  disqualification_reason  text,
  is_converted             boolean not null default false,
  is_disqualified          boolean not null default false,
  sla_breached             boolean not null default false,
  first_response_minutes   integer,
  source_updated_at        timestamptz not null,
  projected_at             timestamptz not null default now(),
  constraint uq_lead_fact_tenant unique (tenant_id, lead_id)
);

create index idx_lead_fact_tenant on analytics.lead_fact (tenant_id, created_on);
create index idx_lead_fact_owner on analytics.lead_fact (tenant_id, owner_id);
create index idx_lead_fact_fresh on analytics.lead_fact (tenant_id, source_updated_at desc);

create table analytics.activity_fact (
  activity_id         uuid primary key,
  tenant_id           uuid not null references platform.tenant(id),
  subject             text not null,
  activity_type       text,
  status              text,
  direction           text,
  outcome             text,
  owner_id            uuid,
  owner_name          text,
  related_entity_type text,
  related_entity_id   uuid,
  account_id          uuid,
  account_name        text,
  occurred_on         date,
  occurred_at         timestamptz,
  completed_at        timestamptz,
  duration_minutes    integer,
  is_completed        boolean not null default false,
  source_updated_at   timestamptz not null,
  projected_at        timestamptz not null default now(),
  constraint uq_activity_fact_tenant unique (tenant_id, activity_id)
);

create index idx_activity_fact_tenant on analytics.activity_fact (tenant_id, occurred_on);
create index idx_activity_fact_account on analytics.activity_fact (tenant_id, account_id);
create index idx_activity_fact_fresh on analytics.activity_fact (tenant_id, source_updated_at desc);

create table analytics.account_fact (
  account_id             uuid primary key,
  tenant_id              uuid not null references platform.tenant(id),
  name                   text not null,
  industry               text,
  segment                text,
  territory              text,
  business_unit          text,
  status                 text,
  owner_id               uuid,
  owner_name             text,
  health_score           numeric(10,2),
  health_band            text,
  annual_revenue         numeric(20,4),
  employee_count         integer,
  contact_count          integer not null default 0,
  open_opportunity_count integer not null default 0,
  open_pipeline_amount   numeric(20,4) not null default 0,
  won_amount             numeric(20,4) not null default 0,
  activity_count         integer not null default 0,
  last_activity_at       timestamptz,
  created_on             date,
  source_updated_at      timestamptz not null,
  projected_at           timestamptz not null default now(),
  constraint uq_account_fact_tenant unique (tenant_id, account_id)
);

create index idx_account_fact_tenant on analytics.account_fact (tenant_id, name);
create index idx_account_fact_owner on analytics.account_fact (tenant_id, owner_id);
create index idx_account_fact_fresh on analytics.account_fact (tenant_id, source_updated_at desc);

-- Stage occupancy, projected from sales.stage_history.
--
-- Its own table rather than columns on opportunity_fact because stage conversion
-- is a COHORT measure — "opportunities that entered stage n in the period" — and
-- a cohort needs one row per entry, not one row per opportunity. Doc 14 §3 is
-- explicit that the point-in-time census alternative "double-counts stalled
-- deals", which is exactly what computing it from the current stage would do.
--
-- Not exposed as a reportable dataset: it is an input to a governed KPI, and a
-- second way to count stage movements is a second answer waiting to happen.
create table analytics.stage_transition_fact (
  transition_id    uuid primary key,
  tenant_id        uuid not null references platform.tenant(id),
  opportunity_id   uuid not null,
  owner_id         uuid,
  from_stage_id    uuid,
  to_stage_id      uuid,
  to_stage_name    text,
  to_stage_order   integer,
  transition_kind  text,
  entered_at       timestamptz not null,
  entered_on       date not null,
  exited_at        timestamptz,
  duration_seconds bigint,
  -- Did the opportunity leave this stage in the FORWARD direction? That is the
  -- numerator of stage conversion, and it is decided here, once, rather than in
  -- each report's SQL.
  exited_forward   boolean not null default false,
  amount           numeric(20,4) not null default 0,
  source_updated_at timestamptz not null,
  projected_at     timestamptz not null default now(),
  constraint uq_stage_transition_fact unique (tenant_id, transition_id)
);

create index idx_stage_transition_cohort
  on analytics.stage_transition_fact (tenant_id, to_stage_order, entered_on);

-- ---------------------------------------------------------------------------
-- Outbox consumer cursor, one per (tenant, consumer, dataset).
--
-- Kept per dataset rather than per tenant so a single bad projection can be
-- rewound and replayed on its own. ADR-008's honest consequence — "rebuilding a
-- projection after a bug is a long operation" — is not made shorter by forcing
-- a rewind of the other three as well.
--
-- Ordering is (created_at, id): a strict row comparison, so two events
-- committed in the same microsecond cannot make the consumer skip one or
-- replay forever.
-- ---------------------------------------------------------------------------
create table analytics.projection_checkpoint (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  consumer       text not null default 'read-model',
  dataset        text not null,
  last_event_at  timestamptz not null default '-infinity',
  last_event_id  uuid,
  events_applied bigint not null default 0,
  rows_written   bigint not null default 0,
  rows_removed   bigint not null default 0,
  last_error     text,
  updated_at     timestamptz not null default now(),
  constraint uq_projection_checkpoint unique (tenant_id, consumer, dataset)
);

-- Backfill / replay runs. Needed because an event-fed projection is only as
-- complete as the event history it has seen, and three ordinary situations
-- leave it incomplete: a fresh deployment, a fixed projection bug, a restore.
-- It is also the entry point that makes the projection path testable with no
-- broker running at all (ADR-003 degraded mode).
create table analytics.projection_backfill_run (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  dataset         text,
  status          text not null default 'QUEUED'
                  check (status in ('QUEUED','RUNNING','COMPLETED','FAILED')),
  reason          text,
  total_units     bigint not null default 0,
  processed_units bigint not null default 0,
  rows_written    bigint not null default 0,
  rows_removed    bigint not null default 0,
  requested_by    uuid,
  queued_at       timestamptz not null default now(),
  started_at      timestamptz,
  finished_at     timestamptz,
  message         text
);

create index idx_projection_backfill_tenant on analytics.projection_backfill_run (tenant_id, queued_at desc);

-- ---------------------------------------------------------------------------
-- SNAPSHOTS — immutable, by construction rather than by convention
--
-- ADR-008 decision 2 and doc 14 §7. Historical trending must not be
-- reconstructed from audit data: reconstruction "is slow, fragile and produces
-- numbers that do not quite tie out — which in a forecast review is worse than
-- no number at all."
--
-- Immutability is enforced with a trigger, not a code review. UPDATE is refused
-- outright. DELETE is refused unless the caller has announced itself as the
-- retention sweep by setting app.snapshot_retention_sweep — retention is the
-- one legitimate reason a snapshot row ever disappears, and ADR-008 requires a
-- retention policy because "snapshots become the largest data in the system
-- without one".
-- ---------------------------------------------------------------------------

create or replace function analytics.forbid_snapshot_mutation() returns trigger
language plpgsql as $$
begin
  if tg_op = 'UPDATE' then
    raise exception 'Snapshot rows are immutable (ADR-008): % may not be updated', tg_table_name
      using errcode = '42501';
  end if;
  if coalesce(current_setting('app.snapshot_retention_sweep', true), '') <> 'on' then
    raise exception
      'Snapshot rows are immutable (ADR-008): % may only be removed by the retention sweep', tg_table_name
      using errcode = '42501';
  end if;
  return old;
end $$;

create table analytics.pipeline_snapshot (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  captured_at       timestamptz not null default now(),
  captured_on       date not null default current_date,
  capture_reason    text not null default 'SCHEDULED',
  stage_id          uuid,
  stage_name        text not null,
  stage_sort_order  integer not null default 0,
  forecast_category text,
  opportunity_count integer not null default 0,
  total_amount      numeric(20,4) not null default 0,
  weighted_amount   numeric(20,4) not null default 0,
  -- One capture per stage per day: re-running the scheduler must not silently
  -- double the history, and a conflict here is the honest signal that it tried.
  constraint uq_pipeline_snapshot_day unique (tenant_id, captured_on, capture_reason, stage_name)
);

create index idx_pipeline_snapshot_trend on analytics.pipeline_snapshot (tenant_id, captured_on desc);

create table analytics.forecast_snapshot (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  captured_at        timestamptz not null default now(),
  captured_on        date not null default current_date,
  capture_reason     text not null default 'SCHEDULED',
  period_code        text not null,
  period_start       date,
  period_end         date,
  commit_amount      numeric(20,4) not null default 0,
  best_case_amount   numeric(20,4) not null default 0,
  pipeline_amount    numeric(20,4) not null default 0,
  omitted_amount     numeric(20,4) not null default 0,
  closed_won_amount  numeric(20,4) not null default 0,
  closed_lost_amount numeric(20,4) not null default 0,
  open_count         integer not null default 0,
  line_count         integer not null default 0,
  constraint uq_forecast_snapshot_day unique (tenant_id, captured_on, capture_reason, period_code)
);

create index idx_forecast_snapshot_trend on analytics.forecast_snapshot (tenant_id, period_code, captured_on desc);

-- Snapshot LINES are what make FR-FCT-005 and FR-FCT-006 exact rather than
-- approximate: a forecast that decomposes to its source deals, and a movement
-- waterfall whose components reconcile to the net change, are trivial with
-- lines and effectively impossible without them.
create table analytics.forecast_snapshot_line (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  snapshot_id       uuid not null references analytics.forecast_snapshot(id) on delete cascade,
  opportunity_id    uuid not null,
  opportunity_name  text not null,
  account_name      text,
  owner_id          uuid,
  owner_name        text,
  stage_name        text,
  forecast_category text,
  amount            numeric(20,4) not null default 0,
  weighted_amount   numeric(20,4) not null default 0,
  probability       numeric(5,2),
  close_date        date,
  is_closed         boolean not null default false,
  is_won            boolean,
  constraint uq_forecast_snapshot_line unique (snapshot_id, opportunity_id)
);

create index idx_forecast_snapshot_line_snap on analytics.forecast_snapshot_line (tenant_id, snapshot_id);

do $$
declare t text;
begin
  foreach t in array array[
    'analytics.pipeline_snapshot',
    'analytics.forecast_snapshot',
    'analytics.forecast_snapshot_line'
  ]
  loop
    execute format(
      'create trigger %s_immutable before update or delete on %s '
      'for each row execute function analytics.forbid_snapshot_mutation()',
      replace(split_part(t, '.', 2), '.', '_'), t);
  end loop;
end $$;

-- Retention. Configured, not hard-coded, and stated with its trade-off: a
-- shorter window costs history that cannot be recovered, a longer one costs
-- storage that grows without bound.
create table analytics.snapshot_retention_policy (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  snapshot_type text not null check (snapshot_type in ('PIPELINE','FORECAST')),
  retain_days   integer not null default 730 check (retain_days between 7 and 3650),
  last_swept_at timestamptz,
  rows_removed  bigint not null default 0,
  updated_at    timestamptz not null default now(),
  constraint uq_snapshot_retention unique (tenant_id, snapshot_type)
);

-- ---------------------------------------------------------------------------
-- GOVERNED KPI REGISTRY (FR-RPT-009)
--
-- "A metric with more than one active definition is a defect, not a
-- configuration choice." A defect that only a code path prevents is a defect
-- waiting for the next code path, so the rule lives in a UNIQUE INDEX: the
-- database refuses the second ACTIVE row for a metric outright.
--
-- Definitions are versioned and superseded rather than edited, because doc 14
-- requires a historical figure to stay reproducible under the definition
-- version in force when it was computed. Editing in place destroys that.
--
-- Deliberately NOT reporting.kpi_definition: that table is a tenant-editable
-- KPI *tracker* (current_value / target_value) consumed by the workspaces
-- module. Overloading it would have made a governed published definition and a
-- mutable dashboard tile the same row, which is the confusion FR-RPT-009 is
-- about.
-- ---------------------------------------------------------------------------
create table analytics.metric_definition (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  metric_code      text not null check (metric_code ~ '^[A-Z][A-Z0-9_]*$'),
  name             text not null,
  version          integer not null default 1 check (version >= 1),
  formula          text not null,
  basis            text,
  unit             text not null default 'NUMBER'
                   check (unit in ('NUMBER','CURRENCY','PERCENT','RATIO','DAYS','MONTHS','CURRENCY_PER_DAY')),
  notes            text,
  requirement_ref  text,
  source_reference text,
  status           text not null default 'DRAFT'
                   check (status in ('DRAFT','ACTIVE','RETIRED')),
  published_at     timestamptz,
  published_by     uuid,
  retired_at       timestamptz,
  supersedes_id    uuid,
  created_at       timestamptz not null default now(),
  constraint uq_metric_definition_version unique (tenant_id, metric_code, version)
);

-- THE constraint. One active definition per metric, enforced by the database.
create unique index uq_metric_definition_single_active
  on analytics.metric_definition (tenant_id, metric_code)
  where status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- RECONCILIATION (ADR-008 consequences, and its Compliance section)
--
-- "Projection logic is a second implementation of business meaning, and it can
-- drift from the transactional model. This is the real long-term risk of this
-- pattern. It requires reconciliation tests that compare projected aggregates
-- against authoritative recomputation on a schedule — not as a one-time
-- verification."
--
-- So drift is a stored, scheduled, queryable observation rather than a test
-- someone ran once.
-- ---------------------------------------------------------------------------
create table analytics.reconciliation_run (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  run_at         timestamptz not null default now(),
  check_code     text not null,
  check_label    text not null,
  dataset        text not null,
  projected      numeric(24,4),
  authoritative  numeric(24,4),
  drift          numeric(24,4),
  drift_pct      numeric(12,6),
  tolerance      numeric(24,4) not null default 0,
  status         text not null check (status in ('MATCH','DRIFT','ERROR')),
  detail         text,
  duration_ms    integer not null default 0
);

create index idx_reconciliation_run_recent on analytics.reconciliation_run (tenant_id, run_at desc);
create index idx_reconciliation_run_drift on analytics.reconciliation_run (tenant_id, status, run_at desc)
  where status <> 'MATCH';

-- ---------------------------------------------------------------------------
-- SAVED REPORTS (FR-RPT-001, FR-RPT-002) and QUERY GUARDRAIL LOG (FR-RPT-011)
--
-- The definition is jsonb because a report is user-authored structure, not a
-- fixed shape — but every identifier inside it is validated against the Java
-- dataset registry before a single character reaches SQL. A tenant-writable
-- table is never allowed to name a column.
-- ---------------------------------------------------------------------------
create table analytics.report_view (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  code        text not null check (code ~ '^[a-z][a-z0-9_]*$'),
  name        text not null,
  description text,
  dataset     text not null,
  format      text not null default 'TABULAR'
              check (format in ('TABULAR','SUMMARY','MATRIX')),
  definition  jsonb not null default '{}'::jsonb,
  created_by  uuid,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  constraint uq_report_view_code unique (tenant_id, code)
);

create table analytics.query_execution (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  executed_by   uuid,
  dataset       text not null,
  format        text,
  row_count     integer not null default 0,
  scanned_rows  integer not null default 0,
  truncated     boolean not null default false,
  elapsed_ms    integer not null default 0,
  status        text not null check (status in ('OK','TRUNCATED','TIMEOUT','REJECTED')),
  message       text,
  executed_at   timestamptz not null default now()
);

create index idx_query_execution_recent on analytics.query_execution (tenant_id, executed_at desc);

-- ---------------------------------------------------------------------------
-- Row-level security — the independent second enforcement layer (ADR-001),
-- applied to every tenant-scoped table in this module, FORCEd so the owner is
-- not exempt.
-- ---------------------------------------------------------------------------
do $$
declare t text;
begin
  foreach t in array array[
    'analytics.opportunity_fact',
    'analytics.lead_fact',
    'analytics.activity_fact',
    'analytics.account_fact',
    'analytics.stage_transition_fact',
    'analytics.projection_checkpoint',
    'analytics.projection_backfill_run',
    'analytics.pipeline_snapshot',
    'analytics.forecast_snapshot',
    'analytics.forecast_snapshot_line',
    'analytics.snapshot_retention_policy',
    'analytics.metric_definition',
    'analytics.reconciliation_run',
    'analytics.report_view',
    'analytics.query_execution'
  ]
  loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format(
      'create policy tenant_isolation on %s '
      'using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
      t);
  end loop;
end $$;

grant select, insert, update, delete on
  analytics.opportunity_fact, analytics.lead_fact, analytics.activity_fact,
  analytics.account_fact, analytics.stage_transition_fact, analytics.projection_checkpoint,
  analytics.projection_backfill_run, analytics.snapshot_retention_policy,
  analytics.metric_definition, analytics.reconciliation_run,
  analytics.report_view, analytics.query_execution
  to axiom_app;

-- No UPDATE grant on the snapshot tables. The trigger already refuses it; the
-- missing grant means an accidental UPDATE fails before it even reaches the
-- trigger, and a reader of \dp can see the intent without reading plpgsql.
grant select, insert, delete on
  analytics.pipeline_snapshot, analytics.forecast_snapshot, analytics.forecast_snapshot_line
  to axiom_app;

-- ---------------------------------------------------------------------------
-- Seed: the published KPI definitions, verbatim from
-- docs/product/14-reporting-and-analytics.md §3.
--
-- Seeded rather than left to an administrator because FR-RPT-009 is about the
-- *standard* metrics having one published meaning. An empty registry on day one
-- means the first three reports each invent a win rate, which is precisely the
-- "three win rates and a standing argument" outcome F-247 exists to prevent.
-- ---------------------------------------------------------------------------
insert into analytics.metric_definition
  (tenant_id, metric_code, name, version, formula, basis, unit, notes, requirement_ref,
   source_reference, status, published_at)
select t.id, m.metric_code, m.name, 1, m.formula, m.basis, m.unit, m.notes, m.requirement_ref,
       'docs/product/14-reporting-and-analytics.md §3', 'ACTIVE', now()
from platform.tenant t
cross join (values
  ('PIPELINE_COVERAGE', 'Pipeline coverage',
   'open pipeline value in period / remaining quota for period',
   'Open pipeline = opportunities with close date in the period, in configured forecast categories (default: PIPELINE, BEST_CASE, COMMIT). Remaining quota = quota - closed won credited to the period.',
   'RATIO',
   'Not computable without a configured quota for the period; the missing input is named rather than a number being shown.',
   'FR-FCT-009, FR-FCT-012'),
  ('SALES_VELOCITY', 'Sales velocity',
   '(open qualified opportunity count * average deal size * win rate) / average sales cycle days',
   'Currency per day. Each of the four inputs is itself a governed KPI computed over the same slice and is displayed with the result.',
   'CURRENCY_PER_DAY',
   'The four inputs are returned alongside the result; a velocity figure without them is not an explainable number.',
   'FR-FCT-009'),
  ('STAGE_CONVERSION', 'Stage conversion',
   'opportunities that exited stage n forward / opportunities that entered stage n',
   'Cohort basis over opportunities entering the stage in the period, taken from stage history.',
   'PERCENT',
   'Deliberately not a point-in-time census, which double-counts stalled deals.',
   'FR-OPP-011'),
  ('WIN_RATE', 'Win rate',
   'closed won count / (closed won count + closed lost count)',
   'Count basis is the published basis. A value-weighted variant is a separately named metric, never a silent redefinition.',
   'PERCENT',
   'Deals closed as disqualified or no-decision are excluded, and the exclusion is stated on the figure.',
   'FR-FCT-010'),
  ('AVERAGE_DEAL_SIZE', 'Average deal size',
   'sum of closed won amount / closed won count',
   'Corporate currency at the stored conversion rate.',
   'CURRENCY',
   'Never recomputed at today''s rate; the rate in force at close is the one that stands.',
   'FR-MDM-002'),
  ('ACV', 'Annual contract value',
   'total recurring value / term in years',
   'One-time amounts excluded and reported separately.',
   'CURRENCY',
   'A deal with no term or no recurring component contributes nothing rather than being counted at its total.',
   'FR-OPP-016'),
  ('ARR', 'Annual recurring revenue',
   'sum of annualized value of active subscriptions at the measurement date',
   'A point-in-time stock, not a period flow.',
   'CURRENCY',
   'Measured at a date. Summing ARR over a period is a category error and is not offered.',
   'FR-OPP-016'),
  ('TCV', 'Total contract value',
   'sum of all contracted value over the full term, recurring plus one-time',
   'Full term, both components.',
   'CURRENCY',
   'Reported alongside ACV so a long-term deal cannot be read as an annual one.',
   'FR-OPP-016'),
  ('QUOTA_ATTAINMENT', 'Quota attainment',
   'credited closed revenue in period / assigned quota for period',
   'The credit basis - closed revenue versus split-credited revenue - is stated explicitly on the figure.',
   'PERCENT',
   'Not computable without an assigned quota; the missing input is named.',
   'FR-FCT-012, FR-OPP-006'),
  ('FORECAST_ACCURACY', 'Forecast accuracy',
   '1 - (abs(actual - submitted) / actual), per user per period',
   'Computed against the locked submission snapshot, never against a retroactively edited number.',
   'PERCENT',
   'Published together with forecast bias: accuracy without bias hides direction.',
   'FR-FCT-004, FR-FCT-011'),
  ('FORECAST_BIAS', 'Forecast bias',
   'mean of ((submitted - actual) / actual), signed, per user over trailing periods',
   'Signed on purpose.',
   'PERCENT',
   'Positive means habitual over-forecasting.',
   'FR-FCT-011'),
  ('SLIPPAGE_RATE', 'Slippage rate',
   'opportunities whose close date moved out of the period / opportunities forecast in the period at its opening snapshot',
   'Denominator anchored to the opening snapshot, not to today''s population.',
   'PERCENT',
   'A value-weighted variant is separately named.',
   'FR-OPP-010'),
  ('MQL_SQL_CONVERSION', 'MQL to SQL conversion',
   'MQLs accepted by sales / MQLs handed off, cohorted by hand-off date',
   'Acceptance and rejection carry a reason.',
   'PERCENT',
   'Rejections are reported alongside, not hidden in the denominator.',
   'FR-CMP-006'),
  ('CAMPAIGN_ROI', 'Campaign ROI',
   '(attributed closed revenue - actual campaign cost) / actual campaign cost',
   'The attribution model and the sourcing definition (sourced versus influenced) are stated on every figure.',
   'PERCENT',
   'ROI without its model named is not a valid output and is withheld rather than shown.',
   'FR-CMP-005, FR-CMP-007'),
  ('CAC_PAYBACK', 'CAC payback',
   'customer acquisition cost / (average ARR per new customer * gross margin %), in months',
   'Requires sales and marketing cost and cost-of-service data from the finance system.',
   'MONTHS',
   'Axiom holds campaign cost but not payroll, tooling or COGS. Without configured finance inputs this KPI displays as not computable with its missing inputs named - it does not display a number built from partial cost data, which would be confidently wrong in the direction that flatters.',
   'FR-CMP-001')
) as m(metric_code, name, formula, basis, unit, notes, requirement_ref)
on conflict do nothing;

-- Default retention: two years of daily snapshots. Long enough for
-- year-over-year trending, bounded enough that the table does not become the
-- largest data in the system.
insert into analytics.snapshot_retention_policy (tenant_id, snapshot_type, retain_days)
select t.id, s.snapshot_type, 730
from platform.tenant t cross join (values ('PIPELINE'), ('FORECAST')) as s(snapshot_type)
on conflict do nothing;

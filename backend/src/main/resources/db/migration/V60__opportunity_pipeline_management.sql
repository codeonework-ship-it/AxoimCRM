-- =============================================================================
-- E06 — Opportunity and pipeline management (FR-OPP-001..016).
--
-- Adds the `pipeline` module schema for pipeline *configuration* (named
-- pipelines, versioned stage gate criteria, governed close-reason taxonomy,
-- competitor catalogue, qualification frameworks, and a minimal deal-desk price
-- list that E08 will supersede) and extends the `sales` schema with the
-- opportunity's transactional children (stage history, line items, splits,
-- competitors, close-date changes, closures, qualification answers, approvals
-- and the append-only state history that powers the movement comparison).
--
-- Two design points are load-bearing and deliberate:
--
--  * STAGE_HISTORY.criteria_version_id pins the exit-criteria version in force
--    when the opportunity ENTERED the stage. Criteria are versioned and never
--    edited in place, so an in-flight opportunity is never re-judged against
--    criteria that did not exist when it entered (FR-OPP-003).
--  * OPPORTUNITY_LINE stores BOTH total and computed_total. An override must
--    never destroy the evidence of what the system calculated (FR-OPP-005).
-- =============================================================================

create schema if not exists pipeline;
grant usage on schema pipeline to axiom_app;

-- The runtime role's search_path is a shared resource: other module migrations
-- extend it too. Append rather than overwrite so a concurrently-added schema is
-- not silently dropped from the path.
do $$
declare
  current_path text;
begin
  select split_part(cfg, '=', 2)
    into current_path
  from pg_roles r, unnest(coalesce(r.rolconfig, array[]::text[])) cfg
  where r.rolname = 'axiom_app' and cfg like 'search_path=%';

  if current_path is null or btrim(current_path) = '' then
    execute 'alter role axiom_app set search_path to pipeline, public';
  elsif position('pipeline' in current_path) = 0 then
    execute format('alter role axiom_app set search_path to %s', 'pipeline, ' || current_path);
  end if;
end $$;

-- -----------------------------------------------------------------------------
-- FR-OPP-002 — named pipelines, each with its own ordered stages.
-- -----------------------------------------------------------------------------
create table pipeline.pipeline (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  api_name    text not null,
  name        text not null,
  description text,
  is_default  boolean not null default false,
  active      boolean not null default true,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, api_name),
  unique (tenant_id, name),
  constraint pipeline_api_name_format check (api_name ~ '^[a-z][a-z0-9_]*$')
);

create unique index uq_pipeline_single_default on pipeline.pipeline(tenant_id) where is_default;

-- Every tenant gets a default pipeline holding the stages it already has.
insert into pipeline.pipeline (tenant_id, api_name, name, description, is_default)
select t.id, 'default_pipeline', 'Default Pipeline',
       'Stages migrated from the pre-E06 single-pipeline configuration.', true
from platform.tenant t
on conflict (tenant_id, api_name) do nothing;

-- -----------------------------------------------------------------------------
-- Stage configuration: probability, forecast category, transition policy.
-- -----------------------------------------------------------------------------
alter table crm.pipeline_stage
  add column if not exists pipeline_id        uuid,
  add column if not exists probability        numeric(5,2) not null default 0,
  add column if not exists forecast_category  text not null default 'PIPELINE',
  add column if not exists allows_backward    boolean not null default false,
  add column if not exists allows_skip        boolean not null default false,
  add column if not exists stalled_after_days integer not null default 30,
  add column if not exists deleted_at         timestamptz;

update crm.pipeline_stage s
set pipeline_id = p.id
from pipeline.pipeline p
where p.tenant_id = s.tenant_id and p.is_default and s.pipeline_id is null;

-- Northstar has no closed stages; closure needs somewhere to land.
insert into crm.pipeline_stage (tenant_id, name, sort_order, is_closed, is_won, requires_economic_buyer, pipeline_id)
select t.id, v.name, v.sort_order, true, v.is_won, false, p.id
from platform.tenant t
join pipeline.pipeline p on p.tenant_id = t.id and p.is_default
cross join (values ('Closed Won', 900, true), ('Closed Lost', 910, false)) as v(name, sort_order, is_won)
where not exists (
  select 1 from crm.pipeline_stage s
  where s.tenant_id = t.id and s.is_closed and s.is_won = v.is_won
);

alter table crm.pipeline_stage
  alter column pipeline_id set not null,
  add constraint fk_pipeline_stage_pipeline_same_tenant
    foreign key (tenant_id, pipeline_id) references pipeline.pipeline(tenant_id, id),
  add constraint pipeline_stage_probability_range check (probability >= 0 and probability <= 100),
  add constraint pipeline_stage_forecast_category check (
    forecast_category in ('OMITTED', 'PIPELINE', 'BEST_CASE', 'COMMIT', 'CLOSED')),
  add constraint pipeline_stage_stalled_after_days_positive check (stalled_after_days > 0);

-- Name and order are unique WITHIN a pipeline, not within the tenant: two
-- pipelines legitimately both have a "Negotiation" stage at position 3.
alter table crm.pipeline_stage
  drop constraint if exists pipeline_stage_tenant_id_name_key,
  drop constraint if exists pipeline_stage_tenant_id_sort_order_key;
alter table crm.pipeline_stage
  add constraint uq_pipeline_stage_pipeline_name unique (tenant_id, pipeline_id, name),
  add constraint uq_pipeline_stage_pipeline_order unique (tenant_id, pipeline_id, sort_order);

update crm.pipeline_stage s
set probability = c.probability,
    forecast_category = c.forecast_category,
    allows_backward = c.allows_backward,
    allows_skip = c.allows_skip
from (
  select st.id,
         case when st.is_closed and st.is_won then 100
              when st.is_closed then 0
              else least(90, 10 + 20 * (row_number() over (partition by st.tenant_id, st.pipeline_id order by st.sort_order))::numeric)
         end as probability,
         case when st.is_closed then 'CLOSED'
              when st.requires_economic_buyer then 'COMMIT'
              else 'PIPELINE' end as forecast_category,
         not st.is_closed as allows_backward,
         not st.is_closed as allows_skip
  from crm.pipeline_stage st
) c
where c.id = s.id;

-- -----------------------------------------------------------------------------
-- FR-OPP-003 — versioned stage gate criteria. A version is published, never
-- edited: publishing a new version leaves in-flight opportunities pinned to the
-- version recorded on their open STAGE_HISTORY row.
--
-- `gate` distinguishes the two legitimate gate kinds:
--   EXIT  — must be satisfied to LEAVE this stage (the FR-OPP-003 mechanism,
--           pinned to the version in force at stage entry).
--   ENTRY — must be satisfied to ENTER this stage. There is no stage occupancy
--           to pin against yet, so the currently published version applies.
--           This is where the pre-E06 requires_economic_buyer flag now lives.
-- -----------------------------------------------------------------------------
create table pipeline.stage_criteria_version (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  stage_id       uuid not null,
  gate           text not null check (gate in ('EXIT', 'ENTRY')),
  version_number integer not null check (version_number > 0),
  effective_from timestamptz not null default now(),
  published_at   timestamptz not null default now(),
  published_by   uuid,
  notes          text,
  unique (tenant_id, id),
  unique (tenant_id, stage_id, gate, version_number),
  constraint fk_criteria_version_stage_same_tenant
    foreign key (tenant_id, stage_id) references crm.pipeline_stage(tenant_id, id)
);

create table pipeline.stage_exit_criterion (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references platform.tenant(id),
  criteria_version_id uuid not null,
  code                text not null,
  label               text not null,
  criterion_type      text not null check (criterion_type in
                        ('FIELD', 'RELATED_RECORD', 'ACTIVITY', 'APPROVAL', 'QUALIFICATION')),
  expression          jsonb not null,
  message             text not null,
  remediation         text not null,
  sort_order          integer not null default 0,
  created_at          timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, criteria_version_id, code),
  constraint fk_criterion_version_same_tenant
    foreign key (tenant_id, criteria_version_id) references pipeline.stage_criteria_version(tenant_id, id)
);

create index idx_criteria_version_stage on pipeline.stage_criteria_version(tenant_id, stage_id, gate, version_number desc);
create index idx_stage_criterion_version on pipeline.stage_exit_criterion(tenant_id, criteria_version_id, sort_order);

-- -----------------------------------------------------------------------------
-- FR-OPP-012 — governed close-reason taxonomy.
-- -----------------------------------------------------------------------------
create table pipeline.close_reason (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references platform.tenant(id),
  code                text not null,
  label               text not null,
  outcome             text not null check (outcome in ('WON', 'LOST')),
  requires_competitor boolean not null default false,
  sort_order          integer not null default 0,
  active              boolean not null default true,
  created_at          timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, code),
  constraint close_reason_code_format check (code ~ '^[A-Z][A-Z0-9_]*$')
);

insert into pipeline.close_reason (tenant_id, code, label, outcome, requires_competitor, sort_order)
select t.id, v.code, v.label, v.outcome, v.requires_competitor, v.sort_order
from platform.tenant t
cross join (values
  ('COMPETITIVE_DISPLACEMENT', 'Competitive displacement',       'WON',  false, 10),
  ('BEST_VALUE',               'Best overall value',             'WON',  false, 20),
  ('RELATIONSHIP',             'Existing relationship / trust',  'WON',  false, 30),
  ('PRODUCT_FIT',              'Superior product fit',           'WON',  false, 40),
  ('RENEWAL',                  'Renewal or expansion',           'WON',  false, 50),
  ('LOST_TO_COMPETITOR',       'Lost to a competitor',           'LOST', true,  60),
  ('PRICE',                    'Price / commercial terms',       'LOST', false, 70),
  ('NO_DECISION',              'No decision made',               'LOST', false, 80),
  ('NO_BUDGET',                'Budget withdrawn',               'LOST', false, 90),
  ('TIMING',                   'Timing — deferred by customer',  'LOST', false, 100),
  ('FEATURE_GAP',              'Capability gap',                 'LOST', false, 110),
  ('LOST_TO_INTERNAL_BUILD',   'Lost to an in-house build',      'LOST', false, 120)
) as v(code, label, outcome, requires_competitor, sort_order)
on conflict (tenant_id, code) do nothing;

-- -----------------------------------------------------------------------------
-- FR-OPP-007 — competitor catalogue.
-- -----------------------------------------------------------------------------
create table pipeline.competitor (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null references platform.tenant(id),
  name       text not null,
  notes      text,
  active     boolean not null default true,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, name)
);

insert into pipeline.competitor (tenant_id, name, notes)
select t.id, v.name, v.notes
from platform.tenant t
cross join (values
  ('Corvus Systems',   'Incumbent in industrial accounts; aggressive on price.'),
  ('Helios Suite',     'Broad suite play; weak on governed audit trails.'),
  ('Northgate Cloud',  'Strong mid-market brand; thin implementation bench.'),
  ('In-house build',   'Customer engineering team proposing to build internally.')
) as v(name, notes)
on conflict (tenant_id, name) do nothing;

-- -----------------------------------------------------------------------------
-- FR-OPP-008 — configurable qualification methodology.
-- -----------------------------------------------------------------------------
create table pipeline.qualification_framework (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null references platform.tenant(id),
  code       text not null,
  name       text not null,
  kind       text not null check (kind in ('MEDDICC', 'SPICED', 'CUSTOM')),
  is_default boolean not null default false,
  active     boolean not null default true,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, code)
);

create table pipeline.qualification_item (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  framework_id uuid not null,
  code         text not null,
  label        text not null,
  guidance     text not null,
  weight       numeric(6,2) not null default 1 check (weight > 0),
  sort_order   integer not null default 0,
  created_at   timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, framework_id, code),
  constraint fk_qualification_item_framework_same_tenant
    foreign key (tenant_id, framework_id) references pipeline.qualification_framework(tenant_id, id)
);

insert into pipeline.qualification_framework (tenant_id, code, name, kind, is_default)
select t.id, v.code, v.name, v.kind, v.is_default
from platform.tenant t
cross join (values
  ('MEDDICC', 'MEDDICC', 'MEDDICC', true),
  ('SPICED',  'SPICED',  'SPICED',  false)
) as v(code, name, kind, is_default)
on conflict (tenant_id, code) do nothing;

insert into pipeline.qualification_item (tenant_id, framework_id, code, label, guidance, sort_order)
select f.tenant_id, f.id, v.code, v.label, v.guidance, v.sort_order
from pipeline.qualification_framework f
cross join (values
  ('METRICS',           'Metrics',           'Quantified business outcome the customer will measure.', 10),
  ('ECONOMIC_BUYER',    'Economic buyer',    'The person who can release the budget, identified and met.', 20),
  ('DECISION_CRITERIA', 'Decision criteria', 'The written criteria the customer will judge against.', 30),
  ('DECISION_PROCESS',  'Decision process',  'The steps and approvals between here and signature.', 40),
  ('PAPER_PROCESS',     'Paper process',     'Legal, procurement and security review path.', 50),
  ('IDENTIFY_PAIN',     'Identified pain',   'The cost of doing nothing, in the customer''s words.', 60),
  ('CHAMPION',          'Champion',          'An internal advocate who sells on your behalf.', 70),
  ('COMPETITION',       'Competition',       'Who else is in the deal and where you stand.', 80)
) as v(code, label, guidance, sort_order)
where f.code = 'MEDDICC'
on conflict (tenant_id, framework_id, code) do nothing;

insert into pipeline.qualification_item (tenant_id, framework_id, code, label, guidance, sort_order)
select f.tenant_id, f.id, v.code, v.label, v.guidance, v.sort_order
from pipeline.qualification_framework f
cross join (values
  ('SITUATION',      'Situation',      'The customer''s current state and how they operate today.', 10),
  ('PAIN',           'Pain',           'What is broken and who feels it.', 20),
  ('IMPACT',         'Impact',         'The measurable value of fixing it.', 30),
  ('CRITICAL_EVENT', 'Critical event', 'The dated event that forces a decision.', 40),
  ('DECISION',       'Decision',       'Who decides, how, and by when.', 50)
) as v(code, label, guidance, sort_order)
where f.code = 'SPICED'
on conflict (tenant_id, framework_id, code) do nothing;

-- -----------------------------------------------------------------------------
-- FR-OPP-005 — minimal deal-desk price list. E08 (products, price books, quotes
-- and CPQ) owns the real catalogue; this exists so opportunity lines are drawn
-- from a governed price book today rather than free text, and is designed to be
-- superseded by E08 without changing the line-item contract.
-- -----------------------------------------------------------------------------
create table pipeline.price_book (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  name          text not null,
  currency_code text not null default 'USD',
  is_default    boolean not null default false,
  active        boolean not null default true,
  created_at    timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, name)
);

create table pipeline.price_book_entry (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  price_book_id   uuid not null,
  product_code    text not null,
  product_name    text not null,
  unit_of_measure text not null default 'EACH',
  list_price      numeric(14,2) not null check (list_price >= 0),
  unit_cost       numeric(14,2),
  recurring       boolean not null default false,
  active          boolean not null default true,
  created_at      timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, price_book_id, product_code),
  constraint fk_price_book_entry_book_same_tenant
    foreign key (tenant_id, price_book_id) references pipeline.price_book(tenant_id, id)
);

insert into pipeline.price_book (tenant_id, name, is_default)
select t.id, 'Standard Price Book', true from platform.tenant t
on conflict (tenant_id, name) do nothing;

insert into pipeline.price_book_entry
  (tenant_id, price_book_id, product_code, product_name, unit_of_measure, list_price, unit_cost, recurring)
select b.tenant_id, b.id, v.product_code, v.product_name, v.uom, v.list_price, v.unit_cost, v.recurring
from pipeline.price_book b
cross join (values
  ('PLT-CORE',   'Axiom Platform — Core licence',        'USER_YEAR', 1200.00,  380.00, true),
  ('PLT-ADV',    'Axiom Platform — Advanced licence',    'USER_YEAR', 1850.00,  520.00, true),
  ('MOD-CPQ',    'CPQ module',                           'USER_YEAR',  640.00,  180.00, true),
  ('MOD-FCST',   'Forecasting and revenue intelligence', 'USER_YEAR',  480.00,  140.00, true),
  ('SVC-IMPL',   'Implementation services',              'DAY',       1450.00,  900.00, false),
  ('SVC-TRAIN',  'Enablement and training',              'DAY',        980.00,  610.00, false),
  ('SUP-PREM',   'Premium support',                      'YEAR',     18000.00, 6400.00, true)
) as v(product_code, product_name, uom, list_price, unit_cost, recurring)
where b.is_default
on conflict (tenant_id, price_book_id, product_code) do nothing;

-- -----------------------------------------------------------------------------
-- FR-OPP-001, 010, 012, 016 — opportunity record columns.
-- -----------------------------------------------------------------------------
alter table sales.opportunity
  add column if not exists pipeline_id               uuid,
  add column if not exists record_type               text not null default 'STANDARD',
  add column if not exists currency_code             text not null default 'USD',
  add column if not exists probability               numeric(5,2),
  add column if not exists forecast_category         text,
  add column if not exists next_step                 text,
  add column if not exists stage_entered_at          timestamptz,
  add column if not exists original_close_date       date,
  add column if not exists slip_count                integer not null default 0,
  add column if not exists cumulative_slip_days      integer not null default 0,
  add column if not exists closed_at                 timestamptz,
  add column if not exists close_reason_id           uuid,
  add column if not exists won_competitor_id         uuid,
  add column if not exists reopen_count              integer not null default 0,
  add column if not exists qualification_framework_id uuid,
  add column if not exists qualification_score      numeric(5,2) not null default 0,
  add column if not exists recurring_amount          numeric(14,2),
  add column if not exists one_time_amount           numeric(14,2),
  add column if not exists term_months               integer,
  add column if not exists billing_frequency         text,
  add column if not exists arr                       numeric(14,2),
  add column if not exists tcv                       numeric(14,2);

update sales.opportunity o
set pipeline_id = s.pipeline_id,
    probability = coalesce(o.probability, s.probability),
    forecast_category = coalesce(o.forecast_category, s.forecast_category),
    stage_entered_at = coalesce(o.stage_entered_at, o.created_at),
    original_close_date = coalesce(o.original_close_date, o.close_date),
    qualification_framework_id = coalesce(o.qualification_framework_id, f.id)
from crm.pipeline_stage s
left join pipeline.qualification_framework f
       on f.tenant_id = s.tenant_id and f.code = 'MEDDICC'
where s.tenant_id = o.tenant_id and s.id = o.stage_id;

-- Opportunities are also created outside this module (lead conversion, imports).
-- Deriving the pipeline and the stage clock in a BEFORE INSERT trigger means
-- every writer gets consistent values without having to know about E06.
create or replace function sales.opportunity_apply_pipeline_defaults() returns trigger
language plpgsql as $$
declare
  st record;
begin
  select s.pipeline_id, s.probability, s.forecast_category
    into st
  from crm.pipeline_stage s
  where s.tenant_id = new.tenant_id and s.id = new.stage_id;

  new.pipeline_id := coalesce(new.pipeline_id, st.pipeline_id);
  new.probability := coalesce(new.probability, st.probability);
  new.forecast_category := coalesce(new.forecast_category, st.forecast_category);
  new.stage_entered_at := coalesce(new.stage_entered_at, now());
  new.original_close_date := coalesce(new.original_close_date, new.close_date);
  new.qualification_framework_id := coalesce(
    new.qualification_framework_id,
    (select f.id from pipeline.qualification_framework f
      where f.tenant_id = new.tenant_id and f.is_default and f.active limit 1));
  return new;
end $$;

create trigger opportunity_pipeline_defaults
  before insert on sales.opportunity
  for each row execute function sales.opportunity_apply_pipeline_defaults();

alter table sales.opportunity
  alter column pipeline_id set not null,
  alter column stage_entered_at set not null,
  add constraint fk_opportunity_pipeline_same_tenant
    foreign key (tenant_id, pipeline_id) references pipeline.pipeline(tenant_id, id),
  add constraint fk_opportunity_close_reason_same_tenant
    foreign key (tenant_id, close_reason_id) references pipeline.close_reason(tenant_id, id),
  add constraint fk_opportunity_won_competitor_same_tenant
    foreign key (tenant_id, won_competitor_id) references pipeline.competitor(tenant_id, id),
  add constraint fk_opportunity_qual_framework_same_tenant
    foreign key (tenant_id, qualification_framework_id) references pipeline.qualification_framework(tenant_id, id),
  add constraint opportunity_billing_frequency check (
    billing_frequency is null or billing_frequency in ('MONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'ANNUAL')),
  add constraint opportunity_term_months_positive check (term_months is null or term_months > 0),
  add constraint opportunity_slippage_non_negative check (slip_count >= 0 and cumulative_slip_days >= 0);

-- -----------------------------------------------------------------------------
-- FR-OPP-011 — complete stage history.
-- -----------------------------------------------------------------------------
create table sales.stage_history (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references platform.tenant(id),
  opportunity_id      uuid not null,
  from_stage_id       uuid,
  to_stage_id         uuid not null,
  transition_kind     text not null check (transition_kind in
                        ('INITIAL', 'FORWARD', 'SKIP', 'BACKWARD', 'CLOSE', 'REOPEN')),
  entered_at          timestamptz not null default now(),
  exited_at           timestamptz,
  duration_seconds    bigint,
  changed_by          uuid,
  changed_by_name     text,
  reason              text,
  criteria_version_id uuid,
  created_at          timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_stage_history_opportunity_same_tenant
    foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id),
  constraint fk_stage_history_to_stage_same_tenant
    foreign key (tenant_id, to_stage_id) references crm.pipeline_stage(tenant_id, id),
  constraint fk_stage_history_criteria_version_same_tenant
    foreign key (tenant_id, criteria_version_id) references pipeline.stage_criteria_version(tenant_id, id),
  constraint stage_history_duration_with_exit check ((exited_at is null) = (duration_seconds is null))
);

create index idx_stage_history_opportunity on sales.stage_history(tenant_id, opportunity_id, entered_at);
create unique index uq_stage_history_open_occupancy
  on sales.stage_history(tenant_id, opportunity_id) where exited_at is null;

-- -----------------------------------------------------------------------------
-- FR-OPP-005 — line items. `total` may be overridden; `computed_total` always
-- retains what the system calculated.
-- -----------------------------------------------------------------------------
create table sales.opportunity_line (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references platform.tenant(id),
  opportunity_id      uuid not null,
  price_book_entry_id uuid not null,
  product_code        text not null,
  product_name        text not null,
  unit_of_measure     text not null,
  quantity            numeric(14,4) not null check (quantity > 0),
  list_price          numeric(14,2) not null check (list_price >= 0),
  discount_pct        numeric(6,3) not null default 0 check (discount_pct >= 0 and discount_pct <= 100),
  sale_price          numeric(14,2) not null check (sale_price >= 0),
  computed_total      numeric(14,2) not null,
  total               numeric(14,2) not null,
  total_is_overridden boolean not null default false,
  override_reason     text,
  unit_cost           numeric(14,2),
  cost                numeric(14,2),
  margin              numeric(14,2),
  sort_order          integer not null default 0,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_opportunity_line_opportunity_same_tenant
    foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id),
  constraint fk_opportunity_line_pbe_same_tenant
    foreign key (tenant_id, price_book_entry_id) references pipeline.price_book_entry(tenant_id, id),
  -- An override is only an override if it differs from the computation, and an
  -- un-overridden line must equal it exactly.
  constraint opportunity_line_override_consistent check (
    (total_is_overridden and override_reason is not null) or (not total_is_overridden and total = computed_total))
);

create index idx_opportunity_line_parent on sales.opportunity_line(tenant_id, opportunity_id, sort_order);

-- -----------------------------------------------------------------------------
-- FR-OPP-006 — revenue and overlay splits.
-- -----------------------------------------------------------------------------
create table sales.opportunity_split (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  opportunity_id uuid not null,
  user_id        uuid not null,
  split_type     text not null check (split_type in ('REVENUE', 'OVERLAY')),
  percentage     numeric(6,3) not null check (percentage > 0 and percentage <= 100),
  amount         numeric(14,2) not null,
  note           text,
  created_at     timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, opportunity_id, split_type, user_id),
  constraint fk_opportunity_split_opportunity_same_tenant
    foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id),
  constraint fk_opportunity_split_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

create index idx_opportunity_split_parent on sales.opportunity_split(tenant_id, opportunity_id, split_type);

-- -----------------------------------------------------------------------------
-- FR-OPP-007 — competitors present and their position.
-- -----------------------------------------------------------------------------
create table sales.opportunity_competitor (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  opportunity_id uuid not null,
  competitor_id  uuid not null,
  position       text not null check (position in ('LEADING', 'THREAT', 'TRAILING', 'ELIMINATED', 'UNKNOWN')),
  is_incumbent   boolean not null default false,
  notes          text,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, opportunity_id, competitor_id),
  constraint fk_opportunity_competitor_opportunity_same_tenant
    foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id),
  constraint fk_opportunity_competitor_catalogue_same_tenant
    foreign key (tenant_id, competitor_id) references pipeline.competitor(tenant_id, id)
);

create index idx_opportunity_competitor_parent on sales.opportunity_competitor(tenant_id, opportunity_id);

-- -----------------------------------------------------------------------------
-- FR-OPP-010 — every close-date change is recorded.
-- -----------------------------------------------------------------------------
create table sales.opportunity_close_date_change (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null references platform.tenant(id),
  opportunity_id       uuid not null,
  old_close_date       date,
  new_close_date       date not null,
  days_moved           integer not null,
  moved_beyond_period  boolean not null,
  reason               text,
  changed_by           uuid,
  changed_by_name      text,
  changed_at           timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_close_date_change_opportunity_same_tenant
    foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id),
  -- A change that pushes the date out of the current period must say why.
  constraint close_date_change_beyond_period_has_reason check (
    not moved_beyond_period or (reason is not null and btrim(reason) <> ''))
);

create index idx_close_date_change_parent on sales.opportunity_close_date_change(tenant_id, opportunity_id, changed_at desc);

-- -----------------------------------------------------------------------------
-- FR-OPP-012 / FR-OPP-013 — closures are append-only. A reopen stamps the
-- closure row it reopened but never rewrites it, so reporting on the ORIGINAL
-- closure stays intact.
-- -----------------------------------------------------------------------------
create table sales.opportunity_closure (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  opportunity_id    uuid not null,
  sequence_no       integer not null check (sequence_no > 0),
  outcome           text not null check (outcome in ('WON', 'LOST')),
  close_reason_id   uuid not null,
  won_competitor_id uuid,
  amount_at_close   numeric(14,2) not null,
  close_date_at_close date,
  stage_id          uuid not null,
  notes             text,
  closed_at         timestamptz not null default now(),
  closed_by         uuid,
  closed_by_name    text,
  reopened_at       timestamptz,
  reopened_by       uuid,
  reopened_by_name  text,
  reopen_reason     text,
  unique (tenant_id, id),
  unique (tenant_id, opportunity_id, sequence_no),
  constraint fk_closure_opportunity_same_tenant
    foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id),
  constraint fk_closure_reason_same_tenant
    foreign key (tenant_id, close_reason_id) references pipeline.close_reason(tenant_id, id),
  constraint fk_closure_competitor_same_tenant
    foreign key (tenant_id, won_competitor_id) references pipeline.competitor(tenant_id, id),
  constraint closure_reopen_has_reason check (
    (reopened_at is null and reopen_reason is null)
    or (reopened_at is not null and reopen_reason is not null and btrim(reopen_reason) <> ''))
);

create index idx_closure_parent on sales.opportunity_closure(tenant_id, opportunity_id, sequence_no);

-- -----------------------------------------------------------------------------
-- FR-OPP-008 — qualification answers.
-- -----------------------------------------------------------------------------
create table sales.opportunity_qualification (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  opportunity_id  uuid not null,
  item_id         uuid not null,
  answered        boolean not null default false,
  value           text,
  updated_at      timestamptz not null default now(),
  updated_by      uuid,
  unique (tenant_id, id),
  unique (tenant_id, opportunity_id, item_id),
  constraint fk_opportunity_qualification_opportunity_same_tenant
    foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id),
  constraint fk_opportunity_qualification_item_same_tenant
    foreign key (tenant_id, item_id) references pipeline.qualification_item(tenant_id, id),
  constraint opportunity_qualification_answered_has_value check (
    not answered or (value is not null and btrim(value) <> ''))
);

-- -----------------------------------------------------------------------------
-- Minimal approval state so the APPROVAL criterion type is genuinely evaluable.
-- E14 (workflow automation and approvals) owns the real engine and will
-- supersede this table.
-- -----------------------------------------------------------------------------
create table sales.opportunity_approval (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  opportunity_id uuid not null,
  approval_type  text not null,
  state          text not null check (state in ('REQUESTED', 'APPROVED', 'REJECTED')),
  requested_by   uuid,
  decided_by     uuid,
  decided_at     timestamptz,
  notes          text,
  created_at     timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, opportunity_id, approval_type),
  constraint fk_opportunity_approval_opportunity_same_tenant
    foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id)
);

-- -----------------------------------------------------------------------------
-- FR-OPP-015 — append-only state history. "The pipeline as of T" is the latest
-- row at or before T for each opportunity; the comparison is then a join of two
-- such projections, which is why added/advanced/slipped/grown/shrunk/won/lost
-- reconcile exactly rather than approximately.
-- -----------------------------------------------------------------------------
create table sales.opportunity_state_history (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  opportunity_id uuid not null,
  observed_at    timestamptz not null default now(),
  change_kind    text not null,
  stage_id       uuid not null,
  stage_rank     integer not null,
  amount         numeric(14,2) not null,
  close_date     date,
  is_closed      boolean not null,
  is_won         boolean,
  unique (tenant_id, id),
  constraint fk_state_history_opportunity_same_tenant
    foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id)
);

create index idx_state_history_asof
  on sales.opportunity_state_history(tenant_id, opportunity_id, observed_at desc);

-- -----------------------------------------------------------------------------
-- Row-level security — the second, independent enforcement layer (ADR-001).
-- `SET LOCAL app.tenant_id` reverts to the EMPTY STRING rather than NULL when
-- the transaction ends, and ''::uuid throws. nullif() is what makes an unset
-- context see zero rows instead of erroring.
-- -----------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array[
    'pipeline.pipeline', 'pipeline.stage_criteria_version', 'pipeline.stage_exit_criterion',
    'pipeline.close_reason', 'pipeline.competitor', 'pipeline.qualification_framework',
    'pipeline.qualification_item', 'pipeline.price_book', 'pipeline.price_book_entry',
    'sales.stage_history', 'sales.opportunity_line', 'sales.opportunity_split',
    'sales.opportunity_competitor', 'sales.opportunity_close_date_change',
    'sales.opportunity_closure', 'sales.opportunity_qualification',
    'sales.opportunity_approval', 'sales.opportunity_state_history'
  ]
  loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format(
      'create policy tenant_isolation on %s '
      || 'using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      || 'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
    execute format('grant select, insert, update, delete on %s to axiom_app', t);
  end loop;
end $$;

-- -----------------------------------------------------------------------------
-- Seed the v1 gate criteria. requires_economic_buyer becomes an ENTRY criterion
-- so the pre-E06 gate is generalised rather than regressed; every stage also
-- gets an EXIT v1 so stage entry always has a version to pin against.
-- -----------------------------------------------------------------------------
insert into pipeline.stage_criteria_version (tenant_id, stage_id, gate, version_number, notes)
select s.tenant_id, s.id, 'EXIT', 1, 'Initial exit criteria published with E06.'
from crm.pipeline_stage s
on conflict (tenant_id, stage_id, gate, version_number) do nothing;

insert into pipeline.stage_criteria_version (tenant_id, stage_id, gate, version_number, notes)
select s.tenant_id, s.id, 'ENTRY', 1, 'Initial entry criteria published with E06.'
from crm.pipeline_stage s
where s.requires_economic_buyer
on conflict (tenant_id, stage_id, gate, version_number) do nothing;

insert into pipeline.stage_exit_criterion
  (tenant_id, criteria_version_id, code, label, criterion_type, expression, message, remediation, sort_order)
select v.tenant_id, v.id, 'ECONOMIC_BUYER_IDENTIFIED', 'Economic buyer identified',
       'RELATED_RECORD',
       '{"relation":"CONTACT_ROLE","role":"ECONOMIC_BUYER","minCount":1}'::jsonb,
       'No contact on this opportunity holds the Economic Buyer role.',
       'Add the contact who can release budget with the ECONOMIC_BUYER role on the opportunity''s contact roles.',
       10
from pipeline.stage_criteria_version v
join crm.pipeline_stage s on s.tenant_id = v.tenant_id and s.id = v.stage_id
where v.gate = 'ENTRY' and v.version_number = 1 and s.requires_economic_buyer
on conflict (tenant_id, criteria_version_id, code) do nothing;

insert into pipeline.stage_exit_criterion
  (tenant_id, criteria_version_id, code, label, criterion_type, expression, message, remediation, sort_order)
select v.tenant_id, v.id, 'AMOUNT_SET', 'Deal value quantified',
       'FIELD',
       '{"field":"amount","operator":"GT","value":0}'::jsonb,
       'The opportunity amount is still zero.',
       'Enter the expected deal value, or add line items so the amount is computed from them.',
       10
from pipeline.stage_criteria_version v
join crm.pipeline_stage s on s.tenant_id = v.tenant_id and s.id = v.stage_id
where v.gate = 'EXIT' and v.version_number = 1 and not s.is_closed
on conflict (tenant_id, criteria_version_id, code) do nothing;

insert into pipeline.stage_exit_criterion
  (tenant_id, criteria_version_id, code, label, criterion_type, expression, message, remediation, sort_order)
select v.tenant_id, v.id, 'CLOSE_DATE_SET', 'Expected close date recorded',
       'FIELD',
       '{"field":"closeDate","operator":"NOT_NULL"}'::jsonb,
       'The opportunity has no expected close date.',
       'Set an expected close date on the opportunity so it can be forecast.',
       20
from pipeline.stage_criteria_version v
join crm.pipeline_stage s on s.tenant_id = v.tenant_id and s.id = v.stage_id
where v.gate = 'EXIT' and v.version_number = 1 and not s.is_closed and s.sort_order > (
  select min(s2.sort_order) from crm.pipeline_stage s2
  where s2.tenant_id = s.tenant_id and s2.pipeline_id = s.pipeline_id
)
on conflict (tenant_id, criteria_version_id, code) do nothing;

-- Every opportunity must have a pinned criteria version and an "as of"
-- baseline from the moment it exists — including ones created by lead
-- conversion or import, which know nothing about E06.
create or replace function sales.opportunity_open_history() returns trigger
language plpgsql as $$
declare
  rank_at_insert integer;
begin
  select count(*) + 1
    into rank_at_insert
  from crm.pipeline_stage s2
  where s2.tenant_id = new.tenant_id
    and s2.pipeline_id = new.pipeline_id
    and s2.sort_order < (select s4.sort_order from crm.pipeline_stage s4
                         where s4.tenant_id = new.tenant_id and s4.id = new.stage_id);

  insert into sales.stage_history
    (tenant_id, opportunity_id, from_stage_id, to_stage_id, transition_kind,
     entered_at, changed_by, reason, criteria_version_id)
  values (new.tenant_id, new.id, null, new.stage_id, 'INITIAL',
          new.stage_entered_at, new.owner_id, 'Opportunity created.',
          (select v.id from pipeline.stage_criteria_version v
            where v.tenant_id = new.tenant_id and v.stage_id = new.stage_id and v.gate = 'EXIT'
            order by v.version_number desc limit 1));

  insert into sales.opportunity_state_history
    (tenant_id, opportunity_id, observed_at, change_kind, stage_id, stage_rank,
     amount, close_date, is_closed, is_won)
  values (new.tenant_id, new.id, new.created_at, 'CREATED', new.stage_id,
          coalesce(rank_at_insert, 1), new.amount, new.close_date, new.is_closed, new.is_won);

  return null;
end $$;

create trigger opportunity_open_history
  after insert on sales.opportunity
  for each row execute function sales.opportunity_open_history();

-- -----------------------------------------------------------------------------
-- Backfill history for opportunities that already exist. Without this an
-- in-flight opportunity has no pinned criteria version and no "as of" baseline.
-- -----------------------------------------------------------------------------
insert into sales.stage_history
  (tenant_id, opportunity_id, from_stage_id, to_stage_id, transition_kind,
   entered_at, changed_by, changed_by_name, reason, criteria_version_id)
select o.tenant_id, o.id, null, o.stage_id, 'INITIAL', o.created_at, o.owner_id, u.display_name,
       'Backfilled at E06 migration from the pre-E06 record state.',
       (select v.id from pipeline.stage_criteria_version v
        where v.tenant_id = o.tenant_id and v.stage_id = o.stage_id and v.gate = 'EXIT'
        order by v.version_number desc limit 1)
from sales.opportunity o
left join identity.app_user u on u.tenant_id = o.tenant_id and u.id = o.owner_id
where not exists (select 1 from sales.stage_history h
                  where h.tenant_id = o.tenant_id and h.opportunity_id = o.id);

insert into sales.opportunity_state_history
  (tenant_id, opportunity_id, observed_at, change_kind, stage_id, stage_rank,
   amount, close_date, is_closed, is_won)
select o.tenant_id, o.id, o.created_at, 'GENESIS', o.stage_id, r.rank,
       o.amount, o.close_date, o.is_closed, o.is_won
from sales.opportunity o
join (
  select st.tenant_id, st.id,
         row_number() over (partition by st.tenant_id, st.pipeline_id order by st.sort_order)::int as rank
  from crm.pipeline_stage st
) r on r.tenant_id = o.tenant_id and r.id = o.stage_id
where not exists (select 1 from sales.opportunity_state_history h
                  where h.tenant_id = o.tenant_id and h.opportunity_id = o.id);

-- -----------------------------------------------------------------------------
-- Reporting projection (Definition of Done point 8). security_invoker keeps the
-- caller's RLS in force through the view rather than the view owner's.
-- -----------------------------------------------------------------------------
create or replace view reporting.v_opportunity_pipeline
with (security_invoker = true) as
select o.tenant_id,
       o.id                       as opportunity_id,
       o.name,
       o.account_id,
       a.name                     as account_name,
       o.pipeline_id,
       p.name                     as pipeline_name,
       o.stage_id,
       s.name                     as stage_name,
       s.sort_order               as stage_sort_order,
       s.forecast_category,
       o.probability,
       o.owner_id,
       u.display_name             as owner_name,
       o.amount,
       o.currency_code,
       o.recurring_amount,
       o.one_time_amount,
       o.term_months,
       o.billing_frequency,
       o.arr,
       o.tcv,
       o.close_date,
       o.original_close_date,
       o.slip_count,
       o.cumulative_slip_days,
       o.qualification_score,
       o.stage_entered_at,
       greatest(0, extract(day from (now() - o.stage_entered_at))::int) as days_in_stage,
       o.is_closed,
       o.is_won,
       o.closed_at,
       o.reopen_count,
       cr.code                    as close_reason_code,
       cr.label                   as close_reason_label,
       wc.name                    as won_competitor_name,
       (select count(*) from sales.opportunity_line l
         where l.tenant_id = o.tenant_id and l.opportunity_id = o.id)      as line_count,
       (select count(*) from sales.opportunity_line l
         where l.tenant_id = o.tenant_id and l.opportunity_id = o.id
           and l.total_is_overridden)                                      as overridden_line_count,
       (select count(*) from sales.opportunity_competitor oc
         where oc.tenant_id = o.tenant_id and oc.opportunity_id = o.id)    as competitor_count,
       exists (select 1 from sales.opportunity_contact_role ocr
               where ocr.tenant_id = o.tenant_id and ocr.opportunity_id = o.id
                 and ocr.role = 'ECONOMIC_BUYER')                          as has_economic_buyer
from sales.opportunity o
join crm.pipeline_stage s on s.tenant_id = o.tenant_id and s.id = o.stage_id
join pipeline.pipeline p on p.tenant_id = o.tenant_id and p.id = o.pipeline_id
join crm.account a on a.tenant_id = o.tenant_id and a.id = o.account_id
left join identity.app_user u on u.tenant_id = o.tenant_id and u.id = o.owner_id
left join pipeline.close_reason cr on cr.tenant_id = o.tenant_id and cr.id = o.close_reason_id
left join pipeline.competitor wc on wc.tenant_id = o.tenant_id and wc.id = o.won_competitor_id;

grant select on reporting.v_opportunity_pipeline to axiom_app;

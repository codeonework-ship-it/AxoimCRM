-- E03 — Organization, reference and master data (FR-MDM-001..010).
--
-- Everything tenant-scoped lives behind row-level security. The policy predicate
-- is deliberately `nullif(current_setting('app.tenant_id', true), '')::uuid`:
-- a `SET LOCAL` GUC does not revert to NULL at transaction end, it reverts to
-- the EMPTY STRING, and `''::uuid` raises
--   invalid input syntax for type uuid: ""
-- which turns an unbound connection into a hard error instead of a zero-row
-- result. nullif() collapses both the unset and the reverted-empty cases to
-- NULL, so the comparison is simply false and the table reads as empty.

create schema if not exists orgdata;
grant usage on schema orgdata to axiom_app;

-- btree_gist is what lets an exclusion constraint mix an equality column with
-- a range overlap test — the database-level backstop for FR-MDM-002's
-- "no two rates for the same pair may cover the same day".
create extension if not exists btree_gist;

-- ---------------------------------------------------------------------------
-- FR-MDM-001 — legal entities and business units
-- ---------------------------------------------------------------------------

create table orgdata.business_unit (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  code            text not null,
  name            text not null,
  is_legal_entity boolean not null default false,
  parent_id       uuid,
  -- Materialized ancestor path (M-rules / §8 "hierarchy traversal"): reporting
  -- roll-up is a prefix scan, not a recursive query at read time.
  path            text not null,
  currency_code   text,
  active          boolean not null default true,
  created_at      timestamptz not null default now(),
  created_by      uuid,
  updated_at      timestamptz not null default now(),
  updated_by      uuid,
  version         bigint not null default 0,
  deleted_at      timestamptz,
  deleted_by      uuid,
  unique (tenant_id, id),
  unique (tenant_id, code),
  constraint business_unit_code_format check (code ~ '^[A-Z][A-Z0-9_]*$'),
  constraint business_unit_not_own_parent check (parent_id is null or parent_id <> id),
  constraint fk_business_unit_parent_same_tenant
    foreign key (tenant_id, parent_id) references orgdata.business_unit(tenant_id, id)
);

create index idx_business_unit_path on orgdata.business_unit(tenant_id, path) where deleted_at is null;

-- User association (FR-MDM-001 "associate users ... with them").
create table orgdata.business_unit_member (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  business_unit_id uuid not null,
  user_id          uuid not null,
  is_primary       boolean not null default true,
  created_at       timestamptz not null default now(),
  created_by       uuid,
  unique (tenant_id, business_unit_id, user_id),
  constraint fk_bu_member_unit_same_tenant
    foreign key (tenant_id, business_unit_id) references orgdata.business_unit(tenant_id, id),
  constraint fk_bu_member_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

-- Record association. Held as a side table keyed by (entity_type, entity_id) so
-- E03 can associate records with a business unit without reaching into the
-- account/opportunity tables owned by other modules.
create table orgdata.business_unit_record (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  business_unit_id uuid not null,
  entity_type      text not null check (entity_type in ('ACCOUNT','CONTACT','LEAD','OPPORTUNITY')),
  entity_id        uuid not null,
  created_at       timestamptz not null default now(),
  created_by       uuid,
  unique (tenant_id, entity_type, entity_id),
  constraint fk_bu_record_unit_same_tenant
    foreign key (tenant_id, business_unit_id) references orgdata.business_unit(tenant_id, id)
);

create index idx_bu_record_unit on orgdata.business_unit_record(tenant_id, business_unit_id, entity_type);

-- ---------------------------------------------------------------------------
-- FR-MDM-002 / FR-MDM-003 — multi-currency and dated exchange rates
-- ---------------------------------------------------------------------------

create table orgdata.currency (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  code           text not null,
  name           text not null,
  symbol         text,
  decimal_places smallint not null default 2 check (decimal_places between 0 and 4),
  is_corporate   boolean not null default false,
  active         boolean not null default true,
  created_at     timestamptz not null default now(),
  created_by     uuid,
  updated_at     timestamptz not null default now(),
  updated_by     uuid,
  unique (tenant_id, id),
  unique (tenant_id, code),
  constraint currency_code_format check (code ~ '^[A-Z]{3}$'),
  -- The corporate currency is the one thing that may never be switched off.
  constraint currency_corporate_is_active check (is_corporate = false or active = true)
);

-- Exactly one corporate currency per tenant (FR-MDM-002).
create unique index uq_currency_one_corporate on orgdata.currency(tenant_id) where is_corporate;

create table orgdata.exchange_rate (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  from_currency  text not null,
  to_currency    text not null,
  rate           numeric(20,10) not null check (rate > 0),
  effective_from date not null,
  effective_to   date,
  source         text not null default 'MANUAL' check (source in ('MANUAL','FEED','IMPORT')),
  created_at     timestamptz not null default now(),
  created_by     uuid,
  unique (tenant_id, id),
  constraint exchange_rate_currency_format
    check (from_currency ~ '^[A-Z]{3}$' and to_currency ~ '^[A-Z]{3}$'),
  constraint exchange_rate_distinct_pair check (from_currency <> to_currency),
  constraint exchange_rate_range_ordered
    check (effective_to is null or effective_to >= effective_from),
  -- FR-MDM-002: a currency pair may not have two rates in force on the same
  -- day. Without this, "the rate on the close date" has no single answer.
  constraint exchange_rate_no_overlap exclude using gist (
    tenant_id with =,
    from_currency with =,
    to_currency with =,
    daterange(effective_from, effective_to, '[]') with &&
  )
);

create index idx_exchange_rate_lookup
  on orgdata.exchange_rate(tenant_id, from_currency, to_currency, effective_from desc);

-- M6: a monetary value stores the transaction currency and amount, the
-- corporate amount, and the rate and rate date that produced it. This is the
-- ledger of stored conversions — a row is written once and is NEVER silently
-- recomputed when rates later change. Re-converting is an explicit, audited act
-- that writes a new row and supersedes the old one.
create table orgdata.money_conversion (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null references platform.tenant(id),
  entity_type          text not null,
  entity_id            uuid not null,
  entity_field         text not null default 'amount',
  transaction_currency text not null,
  transaction_amount   numeric(20,4) not null,
  corporate_currency   text not null,
  corporate_amount     numeric(20,4) not null,
  applied_rate         numeric(20,10) not null,
  rate_date            date not null,
  rate_id              uuid,
  rate_basis           text not null check (rate_basis in ('TODAY','RECORD_DATE')),
  is_current           boolean not null default true,
  superseded_by        uuid,
  converted_at         timestamptz not null default now(),
  converted_by         uuid,
  unique (tenant_id, id),
  constraint money_conversion_currency_format
    check (transaction_currency ~ '^[A-Z]{3}$' and corporate_currency ~ '^[A-Z]{3}$')
);

create unique index uq_money_conversion_current
  on orgdata.money_conversion(tenant_id, entity_type, entity_id, entity_field)
  where is_current;

create index idx_money_conversion_entity
  on orgdata.money_conversion(tenant_id, entity_type, entity_id, converted_at desc);

-- FR-MDM-003: "configurable per object". An opportunity converts at its close
-- date, an order at its order date, and a payment at today's rate. The policy
-- is data, not a code branch.
create table orgdata.currency_conversion_policy (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  object_name       text not null,
  rate_basis        text not null check (rate_basis in ('TODAY','RECORD_DATE')),
  record_date_field text,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  updated_by        uuid,
  unique (tenant_id, object_name),
  constraint conversion_policy_needs_field
    check (rate_basis <> 'RECORD_DATE' or record_date_field is not null)
);

-- ---------------------------------------------------------------------------
-- FR-MDM-004 — fiscal calendar
-- ---------------------------------------------------------------------------

create table orgdata.fiscal_calendar (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  code          text not null,
  name          text not null,
  calendar_type text not null check (calendar_type in ('STANDARD','CUSTOM','FOUR_FOUR_FIVE')),
  start_month   smallint not null check (start_month between 1 and 12),
  start_day     smallint not null default 1 check (start_day between 1 and 28),
  is_default    boolean not null default false,
  active        boolean not null default true,
  created_at    timestamptz not null default now(),
  created_by    uuid,
  updated_at    timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, code),
  constraint fiscal_calendar_code_format check (code ~ '^[A-Z][A-Z0-9_]*$')
);

create unique index uq_fiscal_calendar_default on orgdata.fiscal_calendar(tenant_id) where is_default;

create table orgdata.fiscal_year (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  calendar_id uuid not null,
  year_label  text not null,
  start_date  date not null,
  end_date    date not null,
  created_at  timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, calendar_id, year_label),
  constraint fiscal_year_range check (end_date > start_date),
  constraint fk_fiscal_year_calendar_same_tenant
    foreign key (tenant_id, calendar_id) references orgdata.fiscal_calendar(tenant_id, id)
);

create table orgdata.fiscal_period (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  fiscal_year_id uuid not null,
  period_type    text not null check (period_type in ('QUARTER','PERIOD')),
  quarter_number smallint check (quarter_number between 1 and 4),
  period_number  smallint,
  label          text not null,
  start_date     date not null,
  end_date       date not null,
  created_at     timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, fiscal_year_id, period_type, label),
  constraint fiscal_period_range check (end_date >= start_date),
  constraint fk_fiscal_period_year_same_tenant
    foreign key (tenant_id, fiscal_year_id) references orgdata.fiscal_year(tenant_id, id)
);

create index idx_fiscal_period_resolve
  on orgdata.fiscal_period(tenant_id, period_type, start_date, end_date);

-- ---------------------------------------------------------------------------
-- FR-MDM-005 — business hours, holidays and time zones
-- ---------------------------------------------------------------------------

create table orgdata.business_hours (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null references platform.tenant(id),
  code       text not null,
  name       text not null,
  time_zone  text not null,
  is_default boolean not null default false,
  active     boolean not null default true,
  created_at timestamptz not null default now(),
  created_by uuid,
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, code),
  constraint business_hours_code_format check (code ~ '^[A-Z][A-Z0-9_]*$')
);

create unique index uq_business_hours_default on orgdata.business_hours(tenant_id) where is_default;

create table orgdata.business_hours_day (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  business_hours_id uuid not null,
  -- ISO-8601 day numbering: 1 = Monday .. 7 = Sunday.
  day_of_week       smallint not null check (day_of_week between 1 and 7),
  open_time         time not null,
  close_time        time not null,
  unique (tenant_id, business_hours_id, day_of_week),
  constraint business_hours_day_ordered check (close_time > open_time),
  constraint fk_bh_day_parent_same_tenant
    foreign key (tenant_id, business_hours_id) references orgdata.business_hours(tenant_id, id)
);

create table orgdata.holiday (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  business_hours_id uuid,
  holiday_date      date not null,
  name              text not null,
  recurring_annually boolean not null default false,
  created_at        timestamptz not null default now(),
  created_by        uuid,
  constraint fk_holiday_parent_same_tenant
    foreign key (tenant_id, business_hours_id) references orgdata.business_hours(tenant_id, id)
);

create unique index uq_holiday_calendar_date
  on orgdata.holiday(tenant_id, (coalesce(business_hours_id, '00000000-0000-0000-0000-000000000000'::uuid)), holiday_date);

-- ---------------------------------------------------------------------------
-- FR-MDM-006 — dependent (cascading) picklists over the existing global value
-- sets in reference.value_set / reference.value_set_entry
-- ---------------------------------------------------------------------------

create table reference.value_set_dependency (
  id                      uuid primary key default gen_random_uuid(),
  tenant_id               uuid not null references platform.tenant(id),
  controlling_value_set_id uuid not null,
  dependent_value_set_id  uuid not null,
  created_at              timestamptz not null default now(),
  created_by              uuid,
  unique (tenant_id, id),
  -- One controller per dependent set: two controllers would make "which values
  -- are valid now" ambiguous.
  unique (tenant_id, dependent_value_set_id),
  constraint value_set_dependency_distinct
    check (controlling_value_set_id <> dependent_value_set_id),
  constraint fk_vsd_controlling_same_tenant
    foreign key (tenant_id, controlling_value_set_id) references reference.value_set(tenant_id, id),
  constraint fk_vsd_dependent_same_tenant
    foreign key (tenant_id, dependent_value_set_id) references reference.value_set(tenant_id, id)
);

create table reference.dependent_value_map (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  dependency_id    uuid not null,
  controlling_code text not null,
  dependent_code   text not null,
  active           boolean not null default true,
  created_at       timestamptz not null default now(),
  created_by       uuid,
  unique (tenant_id, dependency_id, controlling_code, dependent_code),
  constraint fk_dvm_dependency_same_tenant
    foreign key (tenant_id, dependency_id) references reference.value_set_dependency(tenant_id, id)
);

create index idx_dependent_value_map_lookup
  on reference.dependent_value_map(tenant_id, dependency_id, controlling_code) where active;

-- ---------------------------------------------------------------------------
-- FR-MDM-008 — territory model, versioned, with preview before activation
-- ---------------------------------------------------------------------------

create table orgdata.territory_model_version (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  version_no  integer not null,
  name        text not null,
  status      text not null default 'DRAFT' check (status in ('DRAFT','ACTIVE','ARCHIVED')),
  notes       text,
  created_at  timestamptz not null default now(),
  created_by  uuid,
  activated_at timestamptz,
  activated_by uuid,
  archived_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, version_no)
);

-- At most one ACTIVE model per tenant. Activation flips this in one
-- transaction, which is what makes FR-MDM-008's atomicity a constraint rather
-- than a hope.
create unique index uq_territory_model_one_active
  on orgdata.territory_model_version(tenant_id) where status = 'ACTIVE';

create table orgdata.territory (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  model_version_id  uuid not null,
  code              text not null,
  name              text not null,
  parent_id         uuid,
  path              text not null,
  active            boolean not null default true,
  created_at        timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, model_version_id, code),
  constraint territory_code_format check (code ~ '^[A-Z][A-Z0-9_]*$'),
  constraint territory_not_own_parent check (parent_id is null or parent_id <> id),
  constraint fk_territory_model_same_tenant
    foreign key (tenant_id, model_version_id) references orgdata.territory_model_version(tenant_id, id),
  constraint fk_territory_parent_same_tenant
    foreign key (tenant_id, parent_id) references orgdata.territory(tenant_id, id)
);

create index idx_territory_model on orgdata.territory(tenant_id, model_version_id, path);

create table orgdata.territory_assignment_rule (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  territory_id uuid not null,
  match_field  text not null check (match_field in ('INDUSTRY','ACCOUNT_NAME','OWNER_NAME')),
  operator     text not null check (operator in ('EQUALS','STARTS_WITH','CONTAINS')),
  match_value  text not null,
  priority     integer not null default 100,
  active       boolean not null default true,
  created_at   timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_tar_territory_same_tenant
    foreign key (tenant_id, territory_id) references orgdata.territory(tenant_id, id)
);

create index idx_territory_rule_territory on orgdata.territory_assignment_rule(tenant_id, territory_id) where active;

-- Materialized assignment, keyed by model version. Rows for the previous
-- version are retained on activation, which is what makes restore a status
-- flip rather than a recomputation against data that has since moved.
create table orgdata.territory_assignment (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  model_version_id uuid not null,
  territory_id     uuid not null,
  account_id       uuid not null,
  matched_rule_id  uuid,
  assigned_at      timestamptz not null default now(),
  unique (tenant_id, model_version_id, account_id),
  constraint fk_ta_model_same_tenant
    foreign key (tenant_id, model_version_id) references orgdata.territory_model_version(tenant_id, id),
  constraint fk_ta_territory_same_tenant
    foreign key (tenant_id, territory_id) references orgdata.territory(tenant_id, id),
  constraint fk_ta_account_same_tenant
    foreign key (tenant_id, account_id) references crm.account(tenant_id, id)
);

create index idx_territory_assignment_territory
  on orgdata.territory_assignment(tenant_id, model_version_id, territory_id);

create table orgdata.territory_member (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  territory_id uuid not null,
  user_id      uuid not null,
  territory_role text not null default 'MEMBER' check (territory_role in ('MEMBER','MANAGER')),
  created_at   timestamptz not null default now(),
  unique (tenant_id, territory_id, user_id),
  constraint fk_tm_territory_same_tenant
    foreign key (tenant_id, territory_id) references orgdata.territory(tenant_id, id),
  constraint fk_tm_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- FR-MDM-009 — quotas by user, team, territory and fiscal period
-- ---------------------------------------------------------------------------

create table orgdata.quota (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  subject_type     text not null check (subject_type in ('USER','TEAM','TERRITORY')),
  subject_id       uuid not null,
  subject_label    text not null,
  fiscal_period_id uuid not null,
  measure          text not null check (measure in ('REVENUE','QUANTITY')),
  target_amount    numeric(20,4) not null check (target_amount >= 0),
  currency_code    text,
  unit_of_measure  text,
  version_no       integer not null default 1,
  is_current       boolean not null default true,
  supersedes_id    uuid,
  change_reason    text,
  created_at       timestamptz not null default now(),
  created_by       uuid,
  unique (tenant_id, id),
  -- M6/M10: a revenue quota needs a currency, a quantity quota needs a unit.
  constraint quota_revenue_has_currency
    check (measure <> 'REVENUE' or currency_code is not null),
  constraint quota_quantity_has_uom
    check (measure <> 'QUANTITY' or unit_of_measure is not null),
  constraint fk_quota_period_same_tenant
    foreign key (tenant_id, fiscal_period_id) references orgdata.fiscal_period(tenant_id, id)
);

-- One live quota per subject/period/measure; prior versions stay queryable so
-- attainment reporting can name the version it used (US-E03-07).
create unique index uq_quota_current
  on orgdata.quota(tenant_id, subject_type, subject_id, fiscal_period_id, measure)
  where is_current;

create index idx_quota_history
  on orgdata.quota(tenant_id, subject_type, subject_id, fiscal_period_id, measure, version_no desc);

-- ---------------------------------------------------------------------------
-- FR-MDM-010 — master-data change control
-- ---------------------------------------------------------------------------

create table orgdata.governed_master (
  tenant_id         uuid not null references platform.tenant(id),
  master_type       text not null,
  requires_approval boolean not null default false,
  description       text not null,
  updated_at        timestamptz not null default now(),
  updated_by        uuid,
  constraint pk_governed_master primary key (tenant_id, master_type)
);

create table orgdata.master_change_request (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  master_type     text not null,
  operation       text not null check (operation in ('CREATE','UPDATE','DEACTIVATE')),
  target_id       uuid,
  summary         text not null,
  payload         jsonb not null,
  status          text not null default 'PENDING'
                  check (status in ('PENDING','APPROVED','REJECTED','APPLIED','FAILED')),
  requested_by    uuid not null,
  requested_at    timestamptz not null default now(),
  decided_by      uuid,
  decided_at      timestamptz,
  decision_reason text,
  applied_at      timestamptz,
  applied_entity_id uuid,
  failure_reason  text,
  unique (tenant_id, id)
);

create index idx_master_change_request_pending
  on orgdata.master_change_request(tenant_id, requested_at desc) where status = 'PENDING';

-- ---------------------------------------------------------------------------
-- Row-level security. Every table above is tenant-scoped.
-- ---------------------------------------------------------------------------

do $$
declare
  qualified text;
begin
  foreach qualified in array array[
    'orgdata.business_unit', 'orgdata.business_unit_member', 'orgdata.business_unit_record',
    'orgdata.currency', 'orgdata.exchange_rate', 'orgdata.money_conversion',
    'orgdata.currency_conversion_policy',
    'orgdata.fiscal_calendar', 'orgdata.fiscal_year', 'orgdata.fiscal_period',
    'orgdata.business_hours', 'orgdata.business_hours_day', 'orgdata.holiday',
    'reference.value_set_dependency', 'reference.dependent_value_map',
    'orgdata.territory_model_version', 'orgdata.territory',
    'orgdata.territory_assignment_rule', 'orgdata.territory_assignment',
    'orgdata.territory_member',
    'orgdata.quota', 'orgdata.governed_master', 'orgdata.master_change_request'
  ]
  loop
    execute format('alter table %s enable row level security', qualified);
    execute format('alter table %s force row level security', qualified);
    execute format(
      'create policy tenant_isolation on %s '
      || 'using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      || 'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
      qualified);
    execute format('grant select, insert, update, delete on %s to axiom_app', qualified);
  end loop;
end $$;

-- ---------------------------------------------------------------------------
-- Runtime schema visibility.
--
-- Read the role's CURRENT search_path and append `orgdata` rather than
-- rewriting a hardcoded list: other modules add schemas in their own
-- migrations, and a literal `alter role ... set search_path to <fixed list>`
-- here would silently un-map whichever schema landed between V10 and V30.
-- ---------------------------------------------------------------------------

do $$
declare
  existing text;
begin
  select substring(cfg from 'search_path=(.*)')
    into existing
    from pg_roles r, unnest(coalesce(r.rolconfig, array[]::text[])) cfg
   where r.rolname = 'axiom_app' and cfg like 'search_path=%'
   limit 1;

  if existing is null or btrim(existing) = '' then
    existing := 'platform, identity, crm, sales, engagement, governance, reference, '
             || 'billing, reporting, integration, i18n, public';
  end if;

  if existing !~ '(^|[,[:space:]])orgdata([,[:space:]]|$)' then
    existing := 'orgdata, ' || existing;
  end if;

  execute format('alter role axiom_app set search_path to %s', existing);
end $$;

-- ---------------------------------------------------------------------------
-- Module and screen registration (additive; other epics own their own rows).
-- ---------------------------------------------------------------------------

insert into governance.module_catalog(module_code, schema_name, display_name, description, owner_role) values
  ('ORGDATA', 'orgdata', 'Organization data',
   'Business units, currencies, fiscal calendars, business hours, territories and quotas.',
   'OPERATIONS')
on conflict (module_code) do nothing;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('orgdata','business_unit','ORGDATA','id',true,'SOFT_DELETE'),
  ('orgdata','business_unit_member','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','business_unit_record','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','currency','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','exchange_rate','ORGDATA','id',true,'APPEND_ONLY'),
  ('orgdata','money_conversion','ORGDATA','id',true,'APPEND_ONLY'),
  ('orgdata','currency_conversion_policy','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','fiscal_calendar','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','fiscal_year','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','fiscal_period','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','business_hours','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','business_hours_day','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','holiday','ORGDATA','id',true,'ACTIVE'),
  ('reference','value_set_dependency','REFERENCE','id',true,'ACTIVE'),
  ('reference','dependent_value_map','REFERENCE','id',true,'ACTIVE'),
  ('orgdata','territory_model_version','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','territory','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','territory_assignment_rule','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','territory_assignment','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','territory_member','ORGDATA','id',true,'ACTIVE'),
  ('orgdata','quota','ORGDATA','id',true,'APPEND_ONLY'),
  ('orgdata','governed_master','ORGDATA','master_type',true,'ACTIVE'),
  ('orgdata','master_change_request','ORGDATA','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

insert into governance.screen_catalog(screen_code, module_code, route, display_name, description, sort_order) values
  ('CURRENCIES', 'ORGDATA', '/org/currencies', 'Currencies & rates',
   'Corporate currency, active currencies and dated exchange rates.', 51),
  ('FISCAL_CALENDAR', 'ORGDATA', '/org/fiscal-calendar', 'Fiscal calendar',
   'Standard, custom and 4-4-5 fiscal years, quarters and periods.', 52),
  ('BUSINESS_HOURS', 'ORGDATA', '/org/business-hours', 'Business hours',
   'Named business-hours definitions, time zones and holidays.', 53),
  ('TERRITORIES', 'ORGDATA', '/org/territories', 'Territories',
   'Territory hierarchy, assignment rules, preview and versioned activation.', 54),
  ('QUOTAS', 'ORGDATA', '/org/quotas', 'Quotas',
   'Revenue and quantity quotas by user, team or territory and fiscal period.', 56)
on conflict (screen_code) do nothing;

insert into governance.rbac_policy(role_code, screen_code, can_read, can_write, can_export, can_admin, scope)
select roles.role_code, screens.screen_code,
       roles.role_code <> 'INTEGRATION',
       roles.role_code in ('SUPER_ADMIN','TENANT_ADMIN','OPERATIONS','FINANCE','DATA_STEWARD'),
       roles.role_code <> 'INTEGRATION',
       roles.role_code in ('SUPER_ADMIN','TENANT_ADMIN'),
       case when roles.role_code in ('SUPER_ADMIN','SUPER_AUDIT') then 'PLATFORM' else 'TENANT' end
from (values
  ('SUPER_ADMIN'),('SUPER_AUDIT'),('TENANT_ADMIN'),('SALES_MANAGER'),('SALES'),
  ('MARKETING'),('SERVICE'),('OPERATIONS'),('FINANCE'),('DATA_STEWARD'),('AUDITOR'),('INTEGRATION')
) roles(role_code)
cross join (values
  ('CURRENCIES'),('FISCAL_CALENDAR'),('BUSINESS_HOURS'),('TERRITORIES'),('QUOTAS')
) screens(screen_code)
on conflict (role_code, screen_code) do update
  set can_read = excluded.can_read,
      can_write = excluded.can_write,
      can_export = excluded.can_export,
      can_admin = excluded.can_admin,
      scope = excluded.scope;

-- ---------------------------------------------------------------------------
-- Seed: every tenant gets a working corporate currency, fiscal calendar,
-- business hours definition and change-control registry. An empty reference
-- layer is not a usable starting point.
-- ---------------------------------------------------------------------------

insert into orgdata.currency (tenant_id, code, name, symbol, decimal_places, is_corporate, active)
select t.id, 'INR', 'Indian rupee', '₹', 2, true, true from platform.tenant t
on conflict (tenant_id, code) do nothing;

insert into orgdata.currency (tenant_id, code, name, symbol, decimal_places, is_corporate, active)
select t.id, seed.code, seed.name, seed.symbol, 2, false, true
from platform.tenant t
cross join (values
  ('USD', 'US dollar', '$'),
  ('EUR', 'Euro', '€'),
  ('AED', 'UAE dirham', 'د.إ')
) as seed(code, name, symbol)
on conflict (tenant_id, code) do nothing;

insert into orgdata.exchange_rate (tenant_id, from_currency, to_currency, rate, effective_from, effective_to, source)
select t.id, seed.from_currency, 'INR', seed.rate, seed.effective_from, seed.effective_to, 'IMPORT'
from platform.tenant t
cross join (values
  ('USD', 82.5000000000, date '2025-01-01', date '2025-12-31'),
  ('USD', 86.2500000000, date '2026-01-01', null::date),
  ('EUR', 90.1000000000, date '2025-01-01', date '2025-12-31'),
  ('EUR', 93.4000000000, date '2026-01-01', null::date),
  ('AED', 22.4500000000, date '2025-01-01', null::date)
) as seed(from_currency, rate, effective_from, effective_to);

insert into orgdata.currency_conversion_policy (tenant_id, object_name, rate_basis, record_date_field)
select t.id, seed.object_name, seed.rate_basis, seed.record_date_field
from platform.tenant t
cross join (values
  ('OPPORTUNITY', 'RECORD_DATE', 'close_date'),
  ('QUOTE',       'RECORD_DATE', 'expires_at'),
  ('ORDER',        'RECORD_DATE', 'order_date'),
  ('PAYMENT',      'TODAY',       null::text)
) as seed(object_name, rate_basis, record_date_field)
on conflict (tenant_id, object_name) do nothing;

insert into orgdata.business_unit (tenant_id, code, name, is_legal_entity, parent_id, path, currency_code)
select t.id, 'GROUP', t.name || ' Group', true, null, '/GROUP', 'INR' from platform.tenant t
on conflict (tenant_id, code) do nothing;

insert into orgdata.fiscal_calendar (tenant_id, code, name, calendar_type, start_month, start_day, is_default)
select t.id, 'FY_APR', 'Indian fiscal year (Apr-Mar)', 'STANDARD', 4, 1, true from platform.tenant t
on conflict (tenant_id, code) do nothing;

insert into orgdata.fiscal_year (tenant_id, calendar_id, year_label, start_date, end_date)
select c.tenant_id, c.id, 'FY2027', date '2026-04-01', date '2027-03-31'
from orgdata.fiscal_calendar c where c.code = 'FY_APR'
on conflict (tenant_id, calendar_id, year_label) do nothing;

insert into orgdata.fiscal_period (tenant_id, fiscal_year_id, period_type, quarter_number, period_number, label, start_date, end_date)
select y.tenant_id, y.id, 'QUARTER', seed.q, null, seed.label,
       (date '2026-04-01' + ((seed.q - 1) * interval '3 months'))::date,
       ((date '2026-04-01' + (seed.q * interval '3 months')) - interval '1 day')::date
from orgdata.fiscal_year y
join orgdata.fiscal_calendar c on c.tenant_id = y.tenant_id and c.id = y.calendar_id and c.code = 'FY_APR'
cross join (values (1,'FY2027-Q1'),(2,'FY2027-Q2'),(3,'FY2027-Q3'),(4,'FY2027-Q4')) as seed(q, label)
where y.year_label = 'FY2027'
on conflict (tenant_id, fiscal_year_id, period_type, label) do nothing;

insert into orgdata.fiscal_period (tenant_id, fiscal_year_id, period_type, quarter_number, period_number, label, start_date, end_date)
select y.tenant_id, y.id, 'PERIOD', ((seed.p - 1) / 3) + 1, seed.p,
       'FY2027-P' || lpad(seed.p::text, 2, '0'),
       (date '2026-04-01' + ((seed.p - 1) * interval '1 month'))::date,
       ((date '2026-04-01' + (seed.p * interval '1 month')) - interval '1 day')::date
from orgdata.fiscal_year y
join orgdata.fiscal_calendar c on c.tenant_id = y.tenant_id and c.id = y.calendar_id and c.code = 'FY_APR'
cross join generate_series(1, 12) as seed(p)
where y.year_label = 'FY2027'
on conflict (tenant_id, fiscal_year_id, period_type, label) do nothing;

insert into orgdata.business_hours (tenant_id, code, name, time_zone, is_default)
select t.id, 'STANDARD', 'Standard business hours', 'Asia/Kolkata', true from platform.tenant t
on conflict (tenant_id, code) do nothing;

insert into orgdata.business_hours_day (tenant_id, business_hours_id, day_of_week, open_time, close_time)
select b.tenant_id, b.id, d, time '09:00', time '18:00'
from orgdata.business_hours b cross join generate_series(1, 5) d
where b.code = 'STANDARD'
on conflict (tenant_id, business_hours_id, day_of_week) do nothing;

insert into orgdata.holiday (tenant_id, business_hours_id, holiday_date, name, recurring_annually)
select b.tenant_id, b.id, seed.holiday_date, seed.name, seed.recurring
from orgdata.business_hours b
cross join (values
  (date '2026-08-15', 'Independence Day', true),
  (date '2026-10-02', 'Gandhi Jayanti', true),
  (date '2026-11-08', 'Diwali', false)
) as seed(holiday_date, name, recurring)
where b.code = 'STANDARD';

insert into orgdata.governed_master (tenant_id, master_type, requires_approval, description)
select t.id, seed.master_type, seed.requires_approval, seed.description
from platform.tenant t
cross join (values
  ('BUSINESS_UNIT', true,  'Legal entities and business units take effect only after approval.'),
  ('CURRENCY',      false, 'Adding or retiring a tenant currency.'),
  ('EXCHANGE_RATE', false, 'Dated exchange rates for a currency pair.'),
  ('QUOTA',         false, 'Quota targets by user, team or territory.')
) as seed(master_type, requires_approval, description)
on conflict (tenant_id, master_type) do nothing;

-- Reference value sets for the new governed vocabularies, plus a dependent
-- pair (region -> country) that demonstrates FR-MDM-006 cascading behaviour.
insert into reference.value_set (tenant_id, api_name, label, module, description)
select t.id, seed.api_name, seed.label, seed.module, seed.description
from platform.tenant t
cross join (values
  ('quota_measure', 'Quota measure', 'REFERENCE', 'Revenue or quantity basis for a quota'),
  ('sales_region', 'Sales region', 'REFERENCE', 'Controlling region for territory and country selection'),
  ('sales_country', 'Sales country', 'REFERENCE', 'Country values that cascade from the selected sales region')
) as seed(api_name, label, module, description)
on conflict (tenant_id, api_name) do nothing;

insert into reference.value_set_entry (tenant_id, value_set_id, code, label, sort_order, system_managed)
select vs.tenant_id, vs.id, seed.code, seed.label, seed.sort_order, seed.system_managed
from reference.value_set vs
join (values
  ('quota_measure', 'REVENUE', 'Revenue', 10, true),
  ('quota_measure', 'QUANTITY', 'Quantity', 20, true),
  ('sales_region', 'APAC', 'Asia Pacific', 10, false),
  ('sales_region', 'EMEA', 'Europe, Middle East & Africa', 20, false),
  ('sales_region', 'AMER', 'Americas', 30, false),
  ('sales_country', 'IN', 'India', 10, false),
  ('sales_country', 'SG', 'Singapore', 20, false),
  ('sales_country', 'AE', 'United Arab Emirates', 30, false),
  ('sales_country', 'DE', 'Germany', 40, false),
  ('sales_country', 'US', 'United States', 50, false),
  ('sales_country', 'BR', 'Brazil', 60, false)
) as seed(api_name, code, label, sort_order, system_managed) on seed.api_name = vs.api_name
on conflict (tenant_id, value_set_id, code) do nothing;

insert into reference.value_set_dependency (tenant_id, controlling_value_set_id, dependent_value_set_id)
select ctrl.tenant_id, ctrl.id, dep.id
from reference.value_set ctrl
join reference.value_set dep on dep.tenant_id = ctrl.tenant_id and dep.api_name = 'sales_country'
where ctrl.api_name = 'sales_region'
on conflict (tenant_id, dependent_value_set_id) do nothing;

insert into reference.dependent_value_map (tenant_id, dependency_id, controlling_code, dependent_code)
select d.tenant_id, d.id, seed.region, seed.country
from reference.value_set_dependency d
join reference.value_set ctrl on ctrl.tenant_id = d.tenant_id and ctrl.id = d.controlling_value_set_id
cross join (values
  ('APAC','IN'), ('APAC','SG'),
  ('EMEA','AE'), ('EMEA','DE'),
  ('AMER','US'), ('AMER','BR')
) as seed(region, country)
where ctrl.api_name = 'sales_region'
on conflict (tenant_id, dependency_id, controlling_code, dependent_code) do nothing;

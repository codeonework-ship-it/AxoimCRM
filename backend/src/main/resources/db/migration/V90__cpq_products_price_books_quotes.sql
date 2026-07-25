-- =============================================================================
-- E08 — Products, price books, quotes and CPQ (FR-CPQ-001 .. FR-CPQ-014).
--
-- V60 created a deliberately minimal `pipeline.price_book` / `price_book_entry`
-- pair so opportunity lines could be drawn from a governed list before this
-- epic existed, and said in its own comment that E08 owns the real catalogue.
-- This migration adds that catalogue in its own schema rather than widening
-- V60's tables: the opportunity line contract is unchanged, `pipeline.*` keeps
-- working, and the two can be reconciled by a later migration without a
-- flag-day rewrite of either module.
--
-- Every tenant-scoped table below gets `enable`+`force row level security` and
-- a `tenant_isolation` policy. The policy predicate is
--   nullif(current_setting('app.tenant_id', true), '')::uuid
-- and the `nullif` is load-bearing, not decoration: `SET LOCAL app.tenant_id`
-- reverts to the EMPTY STRING at transaction end rather than to NULL, and
-- `''::uuid` raises `invalid input syntax for type uuid`. Without the nullif,
-- any statement evaluated on a connection whose local setting has reverted
-- errors instead of simply matching no rows.
-- =============================================================================

create extension if not exists btree_gist;

create schema if not exists cpq;
grant usage on schema cpq to axiom_app;

-- The runtime search_path is shared with every other module, and migrations
-- land in an order this file cannot know. Merge rather than replace (the V70
-- pattern) so a schema added by a migration that ran earlier is not silently
-- dropped from the role setting.
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
  if not ('cpq' = any(merged)) then
    merged := array_append(merged, 'cpq');
  end if;
  merged := array_append(merged, 'public');
  execute format('alter role axiom_app set search_path to %s', array_to_string(merged, ', '));
end $$;

-- -----------------------------------------------------------------------------
-- FR-CPQ-001 — product catalogue.
-- -----------------------------------------------------------------------------
create table cpq.product (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  code             text not null,
  name             text not null,
  description      text,
  product_family   text,
  category         text,
  unit_of_measure  text not null default 'EACH',
  is_active        boolean not null default true,
  is_bundle        boolean not null default false,
  is_subscription  boolean not null default false,
  attributes       jsonb not null default '{}'::jsonb,
  default_cost     numeric(14,2) check (default_cost is null or default_cost >= 0),
  lifecycle_start  date,
  lifecycle_end    date,
  created_at       timestamptz not null default now(),
  created_by       uuid,
  updated_at       timestamptz not null default now(),
  updated_by       uuid,
  version          bigint not null default 0,
  deleted_at       timestamptz,
  deleted_by       uuid,
  unique (tenant_id, id),
  unique (tenant_id, code),
  constraint product_code_format check (code ~ '^[A-Z0-9][A-Z0-9._-]*$'),
  constraint product_lifecycle_ordered check (lifecycle_end is null or lifecycle_start is null or lifecycle_end >= lifecycle_start)
);

create index idx_product_active on cpq.product(tenant_id, is_active, category) where deleted_at is null;
create index idx_product_attributes on cpq.product using gin (attributes);

-- FR-CPQ-005 — bundle composition. A required component cannot be dropped from
-- a configuration; an optional one can.
create table cpq.product_bundle_component (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null references platform.tenant(id),
  bundle_product_id    uuid not null,
  component_product_id uuid not null,
  is_required          boolean not null default false,
  min_qty              numeric(14,4) not null default 1 check (min_qty >= 0),
  max_qty              numeric(14,4) check (max_qty is null or max_qty >= min_qty),
  default_qty          numeric(14,4) not null default 1 check (default_qty >= 0),
  sort_order           integer not null default 0,
  created_at           timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, bundle_product_id, component_product_id),
  constraint fk_bundle_component_bundle_same_tenant
    foreign key (tenant_id, bundle_product_id) references cpq.product(tenant_id, id),
  constraint fk_bundle_component_component_same_tenant
    foreign key (tenant_id, component_product_id) references cpq.product(tenant_id, id),
  constraint bundle_component_not_self check (bundle_product_id <> component_product_id),
  constraint bundle_required_default_qty check (not is_required or default_qty >= greatest(min_qty, 1))
);

create index idx_bundle_component_parent on cpq.product_bundle_component(tenant_id, bundle_product_id, sort_order);

-- FR-CPQ-005 — configuration rules. `resolution_options` is not cosmetic: the
-- requirement is that a blocked configuration names the violated constraint AND
-- the options that would resolve it, so the resolving options are stored beside
-- the rule instead of being invented by the UI.
create table cpq.configuration_rule (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  code               text not null,
  bundle_product_id  uuid,
  rule_type          text not null check (rule_type in ('INCLUDE','EXCLUDE','REQUIRE','VALIDATE')),
  trigger_product_id uuid,
  target_product_id  uuid,
  message            text not null,
  resolution_options jsonb not null default '[]'::jsonb,
  priority           integer not null default 100,
  is_active          boolean not null default true,
  created_at         timestamptz not null default now(),
  created_by         uuid,
  unique (tenant_id, id),
  unique (tenant_id, code),
  constraint fk_config_rule_bundle_same_tenant
    foreign key (tenant_id, bundle_product_id) references cpq.product(tenant_id, id),
  constraint fk_config_rule_trigger_same_tenant
    foreign key (tenant_id, trigger_product_id) references cpq.product(tenant_id, id),
  constraint fk_config_rule_target_same_tenant
    foreign key (tenant_id, target_product_id) references cpq.product(tenant_id, id),
  constraint config_rule_needs_target check (rule_type = 'VALIDATE' or target_product_id is not null)
);

create index idx_config_rule_bundle on cpq.configuration_rule(tenant_id, bundle_product_id, priority) where is_active;

-- -----------------------------------------------------------------------------
-- FR-CPQ-002 — price books, scoped by currency, business unit and segment.
-- FR-CPQ-014 — a book carries a version and a supersedes link so a DRAFT
-- successor can be previewed against open work before it is activated.
-- -----------------------------------------------------------------------------
create table cpq.price_book (
  id                       uuid primary key default gen_random_uuid(),
  tenant_id                uuid not null references platform.tenant(id),
  code                     text not null,
  name                     text not null,
  currency_code            text not null default 'USD' check (char_length(currency_code) = 3),
  business_unit_code       text,
  customer_segment         text,
  version_number           integer not null default 1 check (version_number >= 1),
  supersedes_price_book_id uuid,
  status                   text not null default 'DRAFT' check (status in ('DRAFT','ACTIVE','ARCHIVED')),
  is_default               boolean not null default false,
  activated_at             timestamptz,
  activated_by             uuid,
  created_at               timestamptz not null default now(),
  created_by               uuid,
  updated_at               timestamptz not null default now(),
  updated_by               uuid,
  version                  bigint not null default 0,
  unique (tenant_id, id),
  unique (tenant_id, code),
  constraint fk_price_book_supersedes_same_tenant
    foreign key (tenant_id, supersedes_price_book_id) references cpq.price_book(tenant_id, id),
  constraint price_book_active_has_activation check (status <> 'ACTIVE' or activated_at is not null)
);

create index idx_price_book_scope on cpq.price_book(tenant_id, status, currency_code, business_unit_code, customer_segment);

-- FR-CPQ-002 constraint (09-data-model §4.7): no two ACTIVE entries for the same
-- (price_book_id, product_id) may have overlapping effective ranges. Rejected at
-- save by PriceBookService with an actionable message naming the colliding row;
-- the exclusion constraint below is the reason a race between two concurrent
-- saves cannot slip a second overlapping row past that check.
create table cpq.price_book_entry (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  price_book_id  uuid not null,
  product_id     uuid not null,
  unit_price     numeric(14,2) not null check (unit_price >= 0),
  unit_cost      numeric(14,2) check (unit_cost is null or unit_cost >= 0),
  currency_code  text not null default 'USD' check (char_length(currency_code) = 3),
  pricing_method text not null default 'LIST' check (pricing_method in
                   ('LIST','TIERED','VOLUME','BLOCK','PERCENT_OF_TOTAL','ATTRIBUTE','SUBSCRIPTION')),
  effective_from date not null,
  effective_to   date,
  is_active      boolean not null default true,
  created_at     timestamptz not null default now(),
  created_by     uuid,
  updated_at     timestamptz not null default now(),
  updated_by     uuid,
  version        bigint not null default 0,
  unique (tenant_id, id),
  constraint fk_pbe_book_same_tenant
    foreign key (tenant_id, price_book_id) references cpq.price_book(tenant_id, id),
  constraint fk_pbe_product_same_tenant
    foreign key (tenant_id, product_id) references cpq.product(tenant_id, id),
  constraint pbe_range_ordered check (effective_to is null or effective_to >= effective_from),
  constraint pbe_no_overlapping_active_range exclude using gist (
    tenant_id with =,
    price_book_id with =,
    product_id with =,
    daterange(effective_from, effective_to, '[]') with &&
  ) where (is_active)
);

create index idx_pbe_resolve on cpq.price_book_entry(tenant_id, price_book_id, product_id, effective_from desc) where is_active;

-- FR-CPQ-007 — the parameters for the non-list pricing methods. Kept relational
-- rather than as an expression string so a tier boundary is a queryable number
-- an administrator can inspect, not an opaque formula.
create table cpq.pricing_rule (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  product_id      uuid not null,
  price_book_id   uuid,
  method          text not null check (method in ('TIERED','VOLUME','BLOCK','PERCENT_OF_TOTAL','ATTRIBUTE','SUBSCRIPTION')),
  label           text not null,
  priority        integer not null default 100,
  -- Quantity band. Half-open [min, max) so the boundary value is unambiguous:
  -- quantity 100 with bands [0,100) and [100,+inf) lands in the second band.
  min_quantity    numeric(14,4) not null default 0 check (min_quantity >= 0),
  max_quantity    numeric(14,4) check (max_quantity is null or max_quantity > min_quantity),
  unit_price      numeric(14,4) check (unit_price is null or unit_price >= 0),
  percent         numeric(9,4),
  block_size      numeric(14,4) check (block_size is null or block_size > 0),
  block_price     numeric(14,2) check (block_price is null or block_price >= 0),
  attribute_name  text,
  attribute_value text,
  term_months     integer check (term_months is null or term_months > 0),
  is_active       boolean not null default true,
  created_at      timestamptz not null default now(),
  created_by      uuid,
  unique (tenant_id, id),
  constraint fk_pricing_rule_product_same_tenant
    foreign key (tenant_id, product_id) references cpq.product(tenant_id, id),
  constraint fk_pricing_rule_book_same_tenant
    foreign key (tenant_id, price_book_id) references cpq.price_book(tenant_id, id),
  constraint pricing_rule_attribute_complete check (method <> 'ATTRIBUTE' or (attribute_name is not null and attribute_value is not null)),
  constraint pricing_rule_block_complete check (method <> 'BLOCK' or (block_size is not null and block_price is not null)),
  constraint pricing_rule_percent_complete check (method <> 'PERCENT_OF_TOTAL' or percent is not null)
);

create index idx_pricing_rule_lookup on cpq.pricing_rule(tenant_id, product_id, method, priority) where is_active;

-- FR-CPQ-008 — negotiated customer-specific prices. Overrides the book price for
-- the named account inside the period, and only inside it.
create table cpq.contracted_price (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  account_id     uuid not null,
  product_id     uuid not null,
  unit_price     numeric(14,2) not null check (unit_price >= 0),
  currency_code  text not null default 'USD' check (char_length(currency_code) = 3),
  contract_ref   text,
  effective_from date not null,
  effective_to   date,
  is_active      boolean not null default true,
  created_at     timestamptz not null default now(),
  created_by     uuid,
  unique (tenant_id, id),
  constraint fk_contracted_price_account_same_tenant
    foreign key (tenant_id, account_id) references crm.account(tenant_id, id),
  constraint fk_contracted_price_product_same_tenant
    foreign key (tenant_id, product_id) references cpq.product(tenant_id, id),
  constraint contracted_price_range_ordered check (effective_to is null or effective_to >= effective_from),
  -- Same reasoning as the price book entry: two active negotiated prices for the
  -- same account and product on the same day is not a preference question.
  constraint contracted_price_no_overlap exclude using gist (
    tenant_id with =,
    account_id with =,
    product_id with =,
    daterange(effective_from, effective_to, '[]') with &&
  ) where (is_active)
);

create index idx_contracted_price_lookup on cpq.contracted_price(tenant_id, account_id, product_id, effective_from desc) where is_active;

-- -----------------------------------------------------------------------------
-- FR-CPQ-009 / FR-CPQ-010 — approval policy. Routing is by amount, margin,
-- product and role, so all four are columns rather than one expression.
-- -----------------------------------------------------------------------------
create table cpq.approval_policy (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references platform.tenant(id),
  code                text not null,
  name                text not null,
  trigger_type        text not null check (trigger_type in ('DISCOUNT_PCT','DISCOUNT_AMOUNT','MARGIN_FLOOR')),
  applies_to          text not null default 'QUOTE' check (applies_to in ('LINE','QUOTE')),
  threshold_pct       numeric(9,4) check (threshold_pct is null or threshold_pct >= 0),
  threshold_amount    numeric(14,2) check (threshold_amount is null or threshold_amount >= 0),
  margin_floor_pct    numeric(9,4) check (margin_floor_pct is null or margin_floor_pct >= 0),
  product_id          uuid,
  product_category    text,
  approver_role       text not null check (approver_role in (
                        'SUPER_ADMIN','TENANT_ADMIN','SALES_MANAGER','OPERATIONS','FINANCE')),
  priority            integer not null default 100,
  is_active           boolean not null default true,
  created_at          timestamptz not null default now(),
  created_by          uuid,
  unique (tenant_id, id),
  unique (tenant_id, code),
  constraint fk_approval_policy_product_same_tenant
    foreign key (tenant_id, product_id) references cpq.product(tenant_id, id),
  constraint approval_policy_threshold_present check (
    (trigger_type = 'DISCOUNT_PCT' and threshold_pct is not null)
    or (trigger_type = 'DISCOUNT_AMOUNT' and threshold_amount is not null)
    or (trigger_type = 'MARGIN_FLOOR' and margin_floor_pct is not null))
);

create index idx_approval_policy_active on cpq.approval_policy(tenant_id, trigger_type, priority) where is_active;

-- -----------------------------------------------------------------------------
-- FR-CPQ-011 — branded document template with merge fields.
-- -----------------------------------------------------------------------------
create table cpq.document_template (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  code            text not null,
  name            text not null,
  brand_name      text not null,
  brand_tagline   text,
  accent_hex      text not null default '#0B6E4F' check (accent_hex ~ '^#[0-9A-Fa-f]{6}$'),
  intro_template  text not null,
  terms_template  text not null,
  footer_template text not null,
  is_default      boolean not null default false,
  is_active       boolean not null default true,
  created_at      timestamptz not null default now(),
  created_by      uuid,
  unique (tenant_id, id),
  unique (tenant_id, code)
);

-- -----------------------------------------------------------------------------
-- FR-CPQ-006 — guided selling.
-- -----------------------------------------------------------------------------
create table cpq.guided_question (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  questionnaire  text not null default 'DEFAULT',
  code           text not null,
  sequence_no    integer not null,
  prompt         text not null,
  help_text      text,
  answer_type    text not null default 'SINGLE_SELECT' check (answer_type in ('SINGLE_SELECT','MULTI_SELECT')),
  is_active      boolean not null default true,
  created_at     timestamptz not null default now(),
  created_by     uuid,
  unique (tenant_id, id),
  unique (tenant_id, questionnaire, code),
  unique (tenant_id, questionnaire, sequence_no)
);

create table cpq.guided_answer_option (
  id                     uuid primary key default gen_random_uuid(),
  tenant_id              uuid not null references platform.tenant(id),
  question_id            uuid not null,
  code                   text not null,
  label                  text not null,
  -- Answers filter the catalogue. A category/attribute filter narrows; a named
  -- product recommendation promotes. Both may be set on one option.
  filter_category        text,
  filter_attribute_name  text,
  filter_attribute_value text,
  recommended_product_id uuid,
  weight                 integer not null default 10,
  sort_order             integer not null default 0,
  created_at             timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, question_id, code),
  constraint fk_guided_option_question_same_tenant
    foreign key (tenant_id, question_id) references cpq.guided_question(tenant_id, id),
  constraint fk_guided_option_product_same_tenant
    foreign key (tenant_id, recommended_product_id) references cpq.product(tenant_id, id),
  constraint guided_option_does_something check (
    filter_category is not null or filter_attribute_name is not null or recommended_product_id is not null)
);

-- -----------------------------------------------------------------------------
-- FR-CPQ-003 / 004 / 013 — the quote itself.
--
-- Versions of one commercial offer share `quote_group_id`. A new version is a
-- new row, so the prior version is retained BYTE-FOR-BYTE rather than being
-- reconstructed from a history table — which is what makes the field-level
-- comparison in FR-CPQ-004 a plain join instead of a replay.
--
-- Money follows modelling rule M6: transaction currency and amount, corporate
-- amount, the applied rate and the rate's date are all stored. A stored
-- conversion is never recomputed on read.
-- -----------------------------------------------------------------------------
create table cpq.quote (
  id                       uuid primary key default gen_random_uuid(),
  tenant_id                uuid not null references platform.tenant(id),
  quote_number             text not null,
  quote_group_id           uuid not null,
  version_number           integer not null default 1 check (version_number >= 1),
  is_active_version        boolean not null default true,
  supersedes_quote_id      uuid,
  superseded_by_quote_id   uuid,
  superseded_at            timestamptz,
  opportunity_id           uuid,
  account_id               uuid not null,
  contact_id               uuid,
  price_book_id            uuid not null,
  owner_id                 uuid not null,
  name                     text not null,
  status                   text not null default 'DRAFT' check (status in
                             ('DRAFT','IN_APPROVAL','SENT','ACCEPTED','REJECTED','EXPIRED','ORDERED')),
  approval_status          text not null default 'NOT_REQUIRED' check (approval_status in
                             ('NOT_REQUIRED','PENDING','APPROVED','REJECTED')),
  -- M6 money block.
  currency_code            text not null default 'USD' check (char_length(currency_code) = 3),
  subtotal                 numeric(14,2) not null default 0,
  discount_total           numeric(14,2) not null default 0,
  tax_total                numeric(14,2) not null default 0,
  grand_total              numeric(14,2) not null default 0,
  corporate_currency_code  text not null default 'USD' check (char_length(corporate_currency_code) = 3),
  corporate_grand_total    numeric(14,2) not null default 0,
  fx_rate                  numeric(18,8) not null default 1 check (fx_rate > 0),
  fx_rate_date             date not null default current_date,
  -- FR-CPQ-010 margin.
  cost_total               numeric(14,2) not null default 0,
  margin_amount            numeric(14,2) not null default 0,
  margin_pct               numeric(9,4),
  quote_discount_pct       numeric(9,4) not null default 0 check (quote_discount_pct >= 0 and quote_discount_pct <= 100),
  -- FR-CPQ-013 lifecycle.
  valid_from               date not null default current_date,
  expires_at               timestamptz,
  sent_at                  timestamptz,
  accepted_at              timestamptz,
  rejected_at              timestamptz,
  expired_at               timestamptz,
  reminder_sent_at         timestamptz,
  reject_reason            text,
  -- FR-CPQ-011 / FR-CPQ-012 artefacts.
  document_ref             text,
  document_version         integer,
  esign_envelope_id        uuid,
  esign_status             text check (esign_status is null or esign_status in
                             ('CREATED','SENT','VIEWED','SIGNED','DECLINED','EXPIRED','FAILED')),
  executed_document_ref    text,
  -- FR-CPQ-003 controlled sync.
  synced_to_opportunity_at timestamptz,
  change_reason            text,
  created_at               timestamptz not null default now(),
  created_by               uuid,
  updated_at               timestamptz not null default now(),
  updated_by               uuid,
  version                  bigint not null default 0,
  deleted_at               timestamptz,
  deleted_by               uuid,
  unique (tenant_id, id),
  unique (tenant_id, quote_number),
  unique (tenant_id, quote_group_id, version_number),
  constraint fk_quote_account_same_tenant
    foreign key (tenant_id, account_id) references crm.account(tenant_id, id),
  constraint fk_quote_contact_same_tenant
    foreign key (tenant_id, contact_id) references crm.contact(tenant_id, id),
  constraint fk_quote_opportunity_same_tenant
    foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id),
  constraint fk_quote_price_book_same_tenant
    foreign key (tenant_id, price_book_id) references cpq.price_book(tenant_id, id),
  constraint fk_quote_owner_same_tenant
    foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id),
  constraint fk_quote_supersedes_same_tenant
    foreign key (tenant_id, supersedes_quote_id) references cpq.quote(tenant_id, id),
  -- A superseded row is not the active version and carries its successor. Both
  -- halves of that statement are enforced, because FR-CPQ-004's "a superseded
  -- version cannot be accepted" is only checkable if "superseded" is unambiguous.
  constraint quote_superseded_consistent check (
    (superseded_at is null and superseded_by_quote_id is null and is_active_version)
    or (superseded_at is not null and superseded_by_quote_id is not null and not is_active_version)),
  constraint quote_accepted_has_time check (status <> 'ACCEPTED' or accepted_at is not null),
  constraint quote_sent_has_time check (status not in ('SENT','ACCEPTED','REJECTED','ORDERED') or sent_at is not null)
);

-- FR-CPQ-004 — exactly one active version per commercial offer.
create unique index uq_quote_one_active_version
  on cpq.quote(tenant_id, quote_group_id)
  where is_active_version and deleted_at is null;

create index idx_quote_opportunity on cpq.quote(tenant_id, opportunity_id) where deleted_at is null;
create index idx_quote_account on cpq.quote(tenant_id, account_id, status) where deleted_at is null;
create index idx_quote_expiry on cpq.quote(tenant_id, expires_at) where status = 'SENT' and deleted_at is null;

create table cpq.quote_line (
  id                       uuid primary key default gen_random_uuid(),
  tenant_id                uuid not null references platform.tenant(id),
  quote_id                 uuid not null,
  line_number              integer not null,
  product_id               uuid not null,
  product_code             text not null,
  product_name             text not null,
  unit_of_measure          text not null default 'EACH',
  bundle_parent_line_id    uuid,
  is_required_component    boolean not null default false,
  quantity                 numeric(14,4) not null check (quantity > 0),
  -- FR-CPQ-007. list_price is where the derivation starts; net_unit_price is
  -- where it ends; every step between them is a row in quote_line_adjustment
  -- and a member of the price_adjustments array. The two must agree.
  list_price               numeric(14,4) not null check (list_price >= 0),
  net_unit_price           numeric(14,4) not null check (net_unit_price >= 0),
  extended_amount          numeric(14,2) not null,
  discount_pct             numeric(9,4) not null default 0 check (discount_pct >= 0 and discount_pct <= 100),
  discount_amount          numeric(14,2) not null default 0,
  pricing_method_applied   text not null check (pricing_method_applied in
                             ('LIST','TIERED','VOLUME','BLOCK','PERCENT_OF_TOTAL','ATTRIBUTE','SUBSCRIPTION','CONTRACTED')),
  price_adjustments        jsonb not null default '[]'::jsonb,
  -- Subscription / proration (FR-CPQ-007).
  term_months              integer check (term_months is null or term_months > 0),
  subscription_start       date,
  subscription_end         date,
  proration_factor         numeric(12,8) check (proration_factor is null or proration_factor > 0),
  -- Margin (FR-CPQ-010). Null cost means "cost unknown", which is a different
  -- fact from zero cost and is reported as such rather than as 100% margin.
  unit_cost                numeric(14,4) check (unit_cost is null or unit_cost >= 0),
  cost_amount              numeric(14,2),
  margin_amount            numeric(14,2),
  margin_pct               numeric(9,4),
  -- M6 money block at line level.
  currency_code            text not null default 'USD' check (char_length(currency_code) = 3),
  corporate_extended_amount numeric(14,2),
  fx_rate                  numeric(18,8) not null default 1 check (fx_rate > 0),
  fx_rate_date             date not null default current_date,
  price_book_entry_id      uuid,
  contracted_price_id      uuid,
  created_at               timestamptz not null default now(),
  updated_at               timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, quote_id, line_number),
  constraint fk_quote_line_quote_same_tenant
    foreign key (tenant_id, quote_id) references cpq.quote(tenant_id, id) on delete cascade,
  constraint fk_quote_line_product_same_tenant
    foreign key (tenant_id, product_id) references cpq.product(tenant_id, id),
  constraint fk_quote_line_parent_same_tenant
    foreign key (tenant_id, bundle_parent_line_id) references cpq.quote_line(tenant_id, id) on delete cascade,
  constraint fk_quote_line_pbe_same_tenant
    foreign key (tenant_id, price_book_entry_id) references cpq.price_book_entry(tenant_id, id),
  constraint fk_quote_line_contracted_same_tenant
    foreign key (tenant_id, contracted_price_id) references cpq.contracted_price(tenant_id, id),
  constraint quote_line_subscription_complete check (
    pricing_method_applied <> 'SUBSCRIPTION'
    or (term_months is not null and subscription_start is not null and proration_factor is not null)),
  constraint quote_line_adjustments_present check (jsonb_typeof(price_adjustments) = 'array')
);

create index idx_quote_line_parent on cpq.quote_line(tenant_id, quote_id, line_number);

-- FR-CPQ-007 — the itemization, relationally. `quote_line.price_adjustments`
-- holds the same ledger as JSON for the read path; these rows are what make it
-- queryable (e.g. "how much did tier pricing give away across the quarter").
create table cpq.quote_line_adjustment (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references platform.tenant(id),
  quote_line_id       uuid not null,
  sequence_no         integer not null,
  adjustment_type     text not null check (adjustment_type in (
                        'LIST_PRICE','CONTRACTED_PRICE','TIER','VOLUME','BLOCK','PERCENT_OF_TOTAL',
                        'ATTRIBUTE','SUBSCRIPTION_TERM','PRORATION','LINE_DISCOUNT','QUOTE_DISCOUNT')),
  label               text not null,
  basis_unit_price    numeric(14,4) not null,
  amount              numeric(14,4) not null,
  resulting_unit_price numeric(14,4) not null,
  source_ref          text,
  detail              jsonb not null default '{}'::jsonb,
  created_at          timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, quote_line_id, sequence_no),
  constraint fk_qla_line_same_tenant
    foreign key (tenant_id, quote_line_id) references cpq.quote_line(tenant_id, id) on delete cascade
);

create index idx_qla_line on cpq.quote_line_adjustment(tenant_id, quote_line_id, sequence_no);

-- FR-CPQ-009 / FR-CPQ-010 — outstanding approvals. The refusal message on send
-- names the approval and its current approver, so both are stored on the row
-- rather than resolved at message-formatting time.
create table cpq.quote_approval (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  quote_id         uuid not null,
  policy_id        uuid,
  approval_type    text not null check (approval_type in ('DISCOUNT','MARGIN_FLOOR')),
  reason           text not null,
  observed_value   numeric(14,4),
  threshold_value  numeric(14,4),
  shortfall        numeric(14,4),
  approver_role    text not null,
  approver_id      uuid,
  approver_name    text,
  status           text not null default 'PENDING' check (status in ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
  decided_at       timestamptz,
  decided_by       uuid,
  decided_by_name  text,
  decision_note    text,
  requested_at     timestamptz not null default now(),
  requested_by     uuid,
  unique (tenant_id, id),
  constraint fk_quote_approval_quote_same_tenant
    foreign key (tenant_id, quote_id) references cpq.quote(tenant_id, id) on delete cascade,
  constraint fk_quote_approval_policy_same_tenant
    foreign key (tenant_id, policy_id) references cpq.approval_policy(tenant_id, id),
  constraint quote_approval_decided_consistent check (
    status = 'PENDING' or decided_at is not null)
);

create index idx_quote_approval_pending on cpq.quote_approval(tenant_id, quote_id) where status = 'PENDING';

-- FR-CPQ-011 — the artefact. Content is stored in the row and hashed: the
-- requirement is a STABLE versioned artefact, and an artefact regenerated on
-- every download is not stable, however deterministic the generator claims to be.
create table cpq.quote_document (
  id                   uuid primary key default gen_random_uuid(),
  tenant_id            uuid not null references platform.tenant(id),
  quote_id             uuid not null,
  quote_version_number integer not null,
  template_id          uuid,
  document_version     integer not null check (document_version >= 1),
  document_ref         text not null,
  file_name            text not null,
  content_type         text not null default 'application/pdf',
  byte_size            integer not null check (byte_size > 0),
  content_hash         text not null,
  content              bytea not null,
  is_executed_copy     boolean not null default false,
  generated_at         timestamptz not null default now(),
  generated_by         uuid,
  generated_by_name    text,
  unique (tenant_id, id),
  unique (tenant_id, quote_id, document_version),
  unique (tenant_id, document_ref),
  constraint fk_quote_document_quote_same_tenant
    foreign key (tenant_id, quote_id) references cpq.quote(tenant_id, id) on delete cascade,
  constraint fk_quote_document_template_same_tenant
    foreign key (tenant_id, template_id) references cpq.document_template(tenant_id, id)
);

-- FR-CPQ-012 — e-signature envelope state, provider-agnostic (ADR-007). The
-- provider column is the adapter's name; the envelope_ref is the vendor's own
-- identifier and is the only vendor-shaped value that crosses the boundary.
create table cpq.esign_envelope (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references platform.tenant(id),
  quote_id              uuid not null,
  quote_document_id     uuid,
  provider_code         text not null default 'LOCAL_STUB',
  envelope_ref          text not null,
  status                text not null default 'CREATED' check (status in
                          ('CREATED','SENT','VIEWED','SIGNED','DECLINED','EXPIRED','FAILED')),
  signer_name           text not null,
  signer_email          text not null,
  idempotency_key       text not null,
  sent_at               timestamptz,
  viewed_at             timestamptz,
  completed_at          timestamptz,
  expires_at            timestamptz,
  executed_document_ref text,
  last_error            text,
  created_at            timestamptz not null default now(),
  created_by            uuid,
  updated_at            timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, provider_code, envelope_ref),
  unique (tenant_id, idempotency_key),
  constraint fk_esign_quote_same_tenant
    foreign key (tenant_id, quote_id) references cpq.quote(tenant_id, id) on delete cascade,
  constraint fk_esign_document_same_tenant
    foreign key (tenant_id, quote_document_id) references cpq.quote_document(tenant_id, id)
);

create table cpq.esign_event (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  envelope_id  uuid not null,
  status       text not null,
  occurred_at  timestamptz not null default now(),
  detail       jsonb not null default '{}'::jsonb,
  unique (tenant_id, id),
  constraint fk_esign_event_envelope_same_tenant
    foreign key (tenant_id, envelope_id) references cpq.esign_envelope(tenant_id, id) on delete cascade
);

-- FR-CPQ-013 — conversion of an accepted quote to an order. E09 owns the full
-- order, contract and subscription lifecycle; this table is the CPQ-side
-- hand-off record so acceptance has somewhere to land today. When E09 lands its
-- ORDER_RECORD, this becomes the link row rather than being deleted.
create table cpq.quote_order (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  quote_id       uuid not null,
  order_number   text not null,
  account_id     uuid not null,
  currency_code  text not null default 'USD' check (char_length(currency_code) = 3),
  total_amount   numeric(14,2) not null,
  corporate_total_amount numeric(14,2) not null,
  fx_rate        numeric(18,8) not null default 1,
  fx_rate_date   date not null default current_date,
  status         text not null default 'PENDING_FULFILMENT' check (status in ('PENDING_FULFILMENT','HANDED_OFF','CANCELLED')),
  created_at     timestamptz not null default now(),
  created_by     uuid,
  unique (tenant_id, id),
  unique (tenant_id, order_number),
  unique (tenant_id, quote_id),
  constraint fk_quote_order_quote_same_tenant
    foreign key (tenant_id, quote_id) references cpq.quote(tenant_id, id),
  constraint fk_quote_order_account_same_tenant
    foreign key (tenant_id, account_id) references crm.account(tenant_id, id)
);

-- Per-tenant quote and order numbering. A sequence would be shared across
-- tenants and would leak one tenant's volume to another (modelling rule M2).
create table cpq.document_counter (
  tenant_id     uuid not null references platform.tenant(id),
  counter_code  text not null,
  next_value    bigint not null default 1,
  constraint pk_cpq_document_counter primary key (tenant_id, counter_code)
);

-- -----------------------------------------------------------------------------
-- Row level security. Applied uniformly, generated rather than hand-written so a
-- table added to the list below cannot be given a subtly different predicate.
-- -----------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array[
    'product','product_bundle_component','configuration_rule','price_book','price_book_entry',
    'pricing_rule','contracted_price','approval_policy','document_template','guided_question',
    'guided_answer_option','quote','quote_line','quote_line_adjustment','quote_approval',
    'quote_document','esign_envelope','esign_event','quote_order','document_counter'
  ] loop
    execute format('alter table cpq.%I enable row level security', t);
    execute format('alter table cpq.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on cpq.%I '
      'using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
    execute format('grant select, insert, update, delete on cpq.%I to axiom_app', t);
  end loop;
end $$;

-- -----------------------------------------------------------------------------
-- Governance registration: module, tables, screens and RBAC.
-- -----------------------------------------------------------------------------
insert into governance.module_catalog(module_code, schema_name, display_name, description, owner_role) values
  ('CPQ', 'cpq', 'Products and quoting', 'Product catalogue, price books, configuration, quoting, discount approval and quote documents.', 'SALES_MANAGER')
on conflict (module_code) do nothing;

-- V6 constrained governed value sets to the modules that existed at the time.
-- CPQ owns product/quote enumerations, so widen the module domain before
-- seeding its value sets.
alter table reference.value_set drop constraint if exists value_set_module_check;
alter table reference.value_set
  add constraint value_set_module_check
  check (module in ('CRM','SALES','ENGAGEMENT','GOVERNANCE','REFERENCE','CPQ'));

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('cpq','product','CPQ','id',true,'SOFT_DELETE'),
  ('cpq','product_bundle_component','CPQ','id',true,'ACTIVE'),
  ('cpq','configuration_rule','CPQ','id',true,'ACTIVE'),
  ('cpq','price_book','CPQ','id',true,'ACTIVE'),
  ('cpq','price_book_entry','CPQ','id',true,'ACTIVE'),
  ('cpq','pricing_rule','CPQ','id',true,'ACTIVE'),
  ('cpq','contracted_price','CPQ','id',true,'ACTIVE'),
  ('cpq','approval_policy','CPQ','id',true,'ACTIVE'),
  ('cpq','document_template','CPQ','id',true,'ACTIVE'),
  ('cpq','guided_question','CPQ','id',true,'ACTIVE'),
  ('cpq','guided_answer_option','CPQ','id',true,'ACTIVE'),
  ('cpq','quote','CPQ','id',true,'SOFT_DELETE'),
  ('cpq','quote_line','CPQ','id',true,'ACTIVE'),
  ('cpq','quote_line_adjustment','CPQ','id',true,'APPEND_ONLY'),
  ('cpq','quote_approval','CPQ','id',true,'ACTIVE'),
  ('cpq','quote_document','CPQ','id',true,'APPEND_ONLY'),
  ('cpq','esign_envelope','CPQ','id',true,'ACTIVE'),
  ('cpq','esign_event','CPQ','id',true,'APPEND_ONLY'),
  ('cpq','quote_order','CPQ','id',true,'ACTIVE'),
  ('cpq','document_counter','CPQ','tenant_id',true,'ACTIVE')
on conflict (schema_name, table_name) do nothing;

insert into governance.screen_catalog(screen_code, module_code, route, display_name, description, sort_order) values
  ('PRODUCTS', 'CPQ', '/products', 'Products', 'Product catalogue, bundles and configuration rules.', 40),
  ('PRICE_BOOKS', 'CPQ', '/price-books', 'Price books', 'Effective-dated price book entries, contracted prices and activation impact preview.', 41),
  ('QUOTES', 'CPQ', '/quotes', 'Quotes', 'Quote builder, itemized pricing, discount approval, versions and documents.', 42)
on conflict (screen_code) do nothing;

insert into governance.rbac_policy(role_code, screen_code, can_read, can_write, can_export, can_admin, scope)
select role_code, screen_code,
       role_code <> 'INTEGRATION',
       role_code not in ('SUPER_AUDIT','AUDITOR','INTEGRATION'),
       role_code <> 'INTEGRATION',
       role_code in ('SUPER_ADMIN','TENANT_ADMIN','FINANCE'),
       case when role_code in ('SUPER_ADMIN','SUPER_AUDIT') then 'PLATFORM' else 'TENANT' end
from (values
  ('SUPER_ADMIN'),('SUPER_AUDIT'),('TENANT_ADMIN'),('SALES_MANAGER'),('SALES'),
  ('MARKETING'),('SERVICE'),('OPERATIONS'),('FINANCE'),('DATA_STEWARD'),('AUDITOR'),('INTEGRATION')
) roles(role_code)
cross join (values ('PRODUCTS'),('PRICE_BOOKS'),('QUOTES')) screens(screen_code)
on conflict (role_code, screen_code) do update
  set can_read = excluded.can_read,
      can_write = excluded.can_write,
      can_export = excluded.can_export,
      can_admin = excluded.can_admin,
      scope = excluded.scope;

-- Governed enumerations (modelling rule M7).
insert into reference.value_set (tenant_id, api_name, label, module, description)
select t.id, seed.api_name, seed.label, seed.module, seed.description
from platform.tenant t
cross join (values
  ('quote_status', 'Quote status', 'CPQ', 'Quote lifecycle states'),
  ('pricing_method', 'Pricing method', 'CPQ', 'Pricing methods available to a price book entry or quote line'),
  ('price_adjustment_type', 'Price adjustment type', 'CPQ', 'Itemized adjustment kinds recorded against a quote line'),
  ('esign_status', 'E-signature status', 'CPQ', 'E-signature envelope states reflected on a quote')
) as seed(api_name, label, module, description)
on conflict (tenant_id, api_name) do nothing;

insert into reference.value_set_entry (tenant_id, value_set_id, code, label, sort_order, system_managed)
select vs.tenant_id, vs.id, seed.code, seed.label, seed.sort_order, true
from reference.value_set vs
join (values
  ('quote_status', 'DRAFT', 'Draft', 10),
  ('quote_status', 'IN_APPROVAL', 'In approval', 20),
  ('quote_status', 'SENT', 'Sent', 30),
  ('quote_status', 'ACCEPTED', 'Accepted', 40),
  ('quote_status', 'REJECTED', 'Rejected', 50),
  ('quote_status', 'EXPIRED', 'Expired', 60),
  ('quote_status', 'ORDERED', 'Converted to order', 70),
  ('pricing_method', 'LIST', 'List price', 10),
  ('pricing_method', 'TIERED', 'Tiered', 20),
  ('pricing_method', 'VOLUME', 'Volume', 30),
  ('pricing_method', 'BLOCK', 'Block', 40),
  ('pricing_method', 'PERCENT_OF_TOTAL', 'Percent of total', 50),
  ('pricing_method', 'ATTRIBUTE', 'Attribute-based', 60),
  ('pricing_method', 'SUBSCRIPTION', 'Term subscription', 70),
  ('pricing_method', 'CONTRACTED', 'Contracted price', 80),
  ('price_adjustment_type', 'LIST_PRICE', 'List price', 10),
  ('price_adjustment_type', 'CONTRACTED_PRICE', 'Contracted price override', 20),
  ('price_adjustment_type', 'TIER', 'Tier adjustment', 30),
  ('price_adjustment_type', 'VOLUME', 'Volume adjustment', 40),
  ('price_adjustment_type', 'BLOCK', 'Block adjustment', 50),
  ('price_adjustment_type', 'PERCENT_OF_TOTAL', 'Percent of total', 60),
  ('price_adjustment_type', 'ATTRIBUTE', 'Attribute adjustment', 70),
  ('price_adjustment_type', 'SUBSCRIPTION_TERM', 'Subscription term adjustment', 80),
  ('price_adjustment_type', 'PRORATION', 'Proration', 90),
  ('price_adjustment_type', 'LINE_DISCOUNT', 'Line discount', 100),
  ('price_adjustment_type', 'QUOTE_DISCOUNT', 'Quote discount', 110),
  ('esign_status', 'CREATED', 'Created', 10),
  ('esign_status', 'SENT', 'Sent for signature', 20),
  ('esign_status', 'VIEWED', 'Viewed by signer', 30),
  ('esign_status', 'SIGNED', 'Signed', 40),
  ('esign_status', 'DECLINED', 'Declined', 50),
  ('esign_status', 'EXPIRED', 'Expired', 60),
  ('esign_status', 'FAILED', 'Send failed', 70)
) as seed(api_name, code, label, sort_order) on seed.api_name = vs.api_name
on conflict (tenant_id, value_set_id, code) do nothing;

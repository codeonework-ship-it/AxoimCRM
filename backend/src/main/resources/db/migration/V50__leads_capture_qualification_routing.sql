-- E05 — Lead capture, qualification and routing (FR-LED-001..012).
--
-- Everything configurable about a lead's life lives in the new `leads` schema:
-- the status model, the web-to-lead forms, the duplicate policy, the scoring
-- rules, the explainable predictive factors, the routing rules, the business
-- hours the response clock honours, the qualification framework and the
-- conversion field mapping. The lead record itself stays in `crm` (it is CRM
-- master data, E03/E04 territory by module ownership) and is extended here with
-- the columns the data model calls for in §4.3.
--
-- Two things are worth reading before changing this file:
--
--  1. `first_response_due_at` is a STORED column, not a view expression. The SLA
--     a lead was given must not move when an administrator later edits business
--     hours (data model §4.3, FR-LED-009).
--  2. Every RLS policy uses `nullif(current_setting('app.tenant_id', true), '')`
--     rather than a bare cast. SET LOCAL reverts the placeholder GUC to the
--     EMPTY STRING when the transaction ends, and `''::uuid` throws — see the
--     long note in V10 for the reproduction. The web-to-lead endpoint is the
--     one path here reachable with no tenant bound, so this is not academic.

create schema if not exists leads;

grant usage on schema leads to axiom_app;

-- search_path is appended to rather than restated: V50 lands after every other
-- module's migration, and restating the list from memory would silently drop a
-- schema a concurrently developed module added in V13..V49.
do $$
declare existing text;
begin
  select split_part(cfg, '=', 2) into existing
  from pg_roles r
  cross join unnest(coalesce(r.rolconfig, array[]::text[])) as cfg
  where r.rolname = 'axiom_app' and cfg like 'search%path=%';

  if existing is null then
    execute 'alter role axiom_app set search_path to leads, public';
  elsif existing not like '%leads%' then
    execute 'alter role axiom_app set search_path to ' || existing || ', leads';
  end if;
end $$;

-- ---------------------------------------------------------------------------
-- FR-LED-001 — configurable status model with defined terminal states
-- ---------------------------------------------------------------------------
create table leads.lead_status (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  code text not null,
  label text not null,
  category text not null check (category in ('OPEN', 'CONVERTED', 'DISQUALIFIED', 'RECYCLED')),
  sort_order int not null,
  active boolean not null default true,
  is_default boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, code),
  unique (tenant_id, sort_order),
  constraint lead_status_code_format check (code ~ '^[A-Z][A-Z0-9_]*$')
);

-- Exactly one entry state, and the three terminal categories are single-valued:
-- "converted" cannot be two different statuses, or "is this lead finished?" has
-- no answer a report can rely on.
create unique index uq_lead_status_default on leads.lead_status(tenant_id) where is_default;
create unique index uq_lead_status_terminal on leads.lead_status(tenant_id, category)
  where category <> 'OPEN';

-- ---------------------------------------------------------------------------
-- Queues (FR-LED-008 fall-through target)
-- ---------------------------------------------------------------------------
create table leads.lead_queue (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  code text not null,
  name text not null,
  is_fallback boolean not null default false,
  escalation_user_id uuid,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, code),
  constraint fk_lead_queue_escalation_same_tenant
    foreign key (tenant_id, escalation_user_id) references identity.app_user(tenant_id, id)
);

create unique index uq_lead_queue_fallback on leads.lead_queue(tenant_id) where is_fallback;

-- ---------------------------------------------------------------------------
-- FR-LED-009 — business hours the response clock honours, and the SLA policy
-- ---------------------------------------------------------------------------
create table leads.business_hours (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  code text not null,
  name text not null,
  time_zone text not null default 'UTC',
  is_default boolean not null default false,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, code)
);

create unique index uq_business_hours_default on leads.business_hours(tenant_id) where is_default;

create table leads.business_hours_day (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  business_hours_id uuid not null,
  day_of_week int not null check (day_of_week between 1 and 7), -- ISO-8601: 1 = Monday
  open_time time not null,
  close_time time not null,
  unique (tenant_id, business_hours_id, day_of_week),
  constraint business_hours_day_ordered check (close_time > open_time),
  constraint fk_business_hours_day_parent_same_tenant
    foreign key (tenant_id, business_hours_id) references leads.business_hours(tenant_id, id) on delete cascade
);

create table leads.business_hours_holiday (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  business_hours_id uuid not null,
  holiday_date date not null,
  name text not null,
  unique (tenant_id, business_hours_id, holiday_date),
  constraint fk_business_hours_holiday_parent_same_tenant
    foreign key (tenant_id, business_hours_id) references leads.business_hours(tenant_id, id) on delete cascade
);

create table leads.sla_policy (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  code text not null,
  name text not null,
  first_response_minutes int not null check (first_response_minutes > 0),
  business_hours_id uuid,
  escalation_user_id uuid,
  is_default boolean not null default false,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, code),
  constraint fk_sla_policy_hours_same_tenant
    foreign key (tenant_id, business_hours_id) references leads.business_hours(tenant_id, id),
  constraint fk_sla_policy_escalation_same_tenant
    foreign key (tenant_id, escalation_user_id) references identity.app_user(tenant_id, id)
);

create unique index uq_sla_policy_default on leads.sla_policy(tenant_id) where is_default;

-- An owner's own working pattern and their capacity ceiling. Absent a row the
-- tenant default business hours and an unlimited ceiling apply.
create table leads.owner_work_profile (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  user_id uuid not null,
  business_hours_id uuid,
  max_open_leads int check (max_open_leads is null or max_open_leads >= 0),
  updated_at timestamptz not null default now(),
  unique (tenant_id, user_id),
  constraint fk_owner_work_profile_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id),
  constraint fk_owner_work_profile_hours_same_tenant
    foreign key (tenant_id, business_hours_id) references leads.business_hours(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- FR-LED-008 — ordered assignment rules, round-robin pools, capacity
-- ---------------------------------------------------------------------------
create table leads.assignment_rule (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  name text not null,
  sort_order int not null,
  active boolean not null default true,
  match_territory text,
  match_segment text,
  match_product_interest text,
  match_source text,
  match_min_score int,
  assignment_mode text not null check (assignment_mode in ('USER', 'ROUND_ROBIN', 'QUEUE')),
  target_user_id uuid,
  target_queue_id uuid,
  sla_policy_id uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, name),
  unique (tenant_id, sort_order),
  constraint assignment_rule_target_present check (
    (assignment_mode = 'USER' and target_user_id is not null)
    or (assignment_mode = 'QUEUE' and target_queue_id is not null)
    or assignment_mode = 'ROUND_ROBIN'
  ),
  constraint fk_assignment_rule_user_same_tenant
    foreign key (tenant_id, target_user_id) references identity.app_user(tenant_id, id),
  constraint fk_assignment_rule_queue_same_tenant
    foreign key (tenant_id, target_queue_id) references leads.lead_queue(tenant_id, id),
  constraint fk_assignment_rule_sla_same_tenant
    foreign key (tenant_id, sla_policy_id) references leads.sla_policy(tenant_id, id)
);

create table leads.assignment_rule_member (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  rule_id uuid not null,
  user_id uuid not null,
  sort_order int not null,
  capacity int check (capacity is null or capacity >= 0),
  active boolean not null default true,
  unique (tenant_id, rule_id, user_id),
  unique (tenant_id, rule_id, sort_order),
  constraint fk_assignment_member_rule_same_tenant
    foreign key (tenant_id, rule_id) references leads.assignment_rule(tenant_id, id) on delete cascade,
  constraint fk_assignment_member_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

-- Round-robin position. Kept in its own row so the cursor advance is a single
-- UPDATE that a concurrent assignment serialises against, rather than a
-- read-modify-write of the rule row.
create table leads.assignment_cursor (
  tenant_id uuid not null references platform.tenant(id),
  rule_id uuid not null,
  last_position int not null default -1,
  updated_at timestamptz not null default now(),
  primary key (tenant_id, rule_id),
  constraint fk_assignment_cursor_rule_same_tenant
    foreign key (tenant_id, rule_id) references leads.assignment_rule(tenant_id, id) on delete cascade
);

-- ---------------------------------------------------------------------------
-- FR-LED-006 — rule-based scoring, and the stored breakdown that explains it
-- ---------------------------------------------------------------------------
create table leads.scoring_rule (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  name text not null,
  category text not null check (category in ('ATTRIBUTE', 'BEHAVIOUR')),
  field_key text not null,
  operator text not null check (operator in (
    'EQUALS', 'NOT_EQUALS', 'CONTAINS', 'IN', 'GTE', 'LTE', 'PRESENT', 'ABSENT', 'DOMAIN_NOT_IN')),
  comparison_value text,
  points int not null,
  sort_order int not null,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, name)
);

create table leads.lead_score_component (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  lead_id uuid not null,
  rule_id uuid,
  rule_name text not null,
  category text not null,
  points int not null,
  matched boolean not null,
  detail text not null,
  computed_at timestamptz not null default now(),
  constraint fk_score_component_lead_same_tenant
    foreign key (tenant_id, lead_id) references crm.lead(tenant_id, id) on delete cascade
);

create index idx_lead_score_component_lead on leads.lead_score_component(tenant_id, lead_id);

-- ---------------------------------------------------------------------------
-- FR-LED-007 — predictive conversion likelihood behind a provider port.
-- The shipped provider is a deterministic logistic model whose weights live
-- here, so the factors it reports are the factors it actually used.
-- ---------------------------------------------------------------------------
create table leads.predictive_model (
  tenant_id uuid primary key references platform.tenant(id),
  provider text not null default 'LOCAL_LOGISTIC',
  model_version text not null default 'v1',
  intercept numeric(8, 4) not null default 0,
  updated_at timestamptz not null default now()
);

create table leads.predictive_factor (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  factor_key text not null,
  label text not null,
  field_key text not null,
  operator text not null check (operator in (
    'EQUALS', 'NOT_EQUALS', 'CONTAINS', 'IN', 'GTE', 'LTE', 'PRESENT', 'ABSENT', 'DOMAIN_NOT_IN')),
  comparison_value text,
  weight numeric(8, 4) not null,
  sort_order int not null,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (tenant_id, factor_key)
);

create table leads.lead_prediction_factor (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  lead_id uuid not null,
  factor_key text not null,
  label text not null,
  observed_value text,
  contribution numeric(8, 4) not null,
  direction text not null check (direction in ('POSITIVE', 'NEGATIVE')),
  computed_at timestamptz not null default now(),
  constraint fk_prediction_factor_lead_same_tenant
    foreign key (tenant_id, lead_id) references crm.lead(tenant_id, id) on delete cascade
);

create index idx_lead_prediction_factor_lead on leads.lead_prediction_factor(tenant_id, lead_id);

-- ---------------------------------------------------------------------------
-- FR-LED-010 — qualification framework (BANT / CHAMP / custom)
-- ---------------------------------------------------------------------------
create table leads.qualification_framework (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  code text not null,
  name text not null,
  is_default boolean not null default false,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, code)
);

create unique index uq_qualification_framework_default
  on leads.qualification_framework(tenant_id) where is_default;

create table leads.qualification_field (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  framework_id uuid not null,
  field_key text not null,
  label text not null,
  field_type text not null check (field_type in ('TEXT', 'NUMBER', 'CURRENCY', 'DATE', 'BOOLEAN')),
  required boolean not null default false,
  sort_order int not null,
  -- The column on the resulting opportunity this answer lands in, so the rep
  -- does not retype what they already told us (FR-LED-010).
  opportunity_field text,
  unique (tenant_id, framework_id, field_key),
  constraint fk_qualification_field_parent_same_tenant
    foreign key (tenant_id, framework_id) references leads.qualification_framework(tenant_id, id) on delete cascade
);

-- ---------------------------------------------------------------------------
-- FR-LED-004 / FR-LED-005 — duplicate policy, and the review queue an
-- ambiguous match is routed to rather than guessed at
-- ---------------------------------------------------------------------------
create table leads.duplicate_policy (
  tenant_id uuid primary key references platform.tenant(id),
  behaviour text not null default 'ATTACH'
    check (behaviour in ('CREATE', 'MERGE', 'ATTACH', 'REVIEW')),
  match_email boolean not null default true,
  match_phone boolean not null default true,
  match_company_domain boolean not null default true,
  name_similarity_threshold numeric(4, 3) not null default 0.700
    check (name_similarity_threshold > 0 and name_similarity_threshold <= 1),
  review_confidence_floor numeric(4, 3) not null default 0.500
    check (review_confidence_floor > 0 and review_confidence_floor <= 1),
  updated_at timestamptz not null default now()
);

create table leads.duplicate_review (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  lead_id uuid not null,
  candidate_type text not null check (candidate_type in ('LEAD', 'CONTACT', 'ACCOUNT')),
  candidate_id uuid not null,
  candidate_label text not null,
  confidence numeric(4, 3) not null,
  basis text not null,
  status text not null default 'OPEN' check (status in ('OPEN', 'RESOLVED', 'DISMISSED')),
  resolution text,
  resolved_by uuid,
  resolved_at timestamptz,
  created_at timestamptz not null default now(),
  constraint fk_duplicate_review_lead_same_tenant
    foreign key (tenant_id, lead_id) references crm.lead(tenant_id, id) on delete cascade
);

create index idx_duplicate_review_open on leads.duplicate_review(tenant_id, created_at desc)
  where status = 'OPEN';

-- ---------------------------------------------------------------------------
-- FR-LED-002 — embeddable web-to-lead capture
-- ---------------------------------------------------------------------------
create table leads.capture_form (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  form_key text not null,
  name text not null,
  active boolean not null default true,
  bot_protection text not null default 'BOTH'
    check (bot_protection in ('NONE', 'HONEYPOT', 'TIMING', 'BOTH')),
  honeypot_field text not null default 'company_website_confirm',
  min_fill_seconds int not null default 2 check (min_fill_seconds >= 0),
  required_fields text[] not null default array['firstName', 'lastName', 'company', 'email'],
  field_map jsonb not null default '{}'::jsonb,
  default_source text not null default 'WEB_FORM',
  default_status text,
  default_campaign_code text,
  default_queue_id uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, form_key),
  constraint capture_form_key_format check (form_key ~ '^[a-z0-9][a-z0-9-]{2,63}$'),
  constraint fk_capture_form_queue_same_tenant
    foreign key (tenant_id, default_queue_id) references leads.lead_queue(tenant_id, id)
);

-- Public form-key -> tenant directory.
--
-- The web-to-lead endpoint is reachable with NO tenant bound (a form embedded on
-- a customer's marketing site has no Axiom session), so the tenant has to be
-- resolved from the form key before app.tenant_id can be set. That resolution
-- cannot itself be behind a policy that needs app.tenant_id. RLS therefore stays
-- ON with a policy that admits reads when no tenant is bound, and never admits a
-- WRITE without one — so an authenticated session still sees only its own rows
-- and can only ever create its own. The form key is a public token by design;
-- this table holds nothing else.
create table leads.capture_form_directory (
  form_key text primary key,
  tenant_id uuid not null references platform.tenant(id),
  active boolean not null default true,
  created_at timestamptz not null default now()
);

create table leads.capture_submission (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  form_key text not null,
  payload jsonb not null,
  accepted boolean not null,
  rejection_code text,
  rejection_message text,
  rejection_fields text[],
  lead_id uuid,
  remote_ip text,
  user_agent text,
  created_at timestamptz not null default now(),
  constraint capture_submission_rejection_explained check (accepted or rejection_message is not null),
  constraint fk_capture_submission_lead_same_tenant
    foreign key (tenant_id, lead_id) references crm.lead(tenant_id, id) on delete set null
);

create index idx_capture_submission_recent on leads.capture_submission(tenant_id, created_at desc);

-- ---------------------------------------------------------------------------
-- FR-LED-003 — bulk ingestion with per-record results. The rejected rows keep
-- their payload: "we lost your data but here is the error" is not acceptable.
-- ---------------------------------------------------------------------------
create table leads.ingestion_batch (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  source text not null check (source in ('API', 'BULK_API', 'WEB_FORM', 'MANUAL')),
  submitted_count int not null,
  accepted_count int not null,
  rejected_count int not null,
  created_by uuid,
  created_at timestamptz not null default now(),
  unique (tenant_id, id)
);

create table leads.ingestion_record (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  batch_id uuid not null,
  row_number int not null,
  status text not null check (status in ('CREATED', 'MERGED', 'ATTACHED', 'REVIEW', 'REJECTED')),
  lead_id uuid,
  message text not null,
  payload jsonb not null,
  created_at timestamptz not null default now(),
  unique (tenant_id, batch_id, row_number),
  constraint fk_ingestion_record_batch_same_tenant
    foreign key (tenant_id, batch_id) references leads.ingestion_batch(tenant_id, id) on delete cascade
);

-- ---------------------------------------------------------------------------
-- FR-LED-011 — administrator-configured conversion mapping, and the records of
-- what a conversion carried across
-- ---------------------------------------------------------------------------
create table leads.conversion_mapping (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  target_entity text not null check (target_entity in ('ACCOUNT', 'CONTACT', 'OPPORTUNITY')),
  -- `lead:<column>`, `qual:<fieldKey>` or `custom:<fieldKey>`
  source_expression text not null,
  target_field text not null,
  -- A custom target is stored in leads.converted_custom_field rather than as a
  -- column on another module's table. See the note on that table.
  custom_field boolean not null default false,
  sort_order int not null,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (tenant_id, target_entity, target_field)
);

-- Custom-field values a conversion produced.
--
-- crm.account, crm.contact and sales.opportunity belong to E04/E06 and have no
-- extension column. Rather than add one to a table this epic does not own — and
-- collide with whatever those epics decide — mapped custom fields are stored
-- here, keyed by target entity and id, and served alongside the record.
create table leads.converted_custom_field (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  lead_id uuid not null,
  target_entity text not null check (target_entity in ('ACCOUNT', 'CONTACT', 'OPPORTUNITY')),
  target_id uuid not null,
  field_key text not null,
  field_value text,
  created_at timestamptz not null default now(),
  unique (tenant_id, target_entity, target_id, field_key),
  constraint fk_converted_custom_field_lead_same_tenant
    foreign key (tenant_id, lead_id) references crm.lead(tenant_id, id) on delete cascade
);

-- Campaign membership carried off a converted lead. The campaign module (E11)
-- does not exist yet, so membership is held as a campaign code here and will be
-- reconciled to CAMPAIGN_MEMBER when E11 lands. Recorded rather than dropped:
-- attribution that is silently lost at conversion is unrecoverable.
create table leads.converted_campaign_membership (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  lead_id uuid not null,
  campaign_code text not null,
  account_id uuid,
  contact_id uuid,
  opportunity_id uuid,
  created_at timestamptz not null default now(),
  constraint fk_converted_campaign_lead_same_tenant
    foreign key (tenant_id, lead_id) references crm.lead(tenant_id, id) on delete cascade
);

create table leads.conversion_transfer (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  lead_id uuid not null,
  item_type text not null check (item_type in ('ACTIVITY', 'NOTE', 'CAMPAIGN_MEMBERSHIP', 'CUSTOM_FIELD')),
  item_count int not null,
  target_entity text not null,
  target_id uuid,
  created_at timestamptz not null default now(),
  constraint fk_conversion_transfer_lead_same_tenant
    foreign key (tenant_id, lead_id) references crm.lead(tenant_id, id) on delete cascade
);

-- FR-LED-009 — breach evidence, so a breach is reportable and an escalation is
-- provably fired at most once per lead.
create table leads.sla_breach (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  lead_id uuid not null,
  due_at timestamptz not null,
  breached_at timestamptz not null default now(),
  escalated_to_user_id uuid,
  escalated_at timestamptz,
  minutes_over int not null default 0,
  unique (tenant_id, lead_id),
  constraint fk_sla_breach_lead_same_tenant
    foreign key (tenant_id, lead_id) references crm.lead(tenant_id, id) on delete cascade,
  constraint fk_sla_breach_escalation_same_tenant
    foreign key (tenant_id, escalated_to_user_id) references identity.app_user(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- crm.lead — the columns the data model §4.3 calls for
-- ---------------------------------------------------------------------------
alter table crm.lead
  add column if not exists title text,
  add column if not exists phone text,
  add column if not exists rating text,
  add column if not exists source text,
  add column if not exists campaign_code text,
  add column if not exists territory text,
  add column if not exists segment text,
  add column if not exists product_interest text,
  add column if not exists queue_id uuid,
  add column if not exists assignment_rule_id uuid,
  add column if not exists assignment_rule_name text,
  add column if not exists assigned_at timestamptz,
  add column if not exists sla_policy_id uuid,
  add column if not exists first_response_due_at timestamptz,
  add column if not exists first_responded_at timestamptz,
  add column if not exists sla_breached_at timestamptz,
  add column if not exists sla_escalated_at timestamptz,
  add column if not exists score int not null default 0,
  add column if not exists score_computed_at timestamptz,
  add column if not exists predicted_conversion numeric(5, 4),
  add column if not exists prediction_computed_at timestamptz,
  add column if not exists matched_account_id uuid,
  add column if not exists match_confidence numeric(4, 3),
  add column if not exists match_basis text,
  add column if not exists match_confirmed_at timestamptz,
  add column if not exists qualification_framework_id uuid,
  add column if not exists qualification_data jsonb not null default '{}'::jsonb,
  add column if not exists custom_fields jsonb not null default '{}'::jsonb,
  add column if not exists converted_at timestamptz,
  add column if not exists disqualification_reason_code text,
  add column if not exists disqualified_at timestamptz,
  add column if not exists recycle_date date,
  add column if not exists recycled_at timestamptz,
  add column if not exists duplicate_disposition text,
  add column if not exists duplicate_of_lead_id uuid,
  add column if not exists attached_contact_id uuid,
  add column if not exists attached_account_id uuid,
  add column if not exists capture_source text not null default 'MANUAL',
  add column if not exists ingestion_batch_id uuid,
  add column if not exists updated_at timestamptz not null default now();

-- The status list is administrator-configurable (FR-LED-001), so a hard-coded
-- CHECK is the wrong enforcement point — it would make "configurable" a lie.
-- The governed list is leads.lead_status and LeadStatusModel validates against
-- it, refusing any code the tenant has not defined. A foreign key was
-- considered and rejected: it would fail lead creation in a tenant provisioned
-- before its status model is seeded, turning a config gap into an outage.
alter table crm.lead drop constraint if exists lead_status_check;

alter table crm.lead
  add constraint fk_lead_queue_same_tenant
    foreign key (tenant_id, queue_id) references leads.lead_queue(tenant_id, id),
  add constraint fk_lead_assignment_rule_same_tenant
    foreign key (tenant_id, assignment_rule_id) references leads.assignment_rule(tenant_id, id),
  add constraint fk_lead_sla_policy_same_tenant
    foreign key (tenant_id, sla_policy_id) references leads.sla_policy(tenant_id, id),
  add constraint fk_lead_matched_account_same_tenant
    foreign key (tenant_id, matched_account_id) references crm.account(tenant_id, id),
  add constraint fk_lead_attached_account_same_tenant
    foreign key (tenant_id, attached_account_id) references crm.account(tenant_id, id),
  add constraint fk_lead_attached_contact_same_tenant
    foreign key (tenant_id, attached_contact_id) references crm.contact(tenant_id, id),
  add constraint fk_lead_duplicate_of_same_tenant
    foreign key (tenant_id, duplicate_of_lead_id) references crm.lead(tenant_id, id),
  add constraint fk_lead_qualification_framework_same_tenant
    foreign key (tenant_id, qualification_framework_id)
      references leads.qualification_framework(tenant_id, id),
  add constraint lead_conversion_complete check (
    converted_at is null or (converted_account_id is not null and converted_contact_id is not null)
  ),
  add constraint lead_duplicate_disposition_values check (
    duplicate_disposition is null
    or duplicate_disposition in ('CREATED', 'MERGED', 'ATTACHED', 'REVIEW')
  ),
  add constraint lead_capture_source_values check (
    capture_source in ('MANUAL', 'API', 'BULK_API', 'WEB_FORM', 'IMPORT')
  );

create index if not exists idx_lead_response_due on crm.lead(tenant_id, first_response_due_at)
  where deleted_at is null and first_responded_at is null and first_response_due_at is not null;
create index if not exists idx_lead_queue_work on crm.lead(tenant_id, score desc, created_at desc)
  where deleted_at is null;
create index if not exists idx_lead_recycle on crm.lead(tenant_id, recycle_date)
  where deleted_at is null and recycle_date is not null;
create index if not exists idx_lead_owner_open on crm.lead(tenant_id, owner_id)
  where deleted_at is null and converted_at is null and disqualified_at is null;

-- ---------------------------------------------------------------------------
-- Row-level security on every tenant-scoped table in the schema.
--
-- Applied by iterating the catalogue rather than by twenty-odd hand-written
-- policy statements: a table added to this migration later cannot be forgotten,
-- which is the one mistake in this file that would be a cross-tenant leak.
-- ---------------------------------------------------------------------------
do $$
declare t record;
begin
  for t in
    select c.relname
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'leads' and c.relkind = 'r'
    order by c.relname
  loop
    execute format('alter table leads.%I enable row level security', t.relname);
    execute format('alter table leads.%I force row level security', t.relname);
    execute format(
      'create policy tenant_isolation on leads.%I '
      || 'using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      || 'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
      t.relname);
  end loop;
end $$;

-- The public form directory is the documented exception: readable with no
-- tenant bound, writable only with one.
drop policy tenant_isolation on leads.capture_form_directory;
create policy tenant_isolation on leads.capture_form_directory
  using (nullif(current_setting('app.tenant_id', true), '') is null
         or tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

grant select, insert, update, delete on all tables in schema leads to axiom_app;

-- ---------------------------------------------------------------------------
-- Governance catalogues
-- ---------------------------------------------------------------------------
insert into governance.module_catalog(module_code, schema_name, display_name, description, owner_role) values
  ('LEADS', 'leads', 'Lead operations',
   'Lead capture, duplicate policy, scoring, routing, response SLA, qualification and conversion configuration.',
   'MARKETING')
on conflict (module_code) do nothing;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('leads','lead_status','LEADS','id',true,'ACTIVE'),
  ('leads','lead_queue','LEADS','id',true,'ACTIVE'),
  ('leads','business_hours','LEADS','id',true,'ACTIVE'),
  ('leads','business_hours_day','LEADS','id',true,'ACTIVE'),
  ('leads','business_hours_holiday','LEADS','id',true,'ACTIVE'),
  ('leads','sla_policy','LEADS','id',true,'ACTIVE'),
  ('leads','owner_work_profile','LEADS','id',true,'ACTIVE'),
  ('leads','assignment_rule','LEADS','id',true,'ACTIVE'),
  ('leads','assignment_rule_member','LEADS','id',true,'ACTIVE'),
  ('leads','assignment_cursor','LEADS','rule_id',true,'ACTIVE'),
  ('leads','scoring_rule','LEADS','id',true,'ACTIVE'),
  ('leads','lead_score_component','LEADS','id',true,'APPEND_ONLY'),
  ('leads','predictive_model','LEADS','tenant_id',true,'ACTIVE'),
  ('leads','predictive_factor','LEADS','id',true,'ACTIVE'),
  ('leads','lead_prediction_factor','LEADS','id',true,'APPEND_ONLY'),
  ('leads','qualification_framework','LEADS','id',true,'ACTIVE'),
  ('leads','qualification_field','LEADS','id',true,'ACTIVE'),
  ('leads','duplicate_policy','LEADS','tenant_id',true,'ACTIVE'),
  ('leads','duplicate_review','LEADS','id',true,'ACTIVE'),
  ('leads','capture_form','LEADS','id',true,'ACTIVE'),
  ('leads','capture_form_directory','LEADS','form_key',true,'ACTIVE'),
  ('leads','capture_submission','LEADS','id',true,'APPEND_ONLY'),
  ('leads','ingestion_batch','LEADS','id',true,'APPEND_ONLY'),
  ('leads','ingestion_record','LEADS','id',true,'APPEND_ONLY'),
  ('leads','conversion_mapping','LEADS','id',true,'ACTIVE'),
  ('leads','converted_custom_field','LEADS','id',true,'ACTIVE'),
  ('leads','converted_campaign_membership','LEADS','id',true,'APPEND_ONLY'),
  ('leads','conversion_transfer','LEADS','id',true,'APPEND_ONLY'),
  ('leads','sla_breach','LEADS','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

-- ---------------------------------------------------------------------------
-- Governed reference data: the disqualification taxonomy (FR-LED-012), lead
-- source and rating. These belong in the reference module so a data steward
-- governs them with the same lifecycle controls as every other value set.
-- ---------------------------------------------------------------------------
insert into reference.value_set (tenant_id, api_name, label, module, description)
select t.id, seed.api_name, seed.label, seed.module, seed.description
from platform.tenant t
cross join (values
  ('lead_disqualification_reason', 'Lead disqualification reason', 'CRM',
   'Governed reasons a lead may be disqualified. A disqualification without one is refused.'),
  ('lead_source', 'Lead source', 'CRM', 'Where a lead came from'),
  ('lead_rating', 'Lead rating', 'CRM', 'Qualitative lead rating')
) as seed(api_name, label, module, description)
on conflict (tenant_id, api_name) do nothing;

insert into reference.value_set_entry (tenant_id, value_set_id, code, label, sort_order, system_managed)
select vs.tenant_id, vs.id, seed.code, seed.label, seed.sort_order, true
from reference.value_set vs
join (values
  ('lead_disqualification_reason', 'NO_BUDGET', 'No budget', 10),
  ('lead_disqualification_reason', 'NO_AUTHORITY', 'No buying authority', 20),
  ('lead_disqualification_reason', 'NOT_A_FIT', 'Not a fit for our products', 30),
  ('lead_disqualification_reason', 'TIMING', 'Wrong timing', 40),
  ('lead_disqualification_reason', 'LOST_TO_COMPETITOR', 'Chose a competitor', 50),
  ('lead_disqualification_reason', 'DUPLICATE', 'Duplicate of an existing record', 60),
  ('lead_disqualification_reason', 'UNRESPONSIVE', 'Unresponsive after repeated contact', 70),
  ('lead_disqualification_reason', 'DO_NOT_CONTACT', 'Asked not to be contacted', 80),
  ('lead_source', 'WEB_FORM', 'Website form', 10),
  ('lead_source', 'WEBINAR', 'Webinar', 20),
  ('lead_source', 'TRADE_SHOW', 'Trade show', 30),
  ('lead_source', 'PARTNER_REFERRAL', 'Partner referral', 40),
  ('lead_source', 'OUTBOUND', 'Outbound prospecting', 50),
  ('lead_source', 'API', 'System integration', 60),
  ('lead_rating', 'HOT', 'Hot', 10),
  ('lead_rating', 'WARM', 'Warm', 20),
  ('lead_rating', 'COLD', 'Cold', 30)
) as seed(api_name, code, label, sort_order) on seed.api_name = vs.api_name
on conflict (tenant_id, value_set_id, code) do nothing;

-- Extend the existing lead_status value set with the new working states so the
-- reference-data screen and the routing model agree.
insert into reference.value_set_entry (tenant_id, value_set_id, code, label, sort_order, system_managed)
select vs.tenant_id, vs.id, seed.code, seed.label, seed.sort_order, true
from reference.value_set vs
join (values
  ('WORKING', 'Working', 12),
  ('NURTURING', 'Nurturing', 14),
  ('REVIEW', 'Duplicate review', 16),
  ('RECYCLED', 'Recycled to nurture', 50)
) as seed(code, label, sort_order) on true
where vs.api_name = 'lead_status'
on conflict (tenant_id, value_set_id, code) do nothing;

-- ---------------------------------------------------------------------------
-- Per-tenant defaults. LeadConfigService re-runs the equivalent of this block
-- lazily for tenants provisioned after this migration, so the two must stay in
-- step — see LeadConfigService.ensureTenantDefaults.
-- ---------------------------------------------------------------------------
insert into leads.lead_status (tenant_id, code, label, category, sort_order, is_default)
select t.id, s.code, s.label, s.category, s.sort_order, s.is_default
from platform.tenant t
cross join (values
  ('NEW', 'New', 'OPEN', 10, true),
  ('WORKING', 'Working', 'OPEN', 20, false),
  ('NURTURING', 'Nurturing', 'OPEN', 30, false),
  ('REVIEW', 'Duplicate review', 'OPEN', 40, false),
  ('QUALIFIED', 'Qualified', 'OPEN', 50, false),
  ('CONVERTED', 'Converted', 'CONVERTED', 60, false),
  ('DISQUALIFIED', 'Disqualified', 'DISQUALIFIED', 70, false),
  ('RECYCLED', 'Recycled to nurture', 'RECYCLED', 80, false)
) as s(code, label, category, sort_order, is_default);

insert into leads.lead_queue (tenant_id, code, name, is_fallback, escalation_user_id)
select t.id, 'UNROUTED', 'Unrouted lead queue', true,
       (select u.id from identity.app_user u
        where u.tenant_id = t.id and u.role = 'TENANT_ADMIN' order by u.email limit 1)
from platform.tenant t;

insert into leads.business_hours (tenant_id, code, name, time_zone, is_default)
select t.id, 'STANDARD', 'Standard selling hours', 'Asia/Kolkata', true
from platform.tenant t;

insert into leads.business_hours_day (tenant_id, business_hours_id, day_of_week, open_time, close_time)
select bh.tenant_id, bh.id, d.dow, time '09:00', time '18:00'
from leads.business_hours bh
cross join (values (1), (2), (3), (4), (5)) as d(dow)
where bh.code = 'STANDARD';

insert into leads.business_hours_holiday (tenant_id, business_hours_id, holiday_date, name)
select bh.tenant_id, bh.id, h.d, h.label
from leads.business_hours bh
cross join (values
  (date '2026-08-15', 'Independence Day'),
  (date '2026-10-02', 'Gandhi Jayanti'),
  (date '2026-12-25', 'Christmas Day')
) as h(d, label)
where bh.code = 'STANDARD';

insert into leads.sla_policy (tenant_id, code, name, first_response_minutes, business_hours_id,
                              escalation_user_id, is_default)
select t.id, 'FIRST_RESPONSE', 'Speed to lead — first response', 120,
       (select bh.id from leads.business_hours bh where bh.tenant_id = t.id and bh.code = 'STANDARD'),
       (select u.id from identity.app_user u
        where u.tenant_id = t.id and u.role = 'TENANT_ADMIN' order by u.email limit 1),
       true
from platform.tenant t;

insert into leads.duplicate_policy (tenant_id) select id from platform.tenant;

insert into leads.predictive_model (tenant_id, provider, model_version, intercept)
select id, 'LOCAL_LOGISTIC', 'v1', -1.4000 from platform.tenant;

insert into leads.predictive_factor (tenant_id, factor_key, label, field_key, operator,
                                     comparison_value, weight, sort_order)
select t.id, f.factor_key, f.label, f.field_key, f.operator, f.comparison_value, f.weight, f.sort_order
from platform.tenant t
cross join (values
  ('BUSINESS_EMAIL', 'Business (non free-mail) email address', 'emailDomain', 'DOMAIN_NOT_IN',
   'gmail.com,yahoo.com,hotmail.com,outlook.com,proton.me', 0.9000, 10),
  ('SENIOR_TITLE', 'Senior decision-maker job title', 'title', 'IN',
   'director,vp,vice president,head,chief,cxo,coo,cto,ceo,cfo', 1.1000, 20),
  ('PRODUCT_INTEREST', 'Stated product interest', 'productInterest', 'PRESENT', null, 0.6000, 30),
  ('HIGH_INTENT_SOURCE', 'High-intent capture source', 'source', 'IN',
   'WEBINAR,TRADE_SHOW,PARTNER_REFERRAL', 0.8000, 40),
  ('ENGAGED', 'Two or more logged engagements', 'activityCount', 'GTE', '2', 1.0000, 50),
  ('BUDGET_CONFIRMED', 'Budget captured during qualification', 'qual:budget', 'PRESENT', null, 1.2000, 60),
  ('NO_PHONE', 'No telephone number supplied', 'phone', 'ABSENT', null, -0.7000, 70),
  ('FREE_MAIL', 'Free-mail address only', 'emailDomain', 'IN',
   'gmail.com,yahoo.com,hotmail.com,outlook.com,proton.me', -0.8000, 80)
) as f(factor_key, label, field_key, operator, comparison_value, weight, sort_order);

insert into leads.scoring_rule (tenant_id, name, category, field_key, operator, comparison_value,
                                points, sort_order)
select t.id, r.name, r.category, r.field_key, r.operator, r.comparison_value, r.points, r.sort_order
from platform.tenant t
cross join (values
  ('Senior job title', 'ATTRIBUTE', 'title', 'IN',
   'director,vp,vice president,head,chief,coo,cto,ceo,cfo', 20, 10),
  ('Business email domain', 'ATTRIBUTE', 'emailDomain', 'DOMAIN_NOT_IN',
   'gmail.com,yahoo.com,hotmail.com,outlook.com,proton.me', 15, 20),
  ('Telephone number supplied', 'ATTRIBUTE', 'phone', 'PRESENT', null, 5, 30),
  ('Product interest stated', 'ATTRIBUTE', 'productInterest', 'PRESENT', null, 10, 40),
  ('High-intent source', 'ATTRIBUTE', 'source', 'IN', 'WEBINAR,TRADE_SHOW,PARTNER_REFERRAL', 15, 50),
  ('Enterprise segment', 'ATTRIBUTE', 'segment', 'EQUALS', 'ENTERPRISE', 10, 60),
  ('Two or more logged engagements', 'BEHAVIOUR', 'activityCount', 'GTE', '2', 15, 70),
  ('Budget confirmed', 'BEHAVIOUR', 'qual:budget', 'PRESENT', null, 10, 80)
) as r(name, category, field_key, operator, comparison_value, points, sort_order);

insert into leads.qualification_framework (tenant_id, code, name, is_default)
select id, 'BANT', 'BANT — budget, authority, need, timeline', true from platform.tenant;

insert into leads.qualification_field (tenant_id, framework_id, field_key, label, field_type,
                                       required, sort_order, opportunity_field)
select qf.tenant_id, qf.id, f.field_key, f.label, f.field_type, f.required, f.sort_order, f.opportunity_field
from leads.qualification_framework qf
cross join (values
  ('budget', 'Confirmed budget', 'CURRENCY', true, 10, 'amount'),
  ('authority', 'Decision maker and buying process', 'TEXT', true, 20, null),
  ('need', 'Business need in the buyer''s words', 'TEXT', true, 30, null),
  ('timeline', 'Expected decision date', 'DATE', false, 40, 'close_date')
) as f(field_key, label, field_type, required, sort_order, opportunity_field)
where qf.code = 'BANT';

insert into leads.conversion_mapping (tenant_id, target_entity, source_expression, target_field,
                                      custom_field, sort_order)
select t.id, m.target_entity, m.source_expression, m.target_field, m.custom_field, m.sort_order
from platform.tenant t
cross join (values
  ('ACCOUNT', 'lead:company', 'name', false, 10),
  ('ACCOUNT', 'lead:segment', 'industry', false, 20),
  ('ACCOUNT', 'lead:territory', 'territory', true, 30),
  ('CONTACT', 'lead:first_name', 'first_name', false, 10),
  ('CONTACT', 'lead:last_name', 'last_name', false, 20),
  ('CONTACT', 'lead:email', 'email', false, 30),
  ('CONTACT', 'lead:title', 'title', false, 40),
  ('CONTACT', 'lead:phone', 'phone', true, 50),
  ('OPPORTUNITY', 'qual:budget', 'amount', false, 10),
  ('OPPORTUNITY', 'qual:timeline', 'close_date', false, 20),
  ('OPPORTUNITY', 'lead:product_interest', 'product_interest', true, 30),
  ('OPPORTUNITY', 'qual:authority', 'buying_authority', true, 40),
  ('OPPORTUNITY', 'qual:need', 'business_need', true, 50)
) as m(target_entity, source_expression, target_field, custom_field, sort_order);

-- Demo assignment rules: an ordered set whose first match wins, ending in a
-- round-robin over the sales team with per-owner capacity, and — when nothing
-- matches — the fallback queue rather than an unassigned lead.
insert into leads.assignment_rule (tenant_id, name, sort_order, match_territory, match_segment,
                                   match_product_interest, match_source, match_min_score,
                                   assignment_mode, target_user_id, target_queue_id, sla_policy_id)
select t.id, 'Enterprise West — named owner', 10, 'WEST', 'ENTERPRISE', null, null, null,
       'USER',
       (select u.id from identity.app_user u where u.tenant_id = t.id and u.role = 'SALES'
        order by u.email limit 1),
       null,
       (select p.id from leads.sla_policy p where p.tenant_id = t.id and p.code = 'FIRST_RESPONSE')
from platform.tenant t
where exists (select 1 from identity.app_user u where u.tenant_id = t.id and u.role = 'SALES');

insert into leads.assignment_rule (tenant_id, name, sort_order, match_territory, match_segment,
                                   match_product_interest, match_source, match_min_score,
                                   assignment_mode, target_user_id, target_queue_id, sla_policy_id)
select t.id, 'Inside sales round robin', 20, null, null, null, null, null,
       'ROUND_ROBIN', null, null,
       (select p.id from leads.sla_policy p where p.tenant_id = t.id and p.code = 'FIRST_RESPONSE')
from platform.tenant t
where exists (select 1 from identity.app_user u where u.tenant_id = t.id and u.role = 'SALES');

insert into leads.assignment_rule_member (tenant_id, rule_id, user_id, sort_order, capacity)
select r.tenant_id, r.id, m.id, m.rn, 25
from leads.assignment_rule r
join (
  select u.id, u.tenant_id, row_number() over (partition by u.tenant_id order by u.email) as rn
  from identity.app_user u
  where u.role = 'SALES' and u.active
) m on m.tenant_id = r.tenant_id
where r.name = 'Inside sales round robin';

insert into leads.assignment_cursor (tenant_id, rule_id)
select tenant_id, id from leads.assignment_rule where assignment_mode = 'ROUND_ROBIN';

insert into leads.capture_form (tenant_id, form_key, name, required_fields, field_map,
                                default_source, default_status, default_campaign_code, default_queue_id)
select t.id, t.slug || '-contact-us', 'Contact us',
       array['firstName', 'lastName', 'company', 'email'],
       jsonb_build_object(
         'fname', 'firstName', 'lname', 'lastName', 'org', 'company',
         'work_email', 'email', 'tel', 'phone', 'role', 'title',
         'interest', 'productInterest', 'region', 'territory', 'notes', 'notes'),
       'WEB_FORM', 'NEW', 'WEB-INBOUND-2026',
       (select q.id from leads.lead_queue q where q.tenant_id = t.id and q.code = 'UNROUTED')
from platform.tenant t;

insert into leads.capture_form_directory (form_key, tenant_id, active)
select form_key, tenant_id, active from leads.capture_form;

-- Existing demo leads predate the routing model; give them a capture source and
-- a score baseline so the queue screen is not half-empty on first open.
update crm.lead set capture_source = 'MANUAL' where capture_source is null;

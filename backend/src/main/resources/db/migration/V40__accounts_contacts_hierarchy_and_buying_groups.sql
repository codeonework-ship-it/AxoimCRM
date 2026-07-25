-- E04 — Accounts, contacts, hierarchy and buying groups (FR-ACC-001..014).
--
-- Everything here lands in the existing `crm` schema, so the runtime
-- search_path set in V6 (`alter role axiom_app set search_path to platform,
-- identity, crm, sales, engagement, governance, reference, integration,
-- public`) is already correct. It is deliberately NOT re-asserted: that
-- statement replaces the whole list, and re-issuing a stale copy would silently
-- drop a schema another migration added after V6.
--
-- RLS NOTE (load bearing): TenantSessionAspect binds app.tenant_id with
-- SET LOCAL. When the transaction ends — or when a statement runs before the
-- aspect has bound it — the GUC reverts to the EMPTY STRING, not NULL, and a
-- bare ''::uuid cast raises 22P02 instead of filtering. Every policy below
-- therefore casts through nullif(current_setting('app.tenant_id', true), '').
--
-- ACTOR NOTE: app.actor_id is bound the same way (SET LOCAL) by
-- com.axiom.accounts.ActorSession, so the field-history trigger can attribute a
-- change without every writer having to remember to pass an actor.

-- ---------------------------------------------------------------- 1. account
-- FR-ACC-001 (record), FR-ACC-003 (hierarchy), FR-ACC-014 (health).
alter table crm.account
  add column if not exists legal_name          text,
  add column if not exists account_number      text,
  add column if not exists record_type         text not null default 'STANDARD',
  add column if not exists parent_account_id   uuid,
  add column if not exists ultimate_parent_id  uuid,
  add column if not exists hierarchy_path      text not null default '',
  add column if not exists hierarchy_depth     int  not null default 0,
  add column if not exists business_unit       text,
  add column if not exists territory           text,
  add column if not exists segment             text,
  add column if not exists employee_count      int,
  add column if not exists annual_revenue      numeric(18,2),
  add column if not exists currency_code       text not null default 'INR',
  add column if not exists website             text,
  add column if not exists email_domain        text,
  add column if not exists phone               text,
  add column if not exists status              text not null default 'ACTIVE',
  add column if not exists health_score        int,
  add column if not exists health_band         text,
  add column if not exists health_computed_at  timestamptz,
  add column if not exists source_system       text,
  add column if not exists external_ref        text,
  add column if not exists merged_into_id      uuid,
  add column if not exists created_by          uuid,
  add column if not exists updated_by          uuid;

alter table crm.account
  add constraint account_record_type_known
    check (record_type in ('STANDARD','PROSPECT','CUSTOMER','PARTNER','COMPETITOR','SUBSIDIARY')),
  add constraint account_status_known
    check (status in ('ACTIVE','INACTIVE','MERGED')),
  add constraint account_health_band_known
    check (health_band is null or health_band in ('STRONG','STEADY','WATCH','AT_RISK','CRITICAL')),
  add constraint account_health_score_range
    check (health_score is null or (health_score between 0 and 100)),
  add constraint account_employee_count_sane
    check (employee_count is null or employee_count >= 0),
  add constraint account_currency_code_format
    check (currency_code ~ '^[A-Z]{3}$'),
  -- FR-ACC-003: the cheap half of cycle prevention, declared.
  add constraint account_not_own_parent
    check (parent_account_id is null or parent_account_id <> id),
  add constraint fk_account_parent_same_tenant
    foreign key (tenant_id, parent_account_id) references crm.account(tenant_id, id),
  add constraint fk_account_ultimate_parent_same_tenant
    foreign key (tenant_id, ultimate_parent_id) references crm.account(tenant_id, id),
  add constraint fk_account_merged_into_same_tenant
    foreign key (tenant_id, merged_into_id) references crm.account(tenant_id, id);

-- Backfill the materialized path for rows that predate it (every existing
-- account is a root until somebody assigns a parent).
update crm.account set hierarchy_path = '/' || id::text || '/', hierarchy_depth = 0,
       ultimate_parent_id = id
 where hierarchy_path = '';

-- FR-ACC-003 declarative half #2: the path must terminate in the row's own id.
-- A tampered path that loses its own leaf can no longer pass a write.
alter table crm.account
  add constraint account_path_ends_with_self
    check (hierarchy_path like '%/' || id::text || '/');

create index if not exists idx_account_hierarchy_path on crm.account(tenant_id, hierarchy_path)
  where deleted_at is null;
create index if not exists idx_account_parent on crm.account(tenant_id, parent_account_id)
  where deleted_at is null;
create index if not exists idx_account_ultimate_parent on crm.account(tenant_id, ultimate_parent_id)
  where deleted_at is null;
create index if not exists idx_account_domain on crm.account(tenant_id, lower(email_domain))
  where deleted_at is null and email_domain is not null;

-- ---------------------------------------------------------------- 2. contact
-- FR-ACC-002.
alter table crm.contact
  add column if not exists department            text,
  add column if not exists seniority             text,
  add column if not exists reports_to_contact_id uuid,
  add column if not exists owner_id              uuid,
  add column if not exists phone                 text,
  add column if not exists mobile                text,
  add column if not exists status                text not null default 'ACTIVE',
  add column if not exists email_bounced         boolean not null default false,
  add column if not exists last_engaged_at       timestamptz,
  add column if not exists source_system         text,
  add column if not exists external_ref          text,
  add column if not exists merged_into_id        uuid,
  add column if not exists created_by            uuid,
  add column if not exists updated_by            uuid,
  add column if not exists updated_at            timestamptz not null default now(),
  add column if not exists version               bigint not null default 0;

alter table crm.contact
  add constraint contact_status_known
    check (status in ('ACTIVE','INACTIVE','LEFT_COMPANY','MERGED')),
  add constraint contact_seniority_known
    check (seniority is null or seniority in ('C_LEVEL','VP','DIRECTOR','MANAGER','INDIVIDUAL_CONTRIBUTOR','OTHER')),
  add constraint contact_not_own_manager
    check (reports_to_contact_id is null or reports_to_contact_id <> id),
  add constraint fk_contact_reports_to_same_tenant
    foreign key (tenant_id, reports_to_contact_id) references crm.contact(tenant_id, id),
  add constraint fk_contact_owner_same_tenant
    foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id),
  add constraint fk_contact_merged_into_same_tenant
    foreign key (tenant_id, merged_into_id) references crm.contact(tenant_id, id);

create index if not exists idx_contact_reports_to on crm.contact(tenant_id, reports_to_contact_id)
  where deleted_at is null;
create index if not exists idx_contact_email_lookup on crm.contact(tenant_id, lower(email))
  where deleted_at is null and email is not null;

-- ------------------------------------------------- 3. typed addresses/channels
-- FR-ACC-002: "multiple typed addresses, multiple typed communication channels,
-- and the primary of each type is unambiguous" — the partial unique indexes are
-- what make "unambiguous" a fact rather than a hope.
create table crm.postal_address (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  owner_entity   text not null check (owner_entity in ('ACCOUNT','CONTACT')),
  owner_id       uuid not null,
  address_type   text not null check (address_type in ('BILLING','SHIPPING','REGISTERED','MAILING','SITE','OTHER')),
  is_primary     boolean not null default false,
  line1          text not null,
  line2          text,
  city           text,
  state_region   text,
  postal_code    text,
  country_code   text check (country_code is null or country_code ~ '^[A-Z]{2}$'),
  latitude       numeric(9,6),
  longitude      numeric(9,6),
  validation_status text not null default 'UNVERIFIED'
    check (validation_status in ('UNVERIFIED','VERIFIED','FAILED')),
  created_at     timestamptz not null default now(),
  created_by     uuid,
  updated_at     timestamptz not null default now(),
  deleted_at     timestamptz,
  unique (tenant_id, id)
);
create unique index uq_address_primary_per_type
  on crm.postal_address(tenant_id, owner_entity, owner_id, address_type)
  where is_primary and deleted_at is null;

create table crm.contact_channel (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  contact_id   uuid not null,
  channel      text not null check (channel in ('EMAIL','PHONE','MOBILE','SMS','WHATSAPP','LINKEDIN','POST')),
  channel_type text not null check (channel_type in ('WORK','PERSONAL','SWITCHBOARD','ASSISTANT','OTHER')),
  value        text not null,
  is_primary   boolean not null default false,
  verified_at  timestamptz,
  created_at   timestamptz not null default now(),
  deleted_at   timestamptz,
  unique (tenant_id, id),
  constraint fk_contact_channel_contact_same_tenant
    foreign key (tenant_id, contact_id) references crm.contact(tenant_id, id)
);
create unique index uq_channel_primary_per_kind
  on crm.contact_channel(tenant_id, contact_id, channel)
  where is_primary and deleted_at is null;
create index idx_contact_channel_value on crm.contact_channel(tenant_id, channel, lower(value))
  where deleted_at is null;

-- ------------------------------------------- 4. multi-account relationships
-- FR-ACC-005.
create table crm.account_contact_relation (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  contact_id      uuid not null,
  account_id      uuid not null,
  role            text not null,
  influence_level text not null default 'MEDIUM'
    check (influence_level in ('LOW','MEDIUM','HIGH','DECISIVE')),
  is_active       boolean not null default true,
  is_primary_employer boolean not null default false,
  start_date      date,
  end_date        date,
  notes           text,
  created_at      timestamptz not null default now(),
  created_by      uuid,
  updated_at      timestamptz not null default now(),
  updated_by      uuid,
  version         bigint not null default 0,
  unique (tenant_id, id),
  unique (tenant_id, contact_id, account_id, role),
  constraint acr_dates_ordered check (end_date is null or start_date is null or end_date >= start_date),
  constraint acr_inactive_has_end check (is_active or end_date is not null),
  constraint fk_acr_contact_same_tenant
    foreign key (tenant_id, contact_id) references crm.contact(tenant_id, id),
  constraint fk_acr_account_same_tenant
    foreign key (tenant_id, account_id) references crm.account(tenant_id, id)
);
create index idx_acr_by_account on crm.account_contact_relation(tenant_id, account_id) where is_active;
create index idx_acr_by_contact on crm.account_contact_relation(tenant_id, contact_id);

-- -------------------------------------------------------- 5. buying groups
-- FR-ACC-006: a group hangs off exactly one of account or opportunity.
create table crm.buying_group (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  name           text not null,
  account_id     uuid,
  opportunity_id uuid,
  description    text,
  status         text not null default 'ACTIVE' check (status in ('ACTIVE','ARCHIVED')),
  created_at     timestamptz not null default now(),
  created_by     uuid,
  updated_at     timestamptz not null default now(),
  updated_by     uuid,
  deleted_at     timestamptz,
  version        bigint not null default 0,
  unique (tenant_id, id),
  constraint buying_group_one_anchor
    check ((account_id is not null) <> (opportunity_id is not null)),
  constraint fk_buying_group_account_same_tenant
    foreign key (tenant_id, account_id) references crm.account(tenant_id, id),
  constraint fk_buying_group_opportunity_same_tenant
    foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id)
);
create unique index uq_buying_group_name
  on crm.buying_group(tenant_id, lower(name)) where deleted_at is null;
create index idx_buying_group_account on crm.buying_group(tenant_id, account_id) where deleted_at is null;
create index idx_buying_group_opportunity on crm.buying_group(tenant_id, opportunity_id) where deleted_at is null;

create table crm.buying_group_member (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  buying_group_id   uuid not null,
  contact_id        uuid not null,
  role              text not null check (role in
    ('ECONOMIC_BUYER','CHAMPION','TECHNICAL_EVALUATOR','BLOCKER','INFLUENCER')),
  influence         text not null default 'MEDIUM'
    check (influence in ('LOW','MEDIUM','HIGH','DECISIVE')),
  engagement_status text not null default 'NOT_ENGAGED' check (engagement_status in
    ('NOT_ENGAGED','CONTACTED','ENGAGED','ADVOCATING','DISENGAGED','OPPOSED')),
  last_engaged_at   timestamptz,
  notes             text,
  created_at        timestamptz not null default now(),
  created_by        uuid,
  updated_at        timestamptz not null default now(),
  updated_by        uuid,
  version           bigint not null default 0,
  unique (tenant_id, id),
  unique (tenant_id, buying_group_id, contact_id),
  constraint fk_bgm_group_same_tenant
    foreign key (tenant_id, buying_group_id) references crm.buying_group(tenant_id, id),
  constraint fk_bgm_contact_same_tenant
    foreign key (tenant_id, contact_id) references crm.contact(tenant_id, id)
);
create index idx_bgm_group on crm.buying_group_member(tenant_id, buying_group_id);

-- --------------------------------------------------- 6. consent (append-only)
-- FR-ACC-011. Consent history is evidence. A withdrawal is a NEW row.
create table crm.consent_record (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  subject_type  text not null check (subject_type in ('CONTACT','LEAD')),
  subject_id    uuid not null,
  channel       text not null check (channel in ('EMAIL','PHONE','SMS','WHATSAPP','POST','ANY')),
  purpose       text not null check (purpose in ('MARKETING','SALES_OUTREACH','SERVICE','TRANSACTIONAL','RESEARCH')),
  state         text not null check (state in ('GRANTED','WITHDRAWN','NEVER')),
  lawful_basis  text not null check (lawful_basis in
    ('CONSENT','CONTRACT','LEGAL_OBLIGATION','LEGITIMATE_INTEREST','VITAL_INTEREST','PUBLIC_TASK')),
  source        text not null check (source in
    ('WEB_FORM','IMPORT','VERBAL','EMAIL_REPLY','PREFERENCE_CENTRE','AGENT_ENTRY','DATA_SUBJECT_REQUEST')),
  evidence_ref  text,
  captured_at   timestamptz not null default now(),
  granted_at    timestamptz,
  withdrawn_at  timestamptz,
  recorded_by   uuid,
  note          text,
  unique (tenant_id, id),
  constraint consent_state_timestamps check (
    (state = 'GRANTED'   and granted_at   is not null and withdrawn_at is null) or
    (state = 'WITHDRAWN' and withdrawn_at is not null) or
    (state = 'NEVER'     and granted_at is null and withdrawn_at is null)
  )
);
create index idx_consent_subject on crm.consent_record(tenant_id, subject_type, subject_id, channel, captured_at desc);

create or replace function crm.reject_consent_mutation() returns trigger
language plpgsql as $$
begin
  raise exception
    'Consent records are append-only: record a new consent row instead of changing %.'
      ' Consent history is legal evidence and cannot be rewritten.', old.id
    using errcode = '42501';
end;
$$;

create trigger trg_consent_append_only
  before update or delete on crm.consent_record
  for each row execute function crm.reject_consent_mutation();

-- FR-ACC-011: every send/dial attempt, allowed or blocked, leaves a row.
create table crm.outreach_attempt (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  subject_type   text not null check (subject_type in ('CONTACT','LEAD')),
  subject_id     uuid not null,
  channel        text not null check (channel in ('EMAIL','PHONE','SMS','WHATSAPP','POST')),
  purpose        text not null check (purpose in ('MARKETING','SALES_OUTREACH','SERVICE','TRANSACTIONAL','RESEARCH')),
  origin         text not null check (origin in ('UI','API','CADENCE','AUTOMATION','INTEGRATION')),
  outcome        text not null check (outcome in ('ALLOWED','BLOCKED')),
  block_reason   text,
  subject_line   text,
  attempted_by   uuid,
  attempted_at   timestamptz not null default now(),
  unique (tenant_id, id),
  constraint outreach_block_has_reason check (outcome <> 'BLOCKED' or block_reason is not null)
);
create index idx_outreach_subject on crm.outreach_attempt(tenant_id, subject_type, subject_id, attempted_at desc);

-- ---------------------------------------------------- 7. duplicate detection
-- FR-ACC-008. Rules are data, so a steward tunes them without a release.
create table crm.duplicate_rule (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  rule_code     text not null,
  label         text not null,
  entity_type   text not null check (entity_type in ('ACCOUNT','CONTACT','LEAD')),
  match_kind    text not null check (match_kind in
    ('NAME_FUZZY','COMPANY_FUZZY','DOMAIN_EXACT','EMAIL_EXACT','EMAIL_LOCAL_FUZZY','PHONE_NORMALIZED')),
  enforcement   text not null check (enforcement in ('BLOCKING','WARNING')),
  threshold     numeric(4,3) not null check (threshold > 0 and threshold <= 1),
  active        boolean not null default true,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, rule_code)
);

-- A warning rule is only meaningful if the override is on the record.
create table crm.duplicate_decision (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  entity_type     text not null check (entity_type in ('ACCOUNT','CONTACT','LEAD')),
  entity_id       uuid,
  operation       text not null check (operation in ('CREATE','UPDATE')),
  decision        text not null check (decision in ('PROCEEDED','ABANDONED','BLOCKED')),
  rule_code       text,
  candidate_json  jsonb not null default '[]'::jsonb,
  top_confidence  numeric(4,3),
  decided_by      uuid,
  decided_at      timestamptz not null default now(),
  reason          text,
  unique (tenant_id, id)
);
create index idx_duplicate_decision_entity on crm.duplicate_decision(tenant_id, entity_type, entity_id, decided_at desc);

-- ------------------------------------------------------ 8. merge and reversal
-- FR-ACC-009 / FR-ACC-010.
create table crm.merge_policy (
  tenant_id      uuid primary key references platform.tenant(id),
  retention_days int not null default 30 check (retention_days between 1 and 365),
  updated_at     timestamptz not null default now()
);

create table crm.merge_event (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  entity_type      text not null check (entity_type in ('ACCOUNT','CONTACT')),
  survivor_id      uuid not null,
  losing_ids       uuid[] not null,
  reason           text,
  merged_by        uuid,
  merged_at        timestamptz not null default now(),
  reversible_until timestamptz not null,
  reversed_at      timestamptz,
  reversed_by      uuid,
  reversal_reason  text,
  audit_action     text not null default 'ACCOUNT_MERGE',
  unique (tenant_id, id),
  constraint merge_has_losers check (array_length(losing_ids, 1) >= 1)
);
create index idx_merge_event_recent on crm.merge_event(tenant_id, merged_at desc);

create table crm.merge_field_decision (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  merge_event_id  uuid not null,
  field_name      text not null,
  chosen_value    text,
  chosen_from_id  uuid not null,
  survivor_previous_value text,
  unique (tenant_id, id),
  unique (tenant_id, merge_event_id, field_name),
  constraint fk_mfd_event_same_tenant
    foreign key (tenant_id, merge_event_id) references crm.merge_event(tenant_id, id)
);

-- The reparent log is what makes FR-ACC-010 possible: reversal replays it
-- backwards instead of guessing which child belonged to which parent.
create table crm.merge_reparent_log (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  merge_event_id  uuid not null,
  child_table     text not null,
  child_id        uuid not null,
  column_name     text not null,
  previous_value  uuid,
  new_value       uuid,
  unique (tenant_id, id),
  constraint fk_mrl_event_same_tenant
    foreign key (tenant_id, merge_event_id) references crm.merge_event(tenant_id, id)
);
create index idx_merge_reparent_event on crm.merge_reparent_log(tenant_id, merge_event_id);

-- ------------------------------------------------- 9. enrichment provenance
-- FR-ACC-013. Provider integration is a port; this is the resolution ledger.
create table crm.field_provenance (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  entity_type   text not null check (entity_type in ('ACCOUNT','CONTACT')),
  entity_id     uuid not null,
  field_name    text not null,
  value_source  text not null check (value_source in ('USER','ENRICHMENT','IMPORT','MERGE','SYSTEM')),
  provider_code text,
  confidence    numeric(4,3),
  recorded_at   timestamptz not null default now(),
  recorded_by   uuid,
  unique (tenant_id, id),
  unique (tenant_id, entity_type, entity_id, field_name)
);

create table crm.enrichment_snapshot (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  entity_type   text not null check (entity_type in ('ACCOUNT','CONTACT')),
  entity_id     uuid not null,
  provider_code text not null,
  provider_kind text not null default 'LOCAL_STUB' check (provider_kind in ('LOCAL_STUB','EXTERNAL')),
  requested_at  timestamptz not null default now(),
  requested_by  uuid,
  status        text not null default 'AWAITING_RESOLUTION'
    check (status in ('AWAITING_RESOLUTION','RESOLVED','NO_CHANGE')),
  resolved_at   timestamptz,
  resolved_by   uuid,
  unique (tenant_id, id)
);
create index idx_enrichment_snapshot_entity
  on crm.enrichment_snapshot(tenant_id, entity_type, entity_id, requested_at desc);

create table crm.enrichment_field (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  snapshot_id   uuid not null,
  field_name    text not null,
  current_value text,
  proposed_value text,
  current_source text not null default 'SYSTEM',
  confidence    numeric(4,3),
  outcome       text not null check (outcome in ('APPLIED','CONFLICT','UNCHANGED','REJECTED','ACCEPTED')),
  resolved_at   timestamptz,
  resolved_by   uuid,
  unique (tenant_id, id),
  unique (tenant_id, snapshot_id, field_name),
  constraint fk_enrichment_field_snapshot_same_tenant
    foreign key (tenant_id, snapshot_id) references crm.enrichment_snapshot(tenant_id, id)
);

-- ----------------------------------------------- 10. signals, health, history
-- FR-ACC-004 (open cases) and FR-ACC-014 (health) both need service, renewal
-- and adoption facts. The owning modules (E09 contracts, E12 cases) are not
-- built yet, so the signal table is an explicitly-sourced cache in the shape of
-- CREDIT_SNAPSHOT in the data model: it names its source and its as-of date so
-- the CRM can never present a borrowed fact as its own.
create table crm.account_signal (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  account_id    uuid not null,
  signal_code   text not null check (signal_code in
    ('OPEN_CASES','SLA_BREACHES','RENEWAL_DATE','ADOPTION_SCORE','SUPPORT_CSAT')),
  numeric_value numeric(14,2),
  date_value    date,
  as_of         timestamptz not null default now(),
  source_system text not null,
  unique (tenant_id, id),
  unique (tenant_id, account_id, signal_code),
  constraint fk_account_signal_account_same_tenant
    foreign key (tenant_id, account_id) references crm.account(tenant_id, id)
);

create table crm.health_factor_weight (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  factor_code  text not null check (factor_code in
    ('ENGAGEMENT_RECENCY','OPEN_CASES','SLA_BREACHES','RENEWAL_PROXIMITY','PRODUCT_ADOPTION')),
  label        text not null,
  weight       numeric(4,3) not null check (weight > 0 and weight <= 1),
  active       boolean not null default true,
  unique (tenant_id, id),
  unique (tenant_id, factor_code)
);

create table crm.account_health_snapshot (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  account_id    uuid not null,
  score         int not null check (score between 0 and 100),
  band          text not null check (band in ('STRONG','STEADY','WATCH','AT_RISK','CRITICAL')),
  factors       jsonb not null,
  computed_at   timestamptz not null default now(),
  computed_by   uuid,
  unique (tenant_id, id),
  constraint fk_health_snapshot_account_same_tenant
    foreign key (tenant_id, account_id) references crm.account(tenant_id, id)
);
create index idx_health_snapshot_account
  on crm.account_health_snapshot(tenant_id, account_id, computed_at desc);

-- FR-ACC-012: "notable field changes" on the timeline. Captured by trigger, so
-- the timeline is complete regardless of which code path wrote the row.
create table crm.account_field_change (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  entity_type text not null check (entity_type in ('ACCOUNT','CONTACT')),
  entity_id   uuid not null,
  field_name  text not null,
  old_value   text,
  new_value   text,
  changed_at  timestamptz not null default now(),
  changed_by  uuid,
  unique (tenant_id, id)
);
create index idx_account_field_change_timeline
  on crm.account_field_change(tenant_id, entity_type, entity_id, changed_at desc);

-- --------------------------------------------- 11. hierarchy path maintenance
-- FR-ACC-003. Cycle prevention is enforced here, in the database, so no code
-- path — service, import, script or psql session — can create one.
create or replace function crm.account_hierarchy_apply() returns trigger
language plpgsql as $$
declare
  parent_path  text;
  parent_depth int;
  parent_root  uuid;
  parent_name  text;
begin
  if new.parent_account_id is null then
    new.hierarchy_path := '/' || new.id::text || '/';
    new.hierarchy_depth := 0;
    new.ultimate_parent_id := new.id;
    return new;
  end if;

  select a.hierarchy_path, a.hierarchy_depth, a.ultimate_parent_id, a.name
    into parent_path, parent_depth, parent_root, parent_name
    from crm.account a
   where a.tenant_id = new.tenant_id and a.id = new.parent_account_id;

  if parent_path is null then
    raise exception 'Parent account % does not exist in this tenant', new.parent_account_id
      using errcode = '23503';
  end if;

  -- The cycle test: the proposed parent already sits beneath this account.
  if position('/' || new.id::text || '/' in parent_path) > 0 then
    raise exception
      'Account hierarchy cycle rejected: "%" (%) cannot be a child of "%" (%) because "%" already sits beneath "%" in the hierarchy.',
      new.name, new.id, parent_name, new.parent_account_id, parent_name, new.name
      using errcode = '23514';
  end if;

  new.hierarchy_path := parent_path || new.id::text || '/';
  new.hierarchy_depth := parent_depth + 1;
  new.ultimate_parent_id := parent_root;
  return new;
end;
$$;

create or replace function crm.account_hierarchy_cascade() returns trigger
language plpgsql as $$
begin
  if old.hierarchy_path = new.hierarchy_path then
    return null;
  end if;
  update crm.account d
     set hierarchy_path = new.hierarchy_path || substring(d.hierarchy_path from length(old.hierarchy_path) + 1),
         hierarchy_depth = new.hierarchy_depth + (d.hierarchy_depth - old.hierarchy_depth),
         ultimate_parent_id = new.ultimate_parent_id,
         updated_at = now()
   where d.tenant_id = new.tenant_id
     and d.id <> new.id
     and d.hierarchy_path like old.hierarchy_path || '%';
  return null;
end;
$$;

create trigger trg_account_hierarchy_insert
  before insert on crm.account
  for each row execute function crm.account_hierarchy_apply();

create trigger trg_account_hierarchy_update
  before update of parent_account_id on crm.account
  for each row execute function crm.account_hierarchy_apply();

-- Restricted to the parent column so the cascade cannot recurse into itself:
-- the cascading UPDATE never lists parent_account_id.
create trigger trg_account_hierarchy_cascade
  after update of parent_account_id on crm.account
  for each row execute function crm.account_hierarchy_cascade();

-- --------------------------------------------- 12. field-history capture
create or replace function crm.capture_account_field_change() returns trigger
language plpgsql as $$
declare
  actor uuid := nullif(current_setting('app.actor_id', true), '')::uuid;
begin
  if tg_table_name = 'account' then
    if new.name is distinct from old.name then
      insert into crm.account_field_change(tenant_id, entity_type, entity_id, field_name, old_value, new_value, changed_by)
      values (new.tenant_id, 'ACCOUNT', new.id, 'name', old.name, new.name, actor);
    end if;
    if new.parent_account_id is distinct from old.parent_account_id then
      insert into crm.account_field_change(tenant_id, entity_type, entity_id, field_name, old_value, new_value, changed_by)
      values (new.tenant_id, 'ACCOUNT', new.id, 'parentAccountId', old.parent_account_id::text, new.parent_account_id::text, actor);
    end if;
    if new.owner_id is distinct from old.owner_id then
      insert into crm.account_field_change(tenant_id, entity_type, entity_id, field_name, old_value, new_value, changed_by)
      values (new.tenant_id, 'ACCOUNT', new.id, 'ownerId', old.owner_id::text, new.owner_id::text, actor);
    end if;
    if new.status is distinct from old.status then
      insert into crm.account_field_change(tenant_id, entity_type, entity_id, field_name, old_value, new_value, changed_by)
      values (new.tenant_id, 'ACCOUNT', new.id, 'status', old.status, new.status, actor);
    end if;
    if new.industry is distinct from old.industry then
      insert into crm.account_field_change(tenant_id, entity_type, entity_id, field_name, old_value, new_value, changed_by)
      values (new.tenant_id, 'ACCOUNT', new.id, 'industry', old.industry, new.industry, actor);
    end if;
    if new.segment is distinct from old.segment then
      insert into crm.account_field_change(tenant_id, entity_type, entity_id, field_name, old_value, new_value, changed_by)
      values (new.tenant_id, 'ACCOUNT', new.id, 'segment', old.segment, new.segment, actor);
    end if;
    if new.annual_revenue is distinct from old.annual_revenue then
      insert into crm.account_field_change(tenant_id, entity_type, entity_id, field_name, old_value, new_value, changed_by)
      values (new.tenant_id, 'ACCOUNT', new.id, 'annualRevenue', old.annual_revenue::text, new.annual_revenue::text, actor);
    end if;
    if new.website is distinct from old.website then
      insert into crm.account_field_change(tenant_id, entity_type, entity_id, field_name, old_value, new_value, changed_by)
      values (new.tenant_id, 'ACCOUNT', new.id, 'website', old.website, new.website, actor);
    end if;
  else
    if new.account_id is distinct from old.account_id then
      insert into crm.account_field_change(tenant_id, entity_type, entity_id, field_name, old_value, new_value, changed_by)
      values (new.tenant_id, 'CONTACT', new.id, 'accountId', old.account_id::text, new.account_id::text, actor);
    end if;
    if new.title is distinct from old.title then
      insert into crm.account_field_change(tenant_id, entity_type, entity_id, field_name, old_value, new_value, changed_by)
      values (new.tenant_id, 'CONTACT', new.id, 'title', old.title, new.title, actor);
    end if;
    if new.email is distinct from old.email then
      insert into crm.account_field_change(tenant_id, entity_type, entity_id, field_name, old_value, new_value, changed_by)
      values (new.tenant_id, 'CONTACT', new.id, 'email', old.email, new.email, actor);
    end if;
    if new.status is distinct from old.status then
      insert into crm.account_field_change(tenant_id, entity_type, entity_id, field_name, old_value, new_value, changed_by)
      values (new.tenant_id, 'CONTACT', new.id, 'status', old.status, new.status, actor);
    end if;
    if new.reports_to_contact_id is distinct from old.reports_to_contact_id then
      insert into crm.account_field_change(tenant_id, entity_type, entity_id, field_name, old_value, new_value, changed_by)
      values (new.tenant_id, 'CONTACT', new.id, 'reportsToContactId', old.reports_to_contact_id::text, new.reports_to_contact_id::text, actor);
    end if;
  end if;
  return null;
end;
$$;

create trigger trg_account_field_history
  after update on crm.account
  for each row execute function crm.capture_account_field_change();

create trigger trg_contact_field_history
  after update on crm.contact
  for each row execute function crm.capture_account_field_change();

-- ------------------------------------------------------------- 13. RLS + grants
-- nullif(...,'') is mandatory: SET LOCAL reverts the GUC to '' and a bare
-- ''::uuid cast raises 22P02 rather than filtering.
do $$
declare
  t text;
begin
  foreach t in array array[
    'crm.postal_address',
    'crm.contact_channel',
    'crm.account_contact_relation',
    'crm.buying_group',
    'crm.buying_group_member',
    'crm.consent_record',
    'crm.outreach_attempt',
    'crm.duplicate_rule',
    'crm.duplicate_decision',
    'crm.merge_policy',
    'crm.merge_event',
    'crm.merge_field_decision',
    'crm.merge_reparent_log',
    'crm.field_provenance',
    'crm.enrichment_snapshot',
    'crm.enrichment_field',
    'crm.account_signal',
    'crm.health_factor_weight',
    'crm.account_health_snapshot',
    'crm.account_field_change'
  ]
  loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format('drop policy if exists tenant_isolation on %s', t);
    execute format($p$
      create policy tenant_isolation on %s
        using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
        with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    $p$, t);
  end loop;
end
$$;

grant select, insert, update on
  crm.postal_address, crm.contact_channel, crm.account_contact_relation,
  crm.buying_group, crm.buying_group_member,
  crm.duplicate_rule, crm.merge_policy, crm.merge_event,
  crm.field_provenance, crm.enrichment_snapshot, crm.enrichment_field,
  crm.account_signal, crm.health_factor_weight
  to axiom_app;

-- Append-only at the privilege level, not merely by trigger: the runtime role
-- holds no update or delete right on any of these, so a future code path that
-- tries to rewrite evidence fails on permissions rather than on good intentions.
grant select, insert on
  crm.consent_record, crm.outreach_attempt, crm.duplicate_decision,
  crm.merge_field_decision, crm.merge_reparent_log,
  crm.account_health_snapshot, crm.account_field_change
  to axiom_app;
revoke update, delete on crm.consent_record from axiom_app;

-- Merge reversal must be able to clear the loser tombstone on crm.account and
-- crm.contact; both already grant update to axiom_app from V1/V5.

-- ------------------------------------------------------------- 14. governance
insert into governance.screen_catalog(screen_code, module_code, route, display_name, description, sort_order) values
  ('ACCOUNT_DETAIL', 'CRM', '/accounts/detail', 'Account 360', 'Account detail with hierarchy roll-up, 360 timeline, buying groups, consent and health.', 31),
  ('ACCOUNT_DUPLICATES', 'CRM', '/accounts/duplicates', 'Duplicate review', 'Duplicate candidate review, survivorship merge and merge reversal.', 32)
on conflict (screen_code) do nothing;

insert into governance.rbac_policy(role_code, screen_code, can_read, can_write, can_export, can_admin, scope)
select role_code, screen_code,
       role_code <> 'INTEGRATION',
       role_code not in ('SUPER_AUDIT','AUDITOR','INTEGRATION'),
       role_code <> 'INTEGRATION',
       role_code in ('SUPER_ADMIN','TENANT_ADMIN','DATA_STEWARD'),
       case when role_code in ('SUPER_ADMIN','SUPER_AUDIT') then 'PLATFORM' else 'TENANT' end
from (values
  ('SUPER_ADMIN'),('SUPER_AUDIT'),('TENANT_ADMIN'),('SALES_MANAGER'),('SALES'),
  ('MARKETING'),('SERVICE'),('OPERATIONS'),('FINANCE'),('DATA_STEWARD'),('AUDITOR'),('INTEGRATION')
) roles(role_code)
cross join (values ('ACCOUNT_DETAIL'), ('ACCOUNT_DUPLICATES')) screens(screen_code)
on conflict (role_code, screen_code) do update
  set can_read = excluded.can_read,
      can_write = excluded.can_write,
      can_export = excluded.can_export,
      can_admin = excluded.can_admin,
      scope = excluded.scope;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('crm','postal_address','CRM','id',true,'SOFT_DELETE'),
  ('crm','contact_channel','CRM','id',true,'SOFT_DELETE'),
  ('crm','account_contact_relation','CRM','id',true,'ACTIVE'),
  ('crm','buying_group','CRM','id',true,'SOFT_DELETE'),
  ('crm','buying_group_member','CRM','id',true,'ACTIVE'),
  ('crm','consent_record','CRM','id',true,'APPEND_ONLY'),
  ('crm','outreach_attempt','CRM','id',true,'APPEND_ONLY'),
  ('crm','duplicate_rule','CRM','id',true,'ACTIVE'),
  ('crm','duplicate_decision','CRM','id',true,'APPEND_ONLY'),
  ('crm','merge_policy','CRM','tenant_id',true,'ACTIVE'),
  ('crm','merge_event','CRM','id',true,'ACTIVE'),
  ('crm','merge_field_decision','CRM','id',true,'ACTIVE'),
  ('crm','merge_reparent_log','CRM','id',true,'ACTIVE'),
  ('crm','field_provenance','CRM','id',true,'ACTIVE'),
  ('crm','enrichment_snapshot','CRM','id',true,'ACTIVE'),
  ('crm','enrichment_field','CRM','id',true,'ACTIVE'),
  ('crm','account_signal','CRM','id',true,'ACTIVE'),
  ('crm','health_factor_weight','CRM','id',true,'ACTIVE'),
  ('crm','account_health_snapshot','CRM','id',true,'APPEND_ONLY'),
  ('crm','account_field_change','CRM','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

-- ------------------------------------------------------------- 15. governed values
insert into reference.value_set (tenant_id, api_name, label, module, description)
select t.id, seed.api_name, seed.label, seed.module, seed.description
from platform.tenant t
cross join (values
  ('account_record_type', 'Account record type', 'CRM', 'Record types available on the account layout'),
  ('account_status', 'Account status', 'CRM', 'Lifecycle status of an account'),
  ('account_segment', 'Account segment', 'CRM', 'Commercial segmentation of an account'),
  ('contact_seniority', 'Contact seniority', 'CRM', 'Seniority band used for buying-group weighting'),
  ('relationship_role', 'Account relationship role', 'CRM', 'Role a contact plays at a related account'),
  ('influence_level', 'Influence level', 'CRM', 'Relative influence of a contact on a purchase'),
  ('buying_group_role', 'Buying group role', 'CRM', 'Role of a buying-group member'),
  ('engagement_status', 'Buying group engagement status', 'CRM', 'How engaged a buying-group member currently is'),
  ('consent_channel', 'Consent channel', 'CRM', 'Communication channels consent is held against'),
  ('consent_purpose', 'Consent purpose', 'CRM', 'Purposes consent is held for'),
  ('consent_lawful_basis', 'Lawful basis', 'CRM', 'Lawful basis for processing personal data'),
  ('health_band', 'Account health band', 'CRM', 'Banding applied to the account health score')
) as seed(api_name, label, module, description)
on conflict (tenant_id, api_name) do nothing;

insert into reference.value_set_entry (tenant_id, value_set_id, code, label, sort_order, system_managed)
select vs.tenant_id, vs.id, seed.code, seed.label, seed.sort_order, true
from reference.value_set vs
join (values
  ('account_record_type','STANDARD','Standard',10),
  ('account_record_type','PROSPECT','Prospect',20),
  ('account_record_type','CUSTOMER','Customer',30),
  ('account_record_type','PARTNER','Partner',40),
  ('account_record_type','COMPETITOR','Competitor',50),
  ('account_record_type','SUBSIDIARY','Subsidiary',60),
  ('account_status','ACTIVE','Active',10),
  ('account_status','INACTIVE','Inactive',20),
  ('account_status','MERGED','Merged away',30),
  ('account_segment','ENTERPRISE','Enterprise',10),
  ('account_segment','MID_MARKET','Mid-market',20),
  ('account_segment','SMB','Small business',30),
  ('contact_seniority','C_LEVEL','C-level',10),
  ('contact_seniority','VP','Vice president',20),
  ('contact_seniority','DIRECTOR','Director',30),
  ('contact_seniority','MANAGER','Manager',40),
  ('contact_seniority','INDIVIDUAL_CONTRIBUTOR','Individual contributor',50),
  ('contact_seniority','OTHER','Other',60),
  ('relationship_role','EMPLOYEE','Employee',10),
  ('relationship_role','BOARD_MEMBER','Board member',20),
  ('relationship_role','CONSULTANT','Consultant',30),
  ('relationship_role','PROCUREMENT','Procurement contact',40),
  ('relationship_role','BILLING','Billing contact',50),
  ('relationship_role','FORMER_EMPLOYEE','Former employee',60),
  ('influence_level','LOW','Low',10),
  ('influence_level','MEDIUM','Medium',20),
  ('influence_level','HIGH','High',30),
  ('influence_level','DECISIVE','Decisive',40),
  ('buying_group_role','ECONOMIC_BUYER','Economic buyer',10),
  ('buying_group_role','CHAMPION','Champion',20),
  ('buying_group_role','TECHNICAL_EVALUATOR','Technical evaluator',30),
  ('buying_group_role','BLOCKER','Blocker',40),
  ('buying_group_role','INFLUENCER','Influencer',50),
  ('engagement_status','NOT_ENGAGED','Not engaged',10),
  ('engagement_status','CONTACTED','Contacted',20),
  ('engagement_status','ENGAGED','Engaged',30),
  ('engagement_status','ADVOCATING','Advocating',40),
  ('engagement_status','DISENGAGED','Disengaged',50),
  ('engagement_status','OPPOSED','Opposed',60),
  ('consent_channel','EMAIL','Email',10),
  ('consent_channel','PHONE','Phone',20),
  ('consent_channel','SMS','SMS',30),
  ('consent_channel','WHATSAPP','WhatsApp',40),
  ('consent_channel','POST','Post',50),
  ('consent_channel','ANY','All channels',60),
  ('consent_purpose','MARKETING','Marketing',10),
  ('consent_purpose','SALES_OUTREACH','Sales outreach',20),
  ('consent_purpose','SERVICE','Service and support',30),
  ('consent_purpose','TRANSACTIONAL','Transactional',40),
  ('consent_purpose','RESEARCH','Research',50),
  ('consent_lawful_basis','CONSENT','Consent',10),
  ('consent_lawful_basis','CONTRACT','Performance of a contract',20),
  ('consent_lawful_basis','LEGAL_OBLIGATION','Legal obligation',30),
  ('consent_lawful_basis','LEGITIMATE_INTEREST','Legitimate interest',40),
  ('consent_lawful_basis','VITAL_INTEREST','Vital interest',50),
  ('consent_lawful_basis','PUBLIC_TASK','Public task',60),
  ('health_band','STRONG','Strong',10),
  ('health_band','STEADY','Steady',20),
  ('health_band','WATCH','Watch',30),
  ('health_band','AT_RISK','At risk',40),
  ('health_band','CRITICAL','Critical',50)
) as seed(api_name, code, label, sort_order) on seed.api_name = vs.api_name
on conflict (tenant_id, value_set_id, code) do nothing;

-- ------------------------------------------------------------- 16. tenant defaults
insert into crm.merge_policy(tenant_id, retention_days)
select id, 30 from platform.tenant on conflict (tenant_id) do nothing;

insert into crm.duplicate_rule(tenant_id, rule_code, label, entity_type, match_kind, enforcement, threshold)
select t.id, seed.rule_code, seed.label, seed.entity_type, seed.match_kind, seed.enforcement, seed.threshold
from platform.tenant t
cross join (values
  ('ACCOUNT_DOMAIN_EXACT', 'Account web domain already in use', 'ACCOUNT', 'DOMAIN_EXACT', 'BLOCKING', 1.000),
  ('ACCOUNT_NAME_FUZZY', 'Account name closely matches an existing account', 'ACCOUNT', 'NAME_FUZZY', 'WARNING', 0.820),
  ('ACCOUNT_PHONE_MATCH', 'Account switchboard number already in use', 'ACCOUNT', 'PHONE_NORMALIZED', 'WARNING', 1.000),
  ('CONTACT_EMAIL_EXACT', 'Contact email already exists', 'CONTACT', 'EMAIL_EXACT', 'BLOCKING', 1.000),
  ('CONTACT_NAME_FUZZY', 'Contact name closely matches an existing contact', 'CONTACT', 'NAME_FUZZY', 'WARNING', 0.880),
  ('CONTACT_PHONE_MATCH', 'Contact phone or mobile already in use', 'CONTACT', 'PHONE_NORMALIZED', 'WARNING', 1.000),
  ('LEAD_EMAIL_EXACT', 'Lead email already exists', 'LEAD', 'EMAIL_EXACT', 'WARNING', 1.000),
  ('LEAD_COMPANY_FUZZY', 'Lead company closely matches an existing account or lead', 'LEAD', 'COMPANY_FUZZY', 'WARNING', 0.850)
) as seed(rule_code, label, entity_type, match_kind, enforcement, threshold)
on conflict (tenant_id, rule_code) do nothing;

insert into crm.health_factor_weight(tenant_id, factor_code, label, weight)
select t.id, seed.factor_code, seed.label, seed.weight
from platform.tenant t
cross join (values
  ('ENGAGEMENT_RECENCY', 'How recently anyone spoke to this customer', 0.300),
  ('OPEN_CASES', 'Open support cases', 0.200),
  ('SLA_BREACHES', 'Support promises missed', 0.150),
  ('RENEWAL_PROXIMITY', 'How close the renewal is', 0.150),
  ('PRODUCT_ADOPTION', 'How much of what they bought they actually use', 0.200)
) as seed(factor_code, label, weight)
on conflict (tenant_id, factor_code) do nothing;

-- ------------------------------------------------------------- 17. demo shape
-- A visible hierarchy plus service/renewal/adoption signals so the roll-up and
-- health screens have something honest to show in the dev stack. Signals are
-- stamped with a source_system that says plainly they are seeded, not observed.
update crm.account set email_domain = 'kestrelmfg.example', segment = 'ENTERPRISE',
       record_type = 'CUSTOMER', annual_revenue = 480000000, employee_count = 2400,
       website = 'https://kestrelmfg.example'
 where id = '44444444-4444-4444-4444-444444444401';
update crm.account set email_domain = 'haldencold.example', segment = 'MID_MARKET', record_type = 'CUSTOMER'
 where id = '44444444-4444-4444-4444-444444444402';
update crm.account set email_domain = 'solventcole.example', segment = 'MID_MARKET'
 where id = '44444444-4444-4444-4444-444444444403';

-- Kestrel becomes the family head; Halden and Solvent & Cole sit beneath it.
update crm.account set parent_account_id = '44444444-4444-4444-4444-444444444401'
 where id in ('44444444-4444-4444-4444-444444444402', '44444444-4444-4444-4444-444444444403');

insert into crm.account_signal(tenant_id, account_id, signal_code, numeric_value, date_value, source_system)
values
  ('11111111-1111-1111-1111-111111111111','44444444-4444-4444-4444-444444444401','OPEN_CASES',3,null,'SEEDED_DEV_FIXTURE'),
  ('11111111-1111-1111-1111-111111111111','44444444-4444-4444-4444-444444444401','SLA_BREACHES',1,null,'SEEDED_DEV_FIXTURE'),
  ('11111111-1111-1111-1111-111111111111','44444444-4444-4444-4444-444444444401','ADOPTION_SCORE',62,null,'SEEDED_DEV_FIXTURE'),
  ('11111111-1111-1111-1111-111111111111','44444444-4444-4444-4444-444444444401','RENEWAL_DATE',null,date '2026-10-15','SEEDED_DEV_FIXTURE'),
  ('11111111-1111-1111-1111-111111111111','44444444-4444-4444-4444-444444444402','OPEN_CASES',0,null,'SEEDED_DEV_FIXTURE'),
  ('11111111-1111-1111-1111-111111111111','44444444-4444-4444-4444-444444444402','ADOPTION_SCORE',88,null,'SEEDED_DEV_FIXTURE')
on conflict (tenant_id, account_id, signal_code) do nothing;

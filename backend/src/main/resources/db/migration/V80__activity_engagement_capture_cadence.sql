-- =============================================================================
-- E07 — Activity, email and calendar engagement (FR-ACT-001..012).
--
-- V8 delivered the first-party half of this epic: one `engagement.activity`
-- table with an `activity_type` discriminator, plus participants. This migration
-- completes the epic and three of its decisions are load-bearing:
--
--  * ACTIVITY stays ONE table. The unified timeline (FR-ACT-004) is the dominant
--    read pattern on every record page; five tables would make it a five-way
--    union for every page view forever.
--  * ACTIVITY_RELATION is MANY-TO-MANY (data model §4.6). One captured email
--    legitimately relates to a contact, that contact's account and two open
--    opportunities at the same time. `activity.related_entity_*` is retained as
--    the denormalized PRIMARY relation so the pre-existing single-relation reads
--    keep working, but `activity_relation` is the authoritative set and the
--    timeline reads from it.
--  * `is_private` does NOT mean "hidden". FR-ACT-007 says excluded items are
--    NEVER STORED. The application evaluates exclusions BEFORE the first insert,
--    so an excluded message never reaches this schema at all. The flag exists
--    only for items captured before an exclusion rule was written, and those are
--    DELETED (not flagged) when the rule is added. Hence the delete grants below.
--
-- Provider interop is an anti-corruption seam (ADR-007): the capability contract
-- is expressed in Axiom vocabulary in `com.axiom.engagement.provider`, with a
-- deterministic local stub adapter. Live Microsoft 365 / Google Workspace and
-- live CTI adapters are DEFERRED — there is no vendor tenant to test against.
-- =============================================================================

-- The engagement module schema already exists (V6) and already carries the
-- activity tables, so E07's new tables belong in it rather than in a second
-- schema for the same module. The runtime role's search_path is a shared
-- resource that other module migrations also extend, so assert membership
-- idempotently rather than overwriting it.
create schema if not exists engagement;
grant usage on schema engagement to axiom_app;

do $$
declare
  current_path text;
begin
  select split_part(cfg, '=', 2)
    into current_path
  from pg_roles r, unnest(coalesce(r.rolconfig, array[]::text[])) cfg
  where r.rolname = 'axiom_app' and cfg like 'search_path=%';

  if current_path is null or btrim(current_path) = '' then
    execute 'alter role axiom_app set search_path to engagement, public';
  elsif position('engagement' in current_path) = 0 then
    execute format('alter role axiom_app set search_path to %s', 'engagement, ' || current_path);
  end if;
end $$;

-- -----------------------------------------------------------------------------
-- 0. Repair the V8 RLS predicates.
--
-- A `SET LOCAL` GUC that has been reset reads back as the EMPTY STRING, not
-- NULL, and `''::uuid` raises `invalid input syntax for type uuid: ""`. Every
-- policy in this file — and the two V8 policies below — therefore casts through
-- nullif(current_setting('app.tenant_id', true), '').
-- -----------------------------------------------------------------------------
drop policy if exists tenant_isolation on engagement.activity;
create policy tenant_isolation on engagement.activity
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

drop policy if exists tenant_isolation on engagement.activity_participant;
create policy tenant_isolation on engagement.activity_participant
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- -----------------------------------------------------------------------------
-- 1. ACTIVITY extensions — capture provenance, privacy, threading, references.
-- -----------------------------------------------------------------------------
alter table engagement.activity
  add column if not exists capture_source     text not null default 'MANUAL',
  add column if not exists match_confidence   numeric(4,3),
  add column if not exists match_basis        text,
  add column if not exists is_private         boolean not null default false,
  add column if not exists thread_id          text,
  add column if not exists external_message_id text,
  add column if not exists provider           text,
  add column if not exists recording_ref      text,
  add column if not exists transcript_ref     text;

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'activity_capture_source_governed') then
    alter table engagement.activity
      add constraint activity_capture_source_governed
      check (capture_source in ('MANUAL','AUTO','API','AI'));
  end if;
  if not exists (select 1 from pg_constraint where conname = 'activity_match_confidence_range') then
    alter table engagement.activity
      add constraint activity_match_confidence_range
      check (match_confidence is null or (match_confidence >= 0 and match_confidence <= 1));
  end if;
  -- An automatically captured item must be able to explain itself (FR-ACT-006).
  if not exists (select 1 from pg_constraint where conname = 'activity_auto_capture_explains_match') then
    alter table engagement.activity
      add constraint activity_auto_capture_explains_match
      check (capture_source <> 'AUTO' or (match_confidence is not null and match_basis is not null));
  end if;
  -- The platform stores a REFERENCE to provider-held media, never the media
  -- itself (FR-ACT-011). An inline data: payload is not a reference.
  if not exists (select 1 from pg_constraint where conname = 'activity_recording_is_reference') then
    alter table engagement.activity
      add constraint activity_recording_is_reference
      check (recording_ref is null or (length(recording_ref) <= 512 and recording_ref not like 'data:%'));
  end if;
  if not exists (select 1 from pg_constraint where conname = 'activity_transcript_is_reference') then
    alter table engagement.activity
      add constraint activity_transcript_is_reference
      check (transcript_ref is null or (length(transcript_ref) <= 512 and transcript_ref not like 'data:%'));
  end if;
end $$;

-- Capture is idempotent on the provider's message identity: a redelivered
-- message is a no-op rather than a duplicate timeline entry (ADR-007 §4).
create unique index if not exists uq_activity_external_message
  on engagement.activity(tenant_id, external_message_id)
  where external_message_id is not null;

create index if not exists idx_activity_thread
  on engagement.activity(tenant_id, thread_id) where thread_id is not null;

-- -----------------------------------------------------------------------------
-- 2. ACTIVITY_RELATION — the many-to-many the data model requires.
-- -----------------------------------------------------------------------------
create table engagement.activity_relation (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  activity_id   uuid not null,
  related_type  text not null check (related_type in ('ACCOUNT','CONTACT','LEAD','OPPORTUNITY')),
  related_id    uuid not null,
  relation_role text not null default 'RELATED'
                check (relation_role in ('PRIMARY','RELATED','PARTICIPANT','DERIVED')),
  created_at    timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, activity_id, related_type, related_id),
  constraint fk_activity_relation_parent_same_tenant
    foreign key (tenant_id, activity_id) references engagement.activity(tenant_id, id)
);

create index idx_activity_relation_record
  on engagement.activity_relation(tenant_id, related_type, related_id);
create index idx_activity_relation_activity
  on engagement.activity_relation(tenant_id, activity_id);

-- Backfill: every existing activity's single relation becomes its PRIMARY row.
insert into engagement.activity_relation (tenant_id, activity_id, related_type, related_id, relation_role)
select tenant_id, id, related_entity_type, related_entity_id, 'PRIMARY'
from engagement.activity
on conflict (tenant_id, activity_id, related_type, related_id) do nothing;

-- -----------------------------------------------------------------------------
-- 3. FR-ACT-005 — the mailbox/calendar connection, in our vocabulary.
-- -----------------------------------------------------------------------------
create table engagement.mailbox_connection (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  user_id           uuid not null,
  provider          text not null check (provider in ('MICROSOFT_365','GOOGLE_WORKSPACE','LOCAL_STUB')),
  email_address     text not null,
  status            text not null default 'CONNECTED'
                    check (status in ('CONNECTED','REVOKED','ERROR')),
  capture_email     boolean not null default true,
  capture_calendar  boolean not null default true,
  external_account_ref text,
  connected_at      timestamptz not null default now(),
  revoked_at        timestamptz,
  last_sync_at      timestamptz,
  last_sync_error   text,
  created_by        uuid not null,
  updated_at        timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, user_id, provider),
  constraint fk_mailbox_connection_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id),
  constraint mailbox_revoked_has_time check (status <> 'REVOKED' or revoked_at is not null)
);
create index idx_mailbox_connection_user on engagement.mailbox_connection(tenant_id, user_id);

-- -----------------------------------------------------------------------------
-- 4. FR-ACT-007 — consent is explicit, revocable and RECORDED. Append-only,
--    for the same reason crm.consent_record is: consent history is evidence.
-- -----------------------------------------------------------------------------
create table engagement.capture_consent (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  user_id      uuid not null,
  scope        text not null check (scope in ('EMAIL','CALENDAR','ALL')),
  state        text not null check (state in ('GRANTED','REVOKED')),
  granted_at   timestamptz,
  revoked_at   timestamptz,
  source       text not null default 'UI' check (source in ('UI','API','ADMIN','IMPORT')),
  recorded_by  uuid not null,
  note         text,
  captured_at  timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_capture_consent_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id),
  constraint capture_consent_state_times check (
    (state = 'GRANTED' and granted_at is not null and revoked_at is null) or
    (state = 'REVOKED' and revoked_at is not null)
  )
);
create index idx_capture_consent_latest
  on engagement.capture_consent(tenant_id, user_id, scope, captured_at desc);

create or replace function engagement.reject_capture_consent_mutation() returns trigger
language plpgsql as $$
begin
  raise exception
    'Capture consent is append-only: record a new consent row instead of changing %.'
    ' Consent and its withdrawal are evidence and cannot be rewritten.', old.id
    using errcode = '42501';
end;
$$;

create trigger trg_capture_consent_append_only
  before update or delete on engagement.capture_consent
  for each row execute function engagement.reject_capture_consent_mutation();

-- -----------------------------------------------------------------------------
-- 5. FR-ACT-007 — exclusions. USER scope is the mailbox owner's own rule;
--    TENANT scope is an administrator's rule and applies to every mailbox.
-- -----------------------------------------------------------------------------
create table engagement.capture_exclusion (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  scope          text not null check (scope in ('USER','TENANT')),
  owner_user_id  uuid,
  exclusion_type text not null check (exclusion_type in ('DOMAIN','ADDRESS','ITEM')),
  pattern        text not null,
  reason         text,
  active         boolean not null default true,
  created_by     uuid not null,
  created_at     timestamptz not null default now(),
  purged_count   int not null default 0,
  unique (tenant_id, id),
  constraint fk_capture_exclusion_owner_same_tenant
    foreign key (tenant_id, owner_user_id) references identity.app_user(tenant_id, id),
  constraint capture_exclusion_scope_owner check (
    (scope = 'USER' and owner_user_id is not null) or
    (scope = 'TENANT' and owner_user_id is null)
  ),
  constraint capture_exclusion_pattern_lowercase check (pattern = lower(pattern))
);
create unique index uq_capture_exclusion_rule
  on engagement.capture_exclusion(tenant_id, scope, coalesce(owner_user_id, '00000000-0000-0000-0000-000000000000'::uuid), exclusion_type, pattern)
  where active;
create index idx_capture_exclusion_lookup
  on engagement.capture_exclusion(tenant_id, exclusion_type, pattern) where active;

-- -----------------------------------------------------------------------------
-- 6. FR-ACT-006 — the review queue. An ambiguous or unmatchable item is RETAINED
--    here (never discarded, never guessed), with its confidence and basis.
-- -----------------------------------------------------------------------------
create table engagement.capture_review_item (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references platform.tenant(id),
  connection_id       uuid not null,
  mailbox_user_id     uuid not null,
  item_kind           text not null check (item_kind in ('EMAIL','MEETING')),
  external_message_id text not null,
  thread_id           text,
  subject             text not null,
  body_preview        text,
  occurred_at         timestamptz not null,
  participants        jsonb not null default '[]'::jsonb,
  status              text not null default 'PENDING'
                      check (status in ('PENDING','UNMATCHABLE','RESOLVED','DISMISSED')),
  match_confidence    numeric(4,3) not null,
  match_basis         text not null,
  candidates          jsonb not null default '[]'::jsonb,
  resolved_activity_id uuid,
  resolved_by         uuid,
  resolved_at         timestamptz,
  created_at          timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, external_message_id),
  constraint fk_review_connection_same_tenant
    foreign key (tenant_id, connection_id) references engagement.mailbox_connection(tenant_id, id),
  constraint fk_review_user_same_tenant
    foreign key (tenant_id, mailbox_user_id) references identity.app_user(tenant_id, id),
  constraint review_confidence_range check (match_confidence >= 0 and match_confidence <= 1),
  constraint review_resolution_complete check (
    status <> 'RESOLVED' or (resolved_activity_id is not null and resolved_by is not null and resolved_at is not null)
  )
);
create index idx_review_queue on engagement.capture_review_item(tenant_id, status, occurred_at desc);

-- Capture run evidence. Holds COUNTS only — an excluded message contributes to
-- `suppressed_count` and nothing about it is written anywhere (FR-ACT-007).
create table engagement.capture_run (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  connection_id     uuid not null,
  provider          text not null,
  started_at        timestamptz not null default now(),
  finished_at       timestamptz,
  outcome           text not null default 'OK' check (outcome in ('OK','NO_CONSENT','CONNECTION_REVOKED','PROVIDER_ERROR')),
  fetched_count     int not null default 0,
  suppressed_count  int not null default 0,
  captured_count    int not null default 0,
  review_count      int not null default 0,
  unmatchable_count int not null default 0,
  message           text,
  unique (tenant_id, id),
  constraint fk_capture_run_connection_same_tenant
    foreign key (tenant_id, connection_id) references engagement.mailbox_connection(tenant_id, id)
);
create index idx_capture_run_recent on engagement.capture_run(tenant_id, started_at desc);

-- -----------------------------------------------------------------------------
-- 7. FR-ACT-009 — tenant engagement policy. Tracking is off unless the tenant
--    turns it on: a tracker that defaults to on is a consent problem.
-- -----------------------------------------------------------------------------
create table engagement.tenant_engagement_policy (
  tenant_id            uuid primary key references platform.tenant(id),
  capture_enabled      boolean not null default true,
  tracking_enabled     boolean not null default false,
  require_consent_for_tracking boolean not null default true,
  updated_by           uuid,
  updated_at           timestamptz not null default now()
);

insert into engagement.tenant_engagement_policy (tenant_id, capture_enabled, tracking_enabled)
select id, true, true from platform.tenant
on conflict (tenant_id) do nothing;

-- -----------------------------------------------------------------------------
-- 8. FR-ACT-008 — templates: folders, versions, permission-scoped sharing.
--    A version is never edited in place; an edit writes a new version, so a send
--    can always name the exact body it used.
-- -----------------------------------------------------------------------------
create table engagement.email_template (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  folder          text not null default 'General',
  api_name        text not null,
  name            text not null,
  description     text,
  current_version int not null default 1,
  sharing_scope   text not null default 'PRIVATE'
                  check (sharing_scope in ('PRIVATE','TENANT','ROLE')),
  owner_id        uuid not null,
  active          boolean not null default true,
  created_by      uuid not null,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, api_name),
  constraint fk_email_template_owner_same_tenant
    foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id),
  constraint email_template_api_name_format check (api_name ~ '^[a-z][a-z0-9_]*$')
);
create index idx_email_template_folder on engagement.email_template(tenant_id, folder, name);

create table engagement.email_template_version (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  template_id   uuid not null,
  version_no    int not null,
  subject       text not null,
  body          text not null,
  merge_fields  jsonb not null default '[]'::jsonb,
  change_note   text,
  created_by    uuid not null,
  created_at    timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, template_id, version_no),
  constraint fk_template_version_parent_same_tenant
    foreign key (tenant_id, template_id) references engagement.email_template(tenant_id, id)
);

create or replace function engagement.reject_template_version_mutation() returns trigger
language plpgsql as $$
begin
  raise exception
    'Template versions are immutable: save an edit as a new version instead of changing %.', old.id
    using errcode = '42501';
end;
$$;

create trigger trg_template_version_immutable
  before update or delete on engagement.email_template_version
  for each row execute function engagement.reject_template_version_mutation();

create table engagement.email_template_share (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   uuid not null references platform.tenant(id),
  template_id uuid not null,
  role_code   text not null,
  can_edit    boolean not null default false,
  created_by  uuid not null,
  created_at  timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, template_id, role_code),
  constraint fk_template_share_parent_same_tenant
    foreign key (tenant_id, template_id) references engagement.email_template(tenant_id, id)
);

-- -----------------------------------------------------------------------------
-- 9. FR-ACT-008/009 — a send and the engagement signals it produces.
-- -----------------------------------------------------------------------------
create table engagement.email_send (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  template_id        uuid,
  template_version_id uuid,
  activity_id        uuid,
  recipient_type     text not null check (recipient_type in ('CONTACT','LEAD')),
  recipient_id       uuid not null,
  recipient_email    text not null,
  rendered_subject   text not null,
  rendered_body      text not null,
  provider           text not null default 'LOCAL_STUB',
  external_message_id text,
  thread_id          text,
  tracking_enabled   boolean not null default false,
  tracking_reason    text not null,
  sent_by            uuid not null,
  sent_at            timestamptz not null default now(),
  cadence_enrolment_id uuid,
  unique (tenant_id, id),
  constraint fk_email_send_template_same_tenant
    foreign key (tenant_id, template_id) references engagement.email_template(tenant_id, id),
  constraint fk_email_send_activity_same_tenant
    foreign key (tenant_id, activity_id) references engagement.activity(tenant_id, id)
);
create index idx_email_send_recipient on engagement.email_send(tenant_id, recipient_type, recipient_id, sent_at desc);

create table engagement.engagement_signal (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  send_id        uuid,
  signal_type    text not null check (signal_type in ('OPEN','CLICK','REPLY','BOUNCE','UNSUBSCRIBE')),
  subject_type   text not null check (subject_type in ('CONTACT','LEAD')),
  subject_id     uuid not null,
  link_url       text,
  occurred_at    timestamptz not null default now(),
  notified_owner_id uuid,
  provider       text not null default 'LOCAL_STUB',
  external_event_id text,
  unique (tenant_id, id),
  unique (tenant_id, external_event_id),
  constraint fk_signal_send_same_tenant
    foreign key (tenant_id, send_id) references engagement.email_send(tenant_id, id)
);
create index idx_signal_subject on engagement.engagement_signal(tenant_id, subject_type, subject_id, occurred_at desc);

-- -----------------------------------------------------------------------------
-- 10. FR-ACT-010 — cadences.
-- -----------------------------------------------------------------------------
create table engagement.cadence (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  api_name        text not null,
  name            text not null,
  description     text,
  status          text not null default 'DRAFT' check (status in ('DRAFT','ACTIVE','PAUSED','ARCHIVED')),
  exit_on_reply   boolean not null default true,
  business_hours_only boolean not null default true,
  purpose         text not null default 'SALES_OUTREACH'
                  check (purpose in ('MARKETING','SALES_OUTREACH','SERVICE','TRANSACTIONAL','RESEARCH')),
  created_by      uuid not null,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, api_name),
  constraint cadence_api_name_format check (api_name ~ '^[a-z][a-z0-9_]*$')
);

create table engagement.cadence_step (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  cadence_id   uuid not null,
  step_no      int not null check (step_no > 0),
  step_type    text not null check (step_type in ('EMAIL','CALL','TASK')),
  subject      text not null,
  instruction  text,
  delay_days   int not null default 0 check (delay_days >= 0),
  template_id  uuid,
  branch_on    text not null default 'ANY' check (branch_on in ('ANY','ENGAGED','NOT_ENGAGED')),
  created_at   timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, cadence_id, step_no),
  constraint fk_cadence_step_parent_same_tenant
    foreign key (tenant_id, cadence_id) references engagement.cadence(tenant_id, id),
  constraint fk_cadence_step_template_same_tenant
    foreign key (tenant_id, template_id) references engagement.email_template(tenant_id, id),
  constraint cadence_email_step_has_template check (step_type <> 'EMAIL' or template_id is not null)
);

create table engagement.cadence_enrolment (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  cadence_id    uuid not null,
  target_type   text not null check (target_type in ('CONTACT','LEAD')),
  target_id     uuid not null,
  status        text not null default 'ACTIVE' check (status in
                  ('ACTIVE','COMPLETED','EXITED_REPLIED','EXITED_MANUAL','EXITED_SUPPRESSED')),
  current_step_no int not null default 0,
  next_action_at timestamptz,
  enrolled_by   uuid not null,
  enrolled_at   timestamptz not null default now(),
  exited_at     timestamptz,
  exit_reason   text,
  unique (tenant_id, id),
  constraint fk_cadence_enrolment_parent_same_tenant
    foreign key (tenant_id, cadence_id) references engagement.cadence(tenant_id, id),
  constraint enrolment_exit_has_reason check (status = 'ACTIVE' or exit_reason is not null or status = 'COMPLETED')
);
create unique index uq_cadence_enrolment_active
  on engagement.cadence_enrolment(tenant_id, cadence_id, target_type, target_id)
  where status = 'ACTIVE';
create index idx_cadence_enrolment_target
  on engagement.cadence_enrolment(tenant_id, target_type, target_id);

create table engagement.cadence_step_run (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  enrolment_id  uuid not null,
  step_id       uuid not null,
  step_no       int not null,
  outcome       text not null check (outcome in ('EXECUTED','SKIPPED_BRANCH','BLOCKED_CONSENT','FAILED')),
  detail        text,
  activity_id   uuid,
  executed_at   timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_step_run_enrolment_same_tenant
    foreign key (tenant_id, enrolment_id) references engagement.cadence_enrolment(tenant_id, id),
  constraint fk_step_run_step_same_tenant
    foreign key (tenant_id, step_id) references engagement.cadence_step(tenant_id, id)
);
create index idx_step_run_enrolment on engagement.cadence_step_run(tenant_id, enrolment_id, step_no);

-- -----------------------------------------------------------------------------
-- 11. FR-ACT-011 — telephony. `recording_ref` is a REFERENCE to media the
--     provider holds. The CRM never stores the audio.
-- -----------------------------------------------------------------------------
create table engagement.telephony_call (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  provider          text not null default 'LOCAL_STUB'
                    check (provider in ('LOCAL_STUB','GENERIC_SIP','MICROSOFT_TEAMS_PHONE')),
  provider_call_id  text not null,
  direction         text not null check (direction in ('INBOUND','OUTBOUND')),
  from_number       text not null,
  to_number         text not null,
  status            text not null default 'RINGING'
                    check (status in ('RINGING','CONNECTED','COMPLETED','FAILED','NO_ANSWER')),
  matched_type      text check (matched_type is null or matched_type in ('CONTACT','LEAD')),
  matched_id        uuid,
  match_confidence  numeric(4,3),
  match_basis       text,
  agent_user_id     uuid not null,
  activity_id       uuid,
  recording_ref     text,
  duration_seconds  int check (duration_seconds is null or duration_seconds >= 0),
  screen_pop_at     timestamptz,
  started_at        timestamptz not null default now(),
  ended_at          timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, provider, provider_call_id),
  constraint fk_telephony_agent_same_tenant
    foreign key (tenant_id, agent_user_id) references identity.app_user(tenant_id, id),
  constraint fk_telephony_activity_same_tenant
    foreign key (tenant_id, activity_id) references engagement.activity(tenant_id, id),
  constraint telephony_recording_is_reference
    check (recording_ref is null or (length(recording_ref) <= 512 and recording_ref not like 'data:%'))
);
create index idx_telephony_call_recent on engagement.telephony_call(tenant_id, started_at desc);

-- -----------------------------------------------------------------------------
-- 12. FR-ACT-012 — conversation intelligence. Derived insight only; the
--     transcript stays with the provider and we keep a reference to it.
-- -----------------------------------------------------------------------------
create table engagement.conversation_insight (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references platform.tenant(id),
  activity_id         uuid not null,
  opportunity_id      uuid,
  transcript_ref      text not null,
  extraction_method   text not null default 'DETERMINISTIC_LOCAL'
                      check (extraction_method in ('DETERMINISTIC_LOCAL','EXTERNAL_MODEL')),
  topics              jsonb not null default '[]'::jsonb,
  competitor_mentions jsonb not null default '[]'::jsonb,
  next_steps          jsonb not null default '[]'::jsonb,
  talk_ratio_rep      numeric(5,2),
  talk_ratio_customer numeric(5,2),
  word_count          int,
  created_by          uuid not null,
  created_at          timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, activity_id),
  constraint fk_insight_activity_same_tenant
    foreign key (tenant_id, activity_id) references engagement.activity(tenant_id, id),
  constraint fk_insight_opportunity_same_tenant
    foreign key (tenant_id, opportunity_id) references sales.opportunity(tenant_id, id),
  constraint insight_transcript_is_reference
    check (length(transcript_ref) <= 512 and transcript_ref not like 'data:%')
);
create index idx_insight_opportunity on engagement.conversation_insight(tenant_id, opportunity_id);

-- -----------------------------------------------------------------------------
-- 13. RLS on every new tenant-scoped table + grants.
-- -----------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array[
    'activity_relation','mailbox_connection','capture_consent','capture_exclusion',
    'capture_review_item','capture_run','tenant_engagement_policy','email_template',
    'email_template_version','email_template_share','email_send','engagement_signal',
    'cadence','cadence_step','cadence_enrolment','cadence_step_run','telephony_call',
    'conversation_insight'
  ]
  loop
    execute format('alter table engagement.%I enable row level security', t);
    execute format('alter table engagement.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on engagement.%I '
      'using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
  end loop;
end $$;

grant select, insert, update on
  engagement.activity_relation, engagement.mailbox_connection, engagement.capture_exclusion,
  engagement.capture_review_item, engagement.capture_run, engagement.tenant_engagement_policy,
  engagement.email_template, engagement.email_template_version, engagement.email_template_share,
  engagement.email_send, engagement.engagement_signal, engagement.cadence,
  engagement.cadence_step, engagement.cadence_enrolment, engagement.cadence_step_run,
  engagement.telephony_call, engagement.conversation_insight
  to axiom_app;

-- Consent is append-only evidence: insert and read, never rewrite.
grant select, insert on engagement.capture_consent to axiom_app;

-- FR-ACT-007 requires that excluded items be PURGED, not flagged. Purging is a
-- DELETE, and it is the only reason these grants exist.
grant delete on
  engagement.activity, engagement.activity_participant, engagement.activity_relation,
  engagement.capture_review_item, engagement.email_send, engagement.engagement_signal,
  engagement.conversation_insight
  to axiom_app;

-- -----------------------------------------------------------------------------
-- 14. Governance registration: screens, RBAC, table catalogue, value sets.
-- -----------------------------------------------------------------------------
insert into governance.screen_catalog(screen_code, module_code, route, display_name, description, sort_order) values
  ('ENGAGEMENT_CAPTURE', 'ENGAGEMENT', '/engagement/capture', 'Capture and privacy',
   'Mailbox and calendar connections, capture consent, exclusions and the capture review queue.', 56),
  ('EMAIL_TEMPLATES', 'ENGAGEMENT', '/engagement/templates', 'Email templates',
   'Versioned email templates with merge fields, folders and permission-scoped sharing.', 57),
  ('CADENCES', 'ENGAGEMENT', '/engagement/cadences', 'Cadences',
   'Multi-step outreach sequences, enrolment and engagement signals.', 58)
on conflict (screen_code) do nothing;

insert into governance.rbac_policy(role_code, screen_code, can_read, can_write, can_export, can_admin, scope)
select roles.role_code, screens.screen_code,
       roles.role_code <> 'INTEGRATION',
       roles.role_code not in ('SUPER_AUDIT','AUDITOR','INTEGRATION'),
       roles.role_code <> 'INTEGRATION',
       roles.role_code in ('SUPER_ADMIN','TENANT_ADMIN'),
       case when roles.role_code in ('SUPER_ADMIN','SUPER_AUDIT') then 'PLATFORM' else 'TENANT' end
from (values
  ('SUPER_ADMIN'),('SUPER_AUDIT'),('TENANT_ADMIN'),('SALES_MANAGER'),('SALES'),
  ('MARKETING'),('SERVICE'),('OPERATIONS'),('FINANCE'),('DATA_STEWARD'),('AUDITOR'),('INTEGRATION')
) roles(role_code)
cross join (values ('ENGAGEMENT_CAPTURE'),('EMAIL_TEMPLATES'),('CADENCES')) screens(screen_code)
on conflict (role_code, screen_code) do update
  set can_read = excluded.can_read,
      can_write = excluded.can_write,
      can_export = excluded.can_export,
      can_admin = excluded.can_admin,
      scope = excluded.scope;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('engagement','activity_relation','ENGAGEMENT','id',true,'ACTIVE'),
  ('engagement','mailbox_connection','ENGAGEMENT','id',true,'ACTIVE'),
  ('engagement','capture_consent','ENGAGEMENT','id',true,'APPEND_ONLY'),
  ('engagement','capture_exclusion','ENGAGEMENT','id',true,'ACTIVE'),
  ('engagement','capture_review_item','ENGAGEMENT','id',true,'ACTIVE'),
  ('engagement','capture_run','ENGAGEMENT','id',true,'APPEND_ONLY'),
  ('engagement','tenant_engagement_policy','ENGAGEMENT','tenant_id',true,'ACTIVE'),
  ('engagement','email_template','ENGAGEMENT','id',true,'ACTIVE'),
  ('engagement','email_template_version','ENGAGEMENT','id',true,'APPEND_ONLY'),
  ('engagement','email_template_share','ENGAGEMENT','id',true,'ACTIVE'),
  ('engagement','email_send','ENGAGEMENT','id',true,'APPEND_ONLY'),
  ('engagement','engagement_signal','ENGAGEMENT','id',true,'APPEND_ONLY'),
  ('engagement','cadence','ENGAGEMENT','id',true,'ACTIVE'),
  ('engagement','cadence_step','ENGAGEMENT','id',true,'ACTIVE'),
  ('engagement','cadence_enrolment','ENGAGEMENT','id',true,'ACTIVE'),
  ('engagement','cadence_step_run','ENGAGEMENT','id',true,'APPEND_ONLY'),
  ('engagement','telephony_call','ENGAGEMENT','id',true,'ACTIVE'),
  ('engagement','conversation_insight','ENGAGEMENT','id',true,'ACTIVE')
on conflict (schema_name, table_name) do nothing;

insert into reference.value_set (tenant_id, api_name, label, module, description)
select t.id, seed.api_name, seed.label, 'ENGAGEMENT', seed.description
from platform.tenant t
cross join (values
  ('capture_source', 'Capture source', 'How an activity reached the timeline'),
  ('capture_match_basis', 'Capture match basis', 'Why automatic capture related an item to a record'),
  ('cadence_step_type', 'Cadence step type', 'Step kinds available inside an outreach cadence'),
  ('engagement_signal_type', 'Engagement signal type', 'Recipient engagement events surfaced to the record owner'),
  ('competitor_watchlist', 'Competitor watchlist', 'Competitor names conversation intelligence looks for in transcripts'),
  ('conversation_topic', 'Conversation topic', 'Topics conversation intelligence extracts from call transcripts')
) as seed(api_name, label, description)
on conflict (tenant_id, api_name) do nothing;

insert into reference.value_set_entry (tenant_id, value_set_id, code, label, sort_order, system_managed)
select vs.tenant_id, vs.id, seed.code, seed.label, seed.sort_order, true
from reference.value_set vs
join (values
  ('capture_source','MANUAL','Logged by a user',10),
  ('capture_source','AUTO','Captured automatically',20),
  ('capture_source','API','Created through the API',30),
  ('capture_source','AI','Created by an assistant',40),
  ('capture_match_basis','EXACT_CONTACT_EMAIL','Exact contact email match',10),
  ('capture_match_basis','EXACT_LEAD_EMAIL','Exact lead email match',20),
  ('capture_match_basis','MULTIPLE_EXACT_MATCHES','More than one record shares this address',30),
  ('capture_match_basis','ACCOUNT_DOMAIN_ONLY','Only the email domain matched an account',40),
  ('capture_match_basis','NO_PARTICIPANT_MATCH','No participant matched any record',50),
  ('capture_match_basis','USER_CONFIRMED','Confirmed by a user from the review queue',60),
  ('cadence_step_type','EMAIL','Email',10),
  ('cadence_step_type','CALL','Call',20),
  ('cadence_step_type','TASK','Task',30),
  ('engagement_signal_type','OPEN','Email opened',10),
  ('engagement_signal_type','CLICK','Link clicked',20),
  ('engagement_signal_type','REPLY','Replied',30),
  ('engagement_signal_type','BOUNCE','Bounced',40),
  ('engagement_signal_type','UNSUBSCRIBE','Unsubscribed',50),
  ('competitor_watchlist','NORTHWIND','Northwind Systems',10),
  ('competitor_watchlist','ACME','Acme Industrial',20),
  ('competitor_watchlist','VERTEX','Vertex Controls',30),
  ('conversation_topic','PRICING','Pricing and discount',10),
  ('conversation_topic','SECURITY','Security and compliance',20),
  ('conversation_topic','INTEGRATION','Integration and migration',30),
  ('conversation_topic','TIMELINE','Timeline and go-live',40),
  ('conversation_topic','BUDGET','Budget and approval',50),
  ('conversation_topic','SUPPORT','Support and service levels',60)
) as seed(api_name, code, label, sort_order) on seed.api_name = vs.api_name
on conflict (tenant_id, value_set_id, code) do nothing;

-- -----------------------------------------------------------------------------
-- 15. Demo seed for the running stack.
--
-- The seeded LEAD deliberately reuses contact Lindiwe Mbeki's email address at a
-- different company. That is not an accident: it makes the AMBIGUOUS capture
-- path reproducible on the dev stack without hand-crafted fixtures, which is
-- exactly the case FR-ACT-006 says must never be guessed.
-- -----------------------------------------------------------------------------
insert into crm.lead (id, tenant_id, first_name, last_name, company, email, status, owner_id)
select '77777777-7777-7777-7777-7777777777a1'::uuid, t.id, 'Lindiwe', 'Mbeki',
       'Kestrel Components Division', 'l.mbeki@kestrelmfg.com', 'NEW', u.id
from platform.tenant t
join identity.app_user u on u.tenant_id = t.id and u.email = 'priya.nair@meridianfab.com'
where t.slug = 'meridian'
on conflict (tenant_id, id) do nothing;

-- Kestrel's account domain, so the domain-only match path is reachable too.
update crm.account set email_domain = 'kestrelmfg.com'
where id = '44444444-4444-4444-4444-444444444401' and coalesce(email_domain, '') = '';

-- A connected mailbox for the tenant administrator, plus the recorded consent
-- that makes capture lawful. Consent first: the connection is useless without it.
insert into engagement.capture_consent (tenant_id, user_id, scope, state, granted_at, source, recorded_by, note)
select t.id, u.id, 'ALL', 'GRANTED', now() - interval '2 days', 'UI', u.id,
       'Demo seed: administrator granted email and calendar capture consent.'
from platform.tenant t
join identity.app_user u on u.tenant_id = t.id and u.email = 'raj.malhotra@meridianfab.com'
where t.slug = 'meridian';

insert into engagement.mailbox_connection
  (id, tenant_id, user_id, provider, email_address, status, external_account_ref, created_by, last_sync_at)
select 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb01'::uuid, t.id, u.id, 'LOCAL_STUB',
       u.email, 'CONNECTED', 'stub-account/raj.malhotra', u.id, null
from platform.tenant t
join identity.app_user u on u.tenant_id = t.id and u.email = 'raj.malhotra@meridianfab.com'
where t.slug = 'meridian'
on conflict (tenant_id, user_id, provider) do nothing;

-- A template whose second merge field cannot resolve for a contact with no open
-- opportunity — the FR-ACT-008 block, demonstrable without setup.
insert into engagement.email_template
  (id, tenant_id, folder, api_name, name, description, current_version, sharing_scope, owner_id, created_by)
select 'cccccccc-cccc-cccc-cccc-cccccccccc01'::uuid, t.id, 'Prospecting', 'deal_recap',
       'Deal recap', 'Recaps the named opportunity for the recipient.', 1, 'TENANT', u.id, u.id
from platform.tenant t
join identity.app_user u on u.tenant_id = t.id and u.email = 'raj.malhotra@meridianfab.com'
where t.slug = 'meridian'
on conflict (tenant_id, api_name) do nothing;

insert into engagement.email_template_version
  (tenant_id, template_id, version_no, subject, body, merge_fields, change_note, created_by)
select tpl.tenant_id, tpl.id, 1,
       'Recap: {{opportunity.name}}',
       'Hello {{contact.firstName}},'
       || chr(10) || chr(10) ||
       'Thank you for your time. Recapping {{opportunity.name}} at {{account.name}}, '
       || 'currently valued at {{opportunity.amount}}.'
       || chr(10) || chr(10) || 'Regards,' || chr(10) || '{{user.displayName}}',
       '["opportunity.name","contact.firstName","account.name","opportunity.amount","user.displayName"]'::jsonb,
       'Initial version.', tpl.created_by
from engagement.email_template tpl
where tpl.api_name = 'deal_recap'
on conflict (tenant_id, template_id, version_no) do nothing;

insert into engagement.email_template
  (id, tenant_id, folder, api_name, name, description, current_version, sharing_scope, owner_id, created_by)
select 'cccccccc-cccc-cccc-cccc-cccccccccc02'::uuid, t.id, 'Prospecting', 'first_touch',
       'First touch', 'Opening outreach that only needs recipient fields.', 1, 'TENANT', u.id, u.id
from platform.tenant t
join identity.app_user u on u.tenant_id = t.id and u.email = 'raj.malhotra@meridianfab.com'
where t.slug = 'meridian'
on conflict (tenant_id, api_name) do nothing;

insert into engagement.email_template_version
  (tenant_id, template_id, version_no, subject, body, merge_fields, change_note, created_by)
select tpl.tenant_id, tpl.id, 1,
       'Introduction for {{contact.firstName}}',
       'Hello {{contact.firstName}},' || chr(10) || chr(10)
       || 'I look after fabrication accounts and wanted to introduce myself.'
       || chr(10) || chr(10) || 'Regards,' || chr(10) || '{{user.displayName}}',
       '["contact.firstName","user.displayName"]'::jsonb,
       'Initial version.', tpl.created_by
from engagement.email_template tpl
where tpl.api_name = 'first_touch'
on conflict (tenant_id, template_id, version_no) do nothing;

-- A three-step cadence: email, then a call branch for the disengaged, then a task.
insert into engagement.cadence (id, tenant_id, api_name, name, description, status, exit_on_reply, created_by)
select 'dddddddd-dddd-dddd-dddd-dddddddddd01'::uuid, t.id, 'new_logo_outreach', 'New logo outreach',
       'Three-step opening sequence for unworked fabrication prospects.', 'ACTIVE', true, u.id
from platform.tenant t
join identity.app_user u on u.tenant_id = t.id and u.email = 'raj.malhotra@meridianfab.com'
where t.slug = 'meridian'
on conflict (tenant_id, api_name) do nothing;

insert into engagement.cadence_step (tenant_id, cadence_id, step_no, step_type, subject, instruction, delay_days, template_id, branch_on)
select c.tenant_id, c.id, s.step_no, s.step_type, s.subject, s.instruction, s.delay_days,
       case when s.step_type = 'EMAIL' then (select id from engagement.email_template where tenant_id = c.tenant_id and api_name = 'first_touch') end,
       s.branch_on
from engagement.cadence c
join (values
  (1, 'EMAIL', 'Opening introduction', 'Send the first-touch template.', 0, 'ANY'),
  (2, 'CALL',  'Follow-up call',       'Call anyone who has not engaged with the email.', 2, 'NOT_ENGAGED'),
  (3, 'TASK',  'Research and re-plan', 'Review account fit and decide whether to continue.', 5, 'ANY')
) s(step_no, step_type, subject, instruction, delay_days, branch_on) on true
where c.api_name = 'new_logo_outreach'
on conflict (tenant_id, cadence_id, step_no) do nothing;

-- Contact Tao Sun is deliberately SUPPRESSED for email outreach so the
-- FR-ACT-010 enrolment refusal is demonstrable on the dev stack.
insert into crm.consent_record
  (tenant_id, subject_type, subject_id, channel, purpose, state, lawful_basis, source, withdrawn_at, note)
select t.id, 'CONTACT', '55555555-5555-5555-5555-555555555503'::uuid, 'EMAIL', 'SALES_OUTREACH',
       'WITHDRAWN', 'CONSENT', 'PREFERENCE_CENTRE', now() - interval '10 days',
       'Demo seed: recipient withdrew email outreach consent.'
from platform.tenant t
where t.slug = 'meridian';

-- Contact David Farrow has granted consent, so he is enrollable.
insert into crm.consent_record
  (tenant_id, subject_type, subject_id, channel, purpose, state, lawful_basis, source, granted_at, note)
select t.id, 'CONTACT', '55555555-5555-5555-5555-555555555501'::uuid, 'EMAIL', 'SALES_OUTREACH',
       'GRANTED', 'CONSENT', 'WEB_FORM', now() - interval '30 days',
       'Demo seed: recipient granted email outreach consent.'
from platform.tenant t
where t.slug = 'meridian';

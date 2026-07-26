-- ---------------------------------------------------------------------------
-- E18 — migration and onboarding engine (FRD §23 FR-MIG-001..010,
-- 13-integration-and-migration.md §3, ADR-007, system-design.md §3.3).
--
-- WHAT THIS MODULE IS FOR
-- Migration is where CRM replacements die: a one-way, best-effort import done
-- under time pressure and discovered incomplete after the old system is off.
-- The whole shape of this schema follows from the opposite premises stated in
-- 13-integration-and-migration.md §3 — *verify before you write, reconcile
-- after you write, and keep the exit open until the customer closes it.*
--
-- Three tables carry the differentiation and everything else supports them:
--
--   migration.field_mapping      every source field is a row, including the
--                                ones nobody mapped. FR-MIG-002 forbids silent
--                                omission, so "unmapped" is a stored state with
--                                an explicit acknowledgement, not an absence.
--   migration.record_map         one row per (source record -> target record).
--                                This is simultaneously the rollback ledger
--                                (FR-MIG-007 — we can only remove what we can
--                                prove we created) and the delta-resync
--                                identity map (FR-MIG-008 — a second run finds
--                                the existing target instead of duplicating).
--   migration.run_issue          every reason a record did not land, with BOTH
--                                endpoints named for a broken relationship
--                                (FR-MIG-004). An orphan is a defect report.
--
-- NOTE ON `nullif(current_setting('app.tenant_id', true), '')::uuid`
-- Repeated from V10/V13/V240 because it is load-bearing. TenantSessionAspect
-- uses set_config(..., true) == SET LOCAL; when that transaction ends
-- PostgreSQL restores the placeholder GUC to the EMPTY STRING, not NULL, and a
-- bare ''::uuid cast raises `invalid input syntax for type uuid: ""` on the
-- next pooled connection. nullif() turns it into NULL, the comparison is NULL,
-- and the row is filtered out — the correct outcome for an unbound connection.
--
-- NOTE ON THE WORKER TIER
-- system-design §3.3 lists the migration engine as an extraction candidate:
-- long-running, resource-hungry, episodic. So migration.run is a queue row, not
-- a request. The API writes QUEUED and returns a job handle; MigrationWorker
-- drains it in bounded batches and writes progress back here. No HTTP request
-- is ever held open across an import.
-- ---------------------------------------------------------------------------

create schema if not exists migration;
grant usage on schema migration to axiom_app;

-- The runtime search_path is a shared resource: other module migrations land in
-- an order this file cannot know. Merge rather than replace (the V70/V90/V240
-- pattern) so a schema added by a migration that ran earlier is not dropped.
-- 'migration' is appended LAST (immediately before public) on purpose: this
-- module owns generic-sounding relation names (plan, run) and must never
-- shadow another module's unqualified table. Every statement in
-- com.axiom.migration schema-qualifies anyway.
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
  if not ('migration' = any(merged)) then
    merged := array_append(merged, 'migration');
  end if;
  merged := array_append(merged, 'public');
  execute format('alter role axiom_app set search_path to %s', array_to_string(merged, ', '));
end $$;

-- ---------------------------------------------------------------------------
-- 1. Source connection (FR-MIG-001, F-285/286/287)
--
-- `scope` is a CHECK, not a column the caller picks: ADR-007 puts source
-- systems behind an anti-corruption layer and the importer's read-only scope is
-- a trust statement as much as a safety one. The importer must be technically
-- incapable of damaging the source during a parallel run, so the only value the
-- database will accept is READ_ONLY. Widening it is a migration, reviewable.
--
-- `fixture_wave` exists only for vendor FIXTURE. A deterministic local source
-- cannot "change" between runs the way a live CRM does, so the wave is the
-- simulated passage of time in the source system: wave 1 is what existed at the
-- initial import, wave 2 adds and edits records for the delta re-sync. It is a
-- test affordance and is documented as one.
-- ---------------------------------------------------------------------------
create table migration.source_connection (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  name             text not null,
  vendor           text not null check (vendor in ('SALESFORCE','ZOHO','HUBSPOT','FIXTURE')),
  scope            text not null default 'READ_ONLY' check (scope = 'READ_ONLY'),
  status           text not null default 'PENDING'
                   check (status in ('PENDING','CONNECTED','FAILED','REVOKED')),
  instance_url     text,
  credential_ref   text,                       -- SecretCipher envelope; never plaintext
  fixture_key      text,
  fixture_wave     integer not null default 1 check (fixture_wave >= 1),
  discovered_at    timestamptz,
  last_verified_at timestamptz,
  message          text,
  created_by       uuid,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  constraint source_connection_fixture_needs_key
    check (vendor <> 'FIXTURE' or fixture_key is not null)
);
create unique index uq_source_connection_name on migration.source_connection (tenant_id, lower(name));
create index idx_source_connection_tenant on migration.source_connection (tenant_id, created_at desc);

-- ---------------------------------------------------------------------------
-- 2. Discovered schema (FR-MIG-002, F-288)
--
-- Discovery persists the source's own vocabulary verbatim — including custom
-- objects and fields — before any translation happens. That ordering is the
-- anti-corruption layer working: what the vendor called it is evidence, and the
-- mapping to Axiom vocabulary is a separate, reviewable artefact.
-- ---------------------------------------------------------------------------
create table migration.source_object (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  connection_id  uuid not null references migration.source_connection(id) on delete cascade,
  api_name       text not null,
  label          text not null,
  is_custom      boolean not null default false,
  record_count   bigint not null default 0,
  proposed_target text,
  discovered_at  timestamptz not null default now()
);
create unique index uq_source_object on migration.source_object (tenant_id, connection_id, api_name);

create table migration.source_field (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  connection_id  uuid not null references migration.source_connection(id) on delete cascade,
  object_api_name text not null,
  api_name       text not null,
  label          text not null,
  data_type      text not null,
  is_custom      boolean not null default false,
  nullable       boolean not null default true,
  sample_value   text,
  discovered_at  timestamptz not null default now()
);
create unique index uq_source_field on migration.source_field (tenant_id, connection_id, object_api_name, api_name);

-- ---------------------------------------------------------------------------
-- 3. Plan — one migration project over one connection.
--
-- `unmapped_acknowledged_at` is the enforcement point for the FR-MIG-002 rule.
-- A plan cannot be imported until a named person has acknowledged, in writing
-- and with a timestamp, the exact list of source fields that will NOT come
-- across. Discovering a missing field during mapping review costs a minute;
-- discovering it six months after the source is decommissioned costs the data.
--
-- `retention_days` is the FR-MIG-007 rollback window. `is_sample_data` marks a
-- plan whose records are the clearly-marked evaluation environment of
-- FR-MIG-010 — which is exactly why sample data is a *plan*: "separately
-- deletable" then costs nothing new, it is the rollback that already exists.
-- ---------------------------------------------------------------------------
create table migration.plan (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  connection_id uuid not null references migration.source_connection(id),
  name          text not null,
  status        text not null default 'DRAFT'
                check (status in ('DRAFT','MAPPED','ACKNOWLEDGED','IMPORTED','ROLLED_BACK')),
  retention_days integer not null default 30 check (retention_days between 1 and 365),
  is_sample_data boolean not null default false,
  unmapped_acknowledged_at timestamptz,
  unmapped_acknowledged_by uuid,
  unmapped_acknowledged_count integer not null default 0,
  delta_watermark timestamptz,
  imported_at   timestamptz,
  created_by    uuid,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create unique index uq_migration_plan_name on migration.plan (tenant_id, lower(name));
create index idx_migration_plan_tenant on migration.plan (tenant_id, created_at desc);

-- ---------------------------------------------------------------------------
-- 4. Field mapping (FR-MIG-002)
--
-- Every discovered source field gets a row. status='UNMAPPED' is a first-class
-- state carrying data that WILL BE LOST, and the API surfaces it as its own
-- list rather than as the absence of a mapping — because an absence is exactly
-- what nobody notices.
-- ---------------------------------------------------------------------------
create table migration.field_mapping (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  plan_id        uuid not null references migration.plan(id) on delete cascade,
  source_object  text not null,
  source_field   text not null,
  source_data_type text not null default 'TEXT',
  is_custom      boolean not null default false,
  target_entity  text,
  target_field   text,
  transform      text,
  status         text not null default 'UNMAPPED'
                 check (status in ('MAPPED','UNMAPPED','IGNORED')),
  origin         text not null default 'PROPOSED' check (origin in ('PROPOSED','USER')),
  note           text,
  updated_at     timestamptz not null default now(),
  constraint field_mapping_mapped_has_target
    check (status <> 'MAPPED' or (target_entity is not null and target_field is not null))
);
create unique index uq_field_mapping on migration.field_mapping (tenant_id, plan_id, source_object, source_field);
create index idx_field_mapping_unmapped on migration.field_mapping (tenant_id, plan_id) where status = 'UNMAPPED';

-- ---------------------------------------------------------------------------
-- 5. Run — the job handle (FR-MIG-003, 006, 007, 008; system-design §3.3)
--
-- QUEUED is written by the request thread and nothing else. Every unit of work
-- happens on the worker tier. processed_units/total_units are counts of real
-- records, not a spinner pretending to know.
-- ---------------------------------------------------------------------------
create table migration.run (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  plan_id          uuid not null references migration.plan(id) on delete cascade,
  mode             text not null check (mode in ('DRY_RUN','IMPORT','DELTA','ROLLBACK')),
  status           text not null default 'QUEUED'
                   check (status in ('QUEUED','RUNNING','COMPLETED','FAILED')),
  phase            text,
  total_units      bigint not null default 0,
  processed_units  bigint not null default 0,
  records_created  bigint not null default 0,
  records_updated  bigint not null default 0,
  records_skipped  bigint not null default 0,
  records_removed  bigint not null default 0,
  issue_count      bigint not null default 0,
  delta_since      timestamptz,
  source_watermark timestamptz,
  rollback_of_run  uuid references migration.run(id),
  requested_by     uuid,
  queued_at        timestamptz not null default now(),
  started_at       timestamptz,
  finished_at      timestamptz,
  message          text
);
create index idx_migration_run_tenant on migration.run (tenant_id, queued_at desc);
create index idx_migration_run_pending on migration.run (status, queued_at) where status in ('QUEUED','RUNNING');
create index idx_migration_run_plan on migration.run (tenant_id, plan_id, queued_at desc);

-- ---------------------------------------------------------------------------
-- 6. Record map — the rollback ledger AND the delta identity map.
--
-- One row per source record that produced a target record. It is deliberately
-- NOT derived from a marker column on the business tables: a `source_system`
-- text column is editable by any user with edit rights, and rollback that
-- trusts it would delete rows a user re-tagged by accident. The ledger is
-- owned by this module and is the only thing rollback will act on — which is
-- what makes "removes exactly what it created, and nothing else" a property
-- rather than a hope.
--
-- `target_fingerprint` is taken at write time. If a user edits a migrated
-- record afterwards the fingerprint no longer matches, and the rollback preview
-- flags it (13-integration-and-migration.md §3.6: "records the migration
-- created and users subsequently modified are flagged in the rollback preview
-- — the operator decides with full information, not after the fact").
-- ---------------------------------------------------------------------------
create table migration.record_map (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  plan_id            uuid not null references migration.plan(id) on delete cascade,
  source_object      text not null,
  source_record_id   text not null,
  source_label       text,
  target_entity      text not null
                     check (target_entity in ('ACCOUNT','CONTACT','LEAD','OPPORTUNITY','ACTIVITY','OPPORTUNITY_CONTACT_ROLE')),
  target_id          uuid not null,
  target_fingerprint text,
  -- Taken at write time. The rollback preview compares them against the row as
  -- it stands now, so a record the migration created and a user has since edited
  -- is FLAGGED before anything is removed rather than discovered afterwards
  -- (13-integration-and-migration.md §3.6).
  target_version     bigint,
  target_updated_at  timestamptz,
  source_modified_at timestamptz,
  -- FR-MIG-005: original timestamps and actors preserved as RECORDED VALUES.
  -- Deliberately not resolved onto an Axiom user: the person who logged a note
  -- in the old system may never have had an Axiom account, and inventing a
  -- mapping would attribute their work to somebody else.
  source_created_at  timestamptz,
  source_actor       text,
  state              text not null default 'LIVE' check (state in ('LIVE','ROLLED_BACK')),
  created_run_id     uuid not null references migration.run(id),
  last_run_id        uuid not null references migration.run(id),
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
create unique index uq_record_map_source on migration.record_map (tenant_id, plan_id, source_object, source_record_id);
create index idx_record_map_target on migration.record_map (tenant_id, target_entity, target_id);
create index idx_record_map_live on migration.record_map (tenant_id, plan_id, target_entity) where state = 'LIVE';

-- ---------------------------------------------------------------------------
-- 7. Run issue — every reason something did not land (FR-MIG-003/004/006/007)
--
-- related_* is the FR-MIG-004 clause made structural: a referential gap cannot
-- be recorded without naming the OTHER endpoint, because the columns are right
-- there and a report that says "contact orphaned" without saying which account
-- it wanted is not a defect report, it is a shrug.
-- ---------------------------------------------------------------------------
create table migration.run_issue (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  run_id           uuid not null references migration.run(id) on delete cascade,
  severity         text not null default 'ERROR' check (severity in ('ERROR','WARNING','INFO')),
  category         text not null check (category in
                     ('VALIDATION','DUPLICATE','REFERENTIAL_GAP','UNMAPPED_FIELD','SKIPPED',
                      'ROLLBACK_REMOVED','ROLLBACK_BLOCKED','MODIFIED_SINCE_MIGRATION')),
  source_object    text,
  source_record_id text,
  source_label     text,
  field_name       text,
  related_object   text,
  related_record_id text,
  related_label    text,
  reason           text not null,
  created_at       timestamptz not null default now()
);
create index idx_run_issue_run on migration.run_issue (tenant_id, run_id, category);

-- ---------------------------------------------------------------------------
-- 8. Reconciliation (FR-MIG-006, F-290)
--
-- Counts catch missing records; sums catch value corruption — a currency
-- mis-mapped or a decimal shifted shows up as a sum mismatch even when the
-- counts tie out. Both are stored, per object, so the report is generated by
-- the system rather than assembled by hand.
-- ---------------------------------------------------------------------------
create table migration.reconciliation_line (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  run_id            uuid not null references migration.run(id) on delete cascade,
  source_object     text not null,
  target_entity     text not null,
  source_count      bigint not null default 0,
  target_count      bigint not null default 0,
  not_migrated_count bigint not null default 0,
  source_amount_sum numeric(20,2),
  target_amount_sum numeric(20,2),
  currency_code     text,
  balanced          boolean not null default false,
  created_at        timestamptz not null default now()
);
create unique index uq_reconciliation_line on migration.reconciliation_line (tenant_id, run_id, source_object);

-- ---------------------------------------------------------------------------
-- 9. History migration (FR-MIG-005, F-294)
--
-- Axiom has no document store epic yet, so an attachment migrates as a
-- catalogued reference — original filename, size, author and timestamp
-- preserved as recorded values — attached to the target record. That is an
-- honest partial: the metadata and the linkage survive the system boundary and
-- the bytes are named rather than silently dropped. Notes and activity history
-- migrate fully, into engagement.activity.
-- ---------------------------------------------------------------------------
create table migration.migrated_attachment (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  plan_id           uuid not null references migration.plan(id) on delete cascade,
  run_id            uuid not null references migration.run(id) on delete cascade,
  source_record_id  text not null,
  target_entity     text not null,
  target_id         uuid not null,
  file_name         text not null,
  content_type      text,
  byte_size         bigint,
  external_ref      text,
  original_author   text,
  original_created_at timestamptz,
  created_at        timestamptz not null default now()
);
create index idx_migrated_attachment_target on migration.migrated_attachment (tenant_id, target_entity, target_id);

-- ---------------------------------------------------------------------------
-- 10. Guided onboarding (FR-MIG-009, F-295/F-298)
--
-- Role-specific: an administrator's first week and a salesperson's first week
-- are different jobs, and one shared checklist means everybody ignores most of
-- it. Completion is tracked per tenant per role.
-- ---------------------------------------------------------------------------
create table migration.onboarding_task (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  role         text not null,
  task_key     text not null,
  title        text not null,
  description  text not null,
  route        text,
  sort_order   integer not null default 0,
  completed_at timestamptz,
  completed_by uuid,
  created_at   timestamptz not null default now()
);
create unique index uq_onboarding_task on migration.onboarding_task (tenant_id, role, task_key);

-- ---------------------------------------------------------------------------
-- 11. Configuration templates (FR-MIG-010, F-296/F-297)
--
-- The catalogue is platform-wide and read-only to tenants — it is product
-- content, not tenant data, so it carries no tenant_id and no RLS policy (the
-- same treatment reference catalogues get). Adoption IS tenant data and is
-- tenant-scoped with RLS.
-- ---------------------------------------------------------------------------
create table migration.config_template (
  template_key   text primary key,
  name           text not null,
  industry       text not null,
  company_size   text not null check (company_size in ('SMALL','MID','LARGE','ANY')),
  description    text not null,
  payload        jsonb not null default '{}'::jsonb,
  is_sample_data boolean not null default false,
  sort_order     integer not null default 0
);

create table migration.template_adoption (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  template_key text not null references migration.config_template(template_key),
  applied_at   timestamptz not null default now(),
  applied_by   uuid,
  note         text
);
create index idx_template_adoption_tenant on migration.template_adoption (tenant_id, applied_at desc);

-- ---------------------------------------------------------------------------
-- Row-level security. Every tenant-scoped table in this module.
-- ---------------------------------------------------------------------------
do $$
declare t text;
begin
  foreach t in array array[
    'migration.source_connection',
    'migration.source_object',
    'migration.source_field',
    'migration.plan',
    'migration.field_mapping',
    'migration.run',
    'migration.record_map',
    'migration.run_issue',
    'migration.reconciliation_line',
    'migration.migrated_attachment',
    'migration.onboarding_task',
    'migration.template_adoption'
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
end
$$;

grant select, insert, update, delete on
  migration.source_connection, migration.source_object, migration.source_field,
  migration.plan, migration.field_mapping, migration.run, migration.record_map,
  migration.run_issue, migration.reconciliation_line, migration.migrated_attachment,
  migration.onboarding_task, migration.template_adoption
  to axiom_app;

-- Product content: readable by every tenant, writable by nobody at runtime.
grant select on migration.config_template to axiom_app;

-- ---------------------------------------------------------------------------
-- Seed: configuration templates by industry and company size (FR-MIG-010).
-- ---------------------------------------------------------------------------
insert into migration.config_template (template_key, name, industry, company_size, description, payload, is_sample_data, sort_order) values
  ('MANUFACTURING_MID', 'Manufacturing — mid-market', 'MANUFACTURING', 'MID',
   'Distributor and OEM account hierarchies, quote-to-order pipeline, territory-based lead routing.',
   '{"pipeline":"Default Pipeline","leadRouting":"TERRITORY","accountHierarchy":true,"quoteApproval":"SINGLE_STEP"}'::jsonb, false, 10),
  ('BFSI_LARGE', 'Banking and financial services — enterprise', 'BFSI', 'LARGE',
   'Regulated onboarding checkpoints, segregation-of-duties on approvals, product-interest driven routing.',
   '{"pipeline":"Default Pipeline","leadRouting":"SEGMENT","sodEnforced":true,"quoteApproval":"TWO_STEP"}'::jsonb, false, 20),
  ('SAAS_SMALL', 'B2B SaaS — small team', 'SAAS', 'SMALL',
   'Single shared pipeline, round-robin lead assignment, lightweight forecast categories.',
   '{"pipeline":"Default Pipeline","leadRouting":"ROUND_ROBIN","accountHierarchy":false,"quoteApproval":"NONE"}'::jsonb, false, 30),
  ('PROFESSIONAL_SERVICES_MID', 'Professional services — mid-market', 'PROFESSIONAL_SERVICES', 'MID',
   'Engagement-shaped opportunities, contact-role heavy buying groups, milestone close dates.',
   '{"pipeline":"Default Pipeline","leadRouting":"OWNER","buyingGroups":true,"quoteApproval":"SINGLE_STEP"}'::jsonb, false, 40),
  ('SAMPLE_EVALUATION', 'Sample data — evaluation and training', 'ANY', 'ANY',
   'A clearly marked, separately deletable evaluation dataset. Installed as its own migration plan so removing it is the same audited rollback used for a real migration — evaluation data can never become entangled with migrated production data.',
   '{"fixtureKey":"axiom-sample","plan":"Sample data (evaluation)"}'::jsonb, true, 90)
on conflict (template_key) do nothing;

-- ---------------------------------------------------------------------------
-- Seed: role-specific onboarding checklists for every existing tenant
-- (FR-MIG-009). New tenants are seeded on first read by
-- MigrationOnboardingService, so this is a backfill rather than the mechanism.
-- ---------------------------------------------------------------------------
insert into migration.onboarding_task (tenant_id, role, task_key, title, description, route, sort_order)
select t.id, s.role, s.task_key, s.title, s.description, s.route, s.sort_order
from platform.tenant t
cross join (values
  ('TENANT_ADMIN','INVITE_USERS','Invite your team','Add the users who will work in Axiom and give each the role that matches their job.','/admin/users',10),
  ('TENANT_ADMIN','REVIEW_ROLES','Review role assignments','Confirm nobody holds more access than their job needs; the role catalogue explains what each grants.','/admin/roles',20),
  ('TENANT_ADMIN','CONNECT_SOURCE','Connect your previous CRM','Connect the system you are migrating from, with read-only credentials, and discover its schema.','/migration',30),
  ('TENANT_ADMIN','REVIEW_MAPPING','Review the field mapping','Check the proposed mapping and acknowledge the list of source fields that will not come across.','/migration',40),
  ('TENANT_ADMIN','DRY_RUN','Run a dry run','A full validation pass that writes nothing. Iterate until the pre-flight report is clean.','/migration',50),
  ('TENANT_ADMIN','RECONCILE','Sign off the reconciliation report','Compare source and target counts and monetary sums before you decommission the old system.','/migration',60),
  ('SALES_MANAGER','REVIEW_PIPELINE','Review your pipeline stages','Confirm the stages and probabilities match how your team actually sells.','/pipeline',10),
  ('SALES_MANAGER','ASSIGN_OWNERS','Check account ownership','Migrated accounts land with an owner; confirm the assignment before the team starts working them.','/accounts',20),
  ('SALES_MANAGER','FORECAST_SETUP','Set up your first forecast','Forecast categories drive the roll-up; set them once and the numbers stay comparable.','/forecast',30),
  ('SALES','FIRST_ACCOUNT','Open one of your accounts','Everything about a customer is on one page, including the migrated history.','/accounts',10),
  ('SALES','LOG_ACTIVITY','Log your first activity','Calls, emails and notes on the record keep the timeline continuous across the migration.','/activities',20),
  ('SALES','FIRST_OPPORTUNITY','Work an opportunity','Move a deal through a stage and see the forecast update.','/pipeline',30),
  ('DATA_STEWARD','REVIEW_DUPLICATES','Review detected duplicates','The dry run lists every record that matched existing tenant data. Decide before the import, not after.','/migration',10),
  ('DATA_STEWARD','REFERENCE_DATA','Check your picklists','Migrated values that do not match a governed picklist are reported; fix them here.','/reference-data',20),
  ('DATA_STEWARD','UNMAPPED_FIELDS','Close out the unmapped field list','Every source field Axiom will not store is listed. Silent omission of source data is not acceptable.','/migration',30)
) as s(role, task_key, title, description, route, sort_order)
on conflict (tenant_id, role, task_key) do nothing;

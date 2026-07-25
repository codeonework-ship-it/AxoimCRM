-- E20 / FR-AUD-008, 009, 011, 012, 013, 016
--
-- Compliance module: data subject requests with an enumerable erasure registry,
-- an append-only consent register, encryption posture and customer-managed key
-- lifecycle, complete self-service tenant export, and period evidence packs.
--
-- The registry in compliance.erasable_store is the load-bearing part. Data model
-- §9 says erasure is the hardest thing to retrofit because every derived store
-- must be reachable "from day one". Enumerating the stores in a table — including
-- the ones this deployment cannot reach, with the reason — is what turns
-- "we think we got everything" into a testable claim. It is deliberately NOT
-- tenant-scoped: which stores exist is a deployment fact, not tenant config.

-- ---------------------------------------------------------------------------
-- FR-AUD-008 — erasable store registry
-- ---------------------------------------------------------------------------
create table compliance.erasable_store (
  store_key          text primary key,
  label              text not null,
  store_kind         text not null check (store_kind in (
                       'PRIMARY_TABLE','EVENT_LOG','FILE_STORE','SEARCH_INDEX',
                       'REPORTING_PROJECTION','AI_EMBEDDING','AI_CACHE','BACKUP','AUDIT_TRAIL')),
  adapter            text not null check (adapter in ('JDBC_TABLE','NOT_DEPLOYED','OPERATIONS_RUNBOOK')),
  target_schema      text,
  target_table       text,
  -- How a row in this store is matched to the data subject.
  subject_match      text not null default 'NONE'
                     check (subject_match in ('ID','EMAIL','RELATED_ENTITY','NONE')),
  subject_column     text,
  personal_columns   text[] not null default '{}',
  strategy           text not null check (strategy in
                       ('PSEUDONYMISE','DELETE','RETAIN_NON_PERSONAL','UNREACHABLE')),
  subject_types      text[] not null default '{}',
  reachable          boolean not null default true,
  unreachable_reason text,
  sort_order         integer not null default 100,
  active             boolean not null default true,
  constraint erasable_store_unreachable_needs_reason
    check (reachable or unreachable_reason is not null),
  constraint erasable_store_jdbc_needs_target
    check (adapter <> 'JDBC_TABLE' or (target_schema is not null and target_table is not null))
);

insert into compliance.erasable_store
  (store_key, label, store_kind, adapter, target_schema, target_table, subject_match,
   subject_column, personal_columns, strategy, subject_types, reachable, unreachable_reason, sort_order)
values
  ('crm.contact', 'Contact records', 'PRIMARY_TABLE', 'JDBC_TABLE', 'crm', 'contact',
   'ID', 'id', array['first_name','last_name','email','title'], 'PSEUDONYMISE',
   array['CONTACT'], true, null, 10),

  ('crm.lead', 'Lead records', 'PRIMARY_TABLE', 'JDBC_TABLE', 'crm', 'lead',
   'ID', 'id', array['first_name','last_name','email','company'], 'PSEUDONYMISE',
   array['LEAD'], true, null, 20),

  ('identity.app_user', 'User accounts', 'PRIMARY_TABLE', 'JDBC_TABLE', 'identity', 'app_user',
   'ID', 'id', array['email','display_name'], 'PSEUDONYMISE',
   array['APP_USER'], true, null, 30),

  ('engagement.activity_participant', 'Activity participants', 'PRIMARY_TABLE', 'JDBC_TABLE',
   'engagement', 'activity_participant', 'RELATED_ENTITY', 'participant_id',
   array['display_name','email'], 'PSEUDONYMISE',
   array['CONTACT','LEAD','APP_USER'], true, null, 40),

  ('engagement.activity', 'Activity notes and outcomes', 'PRIMARY_TABLE', 'JDBC_TABLE',
   'engagement', 'activity', 'RELATED_ENTITY', 'related_entity_id',
   array['body','outcome'], 'PSEUDONYMISE',
   array['CONTACT','LEAD'], true, null, 50),

  ('engagement.notification', 'Notification centre messages', 'PRIMARY_TABLE', 'JDBC_TABLE',
   'engagement', 'notification', 'RELATED_ENTITY', 'recipient_user_id',
   array['title','body'], 'PSEUDONYMISE',
   array['APP_USER'], true, null, 60),

  ('identity.login_attempt', 'Sign-in attempt log', 'EVENT_LOG', 'JDBC_TABLE',
   'identity', 'login_attempt', 'RELATED_ENTITY', 'user_id',
   array['email','ip','user_agent'], 'PSEUDONYMISE',
   array['APP_USER'], true, null, 70),

  ('identity.user_session', 'Session records', 'EVENT_LOG', 'JDBC_TABLE',
   'identity', 'user_session', 'RELATED_ENTITY', 'user_id',
   array['subject_email','subject_name','ip','user_agent'], 'PSEUDONYMISE',
   array['APP_USER'], true, null, 80),

  -- The compliance module's own store. An access request's payload is a copy of
  -- the subject's personal data, and the request row names the subject; a later
  -- erasure that left those behind would have erased everything except the file
  -- the data protection officer opened last.
  ('compliance.data_subject_request', 'Data subject request records', 'PRIMARY_TABLE', 'JDBC_TABLE',
   'compliance', 'data_subject_request', 'RELATED_ENTITY', 'subject_id',
   array['subject_email','subject_name','payload'], 'PSEUDONYMISE',
   array['CONTACT','LEAD','APP_USER'], true, null, 95),

  ('integration.outbox_event', 'Domain event outbox payloads', 'EVENT_LOG', 'JDBC_TABLE',
   'integration', 'outbox_event', 'RELATED_ENTITY', 'aggregate_id',
   array['payload'], 'PSEUDONYMISE',
   array['CONTACT','LEAD','APP_USER'], true, null, 90),

  -- Retained deliberately. FR-AUD-008 requires a non-personal record that the
  -- erasure occurred; the audit trail is also append-only at storage level, so
  -- "erasing" it is neither desirable nor possible.
  ('governance.audit_event', 'Audit trail', 'AUDIT_TRAIL', 'OPERATIONS_RUNBOOK', null, null,
   'NONE', null, array[]::text[], 'RETAIN_NON_PERSONAL',
   array['CONTACT','LEAD','APP_USER'], true, null, 200),

  ('compliance.consent_event', 'Consent register history', 'AUDIT_TRAIL', 'OPERATIONS_RUNBOOK', null, null,
   'NONE', null, array[]::text[], 'RETAIN_NON_PERSONAL',
   array['CONTACT','LEAD','APP_USER'], true, null, 210),

  -- Stores that exist in the architecture but cannot be reached from this
  -- deployment. These are REPORTED on every erasure run, never skipped.
  ('search.index', 'Full-text search index', 'SEARCH_INDEX', 'NOT_DEPLOYED', null, null,
   'NONE', null, array[]::text[], 'UNREACHABLE', array['CONTACT','LEAD','APP_USER'], false,
   'The search index service is not deployed in this environment. Its erasure adapter must be registered here before the index goes live.', 300),

  ('reporting.read_model', 'Reporting projection (ADR-008)', 'REPORTING_PROJECTION', 'NOT_DEPLOYED', null, null,
   'NONE', null, array[]::text[], 'UNREACHABLE', array['CONTACT','LEAD','APP_USER'], false,
   'The ADR-008 read model runs in the separate reporting-service project and exposes no erasure adapter yet. Erasure of projected personal data is outstanding.', 310),

  ('ai.embedding_store', 'AI grounding embeddings', 'AI_EMBEDDING', 'NOT_DEPLOYED', null, null,
   'NONE', null, array[]::text[], 'UNREACHABLE', array['CONTACT','LEAD','APP_USER'], false,
   'AI grounding embeddings (E16) are not deployed. Registering an erasure adapter is a release gate on that epic, per data model §9.', 320),

  ('ai.response_cache', 'AI response cache', 'AI_CACHE', 'NOT_DEPLOYED', null, null,
   'NONE', null, array[]::text[], 'UNREACHABLE', array['CONTACT','LEAD','APP_USER'], false,
   'The AI response cache (E16) is not deployed. Cached completions can echo personal data and must be purgeable before that epic ships.', 330),

  ('files.attachment_store', 'Attachment object storage', 'FILE_STORE', 'NOT_DEPLOYED', null, null,
   'NONE', null, array[]::text[], 'UNREACHABLE', array['CONTACT','LEAD','APP_USER'], false,
   'Attachment object storage is not deployed in this environment. Attachment erasure has no adapter.', 340),

  ('backup.pitr_snapshots', 'Database backups and point-in-time snapshots', 'BACKUP', 'OPERATIONS_RUNBOOK', null, null,
   'NONE', null, array[]::text[], 'UNREACHABLE', array['CONTACT','LEAD','APP_USER'], false,
   'Point-in-time backup snapshots cannot be selectively rewritten in place. Erasure of backup copies requires a restore-rebuild-reseal cycle run by platform operations; it is infrastructure-dependent and is reported on every run until completed.', 350);

grant select on compliance.erasable_store to axiom_app;

-- ---------------------------------------------------------------------------
-- FR-AUD-008 — DSR service window and requests
-- ---------------------------------------------------------------------------
create table compliance.dsr_policy (
  tenant_id                 uuid primary key references platform.tenant(id),
  access_window_days        integer not null default 30 check (access_window_days between 1 and 180),
  rectification_window_days integer not null default 30 check (rectification_window_days between 1 and 180),
  portability_window_days   integer not null default 30 check (portability_window_days between 1 and 180),
  erasure_window_days       integer not null default 30 check (erasure_window_days between 1 and 180),
  contact_email             text,
  updated_by                uuid,
  updated_at                timestamptz not null default now()
);

insert into compliance.dsr_policy(tenant_id) select id from platform.tenant
on conflict (tenant_id) do nothing;

create table compliance.data_subject_request (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  reference          text not null,
  request_type       text not null check (request_type in
                       ('ACCESS','RECTIFICATION','PORTABILITY','ERASURE')),
  subject_type       text not null check (subject_type in ('CONTACT','LEAD','APP_USER')),
  subject_id         uuid,
  subject_email      text not null,
  subject_name       text,
  status             text not null default 'RECEIVED' check (status in
                       ('RECEIVED','IN_PROGRESS','COMPLETED','COMPLETED_WITH_UNREACHABLE_STORES','REJECTED')),
  requested_by       uuid not null,
  requested_by_name  text not null,
  received_at        timestamptz not null default now(),
  due_at             timestamptz not null,
  completed_at       timestamptz,
  service_window_days integer not null,
  stores_reached     integer not null default 0,
  stores_unreachable integer not null default 0,
  records_affected   bigint not null default 0,
  outcome_summary    text,
  payload            jsonb,
  correlation_id     text,
  unique (tenant_id, reference)
);

create index idx_dsr_feed on compliance.data_subject_request(tenant_id, received_at desc);

create table compliance.dsr_store_result (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  request_id       uuid not null references compliance.data_subject_request(id),
  store_key        text not null references compliance.erasable_store(store_key),
  store_label      text not null,
  store_kind       text not null,
  status           text not null check (status in
                     ('ERASED','PSEUDONYMISED','RETAINED_NON_PERSONAL','NOT_APPLICABLE','UNREACHABLE','FAILED')),
  records_affected bigint not null default 0,
  detail           text not null,
  ran_at           timestamptz not null default now(),
  unique (request_id, store_key)
);

create index idx_dsr_store_result_request on compliance.dsr_store_result(tenant_id, request_id);

-- ---------------------------------------------------------------------------
-- FR-AUD-009 — consent register. Append-only history is the register; current
-- state is derived, never stored, so a withdrawal cannot overwrite the grant it
-- withdraws. There is no UPDATE grant and a trigger rejects one.
-- ---------------------------------------------------------------------------
create table compliance.consent_event (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  subject_type   text not null check (subject_type in ('CONTACT','LEAD','APP_USER','PROSPECT')),
  subject_id     uuid,
  subject_email  text not null,
  purpose        text not null,
  channel        text not null check (channel in ('EMAIL','PHONE','SMS','POST','WHATSAPP','IN_APP')),
  action         text not null check (action in ('GRANT','WITHDRAW','RECONFIRM')),
  lawful_basis   text not null check (lawful_basis in
                   ('CONSENT','CONTRACT','LEGAL_OBLIGATION','VITAL_INTERESTS','PUBLIC_TASK','LEGITIMATE_INTEREST')),
  source         text not null check (source in
                   ('WEB_FORM','IMPORT','CALL_CENTRE','EMAIL_REPLY','ADMIN_UI','API','PAPER_FORM')),
  evidence_ref   text,
  notes          text,
  actor_id       uuid not null,
  actor_name     text not null,
  correlation_id text,
  effective_at   timestamptz not null default now(),
  recorded_at    timestamptz not null default now()
);

create index idx_consent_subject on compliance.consent_event(
  tenant_id, subject_type, coalesce(subject_id::text, lower(subject_email)), purpose, channel, effective_at desc);
create index idx_consent_feed on compliance.consent_event(tenant_id, recorded_at desc);

-- ---------------------------------------------------------------------------
-- FR-AUD-011 / FR-AUD-012 — encryption posture and tenant key lifecycle
-- ---------------------------------------------------------------------------
create table compliance.encryption_posture (
  tenant_id             uuid primary key references platform.tenant(id),
  at_rest_cipher        text not null default 'AES-256-GCM application envelope over PostgreSQL storage',
  at_rest_status        text not null default 'PARTIAL'
                        check (at_rest_status in ('ENFORCED','PARTIAL','NOT_CONFIGURED')),
  at_rest_detail        text not null default
    'Secrets (MFA seeds, IdP client secrets) are envelope-encrypted with AES-256-GCM. Full-volume encryption is a deployment responsibility and is not asserted by the application.',
  in_transit_protocol   text not null default 'TLS 1.3',
  in_transit_status     text not null default 'PARTIAL'
                        check (in_transit_status in ('ENFORCED','PARTIAL','NOT_CONFIGURED')),
  in_transit_detail     text not null default
    'TLS is terminated at the ingress in managed deployments. The development stack serves plain HTTP on localhost.',
  key_provider          text not null default 'PLATFORM_MANAGED'
                        check (key_provider in ('PLATFORM_MANAGED','CUSTOMER_MANAGED')),
  rotation_interval_days integer not null default 365 check (rotation_interval_days between 30 and 1095),
  last_rotated_at       timestamptz,
  kms_integration_status text not null default 'DEFERRED'
                        check (kms_integration_status in ('DEFERRED','ACTIVE')),
  notes                 text,
  updated_by            uuid,
  updated_at            timestamptz not null default now()
);

insert into compliance.encryption_posture(tenant_id) select id from platform.tenant
on conflict (tenant_id) do nothing;

create table compliance.tenant_key (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  key_ref        text not null,
  provider       text not null check (provider in ('LOCAL_PROVIDER','CUSTOMER_SUPPLIED','EXTERNAL_KMS')),
  -- Fingerprint only. Key material is never stored by this table; a customer
  -- withdrawing a key must make the platform unable to decrypt, which storing a
  -- copy would defeat.
  fingerprint    text not null,
  state          text not null default 'ACTIVE' check (state in ('ACTIVE','ROTATED','REVOKED')),
  created_by     uuid not null,
  created_by_name text not null,
  created_at     timestamptz not null default now(),
  rotated_at     timestamptz,
  revoked_at     timestamptz,
  revoked_by     uuid,
  revoke_reason  text,
  unique (tenant_id, key_ref)
);

create unique index uq_tenant_key_single_active on compliance.tenant_key(tenant_id)
  where state = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- FR-AUD-013 — complete tenant export, self-service, every tier
-- ---------------------------------------------------------------------------
create table compliance.tenant_export (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  status             text not null check (status in ('RUNNING','COMPLETED','FAILED')),
  format             text not null default 'AXIOM-TENANT-EXPORT-V1',
  format_description text not null default
    'ZIP archive: manifest.json plus one newline-delimited JSON (.ndjson) file per dataset, UTF-8, RFC 8259 values.',
  requested_by       uuid not null,
  requested_by_name  text not null,
  requested_at       timestamptz not null default now(),
  completed_at       timestamptz,
  dataset_count      integer not null default 0,
  record_count       bigint not null default 0,
  archive_bytes      bigint not null default 0,
  checksum_algorithm text not null default 'SHA-256',
  manifest           jsonb,
  manifest_checksum  text,
  archive_checksum   text,
  archive            bytea,
  error              text
);

create index idx_tenant_export_feed on compliance.tenant_export(tenant_id, requested_at desc);

-- ---------------------------------------------------------------------------
-- FR-AUD-016 — compliance evidence pack for a period
-- ---------------------------------------------------------------------------
create table compliance.evidence_pack (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  period_start      date not null,
  period_end        date not null,
  generated_by      uuid not null,
  generated_by_name text not null,
  generated_at      timestamptz not null default now(),
  section_counts    jsonb not null default '{}'::jsonb,
  content           jsonb not null,
  checksum          text not null,
  constraint evidence_period_ordered check (period_end >= period_start)
);

create index idx_evidence_pack_feed on compliance.evidence_pack(tenant_id, generated_at desc);

-- ---------------------------------------------------------------------------
-- RLS + grants
-- ---------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array[
    'compliance.dsr_policy',
    'compliance.data_subject_request',
    'compliance.dsr_store_result',
    'compliance.consent_event',
    'compliance.encryption_posture',
    'compliance.tenant_key',
    'compliance.tenant_export',
    'compliance.evidence_pack'
  ] loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format($p$create policy tenant_isolation on %s
        using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
        with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
  end loop;
end $$;

grant select, insert, update on compliance.dsr_policy to axiom_app;
grant select, insert, update on compliance.data_subject_request to axiom_app;
grant select, insert on compliance.dsr_store_result to axiom_app;
grant select, insert on compliance.consent_event to axiom_app;
grant select, insert, update on compliance.encryption_posture to axiom_app;
grant select, insert, update on compliance.tenant_key to axiom_app;
grant select, insert, update on compliance.tenant_export to axiom_app;
grant select, insert on compliance.evidence_pack to axiom_app;

revoke update, delete, truncate on compliance.consent_event from public;
revoke update, delete, truncate on compliance.dsr_store_result from public;
revoke update, delete, truncate on compliance.evidence_pack from public;

drop trigger if exists trg_consent_event_no_update on compliance.consent_event;
create trigger trg_consent_event_no_update before update on compliance.consent_event
  for each row execute function governance.reject_audit_mutation();
drop trigger if exists trg_consent_event_no_delete on compliance.consent_event;
create trigger trg_consent_event_no_delete before delete on compliance.consent_event
  for each row execute function governance.reject_audit_mutation();

drop trigger if exists trg_dsr_store_result_no_update on compliance.dsr_store_result;
create trigger trg_dsr_store_result_no_update before update on compliance.dsr_store_result
  for each row execute function governance.reject_audit_mutation();
drop trigger if exists trg_dsr_store_result_no_delete on compliance.dsr_store_result;
create trigger trg_dsr_store_result_no_delete before delete on compliance.dsr_store_result
  for each row execute function governance.reject_audit_mutation();

drop trigger if exists trg_evidence_pack_no_update on compliance.evidence_pack;
create trigger trg_evidence_pack_no_update before update on compliance.evidence_pack
  for each row execute function governance.reject_audit_mutation();

-- Erasure writes to the personal-data columns of every reachable store. No new
-- grants are issued here: axiom_app already holds UPDATE on every table in the
-- registry (verified against the running database), and widening another
-- module's privileges from this migration would be a boundary violation. A
-- registry entry whose target is not writable surfaces as UNREACHABLE on the
-- run rather than as a silent skip, which is the required behaviour anyway.

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('compliance','erasable_store','COMPLIANCE','store_key',false,'PLATFORM'),
  ('compliance','dsr_policy','COMPLIANCE','tenant_id',true,'ACTIVE'),
  ('compliance','data_subject_request','COMPLIANCE','id',true,'ACTIVE'),
  ('compliance','dsr_store_result','COMPLIANCE','id',true,'APPEND_ONLY'),
  ('compliance','consent_event','COMPLIANCE','id',true,'APPEND_ONLY'),
  ('compliance','encryption_posture','COMPLIANCE','tenant_id',true,'ACTIVE'),
  ('compliance','tenant_key','COMPLIANCE','id',true,'ACTIVE'),
  ('compliance','tenant_export','COMPLIANCE','id',true,'ACTIVE'),
  ('compliance','evidence_pack','COMPLIANCE','id',true,'APPEND_ONLY')
on conflict do nothing;

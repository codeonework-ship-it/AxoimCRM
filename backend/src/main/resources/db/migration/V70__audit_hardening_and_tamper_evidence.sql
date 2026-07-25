-- E20 / FR-AUD-001..007, FR-AUD-010, FR-GLOBAL-005
--
-- Audit hardening. Three things happen here that cannot be done in application
-- code and therefore must live in the database:
--
--   1. The hash chain and per-tenant sequence are assigned by a BEFORE INSERT
--      trigger, not by the writing service. Six modules write audit events; a
--      chain maintained by convention would be broken by the first caller that
--      forgot, and a chain computed in the application is racy under concurrent
--      inserts. The trigger takes a per-tenant advisory lock, so the sequence is
--      monotonic and gap-free by construction.
--   2. Append-only is enforced by triggers plus revoked grants. FR-AUD-001 and
--      US-E20-01 require that modification is *impossible*, not merely denied at
--      the application layer, so the rejection has to fire for anything that
--      reaches the table — including psql as the owning superuser.
--   3. Verification is a database function, so the auditor's recomputation uses
--      exactly the same canonical byte string the writer used. A Java
--      re-implementation would eventually disagree with Postgres about timestamp
--      or jsonb formatting and report a false tamper.
--
-- Note on RLS predicates: SET LOCAL app.tenant_id reverts to the EMPTY STRING,
-- not NULL, when unset, and ''::uuid throws. Every policy below therefore uses
-- nullif(current_setting('app.tenant_id', true), '')::uuid.

-- ---------------------------------------------------------------------------
-- Module schemas
-- ---------------------------------------------------------------------------
create schema if not exists compliance;
create schema if not exists observability;

grant usage on schema compliance, observability to axiom_app;

-- The runtime search_path is shared with every other module. Rewriting it with a
-- literal list would silently drop schemas added by a migration that ran before
-- this one, so it is merged rather than replaced.
do $$
declare
  existing text;
  parts text[];
  merged text[];
  wanted text;
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

  foreach wanted in array array['compliance', 'observability'] loop
    if not (wanted = any(merged)) then
      merged := array_append(merged, wanted);
    end if;
  end loop;

  merged := array_append(merged, 'public');
  execute format('alter role axiom_app set search_path to %s', array_to_string(merged, ', '));
end $$;

insert into governance.module_catalog(module_code, schema_name, display_name, description, owner_role) values
  ('COMPLIANCE', 'compliance', 'Compliance', 'Data subject requests, consent register, encryption posture, tenant export and evidence packs.', 'AUDITOR'),
  ('OBSERVABILITY', 'observability', 'Observability', 'Structured request metrics, service-level indicators and tenant usage telemetry.', 'OPERATIONS')
on conflict (module_code) do nothing;

-- ---------------------------------------------------------------------------
-- FR-AUD-007 — tamper evidence: prev_hash/hash chain + monotonic sequence_no
-- ---------------------------------------------------------------------------
-- FR-GLOBAL-005 names `source` (ui/api/automation/ai/migration) as a required
-- attribute of every material action, and data model §7 lists it on AUDIT_EVENT.
-- It was missing; adding it here rather than leaving audit events unable to say
-- whether a change came from a person, an integration or an automation.
alter table governance.audit_event
  add column if not exists source      text not null default 'UI',
  add column if not exists sequence_no bigint,
  add column if not exists prev_hash   text,
  add column if not exists hash        text;

alter table governance.audit_event
  add constraint audit_event_source_known
  check (source in ('UI','API','AUTOMATION','AI','MIGRATION','SYSTEM'));

-- The single canonical definition of an event's hash. Used by the insert trigger
-- and by the verifier, so the two can never drift.
create or replace function governance.audit_chain_hash(
  p_prev_hash      text,
  p_tenant_id      uuid,
  p_sequence_no    bigint,
  p_occurred_at    timestamptz,
  p_actor_id       uuid,
  p_action         text,
  p_entity_type    text,
  p_entity_id      uuid,
  p_summary        text,
  p_details        jsonb,
  p_correlation_id text,
  p_source         text
) returns text
language sql
immutable
as $$
  select encode(sha256(convert_to(
      coalesce(p_prev_hash, 'GENESIS')
      || '|' || p_tenant_id::text
      || '|' || p_sequence_no::text
      || '|' || to_char(p_occurred_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US')
      || '|' || p_actor_id::text
      || '|' || p_action
      || '|' || p_entity_type
      || '|' || coalesce(p_entity_id::text, '')
      || '|' || p_summary
      || '|' || p_details::text
      || '|' || coalesce(p_correlation_id, '')
      || '|' || coalesce(p_source, 'UI'),
      'UTF8')), 'hex');
$$;

-- Backfill the events written before this migration, in occurrence order per
-- tenant, so the chain is complete rather than starting from "now".
do $$
declare
  r record;
  seq bigint;
  prev text;
  current_tenant uuid;
begin
  current_tenant := null;
  for r in
    select id, tenant_id, occurred_at, actor_id, action, entity_type, entity_id,
           summary, details, correlation_id, source
      from governance.audit_event
     order by tenant_id, occurred_at, id
  loop
    if current_tenant is null or current_tenant <> r.tenant_id then
      current_tenant := r.tenant_id;
      seq := 0;
      prev := null;
    end if;
    seq := seq + 1;
    update governance.audit_event
       set sequence_no = seq,
           prev_hash = prev,
           hash = governance.audit_chain_hash(prev, r.tenant_id, seq, r.occurred_at,
                    r.actor_id, r.action, r.entity_type, r.entity_id, r.summary,
                    r.details, r.correlation_id, r.source)
     where id = r.id;
    select hash into prev from governance.audit_event where id = r.id;
  end loop;
end $$;

alter table governance.audit_event
  alter column sequence_no set not null,
  alter column hash set not null;

alter table governance.audit_event
  add constraint uq_audit_event_tenant_sequence unique (tenant_id, sequence_no);

create index if not exists idx_audit_event_chain on governance.audit_event(tenant_id, sequence_no);
create index if not exists idx_audit_event_action on governance.audit_event(tenant_id, action, occurred_at desc);
create index if not exists idx_audit_event_entity on governance.audit_event(tenant_id, entity_type, entity_id, occurred_at desc);

-- Assigns the chain on every insert regardless of which module wrote the event.
-- SECURITY DEFINER because the previous row must be readable to link to it, and
-- the table has FORCE ROW LEVEL SECURITY.
create or replace function governance.audit_event_assign_chain() returns trigger
language plpgsql
security definer
set search_path = governance, pg_catalog
as $$
declare
  prev_seq  bigint;
  prev_hash text;
begin
  -- Serialize per tenant only: two tenants writing concurrently never contend.
  perform pg_advisory_xact_lock(hashtext('axiom.audit_chain'), hashtext(new.tenant_id::text));

  select sequence_no, hash into prev_seq, prev_hash
    from governance.audit_event
   where tenant_id = new.tenant_id
   order by sequence_no desc
   limit 1;

  new.sequence_no := coalesce(prev_seq, 0) + 1;
  new.prev_hash := prev_hash;
  new.hash := governance.audit_chain_hash(prev_hash, new.tenant_id, new.sequence_no,
                new.occurred_at, new.actor_id, new.action, new.entity_type,
                new.entity_id, new.summary, new.details, new.correlation_id, new.source);
  return new;
end $$;

drop trigger if exists trg_audit_event_assign_chain on governance.audit_event;
create trigger trg_audit_event_assign_chain
  before insert on governance.audit_event
  for each row execute function governance.audit_event_assign_chain();

-- ---------------------------------------------------------------------------
-- FR-AUD-007 — verification: report the FIRST break or gap, or OK
-- ---------------------------------------------------------------------------
create or replace function governance.verify_audit_chain(p_tenant_id uuid)
returns table (
  status         text,
  events_checked bigint,
  first_sequence bigint,
  last_sequence  bigint,
  break_type     text,
  break_sequence bigint,
  break_event_id uuid,
  detail         text
)
language plpgsql
security definer
set search_path = governance, pg_catalog
as $$
declare
  r         record;
  expected  bigint := 1;
  prev      text := null;
  checked   bigint := 0;
  first_seq bigint := null;
  last_seq  bigint := null;
  recomputed text;
begin
  for r in
    select * from governance.audit_event
     where tenant_id = p_tenant_id
     order by sequence_no
  loop
    if r.sequence_no <> expected then
      return query select 'BROKEN'::text, checked, first_seq, last_seq,
        'SEQUENCE_GAP'::text, expected, r.id,
        format('Sequence gap: expected event %s but the next stored event is %s. %s event(s) have been removed from this tenant''s audit stream.',
               expected, r.sequence_no, r.sequence_no - expected);
      return;
    end if;

    if coalesce(r.prev_hash, '') <> coalesce(prev, '') then
      return query select 'BROKEN'::text, checked, first_seq, last_seq,
        'CHAIN_LINK_MISMATCH'::text, r.sequence_no, r.id,
        format('Event %s does not link to its predecessor: stored prev_hash %s, expected %s.',
               r.sequence_no, coalesce(r.prev_hash, '(none)'), coalesce(prev, '(none)'));
      return;
    end if;

    recomputed := governance.audit_chain_hash(r.prev_hash, r.tenant_id, r.sequence_no,
                    r.occurred_at, r.actor_id, r.action, r.entity_type, r.entity_id,
                    r.summary, r.details, r.correlation_id, r.source);
    if r.hash <> recomputed then
      return query select 'BROKEN'::text, checked, first_seq, last_seq,
        'CONTENT_TAMPERED'::text, r.sequence_no, r.id,
        format('Event %s has been altered after it was written: stored hash %s, recomputed %s.',
               r.sequence_no, r.hash, recomputed);
      return;
    end if;

    checked := checked + 1;
    if first_seq is null then first_seq := r.sequence_no; end if;
    last_seq := r.sequence_no;
    prev := r.hash;
    expected := expected + 1;
  end loop;

  return query select 'OK'::text, checked, first_seq, last_seq,
    null::text, null::bigint, null::uuid,
    case when checked = 0
      then 'No audit events recorded for this tenant yet.'
      else format('%s event(s) verified. Sequence %s..%s is contiguous and every hash recomputes.',
                  checked, first_seq, last_seq) end;
end $$;

grant execute on function governance.verify_audit_chain(uuid) to axiom_app;
grant execute on function governance.audit_chain_hash(text, uuid, bigint, timestamptz, uuid, text, text, uuid, text, jsonb, text, text) to axiom_app;

-- ---------------------------------------------------------------------------
-- FR-AUD-001 — append-only AT THE STORAGE LEVEL
-- ---------------------------------------------------------------------------
create or replace function governance.reject_audit_mutation() returns trigger
language plpgsql
as $$
begin
  raise exception
    'Audit records are append-only (FR-AUD-001): % on %.% is rejected. No role, including a platform operator, may alter or remove a recorded audit event.',
    tg_op, tg_table_schema, tg_table_name
    using errcode = '42501';
end $$;

create or replace function governance.reject_audit_truncate() returns trigger
language plpgsql
as $$
begin
  raise exception
    'Audit records are append-only (FR-AUD-001): TRUNCATE on %.% is rejected.',
    tg_table_schema, tg_table_name
    using errcode = '42501';
end $$;

drop trigger if exists trg_audit_event_no_update on governance.audit_event;
create trigger trg_audit_event_no_update
  before update on governance.audit_event
  for each row execute function governance.reject_audit_mutation();

drop trigger if exists trg_audit_event_no_delete on governance.audit_event;
create trigger trg_audit_event_no_delete
  before delete on governance.audit_event
  for each row execute function governance.reject_audit_mutation();

drop trigger if exists trg_audit_event_no_truncate on governance.audit_event;
create trigger trg_audit_event_no_truncate
  before truncate on governance.audit_event
  for each statement execute function governance.reject_audit_truncate();

revoke update, delete, truncate on governance.audit_event from public;
revoke update, delete, truncate on governance.audit_event from axiom_app;
revoke all on governance.audit_event from axiom_relay;

-- ---------------------------------------------------------------------------
-- FR-AUD-002 — field change history
-- ---------------------------------------------------------------------------
create table governance.field_history (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  audit_event_id  uuid references governance.audit_event(id),
  entity_type     text not null,
  entity_id       uuid,
  field_name      text not null,
  old_value       text,
  new_value       text,
  source          text not null default 'UI'
                  check (source in ('UI','API','AUTOMATION','AI','MIGRATION','SYSTEM')),
  changed_by      uuid not null,
  changed_by_name text not null,
  correlation_id  text,
  changed_at      timestamptz not null default now()
);

create index idx_field_history_entity on governance.field_history(tenant_id, entity_type, entity_id, changed_at desc);
create index idx_field_history_field on governance.field_history(tenant_id, entity_type, field_name, changed_at desc);

-- ---------------------------------------------------------------------------
-- FR-AUD-003 — read auditing for designated sensitive objects/fields
-- ---------------------------------------------------------------------------
create table governance.read_audit (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  actor_id       uuid not null,
  actor_name     text not null,
  actor_role     text not null,
  entity_type    text not null,
  entity_id      uuid,
  field_names    text[] not null default '{}',
  access_path    text not null,
  purpose        text,
  record_count   integer not null default 1,
  correlation_id text,
  ip             text,
  at             timestamptz not null default now()
);

create index idx_read_audit_feed on governance.read_audit(tenant_id, at desc);
create index idx_read_audit_record on governance.read_audit(tenant_id, entity_type, entity_id, at desc);

-- ---------------------------------------------------------------------------
-- FR-AUD-005 — export auditing
-- ---------------------------------------------------------------------------
create table governance.export_audit (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  audit_event_id   uuid references governance.audit_event(id),
  actor_id         uuid not null,
  actor_name       text not null,
  object_type      text not null,
  filter_criteria  jsonb not null default '{}'::jsonb,
  row_count        bigint,
  -- False when the producing module did not report a count. Recorded rather
  -- than defaulted to zero: "we do not know" and "no rows" are different facts.
  row_count_known  boolean not null default true,
  destination      text not null,
  format           text,
  correlation_id   text,
  at               timestamptz not null default now()
);

create index idx_export_audit_feed on governance.export_audit(tenant_id, at desc);

-- ---------------------------------------------------------------------------
-- Sensitive field registry — designates what read-audits and what is masked
-- ---------------------------------------------------------------------------
create table governance.sensitive_field_registry (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  entity_type    text not null,
  field_name     text not null,
  classification text not null
                 check (classification in ('PERSONAL','SENSITIVE_PERSONAL','FINANCIAL','CREDENTIAL')),
  read_audited   boolean not null default true,
  mask_in_logs   boolean not null default true,
  active         boolean not null default true,
  created_at     timestamptz not null default now(),
  unique (tenant_id, entity_type, field_name)
);

insert into governance.sensitive_field_registry(tenant_id, entity_type, field_name, classification)
select t.id, f.entity_type, f.field_name, f.classification
  from platform.tenant t
  cross join (values
    ('CONTACT','email','PERSONAL'),
    ('CONTACT','first_name','PERSONAL'),
    ('CONTACT','last_name','PERSONAL'),
    ('CONTACT','title','PERSONAL'),
    ('LEAD','email','PERSONAL'),
    ('LEAD','first_name','PERSONAL'),
    ('LEAD','last_name','PERSONAL'),
    ('APP_USER','email','PERSONAL'),
    ('APP_USER','password_hash','CREDENTIAL'),
    ('USER_MFA','secret_cipher','CREDENTIAL'),
    ('SERVICE_CREDENTIAL','secret_hash','CREDENTIAL'),
    ('SCIM_TOKEN','token_hash','CREDENTIAL'),
    ('IDP_CONFIG','client_secret_cipher','CREDENTIAL'),
    ('OPPORTUNITY','amount','FINANCIAL'),
    ('ACTIVITY','body','PERSONAL')
  ) as f(entity_type, field_name, classification)
on conflict (tenant_id, entity_type, field_name) do nothing;

-- ---------------------------------------------------------------------------
-- FR-AUD-006 — audit retention, minimum seven years, independent of business
-- record retention. The floor is a CHECK constraint, not a service validation:
-- a compliance floor that only the service knows about is one raw UPDATE away
-- from being gone.
-- ---------------------------------------------------------------------------
create table governance.audit_retention_policy (
  tenant_id                         uuid primary key references platform.tenant(id),
  retention_years                   integer not null default 7,
  independent_of_business_retention boolean not null default true,
  legal_basis                       text not null default 'Statutory financial and data-protection record-keeping',
  updated_by                        uuid,
  updated_by_name                   text,
  updated_at                        timestamptz not null default now(),
  constraint audit_retention_minimum_seven_years check (retention_years >= 7),
  constraint audit_retention_upper_bound check (retention_years <= 50),
  constraint audit_retention_is_independent check (independent_of_business_retention)
);

insert into governance.audit_retention_policy(tenant_id) select id from platform.tenant
on conflict (tenant_id) do nothing;

-- ---------------------------------------------------------------------------
-- FR-AUD-010 — per-object retention, legal hold, notification before destruction
-- ---------------------------------------------------------------------------
create table governance.retention_policy (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  object_type        text not null,
  retention_days     integer not null check (retention_days >= 1),
  disposition        text not null default 'PURGE'
                     check (disposition in ('ARCHIVE','PURGE','PSEUDONYMISE')),
  notify_days_before integer not null default 30 check (notify_days_before >= 0),
  active             boolean not null default true,
  created_by         uuid not null,
  created_by_name    text not null,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  unique (tenant_id, object_type)
);

create table governance.legal_hold (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  object_type      text not null,
  -- NULL entity_id holds the whole object type; a value holds one record.
  entity_id        uuid,
  matter_reference text not null,
  reason           text not null,
  placed_by        uuid not null,
  placed_by_name   text not null,
  placed_at        timestamptz not null default now(),
  released_at      timestamptz,
  released_by      uuid,
  release_reason   text
);

create index idx_legal_hold_active on governance.legal_hold(tenant_id, object_type)
  where released_at is null;

create table governance.retention_run (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references platform.tenant(id),
  policy_id             uuid not null references governance.retention_policy(id),
  object_type           text not null,
  status                text not null
                        check (status in ('COMPLETED','BLOCKED','NOTIFIED','FAILED')),
  candidate_count       integer not null default 0,
  destroyed_count       integer not null default 0,
  blocked_by_hold_count integer not null default 0,
  notified_count        integer not null default 0,
  detail                jsonb not null default '{}'::jsonb,
  run_by                uuid not null,
  run_by_name           text not null,
  started_at            timestamptz not null default now(),
  finished_at           timestamptz
);

create index idx_retention_run_feed on governance.retention_run(tenant_id, started_at desc);

create table governance.retention_notice (
  id                       uuid primary key default gen_random_uuid(),
  tenant_id                uuid not null references platform.tenant(id),
  policy_id                uuid not null references governance.retention_policy(id),
  object_type              text not null,
  entity_id                uuid not null,
  entity_label             text not null,
  scheduled_destruction_at timestamptz not null,
  notified_at              timestamptz not null default now(),
  acknowledged_at          timestamptz,
  unique (tenant_id, policy_id, entity_id)
);

-- Non-personal proof of what retention destroyed, and the idempotency key that
-- stops a second run re-processing the same record. Deliberately carries no
-- label or personal column: a destruction ledger that quotes the data it
-- destroyed has not destroyed it.
create table governance.retention_disposal (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  policy_id      uuid not null references governance.retention_policy(id),
  run_id         uuid references governance.retention_run(id),
  object_type    text not null,
  entity_id      uuid not null,
  disposition    text not null check (disposition in ('ARCHIVE','PURGE','PSEUDONYMISE')),
  fields_cleared integer not null default 0,
  disposed_at    timestamptz not null default now(),
  unique (tenant_id, object_type, entity_id, disposition)
);

create index idx_retention_disposal_feed on governance.retention_disposal(tenant_id, disposed_at desc);

-- ---------------------------------------------------------------------------
-- RLS + grants
-- ---------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array[
    'governance.field_history',
    'governance.retention_disposal',
    'governance.read_audit',
    'governance.export_audit',
    'governance.sensitive_field_registry',
    'governance.audit_retention_policy',
    'governance.retention_policy',
    'governance.legal_hold',
    'governance.retention_run',
    'governance.retention_notice'
  ] loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format($p$create policy tenant_isolation on %s
        using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
        with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
  end loop;
end $$;

-- Field history and read/export audit are audit records: insert and select only.
grant select, insert on governance.field_history, governance.read_audit, governance.export_audit to axiom_app;
revoke update, delete, truncate on governance.field_history, governance.read_audit, governance.export_audit from public;

drop trigger if exists trg_field_history_no_update on governance.field_history;
create trigger trg_field_history_no_update before update on governance.field_history
  for each row execute function governance.reject_audit_mutation();
drop trigger if exists trg_field_history_no_delete on governance.field_history;
create trigger trg_field_history_no_delete before delete on governance.field_history
  for each row execute function governance.reject_audit_mutation();

drop trigger if exists trg_read_audit_no_update on governance.read_audit;
create trigger trg_read_audit_no_update before update on governance.read_audit
  for each row execute function governance.reject_audit_mutation();
drop trigger if exists trg_read_audit_no_delete on governance.read_audit;
create trigger trg_read_audit_no_delete before delete on governance.read_audit
  for each row execute function governance.reject_audit_mutation();

drop trigger if exists trg_export_audit_no_update on governance.export_audit;
create trigger trg_export_audit_no_update before update on governance.export_audit
  for each row execute function governance.reject_audit_mutation();
drop trigger if exists trg_export_audit_no_delete on governance.export_audit;
create trigger trg_export_audit_no_delete before delete on governance.export_audit
  for each row execute function governance.reject_audit_mutation();

grant select, insert, update on governance.sensitive_field_registry to axiom_app;
grant select, insert, update on governance.audit_retention_policy to axiom_app;
grant select, insert, update on governance.retention_policy to axiom_app;
grant select, insert, update on governance.legal_hold to axiom_app;
grant select, insert, update on governance.retention_run to axiom_app;
grant select, insert, update, delete on governance.retention_notice to axiom_app;
grant select, insert on governance.retention_disposal to axiom_app;
revoke update, delete, truncate on governance.retention_disposal from public;

drop trigger if exists trg_retention_disposal_no_update on governance.retention_disposal;
create trigger trg_retention_disposal_no_update before update on governance.retention_disposal
  for each row execute function governance.reject_audit_mutation();
drop trigger if exists trg_retention_disposal_no_delete on governance.retention_disposal;
create trigger trg_retention_disposal_no_delete before delete on governance.retention_disposal
  for each row execute function governance.reject_audit_mutation();

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('governance','field_history','GOVERNANCE','id',true,'APPEND_ONLY'),
  ('governance','read_audit','GOVERNANCE','id',true,'APPEND_ONLY'),
  ('governance','export_audit','GOVERNANCE','id',true,'APPEND_ONLY'),
  ('governance','sensitive_field_registry','GOVERNANCE','id',true,'ACTIVE'),
  ('governance','audit_retention_policy','GOVERNANCE','tenant_id',true,'ACTIVE'),
  ('governance','retention_policy','GOVERNANCE','id',true,'ACTIVE'),
  ('governance','legal_hold','GOVERNANCE','id',true,'ACTIVE'),
  ('governance','retention_run','GOVERNANCE','id',true,'APPEND_ONLY'),
  ('governance','retention_notice','GOVERNANCE','id',true,'ACTIVE'),
  ('governance','retention_disposal','GOVERNANCE','id',true,'APPEND_ONLY')
on conflict do nothing;

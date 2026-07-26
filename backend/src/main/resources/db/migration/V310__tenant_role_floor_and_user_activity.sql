-- ---------------------------------------------------------------------------
-- V310 — Tenant administrator/auditor floor, and structural user-activity capture.
--
-- Two independent concerns land together because they share one audience: the
-- security reviewer who has to answer "who can change this workspace" and "what
-- did they actually do".
--
-- PART A — the administrator/auditor floor.
--
--   Every tenant must retain at least one active TENANT_ADMIN and at least one
--   active AUDITOR. The service layer checks this before it acts, and gives a
--   usable refusal. That check is necessary but not sufficient: it lives in one
--   code path, and identity.app_user is written by several (admin user
--   administration, SCIM provisioning, seed migrations, a future bulk import,
--   and psql). A constraint that only one path honours is not a constraint.
--
--   So the floor is also a statement-level trigger. It is deliberately placed
--   on the table rather than expressed as a CHECK, because the property is not
--   about a row — it is about the surviving population of a tenant after a
--   statement. No row-level constraint can see that.
--
--   The trigger fires only when the statement itself removes the last holder.
--   It does NOT retroactively reject work in a tenant that already has none.
--   That distinction is the whole design: this migration must not fail on the
--   existing data (both seeded tenants currently have zero auditors), and an
--   existing gap is a finding to report, not something to auto-heal by handing
--   somebody a role they were never granted. An unexplained role grant is worse
--   than a visible gap — it is a gap you can no longer see.
--
--   The refusal carries SQLSTATE 'AX001' so the API can turn it into a 409 with
--   the constraint's own wording rather than a generic 500.
--
-- PART B — user activity.
--
--   governance.audit_event already records business changes, hash-chained and
--   append-only. It is not the right home for this: it is a business-fact log
--   with a per-tenant sequence and a tamper-evidence chain, and writing one row
--   per HTTP request into it would both dilute it and serialise every request
--   behind the chain's per-tenant advisory lock.
--
--   activity.user_activity is the access log beside it — every request, not
--   every change, and critically every DENIED request. A permission check that
--   fails is precisely the event a security review cares about, and it produces
--   no audit_event at all because nothing changed.
--
--   FR-AUD-014 forbids credentials, tokens and unmasked personal data in this
--   material. The table is shaped to make that structural rather than a matter
--   of reviewer discipline: there is no free-text request-body column, and the
--   one jsonb column is key-allowlisted by trigger. A field name nobody
--   allowlisted cannot be written, whatever the calling code intends.
-- ---------------------------------------------------------------------------

-- ===========================================================================
-- PART A — administrator / auditor floor
-- ===========================================================================

-- Single source of truth for the floor, so the trigger, the report and the
-- repair path cannot drift apart on what "complete" means.
create or replace function security.tenant_floor_roles()
returns table(role_code text, label text, remedy text)
language sql
immutable
as $$
  select 'TENANT_ADMIN'::text,
         'administrator with complete read and write'::text,
         'promote another user to Tenant Admin first'::text
  union all
  select 'AUDITOR'::text,
         'auditor with complete read and view'::text,
         'promote another user to Auditor first'::text
$$;

comment on function security.tenant_floor_roles() is
  'The roles every tenant must retain at least one active holder of. Read by the
   floor trigger, the integrity report and the repair path so all three agree.';

-- SECURITY DEFINER: the count must be the true count for the tenant, not the
-- count visible through whatever app.tenant_id happens to be bound. A trigger
-- that under-counts because of RLS would refuse valid work; one that over-counts
-- would wave through the very statement it exists to stop.
create or replace function security.assert_tenant_role_floor()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  affected record;
  survivors integer;
begin
  for affected in
    select distinct o.tenant_id, o.role
      from removed o
      join security.tenant_floor_roles() f on f.role_code = o.role
     where o.active
  loop
    select count(*) into survivors
      from identity.app_user u
     where u.tenant_id = affected.tenant_id
       and u.role = affected.role
       and u.active;

    if survivors = 0 then
      -- The whole refusal goes in `message`. `hint` and `detail` would be the
      -- tidier home for the remedy, but the JDBC driver is a runtime-scope
      -- dependency here, so the API layer can only read getMessage() without
      -- compiling against the driver's exception type. A constraint whose
      -- remedy the user never sees is half a constraint.
      raise exception using
        errcode = 'AX001',
        message = format(
          'This workspace must keep at least one active %s — the %s. '
          'That is the last one, so the change is refused. To proceed, %s, then repeat this change. '
          'Constraint: tenant administrator/auditor floor.',
          affected.role,
          (select f.label from security.tenant_floor_roles() f where f.role_code = affected.role),
          (select f.remedy from security.tenant_floor_roles() f where f.role_code = affected.role));
    end if;
  end loop;
  return null;
end
$$;

comment on function security.assert_tenant_role_floor() is
  'Statement-level backstop for the tenant administrator/auditor floor. Fires
   only when the statement itself empties a floor role, so a tenant that already
   has a gap is reported by security.tenant_role_floor_report() rather than
   having unrelated work blocked.';

drop trigger if exists trg_app_user_role_floor_update on identity.app_user;
drop trigger if exists trg_app_user_role_floor_delete on identity.app_user;

-- Two triggers rather than one: a single AFTER INSERT OR UPDATE OR DELETE
-- trigger may declare only one transition table name, and UPDATE needs OLD
-- while DELETE needs OLD under the same alias — separate triggers keep both
-- readable and let each name the transition set it actually inspects.
create trigger trg_app_user_role_floor_update
  after update on identity.app_user
  referencing old table as removed
  for each statement execute function security.assert_tenant_role_floor();

create trigger trg_app_user_role_floor_delete
  after delete on identity.app_user
  referencing old table as removed
  for each statement execute function security.assert_tenant_role_floor();

-- The integrity report. SECURITY DEFINER and owned by a BYPASSRLS role because
-- the question is explicitly cross-tenant: "which tenants are currently in
-- breach". A tenant-scoped answer cannot express that, and the platform
-- operator who has to act on it is not signed in to the offending tenant.
-- The API decides who may ask; this function decides what the honest answer is.
create or replace function security.tenant_role_floor_report(p_tenant_id uuid default null)
returns table(
  tenant_id      uuid,
  tenant_slug    text,
  tenant_name    text,
  role_code      text,
  requirement    text,
  active_holders integer,
  compliant      boolean,
  finding        text,
  remedy         text)
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
  select t.id,
         t.slug,
         t.name,
         f.role_code,
         f.label,
         coalesce(h.holders, 0)::integer,
         coalesce(h.holders, 0) >= 1,
         case
           when coalesce(h.holders, 0) = 0
             then format('No active %s. This tenant has no %s.', f.role_code, f.label)
           when h.holders = 1
             then format('Exactly one active %s. Removing it will be refused.', f.role_code)
           else format('%s active holders of %s.', h.holders, f.role_code)
         end,
         case when coalesce(h.holders, 0) = 0
              then format('Grant the %s role to a named, existing user of this tenant.', f.role_code)
              else null
         end
    from platform.tenant t
   cross join security.tenant_floor_roles() f
    left join lateral (
      select count(*) as holders
        from identity.app_user u
       where u.tenant_id = t.id
         and u.role = f.role_code
         and u.active
    ) h on true
   where p_tenant_id is null or t.id = p_tenant_id
   order by t.slug, f.role_code
$$;

comment on function security.tenant_role_floor_report(uuid) is
  'Cross-tenant integrity report for the administrator/auditor floor. Reports
   violations; never repairs them. Repair is an explicit, audited grant.';

grant execute on function security.tenant_role_floor_report(uuid) to axiom_app;
grant execute on function security.tenant_floor_roles() to axiom_app;

-- ===========================================================================
-- PART B — user activity
-- ===========================================================================

create schema if not exists activity;
grant usage on schema activity to axiom_app;

-- The runtime search_path is shared; merge rather than replace so a schema
-- registered by a migration that ran earlier survives (the V70/V90/V240 pattern).
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
  if not ('activity' = any(merged)) then
    merged := array_append(merged, 'activity');
  end if;
  merged := array_append(merged, 'public');
  execute format('alter role axiom_app set search_path to %s', array_to_string(merged, ', '));
end $$;

-- The allowlist. Everything the capture path is permitted to put in
-- user_activity.detail, and nothing else. Adding a key is a migration — which
-- is the point: it puts a reviewable change between someone's convenience and
-- a new class of value entering the log.
create table activity.detail_allowlist (
  detail_key text primary key,
  rationale  text not null
);

insert into activity.detail_allowlist(detail_key, rationale) values
  ('objectType',      'Which securable object the request touched'),
  ('objectId',        'Opaque record identifier; not itself personal data'),
  ('permission',      'The permission code that was evaluated'),
  ('denialReason',    'Why access was refused — the reason a review exists to read'),
  ('accessLevel',     'READ or READ_WRITE'),
  ('cause',           'record_share.cause for an access decision'),
  ('ruleCode',        'Sharing rule / SoD conflict code'),
  ('roleCode',        'Role node code involved in the change'),
  ('targetUserId',    'Subject of an administrative action, as an id'),
  ('targetRole',      'Role being granted or removed'),
  ('durationMs',      'Server-side latency'),
  ('resultCount',     'How many rows were returned or affected'),
  ('constraintName',  'Which declared constraint refused the request');

comment on table activity.detail_allowlist is
  'FR-AUD-014 enforcement surface. user_activity.detail may contain these keys
   and no others; the trigger below rejects the write rather than dropping the
   key, so a capture path that tries to log a token fails loudly in test.';

create table activity.user_activity (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),

  -- Actor. actor_id is nullable because an unauthenticated or rejected request
  -- is exactly the kind of event this table exists to keep.
  actor_id           uuid,
  actor_email        text,
  actor_role         text,

  -- The operator behind an impersonated principal (FR-TEN-011). Held separately
  -- so a review can see both identities without losing which one acted.
  impersonator_id    uuid,
  impersonator_email text,

  action             text not null,
  http_method        text,
  request_path       text,

  object_type        text,
  object_id          uuid,

  source             text not null default 'API'
                     check (source in ('UI','API','AUTOMATION','AI','MIGRATION','SYSTEM')),
  outcome            text not null
                     check (outcome in ('SUCCESS','DENIED','ERROR')),
  status_code        integer,
  denial_reason      text,

  correlation_id     text,
  client_ip          text,
  user_agent         text,
  detail             jsonb not null default '{}'::jsonb,
  occurred_at        timestamptz not null default now()
);

comment on column activity.user_activity.outcome is
  'SUCCESS, DENIED or ERROR. DENIED is the load-bearing one: a refused
   permission check writes no audit_event because nothing changed, and it is
   the event a security review most wants.';

comment on column activity.user_activity.request_path is
  'Path only — never the query string. Query strings carry filter values and,
   in practice, personal data (FR-AUD-014).';

create index idx_user_activity_feed on activity.user_activity(tenant_id, occurred_at desc);
create index idx_user_activity_actor on activity.user_activity(tenant_id, actor_id, occurred_at desc);
create index idx_user_activity_outcome on activity.user_activity(tenant_id, outcome, occurred_at desc);
create index idx_user_activity_action on activity.user_activity(tenant_id, action, occurred_at desc);
create index idx_user_activity_object on activity.user_activity(tenant_id, object_type, object_id, occurred_at desc);

-- Reject, do not sanitise. Silently dropping a disallowed key would let a
-- capture path that tries to log a bearer token keep trying forever, passing
-- every test, until the one day the allowlist is widened for another reason.
create or replace function activity.reject_unlisted_detail_keys()
returns trigger
language plpgsql
as $$
declare
  offending text[];
begin
  select array_agg(k order by k) into offending
    from jsonb_object_keys(new.detail) k
   where not exists (select 1 from activity.detail_allowlist a where a.detail_key = k);

  if offending is not null then
    raise exception using
      errcode = 'AX002',
      message = format('Activity detail may not carry the key(s) %s.', array_to_string(offending, ', ')),
      hint = 'Only allowlisted keys may be recorded (FR-AUD-014: no credentials, tokens or unmasked personal data).';
  end if;

  -- request_path must never carry a query string: filters routinely contain
  -- email addresses and identifiers.
  if new.request_path is not null and position('?' in new.request_path) > 0 then
    new.request_path := split_part(new.request_path, '?', 1);
  end if;

  return new;
end
$$;

create trigger trg_user_activity_detail_allowlist
  before insert or update on activity.user_activity
  for each row execute function activity.reject_unlisted_detail_keys();

-- Append-only. An access log a suspect can edit is not evidence.
create or replace function activity.reject_activity_mutation()
returns trigger
language plpgsql
as $$
begin
  raise exception using
    errcode = 'AX003',
    message = 'activity.user_activity is append-only; rows cannot be changed or removed.';
end
$$;

create trigger trg_user_activity_no_update
  before update on activity.user_activity
  for each row execute function activity.reject_activity_mutation();

create trigger trg_user_activity_no_delete
  before delete on activity.user_activity
  for each row execute function activity.reject_activity_mutation();

alter table activity.user_activity enable row level security;
alter table activity.user_activity force row level security;
create policy tenant_isolation on activity.user_activity
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

grant select, insert on activity.user_activity to axiom_app;
grant select on activity.detail_allowlist to axiom_app;

-- The trigger above sits BEFORE UPDATE/DELETE, but privileges are the cheaper
-- lock: revoke them too so the attempt fails before any trigger has to run.
revoke update, delete, truncate on activity.user_activity from axiom_app;

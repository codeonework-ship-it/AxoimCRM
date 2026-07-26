-- ---------------------------------------------------------------------------
-- E14 — automation, enforced business process and approvals
-- FRD §19, FR-AUT-001..014. ADR-003 (outbox consumer, at-least-once).
--
-- WHAT ALREADY EXISTED, AND WHY THIS FILE DOES NOT TOUCH IT
-- V94 created automation.automation_rule / automation_step / automation_run as
-- the *workspace* surface for E14 — three read-mostly tables the epic workspace
-- renders as evidence tiles. They are owned by another module and are left
-- exactly as they are. Everything the rules engine actually executes lives in
-- the new tables below, which is why the names do not collide.
--
-- NOTE ON `nullif(current_setting('app.tenant_id', true), '')::uuid`
-- Repeated from V10/V13/V240 because it is load-bearing. TenantSessionAspect
-- uses set_config(..., true) == SET LOCAL; when that transaction ends
-- PostgreSQL restores the placeholder GUC to the EMPTY STRING, not NULL, and a
-- bare ''::uuid cast raises `invalid input syntax for type uuid: ""` on the
-- next pooled connection. nullif() turns it into NULL, the comparison is NULL,
-- and the row is filtered out — the correct outcome for an unbound connection.
--
-- NOTE ON FR-AUT-004 ("cannot occur by ANY path")
-- The state machine is enforced by a database trigger, not only by the service
-- layer. A control that lives in one Java service is bypassed by the first
-- caller that writes SQL — and FR-AUT-004 says a transition not in the model
-- cannot occur by any path, which is a statement about the database, not about
-- one code path. The trigger is a no-op for a tenant/object with no ACTIVE
-- process definition, so installing it costs nothing until an administrator
-- activates a process.
--
-- NOTE ON FR-AUT-014 (no rule-count cap)
-- There is deliberately no CHECK, no counter column and no unique-per-object
-- ceiling anywhere in this file that could become a rule cap. Resource
-- protection is automation.throttle_window — a measured, visible, sliding
-- window — and nothing else.
-- ---------------------------------------------------------------------------

create schema if not exists automation;
grant usage on schema automation to axiom_app;

-- The runtime search_path is a shared resource: other module migrations land in
-- an order this file cannot know. Merge rather than replace (the V70/V90/V240
-- pattern) so a schema added by a migration that ran earlier is not dropped.
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
  if not ('automation' = any(merged)) then
    merged := array_append(merged, 'automation');
  end if;
  merged := array_append(merged, 'public');
  execute format('alter role axiom_app set search_path to %s', array_to_string(merged, ', '));
end $$;

-- ---------------------------------------------------------------------------
-- 1. Object metadata registry
--
-- The engine has to act on records owned by every other epic without importing
-- a single one of their service classes. It therefore drives everything from
-- this registry plus information_schema: an object type resolves to a schema,
-- a table and a set of columns, and every column name the engine ever
-- interpolates is checked against information_schema.columns first. That check
-- is what makes dynamic SQL safe here — an unknown column never reaches a
-- statement, so a tenant administrator cannot inject through a field name.
-- ---------------------------------------------------------------------------
create table automation.automation_object (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  object_type   text not null,
  label         text not null,
  schema_name   text not null,
  table_name    text not null,
  id_column     text not null default 'id',
  owner_column  text,
  soft_delete_column text,
  -- Columns automation may never write, whatever a rule says.
  protected_columns text[] not null default array['id','tenant_id','created_at','version']::text[],
  parent_object text,
  parent_column text,
  active        boolean not null default true,
  created_at    timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, object_type)
);

-- ---------------------------------------------------------------------------
-- 2. Rule definitions and versions (FR-AUT-001, 002, 003, 013)
--
-- The definition itself is a jsonb document, not a step table. A no-code
-- builder produces a tree — conditions with branches, loops over related
-- records, nested actions — and a flat ordered step table cannot represent a
-- tree without a parent pointer and a discriminator per node, which is a
-- document with extra steps. Keeping it as one document also makes versioning
-- exact: a version IS the document, so restoring one is a copy, not a replay.
-- ---------------------------------------------------------------------------
create table automation.rule_definition (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  rule_code     text not null,
  name          text not null,
  description   text,
  object_type   text not null,
  trigger_type  text not null check (trigger_type in ('RECORD_CHANGE','SCHEDULED','MANUAL')),
  status        text not null default 'DRAFT' check (status in ('DRAFT','ACTIVE','PAUSED','RETIRED')),
  active_version_no int not null default 1 check (active_version_no > 0),
  execution_order int not null default 100,
  created_by    uuid,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, rule_code)
);

create table automation.rule_version (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  rule_id       uuid not null,
  version_no    int not null check (version_no > 0),
  definition    jsonb not null,
  notes         text,
  -- Set when this version is created by restoring an earlier one, so the audit
  -- trail says "v5 is v2 restored" rather than losing the provenance.
  restored_from_version_no int,
  created_by    uuid,
  created_at    timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, rule_id, version_no),
  constraint fk_rule_version_rule_same_tenant
    foreign key (tenant_id, rule_id) references automation.rule_definition(tenant_id, id) on delete cascade
);

-- Scheduled automation bookkeeping (FR-AUT-002). Separate from the definition
-- so editing a schedule does not rewrite the rule document, and so a rule that
-- has never run is distinguishable from one that ran and produced nothing.
create table automation.rule_schedule_state (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  rule_id       uuid not null,
  last_fired_at timestamptz,
  next_due_at   timestamptz,
  unique (tenant_id, rule_id),
  constraint fk_rule_schedule_rule_same_tenant
    foreign key (tenant_id, rule_id) references automation.rule_definition(tenant_id, id) on delete cascade
);

-- ---------------------------------------------------------------------------
-- 3. Execution log (FR-AUT-011)
--
-- Two tables because the two questions are different: "what happened to this
-- record" is answered by the header, "why did step 4 do that" by the trace.
-- Dry runs are NOT written here. FR-AUT-010 says a simulation performs none of
-- the actions; a simulation that wrote a log row would have performed one, and
-- the cheapest way to be sure is for the code path to have no writer at all.
-- ---------------------------------------------------------------------------
create table automation.rule_execution (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  rule_id       uuid not null,
  rule_code     text not null,
  rule_version_no int not null,
  trigger_type  text not null,
  trigger_event text,
  object_type   text not null,
  record_id     uuid,
  entry_condition_met boolean not null default false,
  entry_condition_detail text,
  status        text not null check (status in ('SUCCEEDED','SKIPPED','FAILED','HALTED','THROTTLED')),
  halted_reason text,
  actions_executed int not null default 0,
  cascade_depth int not null default 0,
  duration_ms   int not null default 0,
  correlation_id text,
  started_at    timestamptz not null default now(),
  completed_at  timestamptz,
  unique (tenant_id, id),
  constraint fk_rule_execution_rule_same_tenant
    foreign key (tenant_id, rule_id) references automation.rule_definition(tenant_id, id) on delete cascade
);

create table automation.rule_execution_step (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  execution_id  uuid not null,
  step_no       int not null,
  step_key      text,
  step_type     text not null,
  label         text not null,
  outcome       text not null,
  detail        jsonb not null default '{}'::jsonb,
  duration_ms   int not null default 0,
  occurred_at   timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_execution_step_execution_same_tenant
    foreign key (tenant_id, execution_id) references automation.rule_execution(tenant_id, id) on delete cascade
);

create index idx_rule_execution_record on automation.rule_execution(tenant_id, object_type, record_id, started_at desc);
create index idx_rule_execution_rule on automation.rule_execution(tenant_id, rule_id, started_at desc);
create index idx_execution_step_execution on automation.rule_execution_step(tenant_id, execution_id, step_no);

-- Retention is configurable per FR-AUT-011, with a default rather than a
-- hard-coded sweep interval.
create table automation.execution_retention_policy (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  retain_days   int not null default 90 check (retain_days between 1 and 3650),
  updated_by    uuid,
  updated_at    timestamptz not null default now(),
  unique (tenant_id)
);

-- ---------------------------------------------------------------------------
-- 4. Fair-use throttling and its telemetry (FR-AUT-014)
--
-- This is the ONLY resource protection in the module. It bounds executions per
-- minute, not rules per object — a tenant may define as many rules as it likes
-- and every one of them is evaluated; what is bounded is how fast the engine
-- will do work, and the bound is measured and shown rather than implied.
-- ---------------------------------------------------------------------------
create table automation.throttle_policy (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  window_seconds     int not null default 60 check (window_seconds between 1 and 3600),
  max_executions     int not null default 2000 check (max_executions > 0),
  max_cascade_depth  int not null default 8 check (max_cascade_depth between 1 and 64),
  updated_by         uuid,
  updated_at         timestamptz not null default now(),
  unique (tenant_id)
);

create table automation.throttle_window (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  window_start  timestamptz not null,
  executions    int not null default 0,
  throttled     int not null default 0,
  unique (tenant_id, window_start)
);

create index idx_throttle_window_recent on automation.throttle_window(tenant_id, window_start desc);

-- ---------------------------------------------------------------------------
-- 5. Outbox consumption (ADR-003)
--
-- Delivery is at-least-once, so the receipt table is the idempotency key and
-- the `on conflict do nothing` insert is the whole control: a duplicate
-- delivery inserts zero rows, the handler sees zero and returns without acting.
-- The cursor exists because Kafka is not running in this environment and
-- ADR-003's documented degraded mode is that the outbox queues — the engine
-- drains it directly so the handlers are exercisable without a broker.
-- ---------------------------------------------------------------------------
create table automation.event_receipt (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  event_key     text not null,
  event_type    text not null,
  processed_at  timestamptz not null default now(),
  unique (tenant_id, event_key)
);

create table automation.event_cursor (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  consumer      text not null,
  last_event_at timestamptz not null default '1970-01-01T00:00:00Z',
  last_event_id uuid,
  updated_at    timestamptz not null default now(),
  unique (tenant_id, consumer)
);

-- ---------------------------------------------------------------------------
-- 6. Enforced business process (FR-AUT-004)
-- ---------------------------------------------------------------------------
create table automation.process_definition (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  process_code  text not null,
  name          text not null,
  object_type   text not null,
  -- The column holding the state. Validated against information_schema before
  -- it is ever interpolated.
  state_field   text not null,
  status        text not null default 'DRAFT' check (status in ('DRAFT','ACTIVE','RETIRED')),
  created_by    uuid,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, process_code)
);

-- At most one ACTIVE process per object per tenant: two active state machines
-- over the same column would each refuse the other's transitions.
create unique index uq_process_active_per_object
  on automation.process_definition(tenant_id, object_type) where status = 'ACTIVE';

create table automation.process_state (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  process_id       uuid not null,
  state_code       text not null,
  label            text not null,
  state_order      int not null default 100,
  is_initial       boolean not null default false,
  is_terminal      boolean not null default false,
  mandatory_fields text[] not null default array[]::text[],
  entry_actions    jsonb not null default '[]'::jsonb,
  sla_minutes      int check (sla_minutes is null or sla_minutes > 0),
  unique (tenant_id, id),
  unique (tenant_id, process_id, state_code),
  constraint fk_process_state_process_same_tenant
    foreign key (tenant_id, process_id) references automation.process_definition(tenant_id, id) on delete cascade
);

create table automation.process_transition (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  process_id      uuid not null,
  from_state      text not null,
  to_state        text not null,
  label           text not null,
  -- [{"field":"amount","op":"GTE","value":"100000","label":"Amount at or above 100,000"}]
  conditions      jsonb not null default '[]'::jsonb,
  required_role   text,
  unique (tenant_id, id),
  unique (tenant_id, process_id, from_state, to_state),
  constraint fk_process_transition_process_same_tenant
    foreign key (tenant_id, process_id) references automation.process_definition(tenant_id, id) on delete cascade
);

create table automation.process_instance (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  process_id     uuid not null,
  object_type    text not null,
  record_id      uuid not null,
  current_state  text not null,
  previous_state text,
  entered_at     timestamptz not null default now(),
  sla_due_at     timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, process_id, record_id),
  constraint fk_process_instance_process_same_tenant
    foreign key (tenant_id, process_id) references automation.process_definition(tenant_id, id) on delete cascade
);

create table automation.process_transition_log (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  process_id    uuid not null,
  object_type   text not null,
  record_id     uuid not null,
  from_state    text,
  to_state      text not null,
  actor_id      uuid,
  occurred_at   timestamptz not null default now(),
  unique (tenant_id, id)
);

create index idx_process_instance_sla on automation.process_instance(tenant_id, sla_due_at) where sla_due_at is not null;
create index idx_process_transition_log_record on automation.process_transition_log(tenant_id, object_type, record_id, occurred_at desc);

-- ---------------------------------------------------------------------------
-- 7. Validation rules (FR-AUT-005)
-- ---------------------------------------------------------------------------
create table automation.validation_rule (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  rule_code     text not null,
  name          text not null,
  object_type   text not null,
  -- The expression that, when TRUE, means the record is INVALID. Same polarity
  -- as Salesforce's validation rules, and the polarity administrators expect.
  expression    text not null,
  message       text not null,
  target_field  text,
  active        boolean not null default true,
  created_by    uuid,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, rule_code)
);

-- ---------------------------------------------------------------------------
-- 8. Approvals (FR-AUT-007, FR-AUT-008, FR-SEC-010)
-- ---------------------------------------------------------------------------
create table automation.approval_process (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  process_code    text not null,
  name            text not null,
  object_type     text not null,
  entry_condition text,
  amount_field    text,
  status          text not null default 'DRAFT' check (status in ('DRAFT','ACTIVE','RETIRED')),
  created_by      uuid,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, process_code)
);

create table automation.approval_step (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        uuid not null references platform.tenant(id),
  approval_process_id uuid not null,
  step_no          int not null check (step_no > 0),
  name             text not null,
  -- Steps sharing a parallel_group are dispatched together; groups run in
  -- ascending order. One column expresses serial AND parallel without a second
  -- shape to keep in sync.
  parallel_group   int not null default 0,
  decision_policy  text not null default 'UNANIMOUS'
                   check (decision_policy in ('UNANIMOUS','FIRST_RESPONSE')),
  approver_type    text not null
                   check (approver_type in ('USER','HIERARCHY','FIELD','AMOUNT_MATRIX','QUEUE')),
  approver_config  jsonb not null default '{}'::jsonb,
  unique (tenant_id, id),
  unique (tenant_id, approval_process_id, step_no),
  constraint fk_approval_step_process_same_tenant
    foreign key (tenant_id, approval_process_id) references automation.approval_process(tenant_id, id) on delete cascade
);

create table automation.approval_amount_band (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  step_id       uuid not null,
  min_amount    numeric not null default 0,
  max_amount    numeric,
  approver_id   uuid not null,
  unique (tenant_id, id),
  constraint fk_amount_band_step_same_tenant
    foreign key (tenant_id, step_id) references automation.approval_step(tenant_id, id) on delete cascade,
  constraint fk_amount_band_approver_same_tenant
    foreign key (tenant_id, approver_id) references identity.app_user(tenant_id, id)
);

create table automation.approval_queue (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  queue_code    text not null,
  name          text not null,
  unique (tenant_id, id),
  unique (tenant_id, queue_code)
);

create table automation.approval_queue_member (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  queue_id      uuid not null,
  user_id       uuid not null,
  unique (tenant_id, queue_id, user_id),
  constraint fk_queue_member_queue_same_tenant
    foreign key (tenant_id, queue_id) references automation.approval_queue(tenant_id, id) on delete cascade,
  constraint fk_queue_member_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

create table automation.approval_instance (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references platform.tenant(id),
  approval_process_id uuid not null,
  object_type       text not null,
  record_id         uuid not null,
  subject           text not null,
  amount            numeric,
  submitted_by      uuid not null,
  submitted_at      timestamptz not null default now(),
  status            text not null default 'PENDING'
                    check (status in ('PENDING','APPROVED','REJECTED','RECALLED')),
  current_group     int not null default 0,
  decided_at        timestamptz,
  rejection_reason  text,
  resubmission_of   uuid,
  submission_no     int not null default 1 check (submission_no > 0),
  unique (tenant_id, id),
  constraint fk_approval_instance_process_same_tenant
    foreign key (tenant_id, approval_process_id) references automation.approval_process(tenant_id, id),
  constraint fk_approval_instance_submitter_same_tenant
    foreign key (tenant_id, submitted_by) references identity.app_user(tenant_id, id),
  -- A rejection without a reason is not a decision anyone can act on, so the
  -- requirement is a table constraint rather than a controller check.
  constraint ck_approval_rejection_reason
    check (status <> 'REJECTED' or coalesce(btrim(rejection_reason), '') <> '')
);

create table automation.approval_task (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  instance_id    uuid not null,
  step_id        uuid not null,
  step_no        int not null,
  parallel_group int not null default 0,
  approver_id    uuid not null,
  -- Both identities, per FR-AUT-008: who is being asked, and whose authority
  -- they are exercising. NULL when the approver holds the authority directly.
  on_behalf_of   uuid,
  delegation_id  uuid,
  assigned_via   text not null,
  status         text not null default 'PENDING'
                 check (status in ('PENDING','APPROVED','REJECTED','SKIPPED','CANCELLED')),
  comment        text,
  decided_at     timestamptz,
  created_at     timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_approval_task_instance_same_tenant
    foreign key (tenant_id, instance_id) references automation.approval_instance(tenant_id, id) on delete cascade,
  constraint fk_approval_task_approver_same_tenant
    foreign key (tenant_id, approver_id) references identity.app_user(tenant_id, id)
);

create index idx_approval_task_inbox on automation.approval_task(tenant_id, approver_id, status);
create index idx_approval_instance_record on automation.approval_instance(tenant_id, object_type, record_id, submitted_at desc);

create table automation.approval_delegation (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  delegator_id  uuid not null,
  delegate_id   uuid not null,
  starts_at     timestamptz not null default now(),
  ends_at       timestamptz not null,
  reason        text,
  active        boolean not null default true,
  created_by    uuid,
  created_at    timestamptz not null default now(),
  unique (tenant_id, id),
  -- "for a bounded period" is the requirement; an open-ended delegation is a
  -- permanent transfer of authority wearing a delegation's name.
  constraint ck_delegation_bounded check (ends_at > starts_at),
  constraint ck_delegation_not_self check (delegator_id <> delegate_id),
  constraint fk_delegation_delegator_same_tenant
    foreign key (tenant_id, delegator_id) references identity.app_user(tenant_id, id),
  constraint fk_delegation_delegate_same_tenant
    foreign key (tenant_id, delegate_id) references identity.app_user(tenant_id, id)
);

create index idx_delegation_window on automation.approval_delegation(tenant_id, delegator_id, active, starts_at, ends_at);

-- ---------------------------------------------------------------------------
-- 9. The state-machine trigger (FR-AUT-004)
-- ---------------------------------------------------------------------------

-- Condition evaluation shared by the trigger. Kept as its own function so the
-- trigger body stays readable and the operator set has exactly one definition.
create or replace function automation.process_condition_holds(
  p_actual text, p_op text, p_expected text) returns boolean
language plpgsql immutable as $fn$
begin
  case upper(p_op)
    when 'EQ'        then return p_actual is not distinct from p_expected;
    when 'NEQ'       then return p_actual is distinct from p_expected;
    when 'BLANK'     then return coalesce(btrim(coalesce(p_actual, '')), '') = '';
    when 'NOT_BLANK' then return coalesce(btrim(coalesce(p_actual, '')), '') <> '';
    when 'IS_TRUE'   then return p_actual = 'true';
    when 'IS_FALSE'  then return p_actual = 'false';
    when 'GT'        then return p_actual is not null and p_actual::numeric >  p_expected::numeric;
    when 'GTE'       then return p_actual is not null and p_actual::numeric >= p_expected::numeric;
    when 'LT'        then return p_actual is not null and p_actual::numeric <  p_expected::numeric;
    when 'LTE'       then return p_actual is not null and p_actual::numeric <= p_expected::numeric;
    when 'IN'        then return p_actual = any(string_to_array(coalesce(p_expected, ''), '|'));
    else return false;
  end case;
exception when others then
  -- A malformed comparison is an unsatisfied condition, never a satisfied one.
  return false;
end
$fn$;

create or replace function automation.enforce_process_transition() returns trigger
language plpgsql as $fn$
declare
  v_object_type text := tg_argv[0];
  v_proc        record;
  v_state       record;
  v_tr          record;
  v_new_json    jsonb;
  v_old_state   text;
  v_new_state   text;
  v_field       text;
  v_cond        jsonb;
  v_actual      text;
begin
  select id, process_code, name, state_field
    into v_proc
    from automation.process_definition
   where tenant_id = new.tenant_id
     and object_type = v_object_type
     and status = 'ACTIVE'
   limit 1;

  -- No active process for this tenant and object: the trigger costs one
  -- indexed lookup and gets out of the way.
  if not found then
    return new;
  end if;

  v_new_json  := to_jsonb(new);
  v_new_state := v_new_json ->> v_proc.state_field;
  v_old_state := case when tg_op = 'INSERT' then null
                      else to_jsonb(old) ->> v_proc.state_field end;

  if tg_op <> 'INSERT' and v_old_state is not distinct from v_new_state then
    return new;
  end if;

  select * into v_state
    from automation.process_state
   where tenant_id = new.tenant_id and process_id = v_proc.id and state_code = v_new_state;
  if not found then
    raise exception using errcode = '23514', message = format(
      'PROCESS_REFUSED: business process "%s" has no state "%s". Define it before a record may enter it.',
      v_proc.process_code, coalesce(v_new_state, '(null)'));
  end if;

  if tg_op = 'INSERT' then
    if not v_state.is_initial then
      raise exception using errcode = '23514', message = format(
        'PROCESS_REFUSED: business process "%s" does not permit a record to be created directly in state "%s"; it is not an entry state.',
        v_proc.process_code, v_new_state);
    end if;
  else
    select * into v_tr
      from automation.process_transition
     where tenant_id = new.tenant_id and process_id = v_proc.id
       and from_state = v_old_state and to_state = v_new_state;
    if not found then
      raise exception using errcode = '23514', message = format(
        'PROCESS_REFUSED: business process "%s" defines no transition from "%s" to "%s". Permitted from "%s": %s.',
        v_proc.process_code, v_old_state, v_new_state, v_old_state,
        coalesce((select string_agg(to_state, ', ' order by to_state)
                    from automation.process_transition
                   where tenant_id = new.tenant_id and process_id = v_proc.id
                     and from_state = v_old_state), '(none)'));
    end if;

    for v_cond in select value from jsonb_array_elements(coalesce(v_tr.conditions, '[]'::jsonb)) loop
      v_actual := v_new_json ->> (v_cond ->> 'field');
      if not automation.process_condition_holds(v_actual, v_cond ->> 'op', v_cond ->> 'value') then
        raise exception using errcode = '23514', message = format(
          'PROCESS_REFUSED: transition "%s" (%s to %s) requires %s. Unsatisfied condition: %s.',
          v_tr.label, v_old_state, v_new_state,
          coalesce(v_cond ->> 'label', format('%s %s %s', v_cond ->> 'field', v_cond ->> 'op', v_cond ->> 'value')),
          format('%s %s %s (actual: %s)', v_cond ->> 'field', v_cond ->> 'op',
                 coalesce(v_cond ->> 'value', ''), coalesce(v_actual, 'null')));
      end if;
    end loop;
  end if;

  foreach v_field in array v_state.mandatory_fields loop
    if coalesce(btrim(coalesce(v_new_json ->> v_field, '')), '') = '' then
      raise exception using errcode = '23514', message = format(
        'PROCESS_REFUSED: state "%s" of business process "%s" requires "%s" to be set before the record may enter it. Unsatisfied condition: %s is mandatory in %s.',
        v_new_state, v_proc.process_code, v_field, v_field, v_new_state);
    end if;
  end loop;

  insert into automation.process_instance
    (tenant_id, process_id, object_type, record_id, current_state, previous_state, entered_at, sla_due_at)
  values (new.tenant_id, v_proc.id, v_object_type, new.id, v_new_state, v_old_state, now(),
          case when v_state.sla_minutes is null then null
               else now() + make_interval(mins => v_state.sla_minutes) end)
  on conflict (tenant_id, process_id, record_id) do update
    set previous_state = automation.process_instance.current_state,
        current_state  = excluded.current_state,
        entered_at     = excluded.entered_at,
        sla_due_at     = excluded.sla_due_at;

  insert into automation.process_transition_log
    (tenant_id, process_id, object_type, record_id, from_state, to_state, actor_id)
  values (new.tenant_id, v_proc.id, v_object_type, new.id, v_old_state, v_new_state,
          nullif(current_setting('app.user_id', true), '')::uuid);

  return new;
end
$fn$;

-- Attached to the tables an administrator can put a process on today. The
-- trigger short-circuits when no process is ACTIVE, so this is inert until an
-- administrator activates one.
create trigger trg_enforce_process_opportunity
  before insert or update on sales.opportunity
  for each row execute function automation.enforce_process_transition('OPPORTUNITY');

create trigger trg_enforce_process_lead
  before insert or update on crm.lead
  for each row execute function automation.enforce_process_transition('LEAD');

create trigger trg_enforce_process_account
  before insert or update on crm.account
  for each row execute function automation.enforce_process_transition('ACCOUNT');

-- ---------------------------------------------------------------------------
-- 10. RLS, grants and governance registration
-- ---------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array[
    'automation.automation_object',
    'automation.rule_definition','automation.rule_version','automation.rule_schedule_state',
    'automation.rule_execution','automation.rule_execution_step',
    'automation.execution_retention_policy',
    'automation.throttle_policy','automation.throttle_window',
    'automation.event_receipt','automation.event_cursor',
    'automation.process_definition','automation.process_state','automation.process_transition',
    'automation.process_instance','automation.process_transition_log',
    'automation.validation_rule',
    'automation.approval_process','automation.approval_step','automation.approval_amount_band',
    'automation.approval_queue','automation.approval_queue_member',
    'automation.approval_instance','automation.approval_task','automation.approval_delegation'
  ] loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format($p$create policy tenant_isolation on %s
        using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
        with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
    execute format('grant select, insert, update, delete on %s to axiom_app', t);
  end loop;
end $$;

-- The trigger runs as the invoking role and reads the process model, so
-- axiom_app needs execute on both functions.
grant execute on function automation.process_condition_holds(text, text, text) to axiom_app;
grant execute on function automation.enforce_process_transition() to axiom_app;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('automation','automation_object','AUTOMATION','id',true,'ACTIVE'),
  ('automation','rule_definition','AUTOMATION','id',true,'ACTIVE'),
  ('automation','rule_version','AUTOMATION','id',true,'APPEND_ONLY'),
  ('automation','rule_schedule_state','AUTOMATION','id',true,'ACTIVE'),
  ('automation','rule_execution','AUTOMATION','id',true,'APPEND_ONLY'),
  ('automation','rule_execution_step','AUTOMATION','id',true,'APPEND_ONLY'),
  ('automation','execution_retention_policy','AUTOMATION','id',true,'ACTIVE'),
  ('automation','throttle_policy','AUTOMATION','id',true,'ACTIVE'),
  ('automation','throttle_window','AUTOMATION','id',true,'APPEND_ONLY'),
  ('automation','event_receipt','AUTOMATION','id',true,'APPEND_ONLY'),
  ('automation','event_cursor','AUTOMATION','id',true,'ACTIVE'),
  ('automation','process_definition','AUTOMATION','id',true,'ACTIVE'),
  ('automation','process_state','AUTOMATION','id',true,'ACTIVE'),
  ('automation','process_transition','AUTOMATION','id',true,'ACTIVE'),
  ('automation','process_instance','AUTOMATION','id',true,'ACTIVE'),
  ('automation','process_transition_log','AUTOMATION','id',true,'APPEND_ONLY'),
  ('automation','validation_rule','AUTOMATION','id',true,'ACTIVE'),
  ('automation','approval_process','AUTOMATION','id',true,'ACTIVE'),
  ('automation','approval_step','AUTOMATION','id',true,'ACTIVE'),
  ('automation','approval_amount_band','AUTOMATION','id',true,'ACTIVE'),
  ('automation','approval_queue','AUTOMATION','id',true,'ACTIVE'),
  ('automation','approval_queue_member','AUTOMATION','id',true,'ACTIVE'),
  ('automation','approval_instance','AUTOMATION','id',true,'ACTIVE'),
  ('automation','approval_task','AUTOMATION','id',true,'ACTIVE'),
  ('automation','approval_delegation','AUTOMATION','id',true,'ACTIVE')
on conflict do nothing;

-- ---------------------------------------------------------------------------
-- 11. Seed: object registry, policies and a working demonstration set
-- ---------------------------------------------------------------------------
insert into automation.automation_object
  (tenant_id, object_type, label, schema_name, table_name, owner_column, soft_delete_column,
   protected_columns, parent_object, parent_column)
select t.id, seed.object_type, seed.label, seed.schema_name, seed.table_name,
       seed.owner_column, seed.soft_delete_column,
       array['id','tenant_id','created_at','version']::text[],
       seed.parent_object, seed.parent_column
from platform.tenant t
cross join (values
  ('ACCOUNT','Account','crm','account','owner_id','deleted_at',null,null),
  ('CONTACT','Contact','crm','contact',null,'deleted_at','ACCOUNT','account_id'),
  ('LEAD','Lead','crm','lead','owner_id','deleted_at',null,null),
  ('OPPORTUNITY','Opportunity','sales','opportunity','owner_id',null,'ACCOUNT','account_id'),
  ('ACTIVITY','Activity','engagement','activity','owner_id','deleted_at',null,null)
) as seed(object_type, label, schema_name, table_name, owner_column, soft_delete_column,
          parent_object, parent_column)
on conflict (tenant_id, object_type) do nothing;

insert into automation.throttle_policy(tenant_id)
select id from platform.tenant on conflict (tenant_id) do nothing;

insert into automation.execution_retention_policy(tenant_id)
select id from platform.tenant on conflict (tenant_id) do nothing;

-- A rule that fires on a real opportunity update. Kept ACTIVE because it is
-- non-destructive: it writes next_step and a notification, nothing else.
insert into automation.rule_definition
  (tenant_id, rule_code, name, description, object_type, trigger_type, status, active_version_no, created_by)
select u.tenant_id, seed.rule_code, seed.name, seed.description, seed.object_type,
       seed.trigger_type, seed.status, 1, u.id
from identity.app_user u
join (values
  ('AUT-BIGDEAL-FLAG', 'Flag large deals for review',
   'When an opportunity amount crosses 500,000 the next step is set and the owner is notified.',
   'OPPORTUNITY', 'RECORD_CHANGE', 'ACTIVE'),
  ('AUT-RENEWAL-SWEEP', 'Renewal sweep 30 days before close',
   'Scheduled relative to the opportunity close date.',
   'OPPORTUNITY', 'SCHEDULED', 'ACTIVE'),
  ('AUT-LOOP-ALPHA', 'Cascade demonstration A',
   'Deliberately paired with AUT-LOOP-BETA to exercise recursion protection. Left PAUSED.',
   'OPPORTUNITY', 'RECORD_CHANGE', 'PAUSED'),
  ('AUT-LOOP-BETA', 'Cascade demonstration B',
   'Deliberately paired with AUT-LOOP-ALPHA to exercise recursion protection. Left PAUSED.',
   'OPPORTUNITY', 'RECORD_CHANGE', 'PAUSED')
) as seed(rule_code, name, description, object_type, trigger_type, status)
  on true
where u.email = 'raj.malhotra@meridianfab.com'
on conflict (tenant_id, rule_code) do nothing;

insert into automation.rule_version(tenant_id, rule_id, version_no, definition, notes, created_by)
select r.tenant_id, r.id, 1, seed.definition::jsonb, 'Seeded baseline', r.created_by
from automation.rule_definition r
join (values
  ('AUT-BIGDEAL-FLAG', '{
     "trigger": {"type":"RECORD_CHANGE","events":["CREATE","UPDATE"]},
     "entryCondition": "ISCHANGED(amount) AND NEW.amount > 500000",
     "steps": [
       {"key":"s1","type":"ACTION","actionType":"UPDATE_FIELDS","label":"Set the next step",
        "target":"TRIGGERING",
        "fields":{"next_step":"CONCAT(''Executive review required for '', TEXT(NEW.amount))"}},
       {"key":"s2","type":"ACTION","actionType":"SEND_NOTIFICATION","label":"Notify the owner",
        "recipientField":"owner_id","title":"Large deal flagged",
        "body":"CONCAT(NEW.name, '' crossed 500,000 and needs executive review.'')"}
     ]}'),
  ('AUT-RENEWAL-SWEEP', '{
     "trigger": {"type":"SCHEDULED","schedule":{"mode":"RELATIVE_TO_FIELD","dateField":"close_date","offsetDays":-30,"timeOfDay":"08:00"}},
     "entryCondition": "NEW.is_closed = false",
     "steps": [
       {"key":"s1","type":"ACTION","actionType":"CREATE_TASK","label":"Raise a renewal task",
        "subject":"CONCAT(''Renewal check: '', NEW.name)","priority":"HIGH","dueInDays":3}
     ]}'),
  ('AUT-LOOP-ALPHA', '{
     "trigger": {"type":"RECORD_CHANGE","events":["UPDATE"]},
     "entryCondition": "ISCHANGED(next_step)",
     "steps": [
       {"key":"s1","type":"ACTION","actionType":"UPDATE_FIELDS","label":"Bump the forecast category",
        "target":"TRIGGERING","fields":{"forecast_category":"CONCAT(''A'', TEXT(LEN(NEW.next_step)))"}}
     ]}'),
  ('AUT-LOOP-BETA', '{
     "trigger": {"type":"RECORD_CHANGE","events":["UPDATE"]},
     "entryCondition": "ISCHANGED(forecast_category)",
     "steps": [
       {"key":"s1","type":"ACTION","actionType":"UPDATE_FIELDS","label":"Bump the next step",
        "target":"TRIGGERING","fields":{"next_step":"CONCAT(''B'', TEXT(LEN(NEW.forecast_category)))"}}
     ]}')
) as seed(rule_code, definition) on seed.rule_code = r.rule_code
on conflict (tenant_id, rule_id, version_no) do nothing;

insert into automation.validation_rule
  (tenant_id, rule_code, name, object_type, expression, message, target_field, created_by)
select u.tenant_id, 'VAL-OPP-NEGATIVE-AMOUNT', 'Opportunity amount cannot be negative',
       'OPPORTUNITY', 'NEW.amount < 0',
       'The opportunity amount cannot be negative.', 'amount', u.id
from identity.app_user u where u.email = 'raj.malhotra@meridianfab.com'
on conflict (tenant_id, rule_code) do nothing;

-- Business process, seeded DRAFT. Activating it turns on database-level
-- enforcement for every writer of sales.opportunity in this tenant, so that is
-- an administrator's decision, not a migration's.
insert into automation.process_definition
  (tenant_id, process_code, name, object_type, state_field, status, created_by)
select u.tenant_id, 'PRC-OPP-FORECAST', 'Opportunity forecast discipline',
       'OPPORTUNITY', 'forecast_category', 'DRAFT', u.id
from identity.app_user u where u.email = 'raj.malhotra@meridianfab.com'
on conflict (tenant_id, process_code) do nothing;

insert into automation.process_state
  (tenant_id, process_id, state_code, label, state_order, is_initial, is_terminal, mandatory_fields, sla_minutes)
select p.tenant_id, p.id, seed.state_code, seed.label, seed.state_order,
       seed.is_initial, seed.is_terminal, seed.mandatory_fields::text[], seed.sla_minutes
from automation.process_definition p
join (values
  ('PIPELINE',  'Pipeline',   10, true,  false, '{}',            null::int),
  ('BEST_CASE', 'Best case',  20, false, false, '{next_step}',   10080),
  ('COMMIT',    'Commit',     30, false, false, '{next_step,close_date}', 4320),
  ('CLOSED',    'Closed',     40, false, true,  '{}',            null::int)
) as seed(state_code, label, state_order, is_initial, is_terminal, mandatory_fields, sla_minutes)
  on true
where p.process_code = 'PRC-OPP-FORECAST'
on conflict (tenant_id, process_id, state_code) do nothing;

insert into automation.process_transition
  (tenant_id, process_id, from_state, to_state, label, conditions)
select p.tenant_id, p.id, seed.from_state, seed.to_state, seed.label, seed.conditions::jsonb
from automation.process_definition p
join (values
  ('PIPELINE','BEST_CASE','Promote to best case','[]'),
  ('BEST_CASE','COMMIT','Commit the deal',
   '[{"field":"amount","op":"GTE","value":"100000","label":"an amount of at least 100,000"},{"field":"close_date","op":"NOT_BLANK","value":"","label":"a close date"}]'),
  ('BEST_CASE','PIPELINE','Return to pipeline','[]'),
  ('COMMIT','CLOSED','Close the deal','[]'),
  ('COMMIT','BEST_CASE','Withdraw the commit','[]')
) as seed(from_state, to_state, label, conditions) on true
where p.process_code = 'PRC-OPP-FORECAST'
on conflict (tenant_id, process_id, from_state, to_state) do nothing;

-- Approval process: two serial groups, the second parallel and unanimous.
insert into automation.approval_process
  (tenant_id, process_code, name, object_type, entry_condition, amount_field, status, created_by)
select u.tenant_id, 'APR-OPP-DISCOUNT', 'Large opportunity approval', 'OPPORTUNITY',
       'NEW.amount > 100000', 'amount', 'ACTIVE', u.id
from identity.app_user u where u.email = 'raj.malhotra@meridianfab.com'
on conflict (tenant_id, process_code) do nothing;

insert into automation.approval_step
  (tenant_id, approval_process_id, step_no, name, parallel_group, decision_policy,
   approver_type, approver_config)
select ap.tenant_id, ap.id, seed.step_no, seed.name, seed.parallel_group, seed.decision_policy,
       seed.approver_type, seed.approver_config::jsonb
from automation.approval_process ap
join (values
  (1, 'Sales management sign-off', 1, 'FIRST_RESPONSE', 'AMOUNT_MATRIX', '{}'),
  (2, 'Finance sign-off',          2, 'UNANIMOUS',      'QUEUE',        '{"queueCode":"QUE-FINANCE"}')
) as seed(step_no, name, parallel_group, decision_policy, approver_type, approver_config) on true
where ap.process_code = 'APR-OPP-DISCOUNT'
on conflict (tenant_id, approval_process_id, step_no) do nothing;

insert into automation.approval_queue(tenant_id, queue_code, name)
select id, 'QUE-FINANCE', 'Finance approvers' from platform.tenant
on conflict (tenant_id, queue_code) do nothing;

insert into automation.approval_queue_member(tenant_id, queue_id, user_id)
select q.tenant_id, q.id, u.id
from automation.approval_queue q
join identity.app_user u on u.tenant_id = q.tenant_id
where q.queue_code = 'QUE-FINANCE'
  and u.email in ('priya.nair@meridianfab.com', 'raj.malhotra@meridianfab.com')
on conflict (tenant_id, queue_id, user_id) do nothing;

insert into automation.approval_amount_band(tenant_id, step_id, min_amount, max_amount, approver_id)
select s.tenant_id, s.id, seed.min_amount, seed.max_amount, u.id
from automation.approval_step s
join automation.approval_process ap
  on ap.tenant_id = s.tenant_id and ap.id = s.approval_process_id
join (values
  (0::numeric,       250000::numeric, 'priya.nair@meridianfab.com'),
  (250000::numeric,  null::numeric,   'raj.malhotra@meridianfab.com')
) as seed(min_amount, max_amount, approver_email) on true
join identity.app_user u on u.tenant_id = s.tenant_id and u.email = seed.approver_email
where ap.process_code = 'APR-OPP-DISCOUNT' and s.step_no = 1
on conflict do nothing;

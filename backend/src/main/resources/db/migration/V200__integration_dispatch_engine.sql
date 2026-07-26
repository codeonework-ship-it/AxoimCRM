-- Integration dispatch engine (E17 · ADR-003 · ADR-007 · FR-INT-005..009).
--
-- The outbox relay (V1/V3, com.axiom.outbox) already publishes domain events.
-- NOTHING consumed them onward to external systems. This migration creates the
-- outbound half: a connector registry, named credentials, event subscriptions,
-- an idempotent delivery queue, a per-attempt trace, a dead-letter store with
-- replay, and per-connector breaker/health state.
--
-- Module ownership: its own physical schema `dispatch`, following the V6/V7
-- pattern. The existing `integration` schema holds the earlier read-only
-- contract/job/webhook-stub registers (V95) and the outbox itself; the dispatch
-- engine is deliberately not mixed into it.
--
-- RLS NOTE (proven on the dev DB, do not regress): a `SET LOCAL` GUC that has
-- been reset reverts to the EMPTY STRING, not NULL, and `''::uuid` throws
-- `invalid input syntax for type uuid: ""`. Every policy below therefore uses
-- `nullif(current_setting('app.tenant_id', true), '')::uuid`, which yields NULL
-- and admits zero rows rather than erroring.

create schema if not exists dispatch;
grant usage on schema dispatch to axiom_app;

-- The runtime role's search_path is a shared resource: other module migrations
-- append to it concurrently, so read the current value and append rather than
-- restating a fixed list that would silently drop another module's schema.
do $$
declare
  current_path text;
begin
  select substring(cfg from 'search_path=(.*)') into current_path
    from pg_roles r, unnest(r.rolconfig) cfg
   where r.rolname = 'axiom_app' and cfg like 'search_path=%';
  if current_path is null then
    execute 'alter role axiom_app set search_path to dispatch, public';
  elsif position('dispatch' in current_path) = 0 then
    execute format('alter role axiom_app set search_path to %s', 'dispatch, ' || current_path);
  end if;
end
$$;

-- ---------------------------------------------------------------------------
-- Named credentials (FR-INT-007)
-- ---------------------------------------------------------------------------
-- The secret is stored as AES-256-GCM ciphertext produced by
-- com.axiom.common.SecretCipher (reused, not reimplemented). It is never
-- selected by any read API; the read DTO carries a masked marker only.
create table dispatch.named_credential (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  name text not null,
  credential_type text not null check (credential_type in
    ('WEBHOOK_SIGNING_SECRET','BEARER_TOKEN','API_KEY','BASIC_AUTH','MTLS_KEYPAIR')),
  secret_cipher text not null,
  description text,
  rotated_at timestamptz,
  last_used_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  created_by uuid,
  unique (tenant_id, id),
  unique (tenant_id, name),
  constraint named_credential_name_format check (name ~ '^[A-Za-z][A-Za-z0-9_.-]{2,63}$')
);

-- ---------------------------------------------------------------------------
-- Connector registry (FR-INT-008)
-- ---------------------------------------------------------------------------
create table dispatch.connector (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  connector_type text not null check (connector_type in
    ('WEBHOOK','ERP','ESIGN','MARKETING','ENRICHMENT','CTRM')),
  vendor text not null,
  display_name text not null,
  enabled boolean not null default true,
  config jsonb not null default '{}'::jsonb,
  -- Credential is referenced BY NAME, never inlined (FR-INT-007). Not a foreign
  -- key on purpose: a connector may be configured before its credential exists,
  -- and resolution failure must surface as a dispatch failure a human can see
  -- rather than as a constraint violation on save.
  credential_ref text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  created_by uuid,
  unique (tenant_id, id),
  unique (tenant_id, display_name)
);

-- ---------------------------------------------------------------------------
-- Outbound event subscriptions
-- ---------------------------------------------------------------------------
-- This is what turns a domain event into a dispatch. `event_type_pattern`
-- supports a trailing `*` wildcard and the bare `*`; `filter_expression` is a
-- conjunction of `payloadKey=value` terms, evaluated against the event payload.
create table dispatch.event_subscription (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  connector_id uuid not null,
  event_type_pattern text not null,
  filter_expression text,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, connector_id, event_type_pattern),
  constraint fk_event_subscription_connector_same_tenant
    foreign key (tenant_id, connector_id) references dispatch.connector(tenant_id, id) on delete cascade
);

-- ---------------------------------------------------------------------------
-- Delivery queue — the idempotency guard (ADR-003 rule 3)
-- ---------------------------------------------------------------------------
-- ONE row per (subscription, source event). The unique constraint is the whole
-- guarantee: the at-least-once backbone can hand us the same event any number
-- of times and the insert is a no-op after the first, so exactly one dispatch
-- happens. Replay from the dead-letter store re-uses the SAME row rather than
-- inserting a second one, so replay is idempotent by the same constraint.
create table dispatch.dispatch_delivery (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  subscription_id uuid not null,
  connector_id uuid not null,
  event_id uuid not null,
  event_type text not null,
  aggregate_type text not null,
  aggregate_id uuid,
  payload jsonb not null,
  event_occurred_at timestamptz not null,
  status text not null default 'PENDING' check (status in
    ('PENDING','IN_FLIGHT','SUCCEEDED','DEAD_LETTERED')),
  attempt_count int not null default 0 check (attempt_count >= 0),
  next_attempt_at timestamptz not null default now(),
  last_error text,
  last_http_status int,
  succeeded_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, subscription_id, event_id),
  constraint fk_dispatch_delivery_subscription_same_tenant
    foreign key (tenant_id, subscription_id) references dispatch.event_subscription(tenant_id, id) on delete cascade,
  constraint fk_dispatch_delivery_connector_same_tenant
    foreign key (tenant_id, connector_id) references dispatch.connector(tenant_id, id) on delete cascade
);

create index idx_dispatch_delivery_due
  on dispatch.dispatch_delivery(tenant_id, status, next_attempt_at)
  where status in ('PENDING','IN_FLIGHT');
create index idx_dispatch_delivery_connector on dispatch.dispatch_delivery(tenant_id, connector_id, created_at desc);

-- ---------------------------------------------------------------------------
-- Per-attempt trace — every attempt, successful or not
-- ---------------------------------------------------------------------------
create table dispatch.dispatch_attempt (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  delivery_id uuid not null,
  connector_id uuid not null,
  attempt_no int not null check (attempt_no >= 1),
  status text not null check (status in ('SUCCESS','RETRYABLE_FAILURE','PERMANENT_FAILURE','BLOCKED_BY_BREAKER')),
  http_status int,
  response_excerpt text,
  error text,
  duration_ms int not null default 0 check (duration_ms >= 0),
  attempted_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, delivery_id, attempt_no),
  constraint fk_dispatch_attempt_delivery_same_tenant
    foreign key (tenant_id, delivery_id) references dispatch.dispatch_delivery(tenant_id, id) on delete cascade
);

create index idx_dispatch_attempt_delivery on dispatch.dispatch_attempt(tenant_id, delivery_id, attempt_no);

-- ---------------------------------------------------------------------------
-- Dead letter (FR-INT-005) — full envelope, never a silent drop
-- ---------------------------------------------------------------------------
create table dispatch.dispatch_dead_letter (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  delivery_id uuid not null,
  connector_id uuid not null,
  subscription_id uuid not null,
  event_id uuid not null,
  event_type text not null,
  envelope jsonb not null,
  failure_reason text not null,
  attempts int not null default 0 check (attempts >= 0),
  created_at timestamptz not null default now(),
  replayed_at timestamptz,
  replayed_by uuid,
  replay_count int not null default 0 check (replay_count >= 0),
  unique (tenant_id, id),
  unique (tenant_id, delivery_id),
  constraint fk_dispatch_dead_letter_delivery_same_tenant
    foreign key (tenant_id, delivery_id) references dispatch.dispatch_delivery(tenant_id, id) on delete cascade
);

create index idx_dispatch_dead_letter_open
  on dispatch.dispatch_dead_letter(tenant_id, connector_id, created_at desc)
  where replayed_at is null;

-- ---------------------------------------------------------------------------
-- Per-connector breaker and health (FR-INT-009)
-- ---------------------------------------------------------------------------
-- State is per connector, so an open breaker on one connector cannot stop any
-- other connector's deliveries — the "permanently failing consumer must not
-- block the stream" rule from ADR-003.
create table dispatch.connector_health (
  connector_id uuid primary key,
  tenant_id uuid not null references platform.tenant(id),
  breaker_state text not null default 'CLOSED' check (breaker_state in ('CLOSED','OPEN','HALF_OPEN')),
  consecutive_failures int not null default 0 check (consecutive_failures >= 0),
  total_success bigint not null default 0,
  total_failure bigint not null default 0,
  last_success_at timestamptz,
  last_failure_at timestamptz,
  last_error text,
  opened_at timestamptz,
  half_open_at timestamptz,
  dlq_alert_at timestamptz,
  updated_at timestamptz not null default now(),
  unique (tenant_id, connector_id),
  constraint fk_connector_health_connector_same_tenant
    foreign key (tenant_id, connector_id) references dispatch.connector(tenant_id, id) on delete cascade
);

-- ---------------------------------------------------------------------------
-- Outbox ingest cursor
-- ---------------------------------------------------------------------------
-- The dispatch worker tails integration.outbox_event (the source of truth per
-- ADR-003 rule 7) rather than the broker. The cursor is re-read with a small
-- overlap window on every tick so a transaction that commits out of timestamp
-- order is still picked up; duplicates that the overlap produces are absorbed
-- by the unique (subscription_id, event_id) constraint above.
create table dispatch.ingest_cursor (
  tenant_id uuid primary key references platform.tenant(id),
  last_event_at timestamptz not null,
  events_seen bigint not null default 0,
  updated_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Row-level security — every tenant-scoped table (ADR-001)
-- ---------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array[
    'named_credential','connector','event_subscription','dispatch_delivery',
    'dispatch_attempt','dispatch_dead_letter','connector_health','ingest_cursor'
  ]
  loop
    execute format('alter table dispatch.%I enable row level security', t);
    execute format('alter table dispatch.%I force row level security', t);
    execute format(
      'create policy tenant_isolation on dispatch.%I '
      || 'using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      || 'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
    execute format('grant select, insert, update, delete on dispatch.%I to axiom_app', t);
  end loop;
end
$$;

comment on table dispatch.connector is 'FR-INT-008 connector registry. Secrets are referenced by credential_ref, never stored here.';
comment on table dispatch.named_credential is 'FR-INT-007 encrypted named credentials. secret_cipher is never returned by any read API.';
comment on table dispatch.dispatch_delivery is 'FR-INT-005 delivery queue. unique(tenant_id, subscription_id, event_id) is the idempotency guard.';
comment on table dispatch.dispatch_dead_letter is 'FR-INT-005 dead-letter store with replay. Holds the full envelope so replay needs nothing else.';
comment on table dispatch.connector_health is 'FR-INT-009 per-connector health and circuit-breaker state.';

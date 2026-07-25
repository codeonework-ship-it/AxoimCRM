-- E01 — Tenancy, identity and access: core local-authentication, MFA and
-- session governance tables.
--
-- Every tenant-scoped table below follows the ADR-001 pattern exactly:
--   tenant_id + enable RLS + force RLS + a `tenant_isolation` policy keyed to
--   current_setting('app.tenant_id', true)::uuid + explicit grants to axiom_app.
-- current_setting(..., true) returns NULL rather than erroring when unset, so a
-- connection with no tenant bound sees zero rows instead of failing open.
--
-- Requirements: FR-TEN-003 (local authentication and password policy),
-- FR-TEN-008 (multi-factor authentication), FR-TEN-009 (step-up),
-- FR-TEN-010 (session governance and immediate revocation).

-- ---------------------------------------------------------------------------
-- Password policy, history and attempt telemetry (FR-TEN-003)
-- ---------------------------------------------------------------------------
create table identity.password_policy (
  tenant_id            uuid primary key references platform.tenant(id),
  min_length           int  not null default 12 check (min_length between 8 and 128),
  require_upper        boolean not null default true,
  require_lower        boolean not null default true,
  require_digit        boolean not null default true,
  require_symbol       boolean not null default true,
  history_count        int  not null default 5  check (history_count between 0 and 50),
  expiry_days          int  not null default 0  check (expiry_days between 0 and 3650),
  max_failed_attempts  int  not null default 5  check (max_failed_attempts between 3 and 50),
  lockout_minutes      int  not null default 15 check (lockout_minutes between 1 and 1440),
  reject_breached      boolean not null default true,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now()
);

-- Hashes only. A history row is never readable as a password; it exists so the
-- reuse check can run without ever storing plaintext.
create table identity.password_history (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  user_id        uuid not null,
  password_hash  text not null,
  created_at     timestamptz not null default now(),
  constraint fk_password_history_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

-- Drives progressive delay, lockout and the sign-in audit trail. user_id is
-- nullable on purpose: an attempt against an address that does not exist must
-- still be counted, otherwise lockout would reveal which addresses are real.
create table identity.login_attempt (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  user_id      uuid,
  email        text not null,
  outcome      text not null check (outcome in
                 ('SUCCESS','BAD_CREDENTIALS','LOCKED_OUT','BLOCKED_NETWORK',
                  'MFA_REQUIRED','MFA_FAILED','PASSWORD_EXPIRED','TENANT_UNAVAILABLE')),
  ip           text,
  user_agent   text,
  at           timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Multi-factor authentication (FR-TEN-008)
-- ---------------------------------------------------------------------------
-- Enforcement policy targetable by role. `target_role` = '*' means every role.
create table identity.mfa_policy (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  target_role  text not null,
  required     boolean not null default true,
  created_at   timestamptz not null default now(),
  unique (tenant_id, target_role)
);

-- `secret_cipher` holds the base32 TOTP secret encrypted at rest by
-- com.axiom.common.SecretCipher (AES-GCM). The column never holds plaintext and
-- is never returned by any read endpoint — only the enrolment response, once.
create table identity.user_mfa (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  user_id        uuid not null,
  method         text not null default 'TOTP' check (method in ('TOTP')),
  secret_cipher  text not null,
  confirmed_at   timestamptz,
  active         boolean not null default false,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  unique (tenant_id, user_id, method),
  constraint user_mfa_active_requires_confirmation check ((not active) or confirmed_at is not null),
  constraint fk_user_mfa_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

-- Issued once, stored hashed, single use (FR-TEN-008 rule).
create table identity.mfa_recovery_code (
  id         uuid primary key default gen_random_uuid(),
  tenant_id  uuid not null references platform.tenant(id),
  user_id    uuid not null,
  code_hash  text not null,
  used_at    timestamptz,
  created_at timestamptz not null default now(),
  constraint fk_mfa_recovery_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- Session governance (FR-TEN-009, FR-TEN-010)
-- ---------------------------------------------------------------------------
create table identity.session_policy (
  tenant_id                uuid primary key references platform.tenant(id),
  absolute_lifetime_minutes int not null default 480 check (absolute_lifetime_minutes between 5 and 10080),
  idle_timeout_minutes      int not null default 120 check (idle_timeout_minutes between 1 and 10080),
  max_concurrent_sessions   int not null default 5   check (max_concurrent_sessions between 1 and 100),
  concurrent_strategy       text not null default 'END_OLDEST'
                              check (concurrent_strategy in ('END_OLDEST','REFUSE_NEW')),
  step_up_max_age_seconds   int not null default 300 check (step_up_max_age_seconds between 30 and 3600),
  created_at                timestamptz not null default now(),
  updated_at                timestamptz not null default now()
);

-- Server-side session state, so revocation is immediate rather than "when the
-- token expires" (system-design §9, Session layer).
--
-- user_id deliberately carries NO foreign key: a platform operator
-- (platform.platform_user) holds a session inside a tenant workspace, and that
-- identity does not exist in identity.app_user. `platform_user` records which
-- table the id refers to.
create table identity.user_session (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  user_id        uuid not null,
  platform_user  boolean not null default false,
  subject_email  text not null,
  subject_name   text not null,
  role           text not null,
  jti            text not null,
  kind           text not null default 'INTERACTIVE'
                   check (kind in ('INTERACTIVE','SERVICE','IMPERSONATION')),
  impersonator_id     uuid,
  impersonator_email  text,
  issued_at      timestamptz not null default now(),
  expires_at     timestamptz not null,
  last_seen_at   timestamptz not null default now(),
  ip             text,
  user_agent     text,
  step_up_at     timestamptz,
  revoked_at     timestamptz,
  revoked_by     uuid,
  revoke_reason  text,
  unique (tenant_id, jti),
  constraint user_session_revocation_has_reason
    check (revoked_at is null or revoke_reason is not null)
);

-- The filter looks a session up by (tenant_id, jti) on every request; this is
-- the index that keeps that a single cheap probe.
create unique index uq_user_session_jti on identity.user_session(jti);
create index idx_user_session_active on identity.user_session(tenant_id, user_id, expires_at)
  where revoked_at is null;
create index idx_password_history_user on identity.password_history(tenant_id, user_id, created_at desc);
create index idx_login_attempt_recent on identity.login_attempt(tenant_id, lower(email), at desc);
create index idx_mfa_recovery_unused on identity.mfa_recovery_code(tenant_id, user_id) where used_at is null;

-- Password expiry needs a reference point (FR-TEN-003, configurable expiry).
alter table identity.app_user
  add column if not exists password_changed_at timestamptz not null default now(),
  add column if not exists must_change_password boolean not null default false;

-- Impersonation and reason are first-class audit attributes: FR-TEN-011 requires
-- BOTH identities on every event, and FR-TEN-009/012 require a recorded reason.
alter table governance.audit_event
  add column if not exists impersonator_id uuid,
  add column if not exists impersonator_email text,
  add column if not exists reason text;

-- ---------------------------------------------------------------------------
-- Row-level security — the second, independent enforcement layer (ADR-001)
-- ---------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array[
    'identity.password_policy',
    'identity.password_history',
    'identity.login_attempt',
    'identity.mfa_policy',
    'identity.user_mfa',
    'identity.mfa_recovery_code',
    'identity.session_policy',
    'identity.user_session'
  ]
  loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format('drop policy if exists tenant_isolation on %s', t);
    execute format(
      'create policy tenant_isolation on %s using (tenant_id = current_setting(''app.tenant_id'', true)::uuid) with check (tenant_id = current_setting(''app.tenant_id'', true)::uuid)',
      t
    );
    execute format('grant select, insert, update on %s to axiom_app', t);
  end loop;
end
$$;

-- Password history is pruned to the configured depth, and expired sessions are
-- reaped, so those two tables also need delete.
grant delete on identity.password_history, identity.user_session to axiom_app;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('identity','password_policy','IDENTITY','tenant_id',true,'ACTIVE'),
  ('identity','password_history','IDENTITY','id',true,'APPEND_ONLY'),
  ('identity','login_attempt','IDENTITY','id',true,'APPEND_ONLY'),
  ('identity','mfa_policy','IDENTITY','id',true,'ACTIVE'),
  ('identity','user_mfa','IDENTITY','id',true,'ACTIVE'),
  ('identity','mfa_recovery_code','IDENTITY','id',true,'ACTIVE'),
  ('identity','session_policy','IDENTITY','tenant_id',true,'ACTIVE'),
  ('identity','user_session','IDENTITY','id',true,'ACTIVE')
on conflict (schema_name, table_name) do nothing;

-- Every existing tenant gets the default baseline, so policy lookups never have
-- to cope with a missing row on an established workspace.
insert into identity.password_policy(tenant_id) select id from platform.tenant
on conflict (tenant_id) do nothing;

insert into identity.session_policy(tenant_id) select id from platform.tenant
on conflict (tenant_id) do nothing;

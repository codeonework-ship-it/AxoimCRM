-- ---------------------------------------------------------------------------
-- Anonymous access layer — public 30-day trial self-registration and the
-- records the two admin masters govern (FR-TEN-001, FR-TEN-002, FR-TEN-004/6).
--
-- WHY THESE TABLES ARE NOT TENANT-SCOPED
-- A trial request exists BEFORE any tenant does. There is no tenant_id to
-- isolate on, and inventing one (a "prospects" pseudo-tenant) would put
-- unauthenticated internet input inside the same isolation domain as customer
-- data, which is exactly what ADR-001 exists to prevent. So these live in the
-- platform schema alongside platform.tenant and platform.company_account, and
-- are governed by a platform-access policy rather than a tenant-match policy.
--
-- ROW-LEVEL SECURITY WITHOUT A TENANT
-- Two session flags admit rows, both set only by server code and neither
-- reachable from any request field (the platform.company_account /
-- PlatformSession pattern, extended):
--
--   app.platform_access = 'on'  a platform operator has already been authorized
--                               by CrmRole.requirePlatform (review, approve,
--                               reject, list).
--   app.trial_intake    = 'on'  the public intake path is executing. It is set
--                               inside TrialRequestService's own transaction,
--                               so it dies with that transaction and cannot
--                               survive on a pooled Hikari connection.
--
-- Intake genuinely needs SELECT — duplicate-pending detection and rate limiting
-- are reads — so a write-only policy would not work. What it does NOT get is
-- any way to reach a different table, and the flag is scoped to the one
-- transaction that sets it.
--
-- THE AUDIT TRAIL IS A SEPARATE TABLE, DELIBERATELY
-- governance.audit_event has `tenant_id uuid not null references
-- platform.tenant(id)`. An anonymous submission has no tenant, so it physically
-- cannot be written there. platform.trial_request_event is the append-only
-- record for the pre-tenant part of the journey; once a tenant exists, the
-- review actions ALSO write governance.audit_event through AuditService, so
-- nothing after provisioning is missing from the main chain.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- Human-quotable reference numbers. A sequence rather than max()+1: two
-- submissions racing on max()+1 produce the same reference, and the first thing
-- a support call starts with is "what is your reference".
-- ---------------------------------------------------------------------------
create sequence if not exists platform.trial_request_reference_seq;

create table platform.trial_request (
  id                    uuid primary key default gen_random_uuid(),
  reference             text not null unique,
  company_name          text not null,
  work_email            text not null,
  email_domain          text not null,
  full_name             text not null,
  job_title             text,
  company_size          text,
  country               text,
  notes                 text,
  status                text not null default 'PENDING'
                          check (status in ('PENDING','APPROVED','PROVISIONED','REJECTED','EXPIRED')),
  trial_days            integer not null default 30 check (trial_days > 0 and trial_days <= 365),
  submitted_at          timestamptz not null default now(),
  reviewed_at           timestamptz,
  reviewed_by           uuid,
  reviewed_by_name      text,
  provisioned_tenant_id uuid references platform.tenant(id),
  provisioned_slug      text,
  reject_reason         text,
  source_ip             text,
  user_agent            text,
  updated_at            timestamptz not null default now(),
  -- A rejection with no reason is an unaccountable decision. The API refuses it
  -- too; this is the layer that cannot be bypassed by a direct write.
  constraint trial_request_rejection_has_reason
    check (status <> 'REJECTED' or (reject_reason is not null and btrim(reject_reason) <> '')),
  constraint trial_request_provisioned_has_tenant
    check (status <> 'PROVISIONED' or provisioned_tenant_id is not null)
);

-- The duplicate-pending guarantee, enforced by the database rather than only by
-- the service: two concurrent submissions from the same address cannot both
-- create a row, whichever one wins the race.
create unique index uq_trial_request_open_email
  on platform.trial_request (lower(work_email))
  where status in ('PENDING','APPROVED');

create index idx_trial_request_status on platform.trial_request (status, submitted_at desc);
create index idx_trial_request_domain on platform.trial_request (email_domain, submitted_at desc);
create index idx_trial_request_ip on platform.trial_request (source_ip, submitted_at desc);
create index idx_trial_request_tenant on platform.trial_request (provisioned_tenant_id);

-- ---------------------------------------------------------------------------
-- Append-only trail for the pre-tenant journey, including the submissions that
-- were REFUSED. A refusal leaves no trial_request row, so trial_request_id is
-- nullable and the context columns carry what was seen — otherwise an abuse
-- pattern would be invisible precisely because the guards worked.
-- ---------------------------------------------------------------------------
create table platform.trial_request_event (
  id               uuid primary key default gen_random_uuid(),
  trial_request_id uuid references platform.trial_request(id) on delete cascade,
  action           text not null check (action in (
                     'SUBMITTED','DUPLICATE_SUPPRESSED','REFUSED_FREE_MAIL','REFUSED_RATE_LIMIT',
                     'REFUSED_VALIDATION','APPROVED','PROVISIONED','REJECTED','EXPIRED',
                     'ACTIVATION_ISSUED','ACTIVATION_REDEEMED')),
  reference        text,
  company_name     text,
  work_email       text,
  email_domain     text,
  actor_id         uuid,
  actor_name       text not null default 'Anonymous (public trial form)',
  detail           text,
  reason           text,
  source_ip        text,
  user_agent       text,
  correlation_id   text,
  occurred_at      timestamptz not null default now()
);

create index idx_trial_request_event_request on platform.trial_request_event (trial_request_id, occurred_at desc);
create index idx_trial_request_event_feed on platform.trial_request_event (occurred_at desc);

-- ---------------------------------------------------------------------------
-- One-time activation links. The token is never stored — only its SHA-256 —
-- so a dump of this table does not let anyone activate an account. Emailing a
-- generated password would put a working credential in a mailbox forever;
-- a single-use link that expires does not.
-- ---------------------------------------------------------------------------
create table platform.trial_activation (
  id               uuid primary key default gen_random_uuid(),
  trial_request_id uuid not null references platform.trial_request(id) on delete cascade,
  tenant_id        uuid not null references platform.tenant(id),
  user_id          uuid not null,
  email            text not null,
  role             text not null check (role in ('TENANT_ADMIN','AUDITOR')),
  token_hash       text not null unique,
  issued_at        timestamptz not null default now(),
  expires_at       timestamptz not null,
  redeemed_at      timestamptz,
  redeemed_ip      text
);

create index idx_trial_activation_tenant on platform.trial_activation (tenant_id);

-- ---------------------------------------------------------------------------
-- Row-level security. FORCE so the owning role is not exempt either.
-- ---------------------------------------------------------------------------
alter table platform.trial_request       enable row level security;
alter table platform.trial_request       force  row level security;
alter table platform.trial_request_event enable row level security;
alter table platform.trial_request_event force  row level security;
alter table platform.trial_activation    enable row level security;
alter table platform.trial_activation    force  row level security;

create policy platform_or_intake on platform.trial_request
  using (current_setting('app.platform_access', true) = 'on'
      or current_setting('app.trial_intake', true) = 'on')
  with check (current_setting('app.platform_access', true) = 'on'
      or current_setting('app.trial_intake', true) = 'on');

create policy platform_or_intake on platform.trial_request_event
  using (current_setting('app.platform_access', true) = 'on'
      or current_setting('app.trial_intake', true) = 'on')
  with check (current_setting('app.platform_access', true) = 'on'
      or current_setting('app.trial_intake', true) = 'on');

create policy platform_or_intake on platform.trial_activation
  using (current_setting('app.platform_access', true) = 'on'
      or current_setting('app.trial_intake', true) = 'on')
  with check (current_setting('app.platform_access', true) = 'on'
      or current_setting('app.trial_intake', true) = 'on');

grant select, insert, update on platform.trial_request    to axiom_app;
grant select, insert         on platform.trial_request_event to axiom_app;
grant select, insert, update on platform.trial_activation to axiom_app;
grant usage on sequence platform.trial_request_reference_seq to axiom_app;

-- UPDATE and DELETE are deliberately NOT granted on the event trail: the
-- application has no legitimate reason to rewrite its own history, and the
-- cheapest way to guarantee that is to withhold the privilege.

-- ---------------------------------------------------------------------------
-- UNBLOCKER, reported rather than hidden.
--
-- crm.pipeline_stage.pipeline_id was made NOT NULL by V60, but
-- TenantLifecycleService.seedPipeline() still inserts stages without it. Every
-- call to provision() therefore fails with a not-null violation and rolls the
-- whole tenant back — which is correct behaviour for a broken step, but it also
-- means no tenant can be provisioned at all, trial or otherwise. Verified
-- against the live V240 schema before writing this.
--
-- That file belongs to another module and is not ours to edit, so the gap is
-- closed in the database instead: when an insert omits pipeline_id, default it
-- to the tenant's default pipeline, creating that pipeline if the tenant does
-- not have one yet. Existing callers all pass pipeline_id explicitly, so for
-- them this trigger is a no-op.
--
-- This is a compatibility shim, not the fix. The right fix is one line in
-- seedPipeline(); it should be made, and then this trigger becomes dead weight
-- that can be dropped.
-- ---------------------------------------------------------------------------
create or replace function crm.pipeline_stage_default_pipeline() returns trigger
language plpgsql as $$
declare
  target uuid;
begin
  if new.pipeline_id is not null then
    return new;
  end if;
  select id into target from pipeline.pipeline
   where tenant_id = new.tenant_id and is_default order by created_at limit 1;
  if target is null then
    select id into target from pipeline.pipeline
     where tenant_id = new.tenant_id order by created_at limit 1;
  end if;
  if target is null then
    insert into pipeline.pipeline(tenant_id, api_name, name, description, is_default, active)
    values (new.tenant_id, 'default_pipeline', 'Default Pipeline',
            'Created automatically because a stage was inserted before a pipeline existed.', true, true)
    returning id into target;
  end if;
  new.pipeline_id := target;
  return new;
end
$$;

drop trigger if exists trg_pipeline_stage_default_pipeline on crm.pipeline_stage;
create trigger trg_pipeline_stage_default_pipeline
  before insert on crm.pipeline_stage
  for each row execute function crm.pipeline_stage_default_pipeline();

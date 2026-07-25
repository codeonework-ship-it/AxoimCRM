-- E01 — Tenancy, identity and access: federation configuration, machine
-- credentials, network restriction, emergency access, impersonation, tenant
-- lifecycle and login branding.
--
-- SCOPE NOTE, stated honestly: the tables and endpoints built on them are our
-- side of the federation boundary — configuration storage, validation, mapping,
-- PKCE/state generation, SP metadata, audit. Consuming a live SAML assertion or
-- completing a live OIDC token exchange requires a purchased identity provider
-- and is NOT claimed as delivered. See docs/epic-status.md.
--
-- Requirements: FR-TEN-004/005/006 (SAML, OIDC, multiple IdPs),
-- FR-TEN-007 (SCIM), FR-TEN-011 (impersonation), FR-TEN-012 (break-glass),
-- FR-TEN-013 (service credentials), FR-TEN-014 (network restriction),
-- FR-TEN-015 (login branding), FR-TEN-001/002 (provisioning and lifecycle).

-- ---------------------------------------------------------------------------
-- Identity provider configuration (FR-TEN-004, 005, 006)
-- ---------------------------------------------------------------------------
-- `email_domain` is how a tenant with more than one concurrent provider routes a
-- sign-in (FR-TEN-006). It is nullable: one provider may be the catch-all.
--
-- client_secret is stored encrypted at rest by com.axiom.common.SecretCipher and
-- is NEVER returned by a read endpoint — IdpConfigService returns a masked
-- marker instead.
create table identity.idp_config (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  protocol       text not null check (protocol in ('SAML2','OIDC')),
  display_name   text not null,
  enabled        boolean not null default false,
  email_domain   text,
  entity_id      text,
  sso_url        text,
  certificate    text,
  client_id      text,
  client_secret_cipher text,
  discovery_url  text,
  attribute_map  jsonb not null default '{}'::jsonb,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  unique (tenant_id, id),
  constraint idp_email_domain_format
    check (email_domain is null or email_domain ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$')
);

create unique index uq_idp_display_name on identity.idp_config(tenant_id, lower(display_name));

-- One enabled provider per routing domain, so routing is deterministic.
create unique index uq_idp_enabled_domain on identity.idp_config(tenant_id, email_domain)
  where enabled = true and email_domain is not null;
create unique index uq_idp_enabled_catchall on identity.idp_config(tenant_id)
  where enabled = true and email_domain is null;

-- Authorization-code + PKCE state we generate and must remember to validate the
-- callback. Our side of the OIDC boundary; genuinely functional.
create table identity.sso_auth_request (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  idp_config_id  uuid not null,
  state          text not null,
  code_verifier  text not null,
  redirect_uri   text not null,
  created_at     timestamptz not null default now(),
  expires_at     timestamptz not null,
  consumed_at    timestamptz,
  unique (tenant_id, state),
  constraint fk_sso_request_idp_same_tenant
    foreign key (tenant_id, idp_config_id) references identity.idp_config(tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- SCIM tokens (FR-TEN-007) and service credentials (FR-TEN-013)
-- ---------------------------------------------------------------------------
-- Only the hash is stored; the plaintext is shown exactly once at issue time.
-- The presented token embeds the workspace slug so the tenant is derived from
-- the credential itself, never from a client-supplied header (ADR-001 rule 4).
create table identity.scim_token (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  name          text not null,
  token_hash    text not null,
  scopes        text[] not null default array['users:read','users:write'],
  expires_at    timestamptz,
  last_used_at  timestamptz,
  revoked_at    timestamptz,
  created_by    uuid,
  created_at    timestamptz not null default now(),
  unique (tenant_id, name)
);

create table identity.service_credential (
  id            uuid primary key default gen_random_uuid(),
  tenant_id     uuid not null references platform.tenant(id),
  name          text not null,
  client_id     text not null unique,
  secret_hash   text not null,
  scopes        text[] not null default array['api:read'],
  expires_at    timestamptz,
  last_used_at  timestamptz,
  rotated_at    timestamptz,
  revoked_at    timestamptz,
  created_by    uuid,
  created_at    timestamptz not null default now(),
  unique (tenant_id, name)
);

-- ---------------------------------------------------------------------------
-- Network restriction (FR-TEN-014)
-- ---------------------------------------------------------------------------
-- `cidr` uses the native type so PostgreSQL validates the notation on write —
-- an unparseable rule is rejected at the boundary rather than silently failing
-- open at sign-in time.
create table identity.network_rule (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    uuid not null references platform.tenant(id),
  cidr         cidr not null,
  description  text not null,
  active       boolean not null default true,
  created_by   uuid,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  unique (tenant_id, cidr)
);

-- ---------------------------------------------------------------------------
-- Break-glass access (FR-TEN-012) and impersonation (FR-TEN-011)
-- ---------------------------------------------------------------------------
create table identity.break_glass_grant (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  actor_id        uuid not null,
  actor_email     text not null,
  case_reference  text not null check (length(btrim(case_reference)) > 0),
  justification   text not null check (length(btrim(justification)) >= 20),
  granted_at      timestamptz not null default now(),
  expires_at      timestamptz not null,
  used_at         timestamptz,
  revoked_at      timestamptz,
  revoke_reason   text,
  constraint break_glass_window check (expires_at > granted_at)
);

create table identity.impersonation_session (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references platform.tenant(id),
  impersonator_id       uuid not null,
  impersonator_email    text not null,
  impersonated_user_id  uuid not null,
  impersonated_email    text not null,
  case_reference        text not null check (length(btrim(case_reference)) > 0),
  reason                text not null check (length(btrim(reason)) >= 10),
  started_at            timestamptz not null default now(),
  ended_at              timestamptz,
  constraint fk_impersonation_target_same_tenant
    foreign key (tenant_id, impersonated_user_id) references identity.app_user(tenant_id, id)
);

-- FR-TEN-011: impersonation is subject to a tenant-level consent policy. Default
-- false — a tenant must opt in before support can act as one of its users.
alter table platform.tenant
  add column if not exists impersonation_consent boolean not null default false;

-- ---------------------------------------------------------------------------
-- Login branding (FR-TEN-015)
-- ---------------------------------------------------------------------------
create table platform.tenant_branding (
  tenant_id         uuid primary key references platform.tenant(id),
  logo_url          text,
  primary_colour    text check (primary_colour is null or primary_colour ~ '^#[0-9a-fA-F]{6}$'),
  support_contact   text,
  sign_in_message   text,
  updated_at        timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Tenant lifecycle (FR-TEN-001, FR-TEN-002)
-- ---------------------------------------------------------------------------
alter table platform.tenant
  add column if not exists terminating_at timestamptz,
  add column if not exists terminated_at timestamptz,
  add column if not exists retention_until date;

-- The status check already covers the five states; re-assert it so the contract
-- is explicit in this migration and survives an earlier partial schema.
alter table platform.tenant drop constraint if exists tenant_status_check;
alter table platform.tenant add constraint tenant_status_check
  check (status in ('provisioning','active','suspended','terminating','terminated'));

-- Idempotency ledger for provisioning: the same request key must never create a
-- second tenant (FR-TEN-001 rule).
create table platform.tenant_provisioning_request (
  request_key   text primary key,
  tenant_id     uuid references platform.tenant(id),
  requested_by  uuid,
  status        text not null check (status in ('COMPLETED','FAILED')),
  detail        text,
  created_at    timestamptz not null default now()
);

create index idx_scim_token_live on identity.scim_token(tenant_id) where revoked_at is null;
create index idx_service_credential_live on identity.service_credential(tenant_id) where revoked_at is null;
create index idx_network_rule_active on identity.network_rule(tenant_id) where active = true;
create index idx_break_glass_live on identity.break_glass_grant(tenant_id, expires_at)
  where revoked_at is null;
create index idx_impersonation_open on identity.impersonation_session(tenant_id, started_at desc)
  where ended_at is null;
create index idx_sso_request_open on identity.sso_auth_request(tenant_id, expires_at) where consumed_at is null;

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array[
    'identity.idp_config',
    'identity.sso_auth_request',
    'identity.scim_token',
    'identity.service_credential',
    'identity.network_rule',
    'identity.break_glass_grant',
    'identity.impersonation_session',
    'platform.tenant_branding'
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

-- tenant_branding is keyed by tenant_id as its primary key, so the generated
-- policy above already reads the right column.
grant delete on identity.idp_config, identity.network_rule, identity.sso_auth_request to axiom_app;

-- Provisioning happens before any tenant context can exist for the new tenant,
-- and is always performed by an already-authorized platform operator. Same
-- design as V9: the row is admitted on a tenant match OR on the server-set
-- app.platform_access flag, which no client input can influence.
alter table platform.tenant_provisioning_request enable row level security;
alter table platform.tenant_provisioning_request force row level security;
drop policy if exists tenant_or_platform on platform.tenant_provisioning_request;
create policy tenant_or_platform on platform.tenant_provisioning_request
  using (
    tenant_id = current_setting('app.tenant_id', true)::uuid
    or current_setting('app.platform_access', true) = 'on'
  )
  with check (
    tenant_id = current_setting('app.tenant_id', true)::uuid
    or current_setting('app.platform_access', true) = 'on'
  );
grant select, insert, update on platform.tenant_provisioning_request to axiom_app;

-- A platform operator provisions and terminates tenants, so axiom_app needs to
-- write platform.tenant. It has no RLS (login must resolve a slug before any
-- tenant context exists); TenantLifecycleService gates every write behind
-- CrmRole.requirePlatform plus a fresh step-up.
grant insert, update on platform.tenant to axiom_app;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('identity','idp_config','IDENTITY','id',true,'ACTIVE'),
  ('identity','sso_auth_request','IDENTITY','id',true,'ACTIVE'),
  ('identity','scim_token','IDENTITY','id',true,'ACTIVE'),
  ('identity','service_credential','IDENTITY','id',true,'ACTIVE'),
  ('identity','network_rule','IDENTITY','id',true,'ACTIVE'),
  ('identity','break_glass_grant','IDENTITY','id',true,'APPEND_ONLY'),
  ('identity','impersonation_session','IDENTITY','id',true,'APPEND_ONLY'),
  ('platform','tenant_branding','PLATFORM','tenant_id',true,'ACTIVE'),
  ('platform','tenant_provisioning_request','PLATFORM','request_key',false,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

-- ---------------------------------------------------------------------------
-- Sessions & Security screen, so the RBAC catalogue covers the new surface
-- ---------------------------------------------------------------------------
insert into governance.screen_catalog(screen_code, module_code, route, display_name, description, sort_order) values
  ('SECURITY', 'IDENTITY', '/security', 'Sessions & Security',
   'Active sessions, revocation, multi-factor enrolment, network rules and machine credentials.', 130)
on conflict (screen_code) do nothing;

insert into governance.rbac_policy(role_code, screen_code, can_read, can_write, can_export, can_admin, scope)
values
  ('SUPER_ADMIN','SECURITY',true,true,true,true,'PLATFORM'),
  ('SUPER_AUDIT','SECURITY',true,false,true,false,'PLATFORM'),
  ('TENANT_ADMIN','SECURITY',true,true,true,true,'TENANT'),
  ('OPERATIONS','SECURITY',true,false,true,false,'TENANT'),
  ('AUDITOR','SECURITY',true,false,true,false,'TENANT')
on conflict (role_code, screen_code) do nothing;

-- ---------------------------------------------------------------------------
-- Baseline records for tenants that already exist
-- ---------------------------------------------------------------------------
insert into platform.tenant_branding(tenant_id, primary_colour, support_contact, sign_in_message)
select t.id,
       case when t.slug = 'northstar' then '#1b76dc' else '#0b5fbe' end,
       case when t.slug = 'northstar' then 'it-support@northstar.example' else 'it-support@meridianfab.com' end,
       'Authorized use only. Sign-in activity is recorded.'
from platform.tenant t
on conflict (tenant_id) do nothing;

-- Loopback and RFC1918 ranges are seeded INACTIVE. An active allowlist that did
-- not include the operator's own address would lock the workspace out on the
-- next sign-in, which is exactly the failure mode FR-TEN-004 warns about.
insert into identity.network_rule(tenant_id, cidr, description, active)
select t.id, seed.cidr::cidr, seed.description, false
from platform.tenant t
cross join (values
  ('10.0.0.0/8',     'Corporate VPN range (example — activate once verified)'),
  ('192.168.0.0/16', 'Office LAN range (example — activate once verified)')
) as seed(cidr, description)
on conflict (tenant_id, cidr) do nothing;

-- MFA is not forced on any role by default; a tenant administrator opts in.
insert into identity.mfa_policy(tenant_id, target_role, required)
select t.id, '*', false from platform.tenant t
on conflict (tenant_id, target_role) do nothing;

-- E01 live federation and SCIM 2.0 certification controls.

alter table identity.idp_config
  add column if not exists jit_enabled boolean not null default false,
  add column if not exists default_role text not null default 'SALES',
  add column if not exists client_auth_method text not null default 'CLIENT_SECRET_BASIC',
  add column if not exists last_live_test_at timestamptz,
  add column if not exists last_live_test_status text,
  add column if not exists last_live_test_detail text,
  add constraint idp_default_role_tenant_only check
    (default_role in ('TENANT_ADMIN','SALES_MANAGER','SALES','MARKETING','SERVICE','OPERATIONS','FINANCE','DATA_STEWARD','AUDITOR')),
  add constraint idp_client_auth_method check
    (client_auth_method in ('CLIENT_SECRET_BASIC','CLIENT_SECRET_POST')),
  add constraint idp_live_test_status check
    (last_live_test_status is null or last_live_test_status in ('PASSED','FAILED'));

alter table identity.sso_auth_request
  add column if not exists protocol text not null default 'OIDC',
  add column if not exists request_id text,
  add column if not exists nonce text,
  add column if not exists return_uri text,
  add constraint sso_request_protocol check (protocol in ('OIDC','SAML2'));

create unique index if not exists uq_sso_request_request_id
  on identity.sso_auth_request(tenant_id, request_id) where request_id is not null;

create table identity.federated_identity (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references platform.tenant(id),
  idp_config_id       uuid not null,
  external_subject    text not null,
  user_id             uuid not null,
  email_at_link       text not null,
  first_authenticated_at timestamptz not null default now(),
  last_authenticated_at  timestamptz not null default now(),
  last_claims         jsonb not null default '{}'::jsonb,
  unique (tenant_id, id),
  unique (tenant_id, idp_config_id, external_subject),
  constraint fk_federated_identity_idp_same_tenant
    foreign key (tenant_id, idp_config_id) references identity.idp_config(tenant_id, id),
  constraint fk_federated_identity_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

create table identity.sso_login_ticket (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  idp_config_id  uuid not null,
  user_id        uuid not null,
  token_hash     text not null unique,
  return_uri     text not null,
  issued_at      timestamptz not null default now(),
  expires_at     timestamptz not null,
  consumed_at    timestamptz,
  unique (tenant_id, id),
  constraint fk_sso_ticket_idp_same_tenant
    foreign key (tenant_id, idp_config_id) references identity.idp_config(tenant_id, id),
  constraint fk_sso_ticket_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);
create index idx_sso_login_ticket_live on identity.sso_login_ticket(token_hash, expires_at)
  where consumed_at is null;

create table identity.scim_user_link (
  tenant_id     uuid not null references platform.tenant(id),
  user_id       uuid not null,
  external_id   text,
  version       bigint not null default 1,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  primary key (tenant_id, user_id),
  unique (tenant_id, external_id),
  constraint fk_scim_user_link_user_same_tenant
    foreign key (tenant_id, user_id) references identity.app_user(tenant_id, id)
);

create table identity.scim_group_link (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  external_id    text,
  display_name   text not null,
  user_group_id  uuid not null,
  version        bigint not null default 1,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, external_id),
  unique (tenant_id, user_group_id),
  constraint fk_scim_group_link_group_same_tenant
    foreign key (tenant_id, user_group_id) references security.user_group(tenant_id, id)
);

create table identity.idp_certification_run (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references platform.tenant(id),
  idp_config_id         uuid,
  provider              text not null,
  external_tenant_ref   text,
  connector_job_ref     text,
  status                text not null default 'IN_PROGRESS'
    check (status in ('IN_PROGRESS','PASSED','FAILED')),
  evidence              jsonb not null default '{}'::jsonb,
  requested_by          uuid not null,
  started_at            timestamptz not null default now(),
  completed_at          timestamptz,
  unique (tenant_id, id),
  constraint fk_idp_certification_config_same_tenant
    foreign key (tenant_id, idp_config_id) references identity.idp_config(tenant_id, id)
);

do $$
declare t text;
begin
  foreach t in array array[
    'identity.federated_identity','identity.sso_login_ticket','identity.scim_user_link',
    'identity.scim_group_link','identity.idp_certification_run'
  ] loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format('create policy tenant_isolation on %s using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
  end loop;
end $$;

grant select, insert, update on identity.federated_identity, identity.sso_login_ticket,
  identity.scim_user_link, identity.scim_group_link, identity.idp_certification_run to axiom_app;
grant delete on identity.sso_login_ticket, identity.scim_group_link to axiom_app;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle)
values
  ('identity','federated_identity','IDENTITY','id',true,'APPEND_ONLY'),
  ('identity','sso_login_ticket','IDENTITY','id',true,'ACTIVE'),
  ('identity','scim_user_link','IDENTITY','user_id',true,'ACTIVE'),
  ('identity','scim_group_link','IDENTITY','id',true,'ACTIVE'),
  ('identity','idp_certification_run','IDENTITY','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

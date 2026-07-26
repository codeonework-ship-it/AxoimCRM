-- E01-E05 closure controls.
--
-- E01: certificate warnings must be durable and idempotent.  A scheduler can
-- run many times without sending the same warning again for the same certificate.
create table identity.idp_certificate_alert (
  id                    uuid primary key default gen_random_uuid(),
  tenant_id             uuid not null references platform.tenant(id),
  idp_config_id         uuid not null,
  certificate_not_after timestamptz not null,
  severity              text not null check (severity in ('WARNING','EXPIRED')),
  notified_at           timestamptz not null default now(),
  recipient_count       int not null default 0 check (recipient_count >= 0),
  unique (tenant_id, idp_config_id, certificate_not_after, severity),
  unique (tenant_id, id),
  constraint fk_idp_certificate_alert_config_same_tenant
    foreign key (tenant_id, idp_config_id) references identity.idp_config(tenant_id, id)
);

create index idx_idp_certificate_alert_recent
  on identity.idp_certificate_alert(tenant_id, notified_at desc);

alter table identity.idp_certificate_alert enable row level security;
alter table identity.idp_certificate_alert force row level security;
create policy tenant_isolation on identity.idp_certificate_alert
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
grant select, insert, update on identity.idp_certificate_alert to axiom_app;

insert into governance.module_table_catalog
  (schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle)
values ('identity','idp_certificate_alert','IDENTITY','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

-- E02: one immutable snapshot item for one live grant in a campaign.  The
-- subject is part of the key because role assignments use the role id as the
-- grant reference and many users can hold the same role.
create unique index if not exists uq_access_review_item_grant
  on security.access_review_item
    (tenant_id, campaign_id, grant_type, grant_ref, subject_user_id);

create index if not exists idx_access_review_open_deadline
  on security.access_review_campaign(tenant_id, deadline_at)
  where status = 'OPEN';

-- E03: effective windows cannot be inverted.  Historical values remain in the
-- table; this constraint only prevents an impossible window from being saved.
alter table reference.value_set_entry
  drop constraint if exists value_set_entry_effective_window;
alter table reference.value_set_entry
  add constraint value_set_entry_effective_window
  check (effective_to is null or effective_from is null or effective_to >= effective_from);

-- The version history uses tenant-qualified foreign keys so a future schema change
-- can never associate an entry snapshot with another tenant. The original table
-- predates that convention and only exposed its globally unique id as a candidate
-- key, so publish the equivalent tenant-qualified key before creating the FK.
alter table reference.value_set_entry
  add constraint value_set_entry_tenant_id_id_unique unique (tenant_id, id);

create table reference.value_set_entry_version (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  value_set_id   uuid not null,
  entry_id       uuid not null,
  code           text not null,
  label          text not null,
  active         boolean not null,
  effective_from date,
  effective_to   date,
  recorded_at    timestamptz not null default now(),
  recorded_by    uuid,
  unique (tenant_id, id),
  constraint fk_value_set_entry_version_set_same_tenant
    foreign key (tenant_id, value_set_id) references reference.value_set(tenant_id, id),
  constraint fk_value_set_entry_version_entry_same_tenant
    foreign key (tenant_id, entry_id) references reference.value_set_entry(tenant_id, id),
  constraint value_set_entry_version_window
    check (effective_to is null or effective_from is null or effective_to >= effective_from)
);

create index idx_value_set_entry_version_resolve
  on reference.value_set_entry_version(tenant_id, value_set_id, code, effective_from, effective_to, recorded_at desc);

alter table reference.value_set_entry_version enable row level security;
alter table reference.value_set_entry_version force row level security;
create policy tenant_isolation on reference.value_set_entry_version
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
grant select, insert on reference.value_set_entry_version to axiom_app;

insert into reference.value_set_entry_version
  (tenant_id, value_set_id, entry_id, code, label, active, effective_from,
   effective_to, recorded_at, recorded_by)
select tenant_id, value_set_id, id, code, label, active, effective_from,
       effective_to, created_at, null::uuid
from reference.value_set_entry;

insert into governance.module_table_catalog
  (schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle)
values ('reference','value_set_entry_version','REFERENCE','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

-- E04: make health history efficient to compare and guarantee that a record has
-- at most one computed snapshot at a particular instant.
create unique index if not exists uq_account_health_snapshot_time
  on crm.account_health_snapshot(tenant_id, account_id, computed_at);

-- E05: batch counters are evidence and must add back to the submitted count.
alter table leads.ingestion_batch
  drop constraint if exists ingestion_batch_counts_balance;
alter table leads.ingestion_batch
  add constraint ingestion_batch_counts_balance
  check (submitted_count >= 0 and accepted_count >= 0 and rejected_count >= 0
         and accepted_count + rejected_count = submitted_count);

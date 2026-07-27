-- E21-E23 first-party closure: offline sync/conflicts, BFSI governed lifecycle,
-- and commodity origination/approval/handoff. External notification, screening
-- and CTRM adapters remain behind the existing integration boundary.

-- ---------------------------------------------------------------------------
-- E21 - offline data packages and deterministic conflict resolution
-- ---------------------------------------------------------------------------
alter table mobile.offline_sync_package
  add column if not exists cache_generated_at timestamptz,
  add column if not exists cache_expires_at timestamptz,
  add column if not exists base_cursor bigint not null default 0,
  add column if not exists package_checksum text,
  add column if not exists downloaded_at timestamptz,
  add column if not exists created_by uuid;

create table mobile.offline_record_snapshot (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  sync_package_id uuid not null,
  entity_type text not null check (entity_type in ('ACCOUNT','CONTACT','LEAD','OPPORTUNITY')),
  record_id uuid not null,
  record_version bigint not null check (record_version >= 0),
  payload jsonb not null,
  payload_checksum text not null check (payload_checksum ~ '^[a-f0-9]{64}$'),
  cached_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, sync_package_id, entity_type, record_id),
  constraint fk_offline_snapshot_package_same_tenant foreign key (tenant_id, sync_package_id)
    references mobile.offline_sync_package(tenant_id, id)
);

create table mobile.offline_change (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  sync_package_id uuid not null,
  device_session_id uuid not null,
  client_mutation_id text not null,
  entity_type text not null check (entity_type in ('ACCOUNT','CONTACT','LEAD','OPPORTUNITY')),
  record_id uuid not null,
  operation text not null check (operation = 'UPDATE'),
  base_version bigint not null check (base_version >= 0),
  patch jsonb not null check (jsonb_typeof(patch) = 'object'),
  status text not null check (status in ('QUEUED','APPLIED','CONFLICT','REJECTED','DISCARDED')),
  conflict_reason text,
  applied_version bigint,
  submitted_by uuid not null,
  submitted_at timestamptz not null default now(),
  applied_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, device_session_id, client_mutation_id),
  constraint fk_offline_change_package_same_tenant foreign key (tenant_id, sync_package_id)
    references mobile.offline_sync_package(tenant_id, id),
  constraint fk_offline_change_device_same_tenant foreign key (tenant_id, device_session_id)
    references mobile.device_session(tenant_id, id)
);

create table mobile.sync_conflict (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  offline_change_id uuid not null,
  entity_type text not null,
  record_id uuid not null,
  base_version bigint not null,
  server_version bigint not null,
  conflicting_fields text[] not null default '{}',
  client_patch jsonb not null,
  server_payload jsonb not null,
  status text not null check (status in ('OPEN','RESOLVED','DISCARDED')),
  resolution text check (resolution in ('SERVER_WINS','CLIENT_WINS','MERGED')),
  resolution_payload jsonb,
  resolution_reason text,
  detected_at timestamptz not null default now(),
  resolved_by uuid,
  resolved_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, offline_change_id),
  constraint fk_sync_conflict_change_same_tenant foreign key (tenant_id, offline_change_id)
    references mobile.offline_change(tenant_id, id)
);

create table mobile.sync_run (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  device_session_id uuid not null,
  sync_package_id uuid not null,
  status text not null check (status in ('RUNNING','COMPLETED','NEEDS_RESOLUTION','FAILED')),
  submitted_count integer not null default 0 check (submitted_count >= 0),
  applied_count integer not null default 0 check (applied_count >= 0),
  conflict_count integer not null default 0 check (conflict_count >= 0),
  rejected_count integer not null default 0 check (rejected_count >= 0),
  result_cursor bigint not null default 0,
  started_by uuid not null,
  started_at timestamptz not null default now(),
  completed_at timestamptz,
  unique (tenant_id, id),
  constraint fk_sync_run_device_same_tenant foreign key (tenant_id, device_session_id)
    references mobile.device_session(tenant_id, id),
  constraint fk_sync_run_package_same_tenant foreign key (tenant_id, sync_package_id)
    references mobile.offline_sync_package(tenant_id, id)
);

create index idx_offline_change_queue on mobile.offline_change(tenant_id, device_session_id, status, submitted_at);
create index idx_sync_conflict_open on mobile.sync_conflict(tenant_id, status, detected_at desc);
create index idx_sync_run_recent on mobile.sync_run(tenant_id, device_session_id, started_at desc);

-- ---------------------------------------------------------------------------
-- E22 - KYC, screening, risk, holdings, suitability and exceptions
-- ---------------------------------------------------------------------------
alter table bfsi.client_onboarding
  add column if not exists relationship_status text not null default 'PENDING'
    check (relationship_status in ('PENDING','ACTIVE','BLOCKED','REJECTED')),
  add column if not exists risk_score numeric(5,2) check (risk_score between 0 and 100),
  add column if not exists risk_factors jsonb not null default '[]'::jsonb,
  add column if not exists risk_rationale text,
  add column if not exists risk_updated_by uuid,
  add column if not exists risk_updated_at timestamptz,
  add column if not exists version bigint not null default 0;

alter table bfsi.compliance_screening
  add column if not exists source_system text not null default 'MANUAL',
  add column if not exists result_payload jsonb not null default '{}'::jsonb,
  add column if not exists disposition text check (disposition in ('FALSE_POSITIVE','CONFIRMED','ACCEPTED_RISK','NOT_APPLICABLE')),
  add column if not exists disposition_reason text,
  add column if not exists dispositioned_by uuid,
  add column if not exists dispositioned_at timestamptz;

create table bfsi.kyc_requirement (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  requirement_code text not null,
  name text not null,
  owner_role text not null,
  document_required boolean not null default true,
  expiry_warning_days integer not null default 30 check (expiry_warning_days between 1 and 365),
  active boolean not null default true,
  unique (tenant_id, id),
  unique (tenant_id, requirement_code)
);

create table bfsi.kyc_item (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  onboarding_id uuid not null,
  requirement_id uuid not null,
  status text not null check (status in ('MISSING','REQUESTED','RECEIVED','VERIFIED','REJECTED','EXPIRED')),
  owner_id uuid not null,
  evidence_reference text,
  expires_at date,
  rejection_reason text,
  verified_by uuid,
  verified_at timestamptz,
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, onboarding_id, requirement_id),
  constraint fk_kyc_item_onboarding_same_tenant foreign key (tenant_id, onboarding_id)
    references bfsi.client_onboarding(tenant_id, id),
  constraint fk_kyc_item_requirement_same_tenant foreign key (tenant_id, requirement_id)
    references bfsi.kyc_requirement(tenant_id, id),
  constraint fk_kyc_item_owner_same_tenant foreign key (tenant_id, owner_id)
    references identity.app_user(tenant_id, id)
);

create table bfsi.product_catalog (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  product_code text not null,
  product_family text not null,
  name text not null,
  minimum_suitability_level text not null check (minimum_suitability_level in ('BASIC','STANDARD','COMPLEX','PROFESSIONAL')),
  active boolean not null default true,
  unique (tenant_id, id),
  unique (tenant_id, product_code)
);

alter table bfsi.product_holding add column if not exists product_id uuid;
alter table bfsi.product_holding add constraint fk_holding_product_same_tenant
  foreign key (tenant_id, product_id) references bfsi.product_catalog(tenant_id, id);

create table bfsi.suitability_assessment (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  onboarding_id uuid not null,
  level text not null check (level in ('BASIC','STANDARD','COMPLEX','PROFESSIONAL')),
  factors jsonb not null,
  status text not null check (status in ('ACTIVE','EXPIRED','SUPERSEDED')),
  assessed_by uuid not null,
  assessed_at timestamptz not null default now(),
  expires_at timestamptz not null,
  unique (tenant_id, id),
  constraint fk_suitability_onboarding_same_tenant foreign key (tenant_id, onboarding_id)
    references bfsi.client_onboarding(tenant_id, id)
);
create unique index uq_suitability_active on bfsi.suitability_assessment(tenant_id, onboarding_id) where status='ACTIVE';

create table bfsi.product_recommendation (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  onboarding_id uuid not null,
  product_id uuid not null,
  suitability_assessment_id uuid not null,
  status text not null check (status in ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','ISSUED')),
  outside_suitability boolean not null default false,
  override_reason text,
  approval_request_id uuid references security.approval_request(id),
  created_by uuid not null,
  created_at timestamptz not null default now(),
  approved_at timestamptz,
  unique (tenant_id, id),
  constraint fk_recommendation_onboarding_same_tenant foreign key (tenant_id, onboarding_id)
    references bfsi.client_onboarding(tenant_id, id),
  constraint fk_recommendation_product_same_tenant foreign key (tenant_id, product_id)
    references bfsi.product_catalog(tenant_id, id),
  constraint fk_recommendation_assessment_same_tenant foreign key (tenant_id, suitability_assessment_id)
    references bfsi.suitability_assessment(tenant_id, id)
);

create table bfsi.exception_case (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  onboarding_id uuid not null,
  exception_type text not null check (exception_type in ('KYC','SCREENING','RISK','SUITABILITY','HOLDING')),
  status text not null check (status in ('OPEN','PENDING_APPROVAL','APPROVED','REJECTED','CLOSED')),
  reason text not null,
  resolution text,
  approval_request_id uuid references security.approval_request(id),
  owner_id uuid not null,
  created_by uuid not null,
  created_at timestamptz not null default now(),
  resolved_at timestamptz,
  unique (tenant_id, id),
  constraint fk_bfsi_exception_onboarding_same_tenant foreign key (tenant_id, onboarding_id)
    references bfsi.client_onboarding(tenant_id, id)
);

create index idx_kyc_item_open on bfsi.kyc_item(tenant_id, onboarding_id, status, expires_at);
create index idx_bfsi_recommendation_status on bfsi.product_recommendation(tenant_id, status, created_at desc);
create index idx_bfsi_exception_status on bfsi.exception_case(tenant_id, status, created_at desc);

-- ---------------------------------------------------------------------------
-- E23 - commodity origination, pricing, approval and reliable execution queue
-- ---------------------------------------------------------------------------
alter table commodity.counterparty_profile
  add column if not exists master_agreement_status text not null default 'MISSING'
    check (master_agreement_status in ('MISSING','PENDING','EXECUTED','EXPIRED')),
  add column if not exists master_agreement_reference text,
  add column if not exists master_agreement_expires_at date,
  add column if not exists credit_headroom numeric(14,2),
  add column if not exists credit_source text,
  add column if not exists credit_as_of timestamptz,
  add column if not exists source_system text not null default 'MANUAL',
  add column if not exists source_synced_at timestamptz;

alter table commodity.trade_enquiry
  add column if not exists origination_type text not null default 'SPOT_CARGO'
    check (origination_type in ('TERM','SPOT_CARGO','TENDER','STRUCTURED')),
  add column if not exists grade text,
  add column if not exists quantity_tolerance_pct numeric(6,3) not null default 0 check (quantity_tolerance_pct between 0 and 100),
  add column if not exists delivery_location_from text,
  add column if not exists delivery_location_to text,
  add column if not exists incoterm text,
  add column if not exists tender_submission_deadline timestamptz,
  add column if not exists lapse_reason text,
  add column if not exists trade_reference text,
  add column if not exists execution_status text not null default 'NOT_READY'
    check (execution_status in ('NOT_READY','QUEUED','DELIVERED','ACKNOWLEDGED','EXCEPTION')),
  add column if not exists version integer not null default 1 check (version > 0),
  add column if not exists updated_at timestamptz not null default now();

alter table commodity.contract_term_sheet
  add column if not exists version integer not null default 1 check (version > 0),
  add column if not exists approval_request_id uuid references security.approval_request(id),
  add column if not exists approved_by uuid,
  add column if not exists approved_at timestamptz,
  add column if not exists terms jsonb not null default '{}'::jsonb,
  add column if not exists updated_at timestamptz not null default now();

create table commodity.indicative_price (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  trade_enquiry_id uuid not null,
  index_name text not null,
  differential_text text not null,
  quotation_period text not null,
  settlement_convention text not null,
  expression text not null,
  label text not null default 'INDICATIVE - NON-BINDING'
    check (label = 'INDICATIVE - NON-BINDING'),
  status text not null default 'ACTIVE' check (status in ('ACTIVE','SUPERSEDED','WITHDRAWN')),
  created_by uuid not null,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_indicative_price_enquiry_same_tenant foreign key (tenant_id, trade_enquiry_id)
    references commodity.trade_enquiry(tenant_id, id)
);

create table commodity.execution_handoff (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  trade_enquiry_id uuid not null,
  enquiry_version integer not null,
  idempotency_key text not null,
  payload jsonb not null,
  status text not null check (status in ('QUEUED','DELIVERED','ACKNOWLEDGED','EXCEPTION')),
  attempt_count integer not null default 0 check (attempt_count >= 0),
  max_attempts integer not null default 5 check (max_attempts between 1 and 20),
  next_attempt_at timestamptz,
  external_trade_reference text,
  last_error text,
  created_by uuid not null,
  created_at timestamptz not null default now(),
  delivered_at timestamptz,
  acknowledged_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, trade_enquiry_id, enquiry_version),
  unique (tenant_id, idempotency_key),
  constraint fk_handoff_enquiry_same_tenant foreign key (tenant_id, trade_enquiry_id)
    references commodity.trade_enquiry(tenant_id, id)
);

create table commodity.origination_exception (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  trade_enquiry_id uuid not null,
  exception_type text not null check (exception_type in ('MASTER_AGREEMENT','CREDIT','APPROVAL','HANDOFF','TENDER')),
  status text not null check (status in ('OPEN','RESOLVED')),
  reason text not null,
  owner_id uuid not null,
  created_at timestamptz not null default now(),
  resolved_at timestamptz,
  unique (tenant_id, id),
  constraint fk_origination_exception_enquiry_same_tenant foreign key (tenant_id, trade_enquiry_id)
    references commodity.trade_enquiry(tenant_id, id)
);

create index idx_indicative_price_active on commodity.indicative_price(tenant_id, trade_enquiry_id, status);
create index idx_execution_handoff_queue on commodity.execution_handoff(tenant_id, status, next_attempt_at);
create index idx_origination_exception_open on commodity.origination_exception(tenant_id, status, created_at desc);

-- ---------------------------------------------------------------------------
-- Tenant isolation, least privilege and lifecycle catalogue
-- ---------------------------------------------------------------------------
do $$
declare t text;
begin
  foreach t in array array[
    'mobile.offline_record_snapshot','mobile.offline_change','mobile.sync_conflict','mobile.sync_run',
    'bfsi.kyc_requirement','bfsi.kyc_item','bfsi.product_catalog','bfsi.suitability_assessment',
    'bfsi.product_recommendation','bfsi.exception_case',
    'commodity.indicative_price','commodity.execution_handoff','commodity.origination_exception'
  ] loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format('create policy tenant_isolation on %s using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
  end loop;
end $$;

grant select, insert on mobile.offline_record_snapshot to axiom_app;
grant select, insert, update on mobile.offline_change, mobile.sync_conflict, mobile.sync_run to axiom_app;
grant select, insert, update on bfsi.kyc_requirement, bfsi.kyc_item, bfsi.product_catalog,
  bfsi.suitability_assessment, bfsi.product_recommendation, bfsi.exception_case to axiom_app;
grant select, insert, update on commodity.indicative_price, commodity.execution_handoff,
  commodity.origination_exception to axiom_app;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('mobile','offline_record_snapshot','MOBILE','id',true,'APPEND_ONLY'),
  ('mobile','offline_change','MOBILE','id',true,'ACTIVE'),
  ('mobile','sync_conflict','MOBILE','id',true,'APPEND_ONLY'),
  ('mobile','sync_run','MOBILE','id',true,'APPEND_ONLY'),
  ('bfsi','kyc_requirement','BFSI','id',true,'ACTIVE'),
  ('bfsi','kyc_item','BFSI','id',true,'ACTIVE'),
  ('bfsi','product_catalog','BFSI','id',true,'ACTIVE'),
  ('bfsi','suitability_assessment','BFSI','id',true,'APPEND_ONLY'),
  ('bfsi','product_recommendation','BFSI','id',true,'ACTIVE'),
  ('bfsi','exception_case','BFSI','id',true,'APPEND_ONLY'),
  ('commodity','indicative_price','COMMODITY','id',true,'APPEND_ONLY'),
  ('commodity','execution_handoff','COMMODITY','id',true,'OUTBOX'),
  ('commodity','origination_exception','COMMODITY','id',true,'APPEND_ONLY')
on conflict (schema_name, table_name) do nothing;

insert into security.controlled_action(action_code, label, description) values
  ('BFSI_SUITABILITY_OVERRIDE','Suitability override','Approve issuance of a recommendation outside assessed suitability.'),
  ('BFSI_EXCEPTION','BFSI exception','Approve a regulated BFSI onboarding or compliance exception.'),
  ('COMMODITY_TERM_APPROVAL','Commodity term approval','Approve a commodity term sheet before an offer is released.')
on conflict (action_code) do update set label=excluded.label, description=excluded.description;

-- Seed governed masters and current demonstration records without tier gates.
insert into bfsi.kyc_requirement(tenant_id, requirement_code, name, owner_role, document_required, expiry_warning_days)
select t.id, v.code, v.name, v.owner_role, true, v.warning_days
from platform.tenant t
cross join (values
  ('IDENTITY','Identity verification','KYC_ANALYST',30),
  ('ADDRESS','Address verification','KYC_ANALYST',30),
  ('BENEFICIAL_OWNER','Beneficial ownership','COMPLIANCE',45),
  ('SOURCE_OF_FUNDS','Source of funds','RELATIONSHIP_MANAGER',60)
) v(code,name,owner_role,warning_days)
on conflict (tenant_id, requirement_code) do nothing;

insert into bfsi.kyc_item(tenant_id, onboarding_id, requirement_id, status, owner_id, evidence_reference, expires_at, verified_by, verified_at)
select o.tenant_id, o.id, r.id,
       case when o.kyc_status='CLEARED' then 'VERIFIED' else 'MISSING' end,
       o.owner_id,
       case when o.kyc_status='CLEARED' then 'seed://verified/' || lower(r.requirement_code) end,
       case when o.kyc_status='CLEARED' then current_date + 365 end,
       case when o.kyc_status='CLEARED' then o.owner_id end,
       case when o.kyc_status='CLEARED' then now() end
from bfsi.client_onboarding o join bfsi.kyc_requirement r on r.tenant_id=o.tenant_id
on conflict (tenant_id,onboarding_id,requirement_id) do nothing;

insert into bfsi.product_catalog(tenant_id,product_code,product_family,name,minimum_suitability_level)
select t.id,v.code,v.family,v.name,v.level from platform.tenant t cross join (values
 ('CASA','DEPOSITS','Current And Savings Account','BASIC'),
 ('TERM_DEP','DEPOSITS','Term Deposit','STANDARD'),
 ('WORKING_CAPITAL','LENDING','Working Capital Facility','STANDARD'),
 ('FX_HEDGE','MARKETS','Foreign Exchange Hedge','COMPLEX'),
 ('STRUCTURED_NOTE','INVESTMENTS','Structured Note','PROFESSIONAL')
) v(code,family,name,level)
on conflict (tenant_id,product_code) do nothing;

update commodity.counterparty_profile
set master_agreement_status=case when status='ACTIVE' then 'EXECUTED' else 'MISSING' end,
    master_agreement_reference=case when status='ACTIVE' then 'MSA-' || counterparty_code end,
    master_agreement_expires_at=case when status='ACTIVE' then current_date + 365 end,
    credit_headroom=greatest(credit_limit-exposure_amount,0), credit_source='AXIOM_LOCAL_CTRM',
    credit_as_of=now(), source_system='AXIOM_LOCAL_CTRM', source_synced_at=now()
where credit_as_of is null;

update commodity.trade_enquiry set
  origination_type=case when enquiry_number like '%TENDER%' then 'TENDER' else 'SPOT_CARGO' end,
  grade=coalesce(grade,'Standard'), quantity_tolerance_pct=coalesce(quantity_tolerance_pct,5),
  delivery_location_from=coalesce(delivery_location_from,'Load Port'),
  delivery_location_to=coalesce(delivery_location_to,'Discharge Port'),
  incoterm=coalesce(incoterm,'CIF')
where grade is null;

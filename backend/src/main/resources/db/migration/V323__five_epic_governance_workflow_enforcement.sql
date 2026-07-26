-- ---------------------------------------------------------------------------
-- Five-epic governance workflow enforcement (E19-E23)
-- ---------------------------------------------------------------------------

insert into automation.automation_object
  (tenant_id, object_type, label, schema_name, table_name, owner_column,
   soft_delete_column, protected_columns, parent_object, parent_column)
select t.id, seed.object_type, seed.label, seed.schema_name, seed.table_name,
       seed.owner_column, null, array['id','tenant_id']::text[], null, null
from platform.tenant t
cross join (values
  ('SANDBOX',             'Sandbox environment',     'platform',   'sandbox_environment', 'owner_id'),
  ('AUDIT_EVIDENCE_PACK', 'Audit evidence pack',     'governance', 'audit_evidence_pack', 'generated_by'),
  ('DEVICE_SESSION',      'Mobile device session',   'mobile',     'device_session',      'user_id'),
  ('BFSI_ONBOARDING',     'BFSI client onboarding',  'bfsi',       'client_onboarding',   'owner_id'),
  ('COMMODITY_ENQUIRY',   'Commodity trade enquiry', 'commodity',  'trade_enquiry',       null)
) as seed(object_type, label, schema_name, table_name, owner_column)
on conflict (tenant_id, object_type) do nothing;

insert into automation.process_definition
  (tenant_id, process_code, name, object_type, state_field, status, created_by)
select t.id, seed.process_code, seed.name, seed.object_type, seed.state_field, 'ACTIVE',
       (select u.id from identity.app_user u where u.tenant_id = t.id
         order by case when u.role = 'SUPER_ADMIN' then 0 when u.role = 'TENANT_ADMIN' then 1 else 2 end,
                  u.created_at limit 1)
from platform.tenant t
cross join (values
  ('PRC-SANDBOX-LIFECYCLE',       'Sandbox refresh lifecycle',      'SANDBOX',             'status'),
  ('PRC-EVIDENCE-PACK-LIFECYCLE', 'Audit evidence pack lifecycle',  'AUDIT_EVIDENCE_PACK', 'status'),
  ('PRC-DEVICE-SYNC-LIFECYCLE',   'Mobile device sync lifecycle',   'DEVICE_SESSION',      'status'),
  ('PRC-BFSI-KYC-LIFECYCLE',      'BFSI KYC clearance lifecycle',   'BFSI_ONBOARDING',     'kyc_status'),
  ('PRC-COMMODITY-ENQUIRY',       'Commodity enquiry lifecycle',    'COMMODITY_ENQUIRY',   'status')
) as seed(process_code, name, object_type, state_field)
on conflict (tenant_id, process_code) do nothing;

insert into automation.process_state
  (tenant_id, process_id, state_code, label, state_order, is_initial,
   is_terminal, mandatory_fields, sla_minutes)
select p.tenant_id, p.id, seed.state_code, seed.label, seed.state_order,
       seed.is_initial, seed.is_terminal, seed.mandatory_fields::text[], seed.sla_minutes
from automation.process_definition p
join (values
  ('PRC-SANDBOX-LIFECYCLE','REQUESTED','Requested',10,true,false,'{sandbox_code,name,sandbox_type,source_environment,owner_id}',1440::int),
  ('PRC-SANDBOX-LIFECYCLE','PROVISIONING','Provisioning',20,false,false,'{sandbox_code,name,sandbox_type,source_environment,owner_id}',240),
  ('PRC-SANDBOX-LIFECYCLE','ACTIVE','Active',30,false,false,'{sandbox_code,name,sandbox_type,source_environment,owner_id,last_refreshed_at,expires_at}',null),
  ('PRC-SANDBOX-LIFECYCLE','REFRESHING','Refreshing',40,false,false,'{sandbox_code,name,sandbox_type,source_environment,owner_id}',240),
  ('PRC-SANDBOX-LIFECYCLE','FAILED','Failed',50,false,false,'{sandbox_code,name,sandbox_type,owner_id}',1440),
  ('PRC-SANDBOX-LIFECYCLE','ARCHIVED','Archived',60,false,true,'{sandbox_code,name}',null),

  ('PRC-EVIDENCE-PACK-LIFECYCLE','DRAFT','Draft',10,true,false,'{pack_code,name,scope,generated_by}',null),
  ('PRC-EVIDENCE-PACK-LIFECYCLE','GENERATING','Generating',20,false,false,'{pack_code,name,scope,generated_by}',240),
  ('PRC-EVIDENCE-PACK-LIFECYCLE','READY','Ready',30,false,false,'{pack_code,name,scope,generated_by,generated_at}',1440),
  ('PRC-EVIDENCE-PACK-LIFECYCLE','EXPORTED','Exported',40,false,true,'{pack_code,name,scope,generated_by,generated_at}',null),
  ('PRC-EVIDENCE-PACK-LIFECYCLE','FAILED','Failed',50,false,false,'{pack_code,name,scope,generated_by}',1440),

  ('PRC-DEVICE-SYNC-LIFECYCLE','ACTIVE','Active',10,true,false,'{device_label,platform,user_id,app_version}',null),
  ('PRC-DEVICE-SYNC-LIFECYCLE','LOCKED','Locked',20,false,false,'{device_label,platform,user_id,app_version}',1440),
  ('PRC-DEVICE-SYNC-LIFECYCLE','WIPED','Wiped',30,false,true,'{device_label,platform,user_id,app_version}',null),
  ('PRC-DEVICE-SYNC-LIFECYCLE','EXPIRED','Expired',40,false,true,'{device_label,platform,user_id,app_version}',null),

  ('PRC-BFSI-KYC-LIFECYCLE','NOT_STARTED','Not started',10,true,false,'{onboarding_number,account_id,client_type,risk_rating,owner_id,due_at}',1440),
  ('PRC-BFSI-KYC-LIFECYCLE','IN_PROGRESS','In progress',20,false,false,'{onboarding_number,account_id,client_type,risk_rating,owner_id,due_at}',4320),
  ('PRC-BFSI-KYC-LIFECYCLE','ENHANCED_DUE_DILIGENCE','Enhanced due diligence',30,false,false,'{onboarding_number,account_id,client_type,risk_rating,owner_id,due_at}',10080),
  ('PRC-BFSI-KYC-LIFECYCLE','CLEARED','Cleared',40,false,true,'{onboarding_number,account_id,client_type,risk_rating,owner_id,due_at,completed_at}',null),
  ('PRC-BFSI-KYC-LIFECYCLE','REJECTED','Rejected',50,false,true,'{onboarding_number,account_id,client_type,risk_rating,owner_id,due_at,completed_at}',null),

  ('PRC-COMMODITY-ENQUIRY','RECEIVED','Received',10,true,false,'{enquiry_number,counterparty_profile_id,commodity_name,quantity,unit}',1440),
  ('PRC-COMMODITY-ENQUIRY','PRICING','Pricing',20,false,false,'{enquiry_number,counterparty_profile_id,commodity_name,quantity,unit,notional_amount}',1440),
  ('PRC-COMMODITY-ENQUIRY','OFFERED','Offered',30,false,false,'{enquiry_number,counterparty_profile_id,commodity_name,quantity,unit,notional_amount,delivery_window_start,delivery_window_end}',2880),
  ('PRC-COMMODITY-ENQUIRY','WON','Won',40,false,true,'{enquiry_number,counterparty_profile_id,commodity_name,quantity,unit,notional_amount}',null),
  ('PRC-COMMODITY-ENQUIRY','LOST','Lost',50,false,true,'{enquiry_number,counterparty_profile_id,commodity_name,quantity,unit}',null),
  ('PRC-COMMODITY-ENQUIRY','EXPIRED','Expired',60,false,true,'{enquiry_number,counterparty_profile_id,commodity_name,quantity,unit}',null)
) as seed(process_code, state_code, label, state_order, is_initial, is_terminal,
          mandatory_fields, sla_minutes)
  on seed.process_code = p.process_code
on conflict (tenant_id, process_id, state_code) do nothing;

insert into automation.process_transition
  (tenant_id, process_id, from_state, to_state, label, conditions)
select p.tenant_id, p.id, seed.from_state, seed.to_state, seed.label, seed.conditions::jsonb
from automation.process_definition p
join (values
  ('PRC-SANDBOX-LIFECYCLE','REQUESTED','PROVISIONING','Begin sandbox provisioning','[]'),
  ('PRC-SANDBOX-LIFECYCLE','REQUESTED','ACTIVE','Complete requested refresh','[]'),
  ('PRC-SANDBOX-LIFECYCLE','PROVISIONING','ACTIVE','Complete sandbox provisioning','[]'),
  ('PRC-SANDBOX-LIFECYCLE','PROVISIONING','FAILED','Fail sandbox provisioning','[]'),
  ('PRC-SANDBOX-LIFECYCLE','ACTIVE','ACTIVE','Refresh active sandbox','[]'),
  ('PRC-SANDBOX-LIFECYCLE','ACTIVE','REFRESHING','Begin active sandbox refresh','[]'),
  ('PRC-SANDBOX-LIFECYCLE','REFRESHING','ACTIVE','Complete sandbox refresh','[]'),
  ('PRC-SANDBOX-LIFECYCLE','REFRESHING','FAILED','Fail sandbox refresh','[]'),
  ('PRC-SANDBOX-LIFECYCLE','FAILED','ACTIVE','Recover failed sandbox','[]'),
  ('PRC-SANDBOX-LIFECYCLE','FAILED','REFRESHING','Retry failed sandbox refresh','[]'),
  ('PRC-SANDBOX-LIFECYCLE','REQUESTED','ARCHIVED','Archive request','[]'),
  ('PRC-SANDBOX-LIFECYCLE','ACTIVE','ARCHIVED','Archive sandbox','[]'),
  ('PRC-SANDBOX-LIFECYCLE','FAILED','ARCHIVED','Archive failed sandbox','[]'),

  ('PRC-EVIDENCE-PACK-LIFECYCLE','DRAFT','GENERATING','Generate evidence pack','[]'),
  ('PRC-EVIDENCE-PACK-LIFECYCLE','GENERATING','READY','Complete evidence pack','[]'),
  ('PRC-EVIDENCE-PACK-LIFECYCLE','GENERATING','FAILED','Fail evidence generation','[]'),
  ('PRC-EVIDENCE-PACK-LIFECYCLE','FAILED','GENERATING','Retry evidence generation','[]'),
  ('PRC-EVIDENCE-PACK-LIFECYCLE','READY','EXPORTED','Export evidence pack','[]'),

  ('PRC-DEVICE-SYNC-LIFECYCLE','ACTIVE','ACTIVE','Acknowledge device sync','[]'),
  ('PRC-DEVICE-SYNC-LIFECYCLE','ACTIVE','LOCKED','Lock device session','[]'),
  ('PRC-DEVICE-SYNC-LIFECYCLE','ACTIVE','WIPED','Wipe device session','[]'),
  ('PRC-DEVICE-SYNC-LIFECYCLE','ACTIVE','EXPIRED','Expire device session','[]'),
  ('PRC-DEVICE-SYNC-LIFECYCLE','LOCKED','ACTIVE','Unlock device session','[]'),
  ('PRC-DEVICE-SYNC-LIFECYCLE','LOCKED','WIPED','Wipe locked device','[]'),
  ('PRC-DEVICE-SYNC-LIFECYCLE','LOCKED','EXPIRED','Expire locked device','[]'),

  ('PRC-BFSI-KYC-LIFECYCLE','NOT_STARTED','IN_PROGRESS','Begin KYC review','[]'),
  ('PRC-BFSI-KYC-LIFECYCLE','NOT_STARTED','CLEARED','Clear completed KYC review','[{"field":"risk_rating","op":"IN","value":"LOW|MEDIUM|HIGH","label":"a permitted risk rating"}]'),
  ('PRC-BFSI-KYC-LIFECYCLE','IN_PROGRESS','ENHANCED_DUE_DILIGENCE','Escalate due diligence','[]'),
  ('PRC-BFSI-KYC-LIFECYCLE','IN_PROGRESS','CLEARED','Clear KYC review','[{"field":"risk_rating","op":"IN","value":"LOW|MEDIUM|HIGH","label":"a permitted risk rating"}]'),
  ('PRC-BFSI-KYC-LIFECYCLE','IN_PROGRESS','REJECTED','Reject KYC review','[]'),
  ('PRC-BFSI-KYC-LIFECYCLE','ENHANCED_DUE_DILIGENCE','CLEARED','Clear enhanced review','[{"field":"risk_rating","op":"IN","value":"LOW|MEDIUM|HIGH","label":"a permitted risk rating"}]'),
  ('PRC-BFSI-KYC-LIFECYCLE','ENHANCED_DUE_DILIGENCE','REJECTED','Reject enhanced review','[]'),

  ('PRC-COMMODITY-ENQUIRY','RECEIVED','PRICING','Begin pricing','[]'),
  ('PRC-COMMODITY-ENQUIRY','RECEIVED','OFFERED','Issue direct offer','[{"field":"notional_amount","op":"GT","value":"0","label":"a positive notional amount"},{"field":"delivery_window_start","op":"NOT_BLANK","value":"","label":"a delivery start date"},{"field":"delivery_window_end","op":"NOT_BLANK","value":"","label":"a delivery end date"}]'),
  ('PRC-COMMODITY-ENQUIRY','PRICING','OFFERED','Issue priced offer','[{"field":"notional_amount","op":"GT","value":"0","label":"a positive notional amount"},{"field":"delivery_window_start","op":"NOT_BLANK","value":"","label":"a delivery start date"},{"field":"delivery_window_end","op":"NOT_BLANK","value":"","label":"a delivery end date"}]'),
  ('PRC-COMMODITY-ENQUIRY','OFFERED','WON','Accept commodity offer','[]'),
  ('PRC-COMMODITY-ENQUIRY','OFFERED','LOST','Decline commodity offer','[]'),
  ('PRC-COMMODITY-ENQUIRY','RECEIVED','EXPIRED','Expire unpriced enquiry','[]'),
  ('PRC-COMMODITY-ENQUIRY','PRICING','EXPIRED','Expire pricing enquiry','[]'),
  ('PRC-COMMODITY-ENQUIRY','OFFERED','EXPIRED','Expire commodity offer','[]')
) as seed(process_code, from_state, to_state, label, conditions)
  on seed.process_code = p.process_code
on conflict (tenant_id, process_id, from_state, to_state) do nothing;

drop trigger if exists trg_enforce_process_sandbox on platform.sandbox_environment;
create trigger trg_enforce_process_sandbox
  before insert or update on platform.sandbox_environment
  for each row execute function automation.enforce_process_transition('SANDBOX');

drop trigger if exists trg_enforce_process_evidence_pack on governance.audit_evidence_pack;
create trigger trg_enforce_process_evidence_pack
  before insert or update on governance.audit_evidence_pack
  for each row execute function automation.enforce_process_transition('AUDIT_EVIDENCE_PACK');

drop trigger if exists trg_enforce_process_device_session on mobile.device_session;
create trigger trg_enforce_process_device_session
  before insert or update on mobile.device_session
  for each row execute function automation.enforce_process_transition('DEVICE_SESSION');

drop trigger if exists trg_enforce_process_bfsi_onboarding on bfsi.client_onboarding;
create trigger trg_enforce_process_bfsi_onboarding
  before insert or update on bfsi.client_onboarding
  for each row execute function automation.enforce_process_transition('BFSI_ONBOARDING');

drop trigger if exists trg_enforce_process_commodity_enquiry on commodity.trade_enquiry;
create trigger trg_enforce_process_commodity_enquiry
  before insert or update on commodity.trade_enquiry
  for each row execute function automation.enforce_process_transition('COMMODITY_ENQUIRY');

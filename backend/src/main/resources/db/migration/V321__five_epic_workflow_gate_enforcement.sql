-- ---------------------------------------------------------------------------
-- Five-epic workflow gate enforcement (E09-E13)
--
-- Adds the operational objects to the automation metadata catalogue, activates
-- one governed process per object, and attaches the same database state-machine
-- trigger used by opportunities.  API, UI, imports and support SQL therefore
-- obey the same transitions.
-- ---------------------------------------------------------------------------

insert into automation.automation_object
  (tenant_id, object_type, label, schema_name, table_name, owner_column,
   soft_delete_column, protected_columns, parent_object, parent_column)
select t.id, seed.object_type, seed.label, seed.schema_name, seed.table_name,
       seed.owner_column, seed.soft_delete_column,
       array['id','tenant_id','created_at','updated_at','version']::text[],
       seed.parent_object, seed.parent_column
from platform.tenant t
cross join (values
  ('CONTRACT',            'Contract',            'contracting', 'contract_record',    'owner_id',   'deleted_at', 'ACCOUNT', 'account_id'),
  ('FORECAST_SUBMISSION', 'Forecast submission', 'forecasting', 'forecast_submission','owner_id',   null,         null,      null),
  ('CAMPAIGN',            'Campaign',            'marketing',   'campaign',           'owner_id',   'deleted_at', null,      null),
  ('CASE',                'Case',                'service',     'case_record',         'owner_id',   'deleted_at', 'ACCOUNT', 'account_id'),
  ('PARTNER_ACCOUNT',     'Partner account',     'channel',     'partner_account',     'manager_id', 'deleted_at', 'ACCOUNT', 'account_id')
) as seed(object_type, label, schema_name, table_name, owner_column,
          soft_delete_column, parent_object, parent_column)
on conflict (tenant_id, object_type) do nothing;

insert into automation.process_definition
  (tenant_id, process_code, name, object_type, state_field, status, created_by)
select t.id, seed.process_code, seed.name, seed.object_type, 'status', 'ACTIVE',
       (select u.id from identity.app_user u where u.tenant_id = t.id
         order by case when u.role = 'SUPER_ADMIN' then 0 when u.role = 'TENANT_ADMIN' then 1 else 2 end,
                  u.created_at limit 1)
from platform.tenant t
cross join (values
  ('PRC-CONTRACT-LIFECYCLE', 'Contract lifecycle', 'CONTRACT'),
  ('PRC-FORECAST-SUBMISSION', 'Forecast submission lifecycle', 'FORECAST_SUBMISSION'),
  ('PRC-CAMPAIGN-LIFECYCLE', 'Campaign lifecycle', 'CAMPAIGN'),
  ('PRC-CASE-RESOLUTION', 'Case resolution lifecycle', 'CASE'),
  ('PRC-PARTNER-LIFECYCLE', 'Partner lifecycle', 'PARTNER_ACCOUNT')
) as seed(process_code, name, object_type)
on conflict (tenant_id, process_code) do nothing;

insert into automation.process_state
  (tenant_id, process_id, state_code, label, state_order, is_initial,
   is_terminal, mandatory_fields, sla_minutes)
select p.tenant_id, p.id, seed.state_code, seed.label, seed.state_order,
       seed.is_initial, seed.is_terminal, seed.mandatory_fields::text[], seed.sla_minutes
from automation.process_definition p
join (values
  ('PRC-CONTRACT-LIFECYCLE','DRAFT','Draft',10,true,false,'{contract_number,title,start_date,end_date}',null::int),
  ('PRC-CONTRACT-LIFECYCLE','IN_REVIEW','In review',20,false,false,'{contract_number,title,start_date,end_date}',2880),
  ('PRC-CONTRACT-LIFECYCLE','ACTIVE','Active',30,false,false,'{signed_document_ref,start_date,end_date}',null),
  ('PRC-CONTRACT-LIFECYCLE','EXPIRING','Expiring',40,false,false,'{signed_document_ref,renewal_notice_date}',10080),
  ('PRC-CONTRACT-LIFECYCLE','EXPIRED','Expired',50,false,true,'{end_date}',null),
  ('PRC-CONTRACT-LIFECYCLE','TERMINATED','Terminated',60,false,true,'{}',null),

  ('PRC-FORECAST-SUBMISSION','DRAFT','Draft',10,true,false,'{submitted_amount,confidence_pct}',null),
  ('PRC-FORECAST-SUBMISSION','SUBMITTED','Submitted',20,false,false,'{submitted_amount,confidence_pct}',1440),
  ('PRC-FORECAST-SUBMISSION','MANAGER_ADJUSTED','Manager adjusted',30,false,false,'{submitted_amount,confidence_pct,manager_note}',1440),
  ('PRC-FORECAST-SUBMISSION','LOCKED','Locked',40,false,true,'{submitted_amount,confidence_pct}',null),

  ('PRC-CAMPAIGN-LIFECYCLE','PLANNED','Planned',10,true,false,'{code,name,start_date,budget_amount}',null),
  ('PRC-CAMPAIGN-LIFECYCLE','ACTIVE','Active',20,false,false,'{code,name,start_date,budget_amount}',null),
  ('PRC-CAMPAIGN-LIFECYCLE','PAUSED','Paused',30,false,false,'{code,name,start_date}',4320),
  ('PRC-CAMPAIGN-LIFECYCLE','COMPLETED','Completed',40,false,true,'{code,name,start_date}',null),
  ('PRC-CAMPAIGN-LIFECYCLE','CANCELLED','Cancelled',50,false,true,'{}',null),

  ('PRC-CASE-RESOLUTION','NEW','New',10,true,false,'{case_number,subject,account_id,owner_id}',240),
  ('PRC-CASE-RESOLUTION','WORKING','Working',20,false,false,'{subject,account_id,owner_id,entitlement_id}',null),
  ('PRC-CASE-RESOLUTION','WAITING_ON_CUSTOMER','Waiting on customer',30,false,false,'{subject,account_id,owner_id}',4320),
  ('PRC-CASE-RESOLUTION','ESCALATED','Escalated',40,false,false,'{subject,account_id,owner_id,entitlement_id}',240),
  ('PRC-CASE-RESOLUTION','RESOLVED','Resolved',50,false,false,'{subject,account_id,owner_id,entitlement_id}',null),
  ('PRC-CASE-RESOLUTION','CLOSED','Closed',60,false,true,'{closed_at}',null),

  ('PRC-PARTNER-LIFECYCLE','ONBOARDING','Onboarding',10,true,false,'{partner_code,account_id,manager_id,tier}',10080),
  ('PRC-PARTNER-LIFECYCLE','ACTIVE','Active',20,false,false,'{partner_code,account_id,manager_id,tier,territory_scope}',null),
  ('PRC-PARTNER-LIFECYCLE','SUSPENDED','Suspended',30,false,false,'{partner_code,account_id,manager_id}',4320),
  ('PRC-PARTNER-LIFECYCLE','TERMINATED','Terminated',40,false,true,'{}',null)
) as seed(process_code, state_code, label, state_order, is_initial, is_terminal,
          mandatory_fields, sla_minutes)
  on seed.process_code = p.process_code
on conflict (tenant_id, process_id, state_code) do nothing;

insert into automation.process_transition
  (tenant_id, process_id, from_state, to_state, label, conditions)
select p.tenant_id, p.id, seed.from_state, seed.to_state, seed.label, seed.conditions::jsonb
from automation.process_definition p
join (values
  ('PRC-CONTRACT-LIFECYCLE','DRAFT','IN_REVIEW','Send for contract review','[]'),
  ('PRC-CONTRACT-LIFECYCLE','DRAFT','ACTIVE','Activate signed contract','[{"field":"signed_document_ref","op":"NOT_BLANK","value":"","label":"a signed document reference"}]'),
  ('PRC-CONTRACT-LIFECYCLE','IN_REVIEW','ACTIVE','Activate approved contract','[{"field":"signed_document_ref","op":"NOT_BLANK","value":"","label":"a signed document reference"}]'),
  ('PRC-CONTRACT-LIFECYCLE','ACTIVE','EXPIRING','Start renewal review','[]'),
  ('PRC-CONTRACT-LIFECYCLE','EXPIRING','ACTIVE','Renew contract','[{"field":"signed_document_ref","op":"NOT_BLANK","value":"","label":"a signed renewal document reference"}]'),
  ('PRC-CONTRACT-LIFECYCLE','EXPIRING','EXPIRED','Expire contract','[]'),
  ('PRC-CONTRACT-LIFECYCLE','DRAFT','TERMINATED','Terminate draft contract','[]'),
  ('PRC-CONTRACT-LIFECYCLE','IN_REVIEW','TERMINATED','Terminate reviewed contract','[]'),
  ('PRC-CONTRACT-LIFECYCLE','ACTIVE','TERMINATED','Terminate active contract','[]'),
  ('PRC-CONTRACT-LIFECYCLE','EXPIRING','TERMINATED','Terminate expiring contract','[]'),

  ('PRC-FORECAST-SUBMISSION','DRAFT','SUBMITTED','Submit forecast','[]'),
  ('PRC-FORECAST-SUBMISSION','MANAGER_ADJUSTED','SUBMITTED','Resubmit adjusted forecast','[]'),
  ('PRC-FORECAST-SUBMISSION','SUBMITTED','MANAGER_ADJUSTED','Record manager adjustment','[]'),
  ('PRC-FORECAST-SUBMISSION','SUBMITTED','LOCKED','Lock forecast','[]'),
  ('PRC-FORECAST-SUBMISSION','MANAGER_ADJUSTED','LOCKED','Lock adjusted forecast','[]'),

  ('PRC-CAMPAIGN-LIFECYCLE','PLANNED','ACTIVE','Launch campaign','[]'),
  ('PRC-CAMPAIGN-LIFECYCLE','ACTIVE','PAUSED','Pause campaign','[]'),
  ('PRC-CAMPAIGN-LIFECYCLE','PAUSED','ACTIVE','Resume campaign','[]'),
  ('PRC-CAMPAIGN-LIFECYCLE','PLANNED','COMPLETED','Complete planned campaign','[]'),
  ('PRC-CAMPAIGN-LIFECYCLE','ACTIVE','COMPLETED','Complete active campaign','[]'),
  ('PRC-CAMPAIGN-LIFECYCLE','PAUSED','COMPLETED','Complete paused campaign','[]'),
  ('PRC-CAMPAIGN-LIFECYCLE','PLANNED','CANCELLED','Cancel planned campaign','[]'),
  ('PRC-CAMPAIGN-LIFECYCLE','ACTIVE','CANCELLED','Cancel active campaign','[]'),
  ('PRC-CAMPAIGN-LIFECYCLE','PAUSED','CANCELLED','Cancel paused campaign','[]'),

  ('PRC-CASE-RESOLUTION','NEW','WORKING','Start case work','[]'),
  ('PRC-CASE-RESOLUTION','WORKING','WAITING_ON_CUSTOMER','Wait for customer','[]'),
  ('PRC-CASE-RESOLUTION','WAITING_ON_CUSTOMER','WORKING','Resume case work','[]'),
  ('PRC-CASE-RESOLUTION','NEW','ESCALATED','Escalate new case','[]'),
  ('PRC-CASE-RESOLUTION','WORKING','ESCALATED','Escalate working case','[]'),
  ('PRC-CASE-RESOLUTION','WAITING_ON_CUSTOMER','ESCALATED','Escalate waiting case','[]'),
  ('PRC-CASE-RESOLUTION','ESCALATED','WORKING','Return escalated case to work','[]'),
  ('PRC-CASE-RESOLUTION','NEW','RESOLVED','Resolve new case','[]'),
  ('PRC-CASE-RESOLUTION','WORKING','RESOLVED','Resolve working case','[]'),
  ('PRC-CASE-RESOLUTION','WAITING_ON_CUSTOMER','RESOLVED','Resolve waiting case','[]'),
  ('PRC-CASE-RESOLUTION','ESCALATED','RESOLVED','Resolve escalated case','[]'),
  ('PRC-CASE-RESOLUTION','RESOLVED','CLOSED','Close resolved case','[]'),

  ('PRC-PARTNER-LIFECYCLE','ONBOARDING','ACTIVE','Activate partner','[]'),
  ('PRC-PARTNER-LIFECYCLE','ACTIVE','SUSPENDED','Suspend partner','[]'),
  ('PRC-PARTNER-LIFECYCLE','SUSPENDED','ACTIVE','Reactivate partner','[]'),
  ('PRC-PARTNER-LIFECYCLE','ONBOARDING','TERMINATED','Terminate onboarding','[]'),
  ('PRC-PARTNER-LIFECYCLE','ACTIVE','TERMINATED','Terminate active partner','[]'),
  ('PRC-PARTNER-LIFECYCLE','SUSPENDED','TERMINATED','Terminate suspended partner','[]')
) as seed(process_code, from_state, to_state, label, conditions)
  on seed.process_code = p.process_code
on conflict (tenant_id, process_id, from_state, to_state) do nothing;

drop trigger if exists trg_enforce_process_contract on contracting.contract_record;
create trigger trg_enforce_process_contract
  before insert or update on contracting.contract_record
  for each row execute function automation.enforce_process_transition('CONTRACT');

drop trigger if exists trg_enforce_process_forecast_submission on forecasting.forecast_submission;
create trigger trg_enforce_process_forecast_submission
  before insert or update on forecasting.forecast_submission
  for each row execute function automation.enforce_process_transition('FORECAST_SUBMISSION');

drop trigger if exists trg_enforce_process_campaign on marketing.campaign;
create trigger trg_enforce_process_campaign
  before insert or update on marketing.campaign
  for each row execute function automation.enforce_process_transition('CAMPAIGN');

drop trigger if exists trg_enforce_process_case on service.case_record;
create trigger trg_enforce_process_case
  before insert or update on service.case_record
  for each row execute function automation.enforce_process_transition('CASE');

drop trigger if exists trg_enforce_process_partner_account on channel.partner_account;
create trigger trg_enforce_process_partner_account
  before insert or update on channel.partner_account
  for each row execute function automation.enforce_process_transition('PARTNER_ACCOUNT');

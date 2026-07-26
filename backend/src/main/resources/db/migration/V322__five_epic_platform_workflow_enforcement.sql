-- ---------------------------------------------------------------------------
-- Five-epic platform workflow enforcement (E14-E18)
-- ---------------------------------------------------------------------------

insert into automation.automation_object
  (tenant_id, object_type, label, schema_name, table_name, owner_column,
   soft_delete_column, protected_columns, parent_object, parent_column)
select t.id, seed.object_type, seed.label, seed.schema_name, seed.table_name,
       seed.owner_column, null,
       array['id','tenant_id','created_at','updated_at','version']::text[], null, null
from platform.tenant t
cross join (values
  ('AUTOMATION_RULE',         'Automation rule',         'automation',  'automation_rule',         'owner_id'),
  ('ANALYTICS_DASHBOARD',     'Analytics dashboard',     'reporting',   'analytics_dashboard',     'owner_id'),
  ('COPILOT_RECOMMENDATION',  'Copilot recommendation',  'ai',          'copilot_recommendation',  null),
  ('INTEGRATION_CONTRACT',    'Integration contract',    'integration', 'endpoint_contract',       'owner_id'),
  ('IMPORT_BATCH',            'Import batch',             'migration',   'import_batch',            'uploaded_by')
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
  ('PRC-AUTOMATION-SIMULATION', 'Automation simulation readiness', 'AUTOMATION_RULE', 'simulation_passed'),
  ('PRC-DASHBOARD-LIFECYCLE', 'Analytics dashboard lifecycle', 'ANALYTICS_DASHBOARD', 'status'),
  ('PRC-COPILOT-DISPOSITION', 'Copilot recommendation disposition', 'COPILOT_RECOMMENDATION', 'status'),
  ('PRC-INTEGRATION-CONTRACT', 'Integration contract lifecycle', 'INTEGRATION_CONTRACT', 'status'),
  ('PRC-IMPORT-VALIDATION', 'Import validation lifecycle', 'IMPORT_BATCH', 'status')
) as seed(process_code, name, object_type, state_field)
on conflict (tenant_id, process_code) do nothing;

insert into automation.process_state
  (tenant_id, process_id, state_code, label, state_order, is_initial,
   is_terminal, mandatory_fields, sla_minutes)
select p.tenant_id, p.id, seed.state_code, seed.label, seed.state_order,
       seed.is_initial, seed.is_terminal, seed.mandatory_fields::text[], seed.sla_minutes
from automation.process_definition p
join (values
  ('PRC-AUTOMATION-SIMULATION','false','Simulation required',10,true,false,'{rule_code,name,owner_id,active_version}',1440::int),
  ('PRC-AUTOMATION-SIMULATION','true','Simulation passed',20,false,false,'{rule_code,name,owner_id,active_version}',null),

  ('PRC-DASHBOARD-LIFECYCLE','DRAFT','Draft',10,true,false,'{dashboard_code,name,owner_id,refresh_interval_minutes}',null),
  ('PRC-DASHBOARD-LIFECYCLE','ACTIVE','Active',20,false,false,'{dashboard_code,name,owner_id,refresh_interval_minutes}',null),
  ('PRC-DASHBOARD-LIFECYCLE','ARCHIVED','Archived',30,false,true,'{dashboard_code,name}',null),

  ('PRC-COPILOT-DISPOSITION','READY','Ready for review',10,true,false,'{recommendation_number,prompt_id,title,confidence_pct,explanation}',1440),
  ('PRC-COPILOT-DISPOSITION','ACCEPTED','Accepted',20,false,true,'{recommendation_number,prompt_id,title,explanation}',null),
  ('PRC-COPILOT-DISPOSITION','DISMISSED','Dismissed',30,false,true,'{recommendation_number,title}',null),
  ('PRC-COPILOT-DISPOSITION','EXPIRED','Expired',40,false,true,'{recommendation_number,title}',null),

  ('PRC-INTEGRATION-CONTRACT','DRAFT','Draft',10,true,false,'{contract_code,name,direction,auth_type,owner_id}',null),
  ('PRC-INTEGRATION-CONTRACT','ACTIVE','Active',20,false,false,'{contract_code,name,direction,auth_type,owner_id}',null),
  ('PRC-INTEGRATION-CONTRACT','DEPRECATED','Deprecated',30,false,false,'{contract_code,name,owner_id}',10080),
  ('PRC-INTEGRATION-CONTRACT','RETIRED','Retired',40,false,true,'{contract_code,name}',null),

  ('PRC-IMPORT-VALIDATION','UPLOADED','Uploaded',10,true,false,'{batch_number,object_type,file_name,total_rows,uploaded_by}',1440),
  ('PRC-IMPORT-VALIDATION','VALIDATING','Validating',20,false,false,'{batch_number,object_type,file_name,total_rows}',240),
  ('PRC-IMPORT-VALIDATION','READY_TO_IMPORT','Ready to import',30,false,false,'{batch_number,object_type,total_rows,valid_rows}',1440),
  ('PRC-IMPORT-VALIDATION','IMPORTED','Imported',40,false,true,'{batch_number,object_type,total_rows,imported_rows}',null),
  ('PRC-IMPORT-VALIDATION','FAILED','Validation failed',50,false,false,'{batch_number,object_type,total_rows,error_rows}',1440),
  ('PRC-IMPORT-VALIDATION','ROLLED_BACK','Rolled back',60,false,true,'{batch_number,object_type}',null)
) as seed(process_code, state_code, label, state_order, is_initial, is_terminal,
          mandatory_fields, sla_minutes)
  on seed.process_code = p.process_code
on conflict (tenant_id, process_id, state_code) do nothing;

insert into automation.process_transition
  (tenant_id, process_id, from_state, to_state, label, conditions)
select p.tenant_id, p.id, seed.from_state, seed.to_state, seed.label, seed.conditions::jsonb
from automation.process_definition p
join (values
  ('PRC-AUTOMATION-SIMULATION','false','true','Run side-effect-free simulation','[]'),
  ('PRC-AUTOMATION-SIMULATION','true','true','Re-run side-effect-free simulation','[]'),

  ('PRC-DASHBOARD-LIFECYCLE','DRAFT','ACTIVE','Activate dashboard','[]'),
  ('PRC-DASHBOARD-LIFECYCLE','ACTIVE','ACTIVE','Refresh active dashboard','[]'),
  ('PRC-DASHBOARD-LIFECYCLE','DRAFT','ARCHIVED','Archive draft dashboard','[]'),
  ('PRC-DASHBOARD-LIFECYCLE','ACTIVE','ARCHIVED','Archive active dashboard','[]'),

  ('PRC-COPILOT-DISPOSITION','READY','ACCEPTED','Accept grounded recommendation','[]'),
  ('PRC-COPILOT-DISPOSITION','READY','DISMISSED','Dismiss recommendation','[]'),
  ('PRC-COPILOT-DISPOSITION','READY','EXPIRED','Expire recommendation','[]'),

  ('PRC-INTEGRATION-CONTRACT','DRAFT','ACTIVE','Verify and activate contract','[]'),
  ('PRC-INTEGRATION-CONTRACT','ACTIVE','ACTIVE','Re-verify active contract','[]'),
  ('PRC-INTEGRATION-CONTRACT','ACTIVE','DEPRECATED','Deprecate contract','[]'),
  ('PRC-INTEGRATION-CONTRACT','DEPRECATED','RETIRED','Retire deprecated contract','[]'),
  ('PRC-INTEGRATION-CONTRACT','DRAFT','RETIRED','Retire draft contract','[]'),

  ('PRC-IMPORT-VALIDATION','UPLOADED','VALIDATING','Start validation','[]'),
  ('PRC-IMPORT-VALIDATION','FAILED','VALIDATING','Re-run failed validation','[]'),
  ('PRC-IMPORT-VALIDATION','READY_TO_IMPORT','VALIDATING','Re-run validation','[]'),
  ('PRC-IMPORT-VALIDATION','VALIDATING','VALIDATING','Resume validation','[]'),
  ('PRC-IMPORT-VALIDATION','VALIDATING','READY_TO_IMPORT','Pass validation','[]'),
  ('PRC-IMPORT-VALIDATION','VALIDATING','FAILED','Fail validation','[]'),
  ('PRC-IMPORT-VALIDATION','READY_TO_IMPORT','IMPORTED','Import validated batch','[]'),
  ('PRC-IMPORT-VALIDATION','IMPORTED','ROLLED_BACK','Roll back imported batch','[]')
) as seed(process_code, from_state, to_state, label, conditions)
  on seed.process_code = p.process_code
on conflict (tenant_id, process_id, from_state, to_state) do nothing;

drop trigger if exists trg_enforce_process_automation_rule on automation.automation_rule;
create trigger trg_enforce_process_automation_rule
  before insert or update on automation.automation_rule
  for each row execute function automation.enforce_process_transition('AUTOMATION_RULE');

drop trigger if exists trg_enforce_process_analytics_dashboard on reporting.analytics_dashboard;
create trigger trg_enforce_process_analytics_dashboard
  before insert or update on reporting.analytics_dashboard
  for each row execute function automation.enforce_process_transition('ANALYTICS_DASHBOARD');

drop trigger if exists trg_enforce_process_copilot_recommendation on ai.copilot_recommendation;
create trigger trg_enforce_process_copilot_recommendation
  before insert or update on ai.copilot_recommendation
  for each row execute function automation.enforce_process_transition('COPILOT_RECOMMENDATION');

drop trigger if exists trg_enforce_process_integration_contract on integration.endpoint_contract;
create trigger trg_enforce_process_integration_contract
  before insert or update on integration.endpoint_contract
  for each row execute function automation.enforce_process_transition('INTEGRATION_CONTRACT');

drop trigger if exists trg_enforce_process_import_batch on migration.import_batch;
create trigger trg_enforce_process_import_batch
  before insert or update on migration.import_batch
  for each row execute function automation.enforce_process_transition('IMPORT_BATCH');

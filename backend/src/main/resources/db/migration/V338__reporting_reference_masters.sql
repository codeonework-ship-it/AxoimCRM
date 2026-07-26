-- E03 / E15: governed reporting masters.
--
-- These are labels and ordering vocabularies used by report authoring and
-- scheduling. They belong in the existing reference-data aggregate rather than
-- new one-table-per-picklist structures. Runtime safety constraints on
-- reporting.report_definition and reporting.report_subscription remain in
-- place: a deactivated label must never make an unsupported renderer executable.

alter table reference.value_set drop constraint if exists value_set_module_check;
alter table reference.value_set
  add constraint value_set_module_check
  check (module in ('CRM','SALES','ENGAGEMENT','GOVERNANCE','REFERENCE','CPQ','REPORTING'));

insert into reference.value_set (tenant_id, api_name, label, module, description)
select t.id, seed.api_name, seed.label, 'REPORTING', seed.description
from platform.tenant t
cross join (values
  ('report_collection', 'Report collection', 'Governed navigation collections for the Jasper report library.'),
  ('report_output_format', 'Report output format', 'Supported first-party Jasper document formats.'),
  ('report_schedule_frequency', 'Report schedule frequency', 'Approved recurrence choices for report subscriptions.')
) as seed(api_name, label, description)
on conflict (tenant_id, api_name) do update
  set label = excluded.label,
      module = excluded.module,
      description = excluded.description,
      active = true,
      updated_at = now();

insert into reference.value_set_entry
  (tenant_id, value_set_id, code, label, sort_order, system_managed)
select vs.tenant_id, vs.id, seed.code, seed.label, seed.sort_order, true
from reference.value_set vs
join (values
  ('report_collection', 'EXECUTIVE', 'Executive', 10),
  ('report_collection', 'SALES', 'Sales', 20),
  ('report_collection', 'GROWTH', 'Growth', 30),
  ('report_collection', 'CUSTOMER', 'Customer', 40),
  ('report_collection', 'COMMERCIAL', 'Commercial', 50),
  ('report_collection', 'GOVERNANCE', 'Governance', 60),
  ('report_collection', 'GENERAL', 'General', 70),
  ('report_output_format', 'PDF', 'PDF', 10),
  ('report_output_format', 'XLSX', 'Excel', 20),
  ('report_output_format', 'DOCX', 'Word', 30),
  ('report_schedule_frequency', 'DAILY', 'Daily', 10),
  ('report_schedule_frequency', 'WEEKLY', 'Weekly', 20),
  ('report_schedule_frequency', 'MONTHLY', 'Monthly', 30)
) as seed(api_name, code, label, sort_order) on seed.api_name = vs.api_name
on conflict (tenant_id, value_set_id, code) do update
  set label = excluded.label,
      sort_order = excluded.sort_order,
      active = true,
      system_managed = true,
      updated_at = now();

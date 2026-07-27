-- Close database-level lifecycle and RBAC coverage for the documentation master.

create trigger no_delete_drawer_translation before delete on documentation.drawer_translation
for each row execute function documentation.reject_master_delete();
create trigger no_delete_section_translation before delete on documentation.drawer_section_translation
for each row execute function documentation.reject_master_delete();
create trigger no_delete_entry_translation before delete on documentation.drawer_entry_translation
for each row execute function documentation.reject_master_delete();
create trigger no_delete_drawer_revision before delete on documentation.drawer_revision
for each row execute function documentation.reject_master_delete();

insert into governance.screen_catalog
  (screen_code, module_code, route, display_name, description, sort_order)
values
  ('DOCUMENTATION_MASTER', 'DOCUMENTATION', '/admin/documentation', 'Documentation Drawer Master',
   'Governed multilingual sections and entries rendered by the user manual drawer.', 125)
on conflict (screen_code) do update set
  module_code=excluded.module_code, route=excluded.route, display_name=excluded.display_name,
  description=excluded.description, sort_order=excluded.sort_order;

insert into governance.rbac_policy
  (role_code, screen_code, can_read, can_write, can_export, can_admin, scope)
values
  ('SUPER_ADMIN',  'DOCUMENTATION_MASTER', true, true,  true, true,  'PLATFORM'),
  ('SUPER_AUDIT',  'DOCUMENTATION_MASTER', true, false, true, false, 'PLATFORM'),
  ('TENANT_ADMIN', 'DOCUMENTATION_MASTER', true, true,  true, true,  'TENANT'),
  ('DATA_STEWARD', 'DOCUMENTATION_MASTER', true, true,  true, true,  'TENANT'),
  ('AUDITOR',      'DOCUMENTATION_MASTER', true, false, true, false, 'TENANT')
on conflict (role_code, screen_code) do update set
  can_read=excluded.can_read, can_write=excluded.can_write, can_export=excluded.can_export,
  can_admin=excluded.can_admin, scope=excluded.scope;

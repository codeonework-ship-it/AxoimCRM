-- ============================================================================
-- Axiom CRM — adopt pre-existing objects into the `Axiom` owner role.
--
-- Only needed for a database that was created BEFORE the four-environment
-- convention, where the objects are still owned by an earlier superuser. New
-- environments never need this.
--
-- Run as a superuser, connected to the database being adopted:
--     psql -U <superuser> -d AxiomCrmdb_Dev -f 03-adopt-existing-objects.sql
--
-- Deliberately targeted rather than `REASSIGN OWNED BY ... TO ...`: that form
-- also transfers SHARED objects — the other databases and the templates —
-- which is a side effect well outside the intent of adopting one database.
-- ============================================================================

do $$
declare
  r record;
begin
  for r in
    select nspname from pg_namespace
     where nspname not in ('pg_catalog', 'information_schema', 'pg_toast')
       and nspname not like 'pg_temp%'
       and nspname not like 'pg_toast_temp%'
  loop
    execute format('alter schema %I owner to %I', r.nspname, 'Axiom');
  end loop;

  for r in
    select schemaname, tablename from pg_tables
     where schemaname not in ('pg_catalog', 'information_schema')
  loop
    execute format('alter table %I.%I owner to %I', r.schemaname, r.tablename, 'Axiom');
  end loop;

  for r in
    select sequence_schema, sequence_name from information_schema.sequences
     where sequence_schema not in ('pg_catalog', 'information_schema')
  loop
    execute format('alter sequence %I.%I owner to %I', r.sequence_schema, r.sequence_name, 'Axiom');
  end loop;

  for r in
    select table_schema, table_name from information_schema.views
     where table_schema not in ('pg_catalog', 'information_schema')
  loop
    execute format('alter view %I.%I owner to %I', r.table_schema, r.table_name, 'Axiom');
  end loop;
end
$$;

-- Re-assert the runtime grants now that ownership has moved.
grant usage on schema public to axiom_app, axiom_relay;
grant select, insert, update, delete on all tables in schema public to axiom_app;
grant usage, select on all sequences in schema public to axiom_app;

-- Same guard rail as 02: any row here means a runtime role owns a table and
-- therefore BYPASSES row-level security. Treat output as a defect.
select tablename, tableowner
  from pg_tables
 where schemaname not in ('pg_catalog', 'information_schema')
   and tableowner in ('axiom_app', 'axiom_relay');

-- ============================================================================
-- Axiom CRM — per-database privileges
--
-- Roles are cluster-wide in PostgreSQL but GRANTs are per-database, so this
-- script must be run ONCE PER ENVIRONMENT DATABASE, as the owner:
--
--     psql -U Axiom -d AxiomCrmdb_Dev  -f 02-per-database-roles.sql
--     psql -U Axiom -d AxiomCrmdb_QA   -f 02-per-database-roles.sql
--     psql -U Axiom -d AxiomCrmdb_UAT  -f 02-per-database-roles.sql
--     psql -U Axiom -d AxiomCrmdb_Prod -f 02-per-database-roles.sql
--
-- Flyway then creates the schema objects. The DEFAULT PRIVILEGES below are the
-- important part: they apply to tables created LATER by the migration runner,
-- so a new migration cannot accidentally ship a table the application cannot
-- read — or, worse, one it owns and therefore silently bypasses RLS on.
-- ============================================================================

-- pgcrypto is used by the seed data for bcrypt password hashing.
create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------------
-- Schema usage. The module schemas are created by Flyway (V6/V7); granting
-- usage on `public` here covers the bootstrap, and the migrations grant on
-- their own schemas as they create them.
-- ---------------------------------------------------------------------------
grant usage on schema public to axiom_app, axiom_relay;

-- ---------------------------------------------------------------------------
-- Default privileges for objects the OWNER creates from here on.
--
-- Note the asymmetry, and it is deliberate:
--   axiom_app   gets DML on every future table — it serves user traffic, and
--               row-level security is what constrains it, not table grants.
--   axiom_relay gets nothing by default. It is granted explicitly on
--               outbox_event alone (V3), because a relay that can read any
--               table is a cross-tenant reader with no tenant context.
-- ---------------------------------------------------------------------------
alter default privileges for role "Axiom" in schema public
  grant select, insert, update, delete on tables to axiom_app;

alter default privileges for role "Axiom" in schema public
  grant usage, select on sequences to axiom_app;

-- ---------------------------------------------------------------------------
-- Anything that already exists (re-runnable).
-- ---------------------------------------------------------------------------
grant select, insert, update, delete on all tables in schema public to axiom_app;
grant usage, select on all sequences in schema public to axiom_app;

-- ---------------------------------------------------------------------------
-- Guard rail: neither runtime role may own objects in this database.
-- A table owner BYPASSES row-level security in PostgreSQL, so an accidentally
-- owned table would silently disable tenant isolation on it (ADR-001).
-- This query returns rows only if that has happened — treat any output as a
-- defect to fix, not a warning to note.
-- ---------------------------------------------------------------------------
select tablename, tableowner
  from pg_tables
 where schemaname not in ('pg_catalog', 'information_schema')
   and tableowner in ('axiom_app', 'axiom_relay');

-- ============================================================================
-- Axiom CRM — cluster bootstrap: login roles and the four environment databases
--
-- Run ONCE per PostgreSQL server, as a superuser, connected to `postgres`:
--     psql -U <superuser> -d postgres -f 01-create-roles-and-databases.sql
--
-- Then run 02-per-database-roles.sql ONCE PER DATABASE — roles are
-- cluster-wide in PostgreSQL but GRANTs are per-database, so the second script
-- has to be applied to each of the four.
--
-- Database names are double-quoted deliberately. PostgreSQL folds unquoted
-- identifiers to lower case, so `AxiomCrmdb_Dev` without quotes would silently
-- become `axiomcrmdb_dev` and every connection string carrying the mixed-case
-- name would then fail to find it.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Owner / administrative login
--    Owns the schema, runs Flyway migrations, and defines the RLS policies.
--    It is NOT the role the application serves traffic as — see axiom_app.
-- ---------------------------------------------------------------------------
do $$
begin
  if not exists (select from pg_roles where rolname = 'Axiom') then
    create role "Axiom" login password 'Axiom@12345'
      createdb createrole;
  else
    alter role "Axiom" with login password 'Axiom@12345' createdb createrole;
  end if;
end
$$;

-- ---------------------------------------------------------------------------
-- 2. Least-privilege runtime roles (ADR-001)
--
--    axiom_app   — every authenticated request runs as this role. It is subject
--                  to the row-level security policies; that is the entire point.
--                  It must NOT own tables and must NOT be superuser, because
--                  table owners and superusers BYPASS RLS and tenant isolation
--                  would silently stop working.
--
--    axiom_relay — the outbox relay only (ADR-003). It reads and marks
--                  outbox_event across tenants and can do nothing else.
-- ---------------------------------------------------------------------------
do $$
begin
  if not exists (select from pg_roles where rolname = 'axiom_app') then
    create role axiom_app login password 'axiom_app_dev_password';
  end if;
  if not exists (select from pg_roles where rolname = 'axiom_relay') then
    create role axiom_relay login password 'axiom_relay_dev_password';
  end if;
end
$$;

-- Neither runtime role may create databases or roles, and neither bypasses RLS.
-- This is the invariant that matters: the roles serving user traffic are the
-- ones RLS must constrain.
alter role axiom_app   with nosuperuser nocreatedb nocreaterole nobypassrls;
alter role axiom_relay with nosuperuser nocreatedb nocreaterole nobypassrls;

-- ---------------------------------------------------------------------------
-- The migration role needs two further grants, both discovered the hard way:
--
-- 1. BYPASSRLS. V1 sets FORCE ROW LEVEL SECURITY, which applies to the table
--    OWNER as well, so the seed INSERTs in V2 and 14 later migrations are
--    refused without it. Those migrations previously ran as a full SUPERUSER,
--    which bypassed RLS implicitly — the dependency was invisible until the
--    migration role was correctly least-privileged. BYPASSRLS is strictly less
--    privileged than the superuser it replaces, and leaves axiom_app and
--    axiom_relay fully subject to RLS, which is what ADR-001 actually requires.
--
-- 2. ADMIN OPTION on the runtime roles. From PostgreSQL 16, CREATEROLE alone
--    does not permit altering a role you did not create, and V6 runs
--    `alter role axiom_app set search_path ...`.
-- ---------------------------------------------------------------------------
alter role "Axiom" with bypassrls;
grant axiom_app   to "Axiom" with admin option;
grant axiom_relay to "Axiom" with admin option;

-- ---------------------------------------------------------------------------
-- 3. The four environment databases
--    Each environment is a separate database on the same cluster for local and
--    CI work. In a real deployment QA/UAT/Prod live on separate servers; the
--    names stay identical so connection strings differ only by host.
-- ---------------------------------------------------------------------------
select 'create database "AxiomCrmdb_Dev"  owner "Axiom" encoding ''UTF8'''
 where not exists (select from pg_database where datname = 'AxiomCrmdb_Dev')
\gexec

select 'create database "AxiomCrmdb_QA"   owner "Axiom" encoding ''UTF8'''
 where not exists (select from pg_database where datname = 'AxiomCrmdb_QA')
\gexec

select 'create database "AxiomCrmdb_UAT"  owner "Axiom" encoding ''UTF8'''
 where not exists (select from pg_database where datname = 'AxiomCrmdb_UAT')
\gexec

select 'create database "AxiomCrmdb_Prod" owner "Axiom" encoding ''UTF8'''
 where not exists (select from pg_database where datname = 'AxiomCrmdb_Prod')
\gexec

-- ---------------------------------------------------------------------------
-- 4. Connect privileges
-- ---------------------------------------------------------------------------
grant connect on database "AxiomCrmdb_Dev"  to axiom_app, axiom_relay;
grant connect on database "AxiomCrmdb_QA"   to axiom_app, axiom_relay;
grant connect on database "AxiomCrmdb_UAT"  to axiom_app, axiom_relay;
grant connect on database "AxiomCrmdb_Prod" to axiom_app, axiom_relay;

-- Production hardening: nobody connects to prod by accident from a template.
revoke connect on database "AxiomCrmdb_Prod" from public;

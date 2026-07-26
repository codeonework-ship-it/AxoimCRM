-- =============================================================================
-- V330 — saved list views, and the evidence trail for bulk record changes.
--
-- Two features, one migration, because they are the same story from the user's
-- side: shaping a list, then acting on what the list selected.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Saved views
--
-- The state stored here is exactly the state `usePersistedGridState` already
-- keeps in localStorage — group columns and column filters — plus sort and
-- column order. That correspondence is deliberate: a saved view is the same
-- object the live grid holds, persisted and named. If the two shapes diverge,
-- applying a view silently drops whatever the grid understands and the view does
-- not, which is the failure mode that makes saved views untrustworthy.
--
-- `definition` is jsonb rather than columns per facet because the facets are a
-- UI concern that will grow (pinned columns, row height, density), and adding a
-- column per facet means a migration every time the grid learns a new trick.
-- The trade-off accepted: the database cannot validate the contents. The service
-- validates on write instead, so a malformed view cannot be stored.
-- -----------------------------------------------------------------------------
create table if not exists crm.saved_view (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  -- Matches the frontend gridKey ("contacts", "accounts", "cpq-quotes"). One
  -- namespace for both so a view can only ever be offered on the grid it fits.
  grid_key       text not null,
  name           text not null,
  description    text,
  owner_id       uuid not null references identity.app_user(id),
  -- PRIVATE: only the owner. SHARED: everyone in the tenant may apply it, only
  -- the owner and tenant admins may change it. There is deliberately no
  -- "shared and editable by anyone" — a view someone relies on daily should not
  -- change under them without an owner deciding.
  visibility     text not null default 'PRIVATE'
                 check (visibility in ('PRIVATE', 'SHARED')),
  definition     jsonb not null default '{}'::jsonb,
  -- At most one default per user per grid; enforced by a partial unique index
  -- below rather than by application care.
  is_default     boolean not null default false,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  created_by     uuid,
  updated_by     uuid,
  deleted_at     timestamptz,
  deleted_by     uuid,
  version        bigint not null default 0
);

create index if not exists idx_saved_view_grid
  on crm.saved_view (tenant_id, grid_key) where deleted_at is null;

-- A user's own view names must be unambiguous on a given grid; two people may
-- each have a "My open accounts" without colliding.
create unique index if not exists uq_saved_view_name
  on crm.saved_view (tenant_id, grid_key, owner_id, lower(name))
  where deleted_at is null;

create unique index if not exists uq_saved_view_single_default
  on crm.saved_view (tenant_id, grid_key, owner_id)
  where is_default and deleted_at is null;

-- -----------------------------------------------------------------------------
-- Bulk change log
--
-- A bulk edit that reports only "42 records updated" is unauditable: it cannot
-- answer which 42, what each one held before, or why three were skipped. Every
-- row a bulk operation touches gets an entry here, including the ones it
-- refused, so a partial success is fully explainable afterwards.
--
-- This does not replace governance.audit_event — that still records the change
-- itself. This records the *batch*, so the individual events can be grouped back
-- into the single human action that caused them.
-- -----------------------------------------------------------------------------
create table if not exists crm.bulk_operation (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  object_type    text not null,
  operation      text not null check (operation in ('FIELD_UPDATE', 'REASSIGN', 'DELETE')),
  requested_by   uuid not null references identity.app_user(id),
  requested_at   timestamptz not null default now(),
  -- What was asked for, verbatim, so the request can be read back even if the
  -- field later changes meaning.
  request        jsonb not null default '{}'::jsonb,
  reason         text,
  total          integer not null default 0,
  succeeded      integer not null default 0,
  failed         integer not null default 0,
  correlation_id text
);

create index if not exists idx_bulk_operation_recent
  on crm.bulk_operation (tenant_id, requested_at desc);

create table if not exists crm.bulk_operation_row (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  operation_id   uuid not null references crm.bulk_operation(id) on delete cascade,
  record_id      uuid not null,
  record_label   text,
  outcome        text not null check (outcome in ('APPLIED', 'SKIPPED', 'FAILED')),
  -- Populated for SKIPPED and FAILED. A row that was refused without a stated
  -- reason is indistinguishable from one that was silently lost.
  detail         text,
  before_value   text,
  after_value    text
);

create index if not exists idx_bulk_operation_row_op
  on crm.bulk_operation_row (tenant_id, operation_id);

-- -----------------------------------------------------------------------------
-- Row-level security. Same predicate as every other tenant-scoped table (ADR-001):
-- the tenant on the row must equal the tenant bound to the session.
-- -----------------------------------------------------------------------------
alter table crm.saved_view          enable row level security;
alter table crm.saved_view          force  row level security;
alter table crm.bulk_operation      enable row level security;
alter table crm.bulk_operation      force  row level security;
alter table crm.bulk_operation_row  enable row level security;
alter table crm.bulk_operation_row  force  row level security;

drop policy if exists tenant_isolation on crm.saved_view;
create policy tenant_isolation on crm.saved_view
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

drop policy if exists tenant_isolation on crm.bulk_operation;
create policy tenant_isolation on crm.bulk_operation
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

drop policy if exists tenant_isolation on crm.bulk_operation_row;
create policy tenant_isolation on crm.bulk_operation_row
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

grant select, insert, update, delete on crm.saved_view         to axiom_app;
grant select, insert, update         on crm.bulk_operation     to axiom_app;
grant select, insert                 on crm.bulk_operation_row to axiom_app;

-- The bulk log is evidence: rows are written once and never edited or removed.
-- Withholding UPDATE and DELETE at the grant is what makes that true, rather
-- than a convention the next service to touch this table has to remember.

-- ---------------------------------------------------------------------------
-- E19 — global search index engine (system-design.md §8.2, ADR-003, ADR-005).
--
-- WHAT THIS IS, AND WHAT IT DELIBERATELY IS NOT
-- This is a search index. It is NOT an authorization store. system-design §8.2
-- is explicit: "Access can change faster than an index can be rebuilt, so the
-- index is not treated as authoritative for authorization." So this schema
-- carries tenant_id, owner_id and a set of sharing keys purely as a *query-time
-- narrowing* device, and the application re-checks every returned page against
-- crm/sales tables through AuthorizationService before anything is displayed.
--
-- The invariant that makes that safe, stated once here because every future
-- change to sharing_keys must preserve it:
--
--     THE INDEX FILTER MUST NEVER BE NARROWER THAN TRUE ACCESS.
--
-- A filter that is too wide costs a dropped row on the result page. A filter
-- that is too narrow silently loses a record the user is entitled to find, and
-- nothing downstream can recover it. When in doubt the indexer widens and lets
-- the recheck do the work.
--
-- ADR-005 keeps the search engine open ("Start with PostgreSQL full-text;
-- escalate on measured need") and requires self-hostability. PostgreSQL FTS is
-- both. The Java side sits behind the SearchIndex interface so an OpenSearch
-- implementation is a new class, not a rewrite.
--
-- NOTE ON `nullif(current_setting('app.tenant_id', true), '')::uuid`
-- Repeated from V10/V13 because it is load-bearing. TenantSessionAspect uses
-- set_config(..., true) == SET LOCAL; when that transaction ends PostgreSQL
-- restores the placeholder GUC to the EMPTY STRING, not NULL, and a bare
-- ''::uuid cast raises `invalid input syntax for type uuid: ""` on the next
-- pooled connection. nullif() turns it into NULL, the comparison is NULL, and
-- the row is filtered out — the correct outcome for an unbound connection.
-- ---------------------------------------------------------------------------

create schema if not exists search;
grant usage on schema search to axiom_app;

-- The runtime search_path is a shared resource: other module migrations land in
-- an order this file cannot know. Merge rather than replace (the V70/V90
-- pattern) so a schema added by a migration that ran earlier is not dropped.
do $$
declare
  existing text;
  parts text[];
  merged text[];
begin
  select split_part(cfg, '=', 2) into existing
    from pg_db_role_setting s
    join pg_roles r on r.oid = s.setrole
    cross join unnest(s.setconfig) as cfg
   where r.rolname = 'axiom_app'
     and cfg like 'search_path=%'
   limit 1;

  parts := coalesce(
    (select array_agg(btrim(p)) from unnest(string_to_array(coalesce(existing, 'public'), ',')) p
      where btrim(p) <> ''),
    array['public']::text[]);

  merged := coalesce((select array_agg(p) from unnest(parts) p where p <> 'public'), array[]::text[]);
  if not ('search' = any(merged)) then
    merged := array_append(merged, 'search');
  end if;
  merged := array_append(merged, 'public');
  execute format('alter role axiom_app set search_path to %s', array_to_string(merged, ', '));
end $$;

-- ---------------------------------------------------------------------------
-- The index itself.
--
-- Four text surfaces rather than one, because field-level security (FR-SEC-007)
-- has to be applied per caller at query time and a single blob cannot be
-- partially withheld:
--
--   title           the record's headline — a securable field (weight A)
--   subtitle        one supporting securable field (weight B)
--   body            descriptive columns that are NOT in the field-security
--                   registry, so they are readable by anyone who may read the
--                   record at all (weight C)
--   secured_terms   concatenation of the values in secured_fields, present only
--                   so those values are searchable (weight D)
--   secured_fields  {apiFieldName: value} — the per-field map the query path
--                   uses to rebuild "the text THIS caller may read", so a hit
--                   that matched only on a field the caller cannot read is
--                   dropped and never appears in a snippet.
--
-- `updated_at` is the SOURCE record's updated_at, not ours. That is what makes
-- the indexer safe under the out-of-order delivery ADR-003 permits: an event
-- carrying an older version of a record cannot overwrite a newer document.
-- `indexed_at` is ours, and only ever feeds the staleness display.
-- ---------------------------------------------------------------------------
create table search.search_document (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      uuid not null references platform.tenant(id),
  entity_type    text not null check (entity_type in ('ACCOUNT','CONTACT','LEAD','OPPORTUNITY')),
  entity_id      uuid not null,
  title          text not null,
  subtitle       text,
  body           text,
  secured_terms  text,
  secured_fields jsonb not null default '{}'::jsonb,
  owner_id       uuid,
  -- Owner, role-hierarchy ancestors, share grantees, team members, territories.
  -- Deliberately a superset of true access; see the invariant at the top.
  sharing_keys   uuid[] not null default array[]::uuid[],
  url_path       text not null,
  updated_at     timestamptz not null,
  indexed_at     timestamptz not null default now(),
  document       tsvector generated always as (
                     setweight(to_tsvector('english', coalesce(title, '')), 'A')
                  || setweight(to_tsvector('english', coalesce(subtitle, '')), 'B')
                  || setweight(to_tsvector('english', coalesce(body, '')), 'C')
                  || setweight(to_tsvector('english', coalesce(secured_terms, '')), 'D')
                 ) stored,
  -- This is what makes re-indexing an upsert, and therefore what makes the
  -- indexer idempotent under the at-least-once delivery of ADR-003.
  constraint uq_search_document_entity unique (tenant_id, entity_type, entity_id)
);

create index idx_search_document_fts on search.search_document using gin (document);
create index idx_search_document_type on search.search_document (tenant_id, entity_type);
create index idx_search_document_owner on search.search_document (tenant_id, owner_id);
create index idx_search_document_keys on search.search_document using gin (sharing_keys);
create index idx_search_document_freshness on search.search_document (tenant_id, updated_at desc);

-- ---------------------------------------------------------------------------
-- Outbox consumer cursor. The indexer is an ADR-003 consumer: it reads
-- integration.outbox_event forward from this cursor, per tenant. Ordering is
-- (created_at, id), which preserves the per-record ordering ADR-003 guarantees
-- for partition key (tenant_id, entity_id) without needing a global order.
--
-- `consumer` is named so a second consumer (an OpenSearch mirror during a
-- migration, say) can track its own position without disturbing this one.
-- ---------------------------------------------------------------------------
create table search.index_checkpoint (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references platform.tenant(id),
  consumer        text not null default 'postgres-fts',
  last_event_at   timestamptz not null default '-infinity',
  last_event_id   uuid,
  events_applied  bigint not null default 0,
  documents_written bigint not null default 0,
  documents_removed bigint not null default 0,
  last_error      text,
  updated_at      timestamptz not null default now(),
  constraint uq_index_checkpoint unique (tenant_id, consumer)
);

-- ---------------------------------------------------------------------------
-- Backfill / reindex runs.
--
-- Data model §8's rule for the sharing recompute applies here for the same
-- reason: a full rebuild must never block business writes, and progress must be
-- a real count of real work rather than a spinner. So a run is a queued row,
-- drained in bounded batches by a poller, with `processed_units` updated as it
-- goes and a cursor written after every batch so an API restart resumes from
-- where it stopped instead of starting again.
-- ---------------------------------------------------------------------------
create table search.reindex_run (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references platform.tenant(id),
  -- null = every indexable entity type
  entity_type        text check (entity_type is null or entity_type in ('ACCOUNT','CONTACT','LEAD','OPPORTUNITY')),
  status             text not null default 'QUEUED'
                     check (status in ('QUEUED','RUNNING','COMPLETED','FAILED')),
  reason             text,
  total_units        bigint not null default 0,
  processed_units    bigint not null default 0,
  documents_written  bigint not null default 0,
  documents_removed  bigint not null default 0,
  cursor_entity_type text,
  cursor_entity_id   uuid,
  requested_by       uuid,
  queued_at          timestamptz not null default now(),
  started_at         timestamptz,
  finished_at        timestamptz,
  message            text
);

create index idx_reindex_run_tenant on search.reindex_run (tenant_id, queued_at desc);
create index idx_reindex_run_pending on search.reindex_run (status, queued_at) where status in ('QUEUED','RUNNING');

-- ---------------------------------------------------------------------------
-- Row-level security — the independent second enforcement layer (ADR-001).
-- Applied to every tenant-scoped table in this module, with FORCE so the owner
-- is not exempt.
-- ---------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array[
    'search.search_document',
    'search.index_checkpoint',
    'search.reindex_run'
  ]
  loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format(
      'create policy tenant_isolation on %s '
      'using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
      t);
  end loop;
end
$$;

grant select, insert, update, delete on
  search.search_document, search.index_checkpoint, search.reindex_run
  to axiom_app;

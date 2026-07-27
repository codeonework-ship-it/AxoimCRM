-- P0 CRUD/lifecycle optimistic concurrency. Lead was the only core commercial
-- aggregate without a version token; commands now reject stale edits exactly as
-- Account and Opportunity commands do.
alter table crm.lead add column if not exists version bigint not null default 0;

comment on column crm.lead.version is
  'Optimistic concurrency token incremented by every interactive lifecycle mutation.';

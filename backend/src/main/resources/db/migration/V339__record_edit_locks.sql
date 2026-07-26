-- ============================================================================
-- V339 — Pessimistic record edit locks
--
-- WHY THIS EXISTS ALONGSIDE THE version COLUMN, WHICH ALREADY GUARDS WRITES
--
-- Every mutable record in this product already carries `version` and every
-- update carries `and version = ?`, so two concurrent SAVES cannot silently
-- overwrite one another — the second one is refused with a 409. That is
-- optimistic concurrency and it protects DATA.
--
-- It does not protect WORK. Two people can still open the same opportunity, both
-- spend ten minutes rewriting the close plan, and only discover the collision at
-- the moment one of them presses save — at which point one of them has ten
-- minutes of typing to throw away and re-do against the other's version. On a
-- shared account in a sales team that is a daily occurrence, and the fix is not a
-- better error message: it is telling the second person BEFORE they start.
--
-- So this table is a lease over the act of editing. Optimistic versioning stays
-- exactly as it is — belt and braces, and it remains the only thing that can
-- catch a write from an API client that never asked for a lock.
--
-- WHY A LEASE AND NOT A PLAIN FLAG
--
-- A boolean "locked_by" set on open and cleared on close leaks locks forever the
-- first time somebody closes their laptop, loses connectivity, or force-quits the
-- browser — and there is no event that will ever clear it. Every such design ends
-- up with an administrator manually unlocking records, which is worse than having
-- no locking.
--
-- A lease expires by itself. The holder renews it with a heartbeat while the form
-- is genuinely open; if the heartbeat stops, the lock lapses on its own and the
-- next person takes it. Nothing has to be cleaned up, and no administrator has to
-- be involved. The trade is a bounded window — up to one TTL — where a record
-- looks locked to a colleague after its holder has actually gone. Two minutes of
-- that is a far smaller problem than a permanently stuck record.
--
-- ONE ROW PER RECORD, REUSED
--
-- The unique constraint means acquiring is an upsert, not an insert, so this
-- table's size is bounded by the number of records ever edited rather than by the
-- number of times they were edited. A lock history is a genuinely different
-- question, and it is already answered: every acquire and release is a request,
-- and every request lands in activity.user_activity.
-- ============================================================================

create table crm.record_lock (
  tenant_id     uuid not null references platform.tenant(id),

  -- The object vocabulary the rest of the product already uses — ACCOUNT,
  -- CONTACT, LEAD, OPPORTUNITY, QUOTE — matching activity.user_activity's
  -- object_type and the bulk allow-list, so one name means one thing everywhere.
  object_type   text not null,
  record_id     uuid not null,

  holder_id     uuid not null,
  holder_email  text not null,
  holder_name   text,

  acquired_at   timestamptz not null default now(),
  -- Refreshed by the heartbeat. Kept separate from acquired_at so a reviewer can
  -- see both when the edit started and when the holder was last actually there.
  heartbeat_at  timestamptz not null default now(),
  expires_at    timestamptz not null,

  -- Set when a lock is taken over from an expired holder. Kept rather than
  -- overwritten silently, because "who had this before me" is the first question
  -- asked when two people disagree about what happened to a record.
  stolen_from   uuid,
  stolen_at     timestamptz,

  primary key (tenant_id, object_type, record_id),

  -- A lease with no future is not a lease.
  constraint record_lock_expiry_after_acquire check (expires_at > acquired_at),
  constraint record_lock_object_type_format check (object_type ~ '^[A-Z][A-Z_]{1,39}$')
);

comment on table crm.record_lock is
  'Lease-based edit locks. A row exists only while a record is being edited, and
   is authoritative only while expires_at > now() — an expired row is a tombstone
   that the next acquirer overwrites. Prevents two people editing the same record
   at once; the version column still guards the write itself.';

comment on column crm.record_lock.expires_at is
  'The lease end. Readers MUST compare against now() rather than treating the row
   presence as the lock — a stale row is not a held lock, and code that forgets
   this is how records become permanently unlockable.';

-- The hot query is "is this record locked, and by whom", which the primary key
-- already serves. This index serves the other one: "what does this user currently
-- hold", needed to release a user's locks on sign-out and to show them their own
-- open edits.
create index idx_record_lock_holder on crm.record_lock(tenant_id, holder_id, expires_at desc);

-- Sweeping expired leases is a range scan over expires_at.
create index idx_record_lock_expiry on crm.record_lock(expires_at) where expires_at is not null;

alter table crm.record_lock enable row level security;
alter table crm.record_lock force row level security;
create policy tenant_isolation on crm.record_lock
  using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
  with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

grant select, insert, update, delete on crm.record_lock to axiom_app;

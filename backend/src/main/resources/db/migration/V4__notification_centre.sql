-- Server-backed, tenant/user scoped notification centre MVP.
-- Notification rows contain only in-app summaries and safe internal routes.
-- External delivery remains deferred until RBAC/sharing and channel policy land.

create table notification (
  id                 uuid primary key default gen_random_uuid(),
  tenant_id          uuid not null references tenant(id),
  recipient_user_id  uuid not null references app_user(id),
  kind               text not null check (kind in ('ACTION','SIGNAL','SYSTEM')),
  priority           text not null default 'NORMAL' check (priority in ('URGENT','NORMAL','LOW')),
  title              text not null,
  body               text not null,
  href               text,
  reason             text not null,
  action_required    boolean not null default false,
  action_completed   boolean not null default false,
  read_at            timestamptz,
  occurred_at        timestamptz not null default now(),
  created_at         timestamptz not null default now(),
  constraint notification_href_internal check (href is null or href like '/%')
);

create index idx_notification_recipient_feed
  on notification(tenant_id, recipient_user_id, occurred_at desc);
create index idx_notification_recipient_unread
  on notification(tenant_id, recipient_user_id, occurred_at desc)
  where read_at is null;

alter table notification enable row level security;
alter table notification force row level security;
create policy tenant_isolation on notification
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);
grant select, insert, update, delete on notification to axiom_app;

-- Deterministic preview feed. Priya and Raj receive different rows so
-- user-level scoping is observable in smoke/acceptance tests.
insert into notification
  (id, tenant_id, recipient_user_id, kind, priority, title, body, href, reason,
   action_required, read_at, occurred_at)
values
  ('88888888-8888-8888-8888-888888888801', '11111111-1111-1111-1111-111111111111',
   '22222222-2222-2222-2222-222222222221', 'ACTION', 'URGENT', 'Stage gate armed',
   'Negotiation requires a confirmed economic buyer before entry.', '/pipeline',
   'You own opportunities affected by this stage policy.', true, null, now() - interval '12 minutes'),
  ('88888888-8888-8888-8888-888888888802', '11111111-1111-1111-1111-111111111111',
   '22222222-2222-2222-2222-222222222221', 'SIGNAL', 'NORMAL', 'Close date approaching',
   'Two opportunities close within the next 14 days.', '/pipeline',
   'You own at least one opportunity in this time window.', false, null, now() - interval '44 minutes'),
  ('88888888-8888-8888-8888-888888888803', '11111111-1111-1111-1111-111111111111',
   '22222222-2222-2222-2222-222222222221', 'SYSTEM', 'LOW', 'Welcome to Axiom',
   'Your workspace is provisioned and ready.', '/',
   'This workspace lifecycle event applies to your account.', false, now() - interval '1 day', now() - interval '1 day'),
  ('88888888-8888-8888-8888-888888888804', '11111111-1111-1111-1111-111111111111',
   '22222222-2222-2222-2222-222222222222', 'SYSTEM', 'NORMAL', 'Administration ready',
   'Your administrator workspace has been provisioned.', '/',
   'You are an administrator for this workspace.', false, null, now() - interval '30 minutes');

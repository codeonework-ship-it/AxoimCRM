-- E07 activity and engagement timeline.
-- First-party/manual activities are implemented now. Microsoft/Google calendar,
-- mailbox and telephony connector capture remain deferred integration stories.

create table engagement.activity (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  activity_type text not null check (activity_type in ('TASK','EVENT','CALL','EMAIL_LOG','NOTE')),
  subject text not null,
  body text,
  status text not null default 'OPEN' check (status in ('OPEN','COMPLETED','CANCELLED')),
  priority text not null default 'NORMAL' check (priority in ('LOW','NORMAL','HIGH','URGENT')),
  related_entity_type text not null check (related_entity_type in ('ACCOUNT','CONTACT','LEAD','OPPORTUNITY')),
  related_entity_id uuid not null,
  owner_id uuid not null,
  created_by uuid not null,
  due_at timestamptz,
  reminder_at timestamptz,
  occurred_at timestamptz not null default now(),
  completed_at timestamptz,
  outcome text,
  direction text check (direction is null or direction in ('INBOUND','OUTBOUND')),
  duration_minutes int check (duration_minutes is null or duration_minutes >= 0),
  disposition text,
  source text not null default 'MANUAL' check (source in ('MANUAL','IMPORT','CONNECTOR_QUEUE')),
  deleted_at timestamptz,
  deleted_by uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  constraint fk_activity_owner_same_tenant
    foreign key (tenant_id, owner_id) references identity.app_user(tenant_id, id),
  constraint activity_completed_has_time check (status <> 'COMPLETED' or completed_at is not null),
  constraint activity_task_has_due check (activity_type <> 'TASK' or due_at is not null),
  constraint activity_call_fields check (
    activity_type <> 'CALL' or (direction is not null and duration_minutes is not null and disposition is not null)
  )
);

create table engagement.activity_participant (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  activity_id uuid not null,
  participant_type text not null check (participant_type in ('USER','CONTACT','LEAD','EMAIL')),
  participant_id uuid,
  display_name text not null,
  email text,
  response_status text check (response_status is null or response_status in ('NEEDS_ACTION','ACCEPTED','DECLINED','TENTATIVE')),
  created_at timestamptz not null default now(),
  constraint fk_activity_participant_parent_same_tenant
    foreign key (tenant_id, activity_id) references engagement.activity(tenant_id, id)
);

create index idx_activity_feed on engagement.activity(tenant_id, occurred_at desc) where deleted_at is null;
create index idx_activity_owner_open on engagement.activity(tenant_id, owner_id, due_at) where deleted_at is null and status = 'OPEN';
create index idx_activity_related on engagement.activity(tenant_id, related_entity_type, related_entity_id, occurred_at desc) where deleted_at is null;
create index idx_activity_participant_parent on engagement.activity_participant(tenant_id, activity_id);

alter table engagement.activity enable row level security;
alter table engagement.activity force row level security;
create policy tenant_isolation on engagement.activity
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

alter table engagement.activity_participant enable row level security;
alter table engagement.activity_participant force row level security;
create policy tenant_isolation on engagement.activity_participant
  using (tenant_id = current_setting('app.tenant_id', true)::uuid)
  with check (tenant_id = current_setting('app.tenant_id', true)::uuid);

grant select, insert, update on engagement.activity, engagement.activity_participant to axiom_app;

insert into governance.screen_catalog(screen_code, module_code, route, display_name, description, sort_order) values
  ('ACTIVITIES', 'ENGAGEMENT', '/activities', 'Activities', 'Tasks, events, calls, notes and manual email engagement timeline.', 55)
on conflict (screen_code) do nothing;

insert into governance.rbac_policy(role_code, screen_code, can_read, can_write, can_export, can_admin, scope)
select role_code, 'ACTIVITIES',
       role_code <> 'INTEGRATION',
       role_code not in ('SUPER_AUDIT','AUDITOR','INTEGRATION'),
       role_code <> 'INTEGRATION',
       role_code in ('SUPER_ADMIN','TENANT_ADMIN'),
       case when role_code in ('SUPER_ADMIN','SUPER_AUDIT') then 'PLATFORM' else 'TENANT' end
from (values
  ('SUPER_ADMIN'),('SUPER_AUDIT'),('TENANT_ADMIN'),('SALES_MANAGER'),('SALES'),
  ('MARKETING'),('SERVICE'),('OPERATIONS'),('FINANCE'),('DATA_STEWARD'),('AUDITOR'),('INTEGRATION')
) roles(role_code)
on conflict (role_code, screen_code) do update
  set can_read = excluded.can_read,
      can_write = excluded.can_write,
      can_export = excluded.can_export,
      can_admin = excluded.can_admin,
      scope = excluded.scope;

insert into governance.module_table_catalog(schema_name, table_name, module_code, primary_key, tenant_scoped, lifecycle) values
  ('engagement','activity','ENGAGEMENT','id',true,'SOFT_DELETE'),
  ('engagement','activity_participant','ENGAGEMENT','id',true,'ACTIVE')
on conflict (schema_name, table_name) do nothing;

insert into reference.value_set (tenant_id, api_name, label, module, description)
select t.id, seed.api_name, seed.label, seed.module, seed.description
from platform.tenant t
cross join (values
  ('activity_type', 'Activity type', 'ENGAGEMENT', 'Task, event, call, manual email and note activity types'),
  ('activity_priority', 'Activity priority', 'ENGAGEMENT', 'Priority values for activity planning'),
  ('call_disposition', 'Call disposition', 'ENGAGEMENT', 'Governed call outcome values')
) as seed(api_name, label, module, description)
on conflict (tenant_id, api_name) do nothing;

insert into reference.value_set_entry (tenant_id, value_set_id, code, label, sort_order, system_managed)
select vs.tenant_id, vs.id, seed.code, seed.label, seed.sort_order, true
from reference.value_set vs
join (values
  ('activity_type', 'TASK', 'Task', 10),
  ('activity_type', 'EVENT', 'Event / meeting', 20),
  ('activity_type', 'CALL', 'Call', 30),
  ('activity_type', 'EMAIL_LOG', 'Email log', 40),
  ('activity_type', 'NOTE', 'Note', 50),
  ('activity_priority', 'LOW', 'Low', 10),
  ('activity_priority', 'NORMAL', 'Normal', 20),
  ('activity_priority', 'HIGH', 'High', 30),
  ('activity_priority', 'URGENT', 'Urgent', 40),
  ('call_disposition', 'CONNECTED', 'Connected', 10),
  ('call_disposition', 'LEFT_VOICEMAIL', 'Left voicemail', 20),
  ('call_disposition', 'NO_ANSWER', 'No answer', 30),
  ('call_disposition', 'FOLLOW_UP_REQUIRED', 'Follow-up required', 40)
) as seed(api_name, code, label, sort_order) on seed.api_name = vs.api_name
on conflict (tenant_id, value_set_id, code) do nothing;

-- Seed all demo tenants with usable activity and transaction-style timeline data.
insert into engagement.activity
  (id, tenant_id, activity_type, subject, body, status, priority, related_entity_type,
   related_entity_id, owner_id, created_by, due_at, reminder_at, occurred_at,
   completed_at, outcome, direction, duration_minutes, disposition)
select 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa01'::uuid, t.id, 'TASK',
       'Confirm economic buyer coverage',
       'Review all opportunities entering gated stages and record missing buying group roles.',
       'OPEN', 'HIGH', 'OPPORTUNITY', '66666666-6666-6666-6666-666666666604'::uuid,
       u.id, u.id, now() + interval '1 day', now() + interval '20 hours', now() - interval '2 hours',
       null, null, null, null, null
from platform.tenant t
join identity.app_user u on u.tenant_id = t.id
where t.slug = 'meridian' and u.email = 'priya.nair@meridianfab.com'
on conflict (tenant_id, id) do nothing;

insert into engagement.activity
  (id, tenant_id, activity_type, subject, body, status, priority, related_entity_type,
   related_entity_id, owner_id, created_by, due_at, reminder_at, occurred_at,
   completed_at, outcome, direction, duration_minutes, disposition)
select 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa02'::uuid, t.id, 'CALL',
       'Discovery call with Kestrel CFO',
       'Confirmed budget owner and renewal timing.',
       'COMPLETED', 'NORMAL', 'ACCOUNT', '44444444-4444-4444-4444-444444444401'::uuid,
       u.id, u.id, now() - interval '1 day', null, now() - interval '1 day',
       now() - interval '1 day' + interval '30 minutes', 'Economic buyer confirmed', 'OUTBOUND', 30, 'CONNECTED'
from platform.tenant t
join identity.app_user u on u.tenant_id = t.id
where t.slug = 'meridian' and u.email = 'priya.nair@meridianfab.com'
on conflict (tenant_id, id) do nothing;

insert into engagement.activity
  (id, tenant_id, activity_type, subject, body, status, priority, related_entity_type,
   related_entity_id, owner_id, created_by, due_at, reminder_at, occurred_at)
select 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa03'::uuid, t.id, 'EVENT',
       'Northstar implementation walkthrough',
       'Demo tenant onboarding meeting with solution review.',
       'OPEN', 'NORMAL', 'ACCOUNT', '33333333-3333-3333-3333-333333333310'::uuid,
       u.id, u.id, now() + interval '3 days', now() + interval '2 days', now() + interval '3 days'
from platform.tenant t
join identity.app_user u on u.tenant_id = t.id
where t.slug = 'northstar' and u.email = 'ava.chen@northstar.example'
on conflict (tenant_id, id) do nothing;

insert into engagement.activity
  (id, tenant_id, activity_type, subject, body, status, priority, related_entity_type,
   related_entity_id, owner_id, created_by, due_at, reminder_at, occurred_at, completed_at, outcome)
select 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa04'::uuid, t.id, 'EMAIL_LOG',
       'Sent proposal follow-up',
       'Manual email log: proposal recap and next-step confirmation sent to buying committee.',
       'COMPLETED', 'NORMAL', 'LEAD', '77777777-7777-7777-7777-777777777702'::uuid,
       u.id, u.id, now() - interval '12 hours', null, now() - interval '12 hours',
       now() - interval '12 hours', 'Proposal follow-up sent'
from platform.tenant t
join identity.app_user u on u.tenant_id = t.id
where t.slug = 'meridian' and u.email = 'priya.nair@meridianfab.com'
on conflict (tenant_id, id) do nothing;

insert into engagement.activity_participant
  (tenant_id, activity_id, participant_type, participant_id, display_name, email, response_status)
select a.tenant_id, a.id, 'CONTACT', c.id, c.first_name || ' ' || c.last_name, c.email, 'ACCEPTED'
from engagement.activity a
join crm.contact c on c.tenant_id = a.tenant_id and c.email = 'd.farrow@kestrelmfg.com'
where a.id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa02'
on conflict do nothing;

insert into engagement.notification
  (tenant_id, recipient_user_id, kind, priority, title, body, href, reason, action_required, occurred_at)
select a.tenant_id, a.owner_id, 'ACTION', 'NORMAL', 'Upcoming activity reminder',
       a.subject, '/activities', 'This task has a reminder due soon.', true, now()
from engagement.activity a
where a.id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa01'
on conflict do nothing;

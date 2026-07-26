-- E15 Reporting Studio: user-authored dashboards, collaboration, embedded views
-- and governed delivery policies. Query definitions remain structured JSON and
-- are always revalidated by ReportQueryService before execution.

alter table analytics.report_view
  add constraint uq_report_view_tenant_id unique (tenant_id, id);

alter table reporting.analytics_dashboard
  add column if not exists description text,
  add column if not exists layout_mode text not null default 'GRID'
    check (layout_mode in ('GRID','FREEFORM')),
  add column if not exists audience text not null default 'PRIVATE'
    check (audience in ('PRIVATE','SHARED','TENANT')),
  add column if not exists updated_at timestamptz not null default now();

alter table reporting.dashboard_widget
  drop constraint if exists dashboard_widget_visualization_type_check;
alter table reporting.dashboard_widget
  add constraint dashboard_widget_visualization_type_check check
    (visualization_type in ('KPI','BAR','LINE','AREA','DONUT','FUNNEL','TABLE','PIVOT','SUMMARY')),
  add column if not exists report_view_id uuid,
  add column if not exists layout_x int not null default 0 check (layout_x between 0 and 11),
  add column if not exists layout_y int not null default 0 check (layout_y >= 0),
  add column if not exists layout_width int not null default 4 check (layout_width between 1 and 12),
  add column if not exists layout_height int not null default 3 check (layout_height between 1 and 12),
  add column if not exists configuration jsonb not null default '{}'::jsonb,
  add constraint fk_dashboard_widget_report_same_tenant foreign key (tenant_id, report_view_id)
    references analytics.report_view(tenant_id, id);

create table analytics.report_share (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  report_view_id uuid,
  dashboard_id uuid,
  principal_type text not null check (principal_type in ('USER','ROLE','TENANT')),
  principal_key text not null,
  permission text not null check (permission in ('VIEW','EDIT')),
  created_by uuid,
  created_at timestamptz not null default now(),
  revoked_at timestamptz,
  unique (tenant_id, id),
  constraint report_share_one_target check
    ((report_view_id is not null)::int + (dashboard_id is not null)::int = 1),
  constraint fk_report_share_report_same_tenant foreign key (tenant_id, report_view_id)
    references analytics.report_view(tenant_id, id),
  constraint fk_report_share_dashboard_same_tenant foreign key (tenant_id, dashboard_id)
    references reporting.analytics_dashboard(tenant_id, id)
);

create table analytics.report_comment (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  report_view_id uuid,
  dashboard_id uuid,
  body text not null check (length(trim(body)) between 1 and 2000),
  created_by uuid,
  created_at timestamptz not null default now(),
  resolved_at timestamptz,
  unique (tenant_id, id),
  constraint report_comment_one_target check
    ((report_view_id is not null)::int + (dashboard_id is not null)::int = 1),
  constraint fk_report_comment_report_same_tenant foreign key (tenant_id, report_view_id)
    references analytics.report_view(tenant_id, id),
  constraint fk_report_comment_dashboard_same_tenant foreign key (tenant_id, dashboard_id)
    references reporting.analytics_dashboard(tenant_id, id)
);

create table analytics.delivery_policy (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  target_type text not null check (target_type in ('REPORT','DASHBOARD')),
  target_code text not null,
  name text not null,
  artifact_format text not null check (artifact_format in ('PDF','XLSX','DOCX','LINK')),
  frequency text not null check (frequency in ('DAILY','WEEKLY','MONTHLY','THRESHOLD')),
  recipients text[] not null check (cardinality(recipients) > 0),
  threshold_metric_code text,
  threshold_operator text check (threshold_operator in ('GT','GTE','LT','LTE','EQ')),
  threshold_value numeric,
  enabled boolean not null default true,
  next_run_at timestamptz not null,
  last_evaluated_value numeric,
  last_triggered_at timestamptz,
  delivery_state text not null default 'PENDING_ADAPTER'
    check (delivery_state in ('PENDING_ADAPTER','QUEUED','GENERATED','FAILED')),
  created_by uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, name),
  constraint threshold_policy_complete check (
    (frequency <> 'THRESHOLD' and threshold_metric_code is null and threshold_operator is null and threshold_value is null)
    or
    (frequency = 'THRESHOLD' and threshold_metric_code is not null and threshold_operator is not null and threshold_value is not null)
  )
);

create table analytics.embed_view (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  target_type text not null check (target_type in ('REPORT','DASHBOARD')),
  target_code text not null,
  embed_code text not null check (embed_code ~ '^[a-z][a-z0-9_-]*$'),
  allowed_origins text[] not null default '{}',
  require_login boolean not null default true,
  active boolean not null default true,
  created_by uuid,
  created_at timestamptz not null default now(),
  revoked_at timestamptz,
  unique (tenant_id, id),
  unique (tenant_id, embed_code),
  constraint authenticated_embed_only check (require_login = true)
);

create index idx_report_share_target on analytics.report_share
  (tenant_id, report_view_id, dashboard_id) where revoked_at is null;
create index idx_report_comment_target on analytics.report_comment
  (tenant_id, report_view_id, dashboard_id, created_at desc);
create index idx_delivery_policy_due on analytics.delivery_policy
  (tenant_id, enabled, next_run_at);
create index idx_dashboard_widget_layout on reporting.dashboard_widget
  (tenant_id, dashboard_id, layout_y, layout_x);

do $$
declare t text;
begin
  foreach t in array array[
    'analytics.report_share', 'analytics.report_comment',
    'analytics.delivery_policy', 'analytics.embed_view'
  ] loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);
    execute format(
      'create policy tenant_isolation on %s using '
      '(tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
      'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
  end loop;
end $$;

grant select, insert, update on analytics.report_share, analytics.report_comment,
  analytics.delivery_policy, analytics.embed_view to axiom_app;
grant select, insert, update on reporting.analytics_dashboard, reporting.dashboard_widget to axiom_app;

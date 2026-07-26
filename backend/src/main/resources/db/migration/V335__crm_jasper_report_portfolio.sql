-- CRM Jasper report portfolio.
--
-- A report catalogue must describe the decision a report supports, not only
-- its implementation name. These fields let the UI organize reports by
-- business outcome and explain who should use each one.
alter table reporting.report_definition
  add column if not exists category text not null default 'GENERAL',
  add column if not exists business_question text not null default '',
  add column if not exists audience text[] not null default '{}',
  add column if not exists sort_order integer not null default 100;

alter table reporting.report_definition
  drop constraint if exists report_definition_category_allowed;
alter table reporting.report_definition
  add constraint report_definition_category_allowed check (
    category in ('EXECUTIVE', 'SALES', 'GROWTH', 'CUSTOMER', 'COMMERCIAL', 'GOVERNANCE', 'GENERAL')
  );

create index if not exists idx_report_definition_catalogue
  on reporting.report_definition(tenant_id, category, sort_order, label)
  where active = true;

insert into reporting.report_definition
  (tenant_id, code, label, description, template_path, allowed_formats, active,
   category, business_question, audience, sort_order)
select t.id, seed.code, seed.label, seed.description,
       'reports/crm-insight-report.jrxml', array['PDF','XLSX','DOCX'], true,
       seed.category, seed.business_question, seed.audience, seed.sort_order
from platform.tenant t
cross join (values
  ('tenant_summary', 'Revenue Command Summary',
   'A one-page CRM operating summary covering customers, demand and open revenue.',
   'EXECUTIVE', 'What is the current commercial position of this company?',
   array['CRO','CEO','REVOPS']::text[], 10),
  ('pipeline_snapshot', 'Pipeline by Stage',
   'Open opportunity value and deal volume arranged in selling-stage order.',
   'SALES', 'Where is open pipeline concentrated today?',
   array['CRO','SALES_MANAGER','SALES']::text[], 20),
  ('forecast_commitment', 'Forecast Commitment',
   'Open pipeline grouped by forecast category with weighted value.',
   'SALES', 'How much pipeline is commit, best case, pipeline or omitted?',
   array['CRO','SALES_MANAGER','FINANCE']::text[], 30),
  ('pipeline_aging_risk', 'Pipeline Aging and Risk',
   'Stage-level stale-deal and overdue-close-date exposure.',
   'SALES', 'Which stages contain revenue that is stalled or already overdue?',
   array['CRO','SALES_MANAGER','REVOPS']::text[], 40),
  ('win_loss_analysis', 'Win and Loss Analysis',
   'Closed outcome value, volume and average sales-cycle duration.',
   'SALES', 'What did we win or lose, and how long did those decisions take?',
   array['CRO','SALES_MANAGER','REVOPS']::text[], 50),
  ('lead_conversion_funnel', 'Lead Conversion Funnel',
   'Lead volume and converted volume by current lifecycle status.',
   'GROWTH', 'Where are prospects accumulating or leaving the demand funnel?',
   array['MARKETING','SALES_MANAGER','REVOPS']::text[], 60),
  ('lead_source_conversion', 'Lead Source Conversion',
   'Demand source volume, converted leads and conversion percentage.',
   'GROWTH', 'Which sources create leads that actually convert?',
   array['MARKETING','CRO','REVOPS']::text[], 70),
  ('sales_activity_productivity', 'Sales Activity Productivity',
   'Activity volume, completion and time invested by activity type.',
   'SALES', 'Is the team completing the customer work it creates?',
   array['SALES_MANAGER','SALES','REVOPS']::text[], 80),
  ('account_health_portfolio', 'Account Health Portfolio',
   'Customer count, revenue exposure and average score by health band.',
   'CUSTOMER', 'How much customer value is healthy, watch-listed or at risk?',
   array['SERVICE','CRO','ACCOUNT_MANAGER']::text[], 90),
  ('customer_service_sla', 'Customer Service SLA',
   'Case volume and overdue response or resolution milestones by priority.',
   'CUSTOMER', 'Where are customer commitments currently at risk of breach?',
   array['SERVICE','SERVICE_MANAGER','AUDITOR']::text[], 100),
  ('quote_conversion_margin', 'Quote Conversion and Margin',
   'Quote volume, commercial value and average margin by quote status.',
   'COMMERCIAL', 'What is the value and margin posture of issued commercial offers?',
   array['FINANCE','SALES_MANAGER','OPERATIONS']::text[], 110),
  ('campaign_roi', 'Campaign Return and Response',
   'Campaign budget, influenced pipeline, audience response and indicative return.',
   'GROWTH', 'Which campaigns are producing engagement and influenced pipeline?',
   array['MARKETING','CRO','FINANCE']::text[], 120),
  ('data_quality_exceptions', 'CRM Data Quality Exceptions',
   'Actionable counts of missing ownership, contactability and process-critical fields.',
   'GOVERNANCE', 'Which missing CRM data can make forecasts, routing or follow-up unreliable?',
   array['REVOPS','DATA_STEWARD','AUDITOR']::text[], 130)
) as seed(code, label, description, category, business_question, audience, sort_order)
on conflict (tenant_id, code) do update set
  label = excluded.label,
  description = excluded.description,
  template_path = excluded.template_path,
  allowed_formats = excluded.allowed_formats,
  active = true,
  category = excluded.category,
  business_question = excluded.business_question,
  audience = excluded.audience,
  sort_order = excluded.sort_order,
  updated_at = now();

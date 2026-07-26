-- Advanced CRM Jasper report portfolio.
--
-- These definitions deliberately point at the same generic, governed Jasper
-- template as the original portfolio.  The Java query contract supplies the
-- report-specific headings and tenant-scoped rows, so PDF, Excel, Word and the
-- browser preview cannot drift into four different interpretations.

insert into reporting.report_definition
  (tenant_id, code, label, description, template_path, allowed_formats, active,
   category, business_question, audience, sort_order)
select t.id, seed.code, seed.label, seed.description,
       'reports/crm-insight-report.jrxml', array['PDF','XLSX','DOCX'], true,
       seed.category, seed.business_question, seed.audience, seed.sort_order
from platform.tenant t
cross join (values
  ('quota_attainment', 'Quota Attainment by Representative and Territory',
   'Revenue attainment against the current governed quota version for each representative or territory.',
   'SALES', 'Who is ahead of quota, who has a gap, and which governed target was used?',
   array['CRO','SALES_MANAGER','FINANCE','REVOPS']::text[], 140),
  ('forecast_accuracy_bias', 'Forecast Accuracy and Directional Bias',
   'Submitted forecasts compared with closed-won actuals in the same owner and reporting period.',
   'SALES', 'How accurate are submitted forecasts, and do they consistently overstate or understate outcomes?',
   array['CRO','SALES_MANAGER','FINANCE','REVOPS']::text[], 150),
  ('stage_conversion_velocity', 'Stage Conversion and Sales Velocity',
   'Cohort-based stage entries, forward exits and elapsed selling time from append-only stage history.',
   'SALES', 'Where does pipeline convert, stall or consume the most selling time?',
   array['CRO','SALES_MANAGER','REVOPS']::text[], 160),
  ('renewal_arr_bridge', 'Renewal, Churn and ARR Movement Bridge',
   'Opening, new, churned, renewal-due and closing annual recurring revenue from governed subscriptions.',
   'CUSTOMER', 'What changed recurring revenue, and how much ARR is approaching renewal?',
   array['CRO','FINANCE','ACCOUNT_MANAGER','SERVICE_MANAGER']::text[], 170),
  ('pipeline_movement_waterfall', 'Pipeline Movement Waterfall',
   'An exactly reconciling pipeline comparison using append-only opportunity state history.',
   'SALES', 'What was added, grown, shrunk, won, lost or removed from pipeline during the period?',
   array['CRO','SALES_MANAGER','REVOPS','FINANCE']::text[], 180),
  ('account_whitespace', 'Account Whitespace and Cross-Sell Opportunity',
   'Active catalogue products absent from each account current subscriptions and open opportunity lines.',
   'CUSTOMER', 'Which active products are not yet represented in each customer relationship?',
   array['ACCOUNT_MANAGER','SALES','SALES_MANAGER','REVOPS']::text[], 190),
  ('customer_360_brief', 'Customer 360 Executive Brief',
   'One-row-per-account executive view of health, ARR, pipeline, service demand, contacts and renewal timing.',
   'EXECUTIVE', 'What is the complete commercial and service posture of each customer account?',
   array['CEO','CRO','ACCOUNT_MANAGER','SERVICE_MANAGER']::text[], 200),
  ('discount_approval_governance', 'Discount Leakage and Approval Governance',
   'Active quote discount, margin and approval posture with exceptions surfaced for review.',
   'GOVERNANCE', 'Where are discounts reducing commercial value without adequate approval or margin protection?',
   array['FINANCE','SALES_MANAGER','OPERATIONS','AUDITOR']::text[], 210)
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

-- The demonstration workspace already contains governed forecasts and selling
-- activity but predates quota seeding.  Add explicit current-quarter targets
-- only for opportunity owners in that workspace.  Production tenants are not
-- silently assigned synthetic quotas.
insert into orgdata.quota
  (tenant_id, subject_type, subject_id, subject_label, fiscal_period_id,
   measure, target_amount, currency_code, version_no, is_current, change_reason)
select t.id, 'USER', u.id, u.display_name, p.id, 'REVENUE',
       case u.email
         when 'priya.nair@meridianfab.com' then 750000.00
         when 'maya.torres@meridianfab.com' then 600000.00
         else 500000.00
       end,
       'USD', 1, true, 'Demonstration quota for the advanced Jasper portfolio.'
from platform.tenant t
join identity.app_user u on u.tenant_id = t.id
join orgdata.fiscal_period p on p.tenant_id = t.id
  and p.period_type = 'QUARTER' and current_date between p.start_date and p.end_date
where t.slug = 'meridian'
  and u.email in ('priya.nair@meridianfab.com', 'maya.torres@meridianfab.com')
on conflict (tenant_id, subject_type, subject_id, fiscal_period_id, measure)
  where is_current do nothing;

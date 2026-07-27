-- Expand the governed documentation drawer with product-wide workflows,
-- report formula guidance, screen impact and data lineage. English is the
-- authoritative fallback for locales whose reviewed translation is pending.

create temporary table seed_manual_section(
  code text primary key, section_type text, sort_order integer, heading text
);
insert into seed_manual_section values
  ('DATA_LIFECYCLE','STEPS',60,'How data reaches a report'),
  ('SCREEN_IMPACT','STEPS',70,'Modules and their impact'),
  ('KPI_FORMULAS','STEPS',80,'How important numbers are calculated'),
  ('WORKFLOW_GATES','STEPS',90,'Workflow gates and next steps'),
  ('GRID_DRAWER_CONTROLS','STEPS',100,'Data grids and drawers');

insert into documentation.drawer_section(tenant_id, drawer_id, code, section_type, sort_order)
select d.tenant_id, d.id, seed.code, seed.section_type, seed.sort_order
from documentation.drawer_master d cross join seed_manual_section seed
where d.code='USER_MANUAL'
on conflict (tenant_id, drawer_id, code) do update
set section_type=excluded.section_type, sort_order=excluded.sort_order, active=true, updated_at=now();

insert into documentation.drawer_section_translation(tenant_id, section_id, locale_code, heading)
select section.tenant_id, section.id, 'en', seed.heading
from documentation.drawer_section section
join seed_manual_section seed on seed.code=section.code
on conflict (tenant_id, section_id, locale_code) do update set heading=excluded.heading;

create temporary table seed_manual_entry(
  section_code text, code text, marker text, sort_order integer, title text, body text,
  primary key(section_code,code)
);
insert into seed_manual_entry values
('DATA_LIFECYCLE','CREATE_RECORD','01',10,'Create or change the business record','A user, API, import or automation submits data. Axiom checks tenant, role, record access, validation, workflow state and record lock before it accepts the change.'),
('DATA_LIFECYCLE','ATOMIC_EVIDENCE','02',20,'Save record, audit and event together','The business row, immutable audit event and outbox event commit together. If one fails, none remain, preventing a report from showing an uncommitted change.'),
('DATA_LIFECYCLE','PROJECT_EVENT','03',30,'Update the reporting projection','The internal event updates tenant-scoped analytics facts. These are report-friendly copies; operational CRM tables remain authoritative.'),
('DATA_LIFECYCLE','QUERY_REPORT','04',40,'Run one governed report query','Report Grid, Jasper PDF, Excel and Word use the same search, filter, ordering and permission contract. The grid requests 100 rows at a time.'),
('DATA_LIFECYCLE','RECHECK_DRILL','05',50,'Recheck access on drill-through','Selecting an aggregate checks the source record permission again. The reporting copy never grants record access.'),
('DATA_LIFECYCLE','RECONCILE','06',60,'Reconcile the two views','Axiom independently compares projections and KPIs with authoritative recomputation. Any drift blocks production certification.'),

('SCREEN_IMPACT','LEADS_IMPACT','01',10,'Leads','Captures demand. Qualification and conversion can create or link Account, Contact and Opportunity records and feed funnel/source reports. Disqualification requires a reason.'),
('SCREEN_IMPACT','ACCOUNT_IMPACT','02',20,'Accounts and Account 360','Owns customer identity, hierarchy, health and ownership. It affects pipeline, service, renewals, sharing and Customer 360 reporting.'),
('SCREEN_IMPACT','CONTACT_IMPACT','03',30,'Contacts','Stores people and their customer relationships. It affects buying groups, engagement timelines, quotes and relationship reporting.'),
('SCREEN_IMPACT','PIPELINE_IMPACT','04',40,'Pipeline and forecast','Opportunity amount, stage, probability and close date drive weighted pipeline, coverage, velocity, win rate, forecast and movement reports.'),
('SCREEN_IMPACT','ACTIVITY_IMPACT','05',50,'Activities','Tasks, events, calls, notes and email logs drive engagement recency, productivity, timelines and next actions.'),
('SCREEN_IMPACT','CPQ_IMPACT','06',60,'Products, price books and quotes','Catalogue, price and discount changes affect quote totals, approvals, margin analysis, contracts and whitespace reporting.'),
('SCREEN_IMPACT','CONTRACT_IMPACT','07',70,'Contracts and renewals','Contract terms and subscriptions drive ACV, ARR, TCV, churn, renewal alerts and billing handoff.'),
('SCREEN_IMPACT','SERVICE_IMPACT','08',80,'Campaigns, cases and partners','Campaigns affect attribution/ROI; cases affect SLA and health; partner registrations affect channel credit and conflict.'),
('SCREEN_IMPACT','REPORT_IMPACT','09',90,'Reports and analytics','Definitions, formulas, joins, pivots and dashboards create governed decision views. They never bypass source permissions.'),
('SCREEN_IMPACT','ADMIN_IMPACT','10',100,'Administration and security','Users, RBAC, trials, company status, billing, alerts and documentation govern every module. Sensitive changes require approval and audit.'),
('SCREEN_IMPACT','OPERATIONS_IMPACT','11',110,'Automation, migration and integrations','Automation follows server gates; migration owns an exact rollback ledger; integrations isolate and retry external delivery.'),
('SCREEN_IMPACT','VERTICAL_IMPACT','12',120,'Mobile, BFSI and commodity','Offline packages synchronize with conflict control. BFSI and commodity workflows add regulated gates without replacing authoritative banking, trading or risk systems.'),

('KPI_FORMULAS','HEALTH_FORMULA','01',10,'Account health','Weighted score = sum(factor weight times factor score) divided by sum of active weights. Bands: Strong 80+, Steady 65+, Watch 50+, At Risk 35+, otherwise Critical.'),
('KPI_FORMULAS','WEIGHTED_AMOUNT','02',20,'Weighted opportunity amount','Amount times probability divided by 100. It changes when amount, probability/stage or the stored currency conversion changes.'),
('KPI_FORMULAS','COVERAGE_FORMULA','03',30,'Pipeline coverage','Open pipeline value closing in the period divided by remaining quota for that period.'),
('KPI_FORMULAS','VELOCITY_FORMULA','04',40,'Sales velocity','Open qualified opportunity count times average deal size times win rate, divided by average sales-cycle days.'),
('KPI_FORMULAS','CONVERSION_FORMULA','05',50,'Stage conversion','Forward exits from a stage divided by entries into that stage, using the stage-entry cohort.'),
('KPI_FORMULAS','WIN_RATE_FORMULA','06',60,'Win rate','Closed-won count divided by closed-won plus closed-lost count. No-decision/disqualified records are excluded and named.'),
('KPI_FORMULAS','ARR_FORMULA','07',70,'ACV, ARR and TCV','ACV annualizes recurring contract value; ARR sums annualized active subscriptions at one date; TCV sums recurring plus one-time value over the full term.'),
('KPI_FORMULAS','ATTAINMENT_FORMULA','08',80,'Quota attainment','Credited closed revenue in the period divided by the versioned assigned quota.'),
('KPI_FORMULAS','FORECAST_FORMULA','09',90,'Forecast accuracy and bias','Accuracy is 1 minus absolute actual-minus-submitted divided by actual. Bias is the mean signed submitted-minus-actual divided by actual.'),
('KPI_FORMULAS','CAMPAIGN_FORMULA','10',100,'Campaign ROI','Attributed closed revenue minus actual campaign cost, divided by actual campaign cost. The attribution model is always named.'),
('KPI_FORMULAS','MISSING_INPUT','11',110,'Missing or zero inputs','Axiom shows Not computable and names the missing input when a denominator is zero or authoritative data is absent; it does not invent a flattering zero.'),

('WORKFLOW_GATES','LEAD_GATE','01',10,'Lead gate','Captured to validated to qualified/disqualified to converted. Conversion creates its linked records atomically.'),
('WORKFLOW_GATES','OPPORTUNITY_GATE','02',20,'Opportunity gate','Qualifying to proposal to negotiation to commit to closed. Buying roles, amount, close date and stage evidence are enforced.'),
('WORKFLOW_GATES','QUOTE_GATE','03',30,'Quote and contract gate','Draft, price, approve, issue and accept before contract activation. Discount and margin exceptions cannot bypass approval.'),
('WORKFLOW_GATES','FORECAST_GATE','04',40,'Forecast gate','Open to submitted to locked to reviewed. Corrections remain movements rather than rewriting the locked submission.'),
('WORKFLOW_GATES','CUSTOMER_GATE','05',50,'Campaign, case and partner gates','Campaign completion protects ROI, case lifecycle protects SLA evidence, and partner approval protects channel credit.'),
('WORKFLOW_GATES','AUTOMATION_GATE','06',60,'Automation gate','Draft to validated to simulated to active. Dry-run writes nothing and four-eyes approval prevents self-approval.'),
('WORKFLOW_GATES','MIGRATION_GATE','07',70,'Migration gate','Discover, map, validate, import/delta, reconcile and rollback. Checkpoints support retries; rollback removes only migration-owned data.'),
('WORKFLOW_GATES','VERTICAL_GATE','08',80,'BFSI and commodity gates','Onboarding/screening/approval and enquiry/price/term-sheet/handoff keep exceptions and external-system dependencies visible.'),

('GRID_DRAWER_CONTROLS','LOAD_CONTROL','01',10,'Load Screen Data','The page does not request business data until selected. It then retrieves the first 100 server-filtered rows and active-screen supporting data.'),
('GRID_DRAWER_CONTROLS','FILTER_CONTROL','02',20,'Search, filter and group','Search/filters run on the server. Group exposes eligible column checkboxes and can combine columns in selected order.'),
('GRID_DRAWER_CONTROLS','EXPORT_CONTROL','03',30,'Audit and export','Audit opens immutable evidence. Excel, Word and PDF use the same complete filtered dataset; export permission is separate from read.'),
('GRID_DRAWER_CONTROLS','FULL_CONTROL','04',40,'Full and restore view','The complete grid utility remains available in full view and returns with its filters, grouping and page state intact.'),
('GRID_DRAWER_CONTROLS','DRAWER_CONTROL','05',50,'Docked drawers','Account 360 and User Manual dock on the right, resize from the left edge, expand to full view, restore, close with the button or Escape, and remain usable on small screens.'),
('GRID_DRAWER_CONTROLS','ROW_CONTROL','06',60,'Row action buttons','Open, Edit, Clone, status and Delete are bordered controls. Delete is a governed soft delete; referenced master records are protected.');

insert into documentation.drawer_entry(tenant_id, section_id, code, marker, sort_order)
select section.tenant_id, section.id, seed.code, seed.marker, seed.sort_order
from documentation.drawer_section section
join seed_manual_entry seed on seed.section_code=section.code
on conflict (tenant_id, section_id, code) do update
set marker=excluded.marker, sort_order=excluded.sort_order, active=true, updated_at=now();

insert into documentation.drawer_entry_translation(tenant_id, entry_id, locale_code, title, body)
select entry.tenant_id, entry.id, 'en', seed.title, seed.body
from documentation.drawer_entry entry
join documentation.drawer_section section on section.tenant_id=entry.tenant_id and section.id=entry.section_id
join seed_manual_entry seed on seed.section_code=section.code and seed.code=entry.code
on conflict (tenant_id, entry_id, locale_code) do update set title=excluded.title, body=excluded.body;

update documentation.drawer_master
set current_version=current_version+1, updated_at=now()
where code='USER_MANUAL';

insert into documentation.drawer_revision(tenant_id,drawer_id,version_no,snapshot,change_note)
select tenant_id,id,current_version,
       jsonb_build_object('seed','expanded_product_manual','sectionCount',5,'entryCount',(select count(*) from seed_manual_entry)),
       'Expanded product workflows, screen impact, formulas and data lineage'
from documentation.drawer_master where code='USER_MANUAL'
on conflict do nothing;

drop table seed_manual_entry;
drop table seed_manual_section;


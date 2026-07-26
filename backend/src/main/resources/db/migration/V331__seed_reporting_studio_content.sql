-- Inspectable starter content for the report builder. These are normal saved
-- definitions, not special screens, so users can clone and edit every field.
insert into analytics.report_view
  (tenant_id, code, name, description, dataset, format, definition, created_by)
select t.id, seed.code, seed.name, seed.description, seed.dataset, seed.format,
       seed.definition::jsonb,
       (select u.id from identity.app_user u where u.tenant_id=t.id order by
          case when u.role in ('SUPER_ADMIN','TENANT_ADMIN') then 0 else 1 end, u.created_at limit 1)
from platform.tenant t
cross join (values
  ('pipeline_by_stage','Pipeline by stage','Open pipeline amount and record volume by stage.','OPPORTUNITY','SUMMARY',
   '{"dataset":"OPPORTUNITY","format":"SUMMARY","groupBy":["stageName"],"summaries":[{"field":"amount","function":"SUM","label":"Pipeline"}],"limit":500,"calculatedMeasures":[],"conditionalRules":[{"field":"sum_amount","operator":"GT","value":"500000","foreground":"#F8FAFC","background":"#075985"}]}'),
  ('account_health','Account health','Customer health, pipeline and activity context in one governed result.','ACCOUNT','TABULAR',
   '{"dataset":"ACCOUNT","format":"TABULAR","columns":["name","segment","ownerName","healthBand","healthScore","openPipelineAmount","lastActivityAt"],"limit":500,"calculatedMeasures":[],"conditionalRules":[{"field":"healthScore","operator":"LT","value":"60","foreground":"#FFFFFF","background":"#9A3412"}]}'),
  ('lead_source_performance','Lead source performance','Lead volume and score by acquisition source.','LEAD','SUMMARY',
   '{"dataset":"LEAD","format":"SUMMARY","groupBy":["source"],"summaries":[{"field":"score","function":"AVG","label":"Average score"}],"limit":500,"calculatedMeasures":[],"conditionalRules":[]}'),
  ('activity_follow_up','Activity follow-up','Open customer activities by owner and outcome.','ACTIVITY','SUMMARY',
   '{"dataset":"ACTIVITY","format":"SUMMARY","groupBy":["ownerName"],"summaries":[{"field":"durationMinutes","function":"SUM","label":"Engagement minutes"}],"filters":[{"field":"isCompleted","operator":"EQ","values":["false"]}],"limit":500,"calculatedMeasures":[],"conditionalRules":[]}')
) as seed(code,name,description,dataset,format,definition)
where exists (select 1 from identity.app_user u where u.tenant_id=t.id)
on conflict (tenant_id, code) do nothing;

insert into reporting.analytics_dashboard
  (tenant_id,dashboard_code,name,description,status,owner_id,refresh_interval_minutes,layout_mode,audience)
select t.id, 'studio_overview', 'Revenue studio overview',
       'An editable starter dashboard composed entirely from saved reports.', 'DRAFT',
       (select u.id from identity.app_user u where u.tenant_id=t.id order by
          case when u.role in ('SUPER_ADMIN','TENANT_ADMIN') then 0 else 1 end, u.created_at limit 1),
       60, 'GRID', 'PRIVATE'
from platform.tenant t
where exists (select 1 from identity.app_user u where u.tenant_id=t.id)
on conflict (tenant_id,dashboard_code) do nothing;

insert into reporting.dashboard_widget
  (tenant_id,dashboard_id,title,visualization_type,source_module,metric_code,metric_value,
   sort_order,report_view_id,layout_x,layout_y,layout_width,layout_height,configuration)
select d.tenant_id,d.id,seed.title,seed.visualization,'ANALYTICS',seed.report_code,0,
       seed.sort_order,r.id,seed.x,seed.y,seed.width,seed.height,seed.config::jsonb
from reporting.analytics_dashboard d
join (values
  ('pipeline_by_stage','Pipeline by stage','BAR',10,0,0,7,4,'{"showLegend":false,"drillThrough":true}'),
  ('account_health','Accounts needing attention','TABLE',20,7,0,5,4,'{"pageSize":10,"drillThrough":true}'),
  ('lead_source_performance','Lead source quality','DONUT',30,0,4,6,4,'{"showLegend":true,"drillThrough":true}'),
  ('activity_follow_up','Follow-up workload','SUMMARY',40,6,4,6,4,'{"showTotals":true,"drillThrough":true}')
) as seed(report_code,title,visualization,sort_order,x,y,width,height,config) on true
join analytics.report_view r on r.tenant_id=d.tenant_id and r.code=seed.report_code
where d.dashboard_code='studio_overview'
  and not exists (select 1 from reporting.dashboard_widget w where w.tenant_id=d.tenant_id
                  and w.dashboard_id=d.id and w.report_view_id=r.id);

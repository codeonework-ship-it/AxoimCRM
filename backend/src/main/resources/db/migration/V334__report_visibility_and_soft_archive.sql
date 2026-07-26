-- Saved-report visibility follows the same explicit sharing model as dashboards.
-- Starter content is tenant-visible; user-authored content is private until shared.
alter table analytics.report_view add column if not exists archived_at timestamptz;

insert into analytics.report_share
  (tenant_id,report_view_id,principal_type,principal_key,permission,created_by)
select r.tenant_id,r.id,'TENANT',r.tenant_id::text,'VIEW',r.created_by
from analytics.report_view r
where r.code in ('pipeline_by_stage','account_health','lead_source_performance','activity_follow_up')
  and not exists (select 1 from analytics.report_share s where s.tenant_id=r.tenant_id
                  and s.report_view_id=r.id and s.principal_type='TENANT' and s.revoked_at is null);

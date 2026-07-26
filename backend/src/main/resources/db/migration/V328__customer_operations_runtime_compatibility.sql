-- Platform SUPER_ADMIN/SUPER_AUDIT actors live in identity.platform_user, while
-- tenant actors live in identity.app_user. Actor ids on cross-tenant commands
-- are therefore deliberately polymorphic and are still attributable through
-- the immutable audit event written by each service.
alter table marketing.campaign_performance_snapshot
  drop constraint fk_campaign_snapshot_user_same_tenant;
alter table reporting.report_subscription
  drop constraint fk_report_subscription_user_same_tenant;

-- The first canonical automation seed predated the Step.message property and
-- used body (now reserved for LOOP child steps). Repair it in place once; rule
-- edits and restores remain append-only after this compatibility correction.
update automation.rule_version v
set definition = jsonb_set(
    v.definition,
    '{steps,1}',
    ((v.definition #> '{steps,1}') - 'body') ||
      jsonb_build_object('message', v.definition #>> '{steps,1,body}'),
    false)
from automation.rule_definition r
where r.tenant_id = v.tenant_id and r.id = v.rule_id
  and r.rule_code = 'AUT-BIGDEAL-FLAG'
  and jsonb_typeof(v.definition #> '{steps,1,body}') = 'string';

-- Opportunity lifecycle history is created automatically with an opportunity.
-- It is an owned derivative, not independent operator data. Remove only history
-- whose parent opportunity is in the tenant-checked rollback target array, then
-- remove the opportunity itself. No other child tables are cascaded here.

create or replace function migration.delete_owned_targets(
  p_tenant_id uuid,
  p_entity text,
  p_target_ids uuid[]
) returns bigint
language plpgsql
security definer
set search_path = pg_catalog, migration, crm, sales, engagement
as $$
declare
  v_bound_tenant uuid;
  v_deleted bigint;
begin
  v_bound_tenant := nullif(current_setting('app.tenant_id', true), '')::uuid;
  if v_bound_tenant is null or v_bound_tenant <> p_tenant_id then
    raise exception 'Migration rollback tenant mismatch' using errcode = '42501';
  end if;
  if coalesce(array_length(p_target_ids, 1), 0) = 0 then return 0; end if;

  case p_entity
    when 'ACTIVITY' then
      delete from engagement.activity where tenant_id = p_tenant_id and id = any(p_target_ids);
    when 'OPPORTUNITY_CONTACT_ROLE' then
      delete from sales.opportunity_contact_role where tenant_id = p_tenant_id and id = any(p_target_ids);
    when 'OPPORTUNITY' then
      delete from sales.stage_history
       where tenant_id = p_tenant_id and opportunity_id = any(p_target_ids);
      delete from sales.opportunity_state_history
       where tenant_id = p_tenant_id and opportunity_id = any(p_target_ids);
      delete from sales.opportunity where tenant_id = p_tenant_id and id = any(p_target_ids);
    when 'LEAD' then
      delete from crm.lead where tenant_id = p_tenant_id and id = any(p_target_ids);
    when 'CONTACT' then
      delete from crm.contact where tenant_id = p_tenant_id and id = any(p_target_ids);
    when 'ACCOUNT' then
      delete from crm.account where tenant_id = p_tenant_id and id = any(p_target_ids);
    else
      raise exception 'Unsupported rollback entity %', p_entity using errcode = '22023';
  end case;
  get diagnostics v_deleted = row_count;
  return v_deleted;
end
$$;

revoke all on function migration.delete_owned_targets(uuid, text, uuid[]) from public;
grant execute on function migration.delete_owned_targets(uuid, text, uuid[]) to axiom_app;

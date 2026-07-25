-- Outbox relay role — see ADR-003 and com.axiom.outbox.OutboxRelay.
--
-- The relay polls outbox_event with NO tenant context (it serves every tenant),
-- but V1 put FORCE ROW LEVEL SECURITY on outbox_event, so a connection without
-- app.tenant_id set sees zero rows. Rather than weakening the tenant policy or
-- running the relay as a superuser, we add a dedicated least-privilege role
-- (axiom_relay) with its own permissive policies scoped TO that role only:
--   - relay_read   : may SELECT every row (cross-tenant, by design — the relay
--                    is infrastructure, not domain logic; ADR-001 rule 6 analog)
--   - relay_update : may UPDATE (needed both to set dispatched_at and because
--                    SELECT ... FOR UPDATE row locks must also pass UPDATE policy)
-- Policies are permissive (OR-combined), so the tenant_isolation policy for
-- axiom_app is unaffected. axiom_relay gets no other table grants at all.

do $$
begin
  if not exists (select from pg_roles where rolname = 'axiom_relay') then
    create role axiom_relay login password 'axiom_relay_dev_password';
  end if;
end
$$;

do $$
begin
  execute format('grant connect on database %I to axiom_relay', current_database());
end
$$;

grant usage on schema public to axiom_relay;
grant select, update on outbox_event to axiom_relay;

create policy relay_read on outbox_event
  for select to axiom_relay using (true);

create policy relay_update on outbox_event
  for update to axiom_relay using (true) with check (true);

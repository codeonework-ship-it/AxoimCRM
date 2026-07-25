-- Axiom CRM — close the row-level-security gap on platform and billing tables.
--
-- FINDING (architecture audit, 2026-07-25)
-- Five tables carry `tenant_id` and are granted select/insert/update to the
-- tenant-facing runtime role `axiom_app`, but had no row-level security:
--
--   platform.company_account      platform.trial_account_event
--   billing.billing_account       billing.invoice
--   billing.payment_transaction
--
-- Their only protection was the application-level `CrmRole.requirePlatform`
-- check in AdminService. That is exactly the single-layer arrangement
-- ADR-001 was written to forbid: "Application-level scoping alone is one
-- hand-written query away from a cross-tenant leak — and that query will
-- eventually be written, by someone new, under deadline, in a code path
-- nobody thought to review."
--
-- These tables hold every tenant's billing account, invoices and payments, so
-- they are the worst place in the schema to have a single point of failure.
--
-- WHY NOT A PLAIN tenant_isolation POLICY
-- The platform-operator views (AdminService.companies() / billing()) are
-- legitimately cross-tenant: a Super Admin must see all companies. A strict
-- own-tenant policy would break them.
--
-- DESIGN
-- The policy admits a row when EITHER
--   (a) it belongs to the caller's tenant  (app.tenant_id, set per transaction
--       by TenantSessionAspect), OR
--   (b) the transaction has been explicitly marked as platform-scoped
--       (app.platform_access = 'on', set by PlatformSession#grantForTransaction
--       and ONLY after CrmRole.requirePlatform has passed).
--
-- Both settings are SET LOCAL: they die with the transaction and cannot leak
-- onto a pooled connection. The flag is not a client input — no request header
-- or body can set it; it is written by server code that has already
-- authorized the caller.
--
-- This keeps the platform view working while restoring the ADR-001 property
-- that matters: a query which forgets its guard returns zero rows instead of
-- every tenant's invoices.

do $$
declare
  t text;
begin
  foreach t in array array[
    'platform.company_account',
    'platform.trial_account_event',
    'billing.billing_account',
    'billing.invoice',
    'billing.payment_transaction'
  ]
  loop
    execute format('alter table %s enable row level security', t);
    execute format('alter table %s force row level security', t);

    -- Drop first so the migration is safe to re-run against a partially
    -- migrated database.
    execute format('drop policy if exists tenant_or_platform on %s', t);
    execute format($p$
      create policy tenant_or_platform on %s
        using (
          tenant_id = current_setting('app.tenant_id', true)::uuid
          or current_setting('app.platform_access', true) = 'on'
        )
        with check (
          tenant_id = current_setting('app.tenant_id', true)::uuid
          or current_setting('app.platform_access', true) = 'on'
        )
    $p$, t);
  end loop;
end
$$;

# Database module schemas

_Last updated: 2026-07-25_

The runnable PostgreSQL model now uses physical schemas that match the application modules. Application SQL may stay unqualified because runtime roles are assigned a controlled `search_path`; ownership and inspection still remain explicit in the database.

| Module | Schema | Tables |
|---|---|---|
| Platform tenancy | `platform` | `tenant`, `platform_user` |
| Tenant identity | `identity` | `app_user` |
| CRM core | `crm` | `account`, `contact`, `lead`, `pipeline_stage` |
| Sales execution | `sales` | `opportunity`, `opportunity_contact_role` |
| Engagement | `engagement` | `notification` |
| Governance | `governance` | `audit_event`, `crm_group`, `crm_group_member`, `module_catalog`, `module_table_catalog` |
| Integration | `integration` | `outbox_event` |
| Reference data | `reference` | `value_set`, `value_set_entry` |

The source of truth for this map in the database is `governance.module_catalog` plus `governance.module_table_catalog`.

## Rules for new tables

- Put each table in the module schema that owns the lifecycle of that data.
- Business tables must carry `tenant_id`; platform-only tables are the exception.
- Use opaque UUID primary keys.
- Add `unique (tenant_id, id)` when the table can be referenced by another tenant-scoped table.
- Foreign keys between tenant-scoped tables must include `tenant_id`, for example `foreign key (tenant_id, account_id) references crm.account(tenant_id, id)`.
- Master/reference data is soft-deactivated or soft-deleted; hard delete is reserved for retention/erasure jobs.
- Enable and force RLS for tenant-scoped tables that are read or written by `axiom_app`.
- Add tenant-leading indexes for every list, lookup, and export path.

## Current runtime roles

| Role | Search path | Notes |
|---|---|---|
| `axiom_app` | `platform, identity, crm, sales, engagement, governance, reference, integration, public` | Normal API runtime; subject to RLS. |
| `axiom_relay` | `integration, platform, public` | Outbox relay only; no business table access. |

Flyway still records history in `public.flyway_schema_history`. Migrations should schema-qualify any new object after `V6__module_schemas_and_reference_governance.sql`.

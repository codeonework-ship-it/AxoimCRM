# Axiom CRM Architecture Audit — 2026-07-25

## Current architecture posture

Axiom CRM is a modular monolith with PostgreSQL-enforced tenant isolation, Kafka
outbox messaging, a React/Vite web client and an Electron desktop wrapper. The
codebase now has explicit database schemas for platform, identity, CRM, sales,
engagement, governance, reference, billing, reporting and integration concerns.

## Database recommendations and fixes applied

- Added reporting and billing schemas to separate commercial/reporting concerns
  from CRM core tables.
- Added screen-level RBAC policy tables, with screen catalogue, module mapping
  and role permission constraints.
- Added platform company-account and trial lifecycle tables.
- Added billing account, invoice and payment transaction tables.
- Added Jasper reporting definition/run tables.
- Added email/report alert configuration and dispatch evidence tables.
- Preserved soft-delete/no-hard-delete posture for master data.
- Preserved PostgreSQL RLS for tenant-scoped reporting and alert configuration.

Recommended next database hardening:

- Add security-definer read models for true all-tenant `identity.app_user`
  inspection by platform operators without weakening tenant RLS.
- Add partitioning strategy for audit, notification, report-run and dispatch
  tables once production volume targets are finalized.
- Add migration-level testcontainers smoke tests for every Flyway migration.

## API recommendations and fixes applied

- Added admin APIs for users, companies, trial extension and billing.
- Added RBAC APIs for role catalogue and current policy visibility.
- Added alert APIs for email alert/report alert configuration and queueing.
- Added Jasper report APIs with PDF, XLSX and DOCX download.
- Login now rejects inactive tenant users.
- CORS remains explicit-origin only; wildcard CORS is rejected at startup.

Recommended next API hardening:

- Add per-screen RBAC middleware beyond the existing read-only mutation guard.
- Add idempotency keys for admin write operations.
- Add OpenAPI documentation generation for every `/api/v1` endpoint.
- Add provider adapters for SMTP/storage only when third-party integrations are
  approved.

## React/UI recommendations and fixes applied

- Added full-size/restore controls for reusable data workspaces.
- Reworked the User Manual into a right-side dock with resize and full-view
  behavior.
- Added Reports module with PDF, Excel and Word downloads.
- Added Administration cockpit for users, RBAC, alert configuration, trials,
  company setup and billing.
- Kept responsive behavior for desktop, tablet and mobile through fixed overlay
  containment and adaptive grids.

Recommended next UI hardening:

- Add route-level guards that consume RBAC policies before rendering modules.
- Add Playwright accessibility/regression tests for dock/full-size behavior.
- Add editable RBAC matrix management once governance approval workflow exists.

## Reporting recommendation

The CRM API currently renders Jasper templates in-process for immediate product
closure. A separate `reporting-service` project now holds the Jasper project
boundary and can be promoted to an independently deployed service when scaling
or isolation requires it.

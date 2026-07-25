# Epic implementation status

> Tracks delivery of the [epic catalogue](product/05-epics-and-stories.md) against the [agile delivery plan](product/15-agile-delivery-plan.md). Product definition and architecture are baselined; implementation is a walking vertical slice with a runnable web preview and Electron shell. No epic is complete until its acceptance contract passes.

**Legend:** ✅ Completed (implemented & verified against acceptance criteria) · 🟡 Partial · ⛔ Not started

_Last updated: 2026-07-25_

| Epic | Capability | Status | Train | Notes |
|---|---|:--:|:--:|---|
| E01 | Tenancy, identity and access | 🟡 | R1 | Tenancy/auth skeleton in `backend/`: tenant + user tables, `TenantContext` + session-variable binding, Postgres RLS `tenant_isolation` policies on every tenant table, JWT scaffolding. No SSO/SCIM/MFA yet |
| E02 | RBAC, record sharing and segregation of duties | ⛔ | R1 | |
| E03 | Organization, reference and master data | ⛔ | R1 (partial) | P0 stories in R1; territory/quota P1 stories in R3 |
| E04 | Accounts, contacts, hierarchy and buying groups | 🟡 | R1 | Tenant-scoped account/contact schema and read-only Accounts preview exist; hierarchy, buying-group workspace, record detail and acceptance coverage remain open |
| E05 | Lead capture, qualification and routing | 🟡 | R1 | Lead→opportunity vertical-slice skeleton: `lead` table, seed data and the beginnings of the capture/convert API path in `backend/` |
| E06 | Opportunity and pipeline management | 🟡 | R1 | Pipeline board, optimistic drag plus keyboard/touch Move control, server stage gate, and outbox events exist for the walking slice; full opportunity lifecycle remains open |
| E07 | Activity, email and calendar engagement | ⛔ | R2 | |
| E08 | Products, price books, quotes and CPQ | ⛔ | R2 | |
| E09 | Contracts, orders, subscriptions and renewals | ⛔ | R3 | |
| E10 | Forecasting and revenue intelligence | ⛔ | R2 | |
| E11 | Campaigns, segments and marketing alignment | ⛔ | R3 | |
| E12 | Cases, entitlements and SLA management | ⛔ | R3 | |
| E13 | Partner, channel and territory management | ⛔ | R4 | |
| E14 | Workflow automation, approvals and rules engine | ⛔ | R1 (partial) | Engine + approvals in R1; visual builder and simulation in R2 |
| E15 | Reporting, dashboards and analytics | ⛔ | R2 | |
| E16 | AI copilot and agentic assistance | ⛔ | R2 (P0 slice) | Deliberately after E02 — permission-scoped grounding needs the sharing model first |
| E17 | Integration platform, APIs, webhooks and events | ⛔ | R2 | Outbox table and Kafka relay config exist in the skeleton as plumbing for the E05/E06 slice |
| E18 | Data migration and onboarding | ⛔ | R2 | |
| E19 | Administration, configuration, sandbox and release | ⛔ | R1 (partial) | Custom objects/layouts/import in R1; sandbox + promotion in R2 |
| E20 | Audit, compliance, observability and governance | ⛔ | R1 (partial) | Immutable audit + observability in R1; DSR/consent/tenant export in R2 |
| E21 | Mobile and offline field access | ⛔ | R3 | Responsive-UI P0 story lands with R2 |
| E22 | BFSI vertical pack | ⛔ | R4 | Gated on the pack framework proving out against a stable core |
| E23 | Commodity trading vertical pack | ⛔ | R4 | Origination only; CTRM connector contract per [system design §11](architecture/system-design.md#11-integration-architecture-and-the-ctrm-connector) |

**0 / 23 epics completed · 4 partial (walking vertical slice) · 19 not started.**

## Skeleton work in detail

What exists in `backend/` today, honestly stated — a walking skeleton proving the architecture's spine, not delivered stories:

- **Tenancy (E01):** `V1__baseline.sql` creates tenant-scoped tables with a generated `tenant_isolation` RLS policy on each (`tenant_id = current_setting('app.tenant_id')`); `TenantContext` / `TenantSessionAspect` bind the tenant from the authenticated principal into the database session — the two-layer enforcement of [ADR-001](architecture/adr/ADR-001-tenancy-isolation.md).
- **Lead→opportunity slice (E05/E06):** `lead`, `account`, `contact`, `pipeline_stage`, `opportunity` and `opportunity_contact_role` tables with `V2__seed_demo.sql` demo data, shaped for the conversion flow in [US-E05-07](product/05-epics-and-stories.md#us-e05-07--lead-conversion-p0-8--fr-led-011).
- **Event backbone (E17 plumbing):** `outbox_event` table and a scheduled Kafka relay configuration per [ADR-003](architecture/adr/ADR-003-event-backbone.md) — transactional outbox from day one, not retrofitted.
- **Notification centre (cross-cutting slice):** `notification` table with tenant RLS and recipient scoping; live feed/unread/read APIs; transactional lead-conversion and stage-movement notices; responsive bell UI with reason, action state, deep link and degraded-state recovery. Email/push, preferences, digests and full record authorization rechecks remain open.

The notification increment has unit/contract coverage and live-stack API/browser evidence recorded in [runtime smoke](../qa/runtime-smoke.md). No complete epic has yet passed its full acceptance catalogue, which is why nothing above is ✅. Run steps are in the [repository README](../README.md).

## Conventions

- Status changes here require the story's Definition of Done ([backlog conventions](product/05-epics-and-stories.md#definition-of-done)) — an epic goes 🟡 when its first story is genuinely done, ✅ when all its stories for the current release scope are.
- Release trains and sprint allocation: [agile delivery plan](product/15-agile-delivery-plan.md).

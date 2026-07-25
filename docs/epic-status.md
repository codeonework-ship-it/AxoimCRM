# Epic implementation status

> Tracks delivery of the [epic catalogue](product/05-epics-and-stories.md) against the [agile delivery plan](product/15-agile-delivery-plan.md). Product definition and architecture are baselined; implementation is a walking vertical slice with a runnable web preview and Electron shell. No epic is complete until its acceptance contract passes.

**Legend:** ✅ Completed (implemented & verified against acceptance criteria) · 🟡 Partial · ⛔ Not started

_Last updated: 2026-07-25_

| Epic | Capability | Status | Train | Notes |
|---|---|:--:|:--:|---|
| E01 | Tenancy, identity and access | 🟡 | R1 | Tenant lifecycle, local password policy, login throttling, MFA/TOTP, recovery codes, sessions/revocation, network rules, step-up, SCIM/service-token foundations, branding and impersonation controls are implemented. Live external IdP federation remains pending |
| E02 | RBAC, record sharing and segregation of duties | 🟡 | R1 | Role hierarchy, profiles, permission sets/groups, object/FLS policy tables, sharing evidence, masking, SoD, maker-checker, delegated admin, access reviews, export audit and access explainers are implemented in schema/services |
| E03 | Organization, reference and master data | 🟡 | R1 (partial) | Multi-currency/rates, fiscal calendar, business hours/holidays, governed picklists, territory preview/activation, quotas and maker-checker governed master changes are implemented |
| E04 | Accounts, contacts, hierarchy and buying groups | 🟡 | R1 | Account/contact management foundation now includes hierarchy, rollups, buying groups, duplicate detection/merge evidence, consent/suppression, health snapshots and account 360 data services; deeper UI flows remain open |
| E05 | Lead capture, qualification and routing | 🟡 | R1 | Lead capture now includes duplicate handling, scoring, routing, SLA clocks, qualification framework, conversion/disqualification support and seeded lead operations data |
| E06 | Opportunity and pipeline management | 🟡 | R1 | Opportunity lifecycle now includes multi-pipeline metadata, stage gates, line items, revenue splits, risk signals, slippage/movement, closure reasons and movement history foundations |
| E07 | Activity, email and calendar engagement | 🟡 | R2 | First-party tasks, events, calls, notes, manual email logs and unified activity timeline are implemented. Microsoft/Google/telephony connector capture remains intentionally pending |
| E08 | Products, price books, quotes and CPQ | 🟡 | R2 | CPQ database foundation is implemented: product catalogue, bundles, configuration rules, price books, pricing methods, contracted prices, approvals, quote versioning, quote lines, document/e-sign hand-off state and seeded governed values. API/UI workflow remains open |
| E09 | Contracts, orders, subscriptions and renewals | ⛔ | R3 | |
| E10 | Forecasting and revenue intelligence | ⛔ | R2 | |
| E11 | Campaigns, segments and marketing alignment | ⛔ | R3 | |
| E12 | Cases, entitlements and SLA management | ⛔ | R3 | |
| E13 | Partner, channel and territory management | ⛔ | R4 | |
| E14 | Workflow automation, approvals and rules engine | ⛔ | R1 (partial) | Engine + approvals in R1; visual builder and simulation in R2 |
| E15 | Reporting, dashboards and analytics | ⛔ | R2 | |
| E16 | AI copilot and agentic assistance | ⛔ | R2 (P0 slice) | Deliberately after E02 — permission-scoped grounding needs the sharing model first |
| E17 | Integration platform, APIs, webhooks and events | 🟡 | R2 | Outbox/Kafka relay, OpenAPI document, Prometheus metrics exposure, service credentials and SCIM service-token foundations are implemented. External webhooks/connectors remain pending |
| E18 | Data migration and onboarding | ⛔ | R2 | |
| E19 | Administration, configuration, sandbox and release | ⛔ | R1 (partial) | Custom objects/layouts/import in R1; sandbox + promotion in R2 |
| E20 | Audit, compliance, observability and governance | 🟡 | R1 (partial) | Tamper-evident audit chain, read/export/authentication audit, field history, retention/legal hold, DSR, consent withdrawal, encryption posture, tenant export, evidence packs, SLI and usage telemetry are implemented |
| E21 | Mobile and offline field access | ⛔ | R3 | Responsive-UI P0 story lands with R2 |
| E22 | BFSI vertical pack | ⛔ | R4 | Gated on the pack framework proving out against a stable core |
| E23 | Commodity trading vertical pack | ⛔ | R4 | Origination only; CTRM connector contract per [system design §11](architecture/system-design.md#11-integration-architecture-and-the-ctrm-connector) |

**0 / 23 epics completed · 12 partial (walking vertical slice) · 11 not started.**

## 2026-07-25 implementation increment

The current runnable slice now includes these additional partial deliveries:

- **E01/E02:** platform `SUPER_ADMIN` and `SUPER_AUDIT` identities, tenant switching, tenant-scoped JWTs, and read-only write blocking for auditor roles.
- **E03/E20:** account/lead master toolbar, audit drawer, immutable audit events, templates, atomic CSV bulk upload validation, governed Excel/Word/PDF exports, soft delete only, and in-use delete protection.
- **E04/E05:** account and lead lists use server-side search/filtering and 100-row pagination; grouped display remains available for the current page.
- **E17:** API CORS is locked to explicit configured origins and exposes only the headers needed by the frontend. Vendor and third-party integrations remain intentionally pending.
- **Database architecture:** physical module schemas now separate `platform`, `identity`, `crm`, `sales`, `engagement`, `governance`, `integration`, and `reference` tables. Tenant-consistent composite foreign keys and module catalog tables are in place.
- **E03 reference data:** governed value-set tables, seeded lead/status governance values, reference-data API, and Reference Data UI workspace are implemented for the preview.
- **E02/E15/E19:** RBAC screen policies, user-management cockpit, trial/company/billing administration, Jasper report downloads, email/report alert configuration and a standalone reporting project boundary are implemented. External delivery providers remain pending.
- **E07:** first-party activity timeline is implemented with tenant-scoped tasks, events, calls, notes and manual email logs; activity creation/completion writes audit/outbox events and reminder notifications.
- **E01/E02/E03/E04/E05/E06/E17/E20:** scanned and integrated the larger security, identity, org-data, account, lead, pipeline, audit/compliance and observability implementation set; compilation blockers, consent-DSR service wiring, migration constraints and runtime actuator exposure were hardened.
- **E08:** CPQ product/price-book/quote schema foundation and governed CPQ reference values are implemented through `V90`; quote/e-sign vendor integrations remain intentionally pending.

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

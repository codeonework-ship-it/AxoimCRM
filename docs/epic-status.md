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
| E08 | Products, price books, quotes and CPQ | 🟡 | R2 | CPQ database foundation, tenant seed data, read-side product/price-book/quote APIs, server-side search/filtering/100-row pagination, quote summary metrics and React commerce workspaces are implemented. Quote authoring, generated document lifecycle and external e-sign vendor integrations remain intentionally pending |
| E09 | Contracts, orders, subscriptions and renewals | 🟡 | R3 | Contract/order/subscription schemas, tenant RLS, seed data and the `/contracts` read workspace are implemented with renewal-risk summary metrics |
| E10 | Forecasting and revenue intelligence | 🟡 | R2 | Forecast periods, submissions, snapshots, tenant seed data and the `/forecast` read workspace are implemented with submitted/weighted/risk metrics |
| E11 | Campaigns, segments and marketing alignment | 🟡 | R3 | Campaign, segment and member schemas, tenant seed data and the `/campaigns` read workspace are implemented with influenced pipeline, budget and response metrics |
| E12 | Cases, entitlements and SLA management | 🟡 | R3 | Entitlements, cases, SLA milestones, tenant seed data and the `/cases` read workspace are implemented with open/escalated/missed-SLA metrics |
| E13 | Partner, channel and territory management | 🟡 | R4 | Partner account, deal-registration, conflict evidence schemas, tenant RLS, seed data and the `/partners` read workspace are implemented |
| E14 | Workflow automation, approvals and rules engine | 🟡 | R1 (partial) | Rule, step, simulation/run-trace schemas, tenant RLS, seed data and the `/automation` read workspace are implemented. External webhook execution remains pending |
| E15 | Reporting, dashboards and analytics | 🟡 | R2 | Jasper report downloads plus analytics dashboard/KPI schemas, tenant seed data and the `/analytics` read workspace are implemented |
| E16 | AI copilot and agentic assistance | 🟡 | R2 (P0 slice) | Provider-independent prompt, recommendation and citation schemas, tenant seed data and the `/copilot` read workspace are implemented. Vendor model execution remains intentionally pending |
| E17 | Integration platform, APIs, webhooks and events | 🟡 | R2 | Outbox/Kafka relay, OpenAPI document, metrics, service credentials, SCIM service-token foundations, endpoint-contract/job/webhook-stub schemas, seed data and the `/integrations` workspace are implemented. External webhooks/connectors remain pending |
| E18 | Data migration and onboarding | 🟡 | R2 | Import templates, batches, validation errors, tenant seed data and the `/migration` read workspace are implemented. Third-party source connectors remain intentionally pending |
| E19 | Administration, configuration, sandbox and release | 🟡 | R1 (partial) | Administration cockpit plus sandbox environment, release package, deployment-run schemas, seed data and the `/sandbox` workspace are implemented |
| E20 | Audit, compliance, observability and governance | 🟡 | R1 (partial) | Tamper-evident audit chain, read/export/authentication audit, field history, retention/legal hold, DSR, consent withdrawal, evidence-pack/control-review/observability schemas and the `/audit` workspace are implemented |
| E21 | Mobile and offline field access | 🟡 | R3 | Responsive shell plus mobile profile, device-session, offline-sync package schemas, tenant seed data and the `/mobile` read workspace are implemented |
| E22 | BFSI vertical pack | 🟡 | R4 | BFSI onboarding, product holding, compliance screening schemas, tenant seed data and the `/packs/bfsi` workspace are implemented |
| E23 | Commodity trading vertical pack | 🟡 | R4 | Commodity counterparty, enquiry, term-sheet schemas, tenant seed data and the `/packs/commodity` workspace are implemented. CTRM connector execution remains pending per [system design §11](architecture/system-design.md#11-integration-architecture-and-the-ctrm-connector) |

**0 / 23 epics completed · 23 partial (walking vertical slice) · 0 not started.**

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
- **E08:** CPQ product/price-book/quote schema foundation and governed CPQ reference values are implemented through `V90`; `V91` seeds product catalogue, active price books, quote transactions, approvals, templates and guided selling prompts. `/api/v1/cpq/products`, `/api/v1/cpq/price-books`, `/api/v1/cpq/quotes` and `/api/v1/cpq/quotes/summary` now back the `/products`, `/price-books` and `/quotes` workspaces with 100-row server pagination. Quote authoring and quote/e-sign vendor integrations remain intentionally pending.
- **E09/E10/E11/E12/E18:** five additional operational workspaces are implemented through `V93` with module schemas, tenant RLS, composite foreign keys, seed data, Spring read APIs, React navigation/routes and 100-row server pagination. `/contracts`, `/forecast`, `/campaigns`, `/cases` and `/migration` now run against live Docker data. Vendor/third-party connectors and write-heavy workflows remain intentionally pending per the current integration boundary.
- **E13/E14/E15/E16/E21:** a second five-epic workspace wave is implemented through `V94` with partner/channel, automation, analytics dashboard, AI copilot and mobile/offline schemas. `/partners`, `/automation`, `/analytics`, `/copilot` and `/mobile` share the same tenant/RLS-governed workspace API and 100-row pagination contract. External partner portals, webhook execution, model-provider calls and native app-store builds remain intentionally pending.
- **E17/E19/E20/E22/E23:** the final five-surface wave is implemented through `V95` with integration contracts/jobs/webhook stubs, sandbox/release/deployment evidence, audit evidence packs/control reviews/observability signals, BFSI onboarding/holdings/screening and commodity counterparty/enquiry/term-sheet schemas. `/integrations`, `/sandbox`, `/audit`, `/packs/bfsi` and `/packs/commodity` are now live preview workspaces. Browser API reachability is hardened by proxying Docker web traffic through same-origin `/api/v1`.
- **E09/E10/E11/E12/E15:** operational workspaces now have governed Excel, Word and PDF exports from the same server-side search/status/page contract used by the UI. Every workspace export writes `WORKSPACE_EXPORT` audit evidence with module, format, filter criteria and row count.
- **E19/E21:** the Electron desktop shell now has a reproducible local publish pipeline that packages the current React production build into a portable Windows desktop folder and zip. Signing/store release remains pending because it requires external certificates and vendor accounts.
- **Cross-workspace UX:** all epic workspace grids now include status grouping plus the existing full-size/restore data view control, so large-page reviews can expand without breaking the surrounding shell.
- **E04/E05/E08/E15/E20:** Account 360 is now exposed through tenant-scoped detail and hierarchy APIs with a right-side drawer in `/accounts`; leads can be disqualified only with a governed reason and optional future recycle date; quotes can be downloaded as PDF, Word or Excel directly from `/quotes`; both lead disqualification and quote document download write immutable audit evidence/outbox events where applicable.

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

# Epic Closure And Dependency Register

_Baseline: 2026-07-26_

## 1. Closure policy

“100% closed” means every in-scope acceptance criterion has implementation,
automated tests, tenant/RBAC evidence, audit or outbox evidence where state is
changed, migration evidence, operator documentation and a repeatable runtime
check. A screen, table or seeded demonstration record alone is not closure.

The current repository is a walking vertical slice: **0 of 23 epics are marked
complete and all 23 are partial**. The authoritative status remains
[`../epic-status.md`](../epic-status.md). This register prevents vendor access
from hiding first-party work and prevents first-party progress from being
misrepresented as a finished enterprise release.

## 2. Architecture guardrails for every remaining story

1. Domain code depends on ports, not vendor SDKs. Vendor adapters live at the
   integration edge behind the anti-corruption contracts in ADR-007.
2. A state change and its `integration.outbox_event` are committed in the same
   PostgreSQL transaction. Kafka publication is asynchronous and at-least-once;
   consumers are idempotent and own retry/DLQ behaviour.
3. Query/read models may be rebuilt from authoritative state and events. They do
   not become an alternative system of record.
4. Every tenant-owned table has a tenant key, tenant-consistent foreign keys,
   row-level security and an explicit lifecycle (`ACTIVE`, `SOFT_DELETE`,
   `APPEND_ONLY`, `IMMUTABLE` or `OUTBOX`) in the module table catalogue.
5. Configurable business vocabulary belongs in `reference.value_set` and
   `reference.value_set_entry`. Security states, protocol states and invariants
   remain constrained code/schema values and are not editable picklists.
6. Commands enforce RBAC, workflow gates and idempotency on the server. UI
   hiding is usability, never authorization.
7. Third-party unavailability may delay an external side effect; it may not
   prevent first-party records, audit evidence, queues or operator recovery from
   working.

## 3. Governed master inventory

| Domain | Authoritative masters | Lifecycle rule |
|---|---|---|
| Platform | Tenant, company account, trial policy, billing plan | Inactivate; never hard-delete a referenced tenant/company |
| Identity | Role catalogue, profile, permission set/group, network and IdP configuration | Version or deactivate; security evidence is append-only |
| Organization | Business unit, territory, fiscal calendar, business hours, holidays, currency and dated exchange rates | Effective-dated and tenant-scoped |
| CRM | Account/contact classifications, lead status/source/reason, pipeline and stage definitions, buying-group roles | Governed value sets or soft-delete entities |
| CPQ | Product, bundle, price book, price-book entry, pricing/approval policy, document template | Soft-delete masters; immutable quote revisions |
| Service/engagement | Activity types, email templates, case priority/status, entitlement and SLA policy | Template versions immutable; policies deactivate |
| Reporting | Report definition, KPI definition, report collection, output format and schedule frequency | Definitions versioned/soft-archived; run evidence append-only |
| Integration/migration | Connector contract, subscription, mapping profile and import template | Vendor-neutral contract first; credentials separated and rotated |
| Governance | Retention policy, legal hold, control definition, audit/export evidence | Evidence append-only; policy changes versioned |

Migration `V338__reporting_reference_masters.sql` adds the reporting collection,
output-format and schedule-frequency value sets. They are system-managed labels
for consistent UI and reporting metadata; renderer and scheduling constraints
remain enforced in the reporting schema.

## 4. Vendor/third-party dependency register

These items are deliberately **PENDING_VENDOR**. Their internal contracts,
queues and recovery surfaces may be completed without claiming live delivery.

| Epic | Pending adapter or external authority | First-party boundary that must remain complete |
|---|---|---|
| E01 | SAML/OIDC IdP federation and production SCIM provider testing | Provider/config masters, certificate alerts, local identity, sessions and access audit |
| E07 | Microsoft 365, Google Workspace and telephony capture | Activities, templates, manual logging, timeline and outbox events |
| E08 | E-signature and external document storage | Quote revisions, approval, Jasper document generation and dispatch queue |
| E13 | Externally hosted partner portal | Partner identities, deal registration, overlap rules and protection evidence |
| E14/E17 | External webhook destinations and vendor connectors | Outbox, subscriptions, signing contract, retry, DLQ and replay |
| E15 | SMTP/email delivery and production-volume certification environment | Artifact generation, schedule/threshold policy and delivery evidence queue |
| E16 | Commercial AI/model providers | Provider-neutral requests, guardrails, citations, audit and deterministic fallback |
| E18 | Salesforce/Zoho/other source connectors | Discovery contract, mapping, validation, dry-run, reconciliation and rollback |
| E21 | Apple/Microsoft store signing and distribution accounts | Responsive/PWA/Electron runtime, device sessions and offline-sync contract |
| E23 | Named CTRM/ETRM products | Generic five-capability contract, anti-corruption mapping and dispatch evidence |

## 5. First-party closure queue

The work below is not blocked by a vendor and therefore remains in the product
closure queue. Delivery follows release priority and the Definition of Done,
not a blanket “complete” flag.

| Priority | Epics | Required closure evidence |
|---|---|---|
| P0 | E01-E06, E20 | Complete end-to-end CRUD/lifecycle flows, record-level authorization, tenant isolation tests, audit/outbox assertions and accessibility/runtime suites |
| P0 | E15 | Report-grid/PDF parity, KPI reconciliation, projection rebuild/reconciliation tests, export audit, scale gate and viewer regression coverage |
| P1 | E07-E14 | Finish first-party authoring and lifecycle commands, workflow/approval coverage, bulk/master validation and cross-module transaction tests |
| P1 | E17-E19 | Complete vendor-neutral connector management, migration rollback/re-sync, sandbox promotion and recovery tests |
| P2 | E16, E21-E23 | Provider-independent guardrails, offline conflict UX and complete vertical-pack workflows before any adapter certification |

## 6. Release decision

No epic moves from partial to completed until its acceptance contract is linked
to passing evidence. Vendor-dependent stories stay visible as `PENDING_VENDOR`;
they are not silently removed from scope, and they do not prevent independent
first-party stories from closing.

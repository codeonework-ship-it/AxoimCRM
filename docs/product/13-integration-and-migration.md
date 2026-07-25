# Integration and migration

This document expands FRD modules `INT` (`FR-INT-001` … `FR-INT-009`, epic E17) and `MIG` (`FR-MIG-001` … `FR-MIG-010`, epic E18). The two belong together because they answer the same buyer question from opposite directions: *can I get my data and processes in, and can I connect — or leave — without a consulting engagement?* The architectural decisions behind this document are [ADR-003 — event backbone](../architecture/adr/ADR-003-event-backbone.md) and [ADR-007 — external system integration](../architecture/adr/ADR-007-external-system-integration.md).

The competitive stance is explicit ([product scope](01-product-scope.md), principle 10): full API access, webhooks and complete export are base-tier capabilities. **No tier traps** — interoperability is never withheld to force an upgrade (`FR-GLOBAL-011`).

## 1. The API-first surface

### 1.1 Complete REST API (`FR-INT-001`, `F-271`, `F-274`)

Every object and operation available in the UI is available through a documented REST API, described by a published, versioned OpenAPI specification. **No capability may be UI-only. If a user can do it, an integration can do it** — and this is enforced by the Definition of Done on every story ([epics and stories](05-epics-and-stories.md), DoD point 7), not audited retroactively. Custom objects and fields participate identically to standard ones (`FR-ADM-001`): creating a custom object makes it a first-class API citizen the moment it is saved.

Everything the API returns passes the same server-side authorization as the UI (`FR-GLOBAL-002`): tenant scoping, record access, field-level security. A field the caller cannot read is absent from the response, not null (`FR-SEC-007`).

Conventions that hold across the entire surface, so an integrator learns them once:

- **Errors are actionable.** A rejected request returns a machine-readable error code, the offending field path, and a message stating what to do — not merely what went wrong (`FR-GLOBAL-003`).
- **Concurrency is explicit.** Every mutable record carries a version; updates supply the version the edit was based on, and a stale version is rejected as a conflict identifying which fields changed and by whom (`FR-GLOBAL-004`). Integrations merge; they do not blindly retry over someone else's edit.
- **Rate limiting is legible.** Standard rate-limit headers on every response state the limit, the remaining allowance and the reset time (§1.3).
- **Authentication is service-grade.** OAuth 2.0 client credentials or scoped API tokens with per-credential scope, expiry, rotation, revocation and last-used telemetry (`FR-TEN-013`).
- **Every request is traceable.** A correlation ID flows through all layers and appears in the response, so a support conversation starts from an identifier instead of a timestamp guess (`FR-GLOBAL-010`).

### 1.2 Bulk API (`FR-INT-002`, `F-272`)

Asynchronous bulk create, update, upsert, delete and query, with job status, **per-record results** and a downloadable error file (`US-E17-02`). Per-record results are the design decision that matters: a 100,000-row job with 90 failures completes 99,910 rows and names the 90, rather than failing all-or-nothing or — worse — succeeding silently and incompletely.

- Jobs are submitted, monitored and cancelled through the API; status reports rows processed, succeeded and failed while the job runs, not only at the end.
- Bulk writes fire automation and produce audit events like any other write — bulk is a throughput mode, not a governance bypass. Where a tenant intentionally suppresses automation for a load, that suppression is an explicit, audited job option.
- Bulk query supports cursoring over result sets far beyond interactive limits, which is what the migration tooling (§3) and CDC consumers build on.
- Mass destructive jobs respect the platform's controls: permission, confirmation of exact record count, and step-up authentication above a volume threshold (`FR-ADM-010`).

### 1.3 No API limits by tier (`FR-INT-003`, `F-273`)

API access is not limited by commercial tier. Both competitors meter API calls by edition; Axiom does not (`US-E17-03`). What exists instead is **fair-use throttling applied uniformly**: published limits, standard rate-limit response headers on every response, and visible usage telemetry (`FR-AUD-015`). The honest distinction: throttling protects the platform from abusive traffic patterns; it is never a revenue lever, and a throttled caller can see exactly where they stand and when to retry.

### 1.4 Versioning and deprecation (`FR-INT-004`, `F-275`)

The API is versioned with a published support and deprecation policy: a minimum notice period before any version retires, and **no breaking change within a version** — additive changes (new fields, new endpoints) only. Deprecation is announced in the API response itself (deprecation headers naming the sunset date), not only in release notes an integrator will not read.

### 1.5 Idempotency keys (`FR-GLOBAL-006`, `F-279`)

Every write endpoint accepts an idempotency key and returns the original result for a repeated key within the retention window, without duplicating the effect (`US-E17-04`). This is global for a reason: every retry policy in every integration — including our own outbound dispatch — depends on it. A client that times out and retries must never create two opportunities.

### 1.6 Webhooks (`FR-INT-005`, `F-276`)

Administrators subscribe endpoints to record and process events, with signed payloads, retry with exponential backoff, a **dead-letter queue and a replay facility** (`US-E17-05`). Delivery is at-least-once from the transactional outbox ([ADR-003](../architecture/adr/ADR-003-event-backbone.md)) — a webhook is never emitted for a rolled-back transaction, and a committed change never silently loses its event.

The subscription model:

- Subscriptions select event types and may filter by object, record type and field-change criteria, so an endpoint receives what it needs rather than a firehose it must discard.
- Payloads are signed with a per-subscription secret; secrets rotate without downtime by honouring two active secrets during a rotation window.
- Payload content respects the subscription's configured field scope, and designated sensitive fields are never included in webhook payloads regardless of scope — an outbound push is an export surface and is treated like one.

The delivery contract:

- Retry with exponential backoff and jitter for a bounded schedule; after exhaustion the delivery lands in the DLQ with the final failure reason.
- DLQ entries are inspectable and replayable individually or in bulk, and replay preserves original ordering per record.
- Receivers must be idempotent, and the documentation says so plainly rather than implying exactly-once. Deliveries carry the event ID for deduplication.
- A permanently failing endpoint surfaces on integration health (§1.9) and does not block the stream for other subscribers.

### 1.7 Event stream and change data capture (`FR-INT-006`, `F-277`, `F-278`)

Domain events are published for near-real-time consumption (`US-E17-06`), ordered per record — partition key `(tenant_id, entity_id)` — not globally, which is the ordering business logic actually needs ([ADR-003](../architecture/adr/ADR-003-event-backbone.md)). Change data capture supports downstream replication **with ordering and no silent gaps**: a consumer can detect a missed change rather than discover it in a month-end reconciliation. Events carry business meaning (opportunity stage changed), not row diffs; CDC carries row-level change for replication. They are different products for different consumers, and both are stated as such.

### 1.8 Named credentials (`FR-INT-007`, `F-280`)

Outbound integration credentials are stored encrypted, never displayed after entry, rotatable, and **referenced by name in configuration rather than embedded**. Automation calls "the ERP connection" (`FR-AUT-006`); it never holds a secret. Rotation is therefore one operation in one place, and an exported configuration contains no credential material. Per-credential scope, expiry and last-used telemetry ride the service-credential model (`FR-TEN-013`).

### 1.9 Integration health — the no-silent-failure rule (`FR-INT-009`, `F-283`)

Administrators see per-integration health: last successful sync, failure count, error detail and affected records, with the ability to retry (`US-E17-07`). The governing rule is worth stating as bluntly as the FRD does: **an integration failing silently is a defect. Every failure must surface to a human within a configured window.** Sustained failure opens a circuit breaker and raises an alert; the alternative — discovering three weeks of unsynced orders during a billing dispute — is the failure mode this entire section exists to prevent. Sandbox environments get integration mocking (`F-284`) so a test tenant can exercise flows without contacting real external systems, consistent with the sandbox outbound-disable default (`FR-ADM-005`).

## 2. Connector catalogue

> **Implementation note (2026-07-25):** The current runnable increment keeps vendor and third-party connector implementations pending by design. The API, Kafka/outbox plumbing, import templates and export surfaces are in place for non-vendor CRM workflows; Microsoft, Google, telephony, ERP, enrichment, payment, and other vendor adapters remain specification-only until their integration stories are started.

Connectors are supported product, not sample code: each ships with configuration, health status and field mapping (`FR-INT-008`, `F-281`, `F-282`). Every connector sits behind an anti-corruption layer — a capability contract in Axiom's vocabulary, an adapter per vendor — so external models never enter core domain code ([ADR-007](../architecture/adr/ADR-007-external-system-integration.md)). Every connector has a defined behaviour when its external system is absent; "the product stops" is not one of them.

| Connector class | Serves | Defined absence behaviour |
|---|---|---|
| Email and calendar — Microsoft 365, Google Workspace | Passive capture, send/receive, calendar sync (`FR-ACT-005`, `FR-ACT-006`) | Capture pauses and queues; manual logging unaffected |
| Telephony / CTI | Click-to-dial, screen-pop, call logging, recording reference (`FR-ACT-011`) | Manual call logging unaffected |
| E-signature | Envelope state on quotes, executed-document reference (`FR-CPQ-012`) | Quotes generate and send; signature state updates on reconnection |
| ERP / billing | Order and contract hand-off with reconciliation (`FR-CTR-011`) | Hand-offs queue with visible pending state; retried with backoff |
| Marketing automation | Segment and campaign membership sync (`FR-CMP-004`) | Segments build locally; sync resumes and reconciles |
| Data enrichment | Account and contact enrichment with provenance (`FR-ACC-013`) | Records function unenriched; no silent staleness |
| Screening — sanctions/PEP/adverse media | BFSI pack screening runs (`FR-BFS-005`) | Onboarding progression blocks — **fails closed**, because a screening gate that passes without screening is a false control |
| **Generic CTRM/ETRM** | Commodity pack: counterparty sync, credit read, agreement read, deal hand-off, status callback (`FR-CTM-010`, `F-371`) | CRM fully usable for origination; credit gates fail closed; hand-offs queue (`FR-CTM-003`, `FR-CTM-009`) |

The CTRM/ETRM connector deserves the emphasis it gets elsewhere: it is a **generic connector against a five-capability contract**, not an integration with any single vendor's API. Any trading system implementing the contract is supported; a specific product is one adapter, never a hard dependency. The contract, its failure table and the reasoning are in [ADR-007](../architecture/adr/ADR-007-external-system-integration.md), and the pack that consumes it is specified in [the commodity trading pack](17-vertical-pack-commodity-trading.md). The cost is stated honestly there too: the first adapter is genuinely more work than a direct integration; the payoff is entirely in the second and third.

Outbound integration never runs synchronously from a request thread — it goes through the outbox and dispatch workers ([ADR-003](../architecture/adr/ADR-003-event-backbone.md)), so a slow external system cannot slow a record save. Every outbound operation is idempotent by key, so redelivery is a no-op.

## 3. Migration — the adoption differentiator

Migration is where CRM replacements die. The industry pattern is a one-way, best-effort import performed under time pressure, discovered incomplete after the old system is switched off. Axiom's migration tooling (`F-285` … `F-294` — nearly all `UNQ` in the [feature catalogue](04-feature-catalogue.md)) is designed around the opposite premises: **verify before you write, reconcile after you write, and keep the exit open until the customer closes it.**

### 3.1 Source connection (`FR-MIG-001`, `F-285`, `F-286`, `F-287`)

Native importers connect to Salesforce, Zoho CRM or HubSpot using **the customer's own credentials, with read-only scope**, and enumerate available objects and record counts (`US-E18-01`). Read-only scope is a trust statement as much as a safety one: the importer is technically incapable of damaging the source system during a parallel run. Rate limits on the source API are the pacing constraint (FRD §29) — large migrations schedule around them rather than hammering into them.

### 3.2 Schema discovery and mapping review (`FR-MIG-002`, `F-288`)

The importer discovers the source schema **including custom objects and fields**, proposes a field mapping to Axiom — creating custom fields where the target has no equivalent — and presents it for review, correction and save (`US-E18-02`).

The rule that separates this from every ad-hoc import script: **unmapped source fields are listed explicitly. Silent omission of source data is not acceptable.** The mapping review closes with a statement of exactly what will not come across and requires the operator to acknowledge it. Discovering a missing field during mapping review costs a minute; discovering it six months after the source is decommissioned costs the data.

### 3.3 Dry run (`FR-MIG-003`, `F-289`)

A full validation pass that **writes nothing** and reports: records to be created per object, validation failures with reasons, duplicates detected, unmapped fields, and referential gaps (`US-E18-03`). The dry run applies the same validation the real import will (`FR-GLOBAL-003`), so its report is a prediction, not an estimate. Teams iterate mapping → dry run → fix source data → dry run until the report is clean, before a single record is written.

### 3.4 Relationship preservation (`FR-MIG-004`, `F-293`)

Migration preserves account hierarchies, contact–account relationships, opportunity–account–contact links, and activity-to-record associations (`US-E18-04`). A relationship that cannot be resolved is **reported with both endpoints named, not silently dropped** — an orphaned contact is a defect report, never a quiet data-quality decay. History migrates too (`FR-MIG-005`, `F-294`): attachments, notes and activity history with original timestamps and actors preserved as recorded values, so an account's timeline reads continuously across the system boundary.

### 3.5 Reconciliation report (`FR-MIG-006`, `F-290`)

After migration: source-versus-target record counts per object, **monetary sums for financial fields**, and a list of every record not migrated with the reason (`US-E18-05`). Counts catch missing records; sums catch value corruption — a currency mis-mapped or a decimal shifted shows up as a sum mismatch even when counts tie out. The report is the artefact a project sponsor signs, which is exactly why it must be generated by the system rather than assembled by hand.

### 3.6 Rollback (`FR-MIG-007`, `F-291`)

A completed migration is reversible: rollback removes every record the migration created and restores the tenant to its pre-migration state, within a configurable retention window (`US-E18-06`). Rollback is itself audited and reports exactly what it removed. Records the migration created and users subsequently modified are flagged in the rollback preview — the operator decides with full information, not after the fact. Rollback is what converts migration from a bet into a trial: a customer can migrate, evaluate against live data, and walk back cleanly if the evaluation fails. No competitor importer offers this, which is why it is classed `UNQ`.

### 3.7 Delta re-sync for parallel-run cutover (`FR-MIG-008`, `F-292`)

During a parallel-run period, the importer re-syncs only records created or changed in the source since the last run, without duplicating previously migrated records (`US-E18-07`) — matched by stable source ID, with updates applied to the previously migrated targets. This is what makes the low-risk cutover pattern practical: migrate the bulk in week one, run both systems while validating, re-sync deltas on a schedule, and make cutover weekend a final small delta instead of a big-bang import. The honest limitation: delta re-sync is one-directional (source → Axiom). Changes made in Axiom during the parallel run are not written back to the source, so the parallel-run plan must designate which system is authoritative for which records until cutover — the tooling narrows the window of double entry; it does not abolish it.

### 3.8 Onboarding (`FR-MIG-009`, `FR-MIG-010`, `F-295` … `F-298`)

Migration ends with users, not records: role-specific onboarding checklists with completion tracking, contextual first-use guidance (`US-E18-08`), configuration templates by industry and company size, and a sample-data environment that is clearly marked and separately deletable — evaluation data must never be mistakable for, or entangled with, migrated production data.

## 4. Requirements coverage

| Module | Requirements | Where specified |
|---|---|---|
| `INT` | `FR-INT-001` … `FR-INT-007` | §1.1 … §1.8 |
| `INT` | `FR-INT-008` | §2 |
| `INT` | `FR-INT-009` | §1.9, §2 |
| `MIG` | `FR-MIG-001` … `FR-MIG-008` | §3.1 … §3.7 |
| `MIG` | `FR-MIG-009`, `FR-MIG-010` | §3.8 |

## Related documents

- [Product scope](01-product-scope.md) — zero-friction adoption as a differentiator; out-of-scope boundaries the connectors respect
- [FRD](03-frd.md) §22–23 — the `INT` and `MIG` requirements this document expands
- [Feature catalogue](04-feature-catalogue.md) E17–E18 — competitive positioning per feature
- [Epics and user stories](05-epics-and-stories.md) E17–E18 — delivery decomposition and acceptance criteria
- [ADR-003 — event backbone](../architecture/adr/ADR-003-event-backbone.md) — outbox, at-least-once delivery, DLQ and replay
- [ADR-007 — external system integration](../architecture/adr/ADR-007-external-system-integration.md) — anti-corruption layer and the CTRM/ETRM capability contract
- [Commodity trading vertical pack](17-vertical-pack-commodity-trading.md) — the pack consuming the CTRM/ETRM connector
- [BFSI vertical pack](12-vertical-pack-bfsi.md) — screening and holdings feeds on the connector framework
- [Data model](09-data-model.md) — entities behind hand-off state and audit

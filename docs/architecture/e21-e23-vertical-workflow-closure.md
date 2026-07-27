# E21-E23 first-party workflow closure

Date: 2026-07-27  
Migration: `V349__mobile_bfsi_commodity_first_party_closure.sql`

## Boundary and architecture

These verticals use the core CRM boundary: HTTP controllers validate transport input, application services own transitions and gates, PostgreSQL owns relational integrity and tenant row security, and each accepted business command records immutable audit evidence plus a transactional outbox event. Vendor delivery is outside the transaction and is represented by durable, retryable state.

No mobile edit, regulated decision, or commodity offer can bypass the application service by relying on frontend state. All tables carry `tenant_id`, foreign keys are tenant-coupled where records cross aggregates, row-level security is forced, and write endpoints reject read-only roles.

## E21 — offline packages and synchronization

- Package generation includes only records visible to the requesting user and is capped at 100 records per selected entity type.
- Every snapshot persists entity, record ID, server version, JSON payload, checksum and cache timestamp. The UI displays age and expiry.
- Each queued mutation has a client idempotency key and a narrow field allow-list; ownership, security and lifecycle fields cannot be edited offline.
- Synchronization rechecks device ownership, active-device status, record visibility and write access. Revoked access produces an explicit rejected item.
- A version mismatch never overwrites silently. It persists the client patch, current server payload, changed fields and both versions in `mobile.sync_conflict`.
- Operators resolve with **Keep server**, **Use offline**, or **Merge**, with a mandatory reason. Resolution rechecks authorization and current version.
- `mobile.sync_run` records submitted, applied, conflicting and rejected counts. Package/device queue states remain inspectable after failure.

## E22 — BFSI governed lifecycle

- New onboarding cases materialize the active KYC requirement master into owned checklist items with due/expiry evidence.
- Activation enumerates missing or expired documents by plain-language name and owner, unresolved screening hits and prohibited risk.
- Screening runs store source, result and hit count. A hit needs an explicit false-positive, confirmed, accepted-risk or not-applicable disposition and rationale.
- Weighted risk factors must total exactly 100; every factor and score is bounded and evidence is stored. Rating thresholds are deterministic and tested.
- Product holdings reference the governed product catalogue. The read model returns current holdings and catalogue whitespace together.
- Suitability assessments expire. Recommendations outside the assessed level create a maker-checker request and cannot self-approve.
- Operational and regulatory exceptions also use independent maker-checker submit/approve/reject decisions with complete rationale evidence.

## E23 — commodity origination and execution handoff

- Counterparty credit limit, exposure and headroom are received values from the named source and are never calculated by CRM. Source and as-of time are visible.
- An offer fails closed when the counterparty is inactive, its master agreement is missing or expired, credit is missing or stale, or received headroom is below notional.
- Term, spot-cargo, tender and structured enquiries persist grade, quantity/tolerance, unit, delivery window/locations, incoterm and tender deadline.
- Indicative pricing stores an expression and is permanently labelled `INDICATIVE - NON-BINDING`; CRM does not calculate settlement, mark-to-market, position or inventory.
- Term sheets are versioned and require independent maker-checker approval before release. Blocked offer attempts persist an operator exception, audit evidence and an outbox event.
- Agreed deals create a connector-neutral handoff with a unique `(enquiry, version)` key. Attempts, retry limit, last error and next attempt are durable. Terminal failures open an exception.
- Acknowledgement stores the external trade reference and closes the handoff evidence loop. The named CTRM adapter remains `PENDING_VENDOR`.

## Operator API and UI

- `/api/v1/mobile/offline/**` and `/mobile`
- `/api/v1/bfsi/**` and `/packs/bfsi`
- `/api/v1/commodity/**` and `/packs/commodity`

The three operator panels are keyboard-accessible, theme-aware, responsive down to the supported mobile breakpoint and use plain-language gate messages. Long evidence and conflict payloads scroll inside their own cards without widening the page.

## Verification contract

- `MobileOfflineServiceTest` proves the offline field allow-list rejects protected fields.
- `BfsiLifecycleServiceTest` proves weighted-risk calculation and fail-closed weight validation.
- `CommodityLifecycleServiceTest` proves stale-credit/agreement gates and that received headroom, rather than a CRM calculation, controls the decision.
- The production frontend build includes TypeScript checking.
- Runtime verification must apply Flyway V349, authenticate through the normal API, execute each workflow and confirm audit/outbox rows under the same tenant.

## External evidence still pending

- Apple/Microsoft store signing and distribution authority for E21.
- A named sanctions/PEP screening-data provider for E22. Manual and imported first-party evidence remains usable.
- A named CTRM/ETRM adapter and its external acknowledgement certification for E23. The vendor-neutral handoff boundary is complete.

## Closure evidence — 2026-07-27

- Flyway V349 applied successfully to `AxiomCrmdb_Dev`; all three new schema families are live and the API health probe is `UP`.
- E21 live run: seven permission-filtered account snapshots, one applied mutation, one deliberate stale-version conflict, and one explicit `SERVER_WINS` disposition persisted as `DISCARDED` rather than silently overwritten.
- E22 live run: four verified KYC items, three clear screenings, a deterministic `LOW` risk result, an `ACTIVE` relationship, holding/whitespace refresh, a current suitability assessment, an approved recommendation, and an exception approved by a different tenant administrator.
- E23 live run: source-mastered agreement/credit evidence, `INDICATIVE - NON-BINDING` pricing, independent term approval, released offer, unique versioned handoff, delivered attempt and `ACKNOWLEDGED` external trade reference. A second incomplete enquiry returned `BLOCKED` and retained its operator exception.
- Database evidence in the 30-minute verification window: 5 offline, 15 BFSI and 11 commodity audit events; 5 offline, 13 BFSI and 8 commodity outbox events.
- Backend suite: 650 tests, 0 failures, 0 errors (2 environment-dependent skips). Frontend release suite: production TypeScript/Vite build plus 4/4 runtime and WCAG tests.
- Browser verification: 1280×720 and 390×844, no horizontal overflow, no off-screen workflow buttons and no console errors. The responsive operator page was left open on the commodity lifecycle for review.

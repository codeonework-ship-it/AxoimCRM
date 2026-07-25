# BFSI vertical pack

This document expands FRD module `BFS` (`FR-BFS-001` … `FR-BFS-013`, epic E22) and features `F-347` … `F-361` into the full specification of the banking, financial services and insurance pack. It exists to prove a strategic claim from [product scope](01-product-scope.md): **industry depth is a configurable pack, not a custom-build project.** If this pack cannot deliver regulated-finance capability without touching core semantics, that claim is false and the strategy needs revisiting ([feature catalogue](04-feature-catalogue.md), summary).

The pack serves two personas defined in [product scope](01-product-scope.md): the **relationship manager** (`RM`) — manage a book of clients, see holdings and life events, act on suitable next-best products — and the **KYC/AML analyst** (`KYC`) — complete onboarding due diligence, risk-rate a client, evidence every decision to a regulator. Both walk through their workflows in §5.

## 1. Pack framework rules

The pack is installed as a governed extension (`FR-BFS-013`, `F-361`, `US-E22-01`). The rules are the framework's contract, and they bind every vertical pack, not only this one:

1. **A pack adds; it never alters.** A pack may add objects, fields, layouts, automation, roles and reports. It may not modify core object semantics. A tenant without the pack behaves identically to a build without the pack — verified as an acceptance criterion, not assumed.
2. **Pack entities live in their own namespace** and reference core entities ([data model §5.1](09-data-model.md)): `RM_BOOK`, `HOUSEHOLD`, `KYC_CASE`, `KYC_DOCUMENT`, `RISK_RATING`, `SCREENING_RUN`/`SCREENING_HIT`, `BENEFICIAL_OWNER`, `PRODUCT_HOLDING`, `SUITABILITY_ASSESSMENT`, `SUITABILITY_OVERRIDE`, `COMMUNICATION_ARCHIVE`, `COMPLAINT`.
3. **Install, configure, version, upgrade and uninstall as a unit.** Pack versions upgrade atomically; a failed upgrade leaves the prior version intact.
4. **Uninstall states exactly what data would be affected** — which entities, how many records, what becomes unreachable — and requires explicit confirmation. Uninstalled pack data follows the soft-delete and retention rules (`FR-GLOBAL-007`); it is not silently destroyed.
5. **Pack capability rides core capability.** Screening providers connect through the connector framework ([integration and migration](13-integration-and-migration.md)); KYC gates are enforced business processes (`FR-AUT-004`); every disposition is audited per `FR-GLOBAL-005`. The pack configures the platform; it does not re-implement it.

The trade-off is stated honestly: building on governed extension points is slower than forking core objects would be, and some conceivable BFSI features will be constrained by what the extension model allows. That constraint is the product. The moment a pack patches core semantics, every tenant is running a different product and the upgrade path is gone.

## 2. Capability specification

### 2.1 Relationship-manager book (`FR-BFS-001`, `F-347`)

An RM gets a consolidated view of their assigned clients: holdings, portfolio value, recent interactions, upcoming reviews and open actions (`US-E22-02`). The book is an exception-first surface, consistent with the product principle — it leads with what needs action, not a wall of client tiles:

- **Due and overdue** — periodic reviews approaching or breached (§2.6), documents expiring (§2.3), suitability assessments lapsing (§2.9).
- **Alerts** — screening results awaiting disposition on the RM's clients (§2.5), complaint clocks running (§2.11).
- **Opportunities** — life-event triggers fired (§2.12), whitespace worth a conversation (§2.8).
- **Relationship hygiene** — clients with no interaction beyond a configured recency threshold, surfaced with the days elapsed.

Portfolio value on the book is a roll-up of sourced holdings (§2.8) and carries the staleness of its weakest feed — a book total is never fresher than the data behind it, and it says so. Book assignment is recorded and reportable; a client transferring between books produces an audited change with effective date, so coverage gaps and handover accountability are reconstructible.

### 2.2 Household and related-party grouping (`FR-BFS-002`, `F-348`)

Clients group into households or related-party groups with defined relationship types (spouse, dependant, trust, controlled entity), and financial values roll up to the group. Roll-ups respect record access exactly as account hierarchy roll-ups do (`FR-ACC-004`): where the viewing RM lacks access to a member, the roll-up indicates restriction rather than silently under-reporting. A client may belong to more than one group (an individual and their family trust); the group membership carries its own validity dates.

### 2.3 KYC onboarding with activation gate (`FR-BFS-003`, `F-349`)

Onboarding is a workflow with a document checklist driven by **client type and risk tier** — an individual retail client and a foreign corporate produce different checklists from the same configuration, maintained by the tenant administrator, not by the vendor.

- Each document carries capture, expiry tracking and a per-document verification status with the verifying analyst recorded.
- **The completion gate is absolute: an incomplete KYC blocks relationship activation, naming the outstanding items and their owner** (`US-E22-03`). The gate is an enforced business process (`FR-AUT-004`), so no path — UI, API, automation, or AI agent — can activate around it.
- A document approaching expiry notifies its owner at a configured threshold; an expired mandatory document moves the relationship into a review state rather than silently leaving stale evidence on file.

### 2.4 Factor-based risk rating (`FR-BFS-004`, `F-350`)

Clients carry a risk rating computed from configurable weighted factors — geography, entity type, industry, product, screening outcome — with the full factor breakdown visible (`US-E22-04`). This is the pack's application of the platform's universal explainability rule (`FR-AIX-008` states it for AI scores; here the computation is deterministic and the same disclosure standard applies). Every rating change is audited with actor and rationale; a rating with no visible basis is a defect. Factor weights are versioned so a historical rating remains explainable under the weights in force when it was computed (`FR-MDM-007` supplies the effective-dating machinery).

### 2.5 Sanctions, PEP and adverse-media screening (`FR-BFS-005`, `F-351`)

The pack integrates with screening providers through the connector framework, recording each screening run, its result, and the disposition of every hit with the reviewer and rationale (`US-E22-05`).

- **A screening hit blocks onboarding progression until dispositioned by an authorized reviewer.** Not warned — blocked. A control that can be clicked through is not a control.
- Disposition (true match / false positive / requires escalation) requires a rationale and is maker-checker eligible (`FR-SEC-010`): the analyst who initiated onboarding cannot disposition their own hit where the tenant so configures.
- Screening re-runs on configurable triggers: periodic review, material detail change, and beneficial-owner addition. Every run is retained — the evidence a regulator wants is the history of runs, not the latest result.
- Dependency, stated honestly: without a screening data provider this requirement is unmet and **the pack is not viable for regulated deployment** (FRD §29). There is no degraded "screening-lite" mode, because a lite version of a sanctions control is a liability, not a feature.

### 2.6 Periodic review by risk tier (`FR-BFS-006`, `F-352`)

Review cycles are scheduled automatically by risk tier — high-risk annually, standard tri-annually, or whatever the tenant's policy dictates — with reminders, escalation on overdue, and an audit trail of each completed review. A completed review re-evaluates the risk rating and re-runs screening; a review that changes the tier reschedules the next cycle from the new tier.

### 2.7 Beneficial ownership (`FR-BFS-007`, `F-353`)

The pack captures ownership and control structures with percentage holdings, identifies ultimate beneficial owners against a configurable threshold, and screens them as related parties. The ownership graph is traversable — an analyst can see *why* a person is flagged as a UBO, through which chain of entities, at what cumulative percentage. Circular ownership structures are detected and surfaced rather than looping.

### 2.8 Product holdings and whitespace (`FR-BFS-008`, `F-354`)

All products held by a client are presented with balances, dates and status, **sourced from core banking or policy systems** — alongside a whitespace view of products not held (`US-E22-06`). The CRM is not the book of record for balances: holdings display their source system and last-sync time, exactly as the commodity pack treats CTRM-mastered data (`FR-CTM-001` sets the precedent). A stale feed shows its staleness; it does not present yesterday's balance as today's fact.

### 2.9 Suitability with documented override (`FR-BFS-009`, `F-355`, `F-356`)

Product recommendations are constrained by a recorded suitability assessment covering objectives, risk tolerance, horizon and knowledge.

- **Recommending outside assessed suitability requires an explicit documented override with reason and approval. An unsuitable recommendation cannot be issued silently** (`US-E22-07`). The override is a first-class record (`SUITABILITY_OVERRIDE`), reportable and audit-ready — the point is not to prevent all exceptions but to make every exception defensible.
- An expired assessment blocks recommendation pending reassessment.
- Next-best-product suggestions (`F-356`), including AI-generated ones, run inside the suitability constraint: the constraint is applied server-side after generation, so an unsuitable product is filtered from the recommendation surface, not merely footnoted. With AI off, rule-based product triggers behave identically (`FR-AIX-013`).

### 2.10 Communication archiving with legal hold (`FR-BFS-010`, `F-357`, `F-358`)

All client communications are archived in an immutable store with configurable retention meeting regulatory minimums, searchable by client, RM, date and content (`US-E22-08`). Legal hold suspends deletion regardless of retention policy, and the hold itself — who placed it, when, over what scope — is audited. Consent and channel preferences ride the core register (`FR-ACC-011`, `FR-AUD-009`); the pack adds the retention and hold regime, not a second consent system. Archive immutability follows the audit-store standard (`FR-AUD-001`): no role, including platform operator, holds delete rights inside the retention window.

### 2.11 Complaint handling with regulatory clock (`FR-BFS-011`, `F-359`)

Complaints run a dedicated workflow with a **regulatory response clock**, mandatory categorization, root-cause capture, resolution recording and a regulatory reporting extract. The clock differs from a service SLA (`FR-CAS-004`) in one deliberate way: it does not pause for customer-pending status unless the applicable regulation says it may — the clock models the regulator's rules, not the tenant's convenience. Breach and imminent-breach escalate per configured rules, and the reporting extract reconciles to the complaint records it summarizes.

### 2.12 Life-event triggers (`FR-BFS-012`, `F-360`)

Configurable triggers — product maturity, recorded life event, threshold crossing — generate opportunities for the RM, **suitability-constrained** per §2.9. A trigger states its basis on the opportunity it creates ("fixed deposit matures 2026-09-30"), consistent with the explainability principle: the RM should never wonder why an opportunity appeared in their queue.

## 3. Tenant configuration surface

Everything a regulator will ask about is tenant policy, and everything that is tenant policy is configurable by a trained administrator through the product — the pack honours `FR-ADM-014` exactly as the core does. The vendor ships defaults; the tenant owns the regime.

| Configuration | Governs | Requirement |
|---|---|---|
| Document checklist templates by client type × risk tier | What KYC onboarding demands (§2.3) | `FR-BFS-003` |
| Risk factor set, weights and tier boundaries | How ratings compute and band (§2.4) | `FR-BFS-004` |
| Screening triggers, providers and disposition roles | When screening runs and who may clear hits (§2.5) | `FR-BFS-005` |
| Review interval per risk tier, escalation recipients | Periodic review cadence (§2.6) | `FR-BFS-006` |
| UBO percentage threshold | Who counts as an ultimate beneficial owner (§2.7) | `FR-BFS-007` |
| Suitability dimensions, validity period, override approval routing | The recommendation constraint (§2.9) | `FR-BFS-009` |
| Archive retention periods per communication class | How long evidence is held (§2.10) | `FR-BFS-010` |
| Complaint categories, regulatory clock durations, extract format | The complaint regime (§2.11) | `FR-BFS-011` |
| Life-event trigger definitions | Which events generate opportunities (§2.12) | `FR-BFS-012` |

Configuration changes are versioned, auditable (`FR-ADM-008`) and promotable through sandbox and change sets (`FR-ADM-006`) like any other configuration. A checklist template change applies to onboarding cases opened after the change; in-flight cases complete under the version they started with, so an analyst is never moved mid-case to a checklist they were not working against.

### 3.1 Pack reporting

The pack ships its own report set on the standard reporting platform ([reporting and analytics](14-reporting-and-analytics.md)) — a pack may add reports, and these are the ones a compliance function needs on day one: KYC case aging by status and owner, document expiry horizon, screening runs and hit dispositions by outcome and reviewer, periodic review completion versus schedule, risk-rating distribution and migration between tiers, suitability overrides with approver, complaint clock position, and RM book coverage. Each is a normal report — drillable, permission-aware and exportable under export governance (`FR-RPT-010`).

## 4. What the pack deliberately does not do

| Not built | Why |
|---|---|
| Core banking, policy administration, portfolio accounting | Systems of record exist; the pack displays holdings with provenance (§2.8) |
| Transaction monitoring / AML alerting on payment flows | Requires transaction-level data the CRM does not hold; a partial implementation would be a false control |
| Regulatory reporting engines (returns, filings) | The pack produces extracts (§2.11); filing is the compliance platform's job |
| Screening list management | The screening provider owns list content and matching; the pack owns runs, hits and dispositions |

The pattern is the same one that governs the commodity trading pack: the CRM owns the relationship and the evidence trail, and integrates with the systems that own the money.

## 5. Persona walk-throughs

### 5.1 A relationship manager's morning

An RM opens their book (§2.1). The exception queue shows: two periodic reviews due this month, one client's passport expiring in 30 days, and a life-event trigger — a household member's fixed deposit matures next week (§2.12). The maturity trigger has generated an opportunity, pre-constrained to products within the client's assessed suitability (§2.9), stating its basis.

Opening the client: holdings from the core banking feed with last-sync timestamp (§2.8), the whitespace view showing no investment product held, household roll-up across the client, spouse and family trust (§2.2). The RM prepares for the call — with AI enabled, a gold-marked summary with citations ([AI capabilities](11-ai-capabilities.md) §2.1); with AI off, the same timeline and holdings data, unsummarized (`FR-AIX-013`).

The client wants a product outside their assessed risk tolerance. The RM cannot issue the recommendation silently: the system requires a documented override with reason, routed for approval (§2.9). The RM records the discussion; it lands in the communication archive under the retention regime (§2.10). Every number the RM saw, every recommendation made, every constraint applied is now evidence.

### 5.2 A KYC analyst's onboarding case

A new corporate client enters onboarding. The checklist generates from client type (foreign corporate) and provisional risk tier: certificate of incorporation, register of directors, UBO declarations, source-of-funds evidence (§2.3). The analyst captures documents; each gets a verification status and expiry date.

The UBO declaration reveals a holding company chain. The analyst records the ownership structure; the system computes cumulative percentages and flags one individual above the UBO threshold (§2.7). That individual is screened as a related party (§2.5). The screening run returns a PEP hit.

**Onboarding progression is now blocked.** The analyst investigates, concludes it is a genuine match to a low-level political exposure, and dispositions the hit with rationale — but tenant configuration applies maker-checker, so a senior reviewer approves the disposition (§2.5, `FR-SEC-010`). The risk rating recomputes with the PEP factor visible in the breakdown, landing the client in the high-risk tier (§2.4) — which regenerates the checklist with enhanced due-diligence items and schedules the first periodic review at the high-risk interval (§2.6).

Only when every checklist item is verified and every hit dispositioned does the activation gate open (§2.3). Eighteen months later, a regulator asks why this client was onboarded. The answer is not a reconstruction: every run, hit, disposition, rating factor, approval and document version is on file, in order, with actors and timestamps.

### 5.3 What the walk-throughs demonstrate

The two narratives exercise every control class the pack claims, and each moment is a testable behaviour, not a UX aspiration:

1. **Gates that cannot be talked around** — activation blocked on incomplete KYC, progression blocked on an undispositioned hit, recommendation blocked outside suitability. All server-side, all naming what is outstanding.
2. **Separation of doing and approving** — hit disposition and suitability override both route past the initiator (`FR-SEC-010`).
3. **Explainability of every judgment** — the risk rating's factor breakdown, the trigger's stated basis, the override's recorded reason.
4. **Evidence as a by-product of work** — neither persona did anything *for* the audit trail; the trail is what their normal workflow leaves behind. That is the difference between a compliance product and a compliance burden.

## 6. Requirements coverage

| Requirement | Where specified |
|---|---|
| `FR-BFS-001` RM book | §2.1, §5.1 |
| `FR-BFS-002` household grouping | §2.2 |
| `FR-BFS-003` KYC onboarding | §2.3, §5.2 |
| `FR-BFS-004` risk rating | §2.4 |
| `FR-BFS-005` screening | §2.5, §5.2 |
| `FR-BFS-006` periodic review | §2.6 |
| `FR-BFS-007` beneficial ownership | §2.7 |
| `FR-BFS-008` product holdings | §2.8 |
| `FR-BFS-009` suitability | §2.9 |
| `FR-BFS-010` communication archiving | §2.10 |
| `FR-BFS-011` complaint handling | §2.11 |
| `FR-BFS-012` life-event triggers | §2.12 |
| `FR-BFS-013` pack framework | §1 |

## Related documents

- [Product scope](01-product-scope.md) — vertical packs as a differentiator; `RM` and `KYC` personas
- [FRD](03-frd.md) §27 — the `BFS` requirements this document expands
- [Feature catalogue](04-feature-catalogue.md) E22 — competitive positioning per feature
- [Epics and user stories](05-epics-and-stories.md) E22 — delivery decomposition and acceptance criteria
- [Data model](09-data-model.md) §5.1 — BFSI pack entities
- [RBAC and sharing model](08-rbac-and-sharing-model.md) — maker-checker and segregation of duties the pack relies on
- [AI capabilities](11-ai-capabilities.md) — suitability-constrained recommendations and AI-off behaviour
- [Integration and migration](13-integration-and-migration.md) — the connector framework screening and holdings feeds ride on
- [Commodity trading vertical pack](17-vertical-pack-commodity-trading.md) — the sibling pack, same framework, same boundary discipline

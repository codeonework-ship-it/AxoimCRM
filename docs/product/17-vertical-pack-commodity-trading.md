# Vertical pack — commodity trading (E23)

Origination and relationship management for physical and paper commodity trading: counterparties, master agreements, credit-gated pipelines, tenders, cargo enquiries, indicative pricing, and a reliable hand-off to the trading system of record. The pack is additive to the core — installed, versioned and removable as a unit per `FR-BFS-013` — and is specified as FRD module CTM (`FR-CTM-001` … `FR-CTM-012`, [FRD](03-frd.md) §27a; features `F-362` … `F-373` in the [feature catalogue](04-feature-catalogue.md)).

The pack exists because trading firms run their commercial relationships in spreadsheets and their trades in a CTRM/ETRM, and nothing owns the space between: who we are talking to, under what agreements, on which tenders, at what indicative levels, and whether credit allows the conversation to continue. That space is a CRM problem. Everything after it is not.

---

## 1. The governing boundary rule

**This pack covers the pre-deal relationship and origination layer only. The CRM never becomes a second trading system.**

Axiom does not, in any configuration of this pack:

- capture trades
- compute positions or value portfolios
- calculate credit exposure
- schedule movements or nominations
- hold inventory
- settle or invoice

All of that belongs to the customer's CTRM/ETRM system of record, integrated as an external system per `FR-CTM-010` and [ADR-007](../architecture/adr/ADR-007-external-system-integration.md). **The hand-off point is "deal agreed"** — the origination closes as won, a structured hand-off goes to the trading system, a trade reference comes back, and the CRM's transactional involvement ends.

The rule is enforced structurally, not by convention: there is deliberately **no exposure, position, valuation or settlement entity anywhere in the data model** ([data model](09-data-model.md) §5.2), and [FRD](03-frd.md) constraint 6 forbids any requirement that would create one. A CRM that computes positions or credit exposure from partial data has become a second, worse trading system — confidently wrong at exactly the moment a trader most needs it right ([product scope](01-product-scope.md), out-of-scope table).

## 2. What this pack is NOT

Stated up front, because every one of these will be asked for by someone:

| Not this | Why not | Where it lives |
|---|---|---|
| Trade capture / deal entry | The origination hand-off is a *proposal to the trading system*, not a trade ticket | CTRM/ETRM |
| Position keeping, P&L, mark-to-market | Requires positions, curves and netting the CRM does not have and must not acquire | CTRM/ETRM, risk system |
| Credit exposure calculation | The CRM **displays** credit; it never computes it (`FR-CTM-003`) | CTRM/ETRM, credit system |
| Scheduling, nominations, logistics | A cargo *enquiry* is a sales attribute, not a movement (`FR-CTM-006`) | CTRM/ETRM, scheduling |
| Inventory and storage | No inventory entity exists in the model | CTRM/ETRM |
| Settlement, invoicing, actualization | Post-trade by definition | CTRM/ETRM, ERP |
| Market data terminal / live curves | Indicative pricing records an agreed *basis*, not a computed value (`FR-CTM-007`) | Market data provider |
| A bundled CTRM | The connector is generic; no trading system ships in the box | Customer's chosen CTRM/ETRM |

If a requirement appears to need one of these inside the CRM, the requirement is wrong, not the boundary.

### Who the pack serves

The pack's primary users are **trade originators** (the deal-pursuit surface: pipelines, tenders, enquiries, indications), **credit and compliance officers** (the gates: master agreement status and credit headroom as enforced stage-exit criteria), and **desk heads** (the analytics: win rate, conversion, volume won versus bid). Traders, schedulers and risk managers are deliberately *not* users of this pack — their systems are its integration counterparties, not its audience.

### Installation and lifecycle

The pack installs per `FR-BFS-013`: as a unit, versioned, upgradable and removable. It adds entities, fields, layouts, pipelines, roles and reports in its own namespace ([data model](09-data-model.md) §5) and **may not modify core object semantics** — an account without the pack behaves identically after the pack is installed beside it. Uninstalling states exactly what data would be affected and requires explicit confirmation. Commercially, the pack is a per-tenant entitlement ([tenancy and licensing](16-tenancy-licensing-and-deployment.md) §4).

## 3. Counterparty extension — `FR-CTM-001` · `F-362`

`COUNTERPARTY_PROFILE` extends the core account ([data model](09-data-model.md) §5.2) with what a trading relationship needs: legal entity identifiers, trading entities, KYC/onboarding status, master agreement references, approved commodities and approved trading venues.

The counterparty master may be owned by the CTRM. Where it is, the CRM treats those fields as **read-only and displays their source and last-sync time** — the CRM is a faithful mirror of the trading system's counterparty record, never a competing version of it. Sync comes through connector capability C1 (§11).

## 4. Master agreements — `FR-CTM-002` · `F-363`

A register of master agreements — ISDA, GTC, GMRA or equivalent — with type, counterparty entity, execution date, governing law, status and expiry. Agreement status is available as a **gate on origination progression**: advancing an origination past a configured stage without an executed master agreement is blocked, and the block names the missing agreement rather than failing generically.

This is deliberately a register plus a gate, not a contract-drafting or negotiation workspace. Where the CTRM masters agreements, they arrive read-only via connector capability C3.

## 5. The credit gate — `FR-CTM-003` · `F-364`

The pack displays counterparty credit limit, current utilisation and available headroom, sourced from the CTRM, and uses headroom as a configurable stage-exit criterion. Three rules make this defensible to a credit officer and an auditor:

1. **The CRM displays credit; it does not compute it.** The value is a `CREDIT_SNAPSHOT` — a cache, not a source — carrying `source_system`, `as_of` and a staleness indicator ([data model](09-data-model.md) §5.2), shown as received.
2. **The gate fails closed.** Credit data stale beyond the configured threshold, or unavailable, blocks the gate and says why. It never silently passes on missing data, and never presents a stale number as current. A credit gate that passes when data is missing is a documented control that controls nothing — exactly the finding an auditor is looking for ([ADR-007](../architecture/adr/ADR-007-external-system-integration.md), rationale).
3. **Staleness is honest by construction.** Cached credit is stale by definition; showing `as_of` makes that visible rather than hiding it. Users will occasionally find this inconvenient. That is the correct trade.

## 6. Origination pipelines — `FR-CTM-004` · `F-365`, `FR-CTM-006` · `F-367`

`ORIGINATION` extends the core opportunity with four record types, each with its own pipeline, stages and exit criteria, because these deals do not originate the same way:

| Type | Shape of the pursuit |
|---|---|
| **Term contract** | Long negotiation, agreement and credit gates matter most |
| **Spot / cargo enquiry** | Fast, specific: commodity, grade, quantity and tolerance, delivery window, load and discharge locations, incoterm, indicative pricing basis |
| **Tender participation** | Deadline-driven, document-heavy (§7) |
| **Structured / paper** | Bespoke, approval-heavy |

Cargo and parcel details are captured **as enquiry attributes on the origination — not as a nomination or a scheduled movement**. The distinction is the boundary rule in miniature: the CRM records what the customer asked for; operational scheduling of what was eventually agreed happens in the CTRM.

Stage progression uses the core pipeline machinery — the master-agreement gate (§4) and credit gate (§5) plug in as exit criteria, not as parallel process engines.

## 7. Tender management — `FR-CTM-005` · `F-366`

Tender participation tracked end to end: issuing body, tender reference, submission deadline, required documents, bid submitted, award outcome, awarded counterparty and price where disclosed.

The submission deadline is the organizing fact: it drives escalating reminders as it approaches, and **a tender not submitted by its deadline auto-closes as lapsed with that reason recorded**. A lapsed tender is commercial signal — analytics (§13) can then separate "we lost" from "we never showed up", which are very different problems to fix.

## 8. Indicative pricing — `FR-CTM-007` · `F-368`

Quotes carry fixed or formula-based indications. A formula is expressed as **index, differential, quotation period and settlement convention**, rendered as a human-readable expression — the way the desk would say it, not a serialized object.

Two hard rules:

- Every indication is **explicitly labelled indicative and non-binding**.
- **The CRM computes no settlement price and no mark-to-market value.** It records the agreed pricing *basis* for hand-off; valuing it requires curves and conventions that live in the trading and market-data systems. The pack captures the sentence, never the number.

## 9. Brokers, agents and intermediaries — `FR-CTM-008` · `F-369`

Brokers, agents and shipping intermediaries are modelled as related parties on an origination, with role and commission basis, and performance reporting on introduced volume. This answers the questions a desk head actually asks — which intermediaries introduce volume that closes, and on what terms — without turning the CRM into a commission-settlement system, which is an ERP concern.

## 10. The deal-agreed hand-off — `FR-CTM-009` · `F-370`

When an origination closes as won, the pack emits a structured hand-off to the CTRM containing counterparty, commodity, quantity and tolerance, delivery terms, pricing basis, agreed period and the originating record reference. Its delivery contract:

- **Asynchronous** — dispatched through the outbox ([ADR-003](../architecture/adr/ADR-003-event-backbone.md)), never synchronously from the close action. A slow CTRM must not fail a rep's attempt to close a deal.
- **Idempotent** — keyed on `(origination_id, version)`, so duplicate delivery is a no-op.
- **Acknowledged** — the origination is **not reported as handed off until acknowledgement is received**. The returned CTRM trade reference is stored on the origination, giving a bidirectional link between the relationship record and the trade record.
- **Never silently dropped** — an unacknowledged hand-off surfaces on an exception queue with the failure reason and a retry action (`DEAL_HANDOFF` carries `status`, `attempts` and `last_error` for exactly this).

"Pending acknowledgement" is a **normal state, not an error** — the UI treats it as such, because async delivery means the trade reference is legitimately absent for a window after close.

## 11. The generic CTRM/ETRM connector — `FR-CTM-010` · `F-371`

Integration with the trading system is a **generic connector against a published five-capability contract**, not an integration with any single vendor's API. The contract, its rationale and its failure semantics are specified in [ADR-007](../architecture/adr/ADR-007-external-system-integration.md); this section only orients:

| # | Capability | Direction |
|---|---|---|
| **C1** | Counterparty master sync | CTRM → CRM |
| **C2** | Credit limit and utilisation read | CTRM → CRM |
| **C3** | Master agreement read | CTRM → CRM |
| **C4** | Deal-agreed hand-off | CRM → CTRM |
| **C5** | Trade status callback (confirm, amend, cancel) | CTRM → CRM |

Any CTRM/ETRM implementing the contract is supported through **an adapter — a new adapter per vendor, never a change to the contract or the pack**. A specific product is one implementation, never a hard dependency; the contract is kept deliberately narrow because every added capability is a tax on every future adapter.

Degradation is defined, not discovered: with the connector unavailable, **the CRM remains fully usable** for relationship and origination work — credit gates fail closed per §5, hand-offs queue for later delivery, and C5 callbacks update downstream state on the origination only when connectivity returns. The honest cost, per [ADR-007](../architecture/adr/ADR-007-external-system-integration.md): the first adapter is genuinely more work than a direct integration would have been; the payoff arrives with the second.

## 12. Commodity reference data — `FR-CTM-011` · `F-372`

The pack maintains commodity, grade, unit of measure, quality specification and location reference data, with conversion factors between units — enough for an origination to be specified precisely and handed off unambiguously. Reference data is **sourced from or reconciled against the CTRM**: the trading system's product master is authoritative where one exists, and the CRM does not invent a competing commodity taxonomy.

## 13. Tender and origination analytics — `FR-CTM-012` · `F-373`

Built on the reporting read model ([ADR-008](../architecture/adr/ADR-008-reporting-read-model.md)), with the counting basis published for every measure, per the explainability principle in [product scope](01-product-scope.md):

- **Tender win rate** by issuing body, commodity and region — with lapsed (never submitted) separated from lost
- **Origination conversion** by type (term, spot/cargo, tender, structured)
- **Volume won versus volume bid**

Deliberately absent: traded-volume, P&L or exposure analytics. Those are trading-system reports over trading-system data. The pack reports on the funnel it owns and stops at the boundary, like everything else in this document.

## Related documents

- [FRD](03-frd.md) — §27a, `FR-CTM-001` … `FR-CTM-012`, and constraint 6 (the boundary rule)
- [Feature catalogue](04-feature-catalogue.md) — `F-362` … `F-373`, all UNQ class: no competitor equivalent exists
- [Epics and stories](05-epics-and-stories.md) — E23, `US-E23-01` … `US-E23-08`
- [ADR-007 — External system integration](../architecture/adr/ADR-007-external-system-integration.md) — the connector contract C1–C5, failure table and alternatives considered
- [ADR-003 — Event backbone](../architecture/adr/ADR-003-event-backbone.md) — the outbox the hand-off rides on
- [Data model](09-data-model.md) — §5.2, the pack's entities and the deliberate absence of trading entities
- [Product scope](01-product-scope.md) — the out-of-scope commitment this pack must never erode
- [Tenancy, licensing and deployment](16-tenancy-licensing-and-deployment.md) — packs as entitlements

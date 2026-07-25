# ADR-007 — External system integration and the CTRM/ETRM connector

**Status:** Accepted · **Date:** 2026-07-25

## Context

Axiom integrates with systems it does not own and cannot control: CTRM/ETRM, ERP and billing, e-signature, email and calendar, telephony, marketing automation, enrichment and screening providers. All of them will be slow, be unavailable, and change shape without notice.

The CTRM/ETRM case is the most demanding and drives this decision. The commodity trading vertical pack (`E23`) covers **origination only**; the trading system of record owns trade capture, position, valuation, risk, scheduling, inventory and settlement. The product decision was explicit: build a **generic CTRM/ETRM connector**, with a specific product such as Octane as one implementation — not an Octane-specific integration.

There is a strong pull in the opposite direction. When one CTRM is known, available and in-house, integrating directly against its API is faster, and the resulting design is invariably shaped by that API.

## Decision

**Anti-corruption layer with a narrow capability contract defined in our vocabulary, one adapter per external product, and defined degradation for every failure mode.**

1. Each external system class has a **capability contract** expressed in Axiom's domain language. External models never enter core domain code.
2. **Adapters** translate between contract and vendor API. Adding a vendor is a new adapter, not a change to the contract.
3. Outbound integration goes through the **outbox** ([ADR-003](ADR-003-event-backbone.md)) and the integration dispatch workers — never synchronously from a request thread.
4. Every outbound operation is **idempotent**, keyed so redelivery is a no-op.
5. Every integration has a **defined behaviour when the external system is absent**. "The product stops" is not one of them.
6. **No shared database with any external system. Ever.**

### The CTRM/ETRM capability contract

Deliberately narrow — five capabilities. A wide contract is a coupling surface.

| # | Capability | Direction | Semantics |
|---|---|---|---|
| **C1** | Counterparty master sync | CTRM → CRM | CTRM-mastered fields are read-only in the CRM and display source and sync time |
| **C2** | Credit limit and utilisation read | CTRM → CRM | Cached as `CREDIT_SNAPSHOT` with `as_of` and staleness. **The CRM never computes credit** |
| **C3** | Master agreement read | CTRM → CRM | Status gates origination progression (`FR-CTM-002`) |
| **C4** | Deal-agreed hand-off | CRM → CTRM | Async, idempotent, acknowledged; returns a trade reference stored on the origination |
| **C5** | Trade status callback | CTRM → CRM | Confirm, amend, cancel; updates downstream state on the origination only |

### Failure behaviour

| Failure | Behaviour |
|---|---|
| CTRM unavailable | CRM **fully usable** for relationship and origination work |
| Credit stale or unreachable | Gate **fails closed**, stating reason and as-of time (`FR-CTM-003`) |
| Hand-off unacknowledged | Queued, retried with backoff, surfaced on an exception queue. Origination is **not** reported as handed off until acknowledged |
| Duplicate delivery | Idempotency key `(origination_id, version)` makes it a no-op |
| Vendor schema change | Absorbed in the adapter; contract and core unchanged |
| Sustained failure | Circuit breaker opens; surfaced to a human within a defined window (`FR-INT-009`) |

## Rationale

**Why an anti-corruption layer.** External vocabularies are not ours. A CTRM's notion of a counterparty carries trading-system concerns — netting sets, position keys, settlement conventions — that have no place in a CRM's account model. Let those in and the core domain slowly becomes a projection of whichever external system was integrated first, at which point integrating the second one requires changing the core.

**Why a narrow contract.** Every capability added to the contract is a capability every future adapter must implement. Five capabilities can be implemented against a mid-tier ETRM in weeks. Twenty-five could not be, and the generic connector would become generic in name only.

**Why credit is read, never computed.** This is the boundary that keeps the CRM from becoming a second, worse trading system. Credit exposure calculation depends on positions, mark-to-market, netting agreements and collateral — none of which the CRM has or should have. Displaying a cached number with its `as_of` timestamp is honest; recomputing it from partial data would be confidently wrong, and would be wrong specifically at the moment a trader most needs it right.

**Why fail closed on stale credit.** A credit gate that passes when data is missing is worse than no gate: it creates a documented control that does not control anything, which is exactly the kind of finding an auditor is looking for. Failing closed is occasionally inconvenient and always defensible.

**Why never a shared database.** A shared schema between two products couples their release cycles permanently. Each product's refactoring becomes the other's outage; each one's migration needs the other's sign-off. It is the fastest integration to build and the one that is never subsequently removable.

## Consequences

**Positive**
- Any CTRM/ETRM implementing the contract is supported; the CRM is sellable to firms running any trading system
- Core domain stays clean
- Vendor API changes are absorbed at the edge
- Failure isolation is designed rather than discovered
- The CRM and the trading system version independently

**Negative, stated honestly**
- **An adapter is genuinely more work than a direct integration**, and the first one carries the contract-design cost with none of the reuse benefit. The payoff is entirely in the second and third adapters.
- The contract's least-common-denominator nature means a specific CTRM's richer capabilities are unavailable without extending the contract for everyone.
- Cached credit data is stale by construction. Displaying staleness honestly makes this visible to users, who will occasionally find it inconvenient.
- Async hand-off means the trade reference is not available immediately after closing an origination. The UI must handle "pending acknowledgement" as a normal state, not an error.
- Every adapter needs its own test suite against a vendor sandbox — real, ongoing cost.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Direct Octane-specific integration** | Fastest, and shapes the contract around one vendor's API. Every other CTRM then becomes a custom project — the outcome the generic-connector decision exists to prevent |
| **Shared database with the CTRM** | Permanent coupling of release cycles; each product's refactor becomes the other's outage |
| **Synchronous hand-off on origination close** | Couples the CRM's availability to the CTRM's. A slow CTRM would fail a rep's attempt to close a deal |
| **File-based batch exchange only** | Simple and widely supported, but latency and error handling are poor, and there is no acknowledgement path. Retained as *one adapter implementation*, not as the contract |
| **Third-party iPaaS for all integration** | Adds a dependency that must itself be self-hostable for sovereign deployments, and moves business-critical failure handling outside our observability |

## Compliance

- Every failure mode in the table above is an explicit test case in the [acceptance test catalogue](../../product/06-acceptance-tests.md).
- **The credit-gate fail-closed path is a mandatory test**, verified with the external system deliberately unavailable and with deliberately stale data.
- Adding a capability to the CTRM contract requires an ADR amendment — it is a change to what every adapter must implement.
- Related: `FR-CTM-001` … `FR-CTM-012`, `FR-INT-008`, `FR-INT-009`, `FR-CTR-011`; [commodity trading pack](../../product/17-vertical-pack-commodity-trading.md).

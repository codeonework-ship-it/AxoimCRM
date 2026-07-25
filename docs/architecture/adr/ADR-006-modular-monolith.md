# ADR-006 — Modular monolith rather than microservices

**Status:** Accepted · **Date:** 2026-07-25

## Context

Axiom is an enterprise B2B CRM with 373 catalogued features, 187 of them P0. It must scale horizontally and vertically, integrate with external systems of record, and be operable by a team that does not yet exist at the scale a large service estate demands.

"Enterprise-standard architecture" is frequently read as "microservices". That reading deserves to be examined rather than assumed, because the CRM domain has a specific property that changes the answer.

## Decision

**A modular monolith with build-enforced module boundaries, deployed as horizontally scalable stateless instances, with a defined extraction path for specific components when measurement justifies it.**

1. One deployable artifact, started in one of three roles: **API**, **worker**, **scheduler**.
2. Modules correspond one-to-one with FRD module codes, so every requirement has an unambiguous owner.
3. Boundary rules **B1–B7** ([system design](../system-design.md) §3.2) are enforced by an architecture test that **fails the build**.
4. Layering: interface → vertical packs → business modules → platform modules. Dependencies point downward only. **No cycles.**
5. Business modules communicate via domain events where eventual consistency is acceptable; synchronous cross-module calls are permitted only within one consistency boundary.
6. **No core module may depend on a vertical pack** (rule **B6**).
7. Extraction candidates, in order: AI orchestration, migration engine, reporting/query, integration dispatch, search indexing.
8. **Never extracted:** account, contact, lead, opportunity, quote, order, contract.

## Rationale

**The domain decides this, not preference.** Lead → account → opportunity → quote → order is a single transactional cluster. Converting a lead atomically creates an account, a contact and an opportunity (`FR-LED-011`); a quote syncs to its opportunity (`FR-CPQ-003`); a forecast must reconcile exactly to its constituent opportunities (`FR-FCT-005`). Split these into services and every one of those operations becomes a saga with compensating transactions, partial-failure states and a new class of user-visible inconsistency — in exchange for nothing, because they scale together and change together.

**Microservices solve organizational scaling, not technical scaling.** Their real payoff is independent deployment by independent teams. A team building a first release does not have that problem, and adopting the solution early buys the entire operational cost with none of the benefit: distributed tracing to debug a record save, network partitions inside a business transaction, version skew between services, and an integration test suite that needs the whole estate running.

**The actual technical scaling problem is workload separation, and it is solved without decomposition.** Force **D2** — interactive requests at 50 ms alongside migrations at hours — is handled by separating the request tier from the work tier ([system design](../system-design.md) §4.1). Each worker class already has its own queue, concurrency limit and scaling policy. That delivers the independent scaling microservices are usually reached for, without splitting the transactional core.

**Precedent.** The sibling Octane CTRM platform made the same call — "a deliberately modular monolith… transactional consistency and straightforward operations while preserving module boundaries that can later become services where scale or ownership warrants it" — and it has held for a comparable enterprise workload. Reusing a validated architectural decision is worth more than re-deriving it.

**The boundary rules are what make this defensible rather than an excuse.** A monolith without enforced boundaries becomes a big ball of mud, and "modular monolith" becomes the label people use while it happens. Rules B1–B7 fail the build, not a review — because a boundary that depends on reviewer vigilance under deadline is not a boundary. Rule **B4** (no cycles) is the load-bearing one: as long as the dependency graph stays acyclic and downward, any module can be extracted later at a cost proportional to its interface, not its history.

## Consequences

**Positive**
- Transactional consistency where the domain requires it, with no sagas
- One deployment, one test suite, one trace — an order of magnitude cheaper to operate
- Local calls: no network latency or partition inside a business operation
- Refactoring across module boundaries is a compiler-checked operation
- Horizontal scaling is fully available: stateless instances, no affinity
- Extraction stays cheap because the graph is acyclic and the workers already run as separate roles

**Negative, stated honestly**
- **The whole artifact deploys together.** A change to the campaign module redeploys the opportunity module. Mitigated by fast pipelines and rolling deploys, not eliminated.
- **One runtime, one language.** A component better served by a different technology cannot use it without extraction.
- **A memory leak or crash affects everything in that instance.** Mitigated by N+1 replicas and health gating.
- **Boundary enforcement requires ongoing discipline.** The architecture test must be maintained; a team that starts adding exemptions has begun the slide to a ball of mud, and there is no technical control that prevents a determined team from doing so.
- **Team scaling has a ceiling.** Beyond roughly 30–40 engineers, contention on one codebase becomes real. That is the point at which extraction is justified by organizational need rather than technical need — and it is a good problem to have.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Microservices from the start** | Sagas throughout the transactional core; distributed debugging; operational cost with no offsetting benefit at current team size. The classic distributed-monolith failure mode is the likely outcome |
| **Unstructured monolith** | Becomes unmaintainable; no extraction path; the thing "modular monolith" is defined against |
| **Serverless functions** | Poor fit for a stateful transactional domain; cold starts on interactive paths; connection-pool exhaustion against a relational primary |
| **Service per bounded context (5–6 services)** | Closer to defensible, but the CRM core is *one* bounded context. Splitting it by object rather than by boundary is arbitrary and cuts through transactions |
| **Modular monolith with in-process modules only, no worker tier** | Fails force **D2**: a migration would compete with interactive requests for the same thread pool |

## Compliance

- The architecture test enforcing B1–B7 is part of the build. **A failing boundary check blocks merge.**
- Any exemption requires an explicit, dated, reviewed entry — not a suppression annotation.
- Extraction of any component requires a measurement showing the need, recorded as a new ADR.
- Related: [system design](../system-design.md) §3; [ADR-001](ADR-001-tenancy-isolation.md) rule B7; [ADR-003](ADR-003-event-backbone.md).

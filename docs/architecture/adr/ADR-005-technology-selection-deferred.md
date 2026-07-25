# ADR-005 — Technology selection

**Status:** Proposed — recommendation stated, decision not yet ratified · **Date:** 2026-07-25

## Context

The [FRD](../../product/03-frd.md) is deliberately stack-agnostic: requirements bind to capabilities and observable behaviour, never to frameworks. The [system design](../system-design.md) is likewise expressed in terms of tiers, boundaries and contracts rather than products.

That was the right sequencing — requirements should not be shaped by a stack chosen before they were understood. But an architecture that is *only* abstract cannot be built, and several architectural questions (search engine, event broker, analytical store) cannot be closed without knowing the stack. This ADR records a **recommendation with its reasoning**, so the decision can be ratified deliberately rather than made by accident in the first sprint.

## Decision

**Deferred, with a recommended reference stack.** ADRs 001–004 and 006–008 hold regardless of which stack is chosen; none of them depends on a specific technology.

### Evaluation criteria, in priority order

1. **Team operational fluency** — a stack the team already runs well in production beats a marginally better one it does not
2. **Self-hostability of every component** — sovereign deployment ([ADR-001](ADR-001-tenancy-isolation.md)) means the customer runs the entire stack. Any component that is only available as a managed cloud service disqualifies itself
3. **Relational transactional strength** — [ADR-006](ADR-006-modular-monolith.md) depends on genuine ACID transactions across the CRM core
4. **Row-level security support** — [ADR-001](ADR-001-tenancy-isolation.md) requires database-level tenant enforcement as an independent second layer
5. **Mature architecture-boundary enforcement tooling** — boundary rules B1–B7 must fail the build, not a review
6. **Long-term maintainability and hiring depth** — this is a decade-scale product
7. **Ecosystem for the specific hard parts** — metadata-driven persistence, partitioning, full-text search, background job processing

### Recommended reference stack

| Layer | Recommendation | Primary reason |
|---|---|---|
| Backend language/runtime | **Java 21** | Criteria 1, 3, 6 — proven in-house on Octane; strongest ecosystem for the hard parts |
| Application framework | **Spring Boot** | Criteria 1, 5, 7 — mature boundary tooling, transaction management, job processing |
| Database | **PostgreSQL 17+** | Criteria 2, 3, 4 — native row-level security, table partitioning, strong full-text search, freely self-hostable |
| Schema migration | **Flyway** | Criterion 1 — the Octane convention, versioned and reviewable |
| Frontend | **React + TypeScript + Vite** | Criteria 1, 6 |
| Styling | **Tailwind CSS** | Criterion 1 |
| E2E testing | **Playwright** | Criterion 1 |
| Unit testing | **JUnit + Vitest** | Criterion 1 |
| API documentation | **OpenAPI / Swagger** | `FR-INT-001` requires a published, versioned specification |
| Observability | **Micrometer + Prometheus**, structured logging, distributed tracing | `FR-AUD-014` |
| Packaging | **Docker + Compose** for dev/sovereign; orchestrated containers for pooled SaaS | Criterion 2 |
| Environments | `dev` / `qa` / `uat` / `prod` profiles | Octane convention |

### Why this stack, specifically

**PostgreSQL is the strongest single argument, and it is close to decisive.** It provides native row-level security — the independent second enforcement layer [ADR-001](ADR-001-tenancy-isolation.md) requires — as a built-in feature rather than something to be simulated. It provides declarative partitioning for the high-volume entities, full-text search adequate for a first release, and it is freely self-hostable, which sovereign deployment demands. Choosing a database without native RLS would mean either weakening ADR-001 or building tenant enforcement twice in application code, which is precisely the single-point-of-failure that ADR was written to avoid.

**Java 21 and Spring Boot follow from criterion 1 more than from any technical superiority.** The team runs this stack in production on Octane, an enterprise workload of comparable shape and consistency demands. Architecture-boundary enforcement (rule B4, no cycles) has mature tooling in this ecosystem, which matters because [ADR-006](ADR-006-modular-monolith.md) is only credible if the boundaries are machine-enforced. A stack the team operates well is worth more than one that benchmarks slightly better and that nobody can debug at 3 a.m.

**The frontend and testing choices are straightforward reuse** of an already-working convention. There is no reason to re-litigate them and some cost in doing so.

### What is explicitly still open

| Component | Options | Blocking |
|---|---|---|
| Event broker ([ADR-003](ADR-003-event-backbone.md)) | **Kafka (recommended)** · RabbitMQ · PostgreSQL-backed queue | Sprint 4 (Q5). At a target scale of 50,000 users, sustained event throughput across the 7 consumer groups in ADR-003 (hundreds–low thousands/sec, bursting higher under migration/bulk load) exceeds what a database-backed queue should carry on the OLTP primary. Kafka's partitioned log fits the fan-out-to-7-consumer-groups shape and gives replay, which the customer-facing event stream (`FR-INT-006`) needs anyway. RabbitMQ remains viable for a smaller deployment but is the weaker fit here. Both are self-hostable; the outbox makes this genuinely reversible regardless |
| Search index | PostgreSQL full-text · OpenSearch · Elasticsearch | Sprint 6 (Q4). Start with PostgreSQL; escalate on measured need |
| Analytical store ([ADR-008](ADR-008-reporting-read-model.md)) | Same engine, separate storage · dedicated columnar store | Stage 2 (Q2). Start with the former |
| Distributed cache | Redis · Valkey | Sprint 3 |
| Object storage | S3-compatible | Sprint 5. Must be S3-compatible so sovereign installs can use MinIO or equivalent |
| Model provider ([ADR-004](ADR-004-ai-provider-abstraction.md)) | Hosted · self-hosted | Sprint 8. Abstracted, so reversible |

## Consequences

**Positive**
- Requirements were written without stack bias — they describe what the product does, not what a framework makes convenient
- The architecture is portable; ADRs 001–004 and 006–008 survive a different choice
- Ratifying the recommendation would let the team reuse working conventions, tooling and operational knowledge from day one

**Negative, stated honestly**
- **Some architectural questions stay open longer than is comfortable**, and the six listed above each block a sprint. Deferring past those points would become an impediment rather than a discipline.
- Prototyping and spikes are constrained until ratification.
- The recommendation is heavily weighted toward what the team already knows. That is a legitimate criterion and also a bias — it should be named rather than presented as pure technical evaluation.
- **Reusing the Octane stack does not mean reusing Octane's code.** These are different products with different domains; sharing a stack is an operational convenience, not an invitation to a shared library that couples two release cycles.

## What would change this recommendation

Stated so the decision is falsifiable rather than a formality:

- If the team's actual composition at build time is predominantly TypeScript rather than Java, criterion 1 inverts and a Node/TypeScript stack becomes the better answer. The Projects root already carries Prisma, which is weak evidence in that direction.
- If a hard requirement emerges for a database without native RLS, [ADR-001](ADR-001-tenancy-isolation.md) must be revisited *first*, not worked around.
- If sovereign deployment is dropped as a product goal, criterion 2 disappears and managed cloud services become viable, which changes several of the open components.

## Ratification

This ADR moves to **Accepted** when the recommendation is confirmed or replaced, with the decision and its reasoning recorded here. Until then it is a proposal, and no requirement, test or plan in this documentation set depends on it.

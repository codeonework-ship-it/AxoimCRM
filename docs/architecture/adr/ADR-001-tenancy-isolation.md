# ADR-001 — Tenancy isolation model

**Status:** Accepted · **Date:** 2026-07-25 · **Supersedes:** —

## Context

Axiom must serve three deployment models from one product:

1. **Pooled SaaS** — many tenants sharing infrastructure, where unit economics decide viability
2. **Dedicated** — one tenant on isolated infrastructure, for enterprise buyers who will pay for it
3. **Sovereign** — one tenant on customer-controlled infrastructure, on-premises or in the customer's own cloud

Sovereign deployment is one of the four product differentiators ([competitive analysis](../../product/02-competitive-analysis-salesforce-zoho.md) §5.2). It is an eligibility gate for regulated buyers, not a preference — and it directly conflicts, on the surface, with the pooled multi-tenancy that makes the SaaS business work.

That apparent conflict is the decision this ADR resolves.

## Decision

**Shared schema. `tenant_id` on every row. Enforced at two independent levels. Deployment model is infrastructure, never domain logic.**

1. Every business entity carries `tenant_id`, participating in every uniqueness constraint and every index supporting a tenant-scoped query.
2. **Application enforcement** — tenant context binds from the authenticated principal at the entry point, propagates through ambient context, and is injected by a tenant-scoped repository base. No code path reaches the data tier without it.
3. **Database enforcement** — row-level security policies filter on a session-scoped tenant variable, independently of application logic.
4. **No client-supplied tenant identifier is ever trusted.** Not a header, not a parameter, not a claim the client can influence.
5. **Foreign keys never cross tenants**, enforced by constraint.
6. `TENANT.deployment_model` exists for operational reporting only. **No business query, service or domain rule may branch on it.**

**A sovereign install is one tenant in the standard schema, running the standard artifact.** No sovereign build, no feature fork, no separate release train.

## Rationale

**Why shared schema over schema-per-tenant or database-per-tenant.**

Schema-per-tenant makes migrations O(tenants) — a schema change becomes a fleet operation with partial-failure states, and connection pooling degrades badly past a few hundred schemas. Database-per-tenant is worse on both counts and destroys pooled unit economics. Both buy isolation that RLS provides at a fraction of the operational cost.

**Why two enforcement levels rather than one.**

Application-level scoping alone is one hand-written query away from a cross-tenant leak — and that query will eventually be written, by someone new, under deadline, in a code path nobody thought to review. RLS turns that class of bug from a data breach into a failed query. The redundancy is the point; it is not belt-and-braces caution, it is the acknowledgement that the application layer will be wrong at least once.

**Why deployment model must not reach the domain.**

The moment a service method contains `if (sovereign)`, the sovereign product begins diverging. Divergence means a second test matrix, then a second release cadence, then features that exist in one and not the other — at which point "the same product, deployed differently" is no longer true and the differentiator has quietly evaporated. Keeping tenancy in infrastructure is what makes the marketing claim survive three years of delivery pressure.

## Consequences

**Positive**
- One codebase, one test matrix, one release train across all three models
- Cheap migrations — one schema
- Efficient pooled resource use
- **Sharding is a routing change, not a data migration**, because every entity is already tenant-scoped with opaque keys
- Sovereign customers get feature parity by construction, not by promise

**Negative, stated honestly**
- Isolation is *logical*, not physical. Some buyers will require physical separation; they get the dedicated or sovereign model, at a higher price. We must not claim shared-schema pooling gives physical isolation.
- RLS carries a query-planning cost that must be measured, not assumed negligible.
- A single tenant's pathological workload can affect neighbours. Mitigated by the quota and bulkhead controls in [system design](../system-design.md) §6 — mitigated, not eliminated.
- Restoring one tenant's data from backup in a pooled database is materially harder than restoring a dedicated one. This needs a tenant-scoped export/restore path built deliberately, not improvised during an incident.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Schema-per-tenant | O(tenants) migrations, connection-pool pressure, partial-failure states during schema change |
| Database-per-tenant | All of the above, plus pooled economics become unviable |
| Application-only scoping (no RLS) | One missed predicate is a cross-tenant breach; the failure mode is unacceptable for the blast radius |
| Separate codebase for sovereign | Guarantees divergence; destroys the differentiator it exists to serve |
| Hybrid: pooled for SMB, schema-per-tenant for enterprise | Two persistence models, two sets of bugs, and every feature written twice |

## Compliance

- Cross-tenant isolation is verified by dedicated tests in the [QA master test plan](../../../qa/qa-master-test-plan.md), including tests that deliberately attempt cross-tenant access through every entry point.
- Any code path bypassing the tenant-scoped repository base fails the architecture test.
- Related: `FR-GLOBAL-001`, `FR-TEN-001`, `FR-TEN-002`; [ADR-006](ADR-006-modular-monolith.md) boundary rule **B7**.

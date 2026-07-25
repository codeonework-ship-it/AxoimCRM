# ADR-008 — Reporting read model

**Status:** Accepted · **Date:** 2026-07-25

## Context

Reporting requirements are substantial: an ad-hoc report builder (`FR-RPT-001`), cross-object reporting (`FR-RPT-002`), dashboards (`FR-RPT-004`), historical trending (`FR-RPT-008`), pipeline analytics (`FR-FCT-009`) and the forecast movement waterfall (`FR-FCT-006`).

Users author these queries themselves. That means the system will run arbitrary, unpredictable, potentially very expensive queries against whatever store they target — chosen by a sales manager on a Monday morning, not by an engineer.

Running that traffic against the OLTP tables is the classic way enterprise CRMs become slow, and the failure is asymmetric: the person who authored the expensive report experiences a slow report, while everyone else experiences a slow product.

## Decision

**A separate read model, maintained by event consumers plus scheduled snapshots. Analytical queries never touch OLTP tables.**

1. Projection workers consume domain events ([ADR-003](ADR-003-event-backbone.md)) and maintain denormalized reporting structures.
2. **Snapshot tables** (`PIPELINE_SNAPSHOT`, `FORECAST_SNAPSHOT` and its lines) are written on schedule and are **immutable**.
3. Report and dashboard queries execute against the read model.
4. **Drill-through goes back to the authoritative record with a fresh permission check** (`FR-RPT-006`). The projection aggregates; it is never the authority on what a user may see.
5. The projection is eventually consistent and **its staleness is displayed to the user**.
6. Per-tenant query concurrency limits, statement timeouts and row caps apply (`FR-RPT-011`).
7. Initially the read model uses the same database engine on separate storage. Migration to a dedicated analytical store is deferred until measurement justifies it (Q2 in [system design](../system-design.md) §15).

## Rationale

**Separation is about contention, not just speed.** A user-authored report scanning ten million opportunity rows will hold locks, consume I/O and evict useful pages from cache. On a shared OLTP primary that degrades every interactive request in the tenant — and, without the quota controls, in neighbouring tenants too. Physical separation makes the blast radius of a bad report the reports, not the product.

**Immutable snapshots are what make two headline requirements exact rather than approximate.** `FR-FCT-005` requires any forecast to decompose to its source deals; `FR-FCT-006` requires a movement waterfall whose components reconcile exactly to the net change. Both are trivial with snapshot *lines* and effectively impossible without them — reconstructing a historical forecast from an audit log is slow, fragile and produces numbers that do not quite tie out, which in a forecast review is worse than having no number at all.

**Decision 4 is the security-critical one.** It is tempting to let the projection carry materialized permissions so drill-through can be served from it directly. Access changes faster than a projection updates, so that design eventually shows someone a record they were removed from an hour ago. Re-checking against the authoritative store costs latency on one page of results and is the only version that is correct.

**Decision 5 is a usability decision with a support cost behind it.** A report thirty seconds behind the record page is fine. A report *silently* thirty seconds behind, while a manager reconciles it against a record they just edited, generates a support ticket and erodes trust in every number the product shows. Displaying staleness is cheap; recovering credibility is not.

**Decision 7 resists premature optimization.** A dedicated analytical engine is a large operational commitment — and one a sovereign customer would also have to run (Q6). Separate storage in a familiar engine solves the contention problem, which is the actual problem, and leaves the harder decision until there is data to make it with.

## Consequences

**Positive**
- Analytical load cannot degrade transactional performance
- Read structures are denormalized for query shape, not normalized for write integrity
- Historical trending and the movement waterfall are exact
- The reporting tier scales independently, on hardware suited to it
- Already positioned for extraction as a service ([ADR-006](ADR-006-modular-monolith.md) §3.3, candidate 3)

**Negative, stated honestly**
- **Eventual consistency is user-visible.** Mitigated by displaying staleness, not eliminated.
- Projection logic is a **second implementation of business meaning**, and it can drift from the transactional model. This is the real long-term risk of this pattern. It requires reconciliation tests that compare projected aggregates against authoritative recomputation on a schedule — not as a one-time verification.
- Storage cost roughly doubles for projected entities, plus snapshot growth over time. Snapshots need a retention policy or they become the largest data in the system.
- Adding a reportable field means changing the projection as well as the entity — a second place to forget.
- Projection lag becomes a monitored service-level indicator with its own incident class.
- Rebuilding a projection after a bug is a long operation. Replay from the outbox makes it possible; it does not make it fast.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Report directly against OLTP tables** | User-authored queries contend with transactional writes. The failure is asymmetric and hits users who did nothing wrong |
| **Report against read replicas only** | Helps with contention, but keeps normalized structures that are wrong for analytical query shape, gives no historical trending, and replica lag is still user-visible without the denormalization benefit |
| **Full CQRS with event sourcing** | Far larger change than the problem requires; poor fit for the ad-hoc relational querying a CRM does constantly |
| **Embedded third-party BI tool** | Both competitors charge separately for this; `F-250` commits to avoiding a BI SKU. Also adds a dependency that sovereign installs must run |
| **Materialized views on the OLTP store** | Refresh contends with the writes it is trying to protect, and refresh granularity is too coarse for the freshness users expect |

## Compliance

- **Reconciliation tests comparing projected aggregates against authoritative recomputation are mandatory and scheduled**, not one-time.
- Drill-through permission re-check is an explicit `SEC-` test case ([acceptance tests](../../product/06-acceptance-tests.md)).
- Projection lag is a monitored SLI with alerting thresholds.
- Related: `FR-RPT-005` … `FR-RPT-011`, `FR-FCT-004` … `FR-FCT-006`; [reporting and analytics](../../product/14-reporting-and-analytics.md).

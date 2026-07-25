# ADR-003 — Event backbone via transactional outbox

**Status:** Accepted · **Date:** 2026-07-25

## Context

Several requirements need reliable notification of business changes to components outside the originating transaction:

- **Automation** (`FR-AUT-001`) — record-triggered rules
- **Audit** (`FR-GLOBAL-005`) — an immutable event for every material action
- **Search indexing** — keeping the index current
- **Reporting projections** ([ADR-008](ADR-008-reporting-read-model.md))
- **Webhooks** (`FR-INT-005`) and the **domain event stream** (`FR-INT-006`)
- **Integration dispatch**, including the CTRM deal-agreed hand-off (`FR-CTM-009`)

Audit and explainability are *functional requirements* here, not operational conveniences. A lost audit event is a compliance failure, and a phantom event for a rolled-back transaction is a corrupted history. Both are unacceptable.

## Decision

**Transactional outbox with at-least-once delivery to idempotent consumers.**

1. Domain events are written to an outbox table **in the same database transaction as the business change**.
2. A relay process polls the outbox, publishes to the broker, and marks rows dispatched.
3. Delivery is **at-least-once**. Every consumer is idempotent.
4. Ordering is guaranteed **per record**, not globally. Partition key is `(tenant_id, entity_id)`.
5. Failures after a bounded retry go to a **dead-letter queue** with replay. A permanently failing consumer must not block the stream.
6. Consumer lag is monitored per consumer group. Sustained lag on the audit or projection consumers is a production incident.
7. **The outbox is the source of truth**, not the broker. Broker loss is recoverable by replay from the outbox within its retention window.

## Rationale

The naive alternative — commit to the database, then publish to the broker — is broken in **both** directions:

- **Publish succeeds, transaction rolls back** → a phantom event. Automation fires on a change that never happened; audit records a lie.
- **Transaction commits, publish fails** → a lost event. Automation silently does not fire; audit has a gap.

There is no ordering of the two operations that fixes this, because they are two systems without a shared transaction. The outbox works by making the event write part of the *same* transaction as the business change — the only place a real guarantee is available.

**Why at-least-once rather than exactly-once.** Exactly-once cannot be honestly delivered end to end across a broker and heterogeneous consumers; systems that claim it are usually providing at-least-once plus deduplication, which is what idempotent consumers are. Naming it accurately means consumers get *designed* for redelivery instead of discovering it in production.

**Why per-record ordering rather than global.** Global ordering forces a single partition and destroys throughput. Per-record ordering is what business logic actually needs: two changes to the same opportunity must be processed in order; a change to an unrelated account need not be ordered against them.

## Consequences

**Positive**
- No lost and no phantom events, under any failure combination
- Consumers are decoupled from the write path — a slow webhook endpoint cannot slow a record save
- Broker is replaceable; the outbox insulates the domain from that choice (relevant to sovereign installs, which must run the whole stack — Q5 in [system design](../system-design.md) §15)
- Replay is available for recovery, for backfilling a new consumer, and for debugging

**Negative, stated honestly**
- The relay is a component that must be monitored, scaled and made highly available. It is a new operational surface.
- The outbox table is high-write and needs partitioning plus a purge policy, or it becomes the largest table in the system.
- Polling adds latency between commit and publish. Acceptable for automation and integration; measured and bounded.
- **Every consumer must be idempotent, and this must be tested with deliberate redelivery.** Idempotency that has never been tested under duplicate delivery is a hope, not a property.
- Eventual consistency is now visible to users in some paths. Where a user can observe the lag, it must be shown to them rather than left to look like a bug.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Direct publish after commit | Loses events on publish failure; phantom events on rollback. Fails the requirement outright |
| Database change data capture (log tailing) | Attractive — no outbox table — but couples the design to a specific engine's replication format, complicating [ADR-005](ADR-005-technology-selection-deferred.md) and sovereign deployment. Emits row changes rather than business-meaningful domain events, so consumers must reconstruct intent. Reconsider for the CDC feature (`FR-INT-006`) specifically |
| Synchronous in-process listeners only | Couples consumer latency and failure to the write path; a webhook timeout would fail a record save |
| Two-phase commit across DB and broker | Operationally fragile, poorly supported, and slow |
| Event sourcing as the primary persistence model | Far larger change to the whole design than the problem requires; poor fit for the ad-hoc relational querying a CRM does constantly |

## Compliance

- Redelivery tests are mandatory for every consumer — see [acceptance tests](../../product/06-acceptance-tests.md) `CON-` series.
- Outbox depth and relay lag are monitored service-level indicators with alerting.
- Related: `FR-GLOBAL-005`, `FR-GLOBAL-006`, `FR-AUT-001`, `FR-INT-005`, `FR-INT-006`, `FR-CTM-009`.

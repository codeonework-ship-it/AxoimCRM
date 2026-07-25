# ADR-002 — Runtime extensibility model

**Status:** Accepted · **Date:** 2026-07-25

## Context

Tenants must be able to create custom objects and fields at runtime (`FR-ADM-001`, `FR-ADM-002`), and those must behave **identically to built-in ones** in security, automation, reporting, search, validation and API. This is table stakes — both Salesforce and Zoho do it, and a CRM without it is not enterprise-viable.

Additionally, `FR-ADM-001` commits to **no tier-based numeric limit** on custom objects or fields, since Zoho's hard per-edition limits are among its most-cited enterprise frustrations and closing that gap is a stated differentiator.

The constraint that makes this hard: [ADR-001](ADR-001-tenancy-isolation.md) commits to a shared schema. Per-tenant DDL is therefore not available.

## Decision

**Typed sparse columns plus a metadata catalogue.**

1. Each extensible entity carries a fixed set of reserved, typed, indexable slots per data type: text, number, date, datetime, boolean, reference, long-text.
2. `CUSTOM_FIELD_DEF` maps a tenant's logical field (api name, label, type, constraints) to a physical slot.
3. `CUSTOM_OBJECT_DEF` plus `CUSTOM_RECORD`/`CUSTOM_RECORD_VALUES` provide custom objects using the same slot mechanism.
4. A metadata-driven query layer translates logical field references to slots for filtering, sorting, aggregation and projection.
5. Custom fields participate in field-level security, validation, automation, reporting, search indexing and the API through the same code paths as standard fields — **there is no "custom field" branch in those subsystems**.
6. Formula and roll-up fields are stored as a definition plus a materialized value with `computed_at`, recomputed on dependency change. Dependency cycles are rejected at definition time, naming the cycle.

## Rationale

Slots are **real typed columns**. They index, sort, filter and aggregate at native speed. That is the whole argument, and it is decisive: the alternatives fail precisely on the operations a CRM does constantly.

Requirement 5 is the one that is easy to under-value and expensive to retrofit. If custom fields are a special case anywhere — in the report builder, in the search indexer, in the API serializer — then every one of those subsystems accumulates two code paths, and the second one is always the buggier. Making custom fields ordinary at the persistence layer is what keeps them ordinary everywhere above it.

## Consequences

**Positive**
- Native query performance on custom fields
- Type safety and database-level constraints preserved
- One shared schema, so [ADR-001](ADR-001-tenancy-isolation.md) holds and migrations stay O(1)
- Custom and standard fields are genuinely uniform above the persistence layer

**Negative, stated honestly**
- **Slot count per type is finite.** `FR-ADM-001` promises no *tier-based* limit and this design honours that — but it does not promise infinity, and product documentation must not imply it does. Slot exhaustion must produce a clear administrator message naming the limit and the expansion path, never an opaque error.
- Expansion (provisioning additional slot ranges) is an operational task with a schema change behind it. It is not self-service.
- The metadata translation layer is genuinely complex and sits on the hot path of every query. It needs strong test coverage and its own performance budget.
- Debugging is harder: a physical column named `text_slot_07` means nothing without the catalogue. Tooling to render the logical view is not optional.
- Sparse rows waste some storage. This is the least important consequence and is not worth optimizing against the others.

**Open question (Q1 in [system design](../system-design.md) §15):** the initial slot allocation per type. Too few is a customer-visible ceiling; too many wastes storage on every row of every tenant. Needs a decision informed by competitor field counts and expected tenant profiles before Sprint 3.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Pure EAV** (one row per field value) | Every query becomes a self-join per referenced field. Sorting, filtering and aggregation degrade non-linearly. Type safety is lost. This is the classic wrong answer and it fails exactly where a CRM works hardest |
| **Per-tenant DDL** (real columns per tenant) | Incompatible with shared schema. Migrations become O(tenants); schema count explodes; connection pooling suffers |
| **Document/JSON column per record** | Attractive and genuinely flexible, but indexing is weaker for the mixed filter-and-sort queries list views generate, type enforcement is application-level only, and aggregate performance is unpredictable at volume. Reconsider only for genuinely schemaless auxiliary data |
| **Hybrid: slots for indexed fields, JSON for the rest** | Two mechanisms, two sets of semantics, and an administrator-visible distinction between "fast fields" and "slow fields" that we would have to explain and defend |

## Compliance

- Custom fields must be covered by the same test suites as standard fields in security, reporting, search, automation and API — verified by test-coverage review, not assertion.
- Slot exhaustion behaviour is an explicit test case in the [acceptance test catalogue](../../product/06-acceptance-tests.md).
- Related: `FR-ADM-001`, `FR-ADM-002`, `FR-ADM-003`; [data model](../../product/09-data-model.md) §6.

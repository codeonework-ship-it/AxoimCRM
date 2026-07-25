# QA automation scenarios

This document translates the current runnable increment into repeatable automation
work. It complements the product acceptance catalogue; it does not mark unimplemented
target-product cases as passing.

## Executable release checks

| ID | Level | Setup and action | Assertion |
|---|---|---|---|
| AUTO-BE-001 | Unit | Run `mvn test` in `backend` | All tests pass; no test failures or errors |
| AUTO-WEB-001 | Build | Run `npm run build` in `frontend` | TypeScript and Vite production build complete |
| AUTO-SYS-001 | System | Run canonical Compose and wait for health | PostgreSQL, Kafka, API, and web report healthy |
| AUTO-API-001 | API | Login as Priya with seeded development access | `200`; signed token identifies Priya and `meridian` |
| AUTO-API-002 | API | Call notification feed/count as Priya | Feed is recipient-scoped and count equals unread items |
| AUTO-API-003 | API | Mark Priya notification read, unread, then read all | Mutations are idempotent and returned feed/count stay consistent |
| AUTO-SEC-001 | Security | Login as Raj and mutate Priya notification ID | `404`; record and audit-relevant state are unchanged |
| AUTO-NEG-001 | Contract | Request notifications with an unsupported view value | `400` with the standard error envelope |
| AUTO-UI-001 | End-to-end | Login, open Signal center, mark item read | Badge, unread tab, and item action update together |
| AUTO-UI-002 | Accessibility | Navigate primary shell, palette, drawer, and notification actions using keyboard | Visible focus, meaningful names, deterministic order, Escape closes overlays |
| AUTO-UI-003 | Responsive | Exercise 390px and desktop viewports | Primary journeys remain operable without page-level horizontal scrolling |

## Notification integration cases

1. Convert a lead and assert one recipient-scoped `SYSTEM` notification is committed
   with the business transaction and links to Accounts.
2. Advance an eligible opportunity and assert one recipient-scoped `SYSTEM`
   notification links to Pipeline.
3. Force either business transaction to roll back and assert its notification is not
   persisted.
4. Query with a different authenticated user and tenant and assert no title, body,
   count, or identifier leaks.
5. Execute read/unread twice and assert stable state with no duplicate rows.

Cases 1–5 define the next integration-test implementation target. The current release
has unit/contract tests plus live-stack acceptance for the read model and isolation
mutation, as recorded in [runtime smoke](runtime-smoke.md).

## CI target

The target pipeline runs dependency audit, backend tests, frontend build, container
build, ephemeral-stack migration, API smoke, and browser acceptance. It publishes
JUnit results, sanitized browser traces on failure, image digests, migration status,
and a traceability summary. Production deployment remains blocked until the canonical
image build and all release gates in [the master plan](qa-master-test-plan.md) pass.

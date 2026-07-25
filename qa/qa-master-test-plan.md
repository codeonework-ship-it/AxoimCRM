# QA master test plan

## Quality strategy

Axiom uses risk-based testing with tenant isolation, transactional correctness, and
actionable refusals as release gates. The product-level catalogue defines expected
behaviour; this plan defines where and how evidence is produced.

## Test layers

| Layer | Purpose | Current tooling |
|---|---|---|
| Unit | Domain decisions and controller contracts | JUnit 5, Mockito |
| Persistence | SQL constraints, RLS, migrations, scoped mutations | PostgreSQL, Flyway, JDBC integration smoke |
| API | Authentication, contracts, status/error shape, user isolation | Authenticated PowerShell/HTTP smoke; automation backlog below |
| Web | Type safety, production bundle, accessible interaction semantics | TypeScript, Vite, browser-driven acceptance |
| Desktop | Isolated preload bridge and web-shell startup | Electron launch smoke |
| System | Health, dependency ordering, and deployable composition | Docker Compose health checks |

## Environments and test data

- Local release candidate: source-built API and web containers, PostgreSQL and Kafka
  from the canonical Compose file.
- Cached-image override: workstation-only recovery path documented in
  [runtime smoke](runtime-smoke.md); never CI evidence for canonical versions.
- Workspace `meridian` contains deterministic demo personas and business records.
- Tests may mutate demo state only when they restore it or recreate the database.
- Secrets, production exports, and customer data are prohibited in test fixtures.

## Release gates

1. `mvn test` passes with no skipped in-scope tests.
2. `npm run build` passes with TypeScript checking enabled.
3. `docker compose config --quiet` passes and all canonical services become healthy.
4. Flyway reports every migration successful and no checksum drift.
5. Authenticated API smoke covers login and the implemented vertical slice.
6. Cross-user and cross-tenant negative checks return non-disclosing errors.
7. Browser acceptance covers desktop and narrow viewport navigation, keyboard use,
   error/degraded states, and the current release journey.
8. Documentation names the shipped boundary and does not claim planned features.

## Current automated evidence

| Capability | Evidence | Status |
|---|---|---|
| Notification user scoping | `NotificationServiceTest` query/update predicates | Automated |
| Notification API contract | `NotificationControllerTest` unread view/count | Automated |
| Frontend compilation | TypeScript + Vite production build | Automated command |
| Migration and service health | Compose/Flyway smoke | Scripted operator check |
| Read/unread interaction | Browser-driven live-stack check | Scripted operator check |
| Cross-user notification mutation | Authenticated API negative check | Scripted operator check |

The next automation increment must add Testcontainers persistence tests and a checked-in
browser suite. Until then, operator-driven checks are evidence, not continuous
regression coverage.

## Defect and evidence policy

Every failure records expected/actual behaviour, reproducible steps, environment,
commit SHA, correlation ID when present, and sanitized artifacts. S1/S2 defects block
release as defined by the [UAT plan](../docs/product/07-uat-plan.md). Flaky tests are
defects: they are quarantined only with an owner, expiry date, and replacement gate.

## Traceability and ownership

Acceptance cases in [the catalogue](../docs/product/06-acceptance-tests.md) map to
requirements and stories. Each release selects the subset matching its declared
scope, records execution in `runtime-smoke.md` or CI, and updates
[epic status](../docs/epic-status.md). Unexecuted cases remain unverified; absence of
a failing test is never evidence that a capability exists.

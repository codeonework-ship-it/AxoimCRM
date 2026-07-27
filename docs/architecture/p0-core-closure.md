# P0 Core Closure: E01-E06 And E20

_Verified 2026-07-27 without Docker._

## Scope and result

This closure slice makes tenancy, identity, authorization, governed master data,
accounts/contacts, leads, opportunities and compliance controls a coherent
first-party runtime. External identity-provider certification and external
Alertmanager delivery remain named third-party release gates; they do not leave
an unimplemented first-party code path.

## Command and lifecycle coverage

| Aggregate | Governed lifecycle surface |
|---|---|
| Account | List/detail, create, optimistic update, reparent, activate/inactivate, hierarchy, permission-filtered rollup and health |
| Contact | List/detail/view, create, optimistic update, clone, reassign, soft delete, address and channel lifecycle |
| Lead | List/detail, single and bulk capture, optimistic update, reassign, reactivate, convert and disqualify |
| Opportunity | List/detail, create/update, gated stage movement, close/reopen, close date, recurring revenue, line items, splits, competitors, contact roles, qualification and approvals |

No lifecycle endpoint performs a hard delete of core CRM records. Mutations use
optimistic versions where concurrent editing is meaningful and execute the
business write, immutable audit entry and domain outbox entry in one Spring
transaction.

## Authorization architecture

`AuthorizationService` is the record-level choke point. It combines object
permissions, read-only-role protection, org-wide defaults, ownership, role
hierarchy, live sharing rules, teams, territories and manual/materialized shares.
Failed reads return 404 to avoid record-existence disclosure. Contact access is
the union of contact ownership/shares and inherited account access. Hierarchy and
rollup queries apply the same SQL predicate before aggregating, so totals cannot
leak hidden records.

PostgreSQL forced row-level security remains the second boundary. Every request
transaction binds `app.tenant_id`; a connection without that setting sees no
tenant records. The Docker-free `LocalTenantIsolationTest` proves explicit
cross-tenant reads and writes are blocked, unbound connections fail closed,
outbox rows are isolated, and the runtime role cannot update audit evidence.

## E20 evidence controls

- Audit events are hash chained, append-only and protected by revoked
  update/delete privileges plus immutable triggers.
- Domain changes write `integration.outbox_event` in the same transaction;
  broker publication remains asynchronous and retryable.
- Read, export and authentication audit, field history, retention, legal hold,
  consent and data-subject-request services remain independently governed.
- Prometheus metrics and SLO/alert rules are first-party complete. An approved
  external Alertmanager receiver is `PENDING_VENDOR`.

## Repeatable Docker-free release gates

```powershell
cd backend
$env:MAVEN_OPTS='-Djava.io.tmpdir=E:\Anand\Projects\CRM\.test-tmp -Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading'
mvn -q -DforkCount=1 -DreuseForks=true test

$env:AXIOM_LOCAL_DB_TESTS='true'
$env:AXIOM_TEST_DB_URL='jdbc:postgresql://localhost:5432/AxiomCrmdb_Dev'
$env:AXIOM_TEST_DB_OWNER='Axiom'
$env:AXIOM_TEST_DB_OWNER_PASSWORD='Axiom@12345'
$env:AXIOM_TEST_DB_RUNTIME='axiom_app'
$env:AXIOM_TEST_DB_RUNTIME_PASSWORD='axiom_app_dev_password'
mvn -q -Dtest=LocalTenantIsolationTest test

cd ..\frontend
npm run build
$env:AXIOM_E2E_EMAIL='superadmin@axiomcrm.com'
$env:AXIOM_E2E_PASSWORD='axiom-demo'
npm exec playwright test
```

The browser suite checks automated WCAG 2.2 A/AA rules on Login, Home, Accounts,
Leads, Pipeline, Reports and Administration. It also checks authenticated P0
routes for uncaught errors and proves Accounts, Leads and Opportunities return a
default server page size of 100.

## Verification record

- Backend: 108 suites, 635 tests, 0 failures, 0 errors. Two opt-in tests are
  skipped by the hermetic run; the Docker-free local PostgreSQL isolation suite
  was run separately and passed both tests.
- Frontend production build: passed.
- Playwright release suite: 4/4 passed against the rebuilt host API.
- Local database: Flyway schema version 346.
- Runtime: host PostgreSQL on 5432, API on 8080 and Vite on 4280. Docker Desktop
  and Docker containers are not running and are not part of this verification.

## External gates

- E01: named Entra ID/Okta production-tenant certification evidence.
- E20: approved external Alertmanager notification receiver.

These are deployment-party dependencies, not missing internal CRUD,
authorization, lifecycle, audit, outbox, accessibility or runtime behavior.

# Axiom CRM 1.0 — System Test Plan And Test Case Catalogue

**Document owner:** Quality Engineering  
**Scope:** Web, REST API, PostgreSQL, event/outbox processing, Jasper reporting and Electron shell  
**Execution rule:** a release is eligible only when every applicable P0/P1 automated test passes, every mandatory manual case has evidence, and there are zero unresolved severity-1 or severity-2 defects.  
**Related specifications:** [FRD](../product/03-frd.md), [feature catalogue](../product/04-feature-catalogue.md), [acceptance tests](../product/06-acceptance-tests.md), [RBAC model](../product/08-rbac-and-sharing-model.md), [NFRs](../product/10-nfr-and-enterprise-readiness.md), [epic status](../epic-status.md).

## 1. Purpose And Truth Standard

This is the executable release-level QA contract for Axiom CRM. “Zero failures” means **zero failures in a named, reproducible execution against a named build**. It does not mean an unexecuted case passed, nor that vendor behavior can be certified without the vendor. A result may be `PASS`, `FAIL`, `BLOCKED_VENDOR`, `NOT_APPLICABLE` or `NOT_RUN`; only `PASS` and approved `NOT_APPLICABLE` cases satisfy the release gate.

Every defect report must contain build/commit, environment, tenant, user/role, locale, theme, browser or desktop build, exact steps, expected and actual results, correlation ID, screenshot/video where relevant, and sanitized request/response evidence.

## 2. Environments And Data Profiles

| Profile | Purpose | Data | Required result |
|---|---|---:|---|
| Unit | Pure domain/service behavior | Builders/mocks | Zero failures |
| Component | Controller, repository, migration and security boundaries | Isolated fixtures | Zero failures |
| Runtime | Real API + PostgreSQL + web application | Seed tenant | Zero failures |
| Scale-L | Pagination/search/filter/lazy-load | 1,000,000 logical rows per transactional screen | Thresholds met |
| Bulk-Master | Atomic master CSV import | 0–5,001 rows, 0–5 MiB+1 | Limits and validation proven |
| Cross-browser | UI, accessibility and responsive layout | Seed tenant | Zero P0/P1 failures |
| Electron | Packaged Windows client now; Linux/macOS in native CI | Seed tenant | Smoke + navigation pass |

The scale fixture is installed by [`scripts/qa/install-scale-dataset.ps1`](../../scripts/qa/install-scale-dataset.ps1). It stores one indexed ordinal set and exposes one million deterministic rows for each active transactional screen. This avoids filling a developer disk with tens of millions of duplicate business records. A physical business-table load is permitted only on a disposable QA database with at least 40 GB free and a recorded restore point.

Master tables are not million-row seeded. Their coverage comes from the master edge-case catalogue: boundary strings, Unicode, duplicates, missing parents, referenced records, deleted records, malformed files and exact capacity boundaries.

## 3. Release Entry And Exit Criteria

Entry requires a versioned build, successful Flyway migration, known seed revision, reachable API health endpoint, supported browser versions, and no shared mutable test tenant for parallel destructive runs.

Exit requires:

- backend unit/component suite: 100% passed;
- frontend build/type check: passed;
- Playwright runtime, accessibility, localization and documentation-master suites: 100% passed;
- no unexpected browser console error or uncaught exception;
- no cross-tenant data observation or mutation;
- audit and outbox assertions for every governed mutation;
- 100-row default server page contract on every grid/list/report query;
- explicit screen load proven to issue no business-data request before selection;
- bulk limits and atomic validation proven at boundary values;
- applicable performance budgets passed with raw evidence retained;
- vendor-owned tests marked `BLOCKED_VENDOR`, never represented as passed.

## 4. Common Test Procedure For Every Authenticated Route

Apply cases `COMMON-001` through `COMMON-020` to every route in section 5 and to every tab that mounts a separate data query.

| ID | Scenario | Steps / data | Expected result |
|---|---|---|---|
| COMMON-001 | Authorization | Open with each applicable role | Route, controls and API follow effective permissions; direct URL cannot bypass RBAC |
| COMMON-002 | Tenant isolation | Repeat using same-shaped IDs in two tenants | No row, count, suggestion, export or error leaks the other tenant |
| COMMON-003 | Explicit load | Open route in a new session; observe network; select Load | No business data request before Load; then first tenant-scoped request starts |
| COMMON-004 | Default page | Load a million-row dataset | Exactly 100 rows requested/rendered; total count is server supplied |
| COMMON-005 | Next/previous | Move across first, middle and final pages | Stable ordering, no duplicate/missing rows, correct disabled states |
| COMMON-006 | Search | Search first/middle/last, Unicode, spaces, `%`, `_`, quote | Server-side, escaped, tenant-scoped results; page resets to zero |
| COMMON-007 | Column filter | Filter each column alone and in combination | Server applies supported filters; result count and export match |
| COMMON-008 | Sorting | Sort every sortable column ascending/descending | Stable deterministic ordering with defined null behavior |
| COMMON-009 | Grouping | Select one/multiple columns, reorder and clear | Groups are correct; controls remain visible in full/restore view |
| COMMON-010 | Export parity | Export Excel, Word and PDF after search/filter/sort | Same governed dataset and ordering; audit includes format and criteria |
| COMMON-011 | Audit drawer | Open Audit for a row/list | Only authorized tenant evidence appears; times/actor/action are clear |
| COMMON-012 | Full/restore | Enter full size, operate every utility, restore | No clipped controls, lost state, horizontal overflow or focus loss |
| COMMON-013 | Copy view | Copy a filtered grid | Snapshot preserves visible grid structure and metadata, not plain text only |
| COMMON-014 | Empty/error/loading | Force empty, 400, 401, 403, 409, 429, 500, offline | Theme-aware, layman-language state; retry only where safe |
| COMMON-015 | CRUD notifications | Create/update/status change/delete/import | One accessible theme-aware toast with outcome and useful next step |
| COMMON-016 | Localization | Switch EN/DE/RU before and after Load | Page, grid, report and manual strings update without overlap/truncation |
| COMMON-017 | Themes | Exercise all themes and OS high contrast/reduced motion | Contrast, focus, dialogs, toasts and popups remain legible |
| COMMON-018 | Responsive | 320, 768, 1024, 1440, 1920 widths; 200% zoom | Navigation and actions remain reachable; no information loss |
| COMMON-019 | Keyboard/screen reader | Tab, shift-tab, arrows, escape, enter; landmarks | Logical order, visible focus, names/roles/states announced |
| COMMON-020 | Refresh/back | Refresh while loaded; navigate away/back | No stale tenant data; intentional per-session load behavior is consistent |

## 5. Route And Screen Coverage Matrix

Every row inherits all common cases. “Specific focus” adds the screen’s high-risk behavior.

| Suite | Routes / tabs | Specific focus |
|---|---|---|
| AUTH | `/login`, SSO callback, trial request | credentials, lockout, SAML/OIDC state/nonce/signature, trial validation, session renewal/logout |
| HOME | `/` | KPI parity, exception queue, quick actions, data freshness |
| SALES-CORE | `/accounts`, Account 360, duplicates, `/contacts`, `/leads`, `/pipeline`, `/forecast` | ownership, hierarchy, merge, conversion, gates, stage transitions, record locks, forecast submission |
| ACTIVITY | `/activities` | tasks/events/calls/email logs, relations, dates/time zones, completion, capture consent |
| CPQ | `/products`, `/price-books`, `/quotes`, `/contracts` | currency/rounding, price precedence, lines, discount approval, document generation, lifecycle gates |
| ENGAGE | `/campaigns`, `/cases`, `/partners` | membership, SLA, escalation, channel conflict and partner approval |
| MASTER | `/reference-data/*`, currencies/rates and master dialogs | templates, atomic import, soft delete, in-use prevention, effective dating |
| REPORT | `/reports` studio/custom/grid/document tabs | catalogue, Jasper PDF viewer, cross-module joins, formulas, pivots, drill-through, schedule/share/export parity |
| ANALYTICS | `/analytics` | dashboard builder/layout, KPI registry, projection freshness, threshold alerts, reconciliation |
| AI | `/copilot` | permission-scoped grounding, citations, PII masking, explainability, AI-off, prompt injection |
| AUTOMATION | `/automation` | visual rules, enforced state machine, maker-checker, dry-run zero writes, retries/idempotency |
| MIGRATION | `/migration` | discovery, mapping, validation, checkpoints, reconcile, delta re-sync, exact rollback and recovery |
| INTEGRATION | `/integrations`, `/integrations/dispatch` | contracts, credentials, replay/dead letter; external adapters remain vendor-blocked |
| RELEASE | `/sandbox` | change set, approval separation, promotion, rollback and DR evidence |
| MOBILE | `/mobile` | offline package, sync, conflicts, expiry, loss/revocation and small-screen operator UX |
| AUDIT | `/audit`, `/security/activity` | immutability, read/export audit, field history, retention/legal hold, evidence packs |
| ADMIN | `/admin/users`, `/admin/rbac`, `/admin/alerts`, `/admin/trials`, `/admin/companies`, `/admin/billing`, documentation master | cross-tenant super roles, maker-checker, payment suspension, alert bodies/attachments, revision/publish |
| ACCESS | `/access/*`, `/security/authorization` | sharing, effective permissions, row authorization, record locks, step-up/break-glass |
| SEARCH | `/search` and command search | authorization after indexing, stale-index deletion, special characters, keyboard selection |
| BFSI | `/packs/bfsi` | onboarding, KYC/AML screening, suitability, holdings, approvals and exceptions |
| COMMODITY | `/packs/commodity` | enquiry, price, term sheet, approval, handoff, exceptions and decimal precision |
| SHELL | header, rail, language, theme, user manual, notifications, user menu | collapsed rail clipping, equal control heights, long translations, dock/full manual, toast center |
| DESKTOP | packaged Electron application | packaged assets, API discovery, deep links, offline shell, update/signing evidence |

## 6. Detailed Functional And Edge Cases

### 6.1 Identity, Session And Tenant

| ID | Scenario | Expected result |
|---|---|---|
| AUTH-001 | Valid password login | Token/session issued; correct tenant and effective role displayed |
| AUTH-002 | Unknown email, wrong password, disabled user/company, expired trial | Same non-enumerating rejection; no session |
| AUTH-003 | Empty, malformed, case-varied and very long email/password | Safe validation; no server exception or reflected input |
| AUTH-004 | Repeated failure | Policy lockout/rate limit, audited without password material |
| AUTH-005 | Logout, expiry and revocation | Token unusable; cached tenant data removed |
| AUTH-006 | SAML/OIDC replay, stale state, wrong nonce/audience/issuer, invalid signature/cert | Rejected and audited |
| AUTH-007 | SCIM create/update/deactivate/reactivate/group mapping | Idempotent, tenant-scoped lifecycle with no privilege escalation |
| AUTH-008 | SUPER_ADMIN tenant switch | Read/write works across active tenants and is audited |
| AUTH-009 | SUPER_AUDIT tenant switch | Read/view/export policy works; every mutation is forbidden |
| AUTH-010 | Concurrent sessions and step-up expiry | Tenant policy enforced; controlled export/delete requests require current step-up |

### 6.2 CRUD, Lifecycle, Workflow Gates And Locks

| ID | Scenario | Expected result |
|---|---|---|
| CRUD-001 | Create with minimum, complete and boundary values | Defaults/constraints correct; audit and outbox written atomically |
| CRUD-002 | Missing required, invalid enum/date/currency/relationship | Field-level layman message; zero partial writes/events |
| CRUD-003 | Update stale version / simultaneous editors | 409 conflict; winning data preserved; recovery path offered |
| CRUD-004 | Acquire, renew, release and expire record lock | Owner can edit; others see themed conflict banner; abandoned lock expires |
| CRUD-005 | Soft delete unused record | `deleted_at/by` set; normal lists/search exclude it; audit/outbox present |
| CRUD-006 | Delete referenced record | Refused with dependency count and next step; no cascading loss |
| CRUD-007 | Workflow prerequisite missing | Action blocked; missing field/step and next valid transition listed |
| CRUD-008 | Direct API/import/automation attempts to skip gate | Same server/database enforcement as UI |
| CRUD-009 | Move lifecycle backward with/without reason | Reason required, saved and audited; cancel writes nothing |
| CRUD-010 | Idempotent retry after response loss | No duplicate record, event, invoice, notification or execution |

### 6.3 Data Grid, Reports And Exports

| ID | Scenario | Expected result |
|---|---|---|
| GRID-001 | One million matching rows, page 0 | At most 100 returned; no browser memory spike or long main-thread task |
| GRID-002 | Deep page and keyset-compatible boundary | Stable results within latency budget; no offset overflow |
| GRID-003 | Filter/search returns zero, one, 100, 101 and one million | Counts/pages correct at every boundary |
| GRID-004 | Null/empty/zero/negative/large/Unicode/date-zone values | Correct display, sort, group and export representation |
| GRID-005 | Column group picker with narrow rail/full view | All eligible columns visible as spread checkboxes; state retained |
| GRID-006 | Sticky data workspace toolbar | Toolbar stays at its owning grid top while page scrolls; it does not freeze an unrelated pane |
| GRID-007 | Export one million filtered rows | Runs as governed asynchronous/streaming work; no heap exhaustion; criteria preserved |
| RPT-001 | Report catalogue selection | Side list selection updates grid and document tab without stale content |
| RPT-002 | Grid versus Jasper PDF/Excel/Word | Dataset fingerprint, row count, order, totals and filters match |
| RPT-003 | Document preview | PDF viewer loads, zooms, pages, full view and downloads accessible document |
| RPT-004 | Cross-module join and drill-through | Join cardinality correct; target permission rechecked at click time |
| RPT-005 | Formula divide-by-zero/null/overflow/cycle | Defined result or author validation, never silent corruption |
| RPT-006 | Schedule, threshold, recipient/CC/BCC/attachment | Correct tenant/time zone; duplicate sends prevented; audit retained |
| RPT-007 | Projection lag/rebuild/reconciliation | Freshness displayed; rebuild reaches zero drift before certification |

### 6.4 Bulk Import And Mass Operations

| ID | Input | Expected result |
|---|---:|---|
| BULK-001 | Empty file/header only | Rejected, zero writes |
| BULK-002 | Wrong/missing/duplicate header, BOM, CRLF/LF | Clear template guidance; supported variants parse correctly |
| BULK-003 | Quoted comma, escaped quote, embedded line break, unterminated quote | Valid CSV preserved; malformed quoting rejected |
| BULK-004 | 4,999 / 5,000 / 5,001 master rows | First two accepted if valid; 5,001 rejected before writes |
| BULK-005 | 5 MiB / 5 MiB+1 master file | Exact limit accepted if otherwise valid; over-limit rejected before parsing |
| BULK-006 | One invalid row among valid master rows | Entire master import rejected; errors identify row/field |
| BULK-007 | 999 / 1,000 / 1,001 lead JSON rows | First two processed; 1,001 rejected before batch creation |
| BULK-008 | Partial lead rejection | Accepted/rejected counts and row errors correct; retry safe |
| BULK-009 | Unauthorized/auditor/cross-tenant IDs | 403/404 policy response; zero writes |
| BULK-010 | Disconnect/retry/concurrent identical batch | Deterministic recovery; duplicate policy and idempotency visible |
| BULK-011 | Mass update/reassign mixed authorization | Per-row outcome; unauthorized rows untouched; operation audited |
| BULK-012 | Formula-like values and dangerous filenames | No spreadsheet execution, path traversal or content-type confusion |

### 6.5 Security, Privacy And Eventing

| ID | Scenario | Expected result |
|---|---|---|
| SEC-001 | SQL/JSON/HTML/script injection in every text/search/filter field | Treated as data; no execution or markup injection |
| SEC-002 | IDOR using valid foreign-tenant UUID | Not found/forbidden without existence disclosure |
| SEC-003 | CORS allowed and unapproved origins/preflight | Only configured origins, methods and headers accepted |
| SEC-004 | Oversized body, file and decompression attack | Early bounded rejection; service remains healthy |
| SEC-005 | Secrets/PII in logs, URLs, toasts and audit detail | Masked/minimized; credentials never recorded |
| SEC-006 | Mutation success/failure transaction | Business row, audit and outbox commit together or all roll back |
| SEC-007 | Duplicate/out-of-order event | Consumer idempotent; projection converges |
| SEC-008 | Maker approves own request, delegated cycle, expired delegation | Four-eyes enforced transitively |
| SEC-009 | Break-glass | Time-bound, justified, notified and immutably audited |
| SEC-010 | Data subject/export/erasure with legal hold | Policy and hold win; evidence explains every retained/deleted store |

### 6.6 Accessibility, Visual And Compatibility

| ID | Scenario | Expected result |
|---|---|---|
| UX-001 | Keyboard-only complete core workflow | No trap or mouse-only action; escape closes top dialog/drawer |
| UX-002 | Screen reader landmarks/grid/dialog/toast | Correct accessible name, role, state and live announcement |
| UX-003 | 200%/400% zoom and text spacing override | Content reflows; controls/text not clipped |
| UX-004 | Dark/light/TRON themes for dialogs, popups and validation | Theme variables applied; AA contrast and focus visible |
| UX-005 | Long German/Russian strings | Header elements shrink/wrap by priority without overlap |
| UX-006 | Sidebar collapsed | Icons centered with adequate rail spacing and no clipping |
| UX-007 | User manual dock resize/full/restore | Application layout remains usable on phone/tablet/desktop |
| UX-008 | Equal action controls | Header/manual/page buttons use consistent control height and hit area |

## 7. Scale And Performance Procedure

1. Use a disposable QA database and record database host, storage class and free capacity.
2. Set `PGPASSWORD` for the restricted QA owner and run `scripts/qa/install-scale-dataset.ps1`.
3. Verify every `TRANSACTION` target reports `target_rows = 1000000` and the ordinal contains exactly one million rows.
4. Warm each query once; execute each server-side page/search/filter query at least 50 times with randomized values.
5. Capture p50/p95/p99, maximum, timeout/error count, database CPU/IO, API heap/GC and browser memory.
6. Run page 0, page boundary 99/100, deep boundary, selective search, non-selective search, single filter and combined filters.
7. Open each UI route without selecting Load and prove zero business-data network requests for at least 400 ms; select Load and prove only page 0 (100 rows) is fetched.
8. Run bulk boundaries in section 6.4. Never submit a million-row synchronous HTTP payload.
9. Retain raw results under a build-specific evidence directory; do not commit credentials or personal data.

Acceptance budgets:

| Measurement | Budget |
|---|---:|
| Initial shell before Load | No business-data call |
| List/search/filter p95 | < 800 ms |
| Report query p95 | < 3 s |
| Browser response rows | <= 100 |
| Browser uncaught/console errors | 0 |
| API timeout/error during certification | 0 |
| Projection/KPI drift | 0 |
| Master import | <= 5,000 rows and <= 5 MiB, atomic |
| Lead synchronous batch | <= 1,000 rows, bounded row outcomes |

## 8. Automation Traceability

| Evidence | Coverage |
|---|---|
| `mvn test` | domain, controller, RBAC, isolation, lifecycle, audit/outbox, migration, identity, reporting, master limits |
| `npm run build` | TypeScript and production bundle integrity |
| `frontend/tests/runtime.spec.ts` | route runtime, 100-row API contract, explicit-load network boundary |
| `frontend/tests/accessibility.spec.ts` | accessible page/dialog structures and keyboard smoke |
| `frontend/tests/internationalization.spec.ts` | locale switching, translation coverage and header layout regression |
| `frontend/tests/documentation-master.spec.ts` | documentation master governance and drawer rendering |
| `electron-client/scripts/package-smoke.cjs` | packaged desktop assets/bootstrap contract |
| `qa.screen_scale_dataset` | deterministic million-row-per-transaction-screen database test surface |
| `qa.master_edge_case_catalog` | master boundary/negative-data obligations |

## 9. Execution Record Template

| Field | Value |
|---|---|
| Build / commit | |
| Environment / database revision | |
| Started / finished UTC | |
| Executor | |
| Backend result | Not run |
| Frontend build result | Not run |
| Playwright result | Not run |
| Scale dataset targets | Not run |
| Bulk boundary result | Not run |
| Manual/cross-browser result | Not run |
| Vendor-blocked cases | |
| Open severity-1 / severity-2 defects | |
| Final release verdict | NOT CERTIFIED |

No one may replace `NOT RUN` with `PASS` without attached evidence. This is the control that makes “zero failures” meaningful.


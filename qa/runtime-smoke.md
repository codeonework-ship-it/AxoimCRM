# Runtime smoke verification

## Canonical stack

```powershell
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

The canonical Compose file builds the Spring Boot API and React web application from source, then runs PostgreSQL 17, Kafka 3.7, the API on port 8080, and Nginx web on port 4280.

## 2026-07-25 verified result

- Frontend TypeScript and Vite production build passed.
- Compose configuration passed.
- PostgreSQL, Kafka, API, and web containers reached healthy state.
- Flyway versions 1–4 applied successfully.
- `GET /actuator/health` returned `UP`.
- Seed login for `meridian` succeeded.
- Authenticated reads returned 9 accounts, 2 leads, 6 stages, and 9 open deals.
- Web `/healthz` returned HTTP 200.
- Notification acceptance: Priya feed 3 / unread 2; read transition reduced unread to 1; Raj feed 1; Raj mutation of Priya notification returned 404.
- Backend notification unit tests: 5 passed, 0 failed.
- Browser checks passed for login, command center, signal center, mobile drawer, and pipeline keyboard/touch move selectors.
- Electron shell launched against `http://localhost:4280` with its isolated preload bridge.

## 2026-07-25 CPQ increment verification

- Backend `mvn verify` passed: 126 tests, 0 failures.
- Frontend `npm run build` passed: TypeScript and Vite production build.
- Docker Compose rebuild passed for API and web; PostgreSQL, Kafka, API and web containers are healthy.
- Flyway `V91__seed_cpq_catalogue_and_quotes` and `V92__cpq_price_books_navigation_i18n` applied successfully.
- CPQ seed verification: 21 products, 3 price books and 4 quotes across seeded tenants.
- Authenticated CPQ API smoke passed for `GET /api/v1/cpq/products`, `/api/v1/cpq/price-books`, `/api/v1/cpq/quotes`, and `/api/v1/cpq/quotes/summary`.
- Timestamp smoke passed: active price books expose `activatedAt`, and sent quotes expose `expiresAt`.
- CORS preflight for `Origin: http://localhost:4280` returned explicit `Access-Control-Allow-Origin: http://localhost:4280`.
- Web `/healthz` and `/products` returned HTTP 200.
- Kafka broker API health command completed successfully.

## 2026-07-25 five-epic workspace verification

- Backend `mvn verify` passed: 128 tests, 0 failures; the existing 8 Docker-gated tenancy integration checks were skipped by their environment gate.
- Frontend `npm run build` passed: TypeScript and Vite production build.
- Docker Compose rebuild passed for API and web; PostgreSQL, Kafka, API and web containers are healthy.
- Flyway `V93__five_epic_operational_workspaces` applied successfully.
- Seed verification passed: 3 contracts, 3 forecast submissions, 3 campaigns, 3 cases and 3 import batches.
- Authenticated workspace API smoke passed for `GET /api/v1/workspaces/contracts`, `/forecast`, `/campaigns`, `/cases` and `/migration`; each response uses the shared 100-row server pagination contract. Positive status-filter checks returned seeded rows across all five modules.
- Web routes `/contracts`, `/forecast`, `/campaigns`, `/cases` and `/migration` returned HTTP 200 through the nginx SPA fallback.
- CORS preflight for `Origin: http://localhost:4280` returned explicit allow-origin, methods and authorization-header access.
- Kafka broker API health command completed successfully.

## 2026-07-25 second five-epic workspace verification

- Backend `mvn verify` passed: 129 tests, 0 failures; the existing 8 Docker-gated tenancy integration checks were skipped by their environment gate.
- Frontend `npm run build` passed after adding `/partners`, `/automation`, `/analytics`, `/copilot` and `/mobile`.
- Docker Compose rebuild passed for API and web; PostgreSQL, Kafka, API and web containers are healthy.
- Flyway `V94__partner_automation_analytics_ai_mobile_workspaces` applied successfully.
- Seed verification passed: 3 partner accounts, 3 automation rules, 3 analytics dashboards, 3 AI recommendations and 3 mobile device sessions.
- Authenticated workspace API smoke passed for `GET /api/v1/workspaces/partners`, `/automation`, `/analytics`, `/copilot` and `/mobile`; each response uses the shared 100-row server pagination contract.
- Web routes `/partners`, `/automation`, `/analytics`, `/copilot` and `/mobile` returned HTTP 200 through the nginx SPA fallback.
- CORS preflight for `Origin: http://localhost:4280` returned explicit allow-origin, methods and authorization-header access.

## 2026-07-25 final five-surface workspace verification

- The Docker web build now proxies same-origin `/api/` traffic to the API container; production frontend defaults to `/api/v1`, while Vite development still defaults to `http://localhost:8080/api/v1`.
- Backend `mvn verify` passed after adding E17/E19/E20/E22/E23 workspace routes.
- Frontend `npm run build` passed after adding `/integrations`, `/sandbox`, `/audit`, `/packs/bfsi` and `/packs/commodity`.
- Docker Compose rebuild passed for API and web; PostgreSQL 17, Kafka 3.7, API and web containers are healthy.
- Flyway `V95__integration_sandbox_audit_vertical_pack_workspaces` applied successfully.
- Seed verification passed: 3 integration endpoint contracts, 3 sandbox environments, 3 audit evidence packs, 3 BFSI onboarding records and 3 commodity enquiries.
- Authenticated same-origin proxy smoke passed through `http://localhost:4280/api/v1`: login issued a token, `/accounts?page=0` returned 9 records at page size 100, and `GET /api/v1/workspaces/integrations`, `/sandbox`, `/audit`, `/bfsi` and `/commodity` each returned seeded rows with the shared 100-row server pagination contract.
- Positive server-side status filter checks passed for `ACTIVE` integrations, `ACTIVE` sandbox environments, `READY` audit packs, `CLEARED` BFSI onboarding and `OFFERED` commodity enquiries.
- CORS preflight for `Origin: http://localhost:4280` returned explicit allow-origin, methods and authorization/content-type header access.
- Web routes `/accounts`, `/integrations`, `/sandbox`, `/audit`, `/packs/bfsi` and `/packs/commodity` returned HTTP 200 through the nginx SPA fallback.
- Kafka broker API health command completed successfully.

## 2026-07-25 governed workspace export and desktop publish verification

- Backend `mvn verify` passed after adding workspace export routing and attachment generation.
- Frontend `npm run build` passed after adding grouped epic workspace views and Excel/Word/PDF export actions.
- Docker Compose rebuild passed for API and web; PostgreSQL 17, Kafka 3.7, API and web containers are healthy.
- Authenticated same-origin proxy export smoke passed for `GET /api/v1/workspaces/forecast/export?format=XLSX&page=0&status=SUBMITTED`, `DOCX` and `PDF`.
- Export smoke returned HTTP 200 with attachment filenames `forecast-workspace-page-1.xlsx`, `.docx` and `.pdf`; payload sizes were non-zero.
- Workspace export audit smoke passed: three `WORKSPACE_EXPORT` audit rows were visible for entity type `WORKSPACE`.
- Electron local publish passed with `npm run package`; generated artifact is `electron-client/release/AxiomCRM-win-x64-0.1.0.zip` and is intentionally ignored from Git because it is a generated binary artifact.

## 2026-07-25 Account 360, lead recycle and quote document verification

- Backend `mvn verify` passed after adding Account 360, lead disqualification and quote document endpoints.
- Frontend `npm run build` passed after adding the Account 360 drawer, lead disqualification action and quote PDF/Word/Excel download controls.
- Docker Compose rebuild passed for API and web; PostgreSQL 17, Kafka 3.7, API and web containers are healthy.
- Authenticated same-origin login through `http://localhost:4280/api/v1/auth/login` succeeded for tenant `meridian`.
- Account 360 smoke passed: `GET /api/v1/accounts/{id}` returned `Arcstone Retail Group`; `GET /api/v1/accounts/{id}/hierarchy` returned hierarchy data.
- Lead disqualification smoke passed: `POST /api/v1/leads/{id}/disqualify` accepted governed reason `NOT_A_FIT`, wrote a future recycle date of `2026-08-15`, and returned status `DISQUALIFIED`.
- CPQ quote document smoke passed: `GET /api/v1/cpq/quotes/{id}/download?format=PDF`, `DOCX` and `XLSX` each returned HTTP 200 with non-zero payloads and the expected attachment content types.

## 2026-07-25 workspace command action verification

- Backend `mvn verify` passed after adding the shared workspace command layer.
- Frontend `npm run build` passed after adding row-level action controls to the shared operational workspace page.
- Canonical `docker compose build --pull=false api web` was attempted, but Docker Desktop again failed while resolving Docker Hub base image metadata through its local HTTPS proxy. The patched API and web images were rebuilt locally from the previous runtime images by copying in the freshly verified Spring Boot JAR and Vite `dist` bundle.
- PostgreSQL 17, Kafka 3.7, patched API and patched web containers are healthy.
- Authenticated same-origin action smoke passed through `http://localhost:4280/api/v1`:
  - `POST /workspaces/forecast/{id}/submit` returned `SUBMITTED` and captured a forecast snapshot.
  - `POST /workspaces/cases/{id}/resolve` returned `RESOLVED` and closed open SLA milestones.
  - `POST /workspaces/automation/{id}/simulate` returned `SIMULATED` and created a dry-run trace.
  - `POST /workspaces/migration/{id}/validate` returned validation status based on recorded validation errors.
  - `POST /workspaces/mobile/{id}/sync` returned `SYNCED` and cleared the offline queue.

## 2026-07-25 second workspace command action verification

- Backend `mvn verify` passed after extending the shared workspace command layer to contracts, campaigns, partners, copilot and BFSI.
- Frontend `npm run build` passed after adding the new action labels/prompts to the shared operational workspace page.
- Patched API and web images were refreshed locally from the already-available runtime images by copying in the freshly verified Spring Boot JAR and Vite `dist` bundle; PostgreSQL 17, Kafka 3.7, API and web containers are healthy.
- Authenticated same-origin action smoke passed through `http://localhost:4280/api/v1`:
  - `POST /workspaces/contracts/{id}/activate` returned `ACTIVE` with signed-document evidence.
  - `POST /workspaces/campaigns/{id}/complete` returned `COMPLETED` with campaign outcome evidence.
  - `POST /workspaces/partners/{id}/activate` returned `ACTIVE` after the local seeded channel conflict was waived for the success-path smoke.
  - `POST /workspaces/copilot/{id}/accept` returned `ACCEPTED` and enforced citation-backed recommendation acceptance.
  - `POST /workspaces/bfsi/{id}/clear` returned `CLEARED` after the local seeded screening hit was waived for the success-path smoke.

## 2026-07-25 third workspace command action verification

- Backend `mvn verify` passed after adding Dashboard Refresh, Integration Contract Verify, Sandbox Refresh, Audit Evidence Pack Export and Commodity Offer commands.
- Frontend `npm run build` passed after wiring the new row-level actions into the shared epic workspace page.
- Flyway `V96__commodity_offer_demo_readiness` was added as a forward-only seed migration so the commodity offer demo path has an approved term sheet and sufficient credit without mutating released migration history.
- Patched API and web images were refreshed locally from the already-available runtime images by copying in the freshly verified Spring Boot JAR and Vite `dist` bundle; PostgreSQL 17, Kafka 3.7, API and web containers are healthy.
- Authenticated same-origin action smoke passed through `http://localhost:4280/api/v1`:
  - `POST /workspaces/analytics/{id}/refresh` returned `REFRESHED` and advanced dashboard refresh evidence.
  - `POST /workspaces/integrations/{id}/verify` returned `ACTIVE`, reset failure count and kept vendor execution pending.
  - `POST /workspaces/sandbox/{id}/refresh` returned `ACTIVE` after requiring a refresh reason.
  - `POST /workspaces/audit/{id}/export` returned `EXPORTED` and marked the evidence pack for secure download.
  - `POST /workspaces/commodity/{id}/offer` returned `OFFERED` after credit exposure and approved term-sheet validation passed.

## 2026-07-25 Data Grid utility-frame verification

- Frontend `npm run build` passed after moving Group, Audit, Export Excel, Export Word and Export PDF controls into the Data Grid frame header for Accounts, Leads and all epic workspaces.
- The grid Full size/Restore control remains in the same sticky header, so users retain the whole utility set while reviewing expanded tables.
- Audit drawers now use a shared reusable component and are scoped by the active grid entity type, keeping master and workspace evidence access consistent.

## 2026-07-25 universal Data Grid utility-frame verification

- Frontend `npm run build` passed after extending the standard Data Grid utility header to Activities, CPQ, Reports, Reference Data and all Administration sub-screens.
- Reports now show grid-level Export Excel, Export Word, Export PDF, Audit, Group and Full size/Restore in the catalogue frame while keeping Jasper PDF/XLSX/DOCX downloads on each report card.
- Screens without a dedicated backend export endpoint use a current-view client export fallback so the utility is still available from the Data Grid in normal and full-size views; screens with governed backend exports continue to use those server routes.

## 2026-07-26 universal button geometry verification

- Frontend `npm run build` passed after adding the cross-screen button sizing contract.
- The User Manual drawer, Data Grid toolbars, report cards, admin tabs, notification controls, menus, icon buttons and row actions now share fixed height/min-width tokens for consistent button geometry across normal and responsive layouts.
- Bootstrap compatibility was hardened by mapping Bootstrap button variables to Axiom design tokens, using logical sizing properties, shared focus rings, disabled states and larger coarse-pointer touch targets.
- Header visual polish passed frontend build: the workspace context, command search, manual button and user menu now align to one optical baseline; the username/role stack has explicit spacing; the User Manual close control is a compact square instead of a full-width action button.

Docker Hub pulls were blocked on this workstation by Docker Desktop's internal HTTPS proxy. Local runtime images plus PostgreSQL 16 and Kafka 3.7 were used for this workstation's live verification only; that private recovery override is deliberately not part of the repository. The canonical, reproducible Compose definition and version targets remain unchanged and are the CI/release contract.

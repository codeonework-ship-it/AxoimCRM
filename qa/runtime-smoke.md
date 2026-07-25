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

Docker Hub pulls were blocked on this workstation by Docker Desktop's internal HTTPS proxy. Local runtime images plus PostgreSQL 16 and Kafka 3.7 were used for this workstation's live verification only; that private recovery override is deliberately not part of the repository. The canonical, reproducible Compose definition and version targets remain unchanged and are the CI/release contract.

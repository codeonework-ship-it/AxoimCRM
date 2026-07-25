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

Docker Hub pulls were blocked on this workstation by Docker Desktop's internal HTTPS proxy. Local runtime images plus PostgreSQL 16 and Kafka 3.7 were used for this workstation's live verification only; that private recovery override is deliberately not part of the repository. The canonical, reproducible Compose definition and version targets remain unchanged and are the CI/release contract.

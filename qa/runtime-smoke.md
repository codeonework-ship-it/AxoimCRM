# Runtime smoke verification

## Canonical stack

```powershell
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

The canonical Compose file builds the Spring Boot API and React web application from source, then runs PostgreSQL 17, Kafka 3.8, the API on port 8080, and Nginx web on port 4280.

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

Docker Hub pulls were blocked on this workstation by Docker Desktop's internal HTTPS proxy. Local runtime images plus PostgreSQL 16 and Kafka 3.7 were used for this workstation's live verification only; that private recovery override is deliberately not part of the repository. The canonical, reproducible Compose definition and version targets remain unchanged and are the CI/release contract.

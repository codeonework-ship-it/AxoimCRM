# Axiom — Enterprise B2B CRM

This repository holds the **complete product definition and architecture** for Axiom, an enterprise B2B CRM (working codename — see [product scope](docs/product/01-product-scope.md)), together with a runnable, tested walking implementation. The product remains broader than the shipped slice; this README is explicit about that boundary.

Axiom's one-paragraph pitch: enterprise governance without enterprise lock-in. SSO, audit, full API, sandboxes and baseline AI in every tier; one codebase deployable as pooled SaaS or a customer-controlled sovereign install; explainable numbers everywhere; and vertical depth (BFSI, commodity trading origination) as installable packs rather than consulting projects.

## What runs today vs. what is specified

| | Status |
|---|---|
| Product definition (scope → FRD → catalogue → backlog → tests) | ✅ Baselined — 287 requirements, 373 features, 23 epics / 183 stories / 1,352 points |
| Architecture (system design + ADR-001…008) | ✅ Baselined (ADR-005 technology choice is a recommendation pending ratification) |
| Backend walking slice | 🟡 Runs and is release-smoke verified: JWT auth, Postgres RLS tenancy, transactional outbox → Kafka relay, lead→opportunity flow, stage gate, dashboard reads, and recipient-scoped notifications |
| Frontend (`frontend/`) | 🟡 Runnable Axiom 1.0 preview: responsive command shell, Home, Pipeline, Accounts, Leads, command palette, help, and server-backed notification centre |
| Desktop shell (`electron-client/`) | 🟡 Runnable Electron shell with native-notification bridge; packaging/signing remain open |
| QA suite (`qa/`) | 🟡 Master plan, UAT plan, automation scenarios, five notification tests, and live-stack/browser smoke evidence; broader regression automation remains open |

Live epic-by-epic status: [docs/epic-status.md](docs/epic-status.md). Release plan: [docs/product/15-agile-delivery-plan.md](docs/product/15-agile-delivery-plan.md).

## Repository layout

```
docs/
  product/          # The product definition, numbered in reading order
  architecture/     # System design + ADRs
  manual/           # End-user documentation
  epic-status.md    # Delivery tracker (all 23 epics)
backend/            # Spring Boot modular-monolith skeleton (Java 21)
frontend/           # React + TypeScript + Vite scaffold
electron-client/    # Electron desktop shell scaffold
qa/                 # QA/UAT strategy, automation scenarios, runtime evidence
deploy/env/         # dev/qa/uat/prod runtime environment files
docker-compose.yml  # Local stack: Postgres + Kafka + API + web
```

## The documentation set

Start with [docs/README.md](docs/README.md) for the recommended reading order. The full inventory:

### docs/product

| Doc | One-liner |
|---|---|
| [01-product-scope.md](docs/product/01-product-scope.md) | Vision, positioning, personas, capability map, explicit out-of-scope |
| [02-competitive-analysis-salesforce-zoho.md](docs/product/02-competitive-analysis-salesforce-zoho.md) | Where Salesforce and Zoho win, gate, and leave openings |
| [03-frd.md](docs/product/03-frd.md) | 287 testable functional requirements with traceability matrix |
| [04-feature-catalogue.md](docs/product/04-feature-catalogue.md) | 373 features mapped against both competitors, classified TS/GAP/UNQ/PAR |
| [05-epics-and-stories.md](docs/product/05-epics-and-stories.md) | 23 epics, 183 stories, 1,352 points, with DoR/DoD and acceptance criteria |
| [06-acceptance-tests.md](docs/product/06-acceptance-tests.md) | The verification contract — acceptance and edge-case catalogue per story |
| [07-uat-plan.md](docs/product/07-uat-plan.md) | Per-persona business validation scenarios *(in progress)* |
| [08-rbac-and-sharing-model.md](docs/product/08-rbac-and-sharing-model.md) | Profiles, permission sets, role hierarchy, sharing, FLS, SoD, maker-checker |
| [09-data-model.md](docs/product/09-data-model.md) | Logical data model, extensibility model, volume constraints |
| [10-nfr-and-enterprise-readiness.md](docs/product/10-nfr-and-enterprise-readiness.md) | Scale/latency/availability targets, compliance mapping, what is *not* claimed |
| [11-ai-capabilities.md](docs/product/11-ai-capabilities.md) | The AI capability set, guardrails, explainability rules, AI-off mode |
| [12-vertical-pack-bfsi.md](docs/product/12-vertical-pack-bfsi.md) | BFSI pack: RM books, KYC/AML, suitability, communication archiving |
| [13-integration-and-migration.md](docs/product/13-integration-and-migration.md) | APIs, webhooks, events, connectors, and the Salesforce/Zoho/HubSpot importers |
| [14-reporting-and-analytics.md](docs/product/14-reporting-and-analytics.md) | Report builder, governed KPI formulas, dashboards, export governance |
| [15-agile-delivery-plan.md](docs/product/15-agile-delivery-plan.md) | Squads, sprints, release trains R1–R4, honest velocity math, risk register |
| [16-tenancy-licensing-and-deployment.md](docs/product/16-tenancy-licensing-and-deployment.md) | Tenant lifecycle, three deployment models, entitlements, never-tier-gated list |
| [17-vertical-pack-commodity-trading.md](docs/product/17-vertical-pack-commodity-trading.md) | Commodity pack: origination only, credit gates, CTRM hand-off contract |
| [18-notifications-and-alerting.md](docs/product/18-notifications-and-alerting.md) | Notification centre, type catalogue, channels, digests, access-safe delivery |

### docs/architecture

- [system-design.md](docs/architecture/system-design.md) — the forces, the modular monolith, scaling model, request path, event backbone, AI and integration architecture
- [adr/](docs/architecture/adr) — ADR-001 tenancy isolation · 002 extensibility · 003 event backbone · 004 AI provider abstraction · 005 technology selection (deferred/recommended) · 006 modular monolith · 007 external integration · 008 reporting read model

- [database-module-schemas.md](docs/architecture/database-module-schemas.md) - physical PostgreSQL schemas, table ownership, and tenant-consistent FK rules

### docs/manual

- [user-guide.md](docs/manual/user-guide.md) — the end-user manual for business users (leads, pipeline, forecasting, AI assistant, notifications, shortcuts, FAQ)

### qa

QA master test plan and automation are being authored *(in progress)* — the FRD's traceability section already binds to `qa/qa-master-test-plan.md` by name.

## Running the backend skeleton

The backend is a Spring Boot (Java 21) modular monolith implementing the walking skeleton: tenant-scoped persistence with **two-layer isolation** (application `TenantContext` + PostgreSQL row-level security), Flyway migrations, a **transactional outbox** relayed to Kafka, and the lead→opportunity slice schema with demo seed data.

**Prerequisites:** Docker (easiest), or locally: JDK 21 + Maven, PostgreSQL 17, and a Kafka broker.

**With Docker (recommended):**

```bash
docker compose up -d --build
# Web preview: http://localhost:4280
# API: http://localhost:8080 — health at /actuator/health
```

Environment-specific database files live in `deploy/env/`. The shared CRM database login requested for each environment is:

| Environment | Database | User | Password |
|---|---|---|---|
| Dev | `AxiomCRMDB_Dev` | `AxiomCRM` | `AxiomCRM@12345` |
| QA | `AxiomCRMDB_QA` | `AxiomCRM` | `AxiomCRM@12345` |
| UAT | `AxiomCRMDB_UAT` | `AxiomCRM` | `AxiomCRM@12345` |
| Prod | `AxiomCRMDB_Prod` | `AxiomCRM` | `AxiomCRM@12345` |

For the current development profile:

```bash
docker compose --env-file deploy/env/dev.env up -d --build
```

The compose images are configurable with `AXIOM_POSTGRES_IMAGE` and `AXIOM_KAFKA_IMAGE`; the default Kafka image is set to a locally verified Apache Kafka tag for reproducible dev startup.

### Demo access

Open `http://localhost:4280` and use workspace `meridian` with password
`axiom-demo`. The seeded personas are:

| Persona | Email | Role |
|---|---|---|
| Axiom Super Admin | `superadmin@axiomcrm.com` | Super admin across tenants |
| Axiom Super Auditor | `superaudit@axiomcrm.com` | Read-only auditor across tenants |
| Priya Nair | `priya.nair@meridianfab.com` | Sales |
| Raj Malhotra | `raj.malhotra@meridianfab.com` | Tenant admin |
| Maya Torres | `maya.torres@meridianfab.com` | Sales |
| Ava Chen | `ava.chen@northstar.example` | Tenant admin for workspace `northstar` |

These credentials are development seed data only. They must not be reused in a
shared, staging, or production environment.

The compose stack brings up Postgres 17, a single-node Kafka (KRaft), and the API. Migrations run as the schema-owning role; runtime traffic uses a least-privilege `axiom_app` role that is subject to RLS — the isolation model is exercised even in dev, deliberately.

**Locally without Docker:**

```bash
# 1. PostgreSQL 17 with a database `axiom` on localhost:5432
#    (defaults in backend/src/main/resources/application.yml; override via
#     SPRING_DATASOURCE_* / FLYWAY_* environment variables)
# 2. Kafka on localhost:9092 (KAFKA_BOOTSTRAP_SERVERS to override)
cd backend
mvn spring-boot:run
```

Flyway applies `V1__baseline.sql` (schema + RLS policies) and the demo seed on first boot.

**What you get, and don't:** a booting API with an RLS-enforced schema, outbox relay, seed data, a user-scoped in-app notification service, and a production-built web preview. The UI covers the walking lead→opportunity slice. There is no SSO, general record-sharing model, email/push notification delivery, automation engine, or production AI yet — see [epic-status.md](docs/epic-status.md) for the exact boundary.

Current verified increment: platform super-admin/super-audit roles, tenant switching, governed master toolbars, account/lead bulk-upload templates, soft-delete protections, immutable audit, server-side pagination/search/filtering at 100 rows per page, and export parity for Excel, Word, and PDF. Vendor and third-party connector implementations remain intentionally pending.

## Frontend and desktop client

- `frontend/` — React 18 + TypeScript + Vite web application (`npm install && npm run dev`, serves on `:5173`) or production Nginx image in Compose on `:4280`.
- `electron-client/` — Electron shell scaffold (`npm install && npm start`) plus local desktop packaging (`npm run package`) that creates a portable Windows folder and zip under `electron-client/release/`. The desktop client is the delivery vehicle for OS-native push notifications per the [notifications spec](docs/product/18-notifications-and-alerting.md); code signing and store publishing still require external certificates/accounts and are tracked as release-management risks in the [delivery plan](docs/product/15-agile-delivery-plan.md#9-risk-register).

## Ground rules for contributors

1. **The FRD is the contract.** Behaviour ships when its acceptance criteria pass, not when the code compiles — see [DoR/DoD](docs/product/05-epics-and-stories.md#definition-of-ready).
2. **No path around tenancy.** Every persistence operation goes through the tenant-scoped base; RLS is the backstop, not the mechanism ([ADR-001](docs/architecture/adr/ADR-001-tenancy-isolation.md)).
3. **Nothing UI-only.** Every capability is API-first (`FR-INT-001`).
4. **Update [epic-status.md](docs/epic-status.md)** when a story genuinely reaches done.

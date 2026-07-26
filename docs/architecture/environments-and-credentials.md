# Environments, credentials and roles

**Status:** Authoritative · **Date:** 2026-07-25

The complete credential and role inventory for Axiom: four environment databases, the PostgreSQL login roles, the application role catalogue, and the seeded accounts.

> **Production warning, stated once and meant.** The values in this document and in the committed `.env.*` files are **development credentials**. They are versioned deliberately — the same convention the sibling Octane platform uses — so a new machine is reproducible without out-of-band setup. Repository access is a far larger group than database access should ever be, so **before any real production deployment: rotate every production value and move it to a secret manager.** `JwtService` already refuses to start on a non-dev/test profile with a default or weak signing key; the database password has no equivalent guard and is the one to fix first.

---

## 1. The four environments

Each environment is its own database. Locally they share one PostgreSQL cluster; in a real deployment QA/UAT/Prod live on separate servers and only the host changes, because the database name is identical everywhere.

| Environment | Database | Spring profile | Env file |
|---|---|---|---|
| Development | `AxiomCrmdb_Dev` | `dev` | `.env.dev` |
| QA | `AxiomCrmdb_QA` | `qa` | `.env.qa` |
| UAT | `AxiomCrmdb_UAT` | `uat` | `.env.uat` |
| Production | `AxiomCrmdb_Prod` | `prod` | `.env.prod` |

Database names are **created double-quoted**. PostgreSQL folds unquoted identifiers to lower case, so `AxiomCrmdb_Dev` written without quotes silently becomes `axiomcrmdb_dev` and every mixed-case connection string then fails to find it.

---

## 2. PostgreSQL login roles

Three roles, and the separation between them is the mechanism that makes tenant isolation real rather than aspirational.

| Role | Password | Purpose |
|---|---|---|
| `Axiom` | `Axiom@12345` | **Owner / migration identity.** Owns the schema, runs Flyway, defines the RLS policies. Has `CREATEDB` and `CREATEROLE`. Not used to serve traffic. |
| `axiom_app` | `axiom_app_dev_password` | **Runtime identity.** Every authenticated request runs as this role. |
| `axiom_relay` | `axiom_relay_dev_password` | **Outbox relay only** (ADR-003). Cross-tenant read/update on `outbox_event` and nothing else. |

### Why the owner and the runtime role must stay different

**A table's owner bypasses row-level security in PostgreSQL.** If the application connected as `Axiom`, every `tenant_isolation` policy in the schema would be silently inert and cross-tenant isolation would be gone — with no error, no failing test, and no visible symptom until a customer saw another customer's data.

So `axiom_app` owns nothing, is `NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS`, and receives only DML grants. Both `scripts/db/init/02-per-database-roles.sql` and `03-adopt-existing-objects.sql` end with the same guard-rail query, which returns rows only if a runtime role has come to own a table. **Any output from it is a defect, not a warning.**

`axiom_relay` gets no default privileges at all and is granted explicitly on `outbox_event` alone — a relay that could read any table would be a cross-tenant reader operating with no tenant context.

### Bootstrap

```bash
psql -U <superuser> -d postgres -f scripts/db/init/01-create-roles-and-databases.sql
```

Then, once per database (roles are cluster-wide; grants are not):

```bash
for DB in AxiomCrmdb_Dev AxiomCrmdb_QA AxiomCrmdb_UAT AxiomCrmdb_Prod; do
  psql -U Axiom -d "$DB" -f scripts/db/init/02-per-database-roles.sql
done
```

`03-adopt-existing-objects.sql` is only for a database created before this convention, whose objects are still owned by an earlier role. New environments never need it.

---

## 3. Application role catalogue

Defined in `com.axiom.auth.CrmRole` — one enum consumed by both the API and the UI, so the API and the navigation can never disagree about what a role may do.

### Platform scope — act across every tenant

| Role | Read-only | Export | Import | Master admin | Description |
|---|:--:|:--:|:--:|:--:|---|
| `SUPER_ADMIN` | — | ✅ | ✅ | ✅ | Read/write administration across every tenant |
| `SUPER_AUDIT` | ✅ | ✅ | — | — | Read-only audit and reporting across every tenant |

Platform roles are the only ones that may reach the company, billing and cross-tenant operator views. Those endpoints call `PlatformSession.requirePlatformAndGrant()`, which authorises the role **and then** sets the `app.platform_access` flag the V9 RLS policies accept — so a query that forgets its guard returns zero rows rather than every tenant's invoices.

### Tenant scope — confined to one tenant

| Role | Read-only | Export | Import | Master admin | Description |
|---|:--:|:--:|:--:|:--:|---|
| `TENANT_ADMIN` | — | ✅ | ✅ | ✅ | Full administration inside one tenant |
| `SALES_MANAGER` | — | ✅ | — | — | Sales team, pipeline, account and lead management |
| `SALES` | — | ✅ | — | — | Owned sales execution and customer records |
| `MARKETING` | — | ✅ | — | — | Campaign, segment and lead operations |
| `SERVICE` | — | ✅ | — | — | Cases, accounts, contacts and entitlements |
| `OPERATIONS` | — | ✅ | — | — | Revenue operations and governed reporting |
| `FINANCE` | — | ✅ | — | — | Commercial and financial review |
| `DATA_STEWARD` | — | ✅ | ✅ | ✅ | Validated master-data import and lifecycle management |
| `AUDITOR` | ✅ | ✅ | — | — | Read-only audit and reporting inside one tenant |
| `INTEGRATION` | — | — | — | — | Non-interactive API integration identity |

`INTEGRATION` deliberately has **no export permission**: a service credential that can bulk-export the tenant is a data-exfiltration path that no human ever reviews.

Export is a permission distinct from read (`FR-SEC-015`) — `CrmRole.requireExport` — because the right to *see* a record and the right to *remove a copy of it from the system* are different risks.

---

## 4. Seeded application accounts

Tenant `meridian` (**Meridian Fabrication Group**), created by `V2__seed_demo.sql`. Password for all three: **`axiom-demo`** (bcrypt-hashed via `pgcrypto`).

| Email | Name | Role |
|---|---|---|
| `raj.malhotra@meridianfab.com` | Raj Malhotra | `TENANT_ADMIN` |
| `priya.nair@meridianfab.com` | Priya Nair | `SALES` |
| `maya.torres@meridianfab.com` | Maya Torres | `SALES` |

Platform operator accounts live in `platform.platform_user` (see `V5`). **Demo data only — not for any environment holding real customer records.**

---

## 5. Application secrets

| Setting | Dev default | Notes |
|---|---|---|
| `AXIOM_JWT_SECRET` | the committed development key | **Guarded.** `JwtService.assertSecretIsSafe` refuses to start on any profile other than `dev`/`test` if this is absent, blank, left at the committed default, or shorter than 32 bytes. The committed key is public, so anyone holding the repository could otherwise forge a `SUPER_ADMIN` token for any tenant. |
| `AXIOM_JWT_TTL_MINUTES` | `480` | Access-token lifetime. |
| `AXIOM_CORS_ORIGINS` | `http://localhost:5173,http://localhost:4280` | Explicit origins only; `CorsConfig` rejects a wildcard outright. |

---

## 6. Running an environment

```bash
docker compose --env-file .env.dev -p axiomcrm-dev up -d
```

Or without containers:

```bash
SPRING_PROFILES_ACTIVE=qa mvn -f backend/pom.xml spring-boot:run
```

Flyway connects as `Axiom` and applies the migrations; the application then serves traffic as `axiom_app`, and the outbox relay uses its own `axiom_relay` pool.

---

## Related documents

- [ADR-001 — tenancy isolation](adr/ADR-001-tenancy-isolation.md) — why owner and runtime roles are separate
- [ADR-003 — event backbone](adr/ADR-003-event-backbone.md) — the relay role's purpose
- [RBAC and sharing model](../product/08-rbac-and-sharing-model.md) — how roles combine with sharing
- [Tenancy, licensing and deployment](../product/16-tenancy-licensing-and-deployment.md)

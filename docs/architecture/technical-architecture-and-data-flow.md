# Axiom CRM 1.0 - Technical Architecture And Data Flow

## 1. Executive explanation

Axiom is a multi-tenant revenue platform built as a modular monolith. In plain language, the product is deployed as one API application, but its code and database are divided into owned modules with explicit boundaries. That gives the team one operational unit today while preserving an extraction path for a module that later needs independent scale.

The browser and Electron client never connect to PostgreSQL or Kafka directly. They call the REST API. The API authenticates the user, establishes tenant context, authorizes the screen and record, validates the request, checks workflow gates and writes business data. Every accepted mutation writes immutable audit evidence and an outbox event in the same database transaction.

## 2. System context

```mermaid
flowchart LR
  User[Web or Electron user] --> UI[React application]
  UI --> API[Spring Boot REST API]
  API --> DB[(PostgreSQL module schemas)]
  API --> OUTBOX[Transactional outbox]
  OUTBOX --> KAFKA[Kafka event backbone]
  KAFKA --> PROJ[Analytics projections]
  PROJ --> REPORTS[Jasper report service]
  REPORTS --> UI
  API --> ADAPTERS[Vendor-neutral adapters]
  ADAPTERS --> VENDORS[Approved external vendors]
```

The external-vendor box is an adapter boundary. First-party workflows are complete without pretending that an unconfigured mail, identity, finance, telephony or trading vendor is available.

## 3. Request and authorization flow

```mermaid
sequenceDiagram
  actor Operator
  participant UI as React/Electron
  participant API as REST API
  participant Auth as Authentication/RBAC
  participant Domain as Domain service
  participant DB as PostgreSQL
  Operator->>UI: Select Load or submit an action
  UI->>API: Bearer token + tenant-scoped request
  API->>Auth: Validate session, tenant, role, record and field access
  Auth-->>API: Effective permission
  API->>Domain: Validate command and workflow gate
  Domain->>DB: Business row + audit + outbox in one transaction
  DB-->>Domain: Commit or rollback all
  Domain-->>UI: Result or layman-language failure
```

Defence is layered: signed session, tenant context, screen permission, record authorization, field protection, service predicates, foreign keys/checks, and forced PostgreSQL row-level security. A missing UI button is convenience; server and database enforcement are authority.

## 4. Core entity relationship model

```mermaid
erDiagram
  TENANT ||--o{ APP_USER : contains
  TENANT ||--o{ ACCOUNT : owns
  APP_USER ||--o{ ACCOUNT : manages
  ACCOUNT ||--o{ CONTACT : employs
  ACCOUNT ||--o{ OPPORTUNITY : pursues
  LEAD o|--o| ACCOUNT : converts_to
  LEAD o|--o| CONTACT : converts_to
  LEAD o|--o| OPPORTUNITY : converts_to
  PIPELINE_STAGE ||--o{ OPPORTUNITY : classifies
  OPPORTUNITY ||--o{ QUOTE : priced_by
  QUOTE ||--o{ QUOTE_LINE : contains
  PRODUCT ||--o{ QUOTE_LINE : references
  QUOTE o|--o| CONTRACT : accepted_as
  CONTRACT ||--o{ SUBSCRIPTION : governs
  ACCOUNT ||--o{ CASE : receives
  ACCOUNT ||--o{ ACTIVITY : relates_to
  OPPORTUNITY ||--o{ ACTIVITY : relates_to
  BUSINESS_RECORD ||--o{ AUDIT_EVENT : evidenced_by
  BUSINESS_RECORD ||--o{ OUTBOX_EVENT : announces
```

Every tenant-owned row carries `tenant_id`. UUID primary keys identify records. Foreign keys protect relationships. Unique/check constraints prevent duplicate or impossible states. Business masters are soft-deleted; a referenced value cannot be removed.

## 5. Create-to-report flow

```mermaid
flowchart TD
  A[Create or update CRM record] --> B{Permission and workflow valid?}
  B -- No --> C[Return safe explanation and write no business change]
  B -- Yes --> D[Commit business row + audit event + outbox event]
  D --> E[Relay publishes event]
  E --> F[Idempotent projector updates analytics fact]
  F --> G[Governed KPI/report query]
  G --> H[100-row Report Grid]
  G --> I[Jasper PDF]
  G --> J[Excel / Word]
  H --> K[Drill-through rechecks current source permission]
  F --> L[Independent reconciliation]
  L -->|Drift| M[Block certification and rebuild/investigate]
```

The report projection is optimized for reading but is not a second source of truth. Dataset fingerprints and row counts prove grid/PDF/Excel/Word parity. Reconciliation compares projection results with independent operational recomputation.

## 6. Workflow activity model

```mermaid
stateDiagram-v2
  [*] --> Draft
  Draft --> Validated: required data present
  Validated --> Approval: threshold or policy requires approval
  Validated --> Ready: no approval required
  Approval --> Ready: different authorized approver accepts
  Approval --> Rejected: approver rejects with reason
  Ready --> Executed: governed action succeeds
  Executed --> [*]
  Draft --> Blocked: prerequisite missing
  Blocked --> Draft: user corrects named issue
```

Concrete modules specialize this model: opportunity stages, quote/discount approval, contract activation and renewal, forecast submission/lock, campaign and case lifecycle, partner registration, automation simulation, migration validation/rollback, BFSI onboarding and commodity execution handoff.

## 7. Module and schema ownership

| Area | Schemas | Responsibility |
|---|---|---|
| Platform identity | `platform`, `identity`, `security` | tenants, users, sessions, SSO/SCIM, RBAC, sharing, step-up and record locks |
| Core CRM | `crm`, `leads`, `pipeline` | accounts, contacts, leads, opportunities, duplicate control and lifecycle |
| Engagement | `engagement`, `marketing`, `service`, `channel` | activities, templates, campaigns, cases, partners and alerts |
| Revenue | `cpq`, `contracting`, `forecasting`, `billing` | products, prices, quotes, orders, contracts, subscriptions, forecast and billing controls |
| Intelligence | `analytics`, `reporting`, `ai` | projections, governed KPIs, report definitions, Jasper artifacts and grounded recommendations |
| Orchestration | `automation`, `integration`, `dispatch`, `migration` | processes, gates, approvals, outbox, connector delivery, migration and recovery |
| Governance | `governance`, `compliance`, `documentation`, `i18n`, `activity` | audit, privacy/retention, manual master, translations and user activity |
| Extended operations | `mobile`, `bfsi`, `commodity` | offline packages/conflicts and vertical workflow packs |

Cross-module calls go through published services. A module does not reach into another module's internal package. Database table ownership is registered in `governance.module_table_catalog`.

## 8. Event and failure recovery flow

```mermaid
flowchart LR
  TX[Database transaction] --> O[(outbox_event)]
  O --> R[Outbox relay]
  R --> K[Kafka topic]
  K --> C1[Analytics consumer]
  K --> C2[Automation consumer]
  K --> C3[Dispatch consumer]
  C1 --> P[(Projection)]
  C2 --> X[(Execution receipt)]
  C3 --> D{Delivery result}
  D -- retryable --> Q[Backoff queue]
  D -- exhausted --> DLQ[Dead-letter workspace]
  DLQ --> REPLAY[Authorized replay]
```

Consumers use event identity/receipts so redelivery does not duplicate work. External failures never roll back an already-committed CRM transaction. Retry, dead-letter and replay remain visible to operators.

## 9. Migration and bulk data flow

```mermaid
flowchart TD
  SRC[Source file or connector] --> DISC[Read-only discovery]
  DISC --> MAP[Versioned field mapping]
  MAP --> DRY[Zero-write dry run]
  DRY -->|errors| FIX[Correct source/mapping]
  FIX --> DRY
  DRY -->|valid| IMPORT[Chunked import + ownership ledger]
  IMPORT --> REC[Source-to-target reconciliation]
  REC --> DELTA[Checkpointed delta re-sync]
  IMPORT --> ROLL[Rollback preview]
  ROLL --> EXEC[Delete only ledger-owned records]
```

Master CSV imports validate the complete file and commit atomically, with a 5,000-row and 5 MiB synchronous limit. Lead JSON batches accept up to 1,000 rows with per-row outcomes. Larger migrations use resumable asynchronous jobs, not a browser request containing a million records.

## 10. Offline synchronization activity

```mermaid
flowchart TD
  ISSUE[Authorize and issue offline package] --> WORK[Operator works offline]
  WORK --> SYNC[Reconnect and submit changes]
  SYNC --> CHECK{Package/session still valid?}
  CHECK -- No --> REJECT[Reject and require fresh package]
  CHECK -- Yes --> VERSION{Source version unchanged?}
  VERSION -- Yes --> APPLY[Apply through normal validation/gates]
  VERSION -- No --> CONFLICT[Create conflict with local/server versions]
  CONFLICT --> DECIDE[Authorized operator chooses resolution]
  DECIDE --> APPLY
  APPLY --> AUDIT[Audit and outbox evidence]
```

## 11. Deployment and observability

The development topology runs React/Vite on port 4280 and the Spring Boot API on 8080, with PostgreSQL on 5432. Production uses built static assets behind the approved web tier and horizontally scaled stateless API nodes. Kafka is the event backbone; PostgreSQL remains the transactional source of truth. Prometheus-compatible metrics cover request latency/error/saturation, pool usage, event lag, projection freshness and job failures.

Backup and disaster recovery require continuous WAL/archive policy, encrypted backups, a separate recovery location, tested restore, recorded RPO/RTO evidence and an approved release rollback path. A runbook is not certified until a rehearsal succeeds.

## 12. Impact analysis

| Change | Direct impact | Indirect impact and mandatory regression |
|---|---|---|
| Account/contact field | CRM validation and UI | search index, duplicate rules, report projection, exports, permissions, migration mapping and documentation |
| Opportunity stage/amount/date | pipeline lifecycle | workflow gates, forecast snapshots, weighted pipeline, velocity/conversion, alerts and Jasper parity |
| Product/price/discount | CPQ | quote totals, approvals, margin report, order/contract conversion and audit |
| Contract/subscription | contracting | ARR/ACV/TCV, renewal/churn, billing handoff, Customer 360 and alerts |
| RBAC/sharing | authorization | every screen/API/report/drill/export/search result; maker-checker and tenant-isolation tests |
| KPI definition | analytics registry | dashboard, report, threshold alert, historical version, reconciliation and explanatory manual |
| Event schema | producer/outbox | every consumer, replay/idempotency, projection rebuild and compatibility contract |
| Reference master | selecting modules | historical label, in-use delete protection, import/export template and report filters |
| Translation/manual content | i18n/documentation | header sizing, every locale fallback, drawer master revision and PDF manual |

## 13. Architectural non-negotiables

1. No hard delete for governed masters.
2. No tenant query without enforced tenant context and row-level security.
3. No mutation without matching audit/outbox evidence.
4. No report aggregate that cannot expose formula, version and contributing records.
5. No drill-through without a fresh authorization check.
6. No workflow shortcut through UI, API, automation, import or support SQL.
7. No dry-run that writes business state.
8. No migration rollback beyond records proven to be migration-owned.
9. No vendor integration claimed complete without configured vendor certification.
10. No release described as zero-failure without named automated and manual evidence.


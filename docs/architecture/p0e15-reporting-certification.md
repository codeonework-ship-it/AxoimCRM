# P0E15 Reporting Projection And Certification Closure

## Decision

P0E15 is closed by a four-part control chain. A projection is not trusted merely
because it rebuilt, and a fast query is not called production-certified merely
because it ran quickly over demonstration data.

1. `POST /api/v1/analytics/projections/rebuild-and-reconcile` rebuilds the
   tenant's four analytical facts and stage transitions, removes tombstones,
   refreshes account rollups and fast-forwards projection checkpoints. It then
   runs independent projection and KPI reconciliation and returns one PASS/FAIL
   verdict.
2. Projection reconciliation compares nine exact read-model aggregates with
   deliberately separate OLTP SQL. Every observation is append-only evidence in
   `analytics.reconciliation_run`.
3. KPI reconciliation independently recomputes win rate, average deal size, ACV,
   ARR, TCV and MQL-to-SQL conversion foundations. Evidence is stored in
   `analytics.kpi_reconciliation_run`; null denominators remain null and are not
   silently converted to zero.
4. `POST /api/v1/analytics/certifications/production` creates an immutable
   production certificate attempt. PASS requires at least 1,000,000 projected
   rows, 50 governed queries in the preceding 30 days, p95 at or below 3,000 ms,
   zero timeouts, zero projection drift and zero KPI drift. Thresholds are server
   constants and cannot be weakened by the caller. A smaller environment returns
   `INSUFFICIENT_EVIDENCE`, never a misleading pass.

All operational writes require a master-administrator role, are tenant/RLS
scoped and write audit evidence. Certificate and KPI evidence tables allow INSERT
and SELECT only to the application role.

## Grid And Jasper Parity

The report grid, inline PDF, PDF download, Excel and Word exports resolve the same
filtered and ordered row dataset. A length-delimited SHA-256 fingerprint covers
all four visible columns in row order. The grid response exposes
`datasetFingerprint`; document responses expose the same value in
`X-Axiom-Dataset-Fingerprint` and the row count in `X-Axiom-Report-Rows`.
Jasper prints the full fingerprint and row count in the PDF footer. This makes a
support or regression check able to prove which exact dataset produced a document.

## Operator Runbook

Use **Reports → Custom Reports → Certification**:

- **Rebuild And Reconcile** after a deployment, restored tenant or projection fix.
- **Reconcile KPIs** to investigate a suspected metric drift without rebuilding.
- **Certify Production** after the production-like load and query suite has
  populated the 30-day evidence window.

An unresolved drift is an incident, not an accepted tolerance. Rebuild once. If
the same check still drifts, investigate projection/formula SQL. Do not repeatedly
rebuild until the alert disappears.

## Verification Contract

- Unit tests prove independent OLTP/read-model SQL paths, durable evidence,
  certificate pass/insufficient-evidence decisions and order-sensitive report
  fingerprints.
- Both Jasper templates compile during the regression suite and carry fingerprint
  parameters.
- Backend full-suite, frontend production build, accessibility and runtime suites
  are the release gate.
- Verification uses the configured host PostgreSQL and host processes. Docker is
  explicitly excluded from this implementation and runbook.


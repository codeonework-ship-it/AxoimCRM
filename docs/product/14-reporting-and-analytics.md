# Reporting and analytics

This document expands FRD module `RPT` (`FR-RPT-001` … `FR-RPT-011`, epic E15) and the analytics requirements of module `FCT` (`FR-FCT-009` … `FR-FCT-012`, with the snapshot machinery of `FR-FCT-004` … `FR-FCT-006`) into the reporting specification: the catalogue by persona, the governed KPI definitions with their exact formulas, the report builder, and the governance around access, delivery and export. The architecture underneath is [ADR-008 — reporting read model](../architecture/adr/ADR-008-reporting-read-model.md).

The governing product principle is **explain every number** ([product scope](01-product-scope.md), principle 2): a number a manager cannot decompose is a defect. Reporting is where that principle either holds or dies, because reporting is where numbers leave their records and start being quoted in meetings.

## 1. Architecture in one paragraph

Analytical queries never touch OLTP tables. Projection workers maintain a denormalized read model from domain events; immutable snapshot tables (`PIPELINE_SNAPSHOT`, `FORECAST_SNAPSHOT` and its lines — [data model §4.9](09-data-model.md)) are written on schedule; report queries run against the read model with per-tenant concurrency limits, timeouts and row caps. The projection is eventually consistent and **its staleness is displayed to the user** — a report thirty seconds behind is fine; a report *silently* thirty seconds behind generates a support ticket and erodes trust in every number the product shows. Drill-through returns to the authoritative record with a fresh permission check (§5). Full reasoning and honestly-stated costs — projection drift, doubled storage, lag as an SLI — are in [ADR-008](../architecture/adr/ADR-008-reporting-read-model.md).

## 2. Report and dashboard catalogue by persona

Standard content ships with the product, built from governed KPIs (§3), owned per persona ([product scope](01-product-scope.md)). Every item is a normal report or dashboard — inspectable, cloneable and editable in the builder (§4), not a black-box screen.

| Persona | Standard dashboards | Core reports |
|---|---|---|
| `AE` | My book: open pipeline by stage, tasks due, deals at risk | My open opportunities; activity by account; my quota attainment; slipped deals with reasons (`FR-OPP-010`) |
| `SDR` | My queue: leads by status and age, speed-to-lead SLA position | Leads by source; disqualification reasons; cadence engagement outcomes |
| `MGR` | Team pipeline and forecast: roll-up vs. submitted, coverage, movement waterfall since last week (`FR-FCT-006`) | Stage conversion by rep; slippage by rep; win/loss by reason and competitor (`FR-FCT-010`); forecast accuracy by rep (`FR-FCT-011`) |
| `CRO` | Committed vs. best case vs. coverage by segment; week-over-week forecast movement; attainment by region | Velocity trends by segment; win rate by segment/product/competitor; ARR bridge (new, expansion, contraction, churn from `FR-CTR-009` reasons) |
| `REVOPS` | Data quality and process health: duplicate rate, stale pipeline, automation failures, integration health | KPI definition audit (§3); quota coverage by territory; stage-duration outliers; forecast bias league table |
| `MKT` | Campaign performance: sourced vs. influenced pipeline, ROI by campaign and type (`FR-CMP-007`) | MQL→SQL conversion and hand-off SLA (`FR-CMP-006`); attribution model comparison side by side (`FR-CMP-005`); segment growth |
| `CSM` | Renewal and health: renewals due by quarter with risk indicators (`FR-CTR-010`), health score movements (`FR-ACC-014`) | Churn and downgrade reasons; case volume and SLA position feeding health (`FR-CAS-010`); whitespace by account |

Dashboards compose from report-backed components with multiple visualization types and filters, and charts embed on record pages (`FR-RPT-004`, `F-239`, `F-249`, `US-E15-02`).

## 3. Governed KPI definitions

**Every standard metric has a single published definition, formula and version, visible wherever the metric appears** (`FR-RPT-009`, `F-247`, `US-E15-07`). Two reports displaying the same named metric compute it identically; a metric with more than one active definition is a defect, not a configuration choice. Where a tenant needs a variant, it is a *new named metric* with its own definition — never a silent redefinition of the standard one. Definitions are versioned; a historical figure remains reproducible under the definition version in force when it was computed.

The published formulas:

| KPI | Formula | Notes |
|---|---|---|
| **Pipeline coverage** | `open pipeline value in period ÷ remaining quota for period` | Open pipeline = opportunities with close date in the period, in configured forecast categories (default: pipeline, best case, commit — `FR-FCT-001`). Remaining quota = quota − closed won credited to the period (`FR-FCT-009`, `FR-FCT-012`) |
| **Sales velocity** | `(# open qualified opportunities × average deal size × win rate) ÷ average sales cycle days` | Currency per day. Each input is itself a governed KPI computed over the same slice; the four inputs are displayed with the result (`FR-FCT-009`) |
| **Stage conversion** | `# opportunities that exited stage n forward ÷ # opportunities that entered stage n` | Cohort basis: computed over opportunities entering the stage in the period, from stage history (`FR-OPP-011`) — not a point-in-time census, which double-counts stalled deals |
| **Win rate** | `# closed won ÷ (# closed won + # closed lost)` | Count basis is the default published basis (`FR-FCT-010`); a value-weighted variant is a separately named metric. Deals closed as disqualified/no-decision are excluded and the exclusion is stated |
| **Average deal size** | `Σ closed won amount ÷ # closed won` | Corporate currency at the stored conversion rate (`FR-MDM-002`); never recomputed at today's rate |
| **ACV** | `annualized contract value of an agreement = total recurring value ÷ term in years` | One-time amounts excluded and reported separately |
| **ARR** | `Σ annualized value of active subscriptions at the measurement date` | A point-in-time stock, not a period flow (`FR-OPP-016`, glossary in the [FRD](03-frd.md)) |
| **TCV** | `total contract value = Σ all contracted value over the full term, recurring + one-time` | |
| **Quota attainment** | `credited closed revenue in period ÷ assigned quota for period` | The credit basis — closed revenue vs. split-credited revenue (`FR-OPP-006`) — is explicitly stated on the figure (`FR-FCT-012`) |
| **Forecast accuracy** | `1 − (|actual − submitted| ÷ actual)` per user per period | Computed against the locked submission snapshot (`FR-FCT-004`), never against a retroactively edited number (`FR-FCT-011`) |
| **Forecast bias** | `mean of ((submitted − actual) ÷ actual)` signed, per user over trailing periods | Positive = habitual over-forecasting. Accuracy without bias hides direction; both are published together |
| **Slippage rate** | `# opportunities whose close date moved out of the period ÷ # opportunities forecast in the period at its opening snapshot` | Denominator anchored to the opening snapshot (`F-186`); close-date moves are recorded events (`FR-OPP-010`). A value-weighted variant is separately named |
| **MQL→SQL conversion** | `# MQLs accepted by sales ÷ # MQLs handed off`, cohorted by hand-off date | Acceptance and rejection with reason per `FR-CMP-006`; rejections reported alongside, not hidden in the denominator |
| **Campaign ROI** | `(attributed closed revenue − actual campaign cost) ÷ actual campaign cost` | The attribution model and sourcing definition (sourced vs. influenced) are stated on every figure (`FR-CMP-005`, `FR-CMP-007`). ROI without its model named is not a valid output |
| **CAC payback** | `customer acquisition cost ÷ (average ARR per new customer × gross margin %)`, in months | **Honest data dependency:** CAC and gross margin require sales and marketing cost and cost-of-service data from the finance system. Axiom holds campaign cost (`FR-CMP-001`) but not payroll, tooling or COGS. Without configured finance inputs (via the ERP connector or manual entry) this KPI displays as *not computable with its missing inputs named* — it does not display a number built from partial cost data, which would be confidently wrong in the direction that flatters |

Every KPI figure is drillable to its contributing records (§5) and links to its definition and version. This is `F-247`'s competitive point: neither competitor ships governed, published, versioned metric definitions — which is why every large Salesforce or Zoho deployment ends up with three win rates and a standing argument about which is real.

## 4. Report builder

Users build their own reports — filters, groupings, summaries, sorts — in tabular, summary, matrix and joined formats, **without administrator assistance** (`FR-RPT-001`, `F-235`, `F-236`, `US-E15-01`). Administrators define cross-object report types with "with" and "without" related-record semantics (`FR-RPT-002`, `F-237`) — "accounts *without* activity this quarter" is a first-class query, not an export-and-VLOOKUP exercise. Custom summary formulas, row-level formulas and bucketing are supported (`FR-RPT-003`, `F-238`), using the platform expression language (`FR-AUT-009`).

Custom objects and fields are reportable the moment they exist (`FR-ADM-001`), and any story adding a reportable field updates the projection in the same story ([epics and stories](05-epics-and-stories.md), DoD point 8 — the one teams skip, and the only reliable defence against projection drift).

Natural-language report generation (`F-257`) emits a builder-native report definition — inspectable and editable like any hand-built report, with the interpretation displayed per [AI capabilities](11-ai-capabilities.md) §2.4. With AI off, the builder is unaffected (`FR-AIX-013`).

### 4.1 Analytics Studio implementation contract

The runnable Analytics Studio at `/reports` is one product surface over the governed engine, not a second reporting implementation:

- The route starts with two explicit workspaces. **Reports Studio** pairs a searchable Jasper report library in a left-side panel with the selected report's full grid and document viewer on the right; **Custom Reports** contains the no-code builder, dashboards, formulas, collaboration and delivery configuration. A user browsing an approved report never has to pass through authoring controls.
- Selecting a standard report resolves one tenant-scoped query contract. **Report Grid** renders its semantic rows for accessible operational review; **Document Preview** independently renders the same contract through Jasper as an authenticated inline PDF. The PDF endpoint is a read use case rather than an export, so opening the viewer does not create false export evidence or require export entitlement. PDF, Excel and Word remain adjacent authoritative download actions.
- Report Grid requests are server-paged at 100 rows by default and accept global search plus independent filters for every visible report column. Search is evaluated across dimension, value, detail and signal; column filters are combined. PDF preview and PDF/Excel/Word downloads use the same complete filtered result rather than only the current page.
- The standard portfolio is a searchable, collection-filtered side library so users can change reports without leaving the preview. The selected report workspace has two accessible tabs: **Report Grid** presents the complete tenant-scoped rows, while **Document Preview** renders the authenticated Jasper PDF through the bundled PDF.js display layer with paging, zoom and open-document controls. It therefore remains visible in browsers and Electron surfaces that disable native PDF plug-ins. Refresh, scheduling and full-view remain shared by both tabs.

- Field chips can be dragged into detail, row-group, measure and pivot-column zones; click-to-add remains available for keyboard and touch users.
- Tabular, summary and matrix/pivot definitions are stored as structured JSON and revalidated on every save and execution.
- Calculated measures use the closed Axiom expression grammar. SQL, scripts, assignment, loops and class/member access are not accepted.
- Conditional formatting is metadata over named output fields. Operators and six-digit colours are validated by the API before they are saved.
- Cross-module joins are expressed through administrator-approved related-record semantics (`WITH` / `WITHOUT`), never user-authored join SQL.
- Dashboard widgets reference saved report definitions and use a validated twelve-column layout. Available visualizations are KPI, bar, line, area, donut, funnel, table, pivot and summary.
- Shares and comments are tenant-scoped. Every viewer still receives a result computed under their own record and field access.
- Embedded views are authenticated-only and allow exact-origin allow-lists. An anonymous embed is rejected by the database constraint.
- Delivery policies can be periodic or governed-KPI threshold driven. Artifact generation is first-party; the external mail adapter remains `PENDING_ADAPTER` until a configured vendor transport reports delivery.
- `/api/v1/analytics/studio/performance` exposes the rolling 30-day execution count, timeouts, truncations, P95 and maximum server time. `scripts/validate-reporting-scale.ps1` is the repeatable release gate and refuses to call a demo-sized dataset a scale test unless explicitly run as a smoke check.

### 4.2 Governed Jasper report portfolio

The standard catalogue is a decision portfolio, not a collection of database dumps. Every report declares the business question it answers, its recommended audience, its collection and its supported document formats. All twenty-one reports render through the same governed Jasper layout and are available as PDF, Excel and Word.

| Collection | Report | Decision supported | Recommended audience |
|---|---|---|---|
| Executive | Revenue Command Summary | What is the commercial position of this tenant now? | CRO, revenue operations, tenant administrator |
| Sales | Pipeline by Stage | Where is pipeline concentrated and what value is exposed? | Sales manager, account executive |
| Sales | Forecast Commitment | What is committed, likely and at risk for the forecast period? | CRO, sales manager, finance |
| Sales | Pipeline Aging and Risk | Which opportunities are stalled or overdue for intervention? | Sales manager, account executive, revenue operations |
| Sales | Win/Loss Analysis | Why are deals won or lost and where should coaching focus? | Sales manager, revenue operations |
| Growth | Lead Conversion Funnel | Where are leads accumulating or falling out of the funnel? | Marketing, SDR manager, revenue operations |
| Growth | Lead Source Conversion | Which acquisition sources create qualified demand? | Marketing, SDR manager |
| Sales | Sales Activity Productivity | Which activity patterns support active pipeline? | Sales manager, account executive |
| Customer | Account Health Portfolio | Which accounts need retention or expansion attention? | Customer success, account executive |
| Customer | Customer Service SLA | Where are case volume and service risk concentrated? | Service manager, customer success |
| Commercial | Quote Conversion and Margin | Which quote outcomes and discount levels threaten conversion or margin? | CPQ manager, finance, sales manager |
| Growth | Campaign ROI | Which campaigns are converting spend into pipeline and revenue? | Marketing, finance |
| Governance | Data Quality Exceptions | Which missing or invalid fields make CRM decisions unreliable? | Data steward, revenue operations, auditor |
| Sales | Quota Attainment by Representative and Territory | Who is ahead of quota and where is the governed target gap? | CRO, sales manager, finance, revenue operations |
| Sales | Forecast Accuracy and Directional Bias | Are submissions accurate, systematically optimistic or systematically conservative? | CRO, sales manager, finance, revenue operations |
| Sales | Stage Conversion and Sales Velocity | Which stage cohorts convert, stall or consume the most time? | CRO, sales manager, revenue operations |
| Customer | Renewal, Churn and ARR Movement Bridge | What changed recurring revenue and what is due for renewal? | CRO, finance, account manager, service manager |
| Sales | Pipeline Movement Waterfall | Which named movements reconcile opening pipeline to closing pipeline? | CRO, sales manager, revenue operations, finance |
| Customer | Account Whitespace and Cross-Sell Opportunity | Which active catalogue products are absent from each account relationship? | Account manager, seller, sales manager |
| Executive | Customer 360 Executive Brief | What is each customer complete commercial, health, renewal and service posture? | CEO, CRO, account manager, service manager |
| Governance | Discount Leakage and Approval Governance | Where do discount or margin exceptions need approval review? | Finance, sales manager, operations, auditor |

The catalogue API rejects an enabled definition whose query implementation is unknown. This fail-closed rule prevents a newly seeded report from silently returning another report's data. Export runs are recorded with the report definition, format, generated row count and user identity.

### 4.3 Report delivery status and discovery roadmap

New reports are selected by business decision, data readiness and repeatability. A mock-up is not considered shipped until its authoritative inputs, calculation grain, tenant isolation, RBAC behaviour, empty-data state and all three export formats are verified.

**Delivered from first-party governed data:**

1. **Quota attainment by representative and territory** — uses the current versioned quota and closed-won revenue in its fiscal period. Team quotas explicitly name the missing team-credit mapping rather than borrowing another scope.
2. **Forecast accuracy and directional bias** — compares each submitted owner-period forecast with closed-won actuals in that exact period.
3. **Stage conversion and sales velocity** — uses append-only stage occupancy cohorts, forward exits and elapsed stage time.
4. **Renewal, churn and ARR movement bridge** — annualizes governed subscriptions and separates opening, new, churned, renewal-due and closing ARR.
5. **Pipeline movement waterfall** — reconstructs opening and closing state from append-only history so its value buckets reconcile.
6. **Account whitespace and cross-sell opportunity** — subtracts active subscriptions and open opportunity lines from the active product catalogue.
7. **Customer 360 executive brief** — combines health, ARR, pipeline, contacts, service demand and renewal timing at account grain.
8. **Discount leakage and approval governance** — compares quote and line discounts with margin and approval state.

**Following priority — valuable after data-quality gates pass:**

- Marketing attribution-model comparison.
- Duplicate, stale-data and workflow-gate exception trends.

**Connector-dependent ideas — deliberately not presented as complete:**

- CAC, gross margin and payback need authoritative finance cost inputs.
- Product-usage-to-renewal correlation needs product telemetry.
- Email and call sentiment needs an approved communications/transcription adapter and AI governance.

Each roadmap candidate must show missing inputs rather than fabricating a partial metric. That is the practical meaning of the product principle, "explain every number."

## 5. Access-aware results and drill-through

Two rules, both enforced server-side:

1. **Results reflect the viewing user's record and field access by default** (`FR-RPT-005`, `F-240`, `US-E15-03`). Two users opening the same dashboard see figures computed over their own permitted data. A dashboard may run as a specified user only where explicitly configured, **and that fact is displayed to every viewer** — an elevated view that looks like a personal view is how aggregate numbers leak record existence. Where access restricts a roll-up, the restriction is indicated rather than silently under-reported (`FR-ACC-004` sets the same rule for hierarchy roll-ups).
2. **Every aggregate is drillable to its contributing records — with a fresh permission check at drill time** (`FR-RPT-006`, `F-244`, `US-E15-04`; [ADR-008](../architecture/adr/ADR-008-reporting-read-model.md) decision 4). The projection aggregates; it is never the authority on what a user may see. Access changes faster than a projection updates, so materialized permissions in the read model would eventually show someone a record they were removed from an hour ago. Re-checking against the authoritative store costs latency on one page of results and is the only version that is correct. The drill-through re-check is a mandatory `SEC-` case in [acceptance tests](06-acceptance-tests.md).

## 6. Scheduled delivery and threshold alerts

Reports and dashboards are schedulable for delivery to permitted recipients, and subscribable with threshold conditions that notify **only when a metric crosses a bound** (`FR-RPT-007`, `F-241`, `F-242`, `US-E15-05`) — "coverage below 3× for any segment", not a Monday PDF nobody opens. Two governance rules:

- Delivery is permission-checked **per recipient at send time**. A recipient sees the report as their own access permits; scheduling is not a privilege-escalation path, and a recipient who has lost access receives nothing rather than a stale entitlement.
- Threshold evaluation runs against governed KPI definitions (§3), so an alert and the dashboard it points to can never disagree about the number.

## 7. Historical trending from snapshots

Designated objects are snapshot on a schedule so trend reports run over historical states **without reconstructing history from audit data** (`FR-RPT-008`, `F-243`, `US-E15-06`). Snapshots are immutable ([ADR-008](../architecture/adr/ADR-008-reporting-read-model.md)); reconstruction from an audit log is slow, fragile and produces numbers that do not quite tie out — which in a forecast review is worse than no number at all.

The same machinery powers the point-in-time comparisons that matter most: pipeline as of two dates (`FR-OPP-015`, `F-186`), the forecast movement waterfall whose components — new, advanced, slipped, pulled in, increased, decreased, won, lost — **reconcile exactly to the net change**, with any residual shown explicitly rather than absorbed (`FR-FCT-006`), and forecast accuracy against locked submissions (`FR-FCT-011`, §3). Snapshot retention is a configured policy with stated trade-offs — snapshots become the largest data in the system without one ([ADR-008](../architecture/adr/ADR-008-reporting-read-model.md), consequences).

## 8. Export governance

Export is a right, not a loophole (`FR-RPT-010`, `F-245`, `F-246`, `US-E15-08`):

- The right to export or print is a **permission distinct from the right to read**, independently grantable and limitable by volume (`FR-SEC-015`).
- Exports above a configurable row threshold require approval; bulk export is a step-up-authentication action (`FR-TEN-009`).
- **Every export is audited**: who, what object, what filter criteria, how many rows, to what destination (`FR-AUD-005`, `EXPORT_AUDIT` in [data model §7](09-data-model.md)).
- Exported results carry the same field-level security as the screen — a field the user cannot read is absent from the file, and masked sensitive fields (`FR-SEC-008`) export masked.

The posture is deliberate and two-sided: governed export for users protects the tenant against exfiltration; the *tenant's own* complete export needs no vendor ticket in any tier (`FR-AUD-013`, `FR-GLOBAL-011`). Making leaving easy and making leaking hard are different controls, and conflating them is how competitors justify tier-gating interoperability.

## 9. Query guardrails

Long-running or excessively broad reports are constrained by timeout and result limits, with a clear message and guidance on narrowing — and cannot degrade service for other tenants (`FR-RPT-011`, `F-248`). Per-tenant concurrency limits and statement timeouts apply at the read model ([ADR-008](../architecture/adr/ADR-008-reporting-read-model.md)). The design intent is honest asymmetry: the person who authored an expensive report experiences a bounded slow report; everyone else experiences nothing at all.

## 10. Requirements coverage

| Requirement | Where specified |
|---|---|
| `FR-RPT-001` … `FR-RPT-003` report builder | §4 |
| `FR-RPT-004` dashboards | §2, §4 |
| `FR-RPT-005` access-aware results | §5 |
| `FR-RPT-006` drill-through | §5 |
| `FR-RPT-007` delivery and alerting | §6 |
| `FR-RPT-008` historical trending | §7 |
| `FR-RPT-009` governed KPIs | §3 |
| `FR-RPT-010` export governance | §8 |
| `FR-RPT-011` query guardrails | §9 |
| `FR-FCT-009` … `FR-FCT-012` analytics | §2, §3, §7 |

## Related documents

- [Product scope](01-product-scope.md) — "explain every number" and the persona definitions behind §2
- [FRD](03-frd.md) §15, §20 — the `FCT` and `RPT` requirements this document expands
- [Feature catalogue](04-feature-catalogue.md) E10, E15 — competitive positioning per feature
- [Epics and user stories](05-epics-and-stories.md) E10, E15 — delivery decomposition and Definition of Done point 8
- [Data model](09-data-model.md) §4.9, §7 — snapshot and export-audit entities
- [ADR-008 — reporting read model](../architecture/adr/ADR-008-reporting-read-model.md) — the architecture and its honestly-stated costs
- [ADR-003 — event backbone](../architecture/adr/ADR-003-event-backbone.md) — the events the projections consume
- [AI capabilities](11-ai-capabilities.md) — natural-language report generation and prediction surfaces
- [RBAC and sharing model](08-rbac-and-sharing-model.md) — the permission model reporting enforces

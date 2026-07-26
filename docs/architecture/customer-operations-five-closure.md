# E11-E15 customer-operations closure increment

This increment closes one deployable, first-party acceptance boundary in each of E11-E15. It does not claim that every target-product story in the broader epic catalogue is finished; vendor transports and later portal/builder experiences remain governed backlog rather than simulated success.

## Implemented controls

| Epic | Acceptance boundary | Server invariant | Operator experience |
|---|---|---|---|
| E11 Campaigns | Explainable performance capture | Performance snapshots are append-only; ROI is `(influenced pipeline - budget) / budget`, with zero budget reported as unavailable | **Capture performance** records members, responses, MQLs, SQLs, pipeline and ROI |
| E12 Cases | Idempotent SLA breach escalation | One escalation per tenant, milestone and level; overdue open milestones become `MISSED` and unresolved cases become `ESCALATED` | **Check SLA** reports whether anything is overdue and creates only missing escalations |
| E13 Partners | Safe partner deal registration | Tenant-consistent opportunity/partner lookup, request idempotency, explicit conflict check and protection only when clear | **Register deal** accepts an open opportunity and reports protected vs held-for-review |
| E14 Automation | Canonical versioned engine convergence | The workspace now reads `automation.rule_definition`, `rule_version` and `rule_execution`; restore copies an old definition forward instead of rewinding history | **Simulate** is the canonical read-only dry run; **Restore version** preserves provenance |
| E15 Reporting | Scheduled first-party report generation | Tenant-scoped subscription and immutable run evidence; only administrative roles may schedule or run due reports | Report cards can be scheduled and the due sweep generates Jasper PDF/Excel/Word attachments internally |

## Architecture decisions

- All new mutable commands derive tenant and actor identity from the verified request context; no tenant identifier is accepted from the browser.
- Campaign snapshots and subscription runs are append-only evidence.
- Case and deal commands use database uniqueness as the final retry boundary, not an in-memory check.
- Report generation is separated from delivery. The scheduler can truthfully say `GENERATED`; external email delivery cannot be reported successful until an approved adapter exists.
- The legacy `automation.automation_rule` remains for migration compatibility, but the product workspace no longer reads or executes it. New product work must use the canonical versioned engine.

## Verification contract

- Flyway migration `V327__customer_operations_five_epic_controls.sql` applies with forced tenant RLS and module-catalog registration.
- Backend regression suite, frontend TypeScript compilation and Vite production build must pass.
- Live proof covers authenticated reads of all five workspaces and one non-destructive command in each bounded context.

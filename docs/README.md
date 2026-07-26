# Axiom documentation map

Read the documentation in this order:

1. [`product/01-product-scope.md`](product/01-product-scope.md) - vision, personas, scope, and non-goals.
2. [`product/03-frd.md`](product/03-frd.md) - functional contract and traceability.
3. [`architecture/system-design.md`](architecture/system-design.md) and [`architecture/adr/`](architecture/adr/) - system forces and decisions.
4. [`architecture/database-module-schemas.md`](architecture/database-module-schemas.md) - physical PostgreSQL schemas, table ownership, and tenant-consistent FK rules.
5. [`design/ux-foundation.md`](design/ux-foundation.md) - implemented interaction and visual foundation.
6. [`epic-status.md`](epic-status.md) - honest current implementation status.
7. [`manual/user-guide.md`](manual/user-guide.md) - operator guidance, with current-preview boundaries called out.
8. [`../qa/qa-master-test-plan.md`](../qa/qa-master-test-plan.md) and [`product/07-uat-plan.md`](product/07-uat-plan.md) - release evidence and business acceptance gates.
9. [`product/24-epic-closure-and-dependency-register.md`](product/24-epic-closure-and-dependency-register.md) - first-party closure queue, governed masters and explicit pending-vendor boundaries.

Product definitions describe the target product. The epic status and preview boundary in the user guide describe what the repository runs today. When those conflict, do not infer that a specified capability has shipped.

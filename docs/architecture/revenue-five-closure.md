# E06–E10 revenue execution closure increment

This increment closes five first-party acceptance gaps while keeping Microsoft, Google, telephony, e-signature and ERP vendor execution outside the local runtime boundary.

## E06 — opportunity stage preflight

Pipeline drag, keyboard selection and direct API movement now share `OpportunityLifecycleService`. Every move first evaluates the exact pinned exit-criteria version and the target entry criteria. Missing criteria return the observation and corrective action before mutation. Backward and skipped-stage transitions request a reason and the command re-evaluates the gate transactionally, so preflight is guidance rather than an authorization bypass.

## E07 — immutable email-template versions

The engagement workspace exposes permission-filtered templates. Creating a template writes version 1; editing appends a version and advances `current_version` in one transaction. Historical versions remain protected by the existing database mutation trigger. Private, tenant and role sharing affect visibility, while owner, administrator and explicit `can_edit` grants govern revisioning.

## E08 — quote revisioning

Only the active, non-ordered quote can create a revision. The transaction retires the current version, creates the next draft in the same quote group, and copies every quote line, bundle-parent relationship and relational price-adjustment ledger. The prior quote remains byte-for-byte available. PostgreSQL continues to enforce exactly one active version and now also enforces the reverse `superseded_by` relationship.

## E09 — idempotent renewal preparation

An active, expiring or expired contract can create one renewal plan and one draft successor. The successor begins the day after expiry, preserves the original term length and value, and copies live subscriptions as `PENDING_RENEWAL`. A tenant-qualified unique constraint makes retries return the existing draft instead of producing duplicate renewals. The source contract is never mutated.

## E10 — explainable forecast scenarios

Managers can save amount, confidence and risk assumptions against a forecast submission. A scenario stores baseline amount, scenario amount, risk-adjusted outcome and the individual factor explanations. Database triggers make the evidence append-only. Scenario creation never changes the governed forecast submission or its snapshots.

## Cross-cutting controls

- Every new table is tenant-scoped, RLS-protected and registered in the governance catalogue.
- Read-only audit roles can inspect but cannot run commercial mutations.
- Material commands write audit evidence; quote and contract commands also emit transactional outbox events.
- Responsive UI controls use the existing equal-size button, drawer and data-workspace design system.

# E01–E05 closure increment

This increment closes five first-party acceptance gaps without claiming external identity-provider, email or vendor integrations.

## E01 — proactive SAML certificate readiness

`IdpCertificateAlertService` checks every enabled SAML configuration once per day. A certificate inside the 30-day warning window produces an urgent in-app notification for every active tenant administrator. The durable key `(tenant, provider, certificate expiry, severity)` makes the job idempotent, so restarts or manual checks do not create duplicate warnings. Invalid certificate text remains the responsibility of **Test connection** and never receives a fabricated expiry date.

## E02 — access recertification

An access-review campaign snapshots live permission bundles, role assignments, manual record shares and delegated-administrator scopes. Review decisions are immutable. A **Revoke** decision changes the authoritative grant in the same database transaction as the review item, so the review cannot report removal while access is still effective. Reviewers cannot certify their own access, and auditors retain read-only visibility.

## E03 — effective-dated reference resolution

Every reference entry create/update appends an immutable version row. The resolver accepts value-set API name, stored code and business date, then returns the label whose effective window covers that date. Inactive values remain resolvable for historical screens and reports but are explicitly marked unavailable for new records.

## E04 — explainable account health and hierarchy roll-up

Account health is a weighted calculation over engagement recency, open cases, SLA breaches, renewal proximity and product adoption. Each snapshot stores the observed value, factor score, direction, configured weight and a plain-language action. Material factor changes are named in the response and audit evidence. Account 360 also exposes account-only and permission-filtered hierarchy pipeline/revenue/activity roll-ups; restricted results state that access filtering was applied without disclosing hidden counts.

## E05 — partial-success lead ingestion

Single and bulk lead capture share the same validation, duplicate policy, scoring, predictive factors, routing and response-SLA services. A bulk request accepts up to 1,000 rows. Each row runs in an independent transaction and receives `CREATED`, `MERGED`, `ATTACHED`, `REVIEW` or `REJECTED`; one invalid row cannot roll back valid rows. The batch and each original payload remain available as audit evidence.

## Runtime boundaries

- Live SAML/OIDC handshakes remain an external-provider integration and are not claimed by this increment.
- In-app certificate alerts are complete; email/push delivery remains under the separately governed alert-provider boundary.
- All new tables are tenant-scoped with PostgreSQL RLS and are registered in the governance catalogue.

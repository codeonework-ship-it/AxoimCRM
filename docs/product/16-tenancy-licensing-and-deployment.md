# Tenancy, licensing and deployment

How Axiom is hosted, packaged and sold. This document covers the multi-tenancy model at a product level (the architectural decision lives in [ADR-001](../architecture/adr/ADR-001-tenancy-isolation.md)), the tenant lifecycle, the three deployment models, the entitlement mechanism, and the commercial packaging — including the list of things Axiom commits to never gating by tier.

> Tier names below are working names, consistent with the Axiom codename convention in [product scope](01-product-scope.md). Confirm before external use. The *structure* — what is in every tier and what differentiates tiers — is a product commitment, not a placeholder.

---

## 1. Multi-tenancy model

**Shared schema, `tenant_id` on every row, enforced independently at the application layer and by database row-level security.** No client-supplied tenant identifier is ever trusted, and foreign keys never cross tenants. The full decision, its rationale and its honestly stated costs — logical rather than physical isolation, RLS query-planning overhead, noisy-neighbour exposure, and the tenant-scoped restore gap — are in [ADR-001](../architecture/adr/ADR-001-tenancy-isolation.md). This document does not restate them; it builds the commercial model on top of them.

The one consequence that matters commercially: **deployment model is infrastructure, never domain logic.** `TENANT.deployment_model` ([data model](09-data-model.md) §3.1) exists for operational reporting only. That single rule is what lets three deployment models and three price tiers run one codebase, one test matrix and one release train.

## 2. Tenant lifecycle

A tenant moves through five states per `FR-TEN-002`, provisioned per `FR-TEN-001`:

| State | Entered by | What is permitted | What is blocked |
|---|---|---|---|
| `provisioning` | Signed order / sovereign install | Platform setup only | All user access |
| `active` | Successful provisioning | Everything the entitlement set allows | — |
| `suspended` | Non-payment, breach, customer request | **Administrator login and complete data export** (`FR-AUD-013`) | All business writes, all non-admin logins |
| `terminating` | Explicit confirmation + step-up auth (`FR-TEN-009`) | Data export until the retention period ends | New sessions, all writes |
| `terminated` | Retention period expiry | Nothing — data is destroyed | Everything |

Permitted transitions are closed-set: `provisioning → active` (or full rollback), `active ↔ suspended`, `active → terminating`, `suspended → terminating`, `terminating → terminated`. There is no path out of `terminating` except completion or an explicit operator abort back to `suspended` while the retention period is still running — and none at all out of `terminated`.

Rules that are commitments, not implementation details:

- **Provisioning is idempotent and atomic** (`FR-TEN-001`). A tenant comes into existence with an isolated data scope, an initial administrator, a default configuration baseline and an entitlement set — or not at all. Partial provisioning rolls back completely.
- **Suspension is not hostage-taking.** A suspended tenant's administrator can still log in and run a complete export. A customer in a billing dispute retains access to their own data; principle 9 of the [product scope](01-product-scope.md) ("the customer's data is the customer's") applies with the most force exactly when the relationship is strained.
- **Termination is deliberate and slow by design.** It requires explicit confirmation, step-up authentication, and a retention period before destruction — because "we deleted the wrong tenant" has no recovery path once the retention window closes.
- The tenant identifier is immutable for the tenant's lifetime.

## 3. Deployment models

Three models, one artifact, defined in [ADR-001](../architecture/adr/ADR-001-tenancy-isolation.md) and carried through [system design](../architecture/system-design.md) driver D4:

| | Pooled SaaS | Dedicated | Sovereign |
|---|---|---|---|
| **Infrastructure** | Shared with other tenants | Isolated stack, operated by us | Customer's own premises or cloud |
| **Isolation** | Logical (application scoping + RLS) — *not* physical | Physical infrastructure isolation | Physical, plus customer control of the perimeter |
| **Who operates it** | Axiom | Axiom | **The customer**, using tooling we provide |
| **Availability commitment** | 99.9% monthly | 99.95% monthly, contracted ([NFR](10-nfr-and-enterprise-readiness.md) §3) | Customer's own target — we cannot commit to infrastructure we do not run |
| **Upgrade cadence** | Continuous, platform-scheduled | Scheduled with the customer | Customer-initiated from our release artifacts |
| **AI provider** | Hosted, zero-retention terms ([ADR-004](../architecture/adr/ADR-004-ai-provider-abstraction.md)) | Hosted or self-hosted | Self-hosted or **AI-off** (`FR-AIX-013`) |
| **Noisy-neighbour exposure** | Mitigated by quotas and bulkheads, not eliminated | None | None |
| **Ops burden on customer** | None | None | **All of it** — backup verification, DR execution, capacity ([FRD](03-frd.md) assumption 3) |
| **Price implication** | Baseline — pooled economics fund the price point | Materially higher — a dedicated stack has a real cost and the price says so | Licence plus the customer's own infrastructure and operations cost |

Honest framing for the sales conversation: **pooled isolation is logical, not physical.** Buyers who require physical separation get the dedicated or sovereign model at a higher price — we do not claim shared-schema pooling gives them physical isolation, because it does not.

### Sovereign is a tenant, not a fork

A sovereign install is **one tenant in the standard schema, running the standard release artifact**. There is no sovereign build, no feature fork, no separate release train, and no `if (sovereign)` anywhere in domain logic. Sovereign customers get feature parity by construction — including the full reporting read model ([system design](../architecture/system-design.md), open question Q6: *sovereign is not a lesser product*). The moment this stops being true, the differentiator in [competitive analysis](02-competitive-analysis-salesforce-zoho.md) §5.2 quietly dies; [ADR-001](../architecture/adr/ADR-001-tenancy-isolation.md) explains why the discipline holds.

## 4. Entitlements and feature flags

`FR-ADM-013` defines the mechanism; `TENANT.entitlements` ([data model](09-data-model.md) §3.1) holds it: a structured set of feature flags and volume limits attached to the tenant.

- **Platform operators** configure per-tenant entitlements — tier, packs installed, volume allowances, advanced-AI enablement.
- **Tenant administrators** can see exactly which capabilities their subscription includes. What a customer bought is never a support ticket to find out.
- Entitlement checks happen at the feature entry point. They gate *access to a capability*; they never branch *domain behaviour* — the same discipline as `deployment_model`.
- Vertical packs ([BFSI and commodity trading](17-vertical-pack-commodity-trading.md)) are entitlements: installable, versionable and removable as a unit per `FR-BFS-013`.

Illustrative entitlement shape (keys are indicative; the mechanism, not the key list, is the requirement):

| Entitlement class | Example keys | May it gate? |
|---|---|---|
| Tier and term | `tier`, `contract_end` | Yes — it *is* the subscription |
| Volume allowances | `storage_gb`, `bulk_job_concurrency`, `ai_compute_units` | Yes, with visible usage telemetry (`FR-AUD-015`) |
| Advanced AI | `agentic_execution`, `custom_model_endpoint` | Yes |
| Vertical packs | `pack.bfsi`, `pack.commodity_trading` | Yes |
| Support level | `support_tier` | Yes |
| Security & interop | SSO, MFA, audit, field-level security, encryption, full API, sandbox, export | **Never** — these keys must not exist (`FR-GLOBAL-011`) |

What entitlements may express is deliberately constrained: volume, advanced capability, packs, deployment and support — never the security and interoperability baseline. The last row is enforced by the FRD itself: no requirement may be satisfied by a tier-gated implementation of those capabilities.

## 5. Packaging: three tiers

Pricing scales on **volume, advanced AI, deployment model and support level**. It never scales on withholding security or interoperability — that is `FR-GLOBAL-011`, a P0 requirement, and [FRD](03-frd.md) constraint 2 states plainly that it constrains the commercial model, not just the implementation.

| | **Foundation** | **Scale** | **Sovereign** |
|---|---|---|---|
| Intended buyer | Mid-market revenue org on pooled SaaS | Large or high-volume org | Regulated buyer needing dedicated or customer-controlled deployment |
| Deployment | Pooled SaaS | Pooled SaaS | Dedicated or sovereign install |
| Included volume (storage, bulk-job and AI allowances) | Baseline allowance | Expanded allowance | Sized per contract |
| Baseline AI (summaries, scoring with explanations, drafting, next-best-action) | ✅ | ✅ | ✅ (self-hosted or AI-off available) |
| Advanced AI (agentic execution, conversational analytics, custom model connections per [ADR-004](../architecture/adr/ADR-004-ai-provider-abstraction.md)) | Add-on | ✅ | ✅ |
| Availability commitment | 99.9% | 99.9% | 99.95% contracted (dedicated); n/a (sovereign — customer-operated) |
| Support | Business-hours, published SLAs | Priority, extended hours | 24×7 with named technical contact |
| Vertical packs (BFSI, commodity trading) | Add-on | Add-on | Add-on |

Three packaging rules that keep the pricing honest:

1. **Every tier is fully usable.** Foundation is not a demo tier; it carries the complete security, API and administration surface. The upgrade motive is volume and advanced capability, never escaping an artificial ceiling.
2. **Published list prices, and claims that survive discounting.** [Competitive analysis](02-competitive-analysis-salesforce-zoho.md) §2.6 notes competitor street prices run 30–50% below list; our value comparison must hold against *discounted* competitor pricing, so it is built on what we include, not on list-price arithmetic.
3. **No mandatory professional services.** Per `FR-ADM-014`, every routine administrative task is completable by a trained tenant administrator. A price that requires a consultant to unlock the product is a hidden tier.

### Licensing mechanics

- **Per seat, per month, billed annually or monthly.** Monthly carries a premium, stated on the price list rather than discovered at renewal.
- **License type is a user attribute** (`USER.license_type`, [data model](09-data-model.md) §3.2), not a separate product. A full seat and a read-mostly seat differ in what the user can do, never in what the platform protects — field-level security, audit and MFA apply identically to every license type.
- **Vertical packs price per enabled tenant**, not per user, because a pack changes what the tenant's data model can express rather than what one person can see.
- **Deactivating a user frees the seat.** Deprovisioned users are deactivated, never hard-deleted (`FR-TEN-007`), so audit history and record ownership survive seat churn without ghost-seat billing.

## 6. What we never gate

The competitor gates documented in [competitive analysis](02-competitive-analysis-salesforce-zoho.md) §2.7 and §3.6 are the wedge. Per `FR-GLOBAL-011`, every row below is in **every** Axiom tier, including Foundation:

| Capability | Salesforce today | Zoho today | Axiom |
|---|---|---|---|
| SSO (SAML 2.0 / OIDC) | Upper tier / add-on | Upper tier | Every tier (`F-003`, `F-004`) |
| MFA enforcement policy | Available, policy depth varies | Limited below Enterprise | Every tier (`F-008`, `F-009`) |
| Audit trail / field audit history | Field Audit Trail is a Shield add-on | Audit log present, depth limited | Every tier, immutable (`FR-AUD-001`, `FR-AUD-002`) |
| Encryption beyond storage default | Shield Platform Encryption add-on | Included, less granular | Every tier; customer-managed keys for sovereign (`FR-AUD-012`) |
| Field-level security | Enterprise and above | Enterprise and above | Every tier ([RBAC model](08-rbac-and-sharing-model.md)) |
| Full API access | Not in entry tiers | Per-edition call limits | Every tier, no per-tier call caps (`F-273`, `FR-INT-003`) |
| Full-copy sandbox | Unlimited or paid add-on | Developer sandbox, Enterprise+ | Every tier (`F-308`, `FR-ADM-005`) |
| Complete self-service data export | Weekly export gated by edition | Export with limits | Every tier, no ticket (`FR-AUD-013`) |
| Numeric limits on custom objects/fields | Per-edition limits | Hard per-edition limits | No limits by tier (`F-301`) |
| Baseline AI | Enterprise+; substantive AI at $550/user | Enterprise+ | Every tier ([competitive analysis](02-competitive-analysis-salesforce-zoho.md) §5.1) |

Two clarifications so this table stays defensible in an evaluation:

- **"No per-tier API limits" is not "no limits."** Fair-use throttling exists, applied uniformly with published limits and standard rate-limit headers (`FR-INT-003`). The commitment is that the throttle is an engineering control, never a price lever.
- **We do gate things** — volume allowances, advanced AI, dedicated/sovereign deployment and premium support, per §5. The claim is not "everything is free"; it is that security and interoperability are never the ransom.

## 7. Open commercial decisions

Recorded here rather than silently assumed, following the convention of [product scope](01-product-scope.md):

1. **Price points are not set in this document.** The structure — three tiers, the never-gate list, volume/AI/deployment/support as the only differentiators — is the commitment. Numbers require a willingness-to-pay study against the verified competitor pricing in [competitive analysis](02-competitive-analysis-salesforce-zoho.md) §2.6 and §3.5, and those figures are volatile.
2. **Volume allowance sizes** (storage, bulk-job and AI compute per tier) need real usage telemetry to calibrate. Set too low they recreate the tier traps this document exists to abolish; the bias at launch is generous-and-measured, then adjusted with notice.
3. **Sovereign licence verification** — how licence terms are enforced on infrastructure we cannot see — is a commercial-legal design question. What is already decided: it will not be a phone-home kill switch, because a sovereign product that stops working when disconnected from the vendor is not sovereign.
4. **Tier names** are working names, per the note at the top of this document.

## Related documents

- [ADR-001 — Tenancy isolation model](../architecture/adr/ADR-001-tenancy-isolation.md) — the architectural decision this document packages commercially
- [Product scope](01-product-scope.md) — principles 7, 9 and 10, which this document operationalizes
- [Competitive analysis](02-competitive-analysis-salesforce-zoho.md) — the competitor gates behind §6
- [FRD](03-frd.md) — `FR-TEN-001`, `FR-TEN-002`, `FR-ADM-013`, `FR-GLOBAL-011`, `FR-AUD-013`
- [Feature catalogue](04-feature-catalogue.md) — `F-001`, `F-273`, `F-301`, `F-308`, `F-317`
- [Data model](09-data-model.md) — `TENANT` entity, `deployment_model` and `entitlements`
- [NFR and enterprise readiness](10-nfr-and-enterprise-readiness.md) — availability targets and the portability bar
- [System design](../architecture/system-design.md) — driver D4 and the quota/bulkhead controls pooled economics depend on

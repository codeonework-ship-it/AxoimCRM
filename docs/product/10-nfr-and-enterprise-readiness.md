# Non-functional requirements and enterprise readiness

Targets the [system design](../architecture/system-design.md) must satisfy. Where a target is a placeholder pending real measurement, it is marked **[to be validated]** rather than presented as settled.

## 1. Scale targets

Sized against the stated target of **50,000 users**, per the scaling discussion in [system design](../architecture/system-design.md) §4 and [ADR-005](../architecture/adr/ADR-005-technology-selection-deferred.md).

| Dimension | Target | Basis |
|---|---|---|
| Total platform users | 50,000 | Stated target scale |
| Peak concurrent users | 10,000 (20% concurrency) | Typical enterprise B2B active-session ratio **[to be validated against actual tenant usage patterns]** |
| Tenants (pooled) | 1,000+ | [System design](../architecture/system-design.md) §4.4 Stage 3 sharding trigger |
| Largest single tenant | 5,000 seats | Product scope persona range ([product scope](01-product-scope.md)) |
| Records per tenant (accounts + contacts + opportunities) | Up to 10M | Enterprise B2B data volume, comparable to Octane's proven 10M-per-type QA target |
| Sustained event throughput | Hundreds–low thousands/sec | Derived in the Kafka broker decision, [ADR-005](../architecture/adr/ADR-005-technology-selection-deferred.md) |
| Peak burst throughput (migration, bulk import) | Tens of thousands of events/sec | Bulk operations run on dedicated worker pools, isolated from interactive load |
| API requests | 1,000 req/sec sustained platform-wide **[to be validated]** | |
| Storage growth | Multi-TB within 3 years for a large tenant | Activity, audit and snapshot volume dominate — [data model](09-data-model.md) §8 |

## 2. Latency budgets

| Operation class | Target (p95) | Notes |
|---|---|---|
| Record read (single) | < 200 ms | Cache-assisted where possible |
| Record save | < 500 ms | Includes validation, sharing recalculation trigger, audit write |
| List view / search | < 800 ms | Up to 10,000 matching records before pagination |
| Report execution (ad-hoc) | < 3 s | Against the read model, not OLTP — [ADR-008](../architecture/adr/ADR-008-reporting-read-model.md) |
| Dashboard load | < 2 s | Cached component results where the dashboard allows |
| Automation execution (per record) | < 2 s | Excludes external callouts, which are async |
| AI summarization / next-best-action | < 5 s | Includes grounding retrieval under the calling user's permissions ([ADR-004](../architecture/adr/ADR-004-ai-provider-abstraction.md)) |
| Conversational query | < 8 s | User-facing "thinking" state expected above 2s |
| Bulk API job (10,000 records) | < 5 min | Async job, not a held request |
| Webhook delivery | < 10 s from event to first attempt | Retried on failure per [ADR-003](../architecture/adr/ADR-003-event-backbone.md) |

**Every target above assumes the tenant is not the subject of an active noisy-neighbour throttle.** Fair-use limits ([system design](../architecture/system-design.md) §6) take priority over latency targets when the two conflict — a tenant driving pathological load is throttled, not allowed to degrade its neighbours' latency budget.

## 3. Availability

| Aspect | Target |
|---|---|
| Platform availability (pooled) | 99.9% monthly (≈ 43 min/month) |
| Platform availability (dedicated, contracted) | 99.95% monthly (≈ 22 min/month), by agreement |
| Scheduled maintenance | Excluded from availability calculation if announced ≥ 72h in advance, and limited to defined low-traffic windows |
| RTO (recovery time objective) — regional loss | 4 hours |
| RTO — single-AZ loss | Automatic failover, < 60 seconds, no manual intervention |
| RPO (recovery point objective) | 5 minutes (continuous log/WAL archiving) |
| Backup restore rehearsal | Quarterly, on a non-production environment, with results logged — an unrehearsed restore procedure is an assumption, not a capability ([system design](../architecture/system-design.md) §12) |

## 4. Security controls

Enumerated in full in [system design §9](../architecture/system-design.md#9-security-architecture). Summary of the controls with a compliance-facing target:

| Control | Target |
|---|---|
| Encryption in transit | TLS 1.3, enforced end to end including internal hops |
| Encryption at rest | AES-256 equivalent, tenant-scoped keys |
| Customer-managed keys | Available to sovereign and designated enterprise tenants (`FR-AUD-012`) |
| Credential rotation | API tokens and service credentials rotatable without downtime |
| Vulnerability scanning | Continuous dependency scanning; critical findings remediated within 7 days |
| Penetration testing | Annual third-party engagement, plus before any major architecture change |
| Session security | Idle timeout, absolute lifetime, concurrent-session limits, all configurable per tenant |
| MFA | Available in every tier; enforceable by tenant policy (`FR-GLOBAL-011`) |

## 5. Compliance mapping

This is a **mapping of product controls to framework requirements**, not a certification claim. Certification is a separate organizational undertaking (audit engagement, policy documentation, evidence collection) that this document supports but does not constitute.

| Framework | Relevant product controls | Primary FRD references |
|---|---|---|
| **SOC 2 (Security, Availability, Confidentiality)** | RBAC, audit trail, encryption, access reviews, incident response, change management, availability monitoring | `FR-SEC-*`, `FR-AUD-*`, `FR-ADM-006` |
| **ISO 27001** | Access control policy, asset management (tenant data inventory), cryptography, operations security, supplier relationships (external integrations) | `FR-SEC-*`, `FR-AUD-011`, `FR-INT-*` |
| **GDPR (EU)** | Data subject access/rectification/portability/erasure, consent register, lawful basis tracking, data residency, breach notification support | `FR-AUD-008`, `FR-AUD-009`, `FR-ACC-011` |
| **India DPDP Act 2023** | Consent management, data principal rights (access, correction, erasure), data fiduciary obligations, cross-border transfer controls, breach notification | `FR-AUD-008`, `FR-AUD-009`, tenant `data_region` ([data model](09-data-model.md) §3.1) |
| **CCPA/CPRA (California)** | Right to know, right to delete, right to opt out of sale (n/a — no data sale), non-discrimination | `FR-AUD-008` |
| **Sector-specific (BFSI pack)** | KYC/AML evidence, suitability records, communication archiving, complaint handling | `FR-BFS-*` — see [BFSI pack](12-vertical-pack-bfsi.md) |

**Residency.** `TENANT.data_region` commits to a storage region per tenant. Cross-region replication (§3 of this document) must respect that commitment — a sovereign or residency-constrained tenant's data must not silently replicate outside its committed region for disaster recovery, which means DR design for those tenants needs an in-region secondary, not the default cross-region async replica.

## 6. Encryption and key management

| Aspect | Approach |
|---|---|
| Keys at rest | Tenant-scoped, platform-managed by default |
| Customer-managed keys | Supported for sovereign and designated enterprise tenants; withdrawal of the key revokes access — an explicit, tested "kill switch" (`FR-AUD-012`) |
| Key rotation | Scheduled, without requiring data re-encryption downtime |
| Secrets (credentials, tokens) | Managed secret store, never in configuration files, container images or logs |
| Field-level encryption | Available for designated sensitive fields beyond storage-level encryption, for defense in depth on the highest-sensitivity data (e.g. BFSI tax IDs) |

## 7. Accessibility

**WCAG 2.2 Level AA**, platform-wide, per `FR-GLOBAL-008`. This is not scoped to "the main app" with admin screens exempted — administration is disproportionately used by less-abled power users over long sessions, and exempting it would fail exactly the users accessibility exists to serve.

| Requirement | Verification |
|---|---|
| Full keyboard operability, no mouse-only interaction | Automated + manual audit per release |
| Visible focus indication | Automated audit |
| Programmatic labels on all interactive elements | Automated audit (axe-core or equivalent) + screen reader manual pass |
| No reliance on colour alone | Manual design review |
| Sufficient contrast ratio | Automated audit |
| Respect for `prefers-reduced-motion` | Manual review |

## 8. Internationalization and localization

Per `FR-GLOBAL-009`. Scope for the first release, stated explicitly so it is not assumed to be "everything":

| In scope, P0 | In scope, P1 | Explicitly deferred |
|---|---|---|
| UI string localization (no hard-coded text) | Right-to-left layout support | Full transliteration of user-entered names |
| Date, number, currency formatting per locale | Locale-aware sort order | Machine translation of user-generated content (notes, emails) |
| Time zone handling per user | Additional language packs beyond initial set | |

**No user-facing string may be hard-coded** — this is enforced by lint rule, not style guide, because a style guide is not read under delivery pressure and a lint failure blocks the build.

## 9. Observability

| Signal | Requirement |
|---|---|
| Structured logs | JSON, correlation ID on every entry, no credentials or unmasked personal data (`FR-AUD-014`) |
| Metrics | Application (latency, error rate, saturation) and business (records created, automation executions, AI usage) |
| Tracing | Correlation ID propagated through every layer and downstream call, including to external integrations |
| Health probes | Liveness and readiness per component, consumed by the orchestration layer |
| Alerting | Defined service-level indicators with paging thresholds; consumer lag on audit and projection consumers is a production incident, not a metric to note |
| Tenant-visible telemetry | Adoption, usage, API consumption, storage, automation volume, all visible to the tenant's own administrator (`FR-AUD-015`) |

## 10. Backup and disaster recovery

Full design in [system design §12](../architecture/system-design.md#12-availability-and-disaster-recovery). Targets:

| Scenario | RTO | RPO |
|---|---|---|
| Single-AZ failure | < 60 s (automatic) | 0 (synchronous standby) |
| Regional failure | 4 hours | 5 minutes |
| Accidental mass deletion | Recovery via recycle bin (`FR-ADM-011`) if within retention; otherwise point-in-time restore | Retention-window dependent |
| Single-tenant data corruption | Tenant-scoped restore path **[needs a dedicated build — flagged as a real gap in ADR-001, not yet a solved problem]** | Target: 1 hour |

## 11. Tenant data portability

Per `FR-AUD-013`. **Complete export, self-service, every tier, no vendor assistance.** This is a differentiator claim ([competitive analysis](02-competitive-analysis-salesforce-zoho.md) §5.3) and is therefore held to a higher bar than a "nice to have" NFR:

- Export includes custom objects, attachments, audit history and configuration
- Delivered in a documented open format with a manifest and integrity checksums
- Completes within a published SLA proportional to tenant data volume
- Available even on a suspended tenant (not a terminated one)

## 12. What this document does not claim

Stated plainly rather than left to be discovered under audit:

- **No target here has been load-tested against a real implementation.** They are engineering targets derived from the scale statement and the architecture in [system design](../architecture/system-design.md), marked **[to be validated]** where the basis is an assumption rather than a measurement.
- **Compliance mappings are not certifications.** Achieving them requires an audit engagement this document does not constitute.
- **The single-tenant restore path is an acknowledged gap**, not a solved capability, and is called out rather than glossed over.
- Availability targets assume the staged scaling path in [system design §4.4](../architecture/system-design.md#44-the-staged-scaling-path) is followed — they are not guaranteed at a scale beyond what that path has been designed for without revisiting the design.

## Related documents

- [System design](../architecture/system-design.md) — the architecture these targets constrain
- [FRD §5 Global requirements](03-frd.md#5-global-requirements) — `FR-GLOBAL-*`, `FR-AUD-*`
- [Tenancy, licensing and deployment](16-tenancy-licensing-and-deployment.md)
- [ADR-005](../architecture/adr/ADR-005-technology-selection-deferred.md) — the Kafka throughput analysis behind §1

# Product scope and capability map

> **Working codename: Axiom.** The product name is a placeholder used consistently across this documentation set so the specs read naturally. Confirm or replace before any external-facing material is produced.

## Product vision

Axiom is an enterprise-grade B2B CRM that gives revenue teams one governed record of every account relationship — from first touch through qualification, deal, contract, renewal and support — with AI as the default working surface rather than a premium add-on, and with the customer's data remaining under the customer's control.

The product exists because the two dominant options force a bad trade. Salesforce delivers depth but couples it to high list price, upper-edition gating of features that enterprises consider baseline (sandboxes, advanced sharing, API volume, AI), and a structural dependency on administrators and consultants. Zoho delivers value and breadth but hits a ceiling on complex enterprise data models, governance granularity and tier-limited automation. Buyers who need enterprise governance *and* predictable cost *and* data sovereignty currently have to compromise on one of the three.

A user should never re-key the same business fact between lead, opportunity, quote, contract, invoice hand-off and support. A manager should never be asked to trust a forecast they cannot decompose. An administrator should never need a certified consultant to change a picklist.

## Positioning statement

For **B2B revenue organizations of 50–5,000 seats** who need enterprise governance without enterprise lock-in, Axiom is a **CRM platform** that **makes AI, sovereignty, and full-featured administration part of the base product rather than upper-tier SKUs**. Unlike **Salesforce and Zoho CRM**, Axiom **ships SSO, audit, full API, sandboxes and AI assistance in every tier, deploys either as pooled SaaS or as a customer-controlled single-tenant install from the same codebase, and treats vertical depth as a configurable pack rather than a custom-build project.**

## Primary personas

| Persona | Code | Primary outcomes |
|---|---|---|
| Account executive / sales rep | `AE` | Work today's highest-value actions, capture a deal accurately in minimal keystrokes, never lose context on an account |
| Sales development rep | `SDR` | Work a prioritized queue of leads, execute multi-step outreach, hand over qualified opportunities cleanly |
| Sales manager | `MGR` | Inspect pipeline quality, coach on specific deals, submit a defensible forecast for the team |
| VP Sales / CRO | `CRO` | See committed vs. best-case vs. pipeline coverage by segment, understand forecast movement week over week |
| Revenue operations | `REVOPS` | Own process definition, territory and quota, data quality, and the accuracy of every reported number |
| Marketing manager | `MKT` | Segment audiences, run campaigns, prove attribution, hand marketing-qualified leads over with context intact |
| Customer success manager | `CSM` | Monitor account health, drive adoption, own renewal and expansion motions |
| Support agent | `SUP` | Resolve cases within entitlement SLA with full account and product context |
| Partner manager | `PRTNR` | Manage channel partners, deal registration, partner-sourced pipeline and conflict resolution |
| Finance / billing | `FIN` | Trust that quotes, discounts, contracts and orders reflect approved commercial terms |
| Compliance officer / auditor | `AUD` | Trace who saw what, who changed what, who approved what, and on what authority |
| Data steward | `STEW` | Enforce deduplication, enrichment, retention and correctness of the customer record |
| Tenant administrator | `ADMIN` | Configure objects, fields, layouts, roles, automation and integrations safely, without a consultant |
| Platform operator | `OPS` | Provision, monitor, upgrade, back up and restore tenants; meet availability and support commitments |
| Relationship manager *(BFSI)* | `RM` | Manage a book of clients, see holdings and life events, act on suitable next-best products |
| KYC / AML analyst *(BFSI)* | `KYC` | Complete onboarding due diligence, risk-rate a client, evidence every decision to a regulator |

`AE`, `SDR`, `MGR`, `REVOPS`, `ADMIN`, `AUD` are the P0 personas — the first release must be genuinely usable by all six without workarounds.

## Product principles

1. **Enter once, reuse everywhere.** A fact captured on a lead flows to account, opportunity, quote, contract and support without re-entry or reconciliation.
2. **Explain every number.** Forecast, score, health index, attribution and pipeline metrics link to their inputs, formula version and the records that produced them. A number a manager cannot decompose is a defect.
3. **Exception-first surfaces.** Home and queues show what needs action, why, who owns it and when it is due — not a wall of records to browse.
4. **AI proposes, humans dispose.** AI drafts, ranks, summarizes and predicts. It never sends, commits, discounts or deletes without an explicit human act, and every AI output is traceable to the records that grounded it.
5. **Safe by default.** Tenant scoping, record-level authorization, field-level security, maker-checker on controlled actions and immutable audit are built in, not configured on.
6. **Administrable without a consultant.** Every routine change — field, picklist, layout, stage, rule, role, report — is achievable by a trained tenant administrator through the product, with sandbox and controlled promotion.
7. **Sovereignty is a deployment choice, not a fork.** The same codebase runs pooled multi-tenant SaaS and single-tenant customer-controlled installs. Tenancy is infrastructure; the domain never branches on it.
8. **Configurable without becoming a rule-engine swamp.** Stable domain concepts stay in code. Customer variation uses governed configuration with versioning, validation and rollback.
9. **The customer's data is the customer's.** Complete export in an open format, on demand, at any tier, without a support ticket. No feature is built on the assumption that leaving is hard.
10. **No tier traps.** SSO, audit log, full API, sandbox, encryption and baseline AI are in the base tier. Pricing scales on volume and advanced capability, never on withholding security or interoperability.

## In-scope capability map

### Core CRM
- Lead capture, deduplication, enrichment, scoring, qualification and routing
- Account, contact and B2B relationship hierarchy, including parent/child org structures and buying groups
- Opportunity and pipeline management with configurable stages, qualification frameworks and competitive tracking
- Activity management: tasks, events, calls, notes, and passive email/calendar capture
- Products, price books, quotes, configure-price-quote, discount approval and e-signature hand-off
- Contracts, orders, subscriptions, entitlements and renewal management
- Case management with entitlement-driven SLA, queues, escalation and knowledge

### Revenue intelligence
- Forecasting with roll-up hierarchy, categories, submission, override and full explainability
- Pipeline analytics: coverage, velocity, conversion, stage duration, slippage, win/loss
- Quota, territory and attainment management
- Health scoring for accounts and deals with contributing-factor disclosure

### Platform
- Multi-tenancy, tenant lifecycle and entitlement management
- Identity: SSO (SAML/OIDC), SCIM provisioning, MFA, session governance
- Authorization: roles, profiles, permission sets, record sharing, field-level security, segregation of duties
- Extensibility: custom objects, fields, layouts, validation rules, formulas
- Automation: workflow rules, approval processes, scheduled and event-triggered actions, business-process enforcement
- Reporting: report builder, dashboards, scheduled delivery, export governance
- Integration: REST and bulk APIs, webhooks, domain event stream, connector catalogue
- Administration: sandbox, configuration versioning, controlled promotion, change audit
- Observability, audit trail, data retention, and tenant data export/erasure

### Differentiators
- **AI-native workspace** — passive activity capture, next-best-action, explainable forecasting, conversational query, agentic task execution, with guardrails and an AI-off mode
- **Sovereign deployment** — single-tenant, customer-controlled installation from the same codebase, including self-hosted or disabled AI
- **Zero-friction adoption** — guided onboarding, and migration importers from Salesforce, Zoho and HubSpot with mapping, dry-run, reconciliation and rollback
- **Vertical packs** — governed, installable industry extensions. Two are specified: a **BFSI pack** (relationship-manager books, KYC/AML onboarding, product holdings, suitability, consent and communication archiving) and a **commodity trading pack** (counterparty origination, master agreements, tenders, cargo enquiries, credit gating, and hand-off to an external CTRM/ETRM system of record)

## Out of scope

Explicitly not built in this product, and not implied by any requirement in this documentation set:

| Out of scope | Rationale / expected source |
|---|---|
| Marketing automation execution (email sending infrastructure, landing pages, journey execution at scale) | Integrate with the customer's existing marketing automation platform; Axiom owns segments, campaign records and attribution |
| Accounting, invoicing, revenue recognition and tax calculation | Integrate with ERP/billing; Axiom owns the contract and order record and hands off |
| E-signature execution | Integrate with the customer's e-signature provider; Axiom owns envelope state and the signed-document reference |
| Telephony/PBX infrastructure | Integrate via CTI connector; Axiom owns the call record, disposition and recording reference |
| Full customer data platform / identity resolution across anonymous web traffic | Axiom resolves known B2B entities; anonymous identity graph is a CDP concern |
| Field service dispatch and scheduling | Adjacent product domain |
| Commerce storefront and cart | Adjacent product domain |
| Building the machine-learning models themselves | Axiom abstracts a model provider (hosted or self-hosted) — see [ADR-004](../architecture/adr/ADR-004-ai-provider-abstraction.md) |
| **Commodity trading execution — trade capture, position, valuation, risk, scheduling, inventory, settlement** | Owned by the customer's CTRM/ETRM system of record. The commodity pack (`E23`) covers **origination only** and hands off at "deal agreed" through a generic connector. A CRM that computes positions or credit exposure has become a second, worse trading system — see [the commodity trading pack](17-vertical-pack-commodity-trading.md) |

## Scope boundaries that require a decision later

These are genuinely open. They are recorded here rather than silently assumed:

1. **Service/case management depth.** This set specifies B2B post-sale support sufficient for entitlement-driven SLA and escalation (`E12`). Full contact-centre capability — omni-channel routing, workforce management, IVR — is not specified and would be a separate product decision.
2. **Further vertical packs.** Two packs are specified — BFSI (`E22`) and commodity trading (`E23`). The pack framework is designed to carry more; no third industry is specified.
3. **Technology stack.** Deliberately unbound — see [ADR-005](../architecture/adr/ADR-005-technology-selection-deferred.md).

## Related documents

- [Competitive analysis: Salesforce and Zoho](02-competitive-analysis-salesforce-zoho.md)
- [Functional requirements (FRD)](03-frd.md)
- [Feature catalogue and parity matrix](04-feature-catalogue.md)
- [Epics, user stories and acceptance criteria](05-epics-and-stories.md)

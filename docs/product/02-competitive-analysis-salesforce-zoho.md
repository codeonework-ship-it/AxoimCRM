# Competitive analysis — Salesforce and Zoho CRM

> **Fact provenance.** Capability descriptions in this document come from product domain knowledge and are stable. **Prices and edition/tier boundaries were verified on 2026-07-25** and are volatile — re-verify before any external use.
>
> `www.salesforce.com` returns HTTP 403 to automated fetches, so Salesforce list prices below are **cross-checked across multiple independent third-party sources rather than read from the vendor page directly**, and are labelled accordingly. Zoho pricing was read from the vendor page (which renders local currency — Indian list prices are the vendor-page figures; USD figures are cross-checked third-party).

---

## 1. Why this analysis exists

The purpose is not to catalogue competitor features for their own sake. It is to answer three questions that determine what Axiom builds:

1. **What is table stakes?** Anything both vendors ship in their mid-tier is a feature buyers will not evaluate us on — its absence loses the deal, its presence wins nothing.
2. **What is gated?** Features both vendors *have* but withhold from lower tiers are our sharpest wedge: we can ship them in base and make the comparison uncomfortable.
3. **What is structurally hard for them?** Capabilities their architecture or business model makes expensive to offer are where a durable moat exists.

Sections 2 and 3 answer the user's direct question — what the features actually are. Sections 4 onward turn that into product strategy.

---

## 2. Salesforce — feature inventory

Salesforce is a platform-first suite. Sales Cloud is one product on a shared metadata platform, and much of what buyers call "Salesforce features" are platform capabilities exposed to every cloud.

### 2.1 Core Sales Cloud

| Area | Capabilities |
|---|---|
| **Lead management** | Lead capture (web-to-lead), assignment rules, queues, lead scoring (Einstein), duplicate management with matching/duplicate rules, conversion to account/contact/opportunity |
| **Account & contact** | Account hierarchy (parent/child), contact roles on opportunities, related contacts across accounts, person accounts (B2C), account teams, account plans |
| **Opportunity** | Configurable sales processes and stage sets per record type, products/line items, opportunity splits (revenue and overlay), competitors, opportunity teams, big deal alerts, path guidance |
| **Activity** | Tasks, events, activity timeline, Einstein Activity Capture (passive email/calendar sync), email templates, Sales Engagement cadences, Inbox |
| **Forecasting** | Collaborative Forecasts with forecast categories, hierarchy roll-up, manager judgment/overrides, quotas, multi-currency, custom forecast measures, forecast types (revenue, quantity, splits) |
| **Territory** | Enterprise Territory Management — territory models, hierarchies, assignment rules, territory-based sharing and forecasting |
| **Quoting** | Native quotes with sync-to-opportunity, quote templates and PDF generation, price books and multiple entries per product |
| **Contracts & orders** | Contract records with lifecycle status, orders and order products, contract line items (with Revenue Cloud) |
| **Partner** | Partner accounts, deal registration, partner roles, Experience Cloud partner portals, channel-partner forecasting |

### 2.2 Service Cloud (relevant B2B post-sale)

Cases with assignment/escalation rules, queues, milestones, entitlements and service contracts, Omni-Channel routing, Knowledge with article versioning and approval, case swarming, CSAT surveys, self-service portals via Experience Cloud, Field Service as a separate SKU.

### 2.3 CPQ and Revenue Cloud

Product bundles and configuration rules, option constraints, guided selling, price rules and discount schedules, tiered/volume/block pricing, contracted pricing, multi-dimensional quoting, subscription/term pricing, amendments, renewals and co-terminus handling, quote document generation, approval matrices, order and asset lifecycle, revenue schedules. **A separate SKU with its own licence cost**, and — historically — one of the more implementation-heavy parts of the stack.

### 2.4 Einstein / Agentforce (AI)

Predictive lead and opportunity scoring, Einstein Forecasting, Einstein Activity Capture, Einstein Conversation Insights (call transcription and keyword/competitor tracking), Einstein Opportunity/Account Insights, Einstein Prediction Builder, Einstein GPT/Prompt Builder for generative field and email drafting, Agentforce autonomous agents grounded on Data Cloud, Einstein Trust Layer (masking, grounding, zero-retention agreements with model providers), Data Cloud as the unifying data substrate.

### 2.5 Platform (the real depth)

| Area | Capabilities |
|---|---|
| **Data model** | Custom objects, custom fields, record types, relationships (lookup, master-detail, junction, hierarchical), roll-up summaries, formula fields, schema builder, external objects (Salesforce Connect) |
| **Security** | Profiles, permission sets and permission set groups, org-wide defaults, role hierarchy, sharing rules (criteria/owner-based), manual sharing, team-based sharing, territory sharing, Apex managed sharing, field-level security, Shield Platform Encryption, Event Monitoring, Field Audit Trail, login IP ranges, session settings |
| **Automation** | Flow (record-triggered, screen, scheduled, autolaunched, orchestration), approval processes with multi-step and dynamic approvers, validation rules, duplicate rules, assignment/escalation rules, Apex triggers |
| **Development** | Apex, Lightning Web Components, SOQL/SOSL, Visualforce (legacy), metadata API, Salesforce DX, unlocked/managed packages, scratch orgs |
| **Release mgmt** | Full, partial and developer sandboxes, change sets, DX source deploys, deployment validation |
| **Analytics** | Reports and report types, joined and matrix reports, dashboards with dynamic running user, Einstein Analytics / CRM Analytics (separate SKU), report subscriptions |
| **Integration** | REST/SOAP/Bulk/Streaming/Pub-Sub APIs, Platform Events, Change Data Capture, External Services, MuleSoft (separate), Named Credentials |
| **Extensibility** | AppExchange with thousands of managed packages — the single largest structural advantage |
| **UX** | Lightning App Builder, dynamic forms and actions, mobile app, Mobile SDK, Experience Cloud portals |

### 2.6 Salesforce editions and pricing

*Sales Cloud list price, per user per month. Verified 2026-07-25 via cross-checked third-party sources; not read from the vendor page (403).*

| Edition | List price | Notes |
|---|---|---|
| Starter Suite | **$25** | The only edition offering monthly billing; the rest require annual |
| Pro Suite | **$80** | |
| Enterprise | **$175** | Reflects the ~6% list increase applied in August 2025 |
| Unlimited | **$350** | Reflects the same increase |
| Agentforce 1 Sales | **$550** | Bundles Agentforce agents, Data Cloud, full Einstein set |

⚠️ **Conflicting sources.** Several aggregators still publish **$165 (Enterprise)** and **$330 (Unlimited)** — the pre-August-2025 figures. The $175/$350 pair is the better-supported current list. An older **Einstein 1 Sales at $500** SKU also appears in sources alongside Agentforce 1 Sales at $550; treat the $550 Agentforce SKU as current and the $500 figure as superseded. Do not quote either pair externally without re-verification.

**Also note:** street price is commonly reported at **30–50% below list** for negotiated enterprise contracts. Any TCO comparison built only on list price overstates the gap and will not survive contact with a procurement team. Our competitive claims must hold at discounted price, not just at list.

### 2.7 What Salesforce gates behind upper tiers or separate SKUs

This is the strategically important list:

- **API access** — not available in the entry tiers
- **Sandboxes** — limited by edition; full-copy sandboxes are Unlimited or a paid add-on
- **Advanced sharing, role hierarchy depth, territory management** — Enterprise and above
- **Workflow/Flow complexity, approval processes** — constrained in lower editions
- **Einstein / Agentforce AI** — Enterprise and above, with the substantive AI in the $550 SKU
- **CPQ / Revenue Cloud** — separate licence
- **CRM Analytics** — separate licence
- **Field Audit Trail, Platform Encryption, Event Monitoring (Shield)** — paid add-on
- **Premier support** — paid add-on below Unlimited

---

## 3. Zoho CRM — feature inventory

Zoho CRM is a value-first suite whose strategic weapon is the surrounding Zoho One bundle (~45 applications for roughly the price of one competitor seat).

### 3.1 Core CRM

| Area | Capabilities |
|---|---|
| **Lead management** | Web forms, lead scoring rules, assignment rules, duplicate detection and merge, lead conversion mapping |
| **Account & contact** | Accounts, contacts, parent account hierarchy, multi-select lookups, related lists |
| **Deals** | Pipelines (multiple per module), stage probability, contact roles, competitors via custom modules, deal-stage history |
| **Activity** | Tasks, meetings, calls, SalesSignals (real-time engagement notifications), email integration and tracking, Cadences (multi-step outreach sequences) |
| **Forecasting** | Forecast targets by user/role/territory, quota management, forecast reports |
| **Inventory** | Native price books, quotes, sales orders, purchase orders, invoices and vendors — genuinely native, not an add-on |
| **CPQ** | Configure-price-quote with product configurator, pricing rules and guided selling |
| **Omnichannel** | Email, telephony (PhoneBridge), live chat (SalesIQ), social, WhatsApp/messaging, customer portals |

### 3.2 Process and automation

- **Blueprint** — visual, *enforced* stage-by-stage process with mandatory fields, transition conditions and SLAs at each state. Genuinely differentiated: it enforces process rather than merely automating it.
- **CommandCenter** — cross-application customer journey orchestration spanning multiple Zoho apps
- **Workflow rules, macros, scheduled functions, custom functions (Deluge scripting)**
- **Approval processes, validation rules, assignment rules, escalation**
- **Wizards** — multi-step guided data entry
- **Canvas** — no-code record-page design, notably better-looking output than most no-code builders

### 3.3 Zia (AI)

Lead/deal scoring, win-probability prediction, anomaly detection against trend, best-time-to-contact, email sentiment and intent, Zia Voice conversational assistant, prediction builder, data enrichment, Zia Vision (image validation), and — at Ultimate — custom AI/ML model building. AI agents are now surfaced from the Standard edition per the vendor pricing page.

### 3.4 Platform

Custom modules, fields and layouts; multiple layouts per module with layout rules; sandbox (developer); REST API with per-edition call limits; Zoho Marketplace extensions; Zoho Creator for low-code apps; Zoho Analytics for BI; territory management; multi-currency; GDPR compliance tooling; audit log; encryption; role and profile-based security with field-level permissions and data-sharing rules.

### 3.5 Zoho editions and pricing

*Verified 2026-07-25. Vendor page renders local currency — Indian figures are from the vendor page; USD figures are cross-checked third-party.*

| Edition | USD/user/month (annual) | INR/user/month (monthly, vendor page) | Key additions at this tier |
|---|---|---|---|
| Free | $0 (up to 3 users) | — | Contact management, basic workflow, 5,000 API calls/day, mobile apps |
| Standard | **$14** | ₹800 | Workflows and assignment rules, AI agents, cadences, reports and dashboards, forecasting, kiosks |
| Professional | **$23** | ₹1,400 | **CPQ**, email intelligence, process automation (Blueprint), widgets, inventory management, Google Ads |
| Enterprise | **$40** | ₹2,400 | **Zia AI sales assistant**, journey orchestration (CommandCenter), **territory management**, custom functions, wizards, customer portals, **developer sandbox** |
| Ultimate | **$52** | ₹2,600 | Enhanced limits, **custom AI/ML**, data preparation, consulting and migration assistance |

Monthly billing runs roughly **20–34% above** annual; Enterprise monthly is about **$50**.

### 3.6 What Zoho gates or limits

- **Zia AI assistant, territory management, sandbox, customer portals, custom functions** — Enterprise and above
- **CPQ, Blueprint** — Professional and above
- **Custom AI/ML, data prep** — Ultimate only
- **API call volume, workflow rule counts, custom module counts, field counts** — hard per-edition numeric limits that bite in real enterprise deployments
- **Zoho Analytics, SalesIQ, Campaigns** — separate products (bundled in Zoho One)

---

## 4. Head-to-head assessment

| Dimension | Salesforce | Zoho CRM | Implication for Axiom |
|---|---|---|---|
| Core CRM breadth | Excellent | Excellent | **Table stakes.** Must match, will not differentiate |
| Data model flexibility | Excellent | Good, with hard limits | Match Salesforce's flexibility; remove the numeric limits |
| Sharing/authorization granularity | Best in class | Moderate | Match Salesforce — this is the real enterprise gate |
| Process enforcement | Flow (powerful, complex) | Blueprint (elegant, enforced) | **Adopt Blueprint's model** — enforced process is better than scripted process |
| CPQ | Deep, separate SKU, heavy | Native, capable, mid-tier | Native CPQ in base is a strong, concrete wedge |
| AI | Deep but expensive and top-tier | Broad but Enterprise+ | **Primary differentiator.** AI in base tier, explainable, with AI-off mode |
| Analytics | Strong, separate SKU for advanced | Good, separate product | Solid native reporting in base; avoid a BI SKU |
| Ecosystem | AppExchange — decisive advantage | Marketplace + Zoho One | **We will not win here.** Compensate with first-class open APIs and migration tooling |
| Admin burden | High — consultant economy | Low–moderate | **Differentiator.** Admin-without-consultant is a measurable claim |
| Total cost | High ($175–$550 list) | Low ($14–$52) | Position between, with no gated security/interop |
| Data sovereignty | Public cloud (Hyperforce residency options) | Public cloud, multi-region DCs | **Differentiator.** True customer-controlled single-tenant install |
| Vertical depth | Industry Clouds, expensive | Limited | Configurable vertical packs, BFSI first |

### The two honest weaknesses in our position

State these plainly rather than discovering them in a lost deal:

1. **Ecosystem.** AppExchange represents thousands of pre-built integrations and a partner network that a new entrant cannot replicate. Our answer must be *interoperability* (open, complete, well-documented APIs and an events stream) rather than a pretence of matching catalogue size.
2. **Trust and reference-ability.** Enterprise buyers de-risk with references and vendor longevity. This is overcome with the BFSI vertical pack (a narrow, credible beachhead), rigorous compliance posture, and sovereign deployment answering the "what if you disappear" objection directly — not with feature count.

---

## 5. Differentiation thesis

Four angles, each stated as a claim we must be able to defend in a competitive evaluation.

### 5.1 AI-native, not AI-upsold

**Claim:** Every Axiom user gets AI assistance in the base tier; every AI output shows its work.

Both competitors treat substantive AI as an upper-tier or separate-SKU upsell — Agentforce at $550/user, Zia's assistant at Enterprise. More importantly, both largely produce *scores* rather than *explanations*. A manager told "this deal is 34% likely" cannot act; a manager told "34% — no economic buyer engaged in 21 days, two competitor mentions, and the close date has slipped twice" can.

Axiom commits to: baseline AI in every tier; every score decomposed into contributing factors; every generated summary or draft citing the source records; agentic execution gated behind explicit human confirmation; and a fully functional AI-off mode for customers who cannot use it. See [AI capabilities](11-ai-capabilities.md).

### 5.2 Sovereign by deployment, not by promise

**Claim:** The same Axiom that runs as pooled SaaS runs inside the customer's own infrastructure, with no feature fork.

Neither competitor offers genuine customer-controlled deployment. For regulated buyers — public sector, defence supply chain, BFSI under residency mandates, EU buyers with US-cloud concerns — this is not a feature preference but an eligibility gate. Because tenancy is a deployment concern rather than a domain concern ([ADR-001](../architecture/adr/ADR-001-tenancy-isolation.md)), a sovereign install is one tenant in the standard schema, running the standard code, with a self-hosted or disabled model provider.

### 5.3 Zero-friction adoption and transparent pricing

**Claim:** A customer can migrate from Salesforce, Zoho or HubSpot themselves, and every security and interoperability feature is in the base price.

The competitor tier structures create the wedge: API access, sandboxes, audit trail, encryption and SSO are all gated or add-on somewhere in their line-ups. Axiom puts all of them in base and prices on volume and advanced capability instead. Migration importers with field mapping, dry-run, reconciliation reporting and rollback ([integration and migration](13-integration-and-migration.md)) attack the switching cost that is the incumbents' strongest retention mechanism.

Pricing packaging is specified in [tenancy, licensing and deployment](16-tenancy-licensing-and-deployment.md).

### 5.4 Vertical depth as configuration, not custom build

**Claim:** BFSI-specific concepts ship as a governed pack, not a six-month implementation project.

Salesforce Industry Clouds are expensive; Zoho has little vertical depth. A BFSI pack delivering relationship-manager books, KYC/AML onboarding, product holdings, suitability and consent tracking ([BFSI pack](12-vertical-pack-bfsi.md)) is a credible beachhead: a narrow market where we can be demonstrably the best fit rather than a cheaper generalist.

---

## 6. Feature decisions driven by this analysis

| Decision | Driver |
|---|---|
| Adopt enforced-process modelling (Blueprint-style) rather than script-first automation | Zoho's Blueprint is the better model; Salesforce Flow's power comes with genuine complexity cost |
| Match Salesforce's sharing model in full | It is the actual enterprise gate; anything less disqualifies us in evaluation |
| Ship native CPQ in base | Salesforce charges separately; Zoho gates to Professional |
| No numeric feature limits by tier (API calls, custom objects, workflow rules) | Zoho's hard limits are its most-cited enterprise frustration |
| Every score and forecast explainable by construction | Neither competitor does this well; it is cheap to build if designed in, expensive to retrofit |
| Migration importers as a P0 epic, not a services offering | Switching cost is the incumbents' retention moat |
| Open API and event stream at every tier | Only viable answer to the AppExchange gap |
| Full-copy sandbox in base | Gated by both competitors; a concrete, checkable claim |

---

## Sources

Verified 2026-07-25.

- [Zoho CRM pricing (vendor page)](https://www.zoho.com/crm/zohocrm-pricing.html)
- [Salesforce Pricing: 2026 Editions, Plans and Costs — Twelverays](https://twelverays.agency/blog/salesforce-pricing)
- [Salesforce Sales Cloud Pricing 2026: Real Tier Costs — Atonement Licensing](https://atonementlicensing.com/blog/salesforce-sales-cloud-pricing-2026/)
- [Salesforce Pricing 2026: Plans, Add-Ons & Hidden Costs — SaaS CRM Review](https://saascrmreview.com/salesforce-pricing/)
- [Salesforce Pricing 2026: Plans, Costs and What You Actually Pay — Leadhaste](https://leadhaste.com/blog/salesforce-pricing-2026)
- [Salesforce Sales Cloud Enterprise vs Unlimited — Redress Compliance](https://redresscompliance.com/salesforce-enterprise-vs-unlimited.html)
- [Zoho CRM Pricing: Complete Guide to Plans and Costs (2026) — Zeeg](https://zeeg.me/en/blog/post/zoho-crm-pricing)
- [Zoho CRM Pricing (2026): Editions, Add-Ons — Codestringers](https://www.codestringers.com/articles/zoho-crm-pricing)
- [Zoho CRM Pricing 2026 — G2](https://www.g2.com/products/zoho-crm/pricing)

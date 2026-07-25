# Feature catalogue and competitive parity matrix

The complete feature brainstorm for Axiom, organized by epic, with each feature mapped against Salesforce and Zoho CRM.

## How to read this document

**Availability legend**

| Symbol | Meaning |
|:--:|---|
| ✅ | Available and strong |
| 🟡 | Available but limited, awkward, or requires custom build |
| 💲 | Available only in an upper edition or as a separately-licensed SKU |
| ⛔ | Not available |

**Strategic classification** — the reason a feature is in the catalogue:

| Code | Meaning |
|---|---|
| `TS` | **Table stakes** — both competitors have it; absence loses deals, presence wins none |
| `GAP` | **Gap we close** — competitors gate it behind price/tier; we ship it in base |
| `UNQ` | **Unique to us** — no competitor equivalent; this is where the moat is |
| `PAR` | **Deliberate parity choice** — we adopt a competitor's superior design |

**Priority** — `P0` first production release · `P1` next release · `P2` later.

Competitor assessments are as of 2026-07-25; see [competitive analysis](02-competitive-analysis-salesforce-zoho.md) for pricing provenance and caveats.

---

## E01 — Tenancy, identity and access

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-001 | Tenant provisioning and lifecycle (create, suspend, resume, terminate) | ✅ | ✅ | ✅ | TS | P0 |
| F-002 | Username/password authentication with configurable policy | ✅ | ✅ | ✅ | TS | P0 |
| F-003 | SAML 2.0 single sign-on | 💲 | 💲 | ✅ | GAP | P0 |
| F-004 | OpenID Connect single sign-on | 💲 | 💲 | ✅ | GAP | P0 |
| F-005 | Multiple concurrent identity providers per tenant | 🟡 | 🟡 | ✅ | GAP | P1 |
| F-006 | SCIM 2.0 user and group provisioning/deprovisioning | 💲 | 🟡 | ✅ | GAP | P0 |
| F-007 | Just-in-time user provisioning from SSO assertion | ✅ | 🟡 | ✅ | TS | P1 |
| F-008 | Multi-factor authentication (TOTP, WebAuthn/passkey) | ✅ | ✅ | ✅ | TS | P0 |
| F-009 | MFA enforcement policy by role, profile or IP context | ✅ | 🟡 | ✅ | TS | P0 |
| F-010 | Step-up authentication for privileged/controlled actions | 🟡 | ⛔ | ✅ | UNQ | P0 |
| F-011 | Session lifetime, idle timeout and concurrent-session policy | ✅ | ✅ | ✅ | TS | P0 |
| F-012 | Active session listing and administrative revocation | ✅ | 🟡 | ✅ | TS | P0 |
| F-013 | Login IP allowlisting and geo/network restriction | ✅ | 🟡 | ✅ | TS | P1 |
| F-014 | Trusted-device registration and recognition | ✅ | 🟡 | ✅ | TS | P1 |
| F-015 | API tokens and OAuth 2.0 client credentials for service accounts | 💲 | ✅ | ✅ | GAP | P0 |
| F-016 | Login-as-user (support impersonation) with consent and full audit | ✅ | 🟡 | ✅ | TS | P0 |
| F-017 | Break-glass emergency access with mandatory case reference | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-018 | Password reset, account lockout and recovery flows | ✅ | ✅ | ✅ | TS | P0 |
| F-019 | Per-tenant branding of login experience | 💲 | 💲 | ✅ | GAP | P1 |
| F-020 | Named-user vs. concurrent licensing enforcement | ✅ | ✅ | ✅ | TS | P1 |

## E02 — RBAC, record sharing and segregation of duties

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-021 | Role hierarchy with upward record visibility roll-up | ✅ | ✅ | ✅ | TS | P0 |
| F-022 | Profiles — baseline object and system permissions | ✅ | ✅ | ✅ | TS | P0 |
| F-023 | Permission sets — additive grants independent of profile | ✅ | 🟡 | ✅ | PAR | P0 |
| F-024 | Permission set groups with mute capability | 💲 | ⛔ | ✅ | GAP | P1 |
| F-025 | Organization-wide default sharing per object | ✅ | ✅ | ✅ | TS | P0 |
| F-026 | Criteria-based sharing rules | 💲 | 🟡 | ✅ | GAP | P0 |
| F-027 | Owner-based sharing rules | 💲 | 🟡 | ✅ | GAP | P0 |
| F-028 | Manual record sharing with expiry | ✅ | 🟡 | ✅ | TS | P1 |
| F-029 | Team-based sharing (account teams, opportunity teams) | ✅ | 🟡 | ✅ | TS | P1 |
| F-030 | Territory-based sharing | 💲 | 💲 | ✅ | GAP | P1 |
| F-031 | Field-level security (read/edit per field per profile) | ✅ | ✅ | ✅ | TS | P0 |
| F-032 | Record-level field masking for sensitive data (PII, financial) | 💲 | 🟡 | ✅ | GAP | P0 |
| F-033 | Segregation of duties — conflicting permission detection | ⛔ | ⛔ | ✅ | UNQ | P0 |
| F-034 | Maker-checker enforcement on controlled actions | 🟡 | 🟡 | ✅ | UNQ | P0 |
| F-035 | Delegated administration scoped to an org branch | ✅ | 🟡 | ✅ | TS | P1 |
| F-036 | Time-bound and auto-expiring access grants | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-037 | Access review campaign — periodic recertification of grants | ⛔ | ⛔ | ✅ | UNQ | P2 |
| F-038 | "Why can this user see this record?" access explainer | 🟡 | ⛔ | ✅ | UNQ | P1 |
| F-039 | Permission change audit with before/after and approver | ✅ | 🟡 | ✅ | TS | P0 |
| F-040 | Export and print permission controlled separately from read | 🟡 | 🟡 | ✅ | GAP | P0 |

## E03 — Organization, reference and master data

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-041 | Legal entity / business unit modelling within a tenant | 🟡 | 🟡 | ✅ | GAP | P0 |
| F-042 | Multi-currency with dated exchange rates | ✅ | ✅ | ✅ | TS | P0 |
| F-043 | Advanced currency management (historical rate per period) | 💲 | 🟡 | ✅ | GAP | P1 |
| F-044 | Fiscal year and custom fiscal calendar definition | ✅ | ✅ | ✅ | TS | P0 |
| F-045 | Business hours, holidays and working-calendar definition | ✅ | ✅ | ✅ | TS | P0 |
| F-046 | Time zone handling per user and per tenant | ✅ | ✅ | ✅ | TS | P0 |
| F-047 | Governed picklists with dependent/cascading values | ✅ | ✅ | ✅ | TS | P0 |
| F-048 | Global value sets reused across objects | ✅ | 🟡 | ✅ | TS | P0 |
| F-049 | Industry, segment and classification taxonomies | ✅ | ✅ | ✅ | TS | P0 |
| F-050 | Territory model with hierarchy and assignment rules | 💲 | 💲 | ✅ | GAP | P1 |
| F-051 | Territory model versioning with preview before activation | 💲 | ⛔ | ✅ | GAP | P2 |
| F-052 | Quota definition by user, team, territory and period | ✅ | ✅ | ✅ | TS | P1 |
| F-053 | Reference data effective dating (values valid from/to) | 🟡 | ⛔ | ✅ | UNQ | P1 |
| F-054 | Master data change approval workflow | 🟡 | 🟡 | ✅ | GAP | P1 |
| F-055 | Reference data import/export with validation report | ✅ | ✅ | ✅ | TS | P0 |

## E04 — Accounts, contacts, hierarchy and buying groups

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-056 | Account record with configurable layout and record types | ✅ | ✅ | ✅ | TS | P0 |
| F-057 | Contact record with configurable layout | ✅ | ✅ | ✅ | TS | P0 |
| F-058 | Multi-level account hierarchy (parent/child, ultimate parent) | ✅ | ✅ | ✅ | TS | P0 |
| F-059 | Roll-up of pipeline, revenue and activity across the hierarchy | 🟡 | 🟡 | ✅ | GAP | P0 |
| F-060 | Contact-to-multiple-account relationships with role and status | ✅ | 🟡 | ✅ | TS | P1 |
| F-061 | Buying group / decision unit modelling with role and influence | 🟡 | ⛔ | ✅ | UNQ | P1 |
| F-062 | Relationship map / org chart visualization | 💲 | 🟡 | ✅ | GAP | P1 |
| F-063 | Account team with role and access level | ✅ | 🟡 | ✅ | TS | P1 |
| F-064 | Account plan — objectives, whitespace, key relationships | 💲 | ⛔ | ✅ | GAP | P2 |
| F-065 | Account health score with contributing-factor breakdown | 💲 | 🟡 | ✅ | GAP | P1 |
| F-066 | Address management with multiple typed addresses | ✅ | ✅ | ✅ | TS | P0 |
| F-067 | Communication preference and consent capture per contact | 🟡 | ✅ | ✅ | TS | P0 |
| F-068 | Do-not-contact / suppression enforcement across all channels | 🟡 | ✅ | ✅ | TS | P0 |
| F-069 | Duplicate detection at entry with fuzzy matching | ✅ | ✅ | ✅ | TS | P0 |
| F-070 | Merge with field-level survivorship selection and full audit | ✅ | 🟡 | ✅ | TS | P0 |
| F-071 | Unmerge / merge reversal within a retention window | ⛔ | ⛔ | ✅ | UNQ | P2 |
| F-072 | Third-party data enrichment on create and refresh | 💲 | 💲 | ✅ | GAP | P1 |
| F-073 | Account 360 timeline unifying every interaction | ✅ | ✅ | ✅ | TS | P0 |
| F-074 | Related-record navigation with configurable related lists | ✅ | ✅ | ✅ | TS | P0 |
| F-075 | Follow/watch an account with change notification | ✅ | ✅ | ✅ | TS | P1 |

## E05 — Lead capture, qualification and routing

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-076 | Lead record with configurable layout and status model | ✅ | ✅ | ✅ | TS | P0 |
| F-077 | Web-to-lead form capture with spam protection | ✅ | ✅ | ✅ | TS | P0 |
| F-078 | Inbound API and bulk lead ingestion | 💲 | ✅ | ✅ | GAP | P0 |
| F-079 | Lead source tracking with campaign attribution | ✅ | ✅ | ✅ | TS | P0 |
| F-080 | Duplicate lead detection against leads, contacts and accounts | ✅ | ✅ | ✅ | TS | P0 |
| F-081 | Rule-based lead scoring | ✅ | ✅ | ✅ | TS | P0 |
| F-082 | Predictive lead scoring with factor explanation | 💲 | 💲 | ✅ | GAP | P0 |
| F-083 | Lead-to-account matching for account-based motions | 💲 | 🟡 | ✅ | GAP | P1 |
| F-084 | Assignment rules by territory, segment, round-robin, capacity | ✅ | ✅ | ✅ | TS | P0 |
| F-085 | Queue-based lead ownership with claim/release | ✅ | ✅ | ✅ | TS | P0 |
| F-086 | Speed-to-lead SLA timer with escalation on breach | 🟡 | 🟡 | ✅ | GAP | P0 |
| F-087 | Lead qualification framework capture (BANT/CHAMP configurable) | 🟡 | 🟡 | ✅ | GAP | P1 |
| F-088 | Lead conversion to account, contact and opportunity with mapping | ✅ | ✅ | ✅ | TS | P0 |
| F-089 | Conversion field mapping configuration including custom fields | ✅ | ✅ | ✅ | TS | P0 |
| F-090 | Lead recycling / nurture return with reason capture | 🟡 | 🟡 | ✅ | GAP | P1 |
| F-091 | Disqualification reason taxonomy and reporting | 🟡 | 🟡 | ✅ | GAP | P0 |
| F-092 | Lead reassignment with history and notification | ✅ | ✅ | ✅ | TS | P0 |
| F-093 | Cadence/sequence enrolment directly from the lead | 💲 | ✅ | ✅ | GAP | P1 |

## E06 — Opportunity and pipeline management

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-094 | Opportunity record with configurable layout and record types | ✅ | ✅ | ✅ | TS | P0 |
| F-095 | Multiple named pipelines with distinct stage sets | ✅ | ✅ | ✅ | TS | P0 |
| F-096 | Stage definition with probability, forecast category and exit criteria | ✅ | 🟡 | ✅ | TS | P0 |
| F-097 | Enforced stage gating — mandatory exit criteria block advancement | 🟡 | ✅ | ✅ | PAR | P0 |
| F-098 | Guided selling — stage-specific guidance and required actions | ✅ | ✅ | ✅ | TS | P0 |
| F-099 | Opportunity line items from price book with quantity and discount | ✅ | ✅ | ✅ | TS | P0 |
| F-100 | Opportunity splits — revenue and overlay credit | 💲 | ⛔ | ✅ | GAP | P1 |
| F-101 | Opportunity team with roles and access | ✅ | 🟡 | ✅ | TS | P1 |
| F-102 | Contact roles on the opportunity with buying-group linkage | ✅ | ✅ | ✅ | TS | P0 |
| F-103 | Competitor tracking with win/loss position | ✅ | 🟡 | ✅ | TS | P1 |
| F-104 | Qualification methodology capture (MEDDICC/SPICED, configurable) | 🟡 | ⛔ | ✅ | UNQ | P1 |
| F-105 | Deal risk signals with named cause and recommended action | 💲 | 🟡 | ✅ | GAP | P0 |
| F-106 | Close-date slippage tracking and history | 🟡 | 🟡 | ✅ | GAP | P0 |
| F-107 | Stage duration and stage-history audit | ✅ | ✅ | ✅ | TS | P0 |
| F-108 | Win/loss reason taxonomy with mandatory capture at closure | 🟡 | 🟡 | ✅ | GAP | P0 |
| F-109 | Kanban pipeline board with drag-to-advance and inline validation | ✅ | ✅ | ✅ | TS | P0 |
| F-110 | Pipeline change ("what moved since last week") comparison view | 💲 | ⛔ | ✅ | UNQ | P1 |
| F-111 | Opportunity cloning including line items | ✅ | ✅ | ✅ | TS | P1 |
| F-112 | Recurring/subscription revenue modelling on the opportunity | 💲 | 🟡 | ✅ | GAP | P1 |
| F-113 | Multi-currency opportunity with locked conversion rate | ✅ | ✅ | ✅ | TS | P0 |
| F-114 | Big-deal alerting and executive notification | ✅ | 🟡 | ✅ | TS | P1 |
| F-115 | Reopen a closed opportunity under controlled amendment | 🟡 | 🟡 | ✅ | GAP | P1 |

## E07 — Activity, email and calendar engagement

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-116 | Task management with due date, priority, owner and reminder | ✅ | ✅ | ✅ | TS | P0 |
| F-117 | Event/meeting management with attendees and outcome capture | ✅ | ✅ | ✅ | TS | P0 |
| F-118 | Call logging with disposition and duration | ✅ | ✅ | ✅ | TS | P0 |
| F-119 | Notes with rich text and attachment | ✅ | ✅ | ✅ | TS | P0 |
| F-120 | Unified activity timeline across all related records | ✅ | ✅ | ✅ | TS | P0 |
| F-121 | Two-way email integration (Microsoft 365, Google Workspace) | ✅ | ✅ | ✅ | TS | P0 |
| F-122 | Passive email/calendar auto-capture with no manual logging | 💲 | 💲 | ✅ | GAP | P0 |
| F-123 | Email-to-record matching with confidence and correction | 💲 | 🟡 | ✅ | GAP | P0 |
| F-124 | Private/personal email exclusion rules and consent controls | 💲 | 🟡 | ✅ | GAP | P0 |
| F-125 | Email templates with merge fields and versioning | ✅ | ✅ | ✅ | TS | P0 |
| F-126 | Email open/click/reply tracking with privacy controls | 💲 | ✅ | ✅ | GAP | P1 |
| F-127 | Multi-step outreach cadences/sequences with branching | 💲 | ✅ | ✅ | GAP | P1 |
| F-128 | Calendar availability and meeting scheduling link | 💲 | ✅ | ✅ | GAP | P1 |
| F-129 | CTI/telephony integration with click-to-dial and screen pop | 💲 | ✅ | ✅ | GAP | P1 |
| F-130 | Call recording reference and transcription linkage | 💲 | 🟡 | ✅ | GAP | P1 |
| F-131 | Conversation intelligence — topics, competitor mentions, talk ratio | 💲 | 🟡 | ✅ | GAP | P2 |
| F-132 | Engagement signal alerts (contact opened proposal, revisited pricing) | 💲 | ✅ | ✅ | GAP | P1 |
| F-133 | Activity roll-up to account and opportunity with recency metrics | 🟡 | 🟡 | ✅ | GAP | P0 |
| F-134 | "Last contacted" and engagement-gap surfacing | 🟡 | ✅ | ✅ | TS | P0 |

## E08 — Products, price books, quotes and CPQ

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-135 | Product catalogue with categories, attributes and lifecycle status | ✅ | ✅ | ✅ | TS | P0 |
| F-136 | Multiple price books with currency and entity scoping | ✅ | ✅ | ✅ | TS | P0 |
| F-137 | Price book entries with effective dating | 🟡 | 🟡 | ✅ | GAP | P0 |
| F-138 | Quote creation from opportunity with sync-back | ✅ | ✅ | ✅ | TS | P0 |
| F-139 | Quote versioning with comparison between versions | 💲 | 🟡 | ✅ | GAP | P0 |
| F-140 | Product bundles and configurable kits | 💲 | ✅ | ✅ | GAP | P1 |
| F-141 | Configuration rules — inclusion, exclusion, requirement constraints | 💲 | ✅ | ✅ | GAP | P1 |
| F-142 | Guided selling questionnaire driving product selection | 💲 | ✅ | ✅ | GAP | P1 |
| F-143 | Tiered, volume and block pricing | 💲 | 🟡 | ✅ | GAP | P1 |
| F-144 | Percent-of-total and attribute-based pricing | 💲 | 🟡 | ✅ | GAP | P2 |
| F-145 | Contracted / negotiated customer-specific pricing | 💲 | 🟡 | ✅ | GAP | P1 |
| F-146 | Discount schedules and approval thresholds | 💲 | ✅ | ✅ | GAP | P0 |
| F-147 | Multi-dimensional quoting (term × segment pricing grid) | 💲 | ⛔ | ✅ | GAP | P2 |
| F-148 | Subscription and term-based pricing with proration | 💲 | 🟡 | ✅ | GAP | P1 |
| F-149 | Margin and cost visibility with floor enforcement | 💲 | 🟡 | ✅ | GAP | P1 |
| F-150 | Quote document generation with branded templates | ✅ | ✅ | ✅ | TS | P0 |
| F-151 | Multi-step discount approval with dynamic approver routing | ✅ | ✅ | ✅ | TS | P0 |
| F-152 | E-signature hand-off with envelope status tracking | 💲 | ✅ | ✅ | GAP | P1 |
| F-153 | Quote expiry, reminder and auto-lapse | 🟡 | ✅ | ✅ | TS | P1 |
| F-154 | Quote-to-order conversion | ✅ | ✅ | ✅ | TS | P1 |
| F-155 | Price-change impact preview before applying a new price book | ⛔ | ⛔ | ✅ | UNQ | P2 |

## E09 — Contracts, orders, subscriptions and renewals

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-156 | Contract record with lifecycle status and effective dates | ✅ | 🟡 | ✅ | TS | P1 |
| F-157 | Contract line items linked to products and quantities | 💲 | 🟡 | ✅ | GAP | P1 |
| F-158 | Clause library and contract term capture | 💲 | ⛔ | ✅ | GAP | P2 |
| F-159 | Contract amendment with versioning and change lineage | 💲 | ⛔ | ✅ | GAP | P1 |
| F-160 | Order and order-product management | ✅ | ✅ | ✅ | TS | P1 |
| F-161 | Subscription lifecycle (activate, suspend, upgrade, cancel) | 💲 | 🟡 | ✅ | GAP | P1 |
| F-162 | Renewal opportunity auto-generation ahead of expiry | 💲 | 🟡 | ✅ | GAP | P1 |
| F-163 | Co-terminus and mid-term amendment handling | 💲 | ⛔ | ✅ | GAP | P2 |
| F-164 | Entitlement records driving support SLA | 💲 | 🟡 | ✅ | GAP | P1 |
| F-165 | Installed base / asset register per account | 💲 | 🟡 | ✅ | GAP | P1 |
| F-166 | Churn and downgrade capture with reason taxonomy | 🟡 | ⛔ | ✅ | GAP | P1 |
| F-167 | Renewal risk scoring with contributing factors | 💲 | ⛔ | ✅ | UNQ | P2 |
| F-168 | Contract document storage with version history | ✅ | ✅ | ✅ | TS | P1 |
| F-169 | Obligation and milestone tracking with due alerts | 💲 | ⛔ | ✅ | GAP | P2 |
| F-170 | ERP/billing hand-off with reconciliation status | 🟡 | 🟡 | ✅ | GAP | P1 |

## E10 — Forecasting and revenue intelligence

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-171 | Forecast categories (pipeline, best case, commit, closed) | ✅ | 🟡 | ✅ | TS | P0 |
| F-172 | Forecast roll-up through the management hierarchy | ✅ | ✅ | ✅ | TS | P0 |
| F-173 | Manager judgment override with mandatory reason | ✅ | 🟡 | ✅ | TS | P0 |
| F-174 | Forecast submission, lock and period snapshot | ✅ | 🟡 | ✅ | TS | P0 |
| F-175 | Forecast versus quota attainment tracking | ✅ | ✅ | ✅ | TS | P0 |
| F-176 | Multiple forecast types (revenue, quantity, ARR, splits) | 💲 | 🟡 | ✅ | GAP | P1 |
| F-177 | AI forecast prediction with confidence interval | 💲 | 💲 | ✅ | GAP | P1 |
| F-178 | **Forecast explainability — full decomposition to source deals** | ⛔ | ⛔ | ✅ | UNQ | P0 |
| F-179 | Week-over-week forecast movement waterfall (added/slipped/lost) | 💲 | ⛔ | ✅ | UNQ | P0 |
| F-180 | Scenario modelling ("if these three deals slip…") | 💲 | ⛔ | ✅ | GAP | P1 |
| F-181 | Pipeline coverage ratio by segment and period | ✅ | ✅ | ✅ | TS | P0 |
| F-182 | Sales velocity and stage-conversion analytics | ✅ | ✅ | ✅ | TS | P0 |
| F-183 | Win/loss analysis by reason, competitor, segment and rep | ✅ | ✅ | ✅ | TS | P0 |
| F-184 | Forecast accuracy tracking against actuals over time | 🟡 | ⛔ | ✅ | UNQ | P1 |
| F-185 | Quota assignment, distribution and versioning | ✅ | ✅ | ✅ | TS | P1 |
| F-186 | Historical pipeline snapshots for point-in-time comparison | 💲 | 🟡 | ✅ | GAP | P0 |

## E11 — Campaigns, segments and marketing alignment

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-187 | Campaign record with hierarchy, budget and actual cost | ✅ | ✅ | ✅ | TS | P1 |
| F-188 | Campaign member management with status progression | ✅ | ✅ | ✅ | TS | P1 |
| F-189 | Dynamic segment builder over CRM data | 💲 | ✅ | ✅ | GAP | P1 |
| F-190 | Segment export/sync to marketing automation platform | ✅ | ✅ | ✅ | TS | P1 |
| F-191 | Campaign influence and multi-touch attribution models | 💲 | 🟡 | ✅ | GAP | P1 |
| F-192 | First-touch, last-touch and custom attribution comparison | 💲 | ⛔ | ✅ | GAP | P2 |
| F-193 | Marketing-qualified to sales-accepted lead hand-off with SLA | 🟡 | 🟡 | ✅ | GAP | P1 |
| F-194 | Campaign ROI reporting against sourced/influenced pipeline | ✅ | 🟡 | ✅ | TS | P1 |
| F-195 | Account-based marketing target list management | 💲 | 🟡 | ✅ | GAP | P2 |
| F-196 | Event and webinar attendance capture | ✅ | ✅ | ✅ | TS | P2 |

## E12 — Cases, entitlements and SLA management

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-197 | Case record with type, priority, severity and status model | 💲 | ✅ | ✅ | GAP | P1 |
| F-198 | Case capture from email, portal, API and web form | 💲 | ✅ | ✅ | GAP | P1 |
| F-199 | Queue and skill-based case assignment | 💲 | ✅ | ✅ | GAP | P1 |
| F-200 | Entitlement-driven SLA with response and resolution milestones | 💲 | 🟡 | ✅ | GAP | P1 |
| F-201 | SLA clock with business-hours awareness and pause/resume | 💲 | 🟡 | ✅ | GAP | P1 |
| F-202 | Escalation rules on milestone breach | 💲 | ✅ | ✅ | GAP | P1 |
| F-203 | Case hierarchy (parent/child) and merge | 💲 | 🟡 | ✅ | GAP | P2 |
| F-204 | Knowledge article authoring, versioning and approval | 💲 | 🟡 | ✅ | GAP | P2 |
| F-205 | Knowledge suggestion in case context | 💲 | 🟡 | ✅ | GAP | P2 |
| F-206 | Customer self-service portal with case visibility | 💲 | 💲 | ✅ | GAP | P2 |
| F-207 | CSAT survey trigger and result linkage | 💲 | ✅ | ✅ | GAP | P2 |
| F-208 | Case-to-account health and renewal-risk feed | ⛔ | ⛔ | ✅ | UNQ | P2 |

## E13 — Partner, channel and territory management

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-209 | Partner account type with tier and status | 💲 | 🟡 | ✅ | GAP | P1 |
| F-210 | Deal registration with expiry and approval | 💲 | ⛔ | ✅ | GAP | P1 |
| F-211 | Channel conflict detection on overlapping registrations | 💲 | ⛔ | ✅ | UNQ | P2 |
| F-212 | Partner portal with scoped record access | 💲 | 💲 | ✅ | GAP | P2 |
| F-213 | Partner-sourced vs. partner-influenced pipeline reporting | 💲 | ⛔ | ✅ | GAP | P1 |
| F-214 | Partner user provisioning with restricted permissions | 💲 | 🟡 | ✅ | GAP | P2 |
| F-215 | Territory assignment rules with realignment simulation | 💲 | 💲 | ✅ | GAP | P2 |
| F-216 | Territory realignment with in-flight deal reassignment | 💲 | ⛔ | ✅ | GAP | P2 |

## E14 — Workflow automation, approvals and rules engine

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-217 | Record-triggered automation (create, update, delete, undelete) | ✅ | ✅ | ✅ | TS | P0 |
| F-218 | Scheduled and time-based automation | ✅ | ✅ | ✅ | TS | P0 |
| F-219 | Visual no-code automation builder | ✅ | ✅ | ✅ | TS | P0 |
| F-220 | **Enforced business process (Blueprint-style state machine)** | 🟡 | ✅ | ✅ | PAR | P0 |
| F-221 | Validation rules with actionable error messages | ✅ | ✅ | ✅ | TS | P0 |
| F-222 | Multi-step approval processes with parallel and serial steps | ✅ | ✅ | ✅ | TS | P0 |
| F-223 | Dynamic approver determination (hierarchy, amount, matrix) | ✅ | 🟡 | ✅ | TS | P0 |
| F-224 | Approval delegation with expiry | ✅ | 🟡 | ✅ | TS | P1 |
| F-225 | Recall, reject-with-reason and resubmission handling | ✅ | ✅ | ✅ | TS | P0 |
| F-226 | Field update, record create, task create, notify actions | ✅ | ✅ | ✅ | TS | P0 |
| F-227 | Outbound webhook and HTTP callout actions | ✅ | ✅ | ✅ | TS | P0 |
| F-228 | Formula and expression language with function library | ✅ | ✅ | ✅ | TS | P0 |
| F-229 | Server-side scripting for advanced logic | ✅ | ✅ | ✅ | TS | P1 |
| F-230 | **Automation dry-run / simulation against real records** | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-231 | Automation execution log with per-step trace | 🟡 | 🟡 | ✅ | GAP | P0 |
| F-232 | Recursion and loop protection with clear diagnostics | 🟡 | 🟡 | ✅ | GAP | P0 |
| F-233 | Automation versioning with rollback to a prior version | 🟡 | ⛔ | ✅ | GAP | P1 |
| F-234 | No hard numeric limit on rule count per object | ⛔ | ⛔ | ✅ | GAP | P0 |

## E15 — Reporting, dashboards and analytics

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-235 | Ad-hoc report builder with filter, group and summarize | ✅ | ✅ | ✅ | TS | P0 |
| F-236 | Tabular, summary, matrix and joined report formats | ✅ | 🟡 | ✅ | TS | P0 |
| F-237 | Cross-object reporting with configurable report types | ✅ | 🟡 | ✅ | TS | P0 |
| F-238 | Custom summary formulas and bucketing | ✅ | 🟡 | ✅ | TS | P1 |
| F-239 | Dashboards with multiple visualization types | ✅ | ✅ | ✅ | TS | P0 |
| F-240 | Dynamic dashboards honouring the viewing user's access | ✅ | 🟡 | ✅ | TS | P0 |
| F-241 | Scheduled report and dashboard delivery by email | ✅ | ✅ | ✅ | TS | P0 |
| F-242 | Report subscription with threshold-based alerting | 💲 | 🟡 | ✅ | GAP | P1 |
| F-243 | Historical trend reporting from periodic snapshots | 💲 | 🟡 | ✅ | GAP | P1 |
| F-244 | Drill-through from any aggregate to source records | ✅ | ✅ | ✅ | TS | P0 |
| F-245 | Export to CSV/XLSX with permission and volume governance | ✅ | ✅ | ✅ | TS | P0 |
| F-246 | Export audit — who exported what, when, how many records | 💲 | 🟡 | ✅ | GAP | P0 |
| F-247 | Governed KPI definitions with a published formula per metric | ⛔ | ⛔ | ✅ | UNQ | P0 |
| F-248 | Report performance guardrails and long-query handling | 🟡 | 🟡 | ✅ | GAP | P1 |
| F-249 | Embedded charts on record pages | ✅ | ✅ | ✅ | TS | P1 |
| F-250 | Advanced BI without a separate licence | 💲 | 💲 | ✅ | GAP | P2 |

## E16 — AI copilot and agentic assistance · **differentiator**

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-251 | AI available in every licence tier | ⛔ | ⛔ | ✅ | UNQ | P0 |
| F-252 | Record summarization (account, opportunity, case) with citations | 💲 | 🟡 | ✅ | GAP | P0 |
| F-253 | Next-best-action recommendation with stated rationale | 💲 | 💲 | ✅ | GAP | P0 |
| F-254 | AI-drafted email and call-prep notes grounded in CRM records | 💲 | 💲 | ✅ | GAP | P0 |
| F-255 | Meeting/call summarization with extracted action items | 💲 | 🟡 | ✅ | GAP | P1 |
| F-256 | Conversational natural-language query over CRM data | 💲 | 💲 | ✅ | GAP | P0 |
| F-257 | Natural-language report generation | 💲 | 💲 | ✅ | GAP | P1 |
| F-258 | Predictive scoring (lead, deal, renewal, health) | 💲 | 💲 | ✅ | GAP | P0 |
| F-259 | **Every AI output cites the records that grounded it** | 🟡 | ⛔ | ✅ | UNQ | P0 |
| F-260 | **Every score decomposed into weighted contributing factors** | 🟡 | ⛔ | ✅ | UNQ | P0 |
| F-261 | Agentic multi-step task execution with human confirmation gate | 💲 | 🟡 | ✅ | GAP | P1 |
| F-262 | Agent action preview and rollback | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-263 | Tenant-scoped grounding — no cross-tenant data in any prompt | ✅ | ✅ | ✅ | TS | P0 |
| F-264 | Contractual guarantee of no training on tenant data | ✅ | 🟡 | ✅ | TS | P0 |
| F-265 | PII masking before model invocation | 💲 | 🟡 | ✅ | GAP | P0 |
| F-266 | **Full AI-off mode with no functional degradation of core CRM** | ⛔ | ⛔ | ✅ | UNQ | P0 |
| F-267 | **Self-hosted / bring-your-own model provider** | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-268 | AI usage, cost and quality telemetry visible to the tenant admin | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-269 | AI output quality evaluation harness with regression tracking | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-270 | User feedback loop on AI outputs feeding quality metrics | 🟡 | 🟡 | ✅ | GAP | P1 |

## E17 — Integration platform, APIs, webhooks and events

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-271 | Complete REST API covering every object and operation | 💲 | ✅ | ✅ | GAP | P0 |
| F-272 | Bulk/batch API for high-volume import and export | 💲 | ✅ | ✅ | GAP | P0 |
| F-273 | **No per-tier API call limits** | ⛔ | ⛔ | ✅ | GAP | P0 |
| F-274 | OpenAPI specification published and versioned | 🟡 | 🟡 | ✅ | GAP | P0 |
| F-275 | API versioning with a published deprecation policy | ✅ | 🟡 | ✅ | TS | P0 |
| F-276 | Outbound webhooks with retry, backoff and dead-letter | ✅ | ✅ | ✅ | TS | P0 |
| F-277 | Domain event stream for near-real-time integration | ✅ | 🟡 | ✅ | TS | P1 |
| F-278 | Change data capture for downstream replication | 💲 | ⛔ | ✅ | GAP | P1 |
| F-279 | Idempotency keys on all write endpoints | 🟡 | 🟡 | ✅ | GAP | P0 |
| F-280 | Named credentials / secure secret storage for outbound calls | ✅ | ✅ | ✅ | TS | P0 |
| F-281 | Email and calendar connectors (Microsoft 365, Google) | ✅ | ✅ | ✅ | TS | P0 |
| F-282 | Telephony, e-signature, ERP and marketing connector catalogue | ✅ | ✅ | ✅ | TS | P1 |
| F-283 | Integration health dashboard with failure surfacing | 🟡 | 🟡 | ✅ | GAP | P1 |
| F-284 | Sandbox-safe integration mocking | 🟡 | ⛔ | ✅ | GAP | P2 |

## E18 — Data migration and onboarding accelerators · **differentiator**

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-285 | **Native Salesforce migration importer** | n/a | 🟡 | ✅ | UNQ | P0 |
| F-286 | **Native Zoho CRM migration importer** | 🟡 | n/a | ✅ | UNQ | P0 |
| F-287 | **Native HubSpot migration importer** | 🟡 | 🟡 | ✅ | UNQ | P1 |
| F-288 | Automatic schema discovery and field-mapping proposal | ⛔ | 🟡 | ✅ | UNQ | P0 |
| F-289 | **Dry-run migration with a full pre-flight validation report** | ⛔ | ⛔ | ✅ | UNQ | P0 |
| F-290 | Post-migration reconciliation report (counts, sums, orphans) | ⛔ | ⛔ | ✅ | UNQ | P0 |
| F-291 | **Migration rollback to pre-import state** | ⛔ | ⛔ | ✅ | UNQ | P0 |
| F-292 | Incremental/delta re-sync during a parallel-run cutover | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-293 | Relationship and hierarchy preservation across objects | 🟡 | 🟡 | ✅ | GAP | P0 |
| F-294 | Attachment, note and activity history migration | 🟡 | 🟡 | ✅ | GAP | P1 |
| F-295 | Guided in-product onboarding checklist per role | 🟡 | ✅ | ✅ | TS | P0 |
| F-296 | Configuration templates by industry and company size | 🟡 | ✅ | ✅ | TS | P1 |
| F-297 | Sample-data sandbox for evaluation and training | ✅ | ✅ | ✅ | TS | P1 |
| F-298 | In-app guidance, tours and contextual help | 💲 | ✅ | ✅ | GAP | P1 |

## E19 — Administration, configuration, sandbox and release management

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-299 | Custom object creation with relationships | ✅ | ✅ | ✅ | TS | P0 |
| F-300 | Custom field creation across all supported data types | ✅ | ✅ | ✅ | TS | P0 |
| F-301 | **No numeric limit on custom objects or fields by tier** | ⛔ | ⛔ | ✅ | GAP | P0 |
| F-302 | Formula and roll-up summary fields | ✅ | 🟡 | ✅ | TS | P0 |
| F-303 | Record types with distinct layouts and picklist subsets | ✅ | ✅ | ✅ | TS | P0 |
| F-304 | Drag-and-drop page layout designer | ✅ | ✅ | ✅ | TS | P0 |
| F-305 | Conditional/dynamic field visibility on layouts | 💲 | ✅ | ✅ | GAP | P0 |
| F-306 | List view builder with filters, columns and sharing | ✅ | ✅ | ✅ | TS | P0 |
| F-307 | Global and object-specific search with relevance ranking | ✅ | ✅ | ✅ | TS | P0 |
| F-308 | **Full-copy sandbox included in base tier** | 💲 | 💲 | ✅ | GAP | P0 |
| F-309 | Configuration change set with validated promotion | ✅ | 🟡 | ✅ | TS | P0 |
| F-310 | Configuration diff between environments | 🟡 | ⛔ | ✅ | GAP | P1 |
| F-311 | Configuration version history with rollback | 🟡 | ⛔ | ✅ | GAP | P1 |
| F-312 | Setup audit trail of every administrative change | ✅ | ✅ | ✅ | TS | P0 |
| F-313 | Bulk data import wizard with validation and error file | ✅ | ✅ | ✅ | TS | P0 |
| F-314 | Mass update, mass transfer and mass delete with limits | ✅ | ✅ | ✅ | TS | P0 |
| F-315 | Recycle bin with configurable retention and restore | ✅ | ✅ | ✅ | TS | P0 |
| F-316 | Data archival policy with retrievable archive | 💲 | 🟡 | ✅ | GAP | P2 |
| F-317 | Feature flag / entitlement administration per tenant | ✅ | 🟡 | ✅ | TS | P0 |
| F-318 | **Admin task completion without vendor or consultant** | ⛔ | 🟡 | ✅ | UNQ | P0 |

## E20 — Audit, compliance, observability and data governance

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-319 | Immutable audit event for every material action | 💲 | 🟡 | ✅ | GAP | P0 |
| F-320 | Field-level change history with before/after values | 💲 | 🟡 | ✅ | GAP | P0 |
| F-321 | Read/view auditing for sensitive objects | 💲 | ⛔ | ✅ | GAP | P1 |
| F-322 | Login, session and authentication event auditing | ✅ | ✅ | ✅ | TS | P0 |
| F-323 | Export and print auditing | 💲 | 🟡 | ✅ | GAP | P0 |
| F-324 | Audit retention configurable per tenant, minimum 7 years | 💲 | 🟡 | ✅ | GAP | P0 |
| F-325 | Tamper-evident audit chain | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-326 | Encryption at rest with tenant-scoped key management | 💲 | 🟡 | ✅ | GAP | P0 |
| F-327 | Customer-managed / bring-your-own encryption keys | 💲 | ⛔ | ✅ | GAP | P2 |
| F-328 | Encryption in transit enforced end to end | ✅ | ✅ | ✅ | TS | P0 |
| F-329 | GDPR/DPDP data subject access request fulfilment | 🟡 | ✅ | ✅ | TS | P0 |
| F-330 | Right-to-erasure with cascade and audit-safe tombstoning | 🟡 | ✅ | ✅ | TS | P0 |
| F-331 | Consent record with lawful basis and provenance | 🟡 | ✅ | ✅ | TS | P0 |
| F-332 | Data retention policy per object with automated enforcement | 💲 | 🟡 | ✅ | GAP | P1 |
| F-333 | **Complete tenant data export in an open format, self-service** | 🟡 | 🟡 | ✅ | UNQ | P0 |
| F-334 | Structured application logging with correlation IDs | ✅ | 🟡 | ✅ | TS | P0 |
| F-335 | Metrics, health probes and alerting | ✅ | 🟡 | ✅ | TS | P0 |
| F-336 | Per-tenant usage and adoption telemetry for the tenant admin | 🟡 | 🟡 | ✅ | GAP | P1 |
| F-337 | Compliance evidence pack generation for audits | ⛔ | ⛔ | ✅ | UNQ | P2 |

## E21 — Mobile and offline field access

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-338 | Responsive web application on tablet and mobile | ✅ | ✅ | ✅ | TS | P0 |
| F-339 | Native mobile applications (iOS, Android) | ✅ | ✅ | ✅ | TS | P1 |
| F-340 | Offline record access with local cache | ✅ | ✅ | ✅ | TS | P2 |
| F-341 | Offline create/edit with conflict-resolving sync | 🟡 | 🟡 | ✅ | GAP | P2 |
| F-342 | Mobile-optimized quick actions and capture forms | ✅ | ✅ | ✅ | TS | P1 |
| F-343 | Voice-note capture with transcription to activity | 🟡 | 🟡 | ✅ | GAP | P2 |
| F-344 | Business-card and document scan to record | 🟡 | ✅ | ✅ | TS | P2 |
| F-345 | Push notification for assignments, approvals and alerts | ✅ | ✅ | ✅ | TS | P1 |
| F-346 | Mobile device management and remote session wipe | 💲 | 🟡 | ✅ | GAP | P2 |

## E22 — BFSI vertical pack · **differentiator**

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-347 | Relationship-manager book of clients with portfolio roll-up | 💲 | ⛔ | ✅ | UNQ | P1 |
| F-348 | Household / related-party grouping across clients | 💲 | ⛔ | ✅ | UNQ | P1 |
| F-349 | KYC onboarding workflow with document checklist | 💲 | ⛔ | ✅ | UNQ | P1 |
| F-350 | Customer risk rating with factor-based scoring | 💲 | ⛔ | ✅ | UNQ | P1 |
| F-351 | Sanctions, PEP and adverse-media screening integration | 💲 | ⛔ | ✅ | UNQ | P1 |
| F-352 | Periodic KYC review scheduling driven by risk tier | 💲 | ⛔ | ✅ | UNQ | P2 |
| F-353 | Beneficial ownership and control-structure capture | 💲 | ⛔ | ✅ | UNQ | P2 |
| F-354 | Product-holding view across the client relationship | 💲 | ⛔ | ✅ | UNQ | P1 |
| F-355 | Suitability assessment with recorded rationale | 💲 | ⛔ | ✅ | UNQ | P1 |
| F-356 | Next-best-product recommendation constrained by suitability | 💲 | ⛔ | ✅ | UNQ | P2 |
| F-357 | Consent and communication-preference register per channel | 🟡 | 🟡 | ✅ | GAP | P1 |
| F-358 | Regulator-grade communication archiving with retention hold | 💲 | ⛔ | ✅ | UNQ | P1 |
| F-359 | Complaint handling workflow with regulatory clock | 💲 | ⛔ | ✅ | UNQ | P2 |
| F-360 | Life-event and trigger-based opportunity generation | 💲 | ⛔ | ✅ | UNQ | P2 |
| F-361 | Vertical pack framework — install, configure, version, uninstall | 🟡 | ⛔ | ✅ | UNQ | P1 |

## E23 — Commodity trading vertical pack · **differentiator**

Origination only. The trading system of record stays external — see [the commodity trading pack](17-vertical-pack-commodity-trading.md) for the boundary and the connector contract.

| # | Feature | SF | Zoho | Axiom | Class | Pri |
|---|---|:--:|:--:|:--:|:--:|:--:|
| F-362 | Counterparty account extension (legal entities, approved commodities/venues) | 🟡 | ⛔ | ✅ | UNQ | P1 |
| F-363 | Master agreement register with status-based origination gating | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-364 | Credit limit and headroom display as a deal gate (read-only from CTRM) | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-365 | Origination pipelines (term, spot/cargo, tender, structured/paper) | 🟡 | ⛔ | ✅ | UNQ | P1 |
| F-366 | Tender participation management with deadline and award tracking | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-367 | Cargo/parcel enquiry capture (grade, tolerance, window, incoterm) | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-368 | Indicative formula pricing on quotes (index, differential, quotation period) | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-369 | Broker, agent and shipping intermediary relationship management | 🟡 | ⛔ | ✅ | UNQ | P2 |
| F-370 | Deal-agreed hand-off to the trading system with acknowledgement and back-reference | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-371 | Generic CTRM/ETRM connector against a published capability contract | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-372 | Commodity, grade, UoM, quality and location reference data with conversions | ⛔ | ⛔ | ✅ | UNQ | P1 |
| F-373 | Tender win rate and origination conversion analytics | ⛔ | ⛔ | ✅ | UNQ | P2 |

---

## Summary

| Metric | Count |
|---|---:|
*Counts below are machine-derived from the tables above, not estimated.*

| Metric | Count |
|---|---:|
| Total catalogued features | **373** |
| P0 (first production release) | 187 |
| P1 (next release) | 143 |
| P2 (later) | 43 |
| Table stakes (`TS`) | 156 |
| Gaps we close (`GAP`) | 147 |
| Unique to us (`UNQ`) | 67 |
| Deliberate parity adoptions (`PAR`) | 3 |

### What this distribution means

**156 table-stakes features — 42% of the catalogue — buy no differentiation whatsoever.** This is the honest cost of entering an established category, and it is the single largest risk to the plan: a great deal of undifferentiated work has to ship before the first differentiated feature matters to anyone.

**187 features are P0.** That is a large first release by any measure. [The delivery plan](15-agile-delivery-plan.md) has to confront this directly rather than assume it away — either the release is long, the team is large, or the P0 line moves.

**147 gap features** are the commercially useful middle. Each is a feature a competitor *has* but withholds from the tier a prospect is evaluating. These win comparisons cheaply, because we are not out-engineering anyone — we are out-packaging them.

**67 unique features** are the actual moat, and they concentrate in five places: explainability (`F-178`, `F-179`, `F-247`, `F-259`, `F-260`), migration and reversibility (`F-285`–`F-292`, `F-230`, `F-262`), governance depth (`F-033`, `F-034`, `F-036`, `F-037`, `F-325`), the BFSI pack (`F-347`–`F-361`) and the commodity trading pack (`F-362`–`F-373`). Everything else in this catalogue is a matter of time and money; these are the things a competitor would have to change their architecture or their business model to copy.

The two vertical packs are worth reading together: they are 27 of the 67 unique features, and they exist to prove one claim — that **industry depth is a configurable pack rather than a custom-build project**. If the pack framework (`F-361`) does not carry both a regulated-finance vertical and a commodity-trading vertical without core changes, that claim is false and the strategy needs revisiting.

**The 3 `PAR` entries are deliberate.** `F-023` (permission sets), `F-097` and `F-220` (enforced process, Blueprint-style) adopt designs the competitors got right. Copying a good design is cheaper than inventing a worse one.

---

## Related documents

- [Competitive analysis](02-competitive-analysis-salesforce-zoho.md) — the evidence behind each competitor assessment
- [Functional requirements (FRD)](03-frd.md) — each feature expanded into testable requirements
- [Epics and user stories](05-epics-and-stories.md) — delivery decomposition
- [Agile delivery plan](15-agile-delivery-plan.md) — how 176 P0 features become a release plan

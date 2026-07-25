# Functional Requirements Document (FRD)

**Product:** Axiom — Enterprise B2B CRM
**Version:** 1.0
**Status:** Baseline for build planning
**Date:** 2026-07-25

---

## 1. Purpose

This document states, in testable terms, what Axiom must do. It is the contract between product definition and engineering delivery, and the source from which [epics and user stories](05-epics-and-stories.md) and the [acceptance test catalogue](06-acceptance-tests.md) are derived.

It does **not** specify how anything is built. Technology selection is deliberately deferred — see [ADR-005](../architecture/adr/ADR-005-technology-selection-deferred.md). Requirements are therefore expressed as capabilities and observable behaviours, never as frameworks, libraries or schemas.

## 2. Scope

In scope is the capability map defined in [product scope](01-product-scope.md) §"In-scope capability map", elaborated as the 361 features in the [feature catalogue](04-feature-catalogue.md). Out of scope is listed in the same document and is not restated here.

## 3. How to read a requirement

```
FR-<MODULE>-<nnn> — <short title>   · <priority> · <feature refs> · <epic>
<Requirement statement — what the system must do.>
- Rules: <business rules that constrain the behaviour>
- On failure: <what the system does when the rule is violated>
```

- **Priority** — `P0` first production release · `P1` next release · `P2` later.
- **Feature refs** — the `F-nnn` entries in the [feature catalogue](04-feature-catalogue.md) this requirement realizes.
- **Epic** — the delivery epic in [epics and user stories](05-epics-and-stories.md).
- Requirement statements use **must** for mandatory behaviour and **should** for strongly preferred behaviour that may be traded off with explicit product sign-off.

## 4. Module codes

| Code | Module | Epic |
|---|---|---|
| `TEN` | Tenancy, identity and access | E01 |
| `SEC` | Authorization, sharing and segregation of duties | E02 |
| `MDM` | Organization, reference and master data | E03 |
| `ACC` | Accounts, contacts and relationships | E04 |
| `LED` | Leads | E05 |
| `OPP` | Opportunities and pipeline | E06 |
| `ACT` | Activity and engagement | E07 |
| `CPQ` | Products, price books, quotes and CPQ | E08 |
| `CTR` | Contracts, orders, subscriptions and renewals | E09 |
| `FCT` | Forecasting and revenue intelligence | E10 |
| `CMP` | Campaigns and marketing alignment | E11 |
| `CAS` | Cases, entitlements and SLA | E12 |
| `PTR` | Partner, channel and territory | E13 |
| `AUT` | Automation, process and approvals | E14 |
| `RPT` | Reporting and analytics | E15 |
| `AIX` | AI copilot and agentic assistance | E16 |
| `INT` | Integration platform | E17 |
| `MIG` | Migration and onboarding | E18 |
| `ADM` | Administration and release management | E19 |
| `AUD` | Audit, compliance and governance | E20 |
| `MOB` | Mobile and offline | E21 |
| `BFS` | BFSI vertical pack | E22 |
| `CTM` | Commodity trading vertical pack | E23 |

## 5. Global requirements

These apply to **every** requirement in this document. They are stated once and are not repeated per module. A feature that satisfies its module requirement but violates a global requirement is not complete.

**FR-GLOBAL-001 — Tenant isolation** · P0 · `F-001` · E01
Every data access must be scoped to exactly one tenant, enforced server-side, independent of any client-supplied tenant identifier.
- Rules: tenant context derives from the authenticated session, never from a request parameter or header the caller controls.
- On failure: cross-tenant access attempts are denied, return no data or record existence signal, and raise a security audit event.

**FR-GLOBAL-002 — Server-side authorization** · P0 · `F-021`–`F-032` · E02
Every read, write, export and action must be authorized server-side against the acting user's effective permissions. Client-side hiding of controls is a usability aid and must never be the enforcement mechanism.
- On failure: denial is returned without disclosing whether the record exists, and is audited.

**FR-GLOBAL-003 — Input validation** · P0 · — · all
All inputs must be validated for type, range, format, referential integrity and business-rule conformance before persistence.
- On failure: the request is rejected atomically with a machine-readable error code, the offending field path, and a message stating what to do — not merely what went wrong.

**FR-GLOBAL-004 — Optimistic concurrency** · P0 · — · all
Every mutable record must carry a version. Updates must supply the version the edit was based on.
- On failure: a stale version is rejected as a conflict, and the response identifies which fields changed and by whom, so the user can merge rather than blindly retry.

**FR-GLOBAL-005 — Audit of material actions** · P0 · `F-319`, `F-320` · E20
Every create, update, delete, state transition, permission change, export and approval must produce an immutable audit event recording actor, timestamp, action, before/after values, source (UI/API/automation/AI), reason where required, and a correlation ID.

**FR-GLOBAL-006 — Idempotency** · P0 · `F-279` · E17
Every write endpoint must accept an idempotency key and must return the original result for a repeated key within the retention window, without duplicating the effect.

**FR-GLOBAL-007 — Soft delete and recovery** · P0 · `F-315` · E19
Deletion must be recoverable for a configurable retention period before permanent removal, except where erasure is compelled by a data-subject request under `FR-AUD-008`.

**FR-GLOBAL-008 — Accessibility** · P0 · — · all
All user interfaces must meet WCAG 2.2 Level AA: full keyboard operability, visible focus, programmatic labels, and no reliance on colour alone to convey meaning.

**FR-GLOBAL-009 — Localization** · P1 · `F-046` · E03
All user-facing text, dates, numbers, currencies and name formats must be localizable without code change. No user-facing string may be hard-coded.

**FR-GLOBAL-010 — Observability** · P0 · `F-334`, `F-335` · E20
Every request must carry a correlation ID through all layers and downstream calls, and must emit structured logs and metrics sufficient to diagnose failure without exposing personal or credential data.

**FR-GLOBAL-011 — No tier-gated security or interoperability** · P0 · `F-273`, `F-301`, `F-308` · E19
SSO, MFA, audit log, field-level security, encryption, full API access, sandbox and complete data export must be available in every commercial tier. No requirement in this document may be satisfied by a tier-gated implementation of these capabilities.

---

## 6. TEN — Tenancy, identity and access

**FR-TEN-001 — Tenant provisioning** · P0 · `F-001` · E01
The platform must provision a tenant with an isolated data scope, an initial administrator, a default configuration baseline and an entitlement set.
- Rules: tenant identifier is immutable; provisioning is idempotent by request key.
- On failure: partial provisioning must roll back completely, leaving no orphaned tenant scope.

**FR-TEN-002 — Tenant lifecycle states** · P0 · `F-001` · E01
A tenant must support `provisioning`, `active`, `suspended`, `terminating` and `terminated` states with defined permitted transitions.
- Rules: a suspended tenant permits administrator login and data export but blocks all business writes; termination requires an explicit confirmation and a retention period before data destruction.

**FR-TEN-003 — Local authentication** · P0 · `F-002`, `F-018` · E01
Users must be able to authenticate with a username and password subject to a configurable policy (length, complexity, history, expiry, breached-password rejection).
- Rules: credentials are stored only as salted, computationally-hard hashes; failed attempts trigger progressive delay then lockout.
- On failure: authentication errors must not reveal whether the username exists.

**FR-TEN-004 — SAML 2.0 SSO** · P0 · `F-003` · E01
A tenant administrator must be able to configure SAML 2.0 SSO, including metadata exchange, certificate rotation, attribute mapping and a test-connection facility that validates configuration before activation.
- On failure: a misconfigured IdP must not lock administrators out; a break-glass local path per `FR-TEN-012` remains available.

**FR-TEN-005 — OpenID Connect SSO** · P0 · `F-004` · E01
The platform must support OIDC authorization-code flow with PKCE, discovery, and claim-to-attribute mapping.

**FR-TEN-006 — Multiple identity providers** · P1 · `F-005` · E01
A tenant must support more than one concurrent identity provider, with routing by email domain or explicit user assignment.

**FR-TEN-007 — SCIM provisioning** · P0 · `F-006`, `F-007` · E01
The platform must expose SCIM 2.0 endpoints for user and group create, update, deactivate and delete, and must support just-in-time provisioning from an SSO assertion.
- Rules: deprovisioning deactivates and revokes sessions; it must never hard-delete a user who owns records, to preserve referential and audit integrity.

**FR-TEN-008 — Multi-factor authentication** · P0 · `F-008`, `F-009` · E01
The platform must support TOTP and WebAuthn/passkey second factors, with enforcement policies targetable by role, profile, permission or network context.
- Rules: recovery codes are issued once, stored hashed, and single-use.

**FR-TEN-009 — Step-up authentication** · P0 · `F-010` · E01
Controlled actions (permission grant, bulk export, mass delete, break-glass access, tenant termination, encryption key operations) must require re-authentication within a short freshness window regardless of session age.
- On failure: the action is refused and the failed step-up is audited.

**FR-TEN-010 — Session governance** · P0 · `F-011`, `F-012` · E01
Sessions must honour configurable absolute lifetime, idle timeout and concurrent-session limits. Administrators must be able to list active sessions and revoke any of them, with immediate effect.

**FR-TEN-011 — Support impersonation** · P0 · `F-016` · E01
A permitted operator must be able to act as a user for support purposes, subject to tenant-level consent policy.
- Rules: impersonation is visibly indicated throughout the session; every action records both the impersonator and the impersonated identity; the operator cannot use impersonation to grant itself permissions.

**FR-TEN-012 — Break-glass access** · P1 · `F-017` · E01
Emergency administrative access must require a case reference and justification, be time-boxed, expire automatically, and notify tenant administrators and the audit channel on use.

**FR-TEN-013 — Service credentials** · P0 · `F-015` · E01
Integrations must authenticate via OAuth 2.0 client credentials or scoped API tokens, with per-credential scope, expiry, rotation and revocation, and last-used telemetry.

**FR-TEN-014 — Access restriction by network** · P1 · `F-013`, `F-014` · E01
Administrators must be able to restrict login by IP range, and to require additional verification from unrecognized devices.

**FR-TEN-015 — Login branding** · P1 · `F-019` · E01
Each tenant must be able to apply its own logo, colours and support contact to the login experience without affecting other tenants.

---

## 7. SEC — Authorization, sharing and segregation of duties

**FR-SEC-001 — Role hierarchy** · P0 · `F-021` · E02
The platform must support a role hierarchy in which a role inherits read access to records owned by roles beneath it, configurable per object.
- Rules: the hierarchy must be acyclic; depth is not artificially limited.
- On failure: an attempted cycle is rejected naming the conflicting roles.

**FR-SEC-002 — Profiles** · P0 · `F-022` · E02
Every user must have exactly one profile establishing baseline object permissions (create/read/edit/delete/view-all/modify-all) and system permissions.

**FR-SEC-003 — Permission sets** · P0 · `F-023`, `F-024` · E02
Additional permissions must be grantable through permission sets assignable to users independently of profile, and groupable with the ability to mute specific permissions within a group.
- Rules: effective permission is the union of profile and assigned sets, minus explicit mutes.

**FR-SEC-004 — Organization-wide defaults** · P0 · `F-025` · E02
Each object must have a tenant-level default sharing setting of `private`, `read-only` or `read-write`, from which all sharing is additive.
- Rules: sharing widens access; it never narrows it. Narrowing is achieved only by lowering the org-wide default.

**FR-SEC-005 — Sharing rules** · P0 · `F-026`, `F-027` · E02
Administrators must be able to define criteria-based and owner-based rules granting read or read-write access to roles, groups or territories.
- Rules: rule evaluation must be deterministic and recomputed on change of owner, criteria field or role membership.

**FR-SEC-006 — Manual and team sharing** · P1 · `F-028`, `F-029` · E02
Users with sufficient rights must be able to share an individual record with a user, group or team, optionally with an expiry date after which access lapses automatically.

**FR-SEC-007 — Field-level security** · P0 · `F-031` · E02
Read and edit permissions must be configurable per field per profile and permission set, enforced uniformly in UI, API, reports, exports, search and AI grounding.
- On failure: a hidden field must be absent from responses entirely, not returned as null — absence and emptiness must not be conflated.

**FR-SEC-008 — Sensitive field masking** · P0 · `F-032` · E02
Designated sensitive fields must support masked display (partial reveal) with full value access requiring a separate permission and producing a read-audit event.

**FR-SEC-009 — Segregation of duties** · P0 · `F-033` · E02
The platform must maintain a configurable set of conflicting permission pairs and must prevent a single user holding both sides of a conflict.
- Rules: conflicts are evaluated on grant, on profile change and on a scheduled sweep; existing violations are reported rather than silently tolerated.
- On failure: the grant is blocked, naming the specific conflict and the existing grant that causes it.

**FR-SEC-010 — Maker-checker** · P0 · `F-034` · E02
For designated controlled actions, the user who initiates must not be able to approve.
- Rules: applies transitively through delegation — a delegate of the initiator also cannot approve.
- On failure: the approval attempt is refused and audited as a segregation violation.

**FR-SEC-011 — Delegated administration** · P1 · `F-035` · E02
A delegated administrator must be able to manage users, roles and configuration only within an assigned branch of the organization, and must not be able to escalate their own privileges.

**FR-SEC-012 — Time-bound access** · P1 · `F-036` · E02
Any permission or role assignment must support an optional expiry, after which it ceases to be effective without requiring a login cycle or administrator action.

**FR-SEC-013 — Access explainer** · P1 · `F-038` · E02
For any user–record pair, an authorized administrator must be able to see exactly why access is or is not granted, enumerating every rule that contributes.

**FR-SEC-014 — Access recertification** · P2 · `F-037` · E02
The platform must support periodic access review campaigns in which reviewers confirm or revoke grants, with an auditable outcome per grant and automatic revocation of items not confirmed by a deadline.

**FR-SEC-015 — Export as a distinct permission** · P0 · `F-040`, `F-323` · E02
The right to export or print records must be a permission distinct from the right to read them, and must be independently grantable, limitable by volume, and audited.

---

## 8. MDM — Organization, reference and master data

**FR-MDM-001 — Business unit modelling** · P0 · `F-041` · E03
A tenant must be able to define legal entities and business units, associate users and records with them, and scope reporting and sharing by them.

**FR-MDM-002 — Multi-currency** · P0 · `F-042`, `F-113` · E03
The platform must support a tenant corporate currency plus any number of active currencies, with dated exchange rates.
- Rules: monetary amounts store both the transaction currency amount and the converted corporate amount with the rate and rate date used. A stored conversion is never silently recomputed.

**FR-MDM-003 — Dated exchange rates** · P1 · `F-043` · E03
Conversions must be able to use the rate effective at a record-defined date (close date, order date) rather than the current rate, configurable per object.

**FR-MDM-004 — Fiscal calendar** · P0 · `F-044` · E03
Administrators must be able to define standard or custom fiscal years, quarters and periods, including 4-4-5 style calendars, used consistently by forecasting, quota and reporting.

**FR-MDM-005 — Business hours and holidays** · P0 · `F-045` · E03
The platform must support named business-hours definitions with holidays and time zones, used by SLA clocks, cadences and scheduled automation.

**FR-MDM-006 — Governed picklists** · P0 · `F-047`, `F-048` · E03
Picklist values must be centrally governed, reusable across objects as global value sets, and support dependent (cascading) relationships.
- Rules: deactivating a value must not corrupt existing records; the value remains readable and reportable but is not selectable for new entry.

**FR-MDM-007 — Reference data effective dating** · P1 · `F-053` · E03
Designated reference data must support valid-from and valid-to dates, so historical records resolve against the values that were in force at their time.

**FR-MDM-008 — Territory model** · P1 · `F-050`, `F-051` · E03
Administrators must be able to define a territory hierarchy with assignment rules, preview the resulting assignment against live data before activation, and activate as a version.
- Rules: activation is atomic; the prior model version is retained and restorable.

**FR-MDM-009 — Quota management** · P1 · `F-052`, `F-185` · E03
Quotas must be definable by user, team, territory and fiscal period, in revenue or quantity, with versioning and an audit of every change.

**FR-MDM-010 — Master data change control** · P1 · `F-054` · E03
Changes to designated master data must be routable through an approval process before taking effect.

---

## 9. ACC — Accounts, contacts and relationships

**FR-ACC-001 — Account record** · P0 · `F-056` · E04
The platform must maintain accounts with configurable layouts, record types, ownership, business unit and currency.

**FR-ACC-002 — Contact record** · P0 · `F-057`, `F-066` · E04
The platform must maintain contacts with multiple typed addresses, multiple typed communication channels, and a primary account association.

**FR-ACC-003 — Account hierarchy** · P0 · `F-058` · E04
Accounts must support a multi-level parent/child hierarchy with a derivable ultimate parent.
- Rules: the hierarchy must be acyclic and depth is not artificially limited.
- On failure: an attempted cycle is rejected naming the accounts involved.

**FR-ACC-004 — Hierarchy roll-up** · P0 · `F-059` · E04
Pipeline value, closed revenue, open cases and activity recency must be viewable both for an account alone and rolled up across its hierarchy.
- Rules: roll-ups respect the viewing user's record access — a user must never infer the existence of records they cannot see from an aggregate. Where access restricts the roll-up, this must be indicated rather than silently under-reported.

**FR-ACC-005 — Multi-account contact relationships** · P1 · `F-060` · E04
A contact must be relatable to more than one account with a role, influence level and active/inactive status per relationship.

**FR-ACC-006 — Buying group** · P1 · `F-061`, `F-102` · E04
The platform must support a named buying group per opportunity or account, listing members with role (economic buyer, champion, technical evaluator, blocker), influence and engagement status.

**FR-ACC-007 — Relationship map** · P1 · `F-062` · E04
Contacts within an account must be visualizable as a relationship/reporting map showing hierarchy, influence and engagement recency.

**FR-ACC-008 — Duplicate detection** · P0 · `F-069`, `F-080` · E04
On create and update, the platform must detect potential duplicates using configurable matching rules including fuzzy name, domain, email and phone matching, across accounts, contacts and leads.
- Rules: rules are configurable as blocking or warning; the acting user sees the candidate matches and their match confidence.

**FR-ACC-009 — Merge with survivorship** · P0 · `F-070` · E04
Users with permission must be able to merge duplicate records, choosing the surviving value field by field.
- Rules: all related records (activities, opportunities, cases) reparent to the survivor; the merge is recorded as a single audited event listing losing record IDs and every field decision.

**FR-ACC-010 — Merge reversal** · P2 · `F-071` · E04
A merge must be reversible within a configurable retention window, restoring the losing records and their relationships to their pre-merge state.

**FR-ACC-011 — Consent and suppression** · P0 · `F-067`, `F-068` · E04
Each contact must carry per-channel consent state with lawful basis, source and timestamp. Suppression must be enforced at the point of send or dial across every channel, including cadences and integrations.
- On failure: a send or dial to a suppressed contact must be blocked, not merely warned, and the block must be audited.

**FR-ACC-012 — Account 360 timeline** · P0 · `F-073` · E04
Every account and contact must present a unified chronological timeline of activities, opportunities, quotes, cases, campaign membership and notable field changes, filterable by type and date, and honouring record and field permissions.

**FR-ACC-013 — Enrichment** · P1 · `F-072` · E04
The platform must support enrichment of accounts and contacts from a configured external provider on create and on refresh.
- Rules: enrichment never silently overwrites a user-entered value; conflicts are presented for resolution, and provenance is recorded per field.

**FR-ACC-014 — Account health** · P1 · `F-065` · E04
Accounts must carry a health indicator computed from engagement recency, open cases and SLA breaches, renewal proximity and product adoption signals, presented with its contributing factors and their weights.

---

## 10. LED — Leads

**FR-LED-001 — Lead record and status model** · P0 · `F-076` · E05
The platform must maintain leads with a configurable status model and defined terminal states (converted, disqualified, recycled).

**FR-LED-002 — Web form capture** · P0 · `F-077` · E05
Administrators must be able to generate an embeddable capture form mapping to lead fields, with bot protection and configurable required fields.
- On failure: a rejected submission must return a user-comprehensible message and must not lose the submitted data silently.

**FR-LED-003 — API and bulk ingestion** · P0 · `F-078` · E05
Leads must be creatable individually and in bulk via API, with per-record validation results returned for a batch rather than an all-or-nothing failure.

**FR-LED-004 — Duplicate handling on ingestion** · P0 · `F-080` · E05
Inbound leads must be checked against existing leads, contacts and accounts, with configurable behaviour: create, merge, attach to existing, or route for review.

**FR-LED-005 — Lead-to-account matching** · P1 · `F-083` · E05
Inbound leads must be matched to existing accounts by domain and name similarity, and the match presented with confidence for confirmation.

**FR-LED-006 — Rule-based scoring** · P0 · `F-081` · E05
Administrators must be able to define scoring rules over lead attributes and behaviours, producing a score with a visible breakdown of contributing rules.

**FR-LED-007 — Predictive scoring** · P0 · `F-082` · E05
The platform must produce a predictive conversion likelihood per lead.
- Rules: the score must be accompanied by its top contributing factors and their direction. A score presented without explanation does not satisfy this requirement.

**FR-LED-008 — Assignment and routing** · P0 · `F-084`, `F-085` · E05
Leads must be assignable by configurable rules evaluating territory, segment, product interest, round-robin and owner capacity, with fall-through to a queue.
- Rules: rules evaluate in defined order; the first match wins; the matched rule is recorded on the lead.

**FR-LED-009 — Speed-to-lead SLA** · P0 · `F-086` · E05
A first-response SLA timer must start on assignment, pause outside business hours, and escalate on breach to a configured recipient.

**FR-LED-010 — Qualification framework** · P1 · `F-087` · E05
Administrators must be able to configure a qualification framework (BANT, CHAMP or custom) whose fields are captured on the lead and carried to the opportunity on conversion.

**FR-LED-011 — Conversion** · P0 · `F-088`, `F-089` · E05
Conversion must create or link an account, a contact and optionally an opportunity in a single atomic operation, with administrator-configured field mapping including custom fields.
- Rules: activities, notes and campaign membership transfer to the resulting records; the lead becomes read-only and retains links to what it became.
- On failure: no partial conversion may persist.

**FR-LED-012 — Disqualification and recycling** · P0 · `F-090`, `F-091` · E05
Disqualification must require a reason from a governed taxonomy. A disqualified lead must be returnable to nurture with a re-engagement date.

---

## 11. OPP — Opportunities and pipeline

**FR-OPP-001 — Opportunity record** · P0 · `F-094` · E06
The platform must maintain opportunities with amount, currency, close date, stage, owner, account, pipeline and record type.

**FR-OPP-002 — Multiple pipelines** · P0 · `F-095`, `F-096` · E06
Administrators must be able to define multiple pipelines, each with its own ordered stages, each stage carrying a probability, forecast category and exit criteria.

**FR-OPP-003 — Enforced stage gating** · P0 · `F-097` · E06
Advancement to the next stage must be blocked until every configured exit criterion for the current stage is satisfied.
- Rules: criteria may reference field values, related-record existence (e.g. an identified economic buyer), completed activities or approval state. Criteria are versioned; an in-flight opportunity continues under the version it entered.
- On failure: advancement is refused and the response names each unsatisfied criterion and the specific action needed to satisfy it.

**FR-OPP-004 — Backward and skip transitions** · P0 · `F-096`, `F-107` · E06
Moving backward or skipping stages must be permitted only where configured, must capture a reason, and must be recorded in stage history.

**FR-OPP-005 — Line items** · P0 · `F-099` · E06
Opportunities must support line items drawn from a price book, with quantity, list price, sale price, discount and computed totals.
- Rules: totals recompute deterministically; a manually overridden total must be visibly flagged as overridden with the computed value retained.

**FR-OPP-006 — Revenue splits** · P1 · `F-100` · E06
The platform must support splitting opportunity revenue across users for credit, with revenue splits summing to 100% and overlay splits unconstrained.
- On failure: a revenue split set that does not total 100% is rejected, naming the shortfall.

**FR-OPP-007 — Competitor tracking** · P1 · `F-103` · E06
Opportunities must record competitors present, their position, and at closure whether the deal was lost to a specific competitor.

**FR-OPP-008 — Qualification methodology** · P1 · `F-104` · E06
Administrators must be able to enable a structured qualification framework (MEDDICC, SPICED or custom) whose completeness is visible as a score and usable as a stage exit criterion.

**FR-OPP-009 — Risk signals** · P0 · `F-105` · E06
The platform must surface deal risk indicators — engagement gap, single-threaded relationship, close-date slippage, stalled stage duration, missing decision-maker, competitor presence — each stating the observation, why it matters and a recommended action.

**FR-OPP-010 — Close date and slippage** · P0 · `F-106` · E06
Every close-date change must be recorded with old value, new value, actor, timestamp and reason where the change moves the date beyond the current period.
- Rules: cumulative slippage is a reportable attribute of the opportunity.

**FR-OPP-011 — Stage history** · P0 · `F-107` · E06
The platform must retain complete stage history with entry and exit timestamps, actor and duration per stage.

**FR-OPP-012 — Closure** · P0 · `F-108` · E06
Closing an opportunity as won or lost must require a reason from a governed taxonomy, and for losses, optionally the winning competitor.
- Rules: a closed opportunity becomes read-only except through the controlled reopen path.

**FR-OPP-013 — Controlled reopen** · P1 · `F-115` · E06
Reopening a closed opportunity must require a permission, capture a reason, restore editability, and be recorded so that historical reporting on the original closure remains intact.

**FR-OPP-014 — Pipeline board** · P0 · `F-109` · E06
Users must be able to view and advance opportunities on a stage-column board, with the same server-side validation as the record form.
- On failure: a blocked drag must return the opportunity to its original column and state why.

**FR-OPP-015 — Pipeline movement view** · P1 · `F-110`, `F-186` · E06
Users must be able to compare the pipeline as of two points in time, showing what was added, advanced, slipped, shrunk, grown, won and lost between them.

**FR-OPP-016 — Recurring revenue** · P1 · `F-112` · E06
Opportunities must support recurring revenue modelling with term, billing frequency, and derived ARR/TCV alongside one-time amounts.

---

## 12. ACT — Activity and engagement

**FR-ACT-001 — Tasks** · P0 · `F-116` · E07
Users must be able to create tasks with subject, due date, priority, owner, related record and reminder, and complete them with an outcome.

**FR-ACT-002 — Events and meetings** · P0 · `F-117` · E07
The platform must support scheduled events with attendees (internal users and contacts), location, related records and post-meeting outcome capture.

**FR-ACT-003 — Calls** · P0 · `F-118` · E07
Calls must be loggable with direction, duration, disposition from a governed list, outcome notes and related records.

**FR-ACT-004 — Unified timeline** · P0 · `F-120`, `F-133` · E07
All activity types must appear on a single chronological timeline on every related record, with derived metrics: last contacted, days since last activity, activity count by period.

**FR-ACT-005 — Email and calendar integration** · P0 · `F-121` · E07
The platform must integrate bidirectionally with Microsoft 365 and Google Workspace for email send/receive and calendar read/write.

**FR-ACT-006 — Passive activity capture** · P0 · `F-122`, `F-123` · E07
Once connected, emails and meetings involving known contacts must be captured and related automatically, without the user logging anything.
- Rules: matching is by participant email address to contact/lead; ambiguous matches are presented for one-click correction; the match confidence and basis are recorded.
- On failure: an unmatchable item is retained in a review queue rather than discarded.

**FR-ACT-007 — Capture privacy controls** · P0 · `F-124` · E07
Users must be able to exclude specific domains, addresses and individual items from capture, and administrators must be able to configure tenant-wide exclusions.
- Rules: user consent to capture must be explicit, revocable, and recorded. Private items are never stored, not merely hidden.

**FR-ACT-008 — Email templates** · P0 · `F-125` · E07
Templates must support merge fields resolving against the recipient and related records, versioning, folder organization and permission-scoped sharing.
- On failure: a merge field that cannot resolve must block the send with a clear message rather than sending a visibly broken message.

**FR-ACT-009 — Engagement tracking** · P1 · `F-126`, `F-132` · E07
Email opens, link clicks and replies must be trackable, subject to consent and tenant policy, and must generate engagement signals surfaced to the record owner.

**FR-ACT-010 — Cadences** · P1 · `F-127` · E07
Administrators must be able to define multi-step outreach cadences combining email, call and task steps with delays and branching on engagement outcome. Users must be able to enrol and unenrol leads and contacts, individually and in bulk.
- Rules: enrolment respects consent and suppression per `FR-ACC-011`; a reply automatically exits the target from the cadence unless configured otherwise.

**FR-ACT-011 — Telephony integration** · P1 · `F-129`, `F-130` · E07
The platform must support click-to-dial, inbound screen-pop to the matched record, automatic call logging, and storage of a reference to the recording held by the telephony provider.

**FR-ACT-012 — Conversation intelligence** · P2 · `F-131` · E07
Where call transcripts are available, the platform must extract topics, competitor mentions, next steps and talk-ratio metrics, linked to the opportunity.

---

## 13. CPQ — Products, price books, quotes and CPQ

**FR-CPQ-001 — Product catalogue** · P0 · `F-135` · E08
The platform must maintain products with code, description, category, attributes, active status and lifecycle dates.

**FR-CPQ-002 — Price books** · P0 · `F-136`, `F-137` · E08
Multiple price books must be supported, scoped by currency, business unit and customer segment, with entries carrying effective-from and effective-to dates.
- Rules: exactly one price must resolve for a given product, price book and date. Overlapping effective ranges for the same product in the same book are rejected at save.

**FR-CPQ-003 — Quote creation and sync** · P0 · `F-138` · E08
Quotes must be creatable from an opportunity, inheriting account, contact and line items, with a controlled sync of the accepted quote's totals back to the opportunity.

**FR-CPQ-004 — Quote versioning** · P0 · `F-139` · E08
Each material change to a sent quote must produce a new version, with the prior version retained and a field-level comparison available between any two versions.
- Rules: only one version may be active at a time; a superseded version cannot be accepted.

**FR-CPQ-005 — Bundles and configuration rules** · P1 · `F-140`, `F-141` · E08
The platform must support product bundles with optional and required components, and configuration rules expressing inclusion, exclusion and requirement constraints.
- On failure: an invalid configuration is blocked with a message naming the violated constraint and the options that would resolve it.

**FR-CPQ-006 — Guided selling** · P1 · `F-142` · E08
Administrators must be able to define a question sequence whose answers filter and recommend products.

**FR-CPQ-007 — Pricing methods** · P1 · `F-143`, `F-144`, `F-148` · E08
The platform must support list, tiered, volume, block, percent-of-total and attribute-based pricing, plus term-based subscription pricing with proration.
- Rules: the applied pricing method and every adjustment must be itemized on the line so the final price is fully derivable.

**FR-CPQ-008 — Contracted pricing** · P1 · `F-145` · E08
Customer-specific negotiated prices must override price book prices for the specified account and period.

**FR-CPQ-009 — Discounting and approval** · P0 · `F-146`, `F-151` · E08
Discounts must be applicable at line and quote level, with thresholds triggering approval routed by amount, margin, product and role.
- Rules: a quote pending discount approval cannot be sent to a customer.
- On failure: attempting to send an unapproved quote is refused, naming the approval that is outstanding and its current approver.

**FR-CPQ-010 — Margin visibility and floor** · P1 · `F-149` · E08
Where cost data is available, quotes must show margin at line and total level, and must enforce a configurable margin floor requiring approval to breach.

**FR-CPQ-011 — Document generation** · P0 · `F-150` · E08
The platform must generate a quote document from a branded template with merge fields, producing a stable, versioned artefact attached to the quote.

**FR-CPQ-012 — E-signature** · P1 · `F-152` · E08
The platform must send a quote document to a configured e-signature provider and reflect envelope state (sent, viewed, signed, declined, expired) on the quote, storing a reference to the executed document.

**FR-CPQ-013 — Quote lifecycle** · P1 · `F-153`, `F-154` · E08
Quotes must support draft, in-approval, sent, accepted, rejected and expired states, with automatic expiry and reminders, and conversion of an accepted quote to an order.

**FR-CPQ-014 — Price change impact preview** · P2 · `F-155` · E08
Before activating a new price book version, an administrator must be able to preview which open quotes and opportunities would be affected and by how much.

---

## 14. CTR — Contracts, orders, subscriptions and renewals

**FR-CTR-001 — Contract record** · P1 · `F-156`, `F-168` · E09
The platform must maintain contracts with parties, term dates, value, status, owner and attached executed documents with version history.

**FR-CTR-002 — Contract line items** · P1 · `F-157` · E09
Contracts must carry line items linked to products, quantities, prices and term, forming the basis for renewal and entitlement.

**FR-CTR-003 — Amendment and versioning** · P1 · `F-159` · E09
Contract changes must create a new version with an explicit change reason and a derivable difference from the prior version.
- Rules: the original version remains immutable and retrievable; reporting can be run as-of any point in time.

**FR-CTR-004 — Orders** · P1 · `F-160` · E09
Orders must be creatable from accepted quotes or directly, with order products, fulfilment status and a hand-off state to the downstream ERP or billing system.

**FR-CTR-005 — Subscription lifecycle** · P1 · `F-161`, `F-163` · E09
Subscriptions must support activation, suspension, upgrade, downgrade, cancellation, mid-term amendment and co-termination, each producing a dated change record.

**FR-CTR-006 — Renewal generation** · P1 · `F-162` · E09
A renewal opportunity must be generated automatically at a configurable lead time before contract or subscription expiry, pre-populated from the expiring terms and assigned by rule.

**FR-CTR-007 — Entitlements** · P1 · `F-164` · E09
Contracts must be able to create entitlements defining the support level, covered products, SLA terms and validity period consumed by case management.

**FR-CTR-008 — Installed base** · P1 · `F-165` · E09
The platform must maintain an asset/installed-base register per account, derived from fulfilled orders, supporting service, renewal and expansion motions.

**FR-CTR-009 — Churn capture** · P1 · `F-166` · E09
Non-renewal, downgrade and cancellation must require a reason from a governed taxonomy, with the lost value quantified and reportable.

**FR-CTR-010 — Renewal risk** · P2 · `F-167` · E09
Contracts approaching renewal must carry a risk indicator built from usage, support history, engagement and payment signals, presented with contributing factors.

**FR-CTR-011 — ERP hand-off** · P1 · `F-170` · E09
Order and contract data must be transmittable to a downstream financial system with a recorded hand-off state, retry on failure, and a reconciliation view of what has and has not been accepted downstream.

---

## 15. FCT — Forecasting and revenue intelligence

**FR-FCT-001 — Forecast categories** · P0 · `F-171` · E10
Every opportunity must map to a forecast category (omitted, pipeline, best case, commit, closed) derived from stage but individually overridable with a reason.

**FR-FCT-002 — Hierarchy roll-up** · P0 · `F-172` · E10
Forecasts must roll up through the management hierarchy, with each level able to see the contributing amounts from the level below, subject to record access.

**FR-FCT-003 — Manager judgment** · P0 · `F-173` · E10
A manager must be able to submit a forecast number that differs from the arithmetic roll-up.
- Rules: the override requires a reason; both the roll-up and the override are retained; the variance is visible and reportable at every level above.

**FR-FCT-004 — Submission and snapshot** · P0 · `F-174`, `F-186` · E10
Forecast submission must lock and snapshot the forecast for the period, retaining the full contributing detail so a historical forecast can be reconstructed exactly as submitted.

**FR-FCT-005 — Forecast explainability** · P0 · `F-178` · E10
Any forecast number, at any level of the hierarchy, must be decomposable to the individual opportunities that constitute it, and further to the fields and judgments that produced each contribution.
- Rules: this includes AI-generated predictions — a prediction that cannot be decomposed must not be presented as a forecast number.

**FR-FCT-006 — Movement waterfall** · P0 · `F-179` · E10
The platform must show, between any two snapshots, a waterfall accounting for the entire change: new pipeline, advanced, slipped out, pulled in, value increased, value decreased, won, lost. The components must reconcile exactly to the net change.
- On failure: any unreconciled residual must be shown explicitly rather than absorbed into another category.

**FR-FCT-007 — AI prediction** · P1 · `F-177` · E10
The platform must produce a predicted period outcome with a confidence interval, presented alongside — never in place of — the submitted human forecast, with its contributing factors disclosed.

**FR-FCT-008 — Scenario modelling** · P1 · `F-180` · E10
Users must be able to model the forecast effect of specified changes (deals slipping, closing, changing value) without altering the underlying records.

**FR-FCT-009 — Pipeline analytics** · P0 · `F-181`, `F-182` · E10
The platform must report pipeline coverage ratio, sales velocity, stage conversion rates, average stage duration and average deal size, sliceable by period, segment, product, territory and owner.

**FR-FCT-010 — Win/loss analysis** · P0 · `F-183` · E10
Closed opportunities must be analysable by reason, competitor, segment, product, source and owner, with win rate computed on a stated, published basis.

**FR-FCT-011 — Forecast accuracy** · P1 · `F-184` · E10
The platform must track submitted forecasts against actual results over time, per forecasting user, producing a bias and accuracy measure.

**FR-FCT-012 — Quota attainment** · P0 · `F-175` · E10
Attainment must be computed against assigned quota for the period at every hierarchy level, with the credit basis (closed revenue, split revenue) explicitly stated.

---

## 16. CMP — Campaigns and marketing alignment

**FR-CMP-001 — Campaign record** · P1 · `F-187` · E11
The platform must maintain campaigns with type, status, dates, budgeted and actual cost, target and parent campaign for hierarchy.

**FR-CMP-002 — Campaign members** · P1 · `F-188` · E11
Leads and contacts must be addable to campaigns individually, in bulk and by rule, with a per-campaign member status progression.

**FR-CMP-003 — Segment builder** · P1 · `F-189` · E11
Users must be able to build audience segments from any combination of account, contact, lead, opportunity and activity criteria, save them, and see live membership counts.

**FR-CMP-004 — Marketing platform sync** · P1 · `F-190` · E11
Segments and campaign membership must be synchronizable to an external marketing automation platform, with status returned and reconciled.

**FR-CMP-005 — Attribution** · P1 · `F-191`, `F-192` · E11
The platform must attribute pipeline and revenue to campaigns using first-touch, last-touch and configurable multi-touch models, and must be able to present the models side by side.
- Rules: the attribution model and its calculation version must be stated on every attributed figure. An attribution number without its model named is not a valid output.

**FR-CMP-006 — MQL hand-off** · P1 · `F-193` · E11
The platform must support a marketing-qualified to sales-accepted transition with an acceptance SLA, an acceptance or rejection decision with reason, and reporting on hand-off quality.

**FR-CMP-007 — Campaign ROI** · P1 · `F-194` · E11
Campaigns must report sourced pipeline, influenced pipeline, closed revenue, cost and return, with the sourcing definition published.

---

## 17. CAS — Cases, entitlements and SLA

**FR-CAS-001 — Case record** · P1 · `F-197` · E12
The platform must maintain cases with account, contact, asset, type, priority, severity, status, owner and resolution.

**FR-CAS-002 — Multi-channel capture** · P1 · `F-198` · E12
Cases must be creatable from email, web form, portal, API and manually, with the origin recorded and email threading preserved on the case.

**FR-CAS-003 — Assignment and queues** · P1 · `F-199` · E12
Cases must route by configurable rules to users or queues, with claim/release from a queue and workload-aware distribution.

**FR-CAS-004 — Entitlement-driven SLA** · P1 · `F-200`, `F-201` · E12
Response and resolution targets must derive from the account's active entitlement and case severity.
- Rules: SLA clocks respect the entitlement's business hours, pause on customer-pending status, and resume on customer response. Every pause and resume is recorded with actor and reason.
- On failure: where no entitlement matches, a configured default applies and the case is flagged as uncovered rather than silently given the default.

**FR-CAS-005 — Escalation** · P1 · `F-202` · E12
Milestone breach and imminent breach must trigger configured escalation actions — reassignment, notification, priority change — with the trigger recorded.

**FR-CAS-006 — Case relationships** · P2 · `F-203` · E12
Cases must support parent/child hierarchy for related incidents and merging of duplicates with activity consolidation.

**FR-CAS-007 — Knowledge** · P2 · `F-204`, `F-205` · E12
The platform must support knowledge articles with versioning, approval workflow, publication state and audience visibility, with contextual suggestion of relevant articles on a case.

**FR-CAS-008 — Self-service portal** · P2 · `F-206` · E12
Authorized customer contacts must be able to raise and track their own cases and view permitted knowledge, with access scoped strictly to their account.

**FR-CAS-009 — Satisfaction measurement** · P2 · `F-207` · E12
Case closure must be able to trigger a satisfaction survey whose result links back to the case, account and agent.

**FR-CAS-010 — Service signal to revenue** · P2 · `F-208` · E12
Case volume, severity, SLA breaches and satisfaction must feed the account health indicator and renewal risk, with the contribution visible.

---

## 18. PTR — Partner, channel and territory

**FR-PTR-001 — Partner accounts** · P1 · `F-209` · E13
The platform must support a partner account type with tier, agreement status, certification and assigned partner manager.

**FR-PTR-002 — Deal registration** · P1 · `F-210` · E13
Partners must be able to register a prospective deal, which is approved or rejected within an SLA and, once approved, confers time-boxed protection.
- Rules: an expiring registration notifies the partner and the partner manager before lapse.

**FR-PTR-003 — Channel conflict detection** · P2 · `F-211` · E13
The platform must detect and surface overlapping registrations or a direct opportunity conflicting with an approved partner registration, and must route the conflict for resolution.

**FR-PTR-004 — Partner portal access** · P2 · `F-212`, `F-214` · E13
Partner users must access only records shared with their partner account, under a restricted permission model that cannot see other partners' data.

**FR-PTR-005 — Channel reporting** · P1 · `F-213` · E13
The platform must distinguish partner-sourced from partner-influenced pipeline and revenue, with the definitions published.

**FR-PTR-006 — Territory realignment** · P2 · `F-215`, `F-216` · E13
Territory changes must be simulatable before activation, and on activation must apply a configured policy for in-flight opportunities (retain, transfer, transfer with credit split).

---

## 19. AUT — Automation, process and approvals

**FR-AUT-001 — Record-triggered automation** · P0 · `F-217` · E14
Administrators must be able to define automation triggered by record create, update, delete and undelete, with entry conditions evaluated against old and new values.

**FR-AUT-002 — Scheduled automation** · P0 · `F-218` · E14
Automation must be schedulable at a fixed time, on a recurrence, or relative to a record date field.

**FR-AUT-003 — Visual builder** · P0 · `F-219` · E14
Automation must be definable by a tenant administrator through a visual, no-code builder covering conditions, branches, loops over related records, and the action set in `FR-AUT-006`.

**FR-AUT-004 — Enforced business process** · P0 · `F-220` · E14
Administrators must be able to define a state machine per object specifying permitted transitions, per-state mandatory fields and actions, transition conditions, and per-state duration SLAs.
- Rules: the state machine is enforced server-side across UI, API and automation. A transition not defined in the model cannot occur by any path.
- On failure: the transition is refused, naming the unsatisfied condition.

**FR-AUT-005 — Validation rules** · P0 · `F-221` · E14
Administrators must be able to define record-level validation with a custom message and a target field for the error.

**FR-AUT-006 — Action set** · P0 · `F-226`, `F-227` · E14
Automation must support: update fields on the triggering or a related record, create records, create tasks, send email, send notification, submit for approval, invoke a webhook, and call a named integration.

**FR-AUT-007 — Approval processes** · P0 · `F-222`, `F-223`, `F-225` · E14
The platform must support multi-step approvals with serial and parallel steps, unanimous or first-response semantics, dynamic approver determination (hierarchy, field value, amount matrix, queue), recall by the submitter, rejection with mandatory reason, and resubmission.
- Rules: maker-checker per `FR-SEC-010` applies to every approval step.

**FR-AUT-008 — Approval delegation** · P1 · `F-224` · E14
Approvers must be able to delegate authority for a bounded period; delegated approvals record both the delegate and the delegating authority.

**FR-AUT-009 — Expression language** · P0 · `F-228` · E14
The platform must provide a formula/expression language with functions for text, number, date, logical and record-reference operations, usable in fields, validation, conditions and criteria, with a syntax checker and test evaluator.

**FR-AUT-010 — Simulation** · P1 · `F-230` · E14
Before activation, an administrator must be able to run an automation against a selected set of real records in a read-only simulation, seeing every action that would occur without any of them occurring.

**FR-AUT-011 — Execution log** · P0 · `F-231` · E14
Every automation execution must produce a log with trigger, entry-condition outcome, each step, each action result and total duration, retained for a configurable period and filterable by record.

**FR-AUT-012 — Loop and recursion protection** · P0 · `F-232` · E14
The platform must detect and halt cascading or recursive automation, with a diagnostic naming the participating rules and the cycle.

**FR-AUT-013 — Versioning and rollback** · P1 · `F-233` · E14
Automation definitions must be versioned, with the active version identifiable and any prior version restorable.

**FR-AUT-014 — No rule-count limits** · P0 · `F-234` · E14
The platform must not impose a fixed numeric limit on automation rules per object or per tenant. Resource protection must be by fair-use throttling with visible telemetry, never by an arbitrary count cap.

---

## 20. RPT — Reporting and analytics

**FR-RPT-001 — Report builder** · P0 · `F-235`, `F-236` · E15
Users must be able to build reports with filters, groupings, summaries and sorts, in tabular, summary, matrix and joined formats, without administrator assistance.

**FR-RPT-002 — Cross-object reporting** · P0 · `F-237` · E15
Administrators must be able to define report types joining related objects, including "with" and "without" related-record semantics.

**FR-RPT-003 — Calculated columns** · P1 · `F-238` · E15
Reports must support custom summary formulas, row-level formulas and bucketing of values into named groups.

**FR-RPT-004 — Dashboards** · P0 · `F-239`, `F-249` · E15
Users must be able to compose dashboards from report-backed components with multiple visualization types, filters, and placement on record pages.

**FR-RPT-005 — Access-aware results** · P0 · `F-240` · E15
Report and dashboard results must reflect the viewing user's record and field access by default.
- Rules: a dashboard may run as a specified user only where that is explicitly configured and the fact is displayed to every viewer.

**FR-RPT-006 — Drill-through** · P0 · `F-244` · E15
Every aggregate value must be drillable to the contributing records, subject to the viewer's access.

**FR-RPT-007 — Scheduled delivery and alerting** · P0 · `F-241`, `F-242` · E15
Reports and dashboards must be schedulable for delivery to permitted recipients, and subscribable with threshold conditions that notify only when a metric crosses a bound.

**FR-RPT-008 — Historical trending** · P1 · `F-243` · E15
Designated objects must be snapshot on a schedule so that trend reports over historical states are possible without reconstructing history from audit data.

**FR-RPT-009 — Governed KPI definitions** · P0 · `F-247` · E15
Every standard metric (win rate, coverage, velocity, ARR, attainment, forecast accuracy) must have a single published definition, formula and version visible to users where the metric appears.
- Rules: two reports displaying the same named metric must compute it identically. A metric with more than one active definition is a defect, not a configuration choice.

**FR-RPT-010 — Export governance** · P0 · `F-245`, `F-246` · E15
Export must respect the export permission per `FR-SEC-015`, be limited by configurable row thresholds requiring approval above a bound, and produce an audit record of who exported what, when and how many rows.

**FR-RPT-011 — Query guardrails** · P1 · `F-248` · E15
Long-running or excessively broad reports must be constrained by timeout and result limits, with the user given a clear message and guidance on narrowing, and must not be able to degrade service for other tenants.

---

## 21. AIX — AI copilot and agentic assistance

**FR-AIX-001 — Availability in all tiers** · P0 · `F-251` · E16
Baseline AI capability must be available in every commercial tier. Tier differentiation may apply to usage volume and advanced capability, never to the presence of AI assistance.

**FR-AIX-002 — Record summarization** · P0 · `F-252` · E16
Users must be able to obtain a summary of an account, opportunity or case covering current state, recent activity, open items and risks.
- Rules: every summary cites the specific records it drew from, and the citations are navigable.

**FR-AIX-003 — Next-best-action** · P0 · `F-253` · E16
The platform must recommend prioritized next actions per opportunity and account, each stating the observation that prompted it, why it matters and the specific action.
- Rules: recommendations respect the acting user's record and field access — the assistant must never surface, cite or reason over data the user cannot see.

**FR-AIX-004 — Grounded drafting** · P0 · `F-254` · E16
The platform must draft emails, call preparation notes and meeting follow-ups grounded in CRM records.
- Rules: drafts are never sent automatically. The user reviews and sends explicitly, and the draft's provenance is recorded on the resulting activity.

**FR-AIX-005 — Conversational query** · P0 · `F-256`, `F-257` · E16
Users must be able to ask questions of their CRM data in natural language and receive answers with the underlying records and the interpreted query shown.
- Rules: the system displays how it interpreted the question, so a user can detect a misinterpretation rather than trust a confident wrong answer.
- On failure: where the question cannot be answered reliably, the system says so. It must not produce a plausible answer it cannot substantiate.

**FR-AIX-006 — Predictive scoring** · P0 · `F-258` · E16
The platform must produce lead conversion, deal win, renewal and health predictions.

**FR-AIX-007 — Universal citation** · P0 · `F-259` · E16
Every generated output must identify the records that grounded it. An output that cannot cite its basis must be labelled as unsupported, and must not be presented as derived from CRM data.

**FR-AIX-008 — Universal score decomposition** · P0 · `F-260` · E16
Every score must be presentable as its weighted contributing factors, each with direction and magnitude, in business language rather than model-internal terms.

**FR-AIX-009 — Agentic execution with confirmation** · P1 · `F-261`, `F-262` · E16
The assistant must be able to execute multi-step tasks (research an account, update fields, create follow-ups, draft and queue outreach).
- Rules: the agent presents the complete plan and the exact changes it would make; execution begins only on explicit human confirmation; every action is attributed to the AI source with the initiating user; the whole action set is reversible as a unit for a retention window.
- On failure: a step that fails halts the sequence and reports what completed and what did not. Partial silent completion is not acceptable.

**FR-AIX-010 — Tenant-scoped grounding** · P0 · `F-263` · E16
No prompt, embedding, cache or context may contain data from more than one tenant. Model interactions must be scoped to the acting tenant and the acting user's permissions.

**FR-AIX-011 — No training on tenant data** · P0 · `F-264` · E16
Tenant data must not be used to train or fine-tune any model shared across tenants. This must be contractually stated and technically enforced.

**FR-AIX-012 — PII handling** · P0 · `F-265` · E16
Designated personal and sensitive fields must be masked or excluded before model invocation, per tenant policy, with the applied policy recorded on the interaction.

**FR-AIX-013 — AI-off mode** · P0 · `F-266` · E16
A tenant must be able to disable AI entirely. With AI off, every non-AI requirement in this document must remain fully satisfied.
- Rules: no core workflow may depend on an AI capability. AI surfaces are hidden rather than shown as errors or upsells.

**FR-AIX-014 — Provider abstraction** · P1 · `F-267` · E16
The platform must support alternative model providers, including a customer-hosted model in a sovereign deployment, configured without code change.

**FR-AIX-015 — Usage and quality telemetry** · P1 · `F-268`, `F-270` · E16
Tenant administrators must be able to see AI usage volume, cost where applicable, latency, and quality signals including user acceptance and rejection of AI outputs.

**FR-AIX-016 — Evaluation harness** · P1 · `F-269` · E16
AI capabilities must be covered by a repeatable evaluation suite with published quality metrics, run on every model or prompt change, with regressions blocking release.

---

## 22. INT — Integration platform

**FR-INT-001 — Complete REST API** · P0 · `F-271`, `F-274` · E17
Every object and operation available in the UI must be available through a documented REST API, described by a published, versioned OpenAPI specification.
- Rules: no capability may be UI-only. If a user can do it, an integration can do it.

**FR-INT-002 — Bulk API** · P0 · `F-272` · E17
The platform must provide asynchronous bulk create, update, upsert, delete and query operations with job status, per-record results and a downloadable error file.

**FR-INT-003 — No API call limits by tier** · P0 · `F-273` · E17
API access must not be limited by commercial tier. Fair-use throttling must be applied uniformly, with published limits, standard rate-limit response headers and visible usage telemetry.

**FR-INT-004 — API versioning** · P0 · `F-275` · E17
The API must be versioned with a published support and deprecation policy, a minimum notice period, and no breaking change within a version.

**FR-INT-005 — Webhooks** · P0 · `F-276` · E17
Administrators must be able to subscribe endpoints to record and process events, with signed payloads, retry with exponential backoff, a dead-letter queue and a replay facility.

**FR-INT-006 — Event stream** · P1 · `F-277`, `F-278` · E17
The platform must publish domain events for near-real-time consumption, and must offer change data capture suitable for downstream replication with ordering and no silent gaps.

**FR-INT-007 — Secure credential storage** · P0 · `F-280` · E17
Outbound integration credentials must be stored encrypted, never displayed after entry, rotatable, and referenced by name in configuration rather than embedded.

**FR-INT-008 — Connector catalogue** · P1 · `F-281`, `F-282` · E17
The platform must ship supported connectors for email/calendar, telephony, e-signature, marketing automation, ERP/billing and data enrichment, each with configuration, health status and field mapping.

**FR-INT-009 — Integration health** · P1 · `F-283` · E17
Administrators must see per-integration health: last successful sync, failure count, error detail and affected records, with the ability to retry.
- Rules: an integration failing silently is a defect. Every failure must surface to a human within a configured window.

---

## 23. MIG — Migration and onboarding

**FR-MIG-001 — Source connection** · P0 · `F-285`, `F-286`, `F-287` · E18
The platform must connect to a Salesforce, Zoho CRM or HubSpot source using the customer's own credentials, with read-only scope, and enumerate available objects and record counts.

**FR-MIG-002 — Schema discovery and mapping** · P0 · `F-288` · E18
The platform must discover the source schema including custom objects and fields, propose a field mapping to Axiom, and allow the user to review, correct and save it.
- Rules: unmapped source fields must be listed explicitly. Silent omission of source data is not acceptable.

**FR-MIG-003 — Dry run** · P0 · `F-289` · E18
The user must be able to execute a full validation pass that writes nothing and produces a report of: records to be created per object, validation failures with reasons, duplicates detected, unmapped fields, and referential gaps.

**FR-MIG-004 — Relationship preservation** · P0 · `F-293` · E18
Migration must preserve account hierarchies, contact-account relationships, opportunity-account-contact links, and activity-to-record associations.
- On failure: a relationship that cannot be resolved is reported with both endpoints named, not silently dropped.

**FR-MIG-005 — History migration** · P1 · `F-294` · E18
Attachments, notes and activity history must be migratable, with original timestamps and actors preserved as recorded values.

**FR-MIG-006 — Reconciliation report** · P0 · `F-290` · E18
After migration, the platform must produce a reconciliation report comparing source and target record counts per object, monetary sums for financial fields, and a list of every record not migrated with the reason.

**FR-MIG-007 — Rollback** · P0 · `F-291` · E18
A completed migration must be reversible, removing every record it created and restoring the tenant to its pre-migration state, within a configurable retention window.
- Rules: rollback is itself audited and reports exactly what was removed.

**FR-MIG-008 — Delta re-sync** · P1 · `F-292` · E18
During a parallel-run period, the platform must be able to re-sync only records created or changed in the source since the last run, without duplicating previously migrated records.

**FR-MIG-009 — Guided onboarding** · P0 · `F-295`, `F-298` · E18
New tenants must receive a role-specific onboarding checklist tracking completion, plus contextual in-product guidance for first-use of major features.

**FR-MIG-010 — Configuration templates** · P1 · `F-296`, `F-297` · E18
The platform must offer starting configuration templates by industry and company size, and a sample-data environment for evaluation and training that is clearly marked and separately deletable.

---

## 24. ADM — Administration and release management

**FR-ADM-001 — Custom objects** · P0 · `F-299`, `F-301` · E19
Administrators must be able to create custom objects with labels, plural labels, record name format, relationships (lookup, master-detail, many-to-many) and full participation in security, automation, reporting, search and API.
- Rules: no numeric limit on object count by tier. Resource protection is by fair use with telemetry.

**FR-ADM-002 — Custom fields** · P0 · `F-300`, `F-302` · E19
Administrators must be able to create fields of all supported types — text, number, currency, percent, date, datetime, checkbox, picklist, multi-picklist, lookup, formula, roll-up summary, encrypted text, long text, URL, email, phone, geolocation — with help text, default values and required/unique constraints.
- Rules: changing a field type must be blocked where it would lose data, with a clear statement of what would be lost.

**FR-ADM-003 — Record types and layouts** · P0 · `F-303`, `F-304`, `F-305` · E19
Administrators must be able to define record types with distinct layouts, picklist subsets and business processes, and design layouts by drag-and-drop with conditional field and section visibility.

**FR-ADM-004 — List views and search** · P0 · `F-306`, `F-307` · E19
Users must be able to create list views with filters, columns, sort and sharing scope. Global search must span objects with relevance ranking and respect all access controls.

**FR-ADM-005 — Sandbox** · P0 · `F-308` · E19
Every tenant, in every tier, must be entitled to at least one sandbox containing a full copy of configuration and a configurable subset or full copy of data.
- Rules: sandbox data must have outbound email, webhooks and integrations disabled by default to prevent contacting real customers from a test environment.

**FR-ADM-006 — Change promotion** · P0 · `F-309`, `F-310` · E19
Administrators must be able to assemble configuration changes into a change set, validate it against the target environment without applying it, view a diff against the target, and deploy atomically.
- On failure: a failed deployment leaves the target unchanged and reports every blocking issue, not just the first.

**FR-ADM-007 — Configuration versioning** · P1 · `F-311` · E19
Configuration changes must be versioned with actor, timestamp and a restorable prior state.

**FR-ADM-008 — Setup audit trail** · P0 · `F-312` · E19
Every administrative and configuration change must be recorded with actor, timestamp, component and before/after values, retained per the audit retention policy.

**FR-ADM-009 — Data import** · P0 · `F-313` · E19
Administrators must be able to import records from file with field mapping, duplicate handling, validation preview and a downloadable error file identifying each failed row and its reason.

**FR-ADM-010 — Mass operations** · P0 · `F-314` · E19
The platform must support mass update, mass transfer of ownership and mass delete, each subject to permission, a confirmation step stating the exact record count, a volume threshold requiring step-up authentication, and full audit.

**FR-ADM-011 — Recycle bin** · P0 · `F-315` · E19
Deleted records must be recoverable for a configurable period with their relationships intact, after which they are permanently removed.

**FR-ADM-012 — Archival** · P2 · `F-316` · E19
Administrators must be able to define archival policies moving aged records out of the active working set while keeping them retrievable and reportable.

**FR-ADM-013 — Entitlement administration** · P0 · `F-317` · E19
Platform operators must be able to configure per-tenant feature entitlements, and tenant administrators must be able to see which capabilities their subscription includes.

**FR-ADM-014 — Administration without vendor dependency** · P0 · `F-318` · E19
Every routine administrative task in this module must be completable by a trained tenant administrator through the product interface, without vendor intervention, professional services or code deployment.
- Rules: any task requiring vendor involvement must be explicitly listed as such in product documentation. An undocumented vendor dependency is a defect.

---

## 25. AUD — Audit, compliance and governance

**FR-AUD-001 — Immutable audit events** · P0 · `F-319` · E20
Audit events must be append-only and must not be editable or deletable by any user or administrator, including platform operators.

**FR-AUD-002 — Field change history** · P0 · `F-320` · E20
Designated fields must record every change with before value, after value, actor, timestamp and source.

**FR-AUD-003 — Read auditing** · P1 · `F-321` · E20
For designated sensitive objects and fields, view events must be audited with actor, record, timestamp and access path.

**FR-AUD-004 — Authentication auditing** · P0 · `F-322` · E20
All login successes and failures, MFA challenges, session creation and revocation, impersonation and break-glass use must be audited.

**FR-AUD-005 — Export auditing** · P0 · `F-323` · E20
Every export, report download and print must be audited with actor, object, filter criteria, row count and destination.

**FR-AUD-006 — Retention** · P0 · `F-324` · E20
Audit retention must be configurable per tenant with a minimum of seven years, and must be independent of business-record retention.

**FR-AUD-007 — Tamper evidence** · P1 · `F-325` · E20
The audit store must provide cryptographic tamper evidence such that undetected modification of a historical event is not possible.

**FR-AUD-008 — Data subject requests** · P0 · `F-329`, `F-330` · E20
The platform must fulfil access, rectification, portability and erasure requests for an identified data subject within a configurable service window.
- Rules: erasure removes or irreversibly pseudonymizes personal data across all objects, backups and derived stores including AI caches and embeddings, while retaining a non-personal audit record that the erasure occurred.
- On failure: any store that cannot be reached by the erasure process must be reported, not silently skipped.

**FR-AUD-009 — Consent register** · P0 · `F-331` · E20
The platform must record consent per data subject per purpose per channel, with lawful basis, source, timestamp, and full history of grant and withdrawal.

**FR-AUD-010 — Retention policies** · P1 · `F-332` · E20
Administrators must be able to define per-object retention with automated enforcement, legal-hold override, and notification before destructive action.

**FR-AUD-011 — Encryption** · P0 · `F-326`, `F-328` · E20
Data must be encrypted in transit and at rest, with tenant-scoped keys and documented rotation.

**FR-AUD-012 — Customer-managed keys** · P2 · `F-327` · E20
Sovereign and designated enterprise tenants must be able to supply and control their own encryption keys, including the ability to revoke access by withdrawing the key.

**FR-AUD-013 — Complete tenant export** · P0 · `F-333` · E20
A tenant administrator must be able to initiate, without vendor assistance, a complete export of all tenant data and configuration in a documented open format, in every commercial tier.
- Rules: the export includes custom objects, attachments, audit history and configuration, with a manifest and integrity checksums.

**FR-AUD-014 — Observability** · P0 · `F-334`, `F-335` · E20
The platform must emit structured logs with correlation IDs, application and business metrics, and health probes, with alerting on defined service-level indicators.
- Rules: logs and metrics must never contain credentials, tokens or unmasked personal data.

**FR-AUD-015 — Tenant usage telemetry** · P1 · `F-336` · E20
Tenant administrators must see their own adoption and usage telemetry: active users, feature usage, API consumption, storage and automation volume.

**FR-AUD-016 — Compliance evidence** · P2 · `F-337` · E20
The platform must be able to generate an evidence pack for a defined period covering access grants and changes, privileged actions, data subject requests, export activity and configuration changes.

---

## 26. MOB — Mobile and offline

**FR-MOB-001 — Responsive interface** · P0 · `F-338` · E21
The full application must be usable on tablet and mobile viewports, with no capability available only on desktop except administration.

**FR-MOB-002 — Native applications** · P1 · `F-339` · E21
Native iOS and Android applications must provide record access, activity capture, approvals, notifications and search.

**FR-MOB-003 — Quick capture** · P1 · `F-342` · E21
Mobile must provide fast-path capture for calls, notes, tasks and meeting outcomes in minimal interactions.

**FR-MOB-004 — Notifications** · P1 · `F-345` · E21
Push notifications must be delivered for assignments, approvals, mentions, SLA warnings and configured alerts, respecting user preference and quiet hours.

**FR-MOB-005 — Offline access** · P2 · `F-340` · E21
Designated records must be available read-only when the device is offline, with the cache age visible to the user.

**FR-MOB-006 — Offline capture and sync** · P2 · `F-341` · E21
Records created or edited offline must synchronize on reconnection with explicit conflict presentation and user-chosen resolution.
- On failure: an unresolvable conflict is retained for the user to resolve. Silent last-write-wins is not acceptable.

**FR-MOB-007 — Device management** · P2 · `F-346` · E21
Administrators must be able to see registered devices and revoke a device's sessions and cached data remotely.

---

## 27. BFS — BFSI vertical pack

The BFSI pack is installed as a governed extension per `FR-BFS-011`. Every requirement in this section is additive to the core; none may alter core behaviour for tenants without the pack.

**FR-BFS-001 — Relationship-manager book** · P1 · `F-347` · E22
The pack must provide a relationship manager a consolidated view of their assigned clients with holdings, portfolio value, recent interactions, upcoming reviews and open actions.

**FR-BFS-002 — Household grouping** · P1 · `F-348` · E22
Clients must be groupable into households or related-party groups with defined relationship types, and financial values must roll up to the group.

**FR-BFS-003 — KYC onboarding** · P1 · `F-349` · E22
The pack must provide an onboarding workflow with a document checklist driven by client type and risk tier, document capture with expiry tracking, verification status per document, and a completion gate before the relationship can be activated.
- On failure: an incomplete KYC blocks activation, naming the outstanding items and their owner.

**FR-BFS-004 — Risk rating** · P1 · `F-350` · E22
Clients must carry a risk rating computed from configurable weighted factors — geography, entity type, industry, product, screening outcome — with the full factor breakdown visible and every rating change audited with actor and rationale.

**FR-BFS-005 — Screening** · P1 · `F-351` · E22
The pack must integrate with sanctions, PEP and adverse-media screening providers, recording each screening run, its result, and the disposition of every hit with the reviewer and rationale.
- Rules: a screening hit blocks onboarding progression until dispositioned by an authorized reviewer.

**FR-BFS-006 — Periodic review** · P2 · `F-352` · E22
Review cycles must be scheduled automatically by risk tier, with reminders, escalation on overdue, and an audit trail of each completed review.

**FR-BFS-007 — Beneficial ownership** · P2 · `F-353` · E22
The pack must capture ownership and control structures with percentage holdings, identify ultimate beneficial owners against a configurable threshold, and screen them as related parties.

**FR-BFS-008 — Product holdings** · P1 · `F-354` · E22
The pack must present all products held by a client with balances, dates and status, sourced from core banking or policy systems, alongside a whitespace view of products not held.

**FR-BFS-009 — Suitability** · P1 · `F-355`, `F-356` · E22
Product recommendations must be constrained by a recorded suitability assessment covering objectives, risk tolerance, horizon and knowledge.
- Rules: recommending a product outside the assessed suitability requires an explicit documented override with reason and approval. An unsuitable recommendation cannot be issued silently.

**FR-BFS-010 — Communication archiving** · P1 · `F-357`, `F-358` · E22
All client communications must be archived in an immutable store with configurable retention meeting regulatory minimums, searchable by client, RM, date and content, with legal-hold capability suspending deletion.

**FR-BFS-011 — Complaint handling** · P2 · `F-359` · E22
The pack must provide a complaint workflow with a regulatory response clock, mandatory categorization, root-cause capture, resolution recording and regulatory reporting extract.

**FR-BFS-012 — Life-event triggers** · P2 · `F-360` · E22
Configurable triggers — maturity, life event, threshold crossing — must generate suitability-constrained opportunities for the relationship manager.

**FR-BFS-013 — Pack framework** · P1 · `F-361` · E22
Vertical packs must be installable, configurable, versionable, upgradable and removable as a unit.
- Rules: a pack may add objects, fields, layouts, automation, roles and reports; it may not modify core object semantics. Uninstalling must state exactly what data would be affected and require explicit confirmation.

---

## 27a. CTM — Commodity trading vertical pack

The commodity-trading pack addresses **origination and relationship management for physical and paper commodity trading**. It is additive to the core, installed per `FR-BFS-013`, and is specified in full in [the commodity trading pack](17-vertical-pack-commodity-trading.md).

**The governing boundary rule.** This pack covers the pre-deal relationship and origination layer only. The CRM must never become a second trading system: it does not capture trades, compute positions, value portfolios, calculate credit exposure, schedule movements, hold inventory, or settle. Those belong to the CTRM/ETRM system of record, which the CRM integrates with as an external system per `FR-CTM-010`. The hand-off point is **deal agreed**.

**FR-CTM-001 — Counterparty as account** · P1 · `F-362` · E23
The pack must extend the account with counterparty attributes: legal entity identifiers, trading entities, KYC/onboarding status, master agreement references (ISDA, GTC, GMRA or equivalent), approved commodities and approved trading venues.
- Rules: counterparty master may be owned by the CTRM system. Where it is, the CRM treats those fields as read-only and displays their source and last-sync time.

**FR-CTM-002 — Master agreement tracking** · P1 · `F-363` · E23
The pack must record master agreements with type, counterparty entity, execution date, governing law, status and expiry, and must make agreement status available as a gate on origination progression.
- On failure: progressing an origination beyond a configured stage without an executed master agreement is blocked, naming the missing agreement.

**FR-CTM-003 — Credit gate** · P1 · `F-364` · E23
The pack must display counterparty credit limit, current utilisation and available headroom, sourced from the CTRM system, and must use available headroom as a configurable stage-exit criterion on an origination.
- Rules: **the CRM displays credit; it does not compute it.** The value shown must carry its source, as-of timestamp and staleness indicator.
- On failure: where credit data is stale beyond a configured threshold or unavailable, the gate must fail closed and say why — it must never silently pass on missing data.

**FR-CTM-004 — Origination pipeline** · P1 · `F-365` · E23
The pack must provide origination record types extending the opportunity: term contract negotiation, spot/cargo enquiry, tender participation and structured/paper deal, each with its own pipeline, stages and exit criteria.

**FR-CTM-005 — Tender management** · P1 · `F-366` · E23
The pack must manage tender participation: issuing body, tender reference, submission deadline, required documents, bid submitted, award outcome, awarded counterparty and price where disclosed.
- Rules: the submission deadline drives escalating reminders; a tender not submitted by its deadline auto-closes as lapsed with that reason recorded.

**FR-CTM-006 — Cargo and parcel enquiry** · P1 · `F-367` · E23
The pack must capture pre-deal cargo enquiries with commodity, grade, quantity and tolerance, delivery window, load and discharge locations, incoterm and indicative pricing basis.
- Rules: these are enquiry attributes on an opportunity, not a nomination or a scheduled movement. Operational scheduling remains in the CTRM system.

**FR-CTM-007 — Indicative pricing** · P1 · `F-368` · E23
The pack must support fixed and formula-based indicative pricing on quotes, expressing formula as index, differential, quotation period and settlement convention, displayed as a human-readable expression.
- Rules: an indication is explicitly labelled as indicative and non-binding. **The CRM does not compute a settlement price or a mark-to-market value** — it records the agreed pricing basis for hand-off.

**FR-CTM-008 — Broker and agent relationships** · P2 · `F-369` · E23
The pack must model brokers, agents and shipping intermediaries as related parties on an origination, with role, commission basis and performance reporting on introduced volume.

**FR-CTM-009 — Trade hand-off** · P1 · `F-370` · E23
On origination closure as won, the pack must emit a structured deal-agreed hand-off to the CTRM system containing counterparty, commodity, quantity and tolerance, delivery terms, pricing basis, agreed period and the originating record reference.
- Rules: the hand-off is asynchronous, idempotent and acknowledged. The CRM record retains the CTRM trade reference returned, giving a bidirectional link.
- On failure: an unacknowledged hand-off surfaces on an exception queue with the failure reason and a retry action. It must never be silently dropped, and the origination must not be reported as handed off until acknowledgement is received.

**FR-CTM-010 — CTRM/ETRM connector** · P1 · `F-371` · E23
Integration with the trading system of record must be implemented as a **generic CTRM/ETRM connector** against a defined capability contract, not against any single vendor's API.
- Rules: the contract covers counterparty master sync, credit limit and utilisation read, master agreement read, deal-agreed hand-off, and trade-status callback. Any CTRM/ETRM system implementing the contract is supported; a specific product is one implementation, never a hard dependency.
- On failure: with the connector unavailable, the CRM must remain fully usable for relationship and origination work, with credit gates failing closed per `FR-CTM-003` and hand-offs queued for later delivery.

**FR-CTM-011 — Commodity reference data** · P1 · `F-372` · E23
The pack must maintain commodity, grade, unit of measure, quality specification and location reference data, with conversion factors between units, sourced from or reconciled against the CTRM system.

**FR-CTM-012 — Tender and origination analytics** · P2 · `F-373` · E23
The pack must report tender win rate by issuing body, commodity and region; origination conversion by type; and volume won versus volume bid, with the counting basis published.

---

## 28. Assumptions

1. Customers provide their own identity provider, email/calendar platform, e-signature provider, marketing automation platform, ERP/billing system and telephony where those integrations are used.
2. Reference data quality of migrated source systems is the customer's responsibility; Axiom reports quality issues but does not silently correct them.
3. Sovereign deployments accept responsibility for their own infrastructure operation, backup verification and disaster recovery execution, using tooling Axiom provides.
4. A tenant administrator is a trained business user, not a developer. Requirements marked "without vendor intervention" assume training, not programming skill.
5. AI model providers are commercially available under terms permitting zero data retention and no training on submitted data.

## 29. Dependencies

| Dependency | Affects | Consequence if unmet |
|---|---|---|
| Identity provider supporting SAML 2.0 or OIDC | `TEN` | Falls back to local authentication; enterprise SSO requirement unmet |
| Microsoft Graph / Google Workspace API access | `ACT` | Passive capture (`FR-ACT-006`) — a P0 differentiator — cannot function |
| Model provider with zero-retention terms | `AIX` | AI capability limited to self-hosted, or tenant runs in AI-off mode |
| Screening data provider | `BFS` | `FR-BFS-005` unmet; BFSI pack not viable for regulated deployment |
| A CTRM/ETRM system implementing the connector contract | `CTM` | Credit gates fail closed, deal-agreed hand-off queues indefinitely; origination remains usable but disconnected from the trading record |
| Source-system API access with adequate rate limits | `MIG` | Migration duration extends; large migrations may need staged execution |
| Enrichment data provider | `ACC` | `FR-ACC-013` unmet; manual data entry burden increases |

## 30. Constraints

1. **Tenancy is a deployment concern.** No requirement may be satisfied by branching domain logic on deployment model — see [ADR-001](../architecture/adr/ADR-001-tenancy-isolation.md).
2. **No tier-gated security or interoperability**, per `FR-GLOBAL-011`. This constrains the commercial model as well as the implementation.
3. **AI-off must be fully functional**, per `FR-AIX-013`. No core workflow may take a hard dependency on AI.
4. **Explainability is not optional.** Any score, forecast or generated output that cannot state its basis fails its requirement, regardless of accuracy.
5. **Technology is unbound.** No requirement may be written in terms of a specific framework, database or vendor.
6. **The CRM is not a trading system.** No requirement may cause the CRM to capture trades, compute positions, value portfolios, calculate credit exposure, schedule movements, hold inventory or settle. `CTM` covers origination only; the trading system of record is external and integrated generically — see [the commodity trading pack](17-vertical-pack-commodity-trading.md).

## 31. Traceability matrix

Requirement counts by module, with epic and priority distribution. **These counts are machine-derived from this document.**

| Module | Epic | Requirements | P0 | P1 | P2 |
|---|---|---:|---:|---:|---:|
| GLOBAL | all | 11 | 10 | 1 | 0 |
| TEN | E01 | 15 | 11 | 4 | 0 |
| SEC | E02 | 15 | 10 | 4 | 1 |
| MDM | E03 | 10 | 5 | 5 | 0 |
| ACC | E04 | 14 | 8 | 5 | 1 |
| LED | E05 | 12 | 10 | 2 | 0 |
| OPP | E06 | 16 | 10 | 6 | 0 |
| ACT | E07 | 12 | 8 | 3 | 1 |
| CPQ | E08 | 14 | 6 | 7 | 1 |
| CTR | E09 | 11 | 0 | 10 | 1 |
| FCT | E10 | 12 | 9 | 3 | 0 |
| CMP | E11 | 7 | 0 | 7 | 0 |
| CAS | E12 | 10 | 0 | 5 | 5 |
| PTR | E13 | 6 | 0 | 3 | 3 |
| AUT | E14 | 14 | 11 | 3 | 0 |
| RPT | E15 | 11 | 8 | 3 | 0 |
| AIX | E16 | 16 | 12 | 4 | 0 |
| INT | E17 | 9 | 6 | 3 | 0 |
| MIG | E18 | 10 | 7 | 3 | 0 |
| ADM | E19 | 14 | 12 | 1 | 1 |
| AUD | E20 | 16 | 10 | 4 | 2 |
| MOB | E21 | 7 | 1 | 3 | 3 |
| BFS | E22 | 13 | 0 | 9 | 4 |
| CTM | E23 | 12 | 0 | 10 | 2 |
| **Total** | | **287** | **154** | **108** | **25** |

**Reading the distribution.** 154 P0 requirements is a large first release, and the concentration matters: `TEN`, `SEC`, `AUT`, `ADM`, `AIX` and `AUD` account for 66 of them — 43% of P0 is platform and governance work that delivers no directly visible sales capability. This is unavoidable in an enterprise B2B product, but it must be planned for honestly rather than discovered in sprint 6. See [the agile delivery plan](15-agile-delivery-plan.md).

Both vertical packs (`BFS`, `CTM`) are entirely P1/P2. Neither is on the critical path to a first release, which is the correct sequencing — the pack framework has to exist and be proven by the core before a vertical is worth building on it.

### Forward traceability

- Every `FR-` in this document is realized by one or more `US-` in [epics and user stories](05-epics-and-stories.md).
- Every `US-` is verified by one or more `TC-`, `EC-`, `NEG-`, `SEC-`, `CON-` or `NFR-` case in [acceptance tests](06-acceptance-tests.md).
- Every P0 epic is validated by one or more `UAT-` scenario in [the UAT plan](07-uat-plan.md).

Traceability is verified mechanically — see [the verification section of the QA master test plan](../../qa/qa-master-test-plan.md).

## 32. Glossary

| Term | Definition |
|---|---|
| **Account** | An organization Axiom does business with or is pursuing |
| **ARR** | Annual recurring revenue — the annualized value of active subscriptions |
| **Blueprint / enforced process** | A state machine constraining permitted record transitions, enforced server-side |
| **Buying group** | The set of people at an account who influence a purchase, with roles |
| **Coverage ratio** | Open pipeline divided by remaining quota for a period |
| **Entitlement** | A contractual support level determining SLA targets on a case |
| **Forecast category** | The classification (pipeline/best case/commit/closed) determining forecast inclusion |
| **Grounding** | Supplying an AI model with specific tenant records as the basis for its output |
| **Maker-checker** | The control that a user who initiates an action cannot approve it |
| **Org-wide default** | The baseline record visibility for an object before sharing is applied |
| **Permission set** | An additive grant of permissions independent of a user's profile |
| **Profile** | A user's baseline object and system permissions |
| **Sovereign deployment** | A single-tenant installation under the customer's own control |
| **Step-up authentication** | Re-authentication required for a controlled action regardless of session age |
| **Tenant** | An isolated customer instance of Axiom |
| **Vertical pack** | A governed, installable extension adding industry-specific capability |

---

## Related documents

- [Product scope](01-product-scope.md) · [Competitive analysis](02-competitive-analysis-salesforce-zoho.md) · [Feature catalogue](04-feature-catalogue.md)
- [Epics and user stories](05-epics-and-stories.md) · [Acceptance tests](06-acceptance-tests.md) · [UAT plan](07-uat-plan.md)
- [RBAC and sharing model](08-rbac-and-sharing-model.md) · [Data model](09-data-model.md) · [Non-functional requirements](10-nfr-and-enterprise-readiness.md)
- [AI capabilities](11-ai-capabilities.md) · [BFSI vertical pack](12-vertical-pack-bfsi.md) · [Integration and migration](13-integration-and-migration.md) · [Reporting and analytics](14-reporting-and-analytics.md)
- [Agile delivery plan](15-agile-delivery-plan.md) · [Tenancy, licensing and deployment](16-tenancy-licensing-and-deployment.md) · [Commodity trading vertical pack](17-vertical-pack-commodity-trading.md)

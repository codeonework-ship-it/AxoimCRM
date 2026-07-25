# Epics, user stories and acceptance criteria

The agile scrum backlog for Axiom, derived from the [FRD](03-frd.md). Every story traces to at least one `FR-` requirement; every `FR-` is realized by at least one story.

## Backlog conventions

- **Priority:** `P0` mandatory for the first production release · `P1` next production capability · `P2` optimization or extension.
- **Estimate:** story points on a modified Fibonacci scale (1, 2, 3, 5, 8, 13). A story estimated above 13 must be split before it enters a sprint.
- **Acceptance criteria use Given/When/Then and describe business outcomes, not UI implementation instructions.** "The button is disabled" is not an acceptance criterion; "the user cannot advance the stage and is told which criterion is unmet" is.
- Every story must additionally satisfy the Definition of Done below and the relevant cases in [the acceptance test catalogue](06-acceptance-tests.md).

## Definition of Ready

A story may not enter a sprint until:

1. It traces to at least one `FR-` requirement.
2. Acceptance criteria are written, and a tester and a developer independently agree on what would falsify each one.
3. Dependencies are identified and either satisfied or explicitly stubbed.
4. Data, permission and tenancy implications are understood.
5. It is estimated by the people who will build it.
6. Any needed design or interface contract exists.

## Definition of Done

Applied to **every** story without exception:

1. Tenant scoping and record/field authorization are enforced **server-side** and verified by test.
2. Inputs, state transitions and concurrency conflicts are validated with actionable messages that state what to do, not merely what failed.
3. Material actions produce immutable audit events with actor, time, before/after, source, reason where required, and correlation ID.
4. The workflow is keyboard accessible and meets WCAG 2.2 AA; it is usable at supported desktop and tablet sizes.
5. Unit, integration, contract and relevant end-to-end tests pass. New behaviour has new tests.
6. Structured logs, metrics and traces identify failure without exposing personal data or credentials.
7. The public API covers the capability — nothing is UI-only (`FR-INT-001`).
8. Where the story adds a reportable field or metric, the reporting projection is updated in the same story.
9. User-facing strings are localizable; no hard-coded text.
10. Documentation — user help, admin guide and API reference — is updated.

**Point 8 is the one teams skip.** [ADR-008](../architecture/adr/ADR-008-reporting-read-model.md) names projection drift as the principal long-term risk of the read-model pattern; the only reliable defence is updating the projection in the same story, not in a later cleanup that never gets prioritized.

---

## Epic catalogue

| Epic | Capability | Priority | Stories | Points |
|---|---|:--:|---:|---:|
| E01 | Tenancy, identity and access | P0 | 9 | 63 |
| E02 | RBAC, record sharing and segregation of duties | P0 | 9 | 71 |
| E03 | Organization, reference and master data | P0 | 7 | 42 |
| E04 | Accounts, contacts, hierarchy and buying groups | P0 | 9 | 55 |
| E05 | Lead capture, qualification and routing | P0 | 8 | 47 |
| E06 | Opportunity and pipeline management | P0 | 10 | 68 |
| E07 | Activity, email and calendar engagement | P0 | 9 | 63 |
| E08 | Products, price books, quotes and CPQ | P0/P1 | 9 | 76 |
| E09 | Contracts, orders, subscriptions and renewals | P1 | 7 | 55 |
| E10 | Forecasting and revenue intelligence | P0/P1 | 8 | 63 |
| E11 | Campaigns, segments and marketing alignment | P1 | 5 | 31 |
| E12 | Cases, entitlements and SLA management | P1 | 7 | 47 |
| E13 | Partner, channel and territory management | P1/P2 | 5 | 34 |
| E14 | Workflow automation, approvals and rules engine | P0 | 9 | 84 |
| E15 | Reporting, dashboards and analytics | P0 | 8 | 63 |
| E16 | AI copilot and agentic assistance · **differentiator** | P0/P1 | 10 | 89 |
| E17 | Integration platform, APIs, webhooks and events | P0 | 7 | 47 |
| E18 | Data migration and onboarding · **differentiator** | P0 | 8 | 63 |
| E19 | Administration, configuration, sandbox and release | P0 | 9 | 76 |
| E20 | Audit, compliance, observability and governance | P0 | 9 | 63 |
| E21 | Mobile and offline field access | P1/P2 | 5 | 34 |
| E22 | BFSI vertical pack · **differentiator** | P1 | 8 | 63 |
| E23 | Commodity trading vertical pack · **differentiator** | P1 | 8 | 55 |
| | **Total** | | **183** | **1,352** |

Sprint allocation and release trains are in [the agile delivery plan](15-agile-delivery-plan.md).

---

## E01 — Tenancy, identity and access

### US-E01-01 — Tenant provisioning (P0, 5) · `FR-TEN-001`, `FR-TEN-002`
As a platform operator, I want to provision a tenant with an isolated data scope and an initial administrator so a customer can begin configuration.

**Acceptance criteria**
- Given a provisioning request, when it completes, then the tenant exists with an isolated scope, a default configuration baseline, an entitlement set and one administrator who can log in.
- Given a provisioning request that fails partway, when the failure occurs, then no partial tenant scope remains and the request may be retried with the same key without creating a duplicate.
- Given a suspended tenant, when any user attempts a business write, then it is refused, while administrator login and data export remain available.

### US-E01-02 — Local authentication and password policy (P0, 5) · `FR-TEN-003`, `FR-TEN-018`
As a user, I want to sign in with a username and password under a policy my organization sets.

**Acceptance criteria**
- Given a configured password policy, when a user sets a password violating it, then it is rejected stating which rule failed.
- Given repeated failed attempts, when the threshold is reached, then the account locks with progressive delay beforehand.
- Given a login attempt for a non-existent username, when it fails, then the error is indistinguishable from a wrong-password error.

### US-E01-03 — SAML SSO configuration (P0, 8) · `FR-TEN-004`
As a tenant administrator, I want to configure SAML SSO and test it before activation so I do not lock my organization out.

**Acceptance criteria**
- Given SAML configuration, when the administrator runs the test-connection facility, then the result reports success or the specific failure without activating the configuration.
- Given an activated but misconfigured IdP, when users cannot authenticate, then a local administrative path remains available.
- Given a certificate approaching expiry, when the threshold is reached, then administrators are notified before authentication breaks.

### US-E01-04 — OIDC SSO (P0, 5) · `FR-TEN-005`
As a tenant administrator, I want OIDC single sign-on so I can use my existing identity provider.

**Acceptance criteria**
- Given an OIDC provider configured by discovery, when a user signs in, then authorization-code flow with PKCE completes and claims map to user attributes as configured.
- Given a claim mapping that yields no email, when a user signs in, then provisioning is refused with a message naming the missing claim.

### US-E01-05 — SCIM provisioning and deprovisioning (P0, 8) · `FR-TEN-007`
As an IT administrator, I want users provisioned and deprovisioned automatically from my directory.

**Acceptance criteria**
- Given a user created in the directory, when SCIM syncs, then the user exists in Axiom with mapped attributes and assigned profile.
- Given a user deactivated in the directory, when SCIM syncs, then the user cannot authenticate, all their sessions are revoked, and **records they own remain intact and attributed to them**.
- Given a user who owns records, when a SCIM delete is received, then the user is deactivated rather than removed, preserving referential and audit integrity.

### US-E01-06 — Multi-factor authentication (P0, 8) · `FR-TEN-008`
As a security administrator, I want to require a second factor for defined populations.

**Acceptance criteria**
- Given an MFA policy targeting a role, when a user in that role authenticates, then a second factor is required and TOTP or passkey both satisfy it.
- Given recovery codes issued, when one is used, then it cannot be used again.
- Given a user exempt by policy, when they authenticate, then no second factor is demanded.

### US-E01-07 — Step-up authentication for controlled actions (P0, 5) · `FR-TEN-009`
As a security administrator, I want privileged actions to require fresh authentication regardless of session age.

**Acceptance criteria**
- Given a session older than the freshness window, when the user attempts a controlled action (permission grant, bulk export, mass delete, break-glass, tenant termination), then re-authentication is demanded before the action proceeds.
- Given a failed step-up, when the user abandons it, then the action does not occur and the failed attempt is audited.

### US-E01-08 — Session governance and revocation (P0, 5) · `FR-TEN-010`, `FR-TEN-012`
As a security administrator, I want to see and revoke active sessions.

**Acceptance criteria**
- Given an active session, when an administrator revokes it, then the next request from that session is refused without waiting for token expiry.
- Given a configured idle timeout, when it elapses, then the session ends.
- Given a concurrent-session limit, when it is exceeded, then the configured policy applies (oldest ended, or new refused) and the user is told which.

### US-E01-09 — Support impersonation with consent (P0, 8) · `FR-TEN-011`, `FR-TEN-012`
As a support operator, I want to act as a user to diagnose a problem, under controls the tenant sets.

**Acceptance criteria**
- Given a tenant that has not consented to impersonation, when an operator attempts it, then it is refused.
- Given an active impersonation, when any action occurs, then the audit event records **both** the operator and the impersonated user.
- Given an active impersonation, when the operator attempts to change permissions or grant themselves access, then it is refused.
- Given an active impersonation, when the operator views any screen, then the impersonation is visibly indicated throughout.

---

## E02 — RBAC, record sharing and segregation of duties

### US-E02-01 — Role hierarchy (P0, 8) · `FR-SEC-001`
As a security administrator, I want a role hierarchy so managers see their teams' records.

**Acceptance criteria**
- Given a user in a role above another, when they query, then records owned by roles beneath them are visible per the object's configuration.
- Given an attempt to create a cycle in the hierarchy, when it is saved, then it is rejected naming the conflicting roles.
- Given a deep hierarchy, when access is evaluated, then no artificial depth limit applies.

### US-E02-02 — Profiles and permission sets (P0, 8) · `FR-SEC-002`, `FR-SEC-003`
As a security administrator, I want baseline permissions by profile and additive grants by permission set.

**Acceptance criteria**
- Given a user with a profile and two permission sets, when effective permissions are computed, then they are the union of all three minus explicit mutes.
- Given a permission set removed from a user, when they next act, then the granted permissions are no longer effective without requiring a new login.

### US-E02-03 — Org-wide defaults and sharing rules (P0, 13) · `FR-SEC-004`, `FR-SEC-005`
As a security administrator, I want to set baseline visibility per object and widen it by rule.

**Acceptance criteria**
- Given an object set to private, when a user who is not the owner and has no sharing grant queries it, then no records are returned and their existence is not disclosed.
- Given a criteria-based sharing rule, when a record's criteria field changes so it matches, then access is granted without administrator action.
- Given a record whose owner changes, when the change commits, then owner-based sharing is recomputed.
- Given sharing recomputation in progress, when a user queries, then results are correct or the operation waits — **stale access is never served**.

### US-E02-04 — Field-level security (P0, 8) · `FR-SEC-007`
As a security administrator, I want to control field visibility per profile.

**Acceptance criteria**
- Given a field hidden from a profile, when a user with that profile reads the record via UI, API, report, export or search, then **the field is absent from the response entirely, not returned as null**.
- Given a field read-only for a profile, when a user attempts to change it via any interface including bulk update and automation triggered by them, then the change is refused.

### US-E02-05 — Sensitive field masking (P0, 5) · `FR-SEC-008`
As a compliance officer, I want sensitive values masked by default with full access separately permissioned.

**Acceptance criteria**
- Given a masked field, when a user without reveal permission views it, then only the configured partial value is shown.
- Given a user with reveal permission, when they reveal the value, then a read-audit event is recorded with actor, record, field and time.

### US-E02-06 — Segregation of duties (P0, 8) · `FR-SEC-009`
As a compliance officer, I want conflicting permissions to be impossible to hold simultaneously.

**Acceptance criteria**
- Given a declared conflicting permission pair, when an administrator grants the second side to a user who holds the first, then the grant is blocked, naming the specific conflict and the existing grant causing it.
- Given a user who already holds both sides because the conflict was declared afterwards, when the scheduled sweep runs, then the violation is reported rather than silently tolerated.

### US-E02-07 — Maker-checker (P0, 8) · `FR-SEC-010`
As a compliance officer, I want the initiator of a controlled action to be unable to approve it.

**Acceptance criteria**
- Given a user who submitted a controlled action, when they attempt to approve it, then approval is refused and the attempt is audited as a segregation violation.
- Given an approver who has delegated authority to the submitter, when the delegate attempts approval, then it is refused — **the constraint applies transitively through delegation**.

### US-E02-08 — Access explainer (P1, 8) · `FR-SEC-013`
As a security administrator, I want to know exactly why a user can or cannot see a record.

**Acceptance criteria**
- Given any user–record pair, when an administrator requests an explanation, then every contributing rule is enumerated — ownership, role hierarchy, named sharing rule, team, territory, manual share.
- Given a user who cannot see a record, when an explanation is requested, then the reason is stated and the minimum change that would grant access is identified.

### US-E02-09 — Export as a distinct permission (P0, 5) · `FR-SEC-015`
As a compliance officer, I want the right to export controlled separately from the right to read.

**Acceptance criteria**
- Given a user who can read records but lacks export permission, when they attempt to export or print, then it is refused while reading continues to work.
- Given an export exceeding the configured row threshold, when it is attempted, then approval is required before it proceeds.
- Given any completed export, when it finishes, then an audit record captures actor, object, filter criteria, row count and destination.

---

## E03 — Organization, reference and master data

### US-E03-01 — Multi-currency with dated rates (P0, 8) · `FR-MDM-002`, `FR-MDM-003`
As a finance user, I want amounts in transaction currency and corporate currency with the rate that was applied.

**Acceptance criteria**
- Given an opportunity in a non-corporate currency, when it is saved, then transaction amount, corporate amount, applied rate and rate date are all stored.
- Given exchange rates updated afterwards, when the historical record is viewed, then **its stored corporate amount is unchanged**.
- Given a record configured to use a dated rate, when the corporate amount is computed, then the rate effective at the record's defined date is used, not today's.

### US-E03-02 — Fiscal calendar (P0, 5) · `FR-MDM-004`
As a revenue operations manager, I want fiscal periods that match how we run the business.

**Acceptance criteria**
- Given a custom fiscal calendar including 4-4-5 style periods, when forecasting, quota and reporting resolve a period, then all three use the same definition.
- Given a fiscal calendar change, when historical periods are affected, then the change is refused or requires explicit confirmation naming the affected submitted forecasts.

### US-E03-03 — Business hours and holidays (P0, 5) · `FR-MDM-005`
As an operations administrator, I want business-hours definitions used consistently by SLA clocks and scheduled work.

**Acceptance criteria**
- Given a lead assigned at 17:55 on a Friday with business hours ending at 18:00, when the first-response SLA is computed, then only business hours count and the due time falls on the next business day.
- Given a holiday defined, when an SLA clock crosses it, then the holiday is excluded.

### US-E03-04 — Governed picklists (P0, 5) · `FR-MDM-006`
As an administrator, I want centrally governed picklists including dependent values.

**Acceptance criteria**
- Given a value deactivated, when new records are created, then the value is not selectable, while existing records retain and correctly report it.
- Given a dependent picklist, when the controlling value changes, then only valid dependent values are offered and an existing invalid combination is flagged rather than silently corrected.

### US-E03-05 — Effective-dated reference data (P1, 8) · `FR-MDM-007`
As a data steward, I want reference values to have validity periods so historical records resolve correctly.

**Acceptance criteria**
- Given a reference value valid until a past date, when a historical record referencing it is viewed or reported, then the value in force at that record's date is resolved.

### US-E03-06 — Territory model with preview (P1, 13) · `FR-MDM-008`
As a revenue operations manager, I want to preview a territory model against live data before activating it.

**Acceptance criteria**
- Given a defined territory model, when preview runs, then the resulting assignment of accounts and users is shown **without any assignment taking effect**.
- Given activation, when it completes, then it is atomic and the prior model version remains restorable.

### US-E03-07 — Quota management (P1, 5) · `FR-MDM-009`
As a revenue operations manager, I want quotas by user, team, territory and period with an audit of changes.

**Acceptance criteria**
- Given a quota changed after a period has begun, when the change is saved, then the prior value, actor, time and reason are retained and attainment reporting can use either version explicitly.

---

## E04 — Accounts, contacts, hierarchy and buying groups

### US-E04-01 — Account and contact management (P0, 5) · `FR-ACC-001`, `FR-ACC-002`
As an account executive, I want to maintain accounts and contacts with the fields my organization has configured.

**Acceptance criteria**
- Given a configured layout and record type, when a user creates an account, then only permitted fields are editable and required fields are enforced server-side.
- Given a contact with multiple addresses and channels, when it is saved, then each is typed and the primary of each type is unambiguous.

### US-E04-02 — Account hierarchy (P0, 8) · `FR-ACC-003`
As an account executive, I want multi-level parent/child account structures.

**Acceptance criteria**
- Given a parent assignment that would create a cycle, when saved, then it is rejected naming the accounts involved.
- Given a multi-level hierarchy, when the ultimate parent is requested, then it is derived correctly at any depth.

### US-E04-03 — Hierarchy roll-up (P0, 8) · `FR-ACC-004`
As a strategic account manager, I want pipeline and revenue rolled up across an account family.

**Acceptance criteria**
- Given an account hierarchy, when roll-up is viewed, then pipeline, closed revenue, open cases and activity recency are shown for the account alone and for the hierarchy.
- Given a user without access to some records in the hierarchy, when the roll-up is computed, then **those records are excluded and the fact that the roll-up is restricted is indicated** — the user must not be able to infer hidden records' existence from an aggregate, nor be silently under-reported to.

### US-E04-04 — Buying group (P1, 5) · `FR-ACC-006`
As an account executive, I want to record who influences a purchase and in what role.

**Acceptance criteria**
- Given a buying group, when members are added, then each carries role, influence and engagement status.
- Given a buying group with no economic buyer identified, when a stage exit criterion requires one, then advancement is blocked naming the missing role.

### US-E04-05 — Duplicate detection (P0, 8) · `FR-ACC-008`
As a data steward, I want duplicates detected at the point of entry.

**Acceptance criteria**
- Given a new account whose name closely matches an existing one, when it is saved, then candidate matches are shown with confidence before the record is created.
- Given a blocking duplicate rule, when a match is found, then creation is refused; given a warning rule, then it is permitted with the decision recorded.

### US-E04-06 — Merge with survivorship (P0, 8) · `FR-ACC-009`
As a data steward, I want to merge duplicates choosing which value survives per field.

**Acceptance criteria**
- Given two accounts merged, when the merge completes, then all activities, opportunities, cases and contacts reparent to the survivor and no related record is orphaned.
- Given a merge, when it completes, then one audit event records the losing record IDs and every field-level survivorship decision.

### US-E04-07 — Consent and suppression (P0, 8) · `FR-ACC-011`
As a compliance officer, I want communication suppression enforced at the point of send.

**Acceptance criteria**
- Given a contact who has withdrawn email consent, when any user, cadence, automation or integration attempts to email them, then **the send is blocked, not merely warned**, and the block is audited.
- Given consent withdrawn, when the consent history is viewed, then both the original grant and the withdrawal are present — **withdrawal adds a record, it never overwrites one**.

### US-E04-08 — Account 360 timeline (P0, 8) · `FR-ACC-012`
As an account executive, I want one chronological view of everything that has happened with an account.

**Acceptance criteria**
- Given an account with activities, opportunities, quotes, cases and campaign memberships, when the timeline is viewed, then all appear in one chronological stream, filterable by type and date.
- Given items the user lacks permission to see, when the timeline renders, then those items are absent and their existence is not implied by gaps or counts.

### US-E04-09 — Account health (P1, 5) · `FR-ACC-014`
As a customer success manager, I want an account health indicator I can act on.

**Acceptance criteria**
- Given a computed health score, when it is viewed, then each contributing factor, its direction and its weight are shown in business language.
- Given a health score, when a factor changes materially, then the score updates and the change is attributable to the specific factor.

---

## E05 — Lead capture, qualification and routing

### US-E05-01 — Lead capture from web and API (P0, 5) · `FR-LED-001`, `FR-LED-002`, `FR-LED-003`
As a marketing manager, I want leads captured from forms and systems without manual entry.

**Acceptance criteria**
- Given a generated web form, when a valid submission arrives, then a lead is created with source and campaign attribution.
- Given a bulk API submission of 1,000 leads with 12 invalid, when it is processed, then 988 are created and the response identifies each of the 12 with its specific reason — **the batch is not rejected wholesale**.

### US-E05-02 — Duplicate handling on ingestion (P0, 5) · `FR-LED-004`
As a data steward, I want inbound leads checked against existing records.

**Acceptance criteria**
- Given an inbound lead matching an existing contact, when the configured behaviour is "attach", then it is associated rather than duplicated.
- Given an ambiguous match, when the configured behaviour is "review", then it is routed to a review queue rather than guessed.

### US-E05-03 — Lead scoring, rule-based and predictive (P0, 8) · `FR-LED-006`, `FR-LED-007`
As a sales development rep, I want to know which leads to work first and why.

**Acceptance criteria**
- Given a scored lead, when the score is viewed, then the contributing rules and their point values are visible.
- Given a predictive score, when it is displayed, then **its top contributing factors and their direction are shown alongside it**. A score displayed without explanation fails this story.

### US-E05-04 — Assignment and routing (P0, 8) · `FR-LED-008`
As a sales operations manager, I want leads routed by rules with a fallback queue.

**Acceptance criteria**
- Given ordered assignment rules, when a lead is evaluated, then the first matching rule wins and the matched rule is recorded on the lead.
- Given no rule matches, when evaluation completes, then the lead lands in the fallback queue rather than being left unassigned.
- Given round-robin with capacity limits, when an owner is at capacity, then they are skipped.

### US-E05-05 — Speed-to-lead SLA (P0, 5) · `FR-LED-009`
As a sales manager, I want first-response time measured and escalated.

**Acceptance criteria**
- Given a lead assigned, when the SLA timer starts, then it respects the owner's business hours and pauses outside them.
- Given the SLA breached, when the breach occurs, then the configured escalation fires and the breach is reportable.

### US-E05-06 — Qualification framework (P1, 5) · `FR-LED-010`
As a sales manager, I want a consistent qualification framework captured on leads.

**Acceptance criteria**
- Given a configured framework, when a lead is qualified, then its fields are captured and **carried to the opportunity on conversion without re-entry**.

### US-E05-07 — Lead conversion (P0, 8) · `FR-LED-011`
As a sales development rep, I want to convert a qualified lead into an account, contact and opportunity in one step.

**Acceptance criteria**
- Given a lead converted, when conversion completes, then account, contact and optionally opportunity exist, mapped per administrator configuration including custom fields.
- Given conversion, when it completes, then activities, notes and campaign membership transfer to the resulting records and the lead becomes read-only with links to what it became.
- Given a failure partway through conversion, when the failure occurs, then **no partial conversion persists**.

### US-E05-08 — Disqualification and recycling (P0, 3) · `FR-LED-012`
As a sales development rep, I want to disqualify a lead with a reason and return it to nurture.

**Acceptance criteria**
- Given a disqualification attempt without a reason from the governed taxonomy, when saved, then it is refused.
- Given a disqualified lead set to recycle on a future date, when that date arrives, then it re-enters the working queue.

---

## E06 — Opportunity and pipeline management

### US-E06-01 — Opportunity and multiple pipelines (P0, 8) · `FR-OPP-001`, `FR-OPP-002`
As a sales operations manager, I want distinct pipelines with their own stages for different sales motions.

**Acceptance criteria**
- Given multiple pipelines, when an opportunity is created with a record type, then it uses that pipeline's stages, probabilities and forecast categories.

### US-E06-02 — Enforced stage gating (P0, 13) · `FR-OPP-003`
As a sales manager, I want stage exit criteria enforced so pipeline data is trustworthy.

**Acceptance criteria**
- Given unmet exit criteria, when a rep attempts to advance the stage by any route — record page, board drag, API or bulk update — then advancement is refused and the response **names each unsatisfied criterion and the specific action needed**.
- Given criteria changed after an opportunity entered a stage, when advancement is attempted, then **the criteria version in force at stage entry applies**, not the current one.
- Given all criteria met, when advancement occurs, then stage history records entry, exit, duration, actor and the criteria version applied.

### US-E06-03 — Backward and skip transitions (P0, 3) · `FR-OPP-004`
As a sales manager, I want backward moves permitted only where configured and always explained.

**Acceptance criteria**
- Given a pipeline that disallows backward movement, when a rep attempts it, then it is refused.
- Given a permitted backward move, when it occurs, then a reason is required and recorded in stage history.

### US-E06-04 — Line items (P0, 8) · `FR-OPP-005`
As an account executive, I want products on the opportunity with quantities, pricing and discounts.

**Acceptance criteria**
- Given line items, when totals are computed, then the calculation is deterministic and reproducible.
- Given a manually overridden total, when it is saved, then it is **visibly flagged as overridden and the system-computed value is retained** alongside it.

### US-E06-05 — Revenue splits (P1, 8) · `FR-OPP-006`
As a sales operations manager, I want revenue credit split across contributors.

**Acceptance criteria**
- Given revenue splits totalling other than 100%, when saved, then it is refused naming the shortfall or excess.
- Given overlay splits, when saved, then they are unconstrained by the 100% rule.

### US-E06-06 — Deal risk signals (P0, 8) · `FR-OPP-009`
As a sales manager, I want to see which deals are at risk and why.

**Acceptance criteria**
- Given an opportunity with no activity for longer than the configured gap, when it is viewed, then a risk signal states the observation, why it matters and a recommended action.
- Given an opportunity with a single engaged contact, when it is viewed, then a single-threading risk is surfaced.
- Given any risk signal, when it is displayed, then it is traceable to the specific records or absence of records that produced it.

### US-E06-07 — Close date and slippage (P0, 5) · `FR-OPP-010`
As a sales manager, I want every close-date change recorded so slippage is visible.

**Acceptance criteria**
- Given a close date moved beyond the current period, when saved, then a reason is required.
- Given repeated slippage, when the opportunity is reported on, then cumulative slip count and original close date are available.

### US-E06-08 — Closure with governed reasons (P0, 5) · `FR-OPP-012`
As a sales operations manager, I want win/loss reasons captured consistently.

**Acceptance criteria**
- Given closure attempted without a reason from the governed taxonomy, when saved, then it is refused.
- Given a closed opportunity, when any user attempts to edit it, then it is read-only except through the controlled reopen path.

### US-E06-09 — Pipeline board (P0, 8) · `FR-OPP-014`
As an account executive, I want to work my pipeline on a stage board.

**Acceptance criteria**
- Given a drag to a stage whose exit criteria are unmet, when it is dropped, then **the opportunity returns to its original column and the reason is stated** — server-side validation applies identically to the record form.

### US-E06-10 — Pipeline movement view (P1, 8) · `FR-OPP-015`
As a sales manager, I want to see what changed in my pipeline since last week.

**Acceptance criteria**
- Given two points in time, when compared, then added, advanced, slipped, grown, shrunk, won and lost opportunities are each listed.
- Given the comparison, when totals are shown, then the components **reconcile exactly** to the net change with no unexplained residual.

---

## E07 — Activity, email and calendar engagement

### US-E07-01 — Tasks, events and calls (P0, 5) · `FR-ACT-001`, `FR-ACT-002`, `FR-ACT-003`
As an account executive, I want to record what I need to do and what I have done.

**Acceptance criteria**
- Given a task with a due date and reminder, when the due time approaches, then the owner is notified per their preference.
- Given a logged call, when saved, then direction, duration, disposition from a governed list and related records are captured.

### US-E07-02 — Unified activity timeline (P0, 8) · `FR-ACT-004`
As an account executive, I want one timeline across all activity types.

**Acceptance criteria**
- Given activities of every type on related records, when the timeline is viewed, then all appear in one chronological stream with derived metrics — last contacted, days since last activity, count by period.

### US-E07-03 — Email and calendar connection (P0, 8) · `FR-ACT-005`
As an account executive, I want my email and calendar connected so I work in one place.

**Acceptance criteria**
- Given a connected Microsoft 365 or Google Workspace account, when a user sends email from Axiom, then it appears in their sent items and threads correctly in their mail client.
- Given a revoked connection, when sync next runs, then it fails gracefully and the user is told how to reconnect.

### US-E07-04 — Passive activity capture (P0, 13) · `FR-ACT-006`
As an account executive, I want my emails and meetings captured without logging anything.

**Acceptance criteria**
- Given a connected mailbox, when an email is exchanged with a known contact, then it is captured and related to the contact, their account and relevant open opportunities **without any user action**.
- Given an ambiguous participant match, when capture runs, then the item is presented for one-click correction rather than guessed.
- Given an unmatchable item, when capture runs, then it is retained in a review queue rather than discarded.
- Given any captured item, when it is viewed, then the match basis and confidence are visible.

### US-E07-05 — Capture privacy controls (P0, 8) · `FR-ACT-007`
As a user, I want control over what is captured from my mailbox.

**Acceptance criteria**
- Given a user who has not consented, when capture is configured, then nothing from their mailbox is captured.
- Given a domain excluded by the user or the administrator, when messages with that domain are processed, then **they are never stored**, not merely hidden.
- Given consent withdrawn, when withdrawal takes effect, then capture stops and previously captured private items are purged per policy.

### US-E07-06 — Email templates (P0, 5) · `FR-ACT-008`
As a sales development rep, I want reusable templates with merge fields.

**Acceptance criteria**
- Given a template with a merge field that cannot resolve for a recipient, when send is attempted, then **it is blocked with a clear message** rather than sending a visibly broken message.

### US-E07-07 — Engagement tracking and signals (P1, 5) · `FR-ACT-009`
As an account executive, I want to know when a prospect engages.

**Acceptance criteria**
- Given tracking enabled and consent present, when a recipient opens or clicks, then a signal is surfaced to the record owner.
- Given a tenant policy disabling tracking, when email is sent, then no tracking occurs and no signal is generated.

### US-E07-08 — Cadences (P1, 13) · `FR-ACT-010`
As a sales development rep, I want multi-step outreach sequences.

**Acceptance criteria**
- Given a cadence with email, call and task steps and delays, when a lead is enrolled, then steps present in order respecting business hours.
- Given a prospect who replies, when the reply is detected, then they exit the cadence unless configured otherwise.
- Given a suppressed or non-consenting contact, when enrolment is attempted, then **it is refused**.

### US-E07-09 — Telephony integration (P1, 8) · `FR-ACT-011`
As an account executive, I want click-to-dial and automatic call logging.

**Acceptance criteria**
- Given an inbound call from a known number, when it arrives, then the matching record is presented to the agent.
- Given a completed call, when it ends, then a call activity is created with duration and a prompt for disposition.

---

## E08 — Products, price books, quotes and CPQ

### US-E08-01 — Product catalogue and price books (P0, 8) · `FR-CPQ-001`, `FR-CPQ-002`
As a pricing administrator, I want products priced differently by currency, entity and segment.

**Acceptance criteria**
- Given two active price book entries for the same product and book with overlapping effective dates, when saved, then **it is rejected** — exactly one price must resolve for a product, book and date.
- Given a price book entry effective from a future date, when a quote is priced today, then the current entry is used.

### US-E08-02 — Quote creation and sync (P0, 5) · `FR-CPQ-003`
As an account executive, I want a quote built from my opportunity.

**Acceptance criteria**
- Given an opportunity with line items, when a quote is created, then account, contact and line items are inherited.
- Given an accepted quote, when it syncs, then the opportunity amount and line items reflect it and the sync is auditable.

### US-E08-03 — Quote versioning (P0, 8) · `FR-CPQ-004`
As a sales manager, I want to see how a quote changed between versions.

**Acceptance criteria**
- Given a material change to a sent quote, when saved, then a new version is created and the prior version is retained unchanged.
- Given two versions, when compared, then differences are shown at field and line level.
- Given a superseded version, when acceptance is attempted, then it is refused.

### US-E08-04 — Bundles and configuration rules (P1, 13) · `FR-CPQ-005`
As a sales engineer, I want invalid product configurations to be impossible.

**Acceptance criteria**
- Given a configuration violating an exclusion rule, when saved, then it is refused, **naming the violated constraint and the options that would resolve it**.
- Given a bundle with required components, when the bundle is added, then required components are included and cannot be individually removed.

### US-E08-05 — Pricing methods (P1, 13) · `FR-CPQ-007`
As a pricing administrator, I want tiered, volume, block and subscription pricing.

**Acceptance criteria**
- Given a quantity crossing a tier boundary, when priced, then the correct tier applies and the boundary behaviour is unambiguous at the exact boundary value.
- Given any priced line, when it is viewed, then **every adjustment is itemized so the final price is fully derivable** from list price.

### US-E08-06 — Discount approval (P0, 8) · `FR-CPQ-009`
As a sales manager, I want discounts above thresholds approved before a quote goes out.

**Acceptance criteria**
- Given a discount exceeding the threshold, when the rep attempts to send the quote, then **it is refused, naming the outstanding approval and its current approver**.
- Given approval granted, when the quote is sent, then the approval decision, approver and time are recorded on the quote.

### US-E08-07 — Margin floor (P1, 5) · `FR-CPQ-010`
As a finance manager, I want a margin floor that cannot be breached without approval.

**Acceptance criteria**
- Given a quote below the margin floor, when submitted, then approval is required and the shortfall is quantified.

### US-E08-08 — Quote document generation (P0, 8) · `FR-CPQ-011`
As an account executive, I want a branded quote document.

**Acceptance criteria**
- Given a quote and template, when a document is generated, then it is a stable versioned artefact attached to the quote and regenerating from the same version produces equivalent content.

### US-E08-09 — E-signature hand-off (P1, 8) · `FR-CPQ-012`
As an account executive, I want the customer to sign electronically.

**Acceptance criteria**
- Given a quote sent for signature, when the envelope state changes, then the quote reflects sent, viewed, signed, declined or expired.
- Given the e-signature provider unavailable, when send is attempted, then the failure is surfaced with a retry, and the quote is not marked sent.

---

## E09 — Contracts, orders, subscriptions and renewals

### US-E09-01 — Contract record and lines (P1, 8) · `FR-CTR-001`, `FR-CTR-002`
As a contract manager, I want contracts with terms, dates, value and line items.

**Acceptance criteria**
- Given a contract with lines, when saved, then each line links to a product, quantity, price and term forming the basis for renewal and entitlement.

### US-E09-02 — Contract amendment and versioning (P1, 8) · `FR-CTR-003`
As a contract manager, I want amendments that preserve history.

**Acceptance criteria**
- Given an amendment, when saved, then a new version is created with a change reason and the prior version remains immutable.
- Given a reporting request as of a past date, when run, then the contract terms in force at that date are returned.

### US-E09-03 — Subscription lifecycle (P1, 8) · `FR-CTR-005`
As a customer success manager, I want to manage subscription changes.

**Acceptance criteria**
- Given a mid-term upgrade, when applied, then proration is computed and a dated change record is created.
- Given co-terminous subscriptions, when one is amended, then the alignment policy applies and the resulting end dates are shown before confirmation.

### US-E09-04 — Renewal generation (P1, 5) · `FR-CTR-006`
As a customer success manager, I want renewal opportunities created ahead of expiry.

**Acceptance criteria**
- Given a contract approaching expiry at the configured lead time, when the scheduled job runs, then a renewal opportunity is created pre-populated from expiring terms and assigned by rule.
- Given the job runs twice, when it completes, then **only one renewal opportunity exists**.

### US-E09-05 — Entitlements (P1, 8) · `FR-CTR-007`
As a support manager, I want SLA terms driven by what the customer bought.

**Acceptance criteria**
- Given a contract creating an entitlement, when a case is raised for that account, then the entitlement determines response and resolution targets.
- Given an expired entitlement, when a case is raised, then the case is flagged as uncovered rather than silently given a default.

### US-E09-06 — Churn capture (P1, 5) · `FR-CTR-009`
As a revenue operations manager, I want to know why we lose renewals.

**Acceptance criteria**
- Given a non-renewal or downgrade, when recorded, then a reason from the governed taxonomy and the quantified lost value are required.

### US-E09-07 — ERP hand-off (P1, 13) · `FR-CTR-011`
As a finance user, I want orders transmitted to our financial system reliably.

**Acceptance criteria**
- Given an order ready for hand-off, when transmitted, then hand-off state is recorded and retried with backoff on failure.
- Given a hand-off failure, when it persists, then it surfaces on an exception queue with the reason — **it is never silently dropped**.
- Given the same order transmitted twice, when processed downstream, then the idempotency key prevents duplication.

---

## E10 — Forecasting and revenue intelligence

### US-E10-01 — Forecast categories and roll-up (P0, 8) · `FR-FCT-001`, `FR-FCT-002`
As a sales manager, I want my team's forecast rolled up from their opportunities.

**Acceptance criteria**
- Given opportunities across stages, when the forecast is viewed, then each maps to a forecast category derived from stage, individually overridable with a reason.
- Given a management hierarchy, when a manager views their forecast, then contributing amounts from each level below are visible subject to record access.

### US-E10-02 — Manager judgment override (P0, 5) · `FR-FCT-003`
As a sales manager, I want to submit a number that differs from the arithmetic roll-up.

**Acceptance criteria**
- Given an override without a reason, when submitted, then it is refused.
- Given an override, when viewed at any level above, then **both the roll-up and the override are visible and the variance is explicit**.

### US-E10-03 — Forecast submission and snapshot (P0, 8) · `FR-FCT-004`
As a VP of sales, I want submitted forecasts locked and reconstructable.

**Acceptance criteria**
- Given a submitted forecast, when the period later changes, then the submission remains exactly as submitted.
- Given a historical submission, when it is opened, then **the individual contributing opportunities as they were at submission** are available, not merely the total.

### US-E10-04 — Forecast explainability (P0, 8) · `FR-FCT-005`
As a VP of sales, I want to decompose any forecast number to the deals behind it.

**Acceptance criteria**
- Given any forecast figure at any hierarchy level, when drilled into, then the constituent opportunities are listed and each can be opened.
- Given an AI-predicted figure, when displayed, then it is decomposable in the same way — **a prediction that cannot be decomposed is not presented as a forecast number**.

### US-E10-05 — Movement waterfall (P0, 13) · `FR-FCT-006`
As a sales manager, I want to know exactly why my forecast changed since last week.

**Acceptance criteria**
- Given two snapshots, when the waterfall is generated, then new pipeline, advanced, slipped out, pulled in, increased, decreased, won and lost are each quantified.
- Given the waterfall, when totalled, then components **reconcile exactly** to the net change; any residual is shown explicitly rather than absorbed into another category.

### US-E10-06 — AI forecast prediction (P1, 8) · `FR-FCT-007`
As a VP of sales, I want a predicted outcome to sanity-check the submitted forecast.

**Acceptance criteria**
- Given a prediction, when displayed, then it appears **alongside, never in place of**, the submitted human forecast, with a confidence interval and contributing factors.

### US-E10-07 — Pipeline and win/loss analytics (P0, 8) · `FR-FCT-009`, `FR-FCT-010`
As a revenue operations manager, I want coverage, velocity, conversion and win-rate analysis.

**Acceptance criteria**
- Given any of these metrics, when displayed, then **the published formula and its version are visible** and two reports showing the same named metric compute it identically.

### US-E10-08 — Forecast accuracy (P1, 5) · `FR-FCT-011`
As a VP of sales, I want to know which managers forecast accurately.

**Acceptance criteria**
- Given historical submissions and actuals, when accuracy is computed, then bias and accuracy are reported per forecasting user over time.

---

## E11 — Campaigns, segments and marketing alignment

### US-E11-01 — Campaign and members (P1, 5) · `FR-CMP-001`, `FR-CMP-002`
As a marketing manager, I want campaigns with hierarchy, cost and members.

**Acceptance criteria**
- Given a campaign hierarchy, when roll-up is viewed, then cost and results aggregate across child campaigns.

### US-E11-02 — Segment builder (P1, 8) · `FR-CMP-003`
As a marketing manager, I want to build audiences from CRM data.

**Acceptance criteria**
- Given segment criteria across accounts, contacts and opportunities, when built, then live membership count is shown and the segment is saveable and re-runnable.
- Given segment members, when exported or synced, then consent and suppression are enforced.

### US-E11-03 — Marketing platform sync (P1, 8) · `FR-CMP-004`
As a marketing operations manager, I want segments synced to our marketing platform.

**Acceptance criteria**
- Given a sync, when it completes, then per-record status is returned and reconciled, and failures are individually visible.

### US-E11-04 — Attribution (P1, 8) · `FR-CMP-005`
As a marketing manager, I want to compare attribution models.

**Acceptance criteria**
- Given the same pipeline, when first-touch, last-touch and multi-touch are applied, then all three can be displayed side by side.
- Given any attributed figure, when displayed, then **the model and calculation version are named** — an attribution number without its model is not a valid output.

### US-E11-05 — MQL hand-off (P1, 5) · `FR-CMP-006`
As a sales manager, I want marketing-qualified leads accepted or rejected within an SLA.

**Acceptance criteria**
- Given an MQL passed to sales, when the acceptance SLA elapses without a decision, then it escalates.
- Given a rejection, when recorded, then a reason is required and hand-off quality is reportable.

---

## E12 — Cases, entitlements and SLA management

### US-E12-01 — Case record and multi-channel capture (P1, 8) · `FR-CAS-001`, `FR-CAS-002`
As a support agent, I want cases raised from any channel with context intact.

**Acceptance criteria**
- Given an inbound email, when a case is created, then the thread is preserved and subsequent replies attach to the same case.

### US-E12-02 — Assignment and queues (P1, 5) · `FR-CAS-003`
As a support manager, I want cases routed to the right team.

**Acceptance criteria**
- Given routing rules, when a case is created, then it reaches the correct user or queue, and queue claim/release works without two agents taking the same case.

### US-E12-03 — Entitlement-driven SLA (P1, 13) · `FR-CAS-004`
As a support manager, I want SLA targets driven by what the customer bought.

**Acceptance criteria**
- Given an account with an active entitlement, when a case is raised, then response and resolution targets derive from the entitlement and case severity.
- Given a case set to customer-pending, when the clock pauses and later resumes, then **each pause and resume is recorded with actor and reason and the SLA position is reconstructable**.
- Given no matching entitlement, when a case is raised, then a default applies **and the case is flagged as uncovered**.

### US-E12-04 — Escalation (P1, 5) · `FR-CAS-005`
As a support manager, I want breaches and imminent breaches escalated.

**Acceptance criteria**
- Given a milestone approaching breach, when the warning threshold is crossed, then the configured escalation fires and the trigger is recorded.

### US-E12-05 — Knowledge (P2, 8) · `FR-CAS-007`
As a support agent, I want relevant knowledge suggested in case context.

**Acceptance criteria**
- Given a case, when knowledge is suggested, then only articles the agent and, where shared externally, the customer are permitted to see are offered.

### US-E12-06 — Self-service portal (P2, 8) · `FR-CAS-008`
As a customer contact, I want to raise and track my own cases.

**Acceptance criteria**
- Given a portal user, when they query cases, then **only their own account's cases are visible** and no other account's data is reachable by any parameter manipulation.

### US-E12-07 — Service signal to revenue (P2, 5) · `FR-CAS-010`
As a customer success manager, I want support history to inform account health.

**Acceptance criteria**
- Given cases, breaches and satisfaction results, when account health is computed, then their contribution is visible as a named factor.

---

## E13 — Partner, channel and territory management

### US-E13-01 — Partner accounts (P1, 5) · `FR-PTR-001`
As a partner manager, I want partners modelled with tier and agreement status.

### US-E13-02 — Deal registration (P1, 8) · `FR-PTR-002`
As a partner, I want to register a deal and receive protection.

**Acceptance criteria**
- Given a submitted registration, when the approval SLA elapses, then it escalates.
- Given an approved registration approaching expiry, when the threshold is reached, then the partner and partner manager are notified before it lapses.

### US-E13-03 — Channel conflict detection (P2, 8) · `FR-PTR-003`
As a partner manager, I want overlapping claims surfaced.

**Acceptance criteria**
- Given a direct opportunity created for an account with an approved partner registration, when it is saved, then the conflict is detected and routed for resolution.

### US-E13-04 — Channel reporting (P1, 5) · `FR-PTR-005`
As a channel director, I want partner-sourced separated from partner-influenced.

**Acceptance criteria**
- Given both types of pipeline, when reported, then the definitions of sourced and influenced are published alongside the figures.

### US-E13-05 — Territory realignment (P2, 8) · `FR-PTR-006`
As a revenue operations manager, I want to simulate a realignment before applying it.

**Acceptance criteria**
- Given a proposed realignment, when simulated, then affected accounts, users and in-flight opportunities are listed **with no change applied**.
- Given activation, when it occurs, then the configured in-flight policy applies and every reassignment is audited.

---

## E14 — Workflow automation, approvals and rules engine

### US-E14-01 — Record-triggered automation (P0, 13) · `FR-AUT-001`, `FR-AUT-006`
As an administrator, I want automation on record changes.

**Acceptance criteria**
- Given an entry condition comparing old and new values, when a record changes, then the automation runs only when the condition transitions to true.
- Given an automation action failing, when it fails, then the outcome is recorded per step and the failure is visible without inspecting logs.

### US-E14-02 — Enforced business process (P0, 13) · `FR-AUT-004`
As a sales operations manager, I want a state machine that enforces our process everywhere.

**Acceptance criteria**
- Given a transition not defined in the state model, when attempted through UI, API, automation or bulk update, then **it cannot occur by any path**.
- Given a state with mandatory fields, when transition is attempted without them, then it is refused naming the unsatisfied condition.

### US-E14-03 — Visual builder (P0, 13) · `FR-AUT-003`
As a tenant administrator without programming skill, I want to build automation visually.

**Acceptance criteria**
- Given a trained administrator, when they build a multi-branch automation with a loop over related records, then they complete it **without writing code and without vendor assistance**.

### US-E14-04 — Approval processes (P0, 13) · `FR-AUT-007`
As a sales manager, I want multi-step approvals with dynamic approvers.

**Acceptance criteria**
- Given an approval matrix by amount, when a request is submitted, then the correct approver is determined and notified.
- Given parallel approvers with unanimous semantics, when one rejects, then the request is rejected with the reason.
- Given the submitter attempting to approve, when attempted, then it is refused per maker-checker.
- Given a recall by the submitter before decision, when recalled, then the request is withdrawn and approvers are notified.

### US-E14-05 — Expression language (P0, 8) · `FR-AUT-009`
As an administrator, I want formulas with a syntax checker and test evaluator.

**Acceptance criteria**
- Given an invalid expression, when saved, then it is refused with the error position identified.
- Given a valid expression, when tested against a sample record, then the evaluated result is shown before activation.

### US-E14-06 — Automation simulation (P1, 13) · `FR-AUT-010`
As an administrator, I want to see what an automation would do before it does it.

**Acceptance criteria**
- Given an inactive automation and a selected set of real records, when simulation runs, then every action that would occur is listed and **none of them occurs**.

### US-E14-07 — Execution log (P0, 8) · `FR-AUT-011`
As an administrator, I want to know why an automation did or did not fire.

**Acceptance criteria**
- Given any execution, when the log is viewed, then trigger, entry-condition outcome, each step, each action result and duration are shown, filterable by record.

### US-E14-08 — Loop and recursion protection (P0, 8) · `FR-AUT-012`
As an administrator, I want cascading automation halted with a useful diagnostic.

**Acceptance criteria**
- Given two automations that trigger each other, when the cycle is detected, then execution halts and the diagnostic **names the participating rules and the cycle**, not merely "limit exceeded".

### US-E14-09 — No rule-count limits (P0, 3) · `FR-AUT-014`
As a tenant administrator, I want no arbitrary cap on how many rules I can create.

**Acceptance criteria**
- Given a tenant with many automation rules, when another is created, then it is permitted; resource protection is by fair-use throttling with visible telemetry, never a count cap.

---

## E15 — Reporting, dashboards and analytics

### US-E15-01 — Report builder (P0, 13) · `FR-RPT-001`, `FR-RPT-002`
As a sales manager, I want to build my own reports without an administrator.

**Acceptance criteria**
- Given a report type joining related objects, when built with filters, groupings and summaries, then results are correct and the user needed no administrator involvement.
- Given a "without related records" report type, when run, then records lacking the related object are returned.

### US-E15-02 — Dashboards (P0, 8) · `FR-RPT-004`
As a VP of sales, I want dashboards composed from reports.

### US-E15-03 — Access-aware results (P0, 8) · `FR-RPT-005`
As a compliance officer, I want reports to respect each viewer's access.

**Acceptance criteria**
- Given two users with different record access, when they run the same report, then each sees only their permitted records.
- Given a dashboard configured to run as a specified user, when any viewer opens it, then **that fact is displayed to them**.

### US-E15-04 — Drill-through (P0, 5) · `FR-RPT-006`
As a sales manager, I want to click an aggregate and see the records behind it.

**Acceptance criteria**
- Given an aggregate from the reporting projection, when drilled into, then the authoritative records are returned **after a fresh permission check**, not from the projection's cached view of access.

### US-E15-05 — Scheduled delivery and threshold alerts (P0, 8) · `FR-RPT-007`
As a VP of sales, I want reports delivered and to be told when a metric crosses a bound.

**Acceptance criteria**
- Given a subscription with a threshold, when the metric does not cross it, then no notification is sent.
- Given scheduled delivery, when recipients lack access to underlying records, then each recipient's copy reflects their own access.

### US-E15-06 — Historical trending (P1, 8) · `FR-RPT-008`
As a revenue operations manager, I want to trend pipeline over time.

**Acceptance criteria**
- Given scheduled snapshots, when a trend report is run, then figures come from the snapshots and are stable — re-running the report later returns the same historical values.

### US-E15-07 — Governed KPI definitions (P0, 8) · `FR-RPT-009`
As a revenue operations manager, I want one definition per metric across the whole product.

**Acceptance criteria**
- Given the same named metric in two different reports, when both are run on the same data, then **they produce identical values**.
- Given any standard metric, when displayed, then its published formula and version are accessible from where it appears.

### US-E15-08 — Export governance (P0, 5) · `FR-RPT-010`
As a compliance officer, I want exports controlled and recorded.

**Acceptance criteria**
- Given an export above the row threshold, when attempted, then approval is required first.
- Given any export, when it completes, then actor, object, filter criteria, row count and destination are audited.

---

## E16 — AI copilot and agentic assistance · **differentiator**

### US-E16-01 — Record summarization with citations (P0, 8) · `FR-AIX-002`, `FR-AIX-007`
As an account executive, I want a summary of an account before a call.

**Acceptance criteria**
- Given an account, when a summary is requested, then it covers current state, recent activity, open items and risks.
- Given a summary, when displayed, then **every claim cites the specific records it came from and the citations are navigable**.
- Given a claim the system cannot ground in a record, when the summary is produced, then that claim is omitted or explicitly labelled as unsupported.

### US-E16-02 — Permission-scoped grounding (P0, 13) · `FR-AIX-003`, `FR-AIX-010`
As a compliance officer, I want the assistant to be incapable of surfacing data the user cannot see.

**Acceptance criteria**
- Given a user without access to an opportunity, when they ask the assistant a question whose answer would require it, then **the opportunity is not retrieved, not cited, not summarized and not reasoned over**.
- Given two users with different access asking the same question, when both are answered, then each answer reflects only their own permitted data.
- Given any AI request, when grounding retrieval executes, then it runs under the calling user's principal through the ordinary authorization path — **no privileged service-account retrieval path exists**.

### US-E16-03 — Next-best-action (P0, 8) · `FR-AIX-003`
As an account executive, I want to be told what to do next and why.

**Acceptance criteria**
- Given an opportunity, when recommendations are requested, then each states the observation, why it matters and the specific action.
- Given a recommendation, when it is shown, then it is traceable to the records or absence of records that produced it.

### US-E16-04 — Grounded drafting (P0, 8) · `FR-AIX-004`
As an account executive, I want a draft email or call-prep note based on the record.

**Acceptance criteria**
- Given a draft, when generated, then it is **never sent automatically** — the user reviews and sends explicitly.
- Given a sent draft, when the activity is created, then its AI provenance is recorded.

### US-E16-05 — Conversational query (P0, 13) · `FR-AIX-005`
As a sales manager, I want to ask questions of my data in plain language.

**Acceptance criteria**
- Given a question, when answered, then **how the question was interpreted is displayed** so a misinterpretation is detectable.
- Given a question that cannot be answered reliably, when processed, then the system says so — **it must not produce a plausible answer it cannot substantiate**.
- Given an answer, when displayed, then the underlying records are accessible.

### US-E16-06 — Score decomposition (P0, 8) · `FR-AIX-006`, `FR-AIX-008`
As a sales manager, I want to understand every score.

**Acceptance criteria**
- Given any score — lead, deal, renewal, health — when viewed, then weighted contributing factors with direction and magnitude are shown **in business language, not model-internal terms**.

### US-E16-07 — Agentic execution with confirmation (P1, 13) · `FR-AIX-009`
As an account executive, I want the assistant to carry out multi-step work I approve.

**Acceptance criteria**
- Given a requested task, when the agent plans it, then **the complete plan and exact changes are shown before anything happens**.
- Given confirmation, when the agent executes, then every action is attributed to the AI source with the initiating user.
- Given a step that fails, when the failure occurs, then the sequence halts and reports what completed and what did not — **partial silent completion is not acceptable**.
- Given a completed action set, when the user reverses it, then the whole set is undone as a unit within the retention window.

### US-E16-08 — PII masking and no-training guarantee (P0, 8) · `FR-AIX-011`, `FR-AIX-012`
As a compliance officer, I want tenant data protected in AI processing.

**Acceptance criteria**
- Given designated sensitive fields, when a model is invoked, then they are masked or excluded per tenant policy and the applied policy is recorded.
- Given any tenant's data, when models are trained or fine-tuned, then it is not used — verifiable contractually and technically.
- Given an erasure request, when processed, then **embeddings and AI caches are reached** along with primary storage.

### US-E16-09 — AI-off mode (P0, 5) · `FR-AIX-013`
As a sovereign customer, I want to run the product with AI entirely disabled.

**Acceptance criteria**
- Given AI disabled, when any user works, then AI surfaces are **absent — not shown as errors, not shown as upsells**.
- Given AI disabled, when every non-AI acceptance test in this backlog is executed, then all pass.

### US-E16-10 — Evaluation harness and telemetry (P1, 5) · `FR-AIX-015`, `FR-AIX-016`
As a product owner, I want AI quality measured and regressions blocked.

**Acceptance criteria**
- Given a model or prompt change, when the evaluation suite runs, then quality metrics are produced and a regression blocks release.
- Given a tenant administrator, when they view AI telemetry, then usage, latency, cost and user acceptance/rejection rates are visible.

---

## E17 — Integration platform, APIs, webhooks and events

### US-E17-01 — Complete REST API (P0, 13) · `FR-INT-001`
As an integration developer, I want every capability available through the API.

**Acceptance criteria**
- Given any operation available in the UI, when attempted via API, then it is available — **nothing is UI-only**.
- Given the API, when the specification is requested, then a published, versioned OpenAPI document describes it accurately.

### US-E17-02 — Bulk API (P0, 8) · `FR-INT-002`
As an integration developer, I want high-volume operations.

**Acceptance criteria**
- Given a bulk job with some invalid records, when it completes, then valid records are processed, and per-record results plus a downloadable error file identify each failure.

### US-E17-03 — No tier-based API limits (P0, 5) · `FR-INT-003`
As a customer on any tier, I want full API access.

**Acceptance criteria**
- Given tenants on different commercial tiers, when they use the API, then **no tier grants more API capability or volume than another**; throttling is uniform fair use with published limits and standard rate-limit headers.

### US-E17-04 — Idempotency (P0, 5) · `FR-GLOBAL-006`
As an integration developer, I want safe retries.

**Acceptance criteria**
- Given a write submitted twice with the same idempotency key, when both are processed, then the effect occurs once and both calls return the same result.

### US-E17-05 — Webhooks (P0, 8) · `FR-INT-005`
As an integration developer, I want reliable event delivery to my endpoint.

**Acceptance criteria**
- Given an endpoint returning errors, when delivery fails, then retries occur with exponential backoff and the message reaches a dead-letter queue after the bound.
- Given a payload, when received, then its signature verifies.
- Given a dead-lettered message, when replayed, then it is delivered.

### US-E17-06 — Event stream (P1, 8) · `FR-INT-006`
As a data engineer, I want a near-real-time stream for replication.

**Acceptance criteria**
- Given a consumer, when it processes the stream, then per-record ordering holds and **gaps are detectable** rather than silent.

### US-E17-07 — Integration health (P1, 8) · `FR-INT-009`
As an administrator, I want to know when an integration is failing.

**Acceptance criteria**
- Given a failing integration, when the configured window elapses, then it surfaces to a human — **an integration failing silently is a defect**.
- Given failures, when viewed, then last successful sync, failure count, error detail, affected records and a retry action are available.

---

## E18 — Data migration and onboarding · **differentiator**

### US-E18-01 — Source connection (P0, 8) · `FR-MIG-001`
As a new customer administrator, I want to connect my existing CRM using my own credentials.

**Acceptance criteria**
- Given Salesforce, Zoho or HubSpot credentials, when connected, then the connection is read-only and available objects with record counts are enumerated.

### US-E18-02 — Schema discovery and mapping (P0, 13) · `FR-MIG-002`
As a new customer administrator, I want field mapping proposed for me.

**Acceptance criteria**
- Given a connected source including custom objects and fields, when discovery runs, then a mapping is proposed, reviewable and correctable.
- Given fields with no target, when the mapping is presented, then **they are listed explicitly** — silent omission of source data is not acceptable.

### US-E18-03 — Dry run (P0, 13) · `FR-MIG-003`
As a new customer administrator, I want to know exactly what will happen before it happens.

**Acceptance criteria**
- Given a configured migration, when a dry run executes, then **nothing is written** and a report shows records to be created per object, validation failures with reasons, duplicates detected, unmapped fields and referential gaps.

### US-E18-04 — Relationship preservation (P0, 8) · `FR-MIG-004`
As a new customer administrator, I want my relationships intact after migration.

**Acceptance criteria**
- Given account hierarchies, contact-account relationships and opportunity links in the source, when migrated, then they are preserved.
- Given a relationship that cannot be resolved, when migration completes, then **it is reported with both endpoints named**, not silently dropped.

### US-E18-05 — Reconciliation report (P0, 8) · `FR-MIG-006`
As a new customer administrator, I want proof the migration was complete.

**Acceptance criteria**
- Given a completed migration, when the reconciliation report is produced, then source and target counts per object and monetary sums for financial fields are compared, and every non-migrated record is listed with its reason.

### US-E18-06 — Rollback (P0, 13) · `FR-MIG-007`
As a new customer administrator, I want to undo a migration that went wrong.

**Acceptance criteria**
- Given a completed migration within the retention window, when rollback is executed, then every record it created is removed and the tenant returns to its pre-migration state.
- Given rollback, when it completes, then it is audited and reports exactly what was removed.

### US-E18-07 — Delta re-sync (P1, 8) · `FR-MIG-008`
As a new customer administrator, I want to run both systems in parallel during cutover.

**Acceptance criteria**
- Given a prior migration, when a delta re-sync runs, then only records created or changed in the source since the last run are processed and **previously migrated records are not duplicated**.

### US-E18-08 — Guided onboarding (P0, 5) · `FR-MIG-009`
As a new user, I want to know what to do first.

**Acceptance criteria**
- Given a new tenant, when a user of a given role logs in, then a role-specific checklist is presented and completion is tracked.

---

## E19 — Administration, configuration, sandbox and release

### US-E19-01 — Custom objects and fields (P0, 13) · `FR-ADM-001`, `FR-ADM-002`
As a tenant administrator, I want to extend the data model myself.

**Acceptance criteria**
- Given a custom object and fields, when created, then they participate fully in security, automation, reporting, search and API — **identically to built-in objects**.
- Given a field type change that would lose data, when attempted, then it is blocked with a clear statement of what would be lost.
- Given slot capacity exhausted, when another field is created, then the message **names the limit and the expansion path** rather than failing opaquely.

### US-E19-02 — No tier-based schema limits (P0, 3) · `FR-ADM-001`
As a tenant administrator on any tier, I want no arbitrary object or field cap.

**Acceptance criteria**
- Given tenants on different tiers, when each creates custom objects and fields, then **no tier permits more than another**.

### US-E19-03 — Record types and layouts (P0, 8) · `FR-ADM-003`
As a tenant administrator, I want different layouts for different processes.

**Acceptance criteria**
- Given conditional visibility rules, when a controlling field changes, then dependent fields and sections show or hide, and **hidden fields are not submitted or persisted from the client**.

### US-E19-04 — Sandbox in every tier (P0, 13) · `FR-ADM-005`
As a tenant administrator, I want a safe place to test changes.

**Acceptance criteria**
- Given any commercial tier, when a sandbox is requested, then it is available with a full configuration copy and configurable data.
- Given a sandbox, when it is created, then **outbound email, webhooks and integrations are disabled by default** and enabling them requires explicit action with a warning.

### US-E19-05 — Change promotion (P0, 13) · `FR-ADM-006`
As a tenant administrator, I want to promote configuration safely.

**Acceptance criteria**
- Given a change set, when validated against the target, then results are reported **without applying anything**.
- Given a failed deployment, when it fails, then the target is unchanged and **every** blocking issue is reported, not just the first.
- Given a change set, when compared to the target, then a diff is shown before deployment.

### US-E19-06 — Setup audit trail (P0, 5) · `FR-ADM-008`
As a compliance officer, I want every configuration change recorded.

**Acceptance criteria**
- Given any administrative change, when made, then actor, timestamp, component and before/after values are recorded and retained per audit policy.

### US-E19-07 — Data import (P0, 8) · `FR-ADM-009`
As an administrator, I want to import records from a file with a clear error report.

**Acceptance criteria**
- Given a file with some invalid rows, when imported, then valid rows load and a downloadable error file identifies each failed row and its specific reason.

### US-E19-08 — Mass operations (P0, 8) · `FR-ADM-010`
As an administrator, I want bulk changes with safeguards.

**Acceptance criteria**
- Given a mass delete, when confirmed, then the **exact record count** is stated before proceeding.
- Given a mass operation above the volume threshold, when attempted, then step-up authentication is required.
- Given any mass operation, when it completes, then it is fully audited.

### US-E19-09 — Administration without vendor dependency (P0, 5) · `FR-ADM-014`
As a tenant administrator, I want to run my own system.

**Acceptance criteria**
- Given a trained administrator, when they perform every routine task in this epic, then **none requires vendor intervention, professional services or code deployment**.
- Given any task that does require vendor involvement, when documentation is checked, then it is explicitly listed as such — **an undocumented vendor dependency is a defect**.

---

## E20 — Audit, compliance, observability and governance

### US-E20-01 — Immutable audit (P0, 8) · `FR-AUD-001`, `FR-GLOBAL-005`
As a compliance officer, I want an audit trail nobody can alter.

**Acceptance criteria**
- Given any user or administrator, including a platform operator, when they attempt to modify or delete an audit event by any available path, then **it is impossible** — not merely permission-denied at the application layer.
- Given any material action, when it occurs, then an audit event records actor, time, action, before/after, source, reason where required and correlation ID.

### US-E20-02 — Field change history (P0, 5) · `FR-AUD-002`
As a sales manager, I want to know who changed a close date and when.

### US-E20-03 — Export and read auditing (P0, 5) · `FR-AUD-003`, `FR-AUD-005`
As a compliance officer, I want to know who saw and who took sensitive data.

**Acceptance criteria**
- Given a sensitive field revealed, when it is viewed, then a read-audit event is recorded.
- Given any export, when it completes, then filter criteria and row count are recorded, not merely the fact of an export.

### US-E20-04 — Tamper evidence (P1, 8) · `FR-AUD-007`
As an auditor, I want to detect if the audit trail has been altered.

**Acceptance criteria**
- Given the audit chain, when verified, then any modification or removal of a historical event is detectable, including via the per-tenant sequence gap.

### US-E20-05 — Data subject requests (P0, 13) · `FR-AUD-008`
As a data protection officer, I want to fulfil access and erasure requests.

**Acceptance criteria**
- Given an erasure request, when executed, then personal data is removed or irreversibly pseudonymized across **all** objects, backups and derived stores **including search indexes, reporting projections, snapshots, AI caches and embeddings**.
- Given a store the erasure process cannot reach, when erasure runs, then **it is reported as unreachable, not silently skipped**.
- Given completed erasure, when audited, then a non-personal record that erasure occurred is retained.

### US-E20-06 — Consent register (P0, 5) · `FR-AUD-009`
As a compliance officer, I want consent history with lawful basis.

### US-E20-07 — Retention policies (P1, 8) · `FR-AUD-010`
As a compliance officer, I want automated retention with legal hold.

**Acceptance criteria**
- Given a record under legal hold, when a retention policy would destroy it, then **the hold wins** and the conflict is reported.

### US-E20-08 — Complete tenant export (P0, 8) · `FR-AUD-013`
As a tenant administrator on any tier, I want all my data back on demand.

**Acceptance criteria**
- Given any commercial tier, when an administrator initiates a full export, then it completes **without vendor assistance** and includes custom objects, attachments, audit history and configuration in a documented open format with a manifest and checksums.

### US-E20-09 — Observability (P0, 5) · `FR-AUD-014`
As a platform operator, I want to diagnose failures without exposing data.

**Acceptance criteria**
- Given any request, when it is traced, then a correlation ID links every layer and downstream call.
- Given any log or metric, when inspected, then **it contains no credentials, tokens or unmasked personal data**.

---

## E21 — Mobile and offline field access

### US-E21-01 — Responsive interface (P0, 8) · `FR-MOB-001`
As a field rep, I want the full product on a tablet.

**Acceptance criteria**
- Given any non-administrative capability, when used on a tablet or phone viewport, then it is available and usable.

### US-E21-02 — Native applications (P1, 13) · `FR-MOB-002`
As a field rep, I want a native app for record access, activity capture, approvals and search.

### US-E21-03 — Quick capture (P1, 5) · `FR-MOB-003`
As a field rep, I want to log a call in seconds after a meeting.

### US-E21-04 — Notifications (P1, 3) · `FR-MOB-004`
As a sales manager, I want to be notified of approvals and SLA warnings.

**Acceptance criteria**
- Given configured quiet hours, when a non-urgent notification is due, then it is deferred.

### US-E21-05 — Offline capture and sync (P2, 13) · `FR-MOB-005`, `FR-MOB-006`
As a field rep, I want to work without connectivity.

**Acceptance criteria**
- Given cached records, when offline, then they are readable and **the cache age is visible**.
- Given an offline edit conflicting with a server change, when sync occurs, then **the conflict is presented for the user to resolve** — silent last-write-wins is not acceptable.

---

## E22 — BFSI vertical pack · **differentiator**

### US-E22-01 — Pack framework (P1, 13) · `FR-BFS-013`
As a platform operator, I want vertical packs installable and removable as a unit.

**Acceptance criteria**
- Given a pack installed, when it adds objects, fields, layouts, automation, roles and reports, then **no core object semantics change**.
- Given a pack uninstalled, when confirmed, then it states exactly what data would be affected and requires explicit confirmation.
- Given a tenant without the pack, when the core product is used, then behaviour is identical to a build without the pack.

### US-E22-02 — Relationship manager book (P1, 8) · `FR-BFS-001`, `FR-BFS-002`
As a relationship manager, I want one view of my clients and their portfolios.

### US-E22-03 — KYC onboarding (P1, 13) · `FR-BFS-003`
As a KYC analyst, I want an onboarding workflow that cannot be short-circuited.

**Acceptance criteria**
- Given incomplete KYC, when relationship activation is attempted, then **it is blocked, naming the outstanding items and their owner**.
- Given a document approaching expiry, when the threshold is reached, then the owner is notified.

### US-E22-04 — Risk rating (P1, 8) · `FR-BFS-004`
As a KYC analyst, I want a defensible risk rating.

**Acceptance criteria**
- Given a computed rating, when viewed, then every weighted factor is visible.
- Given a rating change, when it occurs, then actor, rationale and time are audited.

### US-E22-05 — Screening (P1, 8) · `FR-BFS-005`
As a compliance officer, I want sanctions and PEP screening enforced.

**Acceptance criteria**
- Given a screening hit, when onboarding progression is attempted, then **it is blocked until an authorized reviewer dispositions the hit with a rationale**.
- Given a screening run, when it completes, then the run, its result and every disposition are recorded.

### US-E22-06 — Product holdings and whitespace (P1, 5) · `FR-BFS-008`
As a relationship manager, I want to see what a client holds and what they do not.

### US-E22-07 — Suitability (P1, 8) · `FR-BFS-009`
As a compliance officer, I want recommendations constrained by suitability.

**Acceptance criteria**
- Given a product outside the client's assessed suitability, when a recommendation is attempted, then it requires a documented override with reason and approval — **it cannot be issued silently**.
- Given an expired suitability assessment, when a recommendation is attempted, then it is blocked pending reassessment.

### US-E22-08 — Communication archiving (P1, 8) · `FR-BFS-010`
As a compliance officer, I want client communications retained immutably.

**Acceptance criteria**
- Given archived communications, when a legal hold applies, then deletion is suspended regardless of retention policy.
- Given an archive, when searched by client, RM, date or content, then results are returned within the defined service window.

---

## E23 — Commodity trading vertical pack · **differentiator**

### US-E23-01 — Counterparty extension (P1, 5) · `FR-CTM-001`
As a trade originator, I want counterparty attributes on the account.

**Acceptance criteria**
- Given counterparty fields mastered by the trading system, when displayed, then they are **read-only and show their source and last-sync time**.

### US-E23-02 — Master agreement gating (P1, 5) · `FR-CTM-002`
As a compliance officer, I want origination blocked without an executed master agreement.

**Acceptance criteria**
- Given no executed master agreement, when an origination is advanced past the configured stage, then **it is blocked, naming the missing agreement**.

### US-E23-03 — Credit gate (P1, 8) · `FR-CTM-003`
As a credit officer, I want deals gated on available credit headroom.

**Acceptance criteria**
- Given credit data from the trading system, when displayed, then limit, utilisation, headroom, **source and as-of timestamp** are shown.
- Given credit data staler than the configured threshold, or unavailable, when the gate is evaluated, then **it fails closed and states why** — it never passes on missing data and never presents a stale number as current.
- Given the CRM, when credit is displayed, then **the CRM has not computed it** — it is displayed as received.

### US-E23-04 — Origination pipelines (P1, 8) · `FR-CTM-004`, `FR-CTM-006`
As a trade originator, I want pipelines that match how deals actually originate.

**Acceptance criteria**
- Given term, spot/cargo, tender and structured origination types, when created, then each uses its own pipeline, stages and exit criteria.
- Given a cargo enquiry, when captured, then commodity, grade, quantity and tolerance, delivery window, locations and incoterm are recorded **as enquiry attributes, not as a nomination or scheduled movement**.

### US-E23-05 — Tender management (P1, 8) · `FR-CTM-005`
As a trade originator, I want tenders tracked to their deadline.

**Acceptance criteria**
- Given a tender approaching its submission deadline, when thresholds are crossed, then escalating reminders fire.
- Given a tender not submitted by its deadline, when the deadline passes, then it auto-closes as lapsed with that reason recorded.

### US-E23-06 — Indicative pricing (P1, 5) · `FR-CTM-007`
As a trade originator, I want to quote formula-based indicative prices.

**Acceptance criteria**
- Given a formula indication, when displayed, then index, differential, quotation period and settlement convention are shown as a human-readable expression.
- Given an indication, when presented, then it is **explicitly labelled indicative and non-binding**, and **no settlement price or mark-to-market value is computed by the CRM**.

### US-E23-07 — Deal-agreed hand-off (P1, 13) · `FR-CTM-009`
As a trade originator, I want an agreed deal to reach the trading system reliably.

**Acceptance criteria**
- Given an origination closed as won, when hand-off is emitted, then it contains counterparty, commodity, quantity and tolerance, delivery terms, pricing basis, agreed period and the originating record reference.
- Given hand-off delivered twice, when processed, then the idempotency key `(origination_id, version)` makes it a no-op.
- Given hand-off not acknowledged, when the retry bound is reached, then it surfaces on an exception queue and **the origination is not reported as handed off**.
- Given acknowledgement, when received, then the returned trade reference is stored on the origination.

### US-E23-08 — Generic CTRM/ETRM connector (P1, 8) · `FR-CTM-010`
As a platform architect, I want the trading integration to work with any CTRM.

**Acceptance criteria**
- Given a CTRM implementing the five-capability contract, when connected, then all pack functions operate **without code changes to the core or the pack**.
- Given the trading system unavailable, when users work, then **the CRM remains fully usable** for relationship and origination work, credit gates fail closed, and hand-offs queue for later delivery.

---

## Related documents

- [FRD](03-frd.md) — the requirements these stories realize
- [Acceptance tests](06-acceptance-tests.md) — the cases that verify these criteria
- [UAT plan](07-uat-plan.md) — per-persona business validation
- [Agile delivery plan](15-agile-delivery-plan.md) — sprint and release allocation
- [System design](../architecture/system-design.md) — the architecture these stories are built on

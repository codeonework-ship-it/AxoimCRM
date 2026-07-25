# Acceptance tests and edge-case catalogue

The verification contract for Axiom. Every case here traces to a requirement in [the FRD](03-frd.md) and a story in [the backlog](05-epics-and-stories.md); every `US-` story in that backlog appears in at least one case's traceability. A story whose cases fail is not done, whatever its ticket says.

This catalogue states **what must be observed**, not how the test is implemented. Automation strategy, tooling and data volumes live in [the QA master test plan](../../qa/qa-master-test-plan.md) and [the automation scenario document](../../qa/QA-Automation-Test-Case-Scenarios.md).

## 1. Test levels

Cases in this catalogue are executed at the lowest level that can genuinely falsify them, per the level definitions in [the QA master test plan](../../qa/qa-master-test-plan.md):

| Level | What it proves | Typical cases here |
|---|---|---|
| **Domain** | Business rules in isolation — pricing, splits, waterfall arithmetic, state machines | `TC-`, `EC-`, `NEG-` cases about calculation and transition rules |
| **Persistence** | Tenant isolation (RLS as an independent layer), constraints, soft delete, audit immutability | `SEC-`, `INT-` cases about the data tier |
| **API / contract** | Every capability via the public API, idempotency, error shape, OpenAPI accuracy | `TC-E17-*`, `NEG-` cases exercised through the API surface |
| **Workflow** | Automation, approvals, SLA clocks, schedulers, outbox consumers | `TC-E14-*`, `CON-` redelivery cases |
| **End-to-end** | The golden journeys through the real UI and API together | §2 journeys |
| **Security** | Cross-tenant, RBAC, FLS, maker-checker, AI grounding — attempted, not assumed | `SEC-` series |
| **Concurrency** | Races, duplicate delivery, double-run jobs | `CON-` series |
| **Data integrity** | Referential integrity, reconciliation, erasure completeness | `INT-` series |
| **Non-functional** | The targets in [doc 10](10-nfr-and-enterprise-readiness.md), measured not asserted | `NFR-` series |

### ID conventions

| Prefix | Meaning | Format |
|---|---|---|
| `TC-` | Functional acceptance case | `TC-E<nn>-<nnn>` |
| `EC-` | Edge / boundary case | `EC-E<nn>-<nnn>` |
| `NEG-` | Negative case — the system must refuse, and refuse well | `NEG-E<nn>-<nnn>` |
| `SEC-` | Security, tenancy and authorization case | `SEC-<nnn>` |
| `CON-` | Concurrency, idempotency and race case | `CON-<nnn>` |
| `INT-` | Data-integrity and reconciliation case | `INT-<nnn>` |
| `NFR-` | Non-functional case keyed to [doc 10](10-nfr-and-enterprise-readiness.md) | `NFR-<nnn>` |

A negative case passes only when the refusal is **actionable**: the response names what was refused, why, and what would satisfy the rule (`FR-GLOBAL-003`). "Operation failed" is itself a failure.

---

## 2. Golden journeys

Three end-to-end journeys that must pass, in full, before any release. Each step names the catalogue cases that verify it in depth — the journey proves the steps compose; the cases prove each step is right.

### Journey A — Lead to cash

| # | Step | Expected result | Verified in depth by |
|--:|---|---|---|
| A1 | Prospect submits the web-to-lead form | Lead created with source and campaign attribution | TC-E05-001 |
| A2 | Ingestion dedupe runs against existing records | Match to an existing contact attaches rather than duplicates; ambiguous match goes to review | TC-E05-003, EC-E05-001 |
| A3 | Scoring executes | Rule score with visible point contributions; predictive score with factors and direction | TC-E05-004, TC-E05-005 |
| A4 | Assignment rules route the lead | First matching rule wins and is recorded; no match lands in the fallback queue | TC-E05-006, EC-E05-002 |
| A5 | Speed-to-lead SLA clock starts | Clock respects owner business hours; breach fires the configured escalation and is reportable | TC-E05-008 |
| A6 | SDR qualifies and converts | Account, contact and opportunity created atomically; qualification fields carried without re-entry; lead becomes read-only with links | TC-E05-009, TC-E05-010, TC-E05-011, INT-001, INT-002 |
| A7 | Opportunity picks up its pipeline | Record type determines stages, probabilities and forecast categories | TC-E06-001 |
| A8 | AE attempts stage advance with unmet exit criteria | Refused by form, board, API and bulk alike, naming each unsatisfied criterion | NEG-E06-001, EC-E06-002 |
| A9 | Criteria met; stage advances | Stage history records entry, exit, duration, actor and criteria version | TC-E06-002 |
| A10 | Quote created from the opportunity | Account, contact and line items inherited; pricing resolves from the effective price book entry | TC-E08-002, TC-E08-001 |
| A11 | AE applies a discount above threshold and tries to send | Send refused, naming the outstanding approval and its current approver | NEG-E08-004 |
| A12 | Manager approves; quote is sent for e-signature | Approval decision, approver and time recorded; envelope states tracked through signed | TC-E08-008, TC-E08-011 |
| A13 | Opportunity closed won with a governed reason | Accepted quote synced to the opportunity; closed record read-only outside the reopen path | TC-E08-003, TC-E06-009 |
| A14 | Forecast reflects the win | The deal moves to its closed category in the roll-up; the movement waterfall shows it under "won" and reconciles exactly | TC-E10-001, TC-E10-007, EC-E10-001, INT-008 |

### Journey B — Renewal

| # | Step | Expected result | Verified in depth by |
|--:|---|---|---|
| B1 | Contract created from the closed-won order, with lines | Each line carries product, quantity, price and term — the basis for renewal and entitlement | TC-E09-001 |
| B2 | Contract creates an entitlement | Cases raised for the account derive response and resolution targets from it | TC-E09-007 |
| B3 | Contract approaches expiry at the configured lead time | Scheduled job creates one renewal opportunity, pre-populated from expiring terms, assigned by rule | TC-E09-006 |
| B4 | The renewal job runs a second time | Still exactly one renewal opportunity | EC-E09-001, CON-010 |
| B5a | Renewal won | Renewal opportunity closes won with governed reason; new contract term begins | TC-E06-009 |
| B5b | Renewal lost or downgraded | Churn recorded with a governed reason and quantified lost value — refusal without them | TC-E09-008, NEG-E09-002 |

### Journey C — Support escalation

| # | Step | Expected result | Verified in depth by |
|--:|---|---|---|
| C1 | Customer emails support | Case created with the thread preserved; later replies attach to the same case | TC-E12-001 |
| C2 | Routing assigns the case | Correct user or queue; two agents cannot claim the same case | TC-E12-002, CON-007 |
| C3 | Entitlement SLA attaches | Response and resolution targets derive from the entitlement and severity; no entitlement means default targets **and** an uncovered flag | TC-E12-003, EC-E12-001 |
| C4 | Case set to customer-pending, then resumed | Each pause and resume recorded with actor and reason; SLA position reconstructable | TC-E12-004 |
| C5 | Warning threshold crossed, then breach | Configured escalation fires at each threshold and the trigger is recorded | TC-E12-005 |
| C6 | Account health recomputes | The support contribution appears as a named factor with direction and weight | TC-E12-008, TC-E04-011 |

---

## 3. Per-epic catalogues

One table per epic. **Traces** names the stories (and, where sharper, the `FR-`) each case verifies. Global requirements (`FR-GLOBAL-*`) apply to every case and are not repeated per row.

### E01 — Tenancy, identity and access

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E01-001 | Provision a new tenant | Tenant exists with isolated scope, default configuration baseline, entitlement set, and one administrator who can log in | US-E01-01, FR-TEN-001 |
| TC-E01-002 | Suspend a tenant, then attempt a business write and an admin export | Write refused; administrator login and data export still work | US-E01-01, FR-TEN-002 |
| TC-E01-003 | Sign in with a password compliant with the configured policy | Authentication succeeds; credential stored only as a salted computationally-hard hash | US-E01-02 |
| TC-E01-004 | Run the SAML test-connection facility against a valid and an invalid IdP config | Reports success or the specific failure, without activating the configuration | US-E01-03 |
| TC-E01-005 | Sign in via OIDC discovery-configured provider | Authorization-code flow with PKCE completes; claims map to user attributes as configured | US-E01-04 |
| TC-E01-006 | Create a user in the directory; SCIM syncs | User exists in Axiom with mapped attributes and assigned profile | US-E01-05 |
| TC-E01-007 | Deactivate a directory user who owns records; SCIM syncs | User cannot authenticate, all sessions revoked; owned records intact and still attributed to them; SCIM delete deactivates rather than removes | US-E01-05 |
| TC-E01-008 | Authenticate as a user in an MFA-targeted role | Second factor demanded; TOTP and passkey each satisfy it | US-E01-06 |
| TC-E01-009 | Attempt a controlled action (bulk export) with a session older than the freshness window | Re-authentication demanded before the action proceeds | US-E01-07 |
| TC-E01-010 | Administrator lists active sessions and revokes one | Next request from the revoked session is refused without waiting for token expiry; idle timeout ends sessions on schedule | US-E01-08 |
| TC-E01-011 | Support operator impersonates a user in a consenting tenant | Every audit event records both operator and impersonated user; impersonation visibly indicated on every screen | US-E01-09 |
| EC-E01-001 | Tenant provisioning fails partway; the request is retried with the same key | No partial tenant scope remains; retry succeeds without creating a duplicate | US-E01-01 |
| EC-E01-002 | SAML certificate approaches expiry; IdP later misconfigured | Administrators notified before authentication breaks; a local administrative path remains available throughout | US-E01-03 |
| EC-E01-003 | Use an MFA recovery code, then use it again | First use succeeds; second is refused | US-E01-06 |
| EC-E01-004 | Exceed the concurrent-session limit | Configured policy applies (oldest ended or new refused) and the user is told which | US-E01-08 |
| NEG-E01-001 | Set a password violating the policy | Rejected, stating which rule failed | US-E01-02 |
| NEG-E01-002 | Repeated failed logins; then a login for a non-existent username | Progressive delay then lockout; the non-existent-user error is indistinguishable from wrong-password | US-E01-02 |
| NEG-E01-003 | OIDC claim mapping yields no email on sign-in | Provisioning refused with a message naming the missing claim | US-E01-04 |
| NEG-E01-004 | MFA-exempt user authenticates; a targeted user attempts to skip the factor | Exempt user is not prompted; targeted user cannot complete authentication without it | US-E01-06 |
| NEG-E01-005 | User abandons a failed step-up challenge | The controlled action does not occur; the failed attempt is audited | US-E01-07 |
| NEG-E01-006 | Operator attempts impersonation in a non-consenting tenant; during a valid impersonation attempts to grant themselves access | Both refused; both attempts audited | US-E01-09 |

### E02 — RBAC, record sharing and segregation of duties

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E02-001 | Manager queries records owned by roles beneath them | Records visible per the object's roll-up configuration; objects configured not to roll up stay invisible | US-E02-01 |
| TC-E02-002 | Compute effective permissions for a user with a profile, two permission sets and a group mute | Result is the union of all grants minus explicit mutes | US-E02-02 |
| TC-E02-003 | Remove a permission set from a logged-in user, who then acts | The removed permissions are no longer effective without a new login | US-E02-02 |
| TC-E02-004 | Non-owner without any grant queries a private-default object | No records returned; existence not disclosed by count, error text or timing | US-E02-03 |
| TC-E02-005 | A record's criteria field changes to match a criteria-based sharing rule | Access granted without administrator action; `RECORD_SHARE` row carries the rule as cause | US-E02-03 |
| TC-E02-006 | Record owner changes | Owner-based sharing recomputed on commit | US-E02-03 |
| TC-E02-007 | User whose profile denies a field reads the record via UI, API, report, export and search | The field is **absent from every response — not null** | US-E02-04 |
| TC-E02-008 | User without reveal permission views a masked field; a permitted user reveals it | Partial value only; reveal produces a read-audit event with actor, record, field, time | US-E02-05 |
| TC-E02-009 | Administrator grants the second side of a declared conflicting pair | Grant blocked, naming the specific conflict and the existing grant causing it | US-E02-06 |
| TC-E02-010 | Submitter of a controlled action attempts to approve it | Approval refused; the attempt audited as a segregation violation | US-E02-07 |
| TC-E02-011 | Administrator requests an access explanation for a user–record pair | Every contributing cause enumerated: ownership, role hierarchy, named rule, team, territory, manual share | US-E02-08 |
| TC-E02-012 | Explanation requested for a user who cannot see the record | The reason is stated and the minimum change that would grant access is identified | US-E02-08 |
| TC-E02-013 | User with read but without export permission reads, then exports | Reading works; export and print refused | US-E02-09 |
| EC-E02-001 | Evaluate access through a very deep role hierarchy | Correct results with no artificial depth limit | US-E02-01 |
| EC-E02-002 | Query a record while its sharing recomputation is in progress | Results are correct or the operation waits — stale access is never served | US-E02-03, FR-SEC-005 |
| EC-E02-003 | Manual share granted with an expiry | Grant lapses at expiry with no action by anyone; see SEC-026 for the ±1s boundary | US-E02-03, FR-SEC-006, FR-SEC-012 |
| NEG-E02-001 | Save a role hierarchy change that would create a cycle | Rejected, naming the conflicting roles — via UI, API and bulk import alike | US-E02-01 |
| NEG-E02-002 | Change a read-only field via bulk update and via automation triggered by the restricted user | Both refused | US-E02-04 |
| NEG-E02-003 | Declare a conflict already violated by historical grants; scheduled sweep runs | The pre-existing violation is reported, not silently tolerated | US-E02-06 |
| NEG-E02-004 | Approver delegates authority to the submitter, who then approves | Refused — the maker-checker constraint applies transitively through delegation | US-E02-07 |
| NEG-E02-005 | Export exceeding the configured row threshold attempted without approval | Refused until approval; every completed export audited with actor, object, filter criteria, row count, destination | US-E02-09 |

### E03 — Organization, reference and master data

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E03-001 | Save an opportunity in a non-corporate currency | Transaction amount, corporate amount, applied rate and rate date all stored | US-E03-01 |
| TC-E03-002 | Compute corporate amount for a record configured to use a dated rate | The rate effective at the record's defined date applies, not today's | US-E03-01 |
| TC-E03-003 | Resolve a 4-4-5 fiscal period from forecasting, quota and reporting | All three use the same definition | US-E03-02 |
| TC-E03-004 | Lead assigned 17:55 Friday, business hours end 18:00; compute first-response SLA | Only business hours count; due time falls on the next business day | US-E03-03 |
| TC-E03-005 | SLA clock crosses a defined holiday | The holiday is excluded from the clock | US-E03-03 |
| TC-E03-006 | Deactivate a picklist value | Not selectable on new records; existing records retain and correctly report it | US-E03-04 |
| TC-E03-007 | View and report a historical record referencing a reference value that has since lapsed | The value in force at the record's date resolves | US-E03-05 |
| TC-E03-008 | Preview a territory model against live data | Resulting assignments shown; **no assignment takes effect** | US-E03-06 |
| TC-E03-009 | Activate the previewed model | Activation is atomic; the prior model version remains restorable | US-E03-06 |
| TC-E03-010 | Change a quota after the period has begun | Prior value, actor, time and reason retained; attainment reporting can use either version explicitly | US-E03-07 |
| EC-E03-001 | Update exchange rates after historical records exist | Stored corporate amounts on historical records unchanged | US-E03-01, INT-011 |
| EC-E03-002 | Controlling picklist value changes, leaving an existing dependent combination invalid | Combination flagged, not silently corrected | US-E03-04 |
| NEG-E03-001 | Change a fiscal calendar in a way that affects historical periods | Refused, or requires explicit confirmation naming the affected submitted forecasts | US-E03-02 |
| NEG-E03-002 | Select a dependent picklist value invalid for the controlling value | Only valid dependents are offered; an invalid combination cannot be saved | US-E03-04 |

### E04 — Accounts, contacts, hierarchy and buying groups

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E04-001 | Create an account under a configured layout and record type | Only permitted fields editable; required fields enforced server-side | US-E04-01 |
| TC-E04-002 | Save a contact with multiple addresses and channels | Each is typed; the primary of each type is unambiguous | US-E04-01 |
| TC-E04-003 | Request the ultimate parent in a multi-level hierarchy | Derived correctly at any depth | US-E04-02 |
| TC-E04-004 | View roll-up on an account family | Pipeline, closed revenue, open cases and activity recency shown for the account alone and the hierarchy | US-E04-03 |
| TC-E04-005 | Add buying group members | Each carries role, influence and engagement status | US-E04-04 |
| TC-E04-006 | Save a new account whose name closely matches an existing one | Candidate matches shown with confidence **before** the record is created | US-E04-05 |
| TC-E04-007 | Merge two accounts | All activities, opportunities, cases and contacts reparent to the survivor; no related record orphaned | US-E04-06, INT-003 |
| TC-E04-008 | Inspect the merge audit | One event records the losing record IDs and every field-level survivorship decision | US-E04-06, INT-004 |
| TC-E04-009 | Withdraw consent, then view consent history | Both the original grant and the withdrawal present — withdrawal adds a record, never overwrites one | US-E04-07 |
| TC-E04-010 | View the account 360 timeline | Activities, opportunities, quotes, cases and campaign memberships in one chronological stream, filterable by type and date | US-E04-08 |
| TC-E04-011 | View account health; then a factor changes materially | Each factor, direction and weight shown in business language; the score change attributable to the specific factor | US-E04-09 |
| EC-E04-001 | Roll-up computed for a user lacking access to some records in the hierarchy | Those records excluded **and** the restriction indicated — no inference of hidden records, no silent under-reporting | US-E04-03 |
| EC-E04-002 | Timeline rendered for a user lacking permission to some items | Items absent; existence not implied by gaps or counts | US-E04-08 |
| EC-E04-003 | Save a duplicate under a warning-mode rule | Creation permitted; the decision recorded | US-E04-05 |
| NEG-E04-001 | Assign a parent account that would create a cycle | Rejected, naming the accounts involved | US-E04-02 |
| NEG-E04-002 | Save a duplicate under a blocking rule | Creation refused | US-E04-05 |
| NEG-E04-003 | User, cadence, automation and integration each attempt to email a contact who withdrew consent | Every send **blocked, not warned**; every block audited | US-E04-07 |
| NEG-E04-004 | Advance a stage requiring an economic buyer when the buying group has none | Blocked, naming the missing role | US-E04-04 |

### E05 — Lead capture, qualification and routing

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E05-001 | Valid submission arrives on a generated web form | Lead created with source and campaign attribution | US-E05-01 |
| TC-E05-002 | Bulk API submission of 1,000 leads with 12 invalid | 988 created; response identifies each of the 12 with its specific reason — batch not rejected wholesale | US-E05-01 |
| TC-E05-003 | Inbound lead matches an existing contact under "attach" behaviour | Associated to the existing contact rather than duplicated | US-E05-02 |
| TC-E05-004 | View a rule-based score | Contributing rules and their point values visible | US-E05-03 |
| TC-E05-005 | View a predictive score | Top contributing factors and their direction shown alongside it — a bare number fails | US-E05-03 |
| TC-E05-006 | Lead evaluated against ordered assignment rules | First matching rule wins; the matched rule recorded on the lead | US-E05-04 |
| TC-E05-007 | Round-robin routing reaches an owner at capacity | That owner is skipped | US-E05-04 |
| TC-E05-008 | SLA timer runs across the owner's non-business hours; later breaches | Timer pauses outside business hours; breach fires the configured escalation and is reportable | US-E05-05 |
| TC-E05-009 | Qualify a lead under the configured framework, then convert | Framework fields captured and carried to the opportunity without re-entry | US-E05-06 |
| TC-E05-010 | Convert a qualified lead | Account, contact and optionally opportunity exist, mapped per configuration including custom fields | US-E05-07 |
| TC-E05-011 | Inspect the converted lead | Activities, notes and campaign membership transferred; lead read-only with links to what it became | US-E05-07 |
| TC-E05-012 | Disqualified lead set to recycle on a future date; the date arrives | Lead re-enters the working queue | US-E05-08 |
| EC-E05-001 | Inbound lead is an ambiguous match under "review" behaviour | Routed to the review queue rather than guessed | US-E05-02 |
| EC-E05-002 | No assignment rule matches | Lead lands in the fallback queue rather than being left unassigned | US-E05-04 |
| NEG-E05-001 | Disqualify without a reason from the governed taxonomy | Refused | US-E05-08 |
| NEG-E05-002 | Force a failure partway through conversion | No partial conversion persists — no orphan account, contact or opportunity | US-E05-07, INT-001 |

### E06 — Opportunity and pipeline management

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E06-001 | Create opportunities with different record types | Each uses its pipeline's stages, probabilities and forecast categories | US-E06-01 |
| TC-E06-002 | Advance a stage with all exit criteria met | Stage history records entry, exit, duration, actor and the criteria version applied | US-E06-02 |
| TC-E06-003 | Perform a permitted backward move | A reason is required and recorded in stage history | US-E06-03 |
| TC-E06-004 | Compute totals from line items | Deterministic and reproducible | US-E06-04 |
| TC-E06-005 | Manually override a total | Visibly flagged as overridden; the system-computed value retained alongside | US-E06-04 |
| TC-E06-006 | Save revenue splits totalling exactly 100%; save overlay splits at any total | Both succeed; overlays unconstrained by the 100% rule | US-E06-05 |
| TC-E06-007 | View an opportunity with a long activity gap and a single engaged contact | Inactivity and single-threading risks each state the observation, why it matters, and a recommended action, traceable to the producing records | US-E06-06 |
| TC-E06-008 | Move the close date beyond the current period; repeat over time | Reason required; cumulative slip count and original close date reportable | US-E06-07 |
| TC-E06-009 | Close an opportunity with a governed reason | Closure recorded; record read-only except through the controlled reopen path | US-E06-08 |
| TC-E06-010 | Drag an opportunity to a stage whose criteria are met | Board move commits; identical server-side validation to the record form | US-E06-09 |
| TC-E06-011 | Compare pipeline between two points in time | Added, advanced, slipped, grown, shrunk, won and lost each listed | US-E06-10 |
| EC-E06-001 | Exit criteria changed after an opportunity entered the stage; advancement attempted | The criteria version in force at stage entry applies, not the current one | US-E06-02 |
| EC-E06-002 | Drag to a stage whose exit criteria are unmet | Opportunity returns to its original column and the reason is stated | US-E06-09 |
| EC-E06-003 | Total the movement comparison | Components reconcile exactly to the net change; no unexplained residual | US-E06-10, INT-009 |
| NEG-E06-001 | Advance with unmet criteria via record page, board drag, API and bulk update | All four refused; each response names every unsatisfied criterion and the specific action needed | US-E06-02 |
| NEG-E06-002 | Attempt a backward move on a pipeline that disallows it | Refused | US-E06-03 |
| NEG-E06-003 | Save revenue splits totalling 99% or 101% | Refused, naming the shortfall or excess | US-E06-05 |
| NEG-E06-004 | Close without a reason from the governed taxonomy | Refused | US-E06-08 |

### E07 — Activity, email and calendar engagement

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E07-001 | Task with due date and reminder approaches its due time | Owner notified per their preference | US-E07-01 |
| TC-E07-002 | Log a call | Direction, duration, disposition from a governed list and related records captured | US-E07-01 |
| TC-E07-003 | View the unified timeline | All activity types in one chronological stream with last-contacted, days-since-last-activity and count-by-period metrics | US-E07-02 |
| TC-E07-004 | Send email from Axiom via a connected Microsoft 365 or Google Workspace account | Appears in the user's sent items and threads correctly in their mail client | US-E07-03 |
| TC-E07-005 | Email exchanged with a known contact on a connected, consenting mailbox | Captured and related to the contact, account and relevant open opportunities without any user action | US-E07-04 |
| TC-E07-006 | View any captured item | Match basis and confidence visible | US-E07-04 |
| TC-E07-007 | Process messages from a domain excluded by user or administrator | Never stored — not merely hidden | US-E07-05 |
| TC-E07-008 | Send from a template with all merge fields resolvable | Message renders with merged values | US-E07-06 |
| TC-E07-009 | Recipient opens and clicks a tracked email, with tracking enabled and consent present | Signal surfaced to the record owner | US-E07-07 |
| TC-E07-010 | Enrol a lead in a cadence with email, call and task steps; the prospect replies | Steps present in order respecting business hours; the reply exits the prospect unless configured otherwise | US-E07-08 |
| TC-E07-011 | Inbound call from a known number; call completes | Matching record presented to the agent; call activity created with duration and a disposition prompt | US-E07-09 |
| EC-E07-001 | Capture encounters an ambiguous participant match and an unmatchable item | Ambiguous → one-click correction, not guessed; unmatchable → retained in a review queue, not discarded | US-E07-04 |
| EC-E07-002 | Mailbox connection is revoked externally; sync runs | Fails gracefully; the user is told how to reconnect | US-E07-03 |
| EC-E07-003 | User withdraws capture consent | Capture stops; previously captured private items purged per policy | US-E07-05 |
| NEG-E07-001 | Capture configured for a user who has not consented | Nothing from their mailbox is captured | US-E07-05 |
| NEG-E07-002 | Send a template with a merge field that cannot resolve for a recipient | Blocked with a clear message — no visibly broken message goes out | US-E07-06 |
| NEG-E07-003 | Tenant policy disables tracking; email sent | No tracking occurs; no signal generated | US-E07-07 |
| NEG-E07-004 | Enrol a suppressed or non-consenting contact in a cadence | Refused | US-E07-08 |

### E08 — Products, price books, quotes and CPQ

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E08-001 | Price a quote today when a future-dated price book entry also exists | The currently effective entry is used | US-E08-01 |
| TC-E08-002 | Create a quote from an opportunity with line items | Account, contact and line items inherited | US-E08-02 |
| TC-E08-003 | Accept a quote | Opportunity amount and line items reflect it; the sync is auditable | US-E08-02 |
| TC-E08-004 | Materially change a sent quote; compare versions | New version created, prior retained unchanged; differences shown at field and line level | US-E08-03 |
| TC-E08-005 | Add a bundle with required components | Required components included and not individually removable | US-E08-04 |
| TC-E08-006 | Price quantities across tiered, volume, block and subscription methods | Correct tier applies; behaviour unambiguous at every configured method | US-E08-05 |
| TC-E08-007 | View any priced line | Every adjustment itemized; final price fully derivable from list price | US-E08-05 |
| TC-E08-008 | Approval granted for an above-threshold discount; quote sent | Approval decision, approver and time recorded on the quote | US-E08-06 |
| TC-E08-009 | Submit a quote below the margin floor | Approval required; the shortfall quantified | US-E08-07 |
| TC-E08-010 | Generate a quote document from a template; regenerate from the same version | Stable versioned artefact attached to the quote; regeneration produces equivalent content | US-E08-08 |
| TC-E08-011 | Send a quote for signature; envelope state changes | Quote reflects sent, viewed, signed, declined or expired | US-E08-09 |
| EC-E08-001 | Price a quantity exactly on a tier boundary | The boundary behaviour is unambiguous and documented at the exact value | US-E08-05 |
| EC-E08-002 | E-signature provider unavailable at send | Failure surfaced with a retry; quote **not** marked sent | US-E08-09 |
| NEG-E08-001 | Save two active price book entries for the same product and book with overlapping effective dates | Rejected — exactly one price must resolve for a product, book and date | US-E08-01 |
| NEG-E08-002 | Attempt acceptance of a superseded quote version | Refused | US-E08-03 |
| NEG-E08-003 | Save a configuration violating an exclusion rule | Refused, naming the violated constraint and the options that would resolve it | US-E08-04 |
| NEG-E08-004 | Send a quote carrying a discount above threshold, unapproved | Refused, naming the outstanding approval and its current approver | US-E08-06 |

### E09 — Contracts, orders, subscriptions and renewals

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E09-001 | Save a contract with lines | Each line links product, quantity, price and term — the basis for renewal and entitlement | US-E09-01 |
| TC-E09-002 | Amend a contract | New version with change reason; prior version immutable | US-E09-02 |
| TC-E09-003 | Report contract terms as of a past date | Terms in force at that date returned | US-E09-02 |
| TC-E09-004 | Apply a mid-term subscription upgrade | Proration computed; a dated change record created | US-E09-03 |
| TC-E09-005 | Amend one of a set of co-terminous subscriptions | Alignment policy applies; resulting end dates shown before confirmation | US-E09-03 |
| TC-E09-006 | Renewal job runs at the configured lead time before expiry | Renewal opportunity created, pre-populated from expiring terms, assigned by rule | US-E09-04 |
| TC-E09-007 | Raise a case for an account with an active entitlement | Response and resolution targets derive from the entitlement | US-E09-05 |
| TC-E09-008 | Record a non-renewal or downgrade | Governed reason and quantified lost value required and stored | US-E09-06 |
| TC-E09-009 | Transmit an order to the financial system; first attempt fails | Hand-off state recorded; retried with backoff | US-E09-07 |
| EC-E09-001 | Renewal job runs twice for the same contract | Exactly one renewal opportunity exists | US-E09-04, CON-010 |
| EC-E09-002 | Raise a case against an expired entitlement | Case flagged as uncovered rather than silently given a default | US-E09-05 |
| EC-E09-003 | Same order transmitted twice | Idempotency key prevents downstream duplication | US-E09-07, CON-015 |
| NEG-E09-001 | ERP hand-off failure persists past the retry bound | Surfaces on an exception queue with the reason — never silently dropped | US-E09-07 |
| NEG-E09-002 | Record churn without a governed reason or without quantified lost value | Refused | US-E09-06 |

### E10 — Forecasting and revenue intelligence

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E10-001 | View a team forecast across stages and hierarchy levels | Each opportunity maps to a category derived from stage, individually overridable with a reason; contributing amounts visible per level subject to record access | US-E10-01 |
| TC-E10-002 | Manager submits an override; a level above views it | Both the arithmetic roll-up and the override visible; the variance explicit | US-E10-02 |
| TC-E10-003 | Submit a forecast; the period's opportunities change afterwards | The submission remains exactly as submitted | US-E10-03 |
| TC-E10-004 | Open a historical submission | The individual contributing opportunities **as they were at submission** are available, not merely the total | US-E10-03 |
| TC-E10-005 | Drill into a forecast figure at any hierarchy level | Constituent opportunities listed; each can be opened | US-E10-04 |
| TC-E10-006 | View the AI-predicted outcome | Appears alongside — never in place of — the submitted human forecast, with confidence interval and contributing factors, decomposable the same way | US-E10-06, US-E10-04 |
| TC-E10-007 | Generate the movement waterfall between two snapshots | New pipeline, advanced, slipped out, pulled in, increased, decreased, won and lost each quantified | US-E10-05 |
| TC-E10-008 | Display coverage, velocity, conversion and win-rate in two different reports | Published formula and version visible; both reports compute the named metric identically | US-E10-07 |
| TC-E10-009 | Compute forecast accuracy from historical submissions and actuals | Bias and accuracy reported per forecasting user over time | US-E10-08 |
| EC-E10-001 | Total the waterfall | Components reconcile exactly to the net change; any residual shown explicitly, never absorbed | US-E10-05, INT-008 |
| EC-E10-002 | Manager views a forecast containing records they cannot access | Roll-up respects record access; no hidden-record inference | US-E10-01 |
| NEG-E10-001 | Submit an override without a reason | Refused | US-E10-02 |
| NEG-E10-002 | A prediction that cannot be decomposed to deals | Not presented as a forecast number | US-E10-04, US-E10-06 |

### E11 — Campaigns, segments and marketing alignment

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E11-001 | View roll-up on a campaign hierarchy | Cost and results aggregate across child campaigns | US-E11-01 |
| TC-E11-002 | Build a segment across accounts, contacts and opportunities | Live membership count shown; segment saveable and re-runnable | US-E11-02 |
| TC-E11-003 | Sync a segment to the marketing platform | Per-record status returned and reconciled | US-E11-03 |
| TC-E11-004 | Apply first-touch, last-touch and multi-touch attribution to the same pipeline | All three displayable side by side; every attributed figure names its model and calculation version | US-E11-04 |
| TC-E11-005 | Pass an MQL to sales; the acceptance SLA elapses without a decision | Escalation fires | US-E11-05 |
| EC-E11-001 | Export or sync segment members including suppressed contacts | Consent and suppression enforced — suppressed members excluded from egress | US-E11-02 |
| EC-E11-002 | Sync completes with partial failures | Failures individually visible per record | US-E11-03 |
| NEG-E11-001 | Reject an MQL without a reason | Refused; hand-off quality reportable | US-E11-05 |
| NEG-E11-002 | Render an attributed figure with no model context | Not a valid output — the figure cannot appear without its model and version | US-E11-04 |

### E12 — Cases, entitlements and SLA management

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E12-001 | Inbound email creates a case; the customer replies later | Thread preserved; subsequent replies attach to the same case | US-E12-01 |
| TC-E12-002 | Case created under routing rules | Reaches the correct user or queue; claim/release works | US-E12-02 |
| TC-E12-003 | Raise cases of different severities for an account with an active entitlement | Response and resolution targets derive from entitlement and severity | US-E12-03 |
| TC-E12-004 | Set a case customer-pending, then resume it | Each pause and resume recorded with actor and reason; the SLA position reconstructable | US-E12-03 |
| TC-E12-005 | Milestone approaches breach; then breaches | Configured escalation fires at the warning threshold and at breach; the trigger recorded | US-E12-04 |
| TC-E12-006 | Knowledge suggested in case context | Only articles the agent — and, where shared externally, the customer — may see are offered | US-E12-05 |
| TC-E12-007 | Portal user raises and tracks a case | Only their own account's cases visible | US-E12-06 |
| TC-E12-008 | Account health computed for an account with cases, breaches and satisfaction results | Their contribution visible as a named factor | US-E12-07 |
| EC-E12-001 | Raise a case with no matching entitlement | Default targets apply **and** the case is flagged as uncovered | US-E12-03 |
| EC-E12-002 | Two agents claim the same queued case simultaneously | Exactly one succeeds; the other is told the case is taken | US-E12-02, CON-007 |
| NEG-E12-001 | Portal user manipulates identifiers and query parameters | No other account's data reachable by any parameter manipulation | US-E12-06, SEC-031 |
| NEG-E12-002 | Portal context requests an article not shared externally | Never offered or reachable, even by direct reference | US-E12-05 |

### E13 — Partner, channel and territory management

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E13-001 | Model a partner account | Tier and agreement status carried and reportable | US-E13-01 |
| TC-E13-002 | Submit a deal registration; the approval SLA elapses | Escalation fires | US-E13-02 |
| TC-E13-003 | Approved registration approaches expiry | Partner and partner manager notified before it lapses | US-E13-02 |
| TC-E13-004 | Create a direct opportunity for an account with an approved partner registration | Conflict detected and routed for resolution | US-E13-03 |
| TC-E13-005 | Report channel pipeline | Partner-sourced and partner-influenced separated; definitions published alongside the figures | US-E13-04 |
| TC-E13-006 | Simulate a territory realignment | Affected accounts, users and in-flight opportunities listed; **no change applied** | US-E13-05 |
| EC-E13-001 | Activate the simulated realignment | Configured in-flight policy applies; every reassignment audited | US-E13-05 |
| NEG-E13-001 | Registration lapses unactioned; a direct opportunity is then created | No protection applied from the lapsed registration; no false conflict raised | US-E13-02, US-E13-03 |

### E14 — Workflow automation, approvals and rules engine

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E14-001 | Record changes with an entry condition comparing old and new values | Automation runs only when the condition transitions to true — not on every save that matches | US-E14-01 |
| TC-E14-002 | An automation action fails mid-sequence | Outcome recorded per step; the failure visible without inspecting logs | US-E14-01 |
| TC-E14-003 | Attempt a transition not defined in the state model via UI, API, automation and bulk update | It cannot occur by any path | US-E14-02 |
| TC-E14-004 | Trained administrator builds a multi-branch automation with a loop over related records | Completed without writing code and without vendor assistance | US-E14-03 |
| TC-E14-005 | Submit a request under an amount-based approval matrix | Correct approver determined and notified | US-E14-04 |
| TC-E14-006 | Submitter recalls a request before decision | Request withdrawn; approvers notified | US-E14-04 |
| TC-E14-007 | Test a valid expression against a sample record | Evaluated result shown before activation | US-E14-05 |
| TC-E14-008 | Run simulation of an inactive automation against selected real records | Every action that would occur is listed; **none of them occurs** | US-E14-06 |
| TC-E14-009 | View the execution log for any automation run | Trigger, entry-condition outcome, each step, each action result and duration shown, filterable by record | US-E14-07 |
| TC-E14-010 | Tenant with many rules creates another | Permitted; fair-use throttling with visible telemetry, never a count cap | US-E14-09 |
| EC-E14-001 | Two automations trigger each other | Execution halts; the diagnostic names the participating rules and the cycle — not merely "limit exceeded" | US-E14-08 |
| EC-E14-002 | Parallel approvers with unanimous semantics; one rejects | Request rejected with the reason | US-E14-04 |
| NEG-E14-001 | Attempt a state transition without the state's mandatory fields | Refused, naming the unsatisfied condition | US-E14-02 |
| NEG-E14-002 | Save an invalid expression | Refused with the error position identified | US-E14-05 |
| NEG-E14-003 | Submitter attempts to approve their own request | Refused per maker-checker | US-E14-04, US-E02-07 |

### E15 — Reporting, dashboards and analytics

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E15-001 | Build a report joining related objects with filters, groupings and summaries | Results correct; no administrator involvement needed | US-E15-01 |
| TC-E15-002 | Run a "without related records" report type | Records lacking the related object are returned | US-E15-01 |
| TC-E15-003 | Compose a dashboard from reports | Components render from their source reports | US-E15-02 |
| TC-E15-004 | Two users with different record access run the same report | Each sees only their permitted records | US-E15-03 |
| TC-E15-005 | Open a dashboard configured to run as a specified user | That fact is displayed to every viewer | US-E15-03 |
| TC-E15-006 | Drill from an aggregate into its records | Authoritative records returned after a fresh permission check — never from the projection's cached view of access | US-E15-04, SEC-030 |
| TC-E15-007 | Scheduled delivery to recipients with differing access | Each recipient's copy reflects their own access | US-E15-05 |
| TC-E15-008 | Metric does not cross the subscription threshold | No notification sent | US-E15-05 |
| TC-E15-009 | Run a trend report from scheduled snapshots; re-run later | Figures come from snapshots and are stable across re-runs | US-E15-06 |
| TC-E15-010 | Run the same named metric in two different reports on the same data | Identical values; published formula and version accessible where displayed | US-E15-07 |
| EC-E15-001 | View a report while the projection lags the transactional store | Staleness is displayed to the user, per [ADR-008](../architecture/adr/ADR-008-reporting-read-model.md) | US-E15-06, FR-RPT-008 |
| NEG-E15-001 | Export above the row threshold without approval | Refused until approved; completed exports audited with actor, object, filter criteria, row count and destination | US-E15-08 |
| NEG-E15-002 | Drill through after the viewer's access was revoked post-aggregation | Denied — the aggregate's history grants nothing | US-E15-04 |

### E16 — AI copilot and agentic assistance

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E16-001 | Request an account summary | Covers current state, recent activity, open items and risks | US-E16-01 |
| TC-E16-002 | Inspect the summary's claims | Every claim cites the specific records it came from; citations navigable | US-E16-01 |
| TC-E16-003 | User without access to an opportunity asks a question whose answer would require it | The opportunity is not retrieved, not cited, not summarized and not reasoned over | US-E16-02 |
| TC-E16-004 | Two users with different access ask the same question | Each answer reflects only that user's permitted data | US-E16-02 |
| TC-E16-005 | Request next-best-action recommendations | Each states the observation, why it matters and the specific action, traceable to the producing records or their absence | US-E16-03 |
| TC-E16-006 | Generate and send a drafted email | Never sent automatically — user reviews and sends explicitly; the activity records AI provenance | US-E16-04 |
| TC-E16-007 | Ask a conversational question | The interpretation is displayed; underlying records accessible from the answer | US-E16-05 |
| TC-E16-008 | View any score — lead, deal, renewal, health | Weighted contributing factors with direction and magnitude, in business language, not model-internal terms | US-E16-06 |
| TC-E16-009 | Ask the agent to carry out a multi-step task | Complete plan and exact changes shown before anything happens; on confirmation, every action attributed to the AI source with the initiating user | US-E16-07 |
| TC-E16-010 | Reverse a completed agent action set within the retention window | The whole set undone as a unit | US-E16-07 |
| TC-E16-011 | Invoke a model against a record with designated sensitive fields | Fields masked or excluded per tenant policy; the applied policy recorded on the interaction | US-E16-08 |
| TC-E16-012 | Run the product with AI disabled | AI surfaces absent — not errors, not upsells; every non-AI acceptance test in this catalogue passes | US-E16-09 |
| TC-E16-013 | Change a model or prompt; run the evaluation suite; view tenant AI telemetry | Quality metrics produced, a regression blocks release; usage, latency, cost and acceptance/rejection rates visible to the tenant administrator | US-E16-10 |
| EC-E16-001 | Summary generation encounters a claim it cannot ground in a record | The claim is omitted or explicitly labelled unsupported | US-E16-01 |
| EC-E16-002 | Ask a question the system cannot answer reliably | It says so — it does not produce a plausible answer it cannot substantiate | US-E16-05 |
| EC-E16-003 | An agent step fails mid-sequence | The sequence halts and reports what completed and what did not — no partial silent completion | US-E16-07 |
| NEG-E16-001 | Audit the grounding retrieval path | Retrieval runs under the calling user's principal through the ordinary authorization path; no privileged service-account retrieval path exists | US-E16-02, SEC-029 |
| NEG-E16-002 | Execute an erasure request for a subject present in AI stores | Embeddings and AI caches reached along with primary storage | US-E16-08, INT-012 |
| NEG-E16-003 | A grounded record contains adversarial instruction-like text | The assistant does not act on it and does not surface data beyond the asking user's access | US-E16-02, FR-AIX-010 |

### E17 — Integration platform, APIs, webhooks and events

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E17-001 | Exercise a sample of UI-available operations via the API; fetch the specification | Every operation available; the published, versioned OpenAPI document describes it accurately | US-E17-01 |
| TC-E17-002 | Submit a bulk job with some invalid records | Valid records processed; per-record results plus a downloadable error file identify each failure | US-E17-02 |
| TC-E17-003 | Compare API behaviour across tenants on different commercial tiers | No tier grants more capability or volume; uniform fair-use throttling with published limits and standard rate-limit headers | US-E17-03 |
| TC-E17-004 | Submit the same write twice with one idempotency key | Effect occurs once; both calls return the same result | US-E17-04 |
| TC-E17-005 | Deliver to an endpoint returning errors | Retries with exponential backoff; dead-letter after the bound; replay from the DLQ delivers | US-E17-05 |
| TC-E17-006 | Receive a webhook payload | Its signature verifies | US-E17-05 |
| TC-E17-007 | Consume the event stream | Per-record ordering holds; gaps are detectable rather than silent | US-E17-06 |
| TC-E17-008 | An integration fails for the configured window | Surfaces to a human with last successful sync, failure count, error detail, affected records and a retry action | US-E17-07 |
| EC-E17-001 | Replay a dead-lettered message to an idempotent consumer | Delivered; the business effect occurs exactly once | US-E17-05, CON-005 |
| EC-E17-002 | Consumer detects a stream gap and re-requests | Missed events recoverable; no silent loss | US-E17-06 |
| NEG-E17-001 | Sweep the UI capability inventory against the OpenAPI surface | Any UI-only capability is reported as a defect (`FR-INT-001`) | US-E17-01 |
| NEG-E17-002 | Deliver a webhook with a tampered payload | Signature verification fails; the consumer can reject it | US-E17-05 |

### E18 — Data migration and onboarding

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E18-001 | Connect Salesforce, Zoho or HubSpot with customer credentials | Connection is read-only; available objects enumerated with record counts | US-E18-01 |
| TC-E18-002 | Run schema discovery on a source with custom objects and fields | Mapping proposed, reviewable and correctable | US-E18-02 |
| TC-E18-003 | Execute a dry run of a configured migration | Nothing written; report shows records per object, validation failures with reasons, duplicates, unmapped fields and referential gaps | US-E18-03 |
| TC-E18-004 | Migrate account hierarchies, contact-account relationships and opportunity links | Preserved in the target | US-E18-04 |
| TC-E18-005 | Produce the reconciliation report | Source and target counts per object and monetary sums for financial fields compared; every non-migrated record listed with its reason | US-E18-05, INT-005 |
| TC-E18-006 | Roll back a completed migration within the retention window | Every record it created removed; tenant returns to its pre-migration state; rollback audited and reports exactly what was removed | US-E18-06, INT-006 |
| TC-E18-007 | Run a delta re-sync after a prior migration | Only records created or changed since the last run processed; previously migrated records not duplicated | US-E18-07, INT-007 |
| TC-E18-008 | New user of a given role logs into a new tenant | Role-specific checklist presented; completion tracked | US-E18-08 |
| EC-E18-001 | Discovery finds source fields with no target | Listed explicitly — silent omission of source data is a failure | US-E18-02 |
| EC-E18-002 | Migration encounters a relationship it cannot resolve | Reported with both endpoints named, not silently dropped | US-E18-04 |
| NEG-E18-001 | Attempt rollback outside the retention window | Refused with a clear statement of the window and what remains possible | US-E18-06 |
| NEG-E18-002 | Execute a migration without fresh authentication | Migration execution is a controlled action: step-up required before it proceeds | US-E18-06, US-E01-07 |

### E19 — Administration, configuration, sandbox and release

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E19-001 | Create a custom object with fields | Participates fully in security, automation, reporting, search and API — identically to built-in objects | US-E19-01 |
| TC-E19-002 | Create custom objects and fields on tenants of different tiers | No tier permits more than another | US-E19-02 |
| TC-E19-003 | Change a controlling field under conditional visibility rules | Dependent fields and sections show or hide; hidden fields not submitted or persisted from the client | US-E19-03 |
| TC-E19-004 | Request a sandbox on any commercial tier | Available with a full configuration copy and configurable data | US-E19-04 |
| TC-E19-005 | Inspect a freshly created sandbox | Outbound email, webhooks and integrations disabled by default; enabling requires explicit action with a warning | US-E19-04 |
| TC-E19-006 | Validate a change set against the target; compare | Results reported without applying anything; a diff shown before deployment | US-E19-05 |
| TC-E19-007 | Make any administrative change | Actor, timestamp, component and before/after values recorded and retained per audit policy | US-E19-06 |
| TC-E19-008 | Import a file with some invalid rows | Valid rows load; a downloadable error file identifies each failed row and its specific reason | US-E19-07 |
| TC-E19-009 | Run a mass delete above the volume threshold | Exact record count stated before proceeding; step-up authentication required; the operation fully audited | US-E19-08 |
| TC-E19-010 | Trained administrator performs every routine task in this epic | None requires vendor intervention, professional services or code deployment; any genuine vendor dependency is explicitly documented | US-E19-09 |
| EC-E19-001 | Exhaust slot capacity, then create another field | Message names the limit and the expansion path — never an opaque failure ([ADR-002](../architecture/adr/ADR-002-extensibility-model.md)) | US-E19-01 |
| EC-E19-002 | Deployment of a change set fails | Target unchanged; **every** blocking issue reported, not just the first | US-E19-05 |
| NEG-E19-001 | Attempt a field type change that would lose data | Blocked with a clear statement of what would be lost | US-E19-01 |
| NEG-E19-002 | Client submits a value for a field hidden by visibility rules | Not persisted — server-side enforcement, not layout cosmetics | US-E19-03 |

### E20 — Audit, compliance, observability and governance

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E20-001 | Perform any material action | Audit event records actor, time, action, before/after, source, reason where required, and correlation ID | US-E20-01 |
| TC-E20-002 | Change a close date; query field history | Who changed it, when, and both values available | US-E20-02 |
| TC-E20-003 | Reveal a sensitive field; complete an export | Read-audit event for the reveal; export audit captures filter criteria and row count, not merely the fact of an export | US-E20-03 |
| TC-E20-004 | Verify the audit chain | Any modification or removal of a historical event detectable, including via the per-tenant sequence gap | US-E20-04 |
| TC-E20-005 | Execute a data subject access and an erasure request | Personal data removed or irreversibly pseudonymized across all objects and derived stores, including search indexes, reporting projections, snapshots, AI caches and embeddings | US-E20-05, INT-012 |
| TC-E20-006 | Inspect the consent register | Consent history with lawful basis, grants and withdrawals all present | US-E20-06 |
| TC-E20-007 | Retention policy reaches a record under legal hold | The hold wins; the conflict reported | US-E20-07 |
| TC-E20-008 | Administrator on any tier initiates a full tenant export | Completes without vendor assistance; includes custom objects, attachments, audit history and configuration in a documented open format with manifest and checksums | US-E20-08 |
| TC-E20-009 | Trace any request; inspect logs and metrics | Correlation ID links every layer and downstream call; no credentials, tokens or unmasked personal data anywhere | US-E20-09 |
| EC-E20-001 | Erasure encounters a store it cannot reach | Reported as unreachable, not silently skipped | US-E20-05, INT-013 |
| EC-E20-002 | Audit a completed erasure | A non-personal record that erasure occurred is retained | US-E20-05 |
| NEG-E20-001 | Any user, administrator or platform operator attempts to modify or delete an audit event by any available path | Impossible — not merely permission-denied at the application layer | US-E20-01 |
| NEG-E20-002 | Remove or alter a historical audit event at the storage layer (test rig) | Verification detects it | US-E20-04 |

### E21 — Mobile and offline field access

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E21-001 | Use every non-administrative capability on tablet and phone viewports | Available and usable | US-E21-01 |
| TC-E21-002 | Use the native app for record access, activity capture, approvals and search | All four workflows complete | US-E21-02 |
| TC-E21-003 | Log a call immediately after a meeting via quick capture | Completed in seconds, minimal fields, defaults from context | US-E21-03 |
| TC-E21-004 | Approval and SLA-warning notifications arrive; quiet hours configured | Delivered; non-urgent notifications deferred during quiet hours | US-E21-04 |
| TC-E21-005 | Go offline with cached records | Readable; the cache age visible | US-E21-05 |
| EC-E21-001 | Offline edit conflicts with a server change; sync runs | Conflict presented for the user to resolve — silent last-write-wins is a failure | US-E21-05 |
| NEG-E21-001 | Sync an offline edit to a record the user lost access to while offline | Refused server-side; the user told why | US-E21-05, FR-GLOBAL-002 |

### E22 — BFSI vertical pack

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E22-001 | Install the BFSI pack | Objects, fields, layouts, automation, roles and reports added; no core object semantics change | US-E22-01 |
| TC-E22-002 | Uninstall the pack | States exactly what data would be affected; requires explicit confirmation | US-E22-01 |
| TC-E22-003 | Use the core product on a tenant without the pack | Behaviour identical to a build without the pack | US-E22-01 |
| TC-E22-004 | Relationship manager opens their book | One view of clients and portfolios | US-E22-02 |
| TC-E22-005 | Complete KYC onboarding, then activate the relationship | Activation succeeds only after all items complete | US-E22-03 |
| TC-E22-006 | View a computed risk rating; change it | Every weighted factor visible; actor, rationale and time audited on change | US-E22-04 |
| TC-E22-007 | Run screening; disposition a hit | The run, its result and every disposition recorded | US-E22-05 |
| TC-E22-008 | View a client's product holdings | What they hold and the whitespace both visible | US-E22-06 |
| TC-E22-009 | Recommend a product outside assessed suitability with a documented override | Requires reason and approval — cannot be issued silently | US-E22-07 |
| TC-E22-010 | Search the communication archive by client, RM, date and content; apply a legal hold | Results within the defined service window; deletion suspended under hold regardless of retention policy | US-E22-08 |
| EC-E22-001 | A KYC document approaches expiry | Owner notified at the threshold | US-E22-03 |
| NEG-E22-001 | Attempt relationship activation with incomplete KYC | Blocked, naming the outstanding items and their owner | US-E22-03 |
| NEG-E22-002 | Attempt onboarding progression past an undispositioned screening hit | Blocked until an authorized reviewer dispositions the hit with a rationale | US-E22-05 |
| NEG-E22-003 | Attempt a recommendation against an expired suitability assessment | Blocked pending reassessment | US-E22-07 |

### E23 — Commodity trading vertical pack

| ID | Scenario | Expected result | Traces |
|---|---|---|---|
| TC-E23-001 | Display counterparty fields mastered by the trading system | Read-only, showing source and last-sync time | US-E23-01 |
| TC-E23-002 | Display credit data from the trading system | Limit, utilisation, headroom, source and as-of timestamp shown — displayed as received, never computed by the CRM | US-E23-03 |
| TC-E23-003 | Create term, spot/cargo, tender and structured originations | Each uses its own pipeline, stages and exit criteria | US-E23-04 |
| TC-E23-004 | Capture a cargo enquiry | Commodity, grade, quantity and tolerance, delivery window, locations and incoterm recorded as enquiry attributes — not a nomination or scheduled movement | US-E23-04 |
| TC-E23-005 | Tender approaches its submission deadline | Escalating reminders fire at the configured thresholds | US-E23-05 |
| TC-E23-006 | Display a formula-based price indication | Index, differential, quotation period and settlement convention shown as a human-readable expression, explicitly labelled indicative and non-binding | US-E23-06 |
| TC-E23-007 | Close an origination as won; hand-off acknowledged | Payload contains counterparty, commodity, quantity and tolerance, delivery terms, pricing basis, agreed period and originating reference; returned trade reference stored on the origination | US-E23-07 |
| TC-E23-008 | Connect a CTRM implementing the five-capability contract ([ADR-007](../architecture/adr/ADR-007-external-system-integration.md)) | All pack functions operate without code changes to core or pack | US-E23-08 |
| EC-E23-001 | Tender deadline passes unsubmitted | Auto-closes as lapsed with that reason recorded | US-E23-05 |
| EC-E23-002 | Trading system becomes unavailable | CRM fully usable for relationship and origination work; credit gates fail closed; hand-offs queue for later delivery | US-E23-08 |
| NEG-E23-001 | Advance an origination past the configured stage with no executed master agreement | Blocked, naming the missing agreement | US-E23-02 |
| NEG-E23-002 | Evaluate the credit gate with data staler than threshold, and with data unavailable | Fails closed in both, stating why — never passes on missing data, never presents a stale number as current | US-E23-03 |
| NEG-E23-003 | Hand-off not acknowledged within the retry bound | Surfaces on an exception queue; the origination is **not** reported as handed off | US-E23-07 |
| NEG-E23-004 | Inspect the pack for any computed settlement price, mark-to-market or credit exposure | None exists — the CRM displays received values only | US-E23-03, US-E23-06 |

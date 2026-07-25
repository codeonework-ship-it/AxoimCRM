# User acceptance test plan

## Purpose and evidence rule

This plan governs business acceptance of Axiom. A capability is accepted only
when a named business persona completes the scenario in a release-like environment
and the evidence is attached to the release record. Product specifications describe
the target; [epic status](../epic-status.md) identifies what is available to test.

## Current acceptance boundary

The current runnable increment is the authenticated lead-to-opportunity walking
slice and its server-backed notification centre. SSO, configurable sharing, external
notification channels, workflow automation, forecasting, quoting, service, and AI
remain outside this UAT cycle.

## Participants

| Role | Seed persona | Acceptance responsibility |
|---|---|---|
| Sales representative | Priya Nair | Daily navigation, lead conversion, pipeline movement, notifications |
| Sales representative | Maya Torres | Second-user usability and record-scope checks |
| CRM administrator | Raj Malhotra | Workspace access, administrative visibility, cross-user isolation |
| Product owner | Assigned per release | Scope decision and final sign-off |
| QA lead | Assigned per release | Evidence completeness and defect disposition |

## Entry criteria

- The exact commit and container image identifiers are recorded.
- PostgreSQL migrations have completed and API/web health checks are green.
- Automated tests in [the QA plan](../../qa/qa-master-test-plan.md) pass.
- Demo data has been reset to the documented baseline.
- Known limitations and open severity 2+ defects are disclosed to participants.

## Current-cycle scenarios

| ID | Persona | Scenario | Expected outcome |
|---|---|---|---|
| UAT-001 | Priya | Sign in to workspace `meridian` and move between Home, Leads, Pipeline, and Accounts | Identity and workspace remain visible; navigation is keyboard and pointer operable; no full-page failure occurs |
| UAT-002 | Priya | Open the command palette with `Ctrl/Cmd+K`, search for Pipeline, and navigate | Results narrow predictably and the selected command opens Pipeline |
| UAT-003 | Priya | Convert an unconverted lead | Account, contact, and opportunity are created atomically; the lead becomes converted; a notification is added |
| UAT-004 | Priya | Attempt to move an opportunity into a gated stage without its economic buyer | Movement is refused with an actionable reason and the card remains in its original stage |
| UAT-005 | Priya | Move an eligible opportunity from the keyboard/touch stage selector | Board and summary refresh without losing context; a system notification is added |
| UAT-006 | Priya | Open Signal center, filter unread items, mark one read, then mark it unread | Badge, tab count, and item state update consistently; delivery reason is visible |
| UAT-007 | Raj | Attempt to mutate a notification belonging to Priya through the API | Request returns not found and Priya's item is unchanged |
| UAT-008 | Maya | Use the application at a narrow viewport and open the mobile navigation drawer | All primary destinations and sign-out remain reachable without horizontal page scrolling |
| UAT-009 | Priya | Open the operator guide from the top bar | Context, shortcuts, and notification guidance are available without leaving the task |

## Defect severity and disposition

| Severity | Meaning | Release treatment |
|---|---|---|
| S1 | Data loss, tenant leakage, security bypass, or system unavailable | Stop and reject the release |
| S2 | Core journey cannot complete and no safe workaround exists | Reject unless the product owner and security owner document an exceptional waiver |
| S3 | Journey completes with a safe workaround or material usability issue | Fix or accept with owner and target release |
| S4 | Cosmetic or low-impact documentation issue | May defer with a tracked owner |

## Exit and sign-off

All in-scope scenarios must pass; there must be no open S1 or S2 defects; tenant
isolation evidence is mandatory. The product owner, QA lead, and engineering lead
record decision, commit SHA, environment, exceptions, and date. Passing this plan
accepts only the boundary above and does not imply acceptance of target capabilities
that have not shipped.

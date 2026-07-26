# Workflow gates engine

Axiom workflow gates answer a simple operator question:

> “Can this record move forward, and if not, what exactly is missing?”

The workflow gate engine is deliberately split into two layers.

1. **Process enforcement** is the hard control. The active business process defines valid states, allowed transitions, mandatory fields and transition conditions. If a user or API tries to skip a required step, the backend refuses the transition.
2. **Gate status** is the guidance layer. It evaluates the record before the user acts, stores the current result, keeps an observation history, and gives the user the next best action in plain language.

## What is tracked

For every evaluated record, Axiom stores:

- object type and record id;
- current workflow process and state;
- gate status: `READY`, `BLOCKED`, `COMPLETED`, `UNKNOWN_STATE`, or `NO_PROCESS`;
- missing prerequisite count;
- layman-language next step;
- detailed issues with field, message, target state and next action;
- immutable observation history for audit.

## Product behavior

When a record is blocked, the UI should not merely disable a button. It should say:

- what is missing;
- why it matters to the workflow;
- what the user should do next;
- which state or step the fix unlocks.

Example:

> Close date is required for Commit. Fill Close date before moving to COMMIT.

## API

- `GET /api/v1/automation/workflow-gates/{objectType}/{recordId}`
  - evaluates one record and persists the latest status plus an observation.
- `GET /api/v1/automation/workflow-gates?objectType=OPPORTUNITY&status=BLOCKED`
  - lists latest stored gate statuses.
- `POST /api/v1/automation/workflow-gates/{objectType}/{recordId}/transitions/{targetState}/check`
  - checks the exact transition a command intends to run; the optional JSON body contains proposed field values collected by the form and does not write the business record.

## E09-E13 enforced processes

Contracts, forecast submissions, campaigns, cases and partner accounts are registered in the automation object catalogue and have active lifecycle processes. Their operational commands call the target-specific gate before updating, while database triggers enforce the same transition model for API, automation, import and support-SQL writers. A blocked attempt commits its gate observation independently, so the Automation console still records what was missing even though the business transaction is refused.

## E14-E18 enforced processes

Automation rules, analytics dashboards, copilot recommendations, integration contracts and import batches use the same contract. Simulation readiness is modelled on the rule's `simulation_passed` state; dashboard refresh and integration verification use explicit self-transitions; recommendation acceptance is a terminal disposition; and import validation moves through `VALIDATING` before it can become `READY_TO_IMPORT` or `FAILED`. This makes interrupted or repeated validation visible instead of jumping directly between end states.

## E19-E23 enforced processes

Sandbox environments, audit evidence packs, mobile device sessions, BFSI client onboarding and commodity enquiries complete the first-party lifecycle coverage. Sandbox refresh validates ownership, expiry and refresh evidence; audit packs can be exported only from `READY`; mobile sync acknowledgement is an explicit active-session self-transition; BFSI clearance requires a permitted risk rating and completed evidence; and commodity offers require a positive notional plus delivery dates. The command layer continues to enforce related-record checks such as screening results, counterparty credit and approved term sheets before the governed transition is committed.

## Safety rule

Gate status does not replace process enforcement. It is a proactive guide. The database-backed process engine remains the authority so bulk imports, APIs, automation and UI actions all obey the same workflow rules.

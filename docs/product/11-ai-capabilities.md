# AI capabilities

This document expands FRD module `AIX` (`FR-AIX-001` … `FR-AIX-016`, epic E16) into the full specification of Axiom's AI capability set, its guardrail architecture, its explainability rules and its operational controls. The architectural decision behind everything here is [ADR-004 — AI provider abstraction](../architecture/adr/ADR-004-ai-provider-abstraction.md); the runtime picture is in [system design §10](../architecture/system-design.md).

AI is a differentiator precisely because of how the competitors package it: both gate meaningful AI behind upper editions or separate SKUs (see [competitive analysis](02-competitive-analysis-salesforce-zoho.md)). Axiom's position is the opposite and is a requirement, not a pricing preference: **baseline AI capability ships in every commercial tier** (`FR-AIX-001`, `F-251`). Tier differentiation may apply to usage volume and advanced capability — never to the presence of AI assistance.

## 1. Operating principles

Four principles from [product scope](01-product-scope.md) govern every capability in this document. They are restated here because every design argument below reduces to one of them.

1. **AI proposes, humans dispose.** AI drafts, ranks, summarizes and predicts. It never sends, commits, discounts or deletes without an explicit human act.
2. **Explain every output.** Every generated output cites its grounding records; every score decomposes into weighted factors in business language. An output that cannot state its basis fails its requirement regardless of accuracy.
3. **The assistant sees exactly what the user sees.** Grounding retrieval runs as the calling user through the ordinary authorization path. No privileged retrieval exists.
4. **AI-off is a first-class mode, not a degraded one.** Every non-AI requirement in the [FRD](03-frd.md) holds with AI disabled.

## 2. Capability catalogue

| Capability | FR | Features | Story | Priority |
|---|---|---|---|:--:|
| Record summarization with citations | `FR-AIX-002` | `F-252`, `F-255` | `US-E16-01` | P0 |
| Next-best-action | `FR-AIX-003` | `F-253` | `US-E16-03` | P0 |
| Grounded drafting | `FR-AIX-004` | `F-254` | `US-E16-04` | P0 |
| Conversational query | `FR-AIX-005` | `F-256`, `F-257` | `US-E16-05` | P0 |
| Predictive scoring with decomposition | `FR-AIX-006`, `FR-AIX-008` | `F-258`, `F-260` | `US-E16-06` | P0 |
| Agentic execution with confirmation | `FR-AIX-009` | `F-261`, `F-262` | `US-E16-07` | P1 |

### 2.1 Record summarization with citations

A user can request a summary of an account, opportunity or case covering current state, recent activity, open items and risks (`FR-AIX-002`). Meeting and call summarization with extracted action items (`F-255`) is the same capability applied to a transcript source.

- Every claim in a summary cites the specific records it drew from, and the citations are navigable — clicking one opens the record, subject to the same permission check as any other navigation.
- A claim the system cannot ground in a record is omitted or explicitly labelled as unsupported (`US-E16-01`). A fluent summary containing an unattributable claim is a defect, not a style issue.
- Summaries are generated on demand and are not stored as facts on the record. A summary is a view, not data; storing it would create a second, staler version of the truth.

### 2.2 Next-best-action

The platform recommends prioritized next actions per opportunity and account (`FR-AIX-003`). Each recommendation has a fixed three-part shape: **the observation** ("no activity in 21 days on a commit-stage deal"), **why it matters** ("commit deals stalled past 14 days close at half the base rate"), and **the specific action** ("schedule the pricing review with the economic buyer"). A recommendation missing any of the three is incomplete.

Recommendations may be produced from the *absence* of records — a missing economic buyer, an empty next step — and the absence is cited as such. They respect the acting user's record and field access absolutely: the assistant never surfaces, cites or reasons over data the user cannot see.

### 2.3 Grounded drafting

The platform drafts emails, call-preparation notes and meeting follow-ups grounded in CRM records (`FR-AIX-004`).

- **Drafts are never sent automatically.** The user reviews and sends explicitly. This is the "AI proposes, humans dispose" principle made mechanical, and no configuration option relaxes it.
- The draft's provenance — that it was AI-generated, from which grounding records, under which masking policy — is recorded on the resulting activity when sent.
- Drafting respects consent and suppression (`FR-ACC-011`): the assistant will not draft outreach to a suppressed contact, and says why.

### 2.4 Conversational query

Users ask questions of their CRM data in natural language and receive answers with the underlying records shown (`FR-AIX-005`, `F-256`). Natural-language report generation (`F-257`) is the same interpretation pipeline emitting a saved report definition instead of a one-off answer — the generated report is then a normal report, subject to everything in [reporting and analytics](14-reporting-and-analytics.md).

Two rules distinguish this from a demo feature:

- **The interpretation is displayed.** The system shows how it understood the question — the objects, filters, date ranges and aggregation it resolved the question to — so a user can detect a misinterpretation rather than trust a confident wrong answer.
- **"Cannot substantiate" is a valid answer.** Where the question cannot be answered reliably — ambiguous phrasing, data the user cannot access, a metric with no governed definition — the system says so. It must not produce a plausible answer it cannot substantiate. This is restated as an explainability rule in §4.3 because it applies to every capability, not only query.

### 2.5 Predictive scoring with decomposition

The platform produces lead conversion (`FR-LED-007`), deal win, renewal (`FR-CTR-010`) and health (`FR-ACC-014`) predictions (`FR-AIX-006`, `F-258`), plus the forecast prediction with confidence interval (`FR-FCT-007`).

**Every score is presentable as its weighted contributing factors, each with direction and magnitude, in business language** (`FR-AIX-008`, `F-260`). "Engagement dropped 60% over 30 days (strong negative)" is a factor; "feature_embedding_14 = 0.83" is not. If the underlying model cannot support a business-language decomposition, the score does not ship — the requirement constrains model selection, not the other way round.

Scores never replace human judgment structurally: the AI forecast prediction is presented alongside — never in place of — the submitted human forecast (`FR-FCT-007`), and a prediction that cannot be decomposed must not be presented as a forecast number (`FR-FCT-005`).

### 2.6 Agentic execution with confirmation gates and unit rollback

The assistant can execute multi-step tasks: research an account, update fields, create follow-ups, draft and queue outreach (`FR-AIX-009`, `F-261`, `F-262`, `US-E16-07`). This is the capability with the highest damage potential, so it carries the strictest controls:

1. **Plan before action.** The agent presents the complete plan and the exact changes it would make — field by field, record by record. Not a description of intent; the actual diff.
2. **Explicit confirmation gate.** Execution begins only on explicit human confirmation of that plan. Confirmation of a plan is not consent to a different plan; if re-planning changes the actions, the gate re-arms.
3. **Attribution.** Every action is attributed to the AI source with the initiating user (`AUDIT_EVENT.source = ai` — see [data model §7](09-data-model.md)). Automation triggered by an agent action carries the same provenance chain.
4. **Halt on failure.** A step that fails halts the sequence and reports what completed and what did not. Partial silent completion is not acceptable.
5. **Unit rollback.** The whole action set is reversible as a unit within a retention window. Rollback of a unit is itself audited. Where an action has left the system — an email queued and sent, a webhook fired — rollback reports it as irreversible rather than pretending otherwise.
6. **Existing controls are not bypassed.** Agent actions pass through validation, enforced business processes (`FR-AUT-004`), approval requirements and maker-checker exactly as a user's own actions would. An agent cannot do anything its initiating user could not do.

### 2.7 Failure and degradation behaviour

AI capabilities fail like any external dependency, and each failure mode has a defined behaviour. "The product stops" is not one of them — the same discipline applied to every connector in [integration and migration](13-integration-and-migration.md).

| Condition | Behaviour |
|---|---|
| Provider unavailable | AI surfaces show an unavailable state; every underlying workflow remains completable manually. No queue of pending generations builds up silently |
| Provider slow beyond timeout | The request is abandoned with a clear message; it is never retried invisibly at additional cost |
| Tenant volume bound reached | Warning at a configured threshold before the bound; at the bound, AI surfaces state the reason. Never a silent cut-off, never a surprise invoice (`FR-AIX-001`) |
| Evaluation regression on a pending change | The change does not ship (`FR-AIX-016`). Tenants stay on the last passing configuration |
| Masking policy cannot be applied | The interaction is refused, not sent unmasked. Fail closed is the only acceptable direction for a data-protection control |
| Grounding retrieval returns nothing | The capability says so (§4.3) rather than generating from nothing and presenting it as grounded |

An in-flight agentic sequence (§2.6) interrupted by any of these halts and reports per rule 4; it never resumes silently after recovery without re-confirmation.

## 3. Guardrail architecture

The guardrails are architectural facts, not policy statements. Each is enforced by construction and verified by mandatory `SEC-` test cases in [acceptance tests](06-acceptance-tests.md).

### 3.1 Permission-scoped grounding — the control that matters most

**Grounding retrieval executes with the calling user's permissions, through the ordinary authorization path. No privileged service-account retrieval path exists** (`FR-AIX-003`, `FR-AIX-010`, `US-E16-02`; [ADR-004](../architecture/adr/ADR-004-ai-provider-abstraction.md) decision 4).

Why privileged retrieval is prohibited, stated plainly: a retrieval component running as a privileged service account is the single most likely way this product would leak data between users. It would be faster to build and faster to run. It would look completely correct in every demo and every happy-path test — because the leak only appears when a user asks a question whose answer touches a record they cannot see, and the assistant answers helpfully. The failure is silent, plausible and invisible to the person harmed. Post-filtering the results does not fix it: the model has already seen the unauthorized data, and anything it inferred from it is in the output regardless of what is filtered afterwards.

The consequences are accepted deliberately: permission-scoped retrieval is slower than unrestricted retrieval and forbids some caching strategies. Two users asking the same question get answers grounded only in their own permitted data — which means they may get *different* answers, and that is correct behaviour, not an inconsistency bug.

### 3.2 Tenant-scoped grounding and per-tenant embeddings

No prompt, embedding, cache or context may contain data from more than one tenant (`FR-AIX-010`, `F-263`). Embeddings live in a per-tenant namespace; there is no shared vector space. This costs more storage than a shared space and makes some cross-tenant optimizations impossible — that is the intended outcome, not an accepted loss.

### 3.3 PII masking

Designated personal and sensitive fields are masked or excluded before any model invocation, per tenant policy, and the applied policy is recorded on the interaction (`FR-AIX-012`, `F-265`). Masking composes with field-level security (`FR-SEC-007`) and sensitive-field masking (`FR-SEC-008`): FLS decides what the user's retrieval can see at all; the masking policy decides what, of that, may leave for a model. Both are applied server-side; neither is advisory.

### 3.4 No-training guarantee

Tenant data is not used to train or fine-tune any model shared across tenants (`FR-AIX-011`, `F-264`). The guarantee is contractual **and** technical: provider agreements require zero retention and no training (FRD assumption 5), and the provider abstraction refuses configuration of a provider whose terms do not carry the guarantee. A tenant that cannot obtain the guarantee from any acceptable hosted provider runs self-hosted or AI-off — those are the honest options, and the product does not paper over them.

### 3.5 The AI_INTERACTION audit record

Every model interaction writes an `AI_INTERACTION` record: prompt reference, grounding record IDs, model, masking policy applied, output reference, latency, cost and user acceptance ([data model §7](09-data-model.md)). This is what makes every guardrail in this section auditable *after the fact* rather than merely rendered at the time — a compliance officer can answer "what did the AI see, for whom, and what did it produce" for any interaction in the retention window (`FR-GLOBAL-005`).

### 3.6 Erasure reaches the AI stores

Data-subject erasure (`FR-AUD-008`) enumerates embeddings and AI caches as targets, not only primary storage. These are the most commonly forgotten stores in a right-to-erasure implementation; any store the erasure process cannot reach is reported, not silently skipped.

### 3.7 Grounding scope and data minimization

Each capability grounds on the narrowest record set that serves it, retrieved as the calling user (§3.1). The scope is part of the capability definition, not a per-request improvisation:

| Capability | Grounding scope |
|---|---|
| Summarization | The target record, its timeline (`FR-ACC-012`), open related items and recent field changes |
| Next-best-action | The target record, its activity recency, buying-group completeness and risk signals (`FR-OPP-009`) |
| Grounded drafting | The recipient contact, the related record and the recent thread — not the whole account history |
| Conversational query | The records resolved by the displayed interpretation, and nothing beyond it |
| Predictive scoring | The feature set published in the score's decomposition — a factor not in the decomposition is not an input |
| Agentic execution | The records named in the confirmed plan |

Minimization is a guardrail, not an optimization: every record retrieved is a record that leaves for a model under the masking policy, so the smallest sufficient scope is the correct one. The retrieved set is exactly what `AI_INTERACTION.grounding_record_ids` records, which is what makes the scope auditable per interaction.

## 4. Explainability rules

Three rules apply to every capability without exception. They are the product's answer to the market's central AI credibility problem, and they are testable.

### 4.1 Universal citation

**Every generated output identifies the records that grounded it** (`FR-AIX-007`, `F-259`). An output that cannot cite its basis is labelled as unsupported and is never presented as derived from CRM data. Citations are stored in `AI_INTERACTION.grounding_record_ids`, so they are verifiable later, and navigating a citation performs a fresh permission check — a citation is a link, not a leak.

### 4.2 Universal score decomposition

**Every score is decomposable into weighted contributing factors with direction and magnitude, in business language** (`FR-AIX-008`, `F-260`). This extends to forecasting: any forecast number, including the AI prediction, decomposes to the opportunities that constitute it (`FR-FCT-005`). The decomposition is the same one the model actually used — a post-hoc rationalization presented as an explanation would satisfy the letter of the requirement and betray its purpose, and is treated as a defect.

### 4.3 Cannot substantiate → say so

Where the system cannot answer reliably or ground a claim, **it says so** (`FR-AIX-005`). "I can't answer that from your data" preserves trust; a confident fabrication destroys it — and in a CRM the fabrication gets pasted into a customer email. Declining to answer is a success mode of the product, and the evaluation harness (§7) measures it as one: a capability that never declines is over-claiming.

## 5. AI-off mode

A tenant can disable AI entirely (`FR-AIX-013`, `F-266`, `US-E16-09`). The requirement is stronger than a feature flag:

- With AI off, **every non-AI requirement in the [FRD](03-frd.md) remains fully satisfied**. No core workflow depends on an AI capability — this is architectural (the null provider is an adapter like any other; AI capability is a flag consulted at the surface layer, never a dependency in a domain service).
- AI surfaces are **absent** — not shown as errors, not shown as upsells. A greyed-out "Upgrade to enable AI" button in an AI-off sovereign deployment would violate both this requirement and `FR-GLOBAL-011`'s spirit.
- AI-off is verified continuously: the full non-AI acceptance suite runs against an AI-off configuration, and every AI surface is verified to disappear cleanly ([ADR-004](../architecture/adr/ADR-004-ai-provider-abstraction.md) compliance section).

## 6. Provider abstraction and sovereign deployment

Capabilities are expressed in Axiom's vocabulary — summarize, rank, extract, predict, converse, embed — and provider adapters implement that contract (`FR-AIX-014`, `F-267`; [ADR-004](../architecture/adr/ADR-004-ai-provider-abstraction.md)). Provider selection is per-tenant configuration, not code. Hosted providers, self-hosted models and the null provider (AI-off) are all adapters.

For sovereign deployments this means AI runs **without any external model call**: a customer-hosted model behind the same abstraction, with the same guardrails, citations and telemetry. Two honest caveats, stated to customers rather than discovered by them:

- **Self-hosted models will underperform frontier hosted models.** Sovereign customers are told this plainly rather than sold parity. The evaluation harness (§7) quantifies the gap per capability so the conversation is factual.
- The abstraction cannot expose provider-specific capabilities without leaking through. Some genuinely useful provider features will be unavailable until the capability contract is deliberately extended.

## 7. Evaluation harness

AI capabilities are covered by a repeatable evaluation suite, run on every model or prompt change, **with regressions blocking release** (`FR-AIX-016`, `F-269`, `US-E16-10`). Without this, prompt changes ship on vibes and quality regresses invisibly — the harness is real, ongoing engineering cost accepted deliberately ([ADR-004](../architecture/adr/ADR-004-ai-provider-abstraction.md)).

Published quality metrics, per capability:

| Metric | What it measures |
|---|---|
| Grounding fidelity | Fraction of output claims traceable to a cited record; fabrication rate |
| Citation precision | Fraction of citations that actually support the claim they are attached to |
| Decomposition validity | Whether stated factors reproduce the score within tolerance |
| Refusal correctness | Declines when it should (unanswerable) and does not when it should not |
| Permission leak rate | **Must be zero.** Any grounding of a record outside the test principal's access fails the run outright, regardless of other scores |
| Interpretation accuracy | Conversational query: resolved interpretation matches intent on the reference set |
| Acceptance proxy | Agreement with human-rated reference outputs |

Evaluation sets are synthetic and reference-tenant data — never live tenant data, which would itself violate `FR-AIX-011`. User feedback on production outputs (`F-270`) feeds metric calibration as aggregate signals, not as training data.

Harness governance:

- Every capability × provider combination the product supports is evaluated — including self-hosted providers, so the sovereign quality gap (§6) is a published number per capability, not an anecdote.
- Results are versioned alongside the prompt and model configuration that produced them; a regression report names the specific metric, the delta and the failing cases.
- The blocking rule is absolute for the zero-tolerance metrics (permission leak rate) and threshold-based for quality metrics, with thresholds set per capability and changed only by explicit product decision — never loosened quietly to let a release through.

## 8. Cost and usage telemetry

Tenant administrators see AI usage volume, cost where applicable, latency, and quality signals including user acceptance and rejection of AI outputs (`FR-AIX-015`, `F-268`), sliced by capability and by time. This serves three distinct needs honestly:

- **The tenant's budget.** Volume-based tier boundaries (`FR-AIX-001` permits volume differentiation) are visible and predictable, never a surprise invoice. Approaching a volume bound produces a warning, not a silent cut-off.
- **The tenant's trust decision.** Acceptance/rejection rates per capability tell an administrator which capabilities their users actually find useful — and support an evidence-based decision to disable a capability that is not earning its keep.
- **Our quality loop.** Rejection spikes after a model or prompt change are a production signal that the evaluation harness missed something.

Telemetry is derived from `AI_INTERACTION` records, so the numbers the administrator sees reconcile exactly to the audited interaction stream.

## 9. UI provenance convention — gold marks AI

Every AI-generated or AI-derived element in the product — summaries, recommendations, drafts, scores, predictions, agent plans — is visually marked with the design system's reserved **gold** provenance treatment: gold accent border, gold provenance icon, and an explicit "AI-generated" label for assistive technology (colour alone never conveys the meaning, per `FR-GLOBAL-008`). Gold is reserved: no non-AI surface may use the provenance treatment, and no AI content may appear without it. The rule is deliberately absolute because its value is habit-forming — a user who has learned "gold means generated, verify before you rely" must never encounter a counterexample in either direction. The design system carries the token definitions and component states; this document owns the rule.

Where the convention applies, concretely:

- **Summaries and answers** — gold-bordered panels with inline citation markers.
- **Scores and predictions** — the gold provenance icon beside the value, everywhere the value renders: record page, list view column, report cell, dashboard component.
- **Drafts** — gold-marked until the user sends; the sent activity records AI provenance in data (§2.3) even though the delivered email is, deliberately, unmarked to its recipient.
- **Agent plans and their applied changes** — gold in the plan preview; applied field changes show AI-source attribution in field history (`FR-AUD-002`).
- **Edited AI content** — a draft the user has materially edited before saving may drop the visual mark; the provenance record remains. The mark tracks what the user is currently relying on; the audit trail tracks origin forever.

The convention extends to exports and deliveries: an AI-derived value included in a scheduled report or export retains an AI-provenance marker in the delivered artefact.

## 10. Requirements coverage

| Requirement | Where specified |
|---|---|
| `FR-AIX-001` availability in all tiers | §0 intro, §8 |
| `FR-AIX-002` summarization | §2.1 |
| `FR-AIX-003` next-best-action | §2.2, §3.1 |
| `FR-AIX-004` grounded drafting | §2.3 |
| `FR-AIX-005` conversational query | §2.4, §4.3 |
| `FR-AIX-006` predictive scoring | §2.5 |
| `FR-AIX-007` universal citation | §4.1 |
| `FR-AIX-008` score decomposition | §2.5, §4.2 |
| `FR-AIX-009` agentic execution | §2.6 |
| `FR-AIX-010` tenant-scoped grounding | §3.1, §3.2 |
| `FR-AIX-011` no training on tenant data | §3.4 |
| `FR-AIX-012` PII handling | §3.3 |
| `FR-AIX-013` AI-off mode | §5 |
| `FR-AIX-014` provider abstraction | §6 |
| `FR-AIX-015` usage and quality telemetry | §8 |
| `FR-AIX-016` evaluation harness | §7 |

## Related documents

- [Product scope](01-product-scope.md) — the AI-native differentiator and product principles
- [FRD](03-frd.md) §21 — the `AIX` requirements this document expands
- [Feature catalogue](04-feature-catalogue.md) E16 — competitive positioning per feature
- [Epics and user stories](05-epics-and-stories.md) E16 — delivery decomposition and acceptance criteria
- [Data model](09-data-model.md) §7 — `AI_INTERACTION` and audit entities
- [ADR-004 — AI provider abstraction](../architecture/adr/ADR-004-ai-provider-abstraction.md) — the architectural decision
- [System design](../architecture/system-design.md) §10 — AI runtime architecture
- [Reporting and analytics](14-reporting-and-analytics.md) — where AI predictions surface in reporting

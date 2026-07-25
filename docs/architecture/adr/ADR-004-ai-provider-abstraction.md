# ADR-004 — AI provider abstraction

**Status:** Accepted · **Date:** 2026-07-25

## Context

AI is a core product differentiator: available in every tier (`FR-AIX-001`), always explainable (`FR-AIX-007`, `FR-AIX-008`), and fully disableable (`FR-AIX-013`). Three constraints shape the design:

1. **Sovereign deployments** must run without any external model call — either self-hosted, or AI off entirely.
2. **Model providers change fast.** Capability, price and terms move on a timescale of months. A design coupled to one provider ages badly and negotiates from weakness.
3. **Tenant data must never leak** — across tenants, across users within a tenant, or into a provider's training corpus (`FR-AIX-010`, `FR-AIX-011`).

## Decision

**A capability-oriented provider abstraction, with retrieval running under the calling user's own permissions.**

1. AI capabilities are expressed in **our** vocabulary — summarize, rank, extract, predict, converse, embed — not a provider's API shape.
2. Provider adapters implement the capability contract. Hosted providers, self-hosted models and a null provider (AI off) are all adapters.
3. Provider selection is **configuration per tenant**, not code.
4. **Grounding retrieval executes with the calling user's permissions, through the ordinary authorization path.** No privileged service-account retrieval exists.
5. A **PII masking policy** is applied before any egress, and the applied policy is recorded on the interaction.
6. Embeddings live in a **per-tenant namespace**. There is no shared vector space.
7. Every interaction writes an `AI_INTERACTION` record: grounding record IDs, model, masking policy, output reference, latency, cost, and user acceptance.
8. **AI-off removes surfaces, not function.** No core workflow may take a hard dependency on an AI capability.
9. An **evaluation harness** runs on every model or prompt change; regressions block release (`FR-AIX-016`).

## Rationale

**Decision 4 is the one that matters most, and it is worth being blunt about why.** A retrieval component running as a privileged service account is the single most likely way this product would leak data between users. It would be faster. It would be simpler. It would look completely correct in every demo and every happy-path test — because the leak only appears when a user asks a question whose answer touches a record they cannot see, and the assistant answers helpfully. The failure is silent, plausible and invisible to the person harmed.

Routing retrieval through the same authorization path as a normal read costs some latency and forbids some caching strategies. That is the correct trade.

**Decisions 1–3** exist because provider lock-in is a commercial risk as much as a technical one. If switching providers is a refactor, we cannot renegotiate, cannot respond to a terms change, and cannot serve a sovereign customer at all.

**Decision 8** is what makes AI-off honest. It is easy to say a product works without AI and then quietly build a workflow that assumes a generated summary exists. The constraint has to be architectural — AI capability is a flag consulted at the surface layer, never a dependency in a domain service.

## Consequences

**Positive**
- Providers are swappable per tenant, including self-hosted for sovereign
- Sovereign deployments are genuinely supported rather than nominally supported
- No lock-in; commercial leverage preserved
- Explainability is auditable after the fact via `AI_INTERACTION`, not merely rendered at the time
- Cost and quality are measurable per tenant and per capability

**Negative, stated honestly**
- The abstraction cannot expose provider-specific capabilities without leaking through. Some genuinely useful provider features will be unavailable until the contract is extended — and extending it for one provider's convenience is how abstractions rot.
- Permission-scoped retrieval is slower than unrestricted retrieval and constrains caching. Accepted deliberately.
- Self-hosted models will underperform frontier hosted models. Sovereign customers must be told this plainly rather than sold parity.
- An evaluation harness is real, ongoing engineering cost. Without it, prompt changes ship on vibes and quality regresses invisibly.
- Per-tenant embedding namespaces cost more storage than a shared space and make some cross-tenant optimizations impossible. That is the intended outcome.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Direct integration with one provider | Fastest to build; no sovereign story, no leverage, and a terms change becomes an emergency |
| Third-party AI gateway/framework | Adds a dependency that must itself be self-hostable for sovereign, and typically abstracts at the wrong level — provider APIs rather than product capabilities |
| Privileged retrieval with post-filtering of results | Faster, and wrong. The model has already seen the unauthorized data; anything it infers from it is in the output regardless of what is filtered afterwards |
| AI as a separately-licensed module | Directly contradicts `FR-AIX-001` and the differentiator it serves |
| Fine-tuning per tenant | Cost and operational complexity are not justified at this stage; retrieval-grounded generation covers the specified capabilities |

## Compliance

- Cross-tenant and cross-user grounding leakage are explicit, mandatory test cases (`SEC-` series in [acceptance tests](../../product/06-acceptance-tests.md)).
- Every AI surface must be verified to disappear cleanly under AI-off, with the underlying workflow still completable.
- The erasure process must reach embeddings and caches (`FR-AUD-008`) — the most commonly forgotten store in a right-to-erasure implementation.
- Related: `FR-AIX-001` … `FR-AIX-016`; [AI capabilities](../../product/11-ai-capabilities.md).

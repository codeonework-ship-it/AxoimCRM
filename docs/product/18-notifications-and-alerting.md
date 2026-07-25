# Notifications and alerting

> **Implementation status — 2026-07-25:** the walking slice now ships a server-backed in-app feed with tenant/user scoping, PostgreSQL RLS, chronological retrieval, unread count, read/unread/read-all operations, safe internal deep links, delivery reason, action-required state, polling refresh, and transactional notifications for lead conversion and opportunity stage movement. Email, push, digests, quiet hours, storm control, preferences, action completion, and full render-time record authorization remain target requirements below and are not claimed as complete.

**Product:** Axiom — Enterprise B2B CRM
**Status:** Specification for build
**Date:** 2026-07-25

The notification system tells a user that something needs their attention, without becoming the thing they learn to ignore. It is a cross-cutting platform capability: nearly every epic emits notifications ([assignments](05-epics-and-stories.md#e05--lead-capture-qualification-and-routing), [approvals](05-epics-and-stories.md#e14--workflow-automation-approvals-and-rules-engine), [SLA clocks](03-frd.md), [deal risk](03-frd.md), [AI recommendations](11-ai-capabilities.md), [forecast windows](05-epics-and-stories.md#e10--forecasting-and-revenue-intelligence)), and one rule binds all of them:

> **A notification must never disclose data its recipient cannot read.** Every notification is authorized server-side against the recipient's effective permissions at delivery time *and* again at render time, per `FR-GLOBAL-002`. A notification is a read like any other read.

## 1. Design principles

1. **Action-required is real-time; informational is digested.** The default posture for low-urgency types is a digest, not an interruption. Notification fatigue is not a preference problem to be solved by users toggling things off — it is a product defect prevented by defaults.
2. **Every notification deep-links to the thing it is about**, positioned at the action it asks for (the approval decision, the at-risk deal, the breached lead) — never to a home page.
3. **Every notification states why the recipient received it.** "You are the assigned approver", "you own this lead", "you were @mentioned". A notification that cannot explain itself follows the same rule as a score that cannot ([product principle 2](01-product-scope.md#product-principles)).
4. **Notifications are events, not state.** They are emitted from the [event backbone](../architecture/adr/ADR-003-event-backbone.md) via the outbox — a notification is never the only record of the fact it announces, and losing one never loses business state.
5. **AI-originated notifications are visibly AI-originated** — they carry the gold marking used across every AI surface (see [AI capabilities](11-ai-capabilities.md)), so a user always knows whether a machine or a colleague is asking for their attention.

## 2. In-app notification centre

The bell in the workspace header, available on every screen.

**Requirements**

- The bell must show an unread count, capped for display (e.g. `99+`) but exact in the panel.
- Opening the centre must list notifications newest-first, **grouped by type** with per-group counts (e.g. "4 approval requests", "12 mentions"), with an ungrouped chronological view one action away.
- Each item must show: type, a one-line summary, the record it concerns (name only — subject to §6), why the recipient got it, relative time, and read/unread state.
- Each item must **deep-link to the specific record and position** it concerns. Following the link marks the item read.
- Mark-read must be available per item, per group, and for all. Mark-unread must be available per item.
- Items must be retained and queryable for a tenant-configurable window (default 90 days); the centre paginates rather than truncating silently.
- Action-required items (approval requests, SLA breaches) must remain visually distinct until the underlying action is taken, even after being read — read is not the same as done.
- The centre must be fully keyboard-operable and screen-reader navigable per `FR-GLOBAL-008`.

**On failure:** if the notification service is degraded, the product keeps working and the bell shows a degraded indicator; notifications are queued and delivered late rather than dropped, per the [degraded-modes row of the system design](../architecture/system-design.md#12-availability-and-disaster-recovery).

## 3. Notification types catalogue

| Code | Type | Triggered by | Urgency | Default channels | Source epic / requirement |
|---|---|---|:--:|---|---|
| `NT-ASSIGN` | Record assignment | Lead/case/opportunity assigned or reassigned to the recipient, or landed in a queue they work | Action-required | In-app + push | E05 `FR-LED-008`, E12 `FR-CAS-003` |
| `NT-MENTION` | @mention | The recipient is @mentioned in a note, comment or plan | Timely | In-app + push | E07 |
| `NT-APPR-REQ` | Approval request | An approval step resolves to the recipient as approver | **Action-required** | In-app + push + email | E14 `FR-AUT-007`, E08 `FR-CPQ-009` |
| `NT-APPR-DEC` | Approval decision | A request the recipient submitted is approved, rejected or recalled | Timely | In-app + push | E14 |
| `NT-SLA-WARN` | SLA warning | A first-response or case milestone crosses its warning threshold | **Action-required** | In-app + push | E05 `FR-LED-009`, E12 `FR-CAS-005` |
| `NT-SLA-BREACH` | SLA breach | A milestone breaches; escalation actions fire alongside | **Action-required** | In-app + push + email | E12 `FR-CAS-005` |
| `NT-RISK` | Deal risk signal | A risk indicator fires on an opportunity the recipient owns or manages — engagement gap, single-threading, slippage, stall | Informational | In-app + digest | E06 `FR-OPP-009` |
| `NT-AI-REC` | AI recommendation · **gold-marked** | Next-best-action or other AI recommendation for the recipient | Informational | In-app + digest | E16 `FR-AIX-003` |
| `NT-FCT-WINDOW` | Forecast submission window | Submission window opens / approaches close for a forecasting user who has not submitted | Timely | In-app + push + email (unsubmitted only) | E10 `FR-FCT-004` |
| `NT-RPT-THRESH` | Report threshold alert | A subscribed metric crosses its configured bound | Timely | In-app + email | E15 `FR-RPT-007` |
| `NT-SYS` | System / administrative | Certificate expiry warnings, integration failures, import/export/migration job completion, break-glass use, release notices | Varies (admin-targeted) | In-app + email | E01 `FR-TEN-004`, E17 `FR-INT-009`, E20 |

Rules that hold across the catalogue:

- **Threshold subscriptions notify only on crossing**, never on every evaluation (`FR-RPT-007`).
- **`NT-AI-REC` never escalates itself to real-time.** AI proposes; only a human-configured rule or a human act produces an interruption.
- Vertical packs and custom automation register new types through the same catalogue with the same metadata (urgency class, default channels) — a pack cannot mint an ungoverned notification path.

## 4. Delivery channels

| Channel | Mechanism | Notes |
|---|---|---|
| **In-app** | Notification centre (§2) + transient toast for action-required types | Always on; the system of record for what was notified |
| **Email digest** | Batched summary per §7; individual email only for types whose default or user preference says so | Respects consent and suppression where the recipient is a portal/external user |
| **Push — mobile** | Native iOS/Android push per `FR-MOB-004` (E21) | Payload discipline per §6 |
| **Push — desktop** | OS-native notifications via the Electron client | Same payload discipline; delivery state visible in the centre |
| **Webhook** | Tenant-configured endpoints receiving notification events for integration (Slack/Teams bridges, ticketing) | Delivered through the standard webhook infrastructure — signed, retried, dead-lettered (`FR-INT-005`). Payload carries record IDs and type, **not** field values, so the receiving system must fetch details through the API under its own authorization |

Channel failure never cascades: a push provider outage leaves in-app and email intact, and delivery state per channel is recorded so "did the approver actually get told" is answerable from the audit trail.

## 5. Preferences, quiet hours and tenant defaults

**Per-user preference matrix (channel × type).** Every user can set, per notification type: in-app (always on — the centre is the floor), email (real-time / digest / off), push (on / off). The matrix is one screen, defaulted sensibly (§3), and changes take effect immediately.

**Quiet hours.** Per-user quiet hours in the user's own time zone defer push and real-time email; deferred items deliver as a batch when quiet hours end. In-app items still accrue. Quiet hours never defer `NT-SLA-BREACH` for users with an on-call/escalation role — a tenant-level policy names which types, if any, may pierce quiet hours, and the default set is that one.

**Tenant-admin defaults.** Administrators set the tenant default matrix, may lock specific cells (e.g. approval requests cannot be turned off entirely — some notification path must remain), and may cap real-time email globally. User preference wins wherever the tenant has not locked the cell.

**On failure:** a preference the user cannot change (locked by tenant policy) is shown as locked with the policy named — not silently ignored.

## 6. Access control — the rule that cannot bend

`FR-GLOBAL-002` applied to notifications, stated as testable behaviour:

- **Fan-out is permission-checked.** When an event produces candidate recipients (owner, manager chain, queue members, @mentioned users), each candidate's read access to the subject record is evaluated server-side before a notification is created for them. No access, no notification — not a redacted one, none, because "you cannot see why" still discloses existence.
- **Render is permission-checked again.** Access can be revoked between delivery and reading. When the centre renders, each item's subject is re-authorized; an item whose subject the recipient can no longer read is suppressed from the list. Notification content is never served from a cache that bypasses this check — the same reasoning as [search result re-checking](../architecture/system-design.md#82-search).
- **Push and email payloads are minimal.** External channels transit infrastructure outside the product's authorization boundary, so payloads carry the type and a generic summary ("You have an approval request"), never field values, amounts or record names for masked/sensitive contexts. The detail lives behind the deep link, which authorizes on arrival.
- **Field-level security applies inside notification text.** A notification template that would interpolate a field the recipient cannot read renders without it, exactly as the API omits it (`FR-SEC-007`) — absent, not blanked.
- **Digest assembly is per-recipient.** A digest is built from the recipient's own permitted items only; a shared/team digest is a prohibited construction.
- Every suppressed-for-access notification raises the same audit trail as any denied read.

**Test obligation:** the acceptance suite must include the two-user asymmetry case — same event, one recipient with access and one without — verifying the second user receives nothing on any channel and cannot infer the record's existence from the centre, counts or digests.

## 7. Batching and digest rules — preventing notification fatigue

**The principle: default to digest for low-urgency, real-time only for action-required.** A notification system earns real-time interruptions by spending them rarely.

| Rule | Behaviour |
|---|---|
| Digest cadence | Per-user daily digest (default 08:00 local) collecting all digest-class items: `NT-RISK`, `NT-AI-REC`, read-class `NT-SYS`. Weekly option for users who want less |
| Collapse | Repeated signals on the same record within a digest window collapse to one line with a count ("3 risk signals on Acme renewal"), never N separate items |
| Deduplication | The same underlying event reaching a user by two paths (owner *and* @mentioned) produces one notification, attributed to the strongest reason |
| Storm control | A burst source (bulk import triggering thousands of assignments, an automation misfire) is coalesced into a single summary notification per user with a count and a link to the list; per-item fan-out above the burst threshold is suppressed and the suppression is visible to admins |
| Escalation upgrade | A digested item whose situation escalates (warning → breach) is promoted to its real-time type; the digest entry is superseded, not duplicated |
| Empty digests are not sent | No "nothing happened" email, ever |

Tenant admins see per-type volume telemetry (sent, read, actioned, dismissed rates). A type with a high-sent/low-actioned profile is the signal to re-tier it to digest — measured, not guessed.

## 8. Data and audit

- Notifications are per-user, tenant-scoped rows (deduplicated per recipient), created by a consumer of the domain event stream — never synchronously inside the business transaction, so a slow channel cannot slow a record save ([ADR-003](../architecture/adr/ADR-003-event-backbone.md)).
- Delivery attempts and outcomes per channel are recorded; notification creation and suppression events participate in the standard audit trail (`FR-GLOBAL-005`).
- Notification content is subject to data-subject erasure (`FR-AUD-008`) — a notification that quotes personal data is one of the derived stores erasure must reach.
- Retention follows the tenant's configured window; expiry is a purge, not a soft-hide.

## Related documents

- [FRD](03-frd.md) — `FR-GLOBAL-002`, `FR-MOB-004`, `FR-RPT-007`, `FR-AUT-007`, `FR-CAS-005`, `FR-LED-009`, `FR-OPP-009`
- [AI capabilities](11-ai-capabilities.md) — the gold marking and AI recommendation surfaces
- [RBAC and sharing model](08-rbac-and-sharing-model.md) — the permission evaluation notifications reuse
- [System design](../architecture/system-design.md) — event backbone, degraded modes, search re-check precedent
- [User guide — notification preferences](../manual/user-guide.md#notifications-and-staying-in-control) — the end-user view of this specification

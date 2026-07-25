# AEGIS // Kinetic Alloy — UI/UX foundation

**Status:** implemented foundation for the walking preview

**Last reviewed:** 2026-07-25
**Scope:** responsive web application and Electron desktop shell

## 1. Product experience intent

Axiom is an exception-first revenue workspace. It should help a seller or manager answer three questions in under a minute:

1. What changed?
2. What needs my action?
3. What is the safest next move?

The interface uses an original cinematic mechanical language: graphite structure, precise seams, chamfered landmarks, and short “locking” confirmations. The inspiration is transformation of information into action—not characters, faction marks, licensed logos, vehicle likenesses, franchise language, or copied entertainment assets.

The system name is **AEGIS // Kinetic Alloy**. “Aegis” describes protection and governance; “Kinetic Alloy” describes a workspace whose information reorganizes around the operator’s task.

## 2. Experience principles

### Exception first

Home leads with risks and decisions rather than decorative charts. Every prominent signal must link to a place where the operator can investigate or act.

### Explain the stop

Governance is part of the product, not an error state. When a stage gate refuses a move, say what requirement is missing and preserve the operator’s prior state. Never use a shake, color, or disabled control as the only explanation.

### Dense, not cramped

Desktop layouts favor enterprise working density, but text, focus rings, and controls remain readable over long sessions. On touch devices, targets expand and actions wrap rather than shrinking.

### Gold has one meaning

Gold marks AI-generated or AI-assisted content only. It must not indicate premium tiers, success, selected navigation, revenue, or decoration. AI surfaces must state provenance and preserve human control.

### Truthful previews

Unimplemented capabilities are described as previews or roadmap items. The current recipient-scoped notification feed cannot be presented as the complete multi-channel notification platform, and a command navigator cannot be described as cross-record search.

## 3. Information architecture

The current preview exposes only delivered workspaces:

| Workspace | Operator goal | Current route |
|---|---|---|
| Revenue command | Understand posture and exceptions | `/` |
| Pipeline | Inspect and advance opportunities | `/pipeline` |
| Accounts | Review organizations and ownership | `/accounts` |
| Leads | Qualify and convert demand | `/leads` |

Future navigation groups are reserved as follows, but must not appear until a functional route exists:

- **Plan:** Forecasts, Reports
- **Operate:** Activities, Notifications
- **Configure:** Preferences, Administration (permission-gated)

The persistent desktop rail is labelled. Below 900 px it becomes an off-canvas drawer. The top bar always retains tenant context, the command trigger, notification count, and essential utilities.

## 4. Visual language

### Color roles

| Role | Dark reference | Rule |
|---|---:|---|
| Canvas | `#070B12` | Primary application ground |
| Raised surface | `#111925` | Panels and controls |
| Strong seam | `#3B4B64` | Focus-adjacent borders |
| Primary text | `#E8EDF6` | Main content |
| Muted text | `#98A8BF` | Secondary content; must retain AA contrast |
| Ion blue | `#48B8FF` | Focus, navigation, links, system confidence |
| AI gold | `#F5B83D` | AI provenance only |
| Warning orange | `#FF9F3D` | Human action required |
| Critical red | `#FF5C5C` | Failure and destructive consequences |

Internal tokens use `--ion-*`; product code must avoid franchise-adjacent terminology.

### Geometry

- Chamfers identify shell landmarks, primary actions, KPI modules, and selected deal cards.
- Dense tables and forms remain rectangular for scanning efficiency.
- Hairline grid and structural seams may support hierarchy; they must never compete with content.
- The Axiom mark is an original geometric “A.” No character, robot, faction, or entertainment iconography is permitted.

### Motion

- Hover/focus confirmation: 140–180 ms.
- Panels may translate by at most 1 px.
- Refusals use an inline reason and border state rather than continuous animation.
- No background parallax, constant scanners, autoplay media, or decorative motion loops.
- `prefers-reduced-motion` collapses nonessential transitions.

## 5. Core interaction patterns

### Command center

`Ctrl/Cmd+K` opens an application command palette. In the current preview it navigates among implemented workspaces; it is not record search. Enter opens the first filtered result and Escape closes the palette.

### Pipeline movement

Desktop operators can drag opportunity cards. Keyboard and touch operators use the visible **Move** selector on every card. The server remains authoritative: optimistic movement rolls back if a stage gate refuses the transition.

### Signal center

The bell exposes a chronological server-backed feed with:

- visible unread count;
- All and Unread views;
- action, signal, or system classification;
- read/unread control;
- deep link to an implemented workspace;
- delivery reason and action-required state;
- degraded-service recovery without blocking the rest of the CRM.

Read state persists on the server and is scoped by tenant and recipient. Full record-level access rechecks, action completion, quiet hours, digests, paging, preferences, and external channels remain governed by `docs/product/18-notifications-and-alerting.md` and are not claimed as implemented.

### Operator guide

`Ctrl/Cmd+/` opens contextual help containing the current operating loop, implemented shortcuts, and the AI gold rule. The full manual remains available in `docs/manual/user-guide.md`.

## 6. Responsive behavior

| Width | Shell behavior |
|---|---|
| Above 900 px | Persistent 232 px labelled rail; full command trigger |
| 681–900 px | Off-canvas navigation; compact command icon; normal content grid |
| 441–680 px | Compact top bar; notification sheet; two-column KPI grid; wrapped actions |
| 440 px and below | Single-column KPI and action layout |

Pipeline columns retain horizontal scrolling because stage order is meaningful. Touch movement is provided by each card’s selector. Tables live in a labelled horizontal scroll container until a record-card view is implemented.

## 7. Accessibility contract

- WCAG 2.2 AA is the release target.
- All interactive elements require a visible keyboard focus state.
- Primary navigation uses text labels, not icons alone.
- Drag interactions require a keyboard/touch equivalent.
- Status cannot rely on color or `title` text alone.
- Dialogs and drawers close with Escape and declare modal semantics. Focus trapping and automated axe coverage remain required before production readiness.
- Loading, mutation success, and refusal messages must use suitable live-region semantics.
- Body copy should not fall below 12 px; uppercase micro-labels are supplementary only.
- Reduced-motion preferences are honored globally.

## 8. Content style

- Lead with the outcome: “Move refused: economic buyer required.”
- Use operational nouns users recognize: lead, account, deal, stage, owner.
- Avoid militarized language for customer data or people. “Command center” describes the workspace, not the user’s authority over individuals.
- Label mock, preview, and roadmap content in the interface.
- Dates, money, and numbers must eventually use tenant locale and currency; the current USD preview formatter is not a final internationalization strategy.

## 9. Component architecture

The current foundation uses shared shell components (`CommandRail`, `TopBar`, `CommandPalette`, `HelpDrawer`, notifications and toasts). Before the next broad feature wave, extract stable primitives for Button, IconButton, Badge, Panel, Dialog, Drawer, EmptyState, Skeleton, and DataTable. Product pages should compose these primitives rather than grow page-specific CSS indefinitely.

## 10. Release gates

Before calling this design system production-ready:

- add component and route tests;
- add axe-based accessibility checks and keyboard acceptance tests;
- validate light/dark contrast in CI;
- implement focus trapping/restoration for overlays;
- localize currency, dates, and user-facing strings;
- verify phone, tablet, 1280 px, 1440 px, and high-zoom layouts;
- extend notifications beyond self-originated/workspace-safe events only after minimum RBAC/sharing authorization exists;
- capture approved screenshots for the user manual after visual regression baselines are stable.

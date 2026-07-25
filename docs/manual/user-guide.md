# Axiom user guide

Welcome to Axiom. This guide is for the people who sell, manage and support — not for administrators or developers. It explains how to get your work done, why the product sometimes stops you (it's protecting your data, and we'll show you exactly what it wants), and how to make Axiom quieter, faster and more yours.

> **Walking-preview boundary (2026-07-25).** The runnable preview currently includes local sign-in, Revenue Command, Leads, Accounts, Pipeline, Activities, Reference Data, CPQ read workspaces, Forecast, Contracts, Campaigns, Cases, Partners, Automation, Reports, Analytics, AI Copilot foundations, Migration, Mobile/offline readiness, a workspace command palette, contextual operator help, and a tenant/user-scoped server notification feed. Later sections describe the approved target product and are clearly not evidence that SSO/MFA, cross-record search, external notification channels/preferences, write-heavy quote/contract/case workflows, external partner portals, webhook execution, vendor model calls, native app-store builds, or full administration have shipped. Delivery truth lives in [`../epic-status.md`](../epic-status.md).

If something here doesn't match what you see on screen, your administrator may have configured things differently for your organization — field names, stages and rules are all tailored per company. The behaviour, though, works the way this guide says.

---

## Contents

1. [Getting started](#getting-started)
   - [Signing in](#signing-in)
   - [A tour of your workspace](#a-tour-of-your-workspace)
   - [The navigation rail: every module explained](#the-navigation-rail-every-module-explained)
   - [Global search](#global-search)
   - [The notification bell](#the-notification-bell)
2. [Working leads](#working-leads)
   - [Where leads come from](#where-leads-come-from)
   - [What the lead score means](#what-the-lead-score-means)
   - [Working your queue](#working-your-queue)
   - [Converting a lead](#converting-a-lead)
   - [Disqualifying a lead](#disqualifying-a-lead)
3. [Your pipeline](#your-pipeline)
   - [The pipeline board](#the-pipeline-board)
   - [Why a card refuses to advance](#why-a-card-refuses-to-advance)
   - [Moving backwards and changing close dates](#moving-backwards-and-changing-close-dates)
   - [Closing a deal](#closing-a-deal)
4. [Accounts and contacts](#accounts-and-contacts)
   - [The account 360 timeline](#the-account-360-timeline)
   - [Account hierarchies](#account-hierarchies)
   - [The buying group](#the-buying-group)
5. [Activities and email](#activities-and-email)
   - [Automatic capture — how it works](#automatic-capture--how-it-works)
   - [Your privacy controls](#your-privacy-controls)
   - [Tasks, events and calls](#tasks-events-and-calls)
6. [Quotes and approvals](#quotes-and-approvals)
7. [Forecasting](#forecasting)
   - [Forecast categories](#forecast-categories)
   - [Overriding with a reason](#overriding-with-a-reason)
   - [Reading the movement waterfall](#reading-the-movement-waterfall)
8. [The AI assistant](#the-ai-assistant)
   - [What the gold marking means](#what-the-gold-marking-means)
   - [Citations — checking the assistant's work](#citations--checking-the-assistants-work)
   - [Giving feedback](#giving-feedback)
   - [If your organization has AI turned off](#if-your-organization-has-ai-turned-off)
9. [Notifications and staying in control](#notifications-and-staying-in-control)
10. [Keyboard shortcuts](#keyboard-shortcuts)
11. [Troubleshooting and FAQ](#troubleshooting-and-faq)
12. [Glossary](#glossary)

---

## Getting started

### Signing in

The current walking preview uses the local workspace, email, and password form. The target product supports two organization-selected methods:

For the runnable preview at `http://localhost:4280`, use password `axiom-demo` with one of these accounts:

| Workspace | Email | Role |
|---|---|---|
| `meridian` | `superadmin@axiomcrm.com` | Super admin, read/write across all active tenants |
| `meridian` | `superaudit@axiomcrm.com` | Super audit, read/view only across all active tenants |
| `meridian` | `raj.malhotra@meridianfab.com` | Tenant admin |
| `meridian` | `priya.nair@meridianfab.com` | Sales |
| `northstar` | `ava.chen@northstar.example` | Tenant admin |

Platform users can switch active workspace from the top bar. Tenant users stay in their own workspace.

- **Single sign-on (SSO).** Most organizations use this. Choose your company's sign-in button and you'll be taken to the same login you use for email or your intranet. No separate Axiom password to remember.
- **Username and password.** If your organization uses local sign-in, use the email address and password you were given. Forgot it? Use **Forgot password** on the sign-in screen — the reset link goes to your email.

**Multi-factor authentication (MFA).** Your organization may require a second step at sign-in — a code from an authenticator app on your phone, or a passkey (fingerprint, face, or security key). You'll set this up once, the first time you sign in. Keep your recovery codes somewhere safe: each one works exactly once, and they're your way back in if you lose your phone.

**Occasionally you'll be asked to sign in again mid-session** — usually just before something sensitive, like a large export. That's deliberate. It's called step-up authentication, and it's Axiom double-checking that it's really you before something important happens.

### A tour of your workspace

When you sign in, you land on **Home** — not a wall of records, but the things that need you today: leads waiting for a first response, deals with unmet stage requirements, approvals waiting on you, and tasks due. Axiom is built exception-first: it shows you what needs action, why, and when it's due.

Down the left side is the **navigation rail** — your map of the whole product. It is described in full in [The navigation rail: every module explained](#the-navigation-rail-every-module-explained) below.

Across the top:

- **Command center** — the magnifying glass, or press **⌘K** (Mac) / **Ctrl+K** (Windows), to jump among implemented workspaces. Cross-record search is planned but not part of this preview.
- **The notification bell** — everything that needs your attention, in one place.
- **Your avatar** — profile, preferences, theme, connected email and calendar, and sign out.

### The navigation rail: every module explained

The rail lists **every part of Axiom**, arranged the way a working day is arranged rather than in any internal order. Think of it as the floor plan of a building: even the rooms you never enter are worth seeing on the map.

Modules are gathered into eight groups. Click a group heading to fold it away if you never use it; it stays folded next time you sign in.

#### Workspace — where your day starts

| Module | In plain terms |
|---|---|
| **Home** | Your morning briefing. The handful of things that genuinely need a decision today, each with the reason it's flagged. |
| **Activities** | Every call, meeting, email and task, on one timeline. Your record of who spoke to whom, and when. |

#### Sell — the core sales motion

| Module | In plain terms |
|---|---|
| **Leads** | People who might become customers but aren't yet. You qualify them here, then convert the good ones. |
| **Pipeline** | Your live deals on a board, one column per stage. Drag a deal rightwards as it progresses. |
| **Accounts** | The companies you sell to, and the people inside them. The customer's permanent file. |
| **Forecast** | Your prediction of what will actually close this quarter, and the ability to show your working. |

#### Quote to cash — turning a deal into money

| Module | In plain terms |
|---|---|
| **Products** | Your catalogue: what you sell, and the official price of each item. |
| **Quotes & CPQ** | The priced proposal you send a customer. CPQ means the system helps you configure the right bundle, price it correctly, and route discounts for approval. |
| **Contracts** | The signed agreement, its dates, and what has to be renewed when. |

#### Engage & serve — before and after the sale

| Module | In plain terms |
|---|---|
| **Campaigns** | Marketing activity — a webinar, a mailshot — and which deals it actually produced. |
| **Cases** | Customer problems and questions after they've bought, with a clock against the response time you promised. |
| **Partners** | Resellers and referral partners who sell on your behalf, and the deals they register. |

#### Intelligence — making sense of the numbers

| Module | In plain terms |
|---|---|
| **Reports** | Build your own tables and charts from your data, and schedule them to arrive by email. |
| **AI Copilot** | The assistant. It drafts, summarises and suggests — and always shows which records it read to reach its conclusion. |

#### Vertical packs — industry-specific add-ons

| Module | In plain terms |
|---|---|
| **BFSI** | Extras for banking, financial services and insurance: client onboarding checks, product holdings, suitability records. |
| **Commodity** | Extras for physical commodity trading: counterparties, master agreements, tenders and cargo enquiries. |

#### Platform — the machinery underneath

| Module | In plain terms |
|---|---|
| **Reference Data** | The official lists everything else picks from — industries, countries, currencies, stages. Change it once, it changes everywhere. |
| **Automation** | Rules that act for you: "when a deal passes £50,000, ask the director to approve it." |
| **Integrations** | The connections to your other systems — email, calendar, accounting, phones. |
| **Migration** | Bringing your data across from Salesforce, Zoho or HubSpot, with a rehearsal run before anything is written. |
| **Mobile** | The phone and tablet experience, including working with no signal. |

#### Governance — control and proof

| Module | In plain terms |
|---|---|
| **Administration** | Who works here, what they're allowed to see, and how the system is configured. |
| **Audit & Compliance** | The permanent record of who did what, when, and on whose authority. Used when someone has to prove it. |

#### Why some modules look greyed out

Modules marked **Planned** are specified and on the roadmap, but not built yet. They appear deliberately rather than being hidden — so you can see the shape of the whole product and know that a missing feature is scheduled, not lost. They can't be clicked.

Modules marked **Beta** work, but not every part of them is finished yet. Use them; just expect some gaps.

You'll also only see what your role permits. An account executive doesn't see Administration; that's not a fault.

#### Making the rail narrower

Click the menu button in the top bar to **collapse** the rail. It shrinks to a narrow strip of icons — every module is still one click away, and the highlight still shows where you are — giving you back most of the width for your actual work. Hover any icon to see its name. Click the button again to bring the labels back.

On a phone or tablet the same button slides the full rail in over the page; tap anywhere outside it to dismiss.

### Global search

In the current preview, press **⌘K / Ctrl+K** and type to filter implemented workspaces. **Enter** opens the first match and **Esc** closes the panel.

The target cross-record search will cover permitted accounts, contacts, leads, opportunities, quotes, cases, and notes. It is not yet implemented; the command center does not search customer records.

Search understands more than names. Try an email address, a phone number, or a quote number. Recent records appear before you type anything — most of the time, the record you want is the one you touched an hour ago.

### The notification bell

The preview bell reads its feed from the server. Its badge is the unread count; All/Unread filters, safe workspace deep links, delivery reasons, action-required flags, and read/unread controls are stored per user. A failure in this feed shows a retry state without blocking CRM work.

The broader target notification service adds assignments, @mentions, approval requests, SLA warnings, AI suggestions (marked in gold), forecast reminders, preferences, digests, quiet hours, and external channels. Full record-level access is rechecked when richer record routes are introduced.

Open it and notifications are grouped by type — "4 approval requests" rather than four separate lines. Click any item to jump straight to the record it's about, at the exact spot that needs you. Items asking for an action (like an approval) stay visually flagged until you've actually done the thing, even if you've read them.

You control how loud all this is — see [Notifications and staying in control](#notifications-and-staying-in-control).

---

## Working leads

### Where leads come from

Leads arrive on their own — from your website's forms, from marketing campaigns, from imports and connected systems. Each one carries where it came from (its source and campaign), so you always have context before the first call. Axiom checks new leads against existing records as they arrive; if someone is already a contact, the lead attaches to them instead of creating a duplicate.

### What the lead score means

Every lead has a score, and — this matters — **the score always shows its reasons**. Click it. You'll see exactly which factors contributed: "Visited pricing page (+15)", "Company size matches your best customers (+20)", "No business email (−10)". If a predictive score is enabled, its top contributing factors and their direction are shown right alongside it, in plain language.

You never have to trust a number you can't unpack. If a score seems wrong, the factors tell you *why* it says what it says — and give your operations team something concrete to fix.

### Working your queue

Your lead queue is sorted by priority, not arrival time. Each lead shows its score, its source, and — if your organization measures speed-to-lead — a response timer. The timer respects business hours: a lead that arrives Friday at 5:55pm isn't "overdue" by Monday morning by more than the five business minutes that actually elapsed.

If a lead was routed to you by an assignment rule, the lead shows which rule matched — so if leads are landing in the wrong laps, there's a named rule to point at.

### Converting a lead

When a lead is qualified, press **Convert**. One step turns it into:

- an **account** (or attaches to the existing one),
- a **contact**, and
- optionally an **opportunity**, ready to work.

Everything travels: notes, activities, campaign history, and your qualification answers all move to the new records — you will never re-type something you already captured. The lead itself becomes read-only, with links to what it became, so the paper trail stays intact.

If conversion fails partway (it's rare), nothing is half-created. You get the lead back exactly as it was, with the reason.

### Disqualifying a lead

Not every lead is a fit, and saying so cleanly matters. Disqualifying requires a reason from your organization's list — "no budget", "wrong industry", "competitor" — because those reasons drive real decisions about where marketing money goes.

You can set a disqualified lead to **recycle**: pick a date, and it quietly returns to the working queue when the timing might be better.

---

## Your pipeline

### The pipeline board

The board shows your open opportunities as cards in stage columns. Drag a card to move it forward. Each card shows amount, close date, and any risk flags. The totals at the top of each column are live.

Your organization may run more than one pipeline — new business and renewals often have different stages. Each opportunity follows the stages of its own pipeline.

### Why a card refuses to advance

Sometimes you drag a card to the next stage and it springs back. **This is not a bug — the stage has entry requirements your deal hasn't met yet, and Axiom will tell you exactly which ones.**

Your organization defines what a deal needs before it can claim a stage: an identified economic buyer, a completed discovery call, a quote on the table. When you try to advance, Axiom checks the list and tells you precisely what's missing and what to do about it — "No economic buyer identified in the buying group. Add one under Buying group." Fix the gap and the card moves.

This is the same rule for everyone and every path — the board, the record page, even bulk updates and integrations. Nobody can drag a deal to "Negotiation" as wishful thinking, which is exactly why your forecast is worth reading.

One subtlety worth knowing: if the rules changed *after* your deal entered its current stage, you're held to the rules as they were when you entered it — the goalposts don't move mid-play.

### Moving backwards and changing close dates

- **Moving a deal backwards** may or may not be allowed in your pipeline. Where it's allowed, you'll be asked for a reason, and the move is recorded in the deal's stage history.
- **Pushing a close date** past the end of the current period asks for a reason too. Repeated slips are visible to your manager — not to catch you out, but because slippage is the single most honest early signal a forecast has.

### Closing a deal

Won or lost, closing asks for a reason from your organization's list, and for lost deals usually the competitor if there was one. Closed deals become read-only — the record of what happened stays exactly as it was. If something genuinely needs correcting later, there's a controlled reopen path (usually via your manager or operations).

---

## Accounts and contacts

### Lists, exports and governed master data

Accounts and Leads use server-side search, filtering and pagination. Each page returns 100 records; exports use the same active search and filter values, so Excel, Word and PDF downloads match the working list rather than a stale client-side subset.

The master toolbar provides:

- **Group** to group the current page by the main master field.
- **Audit** to open immutable master activity.
- **Export Excel**, **Export Word** and **Export PDF** for governed downloads.
- **Download template** and **Bulk upload** for roles allowed to import master data.

Bulk upload uses the downloaded CSV template and validates the full file before writing. If any row fails validation, no records from that file are imported. Deletes are soft deletes only, and records already used by related records are protected with a clear in-use message.

### Reference data

Reference Data is the administrator-facing workspace for governed value sets such as lead statuses, pipeline-stage lifecycle values, and master-delete reasons. Everyone with read access can inspect active and inactive entries. Super admins, tenant admins, and data stewards can add tenant-specific values or deactivate values that should no longer be used.

Reference values are not hard-deleted. Deactivation keeps old records readable and auditable while preventing the value from being used for new configuration.

### The account 360 timeline

Open any account and the timeline shows everything, in order: emails, meetings, calls, opportunities opened and closed, quotes sent, support cases, campaign touches. Filter by type or date to cut the noise. Before any customer conversation, two minutes on the timeline replaces twenty minutes of asking around.

You'll only see what you're permitted to see — and where items are hidden from you, the timeline won't hint at them either.

### Account hierarchies

Companies own companies. Axiom models parent/child account structures at any depth, and rolls up pipeline, revenue, open cases and activity across the family — so "how much business do we do with this group?" has one answer. If part of a hierarchy is outside your permissions, the roll-up says it's showing a restricted view rather than quietly under-counting.

### The buying group

B2B deals are bought by committees. On an opportunity, the buying group records who's involved and how: economic buyer, champion, technical evaluator, blocker — plus how engaged each one is. It's two minutes of bookkeeping that pays for itself the first time someone asks "who's actually signing this?"

Some stages require certain roles to be identified before a deal can advance — if a stage gate says "no economic buyer", this is where you fix it.

---

## Activities and email

### Automatic capture — how it works

Connect your work email and calendar (your avatar → **Connected accounts**), and Axiom logs your customer correspondence and meetings for you. An email to a known contact attaches itself to that contact, their account, and the relevant open opportunity. No BCC addresses, no "log a call" forms for things that are already in your inbox.

When Axiom isn't sure where something belongs, it asks instead of guessing — you'll see a one-click prompt to confirm the match. Every captured item shows how it was matched and with what confidence.

### Your privacy controls

Automatic capture only ever happens with **your consent**, and you stay in control:

- **Nothing is captured until you opt in.** Connecting your mailbox is a choice, not a default.
- **Exclude domains.** Add personal or sensitive domains (your doctor, your bank, your family) under **Preferences → Email capture → Excluded domains**. Mail to or from excluded domains is **never stored** — not hidden, never stored. Your administrator maintains an organization-wide exclusion list too.
- **Private by pattern.** Anything that doesn't match a known business contact stays out of the shared record.
- **Withdraw any time.** Turn capture off and it stops immediately; previously captured private items are purged according to your organization's policy.

### Tasks, events and calls

The classics still work the way you'd expect:

- **Tasks** have due dates, reminders and priorities. Overdue tasks surface on Home.
- **Events** are meetings — synced with your calendar, with attendees from your contacts, and a prompt for outcomes afterwards.
- **Calls** log direction, duration and a disposition ("connected", "left voicemail") from your organization's list. If your phone system is integrated, calls log themselves and inbound calls pop the matching record before you pick up.

Everything lands on the same timeline, with useful derived facts on every record: last contacted, days since last activity.

---

## Quotes and approvals

Build a quote straight from an opportunity — account, contact and products carry over. Add lines, apply discounts, and generate a branded document when it's ready.

Two things about quotes that protect you:

- **Versions.** Change a quote that's already been sent and Axiom creates a new version, keeping the old one intact. You can compare versions line by line. An out-of-date version can't be accepted by mistake.
- **Discount approvals.** If your discount crosses a threshold, the quote can't go out until it's approved — and Axiom tells you **who** needs to approve it and where it currently sits, rather than leaving it in limbo. When approval lands, the decision and approver are recorded on the quote. You'll be notified the moment it's decided.

A note on approvals generally, since they appear throughout Axiom (quotes, but also process steps and administrative changes): the person who asks can never be the person who approves — even through delegation. If you're an approver, requests reach you through the bell and email, and each one shows exactly what you're approving with its full context, one click away.

---

## Forecasting

### Forecast categories

Every open deal maps to a forecast category based on its stage:

| Category | Meaning |
|---|---|
| **Pipeline** | Real, but early. Not counted on. |
| **Best case** | Could land this period if things go well. |
| **Commit** | You are prepared to be held to this number. |
| **Closed** | Done. In the bank. |

Your forecast rolls up automatically from your deals; your manager's rolls up from their team's, and so on up. You can recategorize an individual deal away from its stage default — with a reason.

### Overriding with a reason

Managers can submit a number different from the arithmetic roll-up — that's judgment, and it's part of forecasting. But an override always requires a reason, and everyone above sees **both** numbers: the roll-up, the override, and the gap between them. Judgment is welcome; invisible judgment is not.

When you **submit** a forecast, that submission is frozen — a snapshot of the number *and* the deals behind it at that moment. Next quarter, "what did we say, and what did we know at the time?" has an exact answer.

### Reading the movement waterfall

The waterfall answers the question every Monday meeting asks: **"why is the number different from last week?"** It breaks the change into parts:

- **New** — pipeline created since the last snapshot
- **Advanced / Slipped** — deals that moved forward, or pushed out of the period
- **Pulled in** — deals that moved into this period
- **Increased / Decreased** — deals whose value changed
- **Won / Lost** — deals that closed

The parts always add up exactly to the total change — no mystery residue. Click any bar to see the specific deals inside it. If your forecast moved $400K, you can name every deal that moved it.

---

## The AI assistant

The assistant summarizes accounts before your calls, suggests your next best action, drafts emails and call prep, and answers plain-language questions about your data ("which of my deals have gone quiet this month?").

### What the gold marking means

**Anything gold came from AI.** Gold text, gold borders, gold icons — one consistent rule across the whole product. A gold suggestion in your queue, a gold draft in the composer, a gold prediction next to your forecast: gold means a machine produced it and a human (you) decides what happens next.

The assistant never acts on its own. It never sends an email, changes a deal, applies a discount or deletes anything without your explicit say-so. Where it can carry out multi-step work for you, it shows you the complete plan first and asks — and everything it does is recorded as AI-assisted, initiated by you.

### Citations — checking the assistant's work

Every factual claim the assistant makes carries a citation — a link to the record it came from. "Renewal risk: champion left the company ↗" links to the note that says so. Click through and check whenever it matters; that's what citations are for. If the assistant can't ground a claim in a record you can see, it says so or leaves it out — it doesn't fill gaps with plausible-sounding guesses.

And it only ever reads what *you* can read. The assistant sees your data with your permissions — ask it about a deal you can't access and it simply doesn't know that deal exists. Two people asking the same question get answers built from their own permitted view.

### Giving feedback

Every suggestion and draft has thumbs up / thumbs down. Use them, especially the down — a rejected recommendation with a click of "why" (not relevant / wrong facts / bad timing) directly improves what you're shown next, and tells the team which AI features are earning their place.

### If your organization has AI turned off

Some organizations run Axiom with AI disabled entirely — a deliberate choice, common in regulated industries. If that's you, there's nothing to configure and nothing to miss-click: AI surfaces are simply absent. No greyed-out buttons, no upsell banners. Everything else in this guide works identically.

---

## Notifications and staying in control

Axiom's notification philosophy is simple: **interrupt you only when something needs your action; batch the rest.** Approval requests and SLA breaches reach you immediately. Deal risk observations and AI suggestions arrive in a daily digest, not a drip of pings.

You tune it under **Preferences → Notifications**:

- **Per type, per channel.** A grid: rows are notification types (assignments, mentions, approvals, SLA, risk signals, AI suggestions, forecast reminders), columns are channels (in-app, email, push). Set each cell how you like it. In-app is always on — the bell is the one place guaranteed to have everything.
- **Email: real-time, digest or off** per type. The digest arrives once a day, at a time you pick, and only when there's something in it.
- **Quiet hours.** Set your evenings and weekends. Push and email wait until morning; the bell quietly accrues. Genuine emergencies (like an SLA breach, if you're on an escalation path) can be configured to break through — your administrator controls which types may do that.
- Some settings may be locked by your organization (you can't switch off approval requests entirely, for instance). Locked cells say so, and why.

Repeated news about the same record collapses into one line with a count. You will never get twelve pings about the same deal in one afternoon — if you do, report it, because that's a defect by design.

---

## Keyboard shortcuts

Axiom is fully usable without a mouse. The essentials:

| Shortcut (Mac / Windows) | Action |
|---|---|
| **⌘K** / **Ctrl+K** | Global search — from anywhere |
| **⌘/** / **Ctrl+/** | Show all keyboard shortcuts |
| **G** then **H** | Go to Home |
| **G** then **L** | Go to Leads |
| **G** then **P** | Go to Pipeline |
| **G** then **A** | Go to Accounts |
| **G** then **F** | Go to Forecasts |
| **N** | New record (in the current workspace) |
| **E** | Edit the open record |
| **⌘Enter** / **Ctrl+Enter** | Save |
| **Esc** | Cancel / close panel |
| **⌘⇧N** / **Ctrl+Shift+N** | Open the notification bell |
| **A** | Open the AI assistant on the current record |
| **T** | New task on the current record |
| **?** on the pipeline board | Explain why the selected card can't advance |
| **J / K** | Next / previous item in any list |

---

## Troubleshooting and FAQ

**Q: I can't see a record my colleague can see. Is something broken?**
A: Almost certainly not — Axiom shows each person exactly what their permissions allow, and permissions differ by role, team and territory. If you believe you genuinely need access, ask your administrator; they have a tool that explains precisely why you can or can't see any record, and what would change it.

**Q: A field my teammate mentions just isn't on my screen. Where is it?**
A: Same answer, finer grain: fields can be restricted separately from records. If a field is hidden from your role, it isn't blanked out — it's absent entirely. Your administrator can tell you whether that's intentional.

**Q: I dragged a deal to the next stage and it bounced back.**
A: The stage has entry requirements your deal hasn't met. The message lists exactly what's missing and what to do — complete those items and it will move. See [Why a card refuses to advance](#why-a-card-refuses-to-advance).

**Q: I got an error saying someone else changed the record while I was editing.**
A: Two people edited at once, and Axiom refused to silently overwrite either of you. The message shows which fields the other person changed and who they are — review, merge your changes, and save again. Nothing you typed is thrown away without you seeing the conflict.

**Q: Why was I asked to sign in again in the middle of the day?**
A: Either your session hit your organization's idle timeout, or you attempted a sensitive action (like a large export) that requires fresh authentication. Both are policy, not glitches.

**Q: My export button is greyed out but I can see the records fine.**
A: Reading and exporting are separate permissions. Some organizations restrict export tightly for compliance reasons. Large exports may also require an approval first. Your administrator can grant export rights if your role warrants it.

**Q: An email I sent a customer isn't showing on the account timeline.**
A: Check three things: (1) your mailbox is still connected (avatar → Connected accounts — connections occasionally need renewing after a password change); (2) the recipient's domain isn't on your excluded-domains list; (3) the contact exists in Axiom — mail to unknown addresses waits in your review queue for a one-click match rather than guessing.

**Q: How do I stop Axiom capturing emails from a specific address or domain?**
A: Preferences → Email capture → Excluded domains. Anything matching is never stored — retroactively too, if you add an exclusion later, per your organization's policy.

**Q: The lead score seems wrong for an obviously great lead.**
A: Click the score — every contributing factor is listed with its weight. Usually one look explains it (a missing field, a mistyped company size). If the factors themselves seem wrong, tell your operations team; the scoring rules are theirs to tune, and the factor display gives them exactly what to fix.

**Q: What's the difference between "Commit" and "Best case"?**
A: Commit means "hold me to this number." Best case means "possible this period, not promised." Your stage defaults map deals automatically, but the honest answer is a judgment — recategorize (with a reason) when the default is wrong.

**Q: I submitted my forecast and then a deal closed. Does my submission update?**
A: No — and that's the point. A submission is a frozen snapshot of what you said and when. The live view updates continuously; the next submission captures the new reality. That's how forecast accuracy stays measurable.

**Q: Can the AI assistant see things I can't?**
A: No, categorically. It reads with *your* permissions, through the same security checks as your own screen. It can't summarize, cite or even count records you're not allowed to see.

**Q: Can I trust what the AI assistant tells me?**
A: Trust it the way you'd trust a well-prepared colleague: useful, fast, and checkable. Every claim links to its source record — click through when the stakes are high. If it can't back a statement with a record, it says so rather than improvising.

**Q: A notification mentions an approval, but when I click it says I don't have access.**
A: Access changed between the notification being sent and your click — for example, the request was recalled or your role changed. The stale item disappears from your bell shortly. If it recurs, contact your administrator.

**Q: I'm getting too many notifications.**
A: Preferences → Notifications. Move noisy types to the daily digest, set quiet hours, and switch off push for anything that isn't action-required. Axiom's defaults already digest low-urgency types; if something feels like spam, re-tiering it takes one click. See [Notifications and staying in control](#notifications-and-staying-in-control).

**Q: I deleted a record by mistake. Is it gone?**
A: Deleted records go to the recycle bin, where they can be restored for a retention period set by your organization. Ask your administrator if you can't restore it yourself.

**Q: Why does closing (or disqualifying) always demand a reason from a list?**
A: Because "other" teaches nobody anything. Governed reasons make win/loss and lead-quality reporting real. If the list is missing the reason you actually have, that's worth telling your operations team — the list is configurable.

**Q: Does Axiom work on my phone or tablet?**
A: Yes — the full product works in a tablet or phone browser, and native mobile apps (with push notifications and quick capture) are available depending on your organization's rollout. Approvals, search, records and activity capture all work on the go.

---

## Glossary

| Term | What it means for you |
|---|---|
| **Account** | A company you do business with (or want to). |
| **Contact** | A person at an account. |
| **Lead** | A person or company that has shown interest but isn't qualified yet. Converts into an account, contact and (usually) an opportunity. |
| **Opportunity** | A potential deal, with an amount, a close date and a stage. Lives in a pipeline. |
| **Pipeline** | The sequence of stages a deal moves through. Your organization may have several. |
| **Stage gate / exit criteria** | The requirements a deal must meet before it can advance to a stage. The reason a card bounces back. |
| **Buying group** | The people at an account who influence a deal — economic buyer, champion, evaluator, blocker. |
| **Forecast category** | Pipeline, Best case, Commit or Closed — how sure you are, in one word. |
| **Roll-up** | A number computed automatically from the level below — your team's forecast is the roll-up of its members'. |
| **Override** | A submitted number that differs from the roll-up. Always visible alongside it, always with a reason. |
| **Snapshot** | A frozen record of a forecast or pipeline at a moment in time. Snapshots make "what changed?" answerable exactly. |
| **Waterfall** | The chart that decomposes forecast change into new, advanced, slipped, won, lost and friends — adding up exactly. |
| **Quote version** | A preserved copy of a quote at each material change. Old versions are kept, comparable, and unacceptable (literally). |
| **Entitlement** | What a customer's contract promises them in support terms — drives case SLAs. |
| **SLA** | A time promise — first response, resolution — measured in business hours. |
| **Cadence** | A scheduled multi-step outreach sequence (emails, calls, tasks). |
| **Gold marking** | The universal visual signal that content was produced by AI. Gold = machine proposed, you decide. |
| **Citation** | The link from an AI claim to the record it came from. |
| **Step-up authentication** | Being asked to re-authenticate before a sensitive action. Deliberate. |
| **Maker-checker** | The rule that whoever requests something can't be the one who approves it. |
| **Recycle bin** | Where deleted records wait, restorable, until the retention period ends. |
| **Digest** | One batched notification email instead of many — Axiom's default for anything that isn't action-required. |
| **Quiet hours** | Your do-not-disturb window. Notifications wait; the bell accrues. |
| **Tenant** | Your organization's own isolated instance of Axiom. Nobody outside it can ever see in. |

---

*This guide combines walking-preview instructions with the approved target-product manual. The preview boundary at the top and [`../epic-status.md`](../epic-status.md) are authoritative for shipped behavior. Press **⌘/** or **Ctrl+/** for the in-product User Manual.*

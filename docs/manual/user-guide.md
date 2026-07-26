# Axiom user guide

Welcome to Axiom. This guide is for the people who sell, manage and support — not for administrators or developers. It explains how to get your work done, why the product sometimes stops you (it's protecting your data, and we'll show you exactly what it wants), and how to make Axiom quieter, faster and more yours.

> **Walking-preview boundary (2026-07-25).** The runnable preview currently includes local sign-in, Revenue Command, Leads, Accounts, Pipeline, Activities, Reference Data, CPQ read workspaces, Forecast, Contracts, Campaigns, Cases, Partners, Automation, Reports, Analytics, AI Copilot foundations, Integrations, Migration, Sandbox & Release, Audit & Compliance, BFSI, Commodity, Mobile/offline readiness, a workspace command palette, contextual operator help, and a tenant/user-scoped server notification feed. Later sections describe the approved target product and are clearly not evidence that SSO/MFA, cross-record search, external notification channels/preferences, write-heavy quote/contract/case workflows, external partner portals, live webhook execution, vendor model calls, native app-store builds, CTRM adapter execution, or full administration have shipped. Delivery truth lives in [`../epic-status.md`](../epic-status.md).

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
13. [Connectors and outbound messages (for administrators)](#connectors-and-outbound-messages-for-administrators)
    - [What a connector is](#what-a-connector-is)
    - [When a connector says "paused"](#when-a-connector-says-paused)
    - [The undelivered list, and how to retry](#the-undelivered-list-and-how-to-retry)
    - [Why you can't see a saved password](#why-you-cant-see-a-saved-password)

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

The Automation workspace includes a **Workflow gate console**. It shows the latest gate checks already evaluated by Pipeline or automation APIs: the record, gate status, missing prerequisite count and the next step in plain language. The console uses the same governed Data Grid controls as the rest of Axiom: column filters, spread-out grouping options, Excel/Word/PDF export, Copy view, Audit and Full size/Restore. Use **Review** to open the full gate drawer and see every missing field or condition.

The Contracts, Forecast, Campaigns, Cases and Partners registers also show **Check gates** beside a pending business action. Select it before acting to preview the exact next transition. Axiom tells you whether the record is ready, what is missing and what to fill in. The real action checks the same gate again on the server; direct API, import, automation and database writers cannot skip the process because the lifecycle is also enforced in PostgreSQL.

The same **Check gates** control now covers Automation simulations, Analytics refreshes, Copilot recommendation acceptance, Integration contract verification and Migration validation. Migration batches visibly pass through **Validating** before success or failure, and repeated checks retain their observations for operational review.

Sandbox refresh, Audit evidence export, Mobile sync acknowledgement, BFSI clearance and Commodity offer now use **Check gates** too. The review explains missing record fields in everyday language, while the action also verifies related evidence such as compliance screenings, counterparty credit and approved term sheets. This means a green gate is never permission to bypass the final business-control check.

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

Accounts and Leads use server-side search, filtering and pagination. Each page returns 100 records. You can then narrow the visible page further with **Column search** fields under the grid toolbar — for example, search only Owner, Status, Industry or Company without changing the main page search.

Exports follow the view you are actually working with. If you apply column searches or grouping, Excel, Word and PDF downloads use the same visible rows instead of a stale unfiltered subset. Current-view downloads also include a small header with the grid name, generated time, row count, active groups and active column filters, so someone opening the file later can understand what view produced it. Axiom records export evidence before the file is saved, including the object/table type, format, row count and filter/group context.

Use **Copy view** when you do not need a file but want to share what you are seeing. It copies the grid name, generated time, row count, groups and column filters to your clipboard, which is useful for support tickets, audit notes or a quick chat with an administrator.

Administration and security tables use the same idea. Their **Copy view** output also includes the current sort direction and collapsed group count, so a role-review or user-activity question can be reproduced without asking for a screenshot. Those tables also provide **Export Excel**, **Export Word** and **Export PDF** from the currently visible table view, including the same view context header used by the rest of the product. Each table now carries its own sticky table workspace header with a plain-language help tag, visible export scope, **Audit** access and **Full size / Restore view** controls for large permission or activity reviews.

Axiom remembers your grid layout choices in your browser. If you group a grid by Owner, add a Status column search, or tune a security table's column filters, those preferences come back when you return to that screen. Use **Clear** or **Reset view** on the grid when you want to start fresh. If several screens feel confusing after experimentation, open your avatar menu and choose **Reset grid views** to clear saved grouping and column-search preferences across this browser.

When column filters are active, Axiom shows them as small chips under the filter row. Each chip names the column and the value being searched. Click the **×** on a chip to remove just that one filter without losing the rest of your view.

The master toolbar provides:

- **Group** to choose one or more columns as spread-out checkbox chips. Tick the columns you want; untick them to restore a flat list.
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

## Reports and Analytics Studio

Open **Reports** and choose one of two workspaces:

- **Reports Studio** is the governed Jasper grid. Filter or search any column, sort or group the portfolio, choose a report, read its business question and audience, then review the complete browser-safe report inside Axiom before downloading PDF, Excel or Word.
- **Custom Reports** is the no-code authoring workspace for report building, dashboards, calculated measures, conditional formatting, sharing and delivery policies.

The separation is intentional: choosing and reading an approved operating report is a different job from designing a new analytical definition. The two workspaces share the same tenant security, export rights and audit trail.

### Use a standard CRM report

The catalogue is arranged into Executive, Sales, Growth, Customer, Commercial and Governance collections. Each grid row tells you the plain-language decision the report supports, who normally uses it, available formats and Jasper readiness. The twenty-one-report portfolio includes revenue summary, pipeline, forecast, quota, stage velocity, ARR movement, whitespace, Customer 360, discount governance, demand, activity, health, service, quote, campaign and data-quality analysis.

1. Open **Reports Studio** and use the column filters, sorting or **Group** choices to find a report by collection, title, business question, recommended role, format or status.
2. Choose **View Report** in the grid. Axiom renders every row from the same governed query supplied to Jasper in a browser-safe document viewer; choose **Full View** when you need the largest reading area.
3. Review **Decision Supported** to confirm it answers the question you have.
4. Choose **Download PDF** for a presentation-ready document, **Download Excel** for further analysis or **Download Word** for a document you can annotate.
5. Choose **Schedule Report** only when the same governed report is needed repeatedly. A schedule does not give the recipient more access than they already have.

Every generated document names the tenant, collection, audience, business question and generation time. An empty report says there is no matching data; it never substitutes unrelated rows.

### Build a report

1. Choose **Report builder**, then select a dataset such as Opportunities, Accounts, Leads or Activities.
2. Drag fields into **Detail columns**, **Row groups**, **Measures** or **Pivot columns**. On touch devices or with a keyboard, click a field to add it and use the × on a field chip to remove it.
3. Choose **Tabular**, **Summary** or **Pivot**. Add a filter before grouping if you only need part of the dataset.
4. For an Accounts or Opportunities report, use **Cross-module relationship** to ask a question such as “accounts without activity in the last 90 days.”
5. Choose **Run preview**. The preview tells you when the projected data was refreshed, whether access narrowed the result and whether the row limit was reached.
6. Choose **Drill into records** to see the contributing records. Axiom checks your access again at that moment; a report is never a shortcut around record security.
7. Give the report a name and code, then choose **Save report**.

Calculated measures use normal arithmetic over output field names—for example, `amount * probability`. They are formulas, not SQL. Conditional formatting lets you colour a result when it is above, below or equal to a comparison value.

### Design a dashboard

Choose **Dashboards**. Create a dashboard, select its audience and layout, then add saved reports as KPI, bar, line, area, donut, funnel, table, pivot or summary widgets. Width and height use a twelve-column canvas, so the same dashboard rearranges cleanly on narrower screens.

### Share, discuss, embed and deliver

Choose **Share & deliver** to:

- share a report with a user, role or the tenant while preserving each viewer's own access;
- attach review comments to the governed report definition;
- schedule PDF, Excel, Word or link delivery;
- alert recipients only when a governed KPI crosses a threshold; or
- create an authenticated embedded view restricted to exact allowed web origins.

The screen states `PENDING_ADAPTER` until an external mail provider is configured. This means Axiom has accepted and governed the delivery policy, but does not falsely claim that an outside email was sent.

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

## Connectors and outbound messages (for administrators)

This section is for whoever administers your workspace. Everyone else can skip it. You'll find these
screens at **Integrations → Dispatch** (`/integrations/dispatch`).

### What a connector is

A connector is Axiom's link to another system — your accounting package, an e-signature service, a
marketing tool, or just a web address one of your own systems listens on.

Nothing goes out of a connector automatically. You tell it *which* changes to send by adding one or
more **subscriptions**, each naming a kind of event: `lead.converted`, `opportunity.*` for everything
that happens to an opportunity, or `*` for all of it. From that point on, whenever someone in Axiom
makes that kind of change, the connector receives a message about it.

Two things worth knowing:

- **Sending happens in the background, a moment after the change.** A slow or broken external system
  can never slow down or block someone saving a record in Axiom. That is deliberate.
- **A subscription only applies from the moment you add it.** Axiom does not send the history of
  everything that happened before, because arriving one morning to ten thousand messages about last
  year is nobody's idea of a working integration.

### When a connector says "paused"

There are two different kinds of paused, and the screen tells you which:

- **Paused by you** — you pressed Pause. Nothing new is queued for it until you press Resume.
- **Paused after repeated failures** — Axiom did this by itself. When a connector fails several times
  in a row, Axiom stops hammering it and puts the messages in a queue instead. The connector shows
  **PAUSED** and its breaker shows **OPEN**.

The second kind is a safety measure, not a punishment. A system that has just come back up does not
need every message you have been holding thrown at it at once. After a short wait Axiom quietly sends
one test message (the connector shows **PROBING**). If that one works, the connector goes back to
normal and everything queued behind it goes out. If it fails, Axiom waits again.

You do not have to sit and watch for this. When a connector is paused this way, Axiom sends a
notification to every administrator in the workspace. **An integration that fails quietly is treated
as a fault in Axiom, not as normal.** One connector being paused never affects any other connector —
each one is judged on its own.

### The undelivered list, and how to retry

When a message has been retried the maximum number of times and still cannot be delivered, Axiom stops
trying and moves it to the **Dead letters** tab. Think of it as the "undelivered post" tray.

Nothing in this tray has been thrown away. Each entry keeps the whole message exactly as it was going
to be sent, along with the reason it failed — a rejection from the far end, a wrong address, a system
that never answered. Click the event name to see the message itself.

The normal sequence is: fix the receiving system (or fix the connector's settings), then press
**Retry** on the affected entries, or **Retry all** to send the whole tray again. If the queue grows
past a handful of messages, administrators get a notification about that too.

Pressing Retry twice on the same message is harmless. Axiom recognises a message it has already
queued and will not send a duplicate — every message carries an identity the receiving system can
also use to spot a repeat.

### Why you can't see a saved password

Passwords, keys and tokens live under the **Credentials** tab, and once you save one, Axiom will never
show it to you again. The screen shows the name you gave it, what it is for, when it was last changed
and when it was last used — and eight asterisks where the value would be.

This is not an oversight. A value that can be displayed can be read over a shoulder, copied into a
support ticket or pulled out of a screenshot. Storing it so that not even Axiom's own screens can
retrieve it is the whole point, and it is what lets us say plainly that a saved credential cannot leak
through the product.

Practically, this means two things:

- **Keep your own copy** wherever your organization normally keeps such things, at the moment you save
  it. There is no "show me again" later.
- **If you lose it or suspect it has been exposed, replace it rather than looking it up.** Press
  **Replace value**, paste the new one, and every connector that refers to that credential by name
  starts using it immediately — you do not have to edit each connector.

Connectors never contain a password themselves. They refer to a credential *by its name*, which is why
rotating one is a single action rather than a hunt.

## Core administration and revenue controls

### Certificate expiry alerts

Open **Access governance → Identity providers**. Axiom checks enabled SAML signing certificates every day and notifies tenant administrators 30 days before expiry. Use **Check certificate expiry** after replacing a certificate. Running the check repeatedly is safe; the same certificate warning is not duplicated.

### Access reviews

Open **Authorization → Access reviews**. Enter a code, name and future deadline, then choose **Open review**. Axiom snapshots current roles, permission bundles, manual shares and delegated administration. Use **Confirm** when access is still needed or **Revoke** to remove it immediately. You cannot decide your own access; another tenant administrator must review it.

### Historical reference labels

Open **Reference Data**, choose a value set, then select a stored code and the business date from the **Resolve as of date** controls. An inactive value still appears on historical records and reports, but Axiom clearly states that it is unavailable for new records.

### Account health and hierarchy totals

Open **Accounts → View 360**. **Recompute health** refreshes the weighted score. Every factor shows what Axiom observed, how much the factor weighs, whether it helps or hurts, and the recommended action. The roll-up compares the selected account with the visible hierarchy. If record permissions exclude part of the hierarchy, the drawer says the result is restricted without revealing hidden counts.

### Capturing leads

Open **Leads → Capture leads**. The single form validates, checks duplicates, scores, routes and starts the response SLA in one operation. For a batch, paste CSV using the header shown in the panel and process up to 1,000 rows. Valid rows commit even when other rows fail; the result lists every rejected row and its correction message.

## Revenue execution controls

### Moving a deal safely

Open **Pipeline** and choose another stage from a deal card, or drag the card. Axiom checks the current stage's pinned exit rules and the new stage's entry rules before moving anything. If something is missing, the message tells you what Axiom observed and the next corrective action. Moving backward or skipping stages asks for a business reason.

### Versioned email templates

Open **Activities → Manage templates**. Create a reusable subject and body, choose who may see it, and list merge-field names separated by commas. **Create new version** asks for the revised subject, body and a change note. It does not overwrite an earlier version, so previously sent content remains provable.

### Revising a quote

Open **Quotes & CPQ** and choose **New revision** on the current quote. Explain what changed. Axiom creates the next draft with the same products, bundles, prices and pricing-adjustment evidence, then marks the former version as superseded. Ordered quotes cannot be revised from this screen.

### Preparing a contract renewal

Open **Contracts** and choose **Prepare renewal** for an active, expiring or expired contract. Add the renewal rationale. Axiom creates one draft beginning the day after the source ends and copies eligible subscriptions. Repeating the action returns the existing renewal rather than creating a duplicate.

### Comparing a forecast scenario

Open **Forecast** and choose **Scenario**. Name the scenario and enter the amount adjustment, confidence and assumed resolved risks. The right-side comparison explains every factor and the risk-adjusted outcome. Saving a scenario never changes the submitted forecast.

## Customer operations controls

### Capturing campaign performance

Open **Campaigns** and choose **Capture performance**. Axiom freezes the current member, response, MQL, SQL, budget and influenced-pipeline totals as evidence. ROI uses net influenced pipeline against budget. A zero-budget campaign is shown as “not available” rather than an invented percentage. Capturing again creates a new snapshot; it never edits the earlier one.

### Checking a case SLA

Open **Cases** and choose **Check SLA**. Any open milestone whose due time has passed is marked missed and receives one escalation record. Running the check again is safe and does not duplicate the escalation. Resolved and closed cases are never reopened by this control.

### Registering a partner deal

Open **Partners** and choose **Register deal**, then paste the UUID of an open opportunity. Axiom checks for another active protected partner registration for the same customer. A clear registration is approved with a protection window; an overlap stays submitted and is held for review with conflict evidence.

### Restoring an automation rule

Open **Automation** and choose **Restore version**. Axiom selects the latest prior version and asks for confirmation. Restore copies that definition forward as a new active version, so the audit history remains linear. **Simulate** continues to be side-effect free and uses the same canonical rule definition shown in the workspace.

### Scheduling a report

Open **Reports** and choose **Schedule** on a report card. Enter the recipient and a daily, weekly or monthly frequency. **Run due schedules** generates the governed Jasper attachment and advances the next run time. The current first-party boundary generates and records the attachment; external email transport remains pending until its approved adapter is connected.

---

*This guide combines walking-preview instructions with the approved target-product manual. The preview boundary at the top and [`../epic-status.md`](../epic-status.md) are authoritative for shipped behavior. Press **⌘/** or **Ctrl+/** for the in-product User Manual.*

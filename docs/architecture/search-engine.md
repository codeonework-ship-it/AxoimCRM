# Search indexing engine

**Component:** `com.axiom.search` · **Migration:** `V240__search_index_engine.sql` · **Epic:** E19 (`FR-ADM-004`)
**Status:** Implemented on PostgreSQL full-text · **Date:** 2026-07-26

This document exists because the interesting part of a search engine is not the search. It is what
happens between "the index matched twelve rows" and "the user sees ten of them", and why that gap is
not a bug.

---

## 1. What this implements, and what it refuses to implement

The controlling requirement is [system design §8.2](system-design.md#82-search), quoted here in full
because every design decision below is downstream of it:

> Access can change faster than an index can be rebuilt, so the index is **not** treated as
> authoritative for authorization. The approach: index `tenant_id`, owner and sharing keys; filter on
> them at query time; then **re-check the returned page against the authoritative store before
> display**. This costs a little latency on the result page and is the only version that is correct.
> Indexing a materialized ACL and trusting it would be faster and would eventually show someone a
> record they had just been removed from.

Two supporting decisions constrain the shape:

- [ADR-003](adr/ADR-003-event-backbone.md) — the indexer is an outbox consumer. Delivery is
  **at-least-once**, so the indexer must be **idempotent**; ordering is guaranteed per record on
  partition key `(tenant_id, entity_id)`, not globally.
- [ADR-005](adr/ADR-005-technology-selection-deferred.md) — the search engine is an **open**
  component. The standing decision is *"Start with PostgreSQL full-text; escalate on measured need"*,
  and anything chosen must be **self-hostable**, because sovereign deployment means the customer runs
  the entire stack. PostgreSQL FTS satisfies both without adding a second piece of infrastructure to a
  sovereign estate.

---

## 2. The pipeline

```
crm.account / crm.contact / crm.lead / sales.opportunity
        │  (business write, in its own transaction)
        ▼
integration.outbox_event          ← written by the owning module, ADR-003
        │
        │  SearchIndexer polls forward from a per-tenant cursor (created_at, id)
        ▼
SearchProjector                   ← re-reads the CURRENT row from the authoritative table
        │
        ▼
search.search_document            ← upsert on (tenant_id, entity_type, entity_id)
        │
        ▼
SearchIndex.query()  →  authoritative re-check  →  field-level security  →  hits
```

### 2.1 Why the indexer reads the outbox rather than the Kafka topic

ADR-003 names the **outbox**, not the broker, as the source of truth, explicitly so that a consumer can
be fed this way. Reading the outbox table directly means the index stays correct in a sovereign install
that has not deployed a broker at all — which matters, because ADR-005 has not ratified the broker
choice. A `@KafkaListener` variant is a drop-in later: the work the consumer does
(`SearchIndexer.apply`) is keyed on record ids and knows nothing about delivery mechanics.

### 2.2 Why the projection re-reads the row instead of trusting the payload

An event is used for exactly one piece of information: **which record changed**. The document is then
projected from the authoritative row as it stands *now*. Three properties fall out of that single
choice, and each of them is an ADR-003 obligation:

| Situation | What happens | Why it is safe |
|---|---|---|
| **Duplicate delivery** | The same row is re-read, the same document is produced, the unique constraint turns the write into an update of an identical row | One row, every time. This is what makes the consumer idempotent |
| **Out-of-order delivery** | The upsert carries the source record's `updated_at` and refuses anything older than the stored value | The index cannot move backwards. ADR-003 guarantees ordering *per record*, but a redelivery after a relay restart can legitimately arrive behind a newer write |
| **Deletion** | The projection yields nothing, so the document is removed | A hard delete and a soft delete behave identically, and an event type the indexer has never heard of still leaves a correct index |

The idempotency guarantee is asserted under deliberate redelivery in `SearchIndexSqlIT` — ADR-003 is
explicit that "idempotency that has never been tested under duplicate delivery is a hope, not a
property".

---

## 3. The weighting scheme

`search_document.document` is a stored, generated `tsvector`:

| Weight | Column | Contents | Rationale |
|:--:|---|---|---|
| **A** | `title` | The record's own name: account name, person name, opportunity name | The thing a user is almost always looking for |
| **B** | `subtitle` | One supporting securable field: industry, job title, company, account name | Disambiguates two records with similar names |
| **C** | `body` | Descriptive columns that are **not** in the field-security registry — legal name, account number, website, territory, segment, next step, stage | Genuinely useful, and readable by anyone who may read the record at all |
| **D** | `secured_terms` | Values of securable fields that are worth searching — contact and lead email, lead status | Searchable, but individually withholdable at query time (§5) |

Ranking uses `ts_rank_cd`, which honours those weights *and* rewards term proximity. The practical
effect is that a search for "Meridian" puts the company **named** Meridian above the supplier whose
description merely mentions it. Without `setweight` both would score identically and the ordering of a
global search box would be arbitrary — which is precisely what `FR-ADM-004`'s "relevance ranking"
forbids. `SearchIndexSqlIT.titleMatchesOutrankBodyMatches` pins this.

Snippets come from `ts_headline` over the caller-readable text only (§5), with `[[` / `]]` markers
rather than HTML — so a record whose name contains markup cannot become markup in a browser. The UI
splits on the markers and renders elements; nothing uses `dangerouslySetInnerHTML`.

One thing deliberately **not** indexed: `taxId`. Nobody looks a company up by its tax identifier in a
global search box, so indexing it would be all liability and no search value. Leaving it out of the
index entirely is a stronger guarantee than filtering it on the way out.

---

## 4. Why the index is not authoritative for authorization, and what the re-check costs

### 4.1 The invariant

> **The index filter must never be narrower than true access.**

A filter that is *too wide* costs a dropped row on the result page. A filter that is *too narrow*
silently loses a record the user was entitled to find, and nothing downstream can recover it — the
recheck can only remove, never restore. Every future change to `sharing_keys` has to preserve this
direction.

### 4.2 What the index actually holds

`sharing_keys` is a `uuid[]` containing, for each document:

- the owner,
- the owner's role node **and every ancestor of it** (so the role-hierarchy roll-up narrows to a
  manager's branch rather than degenerating to a full scan),
- grantees of active, unrevoked, unexpired manual shares,
- record team members,
- record territories.

A contact has no owner column of its own; its visibility follows its account
(`SecurableObject.CONTACT.parent()`), so a contact document carries the **account's** owner and keys,
plus its own.

### 4.3 What the index deliberately cannot hold

**Criteria-based sharing rules.** A criteria rule is a predicate over a live field value —
`industry = 'Steel'`, `territory = 'APAC'`. Evaluating it at index time and storing the result is
exactly the materialized ACL §8.2 rejects: it goes stale the instant the criteria field changes, and it
goes stale *permissively*. So when a tenant has any active sharing rule on an object, the index filter
for that entity type is **widened to no owner/sharing restriction at all**, and the authoritative
re-check does the narrowing. Wider, never narrower.

The same widening applies when the caller has `view_all` on the object, or when the org-wide default is
permissive.

### 4.4 The re-check

For each entity type present on the page, one query against the authoritative table using
`AuthorizationService.visibleRecordPredicate` — the *same* predicate the record list pages use, which
evaluates ownership, role roll-up, live sharing-rule criteria, teams, territories and manual shares at
the moment of the query. Anything not returned is dropped.

Batched **per type, not per record**: a page of twenty hits spanning three object types costs three
queries, not twenty. The cost is therefore bounded by the number of entity types on the page (at most
four) and by the page size — never by the size of the corpus.

`SearchIndexSqlIT.staleSharingKeyIsCaughtByTheAuthoritativeRecheck` constructs the failure mode
directly: it indexes a document while a manual share is genuinely in force, revokes the share without
rebuilding the index, asserts that **the index still matches for the revoked user**, and then asserts
that the authoritative re-check returns nothing. The first assertion matters as much as the second — if
the index ever stopped being stale-permissive, the re-check would look unnecessary and somebody would
delete it.

### 4.5 Measured cost

The API reports the figure rather than asking anyone to trust a claim. Every search response carries
`indexQueryMillis` and `recheckMillis`, and the UI prints both. On the seeded Meridian tenant (10
accounts, 11 contacts, 9 opportunities, 3 leads) a 20-row page costs a **single-digit millisecond**
re-check against a low-single-digit-millisecond index query — roughly a doubling of a very small
number, which is the "little latency on the result page" §8.2 accepts. The shape that matters is that
the re-check grows with the page, not with the tenant.

### 4.6 The count is not padded

When the re-check drops two of twelve, the response says twelve matched, ten returned, two withheld.
It does **not** fetch two more from the index to top the page back up. Padding would reintroduce the
index as the authority on who sees what, one row further down, and would make the result count a
number that cannot be reconciled with anything.

---

## 5. Field-level security in search (`FR-SEC-007`)

The requirement names search explicitly — permissions must be "enforced uniformly in UI, API, reports,
exports, **search** and AI grounding" — and requires that a hidden field be **absent**, not null:
"absence and emptiness must not be conflated".

The index stores four text surfaces rather than one blob, because a blob cannot be partially withheld.
At query time, for each surviving hit:

1. **Title** is built from named securable fields. If the caller cannot read them, the hit is dropped
   entirely — a search result with no headline is not a redacted result, it is an unusable one.
2. **Subtitle** is omitted from the response map when its field is unreadable. The key is not present;
   it is not `null`. The API client's type marks it optional and the UI renders "Restricted for your
   profile" rather than a blank cell.
3. **`secured_fields`** values are filtered per field, and the caller-readable text is reassembled from
   what remains.
4. That reassembled text is then re-matched against the query. **A hit that matched the index only
   through a field this caller may not read is dropped**, because the existence of the hit would leak
   the hidden value's content — "this contact's hidden email contains `acme.com`" is a disclosure even
   with the address itself withheld.
5. `withheldFields` names what was withheld, so the UI can say so instead of quietly showing less.

---

## 6. The staleness contract

An event-fed index is eventually consistent, and ADR-003 is explicit: "where a user can observe the
lag, it must be shown to them rather than left to look like a bug." §8.1 says the same of the reporting
projection.

`GET /api/v1/search/status` (and the `index` block on every search response) reports:

| Field | Meaning |
|---|---|
| `documentCount`, `documentsByType` | What the index holds |
| `newestIndexedUpdatedAt` | The newest **source** timestamp present in the index |
| `lagSeconds` | Distance from that to now. `null` on an empty index — not `0`, which would claim currency the index does not have |
| `lastIndexedAt` | When the index last accepted a write |
| `consumerCheckpointAt` | How far the outbox consumer has read |
| `pendingEvents` | Indexable outbox events behind the checkpoint — the real backlog, counted, not estimated |
| `activeRun` | A reindex in flight, with progress |

**What the contract promises.** A committed business change becomes findable within one consumer poll
interval plus one projection, under normal operation. It does **not** promise read-your-writes: a user
who saves and searches within the same second may not see their own edit, and the freshness line on the
search page is what tells them so.

**What it does not paper over.** If the consumer is stopped, `pendingEvents` grows and the UI says so.
It does not show "Index current" while silently falling behind.

---

## 7. Backfill and reindex

An event-fed index is only as complete as the event history it has seen, and three ordinary situations
leave it incomplete: a new deployment starts empty, a fixed projection bug invalidates every document
written under the old code, and a tenant restored from backup has no replayable events. "Wait for
organic events" answers none of them — a record nobody edits again would never become findable.

Data model §8's rule for the sharing recompute applies verbatim: rebuilds are incremental and
asynchronous, with visible progress, and must never block business writes.

- A request writes one `search.reindex_run` row and returns. Administrator-gated
  (`CrmRole.requireMasterAdmin`) and **audited** via `AuditService` on both queue and completion — the
  ordinary query path audits nothing, because a search box that writes an audit row per keystroke
  buries the events that matter.
- A poller drains it in bounded batches, writing the cursor after **every** batch, so an API restart
  mid-run resumes at the next record rather than starting again.
- Paging is **keyset on the primary key**, not `OFFSET`: no table-wide lock, no long snapshot, nothing
  for a business write to queue behind.
- `processed_units` against `total_units` is a count of real records. There is no fake percentage.
- Two phases: **INDEX** walks the source and upserts; **PRUNE** walks the index and removes documents
  whose source record has gone. A rebuild that only ever wrote would leave a record hard-deleted during
  an outage findable forever.

---

## 8. The swap path to OpenSearch

ADR-005 keeps the engine open and expects escalation "on measured need". The seam is the
`SearchIndex` interface — six operations, no SQL in any signature:

```java
boolean upsert(UUID tenantId, SearchDocument document);
int     delete(UUID tenantId, IndexedEntity entity, UUID entityId);
List<Candidate> query(UUID tenantId, IndexQuery query, IndexFilter filter);
List<Snippet>   snippets(List<String> readableText, String queryText);
IndexFreshness  freshness(UUID tenantId);
Map<IndexedEntity, Long> documentCounts(UUID tenantId);
List<UUID>      storedIds(UUID tenantId, IndexedEntity entity, UUID afterId, int limit);
```

`PostgresSearchIndex` is the first implementation. An `OpenSearchSearchIndex` would be a new class and
a bean selection; the indexer, the backfill, the query service, the authorization re-check, the
field-security logic and the entire UI are unchanged.

**What deliberately is not on the seam: authorization.** `query` takes an `IndexFilter` and applies it,
but its result type is called `Candidate`, not `Hit`. No implementation of this interface is permitted
to be the last word on what a user may see. The authoritative re-check lives in `SearchService`, above
the seam, so switching engines cannot lose it — which is the failure this whole document exists to
prevent.

**Migration procedure**, when the measurement justifies it:

1. Deploy the new implementation alongside; register a second consumer name in
   `search.index_checkpoint` so it tracks its own outbox position without disturbing the live one.
2. Run a full backfill into the new engine (§7). It is resumable and does not block writes.
3. Compare `documentCounts` and spot-check ranking on real queries.
4. Flip the bean. The re-check, the field security and the staleness contract are unaffected because
   none of them live below the seam.

**What would justify the escalation**, stated so the decision is falsifiable: `ts_rank_cd` over a GIN
index degrading past the interactive budget on the largest tenant's corpus, a requirement for fuzzy or
phonetic matching that `pg_trgm` cannot serve, or cross-language analysis beyond the `english`
configuration. Corpus size alone is not a reason; measured query latency is.

---

## 9. Tenancy

Every table in `search` carries `tenant_id`, has `ENABLE` **and** `FORCE ROW LEVEL SECURITY`, and a
`tenant_isolation` policy using
`nullif(current_setting('app.tenant_id', true), '')::uuid` — the `nullif` is load-bearing, because
`SET LOCAL` restores a placeholder GUC to the **empty string**, not `NULL`, and a bare `''::uuid` cast
raises on the next pooled connection ([ADR-001](adr/ADR-001-tenancy-isolation.md), repeated from V10 and
V13). The query API takes no tenant argument anywhere; the tenant comes from the verified session
(`FR-GLOBAL-001`).

---

## 10. Known gaps

| Gap | Why it is deferred, not hidden |
|---|---|
| Only account, contact, lead and opportunity are indexed | `FR-ADM-004`'s target scope also includes quotes, cases and notes. Adding one is an enum entry plus a projection query; the authorization model is already generic over `SecurableObject` |
| No fuzzy or phonetic matching | `plainto_tsquery` with the `english` configuration handles stemming, not typos. `pg_trgm` would close this without leaving PostgreSQL; not yet measured as needed |
| Criteria sharing rules widen the filter rather than narrowing it | Correct but not optimal: a tenant with a rule on every object gets little index-level narrowing and relies on the re-check. The alternative is the materialized ACL §8.2 rejects |
| `CommandPalette` is not wired to this API | It is a synchronous filter over a hard-coded navigation list; making it a debounced remote search would mean restructuring it. `/search` is the search surface |

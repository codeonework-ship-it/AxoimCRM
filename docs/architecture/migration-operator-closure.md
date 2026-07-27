# E18 migration operator closure

## Closure boundary

The first-party migration engine is complete behind the vendor-neutral
`SourceAdapter` port. The shipped fixture adapter is the executable contract
test. Salesforce, Zoho CRM and HubSpot authenticated round trips are not
simulated and remain `PENDING_VENDOR` until credentials, partner environments
and certification evidence exist.

## Safety invariants

1. **Dry run writes no business data.** The analyzer uses the same assembly,
   validation, duplicate and relationship rules as import without calling a
   mutating database operation.
2. **Import is atomic.** Target records, ledger rows, outbox evidence, plan
   watermark and per-object checkpoints commit in one worker transaction.
3. **A failed run cannot advance a checkpoint.** Retrying creates a new run
   linked by `retry_of_run`; the failed evidence is immutable.
4. **Rollback can only reach ledger-owned records.** `migration.record_map` is
   the exclusive delete input. A tenant-checked, entity-allow-listed database
   function performs the physical deletion; the runtime role has no broad CRM
   delete grant. Foreign-key references created after migration block removal
   and appear as operator issues. Pre-existing tenant records have no ledger
   entry and are structurally unreachable. Opportunity stage/state histories
   are the only explicitly classified owned derivatives and are removed with
   their ledger-owned parent.
5. **Mapping changes are recoverable.** Every proposal, operator edit and
   restore creates an immutable JSON mapping revision. Restoring an older
   revision creates a new revision and invalidates the unmapped-field
   acknowledgement.
6. **Tenant isolation is enforced twice.** Every query binds the authenticated
   tenant and every E18 table uses forced PostgreSQL row-level security.

## Run state model

| State | Safe operator actions | Meaning |
|---|---|---|
| `QUEUED` | Cancel | No source or target work has begun |
| `RUNNING` | Wait | The run is executing atomically; partial cancellation is forbidden |
| `FAILED` | Review issues, retry | No target/checkpoint partial commit survives |
| `CANCELLED` | Retry | Cancellation occurred before execution |
| `COMPLETED` import/delta | Reconcile, rollback | Target writes and checkpoint committed together |
| `COMPLETED` rollback | Review blockers; queue the current ledger again after resolving them | Removed and blocked records are explicit evidence; a completed attempt is immutable |

## Operator API

- `GET/PATCH /api/v1/migration/plans/{id}/mapping` — review and correct all
  field decisions; unmapped fields are a first-class list.
- `GET /api/v1/migration/plans/{id}/mapping/revisions` and
  `POST .../{version}/restore` — immutable mapping history and recovery.
- `POST /api/v1/migration/plans/{id}/runs` — queue `DRY_RUN`, `IMPORT` or
  `DELTA`; every long action returns a pollable handle.
- `POST /api/v1/migration/plans/{id}/reconcile` — fresh authoritative
  source-versus-target comparison.
- `GET /api/v1/migration/plans/{id}/rollback-preview` and
  `POST .../rollback` — retention and exact-scope evidence before removal.
- `GET /api/v1/migration/runs/{id}/issues` — server-side search/category filter
  with 100 rows per page.
- `GET /api/v1/migration/runs/{id}/recovery`, `POST .../retry`, and
  `POST .../cancel` — layman-language next action and safe recovery commands.
- `GET /api/v1/migration/plans/{id}/checkpoints` — per-object successful delta
  watermarks and last-run counts.

## Release evidence

- `MigrationAnalyzerTest`: proves the dry-run path performs zero database
  mutations while producing complete validation evidence.
- `MappingProposerTest`: proves deterministic mapping and explicit unmapped
  fields.
- `MigrationRecoveryServiceTest`: proves allowed actions for queued, running,
  failed, completed, partially rolled-back and fully rolled-back states,
  including durable checkpoint semantics.
- Flyway migrations `V342`-`V344`: database constraints, keys, indexes, forced
  RLS, scoped runtime execution and the explicit owned-derivative policy.
- Live reversible fixture proof (2026-07-26): dry run processed 17 source rows;
  import plus delta created the owned dataset and five per-object checkpoints;
  fresh reconciliation exposed the four intentionally invalid/skipped source
  rows; rollback removed all 19 ledger-owned targets across immutable recovery
  attempts, reduced the live ledger and checkpoints to zero, and preserved all
  9 pre-existing tenant accounts.
- Frontend production build: typed API and the responsive Migration Operations
  workspace compile together.

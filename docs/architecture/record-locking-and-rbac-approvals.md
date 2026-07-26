# Record locking and RBAC approval architecture

Status: implemented and acceptance-tested on 2026-07-26. This note closes
delivery units #57–#60; it does not claim that every story in the wider product
epics is complete.

## Record edit locks (#57)

`crm.record_lock` is a tenant-scoped lease, not a permanent flag. A live lock is
one whose `expires_at` is in the future. The API normalizes record types against
an allow-list and uses one PostgreSQL upsert with a conflict predicate, avoiding
the read-then-write race that occurs when two editors open a record together.

The REST contract is:

| Method | Path | Meaning |
|---|---|---|
| `GET` | `/api/v1/record-locks/{type}/{id}` | Read explicit locked/free state |
| `POST` | `/api/v1/record-locks/{type}/{id}` | Acquire or renew the caller's lease |
| `PUT` | `/api/v1/record-locks/{type}/{id}/heartbeat` | Extend a lease already held by the caller |
| `DELETE` | `/api/v1/record-locks/{type}/{id}` | Idempotently release the caller's lease |
| `POST` | `/api/v1/record-locks/{type}/{id}/force-release` | Administrative override |
| `DELETE` | `/api/v1/record-locks/mine` | Release all leases held by the caller |

The React hook reference-counts leases across React Strict Mode mounts, waits
for an in-flight acquire before cleanup, heartbeats at the interval returned by
the server, and releases on form close. Contact editing is the first complete
consumer. A conflicting editor gets the holder's name/email and lease expiry;
fields and Save remain disabled. Tenant and super administrators also receive
an explicit Force Unlock action. Optimistic version checks remain in place as
the final protection at save time.

## Approval-gated RBAC grants (#58 and #59)

Role, profile, permission-set, and permission-set-group assignments no longer
write access immediately. `RbacAdminController` returns HTTP 202 with a pending
`PERMISSION_GRANT` request. The **Authorization → Maker-Checker Approvals** UI
exposes submission, queue filtering, approve/reject decisions, effective access
inspection, and approval delegation.

Approval and grant application execute inside one Spring transaction. The
sequence is:

1. verify `SYS.APPROVE_PERMISSION_GRANT`;
2. load and validate the typed JSON payload;
3. apply four-eyes and transitive delegation checks;
4. move the request from `PENDING` to `APPROVED`;
5. apply the authoritative role/profile/permission mutation.

If step 5 fails, the transaction rolls the approval back to `PENDING`; an
approved-but-not-applied request cannot be committed. Self-approval and anyone
connected to the initiator through an active delegation chain are refused and
audited as segregation violations.

Axiom has two authenticated principal tables. Migration V341 deliberately
makes approval actor UUIDs polymorphic so platform `SUPER_ADMIN` operators can
submit and decide within an active tenant. Tenant RLS stays in force, reads
resolve actor names across both principal tables, and delegate creation validates
that the target is active before persisting it.

## Electron wrapping and pixelation gate (#60)

`npm run audit:visual` in `electron-client` launches the real sandboxed Electron
renderer, signs into the integrated local stack, and checks Home, Contacts,
Authorization and Reports at 1024×700, 1280×800, 1440×900 and 1920×1080. It
fails on exposed horizontal overflow, clipped controls, controls outside the
viewport, or raster images enlarged beyond native device-pixel resolution. It
also writes one offscreen-rendered PNG per route/viewport and a JSON report to
the ignored `electron-client/audit-output` directory.

The desktop shell uses a content-size contract and resets persisted zoom to
100%. Rail-aware header breakpoints compress the tenant context, search,
language, theme and identity controls before the 230px navigation rail causes
clipping. The minimum size is still 1024×700; below the compact breakpoint,
accessible icon controls replace labels rather than cutting text.

## Acceptance evidence

- Backend full Maven suite: green.
- Frontend TypeScript production build: green.
- Live record-lock proof: second editor receives HTTP 409, sees the holder,
  heartbeat is 30 seconds, and can acquire immediately after release.
- Live maker-checker proof: submit is HTTP 202/PENDING, self-approval is HTTP
  403, a separate tenant admin approves, and the effective profile confirms the
  applied grant.
- Browser proof: approval queue/effective-permissions UI and record conflict
  banner render with no console errors.
- Electron audit: 16/16 route/viewport checks pass.

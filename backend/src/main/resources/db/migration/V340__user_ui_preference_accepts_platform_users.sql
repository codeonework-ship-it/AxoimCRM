-- ============================================================================
-- V340 — user_ui_preference must accept a platform user, not just an app user
--
-- THE BUG, AS REPORTED: changing the theme returned 500.
--
--   ERROR: insert or update on table "user_ui_preference" violates foreign key
--          constraint "user_ui_preference_tenant_id_user_id_fkey"
--
-- V337 gave the preference table a tenant-composite foreign key to
-- identity.app_user(tenant_id, id), following ADR-001's rule that a bare user_id
-- could otherwise point at a user in another tenant. That reasoning is right for
-- a CRM record. It is wrong here, for one reason:
--
--   THIS PRODUCT HAS TWO KINDS OF SIGNED-IN PRINCIPAL.
--
--     identity.app_user        — workspace users (6 in dev)
--     platform.platform_user   — operators of the platform itself (2 in dev)
--
-- Both authenticate, both get a token, both reach the API, and both have a theme.
-- A foreign key to ONE of those tables is therefore unsatisfiable for every
-- principal in the other — so a platform operator could read the theme catalogue
-- and could not save a choice. That is exactly what happened, and it failed at the
-- database rather than in validation, which is why it surfaced as a 500 instead of
-- a message anyone could act on.
--
-- THE FIX FOLLOWS THE PATTERN THE PRODUCT ALREADY USES.
--
-- activity.user_activity has precisely this problem and solved it years of
-- migrations ago: it carries exactly ONE foreign key, to platform.tenant, and its
-- actor_id is a bare uuid with no FK at all. Not an oversight — a principal id
-- cannot be constrained to one table when there are two principal tables. This
-- migration makes the preference table match.
--
-- WHAT IS GIVEN UP, AND WHY IT IS ACCEPTABLE
--
--   1. Referential integrity on user_id. A preference row can outlive its user.
--      The cost of that orphan is one row holding a colour name — it is never
--      read again, since reads are always keyed by the current principal's id, and
--      uuids are not reused. Compare that with the cost of the FK: a whole class
--      of user cannot change theme.
--   2. ON DELETE CASCADE. Was doing the tidying; now nothing does. If orphan
--      preferences ever matter they can be swept by joining against both principal
--      tables, which is a housekeeping job, not an invariant.
--
-- Tenant isolation is untouched: the tenant FK stays, and the RLS policy on this
-- table is what actually prevents cross-tenant reads and writes — the composite FK
-- was defence in depth, not the mechanism.
-- ============================================================================

alter table identity.user_ui_preference
  drop constraint if exists user_ui_preference_tenant_id_user_id_fkey;

comment on column identity.user_ui_preference.user_id is
  'A principal id, from EITHER identity.app_user OR platform.platform_user. There
   is deliberately no foreign key: this product has two principal tables, so a key
   to one of them refuses every principal from the other. Same reasoning, and same
   shape, as activity.user_activity.actor_id. Tenant isolation is enforced by the
   RLS policy and the tenant_id foreign key, which both remain.';

-- =============================================================================
-- V333 — schema USAGE for the two schemas V332 created.
--
-- V332 granted table privileges to axiom_app but not USAGE on the schemas that
-- hold those tables, so every statement failed with
-- "permission denied for schema receivables" before the table grant was even
-- consulted. A table grant is not reachable without USAGE on its schema; the two
-- are separate privileges and both are required.
--
-- Split into its own migration rather than edited into V332 because V332 has
-- already been applied — Flyway validates the checksum of applied migrations, and
-- rewriting one in place makes every existing environment fail to start.
-- =============================================================================

grant usage on schema procurement to axiom_app;
grant usage on schema receivables to axiom_app;

-- The relay role reads outbox_event only (V3) and has no business in either
-- schema, so it is deliberately not granted here.

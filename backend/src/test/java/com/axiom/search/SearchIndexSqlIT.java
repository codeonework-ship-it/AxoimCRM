package com.axiom.search;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of this engine that lives in PostgreSQL, asserted against PostgreSQL.
 *
 * <p>Four of the guarantees this engine makes are database behaviours, not Java
 * behaviours: upsert idempotency, the out-of-order guard, weighted relevance, and
 * tenant isolation under row-level security. Mocking a {@code JdbcTemplate} to
 * "verify" any of them would assert the mock's manners, so these connect to a real
 * server as the least-privilege runtime role {@code axiom_app} — the role the API
 * actually uses — because RLS is bypassed by superusers, and a test connecting as
 * one would pass while production leaked.
 *
 * <p>ADR-003 is explicit that "idempotency that has never been tested under
 * duplicate delivery is a hope, not a property". {@link #duplicateDeliveryProducesExactlyOneRow}
 * is that test.
 *
 * <p><b>Where it runs.</b> Against a real PostgreSQL 17, in a throwaway database this
 * class creates and drops, so it touches no tenant data and leaves nothing behind.
 * It is not a Testcontainers class because Testcontainers cannot reach the Docker
 * daemon from this build environment, and a suite that silently skips its most
 * important assertions is worse than one that is honest about where it points. When
 * no server is reachable the class disables itself rather than failing a build that
 * has nothing to do with search.
 *
 * <p>The schema below is a faithful reproduction of {@code V240__search_index_engine.sql}
 * plus the two authoritative tables the recheck reads, rather than a full Flyway run:
 * it keeps the class fast and makes it fail for exactly one reason at a time.
 */
@EnabledIf("databaseAvailable")
class SearchIndexSqlIT {

    private static final String HOST = System.getProperty("axiom.it.db.host",
            System.getenv().getOrDefault("AXIOM_IT_DB_HOST", "localhost"));
    private static final String PORT = System.getProperty("axiom.it.db.port",
            System.getenv().getOrDefault("AXIOM_IT_DB_PORT", "5432"));
    private static final String ADMIN_USER = System.getProperty("axiom.it.db.user",
            System.getenv().getOrDefault("AXIOM_IT_DB_USER", "Axiom"));
    private static final String ADMIN_PASSWORD = System.getProperty("axiom.it.db.password",
            System.getenv().getOrDefault("AXIOM_IT_DB_PASSWORD", "Axiom@12345"));
    private static final String APP_USER = "axiom_app";
    private static final String APP_PASSWORD = System.getProperty("axiom.it.db.app-password",
            System.getenv().getOrDefault("AXIOM_IT_DB_APP_PASSWORD", "axiom_app_dev_password"));

    /** Its own database, created and dropped here, so no tenant data is ever in reach. */
    private static final String SCRATCH_DB = "axiom_search_it";

    private static String url(String database) {
        return "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
    }

    static boolean databaseAvailable() {
        try (Connection c = DriverManager.getConnection(url("postgres"), ADMIN_USER, ADMIN_PASSWORD)) {
            return c.isValid(3);
        } catch (SQLException ex) {
            System.out.println("[SearchIndexSqlIT] no PostgreSQL at " + url("postgres")
                    + " as " + ADMIN_USER + ": " + ex.getMessage());
            return false;
        }
    }

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("11111111-1111-1111-1111-111111111102");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222223");
    private static final UUID GRANTEE = UUID.fromString("22222222-2222-2222-2222-222222222221");

    private static final UUID ACC_OWNED = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID ACC_SHARED = UUID.fromString("33333333-3333-3333-3333-333333333332");
    private static final UUID ACC_BODY = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ACC_OTHER_TENANT = UUID.fromString("33333333-3333-3333-3333-333333333334");

    @BeforeAll
    static void setUpSchema() throws SQLException {
        try (Connection cluster = DriverManager.getConnection(url("postgres"), ADMIN_USER, ADMIN_PASSWORD);
             Statement bootstrap = cluster.createStatement()) {
            bootstrap.execute("drop database if exists " + SCRATCH_DB + " with (force)");
            bootstrap.execute("create database " + SCRATCH_DB);
            // axiom_app is a cluster-wide role and already exists in this deployment.
            bootstrap.execute("do $$ begin if not exists (select 1 from pg_roles where rolname = '"
                    + APP_USER + "') then create role " + APP_USER + " login password '"
                    + APP_PASSWORD + "'; end if; end $$");
        }

        try (Connection admin = DriverManager.getConnection(url(SCRATCH_DB), ADMIN_USER, ADMIN_PASSWORD);
             Statement st = admin.createStatement()) {

            st.execute("create schema search");
            st.execute("create schema crm");
            st.execute("create schema security");
            st.execute("grant usage on schema search, crm, security to axiom_app");

            // --- the authoritative store the recheck reads -------------------------
            st.execute("""
                    create table crm.account (
                      id uuid primary key,
                      tenant_id uuid not null,
                      name text not null,
                      industry text,
                      owner_id uuid,
                      updated_at timestamptz not null default now(),
                      deleted_at timestamptz
                    )""");
            st.execute("""
                    create table security.record_share (
                      id uuid primary key default gen_random_uuid(),
                      tenant_id uuid not null,
                      object_type text not null,
                      record_id uuid not null,
                      grantee_user_id uuid not null,
                      access_level text not null default 'READ',
                      expires_at timestamptz,
                      revoked_at timestamptz
                    )""");

            // --- the index, reproduced from V240 -----------------------------------
            st.execute("""
                    create table search.search_document (
                      id             uuid primary key default gen_random_uuid(),
                      tenant_id      uuid not null,
                      entity_type    text not null check (entity_type in ('ACCOUNT','CONTACT','LEAD','OPPORTUNITY')),
                      entity_id      uuid not null,
                      title          text not null,
                      subtitle       text,
                      body           text,
                      secured_terms  text,
                      secured_fields jsonb not null default '{}'::jsonb,
                      owner_id       uuid,
                      sharing_keys   uuid[] not null default array[]::uuid[],
                      url_path       text not null,
                      updated_at     timestamptz not null,
                      indexed_at     timestamptz not null default now(),
                      document       tsvector generated always as (
                                         setweight(to_tsvector('english', coalesce(title, '')), 'A')
                                      || setweight(to_tsvector('english', coalesce(subtitle, '')), 'B')
                                      || setweight(to_tsvector('english', coalesce(body, '')), 'C')
                                      || setweight(to_tsvector('english', coalesce(secured_terms, '')), 'D')
                                     ) stored,
                      constraint uq_search_document_entity unique (tenant_id, entity_type, entity_id)
                    )""");
            st.execute("create index idx_search_document_fts on search.search_document using gin (document)");
            st.execute("create index idx_search_document_keys on search.search_document using gin (sharing_keys)");
            st.execute("""
                    create table search.reindex_run (
                      id uuid primary key default gen_random_uuid(),
                      tenant_id uuid not null,
                      entity_type text,
                      status text not null default 'QUEUED',
                      total_units bigint not null default 0,
                      processed_units bigint not null default 0,
                      cursor_entity_type text,
                      cursor_entity_id uuid,
                      queued_at timestamptz not null default now()
                    )""");

            for (String table : List.of("crm.account", "security.record_share",
                    "search.search_document", "search.reindex_run")) {
                st.execute("alter table " + table + " enable row level security");
                st.execute("alter table " + table + " force row level security");
                st.execute("create policy tenant_isolation on " + table
                        + " using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)"
                        + " with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)");
                st.execute("grant select, insert, update, delete on " + table + " to axiom_app");
            }

            // --- fixtures ----------------------------------------------------------
            st.execute("insert into crm.account (id, tenant_id, name, industry, owner_id) values "
                    + "('" + ACC_OWNED + "', '" + TENANT_A + "', 'Meridian Fabrication Group', 'Metal fabrication', '" + OWNER + "'),"
                    + "('" + ACC_SHARED + "', '" + TENANT_A + "', 'Castellan Alloys', 'Speciality alloys', '" + OWNER + "'),"
                    + "('" + ACC_BODY + "', '" + TENANT_A + "', 'Northbridge Tooling', 'Tooling', '" + OWNER + "'),"
                    + "('" + ACC_OTHER_TENANT + "', '" + TENANT_B + "', 'Meridian Lookalike Ltd', 'Metal fabrication', '" + OWNER + "')");

            // A manual share that WILL BE REVOKED, so the index can be left holding a
            // key that the authoritative store no longer honours.
            st.execute("insert into security.record_share (tenant_id, object_type, record_id, grantee_user_id) "
                    + "values ('" + TENANT_A + "', 'ACCOUNT', '" + ACC_SHARED + "', '" + GRANTEE + "')");
        }
    }

    @AfterAll
    static void dropScratchDatabase() throws SQLException {
        try (Connection cluster = DriverManager.getConnection(url("postgres"), ADMIN_USER, ADMIN_PASSWORD);
             Statement st = cluster.createStatement()) {
            st.execute("drop database if exists " + SCRATCH_DB + " with (force)");
        }
    }

    /** Connects as the runtime role, not the owner — owners are not what production uses. */
    private Connection app(UUID tenantId) throws SQLException {
        Connection c = DriverManager.getConnection(url(SCRATCH_DB), APP_USER, APP_PASSWORD);
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("select set_config('app.tenant_id', '" + tenantId + "', true)");
        }
        return c;
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    @DisplayName("duplicate delivery of the same index event produces exactly ONE row")
    void duplicateDeliveryProducesExactlyOneRow() throws SQLException {
        try (Connection c = app(TENANT_A)) {
            int first = upsert(c, ACC_OWNED, "Meridian Fabrication Group", "Metal fabrication",
                    "Pune Maharashtra", "2026-07-20 10:00:00+00", List.of(OWNER));
            int second = upsert(c, ACC_OWNED, "Meridian Fabrication Group", "Metal fabrication",
                    "Pune Maharashtra", "2026-07-20 10:00:00+00", List.of(OWNER));
            int third = upsert(c, ACC_OWNED, "Meridian Fabrication Group", "Metal fabrication",
                    "Pune Maharashtra", "2026-07-20 10:00:00+00", List.of(OWNER));

            assertThat(first).as("first delivery inserts").isEqualTo(1);
            assertThat(second).as("redelivery is accepted, not rejected").isEqualTo(1);
            assertThat(third).isEqualTo(1);
            assertThat(count(c, "select count(*) from search.search_document where entity_id = '"
                    + ACC_OWNED + "'"))
                    .as("three deliveries, one document")
                    .isEqualTo(1);
            c.rollback();
        }
    }

    @Test
    @DisplayName("an out-of-order older update does not overwrite a newer document")
    void olderUpdateIsIgnored() throws SQLException {
        try (Connection c = app(TENANT_A)) {
            upsert(c, ACC_OWNED, "Meridian Fabrication Group (renamed)", "Metal fabrication",
                    "Pune", "2026-07-20 12:00:00+00", List.of(OWNER));

            int applied = upsert(c, ACC_OWNED, "Meridian Fabrication Group (stale)", "Metal fabrication",
                    "Pune", "2026-07-20 09:00:00+00", List.of(OWNER));

            assertThat(applied).as("the stale event changes nothing").isZero();
            assertThat(scalar(c, "select title from search.search_document where entity_id = '"
                    + ACC_OWNED + "'"))
                    .isEqualTo("Meridian Fabrication Group (renamed)");
            c.rollback();
        }
    }

    @Test
    @DisplayName("deleting a record removes its document")
    void deletionRemovesTheDocument() throws SQLException {
        try (Connection c = app(TENANT_A)) {
            upsert(c, ACC_OWNED, "Meridian Fabrication Group", null, null,
                    "2026-07-20 10:00:00+00", List.of(OWNER));
            assertThat(count(c, "select count(*) from search.search_document")).isEqualTo(1);

            try (PreparedStatement ps = c.prepareStatement(
                    "delete from search.search_document where tenant_id = ? and entity_type = ? and entity_id = ?")) {
                ps.setObject(1, TENANT_A);
                ps.setString(2, "ACCOUNT");
                ps.setObject(3, ACC_OWNED);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            assertThat(count(c, "select count(*) from search.search_document")).isZero();
            c.rollback();
        }
    }

    // ------------------------------------------------------------------ relevance

    @Test
    @DisplayName("a title match outranks a body match — that is what setweight buys")
    void titleMatchesOutrankBodyMatches() throws SQLException {
        try (Connection c = app(TENANT_A)) {
            upsert(c, ACC_BODY, "Northbridge Tooling", "Tooling",
                    "supplies castings to meridian fabrication", "2026-07-20 10:00:00+00", List.of(OWNER));
            upsert(c, ACC_OWNED, "Meridian Fabrication Group", "Metal fabrication",
                    "Pune Maharashtra", "2026-07-20 10:00:00+00", List.of(OWNER));

            List<String> ordered = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    select d.title, ts_rank_cd(d.document, q.query) as rank
                    from search.search_document d, plainto_tsquery('english', ?) as q(query)
                    where d.document @@ q.query
                    order by rank desc
                    """)) {
                ps.setString(1, "meridian");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) ordered.add(rs.getString("title"));
                }
            }

            assertThat(ordered).hasSize(2);
            assertThat(ordered.get(0))
                    .as("the record NAMED Meridian must beat the record that merely mentions it")
                    .isEqualTo("Meridian Fabrication Group");
            c.rollback();
        }
    }

    @Test
    @DisplayName("the snippet highlights the term that actually matched")
    void snippetHighlightsTheMatchedTerm() throws SQLException {
        try (Connection c = app(TENANT_A)) {
            String snippet;
            try (PreparedStatement ps = c.prepareStatement("""
                    select ts_headline('english', ?, plainto_tsquery('english', ?),
                                       'StartSel="[[", StopSel="]]", MaxFragments=1, MaxWords=24, MinWords=6')
                    """)) {
                ps.setString(1, "Northbridge Tooling supplies castings to Meridian Fabrication Group in Pune");
                ps.setString(2, "castings");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    snippet = rs.getString(1);
                }
            }
            assertThat(snippet).contains("[[castings]]");
            c.rollback();
        }
    }

    // ------------------------------------------------------------------ THE headline test

    @Test
    @DisplayName("a record the caller may no longer read is dropped by the recheck even though the index still matches it")
    void staleSharingKeyIsCaughtByTheAuthoritativeRecheck() throws SQLException {
        try (Connection c = app(TENANT_A)) {
            // 1. The index was built while GRANTEE genuinely had a manual share, so the
            //    document carries their key. This is the ordinary, correct state.
            upsert(c, ACC_SHARED, "Castellan Alloys", "Speciality alloys",
                    "Chennai", "2026-07-20 10:00:00+00", List.of(OWNER, GRANTEE));

            // 2. Access is revoked. The index is NOT rebuilt — this is the whole point:
            //    "access can change faster than an index can be rebuilt".
            try (Statement st = c.createStatement()) {
                st.execute("update security.record_share set revoked_at = now() where record_id = '"
                        + ACC_SHARED + "' and grantee_user_id = '" + GRANTEE + "'");
            }

            // 3. The index STILL matches for the revoked user. Asserted, not assumed —
            //    if this ever stopped being true the recheck would look unnecessary and
            //    somebody would delete it.
            List<UUID> indexMatches = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    select d.entity_id
                    from search.search_document d, plainto_tsquery('english', ?) as q(query)
                    where d.tenant_id = ? and d.document @@ q.query
                      and (d.owner_id = any(?) or d.sharing_keys && ?)
                    """)) {
                ps.setString(1, "castellan");
                ps.setObject(2, TENANT_A);
                ps.setArray(3, ps.getConnection().createArrayOf("uuid", new Object[]{GRANTEE}));
                ps.setArray(4, ps.getConnection().createArrayOf("uuid", new Object[]{GRANTEE}));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) indexMatches.add(rs.getObject(1, UUID.class));
                }
            }
            assertThat(indexMatches)
                    .as("the index is stale-permissive, exactly as §8.2 predicts")
                    .containsExactly(ACC_SHARED);

            // 4. The authoritative re-check — ownership or a live, unrevoked share —
            //    returns nothing, so the hit is dropped before display.
            List<UUID> permitted = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    select a.id from crm.account a
                    where a.tenant_id = ? and a.id = any(?) and a.deleted_at is null
                      and ( a.owner_id = ?
                            or exists (select 1 from security.record_share rs
                                        where rs.tenant_id = a.tenant_id and rs.object_type = 'ACCOUNT'
                                          and rs.record_id = a.id and rs.grantee_user_id = ?
                                          and rs.revoked_at is null
                                          and (rs.expires_at is null or rs.expires_at > now())) )
                    """)) {
                ps.setObject(1, TENANT_A);
                ps.setArray(2, ps.getConnection().createArrayOf("uuid", indexMatches.toArray()));
                ps.setObject(3, GRANTEE);
                ps.setObject(4, GRANTEE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) permitted.add(rs.getObject(1, UUID.class));
                }
            }
            assertThat(permitted)
                    .as("the recheck is the only thing standing between a revoked user and this record")
                    .isEmpty();
            c.rollback();
        }
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("a query in one tenant cannot see another tenant's documents, even for an identical term")
    void crossTenantQueryReturnsNothing() throws SQLException {
        try (Connection seed = app(TENANT_B)) {
            upsert(seed, ACC_OTHER_TENANT, "Meridian Lookalike Ltd", "Metal fabrication",
                    "Chennai", "2026-07-20 10:00:00+00", List.of(OWNER));
            seed.commit();
        }
        try (Connection c = app(TENANT_A)) {
            upsert(c, ACC_OWNED, "Meridian Fabrication Group", "Metal fabrication",
                    "Pune", "2026-07-20 10:00:00+00", List.of(OWNER));

            // Deliberately asks for the other tenant's row by id, the way a forgotten
            // guard would. Row-level security refuses regardless.
            assertThat(count(c, "select count(*) from search.search_document d,"
                    + " plainto_tsquery('english', 'meridian') as q(query)"
                    + " where d.document @@ q.query and d.entity_id = '" + ACC_OTHER_TENANT + "'"))
                    .isZero();
            assertThat(count(c, "select count(*) from search.search_document d,"
                    + " plainto_tsquery('english', 'meridian') as q(query) where d.document @@ q.query"))
                    .as("only this tenant's Meridian is visible")
                    .isEqualTo(1);
            c.rollback();
        }
        try (Connection cleanup = app(TENANT_B)) {
            try (Statement st = cleanup.createStatement()) {
                st.execute("delete from search.search_document");
            }
            cleanup.commit();
        }
    }

    @Test
    @DisplayName("a transaction with no tenant bound sees no documents rather than all of them")
    void unboundTransactionSeesNothing() throws SQLException {
        try (Connection c = DriverManager.getConnection(url(SCRATCH_DB), APP_USER, APP_PASSWORD)) {
            c.setAutoCommit(false);
            // No set_config at all, and the GUC may be the empty string left by a prior
            // SET LOCAL on a pooled connection — nullif() is what stops that erroring.
            assertThat(count(c, "select count(*) from search.search_document")).isZero();
            c.rollback();
        }
    }

    // ------------------------------------------------------------------ backfill and lag

    @Test
    @DisplayName("a reindex resumes from its stored cursor instead of starting again")
    void reindexIsResumableFromItsCursor() throws SQLException {
        try (Connection c = app(TENANT_A)) {
            List<UUID> all = idsAfter(c, null, 10);
            assertThat(all).hasSize(3);

            // First batch of two, cursor persisted as the backfill does after each batch.
            List<UUID> firstBatch = idsAfter(c, null, 2);
            assertThat(firstBatch).hasSize(2);
            UUID cursor = firstBatch.get(1);
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into search.reindex_run (tenant_id, status, total_units, processed_units,"
                            + " cursor_entity_type, cursor_entity_id) values (?, 'RUNNING', 3, 2, 'INDEX:ACCOUNT', ?)")) {
                ps.setObject(1, TENANT_A);
                ps.setObject(2, cursor);
                ps.executeUpdate();
            }

            // Restart: read the cursor back and continue. Keyset paging means the
            // remainder is exactly the records not yet done — no gap, no repeat.
            UUID resumed = (UUID) scalarObject(c,
                    "select cursor_entity_id from search.reindex_run where tenant_id = '" + TENANT_A + "'");
            List<UUID> secondBatch = idsAfter(c, resumed, 2);

            assertThat(secondBatch).hasSize(1);
            assertThat(firstBatch).doesNotContainAnyElementsOf(secondBatch);
            List<UUID> union = new ArrayList<>(firstBatch);
            union.addAll(secondBatch);
            assertThat(union).containsExactlyInAnyOrderElementsOf(all);
            c.rollback();
        }
    }

    @Test
    @DisplayName("index lag is the distance between the newest indexed source timestamp and now")
    void indexLagIsMeasurable() throws SQLException {
        try (Connection c = app(TENANT_A)) {
            upsert(c, ACC_OWNED, "Meridian Fabrication Group", null, null,
                    "2026-07-20 10:00:00+00", List.of(OWNER));
            upsert(c, ACC_SHARED, "Castellan Alloys", null, null,
                    "2026-07-20 11:30:00+00", List.of(OWNER));

            long lagSeconds = count(c, """
                    select extract(epoch from (now() - max(updated_at)))::bigint
                    from search.search_document
                    """);
            long expected = count(c, """
                    select extract(epoch from (now() - timestamptz '2026-07-20 11:30:00+00'))::bigint
                    """);

            assertThat(count(c, "select count(*) from search.search_document")).isEqualTo(2);
            assertThat(lagSeconds)
                    .as("lag is measured from the NEWEST document, not the oldest")
                    .isBetween(expected - 5, expected + 5);
            c.rollback();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The production upsert, verbatim. Returns rows affected — 0 means "refused as stale". */
    private int upsert(Connection c, UUID entityId, String title, String subtitle, String body,
                       String updatedAt, List<UUID> sharingKeys) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                insert into search.search_document as d
                  (tenant_id, entity_type, entity_id, title, subtitle, body, secured_terms,
                   secured_fields, owner_id, sharing_keys, url_path, updated_at, indexed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::timestamptz, now())
                on conflict (tenant_id, entity_type, entity_id) do update set
                  title = excluded.title, subtitle = excluded.subtitle, body = excluded.body,
                  secured_terms = excluded.secured_terms, secured_fields = excluded.secured_fields,
                  owner_id = excluded.owner_id, sharing_keys = excluded.sharing_keys,
                  url_path = excluded.url_path, updated_at = excluded.updated_at, indexed_at = now()
                where d.updated_at <= excluded.updated_at
                """)) {
            UUID tenantId = tenantOf(c);
            ps.setObject(1, tenantId);
            ps.setString(2, "ACCOUNT");
            ps.setObject(3, entityId);
            ps.setString(4, title);
            ps.setString(5, subtitle);
            ps.setString(6, body);
            ps.setString(7, null);
            ps.setString(8, "{}");
            ps.setObject(9, OWNER);
            ps.setArray(10, c.createArrayOf("uuid", sharingKeys.toArray()));
            ps.setString(11, "/accounts?focus=" + entityId);
            ps.setString(12, updatedAt);
            return ps.executeUpdate();
        }
    }

    private static UUID tenantOf(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select current_setting('app.tenant_id', true)")) {
            rs.next();
            return UUID.fromString(rs.getString(1));
        }
    }

    private List<UUID> idsAfter(Connection c, UUID afterId, int limit) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                select a.id from crm.account a
                where a.tenant_id = ? and a.deleted_at is null
                  and a.id > coalesce(?, '00000000-0000-0000-0000-000000000000'::uuid)
                order by a.id limit ?
                """)) {
            ps.setObject(1, tenantOf(c));
            ps.setObject(2, afterId);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getObject(1, UUID.class));
            }
        }
        return ids;
    }

    private long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private Object scalarObject(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1);
        }
    }
}

package com.axiom.tenancy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the ADR-001 compliance clause: "Cross-tenant isolation is verified by dedicated
 * tests ... including tests that deliberately attempt cross-tenant access through every
 * entry point." Before this class, that clause was unmet — the suite had no isolation test
 * at all, so the product's single most important invariant was asserted only in prose.
 *
 * <p>These tests talk to a real PostgreSQL because row-level security is a database
 * behaviour. Mocking it would test the mock. They connect as the least-privilege runtime
 * role {@code axiom_app} — the role the API actually uses — because RLS is bypassed by
 * table owners and superusers, so a test connecting as {@code postgres} would pass while
 * production leaked.
 *
 * <p>The schema here is a minimal reproduction of the policies in V1 and V9 rather than a
 * full Flyway run: it keeps the test fast and focused on the invariant, and it fails for
 * exactly one reason — a policy that does not isolate.
 */
@Testcontainers(disabledWithoutDocker = true)
class TenantIsolationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeAll
    static void setUpSchema() throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = admin.createStatement()) {

            st.execute("create role axiom_app login password 'app_pw'");

            // A tenant-scoped business table, as V1 defines them.
            st.execute("""
                    create table account (
                      id uuid primary key default gen_random_uuid(),
                      tenant_id uuid not null,
                      name text not null
                    )""");
            st.execute("alter table account enable row level security");
            st.execute("alter table account force row level security");
            st.execute("""
                    create policy tenant_isolation on account
                      using (tenant_id = current_setting('app.tenant_id', true)::uuid)
                      with check (tenant_id = current_setting('app.tenant_id', true)::uuid)""");

            // A billing table, as V9 defines them: own-tenant OR platform-scoped transaction.
            st.execute("""
                    create table invoice (
                      id uuid primary key default gen_random_uuid(),
                      tenant_id uuid not null,
                      amount numeric(12,2) not null
                    )""");
            st.execute("alter table invoice enable row level security");
            st.execute("alter table invoice force row level security");
            st.execute("""
                    create policy tenant_or_platform on invoice
                      using (
                        tenant_id = current_setting('app.tenant_id', true)::uuid
                        or current_setting('app.platform_access', true) = 'on'
                      )
                      with check (
                        tenant_id = current_setting('app.tenant_id', true)::uuid
                        or current_setting('app.platform_access', true) = 'on'
                      )""");

            st.execute("grant select, insert, update, delete on account, invoice to axiom_app");

            st.execute("insert into account (tenant_id, name) values ('" + TENANT_A + "', 'Tenant A Ltd')");
            st.execute("insert into account (tenant_id, name) values ('" + TENANT_B + "', 'Tenant B Ltd')");
            st.execute("insert into invoice (tenant_id, amount) values ('" + TENANT_A + "', 100.00)");
            st.execute("insert into invoice (tenant_id, amount) values ('" + TENANT_B + "', 250.00)");
        }
    }

    /** Connects as the runtime role, not the owner — owners bypass RLS. */
    private Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "axiom_app", "app_pw");
    }

    private void bindTenant(Connection c, UUID tenantId) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("select set_config('app.tenant_id', '" + tenantId + "', true)");
        }
    }

    private int countRows(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("a tenant sees only its own rows, never another tenant's")
    void tenantSeesOnlyItsOwnRows() throws SQLException {
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, TENANT_A);

            assertThat(countRows(c, "account")).isEqualTo(1);
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("select name from account")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("Tenant A Ltd");
            }
            c.rollback();
        }
    }

    @Test
    @DisplayName("an explicit cross-tenant predicate still returns nothing — the leak is blocked at the database")
    void explicitCrossTenantQueryReturnsNothing() throws SQLException {
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, TENANT_A);

            // This is the "hand-written query that forgets its guard" from ADR-001 — worse,
            // it actively asks for another tenant's data. RLS must still refuse.
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "select count(*) from account where tenant_id = '" + TENANT_B + "'")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            c.rollback();
        }
    }

    @Test
    @DisplayName("a transaction with no tenant bound sees nothing rather than everything")
    void unboundTransactionSeesNothing() throws SQLException {
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            // Deliberately no set_config. current_setting(..., true) yields NULL, so the
            // policy predicate is NULL — which filters rather than fails open.
            assertThat(countRows(c, "account")).isZero();
            assertThat(countRows(c, "invoice")).isZero();
            c.rollback();
        }
    }

    @Test
    @DisplayName("a tenant cannot write a row belonging to another tenant")
    void cannotInsertIntoAnotherTenant() throws SQLException {
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, TENANT_A);

            assertThatThrownBy(() -> {
                try (Statement st = c.createStatement()) {
                    st.execute("insert into account (tenant_id, name) values ('" + TENANT_B + "', 'Smuggled')");
                }
            }).isInstanceOf(SQLException.class)
              .hasMessageContaining("row-level security");

            c.rollback();
        }
    }

    @Test
    @DisplayName("a tenant cannot update another tenant's row")
    void cannotUpdateAnotherTenantsRow() throws SQLException {
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, TENANT_A);

            try (Statement st = c.createStatement()) {
                int updated = st.executeUpdate(
                        "update account set name = 'Hijacked' where tenant_id = '" + TENANT_B + "'");
                assertThat(updated).isZero();
            }
            c.rollback();
        }
    }

    @Test
    @DisplayName("billing rows stay tenant-scoped without the platform flag")
    void billingIsTenantScopedByDefault() throws SQLException {
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, TENANT_A);
            assertThat(countRows(c, "invoice")).isEqualTo(1);
            c.rollback();
        }
    }

    @Test
    @DisplayName("the platform flag opens the cross-tenant billing view that operators need")
    void platformFlagGrantsCrossTenantBillingView() throws SQLException {
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, TENANT_A);
            try (Statement st = c.createStatement()) {
                st.execute("select set_config('app.platform_access', 'on', true)");
            }
            assertThat(countRows(c, "invoice")).isEqualTo(2);

            // The flag is scoped to billing-style policies; core business tables stay isolated.
            assertThat(countRows(c, "account")).isEqualTo(1);
            c.rollback();
        }
    }

    @Test
    @DisplayName("the platform flag dies with its transaction and cannot leak onto a pooled connection")
    void platformFlagDoesNotSurviveTheTransaction() throws SQLException {
        try (Connection c = appConnection()) {
            c.setAutoCommit(false);
            bindTenant(c, TENANT_A);
            try (Statement st = c.createStatement()) {
                st.execute("select set_config('app.platform_access', 'on', true)");
            }
            assertThat(countRows(c, "invoice")).isEqualTo(2);
            c.rollback();

            // Same physical connection, new transaction — as Hikari would hand it out again.
            bindTenant(c, TENANT_A);
            assertThat(countRows(c, "invoice"))
                    .as("SET LOCAL must not survive the transaction that set it")
                    .isEqualTo(1);
            c.rollback();
        }
    }
}

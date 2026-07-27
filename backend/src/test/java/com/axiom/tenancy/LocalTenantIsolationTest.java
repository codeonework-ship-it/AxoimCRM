package com.axiom.tenancy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Docker-free RLS release gate against a migrated local PostgreSQL database.
 * It is opt-in so ordinary unit tests remain hermetic; CI/local release runs set
 * AXIOM_LOCAL_DB_TESTS=true and the five connection variables documented below.
 */
@EnabledIfEnvironmentVariable(named = "AXIOM_LOCAL_DB_TESTS", matches = "true")
class LocalTenantIsolationTest {

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String url() { return required("AXIOM_TEST_DB_URL"); }

    @Test void runtimeRoleCannotReadOrMutateAnotherTenantsAccountAndUnboundFailsClosed() throws Exception {
        List<UUID> tenants = new ArrayList<>();
        List<UUID> accounts = new ArrayList<>();
        try (Connection owner = DriverManager.getConnection(url(), required("AXIOM_TEST_DB_OWNER"),
                required("AXIOM_TEST_DB_OWNER_PASSWORD"));
             Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery("""
                     select distinct on (tenant_id) tenant_id, id account_id from crm.account
                     where deleted_at is null order by tenant_id, id limit 2
                     """)) {
            while (rs.next()) { tenants.add(rs.getObject(1, UUID.class)); accounts.add(rs.getObject(2, UUID.class)); }
        }
        assertThat(tenants).as("RLS proof needs account rows in two tenants").hasSize(2);

        try (Connection runtime = DriverManager.getConnection(url(), required("AXIOM_TEST_DB_RUNTIME"),
                required("AXIOM_TEST_DB_RUNTIME_PASSWORD"))) {
            runtime.setAutoCommit(false);
            try (PreparedStatement bind = runtime.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, tenants.get(0).toString()); bind.execute();
            }
            try (PreparedStatement crossRead = runtime.prepareStatement(
                    "select count(*) from crm.account where id = ?")) {
                crossRead.setObject(1, accounts.get(1));
                try (ResultSet rs = crossRead.executeQuery()) { rs.next(); assertThat(rs.getInt(1)).isZero(); }
            }
            try (PreparedStatement crossWrite = runtime.prepareStatement(
                    "update crm.account set updated_at = updated_at where id = ?")) {
                crossWrite.setObject(1, accounts.get(1));
                assertThat(crossWrite.executeUpdate()).isZero();
            }
            runtime.rollback();
        }

        // A fresh pooled connection with no request context must fail closed.
        try (Connection unboundRuntime = DriverManager.getConnection(url(), required("AXIOM_TEST_DB_RUNTIME"),
                required("AXIOM_TEST_DB_RUNTIME_PASSWORD"))) {
            unboundRuntime.setAutoCommit(false);
            try (Statement unbound = unboundRuntime.createStatement();
                 ResultSet rs = unbound.executeQuery("select count(*) from crm.account")) {
                rs.next(); assertThat(rs.getInt(1)).isZero();
            }
            unboundRuntime.rollback();
        }
    }

    @Test void outboxIsTenantIsolatedAndAuditEvidenceCannotBeChanged() throws Exception {
        UUID tenant;
        UUID otherTenant;
        try (Connection owner = DriverManager.getConnection(url(), required("AXIOM_TEST_DB_OWNER"),
                required("AXIOM_TEST_DB_OWNER_PASSWORD"))) {
            try (Statement statement = owner.createStatement(); ResultSet rs = statement.executeQuery(
                    "select distinct tenant_id from crm.account order by tenant_id limit 2")) {
                assertThat(rs.next()).isTrue(); tenant = rs.getObject(1, UUID.class);
                assertThat(rs.next()).isTrue(); otherTenant = rs.getObject(1, UUID.class);
            }
            try (Statement statement = owner.createStatement(); ResultSet rs = statement.executeQuery("""
                    select has_table_privilege('axiom_app', 'governance.audit_event', 'UPDATE'),
                           exists (select 1 from pg_trigger
                                   where tgrelid = 'governance.audit_event'::regclass
                                     and tgname = 'trg_audit_event_no_update' and not tgisinternal)
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).as("runtime role must not update audit evidence").isFalse();
                assertThat(rs.getBoolean(2)).as("owner-level immutable-audit trigger must exist").isTrue();
            }
        }

        try (Connection runtime = DriverManager.getConnection(url(), required("AXIOM_TEST_DB_RUNTIME"),
                required("AXIOM_TEST_DB_RUNTIME_PASSWORD"))) {
            runtime.setAutoCommit(false);
            try (PreparedStatement bind = runtime.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, tenant.toString()); bind.execute();
            }
            try (PreparedStatement crossTenantOutbox = runtime.prepareStatement(
                    "select count(*) from integration.outbox_event where tenant_id = ?")) {
                crossTenantOutbox.setObject(1, otherTenant);
                try (ResultSet rs = crossTenantOutbox.executeQuery()) {
                    rs.next(); assertThat(rs.getInt(1)).isZero();
                }
            }
            runtime.rollback();
        }
    }
}

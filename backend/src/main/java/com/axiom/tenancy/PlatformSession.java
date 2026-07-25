package com.axiom.tenancy;

import com.axiom.auth.CrmRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Marks the current transaction as platform-scoped, which the
 * {@code tenant_or_platform} row-level-security policies added in
 * V9__platform_billing_rls.sql accept in place of a tenant match.
 *
 * <p>This exists because the platform-operator views over
 * {@code platform.company_account} and the {@code billing.*} tables are
 * legitimately cross-tenant, while every other query in the product must stay
 * inside one tenant. Rather than leave those five tables with no RLS at all —
 * protected only by an application-level role check, which ADR-001 explicitly
 * rejects as a single point of failure — the policy admits a row when the
 * transaction has been marked here.
 *
 * <p>The flag is <em>not</em> a client input. Nothing in a request can set it.
 * It is written by server code only after {@link CrmRole#requirePlatform} has
 * already authorized the caller, so a query that forgets its guard simply
 * returns zero rows instead of every tenant's invoices.
 *
 * <p>{@code set_config(..., true)} is {@code SET LOCAL}: the flag dies with the
 * transaction and cannot survive on a pooled Hikari connection.
 */
@Component
public class PlatformSession {

    private final JdbcTemplate jdbc;

    public PlatformSession(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Authorizes the caller as a platform role and marks this transaction
     * platform-scoped. Must be called inside a transaction, otherwise the
     * SET LOCAL evaporates immediately and the caller will correctly see no rows.
     *
     * @throws com.axiom.common.ForbiddenException if the current role is not a platform role
     */
    public void requirePlatformAndGrant() {
        CrmRole.requirePlatform(TenantContext.get().role());
        jdbc.query("select set_config('app.platform_access', 'on', true)", rs -> null);
    }
}

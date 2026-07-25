package com.axiom.security;

import com.axiom.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Runs a unit of work once per tenant, each in its own transaction with the RLS
 * session variable bound.
 *
 * <p>Every scheduled control in this package (segregation-of-duties sweep, expiry
 * sweep, access-review deadline sweep, sharing recompute) has the same problem: it
 * runs with no request and therefore no tenant context, but the row-level security
 * policies filter on {@code app.tenant_id}. A sweep that forgot to bind it would
 * quietly process zero rows — and for a control whose successful output is "no
 * violations found", silently seeing nothing is the worst available failure mode.
 * Centralising the binding means it cannot be forgotten in one of four places.
 *
 * <p>The bound principal is deliberately labelled as a scheduled task rather than
 * borrowing a real administrator's identity: audit rows written by a sweep should
 * not read as though a person did it at 02:15.
 *
 * <p>One transaction <em>per tenant</em>, not one for the run: a failure in tenant
 * seven must not roll back the remediation already written for tenants one to six,
 * and a long-running sweep must not hold a single transaction open across the whole
 * fleet.
 */
@Component
public class SystemTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemTaskRunner.class);

    /** Stable id so audit and grant-log rows written by sweeps are attributable to one actor. */
    public static final UUID SYSTEM_ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000005ec");
    public static final String SYSTEM_ACTOR_EMAIL = "scheduled-task@axiom.internal";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public SystemTaskRunner(JdbcTemplate jdbc,
                            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public List<UUID> tenantIds() {
        return jdbc.query("select id from platform.tenant order by created_at",
                (rs, i) -> rs.getObject(1, UUID.class));
    }

    /**
     * @param taskName used only in the log line when a tenant fails
     * @param work     receives the tenant id, returns a count for the summary log
     * @return the summed result across every tenant that succeeded
     */
    public int forEachTenant(String taskName, Function<UUID, Integer> work) {
        int total = 0;
        for (UUID tenantId : tenantIds()) {
            try {
                Integer result = inTenant(tenantId, work);
                total += result == null ? 0 : result;
            } catch (RuntimeException ex) {
                log.error("{} failed for tenant {}", taskName, tenantId, ex);
            }
        }
        return total;
    }

    /** Bind one tenant and run the work in its own transaction. */
    public Integer inTenant(UUID tenantId, Function<UUID, Integer> work) {
        TenantContext.set(new TenantContext.Principal(tenantId, SYSTEM_ACTOR_ID, "TENANT_ADMIN",
                "Axiom scheduled task", SYSTEM_ACTOR_EMAIL));
        try {
            return tx.execute(status -> {
                // TenantSessionAspect binds this for @Transactional service calls; a
                // programmatic transaction has no join point, so it is bound here.
                jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
                return work.apply(tenantId);
            });
        } finally {
            TenantContext.clear();
        }
    }
}

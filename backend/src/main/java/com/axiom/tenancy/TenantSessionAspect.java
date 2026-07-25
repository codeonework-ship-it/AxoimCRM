package com.axiom.tenancy;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sets the Postgres session variable {@code app.tenant_id} inside every
 * transactional service call, so the row-level security policies in
 * V1__baseline.sql filter on it — the independent second enforcement layer
 * of ADR-001 (defence in depth: an application bug that forgets a tenant
 * predicate becomes a zero-row result, not a cross-tenant leak).
 *
 * SET LOCAL scopes the variable to the current transaction, so a pooled
 * connection returned to Hikari carries no residual tenant identity.
 */
@Aspect
@Component
@Order(10) // inside the transaction: @Transactional's interceptor has lower default order
public class TenantSessionAspect {

    private final JdbcTemplate jdbc;

    public TenantSessionAspect(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Around("@within(org.springframework.transaction.annotation.Transactional) || @annotation(org.springframework.transaction.annotation.Transactional)")
    public Object bindTenantToTransaction(ProceedingJoinPoint pjp) throws Throwable {
        if (TenantContext.isBound()) {
            // set_config with is_local=true == SET LOCAL: dies with the transaction.
            jdbc.query("select set_config('app.tenant_id', ?, true)",
                    rs -> null,
                    TenantContext.get().tenantId().toString());
        }
        return pjp.proceed();
    }
}

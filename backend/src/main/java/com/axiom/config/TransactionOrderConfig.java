package com.axiom.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Pins the transaction advisor to order 0 so that {@code TenantSessionAspect}
 * (order 10) runs <em>inside</em> the transaction.
 *
 * Without this, Spring's transaction interceptor sits at
 * {@code Ordered.LOWEST_PRECEDENCE} (Integer.MAX_VALUE), which would place the
 * aspect (order 10) OUTSIDE the transaction — its
 * {@code set_config('app.tenant_id', ?, true)} would then execute on an
 * autocommit connection before the transaction even opened, the SET LOCAL
 * would evaporate immediately, and every RLS-filtered query would return zero
 * rows. With the advisor at order 0 the chain is:
 * transaction begins → aspect binds app.tenant_id on the tx connection →
 * business code runs with RLS scoped to the tenant (ADR-001).
 */
@Configuration
@EnableTransactionManagement(order = 0)
public class TransactionOrderConfig {
}

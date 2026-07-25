package com.axiom.accounts;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Binds {@code app.actor_id} for the current transaction.
 *
 * <p>The field-history trigger installed by V40 attributes every notable
 * account/contact change to an actor. Reading the actor from a transaction-local
 * GUC rather than from a method argument is deliberate: a caller that has to
 * remember to pass the actor is a caller that will eventually forget, and the
 * change nobody attributed is always the one that matters.
 *
 * <p>SET LOCAL semantics mirror {@code TenantSessionAspect} — the value dies
 * with the transaction, so a pooled connection carries no residual identity.
 * Note the GUC reverts to the EMPTY STRING, not NULL, which is why the trigger
 * reads it through {@code nullif(..., '')}.
 */
@Component
public class ActorSession {

    private final JdbcTemplate jdbc;

    public ActorSession(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Call at the top of every mutating service method. */
    public void bind() {
        if (!TenantContext.isBound()) return;
        jdbc.query("select set_config('app.actor_id', ?, true)", rs -> null,
                TenantContext.get().userId().toString());
    }
}

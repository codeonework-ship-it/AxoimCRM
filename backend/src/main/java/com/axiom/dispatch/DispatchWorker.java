package com.axiom.dispatch;

import com.axiom.integration.DispatchResult;
import com.axiom.security.SystemTaskRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The dispatch worker (system design §7, "worker tier").
 *
 * <p>Runs in three phases per tenant, and the phase split is the whole point:
 *
 * <ol>
 *   <li><b>Ingest</b> (transaction) — outbox rows become deliveries.</li>
 *   <li><b>Claim</b> (transaction) — due deliveries are leased and the breaker
 *       is consulted.</li>
 *   <li><b>Attempt</b> (NO transaction) then <b>record</b> (transaction per
 *       delivery) — the external call happens with no database connection held,
 *       and each result is recorded independently so one connector's failure
 *       cannot roll back another's success.</li>
 * </ol>
 *
 * <p>Tenant binding is delegated to {@link SystemTaskRunner}, which already
 * solves "a scheduled task has no request and therefore no RLS GUC" for four
 * other sweeps. Re-implementing it here would be a fifth place to forget it.
 *
 * <p>A single {@link AtomicBoolean} guard keeps overlapping ticks out: the
 * scheduler's fixed delay is measured from completion, but a manual run through
 * the API can arrive at any time.
 */
@Component
public class DispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(DispatchWorker.class);

    private final SystemTaskRunner systemTasks;
    private final DispatchService dispatch;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DispatchWorker(SystemTaskRunner systemTasks, DispatchService dispatch,
                          @Value("${axiom.dispatch.enabled:true}") boolean enabled) {
        this.systemTasks = systemTasks;
        this.dispatch = dispatch;
        this.enabled = enabled;
    }

    public record TickResult(int queued, int attempted, int succeeded, int failed) {}

    @Scheduled(fixedDelayString = "${axiom.dispatch.poll-fixed-delay-ms:3000}",
               initialDelayString = "${axiom.dispatch.initial-delay-ms:15000}")
    public void tick() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            for (UUID tenantId : systemTasks.tenantIds()) {
                try {
                    TickResult result = runForTenant(tenantId);
                    if (result.queued() > 0 || result.attempted() > 0) {
                        log.info("Dispatch tick tenant={} queued={} attempted={} succeeded={} failed={}",
                                tenantId, result.queued(), result.attempted(), result.succeeded(), result.failed());
                    }
                } catch (RuntimeException ex) {
                    // One tenant's failure must not stop the fleet.
                    log.error("Dispatch tick failed for tenant {}", tenantId, ex);
                }
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * One tenant's tick. Also reachable from the API so an administrator can
     * force a drain after fixing an endpoint rather than waiting for the poll.
     */
    public TickResult runForTenant(UUID tenantId) {
        int[] queued = {0};
        systemTasks.inTenant(tenantId, id -> {
            queued[0] = dispatch.ingest();
            return queued[0];
        });

        List<ClaimedDelivery> claimed = new ArrayList<>();
        systemTasks.inTenant(tenantId, id -> {
            claimed.addAll(dispatch.claimDue());
            return claimed.size();
        });
        if (claimed.isEmpty()) {
            return new TickResult(queued[0], 0, 0, 0);
        }

        int succeeded = 0;
        int failed = 0;
        for (ClaimedDelivery delivery : claimed) {
            // OUTSIDE any transaction: this is the network call.
            DispatchResult result = dispatch.attempt(delivery);
            if (result.success()) succeeded++;
            else failed++;
            final DispatchResult recorded = result;
            systemTasks.inTenant(tenantId, id -> {
                dispatch.recordOutcome(delivery, recorded);
                return 1;
            });
        }
        return new TickResult(queued[0], claimed.size(), succeeded, failed);
    }
}

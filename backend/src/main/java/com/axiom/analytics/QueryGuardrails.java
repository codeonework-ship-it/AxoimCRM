package com.axiom.analytics;

import com.axiom.common.ConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Query guardrails (FR-RPT-011, ADR-008 decision 6, doc 14 §9).
 *
 * <h2>The asymmetry this exists to fix</h2>
 * ADR-008 states the failure mode precisely: "the person who authored the
 * expensive report experiences a slow report, while everyone else experiences a
 * slow product." Guardrails do not make a broad report fast. They make its cost
 * land on its author — a bounded slow report for them, nothing at all for anyone
 * else, in their tenant or in a neighbouring one.
 *
 * <h2>Three controls, deliberately different in kind</h2>
 * <ol>
 *   <li><b>Statement timeout</b> — a wall clock on the database side, applied as
 *       {@code SET LOCAL} so it cannot leak onto the next user of a pooled
 *       connection. Bounds a query that plans badly.</li>
 *   <li><b>Row cap</b> — bounds a query that plans <em>well</em> over far too much
 *       data. Enforced by asking for one row more than the cap: that is what makes
 *       "there are more" distinguishable from "that was all", without a second
 *       counting query over the same predicate.</li>
 *   <li><b>Per-tenant concurrency</b> — bounds the aggregate. Timeouts and caps
 *       limit one report; twenty simultaneous capped reports still saturate the
 *       pool, and the pool is shared with the interactive product.</li>
 * </ol>
 *
 * <h2>Refusal has to be actionable</h2>
 * FR-RPT-011 requires "a clear message and guidance on narrowing". A bare
 * "query too large" tells the author nothing they can act on, so every message
 * here names the limit that was hit and the specific next step — add a date
 * range, group instead of listing, filter by owner.
 */
@Component
public class QueryGuardrails {

    private final int defaultRowLimit;
    private final int maxRowLimit;
    private final int statementTimeoutMs;
    private final int maxConcurrentPerTenant;

    private final Map<UUID, Semaphore> permits = new ConcurrentHashMap<>();

    /**
     * Annotated because this bean also has a package-private no-tuning constructor
     * for tests. With two constructors and no annotation Spring looks for a no-arg
     * one, does not find it, and the context fails to start.
     */
    @Autowired
    public QueryGuardrails(@Value("${axiom.analytics.row-limit-default:2000}") int defaultRowLimit,
                           @Value("${axiom.analytics.row-limit-max:10000}") int maxRowLimit,
                           @Value("${axiom.analytics.statement-timeout-ms:15000}") int statementTimeoutMs,
                           @Value("${axiom.analytics.max-concurrent-queries:4}") int maxConcurrentPerTenant) {
        this.defaultRowLimit = Math.max(1, defaultRowLimit);
        this.maxRowLimit = Math.max(this.defaultRowLimit, maxRowLimit);
        this.statementTimeoutMs = Math.max(1000, statementTimeoutMs);
        this.maxConcurrentPerTenant = Math.max(1, maxConcurrentPerTenant);
    }

    /** Test seam with the shipped defaults. */
    QueryGuardrails() {
        this(2000, 10000, 15000, 4);
    }

    public int statementTimeoutMs() { return statementTimeoutMs; }

    public int maxRowLimit() { return maxRowLimit; }

    public int maxConcurrentPerTenant() { return maxConcurrentPerTenant; }

    /** A requested limit, clamped. A caller asking for a million rows gets the cap, not an error. */
    public int effectiveRowLimit(Integer requested) {
        if (requested == null) return defaultRowLimit;
        return Math.min(Math.max(requested, 1), maxRowLimit);
    }

    /**
     * The message shown when a result was cut off at the cap.
     *
     * <p>Returned <em>with</em> the truncated rows rather than in place of them.
     * An author who asked a broad question is usually about to narrow it, and the
     * first page tells them how — an error page tells them nothing.
     */
    public String truncationGuidance(AnalyticsDataset dataset, int limit) {
        return "This report reached the " + limit + "-row limit and was cut off, so totals below cover "
                + "only the rows shown. Narrow it before relying on the figures: add a date range "
                + "(for example the current quarter), filter by owner, stage or account, or switch to a "
                + "SUMMARY report grouped by " + suggestedGroup(dataset)
                + " — a grouped report summarises every matching row without listing them.";
    }

    /**
     * Acquire a per-tenant query permit.
     *
     * <p>{@code tryAcquire} with no wait, not a blocking acquire: a queued report
     * still holds a request thread, and a thread waiting on a semaphore is
     * indistinguishable to the user from a product that has stopped responding.
     * Refusing immediately with a concrete "try again" is both cheaper and more
     * honest.
     */
    public Permit acquire(UUID tenantId) {
        Semaphore semaphore = permits.computeIfAbsent(tenantId, id -> new Semaphore(maxConcurrentPerTenant));
        if (!semaphore.tryAcquire()) {
            throw new ConflictException("Your tenant already has " + maxConcurrentPerTenant
                    + " reports running, which is the concurrent-report limit. This limit exists so a"
                    + " report cannot slow the rest of the product. Wait for one to finish, or narrow"
                    + " this report with a date range or an owner filter so it completes faster.");
        }
        return new Permit(semaphore);
    }

    /** Released in a try-with-resources block, so an exception cannot leak a permit. */
    public static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private boolean released;

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                semaphore.release();
            }
        }
    }

    private static String suggestedGroup(AnalyticsDataset dataset) {
        return switch (dataset) {
            case OPPORTUNITY -> "stageName or ownerName";
            case LEAD -> "status or source";
            case ACTIVITY -> "activityType or ownerName";
            case ACCOUNT -> "industry or territory";
        };
    }
}

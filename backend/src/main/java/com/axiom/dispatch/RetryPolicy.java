package com.axiom.dispatch;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bounded exponential backoff with jitter (FR-INT-005).
 *
 * <p>Pure and deterministic apart from the jitter sample, which is passed in by
 * the caller in the deterministic overload so the schedule is testable rather
 * than merely observable.
 *
 * <p>Jitter is not decoration. Without it, a receiver that went down while a
 * hundred deliveries were in flight gets all hundred retries back in the same
 * millisecond, repeatedly, which is how a recovering endpoint gets knocked over
 * again by its own clients.
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final long baseDelayMs;
    private final double multiplier;
    private final long maxDelayMs;
    private final double jitterRatio;

    public RetryPolicy(int maxAttempts, long baseDelayMs, double multiplier, long maxDelayMs, double jitterRatio) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
        if (baseDelayMs < 1) throw new IllegalArgumentException("baseDelayMs must be positive");
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be at least 1.0");
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.multiplier = multiplier;
        this.maxDelayMs = Math.max(baseDelayMs, maxDelayMs);
        this.jitterRatio = Math.min(Math.max(jitterRatio, 0.0), 0.9);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** @param attemptsMade attempts already consumed; true while another is permitted. */
    public boolean canRetry(int attemptsMade) {
        return attemptsMade < maxAttempts;
    }

    /** Backoff before attempt {@code attemptsMade + 1}, before jitter, capped. */
    public Duration baseBackoff(int attemptsMade) {
        int exponent = Math.max(0, attemptsMade - 1);
        double raw = baseDelayMs * Math.pow(multiplier, exponent);
        long capped = raw >= maxDelayMs ? maxDelayMs : (long) raw;
        return Duration.ofMillis(Math.max(baseDelayMs, capped));
    }

    /** @param jitterSample uniform in [0,1); 0.5 means "no jitter". */
    public Duration backoff(int attemptsMade, double jitterSample) {
        long base = baseBackoff(attemptsMade).toMillis();
        double factor = 1.0 + ((jitterSample * 2.0) - 1.0) * jitterRatio;
        long jittered = Math.round(base * factor);
        return Duration.ofMillis(Math.max(1, Math.min(jittered, maxDelayMs)));
    }

    public Duration backoff(int attemptsMade) {
        return backoff(attemptsMade, ThreadLocalRandom.current().nextDouble());
    }
}

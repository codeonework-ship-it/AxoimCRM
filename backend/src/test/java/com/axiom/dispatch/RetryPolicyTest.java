package com.axiom.dispatch;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-INT-005: retry with exponential backoff, bounded. */
class RetryPolicyTest {

    @Test void backoffIncreasesWithEachAttemptAndIsCapped() {
        RetryPolicy policy = new RetryPolicy(5, 1000, 3.0, 20_000, 0.0);

        List<Long> schedule = new ArrayList<>();
        for (int attemptsMade = 1; attemptsMade <= 5; attemptsMade++) {
            schedule.add(policy.baseBackoff(attemptsMade).toMillis());
        }

        assertEquals(List.of(1000L, 3000L, 9000L, 20_000L, 20_000L), schedule,
                "1s, 3s, 9s, then capped at the configured ceiling");
        for (int i = 1; i < schedule.size(); i++) {
            assertTrue(schedule.get(i) >= schedule.get(i - 1), "the sequence must never decrease");
        }
        assertTrue(schedule.stream().allMatch(ms -> ms <= 20_000L), "no delay may exceed the cap");
    }

    @Test void attemptsAreBounded() {
        RetryPolicy policy = new RetryPolicy(3, 1000, 2.0, 60_000, 0.0);

        assertTrue(policy.canRetry(0));
        assertTrue(policy.canRetry(1));
        assertTrue(policy.canRetry(2));
        assertFalse(policy.canRetry(3), "the third attempt exhausts a 3-attempt budget");
        assertFalse(policy.canRetry(99));
        assertEquals(3, policy.maxAttempts());
    }

    @Test void jitterStaysInsideTheConfiguredBandAndNeverExceedsTheCap() {
        RetryPolicy policy = new RetryPolicy(6, 1000, 2.0, 10_000, 0.25);

        Duration low = policy.backoff(3, 0.0);
        Duration mid = policy.backoff(3, 0.5);
        Duration high = policy.backoff(3, 1.0);

        assertEquals(4000L, mid.toMillis(), "a 0.5 sample is the un-jittered value");
        assertEquals(3000L, low.toMillis(), "-25%");
        assertEquals(5000L, high.toMillis(), "+25%");
        assertTrue(policy.backoff(20, 1.0).toMillis() <= 10_000L, "jitter must not push past the cap");
    }

    @Test void rejectsNonsenseConfiguration() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(0, 1000, 2.0, 5000, 0.1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, 0, 2.0, 5000, 0.1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RetryPolicy(3, 1000, 0.5, 5000, 0.1));
    }
}

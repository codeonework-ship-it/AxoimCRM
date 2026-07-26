package com.axiom.integration;

/**
 * What an adapter reports back, in our vocabulary rather than the vendor's.
 *
 * <p>The three outcomes are the only distinction the engine needs, and the
 * distinction that matters is <em>retryable versus not</em>. An adapter that
 * cannot tell must answer {@link Outcome#RETRYABLE_FAILURE} — retrying a
 * permanent failure wastes attempts and ends in the dead-letter queue, which is
 * visible; treating a transient failure as permanent loses the delivery
 * quietly, which is exactly what {@code FR-INT-009} calls a defect.
 */
public record DispatchResult(Outcome outcome, Integer httpStatus, String responseExcerpt,
                             String error, long durationMs) {

    public enum Outcome {
        /** The external system accepted it. */
        SUCCESS,
        /** Transient: timeout, connection failure, 5xx, 429. Retry with backoff. */
        RETRYABLE_FAILURE,
        /** Rejected on its merits: 4xx, unresolvable configuration, no adapter. Dead-letter it. */
        PERMANENT_FAILURE
    }

    private static final int EXCERPT_LIMIT = 500;

    public boolean success() {
        return outcome == Outcome.SUCCESS;
    }

    public boolean retryable() {
        return outcome == Outcome.RETRYABLE_FAILURE;
    }

    public static DispatchResult success(Integer httpStatus, String responseExcerpt, long durationMs) {
        return new DispatchResult(Outcome.SUCCESS, httpStatus, excerpt(responseExcerpt), null, durationMs);
    }

    public static DispatchResult retryable(Integer httpStatus, String responseExcerpt, String error, long durationMs) {
        return new DispatchResult(Outcome.RETRYABLE_FAILURE, httpStatus, excerpt(responseExcerpt), error, durationMs);
    }

    public static DispatchResult permanent(Integer httpStatus, String responseExcerpt, String error, long durationMs) {
        return new DispatchResult(Outcome.PERMANENT_FAILURE, httpStatus, excerpt(responseExcerpt), error, durationMs);
    }

    /** Bodies from external systems can be megabytes; the trace stores a bounded excerpt. */
    public static String excerpt(String body) {
        if (body == null) return null;
        String trimmed = body.strip();
        return trimmed.length() <= EXCERPT_LIMIT ? trimmed : trimmed.substring(0, EXCERPT_LIMIT) + "...";
    }
}

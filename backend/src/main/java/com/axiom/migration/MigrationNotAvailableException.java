package com.axiom.migration;

/**
 * A capability whose contract exists but whose implementation is deliberately
 * deferred — surfaced as HTTP 501 with the reason named.
 *
 * <p>Lives in this module rather than {@code com.axiom.common} because it is
 * this module's honesty mechanism, not a platform concern: the three vendor
 * adapters publish their shape and refuse to fake a round-trip they have never
 * performed. A caller gets a specific sentence about what is missing and what to
 * use instead, which is the difference between a documented boundary and a bug.
 */
public class MigrationNotAvailableException extends RuntimeException {

    public MigrationNotAvailableException(String message) {
        super(message);
    }
}

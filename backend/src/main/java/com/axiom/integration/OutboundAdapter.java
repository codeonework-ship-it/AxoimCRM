package com.axiom.integration;

/**
 * The outbound capability contract (ADR-007 rule 2).
 *
 * <p>Deliberately one method. Adding a vendor is a new implementation of this
 * interface; it is never a change to this interface, because every capability
 * added here is a capability every existing adapter must then implement.
 *
 * <p>Implementations MUST NOT throw for an external failure — a failed external
 * call is a {@link DispatchResult}, not an exception. The engine treats an
 * escaping exception as a retryable failure, but an adapter that relies on that
 * has thrown away its own knowledge of whether the failure was retryable.
 */
public interface OutboundAdapter {

    /** Vendor key this adapter serves, e.g. {@code GENERIC_WEBHOOK}, {@code AXIOM_LOCAL_ERP}. */
    String vendor();

    /** Connector type this adapter serves, e.g. {@code WEBHOOK}, {@code ERP}, {@code CTRM}. */
    String connectorType();

    /** True when the adapter talks to a real external system rather than standing in for one. */
    default boolean live() {
        return true;
    }

    DispatchResult dispatch(DispatchEnvelope envelope, ConnectorTarget target);
}

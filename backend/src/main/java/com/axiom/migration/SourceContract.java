package com.axiom.migration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The anti-corruption contract for migration source systems (ADR-007).
 *
 * <p>ADR-007 puts every external system behind an adapter whose contract is
 * expressed in <em>our</em> vocabulary, so a vendor's model never leaks into the
 * core. That is doubly important here: Salesforce, Zoho and HubSpot disagree
 * about almost everything — what a "custom object" is, whether a field has a
 * label separate from its API name, how a parent reference is expressed — and a
 * migration engine written against any one of them is a migration engine for
 * that one vendor.
 *
 * <p>So the types below are deliberately thin and vendor-neutral. An object has
 * an api name, a label, a custom flag and a count. A record has a stable source
 * id, a flat map of values, and a map of references naming the source object it
 * points at. Everything a vendor does beyond that is the adapter's problem.
 *
 * <p>These are nested in one file because they are one contract: reading them
 * apart would be reading half a sentence.
 */
public final class SourceContract {

    private SourceContract() {}

    /**
     * Credentials for a source system.
     *
     * <p>Only {@code READ_ONLY} scope is ever requested, and the database
     * enforces it as a CHECK on {@code migration.source_connection.scope}. This
     * is a trust statement as much as a safety one: during a parallel run the
     * importer must be technically incapable of damaging the system the
     * customer is still operating on.
     *
     * <p>Secrets arrive here in plaintext from the request and leave encrypted
     * through {@link com.axiom.common.SecretCipher}; nothing in this module
     * persists or logs a raw credential.
     */
    public record SourceCredentials(String instanceUrl,
                                    String clientId,
                                    String clientSecret,
                                    String refreshToken,
                                    String fixtureKey) {}

    /**
     * Everything the engine needs to talk to one configured connection. Passed
     * per call rather than held on the adapter, so adapters stay stateless and
     * a single bean serves every tenant.
     *
     * @param fixtureWave fixture adapter only — the simulated passage of time in
     *                    the source system. See {@link FixtureSourceAdapter}.
     */
    public record SourceSession(UUID connectionId,
                                String vendor,
                                String instanceUrl,
                                String fixtureKey,
                                int fixtureWave,
                                SourceCredentials credentials) {}

    /** Result of a successful connect: who we are connected as, and what is there. */
    public record SourceHandshake(String vendor,
                                  String scope,
                                  String connectedAs,
                                  List<SourceObject> objects) {}

    /**
     * A source object. {@code proposedTarget} is the adapter's opinion about
     * which Axiom entity this object corresponds to — an opinion, because the
     * operator overrides it during mapping review.
     */
    public record SourceObject(String apiName,
                               String label,
                               boolean custom,
                               long recordCount,
                               String proposedTarget) {}

    /** A source field, in the source's own vocabulary. Translation happens later, visibly. */
    public record SourceField(String apiName,
                              String label,
                              String dataType,
                              boolean custom,
                              boolean nullable,
                              String sampleValue) {}

    /**
     * One source record.
     *
     * @param values     flat field-name to string-value map; the adapter has already
     *                   flattened whatever nesting the vendor used
     * @param references field name to the api name of the source object it points at,
     *                  e.g. {@code {"AccountId": "Account"}}. The value of the field
     *                  itself lives in {@code values}. Keeping the *target object* out
     *                  of the value is what lets relationship resolution name both
     *                  endpoints when it fails (FR-MIG-004).
     * @param actor      the source system's recorded actor, preserved as a recorded
     *                   value rather than mapped onto an Axiom user (FR-MIG-005)
     */
    public record SourceRecord(String objectApiName,
                               String sourceId,
                               String label,
                               Map<String, String> values,
                               Map<String, String> references,
                               Instant createdAt,
                               Instant modifiedAt,
                               String actor,
                               List<SourceAttachment> attachments) {}

    /**
     * An attachment on a source record. Axiom has no document-store epic yet, so
     * FR-MIG-005 is honoured for attachments as a catalogued reference with the
     * original metadata preserved — named, not silently dropped.
     */
    public record SourceAttachment(String fileName,
                                   String contentType,
                                   long byteSize,
                                   String externalRef,
                                   String author,
                                   Instant createdAt) {}

    /**
     * The port. Four operations, all read-only, all expressed in our vocabulary.
     *
     * <p>{@link #liveInteropAvailable()} is how an adapter says "the contract is
     * implemented, the vendor round-trip is not". A migration engine that
     * pretends to support a vendor it has never authenticated against is the
     * exact failure mode this module exists to prevent, so the answer is a
     * first-class part of the port rather than a comment.
     */
    public interface SourceAdapter {

        /** SALESFORCE | ZOHO | HUBSPOT | FIXTURE. Matches the DB CHECK. */
        String vendor();

        /** Human label for the vendor, shown in the connection picker. */
        String displayName();

        /**
         * False when the adapter can describe the vendor's shape but cannot yet
         * complete a live authenticated round-trip. Such an adapter refuses
         * {@link #connect} rather than degrading silently.
         */
        boolean liveInteropAvailable();

        /** The OAuth scopes this adapter requests — read-only by construction. */
        List<String> requestedScopes();

        /** Verify credentials and enumerate available objects with record counts. */
        SourceHandshake connect(SourceSession session);

        /** Objects available on this connection, including custom objects. */
        List<SourceObject> objects(SourceSession session);

        /** Fields of one object, including custom fields. */
        List<SourceField> fields(SourceSession session, String objectApiName);

        /**
         * Records of one object.
         *
         * @param modifiedSince null for a full read; otherwise only records the
         *                      source says changed after this instant (FR-MIG-008)
         */
        List<SourceRecord> records(SourceSession session, String objectApiName, Instant modifiedSince);
    }
}

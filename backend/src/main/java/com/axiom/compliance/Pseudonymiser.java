package com.axiom.compliance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Irreversible pseudonymisation for erasure (FR-AUD-008).
 *
 * <p>One instance per erasure run, holding a 32-byte salt generated at
 * construction and <b>never persisted</b>. That single decision is what makes the
 * result irreversible rather than merely obfuscated: a stored salt turns
 * "pseudonymised" into "encrypted with a key we kept", and an email address space
 * is small enough to brute-force from a known salt in minutes.
 *
 * <p>Within a run the mapping is deterministic, so a subject appearing in eight
 * tables gets the same token in all eight and referential joins survive. Across
 * runs it is not, which is fine — nothing needs to correlate two erasures of the
 * same subject, and if something did, that would itself be a re-identification
 * path.
 */
final class Pseudonymiser {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] salt = new byte[32];

    Pseudonymiser() {
        RANDOM.nextBytes(salt);
    }

    /** Stable per-run token for one subject. */
    String token(Object subjectKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(String.valueOf(subjectKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", ex);
        }
    }

    /**
     * The replacement value for one column, shaped so the column's own constraints
     * still hold after erasure — an email column keeps a syntactically valid
     * address in the reserved {@code .invalid} TLD (RFC 2606) so a NOT NULL or
     * format constraint does not turn erasure into a failed transaction.
     *
     * @param dataType the column's {@code information_schema} data type
     * @return the value to write, or null when the column has no safe placeholder
     */
    String replacement(String columnName, String dataType, String token) {
        String column = columnName == null ? "" : columnName.toLowerCase(Locale.ROOT);
        String type = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
        if (type.equals("jsonb") || type.equals("json")) {
            return "{\"erased\":true,\"pseudonym\":\"" + token + "\"}";
        }
        if (!type.contains("char") && !type.equals("text")) {
            // Numeric, temporal, boolean and network columns have no safe textual
            // placeholder. Null them and let the caller report a null-rejecting
            // column as a failure rather than guessing a value.
            return null;
        }
        if (column.contains("email")) {
            return "erased-" + token + "@erased.invalid";
        }
        return "erased-" + token;
    }
}

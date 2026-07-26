package com.axiom.integration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * HMAC-SHA256 request signing for outbound webhooks (FR-INT-005).
 *
 * <p>The signature is computed over the <b>raw request body bytes</b> — exactly
 * the bytes put on the wire — and never over a re-serialised object. Signing a
 * re-serialisation is the classic way to ship a signature the receiver cannot
 * reproduce: key order, whitespace and number formatting all differ between two
 * serialisations of the same object, and the receiver only has the bytes.
 *
 * <p>Header format is {@code sha256=<lowercase hex>}, which is what the common
 * receiver libraries already expect.
 */
public final class WebhookSignature {

    public static final String HEADER = "X-Axiom-Signature";
    private static final String ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private WebhookSignature() {}

    /** @param rawBody the exact bytes that will be sent as the request body */
    public static String sign(String secret, byte[] rawBody) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("A webhook signing secret is required");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return "sha256=" + hex(mac.doFinal(rawBody));
        } catch (Exception ex) {
            throw new IllegalStateException("Webhook signature could not be computed", ex);
        }
    }

    public static String sign(String secret, String rawBody) {
        return sign(secret, rawBody.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Constant-time comparison. A receiver implementation lives on the other
     * side of this, but the verifier is here too so our own tests prove the
     * signature is reproducible from the raw body alone.
     */
    public static boolean verify(String secret, byte[] rawBody, String presentedSignature) {
        if (presentedSignature == null) return false;
        String expected = sign(secret, rawBody);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presentedSignature.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }
}

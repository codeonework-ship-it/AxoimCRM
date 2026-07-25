package com.axiom.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * RFC 6238 time-based one-time passwords, implemented directly on
 * {@code javax.crypto} — no new dependency was added for this.
 *
 * <p>HMAC-SHA1, 30-second steps, 6 digits: the combination every authenticator
 * app (Google Authenticator, Microsoft Authenticator, 1Password, Authy) assumes
 * when it is handed an {@code otpauth://totp/} URI without explicit parameters.
 * Choosing SHA-256 here would be cryptographically tidier and would silently
 * fail against several of those apps, which is the wrong trade for a second
 * factor whose whole value is that the user can actually enrol it.
 *
 * <p>Verification accepts a small window either side of the current step, so a
 * user whose phone clock drifts by a few seconds is not locked out. The window
 * is deliberately small: every extra step widens the replay opportunity.
 */
public final class TotpGenerator {

    public static final int STEP_SECONDS = 30;
    public static final int DIGITS = 6;
    /** Steps of tolerance either side of "now" — 1 gives a ±30s window. */
    public static final int SKEW_STEPS = 1;

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpGenerator() {}

    /** @return a fresh 160-bit shared secret, base32 encoded (no padding). */
    public static String newSecretBase32() {
        byte[] raw = new byte[20];
        RANDOM.nextBytes(raw);
        return base32Encode(raw);
    }

    /** The 6-digit code for {@code secretBase32} at {@code at}. */
    public static String codeAt(String secretBase32, Instant at) {
        return codeForStep(base32Decode(secretBase32), Math.floorDiv(at.getEpochSecond(), STEP_SECONDS));
    }

    /**
     * Constant-time-compares {@code candidate} against every code inside the
     * accepted skew window.
     *
     * @return true when the code is valid for some step in the window
     */
    public static boolean verify(String secretBase32, String candidate, Instant at) {
        if (candidate == null) return false;
        String cleaned = candidate.replaceAll("[^0-9]", "");
        if (cleaned.length() != DIGITS) return false;
        byte[] secret = base32Decode(secretBase32);
        long step = Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
        boolean matched = false;
        for (long offset = -SKEW_STEPS; offset <= SKEW_STEPS; offset++) {
            // No early exit: keep the comparison count independent of where the
            // match falls in the window.
            matched |= constantTimeEquals(codeForStep(secret, step + offset), cleaned);
        }
        return matched;
    }

    /** The {@code otpauth://} provisioning URI an authenticator app scans. */
    public static String provisioningUri(String issuer, String accountEmail, String secretBase32) {
        String label = urlEncode(issuer) + ":" + urlEncode(accountEmail);
        return "otpauth://totp/" + label
                + "?secret=" + secretBase32
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1"
                + "&digits=" + DIGITS
                + "&period=" + STEP_SECONDS;
    }

    private static String codeForStep(byte[] secret, long step) {
        byte[] counter = new byte[8];
        long value = step;
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (value & 0xff);
            value >>>= 8;
        }
        byte[] hmac;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            hmac = mac.doFinal(counter);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA1 is unavailable on this JVM", ex);
        }
        int offset = hmac[hmac.length - 1] & 0x0f;
        int binary = ((hmac[offset] & 0x7f) << 24)
                | ((hmac[offset + 1] & 0xff) << 16)
                | ((hmac[offset + 2] & 0xff) << 8)
                | (hmac[offset + 3] & 0xff);
        int modulo = (int) Math.pow(10, DIGITS);
        return String.format("%0" + DIGITS + "d", binary % modulo);
    }

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >>> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    static byte[] base32Decode(String encoded) {
        String cleaned = encoded.trim().replace("=", "").replace(" ", "").toUpperCase();
        int buffer = 0;
        int bits = 0;
        byte[] out = new byte[cleaned.length() * 5 / 8];
        int index = 0;
        for (char c : cleaned.toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Secret is not valid base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >>> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

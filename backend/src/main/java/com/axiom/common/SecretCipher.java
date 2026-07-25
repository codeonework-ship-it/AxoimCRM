package com.axiom.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Envelope encryption for secrets that must be recoverable in plaintext to be
 * used — TOTP shared secrets and OIDC client secrets. Hashing is not an option
 * for these: verifying a TOTP code requires the original secret.
 *
 * <p>AES-256-GCM, random 12-byte nonce per value, nonce prepended to the
 * ciphertext and the whole thing base64-encoded into a {@code text} column. GCM
 * is authenticated, so a tampered column value fails to decrypt rather than
 * silently yielding garbage.
 *
 * <p><b>Stated honestly:</b> the key is derived from configuration
 * ({@code axiom.identity.secret-key}, defaulting to the JWT signing secret so a
 * development stack works out of the box). System design §9 requires a managed
 * secret store for production key material; wiring this class to a KMS is a
 * configuration change here, not a redesign, but it has NOT been done. Rotation
 * is likewise not implemented — a key change invalidates existing ciphertexts,
 * which for TOTP means re-enrolment.
 */
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${axiom.identity.secret-key:${axiom.jwt.secret}}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalStateException(
                    "axiom.identity.secret-key is not configured. Multi-factor secrets and identity "
                    + "provider client secrets cannot be stored without an encryption key.");
        }
        this.key = new SecretKeySpec(sha256(configuredKey), "AES");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", ex);
        }
    }

    /** @return base64(nonce || ciphertext||tag), or null when {@code plaintext} is null. */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(ciphertext, 0, envelope, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException("Secret could not be encrypted", ex);
        }
    }

    public String decrypt(String envelopeBase64) {
        if (envelopeBase64 == null) return null;
        try {
            byte[] envelope = Base64.getDecoder().decode(envelopeBase64);
            if (envelope.length <= NONCE_BYTES) {
                throw new IllegalStateException("Stored secret is truncated");
            }
            byte[] nonce = Arrays.copyOfRange(envelope, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(envelope, NONCE_BYTES, envelope.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            // Never echo the ciphertext or the key in the message.
            throw new IllegalStateException("Stored secret could not be decrypted with the configured key", ex);
        }
    }
}

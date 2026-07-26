package com.axiom.dispatch;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Credential entry (FR-INT-007). The secret is write-only: it enters here and
 * is never returned by any read endpoint, so a rotation is a new value, never
 * an edit of a value the screen showed back to the administrator.
 */
public record CredentialRequest(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.-]{2,63}$",
                 message = "Credential name must start with a letter and use letters, digits, dot, dash or underscore")
        String name,

        @NotBlank
        @Pattern(regexp = "WEBHOOK_SIGNING_SECRET|BEARER_TOKEN|API_KEY|BASIC_AUTH|MTLS_KEYPAIR",
                 message = "Unsupported credential type")
        String credentialType,

        @NotBlank
        @Size(min = 8, max = 4096, message = "A credential secret must be at least 8 characters")
        String secret,

        @Size(max = 500)
        String description) {
}

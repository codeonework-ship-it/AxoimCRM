package com.axiom.dispatch;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** New value for an existing named credential (FR-INT-007 rotation). */
public record SecretRotation(
        @NotBlank
        @Size(min = 8, max = 4096, message = "A credential secret must be at least 8 characters")
        String secret) {
}

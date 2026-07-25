package com.axiom.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the fix for the audit finding that {@code application.yml} falls back to a
 * committed signing key. If that key reaches a production-like profile, anyone holding
 * the repository can forge a SUPER_ADMIN token for any tenant, which voids the ADR-001
 * guarantee that no client-supplied tenant identity is trusted.
 */
class JwtSecretValidationTest {

    private static final String STRONG_SECRET = "a-real-secret-that-is-long-enough-to-be-safe";

    @Test
    @DisplayName("production-like profile refuses to start on the committed dev key")
    void rejectsDevFallbackInProduction() {
        assertThatThrownBy(() ->
                JwtService.assertSecretIsSafe(JwtService.DEV_FALLBACK_SECRET, List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AXIOM_JWT_SECRET")
                .hasMessageContaining("forge");
    }

    @Test
    @DisplayName("qa and uat are production-like — they are real deployments on real data")
    void rejectsDevFallbackInQaAndUat() {
        assertThatThrownBy(() -> JwtService.assertSecretIsSafe(JwtService.DEV_FALLBACK_SECRET, List.of("qa")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtService.assertSecretIsSafe(JwtService.DEV_FALLBACK_SECRET, List.of("uat")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("blank or missing secret is refused in production-like profiles")
    void rejectsBlankSecret() {
        assertThatThrownBy(() -> JwtService.assertSecretIsSafe(null, List.of("prod")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtService.assertSecretIsSafe("   ", List.of("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a secret shorter than 256 bits is refused — HS256 requires it")
    void rejectsShortSecret() {
        String tooShort = "x".repeat(JwtService.MIN_SECRET_BYTES - 1);
        assertThatThrownBy(() -> JwtService.assertSecretIsSafe(tooShort, List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least " + JwtService.MIN_SECRET_BYTES + " bytes");
    }

    @Test
    @DisplayName("dev and test may keep the convenience default")
    void allowsDevFallbackInDevAndTest() {
        assertThatCode(() -> JwtService.assertSecretIsSafe(JwtService.DEV_FALLBACK_SECRET, List.of("dev")))
                .doesNotThrowAnyException();
        assertThatCode(() -> JwtService.assertSecretIsSafe(JwtService.DEV_FALLBACK_SECRET, List.of("test")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a strong secret is accepted everywhere")
    void acceptsStrongSecret() {
        assertThatCode(() -> JwtService.assertSecretIsSafe(STRONG_SECRET, List.of("prod")))
                .doesNotThrowAnyException();
        assertThat(STRONG_SECRET.getBytes()).hasSizeGreaterThanOrEqualTo(JwtService.MIN_SECRET_BYTES);
    }
}

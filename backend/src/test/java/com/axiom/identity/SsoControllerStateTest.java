package com.axiom.identity;

import com.axiom.common.UnauthorizedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SsoControllerStateTest {
    @Test void browserBindingAcceptsOnlyTheOriginatingState() {
        String state = "meridian.opaque-state";
        String digest = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash(state));
        assertDoesNotThrow(() -> SsoController.requireBrowserState(state, digest));
        assertThrows(UnauthorizedException.class,
                () -> SsoController.requireBrowserState("meridian.attacker", digest));
        assertThrows(UnauthorizedException.class,
                () -> SsoController.requireBrowserState(state, null));
    }

    private static byte[] hash(String value) {
        try { return java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}

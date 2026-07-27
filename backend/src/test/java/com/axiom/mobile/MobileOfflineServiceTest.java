package com.axiom.mobile;

import com.axiom.common.ConflictException;
import com.axiom.security.SecurableObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MobileOfflineServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void offlinePatchAllowsOnlyExplicitBusinessFields() throws Exception {
        assertDoesNotThrow(() -> MobileOfflineService.validatePatch(
                SecurableObject.ACCOUNT, json.readTree("{\"name\":\"North Star\",\"industry\":\"Energy\"}")));
        assertThrows(ConflictException.class, () -> MobileOfflineService.validatePatch(
                SecurableObject.ACCOUNT, json.readTree("{\"ownerId\":\"00000000-0000-0000-0000-000000000001\"}")));
        assertThrows(ConflictException.class, () -> MobileOfflineService.validatePatch(
                SecurableObject.ACCOUNT, json.readTree("{}")));
    }
}

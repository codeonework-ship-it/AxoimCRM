package com.axiom.identity;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityCertificationServiceTest {
    @Test void certificationCannotPassWithMissingExternalEvidence() {
        Map<String, Boolean> evidence = new HashMap<>();
        IdentityCertificationService.REQUIRED.forEach(key -> evidence.put(key, true));
        evidence.put("scimGroupMembership", false);
        assertEquals(java.util.List.of("scimGroupMembership"), IdentityCertificationService.missing(evidence));
    }

    @Test void completeEvidenceSetHasNoMissingControls() {
        Map<String, Boolean> evidence = new HashMap<>();
        IdentityCertificationService.REQUIRED.forEach(key -> evidence.put(key, true));
        assertTrue(IdentityCertificationService.missing(evidence).isEmpty());
    }
}

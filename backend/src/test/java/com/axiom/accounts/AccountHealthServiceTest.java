package com.axiom.accounts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccountHealthServiceTest {
    @Test void healthBandsUseStableBusinessThresholds() {
        assertEquals("STRONG", AccountHealthService.band(80));
        assertEquals("STEADY", AccountHealthService.band(65));
        assertEquals("WATCH", AccountHealthService.band(50));
        assertEquals("AT_RISK", AccountHealthService.band(35));
        assertEquals("CRITICAL", AccountHealthService.band(34));
    }
}

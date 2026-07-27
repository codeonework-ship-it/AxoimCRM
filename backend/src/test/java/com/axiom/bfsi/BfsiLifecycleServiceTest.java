package com.axiom.bfsi;

import com.axiom.common.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BfsiLifecycleServiceTest {
    @Test
    void weightedRiskIsDeterministicAndVisible() {
        var result = BfsiLifecycleService.calculateRisk(List.of(
                new BfsiLifecycleService.RiskFactor("Geography", new BigDecimal("40"), new BigDecimal("80"), "Country evidence"),
                new BfsiLifecycleService.RiskFactor("Activity", new BigDecimal("60"), new BigDecimal("50"), "Expected activity")));
        assertEquals(new BigDecimal("62.00"), result.score());
        assertEquals("HIGH", result.rating());
    }

    @Test
    void weightedRiskFailsClosedWhenWeightsDoNotBalance() {
        assertThrows(ConflictException.class, () -> BfsiLifecycleService.calculateRisk(List.of(
                new BfsiLifecycleService.RiskFactor("Incomplete", new BigDecimal("99"), BigDecimal.TEN, "Evidence"))));
    }
}

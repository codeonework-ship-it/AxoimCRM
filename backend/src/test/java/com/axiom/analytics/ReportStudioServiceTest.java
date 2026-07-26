package com.axiom.analytics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportStudioServiceTest {

    @Test
    void thresholdOperatorsUseExactDecimalComparison() {
        BigDecimal value = new BigDecimal("2.9999");
        BigDecimal boundary = new BigDecimal("3.0000");

        assertThat(ReportStudioService.thresholdMatches(value, "LT", boundary)).isTrue();
        assertThat(ReportStudioService.thresholdMatches(value, "GTE", boundary)).isFalse();
        assertThat(ReportStudioService.thresholdMatches(boundary, "EQ", boundary)).isTrue();
        assertThatThrownBy(() -> ReportStudioService.thresholdMatches(value, "EXEC", boundary))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scheduleCadenceAdvancesFromThePriorDueTime() {
        Instant due = Instant.parse("2026-07-26T10:00:00Z");

        assertThat(ReportStudioService.advance(due, "DAILY"))
                .isEqualTo(Instant.parse("2026-07-27T10:00:00Z"));
        assertThat(ReportStudioService.advance(due, "WEEKLY"))
                .isEqualTo(Instant.parse("2026-08-02T10:00:00Z"));
        assertThat(ReportStudioService.advance(due, "THRESHOLD"))
                .isEqualTo(Instant.parse("2026-07-26T10:15:00Z"));
    }
}

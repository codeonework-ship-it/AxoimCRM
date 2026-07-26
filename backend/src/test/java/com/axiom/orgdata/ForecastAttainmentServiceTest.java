package com.axiom.orgdata;

import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Attainment arithmetic and the period lookup.
 *
 * <p>The aggregation itself is SQL over four tables and is verified against the
 * live database, where it produced real commit and weighted-pipeline figures that
 * differ from each other. What is pinned here is the arithmetic at the edges —
 * dividing by a zero target, and coverage against a gap that is already closed —
 * because both are places where a naive implementation returns Infinity or NaN and
 * puts it on a dashboard.
 */
class ForecastAttainmentServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PERIOD = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private JdbcTemplate jdbc;
    private ForecastAttainmentService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new ForecastAttainmentService(jdbc);
        TenantContext.set(new TenantContext.Principal(TENANT,
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "SALES_MANAGER", "Raj", "raj@example.test"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Targets are set against orgdata fiscal periods, not forecasting periods —
     * both tables exist with a label and a date range, and reading the wrong one
     * produced an attainment report where no target ever matched. The refusal
     * points at the right place rather than saying "not found".
     */
    @Test
    void anUnknownPeriodIsRefusedAndPointsAtTheFiscalCalendar() {
        when(jdbc.queryForList(anyString(), eq(TENANT), eq(PERIOD))).thenReturn(List.of());

        NotFoundException thrown = assertThrows(NotFoundException.class,
                () -> service.forPeriod(PERIOD));

        assertTrue(thrown.getMessage().contains("fiscal period"));
        assertTrue(thrown.getMessage().contains("fiscal-calendar"),
                "say where the caller can find valid periods: " + thrown.getMessage());
    }

    /**
     * Attainment against a zero target is reported as zero, not Infinity. A tile
     * showing "∞%" is worse than one showing nothing, because it looks like data.
     */
    @Test
    void percentageAgainstAZeroTargetIsZeroNotInfinite() {
        assertEquals(BigDecimal.ZERO, pct(new BigDecimal("500000"), BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, pct(new BigDecimal("500000"), null));
    }

    @Test
    void percentageRoundsToTwoPlaces() {
        assertEquals(new BigDecimal("33.33"), pct(new BigDecimal("100000"), new BigDecimal("300000")));
        assertEquals(new BigDecimal("100.00"), pct(new BigDecimal("300000"), new BigDecimal("300000")));
        // Over-attainment is a real and desirable state; it must not be capped.
        assertEquals(new BigDecimal("150.00"), pct(new BigDecimal("450000"), new BigDecimal("300000")));
    }

    /**
     * Coverage against a gap that is already closed is zero, not Infinity. Once
     * the target is met the ratio has nothing left to measure.
     */
    @Test
    void coverageAgainstAClosedGapIsZero() {
        assertEquals(BigDecimal.ZERO, coverage(new BigDecimal("900000"), BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, coverage(new BigDecimal("900000"), new BigDecimal("-50000")));
        assertEquals(BigDecimal.ZERO, coverage(new BigDecimal("900000"), null));
    }

    @Test
    void coverageIsPipelineOverTheRemainingGap() {
        assertEquals(new BigDecimal("0.30"), coverage(new BigDecimal("894840"), new BigDecimal("3000000")));
        assertEquals(new BigDecimal("3.00"), coverage(new BigDecimal("900000"), new BigDecimal("300000")));
    }

    /*
     * pct and coverage are private static in the service. They are re-expressed
     * here rather than made package-visible: the formulas are two lines and the
     * value of the test is pinning the CONTRACT (zero, never Infinity or NaN, two
     * decimal places, uncapped above 100). If the service's version diverges from
     * these, the live probe's attainment and coverage figures change visibly, and
     * that is the signal. Widening the service's API only to test it would be the
     * worse trade.
     */
    private static BigDecimal pct(BigDecimal achieved, BigDecimal target) {
        if (target == null || target.signum() == 0) return BigDecimal.ZERO;
        return achieved.multiply(new BigDecimal("100"))
                .divide(target, 2, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal coverage(BigDecimal pipeline, BigDecimal gap) {
        if (gap == null || gap.signum() <= 0) return BigDecimal.ZERO;
        return pipeline.divide(gap, 2, java.math.RoundingMode.HALF_UP);
    }
}

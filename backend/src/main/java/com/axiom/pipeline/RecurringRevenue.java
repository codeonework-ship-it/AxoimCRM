package com.axiom.pipeline;

import com.axiom.common.ConflictException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Derives ARR and TCV from a recurring amount, billing frequency and term
 * (FR-OPP-016).
 *
 * <p>Pure and deterministic — the same inputs always produce the same figures,
 * because forecast and revenue reporting are downstream of these numbers and
 * "the ARR moved and nobody changed anything" is not a defensible answer.
 */
public final class RecurringRevenue {

    /** Billing periods per year for each supported frequency. */
    private static final Map<String, Integer> PERIODS_PER_YEAR = Map.of(
            "MONTHLY", 12,
            "QUARTERLY", 4,
            "SEMIANNUAL", 2,
            "ANNUAL", 1);

    private RecurringRevenue() {}

    /**
     * @param recurringAmount the amount billed per billing period
     * @param billingFrequency MONTHLY, QUARTERLY, SEMIANNUAL or ANNUAL
     * @param termMonths contract term in months
     * @param oneTimeAmount non-recurring amount, included in TCV but never in ARR
     */
    public record Derived(BigDecimal arr, BigDecimal tcv, BigDecimal recurringTotal, int periodsPerYear) {}

    public static Derived derive(BigDecimal recurringAmount, String billingFrequency,
                                 Integer termMonths, BigDecimal oneTimeAmount) {
        BigDecimal oneTime = oneTimeAmount == null ? BigDecimal.ZERO : oneTimeAmount;
        if (recurringAmount == null || recurringAmount.signum() == 0) {
            return new Derived(BigDecimal.ZERO.setScale(2), money(oneTime), BigDecimal.ZERO.setScale(2), 0);
        }
        if (billingFrequency == null || termMonths == null) {
            throw new ConflictException("Recurring revenue needs both a billing frequency and a term in months. "
                    + "Set the billing frequency (MONTHLY, QUARTERLY, SEMIANNUAL or ANNUAL) and the term.");
        }
        String frequency = billingFrequency.trim().toUpperCase(Locale.ROOT);
        Integer periods = PERIODS_PER_YEAR.get(frequency);
        if (periods == null) {
            throw new ConflictException("Unknown billing frequency " + billingFrequency
                    + ". Use MONTHLY, QUARTERLY, SEMIANNUAL or ANNUAL.");
        }
        if (termMonths <= 0) {
            throw new ConflictException("The term must be at least one month. Enter the contract term in months.");
        }

        BigDecimal arr = money(recurringAmount.multiply(BigDecimal.valueOf(periods)));
        BigDecimal recurringTotal = money(arr
                .multiply(BigDecimal.valueOf(termMonths))
                .divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP));
        return new Derived(arr, money(recurringTotal.add(oneTime)), recurringTotal, periods);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}

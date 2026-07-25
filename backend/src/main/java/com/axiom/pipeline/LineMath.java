package com.axiom.pipeline;

import com.axiom.common.ConflictException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic line-item arithmetic (FR-OPP-005).
 *
 * <p>The whole point of this class is that {@code computedTotal} is derived from
 * the inputs and nothing else. A user may override the total they invoice
 * against, but the computation is retained beside it: an override must never
 * destroy the evidence of what the system calculated.
 */
public final class LineMath {

    private LineMath() {}

    public record Totals(BigDecimal salePrice, BigDecimal computedTotal, BigDecimal total,
                         boolean overridden, BigDecimal cost, BigDecimal margin) {}

    /**
     * @param overrideTotal a manually entered total, or null to use the computation
     * @param overrideReason mandatory whenever {@code overrideTotal} differs from the computation
     */
    public static Totals compute(BigDecimal quantity, BigDecimal listPrice, BigDecimal discountPct,
                                 BigDecimal overrideTotal, String overrideReason, BigDecimal unitCost) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ConflictException("Quantity must be greater than zero. Enter how many units are being sold.");
        }
        if (listPrice == null || listPrice.signum() < 0) {
            throw new ConflictException("List price must be zero or more. Pick a price book product to inherit its list price.");
        }
        BigDecimal discount = discountPct == null ? BigDecimal.ZERO : discountPct;
        if (discount.signum() < 0 || discount.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ConflictException("Discount must be between 0% and 100%. Adjust the discount on this line.");
        }

        BigDecimal salePrice = money(listPrice.multiply(
                BigDecimal.ONE.subtract(discount.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP))));
        BigDecimal computedTotal = money(salePrice.multiply(quantity));

        boolean overridden = overrideTotal != null && money(overrideTotal).compareTo(computedTotal) != 0;
        if (overridden && (overrideReason == null || overrideReason.isBlank())) {
            throw new ConflictException("A total that differs from the computed " + computedTotal.toPlainString()
                    + " must say why. Enter a reason for the override, or clear the override to use the computed total.");
        }
        BigDecimal total = overridden ? money(overrideTotal) : computedTotal;

        BigDecimal cost = unitCost == null ? null : money(unitCost.multiply(quantity));
        BigDecimal margin = cost == null ? null : money(total.subtract(cost));
        return new Totals(salePrice, computedTotal, total, overridden, cost, margin);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}

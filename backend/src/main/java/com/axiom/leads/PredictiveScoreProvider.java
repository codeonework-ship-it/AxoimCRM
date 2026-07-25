package com.axiom.leads;

import java.math.BigDecimal;
import java.util.List;

/**
 * Port for predictive conversion likelihood (FR-LED-007).
 *
 * <p>The port exists so the model can be replaced without the lead pipeline
 * knowing. What it may <em>not</em> be replaced by is an implementation that
 * returns a bare number: {@link Prediction#factors()} is non-optional by
 * contract, because the requirement is explicit that "a score presented without
 * explanation does not satisfy this requirement". A provider that cannot say why
 * is not a valid provider here, which is also why the shipped implementation is
 * a local weighted model rather than a call to an opaque hosted scorer.
 *
 * <p>An externally trained model (per-tenant historical fit, drift monitoring,
 * champion/challenger) is DEFERRED — it needs a training corpus this product does
 * not have on day one, and a model fitted on no data would be a confident guess
 * dressed as intelligence.
 */
public interface PredictiveScoreProvider {

    /** Identifier recorded alongside a stored prediction. */
    String providerCode();

    /**
     * @param probability 0.0000–1.0000 conversion likelihood
     * @param factors     ordered by absolute contribution, strongest first;
     *                    never empty for a scored lead
     */
    record Prediction(BigDecimal probability, String modelVersion, List<Factor> factors) {

        public Prediction {
            factors = List.copyOf(factors);
        }

        public List<Factor> topFactors(int limit) {
            return factors.stream().limit(Math.max(1, limit)).toList();
        }
    }

    /**
     * One contributing factor and its direction — "senior job title, raises the
     * likelihood" — which is what a rep can act on, unlike a coefficient.
     */
    record Factor(String key, String label, String observedValue, BigDecimal contribution, String direction) {

        public boolean positive() {
            return "POSITIVE".equals(direction);
        }
    }

    Prediction predict(LeadSnapshot snapshot);
}

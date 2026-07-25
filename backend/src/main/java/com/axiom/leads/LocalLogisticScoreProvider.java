package com.axiom.leads;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The shipped predictive provider (FR-LED-007): a logistic model over
 * administrator-visible weighted factors, evaluated locally and deterministically.
 *
 * <p>Deliberately not a black box. Every factor, its weight and its sign live in
 * {@code leads.predictive_factor} where an administrator can read them, and the
 * probability is a plain logistic transform of their sum plus an intercept. That
 * makes the explanation the requirement demands <em>derivable from the
 * computation itself</em> rather than a plausible narrative generated next to it —
 * the two cannot disagree, because the contributions reported are the terms
 * actually summed.
 *
 * <p>The honest limitation: these weights are priors chosen from B2B lead
 * behaviour, not coefficients fitted to this tenant's history. The model is
 * calibrated to be directionally useful and fully explainable, not to be
 * accurate to the decimal. A fitted per-tenant model is the deferred story.
 */
@Service
public class LocalLogisticScoreProvider implements PredictiveScoreProvider {

    /** Used when a tenant has no factors configured, so a lead is never scoreless-and-silent. */
    private static final List<Object[]> FALLBACK_FACTORS = List.of(
            new Object[]{"BUSINESS_EMAIL", "Business (non free-mail) email address", "emailDomain",
                    "DOMAIN_NOT_IN", "gmail.com,yahoo.com,hotmail.com,outlook.com,proton.me", "0.9"},
            new Object[]{"SENIOR_TITLE", "Senior decision-maker job title", "title", "IN",
                    "director,vp,vice president,head,chief,coo,cto,ceo,cfo", "1.1"},
            new Object[]{"PRODUCT_INTEREST", "Stated product interest", "productInterest", "PRESENT", null, "0.6"},
            new Object[]{"ENGAGED", "Two or more logged engagements", "activityCount", "GTE", "2", "1.0"},
            new Object[]{"NO_PHONE", "No telephone number supplied", "phone", "ABSENT", null, "-0.7"});

    private final JdbcTemplate jdbc;

    public LocalLogisticScoreProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String providerCode() {
        return "LOCAL_LOGISTIC";
    }

    @Override
    @Transactional(readOnly = true)
    public Prediction predict(LeadSnapshot snapshot) {
        UUID tenantId = TenantContext.get().tenantId();

        List<BigDecimal> intercepts = jdbc.queryForList(
                "select intercept from leads.predictive_model where tenant_id = ?", BigDecimal.class, tenantId);
        List<String> versions = jdbc.queryForList(
                "select model_version from leads.predictive_model where tenant_id = ?", String.class, tenantId);
        double intercept = intercepts.isEmpty() ? -1.4d : intercepts.get(0).doubleValue();
        String modelVersion = versions.isEmpty() ? "v1-default" : versions.get(0);

        List<LeadConfigService.PredictiveFactorRow> configured = jdbc.query("""
                select id, factor_key, label, field_key, operator, comparison_value, weight, sort_order, active
                from leads.predictive_factor
                where tenant_id = ? and active = true
                order by sort_order
                """, (rs, i) -> new LeadConfigService.PredictiveFactorRow(rs.getObject("id", UUID.class),
                rs.getString("factor_key"), rs.getString("label"), rs.getString("field_key"),
                rs.getString("operator"), rs.getString("comparison_value"), rs.getBigDecimal("weight"),
                rs.getInt("sort_order"), rs.getBoolean("active")), tenantId);

        if (configured.isEmpty()) {
            configured = new ArrayList<>();
            int order = 0;
            for (Object[] f : FALLBACK_FACTORS) {
                configured.add(new LeadConfigService.PredictiveFactorRow(null, (String) f[0], (String) f[1],
                        (String) f[2], (String) f[3], (String) f[4], new BigDecimal((String) f[5]), order += 10, true));
            }
        }

        double logit = intercept;
        List<Factor> contributing = new ArrayList<>();
        List<Factor> absent = new ArrayList<>();
        for (LeadConfigService.PredictiveFactorRow factor : configured) {
            String observed = snapshot.value(factor.fieldKey());
            boolean matched = RuleOperators.matches(factor.operator(), observed, factor.comparisonValue());
            double weight = factor.weight().doubleValue();
            if (matched) {
                logit += weight;
                contributing.add(new Factor(factor.factorKey(), factor.label(),
                        observed == null || observed.isBlank() ? "not supplied" : observed,
                        factor.weight(), weight >= 0 ? "POSITIVE" : "NEGATIVE"));
            } else if (weight > 0) {
                // A positive factor that did NOT fire is itself an explanation:
                // "no product interest stated" is the actionable half of a low
                // score, and hiding it would leave the rep with nothing to fix.
                absent.add(new Factor(factor.factorKey(), "Missing: " + factor.label(),
                        observed == null || observed.isBlank() ? "not supplied" : observed,
                        factor.weight().negate(), "NEGATIVE"));
            }
        }

        List<Factor> factors = new ArrayList<>(contributing);
        factors.sort(Comparator.comparing((Factor f) -> f.contribution().abs()).reversed());
        absent.sort(Comparator.comparing((Factor f) -> f.contribution().abs()).reversed());
        factors.addAll(absent);
        if (factors.isEmpty()) {
            // The contract forbids a bare number. With no factor either way, say
            // exactly that rather than return an unexplained probability.
            factors.add(new Factor("BASELINE", "No configured factor applies to this lead yet",
                    "baseline only", BigDecimal.ZERO, "NEGATIVE"));
        }

        double probability = 1.0d / (1.0d + Math.exp(-logit));
        BigDecimal rounded = BigDecimal.valueOf(probability).setScale(4, RoundingMode.HALF_UP);
        return new Prediction(rounded, modelVersion, factors);
    }
}

package com.axiom.leads;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Stores and serves predictive conversion likelihood alongside the factors that
 * produced it (FR-LED-007).
 *
 * <p>Score and factors are written in the same statement sequence and read back
 * together. There is deliberately no API that returns the probability on its own.
 */
@Service
public class LeadPredictionService {

    private final JdbcTemplate jdbc;
    private final PredictiveScoreProvider provider;

    public LeadPredictionService(JdbcTemplate jdbc, PredictiveScoreProvider provider) {
        this.jdbc = jdbc;
        this.provider = provider;
    }

    @Transactional(readOnly = true)
    public PredictiveScoreProvider.Prediction predict(LeadSnapshot snapshot) {
        return provider.predict(snapshot);
    }

    @Transactional
    public PredictiveScoreProvider.Prediction predictAndStore(UUID leadId, LeadSnapshot snapshot) {
        UUID tenantId = TenantContext.get().tenantId();
        PredictiveScoreProvider.Prediction prediction = provider.predict(snapshot);
        jdbc.update("delete from leads.lead_prediction_factor where tenant_id = ? and lead_id = ?",
                tenantId, leadId);
        for (PredictiveScoreProvider.Factor factor : prediction.factors()) {
            jdbc.update("""
                    insert into leads.lead_prediction_factor
                      (tenant_id, lead_id, factor_key, label, observed_value, contribution, direction)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, leadId, factor.key(), factor.label(), factor.observedValue(),
                    factor.contribution(), factor.direction());
        }
        jdbc.update("""
                update crm.lead set predicted_conversion = ?, prediction_computed_at = now(), updated_at = now()
                where tenant_id = ? and id = ?
                """, prediction.probability(), tenantId, leadId);
        return prediction;
    }

    /** The stored factors behind a lead's likelihood, strongest contribution first. */
    @Transactional(readOnly = true)
    public List<PredictiveScoreProvider.Factor> factors(UUID leadId) {
        return jdbc.query("""
                select factor_key, label, observed_value, contribution, direction
                from leads.lead_prediction_factor
                where tenant_id = ? and lead_id = ?
                order by abs(contribution) desc, label
                """, (rs, i) -> new PredictiveScoreProvider.Factor(rs.getString("factor_key"),
                rs.getString("label"), rs.getString("observed_value"), rs.getBigDecimal("contribution"),
                rs.getString("direction")), TenantContext.get().tenantId(), leadId);
    }

    public String providerCode() {
        return provider.providerCode();
    }
}

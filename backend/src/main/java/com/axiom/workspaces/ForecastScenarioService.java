package com.axiom.workspaces;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Saved, explainable and non-mutating forecast scenarios for FR-FCT-005/008. */
@Service
public class ForecastScenarioService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;

    public ForecastScenarioService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    public record ScenarioRequest(@NotBlank @Size(max = 180) String name,
                                  @DecimalMin("-100") @DecimalMax("500") BigDecimal amountAdjustmentPct,
                                  @DecimalMin("0") @DecimalMax("100") BigDecimal confidencePct,
                                  @Min(0) @Max(1000) Integer riskReduction) {}
    public record Factor(String code, String label, String baseline, String scenario, String effect) {}
    public record Scenario(UUID id, UUID submissionId, String name, BigDecimal amountAdjustmentPct,
                           BigDecimal confidencePct, int riskCount, BigDecimal baselineAmount,
                           BigDecimal scenarioAmount, BigDecimal weightedAmount,
                           List<Factor> explanation, Instant createdAt, String note) {}
    private record Submission(BigDecimal amount, BigDecimal confidence, int risks, String period, String category) {}

    @Transactional
    public Scenario create(UUID submissionId, ScenarioRequest request) {
        requireWrite();
        Submission source = submission(submissionId);
        BigDecimal adjustment = request.amountAdjustmentPct() == null ? BigDecimal.ZERO : request.amountAdjustmentPct();
        BigDecimal confidence = request.confidencePct() == null ? source.confidence() : request.confidencePct();
        int reduction = request.riskReduction() == null ? 0 : request.riskReduction();
        int risks = Math.max(0, source.risks() - reduction);
        BigDecimal scenarioAmount = money(source.amount().multiply(
                BigDecimal.ONE.add(adjustment.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP))));
        BigDecimal riskFactor = BigDecimal.ONE.subtract(new BigDecimal("0.05").multiply(BigDecimal.valueOf(risks)))
                .max(new BigDecimal("0.50"));
        BigDecimal weighted = money(scenarioAmount.multiply(confidence)
                .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP).multiply(riskFactor));
        List<Factor> factors = List.of(
                new Factor("AMOUNT", "Submitted amount", money(source.amount()).toPlainString(),
                        scenarioAmount.toPlainString(), signed(adjustment) + "% commercial adjustment"),
                new Factor("CONFIDENCE", "Confidence", source.confidence().toPlainString() + "%",
                        confidence.toPlainString() + "%", signed(confidence.subtract(source.confidence())) + " points"),
                new Factor("RISK", "Open risk signals", String.valueOf(source.risks()), String.valueOf(risks),
                        reduction + " risk signal(s) assumed resolved"),
                new Factor("WEIGHTED", "Risk-adjusted outcome", money(source.amount()
                        .multiply(source.confidence()).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.ONE.subtract(new BigDecimal("0.05")
                                .multiply(BigDecimal.valueOf(source.risks()))).max(new BigDecimal("0.50"))))
                        .toPlainString(), weighted.toPlainString(), "Confidence and risk penalty applied"));
        UUID id = jdbc.queryForObject("""
                insert into forecasting.forecast_scenario
                  (tenant_id, submission_id, name, amount_adjustment_pct, confidence_pct, risk_count,
                   baseline_amount, scenario_amount, weighted_amount, explanation, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                returning id
                """, UUID.class, tenantId(), submissionId, request.name().trim(), adjustment,
                confidence, risks, source.amount(), scenarioAmount, weighted, encode(factors), userId());
        audit.record("FORECAST_SCENARIO_CREATED", "FORECAST_SUBMISSION", submissionId,
                "Saved forecast scenario " + request.name().trim(),
                Map.of("scenarioId", id.toString(), "period", source.period(), "category", source.category(),
                        "baselineAmount", source.amount(), "scenarioAmount", scenarioAmount,
                        "weightedAmount", weighted));
        return new Scenario(id, submissionId, request.name().trim(), adjustment, confidence, risks,
                source.amount(), scenarioAmount, weighted, factors, Instant.now(),
                "Scenario saved as immutable evidence; the submitted forecast was not changed.");
    }

    @Transactional(readOnly = true)
    public List<Scenario> list(UUID submissionId) {
        submission(submissionId); // tenant check and clear 404
        return jdbc.query("""
                select id, submission_id, name, amount_adjustment_pct, confidence_pct, risk_count,
                       baseline_amount, scenario_amount, weighted_amount, explanation::text, created_at
                from forecasting.forecast_scenario
                where tenant_id = ? and submission_id = ? order by created_at desc
                """, (rs, i) -> new Scenario(rs.getObject("id", UUID.class),
                rs.getObject("submission_id", UUID.class), rs.getString("name"),
                rs.getBigDecimal("amount_adjustment_pct"), rs.getBigDecimal("confidence_pct"),
                rs.getInt("risk_count"), rs.getBigDecimal("baseline_amount"),
                rs.getBigDecimal("scenario_amount"), rs.getBigDecimal("weighted_amount"),
                decode(rs.getString("explanation")), rs.getTimestamp("created_at").toInstant(),
                "Saved scenario; no forecast submission values were changed."), tenantId(), submissionId);
    }

    private Submission submission(UUID id) {
        List<Submission> rows = jdbc.query("""
                select s.submitted_amount, s.confidence_pct, s.risk_count,
                       p.code as period_code, s.forecast_category
                from forecasting.forecast_submission s
                join forecasting.forecast_period p on p.tenant_id = s.tenant_id and p.id = s.period_id
                where s.tenant_id = ? and s.id = ?
                """, (rs, i) -> new Submission(rs.getBigDecimal("submitted_amount"),
                rs.getBigDecimal("confidence_pct"), rs.getInt("risk_count"),
                rs.getString("period_code"), rs.getString("forecast_category")), tenantId(), id);
        return rows.stream().findFirst().orElseThrow(() -> new NotFoundException("Forecast submission not found"));
    }

    private String encode(List<Factor> value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Scenario explanation could not be stored", ex); }
    }

    private List<Factor> decode(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Scenario explanation is not readable", ex); }
    }

    private void requireWrite() {
        if (CrmRole.current(TenantContext.get().role()).readOnly()) {
            throw new ForbiddenException("Your role can read forecast scenarios but cannot save them.");
        }
    }

    static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private static String signed(BigDecimal value) { return (value.signum() > 0 ? "+" : "") + value.stripTrailingZeros().toPlainString(); }
    private static UUID tenantId() { return TenantContext.get().tenantId(); }
    private static UUID userId() { return TenantContext.get().userId(); }
}

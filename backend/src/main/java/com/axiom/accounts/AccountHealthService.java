package com.axiom.accounts;

import com.axiom.audit.AuditService;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explainable, weighted account health for US-E04-09. */
@Service
public class AccountHealthService {

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final AuditService audit;
    private final ObjectMapper json;

    public AccountHealthService(JdbcTemplate jdbc, AccountService accounts,
                                AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.audit = audit;
        this.json = json;
    }

    public record Factor(String code, String label, BigDecimal weight, int score,
                         String observed, String direction, String explanation) {}
    public record Health(UUID accountId, int score, String band, List<Factor> factors,
                         Instant computedAt, Integer previousScore, String previousBand,
                         String changeExplanation) {}

    private record Weight(String code, String label, BigDecimal weight) {}

    @Transactional(readOnly = true)
    public Health current(UUID accountId) {
        accounts.get(accountId); // record-level authorization and tenant check
        try {
            return jdbc.queryForObject("""
                    select s.score, s.band, s.factors::text, s.computed_at,
                           previous.score as previous_score, previous.band as previous_band
                    from crm.account_health_snapshot s
                    left join lateral (
                      select p.score, p.band from crm.account_health_snapshot p
                      where p.tenant_id = s.tenant_id and p.account_id = s.account_id
                        and p.computed_at < s.computed_at
                      order by p.computed_at desc limit 1
                    ) previous on true
                    where s.tenant_id = ? and s.account_id = ?
                    order by s.computed_at desc limit 1
                    """, (rs, i) -> map(accountId, rs.getInt("score"), rs.getString("band"),
                    rs.getString("factors"), rs.getTimestamp("computed_at").toInstant(),
                    (Integer) rs.getObject("previous_score"), rs.getString("previous_band")),
                    TenantContext.get().tenantId(), accountId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    @Transactional
    public Health recompute(UUID accountId) {
        AccountService.AccountDetail account = accounts.get(accountId);
        UUID tenantId = TenantContext.get().tenantId();
        Health previous = current(accountId);
        Instant now = Instant.now();

        Timestamp lastActivityValue = jdbc.queryForObject("""
                select max(occurred_at) from engagement.activity
                where tenant_id = ? and deleted_at is null
                  and related_entity_type = 'ACCOUNT' and related_entity_id = ?
                """, Timestamp.class, tenantId, accountId);
        Instant lastActivity = lastActivityValue == null ? null : lastActivityValue.toInstant();
        Map<String, Object> signals = new LinkedHashMap<>();
        jdbc.query("""
                select signal_code, numeric_value, date_value
                from crm.account_signal where tenant_id = ? and account_id = ?
                """, (RowCallbackHandler) rs -> signals.put(rs.getString("signal_code"),
                rs.getObject("numeric_value") != null
                        ? rs.getBigDecimal("numeric_value") : rs.getObject("date_value", LocalDate.class)),
                tenantId, accountId);
        List<Weight> weights = jdbc.query("""
                select factor_code, label, weight from crm.health_factor_weight
                where tenant_id = ? and active = true order by factor_code
                """, (rs, i) -> new Weight(rs.getString("factor_code"), rs.getString("label"),
                rs.getBigDecimal("weight")), tenantId);

        List<Factor> factors = new ArrayList<>();
        for (Weight weight : weights) factors.add(evaluate(weight, lastActivity, signals, now));
        BigDecimal totalWeight = factors.stream().map(Factor::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal weighted = factors.stream()
                .map(f -> f.weight().multiply(BigDecimal.valueOf(f.score())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int score = totalWeight.signum() == 0 ? 0
                : weighted.divide(totalWeight, 0, RoundingMode.HALF_UP).intValue();
        String band = band(score);
        String factorJson;
        try {
            factorJson = json.writeValueAsString(factors);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Account health factors could not be serialized", ex);
        }
        jdbc.update("""
                insert into crm.account_health_snapshot
                  (tenant_id, account_id, score, band, factors, computed_by)
                values (?, ?, ?, ?, ?::jsonb, ?)
                """, tenantId, accountId, score, band, factorJson, TenantContext.get().userId());
        jdbc.update("""
                update crm.account
                set health_score = ?, health_band = ?, health_computed_at = now(), updated_at = now()
                where tenant_id = ? and id = ?
                """, score, band, tenantId, accountId);
        List<Factor> changed = previous == null ? factors : materiallyChanged(previous.factors(), factors);
        audit.record("ACCOUNT_HEALTH_COMPUTED", "ACCOUNT", accountId,
                "Computed " + band + " health for " + account.name(),
                Map.of("before", previous == null ? Map.of() : Map.of("score", previous.score(), "band", previous.band()),
                        "after", Map.of("score", score, "band", band),
                        "materialFactors", changed.stream().map(Factor::code).toList()));
        return new Health(accountId, score, band, List.copyOf(factors), now,
                previous == null ? null : previous.score(), previous == null ? null : previous.band(),
                changeText(previous, factors, score));
    }

    private Factor evaluate(Weight w, Instant lastActivity, Map<String, Object> signals, Instant now) {
        return switch (w.code()) {
            case "ENGAGEMENT_RECENCY" -> {
                long days = lastActivity == null ? Long.MAX_VALUE
                        : Math.max(0, Duration.between(lastActivity, now).toDays());
                int score = days <= 7 ? 100 : days <= 30 ? 80 : days <= 60 ? 60 : days <= 90 ? 40 : 20;
                String observed = lastActivity == null ? "No recorded customer activity"
                        : days + " day(s) since the last activity";
                yield factor(w, score, observed, score >= 60 ? "POSITIVE" : "NEGATIVE",
                        score >= 60 ? "The relationship has recent engagement."
                                : "Log a meaningful customer interaction to improve recency.");
            }
            case "OPEN_CASES" -> {
                int count = integer(signals.get("OPEN_CASES"), 0);
                int score = count == 0 ? 100 : count == 1 ? 80 : count == 2 ? 60 : count == 3 ? 40 : 20;
                yield factor(w, score, count + " open support case(s)", count <= 1 ? "POSITIVE" : "NEGATIVE",
                        count == 0 ? "There are no open support cases." : "Resolve open cases to reduce customer friction.");
            }
            case "SLA_BREACHES" -> {
                int count = integer(signals.get("SLA_BREACHES"), 0);
                int score = count == 0 ? 100 : count == 1 ? 50 : 10;
                yield factor(w, score, count + " missed support promise(s)", count == 0 ? "POSITIVE" : "NEGATIVE",
                        count == 0 ? "No SLA breaches are recorded." : "Review missed service commitments with the account owner.");
            }
            case "RENEWAL_PROXIMITY" -> {
                LocalDate renewal = signals.get("RENEWAL_DATE") instanceof LocalDate d ? d : null;
                long days = renewal == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(LocalDate.now(), renewal);
                int score = renewal == null ? 50 : days < 0 ? 10 : days <= 30 ? 35 : days <= 90 ? 65 : 100;
                String observed = renewal == null ? "No renewal date supplied" : "Renewal in " + days + " day(s)";
                yield factor(w, score, observed, score >= 65 ? "STABLE" : "NEGATIVE",
                        renewal == null ? "Add a renewal date so risk is not hidden."
                                : days <= 30 ? "Create and confirm the renewal plan now." : "The renewal is not imminent.");
            }
            case "PRODUCT_ADOPTION" -> {
                int adoption = integer(signals.get("ADOPTION_SCORE"), 50);
                int score = Math.max(0, Math.min(100, adoption));
                yield factor(w, score, adoption + "% adoption", adoption >= 70 ? "POSITIVE" : "NEGATIVE",
                        adoption >= 70 ? "Product usage is healthy." : "Agree an adoption plan with the customer.");
            }
            default -> factor(w, 50, "No evaluator is configured", "UNKNOWN",
                    "This factor needs an administrator-defined evaluator.");
        };
    }

    private static Factor factor(Weight w, int score, String observed, String direction, String explanation) {
        return new Factor(w.code(), w.label(), w.weight(), score, observed, direction, explanation);
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    static String band(int score) {
        if (score >= 80) return "STRONG";
        if (score >= 65) return "STEADY";
        if (score >= 50) return "WATCH";
        if (score >= 35) return "AT_RISK";
        return "CRITICAL";
    }

    private Health map(UUID accountId, int score, String band, String factorsJson, Instant computedAt,
                       Integer previousScore, String previousBand) {
        try {
            List<Factor> factors = json.readValue(factorsJson, new TypeReference<>() {});
            return new Health(accountId, score, band, factors, computedAt, previousScore, previousBand,
                    previousScore == null ? "This is the first computed health snapshot."
                            : "Health changed by " + (score - previousScore) + " point(s) since the prior snapshot.");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored account health factors are not readable", ex);
        }
    }

    private static List<Factor> materiallyChanged(List<Factor> before, List<Factor> after) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        before.forEach(f -> scores.put(f.code(), f.score()));
        return after.stream().filter(f -> Math.abs(f.score() - scores.getOrDefault(f.code(), f.score())) >= 10).toList();
    }

    private static String changeText(Health previous, List<Factor> factors, int score) {
        if (previous == null) return "This is the first computed health snapshot.";
        List<Factor> changed = materiallyChanged(previous.factors(), factors);
        if (changed.isEmpty()) return "No contributing factor changed materially since the prior snapshot.";
        return "Health changed by " + (score - previous.score()) + " point(s), led by "
                + String.join(", ", changed.stream().map(Factor::label).toList()) + ".";
    }
}

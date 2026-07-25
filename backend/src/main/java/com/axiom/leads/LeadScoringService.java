package com.axiom.leads;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rule-based lead scoring (FR-LED-006).
 *
 * <p>The requirement is not "produce a score" — it is "produce a score with a
 * visible breakdown of contributing rules". So the breakdown is not a debug
 * artefact reconstructed on demand from today's rules; it is persisted at the
 * moment of scoring in {@code leads.lead_score_component}. If it were
 * recomputed, editing a rule would silently rewrite the explanation of every
 * score already given, and a rep asking "why is this a 65?" would be shown a
 * number that no longer adds up to 65.
 */
@Service
public class LeadScoringService {

    private final JdbcTemplate jdbc;

    public LeadScoringService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Component(UUID ruleId, String ruleName, String category, int points, boolean matched,
                            String detail) {}

    public record ScoreResult(int score, List<Component> components) {
        /** Only the rules that actually contributed, in descending contribution. */
        public List<Component> contributing() {
            return components.stream().filter(Component::matched).toList();
        }
    }

    /**
     * Scores a snapshot against the tenant's active rules without writing
     * anything — used by the ingestion path before a lead row exists, and by the
     * "what would this score" preview.
     */
    @Transactional(readOnly = true)
    public ScoreResult score(LeadSnapshot snapshot) {
        List<LeadConfigService.ScoringRuleRow> rules = jdbc.query("""
                select id, name, category, field_key, operator, comparison_value, points, sort_order, active
                from leads.scoring_rule
                where tenant_id = ? and active = true
                order by sort_order, name
                """, (rs, i) -> new LeadConfigService.ScoringRuleRow(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getString("category"), rs.getString("field_key"),
                rs.getString("operator"), rs.getString("comparison_value"), rs.getInt("points"),
                rs.getInt("sort_order"), rs.getBoolean("active")), TenantContext.get().tenantId());

        List<Component> components = new ArrayList<>();
        int total = 0;
        for (LeadConfigService.ScoringRuleRow rule : rules) {
            String observed = snapshot.value(rule.fieldKey());
            boolean matched = RuleOperators.matches(rule.operator(), observed, rule.comparisonValue());
            if (matched) {
                total += rule.points();
            }
            components.add(new Component(rule.id(), rule.name(), rule.category(), rule.points(), matched,
                    describe(rule, observed, matched)));
        }
        // A score is clamped to 0..100 so the queue's ordering stays meaningful
        // when an administrator adds a dozen new rules. The breakdown still shows
        // the raw points, so a clamped score is visibly explained rather than
        // mysteriously flat.
        return new ScoreResult(Math.max(0, Math.min(100, total)), List.copyOf(components));
    }

    /** Scores and stores, replacing any earlier breakdown for the lead. */
    @Transactional
    public ScoreResult scoreAndStore(UUID leadId, LeadSnapshot snapshot) {
        UUID tenantId = TenantContext.get().tenantId();
        ScoreResult result = score(snapshot);
        jdbc.update("delete from leads.lead_score_component where tenant_id = ? and lead_id = ?", tenantId, leadId);
        for (Component component : result.components()) {
            jdbc.update("""
                    insert into leads.lead_score_component
                      (tenant_id, lead_id, rule_id, rule_name, category, points, matched, detail)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, leadId, component.ruleId(), component.ruleName(), component.category(),
                    component.points(), component.matched(), component.detail());
        }
        jdbc.update("update crm.lead set score = ?, score_computed_at = now(), updated_at = now() "
                + "where tenant_id = ? and id = ?", result.score(), tenantId, leadId);
        return result;
    }

    /** The stored breakdown behind a lead's current score. */
    @Transactional(readOnly = true)
    public List<Component> breakdown(UUID leadId) {
        return jdbc.query("""
                select rule_id, rule_name, category, points, matched, detail
                from leads.lead_score_component
                where tenant_id = ? and lead_id = ?
                order by matched desc, points desc, rule_name
                """, (rs, i) -> new Component(rs.getObject("rule_id", UUID.class), rs.getString("rule_name"),
                rs.getString("category"), rs.getInt("points"), rs.getBoolean("matched"), rs.getString("detail")),
                TenantContext.get().tenantId(), leadId);
    }

    /**
     * A sentence a sales rep can read, not a serialized predicate. "Job title
     * 'VP Sales' is one of director, vp, head" beats "title IN [...] = true".
     */
    private String describe(LeadConfigService.ScoringRuleRow rule, String observed, boolean matched) {
        String label = LeadSnapshot.keys().getOrDefault(rule.fieldKey(), rule.fieldKey());
        String seen = observed == null || observed.isBlank() ? "not supplied" : "'" + observed + "'";
        String expectation = switch (rule.operator()) {
            case "PRESENT" -> "is supplied";
            case "ABSENT" -> "is not supplied";
            case "EQUALS" -> "is " + rule.comparisonValue();
            case "NOT_EQUALS" -> "is not " + rule.comparisonValue();
            case "CONTAINS" -> "contains " + rule.comparisonValue();
            case "IN" -> "is one of " + rule.comparisonValue();
            case "DOMAIN_NOT_IN" -> "is not a free-mail domain";
            case "GTE" -> "is at least " + rule.comparisonValue();
            case "LTE" -> "is at most " + rule.comparisonValue();
            default -> "matches " + rule.comparisonValue();
        };
        return matched
                ? label + " " + seen + " " + expectation + " — " + signed(rule.points()) + " points"
                : label + " " + seen + ", so the rule '" + label + " " + expectation + "' did not apply";
    }

    private String signed(int points) {
        return points >= 0 ? "+" + points : String.valueOf(points);
    }
}

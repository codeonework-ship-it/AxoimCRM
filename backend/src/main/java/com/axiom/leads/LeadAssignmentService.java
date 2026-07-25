package com.axiom.leads;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rule-based lead routing (FR-LED-008).
 *
 * <p>The contract, in the order it matters:
 * <ol>
 *   <li>Rules evaluate in their configured order.</li>
 *   <li>The <b>first match wins</b> — later rules are not consulted, so rule order
 *       is the whole mechanism by which an administrator expresses priority.</li>
 *   <li>The matched rule is <b>recorded on the lead</b>. Without that, "why did
 *       this go to Priya?" is unanswerable, and unanswerable routing is routing
 *       nobody trusts.</li>
 *   <li>An owner at capacity is skipped, and if nothing can take the lead it goes
 *       to the fallback queue rather than being left unassigned.</li>
 * </ol>
 */
@Service
public class LeadAssignmentService {

    private final JdbcTemplate jdbc;

    public LeadAssignmentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param ownerId       the chosen owner, or null when the lead lands in a queue
     * @param queueId       the queue the lead landed in, or null when it has an owner
     * @param ruleId        the matched rule, or null when nothing matched
     * @param explanation   plain-English reason, shown in the UI and stored in audit
     * @param skippedOwners owners passed over because they were at capacity
     */
    public record Assignment(UUID ownerId, String ownerName, UUID queueId, String queueName, UUID ruleId,
                             String ruleName, UUID slaPolicyId, String explanation, List<String> skippedOwners) {

        public boolean assignedToOwner() {
            return ownerId != null;
        }
    }

    /** The lead attributes routing decides on. */
    public record RoutingInput(String territory, String segment, String productInterest, String source, int score) {}

    /**
     * Evaluates the rule set and returns the assignment. Advances the round-robin
     * cursor as a side effect when a round-robin rule wins, so this is not a pure
     * query — call it once per lead.
     */
    @Transactional
    public Assignment evaluate(RoutingInput input) {
        UUID tenantId = TenantContext.get().tenantId();
        List<String> skipped = new ArrayList<>();

        List<Map<String, Object>> rules = jdbc.queryForList("""
                select r.id, r.name, r.match_territory, r.match_segment, r.match_product_interest,
                       r.match_source, r.match_min_score, r.assignment_mode, r.target_user_id,
                       r.target_queue_id, r.sla_policy_id
                from leads.assignment_rule r
                where r.tenant_id = ? and r.active = true
                order by r.sort_order, r.name
                """, tenantId);

        for (Map<String, Object> rule : rules) {
            if (!criteriaMatch(rule, input)) {
                continue;
            }
            // First match wins: from here on we either assign or fall through to
            // the queue. We do NOT continue to the next rule — a rule that matched
            // but whose owners are all full has still made the decision, and
            // silently trying the next rule would make rule order meaningless.
            UUID ruleId = (UUID) rule.get("id");
            String ruleName = (String) rule.get("name");
            UUID slaPolicyId = (UUID) rule.get("sla_policy_id");
            String mode = (String) rule.get("assignment_mode");

            switch (mode) {
                case "USER" -> {
                    UUID userId = (UUID) rule.get("target_user_id");
                    if (userId != null && hasCapacity(ruleId, userId, skipped)) {
                        return owner(userId, ruleId, ruleName, slaPolicyId,
                                "Matched rule '" + ruleName + "' (" + criteriaSummary(rule) + ")", skipped);
                    }
                }
                case "ROUND_ROBIN" -> {
                    Assignment assignment = roundRobin(tenantId, ruleId, ruleName, slaPolicyId, rule, skipped);
                    if (assignment != null) {
                        return assignment;
                    }
                }
                case "QUEUE" -> {
                    UUID queueId = (UUID) rule.get("target_queue_id");
                    if (queueId != null) {
                        return queue(queueId, ruleId, ruleName, slaPolicyId,
                                "Matched rule '" + ruleName + "', which routes to a queue", skipped);
                    }
                }
                default -> { /* unknown mode: fall through to the queue */ }
            }

            return fallback(ruleId, ruleName,
                    "Matched rule '" + ruleName + "' but no owner in it could take the lead"
                            + (skipped.isEmpty() ? "" : " (at capacity: " + String.join(", ", skipped) + ")"),
                    slaPolicyId, skipped);
        }

        return fallback(null, null, "No assignment rule matched this lead", null, skipped);
    }

    /** Applies an assignment to a lead row. Does not start the response clock. */
    @Transactional
    public void apply(UUID leadId, Assignment assignment) {
        jdbc.update("""
                update crm.lead
                set owner_id = ?, queue_id = ?, assignment_rule_id = ?, assignment_rule_name = ?,
                    updated_at = now()
                where tenant_id = ? and id = ?
                """, assignment.ownerId(), assignment.queueId(), assignment.ruleId(), assignment.ruleName(),
                TenantContext.get().tenantId(), leadId);
    }

    /** Open (unconverted, unqualified-out) leads currently owned by a user. */
    @Transactional(readOnly = true)
    public long openLeadCount(UUID userId) {
        Long count = jdbc.queryForObject("""
                select count(*) from crm.lead
                where tenant_id = ? and owner_id = ? and deleted_at is null
                  and converted_at is null and disqualified_at is null
                """, Long.class, TenantContext.get().tenantId(), userId);
        return count == null ? 0L : count;
    }

    // -------------------------------------------------------------- internals

    private boolean criteriaMatch(Map<String, Object> rule, RoutingInput input) {
        return matches((String) rule.get("match_territory"), input.territory())
                && matches((String) rule.get("match_segment"), input.segment())
                && matches((String) rule.get("match_product_interest"), input.productInterest())
                && matches((String) rule.get("match_source"), input.source())
                && (rule.get("match_min_score") == null
                    || input.score() >= ((Number) rule.get("match_min_score")).intValue());
    }

    /**
     * An unset criterion matches everything — that is how a catch-all rule is
     * expressed. A set criterion accepts a comma-separated list so one rule can
     * cover several territories without being duplicated.
     */
    private boolean matches(String criterion, String observed) {
        if (criterion == null || criterion.isBlank()) {
            return true;
        }
        return RuleOperators.matches("IN", observed, criterion);
    }

    private String criteriaSummary(Map<String, Object> rule) {
        List<String> parts = new ArrayList<>();
        if (rule.get("match_territory") != null) parts.add("territory " + rule.get("match_territory"));
        if (rule.get("match_segment") != null) parts.add("segment " + rule.get("match_segment"));
        if (rule.get("match_product_interest") != null) parts.add("interest " + rule.get("match_product_interest"));
        if (rule.get("match_source") != null) parts.add("source " + rule.get("match_source"));
        if (rule.get("match_min_score") != null) parts.add("score at least " + rule.get("match_min_score"));
        return parts.isEmpty() ? "matches every lead" : String.join(", ", parts);
    }

    private Assignment roundRobin(UUID tenantId, UUID ruleId, String ruleName, UUID slaPolicyId,
                                  Map<String, Object> rule, List<String> skipped) {
        List<Map<String, Object>> members = jdbc.queryForList("""
                select m.user_id, m.sort_order, m.capacity, u.display_name,
                       (select count(*) from crm.lead l
                        where l.tenant_id = m.tenant_id and l.owner_id = m.user_id
                          and l.deleted_at is null and l.converted_at is null and l.disqualified_at is null
                       ) as open_leads
                from leads.assignment_rule_member m
                join identity.app_user u on u.tenant_id = m.tenant_id and u.id = m.user_id
                where m.tenant_id = ? and m.rule_id = ? and m.active = true and u.active = true
                order by m.sort_order
                """, tenantId, ruleId);
        if (members.isEmpty()) {
            return null;
        }

        // FOR UPDATE on the cursor row serialises two concurrent assignments
        // against the same rule, so round robin cannot hand the same position to
        // two leads that arrived in the same millisecond.
        List<Integer> positions = jdbc.queryForList("""
                select last_position from leads.assignment_cursor
                where tenant_id = ? and rule_id = ? for update
                """, Integer.class, tenantId, ruleId);
        int last;
        if (positions.isEmpty()) {
            jdbc.update("insert into leads.assignment_cursor (tenant_id, rule_id, last_position) values (?, ?, -1) "
                    + "on conflict (tenant_id, rule_id) do nothing", tenantId, ruleId);
            last = -1;
        } else {
            last = positions.get(0);
        }

        for (int step = 1; step <= members.size(); step++) {
            int index = (last + step) % members.size();
            Map<String, Object> member = members.get(index);
            UUID userId = (UUID) member.get("user_id");
            Integer capacity = member.get("capacity") == null ? null : ((Number) member.get("capacity")).intValue();
            long open = ((Number) member.get("open_leads")).longValue();
            if (capacity != null && open >= capacity) {
                skipped.add(member.get("display_name") + " (" + open + "/" + capacity + " open)");
                continue;
            }
            jdbc.update("update leads.assignment_cursor set last_position = ?, updated_at = now() "
                    + "where tenant_id = ? and rule_id = ?", index, tenantId, ruleId);
            return new Assignment(userId, (String) member.get("display_name"), null, null, ruleId, ruleName,
                    slaPolicyId,
                    "Matched rule '" + ruleName + "'; round robin selected " + member.get("display_name")
                            + (skipped.isEmpty() ? "" : " after skipping " + String.join(", ", skipped)),
                    List.copyOf(skipped));
        }
        return null;
    }

    private boolean hasCapacity(UUID ruleId, UUID userId, List<String> skipped) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select coalesce(m.capacity, p.max_open_leads) as capacity, u.display_name,
                       (select count(*) from crm.lead l
                        where l.tenant_id = u.tenant_id and l.owner_id = u.id
                          and l.deleted_at is null and l.converted_at is null and l.disqualified_at is null
                       ) as open_leads
                from identity.app_user u
                left join leads.assignment_rule_member m
                       on m.tenant_id = u.tenant_id and m.user_id = u.id and m.rule_id = ?
                left join leads.owner_work_profile p on p.tenant_id = u.tenant_id and p.user_id = u.id
                where u.tenant_id = ? and u.id = ? and u.active = true
                """, ruleId, TenantContext.get().tenantId(), userId);
        if (rows.isEmpty()) {
            return false;
        }
        Object capacity = rows.get(0).get("capacity");
        long open = ((Number) rows.get(0).get("open_leads")).longValue();
        if (capacity != null && open >= ((Number) capacity).longValue()) {
            skipped.add(rows.get(0).get("display_name") + " (" + open + "/" + capacity + " open)");
            return false;
        }
        return true;
    }

    private Assignment owner(UUID userId, UUID ruleId, String ruleName, UUID slaPolicyId, String explanation,
                             List<String> skipped) {
        String name = jdbc.queryForList("select display_name from identity.app_user where tenant_id = ? and id = ?",
                String.class, TenantContext.get().tenantId(), userId).stream().findFirst().orElse(null);
        return new Assignment(userId, name, null, null, ruleId, ruleName, slaPolicyId, explanation,
                List.copyOf(skipped));
    }

    private Assignment queue(UUID queueId, UUID ruleId, String ruleName, UUID slaPolicyId, String explanation,
                             List<String> skipped) {
        String name = jdbc.queryForList("select name from leads.lead_queue where tenant_id = ? and id = ?",
                String.class, TenantContext.get().tenantId(), queueId).stream().findFirst().orElse(null);
        return new Assignment(null, null, queueId, name, ruleId, ruleName, slaPolicyId, explanation,
                List.copyOf(skipped));
    }

    private Assignment fallback(UUID ruleId, String ruleName, String reason, UUID slaPolicyId,
                                List<String> skipped) {
        List<Map<String, Object>> queues = jdbc.queryForList("""
                select id, name from leads.lead_queue
                where tenant_id = ? and active = true order by is_fallback desc, name limit 1
                """, TenantContext.get().tenantId());
        if (queues.isEmpty()) {
            return new Assignment(null, null, null, null, ruleId, ruleName, slaPolicyId,
                    reason + ", and no fallback queue is configured — the lead is unassigned",
                    List.copyOf(skipped));
        }
        return new Assignment(null, null, (UUID) queues.get(0).get("id"), (String) queues.get(0).get("name"),
                ruleId, ruleName, slaPolicyId,
                reason + "; placed in the " + queues.get(0).get("name"), List.copyOf(skipped));
    }
}

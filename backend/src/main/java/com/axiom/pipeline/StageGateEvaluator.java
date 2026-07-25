package com.axiom.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates a published set of stage gate criteria against an opportunity's
 * facts (FR-OPP-003).
 *
 * <p>Pure: it takes a {@link StageGate.Version} and {@link StageGate.Facts} and
 * returns the unsatisfied criteria. It never reads the database, which is what
 * makes "an opportunity that entered under v1 is still judged by v1" testable —
 * the caller decides which version to hand in, and this class cannot quietly
 * substitute the newest one.
 */
@Component
public class StageGateEvaluator {

    private final ObjectMapper json;

    public StageGateEvaluator(ObjectMapper json) {
        this.json = json;
    }

    public StageGate.Result evaluate(StageGate.Version version, String stageName, StageGate.Facts facts) {
        List<StageGate.Unsatisfied> failures = new ArrayList<>();
        for (StageGate.Criterion criterion : version.criteria()) {
            Map<String, Object> expression = parse(criterion);
            if (!satisfied(criterion, expression, facts)) {
                failures.add(new StageGate.Unsatisfied(
                        version.gate(), stageName, criterion.code(), criterion.label(),
                        observation(criterion, expression, facts), criterion.remediation()));
            }
        }
        return new StageGate.Result(version, List.copyOf(failures));
    }

    private Map<String, Object> parse(StageGate.Criterion criterion) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = json.readValue(
                    criterion.expressionJson() == null || criterion.expressionJson().isBlank()
                            ? "{}" : criterion.expressionJson(), Map.class);
            return map;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Stage gate criterion " + criterion.code() + " has an unreadable expression", ex);
        }
    }

    private boolean satisfied(StageGate.Criterion criterion, Map<String, Object> e, StageGate.Facts f) {
        return switch (criterion.type()) {
            case "FIELD" -> field(e, f);
            case "RELATED_RECORD" -> relatedRecord(e, f);
            case "ACTIVITY" -> activity(e, f);
            case "APPROVAL" -> approval(e, f);
            case "QUALIFICATION" -> qualification(e, f);
            default -> throw new IllegalStateException(
                    "Unknown stage gate criterion type: " + criterion.type());
        };
    }

    // ------------------------------------------------------------------ FIELD

    private boolean field(Map<String, Object> e, StageGate.Facts f) {
        String fieldName = str(e.get("field"));
        String operator = str(e.get("operator"), "NOT_NULL");
        Object actual = fieldValue(fieldName, f);

        return switch (operator) {
            case "NOT_NULL" -> actual != null;
            case "NOT_BLANK" -> actual != null && !actual.toString().isBlank();
            case "GT" -> compare(actual, e.get("value")) > 0;
            case "GTE" -> compare(actual, e.get("value")) >= 0;
            case "LT" -> compare(actual, e.get("value")) < 0;
            case "EQ" -> actual != null && actual.toString().equals(str(e.get("value")));
            default -> throw new IllegalStateException("Unknown field operator: " + operator);
        };
    }

    private Object fieldValue(String fieldName, StageGate.Facts f) {
        return switch (fieldName == null ? "" : fieldName) {
            case "amount" -> f.amount();
            case "closeDate" -> f.closeDate();
            case "nextStep" -> f.nextStep();
            case "recurringAmount" -> f.recurringAmount();
            case "termMonths" -> f.termMonths();
            case "qualificationScore" -> f.qualificationScore();
            default -> throw new IllegalStateException("Unknown gate field: " + fieldName);
        };
    }

    private int compare(Object actual, Object expected) {
        if (actual == null) return -1;
        BigDecimal left = new BigDecimal(actual.toString());
        BigDecimal right = expected == null ? BigDecimal.ZERO : new BigDecimal(expected.toString());
        return left.compareTo(right);
    }

    // --------------------------------------------------------- RELATED_RECORD

    private boolean relatedRecord(Map<String, Object> e, StageGate.Facts f) {
        String relation = str(e.get("relation"), "CONTACT_ROLE");
        int minCount = num(e.get("minCount"), 1);
        return switch (relation) {
            case "CONTACT_ROLE" -> {
                String role = str(e.get("role"));
                yield role != null && f.contactRoles().contains(role.toUpperCase(Locale.ROOT));
            }
            case "LINE_ITEM" -> f.lineCount() >= minCount;
            case "COMPETITOR" -> f.competitorCount() >= minCount;
            default -> throw new IllegalStateException("Unknown gate relation: " + relation);
        };
    }

    // -------------------------------------------------------------- ACTIVITY

    private boolean activity(Map<String, Object> e, StageGate.Facts f) {
        String type = str(e.get("activityType"), "ANY").toUpperCase(Locale.ROOT);
        int minCount = num(e.get("minCount"), 1);
        int count = "ANY".equals(type)
                ? f.completedActivityCounts().values().stream().mapToInt(Integer::intValue).sum()
                : f.completedActivityCounts().getOrDefault(type, 0);
        if (count < minCount) return false;

        Integer withinDays = e.get("withinDays") == null ? null : num(e.get("withinDays"), 0);
        if (withinDays == null) return true;

        Instant last = "ANY".equals(type)
                ? f.lastCompletedActivityAt().values().stream().max(Instant::compareTo).orElse(null)
                : f.lastCompletedActivityAt().get(type);
        return last != null && Duration.between(last, f.now()).toDays() <= withinDays;
    }

    // -------------------------------------------------------------- APPROVAL

    private boolean approval(Map<String, Object> e, StageGate.Facts f) {
        String type = str(e.get("approvalType"));
        String required = str(e.get("state"), "APPROVED");
        return type != null && required.equals(f.approvalStates().get(type));
    }

    // --------------------------------------------------------- QUALIFICATION

    private boolean qualification(Map<String, Object> e, StageGate.Facts f) {
        BigDecimal min = new BigDecimal(str(e.get("minScore"), "0"));
        BigDecimal score = f.qualificationScore() == null ? BigDecimal.ZERO : f.qualificationScore();
        return score.compareTo(min) >= 0;
    }

    // ------------------------------------------------------------ observation

    /**
     * The configured message, made specific with the actual observed value where
     * one exists. "The opportunity amount is still zero" is useful; "amount is
     * 0.00, needs to be above 25000" is more useful.
     */
    private String observation(StageGate.Criterion criterion, Map<String, Object> e, StageGate.Facts f) {
        String base = criterion.message();
        return switch (criterion.type()) {
            case "QUALIFICATION" -> base + " Current score is "
                    + (f.qualificationScore() == null ? "0" : f.qualificationScore().stripTrailingZeros().toPlainString())
                    + "%, the gate needs at least " + str(e.get("minScore"), "0") + "%.";
            case "RELATED_RECORD" -> "LINE_ITEM".equals(str(e.get("relation"), "CONTACT_ROLE"))
                    ? base + " There are currently " + f.lineCount() + " line items."
                    : base;
            case "FIELD" -> {
                Object actual = fieldValue(str(e.get("field")), f);
                yield actual == null ? base : base + " Current value: " + actual + ".";
            }
            default -> base;
        };
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static int num(Object value, int fallback) {
        return value instanceof Number n ? n.intValue()
                : value == null ? fallback : Integer.parseInt(value.toString());
    }
}

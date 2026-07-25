package com.axiom.leads;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The one place a configured operator is interpreted.
 *
 * <p>Scoring rules (FR-LED-006), predictive factors (FR-LED-007) and assignment
 * criteria (FR-LED-008) all compare an observed lead attribute against an
 * administrator-typed comparison value. Sharing this evaluator means a rule that
 * scores a lead and a factor that explains its likelihood cannot disagree about
 * what "contains" means — which they would, within a release or two, if each
 * module wrote its own comparison.
 *
 * <p>Comparison is case-insensitive throughout. An administrator typing
 * "Director" and a lead carrying "director" is not a distinction anybody wants
 * to debug.
 */
public final class RuleOperators {

    public static final List<String> SUPPORTED = List.of(
            "EQUALS", "NOT_EQUALS", "CONTAINS", "IN", "GTE", "LTE", "PRESENT", "ABSENT", "DOMAIN_NOT_IN");

    private RuleOperators() {}

    public static boolean isSupported(String operator) {
        return operator != null && SUPPORTED.contains(operator.toUpperCase(Locale.ROOT));
    }

    /**
     * @param observed   the lead's value for the rule's field, possibly null
     * @param comparison the administrator's comparison value; a comma-separated
     *                   list for {@code IN} and {@code DOMAIN_NOT_IN}
     */
    public static boolean matches(String operator, String observed, String comparison) {
        String op = operator == null ? "" : operator.toUpperCase(Locale.ROOT);
        String value = observed == null ? null : observed.trim();
        boolean present = value != null && !value.isEmpty();

        return switch (op) {
            case "PRESENT" -> present;
            case "ABSENT" -> !present;
            case "EQUALS" -> present && value.equalsIgnoreCase(safe(comparison));
            case "NOT_EQUALS" -> !present || !value.equalsIgnoreCase(safe(comparison));
            // CONTAINS reads both ways: a job title of "VP Engineering" must match
            // a comparison of "vp", and a comparison of "vice president of sales"
            // must match a title of "Vice President". Both are what an
            // administrator means by "contains" and neither alone is enough.
            case "CONTAINS" -> present && (lower(value).contains(lower(safe(comparison)))
                    || lower(safe(comparison)).contains(lower(value)));
            case "IN" -> present && list(comparison).stream()
                    .anyMatch(candidate -> value.equalsIgnoreCase(candidate) || lower(value).contains(lower(candidate)));
            case "DOMAIN_NOT_IN" -> present && list(comparison).stream()
                    .noneMatch(candidate -> value.equalsIgnoreCase(candidate));
            case "GTE" -> compare(value, comparison) >= 0;
            case "LTE" -> compare(value, comparison) <= 0 && present;
            default -> false;
        };
    }

    /**
     * Numeric comparison where both sides parse as numbers, lexical otherwise —
     * so a currency budget and an ISO date both behave sensibly without the
     * administrator declaring a type. Returns -1 when the observed value is
     * absent, so GTE never matches a blank.
     */
    private static int compare(String observed, String comparison) {
        if (observed == null || observed.isBlank()) return -1;
        try {
            return new BigDecimal(stripNumeric(observed)).compareTo(new BigDecimal(stripNumeric(safe(comparison))));
        } catch (NumberFormatException ex) {
            return observed.compareToIgnoreCase(safe(comparison));
        }
    }

    private static String stripNumeric(String value) {
        return value.replaceAll("[,\\s\\u00a0]", "").replaceAll("^[^0-9.\\-]+", "");
    }

    public static List<String> list(String comparison) {
        if (comparison == null || comparison.isBlank()) return List.of();
        return Arrays.stream(comparison.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}

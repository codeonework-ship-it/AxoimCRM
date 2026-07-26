package com.axiom.dispatch;

import java.util.Locale;
import java.util.Map;

/**
 * Decides whether a domain event belongs to a subscription.
 *
 * <p>Two deliberately small languages, because a filter language is a feature
 * with a support cost and the requirement (`FR-INT-005`) is "subscribe endpoints
 * to record and process events", not "ship a rules engine at the edge":
 *
 * <ul>
 *   <li><b>Event type pattern</b> — {@code *} for everything, a trailing
 *       {@code *} for a prefix ({@code opportunity.*}), otherwise an exact
 *       case-insensitive match.</li>
 *   <li><b>Filter expression</b> — a conjunction of {@code path=value} or
 *       {@code path!=value} terms separated by {@code &&}. Paths may be dotted
 *       to reach into a nested payload object. Every term must hold.</li>
 * </ul>
 *
 * <p>An unparseable term does NOT match. A filter nobody can read is better
 * surfaced as "this subscription delivers nothing" than as "this subscription
 * delivers everything", which is how an endpoint gets a firehose it never asked
 * for.
 */
public final class SubscriptionMatcher {

    private SubscriptionMatcher() {}

    public static boolean matchesType(String pattern, String eventType) {
        if (pattern == null || pattern.isBlank() || eventType == null) return false;
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        String t = eventType.trim().toLowerCase(Locale.ROOT);
        if (p.equals("*")) return true;
        if (p.endsWith("*")) return t.startsWith(p.substring(0, p.length() - 1));
        return p.equals(t);
    }

    public static boolean matchesFilter(String expression, Map<String, Object> payload) {
        if (expression == null || expression.isBlank()) return true;
        for (String term : expression.split("&&")) {
            String trimmed = term.trim();
            if (trimmed.isEmpty()) continue;
            boolean negated = trimmed.contains("!=");
            String[] parts = trimmed.split(negated ? "!=" : "=", 2);
            if (parts.length != 2) return false;
            String path = parts[0].trim();
            String wanted = unquote(parts[1].trim());
            if (path.isEmpty()) return false;
            Object actual = resolve(payload, path);
            boolean equal = actual != null && String.valueOf(actual).equalsIgnoreCase(wanted);
            if (negated == equal) return false;
        }
        return true;
    }

    public static boolean matches(String pattern, String filterExpression, String eventType,
                                  Map<String, Object> payload) {
        return matchesType(pattern, eventType) && matchesFilter(filterExpression, payload);
    }

    private static Object resolve(Map<String, Object> payload, String path) {
        if (payload == null) return null;
        Object cursor = payload;
        for (String segment : path.split("\\.")) {
            if (!(cursor instanceof Map<?, ?> map)) return null;
            cursor = map.get(segment);
            if (cursor == null) return null;
        }
        return cursor;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}

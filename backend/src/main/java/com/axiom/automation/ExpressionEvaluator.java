package com.axiom.automation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates a parsed formula against a record context (FR-AUT-009).
 *
 * <h2>Old and new values are both first-class</h2>
 * FR-AUT-001 requires entry conditions over old <em>and</em> new values, so the
 * context carries both maps and {@code OLD.field}, {@code PRIORVALUE(field)}
 * and {@code ISCHANGED(field)} all read the same "before" snapshot. On a create
 * there is no before, and {@code ISCHANGED} is false rather than true — a create
 * has not changed anything, it has produced something.
 *
 * <h2>Failure is an error, not a false</h2>
 * A formula that references an unknown field or divides by zero raises rather
 * than quietly evaluating to false. An entry condition that silently becomes
 * false is a rule that stops firing without anyone being told, which is the
 * worst failure an automation platform has.
 */
public final class ExpressionEvaluator {

    /**
     * @param newValues column name → value after the change
     * @param oldValues column name → value before the change; empty on create
     * @param isNew     true when the triggering event created the record
     * @param variables extra names visible to the formula (loop item fields, for example)
     */
    public record Context(Map<String, Object> newValues,
                          Map<String, Object> oldValues,
                          boolean isNew,
                          Map<String, Object> variables) {

        public static Context of(Map<String, Object> newValues, Map<String, Object> oldValues) {
            return new Context(newValues == null ? Map.of() : newValues,
                    oldValues == null ? Map.of() : oldValues,
                    oldValues == null || oldValues.isEmpty(), Map.of());
        }

        public Context withVariables(Map<String, Object> vars) {
            return new Context(newValues, oldValues, isNew, vars == null ? Map.of() : vars);
        }
    }

    /** An expression that parsed but could not be evaluated against this record. */
    public static class EvaluationException extends RuntimeException {
        public EvaluationException(String message) { super(message); }
    }

    private final Context context;

    private ExpressionEvaluator(Context context) {
        this.context = context;
    }

    public static Object evaluate(ExpressionParser.Node node, Context context) {
        return new ExpressionEvaluator(context).eval(node);
    }

    /** Parse and evaluate in one step; the form every caller with a raw string wants. */
    public static Object evaluate(String expression, Context context) {
        return evaluate(ExpressionParser.parse(expression), context);
    }

    /**
     * Evaluate as a condition. A non-boolean result is an error rather than a
     * truthiness coercion — {@code amount} is not a condition, and treating it as
     * one hides a typo forever.
     */
    public static boolean condition(String expression, Context context) {
        Object value = evaluate(expression, context);
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        throw new EvaluationException("The condition '" + expression + "' produced "
                + describe(value) + " rather than true or false.");
    }

    // ------------------------------------------------------------------ eval

    private Object eval(ExpressionParser.Node node) {
        if (node instanceof ExpressionParser.Literal lit) return lit.value();
        if (node instanceof ExpressionParser.Reference ref) return reference(ref);
        if (node instanceof ExpressionParser.Unary unary) return unary(unary);
        if (node instanceof ExpressionParser.Binary binary) return binary(binary);
        if (node instanceof ExpressionParser.FunctionCall call) return call(call);
        throw new EvaluationException("Unsupported expression node");
    }

    private Object reference(ExpressionParser.Reference ref) {
        String field = ref.field();
        if ("OLD".equals(ref.scope())) {
            return lookup(context.oldValues(), field, "OLD." + field);
        }
        if ("NEW".equals(ref.scope())) {
            return lookup(context.newValues(), field, "NEW." + field);
        }
        if (context.variables().containsKey(field)) return context.variables().get(field);
        return lookup(context.newValues(), field, field);
    }

    private Object lookup(Map<String, Object> values, String field, String display) {
        if (values.containsKey(field)) return values.get(field);
        // Tolerate camelCase in a formula against snake_case columns; the builder
        // shows column names but administrators type what they see in the API.
        String snake = toSnake(field);
        if (values.containsKey(snake)) return values.get(snake);
        throw new EvaluationException("'" + display + "' is not a field of this record. Available: "
                + String.join(", ", values.keySet().stream().sorted().toList()));
    }

    static String toSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) sb.append('_').append(Character.toLowerCase(c));
            else sb.append(c);
        }
        return sb.toString();
    }

    private Object unary(ExpressionParser.Unary node) {
        Object value = eval(node.operand());
        if ("NOT".equals(node.operator())) {
            if (value == null) return Boolean.TRUE;
            if (value instanceof Boolean b) return !b;
            throw new EvaluationException("NOT expects true or false but was given " + describe(value) + ".");
        }
        return number(value, "-").negate();
    }

    private Object binary(ExpressionParser.Binary node) {
        String op = node.operator();
        if ("AND".equals(op)) {
            return truth(eval(node.left()), "AND") && truth(eval(node.right()), "AND");
        }
        if ("OR".equals(op)) {
            return truth(eval(node.left()), "OR") || truth(eval(node.right()), "OR");
        }
        Object left = eval(node.left());
        Object right = eval(node.right());
        return switch (op) {
            case "+" -> add(left, right);
            case "-" -> number(left, "-").subtract(number(right, "-"));
            case "*" -> number(left, "*").multiply(number(right, "*"));
            case "/" -> divide(left, right);
            case "%" -> number(left, "%").remainder(number(right, "%"));
            case "=" -> compareEquals(left, right);
            case "!=" -> !compareEquals(left, right);
            case "<" -> compare(left, right) < 0;
            case "<=" -> compare(left, right) <= 0;
            case ">" -> compare(left, right) > 0;
            case ">=" -> compare(left, right) >= 0;
            default -> throw new EvaluationException("Unsupported operator '" + op + "'");
        };
    }

    private boolean truth(Object value, String op) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        throw new EvaluationException(op + " expects true or false but was given " + describe(value) + ".");
    }

    private Object add(Object left, Object right) {
        if (isNumeric(left) && isNumeric(right)) return number(left, "+").add(number(right, "+"));
        return text(left) + text(right);
    }

    private Object divide(Object left, Object right) {
        BigDecimal divisor = number(right, "/");
        if (divisor.signum() == 0) throw new EvaluationException("Division by zero.");
        return number(left, "/").divide(divisor, 10, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private boolean compareEquals(Object left, Object right) {
        if (left == null || right == null) return left == right;
        if (isNumeric(left) && isNumeric(right)) return number(left, "=").compareTo(number(right, "=")) == 0;
        if (left instanceof Boolean || right instanceof Boolean) {
            return String.valueOf(left).equalsIgnoreCase(String.valueOf(right));
        }
        if (isDate(left) || isDate(right)) return date(left).equals(date(right));
        return text(left).equals(text(right));
    }

    private int compare(Object left, Object right) {
        if (left == null || right == null) {
            throw new EvaluationException("Cannot order a comparison when one side is blank.");
        }
        if (isNumeric(left) && isNumeric(right)) return number(left, "<").compareTo(number(right, "<"));
        if (isDate(left) || isDate(right)) return date(left).compareTo(date(right));
        return text(left).compareTo(text(right));
    }

    // ------------------------------------------------------------------ functions

    private Object call(ExpressionParser.FunctionCall call) {
        String name = call.name();
        List<ExpressionParser.Node> args = call.arguments();

        // Record-reference functions take the NAME of a field, so they are
        // resolved before the arguments are evaluated.
        switch (name) {
            case "ISCHANGED" -> {
                String field = fieldNameOf(call, 0);
                if (context.isNew()) return Boolean.FALSE;
                Object before = context.oldValues().get(field);
                Object after = context.newValues().get(field);
                return !java.util.Objects.equals(text(before), text(after));
            }
            case "PRIORVALUE" -> {
                return context.oldValues().get(fieldNameOf(call, 0));
            }
            case "ISNEW" -> {
                return context.isNew();
            }
            default -> { /* value functions fall through */ }
        }

        List<Object> v = args.stream().map(this::eval).toList();
        return switch (name) {
            // ---- text
            case "UPPER" -> text(arg(v, 0, name)).toUpperCase(Locale.ROOT);
            case "LOWER" -> text(arg(v, 0, name)).toLowerCase(Locale.ROOT);
            case "TRIM" -> text(arg(v, 0, name)).trim();
            case "LEN" -> BigDecimal.valueOf(text(arg(v, 0, name)).length());
            case "CONTAINS" -> text(arg(v, 0, name)).contains(text(arg(v, 1, name)));
            case "STARTSWITH" -> text(arg(v, 0, name)).startsWith(text(arg(v, 1, name)));
            case "ENDSWITH" -> text(arg(v, 0, name)).endsWith(text(arg(v, 1, name)));
            case "LEFT" -> substring(text(arg(v, 0, name)), 0, number(arg(v, 1, name), name).intValue());
            case "RIGHT" -> {
                String s = text(arg(v, 0, name));
                int n = number(arg(v, 1, name), name).intValue();
                yield substring(s, Math.max(0, s.length() - n), s.length());
            }
            case "MID" -> {
                String s = text(arg(v, 0, name));
                int from = number(arg(v, 1, name), name).intValue();
                int len = number(arg(v, 2, name), name).intValue();
                yield substring(s, from, from + len);
            }
            case "FIND" -> BigDecimal.valueOf(text(arg(v, 1, name)).indexOf(text(arg(v, 0, name))) + 1L);
            case "SUBSTITUTE" -> text(arg(v, 0, name)).replace(text(arg(v, 1, name)), text(arg(v, 2, name)));
            case "CONCAT" -> {
                StringBuilder sb = new StringBuilder();
                for (Object o : v) sb.append(text(o));
                yield sb.toString();
            }
            case "TEXT" -> text(arg(v, 0, name));

            // ---- number
            case "VALUE" -> new BigDecimal(text(arg(v, 0, name)).trim());
            case "ABS" -> number(arg(v, 0, name), name).abs();
            case "ROUND" -> number(arg(v, 0, name), name)
                    .setScale(v.size() > 1 ? number(v.get(1), name).intValue() : 0, RoundingMode.HALF_UP);
            case "FLOOR" -> number(arg(v, 0, name), name).setScale(0, RoundingMode.FLOOR);
            case "CEILING" -> number(arg(v, 0, name), name).setScale(0, RoundingMode.CEILING);
            case "MOD" -> number(arg(v, 0, name), name).remainder(number(arg(v, 1, name), name));
            case "MIN" -> v.stream().map(o -> number(o, name)).min(BigDecimal::compareTo)
                    .orElseThrow(() -> new EvaluationException("MIN() needs at least one value."));
            case "MAX" -> v.stream().map(o -> number(o, name)).max(BigDecimal::compareTo)
                    .orElseThrow(() -> new EvaluationException("MAX() needs at least one value."));

            // ---- date
            case "TODAY" -> LocalDate.now(ZoneOffset.UTC);
            case "NOW" -> Instant.now();
            case "DATE" -> date(arg(v, 0, name));
            case "YEAR" -> BigDecimal.valueOf(date(arg(v, 0, name)).getYear());
            case "MONTH" -> BigDecimal.valueOf(date(arg(v, 0, name)).getMonthValue());
            case "DAY" -> BigDecimal.valueOf(date(arg(v, 0, name)).getDayOfMonth());
            case "DAYS_BETWEEN" -> BigDecimal.valueOf(
                    ChronoUnit.DAYS.between(date(arg(v, 0, name)), date(arg(v, 1, name))));
            case "ADDDAYS" -> date(arg(v, 0, name)).plusDays(number(arg(v, 1, name), name).longValue());
            case "ADDMONTHS" -> date(arg(v, 0, name)).plusMonths(number(arg(v, 1, name), name).longValue());

            // ---- logical
            case "IF" -> truth(arg(v, 0, name), name) ? v.get(1) : (v.size() > 2 ? v.get(2) : null);
            case "AND" -> v.stream().allMatch(o -> truth(o, name));
            case "OR" -> v.stream().anyMatch(o -> truth(o, name));
            case "NOT" -> !truth(arg(v, 0, name), name);
            case "ISBLANK" -> v.get(0) == null || text(v.get(0)).isBlank();
            case "ISNULL" -> v.get(0) == null;
            case "NULLVALUE" -> v.get(0) == null ? v.get(1) : v.get(0);
            case "CASE" -> caseFunction(v, name);

            default -> throw new EvaluationException("Unknown function " + name + "(). "
                    + "Supported functions: " + String.join(", ", FUNCTIONS));
        };
    }

    /** {@code CASE(expr, match1, result1, match2, result2, ..., default)} */
    private Object caseFunction(List<Object> v, String name) {
        if (v.size() < 4 || v.size() % 2 != 0) {
            throw new EvaluationException("CASE() takes an expression, then match/result pairs, "
                    + "then a default value.");
        }
        Object subject = v.get(0);
        for (int i = 1; i + 1 < v.size(); i += 2) {
            if (compareEquals(subject, v.get(i))) return v.get(i + 1);
        }
        return v.get(v.size() - 1);
    }

    /** The catalogue the syntax checker and the builder's function picker both read. */
    public static final List<String> FUNCTIONS = List.of(
            "UPPER", "LOWER", "TRIM", "LEN", "CONTAINS", "STARTSWITH", "ENDSWITH", "LEFT", "RIGHT",
            "MID", "FIND", "SUBSTITUTE", "CONCAT", "TEXT",
            "VALUE", "ABS", "ROUND", "FLOOR", "CEILING", "MOD", "MIN", "MAX",
            "TODAY", "NOW", "DATE", "YEAR", "MONTH", "DAY", "DAYS_BETWEEN", "ADDDAYS", "ADDMONTHS",
            "IF", "AND", "OR", "NOT", "ISBLANK", "ISNULL", "NULLVALUE", "CASE",
            "ISCHANGED", "PRIORVALUE", "ISNEW");

    private String fieldNameOf(ExpressionParser.FunctionCall call, int i) {
        if (call.arguments().size() <= i
                || !(call.arguments().get(i) instanceof ExpressionParser.Reference ref)) {
            throw new EvaluationException(call.name() + "() takes a field, for example "
                    + call.name() + "(amount).");
        }
        String field = ref.field();
        return context.newValues().containsKey(field) ? field : toSnake(field);
    }

    private static Object arg(List<Object> values, int i, String function) {
        if (values.size() <= i) {
            throw new EvaluationException(function + "() needs at least " + (i + 1) + " argument(s).");
        }
        return values.get(i);
    }

    private static String substring(String s, int from, int to) {
        int a = Math.max(0, Math.min(from, s.length()));
        int b = Math.max(a, Math.min(to, s.length()));
        return s.substring(a, b);
    }

    // ------------------------------------------------------------------ coercion

    static boolean isNumeric(Object value) {
        return value instanceof Number;
    }

    private static boolean isDate(Object value) {
        return value instanceof LocalDate || value instanceof Instant
                || value instanceof java.sql.Date || value instanceof java.sql.Timestamp;
    }

    static BigDecimal number(Object value, String context) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        if (value instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException ex) {
            throw new EvaluationException("'" + value + "' is not a number, so it cannot be used with '"
                    + context + "'.");
        }
    }

    static String text(Object value) {
        if (value == null) return "";
        if (value instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().toString();
        if (value instanceof java.sql.Date d) return d.toLocalDate().toString();
        return String.valueOf(value);
    }

    static LocalDate date(Object value) {
        if (value == null) throw new EvaluationException("A date was expected but the value is blank.");
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        if (value instanceof Instant i) return i.atZone(ZoneOffset.UTC).toLocalDate();
        try {
            return LocalDate.parse(text(value).substring(0, Math.min(10, text(value).length())));
        } catch (RuntimeException ex) {
            throw new EvaluationException("'" + value + "' is not a date.");
        }
    }

    private static String describe(Object value) {
        if (value == null) return "nothing";
        return "'" + text(value) + "'";
    }
}

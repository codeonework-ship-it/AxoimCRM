package com.axiom.automation;

import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The syntax checker and test evaluator FR-AUT-009 requires.
 *
 * <p>Both exist so an administrator finds out a formula is wrong while writing
 * it, not when a rule silently stops firing at 2am. The checker never throws to
 * the caller: an invalid formula is a normal answer to "is this formula valid",
 * so it comes back as {@code valid=false} with the position, and only a broken
 * request is an HTTP error.
 */
@Service
public class ExpressionService {

    private final ObjectMetadataService metadata;

    public ExpressionService(ObjectMetadataService metadata) {
        this.metadata = metadata;
    }

    public record CheckRequest(@NotBlank String expression, String objectType) {}

    /**
     * @param position 1-based character offset of the problem, null when valid
     * @param pointer  a caret line to render under the formula in a monospace editor
     */
    public record CheckResult(boolean valid, String message, Integer position, String pointer,
                              List<String> referencedFields, List<String> unknownFields) {}

    public record EvaluateRequest(@NotBlank String expression, String objectType, UUID recordId,
                                  Map<String, Object> newValues, Map<String, Object> oldValues) {}

    public record EvaluateResult(boolean ok, Object value, String type, String message,
                                 Integer position, Map<String, Object> context) {}

    /** Function catalogue for the builder's picker; grouped so the UI need not hard-code it. */
    public Map<String, List<String>> functionCatalogue() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("text", List.of("UPPER", "LOWER", "TRIM", "LEN", "CONTAINS", "STARTSWITH", "ENDSWITH",
                "LEFT", "RIGHT", "MID", "FIND", "SUBSTITUTE", "CONCAT", "TEXT"));
        groups.put("number", List.of("VALUE", "ABS", "ROUND", "FLOOR", "CEILING", "MOD", "MIN", "MAX"));
        groups.put("date", List.of("TODAY", "NOW", "DATE", "YEAR", "MONTH", "DAY", "DAYS_BETWEEN",
                "ADDDAYS", "ADDMONTHS"));
        groups.put("logical", List.of("IF", "AND", "OR", "NOT", "ISBLANK", "ISNULL", "NULLVALUE", "CASE"));
        groups.put("record", List.of("ISCHANGED", "PRIORVALUE", "ISNEW"));
        return groups;
    }

    @Transactional(readOnly = true)
    public CheckResult check(CheckRequest request) {
        AutomationAccess.requireRead();
        try {
            ExpressionParser.Node node = ExpressionParser.parse(request.expression());
            List<String> referenced = referencedFields(node);
            List<String> unknown = List.of();
            if (request.objectType() != null && !request.objectType().isBlank()) {
                ObjectMetadataService.ObjectDescriptor object = metadata.describe(request.objectType());
                unknown = referenced.stream()
                        .filter(f -> !object.columns().containsKey(f))
                        .filter(f -> !object.columns().containsKey(ExpressionEvaluator.toSnake(f)))
                        .sorted()
                        .toList();
            }
            if (!unknown.isEmpty()) {
                return new CheckResult(false,
                        "The formula parses, but " + String.join(", ", unknown)
                                + " is not a field of " + request.objectType() + ".",
                        null, null, referenced, unknown);
            }
            return new CheckResult(true, "The formula is valid.", null, null, referenced, List.of());
        } catch (ExpressionSyntaxException ex) {
            return new CheckResult(false, ex.getMessage() + " (position " + ex.position() + ")",
                    ex.position(), ex.pointer(), List.of(), List.of());
        }
    }

    /**
     * Evaluate against either a real record (read-only) or a supplied value map.
     * Reading a real record is what makes the test evaluator honest: the values
     * are the ones the rule would actually see.
     */
    @Transactional(readOnly = true)
    public EvaluateResult evaluate(EvaluateRequest request) {
        AutomationAccess.requireRead();
        Map<String, Object> newValues = new LinkedHashMap<>();
        Map<String, Object> oldValues = new LinkedHashMap<>();
        if (request.recordId() != null && request.objectType() != null) {
            newValues.putAll(metadata.readRecord(request.objectType(), request.recordId()));
        }
        if (request.newValues() != null) newValues.putAll(request.newValues());
        if (request.oldValues() != null) oldValues.putAll(request.oldValues());

        ExpressionEvaluator.Context context = new ExpressionEvaluator.Context(
                newValues, oldValues, oldValues.isEmpty(), Map.of());
        try {
            Object value = ExpressionEvaluator.evaluate(request.expression(), context);
            return new EvaluateResult(true, ExpressionEvaluator.text(value),
                    value == null ? "null" : value.getClass().getSimpleName(),
                    "Evaluated against " + newValues.size() + " field(s).", null, newValues);
        } catch (ExpressionSyntaxException ex) {
            return new EvaluateResult(false, null, null,
                    ex.getMessage() + " (position " + ex.position() + ")", ex.position(), newValues);
        } catch (ExpressionEvaluator.EvaluationException ex) {
            return new EvaluateResult(false, null, null, ex.getMessage(), null, newValues);
        }
    }

    /** Field names a formula reads — the builder highlights them, the checker validates them. */
    public static List<String> referencedFields(ExpressionParser.Node node) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        collect(node, out);
        return List.copyOf(out);
    }

    private static void collect(ExpressionParser.Node node, java.util.Set<String> out) {
        if (node instanceof ExpressionParser.Reference ref) {
            out.add(ref.field());
        } else if (node instanceof ExpressionParser.Unary u) {
            collect(u.operand(), out);
        } else if (node instanceof ExpressionParser.Binary b) {
            collect(b.left(), out);
            collect(b.right(), out);
        } else if (node instanceof ExpressionParser.FunctionCall c) {
            c.arguments().forEach(a -> collect(a, out));
        }
    }

    /** Used by every writer of a formula so an unparseable one never reaches the database. */
    public static void requireParseable(String expression, String what) {
        if (expression == null || expression.isBlank()) return;
        try {
            ExpressionParser.parse(expression);
        } catch (ExpressionSyntaxException ex) {
            throw new IllegalArgumentException(what + " is not a valid formula: " + ex.getMessage()
                    + " (position " + ex.position() + ")");
        }
    }

    /** Present so the tenant is visible in traces without another lookup. */
    public UUID tenantId() {
        return TenantContext.get().tenantId();
    }
}

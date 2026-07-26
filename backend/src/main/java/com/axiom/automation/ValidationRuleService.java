package com.axiom.automation;

import com.axiom.audit.AuditService;
import com.axiom.common.BulkValidationException;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Record-level validation with a custom message and a target field (FR-AUT-005).
 *
 * <h2>Polarity</h2>
 * The expression states when the record is <em>invalid</em>, matching the
 * convention every administrator arriving from Salesforce already has. The
 * alternative — "true means valid" — reads better in isolation and produces
 * double negatives the moment anyone writes {@code NOT(ISBLANK(x))}.
 *
 * <h2>The target field is part of the contract</h2>
 * A validation failure that cannot say which field to fix is a dialog the user
 * dismisses. The failure is returned as {@code field: message} in the error
 * details so a form can attach it to the right input.
 */
@Service
public class ValidationRuleService {

    private final JdbcTemplate jdbc;
    private final ObjectMetadataService metadata;
    private final AuditService audit;

    @Autowired
    public ValidationRuleService(JdbcTemplate jdbc, ObjectMetadataService metadata, AuditService audit) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.audit = audit;
    }

    public record ValidationRuleView(UUID id, String ruleCode, String name, String objectType,
                                     String expression, String message, String targetField,
                                     boolean active) {}

    public record ValidationMutation(@NotBlank String ruleCode, @NotBlank String name,
                                     @NotBlank String objectType, @NotBlank String expression,
                                     @NotBlank String message, String targetField, Boolean active) {}

    public record ValidationFailure(String ruleCode, String message, String targetField) {}

    @Transactional(readOnly = true)
    public List<ValidationRuleView> list(String objectType) {
        AutomationAccess.requireRead();
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        String filter = "";
        if (objectType != null && !objectType.isBlank()) {
            filter = " and object_type = ?";
            args.add(objectType.toUpperCase(Locale.ROOT));
        }
        return jdbc.query("""
                select id, rule_code, name, object_type, expression, message, target_field, active
                from automation.validation_rule where tenant_id = ?""" + filter + """

                order by object_type, rule_code
                """, (rs, i) -> map(rs), args.toArray());
    }

    @Transactional
    public ValidationRuleView create(ValidationMutation request) {
        AutomationAccess.requireAdmin("define validation rules");
        ObjectMetadataService.ObjectDescriptor object = metadata.describe(request.objectType());
        ExpressionService.requireParseable(request.expression(), "The validation expression");
        if (request.targetField() != null && !request.targetField().isBlank()) {
            metadata.requireColumn(object, request.targetField());
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into automation.validation_rule
                      (id, tenant_id, rule_code, name, object_type, expression, message, target_field,
                       active, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, TenantContext.get().tenantId(), request.ruleCode(), request.name(),
                    object.objectType(), request.expression(), request.message(), request.targetField(),
                    request.active() == null || request.active(), TenantContext.get().userId());
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ConflictException("A validation rule with code " + request.ruleCode()
                    + " already exists.");
        }
        audit.record("VALIDATION_RULE_CREATED", "AUTOMATION_VALIDATION", id,
                "Created validation rule " + request.ruleCode(),
                Map.of("ruleCode", request.ruleCode(), "objectType", object.objectType()));
        return get(id);
    }

    @Transactional
    public ValidationRuleView update(UUID id, ValidationMutation request) {
        AutomationAccess.requireAdmin("change validation rules");
        get(id);
        ObjectMetadataService.ObjectDescriptor object = metadata.describe(request.objectType());
        ExpressionService.requireParseable(request.expression(), "The validation expression");
        if (request.targetField() != null && !request.targetField().isBlank()) {
            metadata.requireColumn(object, request.targetField());
        }
        jdbc.update("""
                update automation.validation_rule
                set name = ?, object_type = ?, expression = ?, message = ?, target_field = ?,
                    active = ?, updated_at = now()
                where tenant_id = ? and id = ?
                """, request.name(), object.objectType(), request.expression(), request.message(),
                request.targetField(), request.active() == null || request.active(),
                TenantContext.get().tenantId(), id);
        audit.record("VALIDATION_RULE_UPDATED", "AUTOMATION_VALIDATION", id,
                "Updated validation rule " + request.ruleCode(), Map.of("ruleCode", request.ruleCode()));
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        AutomationAccess.requireAdmin("delete validation rules");
        ValidationRuleView rule = get(id);
        jdbc.update("delete from automation.validation_rule where tenant_id = ? and id = ?",
                TenantContext.get().tenantId(), id);
        audit.record("VALIDATION_RULE_DELETED", "AUTOMATION_VALIDATION", id,
                "Deleted validation rule " + rule.ruleCode(), Map.of("ruleCode", rule.ruleCode()));
    }

    @Transactional(readOnly = true)
    public ValidationRuleView get(UUID id) {
        List<ValidationRuleView> rows = jdbc.query("""
                select id, rule_code, name, object_type, expression, message, target_field, active
                from automation.validation_rule where tenant_id = ? and id = ?
                """, (rs, i) -> map(rs), TenantContext.get().tenantId(), id);
        if (rows.isEmpty()) throw new NotFoundException("No validation rule with that id");
        return rows.getFirst();
    }

    /**
     * Evaluate every active rule for an object against a proposed record.
     *
     * <p>All rules run, not just the first that fails: telling a user about one
     * problem at a time turns a single correction into three round trips.
     */
    @Transactional(readOnly = true)
    public List<ValidationFailure> evaluate(String objectType, Map<String, Object> proposed,
                                            Map<String, Object> previous) {
        List<ValidationRuleView> rules = jdbc.query("""
                select id, rule_code, name, object_type, expression, message, target_field, active
                from automation.validation_rule
                where tenant_id = ? and object_type = ? and active
                order by rule_code
                """, (rs, i) -> map(rs), TenantContext.get().tenantId(),
                objectType.toUpperCase(Locale.ROOT));

        ExpressionEvaluator.Context context = new ExpressionEvaluator.Context(
                proposed, previous == null ? Map.of() : previous,
                previous == null || previous.isEmpty(), Map.of());

        List<ValidationFailure> failures = new ArrayList<>();
        for (ValidationRuleView rule : rules) {
            boolean invalid;
            try {
                invalid = ExpressionEvaluator.condition(rule.expression(), context);
            } catch (RuntimeException ex) {
                // A validation rule that cannot be evaluated must not silently pass:
                // a control that fails open is not a control.
                failures.add(new ValidationFailure(rule.ruleCode(),
                        "Validation rule " + rule.ruleCode() + " could not be evaluated: "
                                + ex.getMessage(), rule.targetField()));
                continue;
            }
            if (invalid) {
                failures.add(new ValidationFailure(rule.ruleCode(), rule.message(), rule.targetField()));
            }
        }
        return failures;
    }

    /** Evaluate and refuse, in the shape the API error envelope already carries. */
    public void assertValid(String objectType, Map<String, Object> proposed,
                            Map<String, Object> previous) {
        List<ValidationFailure> failures = evaluate(objectType, proposed, previous);
        if (failures.isEmpty()) return;
        throw new BulkValidationException(
                failures.size() == 1 ? failures.getFirst().message()
                        : failures.size() + " validation rules refused this change.",
                failures.stream()
                        .map(f -> (f.targetField() == null ? "record" : f.targetField())
                                + ": " + f.message() + " [" + f.ruleCode() + "]")
                        .toList());
    }

    private ValidationRuleView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ValidationRuleView(rs.getObject("id", UUID.class), rs.getString("rule_code"),
                rs.getString("name"), rs.getString("object_type"), rs.getString("expression"),
                rs.getString("message"), rs.getString("target_field"), rs.getBoolean("active"));
    }
}

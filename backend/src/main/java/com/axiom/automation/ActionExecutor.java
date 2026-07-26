package com.axiom.automation;

import com.axiom.notifications.NotificationWriter;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The eight action verbs of FR-AUT-006, and the single place the engine decides
 * whether to actually do something.
 *
 * <h2>The dry-run guarantee lives here and nowhere else</h2>
 * FR-AUT-010 requires a simulation that shows every action that would occur with
 * <em>none of them occurring</em>. That is only credible if there is exactly one
 * branch in the codebase where "would" becomes "did". Every method below is
 * written as: compute the full description of the effect, then — and only if the
 * mode is LIVE — apply it. A reviewer can confirm the guarantee by checking that
 * every {@code jdbc.update}, every {@code outbox.write} and every
 * {@code notifications.notifyUser} in this file sits under a
 * {@code if (context.dryRun()) return would(...)} guard, and the unit test
 * asserts it from the outside by proving no mutating call reaches the
 * JdbcTemplate at all.
 *
 * <h2>Values are formulas, not literals</h2>
 * {@code "next_step": "'Review'"} sets the text Review; {@code "next_step":
 * "NEW.name"} copies a field. One rule for the whole action set means an
 * administrator never has to remember which inputs are interpolated.
 */
@Component
public class ActionExecutor {

    private final JdbcTemplate jdbc;
    private final ObjectMetadataService metadata;
    private final NotificationWriter notifications;
    private final OutboxWriter outbox;
    private final ApprovalService approvals;
    /**
     * Lazily resolved because a field update dispatches the rules that watch it,
     * and those rules execute through this class. ObjectProvider breaks the
     * constructor cycle without a proxy and without @Lazy on a field.
     */
    private final ObjectProvider<RecordChangeDispatcher> dispatcher;

    @Autowired
    public ActionExecutor(JdbcTemplate jdbc, ObjectMetadataService metadata,
                          NotificationWriter notifications, OutboxWriter outbox,
                          ApprovalService approvals, ObjectProvider<RecordChangeDispatcher> dispatcher) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.notifications = notifications;
        this.outbox = outbox;
        this.approvals = approvals;
        this.dispatcher = dispatcher;
    }

    /**
     * @param outcome    EXECUTED, WOULD_EXECUTE, FAILED or HALTED
     * @param cascadeHalt the FR-AUT-012 diagnostic when this action's write started
     *                    a cascade that had to be stopped
     */
    public record ActionResult(String outcome, String description, Map<String, Object> detail,
                               String cascadeHalt) {

        public boolean executed() { return "EXECUTED".equals(outcome); }

        public static ActionResult would(String description, Map<String, Object> detail) {
            return new ActionResult("WOULD_EXECUTE", description, detail, null);
        }

        public static ActionResult done(String description, Map<String, Object> detail) {
            return new ActionResult("EXECUTED", description, detail, null);
        }

        public static ActionResult failed(String description, Map<String, Object> detail) {
            return new ActionResult("FAILED", description, detail, null);
        }
    }

    public ActionResult perform(RuleModel.Step step, RunContext context, Map<String, Object> loopVars) {
        String actionType = step.actionType() == null ? "" : step.actionType().toUpperCase(Locale.ROOT);
        try {
            return switch (actionType) {
                case "UPDATE_FIELDS" -> updateFields(step, context, loopVars);
                case "CREATE_RECORD" -> createRecord(step, context, loopVars);
                case "CREATE_TASK" -> createTask(step, context, loopVars);
                case "SEND_EMAIL" -> sendEmail(step, context, loopVars);
                case "SEND_NOTIFICATION" -> sendNotification(step, context, loopVars);
                case "SUBMIT_FOR_APPROVAL" -> submitForApproval(step, context, loopVars);
                case "INVOKE_WEBHOOK" -> invokeWebhook(step, context, loopVars);
                case "CALL_INTEGRATION" -> callIntegration(step, context, loopVars);
                default -> ActionResult.failed("'" + step.actionType() + "' is not a supported action. "
                        + "Supported: " + String.join(", ", RuleModel.ACTION_TYPES), Map.of());
            };
        } catch (ExpressionSyntaxException ex) {
            return ActionResult.failed(ex.getMessage() + " (position " + ex.position() + ")",
                    Map.of("expression", String.valueOf(ex.expression())));
        } catch (RuntimeException ex) {
            return ActionResult.failed(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                    Map.of("error", ex.getClass().getSimpleName()));
        }
    }

    // ------------------------------------------------------------------ 1. update fields

    private ActionResult updateFields(RuleModel.Step step, RunContext context, Map<String, Object> loopVars) {
        ObjectMetadataService.ObjectDescriptor target = targetObject(step, context);
        UUID targetId = targetId(step, context, loopVars);
        if (targetId == null) {
            return ActionResult.failed("The related record to update could not be resolved from "
                    + step.relatedIdField() + ".", Map.of());
        }
        if (step.fields().isEmpty()) {
            return ActionResult.failed("An UPDATE_FIELDS action needs at least one field.", Map.of());
        }

        Map<String, Object> assignments = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : step.fields().entrySet()) {
            String column = metadata.requireWritableColumn(target, entry.getKey());
            Object value = ExpressionEvaluator.evaluate(entry.getValue(), context.evaluation(loopVars));
            assignments.put(column, coerce(target.columns().get(column), value));
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("object", target.objectType());
        detail.put("recordId", targetId.toString());
        detail.put("assignments", render(assignments));
        String description = "Set " + String.join(", ", assignments.keySet()) + " on "
                + target.objectType() + " " + targetId;

        if (context.dryRun()) {
            return ActionResult.would(description, detail);
        }

        Map<String, Object> before = metadata.readRecord(target, targetId);
        StringBuilder sql = new StringBuilder("update ").append(target.qualifiedTable()).append(" set ");
        List<Object> args = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, Object> a : assignments.entrySet()) {
            if (i++ > 0) sql.append(", ");
            sql.append(a.getKey()).append(" = ?");
            if ("jsonb".equals(target.columns().get(a.getKey()))) sql.append("::jsonb");
            args.add(a.getValue());
        }
        if (target.columns().containsKey("updated_at")) sql.append(", updated_at = now()");
        sql.append(" where tenant_id = ? and ").append(target.idColumn()).append(" = ?");
        args.add(TenantContext.get().tenantId());
        args.add(targetId);

        int updated = jdbc.update(sql.toString(), args.toArray());
        detail.put("rowsUpdated", updated);
        if (updated == 0) {
            return ActionResult.failed("No " + target.objectType() + " with id " + targetId
                    + " was visible to this tenant.", detail);
        }

        Map<String, Object> after = metadata.readRecord(target, targetId);
        RecordChangeDispatcher.DispatchResult cascade = dispatcher.getObject()
                .dispatch(target.objectType(), targetId, "UPDATE", before, after, context.cascadeDepth() + 1);
        if (cascade.halted()) {
            detail.put("cascade", cascade.haltDiagnostic());
            return new ActionResult("EXECUTED", description, detail, cascade.haltDiagnostic());
        }
        detail.put("cascadedRules", cascade.executions().size());
        return ActionResult.done(description, detail);
    }

    // ------------------------------------------------------------------ 2. create record

    private ActionResult createRecord(RuleModel.Step step, RunContext context, Map<String, Object> loopVars) {
        if (step.objectType() == null || step.objectType().isBlank()) {
            return ActionResult.failed("A CREATE_RECORD action needs an objectType.", Map.of());
        }
        ObjectMetadataService.ObjectDescriptor target = metadata.describe(step.objectType());
        Map<String, Object> assignments = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : step.values().entrySet()) {
            String column = metadata.requireWritableColumn(target, entry.getKey());
            Object value = ExpressionEvaluator.evaluate(entry.getValue(), context.evaluation(loopVars));
            assignments.put(column, coerce(target.columns().get(column), value));
        }
        Map<String, Object> detail = new LinkedHashMap<>(Map.of(
                "object", target.objectType(), "values", render(assignments)));
        String description = "Create a " + target.objectType() + " with "
                + String.join(", ", assignments.keySet());
        if (context.dryRun()) return ActionResult.would(description, detail);

        UUID id = insert(target, assignments);
        detail.put("recordId", id.toString());
        return ActionResult.done(description + " (id " + id + ")", detail);
    }

    // ------------------------------------------------------------------ 3. create task

    private ActionResult createTask(RuleModel.Step step, RunContext context, Map<String, Object> loopVars) {
        ObjectMetadataService.ObjectDescriptor activity = metadata.describe("ACTIVITY");
        String subject = ExpressionEvaluator.text(
                ExpressionEvaluator.evaluate(orDefault(step.subject(), "'Automation task'"),
                        context.evaluation(loopVars)));
        UUID owner = resolveUser(step.ownerField(), context, loopVars);
        Instant due = step.dueInDays() == null ? null
                : Instant.now().plus(step.dueInDays(), ChronoUnit.DAYS);

        Map<String, Object> assignments = new LinkedHashMap<>();
        assignments.put("activity_type", "TASK");
        assignments.put("subject", subject);
        assignments.put("status", "OPEN");
        assignments.put("priority", orDefault(step.priority(), "NORMAL"));
        assignments.put("related_entity_type", context.object().objectType());
        assignments.put("related_entity_id", context.recordId());
        assignments.put("owner_id", owner);
        assignments.put("created_by", TenantContext.get().userId());
        assignments.put("source", "AUTOMATION");
        if (due != null) assignments.put("due_at", java.sql.Timestamp.from(due));

        Map<String, Object> detail = new LinkedHashMap<>(Map.of(
                "subject", subject, "ownerId", String.valueOf(owner),
                "dueAt", due == null ? "none" : due.toString()));
        String description = "Create task \"" + subject + "\" on " + context.object().objectType()
                + " " + context.recordId();
        if (context.dryRun()) return ActionResult.would(description, detail);

        UUID id = insert(activity, assignments);
        detail.put("taskId", id.toString());
        return ActionResult.done(description + " (id " + id + ")", detail);
    }

    // ------------------------------------------------------------------ 4. send email

    private ActionResult sendEmail(RuleModel.Step step, RunContext context, Map<String, Object> loopVars) {
        String to = ExpressionEvaluator.text(ExpressionEvaluator.evaluate(
                orDefault(step.emailTo(), "''"), context.evaluation(loopVars)));
        String subject = ExpressionEvaluator.text(ExpressionEvaluator.evaluate(
                orDefault(step.subject(), "'Automation notice'"), context.evaluation(loopVars)));
        String body = ExpressionEvaluator.text(ExpressionEvaluator.evaluate(
                orDefault(step.message(), "''"), context.evaluation(loopVars)));

        Map<String, Object> detail = new LinkedHashMap<>(Map.of("to", to, "subject", subject, "body", body));
        String description = "Send email \"" + subject + "\" to " + (to.isBlank() ? "(unresolved)" : to);
        if (context.dryRun()) return ActionResult.would(description, detail);

        // Outbound mail is a side effect on the outside world, so it leaves through
        // the transactional outbox (ADR-003) rather than a synchronous SMTP call in
        // the middle of a business transaction that may still roll back.
        outbox.write("AUTOMATION_EMAIL", context.recordId(), "automation.email.requested",
                Map.of("to", to, "subject", subject, "body", body,
                        "ruleCode", context.ruleCode(),
                        "objectType", context.object().objectType(),
                        "recordId", String.valueOf(context.recordId())));
        return ActionResult.done(description, detail);
    }

    // ------------------------------------------------------------------ 5. send notification

    private ActionResult sendNotification(RuleModel.Step step, RunContext context, Map<String, Object> loopVars) {
        UUID recipient = resolveUser(step.recipientField(), context, loopVars);
        String title = ExpressionEvaluator.text(ExpressionEvaluator.evaluate(
                literalOrFormula(orDefault(step.title(), "Automation")), context.evaluation(loopVars)));
        String body = ExpressionEvaluator.text(ExpressionEvaluator.evaluate(
                literalOrFormula(orDefault(step.message(), "An automation rule fired.")),
                context.evaluation(loopVars)));

        Map<String, Object> detail = new LinkedHashMap<>(Map.of(
                "recipientUserId", String.valueOf(recipient), "title", title, "body", body));
        String description = "Notify " + recipient + ": " + title;
        if (context.dryRun()) return ActionResult.would(description, detail);

        notifications.notifyUser(TenantContext.get().tenantId(), recipient, "AUTOMATION", "NORMAL",
                title, body, null, "Automation rule " + context.ruleCode(), false);
        return ActionResult.done(description, detail);
    }

    // ------------------------------------------------------------------ 6. submit for approval

    private ActionResult submitForApproval(RuleModel.Step step, RunContext context, Map<String, Object> loopVars) {
        String processCode = step.approvalProcessCode();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("approvalProcessCode", String.valueOf(processCode));
        detail.put("object", context.object().objectType());
        detail.put("recordId", String.valueOf(context.recordId()));
        String description = "Submit " + context.object().objectType() + " " + context.recordId()
                + " to approval process " + processCode;
        if (context.dryRun()) {
            ApprovalService.ApprovalPreview preview =
                    approvals.preview(processCode, context.object().objectType(), context.recordId());
            detail.put("wouldRouteTo", preview.approverEmails());
            detail.put("steps", preview.stepNames());
            return ActionResult.would(description + " (first approvers: "
                    + String.join(", ", preview.approverEmails()) + ")", detail);
        }
        ApprovalService.ApprovalInstanceView instance = approvals.submit(
                new ApprovalService.SubmitRequest(processCode, context.object().objectType(),
                        context.recordId(), null));
        detail.put("approvalInstanceId", instance.id().toString());
        return ActionResult.done(description + " (instance " + instance.id() + ")", detail);
    }

    // ------------------------------------------------------------------ 7. webhook

    private ActionResult invokeWebhook(RuleModel.Step step, RunContext context, Map<String, Object> loopVars) {
        String url = orDefault(step.webhookUrl(), "");
        Map<String, Object> payload = renderPayload(step, context, loopVars);
        Map<String, Object> detail = new LinkedHashMap<>(Map.of("url", url, "payload", payload));
        String description = "POST to webhook " + url;
        if (context.dryRun()) return ActionResult.would(description, detail);
        if (url.isBlank()) return ActionResult.failed("An INVOKE_WEBHOOK action needs a webhookUrl.", detail);

        outbox.write("AUTOMATION_WEBHOOK", context.recordId(), "automation.webhook.requested",
                Map.of("url", url, "payload", payload, "ruleCode", context.ruleCode()));
        return ActionResult.done(description, detail);
    }

    // ------------------------------------------------------------------ 8. named integration

    private ActionResult callIntegration(RuleModel.Step step, RunContext context, Map<String, Object> loopVars) {
        String name = orDefault(step.integrationName(), "");
        Map<String, Object> payload = renderPayload(step, context, loopVars);
        Map<String, Object> detail = new LinkedHashMap<>(Map.of("integration", name, "payload", payload));
        String description = "Call named integration " + name;
        if (context.dryRun()) return ActionResult.would(description, detail);
        if (name.isBlank()) return ActionResult.failed("A CALL_INTEGRATION action needs an integrationName.", detail);

        outbox.write("AUTOMATION_INTEGRATION", context.recordId(), "automation.integration.requested",
                Map.of("integration", name, "payload", payload, "ruleCode", context.ruleCode()));
        return ActionResult.done(description, detail);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> renderPayload(RuleModel.Step step, RunContext context,
                                              Map<String, Object> loopVars) {
        Map<String, Object> payload = new LinkedHashMap<>();
        step.payload().forEach((k, formula) -> payload.put(k, ExpressionEvaluator.text(
                ExpressionEvaluator.evaluate(formula, context.evaluation(loopVars)))));
        if (payload.isEmpty()) {
            payload.put("objectType", context.object().objectType());
            payload.put("recordId", String.valueOf(context.recordId()));
        }
        return payload;
    }

    private ObjectMetadataService.ObjectDescriptor targetObject(RuleModel.Step step, RunContext context) {
        if ("RELATED".equalsIgnoreCase(step.target()) && step.relatedObjectType() != null) {
            return metadata.describe(step.relatedObjectType());
        }
        return context.object();
    }

    private UUID targetId(RuleModel.Step step, RunContext context, Map<String, Object> loopVars) {
        if (!"RELATED".equalsIgnoreCase(step.target())) return context.recordId();
        String field = step.relatedIdField();
        if (field == null || field.isBlank()) return null;
        Object value = loopVars.containsKey(field) ? loopVars.get(field) : context.newValues().get(field);
        return toUuid(value);
    }

    private UUID resolveUser(String field, RunContext context, Map<String, Object> loopVars) {
        if (field != null && !field.isBlank()) {
            Object value = loopVars.containsKey(field) ? loopVars.get(field) : context.newValues().get(field);
            UUID id = toUuid(value);
            if (id != null) return id;
        }
        String ownerColumn = context.object().ownerColumn();
        if (ownerColumn != null) {
            UUID owner = toUuid(context.newValues().get(ownerColumn));
            if (owner != null) return owner;
        }
        return TenantContext.get().userId();
    }

    private UUID insert(ObjectMetadataService.ObjectDescriptor target, Map<String, Object> assignments) {
        Map<String, Object> row = new LinkedHashMap<>(assignments);
        row.put("tenant_id", TenantContext.get().tenantId());
        UUID id = UUID.randomUUID();
        row.put(target.idColumn(), id);
        List<String> columns = new ArrayList<>(row.keySet());
        String sql = "insert into " + target.qualifiedTable() + " ("
                + String.join(", ", columns) + ") values ("
                + columns.stream()
                    .map(c -> "jsonb".equals(target.columns().get(c)) ? "?::jsonb" : "?")
                    .collect(java.util.stream.Collectors.joining(", "))
                + ")";
        jdbc.update(sql, columns.stream().map(row::get).toArray());
        return id;
    }

    /** A bare word in a title/body field is text, not a formula; quoted or functional input is a formula. */
    private static String literalOrFormula(String value) {
        String trimmed = value.trim();
        boolean looksLikeFormula = trimmed.startsWith("'") || trimmed.startsWith("\"")
                || trimmed.contains("(") || trimmed.startsWith("NEW.") || trimmed.startsWith("OLD.");
        return looksLikeFormula ? trimmed : "'" + trimmed.replace("'", "''") + "'";
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static UUID toUuid(Object value) {
        if (value == null) return null;
        if (value instanceof UUID u) return u;
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Map<String, Object> render(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        values.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
        return out;
    }

    /**
     * Formula results are Strings, BigDecimals, Booleans and LocalDates; columns
     * are numerics, uuids, dates and jsonb. This is the one conversion, driven by
     * the catalogue type rather than by guessing from the Java class — a String
     * bound into a numeric column is a driver error, not a business one.
     */
    static Object coerce(String dataType, Object value) {
        if (value == null) return null;
        String type = dataType == null ? "text" : dataType;
        try {
            if (type.startsWith("timestamp")) {
                if (value instanceof java.sql.Timestamp ts) return ts;
                if (value instanceof Instant i) return java.sql.Timestamp.from(i);
                return java.sql.Timestamp.from(ExpressionEvaluator.date(value)
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
            }
            return switch (type) {
                case "numeric", "double precision", "real" -> ExpressionEvaluator.number(value, "assignment");
                case "integer", "smallint" -> ExpressionEvaluator.number(value, "assignment").intValue();
                case "bigint" -> ExpressionEvaluator.number(value, "assignment").longValue();
                case "boolean" -> value instanceof Boolean b ? b
                        : Boolean.parseBoolean(ExpressionEvaluator.text(value));
                case "uuid" -> value instanceof UUID u ? u : UUID.fromString(ExpressionEvaluator.text(value));
                case "date" -> value instanceof LocalDate d ? java.sql.Date.valueOf(d)
                        : java.sql.Date.valueOf(ExpressionEvaluator.date(value));
                default -> ExpressionEvaluator.text(value);
            };
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Value '" + ExpressionEvaluator.text(value)
                    + "' cannot be stored in a " + type + " column.", ex);
        }
    }

    /** Exposed so the number coercion is testable without a database. */
    static BigDecimal asNumber(Object value) {
        return ExpressionEvaluator.number(value, "assignment");
    }
}

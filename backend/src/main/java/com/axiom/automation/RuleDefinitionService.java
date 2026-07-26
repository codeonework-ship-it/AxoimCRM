package com.axiom.automation;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Rule definitions and their versions (FR-AUT-001, 002, 003, 013).
 *
 * <h2>Every edit is a version</h2>
 * FR-AUT-013 asks for versioned definitions with an identifiable active version
 * and any prior version restorable. So {@code saveVersion} never overwrites: it
 * appends. Restoring is then a new version carrying the old document plus a
 * pointer to what it came from, which keeps the history linear — a restore that
 * rewound the version number would make the log say a version changed after it
 * was written, and that is the property the requirement is protecting.
 *
 * <h2>No rule cap (FR-AUT-014)</h2>
 * There is no count query in this class, no ceiling constant, and nothing in
 * {@code create} that can refuse a rule because there are already many. That is
 * a deliberate absence; the module's only resource protection is
 * {@link ThrottleService}, which is measured and visible.
 */
@Service
public class RuleDefinitionService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectMetadataService metadata;
    private final AuditService audit;

    @Autowired
    public RuleDefinitionService(JdbcTemplate jdbc, ObjectMapper json,
                                 ObjectMetadataService metadata, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.metadata = metadata;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ views

    public record RuleView(UUID id, String ruleCode, String name, String description, String objectType,
                           String triggerType, String status, int activeVersionNo, int executionOrder,
                           Instant createdAt, Instant updatedAt, int versionCount,
                           RuleModel.Definition definition) {}

    public record RuleVersionView(UUID id, int versionNo, String notes, Integer restoredFromVersionNo,
                                  Instant createdAt, boolean active, RuleModel.Definition definition) {}

    public record RuleMutation(@NotBlank String ruleCode, @NotBlank String name, String description,
                               @NotBlank String objectType, @NotBlank String triggerType,
                               Integer executionOrder, RuleModel.Definition definition, String notes) {}

    /** Just enough of a rule for the dispatcher to decide whether to run it. */
    public record ActiveRule(UUID id, String ruleCode, String name, String objectType,
                             String triggerType, int versionNo, RuleModel.Definition definition) {}

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<RuleView> list(String objectType, String status) {
        AutomationAccess.requireRead();
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        StringBuilder filter = new StringBuilder();
        if (objectType != null && !objectType.isBlank()) {
            filter.append(" and r.object_type = ?");
            args.add(objectType.toUpperCase(Locale.ROOT));
        }
        if (status != null && !status.isBlank()) {
            filter.append(" and r.status = ?");
            args.add(status.toUpperCase(Locale.ROOT));
        }
        return jdbc.query("""
                select r.id, r.rule_code, r.name, r.description, r.object_type, r.trigger_type,
                       r.status, r.active_version_no, r.execution_order, r.created_at, r.updated_at,
                       (select count(*) from automation.rule_version v
                         where v.tenant_id = r.tenant_id and v.rule_id = r.id) as version_count,
                       (select v.definition::text from automation.rule_version v
                         where v.tenant_id = r.tenant_id and v.rule_id = r.id
                           and v.version_no = r.active_version_no) as definition
                from automation.rule_definition r
                where r.tenant_id = ?""" + filter + """

                order by r.execution_order, r.rule_code
                """, (rs, i) -> mapRule(rs), args.toArray());
    }

    @Transactional(readOnly = true)
    public RuleView get(UUID id) {
        AutomationAccess.requireRead();
        List<RuleView> rows = jdbc.query("""
                select r.id, r.rule_code, r.name, r.description, r.object_type, r.trigger_type,
                       r.status, r.active_version_no, r.execution_order, r.created_at, r.updated_at,
                       (select count(*) from automation.rule_version v
                         where v.tenant_id = r.tenant_id and v.rule_id = r.id) as version_count,
                       (select v.definition::text from automation.rule_version v
                         where v.tenant_id = r.tenant_id and v.rule_id = r.id
                           and v.version_no = r.active_version_no) as definition
                from automation.rule_definition r
                where r.tenant_id = ? and r.id = ?
                """, (rs, i) -> mapRule(rs), TenantContext.get().tenantId(), id);
        if (rows.isEmpty()) throw new NotFoundException("No automation rule with that id");
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public List<RuleVersionView> versions(UUID ruleId) {
        AutomationAccess.requireRead();
        RuleView rule = get(ruleId);
        return jdbc.query("""
                select v.id, v.version_no, v.notes, v.restored_from_version_no, v.created_at,
                       v.definition::text as definition
                from automation.rule_version v
                where v.tenant_id = ? and v.rule_id = ?
                order by v.version_no desc
                """, (rs, i) -> new RuleVersionView(
                        rs.getObject("id", UUID.class), rs.getInt("version_no"), rs.getString("notes"),
                        (Integer) rs.getObject("restored_from_version_no"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("version_no") == rule.activeVersionNo(),
                        parse(rs.getString("definition"))),
                TenantContext.get().tenantId(), ruleId);
    }

    /**
     * The dispatcher's read: ACTIVE rules for an object whose trigger handles this
     * event, in execution order. Deliberately unfiltered by count.
     */
    @Transactional(readOnly = true)
    public List<ActiveRule> activeRecordChangeRules(String objectType, String event) {
        List<ActiveRule> rules = jdbc.query("""
                select r.id, r.rule_code, r.name, r.object_type, r.trigger_type, r.active_version_no,
                       v.definition::text as definition
                from automation.rule_definition r
                join automation.rule_version v
                  on v.tenant_id = r.tenant_id and v.rule_id = r.id and v.version_no = r.active_version_no
                where r.tenant_id = ? and r.object_type = ? and r.status = 'ACTIVE'
                  and r.trigger_type = 'RECORD_CHANGE'
                order by r.execution_order, r.rule_code
                """, (rs, i) -> new ActiveRule(
                        rs.getObject("id", UUID.class), rs.getString("rule_code"), rs.getString("name"),
                        rs.getString("object_type"), rs.getString("trigger_type"),
                        rs.getInt("active_version_no"), parse(rs.getString("definition"))),
                TenantContext.get().tenantId(), objectType.toUpperCase(Locale.ROOT));
        return rules.stream().filter(r -> r.definition().trigger().handles(event)).toList();
    }

    @Transactional(readOnly = true)
    public List<ActiveRule> activeScheduledRules() {
        return jdbc.query("""
                select r.id, r.rule_code, r.name, r.object_type, r.trigger_type, r.active_version_no,
                       v.definition::text as definition
                from automation.rule_definition r
                join automation.rule_version v
                  on v.tenant_id = r.tenant_id and v.rule_id = r.id and v.version_no = r.active_version_no
                where r.tenant_id = ? and r.status = 'ACTIVE' and r.trigger_type = 'SCHEDULED'
                order by r.execution_order, r.rule_code
                """, (rs, i) -> new ActiveRule(
                        rs.getObject("id", UUID.class), rs.getString("rule_code"), rs.getString("name"),
                        rs.getString("object_type"), rs.getString("trigger_type"),
                        rs.getInt("active_version_no"), parse(rs.getString("definition"))),
                TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public ActiveRule byCode(String ruleCode) {
        List<ActiveRule> rows = jdbc.query("""
                select r.id, r.rule_code, r.name, r.object_type, r.trigger_type, r.active_version_no,
                       v.definition::text as definition
                from automation.rule_definition r
                join automation.rule_version v
                  on v.tenant_id = r.tenant_id and v.rule_id = r.id and v.version_no = r.active_version_no
                where r.tenant_id = ? and r.rule_code = ?
                """, (rs, i) -> new ActiveRule(
                        rs.getObject("id", UUID.class), rs.getString("rule_code"), rs.getString("name"),
                        rs.getString("object_type"), rs.getString("trigger_type"),
                        rs.getInt("active_version_no"), parse(rs.getString("definition"))),
                TenantContext.get().tenantId(), ruleCode);
        if (rows.isEmpty()) throw new NotFoundException("No automation rule with code " + ruleCode);
        return rows.getFirst();
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public RuleView create(RuleMutation request) {
        AutomationAccess.requireAdmin("define automation rules");
        validate(request);
        TenantContext.Principal p = TenantContext.get();
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into automation.rule_definition
                      (id, tenant_id, rule_code, name, description, object_type, trigger_type,
                       status, active_version_no, execution_order, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, 'DRAFT', 1, ?, ?)
                    """, id, p.tenantId(), request.ruleCode(), request.name(), request.description(),
                    request.objectType().toUpperCase(Locale.ROOT),
                    request.triggerType().toUpperCase(Locale.ROOT),
                    request.executionOrder() == null ? 100 : request.executionOrder(), p.userId());
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ConflictException("A rule with code " + request.ruleCode() + " already exists.");
        }
        insertVersion(id, 1, request.definition(), request.notes() == null ? "Initial version" : request.notes(), null);
        audit.record("AUTOMATION_RULE_CREATED", "AUTOMATION_RULE", id,
                "Created automation rule " + request.ruleCode(),
                Map.of("ruleCode", request.ruleCode(), "objectType", request.objectType()));
        return get(id);
    }

    /** Appends a new version and makes it active. Never overwrites (FR-AUT-013). */
    @Transactional
    public RuleView saveVersion(UUID ruleId, RuleMutation request) {
        AutomationAccess.requireAdmin("change automation rules");
        RuleView existing = get(ruleId);
        validate(request);
        int next = nextVersionNo(ruleId);
        insertVersion(ruleId, next, request.definition(),
                request.notes() == null ? "Edited" : request.notes(), null);
        jdbc.update("""
                update automation.rule_definition
                set name = ?, description = ?, object_type = ?, trigger_type = ?, execution_order = ?,
                    active_version_no = ?, updated_at = now()
                where tenant_id = ? and id = ?
                """, request.name(), request.description(),
                request.objectType().toUpperCase(Locale.ROOT),
                request.triggerType().toUpperCase(Locale.ROOT),
                request.executionOrder() == null ? 100 : request.executionOrder(),
                next, TenantContext.get().tenantId(), ruleId);
        audit.record("AUTOMATION_RULE_VERSIONED", "AUTOMATION_RULE", ruleId,
                "Saved version " + next + " of " + existing.ruleCode(),
                Map.of("ruleCode", existing.ruleCode(), "versionNo", next));
        return get(ruleId);
    }

    /**
     * Restore a prior version by copying it forward as a new one (FR-AUT-013).
     * The restored document is byte-identical to the version being restored; only
     * its number and provenance are new.
     */
    @Transactional
    public RuleView restoreVersion(UUID ruleId, int versionNo) {
        AutomationAccess.requireAdmin("restore automation rule versions");
        RuleView rule = get(ruleId);
        String definition = jdbc.query("""
                        select definition::text from automation.rule_version
                        where tenant_id = ? and rule_id = ? and version_no = ?
                        """, rs -> rs.next() ? rs.getString(1) : null,
                TenantContext.get().tenantId(), ruleId, versionNo);
        if (definition == null) {
            throw new NotFoundException("Rule " + rule.ruleCode() + " has no version " + versionNo);
        }
        int next = nextVersionNo(ruleId);
        jdbc.update("""
                insert into automation.rule_version
                  (id, tenant_id, rule_id, version_no, definition, notes, restored_from_version_no, created_by)
                values (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, UUID.randomUUID(), TenantContext.get().tenantId(), ruleId, next, definition,
                "Restored from version " + versionNo, versionNo, TenantContext.get().userId());
        jdbc.update("""
                update automation.rule_definition set active_version_no = ?, updated_at = now()
                where tenant_id = ? and id = ?
                """, next, TenantContext.get().tenantId(), ruleId);
        audit.record("AUTOMATION_RULE_RESTORED", "AUTOMATION_RULE", ruleId,
                "Restored version " + versionNo + " of " + rule.ruleCode() + " as version " + next,
                Map.of("ruleCode", rule.ruleCode(), "restoredFrom", versionNo, "newVersion", next));
        return get(ruleId);
    }

    @Transactional
    public RuleView setStatus(UUID ruleId, String status) {
        AutomationAccess.requireAdmin("activate or pause automation rules");
        String target = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if (!List.of("DRAFT", "ACTIVE", "PAUSED", "RETIRED").contains(target)) {
            throw new IllegalArgumentException("Status must be DRAFT, ACTIVE, PAUSED or RETIRED.");
        }
        RuleView rule = get(ruleId);
        jdbc.update("""
                update automation.rule_definition set status = ?, updated_at = now()
                where tenant_id = ? and id = ?
                """, target, TenantContext.get().tenantId(), ruleId);
        audit.record("AUTOMATION_RULE_STATUS", "AUTOMATION_RULE", ruleId,
                rule.ruleCode() + " is now " + target,
                Map.of("ruleCode", rule.ruleCode(), "from", rule.status(), "to", target));
        return get(ruleId);
    }

    @Transactional
    public void delete(UUID ruleId) {
        AutomationAccess.requireAdmin("delete automation rules");
        RuleView rule = get(ruleId);
        jdbc.update("delete from automation.rule_definition where tenant_id = ? and id = ?",
                TenantContext.get().tenantId(), ruleId);
        audit.record("AUTOMATION_RULE_DELETED", "AUTOMATION_RULE", ruleId,
                "Deleted automation rule " + rule.ruleCode(), Map.of("ruleCode", rule.ruleCode()));
    }

    // ------------------------------------------------------------------ validation

    /**
     * Everything an administrator can get wrong is caught here, at save time,
     * naming the step — because the alternative is a rule that fails silently at
     * 2am with a stack trace in a log nobody reads.
     */
    void validate(RuleMutation request) {
        if (request.definition() == null) {
            throw new IllegalArgumentException("A rule needs a definition.");
        }
        ObjectMetadataService.ObjectDescriptor object = metadata.describe(request.objectType());
        ExpressionService.requireParseable(request.definition().entryCondition(), "The entry condition");
        validateSteps(request.definition().steps(), object, "step");
    }

    private void validateSteps(List<RuleModel.Step> steps, ObjectMetadataService.ObjectDescriptor object,
                               String path) {
        int i = 0;
        for (RuleModel.Step step : steps) {
            String where = path + "[" + (i++) + "]" + (step.key() == null ? "" : " (" + step.key() + ")");
            String type = step.type() == null ? "ACTION" : step.type().toUpperCase(Locale.ROOT);
            switch (type) {
                case "CONDITION", "BRANCH", "IF" -> {
                    if (step.expression() == null || step.expression().isBlank()) {
                        throw new IllegalArgumentException(where + ": a condition step needs an expression.");
                    }
                    ExpressionService.requireParseable(step.expression(), where + " expression");
                    validateSteps(step.thenSteps(), object, where + ".then");
                    validateSteps(step.elseSteps(), object, where + ".else");
                }
                case "LOOP", "FOR_EACH" -> {
                    if (step.relatedObject() == null || step.relatedForeignKey() == null) {
                        throw new IllegalArgumentException(where
                                + ": a loop needs relatedObject and relatedForeignKey.");
                    }
                    ObjectMetadataService.ObjectDescriptor child = metadata.describe(step.relatedObject());
                    metadata.requireColumn(child, step.relatedForeignKey());
                    validateSteps(step.body(), object, where + ".body");
                }
                default -> {
                    String action = step.actionType() == null ? "" : step.actionType().toUpperCase(Locale.ROOT);
                    if (!RuleModel.ACTION_TYPES.contains(action)) {
                        throw new IllegalArgumentException(where + ": '" + step.actionType()
                                + "' is not a supported action. Supported: "
                                + String.join(", ", RuleModel.ACTION_TYPES));
                    }
                    if ("UPDATE_FIELDS".equals(action)) {
                        if (step.fields().isEmpty()) {
                            throw new IllegalArgumentException(where + ": an update needs at least one field.");
                        }
                        ObjectMetadataService.ObjectDescriptor target =
                                "RELATED".equalsIgnoreCase(step.target()) && step.relatedObjectType() != null
                                        ? metadata.describe(step.relatedObjectType()) : object;
                        step.fields().forEach((field, formula) -> {
                            metadata.requireWritableColumn(target, field);
                            ExpressionService.requireParseable(formula, where + " field " + field);
                        });
                    }
                    if ("CREATE_RECORD".equals(action)) {
                        ObjectMetadataService.ObjectDescriptor target = metadata.describe(step.objectType());
                        step.values().forEach((field, formula) -> {
                            metadata.requireWritableColumn(target, field);
                            ExpressionService.requireParseable(formula, where + " value " + field);
                        });
                    }
                    ExpressionService.requireParseable(step.subject(), where + " subject");
                    ExpressionService.requireParseable(step.emailTo(), where + " recipient");
                }
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private int nextVersionNo(UUID ruleId) {
        Integer max = jdbc.queryForObject("""
                select coalesce(max(version_no), 0) from automation.rule_version
                where tenant_id = ? and rule_id = ?
                """, Integer.class, TenantContext.get().tenantId(), ruleId);
        return (max == null ? 0 : max) + 1;
    }

    private void insertVersion(UUID ruleId, int versionNo, RuleModel.Definition definition,
                               String notes, Integer restoredFrom) {
        jdbc.update("""
                insert into automation.rule_version
                  (id, tenant_id, rule_id, version_no, definition, notes, restored_from_version_no, created_by)
                values (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, UUID.randomUUID(), TenantContext.get().tenantId(), ruleId, versionNo,
                write(definition), notes, restoredFrom, TenantContext.get().userId());
    }

    private String write(RuleModel.Definition definition) {
        try {
            return json.writeValueAsString(definition);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("The rule definition could not be serialized", ex);
        }
    }

    RuleModel.Definition parse(String document) {
        if (document == null || document.isBlank()) {
            return new RuleModel.Definition(null, null, List.of());
        }
        try {
            return json.readValue(document, RuleModel.Definition.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored rule definition is not readable: " + ex.getMessage(), ex);
        }
    }

    private RuleView mapRule(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RuleView(rs.getObject("id", UUID.class), rs.getString("rule_code"),
                rs.getString("name"), rs.getString("description"), rs.getString("object_type"),
                rs.getString("trigger_type"), rs.getString("status"), rs.getInt("active_version_no"),
                rs.getInt("execution_order"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getInt("version_count"),
                parse(rs.getString("definition")));
    }
}

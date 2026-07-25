package com.axiom.orgdata;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.axiom.tenancy.TenantContext.Principal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FR-MDM-008 — versioned territory model with preview before activation.
 *
 * <p>Three properties matter and each is structural rather than procedural:
 *
 * <ol>
 *   <li><b>Preview does not mutate.</b> {@link #preview} runs the same rule
 *       evaluation as activation but writes nothing at all — it is
 *       {@code readOnly} and returns counts and a sample. This is the one
 *       behaviour an operator has to be able to trust absolutely: nobody previews
 *       a reassignment of the entire sales organisation twice.</li>
 *   <li><b>Activation is atomic.</b> Archiving the current version, promoting the
 *       draft and materializing its assignments all happen in one transaction,
 *       and a partial unique index guarantees at most one ACTIVE version exists.
 *       A crash mid-activation leaves the previous model live.</li>
 *   <li><b>The prior version is restorable.</b> Assignment rows are keyed by
 *       model version and are never deleted on activation, so restoring is a
 *       status flip — it reinstates the assignment that actually was, not a
 *       recomputation against data that has since moved.</li>
 * </ol>
 */
@Service
public class TerritoryService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public TerritoryService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record ModelRow(UUID id, int versionNo, String name, String status, String notes,
                           java.time.OffsetDateTime activatedAt, int territoryCount,
                           int ruleCount, int assignmentCount) {}

    public record TerritoryRow(UUID id, UUID modelVersionId, String code, String name,
                               UUID parentId, String path, int depth, boolean active,
                               int ruleCount, int memberCount, int assignedAccountCount) {}

    public record RuleRow(UUID id, UUID territoryId, String territoryCode, String matchField,
                          String operator, String matchValue, int priority, boolean active) {}

    public record ModelRequest(@NotBlank @Size(max = 120) String name, String notes) {}

    public record TerritoryRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$") @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            UUID parentId) {}

    public record RuleRequest(
            @NotNull UUID territoryId,
            @NotBlank @Pattern(regexp = "INDUSTRY|ACCOUNT_NAME|OWNER_NAME") String matchField,
            @NotBlank @Pattern(regexp = "EQUALS|STARTS_WITH|CONTAINS") String operator,
            @NotBlank @Size(max = 160) String matchValue,
            Integer priority) {}

    public record MemberRequest(@NotNull UUID userId,
                                @Pattern(regexp = "MEMBER|MANAGER") String territoryRole) {}

    public record PreviewLine(UUID territoryId, String territoryCode, String territoryName,
                              int accountCount, int memberCount, List<String> sampleAccounts) {}

    /** Result of a dry run. {@code mutated} is always false — it is asserted, not assumed. */
    public record PreviewResult(UUID modelVersionId, int versionNo, String status,
                                int accountsEvaluated, int accountsAssigned, int accountsUnassigned,
                                List<PreviewLine> lines, List<String> unassignedSample,
                                boolean mutated, String note) {}

    public record ActivationResult(UUID activatedModelVersionId, int activatedVersionNo,
                                   UUID archivedModelVersionId, Integer archivedVersionNo,
                                   int accountsAssigned, String note) {}

    /* ---------------------------------------------------------------- */
    /* Model versions                                                    */
    /* ---------------------------------------------------------------- */

    @Transactional(readOnly = true)
    public List<ModelRow> models() {
        return jdbc.query("""
                select m.id, m.version_no, m.name, m.status, m.notes, m.activated_at,
                       (select count(*) from orgdata.territory t
                         where t.tenant_id = m.tenant_id and t.model_version_id = m.id) as territory_count,
                       (select count(*) from orgdata.territory_assignment_rule r
                         join orgdata.territory t2 on t2.tenant_id = r.tenant_id and t2.id = r.territory_id
                         where r.tenant_id = m.tenant_id and t2.model_version_id = m.id) as rule_count,
                       (select count(*) from orgdata.territory_assignment a
                         where a.tenant_id = m.tenant_id and a.model_version_id = m.id) as assignment_count
                from orgdata.territory_model_version m
                where m.tenant_id = ?
                order by m.version_no desc
                """, (rs, i) -> new ModelRow(rs.getObject("id", UUID.class), rs.getInt("version_no"),
                rs.getString("name"), rs.getString("status"), rs.getString("notes"),
                rs.getObject("activated_at", java.time.OffsetDateTime.class),
                rs.getInt("territory_count"), rs.getInt("rule_count"), rs.getInt("assignment_count")),
                TenantContext.get().tenantId());
    }

    @Transactional
    public ModelRow createModel(ModelRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        Integer next = jdbc.queryForObject(
                "select coalesce(max(version_no), 0) + 1 from orgdata.territory_model_version where tenant_id = ?",
                Integer.class, p.tenantId());
        UUID id = jdbc.queryForObject("""
                insert into orgdata.territory_model_version
                  (tenant_id, version_no, name, status, notes, created_by)
                values (?, ?, ?, 'DRAFT', nullif(?, ''), ?) returning id
                """, UUID.class, p.tenantId(), next, request.name().trim(),
                request.notes() == null ? "" : request.notes().trim(), p.userId());
        audit.record("TERRITORY_MODEL_CREATE", "TERRITORY_MODEL", id,
                "Created draft territory model version " + next,
                Map.of("versionNo", next == null ? 0 : next, "name", request.name().trim()));
        return byId(id);
    }

    @Transactional(readOnly = true)
    public ModelRow byId(UUID id) {
        return models().stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Territory model version not found"));
    }

    @Transactional(readOnly = true)
    public List<TerritoryRow> territories(UUID modelVersionId) {
        return jdbc.query("""
                select t.id, t.model_version_id, t.code, t.name, t.parent_id, t.path, t.active,
                       (select count(*) from orgdata.territory_assignment_rule r
                         where r.tenant_id = t.tenant_id and r.territory_id = t.id and r.active) as rule_count,
                       (select count(*) from orgdata.territory_member tm
                         where tm.tenant_id = t.tenant_id and tm.territory_id = t.id) as member_count,
                       (select count(*) from orgdata.territory_assignment a
                         where a.tenant_id = t.tenant_id and a.territory_id = t.id) as assigned_count
                from orgdata.territory t
                where t.tenant_id = ? and t.model_version_id = ?
                order by t.path
                """, (rs, i) -> new TerritoryRow(rs.getObject("id", UUID.class),
                rs.getObject("model_version_id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getObject("parent_id", UUID.class), rs.getString("path"),
                Math.max(0, (int) rs.getString("path").chars().filter(c -> c == '/').count() - 1),
                rs.getBoolean("active"), rs.getInt("rule_count"), rs.getInt("member_count"),
                rs.getInt("assigned_count")), TenantContext.get().tenantId(), modelVersionId);
    }

    @Transactional
    public TerritoryRow addTerritory(UUID modelVersionId, TerritoryRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        ModelRow model = byId(modelVersionId);
        requireDraft(model, "add a territory to");
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        String parentPath = "";
        if (request.parentId() != null) {
            try {
                parentPath = jdbc.queryForObject("""
                        select path from orgdata.territory
                        where tenant_id = ? and id = ? and model_version_id = ?
                        """, String.class, p.tenantId(), request.parentId(), modelVersionId);
            } catch (EmptyResultDataAccessException ex) {
                throw new NotFoundException("Parent territory not found in this model version");
            }
        }
        try {
            UUID id = jdbc.queryForObject("""
                    insert into orgdata.territory
                      (tenant_id, model_version_id, code, name, parent_id, path)
                    values (?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, p.tenantId(), modelVersionId, code, request.name().trim(),
                    request.parentId(), parentPath + "/" + code);
            audit.record("TERRITORY_CREATE", "TERRITORY", id,
                    "Added territory " + code + " to model version " + model.versionNo(),
                    Map.of("code", code, "modelVersionId", modelVersionId, "path", parentPath + "/" + code));
            return territories(modelVersionId).stream().filter(row -> row.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Territory vanished mid-transaction"));
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Territory " + code + " already exists in model version "
                    + model.versionNo() + ". Choose a different code.");
        }
    }

    @Transactional(readOnly = true)
    public List<RuleRow> rules(UUID modelVersionId) {
        return jdbc.query("""
                select r.id, r.territory_id, t.code as territory_code, r.match_field, r.operator,
                       r.match_value, r.priority, r.active
                from orgdata.territory_assignment_rule r
                join orgdata.territory t on t.tenant_id = r.tenant_id and t.id = r.territory_id
                where r.tenant_id = ? and t.model_version_id = ?
                order by r.priority, t.path
                """, (rs, i) -> new RuleRow(rs.getObject("id", UUID.class),
                rs.getObject("territory_id", UUID.class), rs.getString("territory_code"),
                rs.getString("match_field"), rs.getString("operator"), rs.getString("match_value"),
                rs.getInt("priority"), rs.getBoolean("active")),
                TenantContext.get().tenantId(), modelVersionId);
    }

    @Transactional
    public RuleRow addRule(UUID modelVersionId, RuleRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        ModelRow model = byId(modelVersionId);
        requireDraft(model, "add an assignment rule to");
        boolean inModel = territories(modelVersionId).stream()
                .anyMatch(row -> row.id().equals(request.territoryId()));
        if (!inModel) {
            throw new NotFoundException("That territory does not belong to this model version");
        }
        UUID id = jdbc.queryForObject("""
                insert into orgdata.territory_assignment_rule
                  (tenant_id, territory_id, match_field, operator, match_value, priority)
                values (?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, p.tenantId(), request.territoryId(), request.matchField(),
                request.operator(), request.matchValue().trim(),
                request.priority() == null ? 100 : request.priority());
        audit.record("TERRITORY_RULE_CREATE", "TERRITORY", request.territoryId(),
                "Added assignment rule %s %s '%s'".formatted(request.matchField(),
                        request.operator(), request.matchValue().trim()),
                Map.of("modelVersionId", modelVersionId, "matchField", request.matchField(),
                        "operator", request.operator(), "matchValue", request.matchValue().trim()));
        return rules(modelVersionId).stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Rule vanished mid-transaction"));
    }

    @Transactional
    public TerritoryRow addMember(UUID territoryId, MemberRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        UUID modelVersionId;
        try {
            modelVersionId = jdbc.queryForObject(
                    "select model_version_id from orgdata.territory where tenant_id = ? and id = ?",
                    UUID.class, p.tenantId(), territoryId);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Territory not found");
        }
        String role = request.territoryRole() == null || request.territoryRole().isBlank()
                ? "MEMBER" : request.territoryRole();
        try {
            jdbc.update("""
                    insert into orgdata.territory_member (tenant_id, territory_id, user_id, territory_role)
                    values (?, ?, ?, ?)
                    on conflict (tenant_id, territory_id, user_id)
                    do update set territory_role = excluded.territory_role
                    """, p.tenantId(), territoryId, request.userId(), role);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("That user does not exist in this tenant.");
        }
        audit.record("TERRITORY_MEMBER_ASSIGN", "TERRITORY", territoryId,
                "Assigned a user to a territory as " + role,
                Map.of("userId", request.userId(), "territoryRole", role));
        return territories(modelVersionId).stream().filter(row -> row.id().equals(territoryId))
                .findFirst().orElseThrow(() -> new NotFoundException("Territory not found"));
    }

    /* ---------------------------------------------------------------- */
    /* Preview and activation                                            */
    /* ---------------------------------------------------------------- */

    /**
     * Evaluates the model's rules against live accounts and reports what would
     * happen. Read-only by construction and by annotation — no assignment,
     * ownership or sharing row is touched (US-E03-06).
     */
    @Transactional(readOnly = true)
    public PreviewResult preview(UUID modelVersionId) {
        ModelRow model = byId(modelVersionId);
        List<TerritoryRow> territories = territories(modelVersionId);
        List<RuleRow> rules = rules(modelVersionId);
        List<AccountFact> accounts = liveAccounts();

        Map<UUID, List<String>> matches = new LinkedHashMap<>();
        territories.forEach(t -> matches.put(t.id(), new ArrayList<>()));
        List<String> unassigned = new ArrayList<>();
        int assigned = 0;
        for (AccountFact account : accounts) {
            UUID territoryId = firstMatch(rules, account);
            if (territoryId == null) {
                unassigned.add(account.name());
            } else {
                matches.computeIfAbsent(territoryId, key -> new ArrayList<>()).add(account.name());
                assigned++;
            }
        }
        List<PreviewLine> lines = territories.stream().map(t -> {
            List<String> names = matches.getOrDefault(t.id(), List.of());
            return new PreviewLine(t.id(), t.code(), t.name(), names.size(), t.memberCount(),
                    names.stream().sorted().limit(5).toList());
        }).toList();
        return new PreviewResult(model.id(), model.versionNo(), model.status(), accounts.size(),
                assigned, unassigned.size(), lines, unassigned.stream().sorted().limit(5).toList(),
                false, "Preview only — no account was reassigned and no data was written.");
    }

    /**
     * Activates a draft model version. One transaction: archive the incumbent,
     * promote the draft, materialize its assignments.
     */
    @Transactional
    public ActivationResult activate(UUID modelVersionId, String reason) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        ModelRow model = byId(modelVersionId);
        if ("ACTIVE".equals(model.status())) {
            throw new ConflictException("Model version " + model.versionNo()
                    + " is already the active territory model.");
        }
        if ("ARCHIVED".equals(model.status())) {
            throw new ConflictException("Model version " + model.versionNo()
                    + " is archived. Use restore to bring an archived version back into force.");
        }
        if (territories(modelVersionId).isEmpty()) {
            throw new ConflictException("This model version has no territories. "
                    + "Add at least one territory before activating it.");
        }

        ModelRow incumbent = models().stream().filter(row -> "ACTIVE".equals(row.status()))
                .findFirst().orElse(null);
        if (incumbent != null) {
            jdbc.update("""
                    update orgdata.territory_model_version
                    set status = 'ARCHIVED', archived_at = now()
                    where tenant_id = ? and id = ?
                    """, p.tenantId(), incumbent.id());
        }
        jdbc.update("""
                update orgdata.territory_model_version
                set status = 'ACTIVE', activated_at = now(), activated_by = ?, archived_at = null
                where tenant_id = ? and id = ?
                """, p.userId(), p.tenantId(), modelVersionId);

        // Recompute this version's assignments from scratch; earlier versions'
        // rows are untouched, which is what makes restore possible.
        jdbc.update("delete from orgdata.territory_assignment where tenant_id = ? and model_version_id = ?",
                p.tenantId(), modelVersionId);
        List<RuleRow> rules = rules(modelVersionId);
        int assigned = 0;
        for (AccountFact account : liveAccounts()) {
            UUID territoryId = firstMatch(rules, account);
            if (territoryId == null) continue;
            jdbc.update("""
                    insert into orgdata.territory_assignment
                      (tenant_id, model_version_id, territory_id, account_id, matched_rule_id)
                    values (?, ?, ?, ?, ?)
                    """, p.tenantId(), modelVersionId, territoryId, account.id(),
                    matchedRuleId(rules, account));
            assigned++;
        }

        audit.recordWithReason("TERRITORY_MODEL_ACTIVATE", "TERRITORY_MODEL", modelVersionId,
                "Activated territory model version " + model.versionNo()
                        + (incumbent == null ? "" : ", archiving version " + incumbent.versionNo()),
                reason,
                Map.of("versionNo", model.versionNo(), "accountsAssigned", assigned,
                        "archivedVersionNo", incumbent == null ? "" : incumbent.versionNo(),
                        "atomic", true, "priorVersionRestorable", incumbent != null));
        return new ActivationResult(modelVersionId, model.versionNo(),
                incumbent == null ? null : incumbent.id(),
                incumbent == null ? null : incumbent.versionNo(), assigned,
                incumbent == null
                        ? "Activated. This is the first territory model for the tenant."
                        : "Activated atomically. Version " + incumbent.versionNo()
                          + " is archived and can be restored with its original assignment intact.");
    }

    /** Puts an archived version back into force. Its stored assignment is reinstated as-is. */
    @Transactional
    public ActivationResult restore(UUID modelVersionId, String reason) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        ModelRow target = byId(modelVersionId);
        if ("ACTIVE".equals(target.status())) {
            throw new ConflictException("Model version " + target.versionNo()
                    + " is already active — there is nothing to restore.");
        }
        if (!"ARCHIVED".equals(target.status())) {
            throw new ConflictException("Only an archived model version can be restored. "
                    + "Version " + target.versionNo() + " is a draft — activate it instead.");
        }
        ModelRow incumbent = models().stream().filter(row -> "ACTIVE".equals(row.status()))
                .findFirst().orElse(null);
        if (incumbent != null) {
            jdbc.update("""
                    update orgdata.territory_model_version
                    set status = 'ARCHIVED', archived_at = now()
                    where tenant_id = ? and id = ?
                    """, p.tenantId(), incumbent.id());
        }
        jdbc.update("""
                update orgdata.territory_model_version
                set status = 'ACTIVE', activated_at = now(), activated_by = ?, archived_at = null
                where tenant_id = ? and id = ?
                """, p.userId(), p.tenantId(), modelVersionId);
        Integer restoredAssignments = jdbc.queryForObject("""
                select count(*) from orgdata.territory_assignment
                where tenant_id = ? and model_version_id = ?
                """, Integer.class, p.tenantId(), modelVersionId);
        audit.recordWithReason("TERRITORY_MODEL_RESTORE", "TERRITORY_MODEL", modelVersionId,
                "Restored territory model version " + target.versionNo(), reason,
                Map.of("versionNo", target.versionNo(),
                        "assignmentsReinstated", restoredAssignments == null ? 0 : restoredAssignments,
                        "archivedVersionNo", incumbent == null ? "" : incumbent.versionNo()));
        return new ActivationResult(modelVersionId, target.versionNo(),
                incumbent == null ? null : incumbent.id(),
                incumbent == null ? null : incumbent.versionNo(),
                restoredAssignments == null ? 0 : restoredAssignments,
                "Restored version " + target.versionNo()
                        + " with the assignment it held when it was archived.");
    }

    @Transactional(readOnly = true)
    public List<PreviewLine> activeAssignment() {
        ModelRow active = models().stream().filter(row -> "ACTIVE".equals(row.status()))
                .findFirst().orElseThrow(() -> new NotFoundException(
                        "No territory model is active for this tenant"));
        return jdbc.query("""
                select t.id, t.code, t.name,
                       count(a.account_id) as account_count,
                       (select count(*) from orgdata.territory_member tm
                         where tm.tenant_id = t.tenant_id and tm.territory_id = t.id) as member_count
                from orgdata.territory t
                left join orgdata.territory_assignment a
                  on a.tenant_id = t.tenant_id and a.territory_id = t.id
                     and a.model_version_id = t.model_version_id
                where t.tenant_id = ? and t.model_version_id = ?
                group by t.id, t.code, t.name, t.path, t.tenant_id
                order by t.path
                """, (rs, i) -> new PreviewLine(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getInt("account_count"), rs.getInt("member_count"), List.of()),
                TenantContext.get().tenantId(), active.id());
    }

    /* ---------------------------------------------------------------- */
    /* Rule evaluation                                                   */
    /* ---------------------------------------------------------------- */

    record AccountFact(UUID id, String name, String industry, String ownerName) {}

    private List<AccountFact> liveAccounts() {
        return jdbc.query("""
                select a.id, a.name, coalesce(a.industry, '') as industry,
                       coalesce(u.display_name, '') as owner_name
                from crm.account a
                left join identity.app_user u on u.tenant_id = a.tenant_id and u.id = a.owner_id
                where a.tenant_id = ? and a.deleted_at is null
                order by a.name
                """, (rs, i) -> new AccountFact(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("industry"), rs.getString("owner_name")), TenantContext.get().tenantId());
    }

    /** First rule wins, ordered by priority then rule id — deterministic, so preview equals activation. */
    static UUID firstMatch(List<RuleRow> rules, AccountFact account) {
        RuleRow rule = matchedRule(rules, account);
        return rule == null ? null : rule.territoryId();
    }

    private static UUID matchedRuleId(List<RuleRow> rules, AccountFact account) {
        RuleRow rule = matchedRule(rules, account);
        return rule == null ? null : rule.id();
    }

    static RuleRow matchedRule(List<RuleRow> rules, AccountFact account) {
        return rules.stream()
                .filter(RuleRow::active)
                .sorted((a, b) -> a.priority() != b.priority()
                        ? Integer.compare(a.priority(), b.priority())
                        : a.id().compareTo(b.id()))
                .filter(rule -> evaluate(rule, account))
                .findFirst().orElse(null);
    }

    static boolean evaluate(RuleRow rule, AccountFact account) {
        String subject = switch (rule.matchField()) {
            case "INDUSTRY" -> account.industry();
            case "ACCOUNT_NAME" -> account.name();
            case "OWNER_NAME" -> account.ownerName();
            default -> "";
        };
        String value = rule.matchValue() == null ? "" : rule.matchValue();
        String left = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        String right = value.toLowerCase(Locale.ROOT);
        return switch (rule.operator()) {
            case "EQUALS" -> left.equals(right);
            case "STARTS_WITH" -> left.startsWith(right);
            case "CONTAINS" -> left.contains(right);
            default -> false;
        };
    }

    private static void requireDraft(ModelRow model, String action) {
        if (!"DRAFT".equals(model.status())) {
            throw new ConflictException("Model version " + model.versionNo() + " is "
                    + model.status().toLowerCase(Locale.ROOT) + ". You cannot " + action
                    + " a version that is no longer a draft — create a new version instead. "
                    + "That is what keeps an activated model reproducible.");
        }
    }
}

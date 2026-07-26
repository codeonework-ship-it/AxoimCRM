package com.axiom.migration;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Guided onboarding and configuration templates (FR-MIG-009, FR-MIG-010).
 *
 * <h2>Role-specific, because a shared checklist is nobody's</h2>
 * An administrator's first week and a salesperson's first week are different
 * jobs. One combined list means every user scrolls past most of it, learns that
 * the list is not for them, and stops reading — so the checklist is filtered to
 * the caller's own role and completion is tracked per role.
 *
 * <h2>Sample data is a migration plan</h2>
 * FR-MIG-010 wants an evaluation dataset that is "clearly marked and separately
 * deletable". Rather than a second deletion mechanism with its own bugs, sample
 * data is installed as a migration plan with {@code is_sample_data = true}: the
 * records carry a [SAMPLE] prefix, they are listed in the migration ledger like
 * any other imported record, and deleting them is the same audited rollback
 * that a real migration uses. Evaluation data therefore cannot become entangled
 * with migrated production data — they are different plans, and each removes
 * only its own.
 */
@Service
public class MigrationOnboardingService {

    private final JdbcTemplate jdbc;
    private final MigrationConnectionService connections;
    private final MigrationPlanService plans;
    private final MigrationRunService runs;
    private final AuditService audit;

    public MigrationOnboardingService(JdbcTemplate jdbc, MigrationConnectionService connections,
                                      MigrationPlanService plans, MigrationRunService runs,
                                      AuditService audit) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.plans = plans;
        this.runs = runs;
        this.audit = audit;
    }

    public record ChecklistItem(UUID id, String role, String taskKey, String title, String description,
                                String route, int sortOrder, Instant completedAt) {}

    public record Checklist(String role, List<ChecklistItem> items, int completed, int total,
                            int percentComplete) {}

    public record TemplateRow(String templateKey, String name, String industry, String companySize,
                              String description, boolean sampleData, boolean adopted, Instant appliedAt) {}

    // ------------------------------------------------------------------ checklist

    @Transactional(readOnly = true)
    public Checklist checklist(String roleOverride) {
        TenantContext.Principal principal = TenantContext.get();
        String role = roleOverride == null || roleOverride.isBlank()
                ? principal.role() : roleOverride.toUpperCase(Locale.ROOT);

        List<ChecklistItem> items = jdbc.query("""
                select id, role, task_key, title, description, route, sort_order, completed_at
                from migration.onboarding_task
                where tenant_id = ? and role = ?
                order by sort_order
                """, ITEM_MAPPER, principal.tenantId(), role);

        int completed = (int) items.stream().filter(i -> i.completedAt() != null).count();
        return new Checklist(role, items, completed, items.size(),
                items.isEmpty() ? 0 : (completed * 100) / items.size());
    }

    @Transactional
    public Checklist complete(UUID taskId, boolean done) {
        TenantContext.Principal principal = TenantContext.get();
        List<String> roles = jdbc.query("select role from migration.onboarding_task where tenant_id = ? and id = ?",
                (rs, i) -> rs.getString(1), principal.tenantId(), taskId);
        if (roles.isEmpty()) throw new NotFoundException("No onboarding task " + taskId);

        jdbc.update("""
                update migration.onboarding_task
                   set completed_at = case when ? then now() else null end,
                       completed_by = case when ? then ?::uuid else null end
                 where tenant_id = ? and id = ?
                """, done, done, principal.userId().toString(), principal.tenantId(), taskId);
        return checklist(roles.get(0));
    }

    // ------------------------------------------------------------------ templates

    @Transactional(readOnly = true)
    public List<TemplateRow> templates() {
        return jdbc.query("""
                select t.template_key, t.name, t.industry, t.company_size, t.description, t.is_sample_data,
                       a.applied_at
                from migration.config_template t
                left join migration.template_adoption a
                       on a.template_key = t.template_key and a.tenant_id = ?
                order by t.sort_order
                """, (rs, i) -> new TemplateRow(rs.getString("template_key"), rs.getString("name"),
                        rs.getString("industry"), rs.getString("company_size"), rs.getString("description"),
                        rs.getBoolean("is_sample_data"), rs.getTimestamp("applied_at") != null,
                        rs.getTimestamp("applied_at") == null ? null : rs.getTimestamp("applied_at").toInstant()),
                TenantContext.get().tenantId());
    }

    @Transactional
    public TemplateRow adopt(String templateKey) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requireMasterAdmin(principal.role());
        TemplateRow template = templates().stream()
                .filter(t -> t.templateKey().equals(templateKey)).findFirst()
                .orElseThrow(() -> new NotFoundException("No configuration template " + templateKey));

        jdbc.update("""
                insert into migration.template_adoption (tenant_id, template_key, applied_by)
                values (?, ?, ?)
                """, principal.tenantId(), templateKey, principal.userId());

        audit.record("MIGRATION_TEMPLATE_ADOPTED", "MIGRATION_TEMPLATE", null,
                "Adopted configuration template \"" + template.name() + "\"",
                Map.of("templateKey", templateKey, "industry", template.industry(),
                        "companySize", template.companySize()));
        return templates().stream().filter(t -> t.templateKey().equals(templateKey)).findFirst().orElseThrow();
    }

    /**
     * Install the evaluation dataset as its own, clearly marked plan and queue
     * the import. Returns the job handle — the sample data lands on the worker
     * tier like every other import.
     */
    @Transactional
    public MigrationModel.RunHandle installSampleData() {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requireMasterAdmin(principal.role());

        MigrationConnectionService.ConnectionRow connection = connections.connect(
                new MigrationConnectionService.ConnectRequest(
                        "Sample data source " + Instant.now().getEpochSecond(),
                        FixtureSourceAdapter.VENDOR, null, null, null, null, "axiom-sample"));
        connections.discover(connection.id());

        MigrationPlanService.PlanRow plan = plans.create(new MigrationPlanService.CreatePlanRequest(
                connection.id(), "Sample data (evaluation) " + Instant.now().getEpochSecond(), 365, true));
        plans.acknowledgeUnmapped(plan.id());

        audit.record("MIGRATION_SAMPLE_DATA_INSTALLED", "MIGRATION_PLAN", plan.id(),
                "Installed the clearly-marked sample-data environment as plan \"" + plan.name()
                + "\". It is separately deletable through the same audited rollback a real migration uses.",
                Map.of("planId", plan.id().toString(), "sampleData", "true"));

        return runs.queue(plan.id(), "IMPORT");
    }

    private static final RowMapper<ChecklistItem> ITEM_MAPPER = (rs, i) -> new ChecklistItem(
            rs.getObject("id", UUID.class), rs.getString("role"), rs.getString("task_key"),
            rs.getString("title"), rs.getString("description"), rs.getString("route"),
            rs.getInt("sort_order"),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
}

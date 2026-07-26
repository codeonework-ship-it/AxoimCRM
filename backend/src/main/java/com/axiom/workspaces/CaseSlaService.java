package com.axiom.workspaces;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CaseSlaService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CaseSlaService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Escalation(UUID id, UUID caseId, UUID milestoneId, String milestoneType,
                             int level, String status, String reason, Instant openedAt) {}
    public record SweepResult(UUID caseId, int missedMilestones, int createdEscalations,
                              String caseStatus, String message) {}

    @Transactional
    public SweepResult sweep(UUID caseId) {
        CrmRole.requireWrite(TenantContext.get().role());
        List<Map<String, Object>> cases = jdbc.queryForList("""
                select id, case_number, status from service.case_record
                where tenant_id = ? and id = ? and deleted_at is null for update
                """, TenantContext.get().tenantId(), caseId);
        if (cases.isEmpty()) throw new NotFoundException("Case not found");
        List<Map<String, Object>> missed = jdbc.queryForList("""
                select id, milestone_type, due_at from service.case_milestone
                where tenant_id = ? and case_id = ? and status = 'OPEN' and due_at < now()
                for update
                """, TenantContext.get().tenantId(), caseId);
        int created = 0;
        for (Map<String, Object> milestone : missed) {
            UUID milestoneId = (UUID) milestone.get("id");
            jdbc.update("update service.case_milestone set status = 'MISSED' where tenant_id = ? and id = ?",
                    TenantContext.get().tenantId(), milestoneId);
            created += jdbc.update("""
                    insert into service.case_escalation
                      (tenant_id, case_id, milestone_id, escalation_level, reason)
                    values (?, ?, ?, 1, ?)
                    on conflict (tenant_id, milestone_id, escalation_level) do nothing
                    """, TenantContext.get().tenantId(), caseId, milestoneId,
                    milestone.get("milestone_type") + " SLA passed at " + milestone.get("due_at"));
        }
        if (!missed.isEmpty()) {
            jdbc.update("""
                    update service.case_record set status = 'ESCALATED'
                    where tenant_id = ? and id = ? and status not in ('RESOLVED','CLOSED')
                    """, TenantContext.get().tenantId(), caseId);
            audit.record("CASE_SLA_ESCALATED", "CASE", caseId,
                    "Escalated case " + cases.getFirst().get("case_number") + " after SLA sweep",
                    Map.of("missedMilestones", missed.size(), "createdEscalations", created));
        }
        String status = missed.isEmpty() ? String.valueOf(cases.getFirst().get("status")) : "ESCALATED";
        return new SweepResult(caseId, missed.size(), created, status,
                missed.isEmpty() ? "No overdue open milestones were found." : "Overdue milestones were marked missed and escalated.");
    }

    @Transactional(readOnly = true)
    public List<Escalation> list(UUID caseId) {
        return jdbc.query("""
                select e.id, e.case_id, e.milestone_id, m.milestone_type, e.escalation_level,
                       e.status, e.reason, e.opened_at
                from service.case_escalation e
                join service.case_milestone m on m.tenant_id = e.tenant_id and m.id = e.milestone_id
                where e.tenant_id = ? and e.case_id = ? order by e.opened_at desc
                """, (rs, i) -> new Escalation(rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class),
                rs.getObject("milestone_id", UUID.class), rs.getString("milestone_type"),
                rs.getInt("escalation_level"), rs.getString("status"), rs.getString("reason"),
                rs.getTimestamp("opened_at").toInstant()), TenantContext.get().tenantId(), caseId);
    }
}

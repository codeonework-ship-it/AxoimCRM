package com.axiom.orgdata;

import com.axiom.audit.AuditService;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-MDM-010 — master-data change control.
 *
 * <p>The gate is deliberately tiny and knows nothing about the masters it
 * governs. A service that mutates governed master data asks {@link
 * #requiresApproval(String)} first; if the answer is yes it hands the payload to
 * {@link #enqueue} and returns "pending" instead of writing. {@link
 * MasterChangeControlService} owns the other half — deciding a request and then
 * calling the same service's apply path.
 *
 * <p>Splitting it this way is what keeps the dependency graph acyclic: the
 * mutating services depend on the gate, and the decision service depends on the
 * mutating services. If the gate itself had to apply changes, every governed
 * service would be circularly wired to it.
 *
 * <p>{@link #applying()} is the re-entrancy escape hatch. When an approved
 * request is finally applied, the service's normal mutation path runs again —
 * and it must not enqueue a second request for the change that was just
 * approved.
 */
@Component
public class MasterGovernanceGate {

    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;

    public MasterGovernanceGate(JdbcTemplate jdbc, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    public record GovernedMaster(String masterType, boolean requiresApproval, String description) {}

    /** True when this master needs an approval and we are not already applying one. */
    public boolean gated(String masterType) {
        return !APPLYING.get() && requiresApproval(masterType);
    }

    @Transactional(readOnly = true)
    public boolean requiresApproval(String masterType) {
        Boolean value = jdbc.query("""
                select requires_approval from orgdata.governed_master
                where tenant_id = ? and master_type = ?
                """, rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE,
                TenantContext.get().tenantId(), masterType);
        return Boolean.TRUE.equals(value);
    }

    @Transactional(readOnly = true)
    public List<GovernedMaster> registry() {
        return jdbc.query("""
                select master_type, requires_approval, description
                from orgdata.governed_master
                where tenant_id = ?
                order by master_type
                """, (rs, i) -> new GovernedMaster(rs.getString("master_type"),
                rs.getBoolean("requires_approval"), rs.getString("description")),
                TenantContext.get().tenantId());
    }

    @Transactional
    public GovernedMaster setRequiresApproval(String masterType, boolean requiresApproval) {
        int updated = jdbc.update("""
                update orgdata.governed_master
                set requires_approval = ?, updated_at = now(), updated_by = ?
                where tenant_id = ? and master_type = ?
                """, requiresApproval, TenantContext.get().userId(),
                TenantContext.get().tenantId(), masterType);
        if (updated == 0) {
            throw new com.axiom.common.NotFoundException(
                    "Unknown governed master type: " + masterType);
        }
        audit.record("MASTER_GOVERNANCE_UPDATE", "GOVERNED_MASTER", null,
                (requiresApproval ? "Enabled" : "Disabled") + " approval for " + masterType,
                Map.of("masterType", masterType, "requiresApproval", requiresApproval));
        return registry().stream().filter(m -> m.masterType().equals(masterType)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Governed master vanished mid-transaction"));
    }

    /** Records a pending change and returns its id. Nothing is applied here. */
    @Transactional
    public UUID enqueue(String masterType, String operation, UUID targetId, String summary,
                        Map<String, ?> payload) {
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into orgdata.master_change_request
                      (tenant_id, master_type, operation, target_id, summary, payload,
                       status, requested_by)
                    values (?, ?, ?, ?, ?, ?::jsonb, 'PENDING', ?)
                    returning id
                    """, UUID.class, TenantContext.get().tenantId(), masterType, operation,
                    targetId, summary, json.writeValueAsString(payload),
                    TenantContext.get().userId());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Change-request payload could not be serialized", ex);
        }
        audit.record("MASTER_CHANGE_REQUESTED", masterType, id,
                "Change queued for approval: " + summary,
                Map.of("operation", operation, "masterType", masterType, "changeRequestId", id));
        return id;
    }

    /**
     * Runs {@code action} with the gate suppressed, so an approved change is
     * written rather than re-queued.
     */
    public <T> T applying(java.util.function.Supplier<T> action) {
        APPLYING.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            APPLYING.remove();
        }
    }
}

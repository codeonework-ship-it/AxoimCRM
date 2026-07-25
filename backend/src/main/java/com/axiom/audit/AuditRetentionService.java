package com.axiom.audit;

import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * FR-AUD-006 — audit retention, configurable per tenant, minimum seven years,
 * independent of business-record retention.
 *
 * <p>The seven-year floor is enforced in three places, in decreasing order of
 * authority: a CHECK constraint on the table, this service, and the UI. The
 * constraint is the one that matters — a compliance floor that only the service
 * knows about is one hand-written UPDATE away from being gone.
 *
 * <p><b>Independence, stated precisely.</b> Nothing in the business retention
 * enforcement path ({@code com.axiom.compliance.RetentionService}) can reach the
 * audit trail: {@code governance.audit_event} grants no DELETE to any application
 * role and a trigger rejects one. So audit retention is a floor with no ceiling —
 * events are never destroyed by this platform at all. That is a superset of the
 * requirement, and it is stated here rather than implied so nobody later reads
 * "retention_years = 7" as a promise that year-eight events have been purged.
 */
@Service
public class AuditRetentionService {

    public static final int MINIMUM_YEARS = 7;

    public record AuditRetention(int retentionYears, boolean independentOfBusinessRetention,
                                 String legalBasis, String updatedByName, Instant updatedAt,
                                 long eventsRetained, Instant oldestEventAt,
                                 long eventsBeyondConfiguredWindow, String destructionPolicy) {}

    public record AuditRetentionRequest(@NotNull @Min(MINIMUM_YEARS) @Max(50) Integer retentionYears,
                                        String legalBasis) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public AuditRetentionService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public AuditRetention current() {
        return read();
    }

    @Transactional
    public AuditRetention update(AuditRetentionRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        int years = request.retentionYears() == null ? 0 : request.retentionYears();
        if (years < MINIMUM_YEARS) {
            throw new ConflictException("Audit retention cannot be set below " + MINIMUM_YEARS
                    + " years (FR-AUD-006). Requested " + years + ".");
        }
        if (years > 50) {
            throw new ConflictException("Audit retention cannot exceed 50 years. Requested " + years + ".");
        }
        AuditRetention before = read();
        TenantContext.Principal p = TenantContext.get();
        jdbc.update("""
                insert into governance.audit_retention_policy
                  (tenant_id, retention_years, legal_basis, updated_by, updated_by_name, updated_at)
                values (?, ?, coalesce(?, 'Statutory financial and data-protection record-keeping'), ?, ?, now())
                on conflict (tenant_id) do update
                  set retention_years = excluded.retention_years,
                      legal_basis = excluded.legal_basis,
                      updated_by = excluded.updated_by,
                      updated_by_name = excluded.updated_by_name,
                      updated_at = now()
                """, p.tenantId(), years, request.legalBasis(), p.userId(), p.displayName());
        audit.record("AUDIT_RETENTION_UPDATE", "AUDIT_RETENTION_POLICY", p.tenantId(),
                "Audit retention set to " + years + " years",
                Map.of("before", Map.of("retentionYears", before.retentionYears()),
                        "after", Map.of("retentionYears", years),
                        "minimumYears", MINIMUM_YEARS));
        return read();
    }

    private AuditRetention read() {
        java.util.UUID tenantId = TenantContext.get().tenantId();
        Map<String, Object> policy = jdbc.queryForList("""
                select retention_years, independent_of_business_retention, legal_basis,
                       updated_by_name, updated_at
                from governance.audit_retention_policy where tenant_id = ?
                """, tenantId).stream().findFirst().orElse(Map.of());
        int years = policy.get("retention_years") == null ? MINIMUM_YEARS
                : ((Number) policy.get("retention_years")).intValue();
        Map<String, Object> stats = jdbc.queryForMap("""
                select count(*) as total, min(occurred_at) as oldest,
                       count(*) filter (where occurred_at < now() - make_interval(years => ?)) as beyond
                from governance.audit_event where tenant_id = ?
                """, years, tenantId);
        Object updatedAt = policy.get("updated_at");
        Object oldest = stats.get("oldest");
        return new AuditRetention(years,
                policy.get("independent_of_business_retention") == null
                        || (Boolean) policy.get("independent_of_business_retention"),
                (String) policy.getOrDefault("legal_basis", "Statutory financial and data-protection record-keeping"),
                (String) policy.get("updated_by_name"),
                updatedAt instanceof java.sql.Timestamp ts ? ts.toInstant() : null,
                ((Number) stats.get("total")).longValue(),
                oldest instanceof java.sql.Timestamp ts ? ts.toInstant() : null,
                ((Number) stats.get("beyond")).longValue(),
                "Audit events are never destroyed by the platform. No application role holds DELETE on the "
                + "audit store and a trigger rejects one, so the configured window is a retention floor with no "
                + "ceiling. Destruction beyond the window, if a jurisdiction ever compels it, is an operations "
                + "procedure with its own approval trail — not something this service can do.");
    }
}

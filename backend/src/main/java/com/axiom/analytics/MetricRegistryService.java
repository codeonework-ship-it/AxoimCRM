package com.axiom.analytics;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The governed KPI registry (FR-RPT-009, doc 14 §3).
 *
 * <h2>The requirement, and where it is actually enforced</h2>
 * <i>"Two reports displaying the same named metric must compute it identically. A
 * metric with more than one active definition is a defect, not a configuration
 * choice."</i>
 *
 * <p>A defect that only application code prevents is a defect waiting for the next
 * code path — a bulk import, an admin screen, a migration, a support script. So
 * the rule lives in a partial unique index,
 * {@code uq_metric_definition_single_active}, and this class's job is to turn the
 * database's refusal into a message an administrator can act on. The check is not
 * "does an active one already exist?" followed by an insert: that is a race, and
 * two administrators publishing at once would win it.
 *
 * <h2>Versioned, superseded, never edited</h2>
 * Doc 14 requires a historical figure to remain reproducible under the definition
 * version in force when it was computed. Editing a formula in place destroys that
 * silently — every figure ever quoted under the old definition becomes
 * unexplainable. So {@link #publishNewVersion} retires the incumbent and inserts a
 * new version in one transaction, and there is no update path for {@code formula}
 * at all.
 *
 * <h2>Two ways to activate, and only one of them is governed</h2>
 * {@link #publishNewVersion} is the governed path: retire, then activate,
 * atomically. {@link #activate} exists for a draft an administrator prepared
 * earlier — and it deliberately does <em>not</em> retire the incumbent, so if one
 * is active the database refuses it. That refusal is the requirement working, not
 * an inconvenience to route around.
 */
@Service
public class MetricRegistryService {

    public record MetricDefinition(UUID id, String metricCode, String name, int version, String formula,
                                   String basis, String unit, String notes, String requirementRef,
                                   String sourceReference, String status, Instant publishedAt,
                                   Instant retiredAt, UUID supersedesId, Instant createdAt) {}

    public record DefinitionRequest(@NotBlank @Size(max = 64) String metricCode,
                                    @NotBlank @Size(max = 160) String name,
                                    @NotBlank @Size(max = 2000) String formula,
                                    @Size(max = 2000) String basis,
                                    @Size(max = 32) String unit,
                                    @Size(max = 2000) String notes,
                                    @Size(max = 240) String requirementRef,
                                    @Size(max = 240) String sourceReference,
                                    @Size(max = 240) String reason) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public MetricRegistryService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    /** The whole catalogue, every version, newest definition of each metric first. */
    @Transactional(readOnly = true)
    public List<MetricDefinition> catalogue() {
        return jdbc.query(SELECT + " where tenant_id = ? order by metric_code, version desc",
                MAPPER, TenantContext.get().tenantId());
    }

    /** The single published definition of one metric. */
    @Transactional(readOnly = true)
    public MetricDefinition active(String metricCode) {
        List<MetricDefinition> found = jdbc.query(
                SELECT + " where tenant_id = ? and metric_code = ? and status = 'ACTIVE'",
                MAPPER, TenantContext.get().tenantId(), normalise(metricCode));
        if (found.isEmpty()) {
            throw new NotFoundException("No published definition for metric " + normalise(metricCode)
                    + ". A metric with no active definition cannot be computed — publish one first.");
        }
        return found.get(0);
    }

    /** Null-tolerant lookup for the calculator, which annotates figures with their definition. */
    @Transactional(readOnly = true)
    public MetricDefinition activeOrNull(UUID tenantId, String metricCode) {
        List<MetricDefinition> found = jdbc.query(
                SELECT + " where tenant_id = ? and metric_code = ? and status = 'ACTIVE'",
                MAPPER, tenantId, normalise(metricCode));
        return found.isEmpty() ? null : found.get(0);
    }

    @Transactional(readOnly = true)
    public List<MetricDefinition> versions(String metricCode) {
        return jdbc.query(SELECT + " where tenant_id = ? and metric_code = ? order by version desc",
                MAPPER, TenantContext.get().tenantId(), normalise(metricCode));
    }

    // ------------------------------------------------------------------ governance

    /**
     * The governed publication path: retire the incumbent and activate a new
     * version, atomically.
     *
     * <p>Not an update of the existing row. A figure quoted last quarter must stay
     * reproducible under the definition that produced it, and an in-place edit
     * makes that impossible without leaving any trace that it did.
     */
    @Transactional
    public MetricDefinition publishNewVersion(DefinitionRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        UUID tenantId = TenantContext.get().tenantId();
        String code = normalise(request.metricCode());

        MetricDefinition incumbent = activeOrNull(tenantId, code);
        Integer maxVersion = jdbc.queryForObject(
                "select coalesce(max(version), 0) from analytics.metric_definition"
                        + " where tenant_id = ? and metric_code = ?", Integer.class, tenantId, code);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        if (incumbent != null) {
            jdbc.update("""
                    update analytics.metric_definition
                       set status = 'RETIRED', retired_at = now()
                     where tenant_id = ? and id = ?
                    """, tenantId, incumbent.id());
        }

        UUID id = jdbc.queryForObject("""
                insert into analytics.metric_definition
                  (tenant_id, metric_code, name, version, formula, basis, unit, notes,
                   requirement_ref, source_reference, status, published_at, published_by, supersedes_id)
                values (?, ?, ?, ?, ?, ?, coalesce(nullif(?, ''), 'NUMBER'), ?, ?, ?, 'ACTIVE', now(), ?, ?)
                returning id
                """, UUID.class, tenantId, code, request.name(), nextVersion, request.formula(),
                request.basis(), request.unit(), request.notes(), request.requirementRef(),
                request.sourceReference(), TenantContext.get().userId(),
                incumbent == null ? null : incumbent.id());

        audit.recordWithReason("ANALYTICS_METRIC_PUBLISHED", "METRIC_DEFINITION", id,
                "Published " + code + " v" + nextVersion, request.reason(),
                Map.of("metricCode", code, "version", nextVersion,
                        "supersedes", incumbent == null ? "none" : ("v" + incumbent.version())));

        return byId(tenantId, id);
    }

    /** Prepare a definition without publishing it. Drafts are unconstrained; only ACTIVE is unique. */
    @Transactional
    public MetricDefinition createDraft(DefinitionRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        UUID tenantId = TenantContext.get().tenantId();
        String code = normalise(request.metricCode());
        Integer maxVersion = jdbc.queryForObject(
                "select coalesce(max(version), 0) from analytics.metric_definition"
                        + " where tenant_id = ? and metric_code = ?", Integer.class, tenantId, code);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        UUID id = jdbc.queryForObject("""
                insert into analytics.metric_definition
                  (tenant_id, metric_code, name, version, formula, basis, unit, notes,
                   requirement_ref, source_reference, status)
                values (?, ?, ?, ?, ?, ?, coalesce(nullif(?, ''), 'NUMBER'), ?, ?, ?, 'DRAFT')
                returning id
                """, UUID.class, tenantId, code, request.name(), nextVersion, request.formula(),
                request.basis(), request.unit(), request.notes(), request.requirementRef(),
                request.sourceReference());
        return byId(tenantId, id);
    }

    /**
     * Activate an existing draft <em>without</em> retiring the incumbent.
     *
     * <p>This is the ungoverned path, and it is here precisely so the refusal is
     * reachable and testable: if the metric already has an active definition the
     * partial unique index rejects the write and FR-RPT-009 holds at the storage
     * layer, not merely by convention. The caught {@link DuplicateKeyException} is
     * translated into an explanation of what to do instead — publish a new version,
     * which retires the incumbent in the same transaction.
     */
    @Transactional
    public MetricDefinition activate(UUID definitionId) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        UUID tenantId = TenantContext.get().tenantId();
        MetricDefinition definition = byId(tenantId, definitionId);
        if ("ACTIVE".equals(definition.status())) return definition;

        try {
            jdbc.update("""
                    update analytics.metric_definition
                       set status = 'ACTIVE', published_at = now(), published_by = ?, retired_at = null
                     where tenant_id = ? and id = ?
                    """, TenantContext.get().userId(), tenantId, definitionId);
        } catch (DuplicateKeyException ex) {
            MetricDefinition incumbent = activeOrNull(tenantId, definition.metricCode());
            throw new ConflictException(definition.metricCode() + " already has an active definition"
                    + (incumbent == null ? "" : " (v" + incumbent.version() + ")")
                    + ". A metric with more than one active definition is a defect, not a configuration"
                    + " choice (FR-RPT-009): two reports showing the same named metric would compute it"
                    + " differently. Publish this as a new version — that retires the current one in the"
                    + " same transaction — or, if you need a variant that coexists, give it its own"
                    + " metric code and its own definition.");
        }
        audit.record("ANALYTICS_METRIC_ACTIVATED", "METRIC_DEFINITION", definitionId,
                "Activated " + definition.metricCode() + " v" + definition.version(),
                Map.of("metricCode", definition.metricCode(), "version", definition.version()));
        return byId(tenantId, definitionId);
    }

    @Transactional
    public MetricDefinition retire(UUID definitionId, String reason) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        UUID tenantId = TenantContext.get().tenantId();
        MetricDefinition definition = byId(tenantId, definitionId);
        jdbc.update("""
                update analytics.metric_definition
                   set status = 'RETIRED', retired_at = now()
                 where tenant_id = ? and id = ?
                """, tenantId, definitionId);
        audit.recordWithReason("ANALYTICS_METRIC_RETIRED", "METRIC_DEFINITION", definitionId,
                "Retired " + definition.metricCode() + " v" + definition.version(), reason,
                Map.of("metricCode", definition.metricCode(), "version", definition.version()));
        return byId(tenantId, definitionId);
    }

    // ------------------------------------------------------------------ plumbing

    private MetricDefinition byId(UUID tenantId, UUID id) {
        List<MetricDefinition> found = jdbc.query(SELECT + " where tenant_id = ? and id = ?",
                MAPPER, tenantId, id);
        if (found.isEmpty()) throw new NotFoundException("No metric definition with that id");
        return found.get(0);
    }

    static String normalise(String metricCode) {
        if (metricCode == null || metricCode.isBlank()) {
            throw new NotFoundException("A metric code is required");
        }
        return metricCode.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }

    private static final String SELECT = """
            select id, metric_code, name, version, formula, basis, unit, notes, requirement_ref,
                   source_reference, status, published_at, retired_at, supersedes_id, created_at
              from analytics.metric_definition
            """;

    private static final org.springframework.jdbc.core.RowMapper<MetricDefinition> MAPPER =
            (rs, i) -> new MetricDefinition(
                    rs.getObject("id", UUID.class), rs.getString("metric_code"), rs.getString("name"),
                    rs.getInt("version"), rs.getString("formula"), rs.getString("basis"),
                    rs.getString("unit"), rs.getString("notes"), rs.getString("requirement_ref"),
                    rs.getString("source_reference"), rs.getString("status"),
                    instant(rs.getTimestamp("published_at")), instant(rs.getTimestamp("retired_at")),
                    rs.getObject("supersedes_id", UUID.class), instant(rs.getTimestamp("created_at")));

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

package com.axiom.orgdata;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.axiom.tenancy.TenantContext.Principal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FR-MDM-001 — legal entities and business units.
 *
 * <p>Hierarchy is held as a parent pointer plus a materialized {@code path}. The
 * path is what makes "everything under this legal entity" a prefix scan instead
 * of a recursive read on every report, and it is also how a cycle is refused:
 * a unit whose id already appears in the proposed parent's path cannot be that
 * parent's child.
 *
 * <p>Business units are a governed master (FR-MDM-010) — by default a change
 * routes through approval, because moving a record between legal entities has
 * statutory reporting consequences and is not something a single operator
 * should be able to do silently.
 */
@Service
public class BusinessUnitService {

    static final String MASTER_TYPE = "BUSINESS_UNIT";

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final MasterGovernanceGate gate;

    public BusinessUnitService(JdbcTemplate jdbc, AuditService audit, MasterGovernanceGate gate) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.gate = gate;
    }

    public record BusinessUnitRow(UUID id, String code, String name, boolean legalEntity,
                                  UUID parentId, String path, int depth, String currencyCode,
                                  boolean active, int userCount, int recordCount) {}

    public record BusinessUnitRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$",
                    message = "Code must start with a letter and contain only letters, digits and underscores")
            @Size(max = 40) String code,
            @NotBlank @Size(max = 160) String name,
            boolean legalEntity,
            UUID parentId,
            @Pattern(regexp = "^$|^[A-Za-z]{3}$", message = "Currency must be a three-letter ISO code")
            String currencyCode) {}

    public record MemberRequest(UUID userId, boolean primary) {}

    public record RecordScopeRequest(String entityType, UUID entityId) {}

    public record ScopeSummary(UUID businessUnitId, String code, String name,
                               List<String> includedUnitCodes, int accountCount,
                               int contactCount, int leadCount, int opportunityCount,
                               int userCount) {}

    @Transactional(readOnly = true)
    public List<BusinessUnitRow> list() {
        return jdbc.query("""
                select bu.id, bu.code, bu.name, bu.is_legal_entity, bu.parent_id, bu.path,
                       bu.currency_code, bu.active,
                       (select count(*) from orgdata.business_unit_member m
                         where m.tenant_id = bu.tenant_id and m.business_unit_id = bu.id) as user_count,
                       (select count(*) from orgdata.business_unit_record r
                         where r.tenant_id = bu.tenant_id and r.business_unit_id = bu.id) as record_count
                from orgdata.business_unit bu
                where bu.tenant_id = ? and bu.deleted_at is null
                order by bu.path
                """, (rs, i) -> new BusinessUnitRow(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getBoolean("is_legal_entity"), rs.getObject("parent_id", UUID.class),
                rs.getString("path"), depthOf(rs.getString("path")), rs.getString("currency_code"),
                rs.getBoolean("active"), rs.getInt("user_count"), rs.getInt("record_count")),
                TenantContext.get().tenantId());
    }

    @Transactional
    public Submission<BusinessUnitRow> create(BusinessUnitRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        String code = normalizeCode(request.code());
        if (gate.gated(MASTER_TYPE)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", code);
            payload.put("name", request.name().trim());
            payload.put("legalEntity", request.legalEntity());
            payload.put("parentId", request.parentId() == null ? null : request.parentId().toString());
            payload.put("currencyCode", blankToNull(request.currencyCode()));
            UUID changeId = gate.enqueue(MASTER_TYPE, "CREATE", null,
                    "Create business unit " + code, payload);
            return Submission.pending(changeId, "Business unit");
        }
        return Submission.applied(apply(request));
    }

    /** The write path. Called directly, or by change control once approved. */
    @Transactional
    public BusinessUnitRow apply(BusinessUnitRequest request) {
        Principal p = TenantContext.get();
        String code = normalizeCode(request.code());
        String parentPath = "";
        if (request.parentId() != null) {
            parentPath = pathOf(request.parentId());
        }
        String path = parentPath + "/" + code;
        try {
            UUID id = jdbc.queryForObject("""
                    insert into orgdata.business_unit
                      (tenant_id, code, name, is_legal_entity, parent_id, path, currency_code,
                       created_by, updated_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    returning id
                    """, UUID.class, p.tenantId(), code, request.name().trim(),
                    request.legalEntity(), request.parentId(), path,
                    blankToNull(request.currencyCode()), p.userId(), p.userId());
            audit.record("BUSINESS_UNIT_CREATE", MASTER_TYPE, id,
                    "Created business unit " + code,
                    Map.of("code", code, "name", request.name().trim(),
                            "legalEntity", request.legalEntity(), "path", path));
            return byId(id);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("A business unit with code " + code
                    + " already exists in this tenant. Choose a different code or edit the existing unit.");
        }
    }

    @Transactional
    public BusinessUnitRow assignUser(UUID businessUnitId, MemberRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        BusinessUnitRow unit = byId(businessUnitId);
        try {
            jdbc.update("""
                    insert into orgdata.business_unit_member
                      (tenant_id, business_unit_id, user_id, is_primary, created_by)
                    values (?, ?, ?, ?, ?)
                    on conflict (tenant_id, business_unit_id, user_id)
                    do update set is_primary = excluded.is_primary
                    """, p.tenantId(), businessUnitId, request.userId(), request.primary(), p.userId());
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("That user does not exist in this tenant.");
        }
        audit.record("BUSINESS_UNIT_USER_ASSIGN", MASTER_TYPE, businessUnitId,
                "Assigned a user to business unit " + unit.code(),
                Map.of("userId", request.userId(), "primary", request.primary()));
        return byId(businessUnitId);
    }

    @Transactional
    public BusinessUnitRow assignRecord(UUID businessUnitId, RecordScopeRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        BusinessUnitRow unit = byId(businessUnitId);
        String entityType = request.entityType() == null ? "" : request.entityType().trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACCOUNT", "CONTACT", "LEAD", "OPPORTUNITY").contains(entityType)) {
            throw new ConflictException("Records may be scoped as ACCOUNT, CONTACT, LEAD or OPPORTUNITY.");
        }
        jdbc.update("""
                insert into orgdata.business_unit_record
                  (tenant_id, business_unit_id, entity_type, entity_id, created_by)
                values (?, ?, ?, ?, ?)
                on conflict (tenant_id, entity_type, entity_id)
                do update set business_unit_id = excluded.business_unit_id
                """, p.tenantId(), businessUnitId, entityType, request.entityId(), p.userId());
        audit.record("BUSINESS_UNIT_RECORD_ASSIGN", MASTER_TYPE, businessUnitId,
                "Scoped a " + entityType.toLowerCase(Locale.ROOT) + " to business unit " + unit.code(),
                Map.of("entityType", entityType, "entityId", request.entityId()));
        return byId(businessUnitId);
    }

    /**
     * FR-MDM-001 "scope reporting by them". The scope of a unit is the unit plus
     * every descendant, resolved from the materialized path.
     */
    @Transactional(readOnly = true)
    public ScopeSummary scope(UUID businessUnitId) {
        BusinessUnitRow unit = byId(businessUnitId);
        UUID tenantId = TenantContext.get().tenantId();
        String prefix = unit.path() + "/";
        List<String> included = jdbc.queryForList("""
                select code from orgdata.business_unit
                where tenant_id = ? and deleted_at is null and (path = ? or path like ?)
                order by path
                """, String.class, tenantId, unit.path(), prefix + "%");
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query("""
                select r.entity_type, count(*) as n
                from orgdata.business_unit_record r
                join orgdata.business_unit bu
                  on bu.tenant_id = r.tenant_id and bu.id = r.business_unit_id
                where r.tenant_id = ? and bu.deleted_at is null
                  and (bu.path = ? or bu.path like ?)
                group by r.entity_type
                """, rs -> { counts.put(rs.getString("entity_type"), rs.getInt("n")); },
                tenantId, unit.path(), prefix + "%");
        Integer users = jdbc.queryForObject("""
                select count(distinct m.user_id)
                from orgdata.business_unit_member m
                join orgdata.business_unit bu
                  on bu.tenant_id = m.tenant_id and bu.id = m.business_unit_id
                where m.tenant_id = ? and bu.deleted_at is null
                  and (bu.path = ? or bu.path like ?)
                """, Integer.class, tenantId, unit.path(), prefix + "%");
        return new ScopeSummary(unit.id(), unit.code(), unit.name(), included,
                counts.getOrDefault("ACCOUNT", 0), counts.getOrDefault("CONTACT", 0),
                counts.getOrDefault("LEAD", 0), counts.getOrDefault("OPPORTUNITY", 0),
                users == null ? 0 : users);
    }

    @Transactional(readOnly = true)
    public BusinessUnitRow byId(UUID id) {
        return list().stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Business unit not found"));
    }

    private String pathOf(UUID parentId) {
        try {
            return jdbc.queryForObject("""
                    select path from orgdata.business_unit
                    where tenant_id = ? and id = ? and deleted_at is null
                    """, String.class, TenantContext.get().tenantId(), parentId);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Parent business unit not found");
        }
    }

    private static int depthOf(String path) {
        if (path == null || path.isBlank()) return 0;
        return (int) path.chars().filter(c -> c == '/').count() - 1;
    }

    private static String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}

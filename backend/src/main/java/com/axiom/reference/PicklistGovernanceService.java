package com.axiom.reference;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.axiom.tenancy.TenantContext.Principal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FR-MDM-006 and FR-MDM-007 — governed picklists with dependent values, and
 * effective-dated reference resolution.
 *
 * <p>The two rules that are easy to get wrong and expensive to fix later:
 *
 * <ul>
 *   <li><b>A deactivated value stays readable.</b> {@link #selectable} and
 *       {@link #resolve} are different questions with different answers.
 *       Deactivating a value removes it from new entry and nothing else — the
 *       records already carrying it still resolve, report and display it. Nothing
 *       here ever rewrites a stored value.</li>
 *   <li><b>An invalid dependent combination is flagged, not corrected.</b>
 *       {@link #validateCombination} tells the caller the pair is no longer valid
 *       and what the valid options are. Silently swapping the value would destroy
 *       the record of what the user actually chose (US-E03-04).</li>
 * </ul>
 */
@Service
public class PicklistGovernanceService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public PicklistGovernanceService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record ValueOption(String code, String label, int sortOrder, boolean active,
                              boolean selectable, LocalDate effectiveFrom, LocalDate effectiveTo) {}

    public record DependencyRow(UUID id, String controllingApiName, String controllingLabel,
                                String dependentApiName, String dependentLabel, int mappedPairs) {}

    public record CombinationVerdict(boolean valid, String controllingCode, String dependentCode,
                                     String message, List<ValueOption> validOptions) {}

    public record DependencyRequest(@NotBlank String controllingApiName,
                                    @NotBlank String dependentApiName) {}

    public record MappingRequest(@NotBlank String controllingCode,
                                 @NotEmpty List<@NotBlank String> dependentCodes) {}

    public record ResolveRequest(@NotBlank String apiName, @NotBlank String code,
                                 @NotNull LocalDate asOf) {}

    /* ---------------------------------------------------------------- */
    /* Effective dating (FR-MDM-007)                                     */
    /* ---------------------------------------------------------------- */

    /**
     * The values a user may pick <em>today</em>: active, and inside their validity
     * window. This is the only list new entry may offer.
     */
    @Transactional(readOnly = true)
    public List<ValueOption> selectable(String apiName, LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        UUID valueSetId = valueSetId(apiName);
        return jdbc.query("""
                select code, label, sort_order, active, effective_from, effective_to
                from reference.value_set_entry
                where tenant_id = ? and value_set_id = ?
                  and active = true
                  and (effective_from is null or effective_from <= ?)
                  and (effective_to is null or effective_to >= ?)
                order by sort_order, label
                """, (rs, i) -> option(rs, true), TenantContext.get().tenantId(), valueSetId, date, date);
    }

    /**
     * Resolves a stored code as at a date (FR-MDM-007): a historical record shows
     * the label that was in force at its own date, and a value that has since
     * been deactivated still resolves.
     */
    @Transactional(readOnly = true)
    public ValueOption resolve(String apiName, String code, LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        UUID valueSetId = valueSetId(apiName);
        String normalized = normalizeCode(code);
        List<ValueOption> rows = jdbc.query("""
                select code, label, sort_order, active, effective_from, effective_to
                from reference.value_set_entry
                where tenant_id = ? and value_set_id = ? and code = ?
                order by
                  case when (effective_from is null or effective_from <= ?)
                        and (effective_to is null or effective_to >= ?) then 0 else 1 end,
                  effective_from desc nulls last
                """, (rs, i) -> {
                    LocalDate from = rs.getObject("effective_from", LocalDate.class);
                    LocalDate to = rs.getObject("effective_to", LocalDate.class);
                    boolean inForce = (from == null || !from.isAfter(date))
                            && (to == null || !to.isBefore(date));
                    return new ValueOption(rs.getString("code"), rs.getString("label"),
                            rs.getInt("sort_order"), rs.getBoolean("active"),
                            rs.getBoolean("active") && inForce, from, to);
                }, TenantContext.get().tenantId(), valueSetId, normalized, date, date);
        if (rows.isEmpty()) {
            throw new NotFoundException("Reference value " + normalized + " does not exist in " + apiName);
        }
        return rows.get(0);
    }

    /* ---------------------------------------------------------------- */
    /* Dependent picklists (FR-MDM-006)                                  */
    /* ---------------------------------------------------------------- */

    @Transactional(readOnly = true)
    public List<DependencyRow> dependencies() {
        return jdbc.query("""
                select d.id, ctrl.api_name as controlling_api_name, ctrl.label as controlling_label,
                       dep.api_name as dependent_api_name, dep.label as dependent_label,
                       (select count(*) from reference.dependent_value_map m
                         where m.tenant_id = d.tenant_id and m.dependency_id = d.id and m.active) as mapped_pairs
                from reference.value_set_dependency d
                join reference.value_set ctrl on ctrl.tenant_id = d.tenant_id and ctrl.id = d.controlling_value_set_id
                join reference.value_set dep on dep.tenant_id = d.tenant_id and dep.id = d.dependent_value_set_id
                where d.tenant_id = ?
                order by ctrl.api_name, dep.api_name
                """, (rs, i) -> new DependencyRow(rs.getObject("id", UUID.class),
                rs.getString("controlling_api_name"), rs.getString("controlling_label"),
                rs.getString("dependent_api_name"), rs.getString("dependent_label"),
                rs.getInt("mapped_pairs")), TenantContext.get().tenantId());
    }

    /**
     * The dependent values valid for one controlling value. Only selectable
     * values are offered; a mapped-but-deactivated value is filtered out of new
     * entry while remaining resolvable on existing records.
     */
    @Transactional(readOnly = true)
    public List<ValueOption> dependentOptions(String dependentApiName, String controllingCode,
                                              LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        UUID dependencyId = dependencyIdFor(dependentApiName);
        UUID dependentSetId = valueSetId(dependentApiName);
        return jdbc.query("""
                select e.code, e.label, e.sort_order, e.active, e.effective_from, e.effective_to
                from reference.dependent_value_map m
                join reference.value_set_entry e
                  on e.tenant_id = m.tenant_id and e.value_set_id = ? and e.code = m.dependent_code
                where m.tenant_id = ? and m.dependency_id = ? and m.controlling_code = ? and m.active
                  and e.active = true
                  and (e.effective_from is null or e.effective_from <= ?)
                  and (e.effective_to is null or e.effective_to >= ?)
                order by e.sort_order, e.label
                """, (rs, i) -> option(rs, true), dependentSetId, TenantContext.get().tenantId(),
                dependencyId, normalizeCode(controllingCode), date, date);
    }

    /**
     * US-E03-04: an existing invalid combination is <em>flagged</em>, never
     * silently corrected. The verdict carries the valid options so the caller can
     * offer a choice rather than making one.
     */
    @Transactional(readOnly = true)
    public CombinationVerdict validateCombination(String dependentApiName, String controllingCode,
                                                  String dependentCode, LocalDate asOf) {
        String controlling = normalizeCode(controllingCode);
        String dependent = normalizeCode(dependentCode);
        UUID dependencyId = dependencyIdFor(dependentApiName);
        Integer mapped = jdbc.queryForObject("""
                select count(*) from reference.dependent_value_map
                where tenant_id = ? and dependency_id = ? and controlling_code = ?
                  and dependent_code = ? and active
                """, Integer.class, TenantContext.get().tenantId(), dependencyId, controlling, dependent);
        List<ValueOption> options = dependentOptions(dependentApiName, controlling, asOf);
        if (mapped != null && mapped > 0) {
            return new CombinationVerdict(true, controlling, dependent,
                    "Combination is valid.", options);
        }
        return new CombinationVerdict(false, controlling, dependent,
                ("%s is not a valid %s for %s. The stored value has been left unchanged and flagged for "
                        + "review — correct it deliberately rather than letting the system guess.")
                        .formatted(dependent, dependentApiName, controlling), options);
    }

    @Transactional
    public DependencyRow createDependency(DependencyRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        UUID controlling = valueSetId(request.controllingApiName());
        UUID dependent = valueSetId(request.dependentApiName());
        if (controlling.equals(dependent)) {
            throw new ConflictException("A value set cannot control itself.");
        }
        Integer existing = jdbc.queryForObject("""
                select count(*) from reference.value_set_dependency
                where tenant_id = ? and dependent_value_set_id = ?
                """, Integer.class, p.tenantId(), dependent);
        if (existing != null && existing > 0) {
            throw new ConflictException(normalizeApiName(request.dependentApiName())
                    + " already has a controlling value set. A dependent list may have only one "
                    + "controller, otherwise which values are valid becomes ambiguous.");
        }
        UUID id = jdbc.queryForObject("""
                insert into reference.value_set_dependency
                  (tenant_id, controlling_value_set_id, dependent_value_set_id, created_by)
                values (?, ?, ?, ?) returning id
                """, UUID.class, p.tenantId(), controlling, dependent, p.userId());
        audit.record("PICKLIST_DEPENDENCY_CREATE", "REFERENCE_VALUE_SET", id,
                "%s now controls %s".formatted(normalizeApiName(request.controllingApiName()),
                        normalizeApiName(request.dependentApiName())),
                Map.of("controllingApiName", normalizeApiName(request.controllingApiName()),
                        "dependentApiName", normalizeApiName(request.dependentApiName())));
        return dependencies().stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Dependency vanished mid-transaction"));
    }

    /**
     * Replaces the dependent values mapped to one controlling value. Removed pairs
     * are deactivated rather than deleted, so a record that already carries the
     * pair still resolves.
     */
    @Transactional
    public List<ValueOption> setMapping(String dependentApiName, MappingRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        UUID dependencyId = dependencyIdFor(dependentApiName);
        String controlling = normalizeCode(request.controllingCode());
        List<String> wanted = request.dependentCodes().stream().map(PicklistGovernanceService::normalizeCode).toList();

        jdbc.update("""
                update reference.dependent_value_map set active = false
                where tenant_id = ? and dependency_id = ? and controlling_code = ?
                """, p.tenantId(), dependencyId, controlling);
        wanted.forEach(code -> jdbc.update("""
                insert into reference.dependent_value_map
                  (tenant_id, dependency_id, controlling_code, dependent_code, active, created_by)
                values (?, ?, ?, ?, true, ?)
                on conflict (tenant_id, dependency_id, controlling_code, dependent_code)
                do update set active = true
                """, p.tenantId(), dependencyId, controlling, code, p.userId()));
        audit.record("PICKLIST_DEPENDENCY_MAP", "REFERENCE_VALUE_SET", dependencyId,
                "Mapped %d %s value(s) to %s".formatted(wanted.size(),
                        normalizeApiName(dependentApiName), controlling),
                Map.of("controllingCode", controlling, "dependentCodes", wanted,
                        "removedPairsDeactivatedNotDeleted", true));
        return dependentOptions(dependentApiName, controlling, LocalDate.now());
    }

    /** Every controlling value with the dependent values currently mapped to it. */
    @Transactional(readOnly = true)
    public Map<String, List<String>> mappingMatrix(String dependentApiName) {
        UUID dependencyId = dependencyIdFor(dependentApiName);
        Map<String, List<String>> matrix = new LinkedHashMap<>();
        jdbc.query("""
                select controlling_code, dependent_code
                from reference.dependent_value_map
                where tenant_id = ? and dependency_id = ? and active
                order by controlling_code, dependent_code
                """, rs -> {
                    matrix.computeIfAbsent(rs.getString("controlling_code"), key -> new java.util.ArrayList<>())
                            .add(rs.getString("dependent_code"));
                }, TenantContext.get().tenantId(), dependencyId);
        return matrix;
    }

    private UUID dependencyIdFor(String dependentApiName) {
        UUID dependentSetId = valueSetId(dependentApiName);
        try {
            return jdbc.queryForObject("""
                    select id from reference.value_set_dependency
                    where tenant_id = ? and dependent_value_set_id = ?
                    """, UUID.class, TenantContext.get().tenantId(), dependentSetId);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException(normalizeApiName(dependentApiName)
                    + " is not a dependent value set. Create the dependency first.");
        }
    }

    private UUID valueSetId(String apiName) {
        try {
            return jdbc.queryForObject("""
                    select id from reference.value_set
                    where tenant_id = ? and api_name = ? and active = true
                    """, UUID.class, TenantContext.get().tenantId(), normalizeApiName(apiName));
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Reference value set not found: " + normalizeApiName(apiName));
        }
    }

    private static ValueOption option(java.sql.ResultSet rs, boolean selectable) throws java.sql.SQLException {
        return new ValueOption(rs.getString("code"), rs.getString("label"), rs.getInt("sort_order"),
                rs.getBoolean("active"), selectable,
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class));
    }

    private static String normalizeApiName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

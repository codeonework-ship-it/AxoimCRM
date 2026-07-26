package com.axiom.reference;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ReferenceDataService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ReferenceDataService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record ValueSetRow(UUID id, String apiName, String label, String module,
                              String description, boolean active) {}
    public record EntryRow(UUID id, String code, String label, int sortOrder,
                           boolean active, boolean systemManaged,
                           LocalDate effectiveFrom, LocalDate effectiveTo) {}
    public record ResolvedEntry(UUID entryId, String valueSet, String code, String label,
                                LocalDate asOf, LocalDate effectiveFrom, LocalDate effectiveTo,
                                boolean activeNow, boolean selectableForNewRecords,
                                String note) {}

    @Transactional(readOnly = true)
    public List<ValueSetRow> listValueSets() {
        return jdbc.query("""
                select id, api_name, label, module, description, active
                from reference.value_set
                where tenant_id = ?
                order by module, label
                """, (rs, i) -> new ValueSetRow(
                rs.getObject("id", UUID.class),
                rs.getString("api_name"),
                rs.getString("label"),
                rs.getString("module"),
                rs.getString("description"),
                rs.getBoolean("active")), TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public List<EntryRow> listEntries(String apiName, boolean includeInactive) {
        UUID valueSetId = valueSetId(apiName);
        return jdbc.query("""
                select id, code, label, sort_order, active, system_managed,
                       effective_from, effective_to
                from reference.value_set_entry
                where tenant_id = ? and value_set_id = ? and (? = true or active = true)
                order by sort_order, label
                """, (rs, i) -> new EntryRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("label"),
                rs.getInt("sort_order"),
                rs.getBoolean("active"),
                rs.getBoolean("system_managed"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class)),
                TenantContext.get().tenantId(), valueSetId, includeInactive);
    }

    /**
     * Resolve the label that was in force on a business date.  Deliberately does
     * not hide an inactive value: historical records must remain readable and
     * reportable after administrators stop offering that value for new records.
     */
    @Transactional(readOnly = true)
    public ResolvedEntry resolve(String apiName, String code, LocalDate asOf) {
        UUID valueSetId = valueSetId(apiName);
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        try {
            return jdbc.queryForObject("""
                    select v.entry_id, v.code, v.label, v.effective_from, v.effective_to,
                           e.active
                    from reference.value_set_entry_version v
                    join reference.value_set_entry e
                      on e.tenant_id = v.tenant_id and e.id = v.entry_id
                    where v.tenant_id = ? and v.value_set_id = ? and v.code = ?
                      and (v.effective_from is null or v.effective_from <= ?)
                      and (v.effective_to is null or v.effective_to >= ?)
                    order by v.recorded_at desc
                    limit 1
                    """, (rs, i) -> {
                        LocalDate from = rs.getObject("effective_from", LocalDate.class);
                        LocalDate to = rs.getObject("effective_to", LocalDate.class);
                        boolean active = rs.getBoolean("active");
                        boolean selectable = active && !date.isBefore(from == null ? LocalDate.MIN : from)
                                && !date.isAfter(to == null ? LocalDate.MAX : to);
                        return new ResolvedEntry(rs.getObject("entry_id", UUID.class),
                                normalizeApiName(apiName), rs.getString("code"), rs.getString("label"),
                                date, from, to, active, selectable,
                                active
                                        ? "This label is the governed value for the selected date."
                                        : "This value is inactive for new records but remains visible on historical records and reports.");
                    }, TenantContext.get().tenantId(), valueSetId, normalizeCode(code), date, date);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("No " + apiName + " value with code " + normalizeCode(code)
                    + " was effective on " + date + ". Choose another date or review the value's effective window.");
        }
    }

    @Transactional
    public EntryRow createEntry(String apiName, EntryMutation request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        UUID valueSetId = valueSetId(apiName);
        try {
            EntryRow row = jdbc.queryForObject("""
                    insert into reference.value_set_entry
                      (tenant_id, value_set_id, code, label, sort_order, active,
                       effective_from, effective_to)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    returning id, code, label, sort_order, active, system_managed,
                              effective_from, effective_to
                    """, (rs, i) -> new EntryRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getString("label"),
                    rs.getInt("sort_order"),
                    rs.getBoolean("active"),
                    rs.getBoolean("system_managed"),
                    rs.getObject("effective_from", LocalDate.class),
                    rs.getObject("effective_to", LocalDate.class)),
                    TenantContext.get().tenantId(), valueSetId, normalizeCode(request.code()),
                    request.label().trim(), request.sortOrder(), request.active(),
                    request.effectiveFrom(), request.effectiveTo());
            audit.record("REFERENCE_ENTRY_CREATE", "REFERENCE_VALUE_SET", valueSetId,
                    "Created reference entry " + row.code() + " in " + apiName,
                    Map.of("code", row.code(), "label", row.label(), "sortOrder", row.sortOrder()));
            recordVersion(valueSetId, row);
            return row;
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Reference entry code or sort order already exists, or the effective date range is invalid");
        }
    }

    @Transactional
    public EntryRow updateEntry(String apiName, String code, EntryMutation request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        UUID valueSetId = valueSetId(apiName);
        try {
            EntryRow row = jdbc.queryForObject("""
                    update reference.value_set_entry
                    set label = ?, sort_order = ?, active = ?, effective_from = ?,
                        effective_to = ?, updated_at = now()
                    where tenant_id = ? and value_set_id = ? and code = ?
                    returning id, code, label, sort_order, active, system_managed,
                              effective_from, effective_to
                    """, (rs, i) -> new EntryRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getString("label"),
                    rs.getInt("sort_order"),
                    rs.getBoolean("active"),
                    rs.getBoolean("system_managed"),
                    rs.getObject("effective_from", LocalDate.class),
                    rs.getObject("effective_to", LocalDate.class)),
                    request.label().trim(), request.sortOrder(), request.active(),
                    request.effectiveFrom(), request.effectiveTo(),
                    TenantContext.get().tenantId(), valueSetId, normalizeCode(code));
            if (row == null) throw new NotFoundException("Reference entry not found");
            audit.record("REFERENCE_ENTRY_UPDATE", "REFERENCE_VALUE_SET", valueSetId,
                    "Updated reference entry " + row.code() + " in " + apiName,
                    Map.of("code", row.code(), "active", row.active(), "sortOrder", row.sortOrder()));
            recordVersion(valueSetId, row);
            return row;
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Reference entry not found");
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Reference entry code or sort order already exists, or the effective date range is invalid");
        }
    }

    private void recordVersion(UUID valueSetId, EntryRow row) {
        jdbc.update("""
                insert into reference.value_set_entry_version
                  (tenant_id, value_set_id, entry_id, code, label, active,
                   effective_from, effective_to, recorded_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, TenantContext.get().tenantId(), valueSetId, row.id(), row.code(), row.label(),
                row.active(), row.effectiveFrom(), row.effectiveTo(), TenantContext.get().userId());
    }

    private UUID valueSetId(String apiName) {
        try {
            return jdbc.queryForObject("""
                    select id from reference.value_set
                    where tenant_id = ? and api_name = ? and active = true
                    """, UUID.class, TenantContext.get().tenantId(), normalizeApiName(apiName));
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Reference value set not found");
        }
    }

    private String normalizeApiName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

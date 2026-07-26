package com.axiom.analytics;

import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Saved reports (FR-RPT-001, FR-RPT-002).
 *
 * <p>A report is stored as the structured definition the builder produced, not as
 * SQL. That is what makes a saved report inspectable and editable by whoever opens
 * it next — doc 14 §4's requirement that standard content be "inspectable,
 * cloneable and editable in the builder, not a black-box screen" — and it is also
 * what keeps stored user content from ever reaching the SQL planner: the
 * definition is re-validated against {@link AnalyticsDataset} on every run, so a
 * row edited directly in the database cannot name a column either.
 *
 * <p>The definition is validated on save as well as on run. Saving a report that
 * cannot execute is a trap someone else falls into weeks later, holding a report
 * with your name on it that has never worked.
 */
@Service
public class ReportViewService {

    public record SavedReport(UUID id, String code, String name, String description, String dataset,
                              String format, ReportQueryService.ReportRequest definition,
                              UUID createdBy, Instant createdAt, Instant updatedAt) {}

    public record SaveRequest(@NotBlank @Size(max = 64)
                              @Pattern(regexp = "^[a-z][a-z0-9_]*$",
                                      message = "must be lower case letters, digits and underscores")
                              String code,
                              @NotBlank @Size(max = 160) String name,
                              @Size(max = 500) String description,
                              ReportQueryService.ReportRequest definition) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ReportQueryService queries;

    public ReportViewService(JdbcTemplate jdbc, ObjectMapper json, ReportQueryService queries) {
        this.jdbc = jdbc;
        this.json = json;
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public List<SavedReport> list() {
        return jdbc.query("""
                select id, code, name, description, dataset, format, definition,
                       created_by, created_at, updated_at
                  from analytics.report_view where tenant_id = ? order by name
                """, this::map, TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public SavedReport byCode(String code) {
        List<SavedReport> found = jdbc.query("""
                select id, code, name, description, dataset, format, definition,
                       created_by, created_at, updated_at
                  from analytics.report_view where tenant_id = ? and code = ?
                """, this::map, TenantContext.get().tenantId(), code);
        if (found.isEmpty()) throw new NotFoundException("No saved report with code " + code);
        return found.get(0);
    }

    @Transactional
    public SavedReport save(SaveRequest request) {
        ReportQueryService.ReportRequest definition = request.definition();
        AnalyticsDataset dataset = AnalyticsDataset.of(definition == null ? null : definition.dataset());
        // Validate now, so a report that cannot run is never saved. The cheapest way
        // to be certain is to run it — validation that merely resembles execution
        // eventually diverges from it.
        queries.run(definition);

        String format = definition.format() == null ? "TABULAR"
                : definition.format().trim().toUpperCase(java.util.Locale.ROOT);
        String payload;
        try {
            payload = json.writeValueAsString(definition);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Report definition is not serializable", ex);
        }

        try {
            jdbc.update("""
                    insert into analytics.report_view
                      (tenant_id, code, name, description, dataset, format, definition, created_by)
                    values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    on conflict (tenant_id, code) do update set
                      name = excluded.name, description = excluded.description,
                      dataset = excluded.dataset, format = excluded.format,
                      definition = excluded.definition, updated_at = now()
                    """, TenantContext.get().tenantId(), request.code(), request.name(),
                    request.description(), dataset.name(), format, payload,
                    TenantContext.get().userId());
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("A saved report with code " + request.code() + " already exists");
        }
        return byCode(request.code());
    }

    @Transactional
    public void delete(String code) {
        int removed = jdbc.update("delete from analytics.report_view where tenant_id = ? and code = ?",
                TenantContext.get().tenantId(), code);
        if (removed == 0) throw new NotFoundException("No saved report with code " + code);
    }

    private SavedReport map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        ReportQueryService.ReportRequest definition;
        try {
            definition = json.readValue(rs.getString("definition"), ReportQueryService.ReportRequest.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Saved report definition could not be read", ex);
        }
        return new SavedReport(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getString("dataset"), rs.getString("format"),
                definition, rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}

package com.axiom.masterdata;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.BulkValidationException;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class MasterDataService {
    public enum Master {
        ACCOUNTS("ACCOUNT", List.of("name", "industry")),
        CONTACTS("CONTACT", List.of("first_name", "last_name", "email", "title", "account_name")),
        LEADS("LEAD", List.of("first_name", "last_name", "company", "email", "status")),
        PIPELINE_STAGES("PIPELINE_STAGE", List.of("name", "sort_order", "is_closed", "is_won", "requires_economic_buyer"));

        final String entityType;
        final List<String> columns;
        Master(String entityType, List<String> columns) { this.entityType = entityType; this.columns = columns; }

        static Master fromPath(String value) {
            try { return valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ex) { throw new NotFoundException("Unknown master: " + value); }
        }
    }

    public enum ExportFormat {
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
        PDF("application/pdf", "pdf");
        final String contentType;
        final String extension;
        ExportFormat(String contentType, String extension) { this.contentType = contentType; this.extension = extension; }
    }

    public record FilePayload(byte[] bytes, String contentType, String filename) {}
    public record ImportResult(int imported, String master, String message) {}
    private record TabularData(List<String> headers, List<List<String>> rows) {}

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final com.axiom.identity.StepUpService stepUp;

    public MasterDataService(JdbcTemplate jdbc, AuditService audit,
                             com.axiom.identity.StepUpService stepUp) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.stepUp = stepUp;
    }

    public FilePayload template(String masterPath) {
        Master master = Master.fromPath(masterPath);
        String csv = String.join(",", master.columns) + "\r\n";
        return new FilePayload(csv.getBytes(StandardCharsets.UTF_8), "text/csv;charset=UTF-8",
                master.name().toLowerCase(Locale.ROOT) + "-import-template.csv");
    }

    @Transactional
    public ImportResult importCsv(String masterPath, byte[] bytes) {
        Master master = Master.fromPath(masterPath);
        CrmRole.requireImport(TenantContext.get().role());
        if (bytes == null || bytes.length == 0) throw new BulkValidationException("Import file is empty", List.of("Select a populated CSV template"));
        if (bytes.length > 5 * 1024 * 1024) throw new BulkValidationException("Import file exceeds 5 MB", List.of("Split the file into smaller batches"));

        List<Map<String, String>> rows = parseCsv(bytes, master.columns);
        if (rows.size() > 5_000) throw new BulkValidationException("Import exceeds 5,000 rows", List.of("Split the file into batches of 5,000 rows or fewer"));
        List<String> errors = validate(master, rows);
        if (!errors.isEmpty()) throw new BulkValidationException("No records were imported; correct every row and retry", errors);

        rows.forEach(row -> insert(master, row));
        audit.record("BULK_IMPORT", master.entityType, null,
                "Imported " + rows.size() + " " + master.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                Map.of("rowCount", rows.size(), "atomic", true));
        return new ImportResult(rows.size(), master.name(), "Import completed atomically");
    }

    @Transactional
    public FilePayload export(String masterPath, ExportFormat format, String search, String filter) {
        Master master = Master.fromPath(masterPath);
        CrmRole.requireExport(TenantContext.get().role());
        // Bulk export is a controlled action (FR-TEN-009): holding a live session is
        // not sufficient authority to walk out with an entire master file.
        stepUp.requireStepUp("Exporting " + master.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        TabularData data = exportData(master, search, filter);
        byte[] bytes = switch (format) {
            case XLSX -> xlsx(master, data);
            case DOCX -> docx(master, data);
            case PDF -> pdf(master, data);
        };
        audit.record("EXPORT", master.entityType, null,
                "Exported " + data.rows.size() + " " + master.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                Map.of("format", format.name(), "rowCount", data.rows.size(),
                        "search", clean(search) == null ? "" : clean(search),
                        "filter", clean(filter) == null ? "" : clean(filter)));
        return new FilePayload(bytes, format.contentType,
                master.name().toLowerCase(Locale.ROOT) + "-export." + format.extension);
    }

    @Transactional
    public void softDelete(String masterPath, UUID id) {
        Master master = Master.fromPath(masterPath);
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        // Deleting master data is a controlled action (FR-TEN-009).
        stepUp.requireStepUp("Deleting a " + master.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + " record");
        UUID tenantId = TenantContext.get().tenantId();
        int inUse = switch (master) {
            case ACCOUNTS -> count("select count(*) from contact where tenant_id=? and account_id=? and deleted_at is null", tenantId, id)
                    + count("select count(*) from opportunity where tenant_id=? and account_id=?", tenantId, id);
            case CONTACTS -> count("select count(*) from opportunity_contact_role where tenant_id=? and contact_id=?", tenantId, id);
            case LEADS -> count("select count(*) from lead where tenant_id=? and id=? and converted_opportunity_id is not null", tenantId, id);
            case PIPELINE_STAGES -> count("select count(*) from opportunity where tenant_id=? and stage_id=?", tenantId, id);
        };
        if (inUse > 0) {
            throw new ConflictException("This record cannot be deleted because it is in use by " + inUse
                    + " related record" + (inUse == 1 ? "" : "s") + ". Remove or reassign those dependencies first.");
        }
        String table = switch (master) {
            case ACCOUNTS -> "account";
            case CONTACTS -> "contact";
            case LEADS -> "lead";
            case PIPELINE_STAGES -> "pipeline_stage";
        };
        int updated = jdbc.update("update " + table + " set deleted_at=now(), deleted_by=? where tenant_id=? and id=? and deleted_at is null",
                TenantContext.get().userId(), tenantId, id);
        if (updated == 0) throw new NotFoundException("Master record not found");
        audit.record("SOFT_DELETE", master.entityType, id, "Master record moved to the deleted state",
                Map.of("hardDelete", false));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private List<Map<String, String>> parseCsv(byte[] bytes, List<String> requiredHeaders) {
        String text = new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "");
        String[] lines = text.split("\\R", -1);
        if (lines.length == 0) throw new BulkValidationException("CSV has no header", List.of("Download and use the provided template"));
        List<String> headers = parseLine(lines[0]).stream().map(v -> v.trim().toLowerCase(Locale.ROOT)).toList();
        List<String> missing = requiredHeaders.stream().filter(h -> !headers.contains(h)).toList();
        if (!missing.isEmpty()) throw new BulkValidationException("CSV columns do not match the template",
                missing.stream().map(h -> "Missing column: " + h).toList());
        List<Map<String, String>> result = new ArrayList<>();
        for (int lineNo = 1; lineNo < lines.length; lineNo++) {
            if (lines[lineNo].isBlank()) continue;
            List<String> values = parseLine(lines[lineNo]);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), i < values.size() ? values.get(i).trim() : "");
            row.put("_row", Integer.toString(lineNo + 1));
            result.add(row);
        }
        if (result.isEmpty()) throw new BulkValidationException("CSV contains no data rows", List.of("Add records below the header row"));
        return result;
    }

    private List<String> parseLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) { values.add(value.toString()); value.setLength(0); }
            else value.append(c);
        }
        values.add(value.toString());
        return values;
    }

    private List<String> validate(Master master, List<Map<String, String>> rows) {
        List<String> errors = new ArrayList<>();
        Set<String> batchKeys = new HashSet<>();
        for (Map<String, String> row : rows) {
            int n = Integer.parseInt(row.get("_row"));
            switch (master) {
                case ACCOUNTS -> {
                    required(row, "name", n, errors, 160);
                    length(row, "industry", n, errors, 120);
                    duplicateKey(row.get("name"), n, batchKeys, errors);
                    if (!row.get("name").isBlank() && count("select count(*) from account where tenant_id=? and lower(name)=lower(?) and deleted_at is null", TenantContext.get().tenantId(), row.get("name")) > 0)
                        errors.add("Row " + n + ": account name already exists");
                }
                case CONTACTS -> {
                    required(row, "first_name", n, errors, 100); required(row, "last_name", n, errors, 100);
                    email(row, n, errors); length(row, "title", n, errors, 160);
                    required(row, "account_name", n, errors, 160);
                    if (!row.get("account_name").isBlank() && count("select count(*) from account where tenant_id=? and lower(name)=lower(?) and deleted_at is null", TenantContext.get().tenantId(), row.get("account_name")) != 1)
                        errors.add("Row " + n + ": account_name must match one active account");
                }
                case LEADS -> {
                    required(row, "first_name", n, errors, 100); required(row, "last_name", n, errors, 100);
                    required(row, "company", n, errors, 160); email(row, n, errors);
                    String status = row.get("status").isBlank() ? "NEW" : row.get("status").toUpperCase(Locale.ROOT);
                    if (!Set.of("NEW", "QUALIFIED", "DISQUALIFIED").contains(status)) errors.add("Row " + n + ": status must be NEW, QUALIFIED, or DISQUALIFIED");
                }
                case PIPELINE_STAGES -> {
                    required(row, "name", n, errors, 120); duplicateKey(row.get("name"), n, batchKeys, errors);
                    try { Integer.parseInt(row.get("sort_order")); } catch (NumberFormatException ex) { errors.add("Row " + n + ": sort_order must be an integer"); }
                    for (String field : List.of("is_closed", "is_won", "requires_economic_buyer"))
                        if (!Set.of("true", "false").contains(row.get(field).toLowerCase(Locale.ROOT))) errors.add("Row " + n + ": " + field + " must be true or false");
                }
            }
            if (errors.size() >= 100) { errors.add("Validation stopped after 100 errors"); break; }
        }
        return errors;
    }

    private void required(Map<String, String> row, String field, int n, List<String> errors, int max) {
        String value = row.getOrDefault(field, "");
        if (value.isBlank()) errors.add("Row " + n + ": " + field + " is required");
        else if (value.length() > max) errors.add("Row " + n + ": " + field + " exceeds " + max + " characters");
    }
    private void length(Map<String, String> row, String field, int n, List<String> errors, int max) {
        if (row.getOrDefault(field, "").length() > max) errors.add("Row " + n + ": " + field + " exceeds " + max + " characters");
    }
    private void email(Map<String, String> row, int n, List<String> errors) {
        String value = row.getOrDefault("email", "");
        if (!value.isBlank() && !EMAIL.matcher(value).matches()) errors.add("Row " + n + ": email is invalid");
    }
    private void duplicateKey(String value, int n, Set<String> keys, List<String> errors) {
        if (value != null && !value.isBlank() && !keys.add(value.toLowerCase(Locale.ROOT))) errors.add("Row " + n + ": duplicate value in this file: " + value);
    }

    private void insert(Master master, Map<String, String> row) {
        UUID tid = TenantContext.get().tenantId();
        UUID uid = TenantContext.get().userId();
        switch (master) {
            case ACCOUNTS -> jdbc.update("insert into account(tenant_id,name,industry,owner_id) values(?,?,nullif(?,''),?)", tid, row.get("name"), row.get("industry"), uid);
            case CONTACTS -> {
                UUID accountId = jdbc.queryForObject("select id from account where tenant_id=? and lower(name)=lower(?) and deleted_at is null", UUID.class, tid, row.get("account_name"));
                jdbc.update("insert into contact(tenant_id,account_id,first_name,last_name,email,title) values(?,?,?,?,nullif(?,''),nullif(?,''))",
                        tid, accountId, row.get("first_name"), row.get("last_name"), row.get("email"), row.get("title"));
            }
            case LEADS -> jdbc.update("insert into lead(tenant_id,first_name,last_name,company,email,status,owner_id) values(?,?,?,?,nullif(?,''),?,?)",
                    tid, row.get("first_name"), row.get("last_name"), row.get("company"), row.get("email"),
                    row.get("status").isBlank() ? "NEW" : row.get("status").toUpperCase(Locale.ROOT), uid);
            case PIPELINE_STAGES -> jdbc.update("insert into pipeline_stage(tenant_id,name,sort_order,is_closed,is_won,requires_economic_buyer) values(?,?,?,?,?,?)",
                    tid, row.get("name"), Integer.parseInt(row.get("sort_order")), Boolean.parseBoolean(row.get("is_closed")),
                    Boolean.parseBoolean(row.get("is_won")), Boolean.parseBoolean(row.get("requires_economic_buyer")));
        }
    }

    private TabularData exportData(Master master, String search, String filter) {
        UUID tid = TenantContext.get().tenantId();
        List<Object> args = new ArrayList<>();
        args.add(tid);
        return switch (master) {
            case ACCOUNTS -> {
                String where = accountWhere(search, filter, args);
                yield query(List.of("Name", "Industry", "Owner"), """
                    select a.name, coalesce(a.industry,''), coalesce(u.display_name,'') from account a
                    left join app_user u on u.id=a.owner_id and u.tenant_id=a.tenant_id
                    """ + where + " order by a.name", args.toArray());
            }
            case CONTACTS -> {
                String where = contactWhere(search, filter, args);
                yield query(List.of("First name", "Last name", "Email", "Title", "Account"), """
                    select c.first_name,c.last_name,coalesce(c.email,''),coalesce(c.title,''),coalesce(a.name,'') from contact c
                    left join account a on a.id=c.account_id and a.tenant_id=c.tenant_id
                    """ + where + " order by c.last_name,c.first_name", args.toArray());
            }
            case LEADS -> {
                String where = leadWhere(search, filter, args);
                yield query(List.of("First name", "Last name", "Company", "Email", "Status"), """
                    select l.first_name,l.last_name,l.company,coalesce(l.email,''),l.status from lead l
                    left join app_user u on u.id=l.owner_id and u.tenant_id=l.tenant_id
                    """ + where + " order by l.created_at desc", args.toArray());
            }
            case PIPELINE_STAGES -> {
                String where = stageWhere(search, filter, args);
                yield query(List.of("Name", "Order", "Closed", "Won", "Requires economic buyer"), """
                    select name,sort_order::text,is_closed::text,is_won::text,requires_economic_buyer::text from pipeline_stage
                    """ + where + " order by sort_order", args.toArray());
            }
        };
    }

    private TabularData query(List<String> headers, String sql, Object... args) {
        List<List<String>> rows = jdbc.query(sql, (rs, i) -> {
            List<String> row = new ArrayList<>();
            for (int c = 1; c <= headers.size(); c++) row.add(rs.getString(c));
            return row;
        }, args);
        return new TabularData(headers, rows);
    }

    private String accountWhere(String search, String industry, List<Object> args) {
        StringBuilder where = new StringBuilder(" where a.tenant_id=? and a.deleted_at is null");
        String q = searchPattern(search);
        if (q != null) {
            where.append(" and (lower(a.name) like ? or lower(coalesce(a.industry,'')) like ? or lower(coalesce(u.display_name,'')) like ?)");
            args.add(q); args.add(q); args.add(q);
        }
        String f = clean(industry);
        if (f != null) {
            where.append(" and lower(coalesce(a.industry,'')) = ?");
            args.add(f.toLowerCase(Locale.ROOT));
        }
        return where.toString();
    }

    private String contactWhere(String search, String accountName, List<Object> args) {
        StringBuilder where = new StringBuilder(" where c.tenant_id=? and c.deleted_at is null");
        String q = searchPattern(search);
        if (q != null) {
            where.append(" and (lower(c.first_name) like ? or lower(c.last_name) like ? or lower(coalesce(c.email,'')) like ? or lower(coalesce(c.title,'')) like ? or lower(coalesce(a.name,'')) like ?)");
            args.add(q); args.add(q); args.add(q); args.add(q); args.add(q);
        }
        String f = clean(accountName);
        if (f != null) {
            where.append(" and lower(coalesce(a.name,'')) = ?");
            args.add(f.toLowerCase(Locale.ROOT));
        }
        return where.toString();
    }

    private String leadWhere(String search, String status, List<Object> args) {
        StringBuilder where = new StringBuilder(" where l.tenant_id=? and l.deleted_at is null");
        String q = searchPattern(search);
        if (q != null) {
            where.append(" and (lower(l.first_name) like ? or lower(l.last_name) like ? or lower(l.company) like ? or lower(coalesce(l.email,'')) like ? or lower(coalesce(u.display_name,'')) like ?)");
            args.add(q); args.add(q); args.add(q); args.add(q); args.add(q);
        }
        String f = clean(status);
        if (f != null) {
            where.append(" and upper(l.status) = ?");
            args.add(f.toUpperCase(Locale.ROOT));
        }
        return where.toString();
    }

    private String stageWhere(String search, String lifecycle, List<Object> args) {
        StringBuilder where = new StringBuilder(" where tenant_id=? and deleted_at is null");
        String q = searchPattern(search);
        if (q != null) {
            where.append(" and lower(name) like ?");
            args.add(q);
        }
        String f = clean(lifecycle);
        if (f != null) {
            switch (f.toLowerCase(Locale.ROOT)) {
                case "closed" -> where.append(" and is_closed = true");
                case "open" -> where.append(" and is_closed = false");
                case "won" -> where.append(" and is_won = true");
                case "lost" -> where.append(" and is_closed = true and is_won = false");
                default -> { }
            }
        }
        return where.toString();
    }

    private String searchPattern(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : "%" + cleaned.toLowerCase(Locale.ROOT) + "%";
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private byte[] xlsx(Master master, TabularData data) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(master.name());
            Row header = sheet.createRow(0);
            for (int c = 0; c < data.headers.size(); c++) header.createCell(c).setCellValue(data.headers.get(c));
            for (int r = 0; r < data.rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < data.rows.get(r).size(); c++) row.createCell(c).setCellValue(data.rows.get(r).get(c));
            }
            for (int c = 0; c < data.headers.size(); c++) sheet.autoSizeColumn(c);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) { throw new IllegalStateException("Excel export failed", ex); }
    }

    private byte[] docx(Master master, TabularData data) {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Axiom CRM — " + master.name().replace('_', ' '));
            XWPFTable table = document.createTable(data.rows.size() + 1, data.headers.size());
            for (int c = 0; c < data.headers.size(); c++) table.getRow(0).getCell(c).setText(data.headers.get(c));
            for (int r = 0; r < data.rows.size(); r++)
                for (int c = 0; c < data.headers.size(); c++) table.getRow(r + 1).getCell(c).setText(data.rows.get(r).get(c));
            document.write(out);
            return out.toByteArray();
        } catch (IOException ex) { throw new IllegalStateException("Word export failed", ex); }
    }

    private byte[] pdf(Master master, TabularData data) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            int index = -1;
            List<List<String>> lines = new ArrayList<>();
            lines.add(data.headers); lines.addAll(data.rows);
            while (index < lines.size() - 1) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText(); stream.setFont(bold, 14); stream.newLineAtOffset(42, 800);
                    stream.showText("Axiom CRM - " + master.name().replace('_', ' '));
                    stream.setLeading(15); stream.newLine(); stream.setFont(regular, 8);
                    for (int line = 0; line < 45 && ++index < lines.size(); line++) {
                        String text = String.join(" | ", lines.get(index)).replaceAll("[^\\x20-\\x7E]", "");
                        if (text.length() > 115) text = text.substring(0, 112) + "...";
                        stream.showText(text); stream.newLine();
                    }
                    stream.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException ex) { throw new IllegalStateException("PDF export failed", ex); }
    }
}

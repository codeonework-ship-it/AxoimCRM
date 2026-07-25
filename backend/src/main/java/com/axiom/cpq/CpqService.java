package com.axiom.cpq;

import com.axiom.api.PageResult;
import com.axiom.api.QueryService;
import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped CPQ read model.
 *
 * The write-heavy CPQ lifecycle (quote versioning, approvals, document/e-sign
 * hand-off) is deliberately not faked here. These queries expose the governed
 * CPQ records that already exist in the database so the commerce workspace is
 * real, paged, filterable and RLS-backed while vendor integrations remain out of
 * scope.
 */
@Service
@Transactional(readOnly = true)
public class CpqService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CpqService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record ProductRow(UUID id, String code, String name, String productFamily, String category,
                             String unitOfMeasure, boolean active, boolean bundle, boolean subscription,
                             BigDecimal defaultCost, long activePriceCount) {}

    public record PriceBookRow(UUID id, String code, String name, String currencyCode, String businessUnitCode,
                               String customerSegment, int versionNumber, String status, boolean defaultBook,
                               OffsetDateTime activatedAt, long entryCount) {}

    public record QuoteRow(UUID id, String quoteNumber, String name, int versionNumber, String status,
                           String approvalStatus, String accountName, String opportunityName, String ownerName,
                           String currencyCode, BigDecimal subtotal, BigDecimal discountTotal,
                           BigDecimal grandTotal, BigDecimal marginPct, LocalDate validFrom,
                           OffsetDateTime expiresAt) {}

    public record QuoteSummary(long totalQuotes, long draftQuotes, long approvalQuotes, long sentQuotes,
                               long acceptedQuotes, BigDecimal netPipeline, BigDecimal acceptedRevenue) {}
    public enum QuoteDocumentFormat {
        PDF("application/pdf", "pdf"),
        DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");

        final String contentType;
        final String extension;

        QuoteDocumentFormat(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
    }
    public record FilePayload(byte[] bytes, String contentType, String filename) {}

    public PageResult<ProductRow> products(String search, String category, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        String where = productWhere(search, category, args);
        long total = total("select count(*) from cpq.product p " + where, args);

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(QueryService.PAGE_SIZE);
        pageArgs.add(safePage * QueryService.PAGE_SIZE);

        List<ProductRow> items = jdbc.query("""
                select p.id, p.code, p.name, p.product_family, p.category, p.unit_of_measure,
                       p.is_active, p.is_bundle, p.is_subscription, p.default_cost,
                       count(e.id) filter (
                         where e.is_active
                           and e.effective_from <= current_date
                           and (e.effective_to is null or e.effective_to >= current_date)
                       ) as active_price_count
                from cpq.product p
                left join cpq.price_book_entry e on e.tenant_id = p.tenant_id and e.product_id = p.id
                """ + where + "\n" + """
                group by p.id, p.code, p.name, p.product_family, p.category, p.unit_of_measure,
                         p.is_active, p.is_bundle, p.is_subscription, p.default_cost
                order by p.product_family nulls last, p.category nulls last, p.name
                limit ? offset ?
                """,
                (rs, i) -> new ProductRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("product_family"),
                        rs.getString("category"),
                        rs.getString("unit_of_measure"),
                        rs.getBoolean("is_active"),
                        rs.getBoolean("is_bundle"),
                        rs.getBoolean("is_subscription"),
                        rs.getBigDecimal("default_cost"),
                        rs.getLong("active_price_count")),
                pageArgs.toArray());

        return PageResult.of(items, safePage, QueryService.PAGE_SIZE, total);
    }

    public PageResult<PriceBookRow> priceBooks(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        String where = priceBookWhere(search, status, args);
        long total = total("select count(*) from cpq.price_book b " + where, args);

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(QueryService.PAGE_SIZE);
        pageArgs.add(safePage * QueryService.PAGE_SIZE);

        List<PriceBookRow> items = jdbc.query("""
                select b.id, b.code, b.name, b.currency_code, b.business_unit_code, b.customer_segment,
                       b.version_number, b.status, b.is_default, b.activated_at,
                       count(e.id) as entry_count
                from cpq.price_book b
                left join cpq.price_book_entry e on e.tenant_id = b.tenant_id and e.price_book_id = b.id
                """ + where + "\n" + """
                group by b.id, b.code, b.name, b.currency_code, b.business_unit_code, b.customer_segment,
                         b.version_number, b.status, b.is_default, b.activated_at
                order by b.is_default desc, b.status, b.name
                limit ? offset ?
                """,
                (rs, i) -> new PriceBookRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("currency_code"),
                        rs.getString("business_unit_code"),
                        rs.getString("customer_segment"),
                        rs.getInt("version_number"),
                        rs.getString("status"),
                        rs.getBoolean("is_default"),
                        offsetDateTime(rs.getObject("activated_at")),
                        rs.getLong("entry_count")),
                pageArgs.toArray());

        return PageResult.of(items, safePage, QueryService.PAGE_SIZE, total);
    }

    public PageResult<QuoteRow> quotes(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        String where = quoteWhere(search, status, args);
        long total = total("""
                select count(*)
                from cpq.quote q
                join crm.account a on a.tenant_id = q.tenant_id and a.id = q.account_id
                left join sales.opportunity o on o.tenant_id = q.tenant_id and o.id = q.opportunity_id
                left join identity.app_user u on u.tenant_id = q.tenant_id and u.id = q.owner_id
                """ + where, args);

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(QueryService.PAGE_SIZE);
        pageArgs.add(safePage * QueryService.PAGE_SIZE);

        List<QuoteRow> items = jdbc.query("""
                select q.id, q.quote_number, q.name, q.version_number, q.status, q.approval_status,
                       a.name as account_name, o.name as opportunity_name, u.display_name as owner_name,
                       q.currency_code, q.subtotal, q.discount_total, q.grand_total, q.margin_pct,
                       q.valid_from, q.expires_at
                from cpq.quote q
                join crm.account a on a.tenant_id = q.tenant_id and a.id = q.account_id
                left join sales.opportunity o on o.tenant_id = q.tenant_id and o.id = q.opportunity_id
                left join identity.app_user u on u.tenant_id = q.tenant_id and u.id = q.owner_id
                """ + where + "\n" + """
                order by q.created_at desc, q.quote_number desc
                limit ? offset ?
                """,
                (rs, i) -> new QuoteRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("quote_number"),
                        rs.getString("name"),
                        rs.getInt("version_number"),
                        rs.getString("status"),
                        rs.getString("approval_status"),
                        rs.getString("account_name"),
                        rs.getString("opportunity_name"),
                        rs.getString("owner_name"),
                        rs.getString("currency_code"),
                        rs.getBigDecimal("subtotal"),
                        rs.getBigDecimal("discount_total"),
                        rs.getBigDecimal("grand_total"),
                        rs.getBigDecimal("margin_pct"),
                        rs.getObject("valid_from", LocalDate.class),
                        offsetDateTime(rs.getObject("expires_at"))),
                pageArgs.toArray());

        return PageResult.of(items, safePage, QueryService.PAGE_SIZE, total);
    }

    public QuoteSummary quoteSummary() {
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) as total_quotes,
                       count(*) filter (where status = 'DRAFT') as draft_quotes,
                       count(*) filter (where approval_status = 'PENDING' or status = 'IN_APPROVAL') as approval_quotes,
                       count(*) filter (where status = 'SENT') as sent_quotes,
                       count(*) filter (where status in ('ACCEPTED','ORDERED')) as accepted_quotes,
                       coalesce(sum(grand_total) filter (where status in ('DRAFT','IN_APPROVAL','SENT')), 0) as net_pipeline,
                       coalesce(sum(grand_total) filter (where status in ('ACCEPTED','ORDERED')), 0) as accepted_revenue
                from cpq.quote
                where tenant_id = ? and deleted_at is null
                """, tenantId());

        return new QuoteSummary(
                number(row.get("total_quotes")).longValue(),
                number(row.get("draft_quotes")).longValue(),
                number(row.get("approval_quotes")).longValue(),
                number(row.get("sent_quotes")).longValue(),
                number(row.get("accepted_quotes")).longValue(),
                (BigDecimal) row.get("net_pipeline"),
                (BigDecimal) row.get("accepted_revenue"));
    }

    @Transactional
    public FilePayload quoteDocument(UUID quoteId, QuoteDocumentFormat format) {
        CrmRole.requireExport(TenantContext.get().role());
        QuoteRow quote = quote(quoteId);
        byte[] bytes = switch (format) {
            case PDF -> quotePdf(quote);
            case DOCX -> quoteDocx(quote);
            case XLSX -> quoteXlsx(quote);
        };
        audit.record("QUOTE_DOCUMENT_DOWNLOAD", "QUOTE", quote.id(),
                "Downloaded quote document " + quote.quoteNumber(),
                Map.of("format", format.name(), "rowCount", 1,
                        "quoteNumber", quote.quoteNumber(), "destination", "BROWSER_DOWNLOAD"));
        return new FilePayload(bytes, format.contentType,
                quote.quoteNumber().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + "." + format.extension);
    }

    private QuoteRow quote(UUID quoteId) {
        List<QuoteRow> rows = jdbc.query("""
                select q.id, q.quote_number, q.name, q.version_number, q.status, q.approval_status,
                       a.name as account_name, o.name as opportunity_name, u.display_name as owner_name,
                       q.currency_code, q.subtotal, q.discount_total, q.grand_total, q.margin_pct,
                       q.valid_from, q.expires_at
                from cpq.quote q
                join crm.account a on a.tenant_id = q.tenant_id and a.id = q.account_id
                left join sales.opportunity o on o.tenant_id = q.tenant_id and o.id = q.opportunity_id
                left join identity.app_user u on u.tenant_id = q.tenant_id and u.id = q.owner_id
                where q.tenant_id = ? and q.id = ? and q.deleted_at is null
                """, this::mapQuote, tenantId(), quoteId);
        return rows.stream().findFirst().orElseThrow(() -> new NotFoundException("Quote not found: " + quoteId));
    }

    private QuoteRow mapQuote(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new QuoteRow(
                rs.getObject("id", UUID.class),
                rs.getString("quote_number"),
                rs.getString("name"),
                rs.getInt("version_number"),
                rs.getString("status"),
                rs.getString("approval_status"),
                rs.getString("account_name"),
                rs.getString("opportunity_name"),
                rs.getString("owner_name"),
                rs.getString("currency_code"),
                rs.getBigDecimal("subtotal"),
                rs.getBigDecimal("discount_total"),
                rs.getBigDecimal("grand_total"),
                rs.getBigDecimal("margin_pct"),
                rs.getObject("valid_from", LocalDate.class),
                offsetDateTime(rs.getObject("expires_at")));
    }

    private byte[] quotePdf(QuoteRow quote) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
                PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                float y = 740;
                y = pdfLine(stream, bold, 16, 44, y, "Axiom CRM Quote " + quote.quoteNumber());
                y = pdfLine(stream, regular, 10, 44, y - 14, "Account: " + quote.accountName());
                y = pdfLine(stream, regular, 10, 44, y - 10, "Opportunity: " + nullSafe(quote.opportunityName()));
                y = pdfLine(stream, regular, 10, 44, y - 10, "Owner: " + nullSafe(quote.ownerName()));
                y = pdfLine(stream, regular, 10, 44, y - 10, "Status: " + quote.status() + " / " + quote.approvalStatus());
                y = pdfLine(stream, bold, 12, 44, y - 18, "Grand total: " + quote.currencyCode() + " " + quote.grandTotal());
                pdfLine(stream, regular, 10, 44, y - 12, "Subtotal " + quote.subtotal() + " | Discount " + quote.discountTotal() + " | Margin " + quote.marginPct() + "%");
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Quote PDF generation failed", ex);
        }
    }

    private float pdfLine(PDPageContentStream stream, PDType1Font font, int size, float x, float y, String text) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        stream.showText(text == null ? "" : text.replace('\n', ' '));
        stream.endText();
        return y;
    }

    private byte[] quoteDocx(QuoteRow quote) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Axiom CRM Quote " + quote.quoteNumber());
            XWPFTable table = doc.createTable(8, 2);
            List<List<String>> rows = quoteDocumentRows(quote);
            for (int r = 0; r < rows.size(); r++) {
                table.getRow(r).getCell(0).setText(rows.get(r).get(0));
                table.getRow(r).getCell(1).setText(rows.get(r).get(1));
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Quote Word generation failed", ex);
        }
    }

    private byte[] quoteXlsx(QuoteRow quote) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Quote");
            List<List<String>> rows = quoteDocumentRows(quote);
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue(rows.get(r).get(0));
                row.createCell(1).setCellValue(rows.get(r).get(1));
            }
            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Quote Excel generation failed", ex);
        }
    }

    private List<List<String>> quoteDocumentRows(QuoteRow q) {
        return List.of(
                List.of("Quote", q.quoteNumber() + " v" + q.versionNumber()),
                List.of("Name", q.name()),
                List.of("Account", q.accountName()),
                List.of("Opportunity", nullSafe(q.opportunityName())),
                List.of("Owner", nullSafe(q.ownerName())),
                List.of("Status", q.status() + " / " + q.approvalStatus()),
                List.of("Currency", q.currencyCode()),
                List.of("Grand total", q.grandTotal().toPlainString())
        );
    }

    private String productWhere(String search, String category, List<Object> args) {
        StringBuilder where = new StringBuilder(" where p.tenant_id = ? and p.deleted_at is null");
        String q = searchPattern(search);
        if (q != null) {
            where.append(" and (lower(p.code) like ? or lower(p.name) like ? or lower(coalesce(p.product_family,'')) like ? or lower(coalesce(p.category,'')) like ?)");
            args.add(q); args.add(q); args.add(q); args.add(q);
        }
        String f = clean(category);
        if (f != null) {
            where.append(" and lower(coalesce(p.category,'')) = ?");
            args.add(f.toLowerCase(Locale.ROOT));
        }
        return where.toString();
    }

    private String priceBookWhere(String search, String status, List<Object> args) {
        StringBuilder where = new StringBuilder(" where b.tenant_id = ?");
        String q = searchPattern(search);
        if (q != null) {
            where.append(" and (lower(b.code) like ? or lower(b.name) like ? or lower(coalesce(b.customer_segment,'')) like ? or lower(coalesce(b.business_unit_code,'')) like ?)");
            args.add(q); args.add(q); args.add(q); args.add(q);
        }
        String f = clean(status);
        if (f != null) {
            where.append(" and upper(b.status) = ?");
            args.add(f.toUpperCase(Locale.ROOT));
        }
        return where.toString();
    }

    private String quoteWhere(String search, String status, List<Object> args) {
        StringBuilder where = new StringBuilder(" where q.tenant_id = ? and q.deleted_at is null");
        String q = searchPattern(search);
        if (q != null) {
            where.append(" and (lower(q.quote_number) like ? or lower(q.name) like ? or lower(a.name) like ? or lower(coalesce(o.name,'')) like ? or lower(coalesce(u.display_name,'')) like ?)");
            args.add(q); args.add(q); args.add(q); args.add(q); args.add(q);
        }
        String f = clean(status);
        if (f != null) {
            where.append(" and upper(q.status) = ?");
            args.add(f.toUpperCase(Locale.ROOT));
        }
        return where.toString();
    }

    private UUID tenantId() {
        return TenantContext.get().tenantId();
    }

    private long total(String sql, List<Object> args) {
        Long value = jdbc.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0 : value;
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

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private Number number(Object value) {
        return value instanceof Number n ? n : 0;
    }

    private OffsetDateTime offsetDateTime(Object value) {
        if (value instanceof OffsetDateTime dateTime) return dateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        return null;
    }
}

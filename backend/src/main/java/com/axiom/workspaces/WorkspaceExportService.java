package com.axiom.workspaces;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class WorkspaceExportService {
    public enum ExportFormat {
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
        PDF("application/pdf", "pdf");

        final String contentType;
        final String extension;

        ExportFormat(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
    }

    public record FilePayload(byte[] bytes, String contentType, String filename) {}

    private static final List<String> HEADERS = List.of(
            "Code", "Record", "Context", "Owner", "Amount", "Target date", "Status", "Updated", "Signals");

    private final EpicWorkspaceService workspaces;
    private final AuditService audit;

    public WorkspaceExportService(EpicWorkspaceService workspaces, AuditService audit) {
        this.workspaces = workspaces;
        this.audit = audit;
    }

    @Transactional
    public FilePayload export(String module, ExportFormat format, String search, String status, int page) {
        CrmRole.requireExport(TenantContext.get().role());
        EpicWorkspaceService.WorkspacePage workspace = workspaces.workspace(module, search, status, page);
        List<EpicWorkspaceService.WorkspaceRow> rows = workspace.rows().items();
        byte[] bytes = switch (format) {
            case XLSX -> xlsx(workspace, rows);
            case DOCX -> docx(workspace, rows);
            case PDF -> pdf(workspace, rows);
        };
        audit.record("WORKSPACE_EXPORT", "WORKSPACE", null,
                "Exported " + rows.size() + " " + workspace.title() + " workspace rows",
                Map.of("module", workspace.moduleCode(), "format", format.name(), "rowCount", rows.size(),
                        "page", workspace.rows().page(), "pageSize", workspace.rows().size(),
                        "search", clean(search), "status", clean(status), "destination", "BROWSER_DOWNLOAD"));
        return new FilePayload(bytes, format.contentType,
                safeName(workspace.title()) + "-workspace-page-" + (workspace.rows().page() + 1) + "." + format.extension);
    }

    private byte[] xlsx(EpicWorkspaceService.WorkspacePage workspace, List<EpicWorkspaceService.WorkspaceRow> rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Workspace");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue(workspace.title());
            Row header = sheet.createRow(2);
            for (int i = 0; i < HEADERS.size(); i++) header.createCell(i).setCellValue(HEADERS.get(i));
            for (int r = 0; r < rows.size(); r++) {
                EpicWorkspaceService.WorkspaceRow row = rows.get(r);
                Row xrow = sheet.createRow(r + 3);
                xrow.createCell(0).setCellValue(text(row.code()));
                xrow.createCell(1).setCellValue(text(row.title()));
                xrow.createCell(2).setCellValue(text(row.subtitle()));
                xrow.createCell(3).setCellValue(text(row.ownerName()));
                xrow.createCell(4).setCellValue(row.amount() == null ? "" : row.amount().toPlainString());
                xrow.createCell(5).setCellValue(row.targetDate() == null ? "" : row.targetDate().toString());
                xrow.createCell(6).setCellValue(text(row.status()));
                xrow.createCell(7).setCellValue(row.updatedAt() == null ? "" : row.updatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                xrow.createCell(8).setCellValue(metrics(row.metrics()));
            }
            for (int i = 0; i < HEADERS.size(); i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Workspace Excel export failed", ex);
        }
    }

    private byte[] docx(EpicWorkspaceService.WorkspacePage workspace, List<EpicWorkspaceService.WorkspaceRow> rows) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(workspace.title() + " workspace export");
            XWPFTable table = doc.createTable(Math.max(rows.size() + 1, 2), HEADERS.size());
            for (int i = 0; i < HEADERS.size(); i++) table.getRow(0).getCell(i).setText(HEADERS.get(i));
            for (int r = 0; r < rows.size(); r++) {
                EpicWorkspaceService.WorkspaceRow row = rows.get(r);
                List<String> cells = rowValues(row);
                for (int c = 0; c < cells.size(); c++) table.getRow(r + 1).getCell(c).setText(cells.get(c));
            }
            if (rows.isEmpty()) table.getRow(1).getCell(0).setText("No records matched the current search and status filters.");
            doc.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Workspace Word export failed", ex);
        }
    }

    private byte[] pdf(EpicWorkspaceService.WorkspacePage workspace, List<EpicWorkspaceService.WorkspaceRow> rows) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                float y = 745;
                stream.beginText();
                stream.setFont(bold, 13);
                stream.newLineAtOffset(40, y);
                stream.showText(workspace.title() + " workspace export");
                stream.endText();
                y -= 28;
                for (EpicWorkspaceService.WorkspaceRow row : rows) {
                    if (y < 60) break;
                    stream.beginText();
                    stream.setFont(bold, 9);
                    stream.newLineAtOffset(40, y);
                    stream.showText(trim(row.code() + " - " + row.title(), 90));
                    stream.endText();
                    y -= 12;
                    stream.beginText();
                    stream.setFont(regular, 8);
                    stream.newLineAtOffset(48, y);
                    stream.showText(trim(row.status() + " | " + text(row.ownerName()) + " | " + text(row.subtitle()), 105));
                    stream.endText();
                    y -= 16;
                }
                if (rows.isEmpty()) {
                    stream.beginText();
                    stream.setFont(regular, 9);
                    stream.newLineAtOffset(40, y);
                    stream.showText("No records matched the current search and status filters.");
                    stream.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Workspace PDF export failed", ex);
        }
    }

    private List<String> rowValues(EpicWorkspaceService.WorkspaceRow row) {
        return List.of(text(row.code()), text(row.title()), text(row.subtitle()), text(row.ownerName()),
                row.amount() == null ? "" : row.amount().toPlainString(),
                row.targetDate() == null ? "" : row.targetDate().toString(),
                text(row.status()), row.updatedAt() == null ? "" : row.updatedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                metrics(row.metrics()));
    }

    private String metrics(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) return "";
        return metrics.entrySet().stream()
                .limit(8)
                .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeName(String value) {
        return text(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private String trim(String value, int max) {
        String cleaned = text(value).replace('\n', ' ').replace('\r', ' ');
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, Math.max(0, max - 1)) + "...";
    }
}

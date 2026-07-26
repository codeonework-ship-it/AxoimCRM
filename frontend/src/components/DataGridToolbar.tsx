import { useState, type ReactNode } from "react";
import { api, type DownloadedFile } from "../api/client";
import { AuditDrawer } from "./AuditDrawer";
import { GroupColumnPicker, type GroupColumnOption } from "./GroupColumnPicker";
import { InfoTag } from "./InfoTag";
import { useToasts } from "./Toasts";

export type GridExportFormat = "XLSX" | "DOCX" | "PDF";
export type GridExportRow = Record<string, unknown>;

export interface GridExportContext {
  title?: string;
  objectType?: string;
  generatedAt?: Date;
  rowCount?: number;
  groups?: string[];
  filters?: Array<{ label: string; value: string }>;
}

interface DataGridToolbarProps {
  gridName: string;
  grouped?: boolean;
  groupLabel?: string;
  onToggleGroup?: () => void;
  groupColumns?: GroupColumnOption[];
  selectedGroupColumns?: string[];
  onGroupColumnsChange?: (next: string[]) => void;
  /**
   * Column labels used only when naming active filters in an export header, so
   * a PDF says "Industry: Manufacturing" rather than the raw key. The filter
   * CONTROLS live in each grid header now, not here — nothing in this toolbar
   * renders these. Omitted by every caller today; the export falls back to
   * title-casing the key, which is correct for the keys in use.
   */
  filterColumns?: GroupColumnOption[];
  columnFilters?: Record<string, string>;
  /** Retained for API compatibility; the header filter row owns the setter now. */
  onColumnFiltersChange?: (next: Record<string, string>) => void;
  auditEntityType?: string;
  auditTitle?: string;
  exportFilename?: string;
  exportRows?: GridExportRow[];
  onExport?: (format: GridExportFormat) => Promise<DownloadedFile>;
  note?: ReactNode;
  children?: ReactNode;
}

export function saveDownloadedFile(file: DownloadedFile) {
  const url = URL.createObjectURL(file.blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = file.filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 500);
}

export function DataGridToolbar({
  gridName,
  grouped = false,
  groupLabel = "Group",
  onToggleGroup,
  groupColumns,
  selectedGroupColumns,
  onGroupColumnsChange,
  filterColumns,
  columnFilters,
  auditEntityType,
  auditTitle,
  exportFilename,
  exportRows,
  onExport,
  note,
  children,
}: DataGridToolbarProps) {
  const toasts = useToasts();
  const [auditOpen, setAuditOpen] = useState(false);
  const canExport = !!onExport || !!exportRows;
  const viewContext = gridExportContext({
    title: `${gridName} current view`,
    objectType: auditEntityType ?? exportObjectType(gridName),
    rows: exportRows,
    groupColumns,
    selectedGroupColumns,
    filterColumns,
    columnFilters,
  });

  async function download(format: GridExportFormat) {
    try {
      const file = exportRows
        ? createCurrentViewExport(format, exportRows, exportFilename ?? slug(gridName), viewContext)
        : await onExport?.(format);
      if (!file) throw new Error("No export source is configured for this grid.");
      if (exportRows) await recordCurrentViewExportAudit(format, viewContext);
      saveDownloadedFile(file);
      toasts.push("info", `Export ${formatLabel(format)} ready`, "The download reflects the current Data Grid view.");
    } catch (error) {
      toasts.push("error", `Export ${formatLabel(format)} failed`, error instanceof Error ? error.message : "Download failed.");
    }
  }

  async function copyViewSnapshot() {
    if (!viewContext || !exportRows) return;
    try {
      const result = await copyGridSnapshot(exportRows, viewContext, `${slug(gridName)}-view-snapshot`);
      if (result === "clipboard") {
        toasts.push("info", "Grid snapshot copied", "The image includes the current columns, rows, filters, grouping and timestamp.");
      } else {
        toasts.push("info", "Grid snapshot downloaded", "This browser blocked image clipboard access, so the complete PNG snapshot was downloaded instead.");
      }
    } catch (error) {
      toasts.push("error", "Grid snapshot not created", error instanceof Error ? error.message : "The snapshot could not be created.");
    }
  }

  return (
    <>
      {/*
        Three rows, one grid. Every row is `grid-tool-row`, so the section label,
        the controls and the trailing action each start and end on the same axis
        no matter how many controls a row happens to hold. Before this the rows
        were independent wrapping flex lines, which is why "Group" and "Column
        search" sat at different left edges and the trailing Clear links floated
        to wherever the last control finished.
      */}
      <div className="data-grid-tools-stack">
        <div className="grid-tool-row data-grid-toolbar" role="toolbar" aria-label={`${gridName} data grid tools`}>
          <div className="grid-tool-label">
            <span>Actions</span>
            <InfoTag
              text="Use these tools to group rows, search columns, view audit history, or download the current grid."
              label={`${gridName} grid tools help`}
            />
          </div>
          <div className="grid-tool-controls">
            {!(groupColumns && onGroupColumnsChange) && (
              <button className={`btn btn-sm${grouped ? " active" : ""}`} aria-pressed={grouped} disabled={!onToggleGroup} onClick={onToggleGroup}>
                Group: {grouped ? groupLabel : "Off"}
              </button>
            )}
            <button className="btn btn-sm" disabled={!auditEntityType} onClick={() => setAuditOpen(true)}>Audit</button>
            <span className="toolbar-divider" aria-hidden />
            <button className="btn btn-sm" disabled={!canExport} onClick={() => void download("XLSX")}>Export Excel</button>
            <button className="btn btn-sm" disabled={!canExport} onClick={() => void download("DOCX")}>Export Word</button>
            <button className="btn btn-sm" disabled={!canExport} onClick={() => void download("PDF")}>Export PDF</button>
            <button className="btn btn-sm" disabled={!viewContext || !exportRows} onClick={() => void copyViewSnapshot()}>Copy view</button>
            {children}
          </div>
          <div className="grid-tool-trailing">
            {viewContext && <span className="grid-view-summary" aria-live="polite">{viewSummary(viewContext)}</span>}
            {note && <span className="cpq-note">{note}</span>}
          </div>
        </div>
        {groupColumns && onGroupColumnsChange && (
          <GroupColumnPicker
            id={`${slug(gridName)}-toolbar`}
            columns={groupColumns}
            selected={selectedGroupColumns ?? []}
            onChange={onGroupColumnsChange}
          />
        )}
      </div>
      {auditEntityType && <AuditDrawer
        open={auditOpen}
        entityType={auditEntityType}
        title={auditTitle ?? `${gridName} audit`}
        emptyLabel="No audited actions for this grid yet."
        onClose={() => setAuditOpen(false)}
      />}
    </>
  );
}

export function createCurrentViewExport(
  format: GridExportFormat,
  rows: GridExportRow[],
  baseFilename: string,
  context?: GridExportContext,
): DownloadedFile {
  return createCurrentViewExportWithContext(format, rows, baseFilename, context);
}

export function createCurrentViewExportWithContext(
  format: GridExportFormat,
  rows: GridExportRow[],
  baseFilename: string,
  context?: GridExportContext,
): DownloadedFile {
  const normalized = normalizeRows(rows);
  const exportContext = normalizeExportContext(context, rows.length, baseFilename);
  if (format === "XLSX") {
    return {
      blob: new Blob([tableHtml(normalized, exportContext)], { type: "application/vnd.ms-excel;charset=utf-8" }),
      filename: `${baseFilename}.xls`,
    };
  }
  if (format === "DOCX") {
    return {
      blob: new Blob([documentHtml(normalized, exportContext)], { type: "application/msword;charset=utf-8" }),
      filename: `${baseFilename}.doc`,
    };
  }
  return {
    blob: new Blob([pdfDocument(normalized, exportContext)], { type: "application/pdf" }),
    filename: `${baseFilename}.pdf`,
  };
}

export function gridExportContext({
  title,
  objectType,
  rows,
  groupColumns,
  selectedGroupColumns,
  filterColumns,
  columnFilters,
}: {
  title: string;
  objectType?: string;
  rows?: GridExportRow[];
  groupColumns?: GroupColumnOption[];
  selectedGroupColumns?: string[];
  filterColumns?: GroupColumnOption[];
  columnFilters?: Record<string, string>;
}): GridExportContext | undefined {
  if (!rows) return undefined;
  const groupLabels = selectedGroupColumns
    ?.map((key) => lookupColumnLabel(key, groupColumns))
    .filter((value): value is string => !!value) ?? [];
  const filters = Object.entries(columnFilters ?? {})
    .map(([key, value]) => ({ label: lookupColumnLabel(key, filterColumns) ?? label(key), value: value.trim() }))
    .filter((entry) => entry.value.length > 0);
  return {
    title,
    objectType,
    rowCount: rows.length,
    generatedAt: new Date(),
    groups: groupLabels,
    filters,
  };
}

export function gridViewSummaryText(context: GridExportContext): string {
  const normalized = normalizeExportContext(context, context.rowCount ?? 0, context.title ?? "Data Grid");
  return [
    normalized.title,
    `Generated: ${formatDateTime(normalized.generatedAt)}`,
    `Rows: ${normalized.rowCount}`,
    `Groups: ${normalized.groups.length ? normalized.groups.join(" > ") : "None"}`,
    `Column filters: ${normalized.filters.length ? normalized.filters.map((filter) => `${filter.label}: ${filter.value}`).join("; ") : "None"}`,
  ].join("\n");
}

export async function writeClipboardText(value: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value);
    return;
  }
  const textarea = document.createElement("textarea");
  textarea.value = value;
  textarea.setAttribute("readonly", "true");
  textarea.style.position = "fixed";
  textarea.style.left = "-9999px";
  textarea.style.top = "0";
  document.body.appendChild(textarea);
  textarea.focus();
  textarea.select();
  const copied = document.execCommand("copy");
  textarea.remove();
  if (!copied) throw new Error("Clipboard permission was denied by the browser.");
}

/**
 * Copy a faithful visual record of the current Data Grid view.
 *
 * The snapshot is rendered from the same normalized rows used by Excel, Word
 * and PDF exports. It therefore includes data that may sit below the browser
 * fold, while excluding unrelated page chrome and private popovers. Canvas is
 * used instead of a DOM screenshot library so the result is deterministic in
 * every theme and does not require a new runtime dependency.
 */
export async function copyGridSnapshot(
  rows: GridExportRow[],
  context: GridExportContext,
  filename = "grid-view-snapshot",
): Promise<"clipboard" | "download"> {
  const blob = await createGridSnapshotBlob(rows, context);
  if (navigator.clipboard?.write && typeof ClipboardItem !== "undefined") {
    try {
      await navigator.clipboard.write([new ClipboardItem({ "image/png": blob })]);
      return "clipboard";
    } catch {
      // Clipboard image support varies by browser and enterprise policy. The
      // PNG download preserves the requested visual snapshot without falling
      // back to the old plain-text summary.
    }
  }
  saveDownloadedFile({ blob, filename: `${slug(filename)}.png` });
  return "download";
}

export async function createGridSnapshotBlob(
  rows: GridExportRow[],
  context: GridExportContext,
): Promise<Blob> {
  const data = normalizeRows(rows);
  const normalized = normalizeExportContext(context, rows.length, "Data Grid view");
  const cellPadding = 12;
  const rowHeight = 34;
  const headerHeight = 38;
  const pagePadding = 24;
  const reportHeaderHeight = 132;
  const footerHeight = 42;
  const minColumnWidth = 120;
  const maxColumnWidth = 280;
  const measure = document.createElement("canvas").getContext("2d");
  if (!measure) throw new Error("This browser cannot render a grid snapshot.");
  measure.font = "600 12px Arial, sans-serif";
  const widths = data.headers.map((header, columnIndex) => {
    const longest = Math.max(
      measure.measureText(label(header)).width,
      ...data.rows.slice(0, 100).map((row) => measure.measureText(row[columnIndex] ?? "").width),
    );
    return Math.min(maxColumnWidth, Math.max(minColumnWidth, Math.ceil(longest) + cellPadding * 2));
  });
  const tableWidth = Math.max(852, widths.reduce((sum, width) => sum + width, 0));
  const logicalWidth = tableWidth + pagePadding * 2;
  const bodyHeight = Math.max(rowHeight, data.rows.length * rowHeight);
  const logicalHeight = reportHeaderHeight + headerHeight + bodyHeight + footerHeight + pagePadding;
  const pixelRatio = logicalWidth > 4096 || logicalHeight > 8192
    ? 1
    : Math.min(window.devicePixelRatio || 1, 2);
  const canvas = document.createElement("canvas");
  canvas.width = Math.ceil(logicalWidth * pixelRatio);
  canvas.height = Math.ceil(logicalHeight * pixelRatio);
  const painter = canvas.getContext("2d");
  if (!painter) throw new Error("This browser cannot render a grid snapshot.");
  painter.scale(pixelRatio, pixelRatio);

  const styles = getComputedStyle(document.documentElement);
  const palette = {
    page: cssColour(styles, "--paper", "#F4F7FA"),
    raised: cssColour(styles, "--paper-raised", "#FFFFFF"),
    sunk: cssColour(styles, "--paper-sunk", "#E9EFF5"),
    ink: cssColour(styles, "--ink", "#172235"),
    soft: cssColour(styles, "--ink-soft", "#40516A"),
    slate: cssColour(styles, "--slate", "#64748B"),
    line: cssColour(styles, "--line", "#C8D5E3"),
    accent: cssColour(styles, "--ion-600", "#0783A7"),
  };

  painter.fillStyle = palette.page;
  painter.fillRect(0, 0, logicalWidth, logicalHeight);
  painter.fillStyle = palette.raised;
  painter.fillRect(pagePadding, pagePadding, tableWidth, logicalHeight - pagePadding * 2);
  painter.fillStyle = palette.accent;
  painter.fillRect(pagePadding, pagePadding, 5, reportHeaderHeight - pagePadding);

  painter.textBaseline = "middle";
  painter.fillStyle = palette.ink;
  painter.font = "700 22px Arial, sans-serif";
  painter.fillText(ellipsize(painter, normalized.title, tableWidth - 250), pagePadding + 20, pagePadding + 25);
  painter.fillStyle = palette.slate;
  painter.font = "600 11px Arial, sans-serif";
  painter.textAlign = "right";
  painter.fillText(formatDateTime(normalized.generatedAt), pagePadding + tableWidth - 16, pagePadding + 25);
  painter.textAlign = "left";

  const groups = normalized.groups.length ? normalized.groups.join(" > ") : "None";
  const filters = normalized.filters.length
    ? normalized.filters.map((filter) => `${filter.label}: ${filter.value}`).join("; ")
    : "None";
  const metaRows = [
    `ROWS  ${normalized.rowCount}`,
    `GROUPS  ${groups}`,
    `COLUMN FILTERS  ${filters}`,
  ];
  metaRows.forEach((value, index) => {
    painter.fillStyle = index === 0 ? palette.accent : palette.soft;
    painter.font = index === 0 ? "700 11px Arial, sans-serif" : "600 11px Arial, sans-serif";
    painter.fillText(ellipsize(painter, value, tableWidth - 40), pagePadding + 20, pagePadding + 58 + index * 23);
  });

  let y = reportHeaderHeight;
  painter.fillStyle = palette.sunk;
  painter.fillRect(pagePadding, y, tableWidth, headerHeight);
  painter.strokeStyle = palette.line;
  painter.lineWidth = 1;
  let x = pagePadding;
  painter.font = "700 11px Arial, sans-serif";
  painter.fillStyle = palette.ink;
  data.headers.forEach((header, index) => {
    painter.fillText(ellipsize(painter, label(header), widths[index] - cellPadding * 2), x + cellPadding, y + headerHeight / 2);
    painter.beginPath();
    painter.moveTo(x + widths[index], y);
    painter.lineTo(x + widths[index], logicalHeight - pagePadding - footerHeight);
    painter.stroke();
    x += widths[index];
  });
  painter.strokeRect(pagePadding, y, tableWidth, headerHeight + bodyHeight);
  y += headerHeight;

  painter.font = "400 12px Arial, sans-serif";
  if (data.rows.length === 0) {
    painter.fillStyle = palette.slate;
    painter.fillText("No records match the current grid view.", pagePadding + cellPadding, y + rowHeight / 2);
  } else {
    data.rows.forEach((row, rowIndex) => {
      if (rowIndex % 2 === 1) {
        painter.fillStyle = palette.sunk;
        painter.fillRect(pagePadding, y, tableWidth, rowHeight);
      }
      painter.fillStyle = palette.ink;
      x = pagePadding;
      row.forEach((value, columnIndex) => {
        painter.fillText(ellipsize(painter, value, widths[columnIndex] - cellPadding * 2), x + cellPadding, y + rowHeight / 2);
        x += widths[columnIndex];
      });
      painter.strokeStyle = palette.line;
      painter.beginPath();
      painter.moveTo(pagePadding, y + rowHeight);
      painter.lineTo(pagePadding + tableWidth, y + rowHeight);
      painter.stroke();
      y += rowHeight;
    });
  }

  painter.fillStyle = palette.slate;
  painter.font = "600 10px Arial, sans-serif";
  painter.fillText(
    `AXIOM DATA GRID SNAPSHOT  •  CURRENT FILTERED VIEW  •  ${normalized.rowCount} ROWS`,
    pagePadding + 2,
    logicalHeight - pagePadding,
  );

  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error("The PNG snapshot could not be encoded.")), "image/png");
  });
}

function cssColour(styles: CSSStyleDeclaration, token: string, fallback: string): string {
  const value = styles.getPropertyValue(token).trim();
  return value && !value.startsWith("var(") && !value.startsWith("color-mix(") ? value : fallback;
}

function ellipsize(context: CanvasRenderingContext2D, value: string, maxWidth: number): string {
  if (context.measureText(value).width <= maxWidth) return value;
  let low = 0;
  let high = value.length;
  while (low < high) {
    const middle = Math.ceil((low + high) / 2);
    if (context.measureText(`${value.slice(0, middle)}…`).width <= maxWidth) low = middle;
    else high = middle - 1;
  }
  return `${value.slice(0, low)}…`;
}

export async function recordCurrentViewExportAudit(format: GridExportFormat, context: GridExportContext | undefined): Promise<void> {
  const normalized = normalizeExportContext(context, context?.rowCount ?? 0, context?.title ?? "current-view");
  await api.recordClientExportAudit({
    objectType: normalized.objectType,
    rowCount: normalized.rowCount,
    format,
    destination: "CURRENT_VIEW_DOWNLOAD",
    filterCriteria: {
      title: normalized.title,
      groups: normalized.groups,
      filters: normalized.filters.map((filter) => `${filter.label}: ${filter.value}`),
    },
  });
}

function normalizeRows(rows: GridExportRow[]) {
  const headers = Array.from(rows.reduce((set, row) => {
    Object.keys(row).forEach((key) => set.add(key));
    return set;
  }, new Set<string>()));
  return {
    headers,
    rows: rows.map((row) => headers.map((header) => stringify(row[header]))),
  };
}

function tableHtml(data: { headers: string[]; rows: string[][] }, context: Required<GridExportContext>) {
  return `<!doctype html><html><head><meta charset="utf-8"></head><body>${metadataHtml(context)}<table border="1">${tableMarkup(data)}</table></body></html>`;
}

function documentHtml(data: { headers: string[]; rows: string[][] }, context: Required<GridExportContext>) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>body{font-family:Arial,sans-serif;color:#172235}table{border-collapse:collapse;width:100%;margin-top:14px}th,td{border:1px solid #999;padding:6px;text-align:left}th{background:#eef3f8}.meta{margin:0 0 12px}.meta dt{font-weight:bold}.meta dd{margin:0 0 6px}</style></head><body>${metadataHtml(context)}<table>${tableMarkup(data)}</table></body></html>`;
}

function tableMarkup(data: { headers: string[]; rows: string[][] }) {
  return `<thead><tr>${data.headers.map((header) => `<th>${escapeHtml(label(header))}</th>`).join("")}</tr></thead><tbody>${data.rows.map((row) => `<tr>${row.map((value) => `<td>${escapeHtml(value)}</td>`).join("")}</tr>`).join("")}</tbody>`;
}

function pdfDocument(data: { headers: string[]; rows: string[][] }, context: Required<GridExportContext>): string {
  const metadata = [
    context.title,
    `Generated: ${formatDateTime(context.generatedAt)}`,
    `Rows: ${context.rowCount}`,
    `Groups: ${context.groups.length ? context.groups.join(" > ") : "None"}`,
    `Filters: ${context.filters.length ? context.filters.map((filter) => `${filter.label}=${filter.value}`).join("; ") : "None"}`,
    "",
  ];
  const lines = [...metadata, data.headers.map(label).join(" | "), ...data.rows.map((row) => row.join(" | "))];
  const wrapped = lines.flatMap((line) => wrap(line || "-", 95)).slice(0, 46);
  const content = [
    "BT",
    "/F1 9 Tf",
    "36 806 Td",
    "14 TL",
    ...wrapped.map((line, index) => `${index === 0 ? "" : "T* "}${pdfEscape(line)} Tj`),
    "ET",
  ].join("\n");
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
  ];
  let pdf = "%PDF-1.4\n";
  const offsets = [0];
  objects.forEach((object, index) => {
    offsets.push(pdf.length);
    pdf += `${index + 1} 0 obj\n${object}\nendobj\n`;
  });
  const xref = pdf.length;
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  offsets.slice(1).forEach((offset) => { pdf += `${String(offset).padStart(10, "0")} 00000 n \n`; });
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  return pdf;
}

function wrap(value: string, width: number) {
  const out: string[] = [];
  for (let i = 0; i < value.length; i += width) out.push(value.slice(i, i + width));
  return out.length ? out : [value];
}

function stringify(value: unknown): string {
  if (value == null) return "";
  if (Array.isArray(value)) return value.join(", ");
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function label(value: string) {
  return value.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[_-]+/g, " ").replace(/\b\w/g, (char) => char.toUpperCase());
}

function slug(value: string) {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "") || "data-grid";
}

function escapeHtml(value: string) {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function pdfEscape(value: string) {
  return `(${value.replace(/[^\x20-\x7E]/g, "?").replace(/[\\()]/g, "\\$&")})`;
}

function formatLabel(format: GridExportFormat) {
  return format === "XLSX" ? "Excel" : format === "DOCX" ? "Word" : "PDF";
}

function normalizeExportContext(context: GridExportContext | undefined, rowCount: number, baseFilename: string): Required<GridExportContext> {
  return {
    title: context?.title ?? label(baseFilename),
    objectType: context?.objectType ?? exportObjectType(baseFilename),
    generatedAt: context?.generatedAt ?? new Date(),
    rowCount: context?.rowCount ?? rowCount,
    groups: context?.groups ?? [],
    filters: context?.filters ?? [],
  };
}

function exportObjectType(value: string) {
  return slug(value).replace(/-/g, "_").toUpperCase();
}

function metadataHtml(context: Required<GridExportContext>) {
  const filters = context.filters.length
    ? context.filters.map((filter) => `${filter.label}: ${filter.value}`).join("; ")
    : "None";
  const groups = context.groups.length ? context.groups.join(" > ") : "None";
  return `<h1>${escapeHtml(context.title)}</h1><dl class="meta"><dt>Generated</dt><dd>${escapeHtml(formatDateTime(context.generatedAt))}</dd><dt>Rows</dt><dd>${context.rowCount}</dd><dt>Groups</dt><dd>${escapeHtml(groups)}</dd><dt>Column filters</dt><dd>${escapeHtml(filters)}</dd></dl>`;
}

function lookupColumnLabel(key: string, columns?: GroupColumnOption[]) {
  return columns?.find((column) => column.key === key)?.label;
}

function viewSummary(context: GridExportContext) {
  const parts = [`${context.rowCount ?? 0} rows`];
  if (context.filters?.length) parts.push(`${context.filters.length} filters`);
  if (context.groups?.length) parts.push(`${context.groups.length} groups`);
  return parts.join(" · ");
}

function formatDateTime(value: Date) {
  return value.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

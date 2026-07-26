import { useState, type ReactNode } from "react";
import { api, type DownloadedFile } from "../api/client";
import { AuditDrawer } from "./AuditDrawer";
import { GridColumnFilters } from "./GridColumnFilters";
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
  filterColumns?: GroupColumnOption[];
  columnFilters?: Record<string, string>;
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
  onColumnFiltersChange,
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

  async function copyViewSummary() {
    if (!viewContext) return;
    try {
      await writeClipboardText(gridViewSummaryText(viewContext));
      toasts.push("info", "View summary copied", "Paste it into a ticket, chat or audit note to describe this exact grid view.");
    } catch (error) {
      toasts.push("error", "View summary not copied", error instanceof Error ? error.message : "Clipboard is unavailable.");
    }
  }

  return (
    <>
      <div className="data-grid-tools-stack">
        <div className="master-toolbar data-grid-toolbar" role="toolbar" aria-label={`${gridName} data grid tools`}>
          <InfoTag
            text="Use these tools to group rows, search columns, view audit history, or download the current grid."
            label={`${gridName} grid tools help`}
          />
          {groupColumns && onGroupColumnsChange ? (
            <GroupColumnPicker
              id={`${slug(gridName)}-toolbar`}
              columns={groupColumns}
              selected={selectedGroupColumns ?? []}
              onChange={onGroupColumnsChange}
            />
          ) : (
            <button className={`btn btn-sm${grouped ? " active" : ""}`} aria-pressed={grouped} disabled={!onToggleGroup} onClick={onToggleGroup}>
              Group: {grouped ? groupLabel : "Off"}
            </button>
          )}
          <button className="btn btn-sm" disabled={!auditEntityType} onClick={() => setAuditOpen(true)}>Audit</button>
          <span className="toolbar-divider" aria-hidden />
          <button className="btn btn-sm" disabled={!canExport} onClick={() => void download("XLSX")}>Export Excel</button>
          <button className="btn btn-sm" disabled={!canExport} onClick={() => void download("DOCX")}>Export Word</button>
          <button className="btn btn-sm" disabled={!canExport} onClick={() => void download("PDF")}>Export PDF</button>
          <button className="btn btn-sm" disabled={!viewContext} onClick={() => void copyViewSummary()}>Copy view</button>
          {children}
          {viewContext && <span className="grid-view-summary" aria-live="polite">{viewSummary(viewContext)}</span>}
          {note && <span className="cpq-note">{note}</span>}
        </div>
        {filterColumns && columnFilters && onColumnFiltersChange && (
          <GridColumnFilters
            id={`${slug(gridName)}-toolbar`}
            columns={filterColumns}
            filters={columnFilters}
            onChange={onColumnFiltersChange}
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

import { useState, type ReactNode } from "react";
import { type DownloadedFile } from "../api/client";
import { AuditDrawer } from "./AuditDrawer";
import { useToasts } from "./Toasts";

type GridExportFormat = "XLSX" | "DOCX" | "PDF";
type GridExportRow = Record<string, unknown>;

interface DataGridToolbarProps {
  gridName: string;
  grouped?: boolean;
  groupLabel?: string;
  onToggleGroup?: () => void;
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

  async function download(format: GridExportFormat) {
    try {
      const file = onExport
        ? await onExport(format)
        : createCurrentViewExport(format, exportRows ?? [], exportFilename ?? slug(gridName));
      saveDownloadedFile(file);
      toasts.push("info", `Export ${formatLabel(format)} ready`, "The download reflects the current Data Grid view.");
    } catch (error) {
      toasts.push("error", `Export ${formatLabel(format)} failed`, error instanceof Error ? error.message : "Download failed.");
    }
  }

  return (
    <>
      <div className="master-toolbar data-grid-toolbar" role="toolbar" aria-label={`${gridName} data grid tools`}>
        <button className={`btn btn-sm${grouped ? " active" : ""}`} aria-pressed={grouped} disabled={!onToggleGroup} onClick={onToggleGroup}>
          Group: {grouped ? groupLabel : "Off"}
        </button>
        <button className="btn btn-sm" disabled={!auditEntityType} onClick={() => setAuditOpen(true)}>Audit</button>
        <span className="toolbar-divider" aria-hidden />
        <button className="btn btn-sm" disabled={!canExport} onClick={() => void download("XLSX")}>Export Excel</button>
        <button className="btn btn-sm" disabled={!canExport} onClick={() => void download("DOCX")}>Export Word</button>
        <button className="btn btn-sm" disabled={!canExport} onClick={() => void download("PDF")}>Export PDF</button>
        {children}
        {note && <span className="cpq-note">{note}</span>}
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

function createCurrentViewExport(format: GridExportFormat, rows: GridExportRow[], baseFilename: string): DownloadedFile {
  const normalized = normalizeRows(rows);
  if (format === "XLSX") {
    return {
      blob: new Blob([tableHtml(normalized)], { type: "application/vnd.ms-excel;charset=utf-8" }),
      filename: `${baseFilename}.xls`,
    };
  }
  if (format === "DOCX") {
    return {
      blob: new Blob([documentHtml(normalized)], { type: "application/msword;charset=utf-8" }),
      filename: `${baseFilename}.doc`,
    };
  }
  return {
    blob: new Blob([pdfDocument(normalized)], { type: "application/pdf" }),
    filename: `${baseFilename}.pdf`,
  };
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

function tableHtml(data: { headers: string[]; rows: string[][] }) {
  return `<!doctype html><html><head><meta charset="utf-8"></head><body><table border="1">${tableMarkup(data)}</table></body></html>`;
}

function documentHtml(data: { headers: string[]; rows: string[][] }) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>body{font-family:Arial,sans-serif}table{border-collapse:collapse;width:100%}th,td{border:1px solid #999;padding:6px;text-align:left}th{background:#eef3f8}</style></head><body><h1>Data Grid Export</h1><table>${tableMarkup(data)}</table></body></html>`;
}

function tableMarkup(data: { headers: string[]; rows: string[][] }) {
  return `<thead><tr>${data.headers.map((header) => `<th>${escapeHtml(label(header))}</th>`).join("")}</tr></thead><tbody>${data.rows.map((row) => `<tr>${row.map((value) => `<td>${escapeHtml(value)}</td>`).join("")}</tr>`).join("")}</tbody>`;
}

function pdfDocument(data: { headers: string[]; rows: string[][] }): string {
  const lines = [data.headers.map(label).join(" | "), ...data.rows.map((row) => row.join(" | "))];
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

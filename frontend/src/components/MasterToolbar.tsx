import { useRef, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api, type DownloadedFile } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { AuditDrawer } from "./AuditDrawer";
import { createCurrentViewExport, gridExportContext, gridViewSummaryText, saveDownloadedFile, writeClipboardText, type GridExportFormat, type GridExportRow } from "./DataGridToolbar";
import { GridColumnFilters } from "./GridColumnFilters";
import { GroupColumnPicker, type GroupColumnOption } from "./GroupColumnPicker";
import { InfoTag } from "./InfoTag";
import { useToasts } from "./Toasts";

const MASTER_ADMIN_ROLES = new Set(["SUPER_ADMIN", "TENANT_ADMIN", "DATA_STEWARD"]);
const IMPORT_ROLES = MASTER_ADMIN_ROLES;

export function canManageMasters(role?: string): boolean {
  return !!role && MASTER_ADMIN_ROLES.has(role);
}

interface Props {
  master: "accounts" | "leads";
  entityType: "ACCOUNT" | "LEAD";
  search?: string;
  filter?: string;
  grouped: boolean;
  groupLabel: string;
  onToggleGroup: () => void;
  groupColumns?: GroupColumnOption[];
  selectedGroupColumns?: string[];
  onGroupColumnsChange?: (next: string[]) => void;
  filterColumns?: GroupColumnOption[];
  columnFilters?: Record<string, string>;
  onColumnFiltersChange?: (next: Record<string, string>) => void;
  exportFilename?: string;
  exportRows?: GridExportRow[];
  onChanged: () => void;
}

export function MasterToolbar({ master, entityType, search, filter, grouped, groupLabel, onToggleGroup, groupColumns, selectedGroupColumns, onGroupColumnsChange, filterColumns, columnFilters, onColumnFiltersChange, exportFilename, exportRows, onChanged }: Props) {
  const { user } = useAuth();
  const toasts = useToasts();
  const fileRef = useRef<HTMLInputElement>(null);
  const [auditOpen, setAuditOpen] = useState(false);
  const canImport = !!user?.role && IMPORT_ROLES.has(user.role);

  const importMutation = useMutation({
    mutationFn: (file: File) => api.importMaster(master, file),
    onSuccess: (result) => {
      toasts.push("info", "Bulk import complete", `${result.imported} records imported atomically.`);
      onChanged();
    },
    onError: (error) => toasts.push("error", "Bulk import rejected", error instanceof Error ? error.message : "Validation failed."),
  });

  async function download(action: () => Promise<DownloadedFile>, label: string) {
    try { saveDownloadedFile(await action()); toasts.push("info", label, "Your governed download is ready."); }
    catch (error) { toasts.push("error", `${label} failed`, error instanceof Error ? error.message : "Download failed."); }
  }

  function exportAction(format: GridExportFormat) {
    if (exportRows) {
      return Promise.resolve(createCurrentViewExport(
        format,
        exportRows,
        exportFilename ?? `${master}-current-view`,
        gridExportContext({
          title: `${master === "accounts" ? "Accounts" : "Leads"} current view`,
          rows: exportRows,
          groupColumns,
          selectedGroupColumns,
          filterColumns,
          columnFilters,
        }),
      ));
    }
    return api.exportMaster(master, format, { search, filter });
  }

  const viewContext = gridExportContext({
    title: `${master === "accounts" ? "Accounts" : "Leads"} current view`,
    rows: exportRows,
    groupColumns,
    selectedGroupColumns,
    filterColumns,
    columnFilters,
  });

  async function copyViewSummary() {
    if (!viewContext) return;
    try {
      await writeClipboardText(gridViewSummaryText(viewContext));
      toasts.push("info", "View summary copied", "Paste it into a ticket, chat or audit note to describe this exact master-data view.");
    } catch (error) {
      toasts.push("error", "View summary not copied", error instanceof Error ? error.message : "Clipboard is unavailable.");
    }
  }

  return (
    <>
      <div className="data-grid-tools-stack">
        <div className="master-toolbar data-grid-toolbar" role="toolbar" aria-label={`${master} data tools`}>
          <InfoTag
            text="Use these tools to group rows, search columns, check audit history, export records, or upload many rows at once."
            label={`${master} data tools help`}
          />
          {groupColumns && onGroupColumnsChange ? (
            <GroupColumnPicker
              id={`${master}-master-toolbar`}
              columns={groupColumns}
              selected={selectedGroupColumns ?? []}
              onChange={onGroupColumnsChange}
            />
          ) : (
            <button className={`btn btn-sm${grouped ? " active" : ""}`} aria-pressed={grouped} onClick={onToggleGroup}>Group: {grouped ? groupLabel : "Off"}</button>
          )}
          <button className="btn btn-sm" onClick={() => setAuditOpen(true)}>Audit</button>
          <span className="toolbar-divider" aria-hidden />
          <button className="btn btn-sm" onClick={() => void download(() => exportAction("XLSX"), "Export Excel")}>Export Excel</button>
          <button className="btn btn-sm" onClick={() => void download(() => exportAction("DOCX"), "Export Word")}>Export Word</button>
          <button className="btn btn-sm" onClick={() => void download(() => exportAction("PDF"), "Export PDF")}>Export PDF</button>
          <button className="btn btn-sm" disabled={!viewContext} onClick={() => void copyViewSummary()}>Copy view</button>
          {canImport && <>
            <span className="toolbar-divider" aria-hidden />
            <button className="btn btn-sm" onClick={() => void download(() => api.masterTemplate(master), "Import template")}>Download template</button>
            <button className="btn btn-sm btn-primary" disabled={importMutation.isPending} onClick={() => fileRef.current?.click()}>
              {importMutation.isPending ? "Validating..." : "Bulk upload"}
            </button>
            <input ref={fileRef} className="sr-only" type="file" accept=".csv,text/csv" onChange={(event) => {
              const file = event.target.files?.[0]; if (file) importMutation.mutate(file); event.target.value = "";
            }} />
          </>}
          {viewContext && <span className="grid-view-summary" aria-live="polite">{viewSummary(viewContext.rowCount ?? 0, viewContext.filters?.length ?? 0, viewContext.groups?.length ?? 0)}</span>}
        </div>
        {filterColumns && columnFilters && onColumnFiltersChange && (
          <GridColumnFilters
            id={`${master}-master-toolbar`}
            columns={filterColumns}
            filters={columnFilters}
            onChange={onColumnFiltersChange}
          />
        )}
      </div>
      <AuditDrawer
        open={auditOpen}
        entityType={entityType}
        title={`${entityType} audit`}
        emptyLabel="No audited actions for this master yet."
        onClose={() => setAuditOpen(false)}
      />
    </>
  );
}

function viewSummary(rows: number, filters: number, groups: number) {
  return [
    `${rows} rows`,
    filters ? `${filters} filters` : "",
    groups ? `${groups} groups` : "",
  ].filter(Boolean).join(" · ");
}

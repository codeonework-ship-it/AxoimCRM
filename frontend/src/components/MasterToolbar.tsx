import { useRef, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api, type DownloadedFile } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { AuditDrawer } from "./AuditDrawer";
import { useToasts } from "./Toasts";

const MASTER_ADMIN_ROLES = new Set(["SUPER_ADMIN", "TENANT_ADMIN", "DATA_STEWARD"]);
const IMPORT_ROLES = MASTER_ADMIN_ROLES;

export function canManageMasters(role?: string): boolean {
  return !!role && MASTER_ADMIN_ROLES.has(role);
}

function saveFile(file: DownloadedFile) {
  const url = URL.createObjectURL(file.blob);
  const anchor = document.createElement("a");
  anchor.href = url; anchor.download = file.filename; document.body.appendChild(anchor); anchor.click(); anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 500);
}

interface Props {
  master: "accounts" | "leads";
  entityType: "ACCOUNT" | "LEAD";
  search?: string;
  filter?: string;
  grouped: boolean;
  groupLabel: string;
  onToggleGroup: () => void;
  onChanged: () => void;
}

export function MasterToolbar({ master, entityType, search, filter, grouped, groupLabel, onToggleGroup, onChanged }: Props) {
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
    try { saveFile(await action()); toasts.push("info", label, "Your governed download is ready."); }
    catch (error) { toasts.push("error", `${label} failed`, error instanceof Error ? error.message : "Download failed."); }
  }

  return (
    <>
      <div className="master-toolbar data-grid-toolbar" role="toolbar" aria-label={`${master} data tools`}>
        <button className={`btn btn-sm${grouped ? " active" : ""}`} aria-pressed={grouped} onClick={onToggleGroup}>Group: {grouped ? groupLabel : "Off"}</button>
        <button className="btn btn-sm" onClick={() => setAuditOpen(true)}>Audit</button>
        <span className="toolbar-divider" aria-hidden />
        <button className="btn btn-sm" onClick={() => void download(() => api.exportMaster(master, "XLSX", { search, filter }), "Export Excel")}>Export Excel</button>
        <button className="btn btn-sm" onClick={() => void download(() => api.exportMaster(master, "DOCX", { search, filter }), "Export Word")}>Export Word</button>
        <button className="btn btn-sm" onClick={() => void download(() => api.exportMaster(master, "PDF", { search, filter }), "Export PDF")}>Export PDF</button>
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

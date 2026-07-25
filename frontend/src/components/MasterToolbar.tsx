import { useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, type AuditEvent, type DownloadedFile } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { formatDate } from "../lib/format";
import { useToasts } from "./Toasts";
import { CloseIcon } from "./icons";

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
      <div className="master-toolbar" role="toolbar" aria-label={`${master} data tools`}>
        <button className={`btn btn-sm${grouped ? " active" : ""}`} aria-pressed={grouped} onClick={onToggleGroup}>Group: {grouped ? groupLabel : "Off"}</button>
        <button className="btn btn-sm" onClick={() => setAuditOpen(true)}>Audit</button>
        <span className="toolbar-divider" aria-hidden />
        <button className="btn btn-sm" onClick={() => void download(() => api.exportMaster(master, "XLSX", { search, filter }), "Excel export")}>Export Excel</button>
        <button className="btn btn-sm" onClick={() => void download(() => api.exportMaster(master, "DOCX", { search, filter }), "Word export")}>Export Word</button>
        <button className="btn btn-sm" onClick={() => void download(() => api.exportMaster(master, "PDF", { search, filter }), "PDF export")}>Export PDF</button>
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
      <AuditDrawer open={auditOpen} entityType={entityType} onClose={() => setAuditOpen(false)} />
    </>
  );
}

function AuditDrawer({ open, entityType, onClose }: { open: boolean; entityType: string; onClose: () => void }) {
  const auditQ = useQuery({ queryKey: ["audit", entityType], queryFn: () => api.auditEvents(entityType), enabled: open });
  if (!open) return null;
  return <div className="drawer-scrim" role="presentation" onMouseDown={onClose}>
    <aside className="audit-drawer" role="dialog" aria-modal="true" aria-label={`${entityType} audit history`} onMouseDown={(event) => event.stopPropagation()}>
      <header className="drawer-head"><div><span className="eyebrow">Immutable evidence</span><h2>{entityType} audit</h2></div>
        <button className="icon-btn" onClick={onClose} aria-label="Close audit"><CloseIcon /></button></header>
      <div className="audit-list">
        {auditQ.isLoading && <p className="loading-note">Loading audit trail...</p>}
        {auditQ.isError && <p className="empty-note">Audit trail unavailable.</p>}
        {auditQ.data?.map((event: AuditEvent) => <article className="audit-event" key={event.id}>
          <div><strong>{event.action.replace(/_/g, " ")}</strong><time>{formatDate(event.occurredAt)}</time></div>
          <p>{event.summary}</p><small>{event.actorName} · {event.actorRole}</small>
        </article>)}
        {auditQ.data?.length === 0 && <p className="empty-note">No audited actions for this master yet.</p>}
      </div>
    </aside>
  </div>;
}

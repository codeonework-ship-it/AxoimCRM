import { Fragment, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isUnreachable, type DownloadedFile, type WorkspaceRow } from "../api/client";
import { AuditDrawer } from "../components/AuditDrawer";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataViewFrame } from "../components/DataViewFrame";
import { GridLoader } from "../components/Loaders";
import { useToasts } from "../components/Toasts";
import { formatDate, formatMoney } from "../lib/format";

export type WorkspaceModule =
  | "forecast"
  | "contracts"
  | "campaigns"
  | "cases"
  | "migration"
  | "partners"
  | "automation"
  | "analytics"
  | "copilot"
  | "mobile"
  | "integrations"
  | "sandbox"
  | "audit"
  | "bfsi"
  | "commodity";

interface EpicWorkspacePageProps {
  module: WorkspaceModule;
}

const STATUS_OPTIONS: Record<WorkspaceModule, string[]> = {
  forecast: ["DRAFT", "SUBMITTED", "MANAGER_ADJUSTED", "LOCKED"],
  contracts: ["DRAFT", "IN_REVIEW", "ACTIVE", "EXPIRING", "EXPIRED", "TERMINATED"],
  campaigns: ["PLANNED", "ACTIVE", "PAUSED", "COMPLETED", "CANCELLED"],
  cases: ["NEW", "WORKING", "WAITING_ON_CUSTOMER", "ESCALATED", "RESOLVED", "CLOSED"],
  migration: ["UPLOADED", "VALIDATING", "READY_TO_IMPORT", "IMPORTED", "FAILED", "ROLLED_BACK"],
  partners: ["ONBOARDING", "ACTIVE", "SUSPENDED", "TERMINATED"],
  automation: ["DRAFT", "ACTIVE", "PAUSED", "RETIRED"],
  analytics: ["DRAFT", "ACTIVE", "ARCHIVED"],
  copilot: ["READY", "ACCEPTED", "DISMISSED", "EXPIRED"],
  mobile: ["ACTIVE", "LOCKED", "WIPED", "EXPIRED"],
  integrations: ["DRAFT", "ACTIVE", "DEPRECATED", "RETIRED"],
  sandbox: ["REQUESTED", "PROVISIONING", "ACTIVE", "REFRESHING", "FAILED", "ARCHIVED"],
  audit: ["DRAFT", "GENERATING", "READY", "EXPORTED", "FAILED"],
  bfsi: ["NOT_STARTED", "IN_PROGRESS", "CLEARED", "ENHANCED_DUE_DILIGENCE", "REJECTED"],
  commodity: ["RECEIVED", "PRICING", "OFFERED", "WON", "LOST", "EXPIRED"],
};

const EYEBROWS: Record<WorkspaceModule, string> = {
  forecast: "Revenue intelligence",
  contracts: "Quote-to-cash control",
  campaigns: "Marketing alignment",
  cases: "Service operations",
  migration: "Onboarding command",
  partners: "Channel command",
  automation: "Process command",
  analytics: "Metric command",
  copilot: "Grounded intelligence",
  mobile: "Field readiness",
  integrations: "Integration control",
  sandbox: "Release control",
  audit: "Governance proof",
  bfsi: "Financial services pack",
  commodity: "Trading origination",
};

export function EpicWorkspacePage({ module }: EpicWorkspacePageProps) {
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("");
  const [grouped, setGrouped] = useState(false);
  const [auditOpen, setAuditOpen] = useState(false);
  const toasts = useToasts();
  const queryClient = useQueryClient();
  const workspaceQ = useQuery({
    queryKey: ["workspace", module, page, search, status],
    queryFn: () => api.workspace(module, { page, search, status }),
    retry: 1,
  });
  const actionMutation = useMutation({
    mutationFn: (row: WorkspaceRow) => runWorkspaceAction(module, row),
    onSuccess: (result) => {
      toasts.push("info", "Workspace action complete", result.message);
      void queryClient.invalidateQueries({ queryKey: ["workspace", module] });
      void queryClient.invalidateQueries({ queryKey: ["audit"] });
    },
    onError: (error) => toasts.push("error", "Workspace action failed", error instanceof Error ? error.message : "Action failed."),
  });

  if (isUnreachable(workspaceQ.error)) {
    return <ApiUnreachable onRetry={() => void workspaceQ.refetch()} retrying={workspaceQ.isFetching} />;
  }

  const workspace = workspaceQ.data;
  const rows = workspace?.rows.items ?? [];
  const visibleRows = grouped
    ? [...rows].sort((a, b) => a.status.localeCompare(b.status) || a.title.localeCompare(b.title))
    : rows;
  const total = workspace?.rows.total ?? 0;
  const totalPages = workspace?.rows.totalPages ?? 0;

  async function download(format: "XLSX" | "DOCX" | "PDF", label: string) {
    try {
      saveFile(await api.exportWorkspace(module, format, { page, search, status }));
      toasts.push("info", `${label} ready`, "The export used the current page, search and status filters.");
    } catch (error) {
      toasts.push("error", `${label} failed`, error instanceof Error ? error.message : "Download failed.");
    }
  }

  return <>
    <div className="page-head epic-head">
      <div>
        <span className="eyebrow">{EYEBROWS[module]}</span>
        <h1>{workspace?.title ?? titleFromModule(module)}</h1>
        <p>{workspace?.description ?? "Tenant-scoped operational workspace."}</p>
      </div>
      {workspaceQ.isSuccess && <span className="count">{total} records</span>}
    </div>

    <div className="kpi-row epic-kpis">
      {(workspace?.summary ?? []).map((metric) => <div className="kpi" key={metric.label}>
        <span className="label">{metric.label}</span>
        <div className={`kpi-value ${metric.tone === "crit" ? "crit" : ""}`}>
          {metric.unit === "money" ? formatMoney(Number(metric.value)) : metric.value}
        </div>
        <div className="kpi-sub">{metric.unit === "money" ? "Tenant currency rollup" : "Tenant-scoped count"}</div>
      </div>)}
      {workspaceQ.isLoading && [0, 1, 2].map((i) => <div className="kpi" key={i}>
        <span className="label">Loading</span>
        <div className="kpi-value">...</div>
        <div className="kpi-sub">Reading workspace</div>
      </div>)}
    </div>

    <section className="list-controls" aria-label={`${titleFromModule(module)} search and filters`}>
      <label>
        <span>Search</span>
        <input value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} placeholder="Code, title, owner, account or context" />
      </label>
      <label>
        <span>Status</span>
        <select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}>
          <option value="">All statuses</option>
          {STATUS_OPTIONS[module].map((value) => <option key={value} value={value}>{value}</option>)}
        </select>
      </label>
      <button className="btn btn-sm" onClick={() => { setSearch(""); setStatus(""); setPage(0); }} disabled={!search && !status}>Reset</button>
    </section>

    <DataViewFrame
      title={`${workspace?.title ?? titleFromModule(module)} register`}
      actions={<div className="master-toolbar data-grid-toolbar" role="toolbar" aria-label={`${titleFromModule(module)} data tools`}>
        <button className={`btn btn-sm${grouped ? " active" : ""}`} aria-pressed={grouped} onClick={() => setGrouped((value) => !value)}>
          Group: {grouped ? "Status" : "Off"}
        </button>
        <button className="btn btn-sm" onClick={() => setAuditOpen(true)}>Audit</button>
        <span className="toolbar-divider" aria-hidden />
        <button className="btn btn-sm" onClick={() => void download("XLSX", "Export Excel")}>Export Excel</button>
        <button className="btn btn-sm" onClick={() => void download("DOCX", "Export Word")}>Export Word</button>
        <button className="btn btn-sm" onClick={() => void download("PDF", "Export PDF")}>Export PDF</button>
        <span className="cpq-note">100 rows/page - tenant/RLS governed</span>
      </div>}
    >
      {workspaceQ.isLoading && <GridLoader label="Reading operational workspace" rows={6} columns={6} />}
      {workspaceQ.isError && <p className="empty-note">Workspace failed to load{workspaceQ.error instanceof Error ? `: ${workspaceQ.error.message}` : "."}</p>}
      {workspaceQ.isSuccess && <WorkspaceTable rows={visibleRows} grouped={grouped} module={module} busyId={actionMutation.variables?.id} onAction={(row) => actionMutation.mutate(row)} />}
      {workspaceQ.isSuccess && <footer className="page-controls" aria-label="Workspace pagination">
        <span>Showing {visibleRows.length} of {total} records - 100 rows per page</span>
        <div>
          <button className="btn btn-sm" disabled={page === 0 || workspaceQ.isFetching} onClick={() => setPage((value) => Math.max(0, value - 1))}>Previous</button>
          <strong>Page {totalPages === 0 ? 0 : page + 1} of {totalPages}</strong>
          <button className="btn btn-sm" disabled={page + 1 >= totalPages || workspaceQ.isFetching} onClick={() => setPage((value) => value + 1)}>Next</button>
        </div>
      </footer>}
    </DataViewFrame>
    <AuditDrawer
      open={auditOpen}
      entityType={auditEntityFor(module)}
      title={`${titleFromModule(module)} audit`}
      emptyLabel="No audited actions for this workspace yet."
      onClose={() => setAuditOpen(false)}
    />
  </>;
}

function WorkspaceTable({ rows, grouped, module, busyId, onAction }: { rows: WorkspaceRow[]; grouped: boolean; module: WorkspaceModule; busyId?: string; onAction: (row: WorkspaceRow) => void }) {
  let previousStatus = "";
  return <div className="table-wrap"><table className="data-table cpq-table epic-table">
    <thead><tr><th>Code</th><th>Record</th><th>Owner</th><th>Amount</th><th>Target</th><th>Status</th><th>Signals</th><th>Action</th></tr></thead>
    <tbody>
      {rows.map((row) => {
        const showGroup = grouped && row.status !== previousStatus;
        previousStatus = row.status;
        const action = actionFor(module, row);
        return <Fragment key={row.id}>
          {showGroup && <tr className="group-row"><th colSpan={8}>{row.status}</th></tr>}
          <tr>
            <td className="mono">{row.code}</td>
            <td>{row.title}<small>{row.subtitle}</small></td>
            <td>{row.ownerName ?? "-"}</td>
            <td>{row.amount == null ? "-" : formatMoney(row.amount)}</td>
            <td>{formatDate(row.targetDate)}</td>
            <td><span className={`chip ${statusClass(row.status)}`}>{row.status}</span><small>Updated {formatDate(row.updatedAt)}</small></td>
            <td>{Object.entries(row.metrics ?? {}).slice(0, 3).map(([key, value]) => <span className="chip cpq-mini" key={key}>{humanize(key)}: {String(value)}</span>)}</td>
            <td className="table-action">{action
              ? <button className="btn btn-sm" disabled={busyId === row.id} onClick={() => onAction(row)}>{busyId === row.id ? "Working..." : action}</button>
              : <span className="empty-note">No action</span>}</td>
          </tr>
        </Fragment>;
      })}
      {rows.length === 0 && <tr><td colSpan={8} className="empty-note">No records match the current query.</td></tr>}
    </tbody>
  </table></div>;
}

function actionFor(module: WorkspaceModule, row: WorkspaceRow): string | null {
  if (module === "contracts" && ["DRAFT", "IN_REVIEW", "EXPIRING"].includes(row.status)) return "Activate";
  if (module === "campaigns" && !["COMPLETED", "CANCELLED"].includes(row.status)) return "Complete";
  if (module === "forecast" && ["DRAFT", "MANAGER_ADJUSTED"].includes(row.status)) return "Submit";
  if (module === "cases" && !["RESOLVED", "CLOSED"].includes(row.status)) return "Resolve";
  if (module === "partners" && ["ONBOARDING", "SUSPENDED"].includes(row.status)) return "Activate";
  if (module === "automation" && row.status !== "RETIRED") return "Simulate";
  if (module === "analytics" && row.status === "ACTIVE") return "Refresh";
  if (module === "copilot" && row.status === "READY") return "Accept";
  if (module === "migration" && !["IMPORTED", "ROLLED_BACK"].includes(row.status)) return "Validate";
  if (module === "mobile" && row.status === "ACTIVE") return "Ack sync";
  if (module === "integrations" && !["DEPRECATED", "RETIRED"].includes(row.status)) return "Verify";
  if (module === "sandbox" && !["ARCHIVED", "PROVISIONING"].includes(row.status)) return "Refresh";
  if (module === "audit" && row.status === "READY") return "Export pack";
  if (module === "bfsi" && !["CLEARED", "REJECTED"].includes(row.status)) return "Clear KYC";
  if (module === "commodity" && ["RECEIVED", "PRICING"].includes(row.status)) return "Offer";
  return null;
}

async function runWorkspaceAction(module: WorkspaceModule, row: WorkspaceRow) {
  if (module === "contracts") {
    const signedDocumentRef = window.prompt(`Signed document reference for ${row.code}`, `signed://${row.code.toLowerCase()}`);
    if (!signedDocumentRef) throw new Error("Contract activation requires a signed document reference.");
    return api.activateContract(row.id, signedDocumentRef);
  }
  if (module === "campaigns") {
    const outcome = window.prompt(`Complete campaign ${row.code}. Outcome`, "Campaign completed after operator review.");
    if (!outcome) throw new Error("Campaign completion outcome is required.");
    return api.completeCampaign(row.id, outcome);
  }
  if (module === "forecast") {
    const note = window.prompt(`Submit forecast ${row.code}? Optional manager note`, "");
    return api.submitForecast(row.id, note ?? undefined);
  }
  if (module === "cases") {
    const outcome = window.prompt(`Resolve case ${row.code}. Outcome`, "Resolved after operator review.");
    if (!outcome) throw new Error("Case resolution outcome is required.");
    return api.resolveCase(row.id, outcome);
  }
  if (module === "automation") {
    const raw = window.prompt(`Simulation sample size for ${row.code}`, "25");
    const sampleSize = Number(raw || 25);
    if (!Number.isFinite(sampleSize)) throw new Error("Simulation sample size must be numeric.");
    return api.simulateAutomation(row.id, sampleSize);
  }
  if (module === "analytics") {
    const note = window.prompt(`Refresh dashboard ${row.code}? Optional note`, "");
    return api.refreshDashboard(row.id, note ?? undefined);
  }
  if (module === "partners") return api.activatePartner(row.id);
  if (module === "copilot") {
    const note = window.prompt(`Accept copilot recommendation ${row.code}? Optional note`, "");
    return api.acceptCopilotRecommendation(row.id, note ?? undefined);
  }
  if (module === "migration") return api.validateMigration(row.id);
  if (module === "mobile") return api.acknowledgeMobileSync(row.id);
  if (module === "integrations") return api.verifyIntegrationContract(row.id);
  if (module === "sandbox") {
    const reason = window.prompt(`Refresh sandbox ${row.code}. Reason`, "Operator requested environment refresh.");
    if (!reason) throw new Error("Sandbox refresh requires a reason.");
    return api.refreshSandbox(row.id, reason);
  }
  if (module === "audit") {
    const destination = window.prompt(`Export evidence pack ${row.code}. Destination`, "SECURE_DOWNLOAD");
    return api.exportAuditPack(row.id, destination || "SECURE_DOWNLOAD");
  }
  if (module === "bfsi") {
    const note = window.prompt(`Clear BFSI onboarding ${row.code}. Compliance note`, "All screening results are clear or waived.");
    if (!note) throw new Error("BFSI clearance requires a compliance note.");
    return api.clearBfsiOnboarding(row.id, note);
  }
  if (module === "commodity") return api.offerCommodityEnquiry(row.id);
  throw new Error("No governed action is available for this workspace.");
}

function saveFile(file: DownloadedFile) {
  const url = URL.createObjectURL(file.blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = file.filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 500);
}

function titleFromModule(module: WorkspaceModule): string {
  if (module === "copilot") return "AI Copilot";
  if (module === "bfsi") return "BFSI";
  if (module === "sandbox") return "Sandbox & Release";
  if (module === "audit") return "Audit & Compliance";
  return module === "forecast" ? "Forecast" : module === "migration" ? "Migration" : module.charAt(0).toUpperCase() + module.slice(1);
}

function auditEntityFor(module: WorkspaceModule): string {
  const map: Record<WorkspaceModule, string> = {
    forecast: "FORECAST_SUBMISSION",
    contracts: "CONTRACT",
    campaigns: "CAMPAIGN",
    cases: "CASE",
    migration: "IMPORT_BATCH",
    partners: "PARTNER_ACCOUNT",
    automation: "AUTOMATION_RULE",
    analytics: "ANALYTICS_DASHBOARD",
    copilot: "COPILOT_RECOMMENDATION",
    mobile: "DEVICE_SESSION",
    integrations: "INTEGRATION_CONTRACT",
    sandbox: "SANDBOX",
    audit: "AUDIT_EVIDENCE_PACK",
    bfsi: "BFSI_ONBOARDING",
    commodity: "COMMODITY_ENQUIRY",
  };
  return map[module];
}

function statusClass(status: string): string {
  const normalized = status.toLowerCase().replace(/_/g, "-");
  if (["active", "submitted", "booked", "imported", "met", "ready-to-import", "ready", "approved", "converted", "synced", "exported", "cleared", "won", "succeeded", "deployed"].includes(normalized)) return "chip-active";
  if (["draft", "planned", "uploaded", "validating", "working", "new", "in-review", "pending-renewal", "onboarding", "queued", "simulated", "requested", "provisioning", "refreshing", "generating", "in-progress", "pricing", "offered", "received"].includes(normalized)) return "chip-draft";
  if (["escalated", "failed", "missed", "terminated", "cancelled", "suspended", "rejected", "expired", "locked", "wiped", "conflict", "disabled", "retired", "deprecated", "lost", "enhanced-due-diligence"].includes(normalized)) return "chip-cancelled";
  return `chip-${normalized}`;
}

function humanize(value: string): string {
  return value.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[_-]+/g, " ").toLowerCase();
}

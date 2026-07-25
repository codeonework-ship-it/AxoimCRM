import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, isUnreachable, type WorkspaceRow } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataViewFrame } from "../components/DataViewFrame";
import { GridLoader } from "../components/Loaders";
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
  | "mobile";

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
};

export function EpicWorkspacePage({ module }: EpicWorkspacePageProps) {
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("");
  const workspaceQ = useQuery({
    queryKey: ["workspace", module, page, search, status],
    queryFn: () => api.workspace(module, { page, search, status }),
    retry: 1,
  });

  if (isUnreachable(workspaceQ.error)) return <ApiUnreachable onRetry={() => void workspaceQ.refetch()} retrying={workspaceQ.isFetching} />;

  const workspace = workspaceQ.data;
  const rows = workspace?.rows.items ?? [];
  const total = workspace?.rows.total ?? 0;
  const totalPages = workspace?.rows.totalPages ?? 0;

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
        <div className={`kpi-value ${metric.tone === "crit" ? "crit" : ""}`}>{metric.unit === "money" ? formatMoney(Number(metric.value)) : metric.value}</div>
        <div className="kpi-sub">{metric.unit === "money" ? "Tenant currency rollup" : "Tenant-scoped count"}</div>
      </div>)}
      {workspaceQ.isLoading && [0, 1, 2].map((i) => <div className="kpi" key={i}><span className="label">Loading</span><div className="kpi-value">…</div><div className="kpi-sub">Reading workspace</div></div>)}
    </div>

    <section className="list-controls" aria-label={`${titleFromModule(module)} search and filters`}>
      <label><span>Search</span><input value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} placeholder="Code, title, owner, account or context" /></label>
      <label><span>Status</span><select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}><option value="">All statuses</option>{STATUS_OPTIONS[module].map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
      <button className="btn btn-sm" onClick={() => { setSearch(""); setStatus(""); setPage(0); }} disabled={!search && !status}>Reset</button>
    </section>

    <DataViewFrame title={`${workspace?.title ?? titleFromModule(module)} register`} actions={<span className="cpq-note">100 rows/page · tenant/RLS governed</span>}>
      {workspaceQ.isLoading && <GridLoader label="Reading operational workspace" rows={6} columns={6} />}
      {workspaceQ.isError && <p className="empty-note">Workspace failed to load{workspaceQ.error instanceof Error ? `: ${workspaceQ.error.message}` : "."}</p>}
      {workspaceQ.isSuccess && <WorkspaceTable rows={rows} />}
      {workspaceQ.isSuccess && <footer className="page-controls" aria-label="Workspace pagination">
        <span>Showing {rows.length} of {total} records - 100 rows per page</span>
        <div>
          <button className="btn btn-sm" disabled={page === 0 || workspaceQ.isFetching} onClick={() => setPage((value) => Math.max(0, value - 1))}>Previous</button>
          <strong>Page {totalPages === 0 ? 0 : page + 1} of {totalPages}</strong>
          <button className="btn btn-sm" disabled={page + 1 >= totalPages || workspaceQ.isFetching} onClick={() => setPage((value) => value + 1)}>Next</button>
        </div>
      </footer>}
    </DataViewFrame>
  </>;
}

function WorkspaceTable({ rows }: { rows: WorkspaceRow[] }) {
  return <div className="table-wrap"><table className="data-table cpq-table epic-table">
    <thead><tr><th>Code</th><th>Record</th><th>Owner</th><th>Amount</th><th>Target</th><th>Status</th><th>Signals</th></tr></thead>
    <tbody>
      {rows.map((row) => <tr key={row.id}>
        <td className="mono">{row.code}</td>
        <td>{row.title}<small>{row.subtitle}</small></td>
        <td>{row.ownerName ?? "-"}</td>
        <td>{row.amount == null ? "—" : formatMoney(row.amount)}</td>
        <td>{formatDate(row.targetDate)}</td>
        <td><span className={`chip ${statusClass(row.status)}`}>{row.status}</span><small>Updated {formatDate(row.updatedAt)}</small></td>
        <td>{Object.entries(row.metrics ?? {}).slice(0, 3).map(([key, value]) => <span className="chip cpq-mini" key={key}>{humanize(key)}: {String(value)}</span>)}</td>
      </tr>)}
      {rows.length === 0 && <tr><td colSpan={7} className="empty-note">No records match the current query.</td></tr>}
    </tbody>
  </table></div>;
}

function titleFromModule(module: WorkspaceModule): string {
  if (module === "copilot") return "AI Copilot";
  return module === "forecast" ? "Forecast" : module === "migration" ? "Migration" : module.charAt(0).toUpperCase() + module.slice(1);
}

function statusClass(status: string): string {
  const normalized = status.toLowerCase().replace(/_/g, "-");
  if (["active", "submitted", "booked", "imported", "met", "ready-to-import", "ready", "approved", "converted", "synced"].includes(normalized)) return "chip-active";
  if (["draft", "planned", "uploaded", "validating", "working", "new", "in-review", "pending-renewal", "onboarding", "queued", "simulated"].includes(normalized)) return "chip-draft";
  if (["escalated", "failed", "missed", "terminated", "cancelled", "suspended", "rejected", "expired", "locked", "wiped", "conflict", "disabled"].includes(normalized)) return "chip-cancelled";
  return `chip-${normalized}`;
}

function humanize(value: string): string {
  return value.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[_-]+/g, " ").toLowerCase();
}

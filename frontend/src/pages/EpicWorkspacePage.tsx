import { Fragment, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isUnreachable, type ForecastScenario, type WorkflowGateStatus, type WorkspaceRow } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataGridToolbar } from "../components/DataGridToolbar";
import { DataTable, type Column } from "../components/DataTable";
import { DataViewFrame } from "../components/DataViewFrame";
import { GridFilterRow, type GridFilterColumn } from "../components/GridFilterRow";
import { InfoTag } from "../components/InfoTag";
import { GridLoader } from "../components/Loaders";
import { useToasts } from "../components/Toasts";
import { WorkflowGateDrawer } from "../components/WorkflowGateDrawer";
import { formatDate, formatMoney } from "../lib/format";
import { filterRowsByColumns, groupLabelFor, selectedGroupColumns, sortByGroups, type GroupColumn } from "../lib/gridGrouping";
import { usePersistedGridState } from "../lib/usePersistedGridState";
import { useAppDialog, type DialogApi } from "../components/AppDialog";
import { ReleaseControlPlane } from "../components/ReleaseControlPlane";
import { BfsiOperationsPanel, CommodityOperationsPanel, MobileOfflinePanel } from "../components/VerticalClosurePanels";

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
const WORKSPACE_GROUP_COLUMNS: GroupColumn<WorkspaceRow>[] = [
  { key: "code", label: "Code", value: (row) => row.code },
  { key: "record", label: "Record", value: (row) => row.title },
  { key: "status", label: "Status", value: (row) => row.status },
  { key: "owner", label: "Owner", value: (row) => row.ownerName },
  { key: "amount", label: "Amount", value: (row) => row.amount },
  { key: "target", label: "Target date", value: (row) => row.targetDate ? formatDate(row.targetDate) : null },
];

/*
 * One entry per rendered column of the workspace table, in render order. The
 * group list above is ordered differently and omits Signals and Action, which
 * is why this is separate: a filter row built from that list would put the
 * Status box under Amount.
 */
const WORKSPACE_FILTER_COLUMNS: GridFilterColumn[] = [
  { key: "code", label: "Code" },
  { key: "record", label: "Record" },
  { key: "owner", label: "Owner" },
  { key: "amount", label: "Amount" },
  { key: "target", label: "Target" },
  { key: "status", label: "Status" },
  { key: "signals", label: "Signals", kind: "none" },
];

const WORKFLOW_GATE_COLUMNS: Column<WorkflowGateStatus>[] = [
  { key: "objectType", header: "Object", value: (row) => row.objectType, filter: "enum", groupable: true, cellClass: "mono" },
  { key: "recordId", header: "Record", value: (row) => row.recordId, filter: "text", groupable: false, cellClass: "mono" },
  {
    key: "gateStatus",
    header: "Status",
    value: (row) => row.gateStatus,
    filter: "enum",
    groupable: true,
    render: (row) => <span className={`chip ${statusClass(row.gateStatus)}`}>{row.gateStatus}</span>,
  },
  { key: "missingCount", header: "Missing", value: (row) => row.missingCount, filter: "enum", groupable: true, cellClass: "num" },
  { key: "processCode", header: "Process", value: (row) => row.processCode ?? "No process", filter: "enum", groupable: true },
  { key: "currentState", header: "State", value: (row) => row.currentState ?? "No state", filter: "enum", groupable: true },
  { key: "nextStep", header: "Next step", value: (row) => row.nextStep, filter: "text", groupable: false, cellClass: "workflow-next-step" },
  { key: "evaluatedAt", header: "Checked", value: (row) => new Date(row.evaluatedAt).toLocaleString(), filter: "text", groupable: false },
];

export function EpicWorkspacePage({ module }: EpicWorkspacePageProps) {
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("");
  const [gateResult, setGateResult] = useState<WorkflowGateStatus | null>(null);
  const [scenarioResult, setScenarioResult] = useState<ForecastScenario | null>(null);
  const [groupColumns, setGroupColumns, columnFilters, setColumnFilters] = usePersistedGridState(`workspace-${module}`);
  const toasts = useToasts();
  const dialog = useAppDialog();
  const queryClient = useQueryClient();
  const workspaceQ = useQuery({
    queryKey: ["workspace", module, page, search, status],
    queryFn: () => api.workspace(module, { page, search, status }),
    retry: 1,
  });
  const actionMutation = useMutation({
    mutationFn: (row: WorkspaceRow) => runWorkspaceAction(module, row, dialog),
    onSuccess: (result) => {
      toasts.push("info", "Workspace action complete", result.message);
      void queryClient.invalidateQueries({ queryKey: ["workspace", module] });
      void queryClient.invalidateQueries({ queryKey: ["audit"] });
      void queryClient.invalidateQueries({ queryKey: ["workflow-gate-console"] });
    },
    onError: (error) => toasts.push("error", "Workspace action failed", error instanceof Error ? error.message : "Action failed."),
  });
  const gateMutation = useMutation({
    mutationFn: (row: WorkspaceRow) => {
      const transition = workflowTransitionFor(module, row);
      if (!transition) throw new Error("This record has no pending governed transition.");
      return api.workflowTransitionGate(transition.objectType, row.id, transition.targetState);
    },
    onSuccess: (result) => {
      setGateResult(result);
      void queryClient.invalidateQueries({ queryKey: ["workflow-gate-console"] });
      toasts.push(result.gateStatus === "READY" ? "info" : "warn",
        result.gateStatus === "READY" ? "Workflow gate ready" : "Workflow gate needs attention",
        result.nextStep);
    },
    onError: (error) => toasts.push("error", "Workflow gate check failed",
      error instanceof Error ? error.message : "Gate check failed."),
  });
  const renewalMutation = useMutation({
    mutationFn: ({ row, rationale }: { row: WorkspaceRow; rationale: string }) => api.prepareContractRenewal(row.id, rationale),
    onSuccess: (result) => {
      toasts.push("info", result.alreadyGenerated ? "Renewal already prepared" : "Renewal draft prepared", result.message);
      void queryClient.invalidateQueries({ queryKey: ["workspace", "contracts"] });
      void queryClient.invalidateQueries({ queryKey: ["audit"] });
    },
    onError: (error) => toasts.push("error", "Renewal preparation failed", error instanceof Error ? error.message : "Renewal could not be prepared."),
  });
  const scenarioMutation = useMutation({
    mutationFn: ({ row, name, adjustment, confidence, riskReduction }: { row: WorkspaceRow; name: string; adjustment: number; confidence?: number; riskReduction: number }) =>
      api.createForecastScenario(row.id, { name, amountAdjustmentPct: adjustment, confidencePct: confidence, riskReduction }),
    onSuccess: (scenario) => {
      setScenarioResult(scenario);
      toasts.push("info", "Forecast scenario saved", scenario.note);
      void queryClient.invalidateQueries({ queryKey: ["audit"] });
    },
    onError: (error) => toasts.push("error", "Scenario rejected", error instanceof Error ? error.message : "Scenario could not be saved."),
  });
  const controlMutation = useMutation({
    mutationFn: ({ row, input }: { row: WorkspaceRow; input?: string }) => runClosureControl(module, row, dialog, input),
    onSuccess: (result) => {
      toasts.push("info", result.title, result.message);
      void queryClient.invalidateQueries({ queryKey: ["workspace", module] });
      void queryClient.invalidateQueries({ queryKey: ["audit"] });
    },
    onError: (error) => toasts.push("error", "Governed control failed",
      error instanceof Error ? error.message : "The control could not be completed."),
  });

  if (isUnreachable(workspaceQ.error)) {
    return <ApiUnreachable onRetry={() => void workspaceQ.refetch()} retrying={workspaceQ.isFetching} />;
  }

  const workspace = workspaceQ.data;
  const rawRows = workspace?.rows.items ?? [];
  const rows = filterRowsByColumns(rawRows, WORKSPACE_GROUP_COLUMNS, columnFilters);
  const activeGroupColumns = selectedGroupColumns(WORKSPACE_GROUP_COLUMNS, groupColumns);
  const visibleRows = sortByGroups(rows, activeGroupColumns, (row) => row.title);
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

    {module === "automation" && <WorkflowGateConsole />}
    {module === "sandbox" && <ReleaseControlPlane />}
    {module === "mobile" && <MobileOfflinePanel devices={rawRows} />}
    {module === "bfsi" && <BfsiOperationsPanel />}
    {module === "commodity" && <CommodityOperationsPanel />}

    <section className="list-controls" aria-label={`${titleFromModule(module)} search and filters`}>
      <label>
        <span>Search <InfoTag text="Type a code, title, owner, account, or context word to narrow this workspace." label="Workspace search help" /></span>
        <input value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} placeholder="Code, title, owner, account or context" />
      </label>
      <label>
        <span>Status <InfoTag text="Choose one status to focus the register on records at that point in the workflow." label="Workspace status help" /></span>
        <select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}>
          <option value="">All statuses</option>
          {STATUS_OPTIONS[module].map((value) => <option key={value} value={value}>{value}</option>)}
        </select>
      </label>
      <button className="btn btn-sm" onClick={() => { setSearch(""); setStatus(""); setPage(0); }} disabled={!search && !status}>Reset</button>
    </section>

    <DataViewFrame
      title={`${workspace?.title ?? titleFromModule(module)} register`}
      actions={<DataGridToolbar
        gridName={`${workspace?.title ?? titleFromModule(module)} register`}
        grouped={activeGroupColumns.length > 0}
        groupLabel="Status"
        onToggleGroup={() => setGroupColumns((value) => value.length > 0 ? [] : ["status"])}
        groupColumns={WORKSPACE_GROUP_COLUMNS.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={groupColumns}
        onGroupColumnsChange={setGroupColumns}
        columnFilters={columnFilters}
        onColumnFiltersChange={setColumnFilters}
        auditEntityType={auditEntityFor(module)}
        exportFilename={`${module}-workspace`}
        exportRows={visibleRows.map((row) => ({
          code: row.code,
          record: row.title,
          status: row.status,
          owner: row.ownerName ?? "",
          amount: row.amount ?? "",
          targetDate: row.targetDate ? formatDate(row.targetDate) : "",
        }))}
        onExport={(format) => api.exportWorkspace(module, format, { page, search, status })}
        note="Current filtered page - tenant/RLS governed"
      />}
    >
      {workspaceQ.isLoading && <GridLoader label="Reading operational workspace" rows={6} columns={6} />}
      {workspaceQ.isError && <p className="empty-note">Workspace failed to load{workspaceQ.error instanceof Error ? `: ${workspaceQ.error.message}` : "."}</p>}
      {workspaceQ.isSuccess && <WorkspaceTable
        filters={columnFilters}
        onFiltersChange={setColumnFilters}
        rows={visibleRows}
        groupColumns={activeGroupColumns}
        module={module}
        busyId={actionMutation.variables?.id}
        gateBusyId={gateMutation.variables?.id}
        revenueBusyId={renewalMutation.variables?.row.id ?? scenarioMutation.variables?.row.id}
        controlBusyId={controlMutation.variables?.row.id}
        onAction={(row) => actionMutation.mutate(row)}
        onCheckGates={(row) => gateMutation.mutate(row)}
        onRenewal={async (row) => {
          const rationale = await dialog.prompt({ title: "Prepare Renewal Draft", message: `Explain why renewal is expected for ${row.code}.`, label: "Renewal Rationale", defaultValue: "Renewal window reached; commercial review required.", required: true, multiline: true, confirmLabel: "Prepare Draft" });
          if (rationale?.trim()) renewalMutation.mutate({ row, rationale: rationale.trim() });
        }}
        onScenario={async (row) => {
          const name = await dialog.prompt({ title: "Create Forecast Scenario", message: `Name the scenario for ${row.code}.`, label: "Scenario Name", defaultValue: "Upside review", required: true, confirmLabel: "Next" });
          if (!name?.trim()) return;
          const adjustmentRaw = await dialog.prompt({ title: "Amount Adjustment", message: "Enter an adjustment from -100 to 500 percent.", label: "Adjustment Percentage", defaultValue: "10", required: true, confirmLabel: "Next" });
          if (adjustmentRaw === null) return;
          const confidenceRaw = await dialog.prompt({ title: "Scenario Confidence", message: "Enter a confidence percentage from 0 to 100.", label: "Confidence Percentage", defaultValue: String(row.metrics.confidencePct ?? 70), required: true, confirmLabel: "Next" });
          if (confidenceRaw === null) return;
          const riskReductionRaw = await dialog.prompt({ title: "Risk Reduction", message: "Enter how many risk signals this scenario assumes are resolved.", label: "Resolved Risk Signals", defaultValue: "1", required: true, confirmLabel: "Save Scenario" });
          if (riskReductionRaw === null) return;
          const adjustment = Number(adjustmentRaw);
          const confidence = Number(confidenceRaw);
          const riskReduction = Number(riskReductionRaw);
          if (![adjustment, confidence, riskReduction].every(Number.isFinite)) {
            toasts.push("error", "Scenario values are invalid", "Use numbers for adjustment, confidence and risk reduction.");
            return;
          }
          scenarioMutation.mutate({ row, name: name.trim(), adjustment, confidence, riskReduction });
        }}
        onControl={async (row) => {
          let input: string | undefined;
          if (module === "partners") {
            input = (await dialog.prompt({ title: "Register Partner Deal", message: `Enter the open opportunity UUID to register for ${row.code}.`, label: "Opportunity UUID", required: true, confirmLabel: "Register Deal" }))?.trim();
            if (!input) return;
          }
          controlMutation.mutate({ row, input });
        }}
      />}
      {workspaceQ.isSuccess && <footer className="page-controls" aria-label="Workspace pagination">
        <span>Showing {visibleRows.length} of {total} records - 100 rows per page</span>
        <div>
          <button className="btn btn-sm" disabled={page === 0 || workspaceQ.isFetching} onClick={() => setPage((value) => Math.max(0, value - 1))}>Previous</button>
          <strong>Page {totalPages === 0 ? 0 : page + 1} of {totalPages}</strong>
          <button className="btn btn-sm" disabled={page + 1 >= totalPages || workspaceQ.isFetching} onClick={() => setPage((value) => value + 1)}>Next</button>
        </div>
      </footer>}
    </DataViewFrame>
    <WorkflowGateDrawer result={gateResult} onClose={() => setGateResult(null)} />
    <ForecastScenarioDrawer scenario={scenarioResult} onClose={() => setScenarioResult(null)} />
  </>;
}

function WorkflowGateConsole() {
  const [status, setStatus] = useState("");
  const [selected, setSelected] = useState<WorkflowGateStatus | null>(null);
  const gatesQ = useQuery({
    queryKey: ["workflow-gate-console", status],
    queryFn: () => api.workflowGates({ status: status || undefined, limit: 25 }),
    retry: 1,
  });
  const rows = gatesQ.data ?? [];
  const blocked = rows.filter((row) => row.gateStatus === "BLOCKED" || row.gateStatus === "UNKNOWN_STATE").length;

  return (
    <>
      <section className="panel workflow-gate-console" aria-label="Workflow gate console">
        <header className="workflow-gate-console-head">
          <div>
            <span className="eyebrow">Workflow gate console</span>
            <h2>
              Process prerequisites
              <InfoTag
                text="This shows the latest gate checks already evaluated by Pipeline or automation APIs. Use Review to see what is missing and the next step in plain language."
                label="Workflow gate console help"
              />
            </h2>
            <p>Track missing process steps across records without opening each record one by one.</p>
          </div>
          <div className="workflow-gate-console-controls">
            <label>
              <span>Status</span>
              <select value={status} onChange={(event) => setStatus(event.target.value)}>
                <option value="">All gate states</option>
                <option value="BLOCKED">Blocked</option>
                <option value="UNKNOWN_STATE">Unknown state</option>
                <option value="READY">Ready</option>
                <option value="COMPLETED">Completed</option>
                <option value="NO_PROCESS">No process</option>
              </select>
            </label>
            <button className="btn btn-sm" disabled={gatesQ.isFetching} onClick={() => void gatesQ.refetch()}>
              {gatesQ.isFetching ? "Refreshing..." : "Refresh"}
            </button>
          </div>
        </header>
        <div className="workflow-gate-console-summary">
          <span className={blocked > 0 ? "chip chip-cancelled" : "chip chip-active"}>{blocked} blocked</span>
          <span className="chip">{rows.length} latest checks</span>
        </div>
        {gatesQ.isLoading && <GridLoader label="Reading workflow gate status" rows={3} columns={5} />}
        {gatesQ.isError && <p className="empty-note">Workflow gate console failed to load{gatesQ.error instanceof Error ? `: ${gatesQ.error.message}` : "."}</p>}
        {gatesQ.isSuccess && (
          <DataTable
            name="Workflow gate findings"
            columns={WORKFLOW_GATE_COLUMNS}
            rows={rows}
            rowKey={(row) => `${row.objectType}:${row.recordId}`}
            initialGroupBy="gateStatus"
            empty="No workflow gate checks have been recorded yet. Use Check gates on Pipeline records, or evaluate a record through the automation API."
            actions={(row) => <button className="btn btn-sm" onClick={() => setSelected(row)}>Review</button>}
            actionsHeader="Review"
            note="Use the column filters to focus on one object, state, process, or missing-step count. The same filtered and grouped view is used for Excel, Word, PDF, Copy view, Audit, and Full size."
          />
        )}
      </section>
      <WorkflowGateDrawer result={selected} onClose={() => setSelected(null)} />
    </>
  );
}

function WorkspaceTable({ rows, groupColumns, filters, onFiltersChange, module, busyId, gateBusyId, revenueBusyId, controlBusyId, onAction, onCheckGates, onRenewal, onScenario, onControl }: {
  rows: WorkspaceRow[];
  groupColumns: GroupColumn<WorkspaceRow>[];
  filters: Record<string, string>;
  onFiltersChange: (next: Record<string, string>) => void;
  module: WorkspaceModule;
  busyId?: string;
  gateBusyId?: string;
  revenueBusyId?: string;
  controlBusyId?: string;
  onAction: (row: WorkspaceRow) => void;
  onCheckGates: (row: WorkspaceRow) => void;
  onRenewal: (row: WorkspaceRow) => void;
  onScenario: (row: WorkspaceRow) => void;
  onControl: (row: WorkspaceRow) => void;
}) {
  let previousGroup = "";
  return <div className="table-wrap"><table className="data-table cpq-table epic-table">
    <thead><tr><th>Code</th><th>Record</th><th>Owner</th><th>Amount</th><th>Target</th><th>Status</th><th>Signals</th><th>Action</th></tr>
    <GridFilterRow columns={WORKSPACE_FILTER_COLUMNS} filters={filters} onChange={onFiltersChange} trailing={1} /></thead>
    <tbody>
      {rows.map((row) => {
        const group = groupColumns.length > 0 ? groupLabelFor(row, groupColumns) : "";
        const showGroup = groupColumns.length > 0 && group !== previousGroup;
        previousGroup = group;
        const action = actionFor(module, row);
        const gate = workflowTransitionFor(module, row);
        return <Fragment key={row.id}>
          {showGroup && <tr className="group-row"><th colSpan={8}>{group}</th></tr>}
          <tr>
            <td className="mono">{row.code}</td>
            <td>{row.title}<small>{row.subtitle}</small></td>
            <td>{row.ownerName ?? "-"}</td>
            <td>{row.amount == null ? "-" : formatMoney(row.amount)}</td>
            <td>{formatDate(row.targetDate)}</td>
            <td><span className={`chip ${statusClass(row.status)}`}>{row.status}</span><small>Updated {formatDate(row.updatedAt)}</small></td>
            <td>{Object.entries(row.metrics ?? {}).slice(0, 3).map(([key, value]) => <span className="chip cpq-mini" key={key}>{humanize(key)}: {String(value)}</span>)}</td>
            <td className="table-action"><div className="workspace-row-actions">
              {gate && <button className="btn btn-sm" disabled={gateBusyId === row.id || busyId === row.id}
                onClick={() => onCheckGates(row)}>{gateBusyId === row.id ? "Checking..." : "Check gates"}</button>}
              {action
                ? <button className="btn btn-primary btn-sm" disabled={busyId === row.id || gateBusyId === row.id}
                    onClick={() => onAction(row)}>{busyId === row.id ? "Working..." : action}</button>
                : !gate && <span className="empty-note">No action</span>}
              {module === "contracts" && ["ACTIVE", "EXPIRING", "EXPIRED"].includes(row.status) && <button className="btn btn-sm" disabled={revenueBusyId === row.id} onClick={() => onRenewal(row)}>{revenueBusyId === row.id ? "Preparing..." : "Prepare renewal"}</button>}
              {module === "forecast" && <button className="btn btn-sm" disabled={revenueBusyId === row.id} onClick={() => onScenario(row)}>{revenueBusyId === row.id ? "Modelling..." : "Scenario"}</button>}
              {["campaigns", "cases", "partners", "automation"].includes(module) && <button className="btn btn-sm"
                disabled={controlBusyId === row.id} onClick={() => onControl(row)}>
                {controlBusyId === row.id ? "Working..." : controlLabel(module)}
              </button>}
            </div></td>
          </tr>
        </Fragment>;
      })}
      {rows.length === 0 && <tr><td colSpan={8} className="empty-note">No records match the current query.</td></tr>}
    </tbody>
  </table></div>;
}

function controlLabel(module: WorkspaceModule): string {
  if (module === "campaigns") return "Capture performance";
  if (module === "cases") return "Check SLA";
  if (module === "partners") return "Register deal";
  if (module === "automation") return "Restore version";
  return "Govern";
}

async function runClosureControl(module: WorkspaceModule, row: WorkspaceRow, dialog: DialogApi, input?: string): Promise<{ title: string; message: string }> {
  if (module === "campaigns") {
    const result = await api.captureCampaignPerformance(row.id);
    const roi = result.roiPercent == null ? "not available because budget is zero" : `${result.roiPercent.toFixed(2)}%`;
    return { title: "Campaign performance captured", message: `${result.responses}/${result.members} responded; governed ROI is ${roi}.` };
  }
  if (module === "cases") {
    const result = await api.sweepCaseSla(row.id);
    return { title: result.missedMilestones > 0 ? "Case escalated" : "SLA is current", message: result.message };
  }
  if (module === "partners") {
    if (!input) throw new Error("Choose an opportunity UUID.");
    const result = await api.registerPartnerDeal(row.id, input);
    return { title: result.conflictCount > 0 ? "Deal held for conflict review" : "Deal protected",
      message: `${result.registrationNumber}: ${result.conflictStatus.toLowerCase()} (${result.conflictCount} open conflicts).` };
  }
  if (module === "automation") {
    const versions = await api.automationRuleVersions(row.id);
    const previous = versions.filter((version) => !version.active).sort((a, b) => b.versionNo - a.versionNo)[0];
    if (!previous) throw new Error("This rule has no prior version to restore.");
    const confirmed = await dialog.confirm({ title: "Restore Automation Version", message: `Restore ${row.code} version ${previous.versionNo}? A new version will preserve the full audit history.`, confirmLabel: "Restore Version", tone: "danger" });
    if (!confirmed) throw new Error("Restore cancelled.");
    await api.restoreAutomationRuleVersion(row.id, previous.versionNo);
    return { title: "Automation version restored", message: `Version ${previous.versionNo} was copied forward as the new active version.` };
  }
  throw new Error("No additional control is available for this module.");
}

function workflowTransitionFor(module: WorkspaceModule, row: WorkspaceRow): { objectType: string; targetState: string } | null {
  if (module === "contracts" && ["DRAFT", "IN_REVIEW", "EXPIRING"].includes(row.status)) {
    return { objectType: "CONTRACT", targetState: "ACTIVE" };
  }
  if (module === "forecast" && ["DRAFT", "MANAGER_ADJUSTED"].includes(row.status)) {
    return { objectType: "FORECAST_SUBMISSION", targetState: "SUBMITTED" };
  }
  if (module === "campaigns" && !["COMPLETED", "CANCELLED"].includes(row.status)) {
    return { objectType: "CAMPAIGN", targetState: "COMPLETED" };
  }
  if (module === "cases" && !["RESOLVED", "CLOSED"].includes(row.status)) {
    return { objectType: "CASE", targetState: "RESOLVED" };
  }
  if (module === "partners" && ["ONBOARDING", "SUSPENDED"].includes(row.status)) {
    return { objectType: "PARTNER_ACCOUNT", targetState: "ACTIVE" };
  }
  if (module === "analytics" && row.status === "ACTIVE") {
    return { objectType: "ANALYTICS_DASHBOARD", targetState: "ACTIVE" };
  }
  if (module === "copilot" && row.status === "READY") {
    return { objectType: "COPILOT_RECOMMENDATION", targetState: "ACCEPTED" };
  }
  if (module === "integrations" && !["DEPRECATED", "RETIRED"].includes(row.status)) {
    return { objectType: "INTEGRATION_CONTRACT", targetState: "ACTIVE" };
  }
  if (module === "migration" && !["IMPORTED", "ROLLED_BACK"].includes(row.status)) {
    return { objectType: "IMPORT_BATCH", targetState: "VALIDATING" };
  }
  if (module === "sandbox" && !["ARCHIVED", "PROVISIONING"].includes(row.status)) {
    return { objectType: "SANDBOX", targetState: "ACTIVE" };
  }
  if (module === "audit" && row.status === "READY") {
    return { objectType: "AUDIT_EVIDENCE_PACK", targetState: "EXPORTED" };
  }
  if (module === "mobile" && row.status === "ACTIVE") {
    return { objectType: "DEVICE_SESSION", targetState: "ACTIVE" };
  }
  if (module === "bfsi" && !["CLEARED", "REJECTED"].includes(row.status)) {
    return { objectType: "BFSI_ONBOARDING", targetState: "CLEARED" };
  }
  if (module === "commodity" && ["RECEIVED", "PRICING"].includes(row.status)) {
    return { objectType: "COMMODITY_ENQUIRY", targetState: "OFFERED" };
  }
  return null;
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

async function runWorkspaceAction(module: WorkspaceModule, row: WorkspaceRow, dialog: DialogApi) {
  if (module === "contracts") {
    const signedDocumentRef = await dialog.prompt({ title: "Activate Contract", message: `Enter the signed document reference for ${row.code}.`, label: "Signed Document Reference", defaultValue: `signed://${row.code.toLowerCase()}`, required: true, confirmLabel: "Activate Contract" });
    if (!signedDocumentRef) throw new Error("Contract activation requires a signed document reference.");
    return api.activateContract(row.id, signedDocumentRef);
  }
  if (module === "campaigns") {
    const outcome = await dialog.prompt({ title: "Complete Campaign", message: `Record the outcome for ${row.code}.`, label: "Campaign Outcome", defaultValue: "Campaign completed after operator review.", required: true, multiline: true, confirmLabel: "Complete Campaign" });
    if (!outcome) throw new Error("Campaign completion outcome is required.");
    return api.completeCampaign(row.id, outcome);
  }
  if (module === "forecast") {
    const note = await dialog.prompt({ title: "Submit Forecast", message: `Submit forecast ${row.code}. Add an optional manager note.`, label: "Manager Note", multiline: true, confirmLabel: "Submit Forecast" });
    return api.submitForecast(row.id, note ?? undefined);
  }
  if (module === "cases") {
    const outcome = await dialog.prompt({ title: "Resolve Case", message: `Record the resolution outcome for ${row.code}.`, label: "Resolution Outcome", defaultValue: "Resolved after operator review.", required: true, multiline: true, confirmLabel: "Resolve Case" });
    if (!outcome) throw new Error("Case resolution outcome is required.");
    return api.resolveCase(row.id, outcome);
  }
  if (module === "automation") {
    const raw = await dialog.prompt({ title: "Simulate Automation", message: `Choose the simulation sample size for ${row.code}.`, label: "Sample Size", defaultValue: "25", required: true, confirmLabel: "Run Simulation" });
    const sampleSize = Number(raw || 25);
    if (!Number.isFinite(sampleSize)) throw new Error("Simulation sample size must be numeric.");
    return api.simulateAutomation(row.id, sampleSize);
  }
  if (module === "analytics") {
    const note = await dialog.prompt({ title: "Refresh Dashboard", message: `Refresh ${row.code}. Add an optional note.`, label: "Refresh Note", multiline: true, confirmLabel: "Refresh Dashboard" });
    return api.refreshDashboard(row.id, note ?? undefined);
  }
  if (module === "partners") return api.activatePartner(row.id);
  if (module === "copilot") {
    const note = await dialog.prompt({ title: "Accept Recommendation", message: `Accept copilot recommendation ${row.code}. Add an optional note.`, label: "Decision Note", multiline: true, confirmLabel: "Accept Recommendation" });
    return api.acceptCopilotRecommendation(row.id, note ?? undefined);
  }
  if (module === "migration") return api.validateMigration(row.id);
  if (module === "mobile") return api.acknowledgeMobileSync(row.id);
  if (module === "integrations") return api.verifyIntegrationContract(row.id);
  if (module === "sandbox") {
    const reason = await dialog.prompt({ title: "Refresh Sandbox", message: `Explain why ${row.code} needs an environment refresh.`, label: "Refresh Reason", defaultValue: "Operator requested environment refresh.", required: true, multiline: true, confirmLabel: "Refresh Sandbox" });
    if (!reason) throw new Error("Sandbox refresh requires a reason.");
    return api.refreshSandbox(row.id, reason);
  }
  if (module === "audit") {
    const destination = await dialog.prompt({ title: "Export Evidence Pack", message: `Choose the governed destination for ${row.code}.`, label: "Destination", defaultValue: "SECURE_DOWNLOAD", required: true, confirmLabel: "Export Pack" });
    return api.exportAuditPack(row.id, destination || "SECURE_DOWNLOAD");
  }
  if (module === "bfsi") {
    const note = await dialog.prompt({ title: "Clear BFSI Onboarding", message: `Record the compliance decision for ${row.code}.`, label: "Compliance Note", defaultValue: "All screening results are clear or waived.", required: true, multiline: true, confirmLabel: "Clear Onboarding" });
    if (!note) throw new Error("BFSI clearance requires a compliance note.");
    return api.clearBfsiOnboarding(row.id, note);
  }
  if (module === "commodity") return api.offerCommodityEnquiry(row.id);
  throw new Error("No governed action is available for this workspace.");
}

function ForecastScenarioDrawer({ scenario, onClose }: { scenario: ForecastScenario | null; onClose: () => void }) {
  if (!scenario) return null;
  return <div className="drawer-scrim" role="presentation" onMouseDown={onClose}>
    <aside className="audit-drawer forecast-scenario-drawer" role="dialog" aria-modal="true" aria-label="Forecast scenario comparison" onMouseDown={(event) => event.stopPropagation()}>
      <header className="drawer-head">
        <div><span className="eyebrow">Saved scenario</span><h2>{scenario.name}</h2></div>
        <button className="icon-btn" onClick={onClose} aria-label="Close forecast scenario">×</button>
      </header>
      <div className="scenario-totals">
        <div><span>Baseline</span><strong>{formatMoney(scenario.baselineAmount)}</strong></div>
        <div><span>Scenario</span><strong>{formatMoney(scenario.scenarioAmount)}</strong></div>
        <div><span>Risk-adjusted</span><strong>{formatMoney(scenario.weightedAmount)}</strong></div>
      </div>
      <p className="drawer-note">{scenario.note}</p>
      <div className="audit-list">
        {scenario.explanation.map((factor) => <article className="audit-event" key={factor.code}>
          <strong>{factor.label}</strong>
          <p>{factor.baseline} → {factor.scenario}</p>
          <small>{factor.effect}</small>
        </article>)}
      </div>
    </aside>
  </div>;
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

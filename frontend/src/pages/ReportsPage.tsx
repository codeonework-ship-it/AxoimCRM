import { useEffect, useMemo, useState, type FormEvent } from "react";
import { createPortal } from "react-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isUnreachable, type ReportDefinition, type ReportPreview } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { saveDownloadedFile } from "../components/DataGridToolbar";
import { DataTable, type Column } from "../components/DataTable";
import { ReportStudio } from "../components/ReportStudio";
import { useToasts } from "../components/Toasts";

type ReportWorkspace = "REPORTS_STUDIO" | "CUSTOM_REPORTS";
type ReportFormat = "PDF" | "XLSX" | "DOCX";
type ReportRow = Awaited<ReturnType<typeof api.reports>>[number];

interface ScheduleDraft {
  recipient: string;
  format: ReportFormat;
  frequency: "DAILY" | "WEEKLY" | "MONTHLY";
}

const EMPTY_SCHEDULE: ScheduleDraft = { recipient: "", format: "PDF", frequency: "WEEKLY" };
const COLLECTION_ORDER = ["EXECUTIVE", "SALES", "GROWTH", "CUSTOMER", "COMMERCIAL", "GOVERNANCE", "GENERAL"];

const REPORT_GRID_COLUMNS: Column<ReportRow>[] = [
  {
    key: "report",
    header: "Report Name",
    value: (report) => report.label,
    filter: "text",
    groupable: false,
    sortable: true,
    render: (report) => <div className="jasper-grid-report-name"><strong>{report.label}</strong><code>{report.code}</code></div>,
  },
  {
    key: "collection",
    header: "Collection",
    value: (report) => categoryLabel(report.category),
    filter: "enum",
    groupable: true,
    sortable: true,
    render: (report) => <span className="report-signal signal-neutral">{categoryLabel(report.category)}</span>,
  },
  {
    key: "businessQuestion",
    header: "Business Question",
    value: (report) => report.businessQuestion,
    filter: "text",
    groupable: false,
    sortable: true,
    cellClass: "jasper-grid-question",
  },
  {
    key: "audience",
    header: "Recommended For",
    value: (report) => (report.audience ?? []).map(roleLabel).join(", "),
    filter: "text",
    groupable: true,
    sortable: true,
  },
  {
    key: "formats",
    header: "Download Formats",
    value: (report) => report.allowedFormats.map(formatLabel).join(", "),
    filter: "enum",
    groupable: true,
    sortable: false,
    cellClass: "mono",
  },
  {
    key: "status",
    header: "Status",
    value: (report) => report.active ? "Ready" : "Inactive",
    filter: "enum",
    groupable: true,
    sortable: true,
    render: (report) => <span className={`report-signal ${report.active ? "signal-positive" : "signal-risk"}`}>
      {report.active ? "Jasper Ready" : "Inactive"}
    </span>,
  },
];

export function ReportsPage() {
  const toasts = useToasts();
  const queryClient = useQueryClient();
  const [workspace, setWorkspace] = useState<ReportWorkspace>("REPORTS_STUDIO");
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [previewRevision, setPreviewRevision] = useState(0);
  const [preview, setPreview] = useState<ReportPreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [fullPreview, setFullPreview] = useState(false);
  const [scheduleReport, setScheduleReport] = useState<ReportRow | null>(null);
  const [scheduleDraft, setScheduleDraft] = useState<ScheduleDraft>(EMPTY_SCHEDULE);

  const reportsQ = useQuery({ queryKey: ["reports"], queryFn: api.reports, retry: 1 });
  const subscriptionsQ = useQuery({ queryKey: ["reports", "subscriptions"], queryFn: api.reportSubscriptions, retry: 1 });

  const reports = useMemo(() => [...(reportsQ.data ?? [])].sort(compareReports), [reportsQ.data]);
  const collections = useMemo(
    () => [...new Set(reports.map((report) => report.category ?? "GENERAL"))].sort(compareCollections),
    [reports],
  );
  const selectedReport = reports.find((report) => report.code === selectedCode) ?? reports[0] ?? null;

  useEffect(() => {
    if (!reports.length) return;
    if (!selectedCode || !reports.some((report) => report.code === selectedCode)) setSelectedCode(reports[0].code);
  }, [reports, selectedCode]);

  useEffect(() => {
    if (workspace !== "REPORTS_STUDIO" || !selectedReport) return;
    let disposed = false;
    setPreviewLoading(true);
    setPreviewError(null);
    setPreview(null);
    void api.reportPreview(selectedReport.code)
      .then((value) => {
        if (!disposed) setPreview(value);
      })
      .catch((error: unknown) => {
        if (!disposed) setPreviewError(error instanceof Error ? error.message : "The report preview could not be generated.");
      })
      .finally(() => {
        if (!disposed) setPreviewLoading(false);
      });
    return () => {
      disposed = true;
    };
  }, [previewRevision, selectedReport?.code, workspace]);

  const createSubscription = useMutation({
    mutationFn: ({ report, draft }: { report: ReportRow; draft: ScheduleDraft }) => api.createReportSubscription({
      reportCode: report.code,
      name: `${report.label} ${titleCase(draft.frequency)}`,
      format: draft.format,
      frequency: draft.frequency,
      recipients: [draft.recipient.trim()],
    }),
    onSuccess: (subscription) => {
      toasts.push("info", "Report Subscription Created", `${subscription.name} will generate ${subscription.frequency.toLowerCase()}.`);
      setScheduleReport(null);
      setScheduleDraft(EMPTY_SCHEDULE);
      void queryClient.invalidateQueries({ queryKey: ["reports", "subscriptions"] });
    },
    onError: (error) => toasts.push("error", "Subscription Failed", error instanceof Error ? error.message : "Could not schedule report."),
  });
  const runDue = useMutation({
    mutationFn: api.runDueReportSubscriptions,
    onSuccess: (runs) => {
      const generated = runs.filter((run) => run.status === "GENERATED").length;
      toasts.push("info", "Scheduled Report Sweep Complete", `${generated} reports generated; ${runs.length - generated} failed.`);
      void queryClient.invalidateQueries({ queryKey: ["reports", "subscriptions"] });
    },
    onError: (error) => toasts.push("error", "Schedule Sweep Failed", error instanceof Error ? error.message : "Could not run schedules."),
  });

  if (isUnreachable(reportsQ.error)) return <ApiUnreachable onRetry={() => void reportsQ.refetch()} retrying={reportsQ.isFetching} />;

  async function download(report: ReportRow, format: ReportFormat) {
    try {
      saveDownloadedFile(await api.downloadReport(report.code, format));
      toasts.push("info", "Report Download Ready", `${formatLabel(format)} report generated through Jasper Reports.`);
    } catch (error) {
      toasts.push("error", "Report Download Failed", error instanceof Error ? error.message : "Download failed.");
    }
  }

  function openSchedule(report: ReportRow) {
    setScheduleReport(report);
    setScheduleDraft(EMPTY_SCHEDULE);
  }

  function submitSchedule(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!scheduleReport || !scheduleDraft.recipient.trim()) return;
    createSubscription.mutate({ report: scheduleReport, draft: scheduleDraft });
  }

  return <>
    <div className="page-head reports-page-head">
      <div>
        <span className="eyebrow">Jasper Reporting</span>
        <h1>CRM Reports</h1>
        <p>View governed operational reports or build custom analytics from one controlled reporting workspace.</p>
      </div>
      {reportsQ.isSuccess && <div className="report-portfolio-count" aria-label={`${reports.length} reports across ${collections.length} collections`}>
        <span><strong>{reports.length}</strong><small>Reports</small></span>
        <span><strong>{collections.length}</strong><small>Collections</small></span>
        <span><strong>3</strong><small>Formats</small></span>
      </div>}
    </div>

    <nav className="report-workspace-tabs" aria-label="Report Workspaces">
      <button className={workspace === "REPORTS_STUDIO" ? "active" : ""} aria-current={workspace === "REPORTS_STUDIO" ? "page" : undefined}
        onClick={() => setWorkspace("REPORTS_STUDIO")}>
        <strong>Reports Studio</strong>
        <span>Choose, view, download and schedule governed CRM reports.</span>
      </button>
      <button className={workspace === "CUSTOM_REPORTS" ? "active" : ""} aria-current={workspace === "CUSTOM_REPORTS" ? "page" : undefined}
        onClick={() => setWorkspace("CUSTOM_REPORTS")}>
        <strong>Custom Reports</strong>
        <span>Build reports, dashboards, formulas, pivots and delivery policies.</span>
      </button>
    </nav>

    {workspace === "REPORTS_STUDIO" ? <>
      <section className="jasper-report-grid panel" aria-label="Jasper Report Grid">
        <header className="jasper-report-grid-head">
          <div><span className="eyebrow">Jasper Report Grid</span><h2>Governed Report Catalogue</h2>
            <p>Filter, sort or group the complete portfolio, then open any report in the document viewer.</p></div>
          <div className="jasper-engine-status"><span aria-hidden="true" /><strong>Jasper Engine Ready</strong><small>PDF · Excel · Word</small></div>
        </header>
        {reportsQ.isLoading && <p className="loading-note">Loading Jasper Reports...</p>}
        {reportsQ.isError && <p className="empty-note">Reports Could Not Be Loaded.</p>}
        {reportsQ.isSuccess && <DataTable name="Jasper Reports" columns={REPORT_GRID_COLUMNS} rows={reports}
          rowKey={(report) => report.id} empty="No Jasper reports match the current filters."
          actionsHeader="Preview"
          actions={(report) => <button className={`btn btn-sm${selectedReport?.code === report.code ? " primary" : ""}`}
            aria-pressed={selectedReport?.code === report.code} onClick={() => setSelectedCode(report.code)}>
            {selectedReport?.code === report.code ? "Viewing" : "View Report"}
          </button>}
          note="Every preview and download uses the same tenant-scoped report query and is recorded through the reporting audit trail." />}
      </section>

      {!fullPreview && <ReportDocumentWorkspace report={selectedReport} preview={preview} previewLoading={previewLoading}
        previewError={previewError} full={false} onDownload={download} onSchedule={openSchedule}
        onRefresh={() => setPreviewRevision((value) => value + 1)} onToggleFull={() => setFullPreview(true)} />}

      {fullPreview && createPortal(<ReportDocumentWorkspace report={selectedReport} preview={preview} previewLoading={previewLoading}
        previewError={previewError} full onDownload={download} onSchedule={openSchedule}
        onRefresh={() => setPreviewRevision((value) => value + 1)} onToggleFull={() => setFullPreview(false)} />, document.body)}

      <section className="panel report-subscriptions" aria-label="Report Subscriptions">
        <header className="report-subscription-head">
          <div><span className="eyebrow">Governed Delivery</span><h2>Report Subscriptions</h2>
            <p>Generate approved report attachments on a recurring schedule without changing recipient access.</p></div>
          <button className="btn btn-sm" disabled={runDue.isPending} onClick={() => runDue.mutate()}>
            {runDue.isPending ? "Generating Reports..." : "Run Due Schedules"}
          </button>
        </header>
        {subscriptionsQ.isLoading && <p className="loading-note">Loading Schedules...</p>}
        {subscriptionsQ.isError && <p className="empty-note">Schedules Could Not Be Loaded.</p>}
        {subscriptionsQ.isSuccess && <div className="table-wrap"><table className="data-table"><thead><tr>
          <th>Name</th><th>Report</th><th>Format</th><th>Frequency</th><th>Recipients</th><th>Next Run</th><th>Last Run</th>
        </tr></thead><tbody>{subscriptionsQ.data.map((subscription) => <tr key={subscription.id}>
          <td>{subscription.name}</td><td className="mono">{subscription.reportCode}</td><td>{formatLabel(subscription.format)}</td>
          <td>{titleCase(subscription.frequency)}</td><td>{subscription.recipients.join(", ")}</td>
          <td>{new Date(subscription.nextRunAt).toLocaleString()}</td>
          <td>{subscription.lastRunAt ? new Date(subscription.lastRunAt).toLocaleString() : "Not Run"}</td>
        </tr>)}{subscriptionsQ.data.length === 0 && <tr><td colSpan={7} className="empty-note">No Report Subscriptions Yet.</td></tr>}
        </tbody></table></div>}
      </section>
    </> : <ReportStudio />}

    {scheduleReport && <div className="modal-scrim" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) setScheduleReport(null);
    }}>
      <form className="modal-card report-schedule-dialog" role="dialog" aria-modal="true" aria-labelledby="schedule-report-title" onSubmit={submitSchedule}>
        <header className="modal-head">
          <div><span className="eyebrow">Governed Delivery</span><h2 id="schedule-report-title">Schedule Report</h2><p>{scheduleReport.label}</p></div>
          <button type="button" className="icon-btn" aria-label="Close Schedule Report" onClick={() => setScheduleReport(null)}>×</button>
        </header>
        <div className="form-grid">
          <label className="span-2"><span>Recipient Email</span><input type="email" required autoFocus value={scheduleDraft.recipient}
            onChange={(event) => setScheduleDraft((value) => ({ ...value, recipient: event.target.value }))} placeholder="name@company.com" /></label>
          <label><span>Report Format</span><select value={scheduleDraft.format}
            onChange={(event) => setScheduleDraft((value) => ({ ...value, format: event.target.value as ReportFormat }))}>
            <option value="PDF">PDF</option><option value="XLSX">Excel</option><option value="DOCX">Word</option>
          </select></label>
          <label><span>Delivery Frequency</span><select value={scheduleDraft.frequency}
            onChange={(event) => setScheduleDraft((value) => ({ ...value, frequency: event.target.value as ScheduleDraft["frequency"] }))}>
            <option value="DAILY">Daily</option><option value="WEEKLY">Weekly</option><option value="MONTHLY">Monthly</option>
          </select></label>
        </div>
        <p className="form-note">The attachment is generated and audited by Axiom. External email delivery remains pending until an approved mail adapter is configured.</p>
        <footer className="modal-actions"><button type="button" className="btn" onClick={() => setScheduleReport(null)}>Cancel</button>
          <button className="btn primary" disabled={createSubscription.isPending}>{createSubscription.isPending ? "Creating Schedule..." : "Create Schedule"}</button></footer>
      </form>
    </div>}
  </>;
}

function ReportDocumentWorkspace({
  report,
  preview,
  previewLoading,
  previewError,
  full,
  onDownload,
  onSchedule,
  onRefresh,
  onToggleFull,
}: {
  report: ReportRow | null;
  preview: ReportPreview | null;
  previewLoading: boolean;
  previewError: string | null;
  full: boolean;
  onDownload: (report: ReportRow, format: ReportFormat) => Promise<void>;
  onSchedule: (report: ReportRow) => void;
  onRefresh: () => void;
  onToggleFull: () => void;
}) {
  return <section className={`report-document-workspace${full ? " report-document-full" : ""}`} aria-label="Report Document Viewer">
    {report ? <>
      <header className="report-document-head">
        <div>
          <div className="report-card-meta"><span className="eyebrow">{categoryLabel(report.category)}</span><code>{report.code}</code></div>
          <h2>{report.label}</h2>
          <p>{report.description}</p>
        </div>
        <div className="report-document-actions" aria-label="Report Download Options">
          <button className="btn btn-sm primary" onClick={() => void onDownload(report, "PDF")}>Download PDF</button>
          <button className="btn btn-sm" onClick={() => void onDownload(report, "XLSX")}>Download Excel</button>
          <button className="btn btn-sm" onClick={() => void onDownload(report, "DOCX")}>Download Word</button>
          <button className="btn btn-sm" onClick={() => onSchedule(report)}>Schedule Report</button>
        </div>
      </header>
      <div className="report-decision-strip">
        <div><span>Decision Supported</span><strong>{report.businessQuestion}</strong></div>
        <div><span>Recommended For</span><p>{(report.audience ?? []).map(roleLabel).join(" · ")}</p></div>
        <div><span>Available Formats</span><p>{report.allowedFormats.map(formatLabel).join(" · ")}</p></div>
      </div>
      <div className="report-preview-toolbar">
        <div><span className="eyebrow">Full Report Preview</span><small>Same Governed Data As Jasper Downloads</small></div>
        <div>
          <button className="btn btn-sm" disabled={previewLoading} onClick={onRefresh}>
            {previewLoading ? "Generating Preview..." : "Refresh Preview"}
          </button>
          <button className="btn btn-sm" onClick={onToggleFull}>{full ? "Restore View" : "Full View"}</button>
        </div>
      </div>
      <div className="report-preview-frame">
        {previewLoading && <div className="report-preview-state"><span className="spinner" /><strong>Generating Complete Report Preview...</strong><p>The same governed report query used by Jasper is being prepared.</p></div>}
        {previewError && <div className="report-preview-state is-error"><strong>Preview Could Not Be Generated</strong><p>{previewError}</p><button className="btn btn-sm" onClick={onRefresh}>Retry Preview</button></div>}
        {preview && !previewLoading && <div className="report-rendered-document" aria-label={`${report.label} Complete Report Preview`}>
          <header>
            <div><span className="eyebrow">{categoryLabel(preview.category)} Report</span><h3>{preview.label}</h3><p>{preview.description}</p></div>
            <dl><div><dt>Company</dt><dd>{preview.tenantName}</dd></div><div><dt>Generated</dt><dd>{new Date(preview.generatedAt).toLocaleString()}</dd></div></dl>
          </header>
          <div className="report-rendered-question"><span>Business Question</span><strong>{preview.businessQuestion}</strong></div>
          <div className="table-wrap"><table className="data-table report-rendered-table">
            <thead><tr><th>{preview.columns.dimension}</th><th>{preview.columns.value}</th><th>{preview.columns.detail}</th><th>{preview.columns.signal}</th></tr></thead>
            <tbody>{preview.rows.map((row, index) => <tr key={`${row.metric}-${index}`}>
              <td><strong>{row.metric}</strong></td><td className="mono num">{row.value}</td><td>{row.detail}</td><td><span className={`report-signal signal-${signalClass(row.signal)}`}>{titleCase(row.signal)}</span></td>
            </tr>)}{preview.rows.length === 0 && <tr><td colSpan={4} className="empty-note">No Matching Report Data.</td></tr>}</tbody>
          </table></div>
          <footer><span>Generated By Axiom Jasper Reporting</span><span>{preview.rows.length} Report Rows</span></footer>
        </div>}
      </div>
    </> : <div className="report-preview-state"><strong>Choose A Report</strong><p>Select a report from the library to view its complete document.</p></div>}
  </section>;
}

function compareReports(left: ReportRow, right: ReportRow): number {
  const category = compareCollections(left.category ?? "GENERAL", right.category ?? "GENERAL");
  return category || (left.sortOrder ?? 999) - (right.sortOrder ?? 999) || left.label.localeCompare(right.label);
}

function compareCollections(left: string, right: string): number {
  const leftIndex = COLLECTION_ORDER.indexOf(left);
  const rightIndex = COLLECTION_ORDER.indexOf(right);
  return (leftIndex < 0 ? 999 : leftIndex) - (rightIndex < 0 ? 999 : rightIndex) || left.localeCompare(right);
}

function categoryLabel(value: ReportDefinition["category"] | string | null | undefined): string {
  return titleCase(value || "GENERAL");
}

function roleLabel(value: string | null | undefined): string {
  return titleCase(value || "ANY_ROLE");
}

function formatLabel(value: string): string {
  if (value === "XLSX") return "Excel";
  if (value === "DOCX") return "Word";
  return value.toUpperCase();
}

function titleCase(value: string): string {
  const acronyms = new Set(["AI", "API", "ARR", "CEO", "CPQ", "CRM", "CRO", "CSM", "DOCX", "MKT", "PDF", "RBAC", "REVOPS", "SDR", "SLA", "XLSX"]);
  return value.replace(/_/g, " ").toLowerCase().replace(/\b[\p{L}\p{N}]+\b/gu, (word) => {
    const upper = word.toUpperCase();
    return acronyms.has(upper) ? upper : word.charAt(0).toUpperCase() + word.slice(1);
  });
}

function signalClass(value: string): string {
  const normalized = value.toLowerCase();
  if (/risk|overdue|missing|stale|critical|fail|late/.test(normalized)) return "risk";
  if (/active|healthy|won|complete|converted|forecast/.test(normalized)) return "positive";
  return "neutral";
}

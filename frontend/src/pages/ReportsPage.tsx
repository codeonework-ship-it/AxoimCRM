import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { createPortal } from "react-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { GlobalWorkerOptions, getDocument, type PDFDocumentProxy, type RenderTask } from "pdfjs-dist";
import pdfWorkerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { api, isUnreachable, type ReportDefinition, type ReportGridParams, type ReportPreview } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { saveDownloadedFile } from "../components/DataGridToolbar";
import { GridFilterRow } from "../components/GridFilterRow";
import { ReportStudio } from "../components/ReportStudio";
import { MenuIcon } from "../components/icons";
import { useToasts } from "../components/Toasts";
import { useI18n } from "../i18n/I18nProvider";

type ReportWorkspace = "REPORTS_STUDIO" | "CUSTOM_REPORTS";
type ReportFormat = "PDF" | "XLSX" | "DOCX";
type ReportViewerTab = "GRID" | "DOCUMENT";
type ReportRow = Awaited<ReturnType<typeof api.reports>>[number];

interface ScheduleDraft {
  recipient: string;
  format: ReportFormat;
  frequency: "DAILY" | "WEEKLY" | "MONTHLY";
}

type ReportGridFilters = Pick<ReportGridParams, "search" | "metric" | "value" | "detail" | "signal">;

const EMPTY_SCHEDULE: ScheduleDraft = { recipient: "", format: "PDF", frequency: "WEEKLY" };
const EMPTY_REPORT_FILTERS: ReportGridFilters = { search: "", metric: "", value: "", detail: "", signal: "" };
const REPORT_PAGE_SIZE = 100;
const COLLECTION_ORDER = ["EXECUTIVE", "SALES", "GROWTH", "CUSTOMER", "COMMERCIAL", "GOVERNANCE", "GENERAL"];

GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

export function ReportsPage() {
  const { t, tp, formatNumber } = useI18n();
  const toasts = useToasts();
  const queryClient = useQueryClient();
  const [workspace, setWorkspace] = useState<ReportWorkspace>("REPORTS_STUDIO");
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [collection, setCollection] = useState("ALL");
  /* The library starts expanded: a first-time visitor should see what is on
     offer, not a column of two-letter marks they have no key for. */
  const [libraryOpen, setLibraryOpen] = useState(true);
  const [previewRevision, setPreviewRevision] = useState(0);
  const [preview, setPreview] = useState<ReportPreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [documentPreviewUrl, setDocumentPreviewUrl] = useState<string | null>(null);
  const [documentPreviewLoading, setDocumentPreviewLoading] = useState(false);
  const [documentPreviewError, setDocumentPreviewError] = useState<string | null>(null);
  const [fullPreview, setFullPreview] = useState(false);
  const [viewerTab, setViewerTab] = useState<ReportViewerTab>("GRID");
  const [reportPage, setReportPage] = useState(0);
  const [reportFilters, setReportFilters] = useState<ReportGridFilters>(EMPTY_REPORT_FILTERS);
  const [scheduleReport, setScheduleReport] = useState<ReportRow | null>(null);
  const [scheduleDraft, setScheduleDraft] = useState<ScheduleDraft>(EMPTY_SCHEDULE);

  const reportsQ = useQuery({ queryKey: ["reports"], queryFn: api.reports, retry: 1 });
  const subscriptionsQ = useQuery({ queryKey: ["reports", "subscriptions"], queryFn: api.reportSubscriptions, retry: 1 });

  const reports = useMemo(() => [...(reportsQ.data ?? [])].sort(compareReports), [reportsQ.data]);
  const collections = useMemo(
    () => [...new Set(reports.map((report) => report.category ?? "GENERAL"))].sort(compareCollections),
    [reports],
  );
  // Search and collection filters narrow the report library without changing
  // the selected report's complete preview dataset.
  const visibleReports = useMemo(() => {
    const term = search.trim().toLowerCase();
    return reports.filter((report) => {
      if (collection !== "ALL" && report.category !== collection) return false;
      if (!term) return true;
      return [report.label, report.description, report.businessQuestion, report.code, ...(report.audience ?? [])]
        .some((value) => String(value ?? "").toLowerCase().includes(term));
    });
  }, [collection, reports, search]);
  const selectedReport = reports.find((report) => report.code === selectedCode) ?? reports[0] ?? null;

  useEffect(() => {
    if (!reports.length) return;
    if (!selectedCode || !reports.some((report) => report.code === selectedCode)) setSelectedCode(reports[0].code);
  }, [reports, selectedCode]);

  useEffect(() => {
    setReportPage(0);
    setReportFilters(EMPTY_REPORT_FILTERS);
  }, [selectedReport?.code]);

  useEffect(() => {
    if (workspace !== "REPORTS_STUDIO" || !selectedReport) return;
    let disposed = false;
    setPreviewLoading(true);
    setPreviewError(null);
    setPreview(null);
    void api.reportPreview(selectedReport.code, { page: reportPage, size: REPORT_PAGE_SIZE, ...reportFilters })
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
  }, [previewRevision, reportFilters, reportPage, selectedReport?.code, workspace]);

  useEffect(() => {
    if (workspace !== "REPORTS_STUDIO" || viewerTab !== "DOCUMENT" || !selectedReport) {
      setDocumentPreviewUrl(null);
      setDocumentPreviewError(null);
      setDocumentPreviewLoading(false);
      return;
    }
    let disposed = false;
    let objectUrl: string | null = null;
    setDocumentPreviewLoading(true);
    setDocumentPreviewError(null);
    setDocumentPreviewUrl(null);
    void api.reportDocumentPreview(selectedReport.code, reportFilters)
      .then((file) => {
        if (disposed) return;
        objectUrl = URL.createObjectURL(file.blob);
        setDocumentPreviewUrl(objectUrl);
      })
      .catch((error: unknown) => {
        if (!disposed) setDocumentPreviewError(error instanceof Error ? error.message : "The PDF preview could not be generated.");
      })
      .finally(() => {
        if (!disposed) setDocumentPreviewLoading(false);
      });
    return () => {
      disposed = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [previewRevision, reportFilters, selectedReport?.code, viewerTab, workspace]);

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
      saveDownloadedFile(await api.downloadReport(report.code, format, reportFilters));
      toasts.push("info", "Report Download Ready", `${formatLabel(format)} report generated.`);
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
        <span className="eyebrow">{tp("Reporting")}</span>
        <h1>{tp("CRM Reports")}</h1>
        <p>{tp("View governed operational reports or build custom analytics from one controlled reporting workspace.")}</p>
      </div>
      {reportsQ.isSuccess && <div className="report-portfolio-count" aria-label={`${reports.length} reports across ${collections.length} collections`}>
        <span><strong>{formatNumber(reports.length)}</strong><small>{t("nav.module.reports", "Reports")}</small></span>
        <span><strong>{formatNumber(collections.length)}</strong><small>{tp("Collections")}</small></span>
        <span><strong>{formatNumber(3)}</strong><small>{tp("Formats")}</small></span>
      </div>}
    </div>

    <nav className="report-workspace-tabs" aria-label="Report Workspaces">
      <button className={workspace === "REPORTS_STUDIO" ? "active" : ""} aria-current={workspace === "REPORTS_STUDIO" ? "page" : undefined}
        onClick={() => setWorkspace("REPORTS_STUDIO")}>
        <strong>{t("ui.report.reportsStudio", "Reports Studio")}</strong>
        <span>{tp("Choose, view, download and schedule governed CRM reports.")}</span>
      </button>
      <button className={workspace === "CUSTOM_REPORTS" ? "active" : ""} aria-current={workspace === "CUSTOM_REPORTS" ? "page" : undefined}
        onClick={() => setWorkspace("CUSTOM_REPORTS")}>
        <strong>{t("ui.report.customReports", "Custom Reports")}</strong>
        <span>{tp("Build reports, dashboards, formulas, pivots and delivery policies.")}</span>
      </button>
    </nav>

    {workspace === "REPORTS_STUDIO" ? <>
      <section className="reports-studio-shell" aria-label={t("ui.report.reportsStudio", "Reports Studio")}>
        {/* The library collapses to an icon rail.
            The hamburger is the only control that stays visible in both states,
            so there is always a way back out — a collapse control that collapses
            with the thing it controls is a trap. Collapsed items keep their
            `title` and `aria-label`, so the report name is still reachable by
            hover and by screen reader when the visible glyph is just its mark. */}
        <aside
          className={`report-library${libraryOpen ? "" : " is-collapsed"}`}
          aria-label="Report library"
        >
          <header className="report-library-head">
            <button
              type="button"
              className="icon-btn report-library-toggle"
              aria-expanded={libraryOpen}
              aria-controls="report-library-list"
              aria-label={libraryOpen ? "Collapse the report list" : "Expand the report list"}
              title={libraryOpen ? "Collapse the report list" : "Expand the report list"}
              onClick={() => setLibraryOpen((open) => !open)}
            >
              <MenuIcon />
            </button>
            {libraryOpen && (
              <div>
                <span className="eyebrow">{tp("Report Library")}</span>
                <h2>{t("ui.report.chooseReport", "Choose A Report")}</h2>
                <p>{tp("Select a governed CRM report to display its complete grid and document preview.")}</p>
              </div>
            )}
          </header>

          {libraryOpen && <>
            <label className="report-library-search">
              <span>{tp("Search Reports")}</span>
              <input type="search" value={search} onChange={(event) => setSearch(event.target.value)}
                placeholder={tp("Search title, question or role")} />
            </label>
            <div className="report-collection-filter" role="group" aria-label="Filter By Collection">
              <button className={collection === "ALL" ? "active" : ""} onClick={() => setCollection("ALL")}>{t("ui.common.all", "All")}</button>
              {collections.map((value) => <button key={value} className={collection === value ? "active" : ""}
                onClick={() => setCollection(value)}>{categoryLabel(value)}</button>)}
            </div>
          </>}

          <div className="report-library-list" id="report-library-list" role="listbox" aria-label={t("ui.report.availableReports", "Available Reports")}>
            {reportsQ.isLoading && libraryOpen && <p className="loading-note">{tp("Loading Reports...")}</p>}
            {reportsQ.isError && libraryOpen && <p className="empty-note">{tp("Reports Could Not Be Loaded.")}</p>}
            {visibleReports.map((report) => (
              <button key={report.id} role="option" aria-selected={selectedReport?.code === report.code}
                className={`report-library-item${selectedReport?.code === report.code ? " active" : ""}`}
                title={`${report.label} — ${categoryLabel(report.category)}`}
                aria-label={report.label}
                onClick={() => setSelectedCode(report.code)}>
                {/* The mark. Two letters from the collection, so every report in
                    a collection shares a glyph and the rail reads as grouped
                    even with no labels. Shown in both states: expanded it is the
                    row's leading badge, collapsed it is the whole row. */}
                <span className="report-library-mark" aria-hidden="true">
                  {collectionMark(report.category)}
                </span>
                {libraryOpen && <span className="report-library-text">
                  <span className="report-library-collection">{categoryLabel(report.category)}</span>
                  <strong>{report.label}</strong>
                  <small>{report.businessQuestion}</small>
                </span>}
              </button>
            ))}
            {reportsQ.isSuccess && visibleReports.length === 0 && libraryOpen
              && <p className="empty-note">No Reports Match This Search.</p>}
          </div>

          {libraryOpen && (
            <footer className="report-library-status">
              <span aria-hidden="true" />
              <div>
                <strong>Reporting Engine Ready</strong>
                <small>{reports.length} reports · PDF · Excel · Word</small>
              </div>
            </footer>
          )}
        </aside>

        {!fullPreview && <ReportDocumentWorkspace report={selectedReport} preview={preview} previewLoading={previewLoading}
          previewError={previewError} documentPreviewUrl={documentPreviewUrl} documentPreviewLoading={documentPreviewLoading}
          documentPreviewError={documentPreviewError} full={false} activeTab={viewerTab} onTabChange={setViewerTab}
          onDownload={download} onSchedule={openSchedule}
          reportFilters={reportFilters} reportPage={reportPage} onReportFiltersChange={(next) => { setReportFilters(next); setReportPage(0); }} onReportPageChange={setReportPage}
          onRefresh={() => setPreviewRevision((value) => value + 1)} onToggleFull={() => setFullPreview(true)} />}
      </section>

      {fullPreview && createPortal(<ReportDocumentWorkspace report={selectedReport} preview={preview} previewLoading={previewLoading}
        previewError={previewError} documentPreviewUrl={documentPreviewUrl} documentPreviewLoading={documentPreviewLoading}
        documentPreviewError={documentPreviewError} full activeTab={viewerTab} onTabChange={setViewerTab}
        onDownload={download} onSchedule={openSchedule}
        reportFilters={reportFilters} reportPage={reportPage} onReportFiltersChange={(next) => { setReportFilters(next); setReportPage(0); }} onReportPageChange={setReportPage}
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
  documentPreviewUrl,
  documentPreviewLoading,
  documentPreviewError,
  full,
  activeTab,
  onTabChange,
  onDownload,
  onSchedule,
  reportFilters,
  reportPage,
  onReportFiltersChange,
  onReportPageChange,
  onRefresh,
  onToggleFull,
}: {
  report: ReportRow | null;
  preview: ReportPreview | null;
  previewLoading: boolean;
  previewError: string | null;
  documentPreviewUrl: string | null;
  documentPreviewLoading: boolean;
  documentPreviewError: string | null;
  full: boolean;
  activeTab: ReportViewerTab;
  onTabChange: (tab: ReportViewerTab) => void;
  onDownload: (report: ReportRow, format: ReportFormat) => Promise<void>;
  onSchedule: (report: ReportRow) => void;
  reportFilters: ReportGridFilters;
  reportPage: number;
  onReportFiltersChange: (filters: ReportGridFilters) => void;
  onReportPageChange: (page: number) => void;
  onRefresh: () => void;
  onToggleFull: () => void;
}) {
  const { t, tp } = useI18n();
  return <section className={`report-document-workspace${full ? " report-document-full" : ""}`} aria-label="Report Document Viewer">
    {report ? <>
      <header className="report-document-head">
        <div>
          <div className="report-card-meta"><span className="eyebrow">{categoryLabel(report.category)}</span><code>{report.code}</code></div>
          <h2>{report.label}</h2>
          <p>{report.description}</p>
        </div>
        <div className="report-document-actions" aria-label="Report Download Options">
          <button className="btn btn-sm primary" onClick={() => void onDownload(report, "PDF")}>{t("ui.grid.downloadPdf", "Download PDF")}</button>
          <button className="btn btn-sm" onClick={() => void onDownload(report, "XLSX")}>{t("ui.grid.downloadExcel", "Download Excel")}</button>
          <button className="btn btn-sm" onClick={() => void onDownload(report, "DOCX")}>{t("ui.grid.downloadWord", "Download Word")}</button>
          <button className="btn btn-sm" onClick={() => onSchedule(report)}>{t("ui.report.schedule", "Schedule Report")}</button>
        </div>
      </header>
      <div className="report-decision-strip">
        <div><span>{t("ui.report.decisionSupported", "Decision Supported")}</span><strong>{tp(report.businessQuestion)}</strong></div>
        <div><span>{t("ui.report.recommendedFor", "Recommended For")}</span><p>{(report.audience ?? []).map(roleLabel).map(tp).join(" · ")}</p></div>
        <div><span>{t("ui.report.availableFormats", "Available Formats")}</span><p>{report.allowedFormats.map(formatLabel).join(" · ")}</p></div>
      </div>
      <div className="report-view-tabs" role="tablist" aria-label="Report View">
        <button id="report-grid-tab" type="button" role="tab" aria-selected={activeTab === "GRID"}
          aria-controls="report-grid-panel" className={activeTab === "GRID" ? "active" : ""}
          onClick={() => onTabChange("GRID")}>
          <strong>{t("ui.report.reportGrid", "Report Grid")}</strong><span>{tp("Work with the current report rows.")}</span>
        </button>
        <button id="report-document-tab" type="button" role="tab" aria-selected={activeTab === "DOCUMENT"}
          aria-controls="report-document-panel" className={activeTab === "DOCUMENT" ? "active" : ""}
          onClick={() => onTabChange("DOCUMENT")}>
          <strong>{t("ui.report.documentPreview", "Document Preview")}</strong><span>{tp("Review the formatted document.")}</span>
        </button>
      </div>
      <div className="report-preview-toolbar">
        <div><span className="eyebrow">{activeTab === "GRID" ? t("ui.report.currentReportGrid", "Current Report Grid") : t("ui.report.documentPreview", "Document Preview")}</span><small>{tp("Same Governed Data As The Downloads")}</small></div>
        <div>
          <button className="btn btn-sm" disabled={previewLoading} onClick={onRefresh}>
            {previewLoading ? tp("Generating Preview...") : t("ui.report.refreshPreview", "Refresh Preview")}
          </button>
          <button className="btn btn-sm" onClick={onToggleFull}>{full ? t("ui.grid.restoreView", "Restore view") : t("ui.grid.fullView", "Full view")}</button>
        </div>
      </div>
      <div id={activeTab === "GRID" ? "report-grid-panel" : "report-document-panel"} className="report-preview-frame"
        role="tabpanel" aria-labelledby={activeTab === "GRID" ? "report-grid-tab" : "report-document-tab"}>
        {activeTab === "GRID" && previewLoading && <div className="report-preview-state"><span className="spinner" /><strong>Generating Current Report Grid...</strong><p>The governed report query is being prepared.</p></div>}
        {activeTab === "GRID" && previewError && <div className="report-preview-state is-error"><strong>Grid Could Not Be Generated</strong><p>{previewError}</p><button className="btn btn-sm" onClick={onRefresh}>Retry Grid</button></div>}
        {preview && !previewLoading && activeTab === "GRID" && <ReportGridView report={report} preview={preview}
          filters={reportFilters} page={reportPage} onFiltersChange={onReportFiltersChange} onPageChange={onReportPageChange} />}
        {activeTab === "DOCUMENT" && documentPreviewLoading && <div className="report-preview-state"><span className="spinner" /><strong>Rendering PDF...</strong><p>Axiom is creating an authenticated, tenant-scoped document preview.</p></div>}
        {activeTab === "DOCUMENT" && documentPreviewError && <div className="report-preview-state is-error"><strong>PDF Preview Could Not Be Generated</strong><p>{documentPreviewError}</p><button className="btn btn-sm" onClick={onRefresh}>Retry PDF Preview</button></div>}
        {activeTab === "DOCUMENT" && documentPreviewUrl && !documentPreviewLoading && <PdfDocumentViewer
          url={documentPreviewUrl} title={`${report.label} PDF Preview`} />}
      </div>
    </> : <div className="report-preview-state"><strong>{t("ui.report.chooseReport", "Choose A Report")}</strong><p>{tp("Select a report from the library to view its complete document.")}</p></div>}
  </section>;
}

function ReportGridView({ report, preview, filters, page, onFiltersChange, onPageChange }: {
  report: ReportRow;
  preview: ReportPreview;
  filters: ReportGridFilters;
  page: number;
  onFiltersChange: (filters: ReportGridFilters) => void;
  onPageChange: (page: number) => void;
}) {
  const { t, tp, formatNumber, formatDate } = useI18n();
  const rows = preview.rows.items;
  const activeFilters = Object.values(filters).filter((value) => value?.trim()).length;

  function updateFilters(next: Record<string, string>) {
    onFiltersChange({
      search: next.search ?? filters.search ?? "",
      metric: next.metric ?? "",
      value: next.value ?? "",
      detail: next.detail ?? "",
      signal: next.signal ?? "",
    });
  }

  return <div className="report-grid-view" aria-label={`${report.label} Current Report Grid`}>
    <header>
      <div><span className="eyebrow">{t("ui.report.currentDataset", "Current Governed Dataset")}</span><h3>{tp(report.label)}</h3>
        <p>{tp("Search and column filters run on the server. Downloads use the same complete filtered result.")}</p></div>
      <div className="report-grid-count"><strong>{formatNumber(preview.rows.total)}</strong><span>{tp("Matching Rows")}</span></div>
    </header>
    <div className="report-grid-query" role="search" aria-label={`${report.label} report search`}>
      <label><span>{tp("Search All Columns")}</span><input type="search" value={filters.search ?? ""}
        placeholder={tp("Search the complete report")}
        onChange={(event) => onFiltersChange({ ...filters, search: event.target.value })} /></label>
      <div>
        <span>{formatNumber(activeFilters)} {tp(activeFilters === 1 ? "Active Filter" : "Active Filters")}</span>
        <button type="button" className="btn btn-sm" disabled={activeFilters === 0}
          onClick={() => onFiltersChange(EMPTY_REPORT_FILTERS)}>{tp("Reset Filters")}</button>
      </div>
    </div>
    <div className="table-wrap"><table className="data-table report-current-grid">
      <thead><tr><th>{preview.columns.dimension}</th><th>{preview.columns.value}</th><th>{preview.columns.detail}</th><th>{preview.columns.signal}</th></tr>
        <GridFilterRow columns={[
          { key: "metric", label: preview.columns.dimension },
          { key: "value", label: preview.columns.value },
          { key: "detail", label: preview.columns.detail },
          { key: "signal", label: preview.columns.signal },
        ]} filters={filters as Record<string, string>} onChange={updateFilters} />
      </thead>
      <tbody>{rows.map((row, index) => <tr key={`${row.metric}-${page}-${index}`}>
        <td><strong>{row.metric}</strong></td><td className="mono num">{row.value}</td><td>{row.detail}</td>
        <td><span className={`report-signal signal-${signalClass(row.signal)}`}>{titleCase(row.signal)}</span></td>
      </tr>)}{rows.length === 0 && <tr><td colSpan={4} className="empty-note">No Report Rows Match This Search And Filter.</td></tr>}</tbody>
    </table></div>
    <footer className="report-grid-footer">
      <div><span data-i18n-skip>{preview.tenantName}</span><span>{t("ui.report.generated", "Generated")} {formatDate(preview.generatedAt, { dateStyle: "medium", timeStyle: "short" })}</span></div>
      <div className="page-controls" aria-label="Report pagination">
        <span>{tp("Showing")} {formatNumber(rows.length)} {tp("of")} {formatNumber(preview.rows.total)} {tp("records")} - {formatNumber(preview.rows.size)} {t("ui.grid.rowsPerPage", "rows per page")}</span>
        <div>
          <button className="btn btn-sm" disabled={page === 0} onClick={() => onPageChange(Math.max(0, page - 1))}>{t("ui.common.previous", "Previous")}</button>
          <strong>{t("ui.grid.page", "Page")} {formatNumber(preview.rows.totalPages === 0 ? 0 : page + 1)} {tp("of")} {formatNumber(preview.rows.totalPages)}</strong>
          <button className="btn btn-sm" disabled={page + 1 >= preview.rows.totalPages} onClick={() => onPageChange(page + 1)}>{t("ui.common.next", "Next")}</button>
        </div>
      </div>
    </footer>
  </div>;
}

function PdfDocumentViewer({ url, title }: { url: string; title: string }) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [document, setDocument] = useState<PDFDocumentProxy | null>(null);
  const [pageNumber, setPageNumber] = useState(1);
  const [zoom, setZoom] = useState(1.15);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const renderTaskRef = useRef<RenderTask | null>(null);

  useEffect(() => {
    const loadingTask = getDocument({ url });
    let disposed = false;
    setLoading(true);
    setError(null);
    setDocument(null);
    setPageNumber(1);
    void loadingTask.promise
      .then((pdf) => {
        if (!disposed) setDocument(pdf);
      })
      .catch((reason: unknown) => {
        if (!disposed) setError(reason instanceof Error ? reason.message : "The PDF could not be opened.");
      })
      .finally(() => {
        if (!disposed) setLoading(false);
      });
    return () => {
      disposed = true;
      renderTaskRef.current?.cancel();
      void loadingTask.destroy();
    };
  }, [url]);

  useEffect(() => {
    if (!document || !canvasRef.current) return;
    let disposed = false;
    renderTaskRef.current?.cancel();
    void document.getPage(pageNumber).then((page) => {
      if (disposed || !canvasRef.current) return;
      const canvas = canvasRef.current;
      const viewport = page.getViewport({ scale: zoom });
      const outputScale = Math.max(1, window.devicePixelRatio || 1);
      const context = canvas.getContext("2d", { alpha: false });
      if (!context) throw new Error("Canvas rendering is unavailable in this browser.");
      canvas.width = Math.floor(viewport.width * outputScale);
      canvas.height = Math.floor(viewport.height * outputScale);
      canvas.style.width = `${Math.floor(viewport.width)}px`;
      canvas.style.height = `${Math.floor(viewport.height)}px`;
      const renderTask = page.render({
        canvas,
        canvasContext: context,
        viewport,
        transform: outputScale === 1 ? undefined : [outputScale, 0, 0, outputScale, 0, 0],
      });
      renderTaskRef.current = renderTask;
      return renderTask.promise;
    }).catch((reason: unknown) => {
      if (!disposed && reason instanceof Error && reason.name !== "RenderingCancelledException") setError(reason.message);
    });
    return () => {
      disposed = true;
      renderTaskRef.current?.cancel();
    };
  }, [document, pageNumber, zoom]);

  const pageCount = document?.numPages ?? 0;
  return <div className="report-pdf-viewer" aria-label={title}>
    <div className="report-pdf-toolbar" aria-label="PDF Viewer Controls">
      <div>
        <button className="btn btn-sm" disabled={pageNumber <= 1} onClick={() => setPageNumber((value) => Math.max(1, value - 1))}>Previous Page</button>
        <span aria-live="polite">Page {pageNumber} Of {pageCount || "—"}</span>
        <button className="btn btn-sm" disabled={!pageCount || pageNumber >= pageCount} onClick={() => setPageNumber((value) => Math.min(pageCount, value + 1))}>Next Page</button>
      </div>
      <div>
        <button className="btn btn-sm" disabled={zoom <= .65} aria-label="Zoom Out" onClick={() => setZoom((value) => Math.max(.65, value - .15))}>−</button>
        <span aria-live="polite">{Math.round(zoom * 100)}%</span>
        <button className="btn btn-sm" disabled={zoom >= 2} aria-label="Zoom In" onClick={() => setZoom((value) => Math.min(2, value + .15))}>+</button>
        <a className="btn btn-sm" href={url} target="_blank" rel="noreferrer">Open PDF</a>
      </div>
    </div>
    <div className="report-pdf-canvas-stage">
      {loading && <div className="report-preview-state"><span className="spinner" /><strong>Opening PDF Document...</strong></div>}
      {error && <div className="report-preview-state is-error"><strong>PDF Could Not Be Displayed</strong><p>{error}</p><a className="btn btn-sm" href={url} target="_blank" rel="noreferrer">Open PDF</a></div>}
      <canvas ref={canvasRef} hidden={loading || !!error} aria-label={`PDF page ${pageNumber} of ${pageCount}`} />
    </div>
  </div>;
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

/**
 * A two-letter mark for a report collection.
 *
 * <p>Reports have no icon of their own — they are rows in a catalogue, not
 * features — so the collapsed rail needs a glyph derived from data that exists.
 * The collection initials do the job: every report in a collection shares a mark,
 * which means the collapsed rail still reads as GROUPED rather than as twenty-one
 * identical squares. The report name stays reachable through the title attribute
 * and aria-label, so nothing is only communicated by the mark.
 */
function collectionMark(value: ReportDefinition["category"] | string | null | undefined): string {
  const key = String(value ?? "GENERAL").toUpperCase();
  const marks: Record<string, string> = {
    EXECUTIVE: "EX", SALES: "SL", GROWTH: "GR", CUSTOMER: "CU",
    COMMERCIAL: "CM", GOVERNANCE: "GV", GENERAL: "GN",
  };
  return marks[key] ?? key.slice(0, 2);
}

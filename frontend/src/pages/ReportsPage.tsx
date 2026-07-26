import { Fragment } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, isUnreachable } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataGridToolbar, saveDownloadedFile } from "../components/DataGridToolbar";
import { DataViewFrame } from "../components/DataViewFrame";
import { useToasts } from "../components/Toasts";
import { filterRowsByColumns, groupLabelFor, selectedGroupColumns, sortByGroups, type GroupColumn } from "../lib/gridGrouping";
import { usePersistedGridState } from "../lib/usePersistedGridState";

type ReportRow = Awaited<ReturnType<typeof api.reports>>[number];

const REPORT_GROUP_COLUMNS: GroupColumn<ReportRow>[] = [
  { key: "code", label: "Code", value: (row) => row.code },
  { key: "label", label: "Report", value: (row) => row.label },
  { key: "description", label: "Description", value: (row) => row.description },
  { key: "family", label: "Format family", value: (row) => row.allowedFormats.includes("PDF") ? "Jasper document reports" : "Data extracts" },
  { key: "status", label: "Status", value: (row) => row.active ? "Active" : "Inactive" },
  { key: "formats", label: "Formats", value: (row) => row.allowedFormats.join(", ") },
];

export function ReportsPage() {
  const toasts = useToasts();
  const [groupColumns, setGroupColumns, columnFilters, setColumnFilters] = usePersistedGridState("reports");
  const reportsQ = useQuery({ queryKey: ["reports"], queryFn: api.reports, retry: 1 });
  if (isUnreachable(reportsQ.error)) return <ApiUnreachable onRetry={() => void reportsQ.refetch()} retrying={reportsQ.isFetching} />;
  const activeGroupColumns = selectedGroupColumns(REPORT_GROUP_COLUMNS, groupColumns);
  const reports = sortByGroups(filterRowsByColumns(reportsQ.data ?? [], REPORT_GROUP_COLUMNS, columnFilters), activeGroupColumns, (row) => row.label);

  async function download(code: string, format: "PDF" | "XLSX" | "DOCX") {
    try {
      saveDownloadedFile(await api.downloadReport(code, format));
      toasts.push("info", "Report download ready", `${format} report generated through Jasper Reports.`);
    } catch (error) {
      toasts.push("error", "Report download failed", error instanceof Error ? error.message : "Download failed.");
    }
  }

  return <>
    <div className="page-head">
      <div><span className="eyebrow">Jasper reporting</span><h1>Reports</h1><p>Download governed report output as PDF, Excel or Word.</p></div>
      {reportsQ.isSuccess && <span className="count">{reportsQ.data.length} reports</span>}
    </div>
    <DataViewFrame
      title="Report catalogue"
      actions={<DataGridToolbar
        gridName="Report catalogue"
        grouped={activeGroupColumns.length > 0}
        groupLabel="Format family"
        onToggleGroup={() => setGroupColumns((value) => value.length > 0 ? [] : ["family"])}
        groupColumns={REPORT_GROUP_COLUMNS.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={groupColumns}
        onGroupColumnsChange={setGroupColumns}
        filterColumns={REPORT_GROUP_COLUMNS.map(({ key, label }) => ({ key, label }))}
        columnFilters={columnFilters}
        onColumnFiltersChange={setColumnFilters}
        auditEntityType="REPORT_DEFINITION"
        exportFilename="report-catalogue"
        exportRows={reports.map((report) => ({
          code: report.code,
          label: report.label,
          description: report.description,
          allowedFormats: report.allowedFormats.join(", "),
          active: report.active ? "Active" : "Inactive",
        }))}
        note="Catalogue export; report cards still generate Jasper output"
      />}
    >
      {reportsQ.isLoading && <p className="loading-note">Loading reports...</p>}
      {reportsQ.isError && <p className="empty-note">Reports failed to load{reportsQ.error instanceof Error ? `: ${reportsQ.error.message}` : "."}</p>}
      {reportsQ.isSuccess && <div className="report-grid">
        {reports.map((report, index, all) => {
          const group = activeGroupColumns.length > 0 ? groupLabelFor(report, activeGroupColumns) : "";
          const previous = index > 0 ? all[index - 1] : undefined;
          const previousGroup = previous && activeGroupColumns.length > 0 ? groupLabelFor(previous, activeGroupColumns) : "";
          return <Fragment key={report.id}>
            {activeGroupColumns.length > 0 && group !== previousGroup && <div className="grid-card-group">{group}</div>}
            <article className="report-card">
              <div><span className="eyebrow">{report.code}</span><h2>{report.label}</h2><p>{report.description}</p></div>
              <div className="report-actions">
                <button className="btn btn-sm" onClick={() => void download(report.code, "PDF")}>PDF</button>
                <button className="btn btn-sm" onClick={() => void download(report.code, "XLSX")}>Excel</button>
                <button className="btn btn-sm" onClick={() => void download(report.code, "DOCX")}>Word</button>
              </div>
            </article>
          </Fragment>;
        })}
        {reportsQ.data.length === 0 && <p className="empty-note">No active report definitions are configured.</p>}
      </div>}
    </DataViewFrame>
  </>;
}

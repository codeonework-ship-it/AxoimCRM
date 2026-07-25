import { Fragment, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, isUnreachable } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataGridToolbar, saveDownloadedFile } from "../components/DataGridToolbar";
import { DataViewFrame } from "../components/DataViewFrame";
import { useToasts } from "../components/Toasts";

export function ReportsPage() {
  const toasts = useToasts();
  const [grouped, setGrouped] = useState(false);
  const reportsQ = useQuery({ queryKey: ["reports"], queryFn: api.reports, retry: 1 });
  if (isUnreachable(reportsQ.error)) return <ApiUnreachable onRetry={() => void reportsQ.refetch()} retrying={reportsQ.isFetching} />;

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
        grouped={grouped}
        groupLabel="Format family"
        onToggleGroup={() => setGrouped((value) => !value)}
        auditEntityType="REPORT_DEFINITION"
        exportFilename="report-catalogue"
        exportRows={(reportsQ.data ?? []).map((report) => ({
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
        {reportsQ.data.map((report, index, all) => {
          const group = report.allowedFormats.includes("PDF") ? "Jasper document reports" : "Data extracts";
          const previous = index > 0 ? all[index - 1] : undefined;
          const previousGroup = previous?.allowedFormats.includes("PDF") ? "Jasper document reports" : "Data extracts";
          return <Fragment key={report.id}>
            {grouped && group !== previousGroup && <div className="grid-card-group">{group}</div>}
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

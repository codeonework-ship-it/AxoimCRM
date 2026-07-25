import { useQuery } from "@tanstack/react-query";
import { api, isUnreachable, type DownloadedFile } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataViewFrame } from "../components/DataViewFrame";
import { useToasts } from "../components/Toasts";

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

export function ReportsPage() {
  const toasts = useToasts();
  const reportsQ = useQuery({ queryKey: ["reports"], queryFn: api.reports, retry: 1 });
  if (isUnreachable(reportsQ.error)) return <ApiUnreachable onRetry={() => void reportsQ.refetch()} retrying={reportsQ.isFetching} />;

  async function download(code: string, format: "PDF" | "XLSX" | "DOCX") {
    try {
      saveFile(await api.downloadReport(code, format));
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
    <DataViewFrame title="Report catalogue">
      {reportsQ.isLoading && <p className="loading-note">Loading reports...</p>}
      {reportsQ.isError && <p className="empty-note">Reports failed to load{reportsQ.error instanceof Error ? `: ${reportsQ.error.message}` : "."}</p>}
      {reportsQ.isSuccess && <div className="report-grid">
        {reportsQ.data.map((report) => <article className="report-card" key={report.id}>
          <div><span className="eyebrow">{report.code}</span><h2>{report.label}</h2><p>{report.description}</p></div>
          <div className="report-actions">
            <button className="btn btn-sm" onClick={() => void download(report.code, "PDF")}>PDF</button>
            <button className="btn btn-sm" onClick={() => void download(report.code, "XLSX")}>Excel</button>
            <button className="btn btn-sm" onClick={() => void download(report.code, "DOCX")}>Word</button>
          </div>
        </article>)}
        {reportsQ.data.length === 0 && <p className="empty-note">No active report definitions are configured.</p>}
      </div>}
    </DataViewFrame>
  </>;
}

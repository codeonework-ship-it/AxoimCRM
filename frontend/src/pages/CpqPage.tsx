import { useState } from "react";
import { NavLink } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, isUnreachable, type CpqPriceBook, type CpqProduct, type CpqQuote, type DownloadedFile } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataViewFrame } from "../components/DataViewFrame";
import { GridLoader } from "../components/Loaders";
import { useToasts } from "../components/Toasts";
import { formatDate, formatMoney } from "../lib/format";

type CpqSection = "products" | "price-books" | "quotes";

interface CpqPageProps {
  section: CpqSection;
}

const QUOTE_STATUSES = ["DRAFT", "IN_APPROVAL", "SENT", "ACCEPTED", "REJECTED", "EXPIRED", "ORDERED"];
const PRICE_BOOK_STATUSES = ["DRAFT", "ACTIVE", "ARCHIVED"];

export function CpqPage({ section }: CpqPageProps) {
  const toasts = useToasts();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState("");

  const productsQ = useQuery({
    queryKey: ["cpq", "products", page, search, filter],
    queryFn: () => api.cpqProducts({ page, search, category: filter }),
    enabled: section === "products",
    retry: 1,
  });
  const priceBooksQ = useQuery({
    queryKey: ["cpq", "price-books", page, search, filter],
    queryFn: () => api.cpqPriceBooks({ page, search, status: filter }),
    enabled: section === "price-books",
    retry: 1,
  });
  const quotesQ = useQuery({
    queryKey: ["cpq", "quotes", page, search, filter],
    queryFn: () => api.cpqQuotes({ page, search, status: filter }),
    enabled: section === "quotes",
    retry: 1,
  });
  const summaryQ = useQuery({ queryKey: ["cpq", "quotes", "summary"], queryFn: api.cpqQuoteSummary, retry: 1 });

  const activeQ = section === "products" ? productsQ : section === "price-books" ? priceBooksQ : quotesQ;
  if (isUnreachable(activeQ.error)) return <ApiUnreachable onRetry={() => void activeQ.refetch()} retrying={activeQ.isFetching} />;

  const pageData = activeQ.data;
  const total = pageData?.total ?? 0;
  const totalPages = pageData?.totalPages ?? 0;

  function resetFilters() {
    setSearch("");
    setFilter("");
    setPage(0);
  }

  async function downloadQuote(quote: CpqQuote, format: "PDF" | "DOCX" | "XLSX") {
    try {
      saveFile(await api.downloadQuote(quote.id, format));
      toasts.push("info", "Quote document ready", `${quote.quoteNumber} was downloaded as ${format}.`);
    } catch (error) {
      toasts.push("error", "Quote download failed", error instanceof Error ? error.message : "Download failed.");
    }
  }

  return <>
    <div className="page-head cpq-head">
      <div>
        <span className="eyebrow">Quote-to-cash command</span>
        <h1>{section === "products" ? "Products" : section === "price-books" ? "Price books" : "Quotes & CPQ"}</h1>
        <p>Governed commerce records with tenant-safe search, filters, server pagination and audit-ready lifecycle state.</p>
      </div>
      {activeQ.isSuccess && <span className="count">{total} records</span>}
    </div>

    <div className="cpq-tabs" aria-label="CPQ sections">
      <NavLink to="/products">Products</NavLink>
      <NavLink to="/price-books">Price books</NavLink>
      <NavLink to="/quotes">Quotes</NavLink>
    </div>

    <div className="kpi-row cpq-kpis">
      <div className="kpi"><span className="label">Quote pipeline</span><div className="kpi-value">{formatMoney(summaryQ.data?.netPipeline)}</div><div className="kpi-sub">Draft, approval and sent quotes</div></div>
      <div className="kpi"><span className="label">Accepted revenue</span><div className="kpi-value">{formatMoney(summaryQ.data?.acceptedRevenue)}</div><div className="kpi-sub">Accepted or converted quotes</div></div>
      <div className="kpi"><span className="label">In approval</span><div className="kpi-value">{summaryQ.data?.approvalQuotes ?? "..."}</div><div className="kpi-sub">Discount or margin governance</div></div>
      <div className="kpi"><span className="label">Sent</span><div className="kpi-value">{summaryQ.data?.sentQuotes ?? "..."}</div><div className="kpi-sub">Awaiting customer response</div></div>
    </div>

    <section className="list-controls" aria-label="CPQ search and filters">
      <label><span>Search</span><input value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} placeholder={section === "quotes" ? "Quote, account, opportunity, owner" : "Code, name, family, segment"} /></label>
      {section === "products" && <label><span>Category</span><input value={filter} onChange={(event) => { setFilter(event.target.value); setPage(0); }} placeholder="Exact category" /></label>}
      {section === "price-books" && <label><span>Status</span><select value={filter} onChange={(event) => { setFilter(event.target.value); setPage(0); }}><option value="">All statuses</option>{PRICE_BOOK_STATUSES.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>}
      {section === "quotes" && <label><span>Status</span><select value={filter} onChange={(event) => { setFilter(event.target.value); setPage(0); }}><option value="">All statuses</option>{QUOTE_STATUSES.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>}
      <button className="btn btn-sm" onClick={resetFilters} disabled={!search && !filter}>Reset</button>
    </section>

    <DataViewFrame
      title={section === "products" ? "Product catalogue" : section === "price-books" ? "Price book register" : "Quote register"}
      actions={<span className="cpq-note">100 rows/page - vendor integrations pending</span>}
    >
      {activeQ.isLoading && <GridLoader label="Reading governed CPQ records" rows={6} columns={6} />}
      {activeQ.isError && <p className="empty-note">CPQ records failed to load{activeQ.error instanceof Error ? `: ${activeQ.error.message}` : "."}</p>}
      {activeQ.isSuccess && section === "products" && <ProductTable rows={(pageData?.items ?? []) as CpqProduct[]} />}
      {activeQ.isSuccess && section === "price-books" && <PriceBookTable rows={(pageData?.items ?? []) as CpqPriceBook[]} />}
      {activeQ.isSuccess && section === "quotes" && <QuoteTable rows={(pageData?.items ?? []) as CpqQuote[]} onDownload={downloadQuote} />}
      {activeQ.isSuccess && <footer className="page-controls" aria-label="CPQ pagination">
        <span>Showing {pageData?.items.length ?? 0} of {total} records - 100 rows per page</span>
        <div>
          <button className="btn btn-sm" disabled={page === 0 || activeQ.isFetching} onClick={() => setPage((value) => Math.max(0, value - 1))}>Previous</button>
          <strong>Page {totalPages === 0 ? 0 : page + 1} of {totalPages}</strong>
          <button className="btn btn-sm" disabled={page + 1 >= totalPages || activeQ.isFetching} onClick={() => setPage((value) => value + 1)}>Next</button>
        </div>
      </footer>}
    </DataViewFrame>
  </>;
}

function ProductTable({ rows }: { rows: CpqProduct[] }) {
  return <div className="table-wrap"><table className="data-table cpq-table">
    <thead><tr><th>Code</th><th>Product</th><th>Family</th><th>Category</th><th>UOM</th><th>Active prices</th><th>Status</th></tr></thead>
    <tbody>
      {rows.map((row) => <tr key={row.id}>
        <td className="mono">{row.code}</td><td>{row.name}</td><td>{row.productFamily ?? "-"}</td><td>{row.category ?? "-"}</td><td>{row.unitOfMeasure}</td><td>{row.activePriceCount}</td>
        <td><span className={`chip ${row.active ? "chip-open" : "chip-cancelled"}`}>{row.active ? "ACTIVE" : "INACTIVE"}</span>{row.subscription && <span className="chip cpq-mini">SUBSCRIPTION</span>}</td>
      </tr>)}
      {rows.length === 0 && <tr><td colSpan={7} className="empty-note">No products match the current query.</td></tr>}
    </tbody>
  </table></div>;
}

function PriceBookTable({ rows }: { rows: CpqPriceBook[] }) {
  return <div className="table-wrap"><table className="data-table cpq-table">
    <thead><tr><th>Code</th><th>Name</th><th>Currency</th><th>Segment</th><th>Version</th><th>Entries</th><th>Status</th></tr></thead>
    <tbody>
      {rows.map((row) => <tr key={row.id}>
        <td className="mono">{row.code}</td><td>{row.name}{row.defaultBook && <span className="chip cpq-mini">DEFAULT</span>}</td><td>{row.currencyCode}</td><td>{row.customerSegment ?? row.businessUnitCode ?? "All"}</td><td>v{row.versionNumber}</td><td>{row.entryCount}</td>
        <td><span className={`chip chip-${row.status.toLowerCase()}`}>{row.status}</span><small>{row.activatedAt ? `Activated ${formatDate(row.activatedAt)}` : "Not activated"}</small></td>
      </tr>)}
      {rows.length === 0 && <tr><td colSpan={7} className="empty-note">No price books match the current query.</td></tr>}
    </tbody>
  </table></div>;
}

function QuoteTable({ rows, onDownload }: { rows: CpqQuote[]; onDownload: (quote: CpqQuote, format: "PDF" | "DOCX" | "XLSX") => void }) {
  return <div className="table-wrap"><table className="data-table cpq-table">
    <thead><tr><th>Quote</th><th>Account</th><th>Opportunity</th><th>Owner</th><th>Total</th><th>Margin</th><th>Status</th><th>Docs</th></tr></thead>
    <tbody>
      {rows.map((row) => <tr key={row.id}>
        <td><span className="mono">{row.quoteNumber}</span><small>{row.name} - v{row.versionNumber}</small></td>
        <td>{row.accountName}</td><td>{row.opportunityName ?? "-"}</td><td>{row.ownerName ?? "-"}</td>
        <td>{formatMoney(row.grandTotal)}<small>Discount {formatMoney(row.discountTotal)}</small></td>
        <td>{row.marginPct == null ? "-" : `${row.marginPct.toFixed(1)}%`}</td>
        <td><span className={`chip chip-${row.status.toLowerCase()}`}>{row.status}</span><small>{row.approvalStatus} - expires {formatDate(row.expiresAt)}</small></td>
        <td className="quote-doc-actions"><button className="link-btn" onClick={() => onDownload(row, "PDF")}>PDF</button><button className="link-btn" onClick={() => onDownload(row, "DOCX")}>Word</button><button className="link-btn" onClick={() => onDownload(row, "XLSX")}>Excel</button></td>
      </tr>)}
      {rows.length === 0 && <tr><td colSpan={8} className="empty-note">No quotes match the current query.</td></tr>}
    </tbody>
  </table></div>;
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

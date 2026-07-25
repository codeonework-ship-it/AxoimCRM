import { Fragment, useState } from "react";
import { NavLink } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, isUnreachable, type CpqPriceBook, type CpqProduct, type CpqQuote } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataGridToolbar, saveDownloadedFile } from "../components/DataGridToolbar";
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
  const [grouped, setGrouped] = useState(false);

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
  const productRows = ((section === "products" ? pageData?.items : []) ?? []) as CpqProduct[];
  const priceBookRows = ((section === "price-books" ? pageData?.items : []) ?? []) as CpqPriceBook[];
  const quoteRows = ((section === "quotes" ? pageData?.items : []) ?? []) as CpqQuote[];

  function resetFilters() {
    setSearch("");
    setFilter("");
    setPage(0);
  }

  async function downloadQuote(quote: CpqQuote, format: "PDF" | "DOCX" | "XLSX") {
    try {
      saveDownloadedFile(await api.downloadQuote(quote.id, format));
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
      actions={<DataGridToolbar
        gridName={section === "products" ? "Product catalogue" : section === "price-books" ? "Price book register" : "Quote register"}
        grouped={grouped}
        groupLabel={section === "products" ? "Category" : "Status"}
        onToggleGroup={() => setGrouped((value) => !value)}
        auditEntityType={section === "products" ? "PRODUCT" : section === "price-books" ? "PRICE_BOOK" : "QUOTE"}
        exportFilename={`cpq-${section}`}
        exportRows={cpqExportRows(section, productRows, priceBookRows, quoteRows)}
        note="Current filtered page - vendor integrations pending"
      />}
    >
      {activeQ.isLoading && <GridLoader label="Reading governed CPQ records" rows={6} columns={6} />}
      {activeQ.isError && <p className="empty-note">CPQ records failed to load{activeQ.error instanceof Error ? `: ${activeQ.error.message}` : "."}</p>}
      {activeQ.isSuccess && section === "products" && <ProductTable rows={grouped ? sortedBy(productRows, (row) => row.category ?? "Unclassified", (row) => row.name) : productRows} grouped={grouped} />}
      {activeQ.isSuccess && section === "price-books" && <PriceBookTable rows={grouped ? sortedBy(priceBookRows, (row) => row.status, (row) => row.name) : priceBookRows} grouped={grouped} />}
      {activeQ.isSuccess && section === "quotes" && <QuoteTable rows={grouped ? sortedBy(quoteRows, (row) => row.status, (row) => row.quoteNumber) : quoteRows} grouped={grouped} onDownload={downloadQuote} />}
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

function ProductTable({ rows, grouped }: { rows: CpqProduct[]; grouped: boolean }) {
  let previousGroup = "";
  return <div className="table-wrap"><table className="data-table cpq-table">
    <thead><tr><th>Code</th><th>Product</th><th>Family</th><th>Category</th><th>UOM</th><th>Active prices</th><th>Status</th></tr></thead>
    <tbody>
      {rows.map((row) => {
        const group = row.category ?? "Unclassified";
        const showGroup = grouped && group !== previousGroup;
        previousGroup = group;
        return <Fragment key={row.id}>
          {showGroup && <tr className="group-row"><th colSpan={7}>{group}</th></tr>}
          <tr>
            <td className="mono">{row.code}</td><td>{row.name}</td><td>{row.productFamily ?? "-"}</td><td>{row.category ?? "-"}</td><td>{row.unitOfMeasure}</td><td>{row.activePriceCount}</td>
            <td><span className={`chip ${row.active ? "chip-open" : "chip-cancelled"}`}>{row.active ? "ACTIVE" : "INACTIVE"}</span>{row.subscription && <span className="chip cpq-mini">SUBSCRIPTION</span>}</td>
          </tr>
        </Fragment>;
      })}
      {rows.length === 0 && <tr><td colSpan={7} className="empty-note">No products match the current query.</td></tr>}
    </tbody>
  </table></div>;
}

function PriceBookTable({ rows, grouped }: { rows: CpqPriceBook[]; grouped: boolean }) {
  let previousGroup = "";
  return <div className="table-wrap"><table className="data-table cpq-table">
    <thead><tr><th>Code</th><th>Name</th><th>Currency</th><th>Segment</th><th>Version</th><th>Entries</th><th>Status</th></tr></thead>
    <tbody>
      {rows.map((row) => {
        const showGroup = grouped && row.status !== previousGroup;
        previousGroup = row.status;
        return <Fragment key={row.id}>
          {showGroup && <tr className="group-row"><th colSpan={7}>{row.status}</th></tr>}
          <tr>
            <td className="mono">{row.code}</td><td>{row.name}{row.defaultBook && <span className="chip cpq-mini">DEFAULT</span>}</td><td>{row.currencyCode}</td><td>{row.customerSegment ?? row.businessUnitCode ?? "All"}</td><td>v{row.versionNumber}</td><td>{row.entryCount}</td>
            <td><span className={`chip chip-${row.status.toLowerCase()}`}>{row.status}</span><small>{row.activatedAt ? `Activated ${formatDate(row.activatedAt)}` : "Not activated"}</small></td>
          </tr>
        </Fragment>;
      })}
      {rows.length === 0 && <tr><td colSpan={7} className="empty-note">No price books match the current query.</td></tr>}
    </tbody>
  </table></div>;
}

function QuoteTable({ rows, grouped, onDownload }: { rows: CpqQuote[]; grouped: boolean; onDownload: (quote: CpqQuote, format: "PDF" | "DOCX" | "XLSX") => void }) {
  let previousGroup = "";
  return <div className="table-wrap"><table className="data-table cpq-table">
    <thead><tr><th>Quote</th><th>Account</th><th>Opportunity</th><th>Owner</th><th>Total</th><th>Margin</th><th>Status</th><th>Docs</th></tr></thead>
    <tbody>
      {rows.map((row) => {
        const showGroup = grouped && row.status !== previousGroup;
        previousGroup = row.status;
        return <Fragment key={row.id}>
          {showGroup && <tr className="group-row"><th colSpan={8}>{row.status}</th></tr>}
          <tr>
            <td><span className="mono">{row.quoteNumber}</span><small>{row.name} - v{row.versionNumber}</small></td>
            <td>{row.accountName}</td><td>{row.opportunityName ?? "-"}</td><td>{row.ownerName ?? "-"}</td>
            <td>{formatMoney(row.grandTotal)}<small>Discount {formatMoney(row.discountTotal)}</small></td>
            <td>{row.marginPct == null ? "-" : `${row.marginPct.toFixed(1)}%`}</td>
            <td><span className={`chip chip-${row.status.toLowerCase()}`}>{row.status}</span><small>{row.approvalStatus} - expires {formatDate(row.expiresAt)}</small></td>
            <td className="quote-doc-actions"><button className="link-btn" onClick={() => onDownload(row, "PDF")}>PDF</button><button className="link-btn" onClick={() => onDownload(row, "DOCX")}>Word</button><button className="link-btn" onClick={() => onDownload(row, "XLSX")}>Excel</button></td>
          </tr>
        </Fragment>;
      })}
      {rows.length === 0 && <tr><td colSpan={8} className="empty-note">No quotes match the current query.</td></tr>}
    </tbody>
  </table></div>;
}

function sortedBy<T>(rows: T[], group: (row: T) => string, label: (row: T) => string): T[] {
  return [...rows].sort((a, b) => group(a).localeCompare(group(b)) || label(a).localeCompare(label(b)));
}

function cpqExportRows(section: CpqSection, products: CpqProduct[], priceBooks: CpqPriceBook[], quotes: CpqQuote[]) {
  if (section === "products") return products.map((row) => ({
    code: row.code,
    name: row.name,
    family: row.productFamily ?? "",
    category: row.category ?? "",
    unitOfMeasure: row.unitOfMeasure,
    activePrices: row.activePriceCount,
    status: row.active ? "ACTIVE" : "INACTIVE",
  }));
  if (section === "price-books") return priceBooks.map((row) => ({
    code: row.code,
    name: row.name,
    currency: row.currencyCode,
    segment: row.customerSegment ?? row.businessUnitCode ?? "All",
    version: row.versionNumber,
    entries: row.entryCount,
    status: row.status,
  }));
  return quotes.map((row) => ({
    quoteNumber: row.quoteNumber,
    name: row.name,
    account: row.accountName,
    opportunity: row.opportunityName ?? "",
    owner: row.ownerName ?? "",
    grandTotal: row.grandTotal,
    marginPct: row.marginPct ?? "",
    status: row.status,
    approvalStatus: row.approvalStatus,
  }));
}

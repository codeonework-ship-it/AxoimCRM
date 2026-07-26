import { Fragment, useState } from "react";
import { NavLink } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { api, isUnreachable, type CpqPriceBook, type CpqProduct, type CpqQuote } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataGridToolbar, saveDownloadedFile } from "../components/DataGridToolbar";
import { DataViewFrame } from "../components/DataViewFrame";
import { InfoTag } from "../components/InfoTag";
import { GridLoader } from "../components/Loaders";
import { useToasts } from "../components/Toasts";
import { formatDate, formatMoney } from "../lib/format";
import { filterRowsByColumns, groupLabelFor, selectedGroupColumns, sortByGroups, type GroupColumn } from "../lib/gridGrouping";
import { usePersistedGridState } from "../lib/usePersistedGridState";

type CpqSection = "products" | "price-books" | "quotes";

interface CpqPageProps {
  section: CpqSection;
}

const QUOTE_STATUSES = ["DRAFT", "IN_APPROVAL", "SENT", "ACCEPTED", "REJECTED", "EXPIRED", "ORDERED"];
const PRICE_BOOK_STATUSES = ["DRAFT", "ACTIVE", "ARCHIVED"];
const PRODUCT_GROUP_COLUMNS: GroupColumn<CpqProduct>[] = [
  { key: "code", label: "Code", value: (row) => row.code },
  { key: "name", label: "Product", value: (row) => row.name },
  { key: "category", label: "Category", value: (row) => row.category },
  { key: "family", label: "Family", value: (row) => row.productFamily },
  { key: "status", label: "Status", value: (row) => row.active ? "ACTIVE" : "INACTIVE" },
];
const PRICE_BOOK_GROUP_COLUMNS: GroupColumn<CpqPriceBook>[] = [
  { key: "code", label: "Code", value: (row) => row.code },
  { key: "name", label: "Name", value: (row) => row.name },
  { key: "status", label: "Status", value: (row) => row.status },
  { key: "currency", label: "Currency", value: (row) => row.currencyCode },
  { key: "segment", label: "Segment", value: (row) => row.customerSegment ?? row.businessUnitCode },
];
const QUOTE_GROUP_COLUMNS: GroupColumn<CpqQuote>[] = [
  { key: "quote", label: "Quote", value: (row) => row.quoteNumber },
  { key: "name", label: "Name", value: (row) => row.name },
  { key: "status", label: "Status", value: (row) => row.status },
  { key: "account", label: "Account", value: (row) => row.accountName },
  { key: "owner", label: "Owner", value: (row) => row.ownerName },
  { key: "approval", label: "Approval", value: (row) => row.approvalStatus },
];

export function CpqPage({ section }: CpqPageProps) {
  const toasts = useToasts();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState("");
  const [groupColumns, setGroupColumns, columnFilters, setColumnFilters] = usePersistedGridState(`cpq-${section}`);

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
  const productRows = filterRowsByColumns(((section === "products" ? pageData?.items : []) ?? []) as CpqProduct[], PRODUCT_GROUP_COLUMNS, columnFilters);
  const priceBookRows = filterRowsByColumns(((section === "price-books" ? pageData?.items : []) ?? []) as CpqPriceBook[], PRICE_BOOK_GROUP_COLUMNS, columnFilters);
  const quoteRows = filterRowsByColumns(((section === "quotes" ? pageData?.items : []) ?? []) as CpqQuote[], QUOTE_GROUP_COLUMNS, columnFilters);
  const selectedProductGroups = selectedGroupColumns(PRODUCT_GROUP_COLUMNS, groupColumns);
  const selectedPriceBookGroups = selectedGroupColumns(PRICE_BOOK_GROUP_COLUMNS, groupColumns);
  const selectedQuoteGroups = selectedGroupColumns(QUOTE_GROUP_COLUMNS, groupColumns);
  const toolbarGroupColumns = section === "products" ? PRODUCT_GROUP_COLUMNS : section === "price-books" ? PRICE_BOOK_GROUP_COLUMNS : QUOTE_GROUP_COLUMNS;
  const toolbarGroupKeys = groupColumns.filter((key) => toolbarGroupColumns.some((column) => column.key === key));

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
      <label><span>Search <InfoTag text="Type a few words to find the product, price book, or quote you need." label="CPQ search help" /></span><input value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} placeholder={section === "quotes" ? "Quote, account, opportunity, owner" : "Code, name, family, segment"} /></label>
      {section === "products" && <label><span>Category <InfoTag text="Show only products in one category." label="Product category help" /></span><input value={filter} onChange={(event) => { setFilter(event.target.value); setPage(0); }} placeholder="Exact category" /></label>}
      {section === "price-books" && <label><span>Status <InfoTag text="Show price books by draft, active, or archived state." label="Price book status help" /></span><select value={filter} onChange={(event) => { setFilter(event.target.value); setPage(0); }}><option value="">All statuses</option>{PRICE_BOOK_STATUSES.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>}
      {section === "quotes" && <label><span>Status <InfoTag text="Show quotes by where they are in the quote-to-order process." label="Quote status help" /></span><select value={filter} onChange={(event) => { setFilter(event.target.value); setPage(0); }}><option value="">All statuses</option>{QUOTE_STATUSES.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>}
      <button className="btn btn-sm" onClick={resetFilters} disabled={!search && !filter}>Reset</button>
    </section>

    <DataViewFrame
      title={section === "products" ? "Product catalogue" : section === "price-books" ? "Price book register" : "Quote register"}
      actions={<DataGridToolbar
        gridName={section === "products" ? "Product catalogue" : section === "price-books" ? "Price book register" : "Quote register"}
        grouped={toolbarGroupKeys.length > 0}
        groupLabel={section === "products" ? "Category" : "Status"}
        onToggleGroup={() => setGroupColumns((value) => value.length > 0 ? [] : [section === "products" ? "category" : "status"])}
        groupColumns={toolbarGroupColumns.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={toolbarGroupKeys}
        onGroupColumnsChange={setGroupColumns}
        filterColumns={toolbarGroupColumns.map(({ key, label }) => ({ key, label }))}
        columnFilters={columnFilters}
        onColumnFiltersChange={setColumnFilters}
        auditEntityType={section === "products" ? "PRODUCT" : section === "price-books" ? "PRICE_BOOK" : "QUOTE"}
        exportFilename={`cpq-${section}`}
        exportRows={cpqExportRows(section, productRows, priceBookRows, quoteRows)}
        note="Current filtered page - vendor integrations pending"
      />}
    >
      {activeQ.isLoading && <GridLoader label="Reading governed CPQ records" rows={6} columns={6} />}
      {activeQ.isError && <p className="empty-note">CPQ records failed to load{activeQ.error instanceof Error ? `: ${activeQ.error.message}` : "."}</p>}
      {activeQ.isSuccess && section === "products" && <ProductTable rows={sortByGroups(productRows, selectedProductGroups, (row) => row.name)} groupColumns={selectedProductGroups} />}
      {activeQ.isSuccess && section === "price-books" && <PriceBookTable rows={sortByGroups(priceBookRows, selectedPriceBookGroups, (row) => row.name)} groupColumns={selectedPriceBookGroups} />}
      {activeQ.isSuccess && section === "quotes" && <QuoteTable rows={sortByGroups(quoteRows, selectedQuoteGroups, (row) => row.quoteNumber)} groupColumns={selectedQuoteGroups} onDownload={downloadQuote} />}
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

function ProductTable({ rows, groupColumns }: { rows: CpqProduct[]; groupColumns: GroupColumn<CpqProduct>[] }) {
  let previousGroup = "";
  return <div className="table-wrap"><table className="data-table cpq-table">
    <thead><tr><th>Code</th><th>Product</th><th>Family</th><th>Category</th><th>UOM</th><th>Active prices</th><th>Status</th></tr></thead>
    <tbody>
      {rows.map((row) => {
        const group = groupColumns.length > 0 ? groupLabelFor(row, groupColumns) : "";
        const showGroup = groupColumns.length > 0 && group !== previousGroup;
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

function PriceBookTable({ rows, groupColumns }: { rows: CpqPriceBook[]; groupColumns: GroupColumn<CpqPriceBook>[] }) {
  let previousGroup = "";
  return <div className="table-wrap"><table className="data-table cpq-table">
    <thead><tr><th>Code</th><th>Name</th><th>Currency</th><th>Segment</th><th>Version</th><th>Entries</th><th>Status</th></tr></thead>
    <tbody>
      {rows.map((row) => {
        const group = groupColumns.length > 0 ? groupLabelFor(row, groupColumns) : "";
        const showGroup = groupColumns.length > 0 && group !== previousGroup;
        previousGroup = group;
        return <Fragment key={row.id}>
          {showGroup && <tr className="group-row"><th colSpan={7}>{group}</th></tr>}
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

function QuoteTable({ rows, groupColumns, onDownload }: { rows: CpqQuote[]; groupColumns: GroupColumn<CpqQuote>[]; onDownload: (quote: CpqQuote, format: "PDF" | "DOCX" | "XLSX") => void }) {
  let previousGroup = "";
  return <div className="table-wrap"><table className="data-table cpq-table">
    <thead><tr><th>Quote</th><th>Account</th><th>Opportunity</th><th>Owner</th><th>Total</th><th>Margin</th><th>Status</th><th>Docs</th></tr></thead>
    <tbody>
      {rows.map((row) => {
        const group = groupColumns.length > 0 ? groupLabelFor(row, groupColumns) : "";
        const showGroup = groupColumns.length > 0 && group !== previousGroup;
        previousGroup = group;
        return <Fragment key={row.id}>
          {showGroup && <tr className="group-row"><th colSpan={8}>{group}</th></tr>}
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

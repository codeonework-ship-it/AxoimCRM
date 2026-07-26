import { Fragment, useEffect, useMemo, useState, type ReactNode } from "react";
import {
  createCurrentViewExport,
  copyGridSnapshot,
  recordCurrentViewExportAudit,
  saveDownloadedFile,
  type GridExportContext,
  type GridExportFormat,
  type GridExportRow,
} from "./DataGridToolbar";
import { AuditDrawer } from "./AuditDrawer";
import { GridFilterRow } from "./GridFilterRow";
import { GroupColumnPicker } from "./GroupColumnPicker";
import { InfoTag } from "./InfoTag";
import { useToasts } from "./Toasts";
import { usePersistedGridState } from "../lib/usePersistedGridState";

/**
 * One table component for every RBAC screen: per-column filtering, grouping and
 * sorting in one place.
 *
 * <p>Written once rather than per screen because nine tables hand-rolling their
 * own filter inputs is nine chances to disagree about what "contains" means,
 * nine keyboard behaviours to test, and nine places to fix a bug. It also keeps
 * the design system honest — every table here uses the same existing class
 * vocabulary (`.data-table`, `.group-row`, `.chip`, `.link-btn`), so all four
 * themes apply without a line of new CSS.
 *
 * <h2>Filtering is per column and typed</h2>
 * A single search box across a whole row is useless on a permission matrix,
 * where the interesting question is "which profiles can delete" rather than
 * "which rows mention delete". So each column declares its own filter kind:
 * free text for names and codes, a select built from the data's own distinct
 * values for enums, and a tri-state for booleans — "any / yes / no", because
 * a checkbox cannot express "I do not care", and a two-state filter silently
 * hides half the table the moment it is touched.
 *
 * <h2>Grouping collapses, and the header is a real control</h2>
 * Group headers are buttons with `aria-expanded`, not styled divs. A reviewer
 * scanning forty field permissions grouped by object needs to be able to fold
 * away the three objects they are not looking at, from the keyboard.
 *
 * <p>Filtering is applied before grouping, and the group count reports the
 * filtered count — a group header claiming 12 rows while showing 3 is worse
 * than no count at all.
 */

/** How a column may be filtered. `none` opts a column out entirely. */
export type ColumnFilterKind = "text" | "enum" | "boolean" | "none";

export type CellValue = string | number | boolean | null | undefined;

export interface Column<T> {
  /** Stable key; also the filter/group identity. */
  key: string;
  header: string;
  /** The comparable value: what filtering, grouping and sorting all operate on. */
  value: (row: T) => CellValue;
  /** Optional rich cell. Falls back to the string form of `value`. */
  render?: (row: T) => ReactNode;
  filter?: ColumnFilterKind;
  groupable?: boolean;
  sortable?: boolean;
  /** Extra cell class from the existing vocabulary, e.g. `num` or `mono`. */
  cellClass?: string;
  /** Rendered when the value is null/undefined/empty. */
  blank?: string;
}

interface DataTableProps<T> {
  /** Names the table for assistive technology and the group-by control. */
  name: string;
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  /** Trailing action cell. Omitted entirely when not supplied. */
  actions?: (row: T) => ReactNode;
  actionsHeader?: string;
  empty?: string;
  /** Column key to group by on first render. */
  initialGroupBy?: string;
  /** Rendered under the table, e.g. a caveat about what the rows mean. */
  note?: ReactNode;
}

type BooleanFilter = "any" | "yes" | "no";
const DEFAULT_PAGE_SIZE = 100;

function text(value: CellValue): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "boolean") return value ? "Yes" : "No";
  return String(value);
}

function slug(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "") || "data-table";
}

const TABLE_AUDIT_ENTITY_TYPES: Record<string, string> = {
  roles: "ROLE_NODE",
  profiles: "PROFILE",
  "permission sets": "PERMISSION_SET",
  "permission set groups": "PERMISSION_SET_GROUP",
  assignments: "APP_USER",
  "object permissions": "OBJECT_PERMISSION",
  "field permissions": "FIELD_PERMISSION",
  "org-wide defaults": "ORG_WIDE_DEFAULT",
  "sharing rules": "SHARING_RULE",
  "record teams": "RECORD_TEAM",
  territories: "TERRITORY",
  groups: "USER_GROUP",
  "segregation conflicts": "SOD_CONFLICT",
  "segregation findings": "SOD_FINDING",
  "controlled approvals": "CONTROLLED_ACTION",
  delegations: "APPROVAL_DELEGATION",
  "user activity": "USER_ACTIVITY",
  "security reviews": "SECURITY_REVIEW",
  "workflow gate findings": "WORKFLOW_GATE_STATUS",
};

function tableAuditEntityType(name: string): string {
  return TABLE_AUDIT_ENTITY_TYPES[name.trim().toLowerCase()] ?? slug(name).replace(/-/g, "_").toUpperCase();
}

export function DataTable<T>({
  name,
  columns,
  rows,
  rowKey,
  actions,
  actionsHeader = "Action",
  empty = "No rows.",
  initialGroupBy,
  note,
}: DataTableProps<T>) {
  const toasts = useToasts();
  const [groupBy, setGroupBy, filters, setFilters] = usePersistedGridState(`table-${name}`, { groupColumns: initialGroupBy ? [initialGroupBy] : [] });
  const [sort, setSort] = useState<{ key: string; direction: 1 | -1 } | null>(null);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [filtersOpen, setFiltersOpen] = useState(true);
  const [auditOpen, setAuditOpen] = useState(false);
  const [full, setFull] = useState(false);
  const [page, setPage] = useState(0);

  const columnCount = columns.length + (actions ? 1 : 0);
  const auditEntityType = tableAuditEntityType(name);

  /** Distinct values per enum column, taken from the data rather than a hardcoded list. */
  const enumOptions = useMemo(() => {
    const out: Record<string, string[]> = {};
    columns
      .filter((column) => column.filter === "enum")
      .forEach((column) => {
        const seen = new Set<string>();
        rows.forEach((row) => {
          const value = text(column.value(row));
          if (value) seen.add(value);
        });
        out[column.key] = [...seen].sort((a, b) => a.localeCompare(b));
      });
    return out;
  }, [columns, rows]);

  const filtered = useMemo(() => {
    const active = columns.filter((column) => {
      const raw = filters[column.key];
      return raw !== undefined && raw !== "" && raw !== "any";
    });
    if (active.length === 0) return rows;
    return rows.filter((row) =>
      active.every((column) => {
        const raw = filters[column.key];
        const value = column.value(row);
        if (column.filter === "boolean") {
          return (raw as BooleanFilter) === "yes" ? value === true : value !== true;
        }
        if (column.filter === "enum") return text(value) === raw;
        return text(value).toLowerCase().includes(raw.toLowerCase());
      }),
    );
  }, [columns, filters, rows]);

  const sorted = useMemo(() => {
    if (!sort) return filtered;
    const column = columns.find((candidate) => candidate.key === sort.key);
    if (!column) return filtered;
    return [...filtered].sort((a, b) => {
      const left = column.value(a);
      const right = column.value(b);
      if (typeof left === "number" && typeof right === "number") return (left - right) * sort.direction;
      return text(left).localeCompare(text(right)) * sort.direction;
    });
  }, [columns, filtered, sort]);

  const totalPages = sorted.length === 0 ? 0 : Math.ceil(sorted.length / DEFAULT_PAGE_SIZE);
  const pageRows = useMemo(
    () => sorted.slice(page * DEFAULT_PAGE_SIZE, (page + 1) * DEFAULT_PAGE_SIZE),
    [page, sorted],
  );

  useEffect(() => {
    if (totalPages > 0 && page >= totalPages) setPage(totalPages - 1);
    if (totalPages === 0 && page !== 0) setPage(0);
  }, [page, totalPages]);

  const groupColumns = useMemo(
    () => groupBy.map((key) => columns.find((column) => column.key === key)).filter((column): column is Column<T> => !!column),
    [columns, groupBy],
  );
  const groupColumn = {
    value: (row: T) => groupColumns
      .map((column) => `${column.header}: ${text(column.value(row)) || (column.blank ?? "—")}`)
      .join(" / "),
    blank: "Unclassified",
  };

  /** Ordered groups, each already filtered — so the count on the header is true. */
  const groups = useMemo(() => {
    if (groupColumns.length === 0) return null;
    const buckets = new Map<string, T[]>();
    pageRows.forEach((row) => {
      const key = text(groupColumn.value(row)) || (groupColumn.blank ?? "—");
      const bucket = buckets.get(key);
      if (bucket) bucket.push(row);
      else buckets.set(key, [row]);
    });
    return [...buckets.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [groupColumns, pageRows]);

  function toggleSort(key: string) {
    setPage(0);
    setSort((current) => {
      if (!current || current.key !== key) return { key, direction: 1 };
      if (current.direction === 1) return { key, direction: -1 };
      return null;
    });
  }

  function toggleGroup(key: string) {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  function setFilter(key: string, value: string) {
    setPage(0);
    setFilters((current) => ({ ...current, [key]: value }));
  }

  const activeFilterCount = columns.filter((column) => {
    const raw = filters[column.key];
    return raw !== undefined && raw !== "" && raw !== "any";
  }).length;
  const activeFilters = columns
    .map((column) => {
      const raw = filters[column.key];
      if (raw === undefined || raw === "" || raw === "any") return null;
      return {
        key: column.key,
        label: column.header,
        value: column.filter === "boolean" ? (raw === "yes" ? "Yes" : "No") : raw,
      };
    })
    .filter((filter): filter is { key: string; label: string; value: string } => !!filter);
  const sortColumn = sort ? columns.find((column) => column.key === sort.key) : undefined;

  async function copyTableView() {
    try {
      const result = await copyGridSnapshot(tableExportRows(), tableExportContext(), `${slug(name)}-table-view-snapshot`);
      if (result === "clipboard") {
        toasts.push("info", "Grid snapshot copied", "The image includes the current columns, rows, filters, grouping, sort and timestamp.");
      } else {
        toasts.push("info", "Grid snapshot downloaded", "This browser blocked image clipboard access, so the complete PNG snapshot was downloaded instead.");
      }
    } catch (error) {
      toasts.push("error", "Grid snapshot not created", error instanceof Error ? error.message : "The snapshot could not be created.");
    }
  }

  async function exportTableView(format: GridExportFormat) {
    try {
      const context = tableExportContext();
      const file = createCurrentViewExport(
        format,
        tableExportRows(),
        `${slug(name)}-table-view`,
        context,
      );
      await recordCurrentViewExportAudit(format, context);
      saveDownloadedFile(file);
      toasts.push("info", `Table ${format === "XLSX" ? "Excel" : format === "DOCX" ? "Word" : "PDF"} ready`, "The download reflects the current table filters, grouping and sort.");
    } catch (error) {
      toasts.push("error", "Table export failed", error instanceof Error ? error.message : "Download failed.");
    }
  }

  function tableExportRows(): GridExportRow[] {
    return sorted.map((row) => Object.fromEntries(columns.map((column) => {
      const value = text(column.value(row));
      return [column.header, value === "" ? (column.blank ?? "—") : value];
    })));
  }

  function tableExportContext(): GridExportContext {
    return {
      title: `${name} table view`,
      objectType: auditEntityType,
      generatedAt: new Date(),
      rowCount: sorted.length,
      groups: [
        ...groupColumns.map((column) => column.header),
        ...(sortColumn ? [`Sort: ${sortColumn.header} ${sort?.direction === 1 ? "ascending" : "descending"}`] : []),
        ...(collapsed.size ? [`Collapsed groups: ${collapsed.size}`] : []),
      ],
      filters: activeFilters.map((filter) => ({ label: filter.label, value: filter.value })),
    };
  }

  function tableViewScope(): string {
    const parts = [`${sorted.length}${sorted.length !== rows.length ? ` of ${rows.length}` : ""} rows`];
    if (activeFilters.length) parts.push(`${activeFilters.length} filters`);
    if (groupColumns.length) parts.push(`${groupColumns.length} groups`);
    if (sortColumn) parts.push(`sorted by ${sortColumn.header}`);
    if (collapsed.size) parts.push(`${collapsed.size} collapsed`);
    return parts.join(" · ");
  }

  function cell(column: Column<T>, row: T): ReactNode {
    if (column.render) return column.render(row);
    const value = column.value(row);
    const asText = text(value);
    return asText === "" ? (column.blank ?? "—") : asText;
  }

  function bodyRow(row: T) {
    return (
      <tr key={rowKey(row)}>
        {columns.map((column) => (
          <td key={column.key} className={column.cellClass}>
            {cell(column, row)}
          </td>
        ))}
        {actions && <td className="table-action">{actions(row)}</td>}
      </tr>
    );
  }

  return (
    <>
    <section className={`data-table-frame${full ? " data-table-frame-full" : ""}`} aria-label={`${name} table workspace`}>
      <header className="data-table-frame-head">
        <div>
          <span className="eyebrow">Table workspace</span>
          <h2 className="data-view-title">
            <span>{name}</span>
            <InfoTag
              text="Use this table to filter each column, group related rows, sort headers, open audit history, export the current view, or expand it for a larger review."
              label={`${name} table help`}
            />
          </h2>
        </div>
        <span className="grid-view-summary" aria-live="polite">{tableViewScope()}</span>
      </header>
      {/*
        The same Actions / Group rows every other data workspace uses. This
        table previously laid its controls out as one flex line with the group
        picker embedded among the buttons, which put its label at a different
        left edge from the identical picker on the pages beside it.
      */}
      <div className="data-grid-tools-stack">
        <div className="grid-tool-row" role="toolbar" aria-label={`${name} table tools`}>
          <div className="grid-tool-label"><span>Actions</span></div>
          <div className="grid-tool-controls">
            <button
              type="button"
              className="btn btn-sm"
              aria-expanded={filtersOpen}
              onClick={() => setFiltersOpen((open) => !open)}
            >
              {filtersOpen ? "Hide column filters" : "Column filters"}
              {activeFilterCount > 0 && <span className="chip">{activeFilterCount}</span>}
            </button>
            <span className="toolbar-divider" aria-hidden />
            <button type="button" className="btn btn-sm" onClick={() => void exportTableView("XLSX")}>
              Export Excel
            </button>
            <button type="button" className="btn btn-sm" onClick={() => void exportTableView("DOCX")}>
              Export Word
            </button>
            <button type="button" className="btn btn-sm" onClick={() => void exportTableView("PDF")}>
              Export PDF
            </button>
            <button type="button" className="btn btn-sm" onClick={() => void copyTableView()}>
              Copy view
            </button>
            <button type="button" className="btn btn-sm" onClick={() => setAuditOpen(true)}>
              Audit
            </button>
            <button type="button" className="btn btn-sm" onClick={() => setFull((value) => !value)}>
              {full ? "Restore view" : "Full size"}
            </button>
          </div>
          <div className="grid-tool-trailing">
            {(activeFilterCount > 0 || groupBy.length > 0 || sort) && (
              <button
                type="button"
                className="link-btn"
                onClick={() => {
                  setFilters({});
                  setGroupBy([]);
                  setSort(null);
                  setCollapsed(new Set());
                  setPage(0);
                }}
              >
                Reset view
              </button>
            )}
            <span className="count">
              {sorted.length}
              {sorted.length !== rows.length ? ` of ${rows.length}` : ""} rows
            </span>
          </div>
        </div>
        <GroupColumnPicker
          id={`${name}-group-by`}
          columns={columns.filter((column) => column.groupable !== false).map((column) => ({ key: column.key, label: column.header }))}
          selected={groupBy}
          onChange={(next) => {
            setPage(0);
            setGroupBy(next);
            setCollapsed(new Set());
          }}
          helpText="Tick the columns you want to group by. Rows will be grouped using the selected columns in the order you picked them."
        />
      </div>

      {activeFilters.length > 0 && (
        <div className="active-filter-chips data-table-filter-chips" aria-label={`${name} active filters`}>
          {activeFilters.map((filter) => (
            <button
              type="button"
              className="filter-chip"
              key={filter.key}
              title={`Remove ${filter.label} filter`}
              aria-label={`Remove ${filter.label} filter`}
              onClick={() => setFilter(filter.key, "")}
            >
              <span>{filter.label}</span>
              <strong>{filter.value}</strong>
              <em aria-hidden="true">×</em>
            </button>
          ))}
        </div>
      )}

      <div className="table-wrap">
        <table className="data-table" aria-label={name}>
          <thead>
            <tr>
              {columns.map((column) => {
                const sorting = sort?.key === column.key;
                const ariaSort = sorting ? (sort.direction === 1 ? "ascending" : "descending") : "none";
                return (
                  <th key={column.key} aria-sort={ariaSort} scope="col">
                    {column.sortable === false ? (
                      column.header
                    ) : (
                      <button
                        type="button"
                        className="link-btn"
                        onClick={() => toggleSort(column.key)}
                        aria-label={`Sort by ${column.header}`}
                      >
                        {column.header}
                        {sorting && <span aria-hidden="true">{sort.direction === 1 ? " ^" : " v"}</span>}
                      </button>
                    )}
                  </th>
                );
              })}
              {actions && <th className="table-action">{actionsHeader}</th>}
            </tr>
            {/*
              The same in-header filter row every other grid now uses. This
              table had its own copy inline; sharing it means "contains" is
              defined in exactly one place rather than once per table.
            */}
            {filtersOpen && (
              <GridFilterRow
                columns={columns.map((column) => ({
                  key: column.key,
                  label: column.header,
                  kind: column.filter ?? "text",
                  options: enumOptions[column.key] ?? [],
                }))}
                filters={filters}
                onChange={(next) => {
                  setPage(0);
                  setFilters(next);
                }}
                trailing={actions ? 1 : 0}
              />
            )}
          </thead>
          <tbody>
            {sorted.length === 0 && (
              <tr>
                <td colSpan={columnCount} className="empty-note">
                  {activeFilterCount > 0 ? "No rows match these column filters." : empty}
                </td>
              </tr>
            )}

            {groups
              ? groups.map(([value, bucket]) => {
                  const isCollapsed = collapsed.has(value);
                  return (
                    <Fragment key={value}>
                      <tr className="group-row">
                        <th colSpan={columnCount} scope="colgroup">
                          <button
                            type="button"
                            className="link-btn"
                            aria-expanded={!isCollapsed}
                            onClick={() => toggleGroup(value)}
                          >
                            <span aria-hidden="true">{isCollapsed ? "+ " : "- "}</span>
                            {value}
                            <span className="chip">{bucket.length}</span>
                          </button>
                        </th>
                      </tr>
                      {!isCollapsed && bucket.map((row) => bodyRow(row))}
                    </Fragment>
                  );
                })
              : pageRows.map((row) => bodyRow(row))}
          </tbody>
        </table>
      </div>
      <footer className="page-controls" aria-label={`${name} pagination`}>
        <span>Showing {pageRows.length} of {sorted.length} records - {DEFAULT_PAGE_SIZE} rows per page</span>
        <div>
          <button type="button" className="btn btn-sm" disabled={page === 0}
            onClick={() => setPage((value) => Math.max(0, value - 1))}>Previous</button>
          <strong>Page {totalPages === 0 ? 0 : page + 1} of {totalPages}</strong>
          <button type="button" className="btn btn-sm" disabled={page + 1 >= totalPages}
            onClick={() => setPage((value) => value + 1)}>Next</button>
        </div>
      </footer>
      {note && <p className="loading-note">{note}</p>}
    </section>
    <AuditDrawer
      open={auditOpen}
      entityType={auditEntityType}
      title={`${name} audit`}
      emptyLabel="No audited actions for this table yet."
      onClose={() => setAuditOpen(false)}
    />
    </>
  );
}

/** Shared cell renderer: a boolean as a chip, so a permission matrix scans. */
export function BoolChip({ value, yes = "Yes", no = "No" }: { value: boolean; yes?: string; no?: string }) {
  return <span className="chip">{value ? yes : no}</span>;
}

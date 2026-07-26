import { type ReactNode } from "react";

/**
 * Per-column filters that live inside the grid header.
 *
 * <p>This replaces the standalone "Column search" band that used to sit above
 * each grid. The band could never be right: its five equal-width boxes had no
 * relationship to the table's actual column widths, so the input you typed in
 * was rarely above the column it filtered. A filter belongs to its column, and
 * the only way to guarantee it stays with that column at every viewport width
 * is to put it in the same `<th>` the column header occupies. The table's own
 * layout algorithm then keeps them aligned for free — there is no width to
 * synchronise, because there is only one width.
 *
 * <p>Rendered as a second `<tr>` in `<thead>`, so it must be placed there and
 * nowhere else. It participates in the sticky header, scrolls horizontally with
 * the columns it belongs to, and is announced as part of the column it sits in.
 *
 * <h2>Filter kinds</h2>
 * A free-text box is wrong for a column with six possible values and useless
 * for a boolean. `text` is the default; `enum` renders a select over the
 * distinct values actually present in the data; `boolean` renders any/yes/no,
 * because a checkbox cannot express "I do not care" and a two-state control
 * silently hides half the rows the moment it is touched. `none` opts a column
 * out and renders an empty cell so the column count still lines up.
 */

export type GridFilterKind = "text" | "enum" | "boolean" | "none";

export interface GridFilterColumn {
  key: string;
  label: string;
  kind?: GridFilterKind;
  /** Options for `enum` columns. Usually the distinct values in the data. */
  options?: string[];
}

interface GridFilterRowProps {
  columns: GridFilterColumn[];
  filters: Record<string, string>;
  onChange: (next: Record<string, string>) => void;
  /**
   * Empty header cells to append, one per non-data column the table renders
   * after its data columns (a trailing action column, typically). Without this
   * the filter row is short and every cell after the gap lands under the wrong
   * column.
   */
  trailing?: number;
  /** Extra leading empty cells, for a selection or expander column. */
  leading?: number;
}

export function GridFilterRow({
  columns,
  filters,
  onChange,
  trailing = 0,
  leading = 0,
}: GridFilterRowProps) {
  if (columns.length === 0) return null;

  function setFilter(key: string, value: string) {
    const next = { ...filters };
    // An empty box is the absence of a filter, not a filter for "". Deleting the
    // key rather than storing "" keeps the active-filter count honest and stops
    // an export claiming a filter that constrains nothing.
    if (value === "" || value === "any") delete next[key];
    else next[key] = value;
    onChange(next);
  }

  const cells: ReactNode[] = [];
  for (let i = 0; i < leading; i += 1) {
    cells.push(<th key={`lead-${i}`} className="grid-filter-cell is-blank" />);
  }

  columns.forEach((column) => {
    const kind = column.kind ?? "text";
    cells.push(
      <th key={column.key} className="grid-filter-cell" scope="col">
        {kind === "none" ? null : kind === "boolean" ? (
          <select
            aria-label={`Filter ${column.label}`}
            title={`Show rows where ${column.label} is yes, no, or any value.`}
            value={filters[column.key] ?? "any"}
            onChange={(event) => setFilter(column.key, event.target.value)}
          >
            <option value="any">Any</option>
            <option value="yes">Yes</option>
            <option value="no">No</option>
          </select>
        ) : kind === "enum" ? (
          <select
            aria-label={`Filter ${column.label}`}
            title={`Show rows where ${column.label} matches one option.`}
            value={filters[column.key] ?? ""}
            onChange={(event) => setFilter(column.key, event.target.value)}
          >
            <option value="">Any</option>
            {(column.options ?? []).map((option) => (
              <option key={option} value={option}>{option}</option>
            ))}
          </select>
        ) : (
          <input
            type="search"
            aria-label={`Filter ${column.label}`}
            title={`Type text to show rows where ${column.label} contains it.`}
            /* "contains" states the match semantics. Repeating the column name
               here would duplicate the header directly above it. */
            placeholder="contains"
            value={filters[column.key] ?? ""}
            onChange={(event) => setFilter(column.key, event.target.value)}
          />
        )}
      </th>,
    );
  });

  for (let i = 0; i < trailing; i += 1) {
    cells.push(<th key={`trail-${i}`} className="grid-filter-cell is-blank" />);
  }

  return <tr className="grid-filter-row">{cells}</tr>;
}

/**
 * The same per-column filters for grids that are not tables.
 *
 * <p>Leads, Activities, CPQ and the report catalogue render cards, not rows, so
 * there is no `<thead>` to put a filter row in. They still need the filters to
 * belong to the grid rather than float in the toolbar above it, so this renders
 * as the grid's own header strip: placed as the first child inside the grid
 * container, sharing its frame, scrolling with it, and captioned with the same
 * column labels a table would show.
 *
 * <p>It is the same component and the same visual language as the table variant
 * on purpose — a user who learns where filters live on Accounts should not have
 * to learn again on Leads. What differs is only the element it has to be, and
 * that is a consequence of the grid being a card list.
 */
export function GridFilterHeader({
  columns,
  filters,
  onChange,
  label = "Filter columns",
}: Omit<GridFilterRowProps, "trailing" | "leading"> & { label?: string }) {
  if (columns.length === 0) return null;

  function setFilter(key: string, value: string) {
    const next = { ...filters };
    if (value === "" || value === "any") delete next[key];
    else next[key] = value;
    onChange(next);
  }

  const active = columns.filter((column) => (filters[column.key] ?? "").trim().length > 0).length;

  return (
    <div className="grid-filter-header" role="group" aria-label={label}>
      {columns.map((column) => {
        const kind = column.kind ?? "text";
        if (kind === "none") return null;
        return (
          <label className="grid-filter-head-cell" key={column.key}>
            <span>{column.label}</span>
            {kind === "boolean" ? (
              <select value={filters[column.key] ?? "any"} aria-label={`Filter ${column.label}`}
                onChange={(event) => setFilter(column.key, event.target.value)}>
                <option value="any">Any</option>
                <option value="yes">Yes</option>
                <option value="no">No</option>
              </select>
            ) : kind === "enum" ? (
              <select value={filters[column.key] ?? ""} aria-label={`Filter ${column.label}`}
                onChange={(event) => setFilter(column.key, event.target.value)}>
                <option value="">Any</option>
                {(column.options ?? []).map((option) => (
                  <option key={option} value={option}>{option}</option>
                ))}
              </select>
            ) : (
              <input type="search" value={filters[column.key] ?? ""} placeholder="contains"
                aria-label={`Filter ${column.label}`}
                onChange={(event) => setFilter(column.key, event.target.value)} />
            )}
          </label>
        );
      })}
      <button type="button" className="link-btn grid-filter-clear" disabled={active === 0}
        onClick={() => onChange({})}>
        Clear{active > 0 ? ` (${active})` : ""}
      </button>
    </div>
  );
}

/*
 * No predicate lives here on purpose. `filterRowsByColumns` in lib/gridGrouping
 * already defines what "contains" means and every page that renders these
 * controls already filters through it. Adding a second implementation next to
 * the inputs would be the same duplication this component exists to remove —
 * these collect the values, the existing helper decides what matches.
 */

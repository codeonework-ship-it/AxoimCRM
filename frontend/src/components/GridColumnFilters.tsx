import { InfoTag } from "./InfoTag";
import { type GroupColumnOption } from "./GroupColumnPicker";

interface GridColumnFiltersProps {
  id: string;
  columns: GroupColumnOption[];
  filters: Record<string, string>;
  onChange: (next: Record<string, string>) => void;
  helpText?: string;
}

export function GridColumnFilters({
  id,
  columns,
  filters,
  onChange,
  helpText = "Type under any column to search only that column. The visible grid and grouping stay aligned to these filters.",
}: GridColumnFiltersProps) {
  const activeFilters = columns
    .map((column) => ({ ...column, value: (filters[column.key] ?? "").trim() }))
    .filter((column) => column.value.length > 0);
  const active = activeFilters.length;

  function setFilter(key: string, value: string) {
    onChange({ ...filters, [key]: value });
  }

  function clearFilter(key: string) {
    const next = { ...filters };
    delete next[key];
    onChange(next);
  }

  function clear() {
    onChange({});
  }

  if (columns.length === 0) return null;

  return (
    <div className="grid-column-filter-strip" id={`${id}-column-filters`} aria-label="Column search filters">
      <div className="grid-column-filter-title">
        <span>Column search</span>
        {active > 0 && <em>{active} active</em>}
        <InfoTag text={helpText} label="Column search help" />
      </div>
      <div className="grid-column-filter-fields">
        {columns.map((column) => (
          <label key={column.key} className="grid-column-filter-field">
            <span>{column.label}</span>
            <input
              type="search"
              value={filters[column.key] ?? ""}
              placeholder={`Search ${column.label}`}
              aria-label={`Search ${column.label}`}
              onChange={(event) => setFilter(column.key, event.target.value)}
            />
          </label>
        ))}
      </div>
      <button type="button" className="link-btn" disabled={active === 0} onClick={clear}>
        Clear
      </button>
      {active > 0 && (
        <div className="active-filter-chips" aria-label="Active column filters">
          {activeFilters.map((filter) => (
            <button
              type="button"
              className="filter-chip"
              key={filter.key}
              title={`Remove ${filter.label} filter`}
              aria-label={`Remove ${filter.label} filter`}
              onClick={() => clearFilter(filter.key)}
            >
              <span>{filter.label}</span>
              <strong>{filter.value}</strong>
              <em aria-hidden="true">×</em>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

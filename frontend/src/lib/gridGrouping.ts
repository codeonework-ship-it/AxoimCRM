export interface GroupColumn<T> {
  key: string;
  label: string;
  value: (row: T) => unknown;
}

export function selectedGroupColumns<T>(columns: GroupColumn<T>[], selected: string[]): GroupColumn<T>[] {
  return selected.map((key) => columns.find((column) => column.key === key)).filter((column): column is GroupColumn<T> => !!column);
}

export function groupLabelFor<T>(row: T, columns: GroupColumn<T>[]): string {
  return columns
    .map((column) => `${column.label}: ${formatGroupValue(column.value(row))}`)
    .join(" / ");
}

export function sortByGroups<T>(rows: T[], columns: GroupColumn<T>[], fallback: (row: T) => string): T[] {
  if (columns.length === 0) return [...rows].sort((a, b) => fallback(a).localeCompare(fallback(b)));
  return [...rows].sort((a, b) => {
    const groupCompare = groupLabelFor(a, columns).localeCompare(groupLabelFor(b, columns));
    return groupCompare || fallback(a).localeCompare(fallback(b));
  });
}

export function filterRowsByColumns<T>(rows: T[], columns: GroupColumn<T>[], filters: Record<string, string>): T[] {
  const active = columns
    .map((column) => ({ column, needle: (filters[column.key] ?? "").trim().toLowerCase() }))
    .filter((item) => item.needle.length > 0);
  if (active.length === 0) return rows;
  return rows.filter((row) => active.every(({ column, needle }) =>
    formatGroupValue(column.value(row)).toLowerCase().includes(needle),
  ));
}

export function formatGroupValue(value: unknown): string {
  if (value === null || value === undefined || value === "") return "Unclassified";
  if (typeof value === "boolean") return value ? "Yes" : "No";
  return String(value);
}

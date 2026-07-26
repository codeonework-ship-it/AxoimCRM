import { useMemo } from "react";
import { InfoTag } from "./InfoTag";

export interface GroupColumnOption {
  key: string;
  label: string;
}

interface GroupColumnPickerProps {
  id: string;
  columns: GroupColumnOption[];
  selected: string[];
  onChange: (next: string[]) => void;
  disabled?: boolean;
  helpText?: string;
}

export function GroupColumnPicker({
  id,
  columns,
  selected,
  onChange,
  disabled = false,
  helpText = "Choose one or more columns. Matching rows will be grouped in the same order.",
}: GroupColumnPickerProps) {
  const selectedSet = useMemo(() => new Set(selected), [selected]);
  const enabled = !disabled && columns.length > 0;

  function toggleColumn(key: string) {
    if (selectedSet.has(key)) onChange(selected.filter((item) => item !== key));
    else onChange([...selected, key]);
  }

  return (
    <div className="group-column-picker" id={`${id}-group-columns`} role="group" aria-label="Choose grid group columns">
      <div className="group-column-help">
        <span>Group</span>
        <InfoTag text={helpText} label="Group columns help" />
      </div>
      <div className="group-column-options">
        {columns.map((column) => (
          <label className={`group-column-option${selectedSet.has(column.key) ? " is-selected" : ""}`} key={column.key}>
            <input
              type="checkbox"
              disabled={!enabled}
              checked={selectedSet.has(column.key)}
              onChange={() => toggleColumn(column.key)}
            />
            <span>{column.label}</span>
          </label>
        ))}
      </div>
      <button type="button" className="link-btn" disabled={selected.length === 0} onClick={() => onChange([])}>Clear</button>
    </div>
  );
}

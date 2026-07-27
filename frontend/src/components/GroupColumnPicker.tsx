import { useMemo } from "react";
import { useI18n } from "../i18n/I18nProvider";
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
  const { t, tp } = useI18n();
  const selectedSet = useMemo(() => new Set(selected), [selected]);
  const enabled = !disabled && columns.length > 0;

  function toggleColumn(key: string) {
    if (selectedSet.has(key)) onChange(selected.filter((item) => item !== key));
    else onChange([...selected, key]);
  }

  return (
    <div className="grid-tool-row group-column-picker" id={`${id}-group-columns`} role="group" aria-label={t("ui.grid.chooseGroupColumns", "Choose grid group columns")}>
      <div className="grid-tool-label">
        <span>{t("ui.grid.group", "Group")}</span>
        <InfoTag text={tp(helpText)} label={tp("Group columns help")} />
      </div>
      <div className="grid-tool-controls group-column-options">
        {columns.map((column) => (
          <label className={`group-column-option${selectedSet.has(column.key) ? " is-selected" : ""}`} key={column.key}>
            <input
              type="checkbox"
              disabled={!enabled}
              checked={selectedSet.has(column.key)}
              onChange={() => toggleColumn(column.key)}
            />
            <span>{tp(column.label)}</span>
          </label>
        ))}
      </div>
      <div className="grid-tool-trailing">
        <button type="button" className="link-btn" disabled={selected.length === 0} onClick={() => onChange([])}>{t("ui.common.clear", "Clear")}</button>
      </div>
    </div>
  );
}

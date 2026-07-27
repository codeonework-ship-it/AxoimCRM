import { useState, type ReactNode } from "react";
import { InfoTag, screenInfo } from "./InfoTag";
import { DatabaseIcon } from "./icons";
import { GridDataLoadedScope, useGridDataLoad } from "./PageDataGate";
import { useT } from "../i18n/I18nProvider";

interface DataViewFrameProps {
  title: string;
  children: ReactNode;
  actions?: ReactNode;
}

/**
 * The header band every data workspace shares.
 *
 * <p>Laid out as two stacked rows rather than one flex line, and the reason is
 * the whole point of this component. Previously the title and the entire tool
 * cluster competed for one row: the toolbar wrapped to four or five lines, the
 * title was squeezed into whatever was left and wrapped mid-phrase, and because
 * every wrapped line was an independent flex line, no two section labels,
 * control blocks or trailing actions shared a left or right edge. The result
 * read as misalignment on every screen at once, because every screen uses this.
 *
 * <p>Row one is identity: it owns the full width, so a title never wraps to make
 * room for a button. Row two is the tool band, also full width, which lets the
 * group and column-search rows inside it line up on the shared label gutter
 * (`--grid-label-gutter`) instead of starting wherever the previous line ended.
 *
 * <p>Full size sits in row one, not with the grid tools. It changes how you are
 * looking at the data; grouping, searching and exporting change what the data
 * is. Keeping the view control away from the data controls is what stops it
 * being stranded alone on a wrap line.
 */
export function DataViewFrame({ title, children, actions }: DataViewFrameProps) {
  const [full, setFull] = useState(false);
  const t = useT();
  const grid = useGridDataLoad(title);
  return (
    <section className={`data-view-frame${full ? " data-view-full" : ""}`} aria-label={title}>
      <header className="data-view-head">
        <div className="data-view-identity">
          <div className="data-view-heading">
            <span className="eyebrow">Data workspace</span>
            <h2 className="data-view-title">
              <span className="data-view-title-text">{title}</span>
              <InfoTag text={screenInfo(title)} label={`${title} help`} />
            </h2>
          </div>
          <div className="data-view-view-controls">
            <button className="btn btn-sm" onClick={() => setFull((value) => !value)}>
              {full ? "Restore view" : "Full size"}
            </button>
          </div>
        </div>
        {grid.loaded && actions && <div className="data-view-actions">{actions}</div>}
      </header>
      <div className="data-view-body">
        {grid.loaded ? (
          <GridDataLoadedScope>{children}</GridDataLoadedScope>
        ) : (
          <div className="grid-data-gate" role="status" aria-live="polite">
            <span className="grid-data-gate-icon" aria-hidden="true"><DatabaseIcon /></span>
            <div>
              <strong>{t("ui.load.gridTitle", "Grid Data Is Ready On Demand")}</strong>
              <p>{t("ui.load.gridDescription", "Load the first 100 tenant-scoped rows. Search, filters and pagination continue to run on the server.")}</p>
            </div>
            <button className="btn btn-primary btn-sm" onClick={grid.load}>
              {t("ui.load.gridButton", "Load Grid Data")}
            </button>
          </div>
        )}
      </div>
    </section>
  );
}

import { useState, type ReactNode } from "react";
import { InfoTag, screenInfo } from "./InfoTag";

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
  return (
    <section className={`data-view-frame${full ? " data-view-full" : ""}`} aria-label={title}>
      <header className="data-view-head">
        <div className="data-view-identity">
          <div className="data-view-heading">
            <span className="eyebrow">Data workspace</span>
            <h2 className="data-view-title">
              <span>{title}</span>
              <InfoTag text={screenInfo(title)} label={`${title} help`} />
            </h2>
          </div>
          <div className="data-view-view-controls">
            <button className="btn btn-sm" onClick={() => setFull((value) => !value)}>
              {full ? "Restore view" : "Full size"}
            </button>
          </div>
        </div>
        {actions && <div className="data-view-actions">{actions}</div>}
      </header>
      <div className="data-view-body">{children}</div>
    </section>
  );
}

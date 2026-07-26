import { useState, type ReactNode } from "react";
import { InfoTag, screenInfo } from "./InfoTag";

interface DataViewFrameProps {
  title: string;
  children: ReactNode;
  actions?: ReactNode;
}

export function DataViewFrame({ title, children, actions }: DataViewFrameProps) {
  const [full, setFull] = useState(false);
  return (
    <section className={`data-view-frame${full ? " data-view-full" : ""}`} aria-label={title}>
      <header className="data-view-head">
        <div>
          <span className="eyebrow">Data workspace</span>
          <h2 className="data-view-title">
            <span>{title}</span>
            <InfoTag text={screenInfo(title)} label={`${title} help`} />
          </h2>
        </div>
        <div className="data-view-actions">
          {actions}
          <button className="btn btn-sm" onClick={() => setFull((value) => !value)}>
            {full ? "Restore view" : "Full size"}
          </button>
        </div>
      </header>
      <div className="data-view-body">{children}</div>
    </section>
  );
}

import { useState, type ReactNode } from "react";
import { useLocation } from "react-router-dom";
import { DatabaseIcon } from "./icons";
import { InfoTag } from "./InfoTag";
import { useT } from "../i18n/I18nProvider";

/**
 * Structural lazy-loading boundary for every authenticated route.
 *
 * Children are not mounted until the operator asks for the screen data. This
 * is deliberately stronger than hiding a spinner: React Query hooks, summary
 * calls and tab-level requests below the boundary cannot execute early.
 * Loaded routes remain mounted-on-demand for the browser session so Back does
 * not turn normal navigation into repeated confirmation work.
 */
export function PageDataGate({ children }: { children: ReactNode }) {
  const location = useLocation();
  const t = useT();
  const routeKey = location.pathname;
  const [loadedRoutes, setLoadedRoutes] = useState<Set<string>>(() => new Set());
  const loaded = loadedRoutes.has(routeKey);

  if (loaded) return <>{children}</>;
  return <section className="page-data-gate" aria-labelledby="page-data-gate-title">
    <div className="page-data-gate-icon" aria-hidden="true"><DatabaseIcon /></div>
    <div className="page-data-gate-copy">
      <span className="eyebrow">{t("ui.load.eyebrow", "On-Demand Data")}</span>
      <h1 id="page-data-gate-title">{t("ui.load.title", "Load This Screen")}</h1>
      <p>{t("ui.load.description", "The page structure is ready. Load its tenant-scoped data only when you need it, keeping navigation fast even when the workspace contains millions of records.")}</p>
      <div className="page-data-gate-contract">
        <strong>{t("ui.load.whatLoads", "What Loads")}</strong>
        <span>{t("ui.load.contract", "The first 100 server-filtered rows, page summaries, and the active screen's supporting data.")}</span>
        <InfoTag text={t("ui.load.help", "Axiom never sends the entire million-row dataset to the browser. Search, filters and pagination remain on the server.")} label={t("ui.load.help", "On-demand loading help")} />
      </div>
      <button className="btn btn-primary page-data-load" onClick={() => setLoadedRoutes((current) => {
        const next = new Set(current); next.add(routeKey); return next;
      })}>{t("ui.load.button", "Load Screen Data")}</button>
    </div>
  </section>;
}

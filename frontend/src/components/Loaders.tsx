/**
 * CRM-domain loaders.
 *
 * A bare spinner tells a user nothing except "wait". These do three jobs a
 * spinner cannot:
 *   1. Name the work in the user's vocabulary ("Reading pipeline", not "Loading").
 *   2. Hold the shape of the content that is arriving, so the layout does not
 *      jump when data lands — the single biggest cause of mis-clicks on a slow
 *      connection.
 *   3. Stay honest: none of them show a percentage, because the API does not
 *      report progress and a fake progress bar is a lie.
 *
 * Styling lives in styles/motora.css (.skel, .loader-*).
 */

interface StatusProps {
  /** What the deck is doing, in domain language. Keep it short and uppercase-safe. */
  label: string;
  /** Optional second line for scope, e.g. "Meridian Fabrication Group". */
  detail?: string;
}

/** Status line with a pulsing indicator and an indeterminate rail. */
export function LoaderStatus({ label, detail }: StatusProps) {
  return (
    <div role="status" aria-live="polite">
      <span className="loader-status">{label}</span>
      {detail && <div className="loader-sub">{detail}</div>}
      <div className="loader-rail" aria-hidden="true">
        <i />
      </div>
    </div>
  );
}

/** Full-panel loader inside a HUD frame. Use when a whole workspace is loading. */
export function PanelLoader({ label, detail }: StatusProps) {
  return (
    <div className="panel loader-panel">
      <LoaderStatus label={label} detail={detail} />
    </div>
  );
}

/**
 * Skeleton rows for a table or grid. `columns` should match the real header
 * count so the rhythm lines up and nothing shifts on arrival.
 */
export function GridLoader({
  label = "Reading records",
  rows = 6,
  columns = 5,
}: {
  label?: string;
  rows?: number;
  columns?: number;
}) {
  const widths = ["w-md", "w-lg", "w-xs", "w-sm", "w-xs", "w-sm", "w-md"];
  return (
    <div className="panel" role="status" aria-live="polite" aria-busy="true">
      <div style={{ padding: "14px 16px", borderBottom: "1px solid var(--line)" }}>
        <span className="loader-status">{label}</span>
      </div>
      <div className="loader-rows skel">
        {Array.from({ length: rows }).map((_, r) => (
          <div className="loader-row" key={r}>
            {Array.from({ length: columns }).map((__, c) => (
              <span className={`skel-bar ${widths[(r + c) % widths.length]}`} key={c} />
            ))}
          </div>
        ))}
      </div>
      <span className="sr-only">{label}…</span>
    </div>
  );
}

/**
 * Skeleton deal cards for the pipeline board. Mirrors the real card anatomy —
 * name, account, amount, move control — so columns keep their width.
 */
export function BoardLoader({
  columns = 4,
  cardsPerColumn = 2,
  label = "Reading pipeline",
}: {
  columns?: number;
  cardsPerColumn?: number;
  label?: string;
}) {
  return (
    <div role="status" aria-live="polite" aria-busy="true">
      <div style={{ marginBottom: 14 }}>
        <LoaderStatus label={label} />
      </div>
      <div style={{ display: "flex", gap: 14, overflow: "hidden" }}>
        {Array.from({ length: columns }).map((_, col) => (
          <div key={col} style={{ flex: "0 0 250px", display: "grid", gap: 12 }}>
            <div className="skel" style={{ height: 44, clipPath: "var(--cut)" }} />
            <div className="loader-cards">
              {Array.from({ length: cardsPerColumn }).map((__, i) => (
                <div className="skel loader-card" key={i}>
                  <span className="skel-bar tall w-lg" />
                  <span className="skel-bar w-md" />
                  <span className="skel-bar w-sm" />
                  <span className="skel-bar w-lg" />
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
      <span className="sr-only">{label}…</span>
    </div>
  );
}

/** Inline loader for a control that is mid-flight (submitting, saving). */
export function InlineLoader({ label = "Working" }: { label?: string }) {
  return (
    <span className="loader-inline" role="status" aria-live="polite">
      {label}
    </span>
  );
}

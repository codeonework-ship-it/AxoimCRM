interface Props {
  onRetry: () => void;
  retrying?: boolean;
}

/** Full-page state shown when the backend cannot be reached at all. */
export function ApiUnreachable({ onRetry, retrying }: Props) {
  return (
    <div className="fullpage-state">
      <div className="fullpage-inner">
        <span className="label">Link down</span>
        <h1>API unreachable</h1>
        <p>
          Axiom could not reach the backend service. It may be starting up,
          stopped, or blocked by your network.
        </p>
        <p>
          <code>Expected at http://localhost:8080</code>
        </p>
        <button className="btn btn-primary" onClick={onRetry} disabled={retrying}>
          {retrying ? "Retrying…" : "Retry connection"}
        </button>
      </div>
    </div>
  );
}

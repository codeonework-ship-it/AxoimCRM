import { type WorkflowGateStatus } from "../api/client";
import { CloseIcon } from "./icons";

export function WorkflowGateDrawer({ result, onClose }: { result: WorkflowGateStatus | null; onClose: () => void }) {
  if (!result) return null;
  const blocked = result.gateStatus === "BLOCKED" || result.gateStatus === "UNKNOWN_STATE";
  return (
    <div className="drawer-scrim" role="presentation" onMouseDown={onClose}>
      <aside
        className="audit-drawer workflow-gate-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="Workflow gate status"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="drawer-head">
          <div>
            <span className="eyebrow">Workflow gates</span>
            <h2>{blocked ? "Missing steps found" : "Ready for workflow"}</h2>
          </div>
          <button className="icon-btn" onClick={onClose} aria-label="Close workflow gates"><CloseIcon /></button>
        </header>
        <div className="workflow-gate-summary">
          <span className={`chip ${blocked ? "chip-cancelled" : "chip-active"}`}>{result.gateStatus}</span>
          <p>{result.nextStep}</p>
          <small>
            {result.processCode ? `Process ${result.processCode}` : "No active workflow"} ·
            {result.currentState ? ` current state ${result.currentState}` : " no current state"} ·
            checked {new Date(result.evaluatedAt).toLocaleString()}
          </small>
        </div>
        <div className="audit-list">
          {result.issues.length === 0 ? (
            <article className="audit-event">
              <strong>No missing prerequisites</strong>
              <p>The record has all currently required workflow information.</p>
              <small>Continue with the next available process step.</small>
            </article>
          ) : result.issues.map((issue) => (
            <article className="audit-event workflow-gate-issue" key={issue.code}>
              <strong>{issue.gate}</strong>
              <p>{issue.message}</p>
              <small>{issue.nextAction}</small>
            </article>
          ))}
        </div>
      </aside>
    </div>
  );
}

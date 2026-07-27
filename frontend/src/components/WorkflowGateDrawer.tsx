import { type WorkflowGateStatus } from "../api/client";
import { useI18n } from "../i18n/I18nProvider";
import { CloseIcon } from "./icons";

export function WorkflowGateDrawer({ result, onClose }: { result: WorkflowGateStatus | null; onClose: () => void }) {
  const { t, tp, formatDate } = useI18n();
  if (!result) return null;
  const blocked = result.gateStatus === "BLOCKED" || result.gateStatus === "UNKNOWN_STATE";
  return (
    <div className="drawer-scrim" role="presentation" onMouseDown={onClose}>
      <aside
        className="audit-drawer workflow-gate-drawer"
        role="dialog"
        aria-modal="true"
        aria-label={tp("Workflow gate status")}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="drawer-head">
          <div>
            <span className="eyebrow">{t("ui.workflow.workflowGates", "Workflow gates")}</span>
            <h2>{blocked ? tp("Missing steps found") : tp("Ready for workflow")}</h2>
          </div>
          <button className="icon-btn" onClick={onClose} aria-label={`${t("ui.common.close", "Close")} ${t("ui.workflow.workflowGates", "Workflow gates")}`}><CloseIcon /></button>
        </header>
        <div className="workflow-gate-summary">
          <span className={`chip ${blocked ? "chip-cancelled" : "chip-active"}`}>{result.gateStatus}</span>
          <p>{tp(result.nextStep)}</p>
          <small>
            {result.processCode ? `${tp("Process")} ${result.processCode}` : tp("No active workflow")} ·
            {result.currentState ? ` ${tp("current state")} ${tp(result.currentState)}` : ` ${tp("no current state")}`} ·
            {tp("checked")} {formatDate(result.evaluatedAt, { dateStyle: "medium", timeStyle: "short" })}
          </small>
        </div>
        <div className="audit-list">
          {result.issues.length === 0 ? (
            <article className="audit-event">
              <strong>{tp("No missing prerequisites")}</strong>
              <p>{tp("The record has all currently required workflow information.")}</p>
              <small>{tp("Continue with the next available process step.")}</small>
            </article>
          ) : result.issues.map((issue) => (
            <article className="audit-event workflow-gate-issue" key={issue.code}>
              <strong>{tp(issue.gate)}</strong>
              <p>{tp(issue.message)}</p>
              <small>{tp(issue.nextAction)}</small>
            </article>
          ))}
        </div>
      </aside>
    </div>
  );
}

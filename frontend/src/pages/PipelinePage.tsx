import { useEffect, useRef, useState, type DragEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  api,
  ApiError,
  isUnreachable,
  type BoardStage,
  type WorkflowGateStatus,
} from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { useToasts } from "../components/Toasts";
import { formatDate, formatMoney, initials } from "../lib/format";
import { LockIcon } from "../components/icons";
import { BoardLoader } from "../components/Loaders";
import { useAppDialog } from "../components/AppDialog";

/** Move one opportunity between stages, immutably. Returns null if no-op. */
function moveCard(
  stages: BoardStage[],
  oppId: string,
  toStageId: string,
): BoardStage[] | null {
  const fromStage = stages.find((s) =>
    s.opportunities.some((o) => o.id === oppId),
  );
  if (!fromStage || fromStage.id === toStageId) return null;
  const card = fromStage.opportunities.find((o) => o.id === oppId)!;

  return stages.map((s) => {
    if (s.id === fromStage.id) {
      return { ...s, opportunities: s.opportunities.filter((o) => o.id !== oppId) };
    }
    if (s.id === toStageId) {
      return { ...s, opportunities: [...s.opportunities, card] };
    }
    return s;
  });
}

export function PipelinePage() {
  const queryClient = useQueryClient();
  const toasts = useToasts();
  const dialog = useAppDialog();

  const boardQ = useQuery({
    queryKey: ["pipeline", "board"],
    queryFn: api.pipelineBoard,
    retry: 1,
  });

  // Local working copy so drags feel instant and refusals can snap back.
  const [stages, setStages] = useState<BoardStage[]>([]);
  useEffect(() => {
    if (boardQ.data) setStages(boardQ.data.stages);
  }, [boardQ.data]);

  const [draggingId, setDraggingId] = useState<string | null>(null);
  const [dragOverStage, setDragOverStage] = useState<string | null>(null);
  const [refusedId, setRefusedId] = useState<string | null>(null);
  const [preflightId, setPreflightId] = useState<string | null>(null);
  const [gateResult, setGateResult] = useState<WorkflowGateStatus | null>(null);
  const refusedTimer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(refusedTimer.current), []);

  const moveMutation = useMutation({
    mutationFn: ({ oppId, stageId, reason }: { oppId: string; stageId: string; reason?: string }) =>
      api.moveOpportunity(oppId, stageId, reason),
  });

  const gateMutation = useMutation({
    mutationFn: (oppId: string) => api.workflowGate("OPPORTUNITY", oppId),
    onSuccess: (result) => {
      setGateResult(result);
      if (result.gateStatus === "BLOCKED" || result.gateStatus === "UNKNOWN_STATE") {
        toasts.push("warn", "Workflow gates found gaps", result.nextStep);
      } else {
        toasts.push("info", "Workflow gates checked", result.nextStep);
      }
    },
    onError: (error) => toasts.push("error", "Workflow gate check failed",
      error instanceof Error ? error.message : "Could not evaluate the process."),
  });

  async function attemptMove(oppId: string, toStageId: string) {
    const snapshot = stages;
    const next = moveCard(stages, oppId, toStageId);
    if (!next) return;
    setPreflightId(oppId);
    try {
      const gate = await api.previewOpportunityStage(oppId, toStageId);
      if (!gate.allowed) {
        toasts.push("error", "Stage requirements are missing", gate.refusal
          ?? gate.unsatisfied.map((issue) => `${issue.criterion}: ${issue.action}`).join(" "));
        setRefusedId(oppId);
        window.clearTimeout(refusedTimer.current);
        refusedTimer.current = window.setTimeout(() => setRefusedId(null), 600);
        return;
      }
      let reason: string | undefined;
      if (gate.reasonRequired) {
        const answer = await dialog.prompt({
          title: gate.transitionKind === "BACKWARD" ? "Reason For Moving Backward" : "Reason For Skipping Stages",
          message: `${gate.transitionKind === "BACKWARD" ? "Moving backward" : "Skipping stages"} requires a reason so the opportunity history stays complete.`,
          label: "Reason",
          defaultValue: "Customer process changed; reviewed by the opportunity owner.",
          required: true,
          confirmLabel: "Continue Move",
        });
        if (!answer?.trim()) return;
        reason = answer.trim();
      }
      setStages(next);
      moveMutation.mutate({ oppId, stageId: toStageId, reason }, {
        onSuccess: () => {
          void queryClient.invalidateQueries({ queryKey: ["pipeline", "board"] });
          void queryClient.invalidateQueries({ queryKey: ["dashboard", "summary"] });
          void queryClient.invalidateQueries({ queryKey: ["notifications"] });
        },
        onError: (err) => {
          setStages(snapshot);
          toasts.push("error", err instanceof ApiError && err.status === 409 ? "Stage gate" : "Move failed",
            err instanceof Error ? err.message : "The opportunity was not moved.");
        },
      });
    } catch (err) {
      toasts.push("error", "Stage preflight failed", err instanceof Error ? err.message : "The stage could not be checked.");
    } finally {
      setPreflightId(null);
    }
  }

  function handleDrop(e: DragEvent, toStageId: string) {
    e.preventDefault();
    setDragOverStage(null);
    const oppId = e.dataTransfer.getData("text/axiom-opp-id");
    if (oppId) void attemptMove(oppId, toStageId);
  }

  if (isUnreachable(boardQ.error)) {
    return (
      <ApiUnreachable
        onRetry={() => void boardQ.refetch()}
        retrying={boardQ.isFetching}
      />
    );
  }

  if (boardQ.isLoading) {
    return <BoardLoader label="Reading pipeline" columns={4} cardsPerColumn={2} />;
  }

  if (boardQ.isError) {
    return (
      <p className="empty-note">
        Board failed to load
        {boardQ.error instanceof Error ? `: ${boardQ.error.message}` : "."}
      </p>
    );
  }

  return (
    <>
      <div className="page-head">
        <div><span className="eyebrow">Opportunity control</span><h1>Pipeline</h1><p>Drag a card or use its Move control. Stage gates protect forecast integrity.</p></div>
        <span className="count">
          {stages.reduce((n, s) => n + s.opportunities.length, 0)} deals
        </span>
      </div>

      <div className="board">
        {[...stages]
          .sort((a, b) => a.sortOrder - b.sortOrder)
          .map((stage) => {
            const sum = stage.opportunities.reduce((n, o) => n + o.amount, 0);
            return (
              <section
                key={stage.id}
                className={`stage-col${dragOverStage === stage.id ? " drag-over" : ""}`}
                aria-label={`Stage ${stage.name}`}
                onDragOver={(e) => {
                  e.preventDefault();
                  e.dataTransfer.dropEffect = "move";
                  setDragOverStage(stage.id);
                }}
                onDragLeave={(e) => {
                  if (!e.currentTarget.contains(e.relatedTarget as Node)) {
                    setDragOverStage((cur) => (cur === stage.id ? null : cur));
                  }
                }}
                onDrop={(e) => handleDrop(e, stage.id)}
              >
                <header className="stage-head">
                  <span className="stage-name">{stage.name}</span>
                  {stage.requiresEconomicBuyer && (
                    <span
                      className="stage-lock"
                      title="Gated: requires a confirmed economic buyer"
                    >
                      <LockIcon />
                    </span>
                  )}
                  <span className="stage-stats">
                    <span className="stage-sum money">{formatMoney(sum)}</span>
                    {stage.opportunities.length}{" "}
                    {stage.opportunities.length === 1 ? "deal" : "deals"}
                  </span>
                </header>

                <div className="stage-body">
                  {stage.opportunities.map((opp) => (
                    <article
                      key={opp.id}
                      className={[
                        "deal",
                        opp.hasEconomicBuyer ? "" : "no-eb",
                        draggingId === opp.id ? "dragging" : "",
                        refusedId === opp.id ? "refused" : "",
                      ]
                        .filter(Boolean)
                        .join(" ")}
                      draggable
                      tabIndex={0}
                      onDragStart={(e) => {
                        e.dataTransfer.setData("text/axiom-opp-id", opp.id);
                        e.dataTransfer.effectAllowed = "move";
                        setDraggingId(opp.id);
                      }}
                      onDragEnd={() => setDraggingId(null)}
                      title={
                        opp.hasEconomicBuyer
                          ? undefined
                          : "No confirmed economic buyer"
                      }
                    >
                      <div className="deal-name">{opp.name}</div>
                      <div className="deal-account">{opp.accountName}</div>
                      <div className="deal-foot">
                        <span className="deal-amount money">
                          {formatMoney(opp.amount)}
                        </span>
                        <span className="deal-date">{formatDate(opp.closeDate)}</span>
                        <span className="owner-chip" title={opp.ownerName}>
                          {initials(opp.ownerName)}
                        </span>
                      </div>
                      <label className="deal-move">
                        <span>Move</span>
                        <select
                          value={stage.id}
                          aria-label={`Move ${opp.name} to another stage`}
                          disabled={moveMutation.isPending || preflightId === opp.id}
                          onChange={(event) => {
                            void attemptMove(opp.id, event.target.value);
                          }}
                        >
                          {[...stages].sort((a, b) => a.sortOrder - b.sortOrder).map((target) => (
                            <option key={target.id} value={target.id}>{target.name}</option>
                          ))}
                        </select>
                      </label>
                      <button
                        type="button"
                        className="btn btn-sm workflow-gate-btn"
                        disabled={gateMutation.isPending && gateMutation.variables === opp.id}
                        onClick={() => gateMutation.mutate(opp.id)}
                      >
                        {gateMutation.isPending && gateMutation.variables === opp.id ? "Checking..." : "Check gates"}
                      </button>
                    </article>
                  ))}
                  {stage.opportunities.length === 0 && (
                    <p className="empty-note" style={{ padding: "8px 4px" }}>
                      No deals
                    </p>
                  )}
                </div>
              </section>
            );
          })}
      </div>
      <WorkflowGateDrawer result={gateResult} onClose={() => setGateResult(null)} />
    </>
  );
}

function WorkflowGateDrawer({ result, onClose }: { result: WorkflowGateStatus | null; onClose: () => void }) {
  if (!result) return null;
  const blocked = result.gateStatus === "BLOCKED" || result.gateStatus === "UNKNOWN_STATE";
  return (
    <div className="drawer-scrim" role="presentation" onMouseDown={onClose}>
      <aside className="audit-drawer workflow-gate-drawer" role="dialog" aria-modal="true" aria-label="Workflow gate status" onMouseDown={(event) => event.stopPropagation()}>
        <header className="drawer-head">
          <div>
            <span className="eyebrow">Workflow gates</span>
            <h2>{blocked ? "Missing steps found" : "Ready for workflow"}</h2>
          </div>
          <button className="icon-btn" onClick={onClose} aria-label="Close workflow gates">×</button>
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

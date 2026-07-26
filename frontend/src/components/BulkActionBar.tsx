import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, ApiError, type BulkResult } from "../api/client";
import { CloseIcon } from "./icons";
import { InfoTag } from "./InfoTag";
import { InlineLoader } from "./Loaders";

/**
 * Mass edit and ownership transfer for a selection made in a grid.
 *
 * <h2>The field list comes from the server</h2>
 * Not a hardcoded array here. The server owns the allow-list — identity and
 * provenance columns are excluded because setting fifty contacts to the same email
 * is data loss, not a bulk edit — and a frontend copy of that list drifts the
 * moment it changes, surfacing to the user as a 400 on save. Fetching it means the
 * picker can only ever offer what will be accepted.
 *
 * <h2>The outcome report is the feature</h2>
 * A bulk edit that reports "42 updated" out of 50 selected is unauditable: the
 * operator cannot see which 42, what each held before, or why eight were refused,
 * so they cannot finish the job. Every row comes back with its own outcome and
 * reason, and this renders all of them — including the skips, which are the rows
 * that still need attention.
 */
interface BulkActionBarProps {
  /** Server-side object name: CONTACT, ACCOUNT, LEAD. */
  objectType: string;
  selectedIds: string[];
  onClearSelection: () => void;
  /** Called after any row actually changed, so the grid can refetch. */
  onApplied: () => void;
  /** Owner options for a transfer. Omit to hide the reassign path. */
  owners?: Array<{ id: string; name: string }>;
}

export function BulkActionBar({
  objectType, selectedIds, onClearSelection, onApplied, owners,
}: BulkActionBarProps) {
  const [mode, setMode] = useState<"idle" | "field" | "reassign">("idle");
  const [field, setField] = useState("");
  const [value, setValue] = useState("");
  const [ownerId, setOwnerId] = useState("");
  const [reason, setReason] = useState("");
  const [result, setResult] = useState<BulkResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const fieldsQ = useQuery({
    queryKey: ["bulk-editable-fields"],
    queryFn: api.bulkEditableFields,
    // The allow-list changes only on deploy, so refetching it per selection is
    // waste. staleTime keeps the picker instant after the first open.
    staleTime: 10 * 60 * 1000,
    retry: 1,
  });

  const editable = fieldsQ.data?.[objectType] ?? [];

  const fieldMutation = useMutation({
    mutationFn: () => api.bulkUpdateField(objectType, {
      recordIds: selectedIds,
      field,
      value: value.trim() === "" ? null : value.trim(),
      reason: reason.trim() || null,
    }),
    onSuccess: finish,
    onError: (err) => setError(describe(err)),
  });

  const reassignMutation = useMutation({
    mutationFn: () => api.bulkReassign(objectType, {
      recordIds: selectedIds,
      ownerId,
      reason: reason.trim() || null,
    }),
    onSuccess: finish,
    onError: (err) => setError(describe(err)),
  });

  function finish(outcome: BulkResult) {
    setResult(outcome);
    setError(null);
    // Refetch only when something actually changed. A batch that was entirely
    // skipped leaves the grid correct as it stands, and refetching would just
    // flicker it for no reason.
    if (outcome.succeeded > 0) onApplied();
  }

  function reset() {
    setMode("idle");
    setField("");
    setValue("");
    setOwnerId("");
    setReason("");
    setError(null);
  }

  /*
   * The outcome report must outlive the selection.
   *
   * A successful batch calls onApplied, which clears the selection so the grid
   * does not keep stale rows ticked — and that emptied selectedIds, hit this early
   * return, and unmounted the report before anyone could read it. The report IS
   * the feature: it is the only place that says which rows were skipped and why.
   * So the bar stays mounted while a result is on screen, and only the controls
   * are hidden.
   */
  if (selectedIds.length === 0 && !result) return null;
  const busy = fieldMutation.isPending || reassignMutation.isPending;
  const hasSelection = selectedIds.length > 0;

  return (
    <div className="grid-tool-row bulk-action-row" role="group" aria-label="Bulk actions">
      <div className="grid-tool-label">
        <span>Selected</span>
        <InfoTag
          text="Change one field across every selected record, or transfer them to a new owner. Each record is applied independently and reported separately, so a refusal on one does not undo the others."
          label="Bulk actions help"
        />
      </div>

      <div className="grid-tool-controls">
        {hasSelection && <strong className="bulk-count">
          {selectedIds.length} record{selectedIds.length === 1 ? "" : "s"}
        </strong>}

        {!hasSelection && result && (
          <span className="grid-view-summary">Selection applied — see the report</span>
        )}

        {hasSelection && mode === "idle" && <>
          <button type="button" className="btn btn-sm" onClick={() => setMode("field")}
            disabled={editable.length === 0}>
            Edit a field
          </button>
          {owners && owners.length > 0 && (
            <button type="button" className="btn btn-sm" onClick={() => setMode("reassign")}>
              Transfer owner
            </button>
          )}
          <button type="button" className="link-btn" onClick={onClearSelection}>
            Clear selection
          </button>
          {editable.length === 0 && fieldsQ.isSuccess && (
            <span className="grid-view-summary">No fields on {objectType} may be mass-edited</span>
          )}
        </>}

        {hasSelection && mode === "field" && (
          <form className="bulk-form" onSubmit={(e) => { e.preventDefault(); fieldMutation.mutate(); }}>
            <select value={field} onChange={(e) => setField(e.target.value)} required
              aria-label="Field to change">
              <option value="">Choose a field…</option>
              {editable.map((name) => (
                <option key={name} value={name}>{label(name)}</option>
              ))}
            </select>
            <input value={value} onChange={(e) => setValue(e.target.value)}
              placeholder="New value (blank clears it)" aria-label="New value" />
            <input value={reason} onChange={(e) => setReason(e.target.value)}
              placeholder="Reason (recorded)" aria-label="Reason" />
            <button type="submit" className="btn btn-sm btn-primary" disabled={busy || !field}>
              {busy ? <InlineLoader label="Applying" /> : `Apply to ${selectedIds.length}`}
            </button>
            <button type="button" className="btn btn-sm" onClick={reset}>Cancel</button>
          </form>
        )}

        {hasSelection && mode === "reassign" && (
          <form className="bulk-form" onSubmit={(e) => { e.preventDefault(); reassignMutation.mutate(); }}>
            <select value={ownerId} onChange={(e) => setOwnerId(e.target.value)} required
              aria-label="New owner">
              <option value="">Choose a new owner…</option>
              {(owners ?? []).map((owner) => (
                <option key={owner.id} value={owner.id}>{owner.name}</option>
              ))}
            </select>
            <input value={reason} onChange={(e) => setReason(e.target.value)}
              placeholder="Reason (recorded)" aria-label="Reason" />
            <button type="submit" className="btn btn-sm btn-primary" disabled={busy || !ownerId}>
              {busy ? <InlineLoader label="Transferring" /> : `Transfer ${selectedIds.length}`}
            </button>
            <button type="button" className="btn btn-sm" onClick={reset}>Cancel</button>
          </form>
        )}
      </div>

      <div className="grid-tool-trailing">
        {error && <span className="form-error" role="alert">{error}</span>}
      </div>

      {result && <BulkOutcomeReport result={result} onClose={() => { setResult(null); reset(); }} />}
    </div>
  );
}

/**
 * The per-row report.
 *
 * <p>Skips and failures are listed first because they are the rows that still
 * need a decision; the applied ones are evidence, not work. A collapsed summary
 * with a count would hide exactly the information the operator came for.
 */
function BulkOutcomeReport({ result, onClose }: { result: BulkResult; onClose: () => void }) {
  const ordered = [...result.rows].sort((a, b) => rank(a.outcome) - rank(b.outcome));
  return (
    <div className="record-scrim" role="presentation"
      onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="panel bulk-outcome" role="dialog" aria-modal="true" aria-labelledby="bulk-outcome-title">
        <header className="record-dialog-head">
          <div>
            <span className="eyebrow">{result.operation.replace(/_/g, " ").toLowerCase()}</span>
            <h2 id="bulk-outcome-title">
              {result.succeeded} of {result.total} record{result.total === 1 ? "" : "s"} changed
            </h2>
          </div>
          <button className="icon-btn" type="button" onClick={onClose} aria-label="Close outcome report">
            <CloseIcon />
          </button>
        </header>

        <div className="bulk-tallies">
          <div><strong>{result.succeeded}</strong><span>applied</span></div>
          <div><strong>{result.skipped}</strong><span>skipped</span></div>
          <div><strong>{result.failed}</strong><span>failed</span></div>
        </div>

        <p className="sub">{result.note}</p>

        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>Record</th><th>Outcome</th><th>Detail</th><th>Before</th><th>After</th></tr>
            </thead>
            <tbody>
              {ordered.map((row) => (
                <tr key={row.recordId}>
                  <td>{row.label ?? <span className="mono">{row.recordId.slice(0, 8)}</span>}</td>
                  <td><span className={`chip chip-${row.outcome.toLowerCase()}`}>{row.outcome}</span></td>
                  <td>{row.detail ?? "-"}</td>
                  <td>{row.beforeValue ?? "-"}</td>
                  <td>{row.afterValue ?? "-"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <footer className="record-form-actions">
          <button className="btn btn-primary" type="button" onClick={onClose}>Done</button>
        </footer>
      </div>
    </div>
  );
}

/** Problems first: the rows that still need a decision. */
function rank(outcome: string): number {
  return outcome === "FAILED" ? 0 : outcome === "SKIPPED" ? 1 : 2;
}

function label(field: string): string {
  return field.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/^./, (c) => c.toUpperCase());
}

function describe(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return error instanceof Error ? error.message : "The bulk operation failed.";
}

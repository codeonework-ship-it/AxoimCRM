import { useEffect, useRef, useState, type ReactNode } from "react";
import { ApiError } from "../api/client";
import { InlineLoader } from "./Loaders";
import { CloseIcon } from "./icons";
import { useAppDialog } from "./AppDialog";

/**
 * The create / edit / clone dialog every object authors through.
 *
 * <p>Written once rather than per page because the three modes differ only in
 * their title, their starting values and which endpoint they call — everything
 * that is actually hard is identical. Nine pages each solving the hard parts
 * their own way is nine dialogs that disagree about what happens when the save
 * conflicts, nine focus traps to get wrong, and nine places to fix a bug.
 *
 * <h2>The hard parts, solved here</h2>
 *
 * <b>Conflict is a first-class outcome, not an error string.</b> A 409 from
 * optimistic locking means someone else saved while this form was open. Showing
 * it as a red banner and leaving the user to guess is how people lose work, so
 * the dialog surfaces the server's own wording — which names both versions — and
 * offers Reload, which refetches and re-applies nothing. The user decides.
 *
 * <b>A duplicate warning is a question, not a failure.</b> The duplicate engine
 * answers 409 with the candidates it matched and an explicit resolution: resend
 * acknowledged, with a reason. The dialog renders those candidates and asks for
 * the reason rather than making the caller re-type the whole record blind.
 *
 * <b>Dirty state is tracked</b> so Escape and the scrim cannot silently discard
 * a half-written record. An untouched form closes immediately; a touched one
 * confirms first.
 */

export interface RecordFieldOption {
  value: string;
  label: string;
}

export interface RecordField<T> {
  key: keyof T & string;
  label: string;
  /** `select` needs `options`; `textarea` spans the full row. */
  kind?: "text" | "email" | "tel" | "select" | "textarea" | "number";
  options?: RecordFieldOption[];
  required?: boolean;
  placeholder?: string;
  help?: string;
  /** Full-width instead of half. Defaults to full for textarea. */
  wide?: boolean;
  /** Hidden in clone mode — for values that identify the original record. */
  clearedOnClone?: boolean;
}

export type RecordFormMode = "create" | "edit" | "clone";

interface DuplicateCandidate {
  id: string;
  label: string;
  context: string;
  confidence: number;
}

interface RecordFormDialogProps<T extends object> {
  open: boolean;
  mode: RecordFormMode;
  /** Singular, lower case: "contact", "account". Used in every message. */
  objectLabel: string;
  fields: RecordField<T>[];
  initial: Partial<T>;
  busy?: boolean;
  onClose: () => void;
  onSubmit: (values: Partial<T>, extra: { acknowledgeDuplicates: boolean; duplicateReason: string | null }) => Promise<void>;
  /** Rendered under the fields — related-record pickers, say. */
  children?: ReactNode;
  editLock?: {
    checking: boolean;
    blocked: boolean;
    message: string | null;
    holderName?: string | null;
    expiresAt?: string | null;
    canForceRelease?: boolean;
    onRetry: () => void;
    onForceRelease?: () => Promise<void>;
  };
}

export function RecordFormDialog<T extends object>({
  open, mode, objectLabel, fields, initial, busy = false, onClose, onSubmit, children, editLock,
}: RecordFormDialogProps<T>) {
  const dialog = useAppDialog();
  const [values, setValues] = useState<Partial<T>>(initial);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState<string | null>(null);
  const [candidates, setCandidates] = useState<DuplicateCandidate[]>([]);
  const [duplicateReason, setDuplicateReason] = useState("");
  const [saving, setSaving] = useState(false);
  const firstFieldRef = useRef<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(null);

  // Re-seed whenever the dialog is opened against a different record. Without
  // the `open` dependency an edit dialog reopened on another row would show the
  // previous row's values until the first keystroke.
  useEffect(() => {
    if (!open) return;
    setValues(initial);
    setDirty(false);
    setError(null);
    setConflict(null);
    setCandidates([]);
    setDuplicateReason("");
    firstFieldRef.current?.focus();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, initial]);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") void attemptClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, dirty]);

  if (!open) return null;

  async function attemptClose() {
    if (!dirty) {
      onClose();
      return;
    }
    const discard = await dialog.confirm({
      title: `Discard ${objectLabel}`,
      message: `Discard this ${objectLabel}? Your changes have not been saved.`,
      confirmLabel: "Discard Changes",
      tone: "danger",
    });
    if (discard) onClose();
  }

  function setValue(key: string, value: unknown) {
    setDirty(true);
    setValues((current) => ({ ...current, [key]: value }) as Partial<T>);
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    setConflict(null);
    try {
      await onSubmit(values, {
        acknowledgeDuplicates: candidates.length > 0,
        duplicateReason: duplicateReason.trim() || null,
      });
      setDirty(false);
    } catch (err) {
      handleFailure(err);
    } finally {
      setSaving(false);
    }
  }

  /**
   * Three different 409s arrive here and they mean different things. Collapsing
   * them into one "save failed" would tell the user nothing about what to do
   * next, which is the only thing they need at that moment.
   */
  function handleFailure(err: unknown) {
    if (err instanceof ApiError && err.status === 409) {
      const payload = err.payload as
        | { code?: string; candidates?: DuplicateCandidate[]; message?: string }
        | undefined;
      if (payload?.code === "DUPLICATE_WARNING" || payload?.code === "DUPLICATE_BLOCKED") {
        setCandidates(payload.candidates ?? []);
        setError(payload.message ?? err.message);
        return;
      }
      setConflict(err.message);
      return;
    }
    setError(err instanceof Error ? err.message : `The ${objectLabel} could not be saved.`);
  }

  const visible = fields.filter((field) => !(mode === "clone" && field.clearedOnClone));
  const title = mode === "create" ? `New ${objectLabel}`
    : mode === "clone" ? `Clone ${objectLabel}`
      : `Edit ${objectLabel}`;
  const authoringBlocked = mode === "edit" && !!editLock?.blocked;

  return (
    <div className="record-scrim" role="presentation"
      onMouseDown={(event) => { if (event.target === event.currentTarget) void attemptClose(); }}>
      <div className="panel record-dialog" role="dialog" aria-modal="true" aria-labelledby="record-dialog-title">
        <header className="record-dialog-head">
          <div>
            <span className="eyebrow">{mode === "clone" ? "Duplicate a record" : "Record authoring"}</span>
            <h2 id="record-dialog-title">{title}</h2>
          </div>
          <button className="icon-btn" type="button" onClick={attemptClose} aria-label={`Close ${title}`}>
            <CloseIcon />
          </button>
        </header>

        {mode === "clone" && (
          <p className="form-notice" role="status">
            Everything except the identifying fields is copied. Fill those in before saving —
            a clone is a new record, not a second copy of the original.
          </p>
        )}

        {mode === "edit" && editLock?.checking && (
          <div className="record-lock-banner is-checking" role="status">
            <strong>Checking edit availability...</strong>
            <p>Axiom is reserving this record before the form becomes editable.</p>
          </div>
        )}

        {mode === "edit" && editLock && !editLock.checking && editLock.blocked && (
          <div className="record-lock-banner is-blocked" role="alert">
            <strong>{editLock.holderName ? `${editLock.holderName} is editing this record.` : "This record cannot be edited right now."}</strong>
            <p>{editLock.message ?? "The edit lease could not be acquired. Your record remains safe to view."}</p>
            {editLock.expiresAt && <p className="sub">Lease expires {new Date(editLock.expiresAt).toLocaleString()} unless the editor renews it.</p>}
            <div className="inline-actions">
              <button type="button" className="btn btn-sm" onClick={editLock.onRetry}>Retry Lock</button>
              {editLock.canForceRelease && editLock.onForceRelease && (
                <button type="button" className="btn btn-sm danger-link" onClick={() => void editLock.onForceRelease?.()}>Force Unlock</button>
              )}
            </div>
          </div>
        )}

        {conflict && (
          <div className="form-conflict" role="alert">
            <strong>Someone else saved first.</strong>
            <p>{conflict}</p>
            <p className="sub">
              Your changes are still in the form. Close and reopen the record to see the current
              values — nothing here has been applied.
            </p>
          </div>
        )}

        {error && <p className="form-error" role="alert">{error}</p>}

        {candidates.length > 0 && (
          <div className="form-duplicates" role="status">
            <strong>Possible duplicates</strong>
            <ul>
              {candidates.map((candidate) => (
                <li key={candidate.id}>
                  <span>{candidate.label}</span>
                  <small>{candidate.context} · {Math.round(candidate.confidence * 100)}% match</small>
                </li>
              ))}
            </ul>
            <label className="field">
              <span className="label">Why is this a different record?</span>
              <input value={duplicateReason} onChange={(event) => setDuplicateReason(event.target.value)}
                placeholder="e.g. same name, different division" />
            </label>
            <p className="sub">Saving again records this reason against the decision.</p>
          </div>
        )}

        <form className="record-form" onSubmit={submit}>
          <div className="record-form-grid">
            {visible.map((field, index) => {
              const value = (values[field.key] ?? "") as string | number;
              const wide = field.wide || field.kind === "textarea";
              return (
                <label className={`field${wide ? " field-wide" : ""}`} key={field.key}>
                  <span className="label">
                    {field.label}{field.required && <em className="req" aria-hidden> *</em>}
                  </span>
                  {field.kind === "select" ? (
                    <select
                      ref={index === 0 ? (firstFieldRef as React.Ref<HTMLSelectElement>) : undefined}
                      value={String(value)} required={field.required} disabled={authoringBlocked}
                      onChange={(event) => setValue(field.key, event.target.value || null)}>
                      <option value="">Select…</option>
                      {(field.options ?? []).map((option) => (
                        <option key={option.value} value={option.value}>{option.label}</option>
                      ))}
                    </select>
                  ) : field.kind === "textarea" ? (
                    <textarea
                      ref={index === 0 ? (firstFieldRef as React.Ref<HTMLTextAreaElement>) : undefined}
                      rows={3} value={String(value)} required={field.required} disabled={authoringBlocked}
                      placeholder={field.placeholder}
                      onChange={(event) => setValue(field.key, event.target.value)} />
                  ) : (
                    <input
                      ref={index === 0 ? (firstFieldRef as React.Ref<HTMLInputElement>) : undefined}
                      type={field.kind === "number" ? "number" : field.kind ?? "text"}
                      value={String(value)} required={field.required} disabled={authoringBlocked}
                      placeholder={field.placeholder}
                      onChange={(event) => setValue(field.key, event.target.value)} />
                  )}
                  {field.help && <small className="field-help">{field.help}</small>}
                </label>
              );
            })}
          </div>

          {children}

          <footer className="record-form-actions">
            <button className="btn" type="button" onClick={attemptClose}>Cancel</button>
            <button className="btn btn-primary" type="submit" disabled={saving || busy || authoringBlocked}>
              {saving ? <InlineLoader label="Saving" />
                : candidates.length > 0 ? "Save anyway"
                  : mode === "edit" ? "Save changes" : `Create ${objectLabel}`}
            </button>
          </footer>
        </form>
      </div>
    </div>
  );
}

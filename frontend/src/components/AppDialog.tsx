import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type RefObject,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import { CloseIcon } from "./icons";
import { useI18n } from "../i18n/I18nProvider";

export type AppDialogTone = "neutral" | "danger";

interface DialogCopy {
  title?: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: AppDialogTone;
}

export interface AppConfirmOptions extends DialogCopy {}

export interface AppPromptOptions extends DialogCopy {
  label?: string;
  defaultValue?: string;
  placeholder?: string;
  required?: boolean;
  multiline?: boolean;
}

export interface AppAlertOptions extends Omit<DialogCopy, "cancelLabel"> {}

export interface DialogApi {
  confirm: (options: AppConfirmOptions | string) => Promise<boolean>;
  prompt: (options: AppPromptOptions | string, defaultValue?: string) => Promise<string | null>;
  alert: (options: AppAlertOptions | string) => Promise<void>;
}

type DialogRequest =
  | ({ kind: "confirm"; options: AppConfirmOptions } & { resolve: (value: boolean) => void })
  | ({ kind: "prompt"; options: AppPromptOptions } & { resolve: (value: string | null) => void })
  | ({ kind: "alert"; options: AppAlertOptions } & { resolve: () => void });

const DialogContext = createContext<DialogApi | null>(null);

function copyOf(value: DialogCopy | string): DialogCopy {
  return typeof value === "string" ? { message: value } : value;
}

/**
 * Application-owned replacement for native alert/confirm/prompt windows.
 *
 * Native browser popups are rendered by the operating system, so they cannot
 * inherit an Axiom theme, typography, spacing or accessibility behavior. This
 * provider keeps every decision dialog inside the document and queues requests
 * so multi-step workflows remain deterministic.
 */
export function AppDialogProvider({ children }: { children: ReactNode }) {
  const { t, tp } = useI18n();
  const [active, setActive] = useState<DialogRequest | null>(null);
  const [value, setValue] = useState("");
  const queue = useRef<DialogRequest[]>([]);
  const dialogRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement | HTMLTextAreaElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);

  const enqueue = useCallback((request: DialogRequest) => {
    setActive((current) => {
      if (current) {
        queue.current.push(request);
        return current;
      }
      return request;
    });
  }, []);

  const confirm = useCallback<DialogApi["confirm"]>((options) => new Promise<boolean>((resolve) => {
    enqueue({ kind: "confirm", options: copyOf(options), resolve });
  }), [enqueue]);

  const prompt = useCallback<DialogApi["prompt"]>((options, defaultValue) => new Promise<string | null>((resolve) => {
    const normalized = copyOf(options) as AppPromptOptions;
    enqueue({
      kind: "prompt",
      options: { ...normalized, defaultValue: normalized.defaultValue ?? defaultValue },
      resolve,
    });
  }), [enqueue]);

  const alert = useCallback<DialogApi["alert"]>((options) => new Promise<void>((resolve) => {
    enqueue({ kind: "alert", options: copyOf(options), resolve });
  }), [enqueue]);

  const advance = useCallback(() => {
    setActive(queue.current.shift() ?? null);
  }, []);

  const cancel = useCallback(() => {
    if (!active) return;
    if (active.kind === "confirm") active.resolve(false);
    else if (active.kind === "prompt") active.resolve(null);
    else active.resolve();
    advance();
  }, [active, advance]);

  const accept = useCallback(() => {
    if (!active) return;
    if (active.kind === "confirm") active.resolve(true);
    else if (active.kind === "prompt") active.resolve(value);
    else active.resolve();
    advance();
  }, [active, advance, value]);

  useEffect(() => {
    if (!active) return;
    setValue(active.kind === "prompt" ? active.options.defaultValue ?? "" : "");
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    window.setTimeout(() => (active.kind === "prompt" ? inputRef.current : confirmRef.current)?.focus(), 0);
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [active]);

  useEffect(() => {
    if (!active) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        cancel();
        return;
      }
      if (event.key !== "Tab" || !dialogRef.current) return;
      const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ));
      if (!focusable.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [active, cancel]);

  const api = useMemo<DialogApi>(() => ({ alert, confirm, prompt }), [alert, confirm, prompt]);
  const options = active?.options;
  const title = tp(options?.title ?? (active?.kind === "prompt" ? "Additional Information" : active?.kind === "alert" ? "Notice" : "Confirm Action"));
  const requiredPromptEmpty = active?.kind === "prompt" && active.options.required && !value.trim();

  return (
    <DialogContext.Provider value={api}>
      {children}
      {active && options && createPortal(
        <div className="app-dialog-scrim" role="presentation" onMouseDown={(event) => {
          if (event.target === event.currentTarget && active.kind !== "alert") cancel();
        }}>
          <div
            ref={dialogRef}
            className={`app-dialog app-dialog-${options.tone ?? "neutral"}`}
            role={active.kind === "alert" ? "alertdialog" : "dialog"}
            aria-modal="true"
            aria-labelledby="app-dialog-title"
            aria-describedby="app-dialog-message"
          >
            <header className="app-dialog-head">
              <div>
                <span className="eyebrow">{tp("Axiom Workspace")}</span>
                <h2 id="app-dialog-title">{title}</h2>
              </div>
              <button type="button" className="icon-btn app-dialog-close" aria-label={t("ui.dialog.close", "Close Dialog")} onClick={cancel}>
                <CloseIcon />
              </button>
            </header>

            <div className="app-dialog-body">
              <p id="app-dialog-message">{tp(options.message)}</p>
              {active.kind === "prompt" && (
                <label className="app-dialog-field">
                  <span>{tp(active.options.label ?? "Response")}{active.options.required && <em> {t("ui.common.required", "Required")}</em>}</span>
                  {active.options.multiline ? (
                    <textarea
                      ref={inputRef as RefObject<HTMLTextAreaElement>}
                      value={value}
                      placeholder={active.options.placeholder ? tp(active.options.placeholder) : undefined}
                      rows={4}
                      onChange={(event) => setValue(event.target.value)}
                    />
                  ) : (
                    <input
                      ref={inputRef as RefObject<HTMLInputElement>}
                      value={value}
                      placeholder={active.options.placeholder ? tp(active.options.placeholder) : undefined}
                      onChange={(event) => setValue(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter" && !requiredPromptEmpty) accept();
                      }}
                    />
                  )}
                </label>
              )}
            </div>

            <footer className="app-dialog-actions">
              {active.kind !== "alert" && (
                <button type="button" className="btn btn-secondary" onClick={cancel}>
                  {tp(active.options.cancelLabel ?? t("ui.common.cancel", "Cancel"))}
                </button>
              )}
              <button
                ref={confirmRef}
                type="button"
                className={`btn ${options.tone === "danger" ? "btn-danger" : "btn-primary"}`}
                disabled={requiredPromptEmpty}
                onClick={accept}
              >
                {tp(options.confirmLabel ?? (active.kind === "alert" ? t("ui.common.close", "Close") : "Continue"))}
              </button>
            </footer>
          </div>
        </div>,
        document.body,
      )}
    </DialogContext.Provider>
  );
}

export function useAppDialog(): DialogApi {
  const context = useContext(DialogContext);
  if (!context) throw new Error("useAppDialog must be used inside <AppDialogProvider>");
  return context;
}

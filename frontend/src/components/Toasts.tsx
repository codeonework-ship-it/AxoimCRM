import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { desktopNotify } from "../lib/desktop";

export type ToastKind = "info" | "warn" | "error";

interface Toast {
  id: number;
  kind: ToastKind;
  title: string;
  message: string;
}

interface ToastApi {
  push: (kind: ToastKind, title: string, message: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

const KIND_LABEL: Record<ToastKind, string> = {
  info: "OK",
  warn: "Attention",
  error: "Refused",
};

const TOAST_TTL_MS = 6000;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const push = useCallback(
    (kind: ToastKind, title: string, message: string) => {
      const id = nextId.current++;
      setToasts((prev) => [...prev.slice(-4), { id, kind, title, message }]);
      window.setTimeout(() => dismiss(id), TOAST_TTL_MS);
      // Mirror toast-worthy events to native OS notifications when the app
      // is running inside the Axiom desktop shell.
      desktopNotify(title, message);
    },
    [dismiss],
  );

  const apiValue = useMemo<ToastApi>(() => ({ push }), [push]);

  return (
    <ToastContext.Provider value={apiValue}>
      {children}
      <div className="toast-region" role="region" aria-label="Notifications">
        {toasts.map((t) => (
          <div
            key={t.id}
            className={`toast toast-${t.kind}`}
            role={t.kind === "error" ? "alert" : "status"}
          >
            <div>
              <span className="toast-kind">
                {KIND_LABEL[t.kind]} — {t.title}
              </span>
              {t.message}
            </div>
            <button
              className="toast-close"
              aria-label="Dismiss notification"
              onClick={() => dismiss(t.id)}
            >
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToasts(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToasts must be used inside <ToastProvider>");
  return ctx;
}

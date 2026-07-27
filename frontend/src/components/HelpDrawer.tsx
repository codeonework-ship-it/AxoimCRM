import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, type DocumentationSection } from "../api/client";
import { useI18n } from "../i18n/I18nProvider";
import { CloseIcon } from "./icons";

interface HelpDrawerProps {
  open: boolean;
  onClose: () => void;
}

/** Renders the tenant's governed documentation master; no manual content lives in the bundle. */
export function HelpDrawer({ open, onClose }: HelpDrawerProps) {
  const { locale, t } = useI18n();
  const panelRef = useRef<HTMLElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const [full, setFull] = useState(false);
  const [width, setWidth] = useState(480);
  const manualQ = useQuery({
    queryKey: ["documentation", "drawer", locale],
    queryFn: () => api.documentationDrawer(locale),
    enabled: open,
    staleTime: 5 * 60 * 1000,
    retry: 1,
  });

  useEffect(() => {
    if (!open) return;
    const opener = document.activeElement as HTMLElement | null;
    window.setTimeout(() => closeRef.current?.focus(), 0);
    return () => opener?.focus();
  }, [open]);

  useEffect(() => {
    if (!open || full) return;
    const resize = () => setWidth((value) => Math.min(value, Math.max(320, window.innerWidth - 24)));
    window.addEventListener("resize", resize);
    resize();
    return () => window.removeEventListener("resize", resize);
  }, [open, full]);

  function startResize(event: ReactPointerEvent<HTMLButtonElement>) {
    if (full) return;
    event.preventDefault();
    const move = (moveEvent: globalThis.PointerEvent) => {
      const next = Math.min(Math.max(window.innerWidth - moveEvent.clientX, 320), Math.max(320, window.innerWidth - 12));
      setWidth(next);
    };
    const stop = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", stop);
      window.removeEventListener("pointercancel", stop);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", stop);
    window.addEventListener("pointercancel", stop);
  }

  if (!open) return null;
  const title = manualQ.data?.title ?? t("ui.manual.title", "User Manual");
  return (
    <div className="drawer-scrim dock-scrim" role="presentation">
      <aside
        ref={panelRef}
        className={`help-drawer${full ? " help-drawer-full" : ""}`}
        style={full ? undefined : { width: `${width}px` }}
        role="dialog"
        aria-modal="true"
        aria-label={`Axiom ${title}`}
        onMouseDown={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (event.key !== "Tab") return;
          const focusable = [...(panelRef.current?.querySelectorAll<HTMLElement>("button, [href]") ?? [])]
            .filter((element) => !element.hasAttribute("disabled"));
          if (!focusable.length) return;
          const first = focusable[0];
          const last = focusable[focusable.length - 1];
          if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
          else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        }}
      >
        <button className="dock-resizer" aria-label={t("ui.manual.resize", "Resize user manual")} onPointerDown={startResize} />
        <header className="drawer-head">
          <div>
            <span className="eyebrow">{manualQ.data?.eyebrow ?? t("ui.manual.fieldManual", "Field manual")}</span>
            <h2>{title}</h2>
          </div>
          <div className="drawer-actions">
            <button className="btn btn-sm" onClick={() => setFull((value) => !value)}>{full ? t("ui.grid.restoreView", "Restore view") : t("ui.grid.fullView", "Full view")}</button>
            <button ref={closeRef} className="icon-btn" onClick={onClose} aria-label={t("ui.manual.close", "Close guide")}><CloseIcon /></button>
          </div>
        </header>

        <div className="drawer-content" aria-live="polite">
          {manualQ.isLoading && <div className="drawer-state"><span className="status-pip" /><p>{t("ui.manual.loading", "Loading user manual…")}</p></div>}
          {manualQ.isError && <div className="drawer-state drawer-state-error">
            <strong>{t("ui.manual.unavailable", "User manual unavailable")}</strong>
            <p>{manualQ.error instanceof Error ? manualQ.error.message : t("ui.manual.retryBody", "The governed documentation could not be loaded.")}</p>
            <button className="btn btn-sm" onClick={() => void manualQ.refetch()}>{t("ui.action.retry", "Retry")}</button>
          </div>}
          {manualQ.data?.sections.map((section) => <DocumentationSectionView key={section.id} section={section} />)}
        </div>
      </aside>
    </div>
  );
}

function DocumentationSectionView({ section }: { section: DocumentationSection }) {
  if (section.type === "CALLOUT") {
    return <>{section.entries.map((entry) => <section className="guide-callout" key={entry.id}>
      <span className="status-pip" /><div><strong>{entry.title}</strong>{entry.body && <p>{entry.body}</p>}</div>
    </section>)}</>;
  }
  if (section.type === "SHORTCUTS") {
    return <section className="guide-section">
      {section.heading && <span className="eyebrow">{section.heading}</span>}
      <dl className="shortcut-list">{section.entries.map((entry) => <div key={entry.id}>
        <dt><kbd>{entry.marker}</kbd></dt><dd>{entry.title}</dd>
      </div>)}</dl>
    </section>;
  }
  if (section.type === "RULE") {
    return <>{section.entries.map((entry) => <section className="guide-section guide-rule" key={entry.id}>
      {entry.marker && <span className="ai-mark">{entry.marker}</span>}
      <p><strong>{entry.title}</strong>{entry.body && <> {entry.body}</>}</p>
    </section>)}</>;
  }
  return <section className="guide-section">
    {section.heading && <span className="eyebrow">{section.heading}</span>}
    <ol className="guide-steps">{section.entries.map((entry) => <li key={entry.id}>
      <span>{entry.marker}</span><div><strong>{entry.title}</strong>{entry.body && <p>{entry.body}</p>}</div>
    </li>)}</ol>
  </section>;
}

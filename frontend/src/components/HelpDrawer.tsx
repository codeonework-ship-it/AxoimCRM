import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { CloseIcon } from "./icons";

interface HelpDrawerProps {
  open: boolean;
  onClose: () => void;
}

const SHORTCUTS = [
  ["Ctrl K", "Open command center"],
  ["G then H", "Go to Home"],
  ["G then P", "Go to Pipeline"],
  ["G then A", "Go to Accounts"],
  ["G then L", "Go to Leads"],
  ["G then E", "Go to Activities"],
  ["G then R", "Go to Reference Data"],
  ["G then T", "Go to Reports"],
  ["G then U", "Go to Administration"],
  ["Ctrl /", "Open this guide"],
];

export function HelpDrawer({ open, onClose }: HelpDrawerProps) {
  const panelRef = useRef<HTMLElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const [full, setFull] = useState(false);
  const [width, setWidth] = useState(480);

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
  return (
    <div className="drawer-scrim dock-scrim" role="presentation">
      <aside
        ref={panelRef}
        className={`help-drawer${full ? " help-drawer-full" : ""}`}
        style={full ? undefined : { width: `${width}px` }}
        role="dialog"
        aria-modal="true"
        aria-label="Axiom User Manual"
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
        <button className="dock-resizer" aria-label="Resize user manual" onPointerDown={startResize} />
        <header className="drawer-head">
          <div>
            <span className="eyebrow">Field manual · 01</span>
            <h2>User Manual</h2>
          </div>
          <div className="drawer-actions">
            <button className="btn btn-sm" onClick={() => setFull((value) => !value)}>{full ? "Restore view" : "Full view"}</button>
            <button ref={closeRef} className="icon-btn" onClick={onClose} aria-label="Close guide"><CloseIcon /></button>
          </div>
        </header>

        <div className="drawer-content">
          <section className="guide-callout">
            <span className="status-pip" />
            <div><strong>Your fastest route</strong><p>Start on Home, resolve flagged deals, then work the pipeline from left to right.</p></div>
          </section>

          <section className="guide-section">
            <span className="eyebrow">Core loop</span>
            <ol className="guide-steps">
              <li><span>01</span><div><strong>Scan Home</strong><p>Review revenue posture and intervention signals.</p></div></li>
              <li><span>02</span><div><strong>Qualify leads</strong><p>Convert qualified demand into an account, contact, and deal.</p></div></li>
              <li><span>03</span><div><strong>Advance deals</strong><p>Drag cards only after stage requirements are satisfied.</p></div></li>
              <li><span>04</span><div><strong>Capture engagement</strong><p>Use Activities to log tasks, events, calls, notes and manual email summaries against CRM records.</p></div></li>
            </ol>
          </section>

          <section className="guide-section">
            <span className="eyebrow">Admin modules</span>
            <ol className="guide-steps">
              <li><span>05</span><div><strong>RBAC first</strong><p>Review role policies before changing users, trials, company status, billing or alerts.</p></div></li>
              <li><span>06</span><div><strong>Reports</strong><p>Use Reports for governed PDF, Excel and Word downloads for the selected workspace.</p></div></li>
              <li><span>07</span><div><strong>Alert queues</strong><p>Email and report alerts are validated and queued internally until third-party delivery is connected.</p></div></li>
            </ol>
          </section>

          <section className="guide-section">
            <span className="eyebrow">Keyboard map</span>
            <dl className="shortcut-list">
              {SHORTCUTS.map(([keys, action]) => (
                <div key={keys}><dt><kbd>{keys}</kbd></dt><dd>{action}</dd></div>
              ))}
            </dl>
          </section>

          <section className="guide-section guide-rule">
            <span className="ai-mark">AI</span>
            <p><strong>Gold always means machine-generated.</strong> Review it before acting; customer data and system status never use gold.</p>
          </section>
        </div>
      </aside>
    </div>
  );
}

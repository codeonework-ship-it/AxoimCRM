import { useEffect, useRef } from "react";
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
  ["Ctrl /", "Open this guide"],
];

export function HelpDrawer({ open, onClose }: HelpDrawerProps) {
  const panelRef = useRef<HTMLElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!open) return;
    const opener = document.activeElement as HTMLElement | null;
    window.setTimeout(() => closeRef.current?.focus(), 0);
    return () => opener?.focus();
  }, [open]);
  if (!open) return null;
  return (
    <div className="drawer-scrim" role="presentation" onMouseDown={onClose}>
      <aside
        ref={panelRef}
        className="help-drawer"
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
        <header className="drawer-head">
          <div>
            <span className="eyebrow">Field manual · 01</span>
            <h2>User Manual</h2>
          </div>
          <button ref={closeRef} className="icon-btn" onClick={onClose} aria-label="Close guide"><CloseIcon /></button>
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

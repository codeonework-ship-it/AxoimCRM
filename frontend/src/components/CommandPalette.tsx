import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  AccountsIcon,
  HomeIcon,
  LeadsIcon,
  PipelineIcon,
  ReferenceIcon,
  SearchIcon,
  SparkIcon,
} from "./icons";

interface CommandPaletteProps {
  open: boolean;
  onClose: () => void;
  onOpenHelp: () => void;
}

const COMMANDS = [
  { label: "Revenue command center", hint: "Overview and priorities", to: "/", icon: HomeIcon },
  { label: "Opportunity pipeline", hint: "Inspect and advance deals", to: "/pipeline", icon: PipelineIcon },
  { label: "Account intelligence", hint: "Organizations and ownership", to: "/accounts", icon: AccountsIcon },
  { label: "Lead operations", hint: "Qualify and convert demand", to: "/leads", icon: LeadsIcon },
  { label: "Reference data", hint: "Governed value sets and codes", to: "/reference-data", icon: ReferenceIcon },
  { label: "Reports", hint: "Jasper PDF, Excel and Word downloads", to: "/reports", icon: SparkIcon },
  { label: "Administration", hint: "Users, RBAC, trials, billing and alerts", to: "/admin", icon: ReferenceIcon },
];

export function CommandPalette({ open, onClose, onOpenHelp }: CommandPaletteProps) {
  const navigate = useNavigate();
  const [query, setQuery] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);
  const panelRef = useRef<HTMLElement>(null);
  const results = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return needle
      ? COMMANDS.filter((item) => `${item.label} ${item.hint}`.toLowerCase().includes(needle))
      : COMMANDS;
  }, [query]);

  useEffect(() => {
    if (!open) return;
    const opener = document.activeElement as HTMLElement | null;
    setQuery("");
    window.setTimeout(() => inputRef.current?.focus(), 0);
    return () => opener?.focus();
  }, [open]);

  if (!open) return null;

  return (
    <div className="modal-scrim" role="presentation" onMouseDown={onClose}>
      <section
        ref={panelRef}
        className="command-palette"
        role="dialog"
        aria-modal="true"
        aria-label="Command center"
        onMouseDown={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          if (event.key !== "Tab") return;
          const focusable = [...(panelRef.current?.querySelectorAll<HTMLElement>("button, input, [href]") ?? [])]
            .filter((element) => !element.hasAttribute("disabled"));
          if (!focusable.length) return;
          const first = focusable[0];
          const last = focusable[focusable.length - 1];
          if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
          else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        }}
      >
        <div className="command-search">
          <SearchIcon size={20} />
          <input
            ref={inputRef}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Jump to a workspace or action…"
            aria-label="Search commands"
            onKeyDown={(event) => {
              if (event.key === "Enter" && results[0]) {
                navigate(results[0].to);
                onClose();
              }
            }}
          />
          <kbd>Esc</kbd>
        </div>
        <div className="command-section-label">Navigate</div>
        <div className="command-results">
          {results.map(({ label, hint, to, icon: Icon }) => (
            <button
              key={to}
              className="command-result"
              onClick={() => {
                navigate(to);
                onClose();
              }}
            >
              <span className="command-icon"><Icon /></span>
              <span><strong>{label}</strong><small>{hint}</small></span>
              <span className="command-enter">↵</span>
            </button>
          ))}
          {results.length === 0 && <p className="command-empty">No matching workspace or action.</p>}
        </div>
        <footer className="command-footer">
          <span>Type to filter · Enter to open</span>
          <button onClick={() => { onClose(); onOpenHelp(); }}>Open User Manual</button>
        </footer>
      </section>
    </div>
  );
}

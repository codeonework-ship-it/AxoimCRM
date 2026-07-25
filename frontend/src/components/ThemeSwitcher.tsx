import { useEffect, useRef, useState } from "react";
import { useT } from "../i18n/I18nProvider";
import { ChevronIcon, SunMoonIcon } from "./icons";

/**
 * Theme selection across the product's three visual worlds.
 *
 * This replaced a binary light/dark toggle. Once there is a third theme a
 * toggle stops being honest — a user pressing it cannot predict what they get,
 * and there is no way to reach the third option at all. A named list also lets
 * each theme carry a one-line description, which is what actually helps someone
 * choose between two dark themes.
 */
export type ThemeId = "dark" | "light" | "ironman";

export const THEMES: { id: ThemeId; name: string; blurb: string; swatch: string[] }[] = [
  {
    id: "dark",
    name: "Command Deck",
    blurb: "Cinematic carbon and energon cyan",
    swatch: ["#05070b", "#35e0ff", "#ffb547"],
  },
  {
    id: "light",
    name: "Arctic Frost",
    blurb: "Glacial white, deep ice, frosted glass",
    swatch: ["#eef3f8", "#0b6e8f", "#8a5a00"],
  },
  {
    id: "ironman",
    name: "Mark VII",
    blurb: "Hot-rod red, gold trim, arc-reactor glow",
    swatch: ["#1a1010", "#5fd3ee", "#f5b32a"],
  },
];

export const DEFAULT_THEME: ThemeId = "dark";

export function isThemeId(value: unknown): value is ThemeId {
  return value === "dark" || value === "light" || value === "ironman";
}

export function applyTheme(id: ThemeId) {
  document.documentElement.dataset.theme = id;
  localStorage.setItem("axiom.theme", id);
}

export function ThemeSwitcher() {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState<ThemeId>(() => {
    const saved = document.documentElement.dataset.theme;
    return isThemeId(saved) ? saved : DEFAULT_THEME;
  });
  const wrapRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (event: MouseEvent) => {
      if (!wrapRef.current?.contains(event.target as Node)) setOpen(false);
    };
    window.addEventListener("mousedown", onDown);
    return () => window.removeEventListener("mousedown", onDown);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
        buttonRef.current?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  const choose = (id: ThemeId) => {
    applyTheme(id);
    setActive(id);
    setOpen(false);
    buttonRef.current?.focus();
  };

  const current = THEMES.find((theme) => theme.id === active) ?? THEMES[0];

  return (
    <div className="theme-switch" ref={wrapRef}>
      <button
        ref={buttonRef}
        className="icon-btn"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`${t("shell.theme", "Theme")}: ${current.name}`}
        title={`${t("shell.theme", "Theme")}: ${current.name}`}
        onClick={() => setOpen((o) => !o)}
      >
        <SunMoonIcon />
      </button>

      {open && (
        <div className="theme-pop" role="menu" aria-label={t("shell.theme", "Theme")}>
          <span className="eyebrow">{t("shell.theme", "Theme")}</span>
          {THEMES.map((theme) => (
            <button
              key={theme.id}
              role="menuitemradio"
              aria-checked={theme.id === active}
              className={`theme-option${theme.id === active ? " is-active" : ""}`}
              onClick={() => choose(theme.id)}
            >
              <span className="theme-swatch" aria-hidden>
                {theme.swatch.map((colour) => (
                  <i key={colour} style={{ background: colour }} />
                ))}
              </span>
              <span className="theme-meta">
                <strong>{theme.name}</strong>
                <small>{theme.blurb}</small>
              </span>
              <ChevronIcon size={12} />
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

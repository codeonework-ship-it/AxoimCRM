import { useEffect, useRef, useState } from "react";
import { GlobeIcon } from "../components/icons";
import { useI18n, type SupportedLocale, SUPPORTED_LOCALES } from "./I18nProvider";

/**
 * Compact language control for the top bar.
 *
 * A popover rather than a <select>: the options are native-name strings coming
 * from the API and the active one needs the ion accent, neither of which a
 * native select renders consistently across platforms. It reuses the same
 * hairline + uppercase-micro-label vocabulary as .manual-button and .notif-pop,
 * so no new colour or geometry enters the system.
 *
 * The three-letter code, not a flag, labels the button. Flags denote countries;
 * German is not Germany.
 */
export function LocaleSwitcher() {
  const { locale, setLocale, locales, t } = useI18n();
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (event: MouseEvent) => {
      if (!wrapRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
        buttonRef.current?.focus();
      }
    };
    window.addEventListener("mousedown", onDown);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("mousedown", onDown);
      window.removeEventListener("keydown", onKey);
    };
  }, [open]);

  // The API is the source of truth for names and order, but the runtime only
  // knows how to serve en/de/ru, so a locale added to the table before the
  // frontend supports it must not appear as a dead option.
  const options = locales.filter((option) =>
    (SUPPORTED_LOCALES as readonly string[]).includes(option.code),
  );
  const active = options.find((option) => option.code === locale);
  const languageLabel = t("shell.language", "Language");

  return (
    <div className="locale-switch" ref={wrapRef}>
      <button
        ref={buttonRef}
        type="button"
        className="locale-trigger"
        aria-label={`${languageLabel}: ${active?.nativeName ?? locale}`}
        aria-expanded={open}
        aria-haspopup="menu"
        title={languageLabel}
        onClick={() => setOpen((value) => !value)}
      >
        <GlobeIcon size={15} />
        <span className="locale-code">{locale}</span>
      </button>

      {open && (
        <div className="locale-pop" role="menu" aria-label={languageLabel}>
          <p className="locale-pop-head">{languageLabel}</p>
          {options.map((option) => (
            <button
              key={option.code}
              type="button"
              role="menuitemradio"
              aria-checked={option.code === locale}
              className={`locale-option${option.code === locale ? " is-active" : ""}`}
              onClick={() => {
                setLocale(option.code as SupportedLocale);
                setOpen(false);
                buttonRef.current?.focus();
              }}
            >
              <span className="locale-option-code">{option.code}</span>
              <span className="locale-option-name">{option.nativeName}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

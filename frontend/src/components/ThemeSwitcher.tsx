import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type UiTheme } from "../api/client";
import { useT } from "../i18n/I18nProvider";
import { ChevronIcon, SunMoonIcon } from "./icons";
import { useToasts } from "./Toasts";

/**
 * Theme selection, served from the database.
 *
 * <h2>The catalogue is data, not code</h2>
 * This file used to hold the five themes as a hardcoded array and the choice in
 * localStorage. Both were wrong for an enterprise product: the catalogue could
 * not be governed without a frontend deploy, and the preference did not follow
 * the user to a second machine — sign in from a new browser and the product had
 * forgotten who you were, visually. The catalogue now comes from
 * {@code reference.ui_theme} and the selection from
 * {@code identity.user_ui_preference}; see migration V336.
 *
 * <h2>localStorage is still here, demoted to a cache</h2>
 * It is no longer the source of truth — it is the PRE-PAINT cache, and it earns
 * its keep. The theme decides the colour of the first pixel, but the server
 * cannot be consulted before the first pixel: the fetch needs a mounted app and
 * a token. Without a cached value the shell would paint in the default theme and
 * then snap to the user's, which is a full-screen flash on every cold load. So
 * main.tsx paints from the cache synchronously, and this component reconciles
 * with the server a moment later. The cache is written on every server answer,
 * which keeps the next cold load correct.
 *
 * <h2>Why the fallback list still exists</h2>
 * If the catalogue request fails, the picker falls back to a minimal built-in
 * pair rather than rendering an empty menu. A user whose network hiccuped should
 * still be able to switch out of a theme they cannot read — losing the ability to
 * change appearance is a worse failure than showing two options instead of five.
 */

/**
 * A theme id is whatever the catalogue says it is, so this is a plain string
 * rather than a union. A union here would have to be edited — and redeployed —
 * every time a row is added, which is the coupling this change removes.
 */
export type ThemeId = string;

export const THEME_STORAGE_KEY = "axiom.theme";

/**
 * The product default, and the value painted before the server has answered.
 * Duplicated from the {@code is_default} row on purpose: this constant is read
 * during the synchronous pre-paint in main.tsx, where no async call is possible.
 * If the two disagree the server wins, one frame later.
 */
export const DEFAULT_THEME: ThemeId = "dark";

/**
 * The offline fallback: one dark, one light, both of which certainly have CSS.
 * Not the whole catalogue — a stale copy of five entries would drift, and the
 * point of the fallback is escape from an unreadable theme, not feature parity.
 */
export const FALLBACK_THEMES: UiTheme[] = [
  {
    code: "dark", name: "Command Deck", blurb: "Cinematic carbon and energon cyan",
    swatch: ["#05070b", "#35e0ff", "#ffb547"], appearance: "DARK", isDefault: true, sortOrder: 10,
  },
  {
    code: "light", name: "Arctic Frost", blurb: "Glacial white, deep ice, frosted glass",
    swatch: ["#eef3f8", "#0b6e8f", "#8a5a00"], appearance: "LIGHT", isDefault: false, sortOrder: 20,
  },
];

/**
 * Any non-empty, syntactically plausible code is accepted at pre-paint time.
 *
 * <p>It cannot be validated against the catalogue here — the catalogue is on the
 * server and this runs before the first paint. The pattern match is the real
 * guard: it stops a corrupted or injected localStorage value from becoming an
 * attribute selector, and an unknown-but-well-formed code simply matches no CSS
 * block and inherits the :root defaults, which is a survivable frame. The server
 * corrects it immediately afterwards.
 */
export function isThemeId(value: unknown): value is ThemeId {
  return typeof value === "string" && /^[a-z][a-z0-9_-]{1,30}$/.test(value);
}

/** Paint a theme and cache it for the next cold load. */
export function applyTheme(id: ThemeId) {
  document.documentElement.dataset.theme = id;
  try {
    localStorage.setItem(THEME_STORAGE_KEY, id);
  } catch {
    // Private browsing and hardened profiles can refuse storage. The theme still
    // applies for this session; only the pre-paint cache is lost, which costs a
    // flash on the next cold load and nothing else.
  }
}

export function ThemeSwitcher() {
  const t = useT();
  const toasts = useToasts();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  const themeQ = useQuery({
    queryKey: ["ui-theme"],
    queryFn: api.uiTheme,
    // The catalogue changes only on deploy and the preference only when this
    // component changes it, so refetching is waste.
    staleTime: 10 * 60 * 1000,
    retry: 1,
  });

  const themes = themeQ.data?.themes ?? FALLBACK_THEMES;

  /*
   * `active` is what is painted. It starts from the DOM — which main.tsx already
   * set from the pre-paint cache — so the switcher never disagrees with the
   * screen, and is reconciled to the server's answer below.
   */
  const [active, setActive] = useState<ThemeId>(() => {
    const painted = document.documentElement.dataset.theme;
    return isThemeId(painted) ? painted : DEFAULT_THEME;
  });

  /*
   * Reconcile with the server exactly once per load. Deliberately keyed on the
   * effective value rather than running on every render: applying on each
   * refetch would fight a choice the user just made locally.
   */
  const effective = themeQ.data?.effective;
  useEffect(() => {
    if (!effective) return;
    /*
     * applyTheme runs even when the value already matches what is painted, and
     * that is the point: it also PRIMES THE PRE-PAINT CACHE. Returning early on
     * a match left the cache empty, so every cold load painted DEFAULT_THEME and
     * was correct only for as long as the user's preference happened to equal the
     * product default. The moment it did not, they got a flash on every load —
     * which is the exact failure the cache exists to prevent.
     */
    applyTheme(effective);
    if (effective !== active) setActive(effective);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [effective]);

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

  const saveMutation = useMutation({
    mutationFn: (code: ThemeId) => api.chooseUiTheme(code),
    onSuccess: (state) => {
      queryClient.setQueryData(["ui-theme"], state);
      applyTheme(state.effective);
      setActive(state.effective);
    },
    /*
     * The paint already happened optimistically, and it is deliberately NOT
     * rolled back. The user asked to look at this theme; taking it away because
     * a write failed punishes them for the network. The theme is applied and
     * cached locally, so it survives a reload — what is lost is only the
     * cross-device part, and the toast says exactly that.
     */
    onError: () => toasts.push("error", "Theme not saved",
      "The theme is applied on this device, but it could not be saved to your "
      + "profile, so another browser will not pick it up."),
  });

  const choose = (id: ThemeId) => {
    applyTheme(id);
    setActive(id);
    setOpen(false);
    buttonRef.current?.focus();
    const selected = themes.find((theme) => theme.code === id);
    toasts.push("info", "Theme applied", `${selected?.name ?? "Selected theme"} is now active.`);
    saveMutation.mutate(id);
  };

  const current = themes.find((theme) => theme.code === active) ?? themes[0];

  return (
    <div className="theme-switch" ref={wrapRef}>
      <button
        ref={buttonRef}
        className="icon-btn"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`${t("shell.theme", "Theme")}: ${current?.name ?? active}`}
        title={`${t("shell.theme", "Theme")}: ${current?.name ?? active}`}
        onClick={() => setOpen((o) => !o)}
      >
        <SunMoonIcon />
      </button>

      {open && (
        <div className="theme-pop" role="menu" aria-label={t("shell.theme", "Theme")}>
          <div className="theme-pop-head">
            <strong>{t("shell.theme", "Appearance")}</strong>
            <span>
              {themeQ.isError
                ? "Catalogue unavailable — showing the built-in themes"
                : "Choose a visual theme"}
            </span>
          </div>
          {themes.map((theme) => (
            <button
              key={theme.code}
              role="menuitemradio"
              aria-checked={theme.code === active}
              className={`theme-option${theme.code === active ? " is-active" : ""}`}
              onClick={() => choose(theme.code)}
            >
              <span className="theme-swatch" aria-hidden>
                {theme.swatch.map((colour, index) => (
                  <i key={`${theme.code}-${index}`} style={{ background: colour }} />
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

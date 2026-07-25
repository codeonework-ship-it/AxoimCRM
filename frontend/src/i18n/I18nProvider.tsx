import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { api, type LocaleOption, type TranslationBundle } from "../api/client";

/**
 * Database-backed i18n runtime.
 *
 * The bundle is fetched from GET /api/v1/i18n/bundle/{locale} rather than bundled
 * into the JS build. Two reasons:
 *
 *  - tenants may relabel the product vocabulary ("Accounts" -> "Clients") and
 *    those overrides live in the database, per tenant, resolved server-side;
 *  - adding a language, or fixing a bad translation, must not require a
 *    frontend release.
 *
 * The cost is one request before first paint of translated chrome. It is paid
 * without a spinner: `t()` falls back to the English literal already present in
 * the source, so the UI renders correctly-in-English immediately and swaps to
 * the resolved language when the bundle lands. There is deliberately no
 * loading gate — a blank shell would be worse than an English one.
 */

export const SUPPORTED_LOCALES = ["en", "de", "ru"] as const;
export type SupportedLocale = (typeof SUPPORTED_LOCALES)[number];

const STORAGE_KEY = "axiom.locale";
const DEFAULT_LOCALE: SupportedLocale = "en";

/** Native names used until /i18n/locales answers, so the switcher is never blank. */
const FALLBACK_LOCALE_OPTIONS: LocaleOption[] = [
  { code: "en", englishName: "English", nativeName: "English", isDefault: true, sortOrder: 10 },
  { code: "de", englishName: "German", nativeName: "Deutsch", isDefault: false, sortOrder: 20 },
  { code: "ru", englishName: "Russian", nativeName: "Русский", isDefault: false, sortOrder: 30 },
];

function isSupported(value: string | null | undefined): value is SupportedLocale {
  return !!value && (SUPPORTED_LOCALES as readonly string[]).includes(value);
}

/**
 * Stored choice wins over the browser. A user who explicitly picked English on a
 * German machine meant it.
 */
function initialLocale(): SupportedLocale {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (isSupported(stored)) return stored;
  } catch {
    /* private-mode localStorage throws; fall through to the browser language */
  }
  const browser = (navigator.languages?.[0] ?? navigator.language ?? "")
    .slice(0, 2)
    .toLowerCase();
  return isSupported(browser) ? browser : DEFAULT_LOCALE;
}

/**
 * Last-resort display text for a key with no translation and no inline fallback.
 * "nav.module.referenceData" -> "Reference Data". Never returns an empty string:
 * a blank label is an invisible control, which is worse than an imperfect one.
 */
export function humanizeKey(key: string): string {
  const segment = key.split(".").pop() ?? key;
  const spaced = segment
    .replace(/[_-]+/g, " ")
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .trim();
  if (!spaced) return key;
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

export type TranslateFn = (key: string, fallback?: string) => string;

interface I18nContextValue {
  locale: SupportedLocale;
  setLocale: (locale: SupportedLocale) => void;
  locales: LocaleOption[];
  t: TranslateFn;
  /** True while the first bundle for the current locale is in flight. */
  loading: boolean;
}

const I18nContext = createContext<I18nContextValue | null>(null);

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<SupportedLocale>(initialLocale);
  const [bundle, setBundle] = useState<TranslationBundle>({});
  const [locales, setLocales] = useState<LocaleOption[]>(FALLBACK_LOCALE_OPTIONS);
  const [loading, setLoading] = useState(true);

  // Screen readers and CSS :lang() both need this, and it is also what tells a
  // browser's translate prompt to leave the page alone.
  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api
      .translationBundle(locale)
      .then((next) => {
        if (!cancelled) setBundle(next);
      })
      .catch(() => {
        // API down or locale rejected — keep whatever we had. Every call site
        // passes an English fallback, so the shell stays usable.
        if (!cancelled) setBundle({});
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [locale]);

  useEffect(() => {
    let cancelled = false;
    api
      .locales()
      .then((options) => {
        if (!cancelled && options.length > 0) setLocales(options);
      })
      .catch(() => {
        /* keep FALLBACK_LOCALE_OPTIONS */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const setLocale = useCallback((next: SupportedLocale) => {
    setLocaleState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* non-persistent session; the choice still applies for this page load */
    }
  }, []);

  const t = useCallback<TranslateFn>(
    (key, fallback) => bundle[key] ?? fallback ?? humanizeKey(key),
    [bundle],
  );

  const value = useMemo<I18nContextValue>(
    () => ({ locale, setLocale, locales, t, loading }),
    [locale, setLocale, locales, t, loading],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

function useI18nContext(): I18nContextValue {
  const ctx = useContext(I18nContext);
  if (!ctx) throw new Error("useT/useI18n must be used inside <I18nProvider>");
  return ctx;
}

/** The common case: just the translate function. */
export function useT(): TranslateFn {
  return useI18nContext().t;
}

/** Full runtime — used by LocaleSwitcher. */
export function useI18n(): I18nContextValue {
  return useI18nContext();
}

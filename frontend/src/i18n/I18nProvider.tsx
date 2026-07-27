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
export type TranslatePhraseFn = (source: string) => string;
export type FormatTranslationFn = (
  key: string,
  fallback: string,
  values?: Record<string, string | number>,
) => string;

interface I18nContextValue {
  locale: SupportedLocale;
  setLocale: (locale: SupportedLocale) => void;
  locales: LocaleOption[];
  t: TranslateFn;
  /** Translate an exact product phrase. Tenant/business data must not be passed here. */
  tp: TranslatePhraseFn;
  /** Translate a keyed template and replace named {tokens}. */
  format: FormatTranslationFn;
  formatNumber: (value: number, options?: Intl.NumberFormatOptions) => string;
  formatDate: (value: Date | string | number, options?: Intl.DateTimeFormatOptions) => string;
  /** True while the first bundle for the current locale is in flight. */
  loading: boolean;
}

const I18nContext = createContext<I18nContextValue | null>(null);

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<SupportedLocale>(initialLocale);
  const [bundle, setBundle] = useState<TranslationBundle>({});
  const [phraseBundle, setPhraseBundle] = useState<TranslationBundle>({});
  const [locales, setLocales] = useState<LocaleOption[]>(FALLBACK_LOCALE_OPTIONS);
  const [loading, setLoading] = useState(true);

  // Screen readers and CSS :lang() both need this, and it is also what tells a
  // browser's translate prompt to leave the page alone.
  useEffect(() => {
    document.documentElement.lang = locale;
    document.documentElement.dir = "ltr";
  }, [locale]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.allSettled([api.translationBundle(locale), api.translationPhraseBundle(locale)])
      .then(([keyed, phrases]) => {
        if (cancelled) return;
        // The two resources degrade independently. During a rolling deployment
        // an older API may not expose /phrases yet; keyed shell translation must
        // continue to work in that window.
        setBundle(keyed.status === "fulfilled" ? keyed.value : {});
        setPhraseBundle(phrases.status === "fulfilled" ? phrases.value : {});
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

  const tp = useCallback<TranslatePhraseFn>(
    (source) => phraseBundle[source] ?? source,
    [phraseBundle],
  );

  const format = useCallback<FormatTranslationFn>(
    (key, fallback, values = {}) => {
      const template = bundle[key] ?? fallback;
      return template.replace(/\{([a-zA-Z0-9_]+)\}/g, (match, token: string) =>
        Object.prototype.hasOwnProperty.call(values, token) ? String(values[token]) : match,
      );
    },
    [bundle],
  );

  const formatNumber = useCallback(
    (value: number, options?: Intl.NumberFormatOptions) =>
      new Intl.NumberFormat(locale, options).format(value),
    [locale],
  );

  const formatDate = useCallback(
    (value: Date | string | number, options?: Intl.DateTimeFormatOptions) =>
      new Intl.DateTimeFormat(locale, options).format(value instanceof Date ? value : new Date(value)),
    [locale],
  );

  useEffect(() => installExactPhraseTranslator(phraseBundle), [phraseBundle]);

  const value = useMemo<I18nContextValue>(
    () => ({ locale, setLocale, locales, t, tp, format, formatNumber, formatDate, loading }),
    [locale, setLocale, locales, t, tp, format, formatNumber, formatDate, loading],
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

/**
 * Compatibility translator for product chrome that predates keyed t() calls.
 *
 * It replaces only exact phrases returned by the governed server catalogue.
 * Text in code samples and any subtree marked data-i18n-skip/translate="no" is
 * untouched. WeakMap state retains the English source so switching de -> ru ->
 * en never chains translations, while React updates are detected as new source
 * text. The observer also covers lazy routes, drawers, toasts and report views.
 */
const textState = new WeakMap<Text, { source: string; rendered: string }>();
const attributeState = new WeakMap<Element, Map<string, { source: string; rendered: string }>>();
const TRANSLATED_ATTRIBUTES = ["aria-label", "title", "placeholder", "alt"] as const;
const SKIPPED_TAGS = new Set(["SCRIPT", "STYLE", "CODE", "KBD", "PRE", "NOSCRIPT"]);

function installExactPhraseTranslator(phrases: TranslationBundle): () => void {
  function skipped(node: Node): boolean {
    const parent = node instanceof Element ? node : node.parentElement;
    return (!!parent?.closest('[data-i18n-skip], [translate="no"]')) ||
      (!!parent && SKIPPED_TAGS.has(parent.tagName));
  }

  function translateTextNode(node: Text) {
    if (skipped(node)) return;
    const current = node.nodeValue ?? "";
    const previous = textState.get(node);
    const sourceValue = previous && current === previous.rendered ? previous.source : current;
    const match = /^(\s*)([\s\S]*?)(\s*)$/.exec(sourceValue);
    if (!match || !match[2]) return;
    const translated = phrases[match[2]] ?? match[2];
    const rendered = `${match[1]}${translated}${match[3]}`;
    textState.set(node, { source: sourceValue, rendered });
    if (current !== rendered) node.nodeValue = rendered;
  }

  function translateAttributes(element: Element) {
    if (skipped(element)) return;
    let state = attributeState.get(element);
    if (!state) {
      state = new Map();
      attributeState.set(element, state);
    }
    for (const attribute of TRANSLATED_ATTRIBUTES) {
      const current = element.getAttribute(attribute);
      if (!current) continue;
      const previous = state.get(attribute);
      const source = previous && current === previous.rendered ? previous.source : current;
      const rendered = phrases[source] ?? source;
      state.set(attribute, { source, rendered });
      if (current !== rendered) element.setAttribute(attribute, rendered);
    }
  }

  function translateTree(root: Node) {
    if (root.nodeType === Node.TEXT_NODE) {
      translateTextNode(root as Text);
      return;
    }
    if (!(root instanceof Element) && root !== document.body) return;
    if (root instanceof Element) translateAttributes(root);
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
    let current = walker.nextNode();
    while (current) {
      if (current.nodeType === Node.TEXT_NODE) translateTextNode(current as Text);
      else translateAttributes(current as Element);
      current = walker.nextNode();
    }
  }

  translateTree(document.body);
  const observer = new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      if (mutation.type === "characterData") translateTextNode(mutation.target as Text);
      else if (mutation.type === "attributes") translateAttributes(mutation.target as Element);
      else mutation.addedNodes.forEach(translateTree);
    }
  });
  observer.observe(document.body, {
    subtree: true,
    childList: true,
    characterData: true,
    attributes: true,
    attributeFilter: [...TRANSLATED_ATTRIBUTES],
  });
  return () => observer.disconnect();
}

import { useEffect, useRef, useState, type Dispatch, type SetStateAction } from "react";

export const GRID_STORAGE_PREFIX = "axiom.grid.";
export const GRID_PREFERENCES_RESET_EVENT = "axiom:grid-preferences-reset";

export interface PersistedGridState {
  groupColumns: string[];
  columnFilters: Record<string, string>;
}

const EMPTY_GRID_STATE: PersistedGridState = {
  groupColumns: [],
  columnFilters: {},
};

export function usePersistedGridState(
  gridKey: string,
  defaults: Partial<PersistedGridState> = {},
): [
  string[],
  Dispatch<SetStateAction<string[]>>,
  Record<string, string>,
  Dispatch<SetStateAction<Record<string, string>>>,
] {
  const storageKey = `${GRID_STORAGE_PREFIX}${gridKey}`;
  const fallback = sanitizeGridState({ ...EMPTY_GRID_STATE, ...defaults });
  const [state, setState] = useLocalStorageState(storageKey, fallback, sanitizeGridState);

  const setGroupColumns: Dispatch<SetStateAction<string[]>> = (next) => {
    setState((current) => ({
      ...current,
      groupColumns: sanitizeStringList(resolve(next, current.groupColumns)),
    }));
  };

  const setColumnFilters: Dispatch<SetStateAction<Record<string, string>>> = (next) => {
    setState((current) => ({
      ...current,
      columnFilters: sanitizeFilterMap(resolve(next, current.columnFilters)),
    }));
  };

  return [state.groupColumns, setGroupColumns, state.columnFilters, setColumnFilters];
}

export function useLocalStorageState<T>(
  storageKey: string,
  fallback: T,
  sanitize: (value: unknown, fallback: T) => T = defaultSanitize,
): [T, Dispatch<SetStateAction<T>>] {
  const [state, setState] = useState<T>(() => read(storageKey, fallback, sanitize));
  const skipNextPersist = useRef(false);
  const fallbackRef = useRef(fallback);
  const sanitizeRef = useRef(sanitize);

  useEffect(() => {
    fallbackRef.current = fallback;
    sanitizeRef.current = sanitize;
  });

  useEffect(() => {
    skipNextPersist.current = true;
    setState(read(storageKey, fallbackRef.current, sanitizeRef.current));
  }, [storageKey]);

  useEffect(() => {
    const onReset = () => {
      if (!storageKey.startsWith(GRID_STORAGE_PREFIX)) return;
      skipNextPersist.current = true;
      setState(fallbackRef.current);
    };
    window.addEventListener(GRID_PREFERENCES_RESET_EVENT, onReset);
    return () => window.removeEventListener(GRID_PREFERENCES_RESET_EVENT, onReset);
  }, [storageKey]);

  useEffect(() => {
    if (skipNextPersist.current) {
      skipNextPersist.current = false;
      return;
    }
    try {
      window.localStorage.setItem(storageKey, JSON.stringify(state));
    } catch {
      // Private browsing and locked-down desktop shells can deny storage. The
      // grid must still work; persistence simply becomes best-effort.
    }
  }, [state, storageKey]);

  return [state, setState];
}

export function clearGridPreferences(): number {
  try {
    const keys: string[] = [];
    for (let index = 0; index < window.localStorage.length; index += 1) {
      const key = window.localStorage.key(index);
      if (key?.startsWith(GRID_STORAGE_PREFIX)) keys.push(key);
    }
    keys.forEach((key) => window.localStorage.removeItem(key));
    return keys.length;
  } catch {
    return 0;
  }
}

export function notifyGridPreferencesReset(): void {
  window.dispatchEvent(new Event(GRID_PREFERENCES_RESET_EVENT));
}

function read<T>(storageKey: string, fallback: T, sanitize: (value: unknown, fallback: T) => T): T {
  try {
    const raw = window.localStorage.getItem(storageKey);
    return raw ? sanitize(JSON.parse(raw), fallback) : fallback;
  } catch {
    return fallback;
  }
}

function sanitizeGridState(value: unknown, fallback: PersistedGridState = EMPTY_GRID_STATE): PersistedGridState {
  if (!value || typeof value !== "object") return fallback;
  const candidate = value as Partial<PersistedGridState>;
  return {
    groupColumns: sanitizeStringList(candidate.groupColumns ?? fallback.groupColumns),
    columnFilters: sanitizeFilterMap(candidate.columnFilters ?? fallback.columnFilters),
  };
}

function sanitizeStringList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return [...new Set(value.filter((item): item is string => typeof item === "string" && item.trim().length > 0))];
}

function sanitizeFilterMap(value: unknown): Record<string, string> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  return Object.fromEntries(Object.entries(value)
    .filter((entry): entry is [string, string] => typeof entry[0] === "string" && typeof entry[1] === "string" && entry[1].trim().length > 0)
    .map(([key, filter]) => [key, filter]));
}

function resolve<T>(next: SetStateAction<T>, current: T): T {
  return typeof next === "function" ? (next as (value: T) => T)(current) : next;
}

function defaultSanitize<T>(value: unknown, fallback: T): T {
  return value === undefined ? fallback : value as T;
}

import { createContext, useContext, useMemo, useState, type ReactNode } from "react";
import { useLocation } from "react-router-dom";

interface GridDataRegistry {
  loadedKeys: ReadonlySet<string>;
  load: (key: string) => void;
  reset: (key: string) => void;
}

const GridDataRegistryContext = createContext<GridDataRegistry | null>(null);
const EnclosingGridLoadedContext = createContext(false);

/**
 * Route-scoped registry for deferred grid loading.
 *
 * This component intentionally renders the routed screen immediately. Earlier
 * versions replaced the complete screen with a Load button, which also hid
 * headings, forms, tabs and guidance. Data grids now own that interaction via
 * `useGridDataLoad`; this provider only remembers which grids were loaded while
 * the operator remains on a route.
 */
export function PageDataGate({ children }: { children: ReactNode }) {
  const location = useLocation();
  const [loadedKeys, setLoadedKeys] = useState<Set<string>>(() => new Set());
  const routePrefix = location.pathname;

  const value = useMemo<GridDataRegistry>(() => ({
    loadedKeys,
    load: (key) => setLoadedKeys((current) => {
      const next = new Set(current);
      next.add(`${routePrefix}::${key}`);
      return next;
    }),
    reset: (key) => setLoadedKeys((current) => {
      const next = new Set(current);
      next.delete(`${routePrefix}::${key}`);
      return next;
    }),
  }), [loadedKeys, routePrefix]);

  return <GridDataRegistryContext.Provider value={value}>{children}</GridDataRegistryContext.Provider>;
}

/** Stable load state shared by a grid and the query that supplies its rows. */
export function useGridDataLoad(gridKey: string) {
  const location = useLocation();
  const registry = useContext(GridDataRegistryContext);
  const scopedKey = `${location.pathname}::${gridKey}`;
  const loaded = registry ? registry.loadedKeys.has(scopedKey) : true;
  return {
    loaded,
    load: () => registry?.load(gridKey),
    reset: () => registry?.reset(gridKey),
  };
}

/** Prevents a DataTable nested in an already-loaded DataViewFrame from asking twice. */
export function GridDataLoadedScope({ children }: { children: ReactNode }) {
  return <EnclosingGridLoadedContext.Provider value>{children}</EnclosingGridLoadedContext.Provider>;
}

export function useEnclosingGridLoaded() {
  return useContext(EnclosingGridLoadedContext);
}

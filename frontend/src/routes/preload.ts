type RoutePreloader = () => Promise<unknown>;

const ROUTE_PRELOADERS: Array<[RegExp, RoutePreloader]> = [
  [/^\/$/, () => import("../pages/HomePage")],
  [/^\/pipeline$/, () => import("../pages/PipelinePage")],
  [/^\/accounts$/, () => import("../pages/AccountsPage")],
  [/^\/leads$/, () => import("../pages/LeadsPage")],
  [/^\/activities$/, () => import("../pages/ActivitiesPage")],
  [/^\/(products|price-books|quotes)$/, () => import("../pages/CpqPage")],
  [/^\/reference-data(?:\/.*)?$/, () => import("../pages/ReferenceDataPage")],
  [/^\/reports$/, () => import("../pages/ReportsPage")],
  [/^\/(?:forecast|contracts|campaigns|cases|partners|analytics|copilot|integrations|sandbox|automation|mobile|audit)$/, () => import("../pages/EpicWorkspacePage")],
  [/^\/migration$/, () => import("../pages/MigrationPage")],
  [/^\/packs\/(?:bfsi|commodity)$/, () => import("../pages/EpicWorkspacePage")],
  [/^\/integrations\/dispatch$/, () => import("../pages/IntegrationDispatchPage")],
  [/^\/search$/, () => import("../pages/SearchPage")],
  [/^\/admin(?:\/.*)?$/, () => import("../pages/AdminPage")],
  [/^\/access(?:\/.*)?$/, () => import("../pages/AccessGovernancePage")],
  [/^\/security\/authorization$/, () => import("../pages/RbacAdminPage")],
  [/^\/security\/activity$/, () => import("../pages/UserActivityPage")],
];

const warmed = new Set<string>();

export function preloadRoute(to: string | undefined): void {
  if (!to || warmed.has(to)) return;
  const path = to.split(/[?#]/, 1)[0] || "/";
  const match = ROUTE_PRELOADERS.find(([pattern]) => pattern.test(path));
  if (!match) return;
  warmed.add(to);
  void match[1]().catch(() => {
    warmed.delete(to);
  });
}

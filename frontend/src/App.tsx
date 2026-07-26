import { lazy, Suspense, type ComponentType } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { AppShell } from "./components/AppShell";
import { RequireAuth } from "./auth/RequireAuth";
import { PanelLoader } from "./components/Loaders";
import { type WorkspaceModule } from "./pages/EpicWorkspacePage";

type CpqSection = "products" | "price-books" | "quotes";
type AccessTab = "sso" | "requests" | "accounts";

interface CpqRouteProps {
  section: CpqSection;
}

interface WorkspaceRouteProps {
  module: WorkspaceModule;
}

interface AccessGovernanceRouteProps {
  initialTab?: AccessTab;
}

const LoginPage = lazyPage(() => import("./pages/LoginPage"), "LoginPage");
const ActivateAccountPage = lazyPage(() => import("./pages/ActivateAccountPage"), "ActivateAccountPage");
const HomePage = lazyPage(() => import("./pages/HomePage"), "HomePage");
const PipelinePage = lazyPage(() => import("./pages/PipelinePage"), "PipelinePage");
const AccountsPage = lazyPage(() => import("./pages/AccountsPage"), "AccountsPage");
const ContactsPage = lazyPage(() => import("./pages/ContactsPage"), "ContactsPage");
const LeadsPage = lazyPage(() => import("./pages/LeadsPage"), "LeadsPage");
const ActivitiesPage = lazyPage(() => import("./pages/ActivitiesPage"), "ActivitiesPage");
const CpqPage = lazyPage<CpqRouteProps>(() => import("./pages/CpqPage"), "CpqPage");
const ReferenceDataPage = lazyPage(() => import("./pages/ReferenceDataPage"), "ReferenceDataPage");
const ReportsPage = lazyPage(() => import("./pages/ReportsPage"), "ReportsPage");
const EpicWorkspacePage = lazyPage<WorkspaceRouteProps>(() => import("./pages/EpicWorkspacePage"), "EpicWorkspacePage");
const SearchPage = lazyPage(() => import("./pages/SearchPage"), "SearchPage");
const AdminPage = lazyPage(() => import("./pages/AdminPage"), "AdminPage");
const IntegrationDispatchPage = lazyPage(() => import("./pages/IntegrationDispatchPage"), "IntegrationDispatchPage");
const AccessGovernancePage = lazyPage<AccessGovernanceRouteProps>(() => import("./pages/AccessGovernancePage"), "AccessGovernancePage");
const RbacAdminPage = lazyPage(() => import("./pages/RbacAdminPage"), "RbacAdminPage");
const UserActivityPage = lazyPage(() => import("./pages/UserActivityPage"), "UserActivityPage");

export function App() {
  return (
    <Suspense fallback={<PanelLoader label="Loading Axiom workspace" detail="Preparing the selected module" />}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        {/* Unauthenticated: the one-time activation link a provisioned trial is sent. */}
        <Route path="/activate/:token" element={<ActivateAccountPage />} />
        <Route
          element={
            <RequireAuth>
              <AppShell />
            </RequireAuth>
          }
        >
          <Route path="/" element={<HomePage />} />
          <Route path="/pipeline" element={<PipelinePage />} />
          <Route path="/accounts" element={<AccountsPage />} />
          <Route path="/contacts" element={<ContactsPage />} />
          <Route path="/leads" element={<LeadsPage />} />
          <Route path="/activities" element={<ActivitiesPage />} />
          {workspaceRoute("/forecast", "forecast")}
          {cpqRoute("/products", "products")}
          {cpqRoute("/price-books", "price-books")}
          {cpqRoute("/quotes", "quotes")}
          {workspaceRoute("/contracts", "contracts")}
          {workspaceRoute("/campaigns", "campaigns")}
          {workspaceRoute("/cases", "cases")}
          {workspaceRoute("/partners", "partners")}
          {/* Bare /reference-data still resolves; the page redirects it onto the
              first master's canonical /reference-data/:setCode path. */}
          <Route path="/reference-data" element={<ReferenceDataPage />} />
          <Route path="/reference-data/:setCode" element={<ReferenceDataPage />} />
          <Route path="/reports" element={<ReportsPage />} />
          {workspaceRoute("/analytics", "analytics")}
          {workspaceRoute("/copilot", "copilot")}
          {workspaceRoute("/migration", "migration")}
          {workspaceRoute("/integrations", "integrations")}
          <Route path="/integrations/dispatch" element={<IntegrationDispatchPage />} />
          {workspaceRoute("/sandbox", "sandbox")}
          {workspaceRoute("/automation", "automation")}
          {workspaceRoute("/mobile", "mobile")}
          {workspaceRoute("/audit", "audit")}
          {workspaceRoute("/packs/bfsi", "bfsi")}
          {workspaceRoute("/packs/commodity", "commodity")}
          <Route path="/search" element={<SearchPage />} />
          <Route path="/admin/*" element={<AdminPage />} />
          {/* Access governance: SSO setup and the trial masters. */}
          <Route path="/access" element={<AccessGovernancePage />} />
          <Route path="/access/sso" element={<AccessGovernancePage initialTab="sso" />} />
          <Route path="/access/trial-requests" element={<AccessGovernancePage initialTab="requests" />} />
          <Route path="/access/trial-accounts" element={<AccessGovernancePage initialTab="accounts" />} />
          {/* Authorization: the RBAC engine's admin surface and the access log.
              Both pages gate themselves on the caller's role, mirroring the API. */}
          <Route path="/security/authorization" element={<RbacAdminPage />} />
          <Route path="/security/activity" element={<UserActivityPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Suspense>
  );
}

function lazyPage<TProps = object>(
  loader: () => Promise<Record<string, unknown>>,
  exportName: string,
) {
  return lazy(async () => {
    const module = await loader();
    return { default: module[exportName] as ComponentType<TProps> };
  });
}

function cpqRoute(path: string, section: CpqSection) {
  return <Route key={path} path={path} element={<CpqPage section={section} />} />;
}

function workspaceRoute(path: string, module: WorkspaceModule) {
  return <Route key={path} path={path} element={<EpicWorkspacePage module={module} />} />;
}

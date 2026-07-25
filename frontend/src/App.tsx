import { Navigate, Route, Routes } from "react-router-dom";
import { AppShell } from "./components/AppShell";
import { RequireAuth } from "./auth/RequireAuth";
import { LoginPage } from "./pages/LoginPage";
import { HomePage } from "./pages/HomePage";
import { PipelinePage } from "./pages/PipelinePage";
import { AccountsPage } from "./pages/AccountsPage";
import { LeadsPage } from "./pages/LeadsPage";
import { ReferenceDataPage } from "./pages/ReferenceDataPage";
import { ReportsPage } from "./pages/ReportsPage";
import { AdminPage } from "./pages/AdminPage";
import { ActivitiesPage } from "./pages/ActivitiesPage";
import { CpqPage } from "./pages/CpqPage";
import { EpicWorkspacePage } from "./pages/EpicWorkspacePage";

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
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
        <Route path="/leads" element={<LeadsPage />} />
        <Route path="/activities" element={<ActivitiesPage />} />
        <Route path="/forecast" element={<EpicWorkspacePage module="forecast" />} />
        <Route path="/products" element={<CpqPage section="products" />} />
        <Route path="/price-books" element={<CpqPage section="price-books" />} />
        <Route path="/quotes" element={<CpqPage section="quotes" />} />
        <Route path="/contracts" element={<EpicWorkspacePage module="contracts" />} />
        <Route path="/campaigns" element={<EpicWorkspacePage module="campaigns" />} />
        <Route path="/cases" element={<EpicWorkspacePage module="cases" />} />
        <Route path="/partners" element={<EpicWorkspacePage module="partners" />} />
        {/* Bare /reference-data still resolves; the page redirects it onto the
            first master's canonical /reference-data/:setCode path. */}
        <Route path="/reference-data" element={<ReferenceDataPage />} />
        <Route path="/reference-data/:setCode" element={<ReferenceDataPage />} />
        <Route path="/reports" element={<ReportsPage />} />
        <Route path="/analytics" element={<EpicWorkspacePage module="analytics" />} />
        <Route path="/copilot" element={<EpicWorkspacePage module="copilot" />} />
        <Route path="/migration" element={<EpicWorkspacePage module="migration" />} />
        <Route path="/integrations" element={<EpicWorkspacePage module="integrations" />} />
        <Route path="/sandbox" element={<EpicWorkspacePage module="sandbox" />} />
        <Route path="/automation" element={<EpicWorkspacePage module="automation" />} />
        <Route path="/mobile" element={<EpicWorkspacePage module="mobile" />} />
        <Route path="/audit" element={<EpicWorkspacePage module="audit" />} />
        <Route path="/packs/bfsi" element={<EpicWorkspacePage module="bfsi" />} />
        <Route path="/packs/commodity" element={<EpicWorkspacePage module="commodity" />} />
        <Route path="/admin/*" element={<AdminPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

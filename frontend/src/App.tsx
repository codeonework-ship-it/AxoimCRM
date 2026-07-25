import { Navigate, Route, Routes } from "react-router-dom";
import { AppShell } from "./components/AppShell";
import { RequireAuth } from "./auth/RequireAuth";
import { LoginPage } from "./pages/LoginPage";
import { HomePage } from "./pages/HomePage";
import { PipelinePage } from "./pages/PipelinePage";
import { AccountsPage } from "./pages/AccountsPage";
import { LeadsPage } from "./pages/LeadsPage";
import { ReferenceDataPage } from "./pages/ReferenceDataPage";

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
        <Route path="/reference-data" element={<ReferenceDataPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

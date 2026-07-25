import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, HashRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App } from "./App";
import { AuthProvider } from "./auth/AuthContext";
import { ToastProvider } from "./components/Toasts";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { I18nProvider } from "./i18n/I18nProvider";
import { DEFAULT_THEME, isThemeId } from "./components/ThemeSwitcher";
import { ApiUnreachableError } from "./api/client";
import { isDesktop } from "./lib/desktop";
/*
 * Stylesheet order is load-bearing:
 *   1. Bootstrap — foundation and utilities only.
 *   2. tokens    — the MOTORA palette, as custom properties.
 *   3. app       — component layout and structure.
 *   4. motora    — the cinematic skin. Loads LAST so it wins over both
 *                  Bootstrap Reboot's light defaults and app.css's earlier
 *                  surface rules.
 */
import "bootstrap/dist/css/bootstrap.min.css";
import "./styles/tokens.css";
import "./styles/app.css";
import "./styles/motora.css";

/*
 * Theme resolution. Axiom dark is the flagship, so it is also the DEFAULT:
 * a first-run user gets the cinematic console, not whatever the OS happens
 * to prefer. An explicit choice via the TopBar toggle always wins and is
 * remembered; "light" remains a first-class daylight-ops mode.
 */
const savedTheme = localStorage.getItem("axiom.theme");
document.documentElement.dataset.theme = isThemeId(savedTheme) ? savedTheme : DEFAULT_THEME;

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        // Don't hammer a dead API; the UI shows a retry button instead.
        if (error instanceof ApiUnreachableError) return false;
        return failureCount < 2;
      },
    },
  },
});

const Router = isDesktop() ? HashRouter : BrowserRouter;

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        {/*
          I18nProvider sits outside AuthProvider on purpose: the bundle endpoint
          is anonymous, so the login screen is translated too. Inside Auth it
          would only start resolving strings after sign-in.
        */}
        <I18nProvider>
          <Router>
            <AuthProvider>
              <ToastProvider>
                <App />
              </ToastProvider>
            </AuthProvider>
          </Router>
        </I18nProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
);

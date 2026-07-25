import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, HashRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App } from "./App";
import { AuthProvider } from "./auth/AuthContext";
import { ToastProvider } from "./components/Toasts";
import { ErrorBoundary } from "./components/ErrorBoundary";
import { ApiUnreachableError } from "./api/client";
import { isDesktop } from "./lib/desktop";
import "./styles/tokens.css";
import "./styles/app.css";

const savedTheme = localStorage.getItem("axiom.theme");
if (savedTheme === "light" || savedTheme === "dark") {
  document.documentElement.dataset.theme = savedTheme;
}

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
        <Router>
          <AuthProvider>
            <ToastProvider>
              <App />
            </ToastProvider>
          </AuthProvider>
        </Router>
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
);

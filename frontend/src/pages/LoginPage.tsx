import { useState, type FormEvent } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ApiError, isUnreachable } from "../api/client";

export function LoginPage() {
  const { login, isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [tenantSlug, setTenantSlug] = useState("meridian");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await login({ tenantSlug: tenantSlug.trim(), email: email.trim(), password });
      const from = (location.state as { from?: string } | null)?.from ?? "/";
      navigate(from, { replace: true });
    } catch (err) {
      if (isUnreachable(err)) {
        setError(
          "API unreachable — the Axiom backend at localhost:8080 is not responding.",
        );
      } else if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError("Sign-in failed unexpectedly. Try again.");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <section className="login-brand" aria-labelledby="login-product-title">
        <div className="wordmark">
          <img src="/axiom.svg" alt="" width={26} height={26} />
          Axiom <span>1.0</span>
        </div>

        <div className="login-product-copy">
          <p className="eyebrow">Enterprise revenue command</p>
          <h1 id="login-product-title" className="login-headline">
            Revenue work, finally <span className="gold">under command.</span>
          </h1>
          <p className="login-story">
            Axiom unifies CRM, pipeline governance, CPQ, reporting, audit evidence,
            tenant administration and role-led operations into one cinematic control
            surface. Every record is traceable, every export is governed, and every
            team works from the same revenue truth.
          </p>

          <div className="login-proof-grid" aria-label="Axiom product highlights">
            <div>
              <strong>23</strong>
              <span>enterprise modules mapped for expansion</span>
            </div>
            <div>
              <strong>100</strong>
              <span>rows per governed data-grid page</span>
            </div>
            <div>
              <strong>PDF · Excel · Word</strong>
              <span>report outputs designed into the workflow</span>
            </div>
          </div>
        </div>

        <div className="login-footline">Axiom · Version 1.0</div>
      </section>

      <section className="login-form-side app-ground">
        <form className="login-card" onSubmit={onSubmit}>
          <span className="eyebrow">Secure workspace</span>
          <h2>Sign in to Axiom</h2>
          <p className="sub">
            Use your workspace credentials. The default demo workspace is
            <strong> meridian</strong>.
          </p>

          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}

          <div className="field">
            <label className="label" htmlFor="tenant">
              Workspace
            </label>
            <input
              id="tenant"
              value={tenantSlug}
              onChange={(e) => setTenantSlug(e.target.value)}
              autoComplete="organization"
              required
            />
          </div>

          <div className="field">
            <label className="label" htmlFor="email">
              Email
            </label>
            <input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
              placeholder="you@company.com"
              required
            />
          </div>

          <div className="field">
            <label className="label" htmlFor="password">
              Password
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </div>

          <button className="btn btn-primary" type="submit" disabled={busy}>
            {busy ? "Signing in..." : "Sign in"}
          </button>

          <aside className="login-downloads" aria-label="Desktop client downloads">
            <div>
              <span className="eyebrow">Desktop client</span>
              <strong>Axiom 1.0 for focused operators</strong>
              <p>
                Download the desktop channel notes for Windows, Linux and macOS.
                Production installers are generated from the Electron client.
              </p>
            </div>
            <div className="download-grid">
              <a className="download-card" href="/downloads/axiom-desktop-windows-1.0.txt" download>
                <span>Windows</span>
                <small>x64 desktop channel</small>
              </a>
              <a className="download-card" href="/downloads/axiom-desktop-linux-1.0.txt" download>
                <span>Linux</span>
                <small>AppImage channel</small>
              </a>
              <a className="download-card" href="/downloads/axiom-desktop-macos-1.0.txt" download>
                <span>macOS</span>
                <small>Universal channel</small>
              </a>
            </div>
          </aside>
        </form>
      </section>
    </div>
  );
}

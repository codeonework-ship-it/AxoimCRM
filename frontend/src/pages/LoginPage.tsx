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
      <section className="login-brand" aria-hidden>
        <div className="wordmark">
          <img src="/axiom.svg" alt="" width={26} height={26} />
          Axiom CRM
        </div>
        <h1 className="login-headline">
          Every number <span className="gold">explains itself.</span>
        </h1>
        <div className="login-footline">Aegis operations console · v0.1</div>
      </section>

      <section className="login-form-side app-ground">
        <form className="login-card" onSubmit={onSubmit}>
          <h1>Sign in</h1>
          <p className="sub">Use your workspace credentials.</p>

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
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>
      </section>
    </div>
  );
}

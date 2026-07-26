import { useState, type FormEvent } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ApiError, isUnreachable } from "../api/client";
import { beginSso, discoverIdp, type IdpRoute } from "../api/access";
import { InlineLoader } from "../components/Loaders";
import { applyTheme, DEFAULT_THEME, isThemeId, THEMES, type ThemeId } from "../components/ThemeSwitcher";
import { TrialRequestDialog } from "./TrialRequestDialog";
import { InfoLabel } from "../components/InfoTag";

type Mode = "choose" | "credentials";

export function LoginPage() {
  const { login, isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [mode, setMode] = useState<Mode>("choose");
  const [tenantSlug, setTenantSlug] = useState("meridian");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [idp, setIdp] = useState<IdpRoute | null>(null);
  const [trialOpen, setTrialOpen] = useState(false);
  const [activeTheme, setActiveTheme] = useState<ThemeId>(() => {
    const current = document.documentElement.dataset.theme;
    return isThemeId(current) ? current : DEFAULT_THEME;
  });

  if (isAuthenticated) return <Navigate to="/" replace />;

  /**
   * SSO path. Three behaviours here are requirements, not polish:
   *
   * 1. A tenant with no identity provider is a NORMAL state, not an error, so a
   *    null route drops the user into the credentials form with an explanation
   *    rather than showing a failure.
   * 2. The browser is only sent to a provider when the handshake can actually
   *    come back. While `handshakeAvailable` is false the redirect would land on
   *    a 501 from the callback — the user would be stranded on a JSON error page
   *    with no way back to a form. Naming the provider and returning them to
   *    credentials is the honest outcome, and the server writes that wording.
   * 3. If anything fails, the user must still be able to get in. Every failure
   *    path below falls back to credentials and says why — a broken IdP
   *    configuration must never lock an administrator out of the system that
   *    would let them fix it.
   */
  async function continueWithSso(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const route = await discoverIdp(tenantSlug.trim(), email.trim());
      if (!route) {
        setMode("credentials");
        setNotice(
          `No single sign-on is configured for “${tenantSlug.trim()}”. Sign in with your Axiom credentials instead.`,
        );
        return;
      }
      setIdp(route);
      if (!route.handshakeAvailable) {
        setMode("credentials");
        setNotice(route.message);
        return;
      }
      const { redirectUrl } = await beginSso(route.id, tenantSlug.trim());
      window.location.assign(redirectUrl);
    } catch (err) {
      setMode("credentials");
      setNotice(
        isUnreachable(err)
          ? "Single sign-on is unreachable right now. You can still sign in with your credentials."
          : "Single sign-on could not start. Sign in with your credentials instead — your access is unaffected.",
      );
    } finally {
      setBusy(false);
    }
  }

  async function signIn(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await login({ tenantSlug: tenantSlug.trim(), email: email.trim(), password });
      sessionStorage.setItem("axiom.tronLoginLoader", "1");
      const from = (location.state as { from?: string } | null)?.from ?? "/";
      navigate(from, { replace: true });
    } catch (err) {
      sessionStorage.removeItem("axiom.tronLoginLoader");
      if (isUnreachable(err)) {
        setError("API unreachable — the Axiom backend at localhost:8080 is not responding.");
      } else if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError("Sign-in failed unexpectedly. Try again.");
      }
    } finally {
      setBusy(false);
    }
  }

  function chooseTheme(theme: ThemeId) {
    applyTheme(theme);
    setActiveTheme(theme);
  }

  return (
    <div className="login">
      {/* ---- Left: product story, proof, and the desktop downloads ---- */}
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
            Axiom unifies CRM, pipeline governance, CPQ, reporting, audit evidence, tenant
            administration and role-led operations into one control surface. Every record is
            traceable, every export is governed, and every team works from the same revenue truth.
          </p>

          <div className="login-proof-grid" aria-label="Axiom product highlights">
            <div><strong>23</strong><span>enterprise modules mapped for expansion</span></div>
            <div><strong>4</strong><span>governed environments: dev, QA, UAT, production</span></div>
            <div><strong>PDF · Excel · Word</strong><span>report outputs built into the workflow</span></div>
          </div>
        </div>

        {/*
          Downloads belong on the brand side. They are product collateral for
          someone evaluating Axiom, not a step in signing in — putting them in
          the form column made that column do two unrelated jobs and pushed the
          actual sign-in controls up and away from the eye's landing point.
        */}
        <aside className="login-downloads" aria-label="Desktop client downloads">
          <span className="eyebrow">Desktop client</span>
          <strong className="downloads-title">Axiom 1.0 for focused operators</strong>
          <p className="downloads-sub">
            Native shell with OS notifications and offline-aware caching. Same product, same data.
          </p>
          <div className="download-grid">
            <a className="download-card" href="/downloads/axiom-desktop-windows-1.0.txt" download>
              <span>Windows</span><small>x64 desktop channel</small>
            </a>
            <a className="download-card" href="/downloads/axiom-desktop-linux-1.0.txt" download>
              <span>Linux</span><small>AppImage channel</small>
            </a>
            <a className="download-card" href="/downloads/axiom-desktop-macos-1.0.txt" download>
              <span>macOS</span><small>Universal channel</small>
            </a>
          </div>
        </aside>

        <div className="login-footline">Axiom · Version 1.0</div>
      </section>

      {/* ---- Right: one task — get in ---- */}
      <section className="login-form-side app-ground">
        <div className="login-card">
          <span className="eyebrow">Secure workspace</span>
          <h2>Sign in to Axiom</h2>

          {notice && <p className="form-notice" role="status">{notice}</p>}
          {error && <p className="form-error" role="alert">{error}</p>}

          {mode === "choose" ? (
            <>
              <p className="sub">
                If your company uses Microsoft Entra ID, Active Directory or any SAML/OIDC provider,
                sign in with single sign-on — no separate Axiom password needed.
              </p>

              <form onSubmit={continueWithSso}>
                <div className="field">
                  <InfoLabel className="label" htmlFor="tenant">Workspace</InfoLabel>
                  <input id="tenant" value={tenantSlug} onChange={(e) => setTenantSlug(e.target.value)}
                    autoComplete="organization" required />
                </div>
                <div className="field">
                  <InfoLabel className="label" htmlFor="sso-email">Work email</InfoLabel>
                  <input id="sso-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)}
                    autoComplete="username" placeholder="you@company.com" required />
                </div>
                <button className="btn btn-primary btn-block" type="submit" disabled={busy}>
                  {busy ? <InlineLoader label="Checking" /> : "Continue with single sign-on"}
                </button>
              </form>

              {idp && <p className="sub sso-hint">Routing to <strong>{idp.displayName}</strong>…</p>}

              <div className="login-divider"><span>or</span></div>

              <button className="btn btn-block" type="button"
                onClick={() => { setMode("credentials"); setNotice(null); }}>
                Sign in with credentials
              </button>
            </>
          ) : (
            <>
              <p className="sub">
                Use your workspace credentials. The demo workspace is <strong>meridian</strong>.
              </p>

              <form onSubmit={signIn}>
                <div className="field">
                  <InfoLabel className="label" htmlFor="c-tenant">Workspace</InfoLabel>
                  <input id="c-tenant" value={tenantSlug} onChange={(e) => setTenantSlug(e.target.value)}
                    autoComplete="organization" required />
                </div>
                <div className="field">
                  <InfoLabel className="label" htmlFor="c-email">Email</InfoLabel>
                  <input id="c-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)}
                    autoComplete="username" placeholder="you@company.com" required />
                </div>
                <div className="field">
                  <InfoLabel className="label" htmlFor="c-password">Password</InfoLabel>
                  <input id="c-password" type="password" value={password}
                    onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
                </div>
                <button className="btn btn-primary btn-block" type="submit" disabled={busy}>
                  {busy ? <InlineLoader label="Signing in" /> : "Sign in"}
                </button>
              </form>

              <button className="link-btn login-back" type="button"
                onClick={() => { setMode("choose"); setError(null); setNotice(null); }}>
                ← Back to single sign-on
              </button>
            </>
          )}

          <div className="login-trial">
            <span className="eyebrow">No account yet?</span>
            <p>Evaluate the full product for 30 days with your own workspace and demo data.</p>
            <button className="btn btn-block" type="button" onClick={() => setTrialOpen(true)}>
              Request 30-day trial access
            </button>
          </div>
        </div>
      </section>

      <div className="login-theme-strip" aria-label="Theme selection">
        {THEMES.map((theme) => (
          <button
            key={theme.id}
            type="button"
            className={`login-theme-chip${theme.id === activeTheme ? " is-active" : ""}`}
            aria-label={`Use ${theme.name} theme`}
            aria-pressed={theme.id === activeTheme}
            title={theme.name}
            onClick={() => chooseTheme(theme.id)}
          >
            <span className="theme-swatch" aria-hidden>
              {theme.swatch.map((colour) => (
                <i key={colour} style={{ background: colour }} />
              ))}
            </span>
          </button>
        ))}
      </div>

      <TrialRequestDialog open={trialOpen} onClose={() => setTrialOpen(false)} />
    </div>
  );
}

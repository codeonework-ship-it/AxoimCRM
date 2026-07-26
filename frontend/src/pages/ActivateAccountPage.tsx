import { useState, type FormEvent } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { activateAccount } from "../api/access";
import { InlineLoader } from "../components/Loaders";

/**
 * Redeems the one-time activation link a provisioned trial account is sent.
 *
 * This page exists so that "we issue activation links instead of emailing
 * passwords" is a fact rather than a claim. The token in the URL is the entire
 * authority: it is single-use, expires, and is stored server-side only as a
 * hash, so nobody — including us — can turn the stored row back into a working
 * link.
 *
 * Every failure shows the same wording on purpose. Expired, already used and
 * never valid are different states, and telling an anonymous visitor which one
 * they hit would turn this page into an oracle for guessing tokens.
 */
export function ActivateAccountPage() {
  const { token } = useParams<{ token: string }>();
  const navigate = useNavigate();
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<{ email: string; tenantSlug: string; message: string } | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    if (password !== confirm) {
      setError("The two passwords do not match.");
      return;
    }
    setBusy(true);
    try {
      const outcome = await activateAccount(token ?? "", password);
      setDone(outcome);
    } catch (err) {
      setError((err as Error).message || "That activation link could not be used.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="panel" style={{ padding: 24, maxWidth: 460, margin: "48px auto" }}>
      <span className="eyebrow">Trial workspace</span>
      <h1>Activate your account</h1>

      {done ? (
        <div role="status">
          <p className="form-notice">{done.message}</p>
          <button className="btn btn-primary btn-block" onClick={() => navigate("/login")}>
            Go to sign in
          </button>
        </div>
      ) : (
        <form onSubmit={submit}>
          <p>
            Choose the password for your Axiom account. This link works once and then stops working, so
            finish here rather than saving it for later.
          </p>
          {error && <p className="form-error" role="alert">{error}</p>}
          <div className="field">
            <label className="label" htmlFor="activate-password">New password</label>
            <input id="activate-password" type="password" value={password} autoComplete="new-password"
              onChange={(event) => setPassword(event.target.value)} required />
          </div>
          <div className="field">
            <label className="label" htmlFor="activate-confirm">Confirm password</label>
            <input id="activate-confirm" type="password" value={confirm} autoComplete="new-password"
              onChange={(event) => setConfirm(event.target.value)} required />
          </div>
          <p className="form-notice">
            Your workspace's own password policy applies here — the same rules everyone else in it will be
            held to, not a weaker set for the first account.
          </p>
          <button className="btn btn-primary btn-block" type="submit" disabled={busy || !password}>
            {busy ? <InlineLoader label="Activating" /> : "Activate account"}
          </button>
        </form>
      )}
    </div>
  );
}

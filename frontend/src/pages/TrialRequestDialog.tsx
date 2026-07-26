import { useEffect, useRef, useState, type FormEvent } from "react";
import { requestTrial, type TrialOutcome } from "../api/access";
import { CloseIcon } from "../components/icons";
import { InfoLabel } from "../components/InfoTag";
import { InlineLoader } from "../components/Loaders";

/**
 * 30-day trial request.
 *
 * Deliberately short. Every extra field on a pre-sales form costs completions,
 * so this asks only for what a human actually needs to provision a workspace
 * and call the person back: who they are, where they work, and how to reach
 * them. Everything else is optional and clearly marked as such.
 */
export function TrialRequestDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [companyName, setCompanyName] = useState("");
  const [fullName, setFullName] = useState("");
  const [workEmail, setWorkEmail] = useState("");
  const [jobTitle, setJobTitle] = useState("");
  const [companySize, setCompanySize] = useState("");
  const [country, setCountry] = useState("");
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<TrialOutcome | null>(null);

  const dialogRef = useRef<HTMLDivElement>(null);
  const firstFieldRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open) return;
    firstFieldRef.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const outcome = await requestTrial({
        companyName: companyName.trim(),
        fullName: fullName.trim(),
        workEmail: workEmail.trim(),
        jobTitle: jobTitle.trim() || undefined,
        companySize: companySize || undefined,
        country: country.trim() || undefined,
        notes: notes.trim() || undefined,
      });
      setDone(outcome);
    } catch (err) {
      setError((err as Error).message || "Could not submit the request. Try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="trial-scrim" role="presentation" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="panel trial-dialog" role="dialog" aria-modal="true" aria-labelledby="trial-title" ref={dialogRef}>
        <header className="trial-head">
          <div>
            <span className="eyebrow">Evaluation access</span>
            <h2 id="trial-title">Request a 30-day trial</h2>
          </div>
          <button className="icon-btn" onClick={onClose} aria-label="Close trial request"><CloseIcon /></button>
        </header>

        {done ? (
          <div className="trial-done" role="status">
            <span className="eyebrow">Request received</span>
            <p className="trial-ref">
              Reference <strong className="mono">{done.reference}</strong>
            </p>
            <p>{done.message}</p>
            <p className="trial-note">
              Your workspace will be provisioned with a {done.trialDays}-day trial and a full set of
              demonstration data, so you can evaluate against realistic records rather than an empty
              system. We will email <strong>{workEmail}</strong> once it is ready.
            </p>
            <button className="btn btn-primary" onClick={onClose}>Close</button>
          </div>
        ) : (
          <form className="trial-form" onSubmit={submit}>
            <p className="trial-intro">
              Full product, 30 days, no payment details. You get an isolated workspace with your own
              administrator and auditor accounts, seeded with demonstration data.
            </p>

            {error && <p className="form-error" role="alert">{error}</p>}

            <div className="trial-grid">
              <div className="field">
                <InfoLabel className="label" htmlFor="t-company">Company name</InfoLabel>
                <input id="t-company" ref={firstFieldRef} value={companyName}
                  onChange={(e) => setCompanyName(e.target.value)} required autoComplete="organization" />
              </div>
              <div className="field">
                <InfoLabel className="label" htmlFor="t-name">Full name</InfoLabel>
                <input id="t-name" value={fullName} onChange={(e) => setFullName(e.target.value)}
                  required autoComplete="name" />
              </div>
              <div className="field">
                <InfoLabel className="label" htmlFor="t-email">Work email</InfoLabel>
                <input id="t-email" type="email" value={workEmail} onChange={(e) => setWorkEmail(e.target.value)}
                  required autoComplete="email" placeholder="you@company.com" />
              </div>
              <div className="field">
                <InfoLabel className="label" htmlFor="t-title" info="Your role helps us set up useful demo examples.">Job title</InfoLabel>
                <span className="opt">optional</span>
                <input id="t-title" value={jobTitle} onChange={(e) => setJobTitle(e.target.value)}
                  autoComplete="organization-title" />
              </div>
              <div className="field">
                <InfoLabel className="label" htmlFor="t-size">Company size</InfoLabel>
                <span className="opt">optional</span>
                <select id="t-size" value={companySize} onChange={(e) => setCompanySize(e.target.value)}>
                  <option value="">Select…</option>
                  <option>1–50</option>
                  <option>51–250</option>
                  <option>251–1000</option>
                  <option>1001–5000</option>
                  <option>5000+</option>
                </select>
              </div>
              <div className="field">
                <InfoLabel className="label" htmlFor="t-country">Country</InfoLabel>
                <span className="opt">optional</span>
                <input id="t-country" value={country} onChange={(e) => setCountry(e.target.value)}
                  autoComplete="country-name" />
              </div>
            </div>

            <div className="field">
              <InfoLabel className="label" htmlFor="t-notes" info="Tell us what you want to test so the trial can be prepared around it.">What do you want to evaluate?</InfoLabel>
              <span className="opt">optional</span>
              <textarea id="t-notes" rows={3} value={notes} onChange={(e) => setNotes(e.target.value)}
                placeholder="e.g. pipeline governance and CPQ approvals for a 40-seat sales team" />
            </div>

            <div className="trial-actions">
              <button className="btn" type="button" onClick={onClose}>Cancel</button>
              <button className="btn btn-primary" type="submit" disabled={busy}>
                {busy ? <InlineLoader label="Submitting" /> : "Request trial access"}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}

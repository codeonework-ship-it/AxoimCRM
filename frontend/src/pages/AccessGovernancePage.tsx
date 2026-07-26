import { Fragment, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  idpApi,
  stepUp,
  trialApi,
  type ApprovalResult,
  type IdpConfig,
  type IdpMutation,
  type IdpProtocol,
  type IdpTestResult,
  type TrialRequestRow,
} from "../api/access";
import { useAuth } from "../auth/AuthContext";
import { GridLoader, InlineLoader, LoaderStatus, PanelLoader } from "../components/Loaders";
import { useToasts } from "../components/Toasts";

/**
 * Anonymous access governance.
 *
 * Three surfaces, in the order an administrator meets them: who can sign in
 * with their own identity provider, who has asked for a trial, and what state
 * the trials that were granted are in.
 *
 * Two things are stated on screen rather than left implicit, because both are
 * the sort of thing people only discover during an incident:
 *
 *   1. Local password sign-in is never disabled by anything on this page. A
 *      misconfigured provider cannot lock administrators out (FR-TEN-004).
 *   2. A stored client secret is never returned by the API. The field shows a
 *      mask; typing into it rotates the secret, leaving it blank keeps it.
 */

const PLATFORM_ROLES = new Set(["SUPER_ADMIN", "SUPER_AUDIT"]);
const SSO_ROLES = new Set(["SUPER_ADMIN", "TENANT_ADMIN"]);
const READ_ONLY_ROLES = new Set(["SUPER_AUDIT", "AUDITOR"]);

const STATUS_FILTERS = ["ALL", "PENDING", "APPROVED", "PROVISIONED", "REJECTED", "EXPIRED"] as const;

const EMPTY_IDP: IdpMutation & { certificate: string } = {
  protocol: "OIDC",
  displayName: "",
  enabled: false,
  emailDomain: "",
  entityId: "",
  ssoUrl: "",
  certificate: "",
  clientId: "",
  clientSecret: "",
  discoveryUrl: "",
  attributeMap: { email: "email", displayName: "name" },
};

type TabKey = "sso" | "requests" | "accounts";

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : "Request failed.";
}

/** True when the API refused because the session has not been re-authenticated. */
function needsStepUp(error: unknown): boolean {
  const message = messageOf(error).toLowerCase();
  return message.includes("step-up") || message.includes("confirm your password");
}

function when(value: string | null | undefined): string {
  if (!value) return "-";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? "-" : parsed.toLocaleDateString();
}

function requestChip(status: string): string {
  switch (status) {
    case "PROVISIONED": return "chip chip-accepted";
    case "APPROVED": return "chip chip-active";
    case "PENDING": return "chip chip-in_approval";
    case "REJECTED": return "chip chip-rejected";
    case "EXPIRED": return "chip chip-expired";
    default: return "chip";
  }
}

function accountChip(status: string): string {
  switch (status) {
    case "ACTIVE": return "chip chip-accepted";
    case "TRIAL": return "chip chip-active";
    case "PAST_DUE": return "chip chip-in_approval";
    case "SUSPENDED":
    case "INACTIVE": return "chip chip-rejected";
    default: return "chip";
  }
}

/** Whole days from today to the trial end. Negative means it has already lapsed. */
function daysRemaining(trialEndsAt: string | null): number | null {
  if (!trialEndsAt) return null;
  const end = new Date(trialEndsAt);
  if (Number.isNaN(end.getTime())) return null;
  const today = new Date();
  const startOfToday = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate());
  const endUtc = Date.UTC(end.getFullYear(), end.getMonth(), end.getDate());
  return Math.round((endUtc - startOfToday) / 86_400_000);
}

function riskChip(days: number | null): { className: string; label: string } {
  if (days === null) return { className: "chip", label: "No trial window" };
  if (days < 0) return { className: "chip chip-rejected", label: `Lapsed ${Math.abs(days)}d ago` };
  if (days === 0) return { className: "chip chip-rejected", label: "Ends today" };
  if (days <= 7) return { className: "chip chip-in_approval", label: `${days}d left` };
  return { className: "chip chip-active", label: `${days}d left` };
}

export function AccessGovernancePage({ initialTab }: { initialTab?: TabKey }) {
  const { user } = useAuth();
  const toasts = useToasts();
  const queryClient = useQueryClient();

  const role = user?.role ?? "";
  const platform = PLATFORM_ROLES.has(role);
  const canConfigureSso = SSO_ROLES.has(role);
  const readOnly = READ_ONLY_ROLES.has(role);

  const tabs = useMemo(() => {
    const list: { key: TabKey; label: string }[] = [];
    if (canConfigureSso) list.push({ key: "sso", label: "SSO setup" });
    if (platform) list.push({ key: "requests", label: "Trial requests" });
    if (platform) list.push({ key: "accounts", label: "Trial accounts" });
    return list;
  }, [canConfigureSso, platform]);

  const [tab, setTab] = useState<TabKey>(
    initialTab && tabs.some((t) => t.key === initialTab) ? initialTab : (tabs[0]?.key ?? "sso"),
  );

  // ---------------------------------------------------------------- step-up
  const [stepUpFor, setStepUpFor] = useState<string | null>(null);
  const [stepUpPassword, setStepUpPassword] = useState("");
  const confirmStepUp = useMutation({
    mutationFn: () => stepUp(stepUpPassword),
    onSuccess: () => {
      setStepUpFor(null);
      setStepUpPassword("");
      toasts.push("info", "Identity confirmed", "Repeat the action — the confirmation lasts five minutes.");
    },
    onError: (error) => toasts.push("error", "Confirmation failed", messageOf(error)),
  });

  function handle(title: string, error: unknown) {
    if (needsStepUp(error)) {
      setStepUpFor(title);
      return;
    }
    toasts.push("error", title, messageOf(error));
  }

  // ---------------------------------------------------------------- SSO state
  const idpQ = useQuery({ queryKey: ["access", "idp"], queryFn: idpApi.list, enabled: canConfigureSso, retry: 1 });
  const certificateAlertsQ = useQuery({ queryKey: ["access", "idp", "certificate-alerts"],
    queryFn: idpApi.certificateAlerts, enabled: canConfigureSso, retry: 1 });
  const [idpDraft, setIdpDraft] = useState(EMPTY_IDP);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [attributeText, setAttributeText] = useState("email=email\ndisplayName=name");
  const [testResult, setTestResult] = useState<{ name: string; result: IdpTestResult } | null>(null);
  const [routeProbe, setRouteProbe] = useState("");
  const [routeAnswer, setRouteAnswer] = useState<string | null>(null);

  function parseAttributes(text: string): Record<string, string> {
    const map: Record<string, string> = {};
    text.split("\n").forEach((line) => {
      const [key, ...rest] = line.split("=");
      if (key?.trim() && rest.length) map[key.trim()] = rest.join("=").trim();
    });
    return map;
  }

  function payload(): IdpMutation {
    return {
      protocol: idpDraft.protocol,
      displayName: idpDraft.displayName.trim(),
      enabled: idpDraft.enabled,
      emailDomain: idpDraft.emailDomain?.trim() || null,
      entityId: idpDraft.entityId?.trim() || null,
      ssoUrl: idpDraft.ssoUrl?.trim() || null,
      certificate: idpDraft.certificate?.trim() || null,
      clientId: idpDraft.clientId?.trim() || null,
      // Blank means "leave the stored secret alone". The mask is never sent back.
      clientSecret: idpDraft.clientSecret?.trim() || null,
      discoveryUrl: idpDraft.discoveryUrl?.trim() || null,
      attributeMap: parseAttributes(attributeText),
    };
  }

  function resetIdpForm() {
    setIdpDraft(EMPTY_IDP);
    setAttributeText("email=email\ndisplayName=name");
    setEditingId(null);
  }

  const saveIdp = useMutation({
    mutationFn: () => (editingId ? idpApi.update(editingId, payload()) : idpApi.create(payload())),
    onSuccess: (row) => {
      toasts.push("info", editingId ? "Provider updated" : "Provider added",
        `${row.displayName} is stored. Local password sign-in is unaffected.`);
      resetIdpForm();
      void queryClient.invalidateQueries({ queryKey: ["access", "idp"] });
    },
    onError: (error) => handle("Provider not saved", error),
  });

  const removeIdp = useMutation({
    mutationFn: (id: string) => idpApi.remove(id),
    onSuccess: () => {
      toasts.push("info", "Provider removed", "Users on that domain fall back to password sign-in.");
      resetIdpForm();
      void queryClient.invalidateQueries({ queryKey: ["access", "idp"] });
    },
    onError: (error) => handle("Provider not removed", error),
  });

  const testIdp = useMutation({
    mutationFn: (row: IdpConfig) => idpApi.test(row.id).then((result) => ({ name: row.displayName, result })),
    onSuccess: (value) => setTestResult(value),
    onError: (error) => handle("Test could not run", error),
  });

  const probeRoute = useMutation({
    mutationFn: () => idpApi.route(routeProbe.trim()),
    onSuccess: (answer) => setRouteAnswer(answer.note),
    onError: (error) => handle("Routing check failed", error),
  });
  const sweepCertificates = useMutation({
    mutationFn: idpApi.sweepCertificateAlerts,
    onSuccess: (result) => {
      toasts.push("info", "Certificate control completed", result.alertsCreated
        ? `${result.alertsCreated} new administrator warning(s) were created.`
        : "No new warnings were needed; previous warnings remain deduplicated.");
      void certificateAlertsQ.refetch();
      void queryClient.invalidateQueries({ queryKey: ["notifications"] });
    },
    onError: (error) => toasts.push("error", "Certificate control failed", messageOf(error)),
  });

  function editIdp(row: IdpConfig) {
    setEditingId(row.id);
    setIdpDraft({
      protocol: row.protocol,
      displayName: row.displayName,
      enabled: row.enabled,
      emailDomain: row.emailDomain ?? "",
      entityId: row.entityId ?? "",
      ssoUrl: row.ssoUrl ?? "",
      certificate: "",
      clientId: row.clientId ?? "",
      clientSecret: "",
      discoveryUrl: row.discoveryUrl ?? "",
      attributeMap: row.attributeMap,
    });
    setAttributeText(Object.entries(row.attributeMap ?? {}).map(([k, v]) => `${k}=${v}`).join("\n"));
  }

  // ------------------------------------------------------------- trial state
  const [statusFilter, setStatusFilter] = useState<string>("PENDING");
  const requestsQ = useQuery({
    queryKey: ["access", "trial-requests", statusFilter],
    queryFn: () => trialApi.requests(statusFilter),
    enabled: platform,
    retry: 1,
  });
  const companiesQ = useQuery({
    queryKey: ["access", "companies"],
    queryFn: trialApi.companies,
    enabled: platform,
    retry: 1,
  });

  const [openRequestId, setOpenRequestId] = useState<string | null>(null);
  const [rejectReason, setRejectReason] = useState("");
  const [approvalNote, setApprovalNote] = useState("");
  const [approved, setApproved] = useState<ApprovalResult | null>(null);

  function refreshTrials() {
    void queryClient.invalidateQueries({ queryKey: ["access", "trial-requests"] });
    void queryClient.invalidateQueries({ queryKey: ["access", "companies"] });
  }

  const approve = useMutation({
    mutationFn: (row: TrialRequestRow) => trialApi.approve(row.id, approvalNote.trim() || undefined),
    onSuccess: (result) => {
      setApproved(result);
      setApprovalNote("");
      setOpenRequestId(null);
      toasts.push("info", result.created ? "Workspace provisioned" : "Already provisioned", result.note);
      refreshTrials();
    },
    onError: (error) => handle("Approval refused", error),
  });

  const reject = useMutation({
    mutationFn: (row: TrialRequestRow) => trialApi.reject(row.id, rejectReason.trim()),
    onSuccess: (row) => {
      setRejectReason("");
      setOpenRequestId(null);
      toasts.push("info", "Request declined", `${row.reference} was declined and the reason recorded.`);
      refreshTrials();
    },
    onError: (error) => handle("Rejection refused", error),
  });

  const expireStale = useMutation({
    mutationFn: () => trialApi.expireStale(),
    onSuccess: (result) => {
      toasts.push("info", "Queue swept", result.message);
      refreshTrials();
    },
    onError: (error) => handle("Sweep refused", error),
  });

  const extend = useMutation({
    mutationFn: (tenantId: string) =>
      trialApi.extendTrial(tenantId, 14, "Extended from the trial accounts master"),
    onSuccess: () => {
      toasts.push("info", "Trial extended", "Fourteen days were added, within the configured maximum.");
      refreshTrials();
    },
    onError: (error) => handle("Extension refused", error),
  });

  const convert = useMutation({
    mutationFn: (tenantId: string) =>
      trialApi.setCompanyStatus(tenantId, "ACTIVE", "Converted to a paid subscription"),
    onSuccess: () => {
      toasts.push("info", "Converted to paid", "The account is now ACTIVE and the trial window no longer applies.");
      refreshTrials();
    },
    onError: (error) => handle("Conversion refused", error),
  });

  // ---------------------------------------------------------------- render
  if (tabs.length === 0) {
    return <>
      <div className="page-head">
        <div>
          <span className="eyebrow">Access governance</span>
          <h1>Anonymous access</h1>
          <p>Single sign-on setup and trial account governance.</p>
        </div>
      </div>
      <div className="panel" style={{ padding: 16 }}>
        <p className="empty-note">
          Your role does not include access governance. Single sign-on setup needs Tenant Admin or Super
          Admin; the trial masters need a platform role.
        </p>
      </div>
    </>;
  }

  return <>
    <div className="page-head">
      <div>
        <span className="eyebrow">Access governance</span>
        <h1>Anonymous access</h1>
        <p>Identity providers, public trial requests and the trials already granted.</p>
      </div>
      {readOnly && <span className="count">Read-only audit mode</span>}
    </div>

    <div className="master-tabs" role="tablist" aria-label="Access governance sections">
      {tabs.map((entry) => <button
        key={entry.key}
        type="button"
        className="master-tab"
        role="tab"
        aria-selected={tab === entry.key}
        onClick={() => setTab(entry.key)}
      >
        <span className="master-tab-label">{entry.label}</span>
      </button>)}
    </div>

    {stepUpFor && <div className="panel" style={{ padding: 16, marginBottom: 14 }}>
      <span className="eyebrow">Confirm it is you</span>
      <p className="form-notice">
        {stepUpFor} needs your password again before it will run. This is the standard guard on actions
        that create tenants or change what a customer is paying for.
      </p>
      <div className="field">
        <label className="label" htmlFor="stepup-password">Password</label>
        <input id="stepup-password" type="password" value={stepUpPassword} autoComplete="current-password"
          onChange={(e) => setStepUpPassword(e.target.value)} />
      </div>
      <button className="btn btn-primary btn-sm" disabled={confirmStepUp.isPending || !stepUpPassword}
        onClick={() => confirmStepUp.mutate()}>
        {confirmStepUp.isPending ? <InlineLoader label="Confirming" /> : "Confirm"}
      </button>
      <button className="btn btn-sm" style={{ marginLeft: 8 }}
        onClick={() => { setStepUpFor(null); setStepUpPassword(""); }}>Cancel</button>
    </div>}

    {/* ================================================================ SSO */}
    {tab === "sso" && canConfigureSso && <>
      <div className="panel" style={{ padding: "12px 16px", marginBottom: 14 }}>
        <p className="form-notice" style={{ margin: 0 }}>
          Local password sign-in always remains available, whatever is configured here. Nothing on this
          screen can switch it off, so a broken provider cannot lock administrators out of the workspace.
          Stored client secrets are never returned by the API — the field below shows a mask, and leaving
          it empty keeps the existing secret.
        </p>
      </div>

      {idpQ.isLoading && <GridLoader label="Reading identity providers" rows={3} columns={6} />}
      {idpQ.isError && <p className="empty-note">Providers failed to load: {messageOf(idpQ.error)}</p>}
      {idpQ.isSuccess && idpQ.data.length === 0
        && <p className="empty-note">No identity provider is configured. Users sign in with their Axiom password.</p>}

      {idpQ.isSuccess && idpQ.data.length > 0 && <div className="panel" style={{ padding: 16, marginBottom: 14 }}>
        <div className="page-head compact-head"><div><span className="eyebrow">Certificate readiness</span>
          <p>Enabled SAML providers are checked daily. Run the same idempotent check now after rotating a certificate.</p></div>
          <button type="button" className="btn btn-sm" disabled={sweepCertificates.isPending}
            onClick={() => sweepCertificates.mutate()}>{sweepCertificates.isPending ? "Checking..." : "Check certificate expiry"}</button></div>
        <div className="table-wrap"><table className="data-table">
          <thead><tr>
            <th>Provider</th><th>Protocol</th><th>Routes</th><th>State</th>
            <th>Secret</th><th>Certificate</th><th>Updated</th>
            {!readOnly && <th className="table-action">Action</th>}
          </tr></thead>
          <tbody>{idpQ.data.map((row) => <Fragment key={row.id}>
            <tr>
              <td>{row.displayName}</td>
              <td>{row.protocol}</td>
              <td>{row.emailDomain ? <span className="mono">{row.emailDomain}</span> : "Workspace default"}</td>
              <td><span className={row.enabled ? "chip chip-active" : "chip chip-draft"}>
                {row.enabled ? "Enabled" : "Disabled"}</span></td>
              <td>{row.clientSecret
                ? <span className="mono">{row.clientSecret}</span>
                : <span className="chip chip-draft">None stored</span>}</td>
              <td>
                {row.certificatePresent
                  ? <span className={row.certificateExpiringSoon ? "chip chip-in_approval" : "chip chip-active"}>
                      {row.certificateNotAfter ? `Expires ${when(row.certificateNotAfter)}` : "Stored"}
                    </span>
                  : <span className="chip chip-draft">None</span>}
              </td>
              <td>{when(row.updatedAt)}</td>
              {!readOnly && <td className="table-action">
                <button className="link-btn" onClick={() => editIdp(row)}>Edit</button>
                <button className="link-btn" onClick={() => testIdp.mutate(row)}>Test configuration</button>
                <button className="link-btn danger-link" onClick={() => removeIdp.mutate(row.id)}>Remove</button>
              </td>}
            </tr>
          </Fragment>)}</tbody>
        </table></div>
      </div>}
      {certificateAlertsQ.isSuccess && certificateAlertsQ.data.length > 0 && <div className="panel" style={{ padding: 16, marginBottom: 14 }}>
        <span className="eyebrow">Recent certificate alerts</span>
        <div className="table-wrap"><table className="data-table"><thead><tr><th>Provider</th><th>Severity</th><th>Certificate expiry</th><th>Administrators notified</th></tr></thead>
          <tbody>{certificateAlertsQ.data.map((alert) => <tr key={alert.id}><td>{alert.providerName}</td>
            <td><span className={alert.severity === "EXPIRED" ? "chip chip-crit" : "chip chip-in_approval"}>{alert.severity}</span></td>
            <td>{when(alert.certificateNotAfter)}</td><td>{alert.recipientCount}</td></tr>)}</tbody></table></div>
      </div>}

      {testIdp.isPending && <LoaderStatus label="Checking provider configuration" />}

      {testResult && <div className="panel" style={{ padding: 16, marginBottom: 14 }}>
        <span className="eyebrow">Test configuration</span>
        <h2>{testResult.name}</h2>
        <p>
          <span className={testResult.result.ready ? "chip chip-accepted" : "chip chip-rejected"}>
            {testResult.result.ready ? "Ready to enable" : "Not ready"}
          </span>
        </p>
        {testResult.result.problems.length > 0 && <ul>
          {testResult.result.problems.map((problem) => <li key={problem} className="form-error">{problem}</li>)}
        </ul>}
        {testResult.result.warnings.length > 0 && <ul>
          {testResult.result.warnings.map((warning) => <li key={warning} className="form-notice">{warning}</li>)}
        </ul>}
        <p className="form-notice">{testResult.result.note}</p>
        <button className="btn btn-sm" onClick={() => setTestResult(null)}>Dismiss</button>
      </div>}

      {!readOnly && <div className="panel" style={{ padding: 16, marginBottom: 14 }}>
        <span className="eyebrow">{editingId ? "Edit provider" : "Add provider"}</span>
        <h2>{editingId ? idpDraft.displayName || "Identity provider" : "New identity provider"}</h2>

        <div className="field">
          <label className="label" htmlFor="idp-protocol">Protocol</label>
          <select id="idp-protocol" value={idpDraft.protocol}
            onChange={(e) => setIdpDraft((v) => ({ ...v, protocol: e.target.value as IdpProtocol }))}>
            <option value="OIDC">OIDC</option>
            <option value="SAML2">SAML 2.0</option>
          </select>
        </div>
        <div className="field">
          <label className="label" htmlFor="idp-name">Display name</label>
          <input id="idp-name" value={idpDraft.displayName}
            onChange={(e) => setIdpDraft((v) => ({ ...v, displayName: e.target.value }))}
            placeholder="Okta — Corporate" />
        </div>
        <div className="field">
          <label className="label" htmlFor="idp-domain">Email domain routing</label>
          <input id="idp-domain" value={idpDraft.emailDomain ?? ""}
            onChange={(e) => setIdpDraft((v) => ({ ...v, emailDomain: e.target.value }))}
            placeholder="meridianfab.com — leave empty for the workspace default" />
        </div>

        {idpDraft.protocol === "SAML2" ? <>
          <div className="field">
            <label className="label" htmlFor="idp-entity">IdP entity ID (issuer)</label>
            <input id="idp-entity" value={idpDraft.entityId ?? ""}
              onChange={(e) => setIdpDraft((v) => ({ ...v, entityId: e.target.value }))} />
          </div>
          <div className="field">
            <label className="label" htmlFor="idp-sso">Single sign-on URL</label>
            <input id="idp-sso" value={idpDraft.ssoUrl ?? ""}
              onChange={(e) => setIdpDraft((v) => ({ ...v, ssoUrl: e.target.value }))}
              placeholder="https://idp.example.com/sso/saml" />
          </div>
          <div className="field">
            <label className="label" htmlFor="idp-cert">Signing certificate (PEM)</label>
            <textarea id="idp-cert" rows={4} value={idpDraft.certificate}
              onChange={(e) => setIdpDraft((v) => ({ ...v, certificate: e.target.value }))}
              placeholder={editingId ? "Leave empty to keep the stored certificate" : "-----BEGIN CERTIFICATE-----"} />
          </div>
        </> : <>
          <div className="field">
            <label className="label" htmlFor="idp-client">Client ID</label>
            <input id="idp-client" value={idpDraft.clientId ?? ""}
              onChange={(e) => setIdpDraft((v) => ({ ...v, clientId: e.target.value }))} />
          </div>
          <div className="field">
            <label className="label" htmlFor="idp-secret">Client secret</label>
            <input id="idp-secret" type="password" value={idpDraft.clientSecret ?? ""}
              autoComplete="off"
              onChange={(e) => setIdpDraft((v) => ({ ...v, clientSecret: e.target.value }))}
              placeholder={editingId ? "Leave empty to keep the stored secret" : "Stored encrypted; never shown again"} />
          </div>
          <div className="field">
            <label className="label" htmlFor="idp-discovery">Discovery URL</label>
            <input id="idp-discovery" value={idpDraft.discoveryUrl ?? ""}
              onChange={(e) => setIdpDraft((v) => ({ ...v, discoveryUrl: e.target.value }))}
              placeholder="https://idp.example.com/.well-known/openid-configuration" />
          </div>
          <div className="field">
            <label className="label" htmlFor="idp-authz">Authorization endpoint</label>
            <input id="idp-authz" value={idpDraft.ssoUrl ?? ""}
              onChange={(e) => setIdpDraft((v) => ({ ...v, ssoUrl: e.target.value }))}
              placeholder="Only needed when there is no discovery document" />
          </div>
        </>}

        <div className="field">
          <label className="label" htmlFor="idp-attrs">Attribute mapping — one <span className="mono">key=claim</span> per line</label>
          <textarea id="idp-attrs" rows={3} value={attributeText}
            onChange={(e) => setAttributeText(e.target.value)} />
        </div>
        <div className="field">
          <label className="label" htmlFor="idp-enabled">
            <input id="idp-enabled" type="checkbox" checked={idpDraft.enabled}
              onChange={(e) => setIdpDraft((v) => ({ ...v, enabled: e.target.checked }))} />
            {" "}Enabled — routes matching users to this provider
          </label>
        </div>

        <button className="btn btn-primary btn-sm" disabled={saveIdp.isPending || !idpDraft.displayName.trim()}
          onClick={() => saveIdp.mutate()}>
          {saveIdp.isPending ? <InlineLoader label="Saving" /> : editingId ? "Save changes" : "Add provider"}
        </button>
        {editingId && <button className="btn btn-sm" style={{ marginLeft: 8 }} onClick={resetIdpForm}>Cancel</button>}
      </div>}

      <div className="panel" style={{ padding: 16 }}>
        <span className="eyebrow">Routing check</span>
        <h2>Which provider would serve an address?</h2>
        <div className="field">
          <label className="label" htmlFor="idp-probe">Email address</label>
          <input id="idp-probe" value={routeProbe} onChange={(e) => setRouteProbe(e.target.value)}
            placeholder="someone@meridianfab.com" />
        </div>
        <button className="btn btn-sm" disabled={probeRoute.isPending || !routeProbe.trim()}
          onClick={() => probeRoute.mutate()}>
          {probeRoute.isPending ? <InlineLoader label="Checking" /> : "Check routing"}
        </button>
        {routeAnswer && <p className="form-notice" style={{ marginTop: 12 }}>{routeAnswer}</p>}
      </div>
    </>}

    {/* =================================================== TRIAL REQUESTS */}
    {tab === "requests" && platform && <>
      <div className="panel" style={{ padding: "12px 16px", marginBottom: 14 }}>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 8, alignItems: "center" }}>
          {STATUS_FILTERS.map((value) => <button key={value} type="button" className="btn btn-sm"
            aria-pressed={statusFilter === value} onClick={() => setStatusFilter(value)}>{value}</button>)}
          <span style={{ marginLeft: "auto", display: "flex", gap: 8, alignItems: "center" }}>
            {expireStale.isPending && <InlineLoader label="Sweeping" />}
            <button className="btn btn-sm" onClick={() => refreshTrials()}>Refresh</button>
            {!readOnly && <button className="btn btn-sm" disabled={expireStale.isPending}
              onClick={() => expireStale.mutate()}>Expire stale requests</button>}
          </span>
        </div>
      </div>

      {approved && <div className="panel" style={{ padding: 16, marginBottom: 14 }}>
        <span className="eyebrow">Provisioned</span>
        <h2>{approved.companyName} — <span className="mono">{approved.slug}</span></h2>
        <p>{approved.note}</p>
        <p>
          <span className="chip chip-accepted">{approved.tenantAdminCount} tenant admin</span>{" "}
          <span className="chip chip-accepted">{approved.auditorCount} auditor</span>{" "}
          <span className="chip chip-active">{approved.trialDays}-day trial to {when(approved.trialEndsAt)}</span>
        </p>
        {approved.demoData.length > 0
          && <p className="form-notice">Seeded so the trial is evaluable on day one: {approved.demoData.join(", ")}.</p>}
        {approved.activationLinks.length > 0 && <>
          <p className="form-notice">
            These links are shown once and cannot be recovered. Send each to its owner — they are
            single-use and expire.
          </p>
          <div className="table-wrap"><table className="data-table">
            <thead><tr><th>Account</th><th>Role</th><th>Activation link</th><th>Expires</th></tr></thead>
            <tbody>{approved.activationLinks.map((link) => <tr key={link.url}>
              <td>{link.email}</td>
              <td>{link.role}</td>
              <td><span className="mono" style={{ wordBreak: "break-all" }}>{link.url}</span></td>
              <td>{when(link.expiresAt)}</td>
            </tr>)}</tbody>
          </table></div>
        </>}
        <button className="btn btn-sm" onClick={() => setApproved(null)}>Dismiss</button>
      </div>}

      {requestsQ.isLoading && <GridLoader label="Reading trial requests" rows={5} columns={7} />}
      {requestsQ.isError && <p className="empty-note">Trial requests failed to load: {messageOf(requestsQ.error)}</p>}
      {requestsQ.isSuccess && requestsQ.data.length === 0
        && <p className="empty-note">No trial requests with this status.</p>}

      {requestsQ.isSuccess && requestsQ.data.length > 0 && <div className="panel" style={{ padding: 16 }}>
        <div className="table-wrap"><table className="data-table">
          <thead><tr>
            <th>Reference</th><th>Company</th><th>Requester</th><th>Domain</th>
            <th>Status</th><th>Submitted</th>
            {!readOnly && <th className="table-action">Action</th>}
          </tr></thead>
          <tbody>{requestsQ.data.map((row) => <Fragment key={row.id}>
            <tr>
              <td><span className="mono">{row.reference}</span></td>
              <td>
                {row.companyName}
                {row.provisionedSlug && <div className="lead-meta">workspace {row.provisionedSlug}</div>}
              </td>
              <td>
                {row.fullName}
                <div className="lead-meta">{row.workEmail}{row.jobTitle ? ` · ${row.jobTitle}` : ""}</div>
              </td>
              <td><span className="mono">{row.emailDomain}</span></td>
              <td><span className={requestChip(row.status)}>{row.status}</span></td>
              <td>{when(row.submittedAt)}</td>
              {!readOnly && <td className="table-action">
                <button className="link-btn"
                  onClick={() => setOpenRequestId(openRequestId === row.id ? null : row.id)}>
                  {openRequestId === row.id ? "Close" : "Review"}
                </button>
              </td>}
            </tr>
            {openRequestId === row.id && <tr>
              <td colSpan={readOnly ? 6 : 7}>
                <div style={{ display: "grid", gap: 12, padding: "8px 0" }}>
                  {row.notes && <p>What they want to evaluate: {row.notes}</p>}
                  <p className="lead-meta">
                    {row.companySize ? `${row.companySize} employees · ` : ""}
                    {row.country ?? "Country not given"} · submitted from {row.sourceIp ?? "an unknown address"}
                  </p>
                  {row.rejectReason && <p className="form-error">Declined: {row.rejectReason}</p>}

                  {(row.status === "PENDING" || row.status === "APPROVED") && <>
                    <div className="field">
                      <label className="label" htmlFor={`note-${row.id}`}>Approval note (optional, audited)</label>
                      <input id={`note-${row.id}`} value={approvalNote}
                        onChange={(e) => setApprovalNote(e.target.value)}
                        placeholder="e.g. qualified by sales on the 24th" />
                    </div>
                    <div className="field">
                      <label className="label" htmlFor={`reason-${row.id}`}>Reason for declining (required to decline)</label>
                      <input id={`reason-${row.id}`} value={rejectReason}
                        onChange={(e) => setRejectReason(e.target.value)}
                        placeholder="e.g. competitor; no business need stated" />
                    </div>
                    <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                      <button className="btn btn-primary btn-sm" disabled={approve.isPending}
                        onClick={() => approve.mutate(row)}>
                        {approve.isPending ? <InlineLoader label="Provisioning" /> : "Approve and provision"}
                      </button>
                      <button className="btn btn-sm" disabled={reject.isPending || !rejectReason.trim()}
                        onClick={() => reject.mutate(row)}>
                        {reject.isPending ? <InlineLoader label="Declining" /> : "Decline with reason"}
                      </button>
                    </div>
                    <p className="form-notice">
                      Approving creates a real workspace with one tenant administrator, one auditor, a
                      30-day trial and demonstration data. It is idempotent: approving twice returns the
                      same workspace rather than building a second one.
                    </p>
                  </>}
                </div>
              </td>
            </tr>}
          </Fragment>)}</tbody>
        </table></div>
      </div>}
    </>}

    {/* =================================================== TRIAL ACCOUNTS */}
    {tab === "accounts" && platform && <>
      {companiesQ.isLoading && <PanelLoader label="Reading company accounts" />}
      {companiesQ.isError && <p className="empty-note">Company accounts failed to load: {messageOf(companiesQ.error)}</p>}

      {companiesQ.isSuccess && <div className="panel" style={{ padding: 16 }}>
        <div className="table-wrap"><table className="data-table">
          <thead><tr>
            <th>Company</th><th>Workspace</th><th>Status</th><th>Trial ends</th>
            <th>Expiry risk</th><th className="num">Extensions</th>
            {!readOnly && <th className="table-action">Action</th>}
          </tr></thead>
          <tbody>{companiesQ.data.map((row) => {
            const days = row.accountStatus === "TRIAL" ? daysRemaining(row.trialEndsAt) : null;
            const risk = riskChip(days);
            return <tr key={row.tenantId}>
              <td>
                {row.tenantName}
                <div className="lead-meta">{row.legalName}</div>
              </td>
              <td><span className="mono">{row.tenantSlug}</span></td>
              <td><span className={accountChip(row.accountStatus)}>{row.accountStatus}</span></td>
              <td>{when(row.trialEndsAt)}</td>
              <td>{row.accountStatus === "TRIAL"
                ? <span className={risk.className}>{risk.label}</span>
                : <span className="chip chip-draft">Not on trial</span>}</td>
              <td className="num">{row.trialExtensionCount} · max {row.maxTrialExtensionDays}d</td>
              {!readOnly && <td className="table-action">
                <button className="link-btn" disabled={extend.isPending}
                  onClick={() => extend.mutate(row.tenantId)}>Extend 14d</button>
                <button className="link-btn" disabled={convert.isPending}
                  onClick={() => convert.mutate(row.tenantId)}>Convert to paid</button>
              </td>}
            </tr>;
          })}</tbody>
        </table></div>
        <p className="form-notice" style={{ marginTop: 12 }}>
          Extensions are capped by each account's configured maximum, so an extension that would exceed it
          is refused rather than silently trimmed. Every action here is audited with the operator and the
          reason.
        </p>
      </div>}
    </>}
  </>;
}

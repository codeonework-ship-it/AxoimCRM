/**
 * Anonymous access-path client: SSO discovery and trial self-registration.
 *
 * Kept out of `client.ts` deliberately. Everything there assumes a bearer
 * token; these three calls run BEFORE anyone is authenticated, which is a
 * different trust context and worth keeping visibly separate.
 */

const BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

export interface IdpRoute {
  id: string;
  displayName: string;
  protocol: "SAML2" | "OIDC";
  /** True when the provider uses a complete browser redirect/callback flow. */
  handshakeAvailable: boolean;
  /** Provider-specific wording to show the user. Server-authored; render verbatim. */
  message: string;
}

interface PublicRouteResponse {
  method: "SSO" | "PASSWORD";
  idpConfigId: string | null;
  displayName: string | null;
  protocol: "SAML2" | "OIDC" | null;
  handshakeAvailable: boolean;
  message: string;
}

export interface TrialRequest {
  companyName: string;
  workEmail: string;
  fullName: string;
  jobTitle?: string;
  companySize?: string;
  country?: string;
  notes?: string;
}

export interface TrialOutcome {
  reference: string;
  status: string;
  trialDays: number;
  message: string;
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw Object.assign(new Error(body?.message ?? `Request failed (${res.status})`), {
      status: res.status,
      code: body?.code,
    });
  }
  return body as T;
}

/**
 * Which identity provider, if any, serves this workspace + email domain.
 * Returns null when the tenant has no SSO configured — the caller then shows
 * the credentials form rather than an error, because "no SSO here" is a normal
 * state, not a failure.
 *
 * Hits `/public/sso/route`, not the authenticated `/identity/idp/route`. The
 * caller has no token yet, so the authenticated one answered 401 and every SSO
 * attempt fell through to the password form regardless of what the workspace
 * had configured. Discovery has to happen before authentication or not at all.
 */
export async function discoverIdp(tenantSlug: string, email: string): Promise<IdpRoute | null> {
  const qs = new URLSearchParams({ tenantSlug, email });
  const res = await call<PublicRouteResponse>(`/api/v1/public/sso/route?${qs}`);
  if (res.method !== "SSO" || !res.idpConfigId) return null;
  return {
    id: res.idpConfigId,
    displayName: res.displayName ?? "your identity provider",
    protocol: res.protocol ?? "OIDC",
    handshakeAvailable: res.handshakeAvailable,
    message: res.message,
  };
}

/** Begins the redirect handshake. Returns the URL to send the browser to. */
export async function beginSso(idpId: string, tenantSlug: string): Promise<{ redirectUrl: string }> {
  return call(`/api/v1/sso/${idpId}/authorize`, {
    method: "POST",
    credentials: "include",
    body: JSON.stringify({ tenantSlug, redirectUri: `${window.location.origin}/login` }),
  });
}

export async function exchangeSsoTicket(ticket: string): Promise<import("./client").LoginResponse> {
  return call(`/api/v1/sso/session/exchange`, {
    method: "POST",
    body: JSON.stringify({ ticket }),
  });
}

/** Public 30-day trial request. No authentication — this is the front door. */
export async function requestTrial(req: TrialRequest): Promise<TrialOutcome> {
  return call(`/api/v1/public/trial-requests`, {
    method: "POST",
    body: JSON.stringify(req),
  });
}

/**
 * Redeems a one-time activation link and sets the account's first password.
 * Also unauthenticated — the token in the link is the entire authority, which
 * is why it is single-use and short-lived.
 */
export async function activateAccount(
  token: string,
  password: string,
): Promise<{ email: string; role: string; tenantSlug: string; message: string }> {
  return call(`/api/v1/public/trial-activations/${encodeURIComponent(token)}`, {
    method: "POST",
    body: JSON.stringify({ password }),
  });
}

/* ==========================================================================
   Authenticated half: the two admin masters that govern anonymous access.

   These need a bearer token, which is read from the same `axiom.session`
   entry AuthContext writes rather than from a second copy kept here — one
   source for the session means signing out cannot leave this module holding
   a live token. The anonymous calls above deliberately do NOT send it.
   ========================================================================== */

const SESSION_KEY = "axiom.session";

function bearer(): string | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    return (JSON.parse(raw) as { token?: string })?.token ?? null;
  } catch {
    return null;
  }
}

async function authed<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = bearer();
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const parsed = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw Object.assign(new Error(parsed?.message ?? `Request failed (${res.status})`), {
      status: res.status,
      code: parsed?.code,
    });
  }
  return parsed as T;
}

/* -------------------------------------------------- identity providers ---- */

export type IdpProtocol = "SAML2" | "OIDC";

/**
 * Note `clientSecret`. The API returns a mask string, never the stored value —
 * verified in IdpConfigService.map(). Treat any non-null value here as
 * "a secret exists", not as the secret.
 */
export interface IdpConfig {
  id: string;
  protocol: IdpProtocol;
  displayName: string;
  enabled: boolean;
  emailDomain: string | null;
  entityId: string | null;
  ssoUrl: string | null;
  certificatePresent: boolean;
  clientId: string | null;
  clientSecret: string | null;
  discoveryUrl: string | null;
  attributeMap: Record<string, string>;
  jitEnabled: boolean;
  defaultRole: string;
  clientAuthMethod: "CLIENT_SECRET_BASIC" | "CLIENT_SECRET_POST";
  lastLiveTestAt: string | null;
  lastLiveTestStatus: "PASSED" | "FAILED" | null;
  lastLiveTestDetail: string | null;
  certificateNotAfter: string | null;
  certificateExpiringSoon: boolean;
  updatedAt: string;
}

export interface IdpMutation {
  protocol: IdpProtocol;
  displayName: string;
  enabled: boolean;
  emailDomain?: string | null;
  entityId?: string | null;
  ssoUrl?: string | null;
  certificate?: string | null;
  clientId?: string | null;
  clientSecret?: string | null;
  discoveryUrl?: string | null;
  attributeMap?: Record<string, string>;
  jitEnabled?: boolean;
  defaultRole?: string;
  clientAuthMethod?: "CLIENT_SECRET_BASIC" | "CLIENT_SECRET_POST";
}

export interface IdpTestResult {
  ready: boolean;
  problems: string[];
  warnings: string[];
  inspected: Record<string, unknown>;
  note: string;
}

export interface IdpCertificateAlert {
  id: string;
  providerId: string;
  providerName: string;
  certificateNotAfter: string;
  severity: "WARNING" | "EXPIRED";
  notifiedAt: string;
  recipientCount: number;
}

export const IDENTITY_CERTIFICATION_EVIDENCE = [
  "federatedLogin", "issuerAudienceNonceValidated", "scimDiscovery", "scimUserCreate",
  "scimUserUpdate", "scimUserDeactivate", "sessionsRevoked", "ownedRecordsPreserved",
  "scimGroupCreate", "scimGroupMembership", "scimGroupDeactivate", "filterAndPagination",
] as const;

export interface IdentityCertification {
  id: string;
  idpConfigId: string | null;
  provider: string;
  externalTenantRef: string;
  connectorJobRef: string;
  status: "PASSED" | "FAILED";
  evidence: Record<string, boolean>;
  missing: string[];
  startedAt: string;
  completedAt: string;
}

export interface IdentityCertificationRequest {
  idpConfigId?: string | null;
  provider: string;
  externalTenantRef: string;
  connectorJobRef: string;
  evidence: Record<string, boolean>;
}

export const idpApi = {
  list: () => authed<IdpConfig[]>("GET", "/api/v1/identity/idp"),
  create: (body: IdpMutation) => authed<IdpConfig>("POST", "/api/v1/identity/idp", body),
  update: (id: string, body: IdpMutation) => authed<IdpConfig>("PUT", `/api/v1/identity/idp/${id}`, body),
  remove: (id: string) => authed<void>("DELETE", `/api/v1/identity/idp/${id}`),
  test: (id: string) => authed<IdpTestResult>("POST", `/api/v1/identity/idp/${id}/test`),
  liveTest: (id: string) => authed<{ ready: boolean; protocol: string; inspected: Record<string, unknown>; message: string; testedAt: string }>("POST", `/api/v1/identity/idp/${id}/live-test`),
  route: (email: string) =>
    authed<{ method: string; idpConfigId: string | null; displayName: string | null; protocol: string | null; note: string }>(
      "GET", `/api/v1/identity/idp/route?email=${encodeURIComponent(email)}`),
  certificateAlerts: () => authed<IdpCertificateAlert[]>("GET", "/api/v1/identity/idp/certificate-alerts"),
  sweepCertificateAlerts: () => authed<{ alertsCreated: number }>("POST", "/api/v1/identity/idp/certificate-alerts/sweep"),
  certifications: () => authed<IdentityCertification[]>("GET", "/api/v1/identity/certifications"),
  recordCertification: (body: IdentityCertificationRequest) =>
    authed<IdentityCertification>("POST", "/api/v1/identity/certifications", body),
};

/* ------------------------------------------------------ trial accounts ---- */

export type TrialRequestStatus = "PENDING" | "APPROVED" | "PROVISIONED" | "REJECTED" | "EXPIRED";

export interface TrialRequestRow {
  id: string;
  reference: string;
  companyName: string;
  workEmail: string;
  emailDomain: string;
  fullName: string;
  jobTitle: string | null;
  companySize: string | null;
  country: string | null;
  notes: string | null;
  status: TrialRequestStatus;
  trialDays: number;
  submittedAt: string | null;
  reviewedAt: string | null;
  reviewedByName: string | null;
  provisionedTenantId: string | null;
  provisionedSlug: string | null;
  rejectReason: string | null;
  sourceIp: string | null;
}

export interface ActivationLink {
  email: string;
  displayName: string;
  role: string;
  url: string;
  expiresAt: string;
}

export interface ApprovalResult {
  trialRequestId: string;
  reference: string;
  tenantId: string;
  slug: string;
  companyName: string;
  trialDays: number;
  trialStartAt: string | null;
  trialEndsAt: string | null;
  created: boolean;
  tenantAdminCount: number;
  auditorCount: number;
  activationLinks: ActivationLink[];
  demoData: string[];
  note: string;
}

export interface CompanyAccountRow {
  tenantId: string;
  tenantSlug: string;
  tenantName: string;
  legalName: string;
  accountStatus: string;
  trialStartAt: string | null;
  trialEndsAt: string | null;
  trialExtensionCount: number;
  maxTrialExtensionDays: number;
  inactiveReason: string | null;
  inactiveAt: string | null;
}

export const trialApi = {
  requests: (status?: string) =>
    authed<TrialRequestRow[]>("GET", `/api/v1/access/trial-requests${status && status !== "ALL" ? `?status=${status}` : ""}`),
  approve: (id: string, note?: string, slug?: string) =>
    authed<ApprovalResult>("POST", `/api/v1/access/trial-requests/${id}/approve`, { note, slug }),
  reject: (id: string, reason: string) =>
    authed<TrialRequestRow>("POST", `/api/v1/access/trial-requests/${id}/reject`, { reason }),
  expireStale: () =>
    authed<{ expired: number; message: string }>("POST", "/api/v1/access/trial-requests/expire-stale"),

  // The company-account half already exists on /api/v1/admin. Calling it rather
  // than re-exposing it keeps one audited path per state change.
  companies: () => authed<CompanyAccountRow[]>("GET", "/api/v1/admin/companies"),
  extendTrial: (tenantId: string, days: number, note: string) =>
    authed<CompanyAccountRow>("POST", `/api/v1/admin/trials/${tenantId}/extend`, { days, months: 0, note }),
  setCompanyStatus: (tenantId: string, status: string, reason: string) =>
    authed<CompanyAccountRow>("PATCH", `/api/v1/admin/companies/${tenantId}/status`, { status, reason }),
};

/** Step-up re-authentication, required before approving or extending. */
export async function stepUp(password: string): Promise<{ steppedUpAt: string; validForSeconds: number }> {
  return authed("POST", "/api/v1/auth/step-up", { password });
}

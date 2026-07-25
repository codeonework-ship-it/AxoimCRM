/**
 * Identity & access API surface (epic E01).
 *
 * Deliberately separate from `src/api/client.ts`: that module is shared by every
 * workspace screen, and the identity endpoints are needed in two places it cannot
 * reach — the sign-in screen before a session exists, and the step-up modal, which
 * has to be able to fire while another request is being refused. Keeping them here
 * also means the sign-in flow does not depend on the shared client's
 * "any 401 logs you out" behaviour, which would be wrong during an MFA challenge.
 *
 * The bearer token is read from the same `axiom.session` localStorage entry that
 * `AuthContext` writes, so the two stay in step without either owning the other.
 */

const DEFAULT_API_BASE_URL = import.meta.env.DEV ? "http://localhost:8080/api/v1" : "/api/v1";

const BASE_URL: string =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ??
  DEFAULT_API_BASE_URL;

/** Mirrors the key AuthContext uses. Changing one without the other breaks sign-in. */
const SESSION_KEY = "axiom.session";

export interface IdentityUser {
  id: string;
  displayName: string;
  email: string;
  role: string;
  platformUser: boolean;
}

export interface IdentityTenant {
  id: string;
  slug: string;
  name: string;
}

export interface LoginOutcome {
  token: string | null;
  user: IdentityUser;
  tenant: IdentityTenant;
  mfaRequired: boolean;
  mfaChallengeToken: string | null;
  notice: string | null;
}

export interface WorkspaceBranding {
  workspaceSlug: string;
  workspaceName: string | null;
  logoUrl: string | null;
  primaryColour: string | null;
  supportContact: string | null;
  signInMessage: string | null;
}

export interface SessionRow {
  id: string;
  userId: string;
  subjectEmail: string;
  subjectName: string;
  role: string;
  kind: string;
  platformUser: boolean;
  impersonatorEmail: string | null;
  issuedAt: string;
  expiresAt: string;
  lastSeenAt: string;
  ip: string | null;
  userAgent: string | null;
  stepUpAt: string | null;
  revokedAt: string | null;
  revokeReason: string | null;
  current: boolean;
}

export interface SessionPolicy {
  absoluteLifetimeMinutes: number;
  idleTimeoutMinutes: number;
  maxConcurrentSessions: number;
  concurrentStrategy: string;
  stepUpMaxAgeSeconds: number;
}

export interface MfaStatus {
  enrolled: boolean;
  active: boolean;
  requiredByPolicy: boolean;
  unusedRecoveryCodes: number;
  confirmedAt: string | null;
}

export interface MfaEnrolment {
  method: string;
  secretBase32: string;
  provisioningUri: string;
  digits: number;
  periodSeconds: number;
  instructions: string;
}

export interface MfaConfirmation {
  active: boolean;
  recoveryCodes: string[];
  warning: string;
}

export interface StepUpResult {
  steppedUpAt: string;
  validForSeconds: number;
}

export interface ServiceCredential {
  id: string;
  name: string;
  clientId: string;
  scopes: string[];
  expiresAt: string | null;
  lastUsedAt: string | null;
  rotatedAt: string | null;
  revokedAt: string | null;
  state: string;
}

export interface IssuedServiceCredential {
  id: string;
  name: string;
  clientId: string;
  clientSecret: string;
  scopes: string[];
  expiresAt: string | null;
  warning: string;
}

export interface NetworkRule {
  id: string;
  cidr: string;
  description: string;
  active: boolean;
  updatedAt: string;
}

export interface PasswordPolicy {
  minLength: number;
  requireUpper: boolean;
  requireLower: boolean;
  requireDigit: boolean;
  requireSymbol: boolean;
  historyCount: number;
  expiryDays: number;
  maxFailedAttempts: number;
  lockoutMinutes: number;
  rejectBreached: boolean;
}

/** A non-2xx response, carrying the server's message and status. */
export class IdentityError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "IdentityError";
    this.status = status;
  }
}

/** True when the server refused because a fresh re-authentication is needed. */
export function needsStepUp(error: unknown): boolean {
  return (
    error instanceof IdentityError &&
    error.status === 403 &&
    error.message.toLowerCase().includes("confirm your password")
  );
}

function storedToken(): string | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { token?: string };
    return parsed?.token ?? null;
  } catch {
    return null;
  }
}

/**
 * Installs a session and reloads onto `destination`.
 *
 * A full navigation rather than a state update is intentional: the MFA step
 * completes outside AuthContext's own login call, and a reload is the one way to
 * hand the new token to every module that primed itself at start-up without
 * reaching into AuthContext's internals from here.
 */
export function installSession(outcome: LoginOutcome, destination: string): void {
  if (!outcome.token) {
    throw new IdentityError(500, "Sign-in did not return a session token.");
  }
  localStorage.setItem(
    SESSION_KEY,
    JSON.stringify({ token: outcome.token, user: outcome.user, tenant: outcome.tenant }),
  );
  window.location.replace(destination);
}

async function request<T>(
  method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE",
  path: string,
  body?: unknown,
): Promise<T> {
  const token = storedToken();
  let res: Response;
  try {
    res = await fetch(`${BASE_URL}${path}`, {
      method,
      headers: {
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new IdentityError(0, `The Axiom API at ${BASE_URL} could not be reached.`);
  }
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const data = (await res.json()) as { message?: string };
      if (data?.message) message = data.message;
    } catch {
      /* non-JSON body — keep the generic message */
    }
    throw new IdentityError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const identityApi = {
  /* ---- sign-in (no token yet) ---- */

  branding(slug: string): Promise<WorkspaceBranding> {
    return request<WorkspaceBranding>("GET", `/branding/${encodeURIComponent(slug)}`);
  },

  login(req: { tenantSlug: string; email: string; password: string }): Promise<LoginOutcome> {
    return request<LoginOutcome>("POST", "/auth/login", req);
  },

  verifyMfa(req: {
    challengeToken: string;
    code?: string;
    recoveryCode?: string;
  }): Promise<LoginOutcome> {
    return request<LoginOutcome>("POST", "/auth/mfa/verify", req);
  },

  /* ---- step-up ---- */

  stepUp(req: { password: string; code?: string }): Promise<StepUpResult> {
    return request<StepUpResult>("POST", "/auth/step-up", req);
  },

  /* ---- sessions ---- */

  sessions(includeEnded = false): Promise<SessionRow[]> {
    return request<SessionRow[]>("GET", `/security/sessions?includeEnded=${includeEnded}`);
  },

  sessionPolicy(): Promise<SessionPolicy> {
    return request<SessionPolicy>("GET", "/security/sessions/policy");
  },

  revokeSession(id: string, reason: string): Promise<void> {
    return request<void>("POST", `/security/sessions/${encodeURIComponent(id)}/revoke`, { reason });
  },

  /* ---- multi-factor ---- */

  mfaStatus(): Promise<MfaStatus> {
    return request<MfaStatus>("GET", "/security/mfa");
  },

  mfaEnrol(): Promise<MfaEnrolment> {
    return request<MfaEnrolment>("POST", "/security/mfa/enrol");
  },

  mfaConfirm(code: string): Promise<MfaConfirmation> {
    return request<MfaConfirmation>("POST", "/security/mfa/confirm", { code });
  },

  mfaRecoveryCodes(): Promise<MfaConfirmation> {
    return request<MfaConfirmation>("POST", "/security/mfa/recovery-codes");
  },

  /* ---- machine credentials and network rules ---- */

  serviceCredentials(): Promise<ServiceCredential[]> {
    return request<ServiceCredential[]>("GET", "/identity/service-credentials");
  },

  issueServiceCredential(req: {
    name: string;
    scopes: string[];
    expiresInDays?: number;
  }): Promise<IssuedServiceCredential> {
    return request<IssuedServiceCredential>("POST", "/identity/service-credentials", req);
  },

  revokeServiceCredential(id: string, reason: string): Promise<void> {
    return request<void>("POST", `/identity/service-credentials/${encodeURIComponent(id)}/revoke`, {
      reason,
    });
  },

  networkRules(): Promise<NetworkRule[]> {
    return request<NetworkRule[]>("GET", "/identity/network-rules");
  },

  createNetworkRule(req: {
    cidr: string;
    description: string;
    active: boolean;
  }): Promise<NetworkRule> {
    return request<NetworkRule>("POST", "/identity/network-rules", req);
  },

  setNetworkRuleActive(id: string, active: boolean): Promise<void> {
    return request<void>("PATCH", `/identity/network-rules/${encodeURIComponent(id)}/active`, {
      active,
    });
  },

  passwordPolicy(): Promise<PasswordPolicy> {
    return request<PasswordPolicy>("GET", "/identity/password-policy");
  },
};

/**
 * Integration dispatch API client.
 *
 * A separate module from `client.ts` on purpose: this is the only consumer of
 * `/api/v1/integration`, and the shared client is edited by every other module.
 *
 * The bearer token is read from the same `axiom.session` entry `AuthContext`
 * writes, rather than from a second copy kept here — one source for the session
 * means signing out cannot leave this module holding a live token.
 */

const DEFAULT_API_BASE_URL = import.meta.env.DEV ? "http://localhost:8080/api/v1" : "/api/v1";
const BASE_URL: string =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? DEFAULT_API_BASE_URL;

const SESSION_KEY = "axiom.session";

function token(): string | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { token?: string };
    return parsed?.token ?? null;
  } catch {
    return null;
  }
}

export class IntegrationApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "IntegrationApiError";
    this.status = status;
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const bearer = token();
  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const data: unknown = await res.json();
      if (data && typeof data === "object" && "message" in data
        && typeof (data as { message: unknown }).message === "string") {
        message = (data as { message: string }).message;
      }
    } catch {
      /* non-JSON error body */
    }
    throw new IntegrationApiError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/* ------------------------------------------------------------------ */
/* Types — mirror of the backend records                               */
/* ------------------------------------------------------------------ */

export type ConnectorType = "WEBHOOK" | "ERP" | "ESIGN" | "MARKETING" | "ENRICHMENT" | "CTRM";

export interface Connector {
  id: string;
  connectorType: ConnectorType;
  vendor: string;
  displayName: string;
  enabled: boolean;
  /** Secret-shaped keys arrive masked; there is no field carrying a secret. */
  config: Record<string, unknown>;
  credentialRef: string | null;
  credentialStatus: "SET" | "MISSING" | "NONE";
  subscriptionCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface Subscription {
  id: string;
  connectorId: string;
  eventTypePattern: string;
  filterExpression: string | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface NamedCredential {
  id: string;
  name: string;
  credentialType: string;
  description: string | null;
  /** Always the constant mask. The value itself is never returned. */
  secretMasked: string;
  rotatedAt: string | null;
  lastUsedAt: string | null;
  createdAt: string;
  inUse: boolean;
}

export interface ConnectorHealth {
  connectorId: string;
  connectorName: string;
  connectorType: string;
  vendor: string;
  enabled: boolean;
  breakerState: "CLOSED" | "OPEN" | "HALF_OPEN";
  consecutiveFailures: number;
  totalSuccess: number;
  totalFailure: number;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  lastError: string | null;
  breakerOpenedAt: string | null;
  pendingDepth: number;
  deadLetterDepth: number;
  oldestPendingAt: string | null;
  status: "HEALTHY" | "DEGRADED" | "PAUSED" | "PROBING" | "IDLE" | "DISABLED";
}

export interface HealthSummary {
  connectors: number;
  enabledConnectors: number;
  openBreakers: number;
  pendingDepth: number;
  deadLetterDepth: number;
  succeededLast24h: number;
  failedLast24h: number;
  generatedAt: string;
}

export interface Delivery {
  id: string;
  connectorId: string;
  connectorName: string;
  subscriptionId: string;
  eventId: string;
  eventType: string;
  aggregateType: string;
  aggregateId: string | null;
  status: "PENDING" | "IN_FLIGHT" | "SUCCEEDED" | "DEAD_LETTERED";
  attemptCount: number;
  nextAttemptAt: string | null;
  succeededAt: string | null;
  lastError: string | null;
  lastHttpStatus: number | null;
  createdAt: string;
}

export interface Attempt {
  id: string;
  attemptNo: number;
  status: "SUCCESS" | "RETRYABLE_FAILURE" | "PERMANENT_FAILURE" | "BLOCKED_BY_BREAKER";
  httpStatus: number | null;
  responseExcerpt: string | null;
  error: string | null;
  durationMs: number;
  attemptedAt: string;
}

export interface DeadLetter {
  id: string;
  deliveryId: string;
  connectorId: string;
  connectorName: string;
  eventId: string;
  eventType: string;
  envelope: Record<string, unknown>;
  failureReason: string;
  attempts: number;
  createdAt: string;
  replayedAt: string | null;
  replayCount: number;
}

export interface ReplayOutcome {
  deadLetterId: string;
  deliveryId: string;
  requeued: boolean;
  detail: string;
}

export interface AdapterDescriptor {
  connectorType: string;
  vendor: string;
  live: boolean;
}

export interface TickResult {
  queued: number;
  attempted: number;
  succeeded: number;
  failed: number;
}

export interface ConnectorInput {
  connectorType: ConnectorType;
  vendor: string;
  displayName: string;
  enabled: boolean;
  config: Record<string, unknown>;
  credentialRef: string | null;
}

export interface SubscriptionInput {
  eventTypePattern: string;
  filterExpression: string | null;
  active: boolean;
}

export interface CredentialInput {
  name: string;
  credentialType: string;
  secret: string;
  description: string | null;
}

/* ------------------------------------------------------------------ */

export const integrationApi = {
  connectors: () => request<Connector[]>("GET", "/integration/connectors"),
  connector: (id: string) => request<Connector>("GET", `/integration/connectors/${id}`),
  createConnector: (input: ConnectorInput) =>
    request<Connector>("POST", "/integration/connectors", input),
  updateConnector: (id: string, input: ConnectorInput) =>
    request<Connector>("PUT", `/integration/connectors/${id}`, input),
  setConnectorEnabled: (id: string, enabled: boolean) =>
    request<Connector>("PATCH", `/integration/connectors/${id}/enabled?enabled=${enabled}`),
  deleteConnector: (id: string) => request<void>("DELETE", `/integration/connectors/${id}`),

  adapters: () => request<AdapterDescriptor[]>("GET", "/integration/adapters"),

  subscriptions: (connectorId: string) =>
    request<Subscription[]>("GET", `/integration/connectors/${connectorId}/subscriptions`),
  addSubscription: (connectorId: string, input: SubscriptionInput) =>
    request<Subscription>("POST", `/integration/connectors/${connectorId}/subscriptions`, input),
  updateSubscription: (connectorId: string, id: string, input: SubscriptionInput) =>
    request<Subscription>("PUT", `/integration/connectors/${connectorId}/subscriptions/${id}`, input),
  deleteSubscription: (connectorId: string, id: string) =>
    request<void>("DELETE", `/integration/connectors/${connectorId}/subscriptions/${id}`),

  credentials: () => request<NamedCredential[]>("GET", "/integration/credentials"),
  createCredential: (input: CredentialInput) =>
    request<NamedCredential>("POST", "/integration/credentials", input),
  rotateCredential: (name: string, secret: string) =>
    request<NamedCredential>("POST", `/integration/credentials/${encodeURIComponent(name)}/rotate`, { secret }),
  deleteCredential: (name: string) =>
    request<void>("DELETE", `/integration/credentials/${encodeURIComponent(name)}`),

  health: () => request<ConnectorHealth[]>("GET", "/integration/health"),
  healthSummary: () => request<HealthSummary>("GET", "/integration/health/summary"),

  deliveries: (connectorId?: string, status?: string) => {
    const params = new URLSearchParams();
    if (connectorId) params.set("connectorId", connectorId);
    if (status) params.set("status", status);
    params.set("limit", "100");
    return request<Delivery[]>("GET", `/integration/deliveries?${params.toString()}`);
  },
  attempts: (deliveryId: string) =>
    request<Attempt[]>("GET", `/integration/deliveries/${deliveryId}/attempts`),

  deadLetters: (connectorId?: string, includeReplayed = false) => {
    const params = new URLSearchParams();
    if (connectorId) params.set("connectorId", connectorId);
    params.set("includeReplayed", String(includeReplayed));
    params.set("limit", "100");
    return request<DeadLetter[]>("GET", `/integration/dead-letters?${params.toString()}`);
  },
  replay: (deadLetterId: string) =>
    request<ReplayOutcome>("POST", `/integration/dead-letters/${deadLetterId}/replay`),
  replayMany: (deadLetterIds: string[]) =>
    request<ReplayOutcome[]>("POST", "/integration/dead-letters/replay", { deadLetterIds }),

  runWorker: () => request<TickResult>("POST", "/integration/worker/run"),
};

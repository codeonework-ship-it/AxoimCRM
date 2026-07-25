/**
 * Axiom CRM — typed API client.
 *
 * This is the ONLY module that knows the backend's URL layout and payload
 * shapes. If the backend shape shifts, fix it here.
 *
 * Base URL: http://localhost:8080/api/v1  (override with VITE_API_BASE_URL)
 */

const BASE_URL: string =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ??
  "http://localhost:8080/api/v1";

/* ------------------------------------------------------------------ */
/* Types (mirror of backend contracts)                                 */
/* ------------------------------------------------------------------ */

export interface AuthUser {
  id: string;
  displayName: string;
  email: string;
  role: string;
  platformUser: boolean;
}

export interface AuthTenant {
  id: string;
  slug: string;
  name: string;
}

export interface LoginRequest {
  tenantSlug: string;
  email: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  user: AuthUser;
  tenant: AuthTenant;
}

export interface TenantOption extends AuthTenant {}

export interface AuditEvent {
  id: string;
  actorName: string;
  actorRole: string;
  action: string;
  entityType: string;
  entityId: string | null;
  summary: string;
  details: Record<string, unknown>;
  correlationId: string | null;
  occurredAt: string;
}

export interface DownloadedFile {
  blob: Blob;
  filename: string;
}

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface ListParams {
  page?: number;
  search?: string;
  filter?: string;
}

export interface ReferenceValueSet {
  id: string;
  apiName: string;
  label: string;
  module: string;
  description: string | null;
  active: boolean;
}

export interface ReferenceEntry {
  id: string;
  code: string;
  label: string;
  sortOrder: number;
  active: boolean;
  systemManaged: boolean;
  effectiveFrom: string | null;
  effectiveTo: string | null;
}

export interface ReferenceEntryMutation {
  code: string;
  label: string;
  sortOrder: number;
  active: boolean;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
}

export interface MeResponse {
  user: AuthUser;
  tenant: AuthTenant;
}

export interface Account {
  id: string;
  name: string;
  industry: string | null;
  ownerName: string | null;
}

export type LeadStatus =
  | "NEW"
  | "WORKING"
  | "NURTURING"
  | "QUALIFIED"
  | "CONVERTED"
  | "DISQUALIFIED"
  | string; // tolerate unknown statuses from the server

export interface Lead {
  id: string;
  name: string;
  company: string | null;
  email: string | null;
  status: LeadStatus;
  ownerName: string | null;
}

export interface BoardOpportunity {
  id: string;
  name: string;
  accountName: string;
  amount: number;
  closeDate: string; // ISO date
  ownerName: string;
  hasEconomicBuyer: boolean;
}

export interface BoardStage {
  id: string;
  name: string;
  sortOrder: number;
  isClosed: boolean;
  requiresEconomicBuyer: boolean;
  opportunities: BoardOpportunity[];
}

export interface PipelineBoard {
  stages: BoardStage[];
}

export interface DashboardStageSlice {
  stageId: string;
  stageName: string;
  amount: number;
  count: number;
}

export interface DashboardSummary {
  openPipeline: number;
  openCount: number;
  byStage: DashboardStageSlice[];
  atRiskCount: number;
}

export interface NotificationItem {
  id: string;
  kind: "ACTION" | "SIGNAL" | "SYSTEM";
  priority: "URGENT" | "NORMAL" | "LOW";
  title: string;
  body: string;
  href: string | null;
  reason: string;
  actionRequired: boolean;
  actionCompleted: boolean;
  read: boolean;
  occurredAt: string;
}

/* ------------------------------------------------------------------ */
/* Errors                                                              */
/* ------------------------------------------------------------------ */

/** HTTP-level failure: server responded with a non-2xx status. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

/** Network-level failure: the API could not be reached at all. */
export class ApiUnreachableError extends Error {
  constructor() {
    super(`The Axiom API at ${BASE_URL} could not be reached.`);
    this.name = "ApiUnreachableError";
  }
}

export function isUnreachable(err: unknown): boolean {
  return err instanceof ApiUnreachableError;
}

/* ------------------------------------------------------------------ */
/* Token + 401 handling                                                */
/* ------------------------------------------------------------------ */

let authToken: string | null = null;
let onUnauthorized: (() => void) | null = null;

export function setAuthToken(token: string | null): void {
  authToken = token;
}

/** AuthContext registers a callback so any 401 logs the session out. */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler;
}

/* ------------------------------------------------------------------ */
/* Core request helper                                                 */
/* ------------------------------------------------------------------ */

async function request<T>(
  method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE",
  path: string,
  body?: unknown,
  opts?: { skipAuthRedirect?: boolean },
): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${BASE_URL}${path}`, {
      method,
      headers: {
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    // fetch throws TypeError on DNS/conn-refused/CORS-down — API unreachable.
    throw new ApiUnreachableError();
  }

  if (res.status === 401 && !opts?.skipAuthRedirect) {
    onUnauthorized?.();
    throw new ApiError(401, "Your session has expired. Sign in again.");
  }

  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const data: unknown = await res.json();
      if (
        data &&
        typeof data === "object" &&
        "message" in data &&
        typeof (data as { message: unknown }).message === "string"
      ) {
        message = (data as { message: string }).message;
      }
    } catch {
      /* non-JSON error body — keep generic message */
    }
    throw new ApiError(res.status, message);
  }

  if (res.status === 204) {
    return undefined as T;
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

async function fileRequest(path: string, init?: RequestInit): Promise<DownloadedFile> {
  let res: Response;
  try {
    res = await fetch(`${BASE_URL}${path}`, {
      ...init,
      headers: { ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}), ...init?.headers },
    });
  } catch {
    throw new ApiUnreachableError();
  }
  if (res.status === 401) { onUnauthorized?.(); throw new ApiError(401, "Your session has expired. Sign in again."); }
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try { const data = await res.json() as { message?: string }; if (data.message) message = data.message; } catch { /* binary/non-JSON */ }
    throw new ApiError(res.status, message);
  }
  const disposition = res.headers.get("Content-Disposition") ?? "";
  const filename = /filename="?([^";]+)"?/i.exec(disposition)?.[1] ?? "axiom-download";
  return { blob: await res.blob(), filename };
}

function queryString(params?: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value !== undefined && `${value}`.trim() !== "") search.set(key, `${value}`);
  });
  const text = search.toString();
  return text ? `?${text}` : "";
}

/* ------------------------------------------------------------------ */
/* Endpoints                                                           */
/* ------------------------------------------------------------------ */

export const api = {
  login(req: LoginRequest): Promise<LoginResponse> {
    return request<LoginResponse>("POST", "/auth/login", req, {
      skipAuthRedirect: true,
    });
  },

  me(): Promise<MeResponse> {
    return request<MeResponse>("GET", "/auth/me");
  },

  tenants(): Promise<TenantOption[]> {
    return request<TenantOption[]>("GET", "/auth/tenants");
  },

  switchTenant(tenantSlug: string): Promise<LoginResponse> {
    return request<LoginResponse>("POST", "/auth/switch-tenant", { tenantSlug });
  },

  auditEvents(entityType?: string): Promise<AuditEvent[]> {
    const query = entityType ? `?entityType=${encodeURIComponent(entityType)}` : "";
    return request<AuditEvent[]>("GET", `/audit${query}`);
  },

  masterTemplate(master: string): Promise<DownloadedFile> {
    return fileRequest(`/master-data/${encodeURIComponent(master)}/template`);
  },

  exportMaster(master: string, format: "XLSX" | "DOCX" | "PDF", params?: ListParams): Promise<DownloadedFile> {
    return fileRequest(`/master-data/${encodeURIComponent(master)}/export${queryString({
      format,
      search: params?.search,
      filter: params?.filter,
    })}`);
  },

  importMaster(master: string, file: File): Promise<{ imported: number; message: string }> {
    const form = new FormData(); form.append("file", file);
    return fileRequest(`/master-data/${encodeURIComponent(master)}/import`, { method: "POST", body: form })
      .then(async ({ blob }) => JSON.parse(await blob.text()) as { imported: number; message: string });
  },

  deleteMaster(master: string, id: string): Promise<void> {
    return request<void>("DELETE", `/master-data/${encodeURIComponent(master)}/${encodeURIComponent(id)}`);
  },

  referenceValueSets(): Promise<ReferenceValueSet[]> {
    return request<ReferenceValueSet[]>("GET", "/reference/value-sets");
  },

  referenceEntries(apiName: string, includeInactive = true): Promise<ReferenceEntry[]> {
    return request<ReferenceEntry[]>("GET", `/reference/value-sets/${encodeURIComponent(apiName)}/entries${queryString({ includeInactive })}`);
  },

  createReferenceEntry(apiName: string, entry: ReferenceEntryMutation): Promise<ReferenceEntry> {
    return request<ReferenceEntry>("POST", `/reference/value-sets/${encodeURIComponent(apiName)}/entries`, entry);
  },

  updateReferenceEntry(apiName: string, code: string, entry: ReferenceEntryMutation): Promise<ReferenceEntry> {
    return request<ReferenceEntry>("PATCH", `/reference/value-sets/${encodeURIComponent(apiName)}/entries/${encodeURIComponent(code)}`, entry);
  },

  accounts(params?: ListParams): Promise<PageResult<Account>> {
    return request<PageResult<Account>>("GET", `/accounts${queryString({
      page: params?.page ?? 0,
      search: params?.search,
      industry: params?.filter,
    })}`);
  },

  leads(params?: ListParams): Promise<PageResult<Lead>> {
    return request<PageResult<{
      id: string; firstName: string; lastName: string; company: string | null;
      email: string | null; status: LeadStatus; ownerName: string | null;
    }>>("GET", `/leads${queryString({
      page: params?.page ?? 0,
      search: params?.search,
      status: params?.filter,
    })}`).then((page) => ({
      ...page,
      items: page.items.map((row) => ({
        id: row.id,
        name: [row.firstName, row.lastName].filter(Boolean).join(" "),
        company: row.company,
        email: row.email,
        status: row.status,
        ownerName: row.ownerName,
      })),
    }));
  },

  convertLead(leadId: string): Promise<void> {
    return request<void>("POST", `/leads/${encodeURIComponent(leadId)}/convert`);
  },

  pipelineBoard(): Promise<PipelineBoard> {
    return request<BoardStage[]>("GET", "/pipeline/board").then((stages) => ({ stages }));
  },

  /** 409 → ApiError whose message is the server's exact stage-gate refusal. */
  moveOpportunity(opportunityId: string, stageId: string): Promise<void> {
    return request<void>(
      "POST",
      `/opportunities/${encodeURIComponent(opportunityId)}/stage`,
      { stageId },
    );
  },

  dashboardSummary(): Promise<DashboardSummary> {
    return request<{
      openPipeline: number;
      openCount: number;
      byStage: Array<{ stage: string; sum: number; count: number }>;
      atRiskCount: number;
    }>("GET", "/dashboard/summary").then((summary) => ({
      openPipeline: summary.openPipeline,
      openCount: summary.openCount,
      atRiskCount: summary.atRiskCount,
      byStage: summary.byStage.map((slice, index) => ({
        stageId: `${index}-${slice.stage}`,
        stageName: slice.stage,
        amount: slice.sum,
        count: slice.count,
      })),
    }));
  },

  notifications(view: "all" | "unread" = "all"): Promise<NotificationItem[]> {
    return request<NotificationItem[]>("GET", `/notifications?view=${view}`);
  },

  notificationUnreadCount(): Promise<number> {
    return request<{ count: number }>("GET", "/notifications/unread-count")
      .then((response) => response.count);
  },

  markNotificationRead(id: string): Promise<void> {
    return request<void>("PATCH", `/notifications/${encodeURIComponent(id)}/read`);
  },

  markNotificationUnread(id: string): Promise<void> {
    return request<void>("PATCH", `/notifications/${encodeURIComponent(id)}/unread`);
  },

  markAllNotificationsRead(): Promise<{ updated: number }> {
    return request<{ updated: number }>("POST", "/notifications/read-all");
  },
};

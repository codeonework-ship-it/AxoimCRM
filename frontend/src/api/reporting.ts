/**
 * Axiom CRM — reporting read-model client (E15, ADR-008).
 *
 * A module of its own rather than an addition to `client.ts`, for the same reason
 * `search.ts` is separate: the read model is a separate module on the backend, and
 * coupling the two files would mean every reporting change touched the one file
 * every other screen depends on.
 *
 * The bearer token is read from the same session key `AuthContext` writes. That
 * duplication is deliberate — `client.ts` keeps its token in a module private and
 * does not export it, and widening that for one feature is a change to shared code.
 */

const DEFAULT_API_BASE_URL = import.meta.env.DEV ? "http://localhost:8080/api/v1" : "/api/v1";

const BASE_URL: string =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? DEFAULT_API_BASE_URL;

/** Written by AuthContext. Read-only here. */
const SESSION_STORAGE_KEY = "axiom.session";

function bearerToken(): string | null {
  try {
    const raw = localStorage.getItem(SESSION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { token?: string };
    return parsed?.token ?? null;
  } catch {
    return null;
  }
}

export class ReportingApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "ReportingApiError";
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = bearerToken();
  const response = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  });
  if (!response.ok) {
    let message = `Request failed with status ${response.status}`;
    try {
      const body = (await response.json()) as { message?: string; detail?: string };
      message = body?.message ?? body?.detail ?? message;
    } catch {
      /* a non-JSON error body is still an error; keep the status message */
    }
    throw new ReportingApiError(response.status, message);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

/* ------------------------------------------------------------------ */
/* Types — mirror of com.axiom.analytics                               */
/* ------------------------------------------------------------------ */

export type FieldKind = "ID" | "TEXT" | "NUMBER" | "MONEY" | "DATE" | "TIMESTAMP" | "BOOLEAN";

export interface DatasetField {
  name: string;
  label: string;
  kind: FieldKind;
  groupable: boolean;
  summable: boolean;
}

export interface Dataset {
  name: string;
  label: string;
  /** The authoritative object that decides visibility. Null means drill-through is unavailable. */
  securable: string | null;
  fields: DatasetField[];
}

/**
 * Projection staleness, carried on every projected figure.
 *
 * ADR-008 decision 5 requires this to be displayed, not merely available: "a report
 * silently thirty seconds behind generates a support ticket and erodes trust in
 * every number the product shows." Treat a missing `staleness` as a bug, never as
 * "the data is live".
 */
export interface Staleness {
  asOf: string | null;
  /** Seconds between the newest projected record's own timestamp and now. */
  lagSeconds: number | null;
  pendingEvents: number;
  degraded: boolean;
  /** Server-composed sentence, so every surface says the same thing about the same lag. */
  statement: string;
}

export interface DatasetStaleness {
  dataset: string;
  rowCount: number;
  sourceRowCount: number;
  behindSourceRows: number;
  newestSourceUpdatedAt: string | null;
  lastProjectedAt: string | null;
  checkpointAt: string | null;
  lagSeconds: number | null;
  pendingEvents: number;
  degraded: boolean;
}

export type ReportFormat = "TABULAR" | "SUMMARY" | "MATRIX";

export interface ReportFilter {
  field: string;
  operator: string;
  values: string[];
}

export interface ReportSummary {
  field: string;
  function: string;
  label?: string | null;
}

export interface RelatedFilter {
  related: string;
  mode: "WITH" | "WITHOUT";
  withinDays?: number | null;
}

export interface ReportRequest {
  dataset: string;
  format?: ReportFormat;
  columns?: string[] | null;
  filters?: ReportFilter[] | null;
  groupBy?: string[] | null;
  columnGroup?: string | null;
  summaries?: ReportSummary[] | null;
  sortBy?: string | null;
  sortDirection?: "ASC" | "DESC" | null;
  limit?: number | null;
  related?: RelatedFilter | null;
}

export interface ReportColumn {
  field: string;
  label: string;
  kind: string;
  role: "DETAIL" | "GROUP" | "MEASURE";
}

export interface ReportResult {
  dataset: string;
  format: ReportFormat;
  columns: ReportColumn[];
  rows: Array<Record<string, unknown>>;
  grandTotals: Record<string, unknown>;
  rowCount: number;
  truncated: boolean;
  rowLimit: number;
  /** Present when the row cap was hit: names the limit and how to narrow (FR-RPT-011). */
  guidance: string | null;
  /** True when the figures cover only the records this viewer may read (FR-RPT-005). */
  accessRestricted: boolean;
  /** Fields absent from the result because the viewer's profile hides them (FR-SEC-007). */
  withheldFields: string[];
  drillField: string | null;
  elapsedMs: number;
  staleness: Staleness;
}

export interface SavedReport {
  id: string;
  code: string;
  name: string;
  description: string | null;
  dataset: string;
  format: ReportFormat;
  definition: ReportRequest;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DrillRow {
  record: Record<string, unknown>;
  urlPath: string;
}

export interface DrillResult {
  dataset: string;
  /** Candidates the projection listed. */
  projectedCandidates: number;
  returned: number;
  /** Listed by the projection, refused by the authoritative store. Reported, never padded over. */
  droppedByRecheck: number;
  rows: DrillRow[];
  withheldFields: string[];
  note: string;
  staleness: Staleness;
}

export interface MetricDefinition {
  id: string;
  metricCode: string;
  name: string;
  version: number;
  formula: string;
  basis: string | null;
  unit: string;
  notes: string | null;
  requirementRef: string | null;
  sourceReference: string | null;
  status: "DRAFT" | "ACTIVE" | "RETIRED";
  publishedAt: string | null;
  retiredAt: string | null;
  supersedesId: string | null;
  createdAt: string | null;
}

export interface KpiValue {
  metricCode: string;
  name: string;
  definitionVersion: number | null;
  formula: string | null;
  basis: string | null;
  unit: string;
  requirementRef: string | null;
  notes: string | null;
  value: number | null;
  /** False means the metric is deliberately withheld — render `missingInputs`, never a zero. */
  computable: boolean;
  missingInputs: string[];
  inputs: Record<string, unknown>;
  note: string | null;
  accessRestricted: boolean;
  periodStart: string;
  periodEnd: string;
  staleness: Staleness;
}

export interface BackfillRun {
  id: string;
  dataset: string | null;
  status: string;
  reason: string | null;
  totalUnits: number;
  processedUnits: number;
  rowsWritten: number;
  rowsRemoved: number;
  queuedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  message: string | null;
  results: Array<{ dataset: string; sourceRows: number; rowsWritten: number; rowsRemoved: number }>;
}

export interface PipelineSnapshotRow {
  id: string;
  capturedOn: string;
  capturedAt: string;
  captureReason: string;
  stageName: string;
  stageSortOrder: number;
  forecastCategory: string | null;
  opportunityCount: number;
  totalAmount: number;
  weightedAmount: number;
}

export interface ForecastSnapshotRow {
  id: string;
  capturedOn: string;
  capturedAt: string;
  captureReason: string;
  periodCode: string;
  periodStart: string;
  periodEnd: string;
  commitAmount: number;
  bestCaseAmount: number;
  pipelineAmount: number;
  omittedAmount: number;
  closedWonAmount: number;
  closedLostAmount: number;
  openCount: number;
  lineCount: number;
}

export interface ReconciliationCheck {
  checkCode: string;
  checkLabel: string;
  dataset: string;
  projected: number | null;
  authoritative: number | null;
  drift: number | null;
  driftPct: number | null;
  status: "MATCH" | "DRIFT" | "ERROR";
  detail: string;
  durationMs: number;
}

export interface ReconciliationReport {
  runAt: string;
  checksRun: number;
  checksMatched: number;
  checksDrifted: number;
  totalAbsoluteDrift: number;
  checks: ReconciliationCheck[];
  verdict: string;
}

/* ------------------------------------------------------------------ */
/* Calls                                                              */
/* ------------------------------------------------------------------ */

export const reportingApi = {
  datasets(): Promise<Dataset[]> {
    return request<Dataset[]>("/analytics/datasets");
  },

  run(definition: ReportRequest): Promise<ReportResult> {
    return request<ReportResult>("/analytics/reports/run", {
      method: "POST",
      body: JSON.stringify(definition),
    });
  },

  savedReports(): Promise<SavedReport[]> {
    return request<SavedReport[]>("/analytics/reports");
  },

  saveReport(code: string, name: string, description: string, definition: ReportRequest) {
    return request<SavedReport>("/analytics/reports", {
      method: "POST",
      body: JSON.stringify({ code, name, description, definition }),
    });
  },

  deleteReport(code: string): Promise<void> {
    return request<void>(`/analytics/reports/${encodeURIComponent(code)}`, { method: "DELETE" });
  },

  drill(definition: ReportRequest, limit = 25): Promise<DrillResult> {
    return request<DrillResult>(`/analytics/drill?limit=${limit}`, {
      method: "POST",
      body: JSON.stringify(definition),
    });
  },

  drillRecord(dataset: string, id: string): Promise<Record<string, unknown>> {
    return request<Record<string, unknown>>(
      `/analytics/drill/${encodeURIComponent(dataset)}/${encodeURIComponent(id)}`,
    );
  },

  metrics(): Promise<MetricDefinition[]> {
    return request<MetricDefinition[]>("/analytics/metrics");
  },

  metricVersions(code: string): Promise<MetricDefinition[]> {
    return request<MetricDefinition[]>(`/analytics/metrics/${encodeURIComponent(code)}/versions`);
  },

  kpis(periodStart?: string, periodEnd?: string): Promise<KpiValue[]> {
    const params = new URLSearchParams();
    if (periodStart) params.set("periodStart", periodStart);
    if (periodEnd) params.set("periodEnd", periodEnd);
    const query = params.toString();
    return request<KpiValue[]>(`/analytics/kpis${query ? `?${query}` : ""}`);
  },

  projectionStatus(): Promise<DatasetStaleness[]> {
    return request<DatasetStaleness[]>("/analytics/projections");
  },

  backfill(reason: string): Promise<BackfillRun> {
    return request<BackfillRun>("/analytics/projections/backfill", {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },

  captureSnapshot(reason: string): Promise<unknown> {
    return request<unknown>(
      `/analytics/snapshots/capture?reason=${encodeURIComponent(reason)}`,
      { method: "POST" },
    );
  },

  pipelineTrend(): Promise<PipelineSnapshotRow[]> {
    return request<PipelineSnapshotRow[]>("/analytics/snapshots/pipeline");
  },

  forecastSnapshots(): Promise<ForecastSnapshotRow[]> {
    return request<ForecastSnapshotRow[]>("/analytics/snapshots/forecast");
  },

  reconcile(): Promise<ReconciliationReport> {
    return request<ReconciliationReport>("/analytics/reconciliation/run", { method: "POST" });
  },

  reconciliationHistory(): Promise<ReconciliationCheck[]> {
    return request<ReconciliationCheck[]>("/analytics/reconciliation?limit=30");
  },
};

/* ------------------------------------------------------------------ */
/* Presentation helpers                                                */
/* ------------------------------------------------------------------ */

/** Compact age, matching the server's own wording so the two never disagree. */
export function formatLag(lagSeconds: number | null): string {
  if (lagSeconds === null || lagSeconds === undefined) return "no data";
  if (lagSeconds < 60) return `${lagSeconds}s`;
  if (lagSeconds < 3600) return `${Math.floor(lagSeconds / 60)}m`;
  if (lagSeconds < 86400) return `${Math.floor(lagSeconds / 3600)}h`;
  return `${Math.floor(lagSeconds / 86400)}d`;
}

const CURRENCY = new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 });
const DECIMAL = new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 });

/**
 * Render a governed KPI value in its declared unit.
 *
 * A metric that is not computable renders as an em dash — never as 0, and never as
 * a blank cell that could be mistaken for a loading state. The reason travels
 * separately in `missingInputs`.
 */
export function formatMetric(value: number | null, unit: string): string {
  if (value === null || value === undefined) return "—";
  switch (unit) {
    case "PERCENT":
      return `${DECIMAL.format(value * 100)}%`;
    case "RATIO":
      return `${DECIMAL.format(value)}x`;
    case "CURRENCY":
      return `$${CURRENCY.format(value)}`;
    case "CURRENCY_PER_DAY":
      return `$${CURRENCY.format(value)}/day`;
    case "DAYS":
      return `${DECIMAL.format(value)} days`;
    case "MONTHS":
      return `${DECIMAL.format(value)} months`;
    default:
      return DECIMAL.format(value);
  }
}

/** Cell rendering for a report grid. Numbers right-align via the `.num` class at the call site. */
export function formatCell(value: unknown, kind: string): string {
  if (value === null || value === undefined) return "—";
  if (kind === "MONEY") return `$${CURRENCY.format(Number(value))}`;
  if (kind === "NUMBER") return DECIMAL.format(Number(value));
  if (typeof value === "boolean") return value ? "Yes" : "No";
  return String(value);
}

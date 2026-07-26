/**
 * Axiom — typed API client.
 *
 * This is the ONLY module that knows the backend's URL layout and payload
 * shapes. If the backend shape shifts, fix it here.
 *
 * Base URL: http://localhost:8080/api/v1  (override with VITE_API_BASE_URL)
 */

const DEFAULT_API_BASE_URL = import.meta.env.DEV ? "http://localhost:8080/api/v1" : "/api/v1";

const BASE_URL: string =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ??
  DEFAULT_API_BASE_URL;

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

export interface ClientExportAuditRequest {
  objectType: string;
  filterCriteria?: Record<string, unknown>;
  rowCount?: number;
  destination?: string;
  format: "XLSX" | "DOCX" | "PDF";
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

export interface ResolvedReferenceEntry {
  entryId: string;
  valueSet: string;
  code: string;
  label: string;
  asOf: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  activeNow: boolean;
  selectableForNewRecords: boolean;
  note: string;
}

export interface LocaleOption {
  code: string;
  englishName: string;
  nativeName: string;
  isDefault: boolean;
  sortOrder: number;
}

/** Flat key_path -> translated value map for one locale. */
export type TranslationBundle = Record<string, string>;

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

/* --------------------------------------------------------------- contacts --- */

/**
 * The full contact row. `version` is the reason this type exists separately from
 * the lightweight `ContactRow` the picker uses: it is what makes an edit safe to
 * send back, and a type that omits it lets a caller write an unsafe PUT without
 * the compiler noticing.
 */
export interface ContactDetail {
  id: string;
  accountId: string | null;
  accountName: string | null;
  firstName: string;
  lastName: string;
  title: string | null;
  department: string | null;
  seniority: string | null;
  reportsToContactId: string | null;
  reportsToName: string | null;
  ownerId: string | null;
  ownerName: string | null;
  email: string | null;
  phone: string | null;
  mobile: string | null;
  status: string;
  emailBounced: boolean;
  lastEngagedAt: string | null;
  sourceSystem: string | null;
  externalRef: string | null;
  mergedIntoId: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface RecordLock {
  objectType: string;
  recordId: string;
  holderId: string;
  holderEmail: string;
  holderName: string | null;
  acquiredAt: string;
  expiresAt: string;
  heldByMe: boolean;
  heartbeatSeconds: number;
}

export interface RecordLockStatus {
  locked: boolean;
  lock: RecordLock | null;
}

export interface ContactRequest {
  firstName: string;
  lastName: string;
  accountId?: string | null;
  title?: string | null;
  department?: string | null;
  seniority?: string | null;
  reportsToContactId?: string | null;
  ownerId?: string | null;
  email?: string | null;
  phone?: string | null;
  mobile?: string | null;
  status?: string | null;
  sourceSystem?: string | null;
  externalRef?: string | null;
  acknowledgeDuplicates?: boolean;
  duplicateReason?: string | null;
}

export interface ContactTimelineEntry {
  id: string;
  activityType: string;
  subject: string;
  status: string;
  ownerName: string | null;
  occurredAt: string | null;
  dueAt: string | null;
}

export interface ContactAddress {
  id: string;
  addressType: string;
  isPrimary: boolean;
  line1: string;
  line2: string | null;
  city: string | null;
  stateRegion: string | null;
  postalCode: string | null;
  countryCode: string | null;
  validationStatus: string | null;
}

export interface ContactChannel {
  id: string;
  channel: string;
  channelType: string;
  value: string;
  isPrimary: boolean;
  verifiedAt: string | null;
}

/* ---------------------------------------------- saved views + bulk edit --- */

/**
 * The grid state a saved view stores. Mirrors `PersistedGridState` plus the
 * facets the server accepts — deliberately the same shape, because a saved view
 * is the live grid's state under a name, not a separate concept.
 */
export interface SavedViewDefinition {
  groupColumns?: string[];
  columnFilters?: Record<string, string>;
  sort?: { key: string; direction: 1 | -1 } | null;
  columnOrder?: string[];
  hiddenColumns?: string[];
}

/**
 * One client-observed event. `ageMs` is how long ago the client saw it — the
 * server timestamps on receipt and keeps this only as evidence, so a wrong client
 * clock cannot reorder the audit trail. `action` must be in the server's closed
 * vocabulary; anything else is skipped rather than failing the batch.
 */
export interface UiEventReport {
  action: string;
  screen: string;
  objectType?: string | null;
  objectId?: string | null;
  ageMs?: number;
}

/**
 * One row of reference.ui_theme (V336). `swatch` is exactly three hex colours —
 * ground, interactive accent, AI-provenance mark — and the length is enforced by
 * a database check constraint, so the client can render it without guarding.
 */
export interface UiTheme {
  code: string;
  name: string;
  blurb: string;
  swatch: string[];
  appearance: "LIGHT" | "DARK";
  isDefault: boolean;
  sortOrder: number;
}

/**
 * `selected` is the user's stored choice and is null when they have never made
 * one; `effective` is what to actually paint, which is the selection when it is
 * still on offer and the default otherwise. Painting `effective` rather than
 * `selected ?? defaultCode` is what makes retiring a theme actually retire it.
 */
export interface UiThemeState {
  themes: UiTheme[];
  selected: string | null;
  defaultCode: string;
  effective: string;
}

export interface SavedView {
  id: string;
  gridKey: string;
  name: string;
  description: string | null;
  ownerId: string;
  ownerName: string | null;
  visibility: "PRIVATE" | "SHARED";
  definition: SavedViewDefinition;
  isDefault: boolean;
  /** False for a shared view owned by somebody else: apply yes, change no. */
  editable: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface SavedViewRequest {
  gridKey: string;
  name: string;
  description?: string | null;
  visibility?: "PRIVATE" | "SHARED";
  definition: SavedViewDefinition;
  isDefault?: boolean;
}

export interface BulkFieldUpdate {
  recordIds: string[];
  field: string;
  value: string | null;
  reason?: string | null;
}

export interface BulkReassign {
  recordIds: string[];
  ownerId: string;
  reason?: string | null;
}

/** One row's outcome. `SKIPPED` and `FAILED` always carry a `detail`. */
export interface BulkRowOutcome {
  recordId: string;
  label: string | null;
  outcome: "APPLIED" | "SKIPPED" | "FAILED";
  detail: string | null;
  beforeValue: string | null;
  afterValue: string | null;
}

export interface BulkResult {
  operationId: string;
  objectType: string;
  operation: string;
  total: number;
  succeeded: number;
  failed: number;
  skipped: number;
  rows: BulkRowOutcome[];
  note: string;
}

/** Everything the detail drawer renders, from one request. */
export interface ContactView {
  contact: ContactDetail;
  directReports: ContactDetail[];
  timeline: ContactTimelineEntry[];
  addresses: ContactAddress[];
  channels: ContactChannel[];
}

export interface AccountDetail extends Account {
  legalName: string | null;
  accountNumber: string | null;
  recordType: string | null;
  parentAccountId: string | null;
  parentAccountName: string | null;
  ultimateParentId: string | null;
  ultimateParentName: string | null;
  hierarchyDepth: number;
  hierarchyPath: string | null;
  ownerId: string | null;
  businessUnit: string | null;
  territory: string | null;
  segment: string | null;
  employeeCount: number | null;
  annualRevenue: number | null;
  currencyCode: string | null;
  website: string | null;
  emailDomain: string | null;
  phone: string | null;
  status: string | null;
  healthScore: number | null;
  healthBand: string | null;
  fieldsHiddenByPermission: string[];
}

export interface AccountHierarchyNode {
  id: string;
  name: string;
  parentAccountId: string | null;
  depth: number;
  status: string;
  healthScore: number | null;
  healthBand: string | null;
  isSelf: boolean;
}

export interface AccountHierarchy {
  accountId: string;
  ultimateParentId: string | null;
  ultimateParentName: string | null;
  nodes: AccountHierarchyNode[];
  restricted: boolean;
  restrictionNote: string | null;
}

export interface AccountHealthFactor {
  code: string;
  label: string;
  weight: number;
  score: number;
  observed: string;
  direction: string;
  explanation: string;
}

export interface AccountHealth {
  accountId: string;
  score: number;
  band: string;
  factors: AccountHealthFactor[];
  computedAt: string;
  previousScore: number | null;
  previousBand: string | null;
  changeExplanation: string;
}

export interface AccountRollupFigures {
  accountsIncluded: number;
  openPipelineValue: number;
  openOpportunityCount: number;
  closedWonRevenue: number;
  closedWonCount: number;
  openCases: number | null;
  slaBreaches: number | null;
  caseSignalSource: string | null;
  caseSignalAsOf: string | null;
  lastActivityAt: string | null;
  activityCount90d: number;
}

export interface AccountRollup {
  accountId: string;
  accountName: string;
  ultimateParentId: string | null;
  ultimateParentName: string | null;
  hierarchyDepth: number;
  accountOnly: AccountRollupFigures;
  hierarchy: AccountRollupFigures;
  restricted: boolean;
  restrictionNote: string | null;
  unavailableMeasures: { code: string; label: string; reason: string }[];
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

export interface LeadIngestRequest {
  firstName: string;
  lastName: string;
  company: string;
  email?: string | null;
  phone?: string | null;
  title?: string | null;
  source?: string | null;
  campaignCode?: string | null;
  territory?: string | null;
  segment?: string | null;
  productInterest?: string | null;
  rating?: string | null;
  status?: string | null;
  ownerId?: string | null;
  notes?: string | null;
  qualificationData?: Record<string, unknown>;
  customFields?: Record<string, unknown>;
}

export interface LeadIngestRowResult {
  rowNumber: number;
  status: string;
  recordId: string | null;
  recordType: string | null;
  score: number | null;
  assignment: string | null;
  errors: string[];
  message: string;
}

export interface LeadBatchResult {
  batchId: string;
  submitted: number;
  accepted: number;
  rejected: number;
  rows: LeadIngestRowResult[];
  note: string;
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

export interface OpportunityGateIssue {
  gate: string;
  stageName: string;
  code: string;
  criterion: string;
  observation: string;
  action: string;
}

export interface OpportunityGatePreview {
  opportunityId: string;
  targetStageId: string;
  targetStageName: string;
  transitionKind: string;
  allowed: boolean;
  reasonRequired: boolean;
  appliedExitVersionId: string | null;
  appliedExitVersionNumber: number;
  unsatisfied: OpportunityGateIssue[];
  refusal: string | null;
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

export interface RoleDescriptor {
  code: string;
  scope: string;
  description: string;
  readOnly: boolean;
  exportAllowed: boolean;
  importAllowed: boolean;
  masterAdmin: boolean;
}

export interface ScreenPolicy {
  roleCode: string;
  screenCode: string;
  moduleCode: string;
  route: string;
  displayName: string;
  description: string;
  canRead: boolean;
  canWrite: boolean;
  canExport: boolean;
  canAdmin: boolean;
  scope: string;
}

export interface AdminUser {
  id: string;
  tenantSlug: string;
  tenantName: string;
  displayName: string;
  email: string;
  role: string;
  active: boolean;
  platformUser: boolean;
}

export interface CompanyAccount {
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

export interface BillingRow {
  tenantId: string;
  tenantSlug: string;
  tenantName: string;
  planCode: string;
  paymentStatus: string;
  billingEmail: string;
  currentPeriodStart: string | null;
  currentPeriodEnd: string | null;
  invoiceNumber: string | null;
  amount: number | null;
  currency: string | null;
  invoiceStatus: string | null;
  dueAt: string | null;
}

export interface ReportDefinition {
  id: string;
  code: string;
  label: string;
  description: string;
  allowedFormats: Array<"PDF" | "XLSX" | "DOCX" | string>;
  active: boolean;
  category: "EXECUTIVE" | "SALES" | "GROWTH" | "CUSTOMER" | "COMMERCIAL" | "GOVERNANCE" | "GENERAL";
  businessQuestion: string;
  audience: string[];
  sortOrder: number;
}

export interface ReportPreview {
  code: string;
  label: string;
  description: string;
  category: string;
  businessQuestion: string;
  audience: string[];
  tenantName: string;
  generatedAt: string;
  columns: { dimension: string; value: string; detail: string; signal: string };
  rows: PageResult<{ metric: string; value: string; detail: string; signal: string }>;
}

export interface ReportGridParams {
  page?: number;
  size?: number;
  search?: string;
  metric?: string;
  value?: string;
  detail?: string;
  signal?: string;
}

export interface EmailAlert {
  id: string;
  name: string;
  subject: string;
  bodyHtml: string;
  to: string[];
  cc: string[];
  bcc: string[];
  attachmentOptional: boolean;
  active: boolean;
  createdAt: string;
}

export interface ReportAlert {
  id: string;
  reportDefinitionId: string;
  reportLabel: string;
  name: string;
  subject: string;
  bodyHtml: string;
  to: string[];
  cc: string[];
  bcc: string[];
  formats: string[];
  active: boolean;
  createdAt: string;
}

export interface DispatchResult {
  dispatchId: string;
  status: string;
  message: string;
}

export interface ActivityRow {
  id: string;
  activityType: "TASK" | "EVENT" | "CALL" | "EMAIL_LOG" | "NOTE" | string;
  subject: string;
  body: string | null;
  status: "OPEN" | "COMPLETED" | "CANCELLED" | string;
  priority: "LOW" | "NORMAL" | "HIGH" | "URGENT" | string;
  relatedEntityType: "ACCOUNT" | "CONTACT" | "LEAD" | "OPPORTUNITY" | string;
  relatedEntityId: string;
  relatedLabel: string | null;
  ownerId: string;
  ownerName: string;
  dueAt: string | null;
  reminderAt: string | null;
  occurredAt: string;
  completedAt: string | null;
  outcome: string | null;
  direction: "INBOUND" | "OUTBOUND" | string | null;
  durationMinutes: number | null;
  disposition: string | null;
  participantCount: number;
}

export interface ActivitySummary {
  openCount: number;
  overdueCount: number;
  completedLast7Days: number;
  lastContactedAt: string | null;
  daysSinceLastActivity: number | null;
}

export interface ActivityRequest {
  activityType: string;
  subject: string;
  body?: string;
  priority?: string;
  relatedEntityType: string;
  relatedEntityId: string;
  dueAt?: string | null;
  reminderAt?: string | null;
  occurredAt?: string | null;
  direction?: string | null;
  durationMinutes?: number | null;
  disposition?: string | null;
}

export interface EmailTemplate {
  id: string;
  apiName: string;
  name: string;
  folder: string;
  description: string | null;
  sharingScope: string;
  active: boolean;
  currentVersion: number;
  subject: string;
  body: string;
  mergeFields: string[];
  changeNote: string | null;
  ownerId: string;
  ownerName: string;
  canEdit: boolean;
  updatedAt: string;
}

export interface CpqProduct {
  id: string;
  code: string;
  name: string;
  productFamily: string | null;
  category: string | null;
  unitOfMeasure: string;
  active: boolean;
  bundle: boolean;
  subscription: boolean;
  defaultCost: number | null;
  activePriceCount: number;
}

export interface CpqPriceBook {
  id: string;
  code: string;
  name: string;
  currencyCode: string;
  businessUnitCode: string | null;
  customerSegment: string | null;
  versionNumber: number;
  status: string;
  defaultBook: boolean;
  activatedAt: string | null;
  entryCount: number;
}

export interface CpqQuote {
  id: string;
  quoteNumber: string;
  name: string;
  versionNumber: number;
  activeVersion: boolean;
  status: string;
  approvalStatus: string;
  accountName: string;
  opportunityName: string | null;
  ownerName: string | null;
  currencyCode: string;
  subtotal: number;
  discountTotal: number;
  grandTotal: number;
  marginPct: number | null;
  validFrom: string | null;
  expiresAt: string | null;
}

export interface CpqQuoteSummary {
  totalQuotes: number;
  draftQuotes: number;
  approvalQuotes: number;
  sentQuotes: number;
  acceptedQuotes: number;
  netPipeline: number;
  acceptedRevenue: number;
}

export interface QuoteRevisionResult {
  previousQuoteId: string;
  quoteId: string;
  quoteGroupId: string;
  quoteNumber: string;
  versionNumber: number;
  status: string;
  copiedLines: number;
  message: string;
}

export interface WorkspaceMetric {
  label: string;
  value: string;
  unit: "money" | "count" | string;
  tone: "good" | "warn" | "crit" | string;
}

export interface WorkspaceRow {
  id: string;
  code: string;
  title: string;
  subtitle: string;
  status: string;
  ownerName: string | null;
  amount: number | null;
  targetDate: string | null;
  updatedAt: string | null;
  metrics: Record<string, unknown>;
}

export interface WorkspacePage {
  moduleCode: string;
  title: string;
  description: string;
  summary: WorkspaceMetric[];
  rows: PageResult<WorkspaceRow>;
}

export interface WorkspaceActionResult {
  id: string;
  module: string;
  status: string;
  message: string;
  details: Record<string, unknown>;
}

export interface CampaignPerformanceSnapshot {
  id: string; campaignId: string; members: number; responses: number; mqls: number; sqls: number;
  budget: number; influencedPipeline: number; roiPercent: number | null; capturedAt: string;
}

export interface CaseSlaSweepResult {
  caseId: string; missedMilestones: number; createdEscalations: number; caseStatus: string; message: string;
}

export interface PartnerRegistration {
  id: string; registrationNumber: string; customerName: string; dealName: string; status: string;
  conflictStatus: string; conflictCount: number; protectionExpiresAt: string | null; replayed: boolean;
}

export interface AutomationRuleVersion {
  id: string; versionNo: number; notes: string | null; restoredFromVersionNo: number | null;
  createdAt: string; active: boolean; definition: Record<string, unknown>;
}

export interface ReportSubscription {
  id: string; reportCode: string; name: string; format: "PDF" | "XLSX" | "DOCX";
  frequency: "DAILY" | "WEEKLY" | "MONTHLY"; recipients: string[]; enabled: boolean;
  nextRunAt: string; lastRunAt: string | null;
}

export interface ContractRenewalResult {
  planId: string;
  sourceContractId: string;
  generatedContractId: string;
  generatedContractNumber: string;
  startDate: string;
  endDate: string;
  subscriptionsCopied: number;
  alreadyGenerated: boolean;
  message: string;
}

export interface ForecastScenarioFactor {
  code: string;
  label: string;
  baseline: string;
  scenario: string;
  effect: string;
}

export interface ForecastScenario {
  id: string;
  submissionId: string;
  name: string;
  amountAdjustmentPct: number;
  confidencePct: number;
  riskCount: number;
  baselineAmount: number;
  scenarioAmount: number;
  weightedAmount: number;
  explanation: ForecastScenarioFactor[];
  createdAt: string;
  note: string;
}

export interface WorkflowGateIssue {
  code: string;
  gate: string;
  field: string | null;
  message: string;
  nextAction: string;
  targetState: string | null;
}

export interface WorkflowGateStatus {
  id: string | null;
  objectType: string;
  recordId: string;
  processCode: string | null;
  currentState: string | null;
  gateStatus: "NO_PROCESS" | "READY" | "BLOCKED" | "COMPLETED" | "UNKNOWN_STATE" | string;
  missingCount: number;
  nextStep: string;
  issues: WorkflowGateIssue[];
  evaluatedAt: string;
}

/* ------------------------------------------------------------------ */
/* Errors                                                              */
/* ------------------------------------------------------------------ */

/** HTTP-level failure: server responded with a non-2xx status. */
export class ApiError extends Error {
  readonly status: number;
  /**
   * The parsed error body, when the server sent one.
   *
   * <p>Several endpoints answer a 409 with structured detail the UI is meant to
   * act on rather than merely display — the duplicate engine returns the
   * candidate records it matched, their confidence, and the exact resolution
   * ("resend acknowledged, with a reason"). Keeping only `message` threw all of
   * that away and left the dialog able to say "duplicate" without being able to
   * show which record, so the user had to go and find it themselves.
   */
  readonly payload: unknown;

  constructor(status: number, message: string, payload?: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.payload = payload;
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
    let payload: unknown;
    let message = `Request failed (${res.status})`;
    try {
      const data: unknown = await res.json();
      payload = data;
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
    throw new ApiError(res.status, message, payload);
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

  /**
   * Translation endpoints are optionally authenticated on the server: without a
   * token they return the base product strings (so the login screen is
   * translated), with one they also apply the tenant's own relabelling.
   *
   * skipAuthRedirect: a 401 here means the stored token has expired. The other
   * queries on the page will hit the same 401 and drive the logout properly;
   * having a background bundle refresh be the thing that ejects the user would
   * make an expiry look like a random sign-out.
   */
  locales(): Promise<LocaleOption[]> {
    return request<LocaleOption[]>("GET", "/i18n/locales", undefined, { skipAuthRedirect: true });
  },

  translationBundle(locale: string): Promise<TranslationBundle> {
    return request<TranslationBundle>("GET", `/i18n/bundle/${encodeURIComponent(locale)}`,
      undefined, { skipAuthRedirect: true });
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

  recordClientExportAudit(payload: ClientExportAuditRequest): Promise<void> {
    return request<void>("POST", "/audit/exports", payload);
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

  /* ------------------------------------------------------------ contacts ----
     The grid binds to /contacts/full rather than /contacts. Both return the same
     rows; only the full projection carries `version`, and an editor without the
     version cannot send a safe PUT — optimistic locking would silently decay
     into last-write-wins. The lighter endpoint stays for callers that only
     render names. */

  contactsFull(params: { accountId?: string; search?: string; status?: string } = {}): Promise<ContactDetail[]> {
    return request<ContactDetail[]>("GET", `/contacts/full${queryString(params)}`);
  },

  recordLockStatus(objectType: string, recordId: string): Promise<RecordLockStatus> {
    return request<RecordLockStatus>("GET", `/record-locks/${encodeURIComponent(objectType)}/${encodeURIComponent(recordId)}`);
  },

  acquireRecordLock(objectType: string, recordId: string): Promise<RecordLock> {
    return request<RecordLock>("POST", `/record-locks/${encodeURIComponent(objectType)}/${encodeURIComponent(recordId)}`);
  },

  heartbeatRecordLock(objectType: string, recordId: string): Promise<RecordLock> {
    return request<RecordLock>("PUT", `/record-locks/${encodeURIComponent(objectType)}/${encodeURIComponent(recordId)}/heartbeat`);
  },

  releaseRecordLock(objectType: string, recordId: string): Promise<void> {
    return request<void>("DELETE", `/record-locks/${encodeURIComponent(objectType)}/${encodeURIComponent(recordId)}`);
  },

  forceReleaseRecordLock(objectType: string, recordId: string): Promise<{ message: string }> {
    return request<{ message: string }>("POST", `/record-locks/${encodeURIComponent(objectType)}/${encodeURIComponent(recordId)}/force-release`);
  },

  contact(id: string): Promise<ContactDetail> {
    return request<ContactDetail>("GET", `/contacts/${encodeURIComponent(id)}`);
  },

  contactView(id: string): Promise<ContactView> {
    return request<ContactView>("GET", `/contacts/${encodeURIComponent(id)}/view`);
  },

  createContact(body: ContactRequest): Promise<ContactDetail> {
    return request<ContactDetail>("POST", "/contacts", body);
  },

  /** `version` is the one the editor loaded. A 409 means someone else saved first. */
  updateContact(id: string, version: number, body: ContactRequest): Promise<ContactDetail> {
    return request<ContactDetail>("PUT", `/contacts/${encodeURIComponent(id)}${queryString({ version })}`, body);
  },

  cloneContact(id: string, overrides: Partial<ContactRequest> = {}): Promise<ContactDetail> {
    return request<ContactDetail>("POST", `/contacts/${encodeURIComponent(id)}/clone`, overrides);
  },

  reassignContact(id: string, ownerId: string, reason?: string): Promise<ContactDetail> {
    return request<ContactDetail>("POST", `/contacts/${encodeURIComponent(id)}/reassign`, { ownerId, reason });
  },

  deleteContact(id: string, reason?: string): Promise<void> {
    return request<void>("DELETE", `/contacts/${encodeURIComponent(id)}${queryString({ reason })}`);
  },

  /* ----------------------------------------------------- ui activity log ----
     Append-only. The client reports WHAT happened; the server supplies the actor
     from the verified token and the timestamp from the database, so neither can
     be forged from here. */

  reportUiEvents(events: UiEventReport[]): Promise<{ received: number; accepted: number }> {
    return request<{ received: number; accepted: number }>(
      "POST", "/activity/ui-events", { events });
  },

  /* ----------------------------------------------------------------- ui ----
     The theme catalogue and this user's choice. One GET returns both, because
     the theme decides the first paint and two sequential requests would mean
     painting the default and then switching. */

  uiTheme(): Promise<UiThemeState> {
    return request<UiThemeState>("GET", "/ui/theme");
  },

  /**
   * The catalogue alone, for the sign-in screen — there is no token there, so no
   * preference to read. The choice is cached locally and attached to the user by
   * chooseUiTheme once they are signed in.
   */
  publicUiThemes(): Promise<UiTheme[]> {
    return request<UiTheme[]>("GET", "/public/ui/themes");
  },

  /** Pass null to clear the choice and follow the product default again. */
  chooseUiTheme(themeCode: string | null): Promise<UiThemeState> {
    return request<UiThemeState>("PUT", "/ui/theme", { themeCode });
  },

  /* ------------------------------------------------- saved views + bulk ----
     `gridKey` is the same string usePersistedGridState uses for localStorage, so
     a saved view and the live grid are describing the same object. If those two
     ever diverge, applying a view silently drops whatever the grid understands
     and the view does not. */

  savedViews(gridKey: string): Promise<SavedView[]> {
    return request<SavedView[]>("GET", `/list-views${queryString({ gridKey })}`);
  },

  createSavedView(body: SavedViewRequest): Promise<SavedView> {
    return request<SavedView>("POST", "/list-views", body);
  },

  updateSavedView(id: string, version: number, body: SavedViewRequest): Promise<SavedView> {
    return request<SavedView>("PUT", `/list-views/${encodeURIComponent(id)}${queryString({ version })}`, body);
  },

  deleteSavedView(id: string): Promise<void> {
    return request<void>("DELETE", `/list-views/${encodeURIComponent(id)}`);
  },

  /** Published by the server so the picker offers exactly what it will accept. */
  bulkEditableFields(): Promise<Record<string, string[]>> {
    return request<Record<string, string[]>>("GET", "/list-views/bulk/editable-fields");
  },

  bulkUpdateField(objectType: string, body: BulkFieldUpdate): Promise<BulkResult> {
    return request<BulkResult>("POST", `/list-views/bulk/${encodeURIComponent(objectType)}/field`, body);
  },

  bulkReassign(objectType: string, body: BulkReassign): Promise<BulkResult> {
    return request<BulkResult>("POST", `/list-views/bulk/${encodeURIComponent(objectType)}/reassign`, body);
  },

  referenceValueSets(): Promise<ReferenceValueSet[]> {
    return request<ReferenceValueSet[]>("GET", "/reference/value-sets");
  },

  referenceEntries(apiName: string, includeInactive = true): Promise<ReferenceEntry[]> {
    return request<ReferenceEntry[]>("GET", `/reference/value-sets/${encodeURIComponent(apiName)}/entries${queryString({ includeInactive })}`);
  },

  resolveReferenceEntry(apiName: string, code: string, asOf: string): Promise<ResolvedReferenceEntry> {
    return request<ResolvedReferenceEntry>("GET",
      `/reference/value-sets/${encodeURIComponent(apiName)}/resolve/${encodeURIComponent(code)}${queryString({ asOf })}`);
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

  account(id: string): Promise<AccountDetail> {
    return request<AccountDetail>("GET", `/accounts/${encodeURIComponent(id)}`);
  },

  accountHierarchy(id: string): Promise<AccountHierarchy> {
    return request<AccountHierarchy>("GET", `/accounts/${encodeURIComponent(id)}/hierarchy`);
  },

  accountRollup(id: string): Promise<AccountRollup> {
    return request<AccountRollup>("GET", `/accounts/${encodeURIComponent(id)}/rollup`);
  },

  accountHealth(id: string): Promise<AccountHealth | null> {
    return request<AccountHealth | null>("GET", `/accounts/${encodeURIComponent(id)}/health`);
  },

  recomputeAccountHealth(id: string): Promise<AccountHealth> {
    return request<AccountHealth>("POST", `/accounts/${encodeURIComponent(id)}/health/recompute`);
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

  captureLead(body: LeadIngestRequest): Promise<LeadIngestRowResult> {
    return request<LeadIngestRowResult>("POST", "/leads", body);
  },

  bulkCaptureLeads(rows: LeadIngestRequest[]): Promise<LeadBatchResult> {
    return request<LeadBatchResult>("POST", "/leads/bulk", rows);
  },

  convertLead(leadId: string): Promise<void> {
    return request<void>("POST", `/leads/${encodeURIComponent(leadId)}/convert`);
  },

  disqualifyLead(leadId: string, req: { reasonCode: string; note?: string | null; recycleDate?: string | null }): Promise<{ leadId: string; status: string; reasonCode: string; recycleDate: string | null }> {
    return request<{ leadId: string; status: string; reasonCode: string; recycleDate: string | null }>("POST", `/leads/${encodeURIComponent(leadId)}/disqualify`, req);
  },

  pipelineBoard(): Promise<PipelineBoard> {
    return request<BoardStage[]>("GET", "/pipeline/board").then((stages) => ({ stages }));
  },

  /** 409 → ApiError whose message is the server's exact stage-gate refusal. */
  previewOpportunityStage(opportunityId: string, targetStageId: string): Promise<OpportunityGatePreview> {
    return request<OpportunityGatePreview>("GET",
      `/opportunities/${encodeURIComponent(opportunityId)}/stage-gate${queryString({ targetStageId })}`);
  },

  moveOpportunity(opportunityId: string, stageId: string, reason?: string): Promise<void> {
    return request<void>(
      "POST",
      `/opportunities/${encodeURIComponent(opportunityId)}/stage`,
      { stageId, reason: reason?.trim() || null },
    );
  },

  workflowGate(objectType: string, recordId: string): Promise<WorkflowGateStatus> {
    return request<WorkflowGateStatus>("GET",
      `/automation/workflow-gates/${encodeURIComponent(objectType)}/${encodeURIComponent(recordId)}`);
  },

  workflowTransitionGate(objectType: string, recordId: string, targetState: string,
                         proposedValues: Record<string, unknown> = {}): Promise<WorkflowGateStatus> {
    return request<WorkflowGateStatus>("POST",
      `/automation/workflow-gates/${encodeURIComponent(objectType)}/${encodeURIComponent(recordId)}`
      + `/transitions/${encodeURIComponent(targetState)}/check`, proposedValues);
  },

  workflowGates(params?: { objectType?: string; status?: string; limit?: number }): Promise<WorkflowGateStatus[]> {
    return request<WorkflowGateStatus[]>("GET", `/automation/workflow-gates${queryString(params ?? {})}`);
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

  roles(): Promise<RoleDescriptor[]> {
    return request<RoleDescriptor[]>("GET", "/rbac/roles");
  },

  rbacPolicies(role?: string): Promise<ScreenPolicy[]> {
    return request<ScreenPolicy[]>("GET", `/rbac/policies${queryString({ role })}`);
  },

  adminUsers(): Promise<AdminUser[]> {
    return request<AdminUser[]>("GET", "/admin/users");
  },

  createAdminUser(req: { displayName: string; email: string; role: string; password: string }): Promise<AdminUser> {
    return request<AdminUser>("POST", "/admin/users", req);
  },

  setAdminUserActive(id: string, active: boolean): Promise<void> {
    return request<void>("PATCH", `/admin/users/${encodeURIComponent(id)}/active`, { active });
  },

  companies(): Promise<CompanyAccount[]> {
    return request<CompanyAccount[]>("GET", "/admin/companies");
  },

  extendTrial(tenantId: string, req: { days: number; months: number; note?: string }): Promise<CompanyAccount> {
    return request<CompanyAccount>("POST", `/admin/trials/${encodeURIComponent(tenantId)}/extend`, req);
  },

  setCompanyStatus(tenantId: string, req: { status: string; reason?: string }): Promise<CompanyAccount> {
    return request<CompanyAccount>("PATCH", `/admin/companies/${encodeURIComponent(tenantId)}/status`, req);
  },

  billing(): Promise<BillingRow[]> {
    return request<BillingRow[]>("GET", "/admin/billing");
  },

  reports(): Promise<ReportDefinition[]> {
    return request<ReportDefinition[]>("GET", "/reports");
  },

  reportPreview(code: string, params?: ReportGridParams): Promise<ReportPreview> {
    return request<ReportPreview>("GET", `/reports/${encodeURIComponent(code)}/preview${queryString({
      page: params?.page ?? 0,
      size: params?.size ?? 100,
      search: params?.search,
      metric: params?.metric,
      value: params?.value,
      detail: params?.detail,
      signal: params?.signal,
    })}`);
  },

  reportDocumentPreview(code: string, params?: ReportGridParams): Promise<DownloadedFile> {
    return fileRequest(`/reports/${encodeURIComponent(code)}/document-preview${queryString({
      search: params?.search,
      metric: params?.metric,
      value: params?.value,
      detail: params?.detail,
      signal: params?.signal,
    })}`);
  },

  downloadReport(code: string, format: "PDF" | "XLSX" | "DOCX", params?: ReportGridParams): Promise<DownloadedFile> {
    return fileRequest(`/reports/${encodeURIComponent(code)}/download${queryString({
      format,
      search: params?.search,
      metric: params?.metric,
      value: params?.value,
      detail: params?.detail,
      signal: params?.signal,
    })}`);
  },

  reportSubscriptions(): Promise<ReportSubscription[]> {
    return request<ReportSubscription[]>("GET", "/reports/subscriptions");
  },

  createReportSubscription(req: { reportCode: string; name: string; format: "PDF" | "XLSX" | "DOCX";
    frequency: "DAILY" | "WEEKLY" | "MONTHLY"; recipients: string[]; nextRunAt?: string }): Promise<ReportSubscription> {
    return request<ReportSubscription>("POST", "/reports/subscriptions", req);
  },

  runDueReportSubscriptions(): Promise<{ subscriptionId: string; status: string; filename: string | null; nextRunAt: string }[]> {
    return request("POST", "/reports/subscriptions/run-due");
  },

  emailAlerts(): Promise<EmailAlert[]> {
    return request<EmailAlert[]>("GET", "/alerts/email");
  },

  createEmailAlert(req: {
    name: string; subject: string; bodyHtml: string; to: string[]; cc: string[]; bcc: string[]; attachmentOptional: boolean;
  }): Promise<EmailAlert> {
    return request<EmailAlert>("POST", "/alerts/email", req);
  },

  sendEmailAlert(id: string): Promise<DispatchResult> {
    return request<DispatchResult>("POST", `/alerts/email/${encodeURIComponent(id)}/send`);
  },

  reportAlerts(): Promise<ReportAlert[]> {
    return request<ReportAlert[]>("GET", "/alerts/reports");
  },

  createReportAlert(req: {
    name: string; subject: string; bodyHtml: string; to: string[]; cc: string[]; bcc: string[]; formats: string[]; reportDefinitionId?: string;
  }): Promise<ReportAlert> {
    return request<ReportAlert>("POST", "/alerts/reports", req);
  },

  sendReportAlert(id: string): Promise<DispatchResult> {
    return request<DispatchResult>("POST", `/alerts/reports/${encodeURIComponent(id)}/send`);
  },

  activities(params?: ListParams & { type?: string; status?: string; relatedEntityType?: string; relatedEntityId?: string }): Promise<PageResult<ActivityRow>> {
    return request<PageResult<ActivityRow>>("GET", `/activities${queryString({
      page: params?.page ?? 0,
      search: params?.search,
      type: params?.type,
      status: params?.status,
      relatedEntityType: params?.relatedEntityType,
      relatedEntityId: params?.relatedEntityId,
    })}`);
  },

  activitySummary(): Promise<ActivitySummary> {
    return request<ActivitySummary>("GET", "/activities/summary");
  },

  createActivity(req: ActivityRequest): Promise<ActivityRow> {
    return request<ActivityRow>("POST", "/activities", req);
  },

  completeActivity(id: string, outcome: string): Promise<ActivityRow> {
    return request<ActivityRow>("PATCH", `/activities/${encodeURIComponent(id)}/complete`, { outcome });
  },

  emailTemplates(): Promise<EmailTemplate[]> {
    return request<EmailTemplate[]>("GET", "/engagement/email-templates");
  },

  createEmailTemplate(req: { apiName: string; name: string; folder?: string; description?: string;
    sharingScope: string; subject: string; body: string; mergeFields?: string[]; changeNote?: string }): Promise<EmailTemplate> {
    return request<EmailTemplate>("POST", "/engagement/email-templates", req);
  },

  reviseEmailTemplate(id: string, req: { subject: string; body: string; mergeFields?: string[]; changeNote: string }): Promise<EmailTemplate> {
    return request<EmailTemplate>("POST", `/engagement/email-templates/${encodeURIComponent(id)}/versions`, req);
  },

  cpqProducts(params?: ListParams & { category?: string }): Promise<PageResult<CpqProduct>> {
    return request<PageResult<CpqProduct>>("GET", `/cpq/products${queryString({
      page: params?.page ?? 0,
      search: params?.search,
      category: params?.category ?? params?.filter,
    })}`);
  },

  cpqPriceBooks(params?: ListParams & { status?: string }): Promise<PageResult<CpqPriceBook>> {
    return request<PageResult<CpqPriceBook>>("GET", `/cpq/price-books${queryString({
      page: params?.page ?? 0,
      search: params?.search,
      status: params?.status ?? params?.filter,
    })}`);
  },

  cpqQuotes(params?: ListParams & { status?: string }): Promise<PageResult<CpqQuote>> {
    return request<PageResult<CpqQuote>>("GET", `/cpq/quotes${queryString({
      page: params?.page ?? 0,
      search: params?.search,
      status: params?.status ?? params?.filter,
    })}`);
  },

  cpqQuoteSummary(): Promise<CpqQuoteSummary> {
    return request<CpqQuoteSummary>("GET", "/cpq/quotes/summary");
  },

  downloadQuote(quoteId: string, format: "PDF" | "DOCX" | "XLSX"): Promise<DownloadedFile> {
    return fileRequest(`/cpq/quotes/${encodeURIComponent(quoteId)}/download${queryString({ format })}`);
  },

  reviseQuote(quoteId: string, reason: string): Promise<QuoteRevisionResult> {
    return request<QuoteRevisionResult>("POST", `/cpq/quotes/${encodeURIComponent(quoteId)}/revisions`, { reason });
  },

  workspace(module: "forecast" | "contracts" | "campaigns" | "cases" | "migration" | "partners" | "automation" | "analytics" | "copilot" | "mobile" | "integrations" | "sandbox" | "audit" | "bfsi" | "commodity", params?: ListParams & { status?: string }): Promise<WorkspacePage> {
    return request<WorkspacePage>("GET", `/workspaces/${module}${queryString({
      page: params?.page ?? 0,
      search: params?.search,
      status: params?.status ?? params?.filter,
    })}`);
  },

  exportWorkspace(module: "forecast" | "contracts" | "campaigns" | "cases" | "migration" | "partners" | "automation" | "analytics" | "copilot" | "mobile" | "integrations" | "sandbox" | "audit" | "bfsi" | "commodity", format: "XLSX" | "DOCX" | "PDF", params?: ListParams & { status?: string }): Promise<DownloadedFile> {
    return fileRequest(`/workspaces/${module}/export${queryString({
      format,
      page: params?.page ?? 0,
      search: params?.search,
      status: params?.status ?? params?.filter,
    })}`);
  },

  submitForecast(id: string, managerNote?: string): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/forecast/${encodeURIComponent(id)}/submit`, {
      managerNote: managerNote?.trim() || null,
    });
  },

  prepareContractRenewal(id: string, rationale: string): Promise<ContractRenewalResult> {
    return request<ContractRenewalResult>("POST", `/workspaces/contracts/${encodeURIComponent(id)}/renewal`, { rationale });
  },

  forecastScenarios(id: string): Promise<ForecastScenario[]> {
    return request<ForecastScenario[]>("GET", `/workspaces/forecast/${encodeURIComponent(id)}/scenarios`);
  },

  createForecastScenario(id: string, req: { name: string; amountAdjustmentPct: number;
    confidencePct?: number; riskReduction?: number }): Promise<ForecastScenario> {
    return request<ForecastScenario>("POST", `/workspaces/forecast/${encodeURIComponent(id)}/scenarios`, req);
  },

  resolveCase(id: string, outcome: string): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/cases/${encodeURIComponent(id)}/resolve`, { outcome });
  },

  simulateAutomation(id: string, sampleSize = 25): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/automation/${encodeURIComponent(id)}/simulate`, { sampleSize });
  },

  validateMigration(id: string): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/migration/${encodeURIComponent(id)}/validate`);
  },

  acknowledgeMobileSync(id: string): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/mobile/${encodeURIComponent(id)}/sync`);
  },

  activateContract(id: string, signedDocumentRef: string): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/contracts/${encodeURIComponent(id)}/activate`, { signedDocumentRef });
  },

  completeCampaign(id: string, outcome: string): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/campaigns/${encodeURIComponent(id)}/complete`, { outcome });
  },

  captureCampaignPerformance(id: string): Promise<CampaignPerformanceSnapshot> {
    return request<CampaignPerformanceSnapshot>("POST", `/workspaces/campaigns/${encodeURIComponent(id)}/performance`);
  },

  sweepCaseSla(id: string): Promise<CaseSlaSweepResult> {
    return request<CaseSlaSweepResult>("POST", `/workspaces/cases/${encodeURIComponent(id)}/sla-sweep`);
  },

  registerPartnerDeal(id: string, opportunityId: string, amount?: number): Promise<PartnerRegistration> {
    return request<PartnerRegistration>("POST", `/workspaces/partners/${encodeURIComponent(id)}/registrations`, {
      opportunityId, amount: amount ?? null, idempotencyKey: crypto.randomUUID(),
    });
  },

  automationRuleVersions(id: string): Promise<AutomationRuleVersion[]> {
    return request<AutomationRuleVersion[]>("GET", `/automation/rules/${encodeURIComponent(id)}/versions`);
  },

  restoreAutomationRuleVersion(id: string, versionNo: number): Promise<unknown> {
    return request("POST", `/automation/rules/${encodeURIComponent(id)}/versions/${versionNo}/restore`);
  },

  activatePartner(id: string): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/partners/${encodeURIComponent(id)}/activate`);
  },

  acceptCopilotRecommendation(id: string, note?: string): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/copilot/${encodeURIComponent(id)}/accept`, {
      note: note?.trim() || null,
    });
  },

  clearBfsiOnboarding(id: string, note: string): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/bfsi/${encodeURIComponent(id)}/clear`, { note });
  },

  refreshDashboard(id: string, note?: string): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/analytics/${encodeURIComponent(id)}/refresh`, {
      note: note?.trim() || null,
    });
  },

  verifyIntegrationContract(id: string): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/integrations/${encodeURIComponent(id)}/verify`);
  },

  refreshSandbox(id: string, reason: string): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/sandbox/${encodeURIComponent(id)}/refresh`, { reason });
  },

  exportAuditPack(id: string, destination = "SECURE_DOWNLOAD"): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/audit/${encodeURIComponent(id)}/export`, { destination });
  },

  offerCommodityEnquiry(id: string): Promise<WorkspaceActionResult> {
    return request<WorkspaceActionResult>("POST", `/workspaces/commodity/${encodeURIComponent(id)}/offer`);
  },
};

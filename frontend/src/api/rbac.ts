/**
 * RBAC administration and user-activity client.
 *
 * Kept out of `client.ts` deliberately. That module is the CRM's business
 * surface; this one fronts the authorization engine and the access log, which
 * only two screens consume and which a business page should never accidentally
 * import. Separating them also keeps the security contract reviewable on its
 * own — sibling modules `access.ts`, `integration.ts` and `search.ts` follow the
 * same convention.
 */

const DEFAULT_API_BASE_URL = import.meta.env.DEV ? "http://localhost:8080/api/v1" : "/api/v1";
const BASE_URL: string =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? DEFAULT_API_BASE_URL;

/** Mirrors the shared client's error shape so pages can branch on `status` alike. */
export class RbacApiError extends Error {
  readonly status: number;
  readonly code?: string;

  constructor(status: number, message: string, code?: string) {
    super(message);
    this.name = "RbacApiError";
    this.status = status;
    this.code = code;
  }
}

/**
 * The token is owned by `client.ts` (which holds it in a module-level variable
 * we cannot read from here), so `AuthProvider` cannot prime this module. Reading
 * the same localStorage entry the provider writes keeps one source of truth
 * without reaching into another module's internals.
 */
function bearer(): string | null {
  try {
    const raw = window.localStorage.getItem("axiom.session");
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { token?: string };
    return parsed.token ?? null;
  } catch {
    return null;
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = bearer();
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";

  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = res.status === 204 ? "" : await res.text();
  const parsed = text ? (JSON.parse(text) as Record<string, unknown>) : undefined;
  if (!res.ok) {
    const message =
      typeof parsed?.message === "string" ? parsed.message : `Request failed (${res.status})`;
    throw new RbacApiError(res.status, message, parsed?.code as string | undefined);
  }
  return parsed as T;
}

function qs(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined) return;
    const asString = String(value);
    if (!asString.trim()) return;
    search.append(key, asString);
  });
  const out = search.toString();
  return out ? `?${out}` : "";
}

// ---------------------------------------------------------------------------
// Types — mirror of the backend records
// ---------------------------------------------------------------------------

export interface RoleNode {
  id: string;
  code: string;
  name: string;
  description: string | null;
  parentId: string | null;
  parentCode: string | null;
  path: string;
  depth: number;
  active: boolean;
  memberCount: number;
}

export interface RoleRequest {
  code: string;
  name: string;
  description?: string | null;
  parentId?: string | null;
}

export interface ProfileRow {
  id: string;
  code: string;
  name: string;
  description: string | null;
  systemManaged: boolean;
  exportRowLimit: number | null;
  active: boolean;
  userCount: number;
}

export interface PermissionSetRow {
  id: string;
  code: string;
  name: string;
  description: string | null;
  active: boolean;
  assignmentCount: number;
}

export interface GroupMember {
  permissionSetId: string;
  permissionSetCode: string;
  mutedPermissions: string[];
}

export interface PermissionSetGroupRow {
  id: string;
  code: string;
  name: string;
  description: string | null;
  active: boolean;
  members: GroupMember[];
}

export interface ObjectPermissionRow {
  holderType: string;
  holderId: string;
  objectType: string;
  canCreate: boolean;
  canRead: boolean;
  canEdit: boolean;
  canDelete: boolean;
  viewAll: boolean;
  modifyAll: boolean;
  canExport: boolean;
  canReveal: boolean;
}

export interface FieldPermissionRow {
  holderType: string;
  holderId: string;
  objectType: string;
  fieldName: string;
  readable: boolean;
  editable: boolean;
}

export interface AssignmentRow {
  id: string;
  userId: string;
  userEmail: string;
  permissionSetId: string | null;
  permissionSetCode: string | null;
  groupId: string | null;
  groupCode: string | null;
  expiresAt: string | null;
  grantedAt: string;
  grantedByEmail: string | null;
  revokedAt: string | null;
}

export interface ApprovalRequest {
  id: string;
  actionCode: string;
  entityType: string;
  entityId: string | null;
  summary: string;
  payload: string;
  initiatedBy: string;
  initiatedByEmail: string;
  initiatedAt: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "WITHDRAWN";
  decidedBy: string | null;
  decidedByEmail: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
}

export interface ApprovalDelegation {
  id: string;
  delegatorId: string;
  delegatorEmail: string;
  delegateId: string;
  delegateEmail: string;
  startsAt: string;
  expiresAt: string | null;
  active: boolean;
}

export interface EffectivePermissionView {
  userId: string;
  userEmail: string;
  profileCode: string | null;
  permissionSets: string[];
  roleCode: string | null;
  rolePath: string | null;
  permissionCodes: string[];
  objectAccess: Record<string, Record<string, boolean>>;
  unreadableFields: Record<string, string[]>;
  exportRowLimit: number | null;
}

export interface OrgWideDefaultRow {
  objectType: string;
  defaultAccess: "private" | "read_only" | "read_write";
  roleHierarchyRollup: boolean;
  updatedAt: string | null;
}

export interface PermissionDescriptor {
  code: string;
  category: string;
  label: string;
  description: string;
}

export interface SharingRule {
  id: string;
  code: string;
  name: string;
  objectType: string;
  ruleType: "CRITERIA" | "OWNER";
  criteriaField: string | null;
  criteriaOperator: string | null;
  criteriaValue: string | null;
  sourceType: string | null;
  sourceId: string | null;
  sourceLabel: string | null;
  targetType: string;
  targetId: string;
  targetLabel: string | null;
  targetIncludesSubordinates: boolean;
  accessLevel: "READ" | "READ_WRITE";
  active: boolean;
  lastRecomputedAt: string | null;
  materializedShares: number;
}

export interface SharingRuleRequest {
  code: string;
  name: string;
  objectType: string;
  ruleType: string;
  criteriaField?: string | null;
  criteriaOperator?: string | null;
  criteriaValue?: string | null;
  sourceType?: string | null;
  sourceId?: string | null;
  targetType: string;
  targetId: string;
  targetIncludesSubordinates?: boolean;
  accessLevel: string;
}

export interface RecomputeJob {
  id: string;
  objectType: string;
  scope: string;
  scopeRef: string | null;
  triggerReason: string;
  status: "QUEUED" | "RUNNING" | "COMPLETE" | "FAILED";
  totalUnits: number;
  processedUnits: number;
  sharesWritten: number;
  sharesRevoked: number;
  queuedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  message: string | null;
}

export interface NamedRow {
  id: string;
  code: string;
  name: string;
  memberCount: number;
}

export interface SodConflict {
  id: string;
  code: string;
  name: string;
  permissionA: string;
  permissionB: string;
  severity: string;
  rationale: string | null;
  active: boolean;
}

export interface SodFinding {
  id: string;
  conflictId: string;
  conflictCode: string;
  conflictName: string;
  userId: string;
  userEmail: string;
  holderA: string;
  holderB: string;
  status: string;
  detectedAt: string;
}

export interface SensitiveField {
  objectType: string;
  fieldName: string;
  maskStyle: string;
  revealPermission: string;
  reason: string | null;
}

export interface TenantUser {
  id: string;
  email: string;
  displayName: string;
  crmRole: string;
  active: boolean;
}

export interface RbacOverview {
  permissions: PermissionDescriptor[];
  objects: string[];
  roles: RoleNode[];
  profiles: ProfileRow[];
  permissionSets: PermissionSetRow[];
  userGroups: NamedRow[];
  territories: NamedRow[];
  users: TenantUser[];
  sensitiveFields: SensitiveField[];
  canWrite: boolean;
}

export interface AccessCause {
  layer: string;
  cause: string | null;
  verdict: "GRANT" | "DENY" | "NOT_APPLICABLE";
  accessLevel: string | null;
  ruleRef: string | null;
  detail: string;
  expiresAt: string | null;
}

export interface MaterializedShare {
  id: string;
  cause: string;
  causeRef: string | null;
  causeDetail: string | null;
  accessLevel: string;
  expiresAt: string | null;
  createdAt: string;
}

export interface AccessExplanation {
  userId: string;
  userEmail: string;
  userDisplayName: string;
  userCrmRole: string;
  profileCode: string;
  roleCode: string | null;
  rolePath: string | null;
  objectType: string;
  recordId: string;
  recordExists: boolean;
  orgWideDefault: string;
  roleHierarchyRollup: boolean;
  canRead: boolean;
  canEdit: boolean;
  verdict: "READ_WRITE" | "READ" | "NO_ACCESS" | "NO_SUCH_RECORD";
  denialReason: string | null;
  causes: AccessCause[];
  materializedShares: MaterializedShare[];
  unreadableFields: string[];
  materializationMatchesLiveEvaluation: boolean;
}

export interface FloorFinding {
  tenantId: string;
  tenantSlug: string;
  tenantName: string;
  roleCode: string;
  requirement: string;
  activeHolders: number;
  compliant: boolean;
  finding: string;
  remedy: string | null;
}

export interface FloorReport {
  findings: FloorFinding[];
  tenantsInspected: number;
  violations: number;
  crossTenant: boolean;
}

export interface AccessReviewCampaign {
  id: string;
  code: string;
  name: string;
  scopeNote: string | null;
  deadlineAt: string;
  status: "DRAFT" | "OPEN" | "CLOSED";
  createdAt: string;
  totalItems: number;
  pendingItems: number;
  confirmedItems: number;
  revokedItems: number;
  overdue: boolean;
}

export interface AccessReviewItem {
  id: string;
  campaignId: string;
  grantType: string;
  grantRef: string;
  subjectUserId: string;
  subjectEmail: string;
  description: string;
  reviewerId: string | null;
  reviewerName: string | null;
  decision: "PENDING" | "CONFIRMED" | "REVOKED" | "AUTO_REVOKED";
  decidedBy: string | null;
  decidedAt: string | null;
  note: string | null;
  createdAt: string;
}

export interface ActivityRow {
  id: string;
  actorId: string | null;
  actorEmail: string | null;
  actorRole: string | null;
  impersonatorEmail: string | null;
  action: string;
  httpMethod: string | null;
  requestPath: string | null;
  objectType: string | null;
  objectId: string | null;
  source: string;
  outcome: "SUCCESS" | "DENIED" | "ERROR";
  statusCode: number | null;
  denialReason: string | null;
  correlationId: string | null;
  clientIp: string | null;
  userAgent: string | null;
  detail: Record<string, unknown>;
  occurredAt: string;
}

export interface ActivitySummary {
  total: number;
  denied: number;
  errors: number;
  distinctActors: number;
}

export interface UserTimeline {
  userId: string;
  email: string;
  displayName: string;
  crmRole: string;
  active: boolean;
  summary: ActivitySummary;
  events: ActivityRow[];
}

export interface ActivityFilter {
  actorId?: string;
  action?: string;
  objectType?: string;
  outcome?: string;
  from?: string;
  to?: string;
  limit?: number;
}

// ---------------------------------------------------------------------------
// Calls
// ---------------------------------------------------------------------------

const RBAC = "/security/rbac";
const APPROVALS = "/security/approvals";

export const rbac = {
  overview: () => request<RbacOverview>("GET", `${RBAC}/overview`),
  fieldsOf: (objectType: string) =>
    request<string[]>("GET", `${RBAC}/objects/${encodeURIComponent(objectType)}/fields`),

  // Roles
  roles: () => request<RoleNode[]>("GET", `${RBAC}/roles`),
  createRole: (body: RoleRequest) => request<RoleNode>("POST", `${RBAC}/roles`, body),
  updateRole: (id: string, body: RoleRequest) =>
    request<RoleNode>("PUT", `${RBAC}/roles/${encodeURIComponent(id)}`, body),
  deactivateRole: (id: string) =>
    request<void>("POST", `${RBAC}/roles/${encodeURIComponent(id)}/deactivate`),
  assignRole: (body: { userId: string; roleNodeId: string; expiresAt?: string | null }) =>
    request<ApprovalRequest>("POST", `${RBAC}/roles/assignments`, body),

  // Profiles, permission sets and groups
  permissions: () => request<PermissionDescriptor[]>("GET", `${RBAC}/permissions`),
  profiles: () => request<ProfileRow[]>("GET", `${RBAC}/profiles`),
  assignProfile: (body: { userId: string; profileId: string; reason?: string }) =>
    request<ApprovalRequest>("POST", `${RBAC}/profiles/assignments`, body),
  permissionSets: () => request<PermissionSetRow[]>("GET", `${RBAC}/permission-sets`),
  createPermissionSet: (body: { code: string; name: string; description?: string }) =>
    request<PermissionSetRow>("POST", `${RBAC}/permission-sets`, body),
  permissionSetGroups: () =>
    request<PermissionSetGroupRow[]>("GET", `${RBAC}/permission-set-groups`),
  createGroup: (body: { code: string; name: string; description?: string }) =>
    request<PermissionSetGroupRow>("POST", `${RBAC}/permission-set-groups`, body),
  setGroupMember: (groupId: string, body: { permissionSetId: string; mutedPermissions: string[] }) =>
    request<void>("PUT", `${RBAC}/permission-set-groups/${encodeURIComponent(groupId)}/members`, body),

  holderPermissions: (holderType: string, holderId: string) =>
    request<string[]>(
      "GET",
      `${RBAC}/holders/${encodeURIComponent(holderType)}/${encodeURIComponent(holderId)}/permissions`,
    ),
  setHolderPermissions: (holderType: string, holderId: string, permissionCodes: string[]) =>
    request<void>(
      "PUT",
      `${RBAC}/holders/${encodeURIComponent(holderType)}/${encodeURIComponent(holderId)}/permissions`,
      { permissionCodes },
    ),

  objectPermissions: (holderType?: string, holderId?: string) =>
    request<ObjectPermissionRow[]>("GET", `${RBAC}/object-permissions${qs({ holderType, holderId })}`),
  setObjectPermission: (body: ObjectPermissionRow) =>
    request<void>("PUT", `${RBAC}/object-permissions`, body),
  fieldPermissions: (holderType?: string, holderId?: string) =>
    request<FieldPermissionRow[]>("GET", `${RBAC}/field-permissions${qs({ holderType, holderId })}`),
  setFieldPermission: (body: FieldPermissionRow) =>
    request<void>("PUT", `${RBAC}/field-permissions`, body),

  assignments: (userId?: string) =>
    request<AssignmentRow[]>("GET", `${RBAC}/assignments${qs({ userId })}`),
  assign: (body: {
    userId: string;
    permissionSetId?: string | null;
    permissionSetGroupId?: string | null;
    expiresAt?: string | null;
    reason?: string;
  }) => request<ApprovalRequest>("POST", `${RBAC}/assignments`, body),
  revokeAssignment: (id: string, reason?: string) =>
    request<void>("POST", `${RBAC}/assignments/${encodeURIComponent(id)}/revoke`, { reason }),
  effectivePermissions: (userId: string) =>
    request<EffectivePermissionView>("GET", `${RBAC}/users/${encodeURIComponent(userId)}/effective-permissions`),

  // Maker-checker. Approval applies the grant in the same transaction.
  approvals: (status?: string) => request<ApprovalRequest[]>("GET", `${APPROVALS}${qs({ status })}`),
  approve: (id: string, note: string) =>
    request<ApprovalRequest>("POST", `${APPROVALS}/${encodeURIComponent(id)}/approve`, { note }),
  reject: (id: string, note: string) =>
    request<ApprovalRequest>("POST", `${APPROVALS}/${encodeURIComponent(id)}/reject`, { note }),
  approvalDelegations: () => request<ApprovalDelegation[]>("GET", `${APPROVALS}/delegations`),
  delegateApproval: (delegateId: string, expiresAt?: string | null) =>
    request<ApprovalDelegation>("POST", `${APPROVALS}/delegations`, { delegateId, expiresAt }),
  revokeApprovalDelegation: (id: string) =>
    request<void>("DELETE", `${APPROVALS}/delegations/${encodeURIComponent(id)}`),

  // Org-wide defaults
  orgWideDefaults: () => request<OrgWideDefaultRow[]>("GET", `${RBAC}/org-wide-defaults`),
  setOrgWideDefault: (body: {
    objectType: string;
    defaultAccess: string;
    roleHierarchyRollup: boolean;
  }) => request<void>("PUT", `${RBAC}/org-wide-defaults`, body),

  // Sharing
  sharingRules: () => request<SharingRule[]>("GET", `${RBAC}/sharing-rules`),
  defineSharingRule: (body: SharingRuleRequest) =>
    request<SharingRule>("POST", `${RBAC}/sharing-rules`, body),
  activateSharingRule: (id: string, active: boolean) =>
    request<SharingRule>("POST", `${RBAC}/sharing-rules/${encodeURIComponent(id)}/activation${qs({ active })}`),
  sharingJobs: (limit = 25) => request<RecomputeJob[]>("GET", `${RBAC}/sharing-jobs${qs({ limit })}`),
  drainSharingJobs: () => request<{ processed: number }>("POST", `${RBAC}/sharing-jobs/drain`),
  userGroups: () => request<NamedRow[]>("GET", `${RBAC}/user-groups`),
  territories: () => request<NamedRow[]>("GET", `${RBAC}/territories`),

  // Segregation of duties
  sodConflicts: () => request<SodConflict[]>("GET", `${RBAC}/sod/conflicts`),
  declareConflict: (body: {
    code: string;
    name: string;
    permissionA: string;
    permissionB: string;
    severity?: string;
    rationale?: string;
  }) => request<SodConflict>("POST", `${RBAC}/sod/conflicts`, body),
  retireConflict: (id: string) =>
    request<void>("POST", `${RBAC}/sod/conflicts/${encodeURIComponent(id)}/retire`),
  sodFindings: () => request<SodFinding[]>("GET", `${RBAC}/sod/findings`),
  sweepSod: () => request<{ openFindings: number }>("POST", `${RBAC}/sod/sweep`),
  resolveFinding: (id: string, status: string, note?: string) =>
    request<void>("POST", `${RBAC}/sod/findings/${encodeURIComponent(id)}/resolution`, { status, note }),

  // Access explainer (FR-SEC-013)
  explain: (userId: string, objectType: string, recordId: string) =>
    request<AccessExplanation>("GET", `${RBAC}/access-explainer${qs({ userId, objectType, recordId })}`),

  // Administrator / auditor floor
  tenantFloor: () => request<FloorReport>("GET", `${RBAC}/tenant-floor`),
  users: () => request<TenantUser[]>("GET", `${RBAC}/users`),
  setUserActive: (userId: string, active: boolean, reason?: string) =>
    request<TenantUser>("POST", `${RBAC}/users/active`, { userId, active, reason }),
  changeUserRole: (userId: string, role: string, reason?: string) =>
    request<TenantUser>("POST", `${RBAC}/users/role`, { userId, role, reason }),
  repairFloor: (userId: string, role: string, reason: string) =>
    request<TenantUser>("POST", `${RBAC}/tenant-floor/repair`, { userId, role, reason }),

  // Access recertification
  accessReviews: () => request<AccessReviewCampaign[]>("GET", `${RBAC}/access-reviews`),
  createAccessReview: (body: { code: string; name: string; scopeNote?: string; deadlineAt: string }) =>
    request<AccessReviewCampaign>("POST", `${RBAC}/access-reviews`, body),
  accessReviewItems: (campaignId: string) =>
    request<AccessReviewItem[]>("GET", `${RBAC}/access-reviews/${encodeURIComponent(campaignId)}/items`),
  decideAccessReviewItem: (itemId: string, decision: "CONFIRMED" | "REVOKED", note?: string) =>
    request<AccessReviewItem>("POST", `${RBAC}/access-reviews/items/${encodeURIComponent(itemId)}/decision`,
      { decision, note }),
};

export const activityApi = {
  events: (filter: ActivityFilter) =>
    request<ActivityRow[]>("GET", `/activity/events${qs({ ...filter })}`),
  summary: (filter: ActivityFilter) =>
    request<ActivitySummary>(
      "GET",
      `/activity/summary${qs({
        actorId: filter.actorId,
        action: filter.action,
        objectType: filter.objectType,
        outcome: filter.outcome,
        from: filter.from,
        to: filter.to,
      })}`,
    ),
  timeline: (userId: string, limit = 200) =>
    request<UserTimeline>(
      "GET",
      `/activity/users/${encodeURIComponent(userId)}/timeline${qs({ limit })}`,
    ),
  actions: () => request<string[]>("GET", "/activity/actions"),
  detailAllowlist: () =>
    request<{ detailKey: string; rationale: string }[]>("GET", "/activity/detail-allowlist"),
};

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { BoolChip, DataTable, type Column } from "../components/DataTable";
import { GridLoader, InlineLoader, PanelLoader } from "../components/Loaders";
import { RoleTree } from "../components/RoleTree";
import { useToasts } from "../components/Toasts";
import {
  rbac,
  RbacApiError,
  type AccessCause,
  type AccessReviewCampaign,
  type AccessReviewItem,
  type ApprovalDelegation,
  type ApprovalRequest,
  type AssignmentRow,
  type FieldPermissionRow,
  type FloorFinding,
  type MaterializedShare,
  type ObjectPermissionRow,
  type OrgWideDefaultRow,
  type PermissionSetRow,
  type PermissionSetGroupRow,
  type ProfileRow,
  type RecomputeJob,
  type RoleNode,
  type SharingRule,
  type SodConflict,
  type SodFinding,
  type TenantUser,
} from "../api/rbac";

/**
 * The administration surface for the RBAC engine.
 *
 * <p>The engine already existed — five additive layers, sharing rules,
 * segregation of duties, delegated administration — with no way to see or change
 * any of it outside SQL. This page is that way. Nothing here re-implements a
 * rule; every action calls the service that already owns it, so a refusal shown
 * on screen is the same refusal an API caller gets.
 *
 * <p>The tab that matters most is not the biggest one. "Access explainer"
 * answers the only question anybody actually asks of an authorization model —
 * why can this person see this record — and "Admin & auditor floor" is the
 * invariant that keeps a workspace from being locked out or left unwatched.
 */

/** Mirrors com.axiom.security.RbacAccess.READ — the gate the API actually applies. */
const RBAC_READ_ROLES = new Set([
  "SUPER_ADMIN",
  "SUPER_AUDIT",
  "TENANT_ADMIN",
  "AUDITOR",
  "OPERATIONS",
  "DATA_STEWARD",
]);

const TABS = [
  { id: "roles", label: "Roles" },
  { id: "permissions", label: "Profiles & permission sets" },
  { id: "owd", label: "Org-wide defaults" },
  { id: "sharing", label: "Sharing rules" },
  { id: "sod", label: "Segregation of duties" },
  { id: "reviews", label: "Access reviews" },
  { id: "approvals", label: "Maker-checker approvals" },
  { id: "explainer", label: "Access explainer" },
  { id: "floor", label: "Admin & auditor floor" },
] as const;

type TabId = (typeof TABS)[number]["id"];

const CRM_ROLES = [
  "TENANT_ADMIN",
  "SALES_MANAGER",
  "SALES",
  "MARKETING",
  "SERVICE",
  "OPERATIONS",
  "FINANCE",
  "DATA_STEWARD",
  "AUDITOR",
  "INTEGRATION",
];

function messageOf(error: unknown): string {
  if (error instanceof RbacApiError) return error.message;
  if (error instanceof Error) return error.message;
  return "The request failed.";
}

function DecisionNoteDialog({
  title,
  description,
  note,
  noteLabel,
  confirmLabel,
  destructive = false,
  busy,
  required = false,
  onNoteChange,
  onCancel,
  onConfirm,
}: {
  title: string;
  description: string;
  note: string;
  noteLabel: string;
  confirmLabel: string;
  destructive?: boolean;
  busy: boolean;
  required?: boolean;
  onNoteChange: (note: string) => void;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return <div className="modal-scrim" role="presentation" onMouseDown={(event) => {
    if (event.target === event.currentTarget && !busy) onCancel();
  }}>
    <section className="modal-card decision-note-dialog" role="dialog" aria-modal="true" aria-labelledby="decision-note-title">
      <header className="modal-head">
        <div><span className="eyebrow">Governed Decision</span><h2 id="decision-note-title">{title}</h2><p>{description}</p></div>
        <button type="button" className="icon-btn" aria-label="Close Decision Dialog" disabled={busy} onClick={onCancel}>×</button>
      </header>
      <label className="decision-note-field"><span>{noteLabel}</span><textarea autoFocus required={required} rows={5} value={note}
        onChange={(event) => onNoteChange(event.target.value)} /></label>
      <p className="form-note">This note becomes part of the immutable approval evidence.</p>
      <footer className="modal-actions"><button type="button" className="btn" disabled={busy} onClick={onCancel}>Cancel</button>
        <button type="button" className={`btn ${destructive ? "danger" : "primary"}`} disabled={busy || (required && !note.trim())}
          onClick={onConfirm}>{busy ? "Recording Decision..." : confirmLabel}</button></footer>
    </section>
  </div>;
}

export function RbacAdminPage() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<TabId>("roles");

  const allowed = RBAC_READ_ROLES.has(user?.role ?? "");

  const overviewQ = useQuery({ queryKey: ["rbac", "overview"], queryFn: rbac.overview, retry: 1, enabled: allowed });
  const canWrite = overviewQ.data?.canWrite ?? false;

  function invalidate(...keys: string[]) {
    keys.forEach((key) => void queryClient.invalidateQueries({ queryKey: ["rbac", key] }));
    void queryClient.invalidateQueries({ queryKey: ["rbac", "overview"] });
  }

  if (!allowed) {
    return (
      <>
        <div className="page-head">
          <div>
            <span className="eyebrow">Security</span>
            <h1>Authorization</h1>
          </div>
        </div>
        <p className="form-error">
          Viewing the authorization model requires an administrator, auditor, operations or data
          steward role. Your role is {user?.role ?? "unknown"}.
        </p>
      </>
    );
  }

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">Security</span>
          <h1>Authorization</h1>
          <p>
            Roles, profiles, permission sets, sharing, segregation of duties and the access
            explainer — the whole model, over the engine that enforces it.
          </p>
        </div>
        {overviewQ.isSuccess && (
          <span className="count">{canWrite ? "Read / write" : "Read only"}</span>
        )}
      </div>

      <div className="master-tabs" role="tablist" aria-label="Authorization sections">
        {TABS.map((entry) => (
          <button
            key={entry.id}
            type="button"
            className="master-tab"
            role="tab"
            aria-selected={tab === entry.id}
            onClick={() => setTab(entry.id)}
          >
            <span className="master-tab-label">{entry.label}</span>
          </button>
        ))}
      </div>

      {overviewQ.isLoading && <PanelLoader label="Resolving the authorization model" />}
      {overviewQ.isError && <p className="form-error">{messageOf(overviewQ.error)}</p>}

      {overviewQ.isSuccess && (
        <>
          {tab === "roles" && <RolesTab canWrite={canWrite} onChanged={() => invalidate("roles")} />}
          {tab === "permissions" && (
            <PermissionsTab canWrite={canWrite} onChanged={() => invalidate("profiles", "permission-sets", "assignments")} />
          )}
          {tab === "owd" && <OrgWideDefaultsTab canWrite={canWrite} onChanged={() => invalidate("owd")} />}
          {tab === "sharing" && <SharingTab canWrite={canWrite} onChanged={() => invalidate("sharing")} />}
          {tab === "sod" && <SodTab canWrite={canWrite} onChanged={() => invalidate("sod")} />}
          {tab === "reviews" && <AccessReviewsTab canWrite={canWrite} onChanged={() => invalidate("reviews")} />}
          {tab === "approvals" && <ApprovalsTab canWrite={canWrite} users={overviewQ.data.users}
            profiles={overviewQ.data.profiles} permissionSets={overviewQ.data.permissionSets}
            onChanged={() => invalidate("approvals", "assignments", "profiles", "permission-sets")} />}
          {tab === "explainer" && <ExplainerTab users={overviewQ.data.users} objects={overviewQ.data.objects} />}
          {tab === "floor" && (
            <FloorTab canWrite={canWrite} users={overviewQ.data.users} onChanged={() => invalidate("floor", "users")} />
          )}
        </>
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Maker-checker approvals and effective permission evidence
// ---------------------------------------------------------------------------

function ApprovalsTab({ canWrite, users, profiles, permissionSets, onChanged }: {
  canWrite: boolean;
  users: TenantUser[];
  profiles: ProfileRow[];
  permissionSets: PermissionSetRow[];
  onChanged: () => void;
}) {
  const toasts = useToasts();
  const [status, setStatus] = useState("PENDING");
  const [selectedUser, setSelectedUser] = useState(users[0]?.id ?? "");
  const [draft, setDraft] = useState({ userId: users[0]?.id ?? "", kind: "PROFILE", grantId: "", expiresAt: "" });
  const [delegateId, setDelegateId] = useState("");
  const [delegateExpiry, setDelegateExpiry] = useState("");
  const [approvalDecision, setApprovalDecision] = useState<{
    request: ApprovalRequest;
    decision: "APPROVE" | "REJECT";
    note: string;
  } | null>(null);

  const approvalsQ = useQuery({ queryKey: ["rbac", "approvals", status], queryFn: () => rbac.approvals(status || undefined), retry: 1 });
  const groupsQ = useQuery({ queryKey: ["rbac", "permission-set-groups"], queryFn: rbac.permissionSetGroups, retry: 1 });
  const delegationsQ = useQuery({ queryKey: ["rbac", "approval-delegations"], queryFn: rbac.approvalDelegations, retry: 1 });
  const effectiveQ = useQuery({
    queryKey: ["rbac", "effective-permissions", selectedUser],
    queryFn: () => rbac.effectivePermissions(selectedUser),
    enabled: !!selectedUser,
    retry: 1,
  });

  const grantOptions = draft.kind === "PROFILE"
    ? profiles.map((row) => ({ id: row.id, label: `${row.code} — ${row.name}` }))
    : draft.kind === "SET"
      ? permissionSets.map((row) => ({ id: row.id, label: `${row.code} — ${row.name}` }))
      : (groupsQ.data ?? []).map((row: PermissionSetGroupRow) => ({ id: row.id, label: `${row.code} — ${row.name}` }));

  const submit = useMutation({
    mutationFn: async () => {
      if (!draft.userId || !draft.grantId) throw new Error("Choose a user and the access to request.");
      const expiresAt = draft.expiresAt ? new Date(`${draft.expiresAt}T23:59:59`).toISOString() : null;
      if (draft.kind === "PROFILE") {
        return rbac.assignProfile({ userId: draft.userId, profileId: draft.grantId, reason: "Submitted from maker-checker workspace" });
      }
      return rbac.assign({
        userId: draft.userId,
        permissionSetId: draft.kind === "SET" ? draft.grantId : null,
        permissionSetGroupId: draft.kind === "GROUP" ? draft.grantId : null,
        expiresAt,
        reason: "Submitted from maker-checker workspace",
      });
    },
    onSuccess: (request) => {
      toasts.push("info", "Approval Request Submitted", `${request.summary}. A different authorized administrator must approve it.`);
      void approvalsQ.refetch();
      onChanged();
    },
    onError: (error) => toasts.push("error", "Request Not Submitted", messageOf(error)),
  });

  const decide = useMutation({
    mutationFn: async ({ request, decision, note }: { request: ApprovalRequest; decision: "APPROVE" | "REJECT"; note: string }) => {
      if (!note.trim()) throw new Error("A decision note is required.");
      return decision === "APPROVE" ? rbac.approve(request.id, note.trim()) : rbac.reject(request.id, note.trim());
    },
    onSuccess: (request) => {
      setApprovalDecision(null);
      toasts.push("info", `Request ${request.status === "APPROVED" ? "Approved" : "Rejected"}`,
        request.status === "APPROVED" ? "The RBAC grant is now effective and audited." : "No access was changed.");
      void approvalsQ.refetch();
      if (selectedUser) void effectiveQ.refetch();
      onChanged();
    },
    onError: (error) => toasts.push("error", "Decision Refused", messageOf(error)),
  });

  const delegate = useMutation({
    mutationFn: () => rbac.delegateApproval(delegateId,
      delegateExpiry ? new Date(`${delegateExpiry}T23:59:59`).toISOString() : null),
    onSuccess: () => {
      toasts.push("info", "Approval Authority Delegated", "The delegation is active, but cannot be used to bypass maker-checker separation.");
      setDelegateId(""); setDelegateExpiry(""); void delegationsQ.refetch();
    },
    onError: (error) => toasts.push("error", "Delegation Not Created", messageOf(error)),
  });
  const revokeDelegation = useMutation({
    mutationFn: rbac.revokeApprovalDelegation,
    onSuccess: () => { toasts.push("info", "Delegation Revoked", "Approval authority returned to the delegator."); void delegationsQ.refetch(); },
    onError: (error) => toasts.push("error", "Delegation Not Revoked", messageOf(error)),
  });

  const approvalColumns: Column<ApprovalRequest>[] = [
    { key: "status", header: "Status", value: (r) => r.status, filter: "enum", render: (r) => <span className={`chip chip-${r.status.toLowerCase()}`}>{r.status}</span> },
    { key: "summary", header: "Requested Change", value: (r) => r.summary },
    { key: "initiator", header: "Requested By", value: (r) => r.initiatedByEmail, filter: "enum" },
    { key: "initiatedAt", header: "Requested At", value: (r) => new Date(r.initiatedAt).toLocaleString() },
    { key: "decider", header: "Decided By", value: (r) => r.decidedByEmail ?? "", blank: "Pending", filter: "enum" },
    { key: "note", header: "Decision Note", value: (r) => r.decisionNote ?? "", blank: "Pending" },
  ];
  const delegationColumns: Column<ApprovalDelegation>[] = [
    { key: "delegator", header: "Delegator", value: (r) => r.delegatorEmail, filter: "enum" },
    { key: "delegate", header: "Delegate", value: (r) => r.delegateEmail, filter: "enum" },
    { key: "starts", header: "Starts", value: (r) => new Date(r.startsAt).toLocaleString() },
    { key: "expires", header: "Expires", value: (r) => r.expiresAt ? new Date(r.expiresAt).toLocaleString() : "", blank: "Never" },
    { key: "active", header: "Active", value: (r) => r.active, filter: "boolean", render: (r) => <BoolChip value={r.active} /> },
  ];

  type EffectiveObjectRow = { object: string; create: boolean; read: boolean; edit: boolean; delete: boolean; export: boolean; modifyAll: boolean };
  const effectiveObjects: EffectiveObjectRow[] = effectiveQ.data
    ? Object.entries(effectiveQ.data.objectAccess).map(([object, access]) => ({
        object,
        create: !!access.canCreate,
        read: !!access.canRead,
        edit: !!access.canEdit,
        delete: !!access.canDelete,
        export: !!access.canExport,
        modifyAll: !!access.modifyAll,
      }))
    : [];
  const effectiveColumns: Column<EffectiveObjectRow>[] = [
    { key: "object", header: "Object", value: (r) => r.object, filter: "enum" },
    ...(["create", "read", "edit", "delete", "export", "modifyAll"] as const).map((key): Column<EffectiveObjectRow> => ({
      key, header: key === "modifyAll" ? "Modify All" : key[0].toUpperCase() + key.slice(1), value: (r) => r[key],
      filter: "boolean", render: (r) => <BoolChip value={r[key]} />,
    })),
  ];

  return <>
    <div className="page-head compact-head"><div><span className="eyebrow">Four-Eyes Governance</span>
      <h2>Maker-Checker Approval Queue</h2><p>Submitting a grant never changes access. A different authorized administrator must approve it, and delegation cannot bypass that separation.</p></div></div>

    {canWrite && <section className="list-controls" aria-label="Submit permission grant for approval">
      <label><span>User</span><select value={draft.userId} onChange={(e) => setDraft((v) => ({ ...v, userId: e.target.value }))}>
        <option value="">Select User</option>{users.filter((u) => u.active).map((u) => <option key={u.id} value={u.id}>{u.displayName} — {u.email}</option>)}
      </select></label>
      <label><span>Grant Type</span><select value={draft.kind} onChange={(e) => setDraft((v) => ({ ...v, kind: e.target.value, grantId: "" }))}>
        <option value="PROFILE">Profile</option><option value="SET">Permission Set</option><option value="GROUP">Permission Set Group</option>
      </select></label>
      <label><span>Requested Access</span><select value={draft.grantId} onChange={(e) => setDraft((v) => ({ ...v, grantId: e.target.value }))}>
        <option value="">Select Access</option>{grantOptions.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
      </select></label>
      <label><span>Expires (Optional)</span><input type="date" value={draft.expiresAt} onChange={(e) => setDraft((v) => ({ ...v, expiresAt: e.target.value }))} /></label>
      <button type="button" className="btn btn-primary btn-sm" disabled={submit.isPending || !draft.userId || !draft.grantId}
        onClick={() => submit.mutate()}>{submit.isPending ? "Submitting..." : "Submit For Approval"}</button>
    </section>}

    <div className="page-controls"><label><span>Status</span><select value={status} onChange={(e) => setStatus(e.target.value)}>
      <option value="PENDING">Pending</option><option value="APPROVED">Approved</option><option value="REJECTED">Rejected</option><option value="">All</option>
    </select></label></div>
    {approvalsQ.isLoading && <GridLoader label="Reading approval requests" rows={5} columns={6} />}
    {approvalsQ.isError && <p className="form-error">{messageOf(approvalsQ.error)}</p>}
    {approvalsQ.isSuccess && <DataTable name="Controlled approvals" columns={approvalColumns} rows={approvalsQ.data} rowKey={(r) => r.id}
      actions={canWrite ? (request) => request.status !== "PENDING" ? null : <span className="inline-actions">
        <button className="link-btn" disabled={decide.isPending} onClick={() => setApprovalDecision({ request, decision: "APPROVE", note: "Reviewed against the user's current duties" })}>Approve & Apply</button>
        <button className="link-btn danger-link" disabled={decide.isPending} onClick={() => setApprovalDecision({ request, decision: "REJECT", note: "Access is not required" })}>Reject</button>
      </span> : undefined}
      note="Approval and RBAC application commit together. If the grant fails policy, the request stays pending." />}

    <h2 className="eyebrow" style={{ marginTop: 20 }}>Effective Permissions By User</h2>
    <div className="page-controls"><label><span>User</span><select value={selectedUser} onChange={(e) => setSelectedUser(e.target.value)}>
      <option value="">Select User</option>{users.map((u) => <option key={u.id} value={u.id}>{u.displayName} — {u.email}</option>)}
    </select></label>{effectiveQ.data && <span className="count">Profile {effectiveQ.data.profileCode ?? "None"} · Role {effectiveQ.data.roleCode ?? "None"} · {effectiveQ.data.permissionCodes.length} permission codes</span>}</div>
    {effectiveQ.isLoading && <GridLoader label="Calculating effective permissions" rows={6} columns={7} />}
    {effectiveQ.isError && <p className="form-error">{messageOf(effectiveQ.error)}</p>}
    {effectiveQ.isSuccess && <DataTable name="Effective object permissions" columns={effectiveColumns} rows={effectiveObjects} rowKey={(r) => r.object}
      note={`Effective access is profile plus ${effectiveQ.data.permissionSets.length} assigned set(s), minus explicit mutes. Export ceiling: ${effectiveQ.data.exportRowLimit ?? "unlimited"}.`} />}

    <h2 className="eyebrow" style={{ marginTop: 20 }}>Approval Delegations</h2>
    {canWrite && <section className="list-controls" aria-label="Delegate approval authority"><label><span>Delegate To</span><select value={delegateId} onChange={(e) => setDelegateId(e.target.value)}>
      <option value="">Select User</option>{users.filter((u) => u.active).map((u) => <option key={u.id} value={u.id}>{u.displayName} — {u.email}</option>)}
    </select></label><label><span>Expires (Optional)</span><input type="date" value={delegateExpiry} onChange={(e) => setDelegateExpiry(e.target.value)} /></label>
      <button className="btn btn-sm" disabled={!delegateId || delegate.isPending} onClick={() => delegate.mutate()}>Delegate Authority</button></section>}
    {delegationsQ.isLoading && <GridLoader label="Reading approval delegations" rows={3} columns={5} />}
    {delegationsQ.isSuccess && <DataTable name="Approval delegations" columns={delegationColumns} rows={delegationsQ.data} rowKey={(r) => r.id}
      actions={canWrite ? (row) => row.active ? <button className="link-btn danger-link" disabled={revokeDelegation.isPending} onClick={() => revokeDelegation.mutate(row.id)}>Revoke</button> : null : undefined} />}
    {approvalDecision && <DecisionNoteDialog
      title={approvalDecision.decision === "APPROVE" ? "Approve And Apply Access" : "Reject Access Request"}
      description={approvalDecision.request.summary}
      note={approvalDecision.note}
      noteLabel={approvalDecision.decision === "APPROVE" ? "Approval Evidence" : "Rejection Reason"}
      confirmLabel={approvalDecision.decision === "APPROVE" ? "Approve And Apply" : "Reject Request"}
      destructive={approvalDecision.decision === "REJECT"}
      busy={decide.isPending}
      required
      onNoteChange={(note) => setApprovalDecision((current) => current ? { ...current, note } : current)}
      onCancel={() => setApprovalDecision(null)}
      onConfirm={() => decide.mutate(approvalDecision)} />}
  </>;
}

// ---------------------------------------------------------------------------
// Access recertification
// ---------------------------------------------------------------------------

function AccessReviewsTab({ canWrite, onChanged }: { canWrite: boolean; onChanged: () => void }) {
  const toasts = useToasts();
  const [selected, setSelected] = useState<string | null>(null);
  const [reviewDecision, setReviewDecision] = useState<{
    item: AccessReviewItem;
    decision: "CONFIRMED" | "REVOKED";
    note: string;
  } | null>(null);
  const [draft, setDraft] = useState(() => ({
    code: `ACCESS_${new Date().getFullYear()}`,
    name: "Quarterly access review",
    scopeNote: "Roles, permission bundles, manual record shares and delegated administration",
    deadline: new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10),
  }));
  const campaignsQ = useQuery({ queryKey: ["rbac", "reviews"], queryFn: rbac.accessReviews, retry: 1 });
  const itemsQ = useQuery({
    queryKey: ["rbac", "reviews", selected, "items"],
    queryFn: () => rbac.accessReviewItems(selected as string),
    enabled: !!selected,
    retry: 1,
  });
  const create = useMutation({
    mutationFn: () => rbac.createAccessReview({
      code: draft.code,
      name: draft.name,
      scopeNote: draft.scopeNote,
      deadlineAt: new Date(`${draft.deadline}T23:59:59`).toISOString(),
    }),
    onSuccess: (campaign) => {
      setSelected(campaign.id);
      toasts.push("info", "Access review opened", `${campaign.totalItems} live grants were snapshotted for review.`);
      void campaignsQ.refetch();
      onChanged();
    },
    onError: (error) => toasts.push("error", "Review not created", messageOf(error)),
  });
  const decide = useMutation({
    mutationFn: ({ item, decision, note }: { item: AccessReviewItem; decision: "CONFIRMED" | "REVOKED"; note: string }) => {
      return rbac.decideAccessReviewItem(item.id, decision, note);
    },
    onSuccess: () => {
      setReviewDecision(null);
      toasts.push("info", "Access decision recorded", "The decision is immutable and any revocation is already effective.");
      void itemsQ.refetch();
      void campaignsQ.refetch();
      onChanged();
    },
    onError: (error) => {
      if (messageOf(error) !== "Decision cancelled.") toasts.push("error", "Decision refused", messageOf(error));
    },
  });

  const campaignColumns: Column<AccessReviewCampaign>[] = [
    { key: "code", header: "Code", value: (r) => r.code, cellClass: "mono" },
    { key: "name", header: "Review", value: (r) => r.name },
    { key: "status", header: "Status", value: (r) => r.overdue ? "OVERDUE" : r.status, filter: "enum" },
    { key: "deadline", header: "Deadline", value: (r) => new Date(r.deadlineAt).toLocaleDateString() },
    { key: "pending", header: "Pending", value: (r) => r.pendingItems, cellClass: "num" },
    { key: "confirmed", header: "Confirmed", value: (r) => r.confirmedItems, cellClass: "num" },
    { key: "revoked", header: "Revoked", value: (r) => r.revokedItems, cellClass: "num" },
    {
      key: "review",
      header: "Action",
      value: () => "Review",
      render: (r) => <button type="button" className="link-btn" onClick={() => setSelected(r.id)}>Review grants</button>,
    },
  ];
  const itemColumns: Column<AccessReviewItem>[] = [
    { key: "subject", header: "User", value: (r) => r.subjectEmail, filter: "enum" },
    { key: "grantType", header: "Grant type", value: (r) => r.grantType.replace(/_/g, " "), filter: "enum" },
    { key: "description", header: "Access being reviewed", value: (r) => r.description },
    { key: "decision", header: "Decision", value: (r) => r.decision, filter: "enum" },
    { key: "note", header: "Note", value: (r) => r.note ?? "", blank: "not decided" },
    {
      key: "action",
      header: "Action",
      value: (r) => r.decision,
      render: (r) => r.decision !== "PENDING" || !canWrite ? <span>{r.decision}</span> : <span className="inline-actions">
        <button type="button" className="link-btn" disabled={decide.isPending}
          onClick={() => setReviewDecision({ item: r, decision: "CONFIRMED", note: "Still required for current duties" })}>Confirm</button>
        <button type="button" className="link-btn danger-link" disabled={decide.isPending}
          onClick={() => setReviewDecision({ item: r, decision: "REVOKED", note: "No longer required" })}>Revoke</button>
      </span>,
    },
  ];

  return <>
    <div className="page-head compact-head">
      <div><span className="eyebrow">Access recertification</span><h2>Review who still needs access</h2>
        <p>A campaign snapshots current grants. Confirm what is still required or revoke it immediately.</p></div>
    </div>
    {canWrite && <section className="list-controls" aria-label="Create an access review">
      <label><span>Review code</span><input value={draft.code} onChange={(e) => setDraft((v) => ({ ...v, code: e.target.value.toUpperCase() }))} /></label>
      <label><span>Review name</span><input value={draft.name} onChange={(e) => setDraft((v) => ({ ...v, name: e.target.value }))} /></label>
      <label><span>Deadline</span><input type="date" value={draft.deadline} onChange={(e) => setDraft((v) => ({ ...v, deadline: e.target.value }))} /></label>
      <button type="button" className="btn btn-primary btn-sm" disabled={create.isPending || !draft.code || !draft.name || !draft.deadline}
        onClick={() => create.mutate()}>{create.isPending ? "Opening review..." : "Open review"}</button>
    </section>}
    {campaignsQ.isLoading && <GridLoader label="Reading access reviews" rows={4} columns={7} />}
    {campaignsQ.isError && <p className="form-error">{messageOf(campaignsQ.error)}</p>}
    {campaignsQ.isSuccess && <DataTable name="Access review campaigns" columns={campaignColumns}
      rows={campaignsQ.data} rowKey={(r) => r.id}
      note="Overdue campaigns remain open until every captured grant has a decision." />}
    {selected && <>
      <h2 className="eyebrow" style={{ marginTop: 20 }}>Captured grants</h2>
      {itemsQ.isLoading && <GridLoader label="Reading captured grants" rows={6} columns={6} />}
      {itemsQ.isError && <p className="form-error">{messageOf(itemsQ.error)}</p>}
      {itemsQ.isSuccess && <DataTable name="Access review items" columns={itemColumns}
        rows={itemsQ.data} rowKey={(r) => r.id}
        note="A revoked decision updates the authoritative grant in the same database transaction." />}
    </>}
    {reviewDecision && <DecisionNoteDialog
      title={reviewDecision.decision === "REVOKED" ? "Revoke Reviewed Access" : "Confirm Reviewed Access"}
      description={reviewDecision.item.description}
      note={reviewDecision.note}
      noteLabel={reviewDecision.decision === "REVOKED" ? "Revocation Reason" : "Review Note"}
      confirmLabel={reviewDecision.decision === "REVOKED" ? "Revoke Access" : "Confirm Access"}
      destructive={reviewDecision.decision === "REVOKED"}
      busy={decide.isPending}
      required={reviewDecision.decision === "REVOKED"}
      onNoteChange={(note) => setReviewDecision((current) => current ? { ...current, note } : current)}
      onCancel={() => setReviewDecision(null)}
      onConfirm={() => decide.mutate(reviewDecision)} />}
  </>;
}

// ---------------------------------------------------------------------------
// Roles
// ---------------------------------------------------------------------------

function RolesTab({ canWrite, onChanged }: { canWrite: boolean; onChanged: () => void }) {
  const toasts = useToasts();
  const [errors, setErrors] = useState<Record<string, string>>({});
  const rolesQ = useQuery({ queryKey: ["rbac", "roles"], queryFn: rbac.roles, retry: 1 });

  /**
   * A rejected cycle names both roles; pinning it to the node keeps the message
   * next to the control that produced it (FR-SEC-001's on-failure clause is
   * about the administrator understanding what collided).
   */
  function fail(roleId: string, error: unknown) {
    setErrors((current) => ({ ...current, [roleId]: messageOf(error) }));
  }

  const create = useMutation({
    mutationFn: (input: { parent: RoleNode | null; code: string; name: string }) =>
      rbac.createRole({ code: input.code, name: input.name, parentId: input.parent?.id ?? null }),
    onSuccess: () => {
      toasts.push("info", "Role created", "The hierarchy was updated.");
      onChanged();
      void rolesQ.refetch();
    },
    onError: (error, input) => fail(input.parent?.id ?? "__root__", error),
  });

  const rename = useMutation({
    mutationFn: (input: { role: RoleNode; name: string }) =>
      rbac.updateRole(input.role.id, {
        code: input.role.code,
        name: input.name,
        description: input.role.description,
        parentId: input.role.parentId,
      }),
    onSuccess: () => {
      onChanged();
      void rolesQ.refetch();
    },
    onError: (error, input) => fail(input.role.id, error),
  });

  const reparent = useMutation({
    mutationFn: (input: { role: RoleNode; parentId: string | null }) =>
      rbac.updateRole(input.role.id, {
        code: input.role.code,
        name: input.role.name,
        description: input.role.description,
        parentId: input.parentId,
      }),
    onSuccess: (_data, input) => {
      setErrors((current) => {
        const next = { ...current };
        delete next[input.role.id];
        return next;
      });
      onChanged();
      void rolesQ.refetch();
    },
    onError: (error, input) => fail(input.role.id, error),
  });

  const remove = useMutation({
    mutationFn: (role: RoleNode) => rbac.deactivateRole(role.id),
    onSuccess: () => {
      onChanged();
      void rolesQ.refetch();
    },
    onError: (error, role) => fail(role.id, error),
  });

  const busy = create.isPending || rename.isPending || reparent.isPending || remove.isPending;
  const roles = rolesQ.data ?? [];

  const columns: Column<RoleNode>[] = [
    { key: "code", header: "Code", value: (r) => r.code, cellClass: "mono" },
    { key: "name", header: "Name", value: (r) => r.name },
    { key: "parentCode", header: "Reports to", value: (r) => r.parentCode ?? "", blank: "(top level)", filter: "enum" },
    { key: "depth", header: "Depth", value: (r) => r.depth, cellClass: "num", filter: "enum" },
    { key: "path", header: "Path", value: (r) => r.path, cellClass: "mono" },
    { key: "memberCount", header: "Members", value: (r) => r.memberCount, cellClass: "num" },
    {
      key: "active",
      header: "Active",
      value: (r) => r.active,
      render: (r) => <BoolChip value={r.active} />,
      filter: "boolean",
    },
  ];

  return (
    <>
      {rolesQ.isLoading && <GridLoader label="Reading the role hierarchy" rows={5} columns={6} />}
      {rolesQ.isError && <p className="form-error">{messageOf(rolesQ.error)}</p>}
      {rolesQ.isSuccess && (
        <>
          <RoleTree
            roles={roles}
            canWrite={canWrite}
            busy={busy}
            errors={errors}
            onCreateChild={(parent, code, name) => create.mutate({ parent, code, name })}
            onRename={(role, name) => rename.mutate({ role, name })}
            onReparent={(role, parentId) => reparent.mutate({ role, parentId })}
            onDelete={(role) => remove.mutate(role)}
            onDismissError={(roleId) =>
              setErrors((current) => {
                const next = { ...current };
                delete next[roleId];
                return next;
              })
            }
          />
          {errors.__root__ && <p className="form-error">{errors.__root__}</p>}
          {busy && <InlineLoader label="Saving the hierarchy" />}

          <h2 className="eyebrow" style={{ marginTop: 20 }}>
            Roles as a table
          </h2>
          <DataTable
            name="Roles"
            columns={columns}
            rows={roles}
            rowKey={(r) => r.id}
            empty="No roles are defined for this workspace."
            note="Access rolls upward through this hierarchy. Siblings never see each other's records."
          />
        </>
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Profiles, permission sets, object and field permissions
// ---------------------------------------------------------------------------

function PermissionsTab({ canWrite, onChanged }: { canWrite: boolean; onChanged: () => void }) {
  const toasts = useToasts();
  const [holder, setHolder] = useState<{ type: string; id: string; label: string } | null>(null);

  const profilesQ = useQuery({ queryKey: ["rbac", "profiles"], queryFn: rbac.profiles, retry: 1 });
  const setsQ = useQuery({ queryKey: ["rbac", "permission-sets"], queryFn: rbac.permissionSets, retry: 1 });
  const groupsQ = useQuery({ queryKey: ["rbac", "permission-set-groups"], queryFn: rbac.permissionSetGroups, retry: 1 });
  const assignmentsQ = useQuery({ queryKey: ["rbac", "assignments"], queryFn: () => rbac.assignments(), retry: 1 });
  const objectPermsQ = useQuery({
    queryKey: ["rbac", "object-permissions", holder?.type, holder?.id],
    queryFn: () => rbac.objectPermissions(holder?.type, holder?.id),
    retry: 1,
  });
  const fieldPermsQ = useQuery({
    queryKey: ["rbac", "field-permissions", holder?.type, holder?.id],
    queryFn: () => rbac.fieldPermissions(holder?.type, holder?.id),
    retry: 1,
  });

  const setObjectPerm = useMutation({
    mutationFn: (row: ObjectPermissionRow) => rbac.setObjectPermission(row),
    onSuccess: () => {
      void objectPermsQ.refetch();
      onChanged();
    },
    onError: (error) => toasts.push("error", "Permission not changed", messageOf(error)),
  });

  const setFieldPerm = useMutation({
    mutationFn: (row: FieldPermissionRow) => rbac.setFieldPermission(row),
    onSuccess: () => {
      void fieldPermsQ.refetch();
      onChanged();
    },
    onError: (error) => toasts.push("error", "Field permission not changed", messageOf(error)),
  });

  const revoke = useMutation({
    mutationFn: (id: string) => rbac.revokeAssignment(id, "Revoked from the authorization screen"),
    onSuccess: () => {
      void assignmentsQ.refetch();
      onChanged();
    },
    onError: (error) => toasts.push("error", "Assignment not revoked", messageOf(error)),
  });

  const profileColumns: Column<ProfileRow>[] = [
    { key: "code", header: "Code", value: (p) => p.code, cellClass: "mono" },
    { key: "name", header: "Name", value: (p) => p.name },
    { key: "exportRowLimit", header: "Export ceiling", value: (p) => p.exportRowLimit ?? "", blank: "unlimited", cellClass: "num" },
    { key: "userCount", header: "Users", value: (p) => p.userCount, cellClass: "num" },
    { key: "systemManaged", header: "System", value: (p) => p.systemManaged, render: (p) => <BoolChip value={p.systemManaged} />, filter: "boolean" },
    { key: "active", header: "Active", value: (p) => p.active, render: (p) => <BoolChip value={p.active} />, filter: "boolean" },
  ];

  const setColumns: Column<PermissionSetRow>[] = [
    { key: "code", header: "Code", value: (s) => s.code, cellClass: "mono" },
    { key: "name", header: "Name", value: (s) => s.name },
    { key: "assignmentCount", header: "Assignments", value: (s) => s.assignmentCount, cellClass: "num" },
    { key: "active", header: "Active", value: (s) => s.active, render: (s) => <BoolChip value={s.active} />, filter: "boolean" },
  ];

  interface MemberRow {
    id: string;
    groupCode: string;
    setCode: string;
    muted: string;
    mutedCount: number;
  }

  const memberRows: MemberRow[] = (groupsQ.data ?? []).flatMap((group) =>
    group.members.map((member) => ({
      id: `${group.id}:${member.permissionSetId}`,
      groupCode: group.code,
      setCode: member.permissionSetCode,
      muted: member.mutedPermissions.join(", "),
      mutedCount: member.mutedPermissions.length,
    })),
  );

  const memberColumns: Column<MemberRow>[] = [
    { key: "groupCode", header: "Group", value: (m) => m.groupCode, filter: "enum", cellClass: "mono" },
    { key: "setCode", header: "Permission set", value: (m) => m.setCode, filter: "enum", cellClass: "mono" },
    { key: "mutedCount", header: "Mutes", value: (m) => m.mutedCount, cellClass: "num" },
    { key: "muted", header: "Muted permissions", value: (m) => m.muted, blank: "none" },
  ];

  const objectColumns: Column<ObjectPermissionRow>[] = [
    { key: "holderType", header: "Holder type", value: (r) => r.holderType, filter: "enum" },
    { key: "objectType", header: "Object", value: (r) => r.objectType, filter: "enum" },
    ...(["canCreate", "canRead", "canEdit", "canDelete", "viewAll", "modifyAll", "canExport", "canReveal"] as const).map(
      (flag): Column<ObjectPermissionRow> => ({
        key: flag,
        header: flag
          .replace(/^can/, "")
          .replace(/([A-Z])/g, " $1")
          .trim(),
        value: (r) => r[flag],
        filter: "boolean",
        render: (r) =>
          canWrite ? (
            <button
              type="button"
              className="link-btn"
              disabled={setObjectPerm.isPending}
              onClick={() => setObjectPerm.mutate({ ...r, [flag]: !r[flag] })}
            >
              {r[flag] ? "Yes" : "No"}
            </button>
          ) : (
            <BoolChip value={r[flag]} />
          ),
      }),
    ),
  ];

  const fieldColumns: Column<FieldPermissionRow>[] = [
    { key: "holderType", header: "Holder type", value: (r) => r.holderType, filter: "enum" },
    { key: "objectType", header: "Object", value: (r) => r.objectType, filter: "enum" },
    { key: "fieldName", header: "Field", value: (r) => r.fieldName, cellClass: "mono" },
    {
      key: "readable",
      header: "Readable",
      value: (r) => r.readable,
      filter: "boolean",
      render: (r) =>
        canWrite ? (
          <button
            type="button"
            className="link-btn"
            disabled={setFieldPerm.isPending}
            onClick={() => setFieldPerm.mutate({ ...r, readable: !r.readable, editable: r.readable ? false : r.editable })}
          >
            {r.readable ? "Yes" : "No"}
          </button>
        ) : (
          <BoolChip value={r.readable} />
        ),
    },
    {
      key: "editable",
      header: "Editable",
      value: (r) => r.editable,
      filter: "boolean",
      render: (r) =>
        canWrite ? (
          <button
            type="button"
            className="link-btn"
            disabled={setFieldPerm.isPending}
            onClick={() => setFieldPerm.mutate({ ...r, editable: !r.editable, readable: r.editable ? r.readable : true })}
          >
            {r.editable ? "Yes" : "No"}
          </button>
        ) : (
          <BoolChip value={r.editable} />
        ),
    },
  ];

  const assignmentColumns: Column<AssignmentRow>[] = [
    { key: "userEmail", header: "User", value: (a) => a.userEmail, filter: "enum" },
    { key: "grant", header: "Grant", value: (a) => a.permissionSetCode ?? a.groupCode ?? "", cellClass: "mono", filter: "enum" },
    { key: "kind", header: "Kind", value: (a) => (a.permissionSetCode ? "Permission set" : "Group"), filter: "enum" },
    { key: "expiresAt", header: "Expires", value: (a) => a.expiresAt ?? "", blank: "never" },
    { key: "grantedAt", header: "Granted", value: (a) => a.grantedAt },
    { key: "grantedByEmail", header: "Granted by", value: (a) => a.grantedByEmail ?? "", filter: "enum" },
    {
      key: "revokedAt",
      header: "Live",
      value: (a) => a.revokedAt === null,
      render: (a) => <BoolChip value={a.revokedAt === null} yes="Live" no="Revoked" />,
      filter: "boolean",
    },
  ];

  const holderOptions = useMemo(
    () => [
      ...(profilesQ.data ?? []).map((p) => ({ type: "PROFILE", id: p.id, label: `Profile ${p.code}` })),
      ...(setsQ.data ?? []).map((s) => ({ type: "PERMISSION_SET", id: s.id, label: `Set ${s.code}` })),
    ],
    [profilesQ.data, setsQ.data],
  );

  return (
    <>
      <h2 className="eyebrow">Profiles — exactly one per user</h2>
      {profilesQ.isLoading ? (
        <GridLoader label="Reading profiles" rows={4} columns={6} />
      ) : (
        <DataTable
          name="Profiles"
          columns={profileColumns}
          rows={profilesQ.data ?? []}
          rowKey={(p) => p.id}
          note="A profile answers what a user can do. A role answers whose records they can see. Kept separate on purpose."
        />
      )}

      <h2 className="eyebrow" style={{ marginTop: 20 }}>
        Permission sets — additive, many per user
      </h2>
      {setsQ.isLoading ? (
        <GridLoader label="Reading permission sets" rows={3} columns={4} />
      ) : (
        <DataTable name="Permission sets" columns={setColumns} rows={setsQ.data ?? []} rowKey={(s) => s.id} />
      )}

      <h2 className="eyebrow" style={{ marginTop: 20 }}>
        Permission set group members and their mutes
      </h2>
      {groupsQ.isLoading ? (
        <GridLoader label="Reading permission set groups" rows={3} columns={4} />
      ) : (
        <DataTable
          name="Permission set group members"
          columns={memberColumns}
          rows={memberRows}
          rowKey={(m) => m.id}
          initialGroupBy="groupCode"
          empty="No permission set groups are defined."
          note="Effective permission is profile ∪ assigned sets − explicit mutes. Union, never intersection."
        />
      )}

      <h2 className="eyebrow" style={{ marginTop: 20 }}>
        Object and field permissions
      </h2>
      <div className="page-controls">
        <div>
          <label className="label" htmlFor="holder-filter">
            Holder
          </label>
          <select
            id="holder-filter"
            value={holder ? `${holder.type}:${holder.id}` : ""}
            onChange={(event) => {
              const raw = event.target.value;
              if (!raw) {
                setHolder(null);
                return;
              }
              const match = holderOptions.find((option) => `${option.type}:${option.id}` === raw);
              setHolder(match ?? null);
            }}
          >
            <option value="">Every holder</option>
            {holderOptions.map((option) => (
              <option key={`${option.type}:${option.id}`} value={`${option.type}:${option.id}`}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      {objectPermsQ.isLoading ? (
        <GridLoader label="Reading object permissions" rows={5} columns={8} />
      ) : (
        <DataTable
          name="Object permissions"
          columns={objectColumns}
          rows={objectPermsQ.data ?? []}
          rowKey={(r) => `${r.holderType}:${r.holderId}:${r.objectType}`}
          initialGroupBy="objectType"
          note="View-all and modify-all short-circuit every record-sharing layer beneath them."
        />
      )}

      <h2 className="eyebrow" style={{ marginTop: 20 }}>
        Field-level security
      </h2>
      {fieldPermsQ.isLoading ? (
        <GridLoader label="Reading field permissions" rows={4} columns={5} />
      ) : (
        <DataTable
          name="Field permissions"
          columns={fieldColumns}
          rows={fieldPermsQ.data ?? []}
          rowKey={(r) => `${r.holderType}:${r.holderId}:${r.objectType}:${r.fieldName}`}
          initialGroupBy="objectType"
          empty="No field is restricted; every field is readable by default."
          note="A denied field is removed from the response entirely — absent, not null. Absence and emptiness are not the same answer."
        />
      )}

      <h2 className="eyebrow" style={{ marginTop: 20 }}>
        Assignments, with expiry
      </h2>
      {assignmentsQ.isLoading ? (
        <GridLoader label="Reading assignments" rows={4} columns={7} />
      ) : (
        <DataTable
          name="Permission set assignments"
          columns={assignmentColumns}
          rows={assignmentsQ.data ?? []}
          rowKey={(a) => a.id}
          actions={
            canWrite
              ? (a) =>
                  a.revokedAt ? null : (
                    <button
                      type="button"
                      className="link-btn danger-link"
                      disabled={revoke.isPending}
                      onClick={() => revoke.mutate(a.id)}
                    >
                      Revoke
                    </button>
                  )
              : undefined
          }
          note="An expiry takes effect without a login cycle — the resolver filters on it at every request."
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Org-wide defaults
// ---------------------------------------------------------------------------

function OrgWideDefaultsTab({ canWrite, onChanged }: { canWrite: boolean; onChanged: () => void }) {
  const toasts = useToasts();
  const owdQ = useQuery({ queryKey: ["rbac", "owd"], queryFn: rbac.orgWideDefaults, retry: 1 });

  const save = useMutation({
    mutationFn: (row: { objectType: string; defaultAccess: string; roleHierarchyRollup: boolean }) =>
      rbac.setOrgWideDefault(row),
    onSuccess: () => {
      toasts.push("info", "Org-wide default updated", "A recompute was queued for the affected records.");
      void owdQ.refetch();
      onChanged();
    },
    onError: (error) => toasts.push("error", "Default not changed", messageOf(error)),
  });

  const columns: Column<OrgWideDefaultRow>[] = [
    { key: "objectType", header: "Object", value: (r) => r.objectType, filter: "enum", cellClass: "mono" },
    {
      key: "defaultAccess",
      header: "Default access",
      value: (r) => r.defaultAccess,
      filter: "enum",
      render: (r) =>
        canWrite ? (
          <select
            aria-label={`Org-wide default for ${r.objectType}`}
            value={r.defaultAccess}
            disabled={save.isPending}
            onChange={(event) =>
              save.mutate({
                objectType: r.objectType,
                defaultAccess: event.target.value,
                roleHierarchyRollup: r.roleHierarchyRollup,
              })
            }
          >
            <option value="private">private</option>
            <option value="read_only">read_only</option>
            <option value="read_write">read_write</option>
          </select>
        ) : (
          <span className="chip">{r.defaultAccess}</span>
        ),
    },
    {
      key: "roleHierarchyRollup",
      header: "Role roll-up",
      value: (r) => r.roleHierarchyRollup,
      filter: "boolean",
      render: (r) =>
        canWrite ? (
          <button
            type="button"
            className="link-btn"
            disabled={save.isPending}
            onClick={() =>
              save.mutate({
                objectType: r.objectType,
                defaultAccess: r.defaultAccess,
                roleHierarchyRollup: !r.roleHierarchyRollup,
              })
            }
          >
            {r.roleHierarchyRollup ? "On" : "Off"}
          </button>
        ) : (
          <BoolChip value={r.roleHierarchyRollup} yes="On" no="Off" />
        ),
    },
    { key: "updatedAt", header: "Updated", value: (r) => r.updatedAt ?? "", blank: "never" },
  ];

  return (
    <>
      {owdQ.isLoading && <GridLoader label="Reading org-wide defaults" rows={4} columns={4} />}
      {owdQ.isError && <p className="form-error">{messageOf(owdQ.error)}</p>}
      {owdQ.isSuccess && (
        <DataTable
          name="Org-wide defaults"
          columns={columns}
          rows={owdQ.data}
          rowKey={(r) => r.objectType}
          note="This is the floor. Every other layer only widens it; narrowing is achieved by lowering the default, never by a sharing rule."
        />
      )}
      {save.isPending && <InlineLoader label="Saving the default" />}
    </>
  );
}

// ---------------------------------------------------------------------------
// Sharing rules and the recompute queue
// ---------------------------------------------------------------------------

function SharingTab({ canWrite, onChanged }: { canWrite: boolean; onChanged: () => void }) {
  const toasts = useToasts();
  const rulesQ = useQuery({ queryKey: ["rbac", "sharing", "rules"], queryFn: rbac.sharingRules, retry: 1 });
  const jobsQ = useQuery({
    queryKey: ["rbac", "sharing", "jobs"],
    queryFn: () => rbac.sharingJobs(50),
    retry: 1,
    // The queue is the one thing on this page that moves on its own.
    refetchInterval: 5000,
  });

  const activate = useMutation({
    mutationFn: (input: { id: string; active: boolean }) => rbac.activateSharingRule(input.id, input.active),
    onSuccess: (_data, input) => {
      toasts.push(
        "info",
        input.active ? "Sharing rule activated" : "Sharing rule deactivated",
        "A recompute job was queued; watch its progress below.",
      );
      void rulesQ.refetch();
      void jobsQ.refetch();
      onChanged();
    },
    onError: (error) => toasts.push("error", "Rule not changed", messageOf(error)),
  });

  const drain = useMutation({
    mutationFn: () => rbac.drainSharingJobs(),
    onSuccess: (result) => {
      toasts.push("info", "Recompute drained", `${result.processed} job(s) processed.`);
      void jobsQ.refetch();
    },
    onError: (error) => toasts.push("error", "Drain refused", messageOf(error)),
  });

  const ruleColumns: Column<SharingRule>[] = [
    { key: "code", header: "Code", value: (r) => r.code, cellClass: "mono" },
    { key: "name", header: "Name", value: (r) => r.name },
    { key: "objectType", header: "Object", value: (r) => r.objectType, filter: "enum" },
    { key: "ruleType", header: "Type", value: (r) => r.ruleType, filter: "enum" },
    {
      key: "criteria",
      header: "Criteria / source",
      value: (r) =>
        r.ruleType === "CRITERIA"
          ? `${r.criteriaField} ${r.criteriaOperator} ${r.criteriaValue}`
          : `${r.sourceType} ${r.sourceLabel ?? r.sourceId ?? ""}`,
    },
    { key: "targetType", header: "Target type", value: (r) => r.targetType, filter: "enum" },
    { key: "targetLabel", header: "Target", value: (r) => r.targetLabel ?? r.targetId },
    {
      key: "targetIncludesSubordinates",
      header: "Incl. subordinates",
      value: (r) => r.targetIncludesSubordinates,
      filter: "boolean",
      render: (r) => <BoolChip value={r.targetIncludesSubordinates} />,
    },
    { key: "accessLevel", header: "Access", value: (r) => r.accessLevel, filter: "enum" },
    { key: "materializedShares", header: "Shares", value: (r) => r.materializedShares, cellClass: "num" },
    { key: "lastRecomputedAt", header: "Last recomputed", value: (r) => r.lastRecomputedAt ?? "", blank: "never" },
    {
      key: "active",
      header: "Active",
      value: (r) => r.active,
      filter: "boolean",
      render: (r) => <BoolChip value={r.active} />,
    },
  ];

  const jobColumns: Column<RecomputeJob>[] = [
    { key: "objectType", header: "Object", value: (j) => j.objectType, filter: "enum" },
    { key: "scope", header: "Scope", value: (j) => j.scope, filter: "enum" },
    { key: "triggerReason", header: "Trigger", value: (j) => j.triggerReason },
    { key: "status", header: "Status", value: (j) => j.status, filter: "enum", render: (j) => <span className="chip">{j.status}</span> },
    {
      key: "progress",
      header: "Progress",
      value: (j) => (j.totalUnits === 0 ? 0 : Math.round((j.processedUnits / j.totalUnits) * 100)),
      render: (j) => (
        <span className="num">
          {j.processedUnits} / {j.totalUnits}
          {j.totalUnits > 0 ? ` (${Math.round((j.processedUnits / j.totalUnits) * 100)}%)` : ""}
        </span>
      ),
      cellClass: "num",
    },
    { key: "sharesWritten", header: "Written", value: (j) => j.sharesWritten, cellClass: "num" },
    { key: "sharesRevoked", header: "Revoked", value: (j) => j.sharesRevoked, cellClass: "num" },
    { key: "queuedAt", header: "Queued", value: (j) => j.queuedAt },
    { key: "message", header: "Message", value: (j) => j.message ?? "", blank: "—" },
  ];

  return (
    <>
      {rulesQ.isLoading && <GridLoader label="Reading sharing rules" rows={4} columns={8} />}
      {rulesQ.isError && <p className="form-error">{messageOf(rulesQ.error)}</p>}
      {rulesQ.isSuccess && (
        <DataTable
          name="Sharing rules"
          columns={ruleColumns}
          rows={rulesQ.data}
          rowKey={(r) => r.id}
          initialGroupBy="objectType"
          empty="No sharing rules are defined. Every record is governed by the org-wide default alone."
          actions={
            canWrite
              ? (r) => (
                  <button
                    type="button"
                    className="link-btn"
                    disabled={activate.isPending}
                    onClick={() => activate.mutate({ id: r.id, active: !r.active })}
                  >
                    {r.active ? "Deactivate" : "Activate"}
                  </button>
                )
              : undefined
          }
          note="Rules are created inactive. Activation queues a recompute rather than blocking the request."
        />
      )}

      <h2 className="eyebrow" style={{ marginTop: 20 }}>
        Recompute queue
      </h2>
      <div className="page-controls">
        <div>
          {canWrite && (
            <button type="button" className="btn btn-sm" disabled={drain.isPending} onClick={() => drain.mutate()}>
              {drain.isPending ? "Draining..." : "Drain now"}
            </button>
          )}
        </div>
        <span className="count">refreshing every 5s</span>
      </div>
      {jobsQ.isLoading ? (
        <GridLoader label="Reading the recompute queue" rows={4} columns={7} />
      ) : (
        <DataTable
          name="Sharing recompute jobs"
          columns={jobColumns}
          rows={jobsQ.data ?? []}
          rowKey={(j) => j.id}
          initialGroupBy="status"
          empty="Nothing queued."
          note="A full rebuild never blocks business writes; it advances in batches and reports its own progress."
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Segregation of duties
// ---------------------------------------------------------------------------

function SodTab({ canWrite, onChanged }: { canWrite: boolean; onChanged: () => void }) {
  const toasts = useToasts();
  const conflictsQ = useQuery({ queryKey: ["rbac", "sod", "conflicts"], queryFn: rbac.sodConflicts, retry: 1 });
  const findingsQ = useQuery({ queryKey: ["rbac", "sod", "findings"], queryFn: rbac.sodFindings, retry: 1 });

  const sweep = useMutation({
    mutationFn: () => rbac.sweepSod(),
    onSuccess: (result) => {
      toasts.push("info", "Sweep complete", `${result.openFindings} open finding(s).`);
      void findingsQ.refetch();
      onChanged();
    },
    onError: (error) => toasts.push("error", "Sweep refused", messageOf(error)),
  });

  const resolve = useMutation({
    mutationFn: (input: { id: string; status: string }) =>
      rbac.resolveFinding(input.id, input.status, "Resolved from the authorization screen"),
    onSuccess: () => {
      void findingsQ.refetch();
      onChanged();
    },
    onError: (error) => toasts.push("error", "Finding not updated", messageOf(error)),
  });

  const conflictColumns: Column<SodConflict>[] = [
    { key: "code", header: "Code", value: (c) => c.code, cellClass: "mono" },
    { key: "name", header: "Name", value: (c) => c.name },
    { key: "permissionA", header: "Permission A", value: (c) => c.permissionA, cellClass: "mono", filter: "enum" },
    { key: "permissionB", header: "Permission B", value: (c) => c.permissionB, cellClass: "mono", filter: "enum" },
    { key: "severity", header: "Severity", value: (c) => c.severity, filter: "enum", render: (c) => <span className="chip">{c.severity}</span> },
    { key: "rationale", header: "Rationale", value: (c) => c.rationale ?? "", blank: "—" },
    { key: "active", header: "Active", value: (c) => c.active, filter: "boolean", render: (c) => <BoolChip value={c.active} /> },
  ];

  const findingColumns: Column<SodFinding>[] = [
    { key: "conflictCode", header: "Conflict", value: (f) => f.conflictCode, cellClass: "mono", filter: "enum" },
    { key: "conflictName", header: "Name", value: (f) => f.conflictName },
    { key: "userEmail", header: "User", value: (f) => f.userEmail, filter: "enum" },
    { key: "holderA", header: "Holds A via", value: (f) => f.holderA },
    { key: "holderB", header: "Holds B via", value: (f) => f.holderB },
    { key: "status", header: "Status", value: (f) => f.status, filter: "enum", render: (f) => <span className="chip">{f.status}</span> },
    { key: "detectedAt", header: "Detected", value: (f) => f.detectedAt },
  ];

  return (
    <>
      <h2 className="eyebrow">Declared conflicting pairs</h2>
      {conflictsQ.isLoading ? (
        <GridLoader label="Reading declared conflicts" rows={3} columns={7} />
      ) : (
        <DataTable
          name="Segregation of duties conflicts"
          columns={conflictColumns}
          rows={conflictsQ.data ?? []}
          rowKey={(c) => c.id}
          initialGroupBy="severity"
          empty="No conflicting pairs are declared for this workspace."
          actions={
            canWrite
              ? (c) =>
                  c.active ? (
                    <button
                      type="button"
                      className="link-btn danger-link"
                      onClick={() => rbac.retireConflict(c.id).then(() => void conflictsQ.refetch())}
                    >
                      Retire
                    </button>
                  ) : null
              : undefined
          }
        />
      )}

      <h2 className="eyebrow" style={{ marginTop: 20 }}>
        Existing violations
      </h2>
      <div className="page-controls">
        <div>
          {canWrite && (
            <button type="button" className="btn btn-sm" disabled={sweep.isPending} onClick={() => sweep.mutate()}>
              {sweep.isPending ? "Sweeping..." : "Sweep now"}
            </button>
          )}
        </div>
        <span className="count">{(findingsQ.data ?? []).filter((f) => f.status === "OPEN").length} open</span>
      </div>
      {findingsQ.isLoading ? (
        <GridLoader label="Reading findings" rows={3} columns={7} />
      ) : (
        <DataTable
          name="Segregation of duties findings"
          columns={findingColumns}
          rows={findingsQ.data ?? []}
          rowKey={(f) => f.id}
          initialGroupBy="status"
          empty="No user currently holds both sides of a declared conflict."
          actions={
            canWrite
              ? (f) =>
                  f.status === "OPEN" ? (
                    <button
                      type="button"
                      className="link-btn"
                      disabled={resolve.isPending}
                      onClick={() => resolve.mutate({ id: f.id, status: "ACKNOWLEDGED" })}
                    >
                      Acknowledge
                    </button>
                  ) : null
              : undefined
          }
          note="A pair declared today may already be violated by a grant made last year. Those surface here rather than being grandfathered in."
        />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Access explainer — FR-SEC-013
// ---------------------------------------------------------------------------

function ExplainerTab({ users, objects }: { users: TenantUser[]; objects: string[] }) {
  const [userId, setUserId] = useState(users[0]?.id ?? "");
  const [objectType, setObjectType] = useState(objects[0] ?? "ACCOUNT");
  const [recordId, setRecordId] = useState("");
  const [asked, setAsked] = useState<{ userId: string; objectType: string; recordId: string } | null>(null);

  const explainQ = useQuery({
    queryKey: ["rbac", "explain", asked?.userId, asked?.objectType, asked?.recordId],
    queryFn: () => rbac.explain(asked!.userId, asked!.objectType, asked!.recordId),
    enabled: asked !== null,
    retry: false,
  });

  const causeColumns: Column<AccessCause>[] = [
    { key: "layer", header: "Layer", value: (c) => c.layer, filter: "enum" },
    { key: "cause", header: "record_share cause", value: (c) => c.cause ?? "", blank: "—", filter: "enum", cellClass: "mono" },
    {
      key: "verdict",
      header: "Verdict",
      value: (c) => c.verdict,
      filter: "enum",
      render: (c) => <span className="chip">{c.verdict}</span>,
    },
    { key: "accessLevel", header: "Grants", value: (c) => c.accessLevel ?? "", blank: "—", filter: "enum" },
    { key: "ruleRef", header: "Rule / reference", value: (c) => c.ruleRef ?? "", blank: "—", cellClass: "mono" },
    { key: "detail", header: "Why", value: (c) => c.detail, filter: "text" },
    { key: "expiresAt", header: "Expires", value: (c) => c.expiresAt ?? "", blank: "—" },
  ];

  const shareColumns: Column<MaterializedShare>[] = [
    { key: "cause", header: "Cause", value: (s) => s.cause, filter: "enum", cellClass: "mono" },
    { key: "causeRef", header: "Cause ref", value: (s) => s.causeRef ?? "", blank: "—", cellClass: "mono" },
    { key: "causeDetail", header: "Detail", value: (s) => s.causeDetail ?? "", blank: "—" },
    { key: "accessLevel", header: "Access", value: (s) => s.accessLevel, filter: "enum" },
    { key: "expiresAt", header: "Expires", value: (s) => s.expiresAt ?? "", blank: "never" },
    { key: "createdAt", header: "Granted", value: (s) => s.createdAt },
  ];

  const explanation = explainQ.data;

  return (
    <>
      <div className="panel" style={{ padding: 14, display: "flex", gap: 12, flexWrap: "wrap", alignItems: "flex-end" }}>
        <div>
          <label className="label" htmlFor="explain-user">
            User
          </label>
          <select id="explain-user" value={userId} onChange={(event) => setUserId(event.target.value)}>
            {users.map((candidate) => (
              <option key={candidate.id} value={candidate.id}>
                {candidate.displayName} ({candidate.crmRole})
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="label" htmlFor="explain-object">
            Object
          </label>
          <select id="explain-object" value={objectType} onChange={(event) => setObjectType(event.target.value)}>
            {objects.map((candidate) => (
              <option key={candidate} value={candidate}>
                {candidate}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="label" htmlFor="explain-record">
            Record id
          </label>
          <input
            id="explain-record"
            className="mono"
            placeholder="00000000-0000-0000-0000-000000000000"
            value={recordId}
            onChange={(event) => setRecordId(event.target.value.trim())}
            size={40}
          />
        </div>
        <button
          type="button"
          className="btn btn-sm btn-primary"
          disabled={!userId || !recordId}
          onClick={() => setAsked({ userId, objectType, recordId })}
        >
          Explain access
        </button>
      </div>

      {explainQ.isFetching && <PanelLoader label="Evaluating every layer" detail="Ownership, role hierarchy, sharing rules, teams, territories, manual shares" />}
      {explainQ.isError && <p className="form-error">{messageOf(explainQ.error)}</p>}

      {explanation && !explainQ.isFetching && (
        <>
          <div className="panel" style={{ padding: 14, marginTop: 14 }}>
            <span className="eyebrow">Verdict</span>
            <p>
              <strong>{explanation.userDisplayName}</strong> ({explanation.userEmail},{" "}
              {explanation.userCrmRole}, profile {explanation.profileCode}
              {explanation.roleCode ? `, role ${explanation.roleCode}` : ", no role node"}) on{" "}
              <span className="mono">
                {explanation.objectType} {explanation.recordId}
              </span>
              : <span className="chip">{explanation.verdict}</span>
            </p>
            <p className="loading-note">
              Org-wide default <span className="mono">{explanation.orgWideDefault}</span>, role
              roll-up {explanation.roleHierarchyRollup ? "on" : "off"}.
            </p>
            {explanation.denialReason && <p className="form-error">{explanation.denialReason}</p>}
            {explanation.unreadableFields.length > 0 && (
              <p className="form-notice">
                Fields removed from every response for this user (absent, not null):{" "}
                <span className="mono">{explanation.unreadableFields.join(", ")}</span>
              </p>
            )}
            {!explanation.materializationMatchesLiveEvaluation && (
              <p className="form-notice">
                Live evaluation and the materialized shares disagree. A recompute job is behind —
                check the sharing tab rather than trusting either number alone.
              </p>
            )}
          </div>

          <h2 className="eyebrow" style={{ marginTop: 20 }}>
            Every rule that contributes
          </h2>
          <DataTable
            name="Access causes"
            columns={causeColumns}
            rows={explanation.causes}
            rowKey={(c) => `${c.layer}:${c.ruleRef ?? c.cause ?? ""}`}
            initialGroupBy="verdict"
            note="Layers that granted nothing are still listed. A silent layer is indistinguishable from an unevaluated one."
          />

          <h2 className="eyebrow" style={{ marginTop: 20 }}>
            Materialized record_share rows
          </h2>
          <DataTable
            name="Materialized shares"
            columns={shareColumns}
            rows={explanation.materializedShares}
            rowKey={(s) => s.id}
            initialGroupBy="cause"
            empty="No materialized share. Ownership, an open org-wide default and view-all are deliberately not materialized."
            note="cause + cause_ref is the stored answer to why, captured when the grant was made."
          />
        </>
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Administrator / auditor floor
// ---------------------------------------------------------------------------

function FloorTab({
  canWrite,
  users,
  onChanged,
}: {
  canWrite: boolean;
  users: TenantUser[];
  onChanged: () => void;
}) {
  const toasts = useToasts();
  const [refusal, setRefusal] = useState<string | null>(null);
  const [repairUser, setRepairUser] = useState("");
  const [repairRole, setRepairRole] = useState("AUDITOR");
  const [repairReason, setRepairReason] = useState("");

  const reportQ = useQuery({ queryKey: ["rbac", "floor"], queryFn: rbac.tenantFloor, retry: 1 });
  const usersQ = useQuery({ queryKey: ["rbac", "users"], queryFn: rbac.users, retry: 1 });

  const setActive = useMutation({
    mutationFn: (input: { userId: string; active: boolean }) =>
      rbac.setUserActive(input.userId, input.active, "Changed from the authorization screen"),
    onSuccess: () => {
      setRefusal(null);
      toasts.push("info", "User updated", "The floor still holds.");
      void usersQ.refetch();
      void reportQ.refetch();
      onChanged();
    },
    onError: (error) => setRefusal(messageOf(error)),
  });

  const changeRole = useMutation({
    mutationFn: (input: { userId: string; role: string }) =>
      rbac.changeUserRole(input.userId, input.role, "Changed from the authorization screen"),
    onSuccess: () => {
      setRefusal(null);
      void usersQ.refetch();
      void reportQ.refetch();
      onChanged();
    },
    onError: (error) => setRefusal(messageOf(error)),
  });

  const repair = useMutation({
    mutationFn: () => rbac.repairFloor(repairUser, repairRole, repairReason),
    onSuccess: () => {
      setRefusal(null);
      setRepairReason("");
      toasts.push("info", "Floor repaired", "The grant was recorded with your reason.");
      void usersQ.refetch();
      void reportQ.refetch();
      onChanged();
    },
    onError: (error) => setRefusal(messageOf(error)),
  });

  const findingColumns: Column<FloorFinding>[] = [
    { key: "tenantSlug", header: "Workspace", value: (f) => f.tenantSlug, filter: "enum", cellClass: "mono" },
    { key: "tenantName", header: "Name", value: (f) => f.tenantName },
    { key: "roleCode", header: "Seat", value: (f) => f.roleCode, filter: "enum", cellClass: "mono" },
    { key: "requirement", header: "Must have", value: (f) => f.requirement },
    { key: "activeHolders", header: "Active holders", value: (f) => f.activeHolders, cellClass: "num" },
    {
      key: "compliant",
      header: "Compliant",
      value: (f) => f.compliant,
      filter: "boolean",
      render: (f) => <BoolChip value={f.compliant} yes="Yes" no="VIOLATION" />,
    },
    { key: "finding", header: "Finding", value: (f) => f.finding },
    { key: "remedy", header: "Remedy", value: (f) => f.remedy ?? "", blank: "—" },
  ];

  const userColumns: Column<TenantUser>[] = [
    { key: "displayName", header: "Name", value: (u) => u.displayName },
    { key: "email", header: "Email", value: (u) => u.email },
    {
      key: "crmRole",
      header: "Role",
      value: (u) => u.crmRole,
      filter: "enum",
      render: (u) =>
        canWrite ? (
          <select
            aria-label={`Role for ${u.email}`}
            value={u.crmRole}
            disabled={changeRole.isPending}
            onChange={(event) => changeRole.mutate({ userId: u.id, role: event.target.value })}
          >
            {CRM_ROLES.map((role) => (
              <option key={role} value={role}>
                {role}
              </option>
            ))}
          </select>
        ) : (
          <span className="chip">{u.crmRole}</span>
        ),
    },
    {
      key: "active",
      header: "Active",
      value: (u) => u.active,
      filter: "boolean",
      render: (u) => <BoolChip value={u.active} />,
    },
  ];

  const violations = (reportQ.data?.findings ?? []).filter((f) => !f.compliant);

  return (
    <>
      <div className="panel" style={{ padding: 14 }}>
        <span className="eyebrow">The invariant</span>
        <p>
          Every workspace must keep exactly one active <span className="mono">TENANT_ADMIN</span> —
          the administrator with complete read and write — and one active{" "}
          <span className="mono">AUDITOR</span> — the auditor with complete read and view. The last
          holder of either seat cannot be deactivated, deleted or moved to another role. That is
          checked here, and again by a statement-level trigger on the user table so it also holds
          for a direct SQL edit.
        </p>
        {reportQ.data && (
          <p className="loading-note">
            {reportQ.data.tenantsInspected} workspace(s) inspected
            {reportQ.data.crossTenant ? " across the platform" : ""}, {reportQ.data.violations}{" "}
            violation(s).
          </p>
        )}
      </div>

      {refusal && (
        <p className="form-error" role="alert">
          {refusal}
        </p>
      )}

      {violations.length > 0 && (
        <p className="form-notice">
          Existing gaps are reported, not repaired automatically. An unexplained role grant is worse
          than a visible gap — use the repair form below and give a reason that will be audited.
        </p>
      )}

      {reportQ.isLoading && <GridLoader label="Checking the administrator/auditor floor" rows={4} columns={7} />}
      {reportQ.isError && <p className="form-error">{messageOf(reportQ.error)}</p>}
      {reportQ.isSuccess && (
        <DataTable
          name="Administrator and auditor floor"
          columns={findingColumns}
          rows={reportQ.data.findings}
          rowKey={(f) => `${f.tenantId}:${f.roleCode}`}
          initialGroupBy="compliant"
          note="Reported, never silently repaired."
        />
      )}

      {canWrite && violations.length > 0 && (
        <div className="panel" style={{ padding: 14, marginTop: 14, display: "flex", gap: 12, flexWrap: "wrap", alignItems: "flex-end" }}>
          <span className="eyebrow">Repair a gap</span>
          <div>
            <label className="label" htmlFor="repair-user">
              Grant to
            </label>
            <select id="repair-user" value={repairUser} onChange={(event) => setRepairUser(event.target.value)}>
              <option value="">Choose a user</option>
              {(usersQ.data ?? users)
                .filter((candidate) => candidate.active)
                .map((candidate) => (
                  <option key={candidate.id} value={candidate.id}>
                    {candidate.displayName} ({candidate.crmRole})
                  </option>
                ))}
            </select>
          </div>
          <div>
            <label className="label" htmlFor="repair-role">
              Seat
            </label>
            <select id="repair-role" value={repairRole} onChange={(event) => setRepairRole(event.target.value)}>
              <option value="AUDITOR">AUDITOR</option>
              <option value="TENANT_ADMIN">TENANT_ADMIN</option>
            </select>
          </div>
          <div>
            <label className="label" htmlFor="repair-reason">
              Reason (audited)
            </label>
            <input
              id="repair-reason"
              value={repairReason}
              onChange={(event) => setRepairReason(event.target.value)}
              placeholder="Appointed by the compliance lead on 2026-07-26"
              size={44}
            />
          </div>
          <button
            type="button"
            className="btn btn-sm btn-primary"
            disabled={!repairUser || !repairReason.trim() || repair.isPending}
            onClick={() => repair.mutate()}
          >
            {repair.isPending ? "Granting..." : "Grant the seat"}
          </button>
        </div>
      )}

      <h2 className="eyebrow" style={{ marginTop: 20 }}>
        Workspace users
      </h2>
      {usersQ.isLoading ? (
        <GridLoader label="Reading users" rows={4} columns={4} />
      ) : (
        <DataTable
          name="Workspace users"
          columns={userColumns}
          rows={usersQ.data ?? users}
          rowKey={(u) => u.id}
          initialGroupBy="crmRole"
          actions={
            canWrite
              ? (u) => (
                  <button
                    type="button"
                    className={u.active ? "link-btn danger-link" : "link-btn"}
                    disabled={setActive.isPending}
                    onClick={() => setActive.mutate({ userId: u.id, active: !u.active })}
                  >
                    {u.active ? "Deactivate" : "Reactivate"}
                  </button>
                )
              : undefined
          }
        />
      )}
    </>
  );
}

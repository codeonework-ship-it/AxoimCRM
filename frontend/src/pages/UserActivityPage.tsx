import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useAuth } from "../auth/AuthContext";
import { BoolChip, DataTable, type Column } from "../components/DataTable";
import { GridLoader, PanelLoader } from "../components/Loaders";
import {
  activityApi,
  rbac,
  RbacApiError,
  type ActivityFilter,
  type ActivityRow,
  type TenantUser,
} from "../api/rbac";

/**
 * User activity — every request, and every refusal.
 *
 * <p>The default filter is deliberately "everything", not "denied only". A
 * denial list on its own reads as a list of attacks; seen against the volume of
 * ordinary traffic it usually reads as a misconfigured permission set, which is
 * what it normally is. Both readings need the same screen.
 *
 * <p>The FR-AUD-014 guarantee is published rather than asserted: the allowlist
 * of keys this log is permitted to hold is shown at the bottom, so an auditor
 * can check the claim instead of taking it.
 */

/** Mirrors com.axiom.activity.UserActivityService.ActivityAccess.READ. */
const ACTIVITY_READ_ROLES = new Set([
  "SUPER_ADMIN",
  "SUPER_AUDIT",
  "TENANT_ADMIN",
  "AUDITOR",
  "OPERATIONS",
]);

const OUTCOMES = ["", "SUCCESS", "DENIED", "ERROR"];

function messageOf(error: unknown): string {
  if (error instanceof RbacApiError) return error.message;
  if (error instanceof Error) return error.message;
  return "The request failed.";
}

function detailText(detail: Record<string, unknown>): string {
  return Object.entries(detail)
    .map(([key, value]) => `${key}=${String(value)}`)
    .join(" ");
}

export function UserActivityPage() {
  const { user } = useAuth();
  const allowed = ACTIVITY_READ_ROLES.has(user?.role ?? "");

  const [filter, setFilter] = useState<ActivityFilter>({ limit: 200 });
  const [timelineUser, setTimelineUser] = useState<string | null>(null);

  const usersQ = useQuery({ queryKey: ["rbac", "users"], queryFn: rbac.users, retry: 1, enabled: allowed });
  const actionsQ = useQuery({ queryKey: ["activity", "actions"], queryFn: activityApi.actions, retry: 1, enabled: allowed });
  const eventsQ = useQuery({
    queryKey: ["activity", "events", filter],
    queryFn: () => activityApi.events(filter),
    retry: 1,
    enabled: allowed,
  });
  const summaryQ = useQuery({
    queryKey: ["activity", "summary", filter],
    queryFn: () => activityApi.summary(filter),
    retry: 1,
    enabled: allowed,
  });
  const allowlistQ = useQuery({
    queryKey: ["activity", "allowlist"],
    queryFn: activityApi.detailAllowlist,
    retry: 1,
    enabled: allowed,
  });
  const timelineQ = useQuery({
    queryKey: ["activity", "timeline", timelineUser],
    queryFn: () => activityApi.timeline(timelineUser!, 200),
    enabled: allowed && timelineUser !== null,
    retry: 1,
  });

  if (!allowed) {
    return (
      <>
        <div className="page-head">
          <div>
            <span className="eyebrow">Security</span>
            <h1>User activity</h1>
          </div>
        </div>
        <p className="form-error">
          Reading the user activity log requires an administrator, auditor or operations role. Your
          role is {user?.role ?? "unknown"}.
        </p>
      </>
    );
  }

  const users: TenantUser[] = usersQ.data ?? [];

  const columns: Column<ActivityRow>[] = [
    { key: "occurredAt", header: "When", value: (row) => row.occurredAt, cellClass: "mono" },
    { key: "actorEmail", header: "Actor", value: (row) => row.actorEmail ?? "", blank: "anonymous", filter: "enum" },
    { key: "actorRole", header: "Role", value: (row) => row.actorRole ?? "", blank: "—", filter: "enum" },
    {
      key: "impersonatorEmail",
      header: "Impersonated by",
      value: (row) => row.impersonatorEmail ?? "",
      blank: "—",
      filter: "enum",
    },
    { key: "action", header: "Action", value: (row) => row.action, cellClass: "mono" },
    { key: "objectType", header: "Object", value: (row) => row.objectType ?? "", blank: "—", filter: "enum" },
    { key: "objectId", header: "Record", value: (row) => row.objectId ?? "", blank: "—", cellClass: "mono" },
    { key: "source", header: "Source", value: (row) => row.source, filter: "enum" },
    {
      key: "outcome",
      header: "Outcome",
      value: (row) => row.outcome,
      filter: "enum",
      render: (row) => <span className="chip">{row.outcome}</span>,
    },
    { key: "statusCode", header: "Status", value: (row) => row.statusCode ?? "", cellClass: "num", filter: "enum" },
    { key: "denialReason", header: "Denial reason", value: (row) => row.denialReason ?? "", blank: "—" },
    { key: "clientIp", header: "IP", value: (row) => row.clientIp ?? "", blank: "—", cellClass: "mono" },
    { key: "correlationId", header: "Correlation", value: (row) => row.correlationId ?? "", blank: "—", cellClass: "mono" },
    { key: "detail", header: "Detail", value: (row) => detailText(row.detail), blank: "—", cellClass: "mono" },
  ];

  return (
    <>
      <div className="page-head">
        <div>
          <span className="eyebrow">Security</span>
          <h1>User activity</h1>
          <p>
            Every API request in this workspace, with its outcome. Refused requests are kept
            alongside successful ones — a permission check that fails changes nothing, so it leaves
            no audit event, and it is the event a security review most wants.
          </p>
        </div>
        {summaryQ.data && (
          <span className="count">
            {summaryQ.data.total} events · {summaryQ.data.denied} denied · {summaryQ.data.errors} errors ·{" "}
            {summaryQ.data.distinctActors} actors
          </span>
        )}
      </div>

      <div className="panel" style={{ padding: 14, display: "flex", gap: 12, flexWrap: "wrap", alignItems: "flex-end" }}>
        <div>
          <label className="label" htmlFor="activity-user">
            User
          </label>
          <select
            id="activity-user"
            value={filter.actorId ?? ""}
            onChange={(event) => setFilter((current) => ({ ...current, actorId: event.target.value || undefined }))}
          >
            <option value="">Everyone</option>
            {users.map((candidate) => (
              <option key={candidate.id} value={candidate.id}>
                {candidate.displayName} ({candidate.crmRole})
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="label" htmlFor="activity-action">
            Action contains
          </label>
          <input
            id="activity-action"
            list="activity-actions"
            value={filter.action ?? ""}
            onChange={(event) => setFilter((current) => ({ ...current, action: event.target.value || undefined }))}
            placeholder="/api/v1/security"
            size={28}
          />
          <datalist id="activity-actions">
            {(actionsQ.data ?? []).map((action) => (
              <option key={action} value={action} />
            ))}
          </datalist>
        </div>
        <div>
          <label className="label" htmlFor="activity-object">
            Object
          </label>
          <input
            id="activity-object"
            value={filter.objectType ?? ""}
            onChange={(event) => setFilter((current) => ({ ...current, objectType: event.target.value || undefined }))}
            placeholder="ACCOUNT"
            size={14}
          />
        </div>
        <div>
          <label className="label" htmlFor="activity-outcome">
            Outcome
          </label>
          <select
            id="activity-outcome"
            value={filter.outcome ?? ""}
            onChange={(event) => setFilter((current) => ({ ...current, outcome: event.target.value || undefined }))}
          >
            {OUTCOMES.map((outcome) => (
              <option key={outcome || "any"} value={outcome}>
                {outcome || "Any"}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="label" htmlFor="activity-from">
            From
          </label>
          <input
            id="activity-from"
            type="datetime-local"
            value={filter.from ? filter.from.slice(0, 16) : ""}
            onChange={(event) =>
              setFilter((current) => ({
                ...current,
                from: event.target.value ? `${event.target.value}:00Z` : undefined,
              }))
            }
          />
        </div>
        <div>
          <label className="label" htmlFor="activity-to">
            To
          </label>
          <input
            id="activity-to"
            type="datetime-local"
            value={filter.to ? filter.to.slice(0, 16) : ""}
            onChange={(event) =>
              setFilter((current) => ({
                ...current,
                to: event.target.value ? `${event.target.value}:00Z` : undefined,
              }))
            }
          />
        </div>
        <button type="button" className="btn btn-sm" onClick={() => setFilter({ limit: 200 })}>
          Clear filters
        </button>
        <button
          type="button"
          className="btn btn-sm"
          onClick={() => setFilter((current) => ({ ...current, outcome: "DENIED" }))}
        >
          Denied only
        </button>
      </div>

      {eventsQ.isLoading && <GridLoader label="Reading the activity log" rows={8} columns={9} />}
      {eventsQ.isError && <p className="form-error">{messageOf(eventsQ.error)}</p>}
      {eventsQ.isSuccess && (
        <DataTable
          name="User activity"
          columns={columns}
          rows={eventsQ.data}
          rowKey={(row) => row.id}
          initialGroupBy="outcome"
          empty="No activity matches this filter."
          actions={(row) =>
            row.actorId ? (
              <button type="button" className="link-btn" onClick={() => setTimelineUser(row.actorId)}>
                Timeline
              </button>
            ) : null
          }
          note="Paths are recorded without their query string, and the detail column is key-allowlisted — see the guarantee below."
        />
      )}

      {timelineUser && (
        <>
          <h2 className="eyebrow" style={{ marginTop: 20 }}>
            One user's timeline
          </h2>
          {timelineQ.isLoading && <PanelLoader label="Reading this user's timeline" />}
          {timelineQ.isError && <p className="form-error">{messageOf(timelineQ.error)}</p>}
          {timelineQ.data && (
            <>
              <div className="panel" style={{ padding: 14 }}>
                <p>
                  <strong>{timelineQ.data.displayName}</strong> ({timelineQ.data.email},{" "}
                  {timelineQ.data.crmRole}){" "}
                  <BoolChip value={timelineQ.data.active} yes="Active" no="Disabled" />
                </p>
                <p className="loading-note">
                  {timelineQ.data.summary.total} events, {timelineQ.data.summary.denied} denied,{" "}
                  {timelineQ.data.summary.errors} errors.
                </p>
                <button type="button" className="link-btn" onClick={() => setTimelineUser(null)}>
                  Close timeline
                </button>
              </div>
              <DataTable
                name="User timeline"
                columns={columns}
                rows={timelineQ.data.events}
                rowKey={(row) => row.id}
                empty="This user has no recorded activity."
              />
            </>
          )}
        </>
      )}

      <h2 className="eyebrow" style={{ marginTop: 20 }}>
        What this log is permitted to hold
      </h2>
      <p className="form-notice">
        FR-AUD-014: logs must never contain credentials, tokens or unmasked personal data. There is
        no request-body column and no header column, so a bearer token has nowhere to land. The
        structured detail column accepts only these keys — anything else is dropped on the way in
        and rejected by the database if some other path tries.
      </p>
      {allowlistQ.data && (
        <DataTable
          name="Activity detail allowlist"
          columns={[
            { key: "detailKey", header: "Key", value: (row) => row.detailKey, cellClass: "mono" },
            { key: "rationale", header: "Why it is safe", value: (row) => row.rationale },
          ]}
          rows={allowlistQ.data}
          rowKey={(row) => row.detailKey}
        />
      )}
    </>
  );
}

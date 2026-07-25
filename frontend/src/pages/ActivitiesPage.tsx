import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isUnreachable, type ActivityRow } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataGridToolbar } from "../components/DataGridToolbar";
import { DataViewFrame } from "../components/DataViewFrame";
import { useToasts } from "../components/Toasts";
import { formatDate } from "../lib/format";
import { GridLoader } from "../components/Loaders";

const TYPES = ["TASK", "EVENT", "CALL", "EMAIL_LOG", "NOTE"];
const STATUSES = ["OPEN", "COMPLETED", "CANCELLED"];
const PRIORITIES = ["LOW", "NORMAL", "HIGH", "URGENT"];
const RELATED_TYPES = ["ACCOUNT", "CONTACT", "LEAD", "OPPORTUNITY"];

function localDateTime(value: string): string | null {
  return value ? new Date(value).toISOString() : null;
}

export function ActivitiesPage() {
  const toasts = useToasts();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [type, setType] = useState("");
  const [status, setStatus] = useState("OPEN");
  const [groupedView, setGroupedView] = useState(true);
  const [draft, setDraft] = useState({
    activityType: "TASK",
    subject: "",
    body: "",
    priority: "NORMAL",
    relatedEntityType: "ACCOUNT",
    relatedEntityId: "",
    dueAt: "",
    reminderAt: "",
    direction: "OUTBOUND",
    durationMinutes: 15,
    disposition: "CONNECTED",
  });

  const activitiesQ = useQuery({
    queryKey: ["activities", page, search, type, status],
    queryFn: () => api.activities({ page, search, type, status }),
    retry: 1,
  });
  const summaryQ = useQuery({ queryKey: ["activities", "summary"], queryFn: api.activitySummary, retry: 1 });
  const createMutation = useMutation({
    mutationFn: () => api.createActivity({
      activityType: draft.activityType,
      subject: draft.subject,
      body: draft.body,
      priority: draft.priority,
      relatedEntityType: draft.relatedEntityType,
      relatedEntityId: draft.relatedEntityId,
      dueAt: localDateTime(draft.dueAt),
      reminderAt: localDateTime(draft.reminderAt),
      direction: draft.activityType === "CALL" ? draft.direction : null,
      durationMinutes: draft.activityType === "CALL" ? draft.durationMinutes : null,
      disposition: draft.activityType === "CALL" ? draft.disposition : null,
    }),
    onSuccess: () => {
      toasts.push("info", "Activity saved", "The engagement timeline was updated.");
      setDraft((value) => ({ ...value, subject: "", body: "" }));
      void queryClient.invalidateQueries({ queryKey: ["activities"] });
      void queryClient.invalidateQueries({ queryKey: ["notifications"] });
    },
    onError: (error) => toasts.push("error", "Activity rejected", error instanceof Error ? error.message : "Save failed."),
  });
  const completeMutation = useMutation({
    mutationFn: ({ id, outcome }: { id: string; outcome: string }) => api.completeActivity(id, outcome),
    onSuccess: () => {
      toasts.push("info", "Activity completed", "Outcome captured and audited.");
      void queryClient.invalidateQueries({ queryKey: ["activities"] });
    },
    onError: (error) => toasts.push("error", "Completion failed", error instanceof Error ? error.message : "Update failed."),
  });

  if (isUnreachable(activitiesQ.error)) return <ApiUnreachable onRetry={() => void activitiesQ.refetch()} retrying={activitiesQ.isFetching} />;

  const total = activitiesQ.data?.total ?? 0;
  const totalPages = activitiesQ.data?.totalPages ?? 0;
  const rows = activitiesQ.data?.items ?? [];
  const grouped = useMemo(() => {
    const result = new Map<string, ActivityRow[]>();
    rows.forEach((row) => {
      const key = row.relatedLabel ?? `${row.relatedEntityType} ${row.relatedEntityId.slice(0, 8)}`;
      result.set(key, [...(result.get(key) ?? []), row]);
    });
    return [...result.entries()];
  }, [rows]);

  function resetFilters() {
    setSearch("");
    setType("");
    setStatus("OPEN");
    setPage(0);
  }

  return <>
    <div className="page-head">
      <div><span className="eyebrow">Engagement timeline</span><h1>Activities</h1><p>Tasks, events, calls, notes and manual email logs across CRM records.</p></div>
      {activitiesQ.isSuccess && <span className="count">{total} activities</span>}
    </div>

    <div className="kpi-row activity-kpis">
      <div className="kpi"><span className="label">Open</span><div className="kpi-value">{summaryQ.data?.openCount ?? "…"}</div><div className="kpi-sub">Activities waiting for action</div></div>
      <div className="kpi"><span className="label">Overdue</span><div className={`kpi-value${(summaryQ.data?.overdueCount ?? 0) > 0 ? " crit" : ""}`}>{summaryQ.data?.overdueCount ?? "…"}</div><div className="kpi-sub">Past due open tasks/events</div></div>
      <div className="kpi"><span className="label">Completed 7d</span><div className="kpi-value">{summaryQ.data?.completedLast7Days ?? "…"}</div><div className="kpi-sub">Recent engagement closure</div></div>
      <div className="kpi"><span className="label">Last contacted</span><div className="kpi-value activity-date">{formatDate(summaryQ.data?.lastContactedAt)}</div><div className="kpi-sub">{summaryQ.data?.daysSinceLastActivity == null ? "No contact yet" : `${summaryQ.data.daysSinceLastActivity} days ago`}</div></div>
    </div>

    <section className="list-controls activity-controls" aria-label="Activity search and filters">
      <label><span>Search</span><input value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} placeholder="Subject, notes, outcome" /></label>
      <label><span>Type</span><select value={type} onChange={(event) => { setType(event.target.value); setPage(0); }}><option value="">All types</option>{TYPES.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
      <label><span>Status</span><select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}><option value="">All statuses</option>{STATUSES.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
      <button className="btn btn-sm" onClick={resetFilters}>Reset</button>
    </section>

    <DataViewFrame
      title="Activity timeline"
      actions={<DataGridToolbar
        gridName="Activity timeline"
        grouped={groupedView}
        groupLabel="Related record"
        onToggleGroup={() => setGroupedView((value) => !value)}
        auditEntityType="ACTIVITY"
        exportFilename="activity-timeline"
        exportRows={rows.map((row) => ({
          type: row.activityType,
          subject: row.subject,
          status: row.status,
          priority: row.priority,
          related: row.relatedLabel ?? row.relatedEntityType,
          owner: row.ownerName,
          dueAt: row.dueAt ?? "",
          occurredAt: row.occurredAt,
          completedAt: row.completedAt ?? "",
          outcome: row.outcome ?? "",
        }))}
        note="Current filtered page"
      />}
    >
      <section className="activity-create-panel" aria-label="Create activity">
        <div className="activity-form-grid">
          <select value={draft.activityType} onChange={(event) => setDraft((value) => ({ ...value, activityType: event.target.value }))}>{TYPES.map((value) => <option key={value} value={value}>{value}</option>)}</select>
          <input value={draft.subject} onChange={(event) => setDraft((value) => ({ ...value, subject: event.target.value }))} placeholder="Subject" />
          <select value={draft.priority} onChange={(event) => setDraft((value) => ({ ...value, priority: event.target.value }))}>{PRIORITIES.map((value) => <option key={value} value={value}>{value}</option>)}</select>
          <select value={draft.relatedEntityType} onChange={(event) => setDraft((value) => ({ ...value, relatedEntityType: event.target.value }))}>{RELATED_TYPES.map((value) => <option key={value} value={value}>{value}</option>)}</select>
          <input value={draft.relatedEntityId} onChange={(event) => setDraft((value) => ({ ...value, relatedEntityId: event.target.value }))} placeholder="Related record UUID" />
          <input type="datetime-local" value={draft.dueAt} onChange={(event) => setDraft((value) => ({ ...value, dueAt: event.target.value }))} aria-label="Due date" />
          <input type="datetime-local" value={draft.reminderAt} onChange={(event) => setDraft((value) => ({ ...value, reminderAt: event.target.value }))} aria-label="Reminder date" />
        </div>
        {draft.activityType === "CALL" && <div className="activity-form-grid call-fields">
          <select value={draft.direction} onChange={(event) => setDraft((value) => ({ ...value, direction: event.target.value }))}><option value="OUTBOUND">OUTBOUND</option><option value="INBOUND">INBOUND</option></select>
          <input type="number" min={0} value={draft.durationMinutes} onChange={(event) => setDraft((value) => ({ ...value, durationMinutes: Number(event.target.value) }))} aria-label="Duration minutes" />
          <input value={draft.disposition} onChange={(event) => setDraft((value) => ({ ...value, disposition: event.target.value.toUpperCase() }))} placeholder="Disposition" />
        </div>}
        <textarea value={draft.body} onChange={(event) => setDraft((value) => ({ ...value, body: event.target.value }))} placeholder="Notes, agenda, email summary, or call outcome detail" />
        <button className="btn btn-primary btn-sm" disabled={createMutation.isPending} onClick={() => createMutation.mutate()}>{createMutation.isPending ? "Saving..." : "Create activity"}</button>
      </section>

      {activitiesQ.isLoading && <GridLoader label="Reading engagement timeline" rows={6} columns={5} />}
      {activitiesQ.isError && <p className="empty-note">Activities failed to load{activitiesQ.error instanceof Error ? `: ${activitiesQ.error.message}` : "."}</p>}
      {activitiesQ.isSuccess && rows.length === 0 && <p className="empty-note">No activities match the current query.</p>}
      {groupedView && grouped.map(([group, items]) => <section className="activity-group" key={group}>
        <h2>{group}</h2>
        {items.map((activity) => <article className={`activity-row activity-${activity.activityType.toLowerCase()}`} key={activity.id}>
          <span className={`activity-stripe priority-${activity.priority.toLowerCase()}`} aria-hidden />
          <div className="activity-main">
            <div className="activity-title"><strong>{activity.subject}</strong><span className={`chip chip-${activity.status.toLowerCase()}`}>{activity.status}</span></div>
            <p>{activity.body || activity.outcome || "No notes captured."}</p>
            <small>{activity.activityType} · {activity.priority} · owner {activity.ownerName} · {activity.dueAt ? `due ${formatDate(activity.dueAt)}` : `occurred ${formatDate(activity.occurredAt)}`}</small>
          </div>
          {activity.status !== "COMPLETED" && <button className="btn btn-sm" disabled={completeMutation.isPending} onClick={() => {
            const outcome = window.prompt("Outcome", "Completed");
            if (outcome != null) completeMutation.mutate({ id: activity.id, outcome });
          }}>Complete</button>}
        </article>)}
      </section>)}
      {!groupedView && rows.map((activity) => <article className={`activity-row activity-${activity.activityType.toLowerCase()}`} key={activity.id}>
        <span className={`activity-stripe priority-${activity.priority.toLowerCase()}`} aria-hidden />
        <div className="activity-main">
          <div className="activity-title"><strong>{activity.subject}</strong><span className={`chip chip-${activity.status.toLowerCase()}`}>{activity.status}</span></div>
          <p>{activity.body || activity.outcome || "No notes captured."}</p>
          <small>{activity.activityType} · {activity.priority} · {activity.relatedLabel ?? activity.relatedEntityType} · owner {activity.ownerName} · {activity.dueAt ? `due ${formatDate(activity.dueAt)}` : `occurred ${formatDate(activity.occurredAt)}`}</small>
        </div>
        {activity.status !== "COMPLETED" && <button className="btn btn-sm" disabled={completeMutation.isPending} onClick={() => {
          const outcome = window.prompt("Outcome", "Completed");
          if (outcome != null) completeMutation.mutate({ id: activity.id, outcome });
        }}>Complete</button>}
      </article>)}
      {activitiesQ.isSuccess && <footer className="page-controls" aria-label="Activity pagination">
        <span>Showing {rows.length} of {total} records - 100 rows per page</span>
        <div>
          <button className="btn btn-sm" disabled={page === 0 || activitiesQ.isFetching} onClick={() => setPage((value) => Math.max(0, value - 1))}>Previous</button>
          <strong>Page {totalPages === 0 ? 0 : page + 1} of {totalPages}</strong>
          <button className="btn btn-sm" disabled={page + 1 >= totalPages || activitiesQ.isFetching} onClick={() => setPage((value) => value + 1)}>Next</button>
        </div>
      </footer>}
    </DataViewFrame>
  </>;
}

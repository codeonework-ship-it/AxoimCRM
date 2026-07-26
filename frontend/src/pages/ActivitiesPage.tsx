import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isUnreachable, type ActivityRow, type EmailTemplate } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataGridToolbar } from "../components/DataGridToolbar";
import { DataViewFrame } from "../components/DataViewFrame";
import { GridFilterHeader } from "../components/GridFilterRow";
import { InfoTag } from "../components/InfoTag";
import { useToasts } from "../components/Toasts";
import { formatDate } from "../lib/format";
import { GridLoader } from "../components/Loaders";
import { filterRowsByColumns, groupLabelFor, selectedGroupColumns, type GroupColumn } from "../lib/gridGrouping";
import { usePersistedGridState } from "../lib/usePersistedGridState";
import { useAppDialog } from "../components/AppDialog";

const TYPES = ["TASK", "EVENT", "CALL", "EMAIL_LOG", "NOTE"];
const STATUSES = ["OPEN", "COMPLETED", "CANCELLED"];
const PRIORITIES = ["LOW", "NORMAL", "HIGH", "URGENT"];
const RELATED_TYPES = ["ACCOUNT", "CONTACT", "LEAD", "OPPORTUNITY"];
const ACTIVITY_GROUP_COLUMNS: GroupColumn<ActivityRow>[] = [
  { key: "related", label: "Related record", value: (row) => row.relatedLabel ?? `${row.relatedEntityType} ${row.relatedEntityId.slice(0, 8)}` },
  { key: "type", label: "Type", value: (row) => row.activityType },
  { key: "status", label: "Status", value: (row) => row.status },
  { key: "priority", label: "Priority", value: (row) => row.priority },
  { key: "owner", label: "Owner", value: (row) => row.ownerName },
];

function localDateTime(value: string): string | null {
  return value ? new Date(value).toISOString() : null;
}

export function ActivitiesPage() {
  const toasts = useToasts();
  const dialog = useAppDialog();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [type, setType] = useState("");
  const [status, setStatus] = useState("OPEN");
  const [groupColumns, setGroupColumns, columnFilters, setColumnFilters] = usePersistedGridState("activities", { groupColumns: ["related"] });
  const [showTemplates, setShowTemplates] = useState(false);
  const [templateDraft, setTemplateDraft] = useState({
    apiName: "", name: "", folder: "General", sharingScope: "TENANT",
    subject: "", body: "", mergeFields: "",
  });
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
  const templatesQ = useQuery({
    queryKey: ["engagement", "email-templates"], queryFn: api.emailTemplates,
    enabled: showTemplates, retry: 1,
  });
  const createTemplate = useMutation({
    mutationFn: () => api.createEmailTemplate({
      ...templateDraft,
      mergeFields: templateDraft.mergeFields.split(",").map((value) => value.trim()).filter(Boolean),
      changeNote: "Initial approved version",
    }),
    onSuccess: () => {
      toasts.push("info", "Email template created", "Version 1 is now available without overwriting history.");
      setTemplateDraft({ apiName: "", name: "", folder: "General", sharingScope: "TENANT", subject: "", body: "", mergeFields: "" });
      void queryClient.invalidateQueries({ queryKey: ["engagement", "email-templates"] });
    },
    onError: (error) => toasts.push("error", "Template rejected", error instanceof Error ? error.message : "Template could not be saved."),
  });
  const reviseTemplate = useMutation({
    mutationFn: ({ template, subject, body, changeNote }: { template: EmailTemplate; subject: string; body: string; changeNote: string }) =>
      api.reviseEmailTemplate(template.id, { subject, body, mergeFields: template.mergeFields, changeNote }),
    onSuccess: (template) => {
      toasts.push("info", "New template version saved", `${template.name} is now at version ${template.currentVersion}.`);
      void queryClient.invalidateQueries({ queryKey: ["engagement", "email-templates"] });
    },
    onError: (error) => toasts.push("error", "Revision rejected", error instanceof Error ? error.message : "Revision could not be saved."),
  });
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

  const total = activitiesQ.data?.total ?? 0;
  const totalPages = activitiesQ.data?.totalPages ?? 0;
  const rawRows = activitiesQ.data?.items ?? [];
  const rows = filterRowsByColumns(rawRows, ACTIVITY_GROUP_COLUMNS, columnFilters);
  const activeGroupColumns = selectedGroupColumns(ACTIVITY_GROUP_COLUMNS, groupColumns);
  const grouped = useMemo(() => {
    const result = new Map<string, ActivityRow[]>();
    rows.forEach((row) => {
      const key = groupLabelFor(row, activeGroupColumns);
      result.set(key, [...(result.get(key) ?? []), row]);
    });
    return [...result.entries()];
  }, [activeGroupColumns, rows]);

  // Hooks must run in the same order on every render. Keeping this return
  // below the grid projection prevents a request changing from success to a
  // network/CORS failure from crashing React with a hook-order violation.
  if (isUnreachable(activitiesQ.error)) return <ApiUnreachable onRetry={() => void activitiesQ.refetch()} retrying={activitiesQ.isFetching} />;

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

    <section className="panel engagement-template-panel" aria-label="Versioned email templates">
      <header className="engagement-template-head">
        <div><span className="eyebrow">Reusable engagement</span><h2>Email template library</h2><p>Every edit creates a new immutable version, so sent-message evidence stays exact.</p></div>
        <button className="btn btn-sm" onClick={() => setShowTemplates((value) => !value)}>{showTemplates ? "Hide templates" : "Manage templates"}</button>
      </header>
      {showTemplates && <>
        <div className="template-create-grid">
          <input value={templateDraft.apiName} onChange={(event) => setTemplateDraft((value) => ({ ...value, apiName: event.target.value.toLowerCase().replace(/[^a-z0-9_]/g, "_") }))} placeholder="API name, e.g. renewal_follow_up" title="Stable lowercase name used by automation." />
          <input value={templateDraft.name} onChange={(event) => setTemplateDraft((value) => ({ ...value, name: event.target.value }))} placeholder="Template name" title="Plain-language name users will recognize." />
          <input value={templateDraft.folder} onChange={(event) => setTemplateDraft((value) => ({ ...value, folder: event.target.value }))} placeholder="Folder" />
          <select value={templateDraft.sharingScope} onChange={(event) => setTemplateDraft((value) => ({ ...value, sharingScope: event.target.value }))}><option value="PRIVATE">Private</option><option value="TENANT">Whole workspace</option><option value="ROLE">Shared roles</option></select>
          <input className="template-wide" value={templateDraft.subject} onChange={(event) => setTemplateDraft((value) => ({ ...value, subject: event.target.value }))} placeholder="Email subject" />
          <input className="template-wide" value={templateDraft.mergeFields} onChange={(event) => setTemplateDraft((value) => ({ ...value, mergeFields: event.target.value }))} placeholder="Merge fields, comma separated: first_name, account_name" />
          <textarea className="template-wide" value={templateDraft.body} onChange={(event) => setTemplateDraft((value) => ({ ...value, body: event.target.value }))} placeholder="Email body" />
          <button className="btn btn-primary btn-sm" disabled={createTemplate.isPending || !templateDraft.apiName || !templateDraft.name || !templateDraft.subject || !templateDraft.body} onClick={() => createTemplate.mutate()}>{createTemplate.isPending ? "Saving..." : "Create template"}</button>
        </div>
        {templatesQ.isLoading && <GridLoader label="Reading template library" rows={3} columns={4} />}
        <div className="template-card-grid">
          {(templatesQ.data ?? []).map((template) => <article className="template-card" key={template.id}>
            <div><strong>{template.name}</strong><span className="chip">v{template.currentVersion}</span></div>
            <small>{template.folder} · {template.sharingScope} · owner {template.ownerName}</small>
            <p>{template.subject}</p>
            <code>{template.mergeFields.length ? template.mergeFields.join(", ") : "No merge fields"}</code>
            {template.canEdit && <button className="btn btn-sm" disabled={reviseTemplate.isPending} onClick={async () => {
              const subject = await dialog.prompt({ title: "Revise Email Template", message: `Enter the subject for the new immutable version of ${template.name}.`, label: "Email Subject", defaultValue: template.subject, required: true, confirmLabel: "Next" });
              if (subject == null) return;
              const body = await dialog.prompt({ title: "Revise Email Body", message: "Update the content for the new version. The previous version remains unchanged.", label: "Email Body", defaultValue: template.body, required: true, multiline: true, confirmLabel: "Next" });
              if (body == null) return;
              const changeNote = await dialog.prompt({ title: "Explain The Revision", message: "Record what changed and why for the audit trail.", label: "Change Note", defaultValue: "Updated after content review.", required: true, multiline: true, confirmLabel: "Create Version" });
              if (changeNote?.trim()) reviseTemplate.mutate({ template, subject, body, changeNote });
            }}>Create new version</button>}
          </article>)}
        </div>
        {templatesQ.isSuccess && templatesQ.data.length === 0 && <p className="empty-note">No visible templates yet. Create the first governed version above.</p>}
      </>}
    </section>

    <section className="list-controls activity-controls" aria-label="Activity search and filters">
      <label><span>Search <InfoTag text="Type words from the subject, notes, or outcome to narrow activities." label="Activity search help" /></span><input value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} placeholder="Subject, notes, outcome" /></label>
      <label><span>Type <InfoTag text="Choose the kind of activity, such as task, call, email log, or note." label="Activity type help" /></span><select value={type} onChange={(event) => { setType(event.target.value); setPage(0); }}><option value="">All types</option>{TYPES.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
      <label><span>Status <InfoTag text="Show only open, completed, or cancelled activities." label="Activity status help" /></span><select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}><option value="">All statuses</option>{STATUSES.map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
      <button className="btn btn-sm" onClick={resetFilters}>Reset</button>
    </section>

    <DataViewFrame
      title="Activity timeline"
      actions={<DataGridToolbar
        gridName="Activity timeline"
        grouped={activeGroupColumns.length > 0}
        groupLabel="Related record"
        onToggleGroup={() => setGroupColumns((value) => value.length > 0 ? [] : ["related"])}
        groupColumns={ACTIVITY_GROUP_COLUMNS.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={groupColumns}
        onGroupColumnsChange={setGroupColumns}
        columnFilters={columnFilters}
        onColumnFiltersChange={setColumnFilters}
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
        <div className="form-title-with-info activity-create-help">
          <span>Create activity</span>
          <InfoTag text="Log a task, call, meeting, note, or email summary against a CRM record." label="Create activity form help" />
        </div>
        <div className="activity-form-grid">
          <select title="Choose whether this is a task, meeting, call, email log, or note." value={draft.activityType} onChange={(event) => setDraft((value) => ({ ...value, activityType: event.target.value }))}>{TYPES.map((value) => <option key={value} value={value}>{value}</option>)}</select>
          <input title="A short title for the work or conversation." value={draft.subject} onChange={(event) => setDraft((value) => ({ ...value, subject: event.target.value }))} placeholder="Subject" />
          <select title="How urgent or important this activity is." value={draft.priority} onChange={(event) => setDraft((value) => ({ ...value, priority: event.target.value }))}>{PRIORITIES.map((value) => <option key={value} value={value}>{value}</option>)}</select>
          <select title="The type of record this activity belongs to." value={draft.relatedEntityType} onChange={(event) => setDraft((value) => ({ ...value, relatedEntityType: event.target.value }))}>{RELATED_TYPES.map((value) => <option key={value} value={value}>{value}</option>)}</select>
          <input title="The internal ID of the account, lead, contact, or opportunity this activity belongs to." value={draft.relatedEntityId} onChange={(event) => setDraft((value) => ({ ...value, relatedEntityId: event.target.value }))} placeholder="Related record UUID" />
          <input title="When this work should be completed." type="datetime-local" value={draft.dueAt} onChange={(event) => setDraft((value) => ({ ...value, dueAt: event.target.value }))} aria-label="Due date" />
          <input title="When Axiom should remind the owner." type="datetime-local" value={draft.reminderAt} onChange={(event) => setDraft((value) => ({ ...value, reminderAt: event.target.value }))} aria-label="Reminder date" />
        </div>
        {draft.activityType === "CALL" && <div className="activity-form-grid call-fields">
          <select title="Whether your team made the call or received it." value={draft.direction} onChange={(event) => setDraft((value) => ({ ...value, direction: event.target.value }))}><option value="OUTBOUND">OUTBOUND</option><option value="INBOUND">INBOUND</option></select>
          <input title="How long the call lasted, in minutes." type="number" min={0} value={draft.durationMinutes} onChange={(event) => setDraft((value) => ({ ...value, durationMinutes: Number(event.target.value) }))} aria-label="Duration minutes" />
          <input title="Short call result, such as CONNECTED or LEFT_MESSAGE." value={draft.disposition} onChange={(event) => setDraft((value) => ({ ...value, disposition: event.target.value.toUpperCase() }))} placeholder="Disposition" />
        </div>}
        <textarea title="Add the useful details: notes, agenda, email summary, or call outcome." value={draft.body} onChange={(event) => setDraft((value) => ({ ...value, body: event.target.value }))} placeholder="Notes, agenda, email summary, or call outcome detail" />
        {/* The button lives in its own actions row, not as a bare child of the
            panel's grid. A grid item stretches to its column by default, so
            directly parented it rendered 1,554px wide — a primary action the
            full width of the workspace, which reads as a banner rather than as
            a button. The row also gives it the same trailing alignment as every
            other form in the product. */}
        <div className="inline-actions activity-create-actions">
          <button className="btn btn-primary btn-sm" disabled={createMutation.isPending} onClick={() => createMutation.mutate()}>{createMutation.isPending ? "Saving..." : "Create activity"}</button>
        </div>
      </section>

      {activitiesQ.isLoading && <GridLoader label="Reading engagement timeline" rows={6} columns={5} />}
      {activitiesQ.isError && <p className="empty-note">Activities failed to load{activitiesQ.error instanceof Error ? `: ${activitiesQ.error.message}` : "."}</p>}
      {/* Timeline cards, so the filters are the grid's header rather than a
          <thead> row — same component and controls as the table grids. */}
      {activitiesQ.isSuccess && <GridFilterHeader
        columns={ACTIVITY_GROUP_COLUMNS.map(({ key, label }) => ({ key, label }))}
        filters={columnFilters}
        onChange={setColumnFilters}
        label="Filter activity columns"
      />}
      {activitiesQ.isSuccess && rows.length === 0 && <p className="empty-note">No activities match the current query.</p>}
      {activeGroupColumns.length > 0 && grouped.map(([group, items]) => <section className="activity-group" key={group}>
        <h2>{group}</h2>
        {items.map((activity) => <article className={`activity-row activity-${activity.activityType.toLowerCase()}`} key={activity.id}>
          <span className={`activity-stripe priority-${activity.priority.toLowerCase()}`} aria-hidden />
          <div className="activity-main">
            <div className="activity-title"><strong>{activity.subject}</strong><span className={`chip chip-${activity.status.toLowerCase()}`}>{activity.status}</span></div>
            <p>{activity.body || activity.outcome || "No notes captured."}</p>
            <small>{activity.activityType} · {activity.priority} · owner {activity.ownerName} · {activity.dueAt ? `due ${formatDate(activity.dueAt)}` : `occurred ${formatDate(activity.occurredAt)}`}</small>
          </div>
          {activity.status !== "COMPLETED" && <button className="btn btn-sm" disabled={completeMutation.isPending} onClick={async () => {
            const outcome = await dialog.prompt({ title: "Complete Activity", message: `Record the outcome of ${activity.subject}.`, label: "Outcome", defaultValue: "Completed", required: true, multiline: true, confirmLabel: "Complete Activity" });
            if (outcome != null) completeMutation.mutate({ id: activity.id, outcome });
          }}>Complete</button>}
        </article>)}
      </section>)}
      {activeGroupColumns.length === 0 && rows.map((activity) => <article className={`activity-row activity-${activity.activityType.toLowerCase()}`} key={activity.id}>
        <span className={`activity-stripe priority-${activity.priority.toLowerCase()}`} aria-hidden />
        <div className="activity-main">
          <div className="activity-title"><strong>{activity.subject}</strong><span className={`chip chip-${activity.status.toLowerCase()}`}>{activity.status}</span></div>
          <p>{activity.body || activity.outcome || "No notes captured."}</p>
          <small>{activity.activityType} · {activity.priority} · {activity.relatedLabel ?? activity.relatedEntityType} · owner {activity.ownerName} · {activity.dueAt ? `due ${formatDate(activity.dueAt)}` : `occurred ${formatDate(activity.occurredAt)}`}</small>
        </div>
        {activity.status !== "COMPLETED" && <button className="btn btn-sm" disabled={completeMutation.isPending} onClick={async () => {
          const outcome = await dialog.prompt({ title: "Complete Activity", message: `Record the outcome of ${activity.subject}.`, label: "Outcome", defaultValue: "Completed", required: true, multiline: true, confirmLabel: "Complete Activity" });
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

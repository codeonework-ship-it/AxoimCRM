import { Fragment, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  api, isUnreachable,
  type ContactDetail, type ContactRequest, type ContactView,
} from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataGridToolbar } from "../components/DataGridToolbar";
import { DataViewFrame } from "../components/DataViewFrame";
import { GridFilterRow } from "../components/GridFilterRow";
import { InfoTag } from "../components/InfoTag";
import { GridLoader, PanelLoader } from "../components/Loaders";
import { canManageMasters } from "../components/MasterToolbar";
import { BulkActionBar } from "../components/BulkActionBar";
import { RecordFormDialog, type RecordField, type RecordFormMode } from "../components/RecordFormDialog";
import { SavedViewBar } from "../components/SavedViewBar";
import { useToasts } from "../components/Toasts";
import { formatDate } from "../lib/format";
import { filterRowsByColumns, groupLabelFor, selectedGroupColumns, sortByGroups, type GroupColumn } from "../lib/gridGrouping";
import { usePersistedGridState } from "../lib/usePersistedGridState";

/**
 * Contacts, at the depth accounts and leads already had.
 *
 * <p>The service behind this had create, update, addresses, channels, optimistic
 * locking and duplicate guarding since V40 — but nothing was wired to a
 * controller, so none of it was reachable and there was no page. This screen is
 * the surface for logic that already existed rather than a new subsystem.
 *
 * <p>Authoring goes through the shared {@code RecordFormDialog} rather than a
 * hand-rolled form, so a conflict, a duplicate warning and an unsaved-changes
 * close behave here exactly as they will on every other object that adopts it.
 */

const STATUSES = ["ACTIVE", "INACTIVE", "DO_NOT_CONTACT"];
const SENIORITIES = ["C_LEVEL", "VP", "DIRECTOR", "MANAGER", "INDIVIDUAL"];

const CONTACT_GROUP_COLUMNS: GroupColumn<ContactDetail>[] = [
  { key: "name", label: "Name", value: (row) => `${row.firstName} ${row.lastName}`.trim() },
  { key: "account", label: "Account", value: (row) => row.accountName },
  { key: "title", label: "Title", value: (row) => row.title },
  { key: "status", label: "Status", value: (row) => row.status },
  { key: "owner", label: "Owner", value: (row) => row.ownerName },
];

/*
 * One entry per rendered column, in render order — not the group list, which is
 * ordered differently and would put each filter under the wrong heading.
 */
const CONTACT_FILTER_COLUMNS = [
  { key: "name", label: "Name" },
  { key: "account", label: "Account" },
  { key: "title", label: "Title" },
  { key: "email", label: "Email" },
  { key: "status", label: "Status" },
  { key: "owner", label: "Owner" },
];

const FIELDS: RecordField<ContactRequest>[] = [
  { key: "firstName", label: "First name", required: true },
  { key: "lastName", label: "Last name", required: true },
  // Cleared on clone: the email identifies the person, and copying it would
  // create the exact duplicate the dedupe engine exists to prevent.
  { key: "email", label: "Work email", kind: "email", clearedOnClone: true,
    help: "Not copied when cloning — a clone is a different person." },
  { key: "title", label: "Job title" },
  { key: "department", label: "Department" },
  { key: "seniority", label: "Seniority", kind: "select",
    options: SENIORITIES.map((value) => ({ value, label: value.replace(/_/g, " ") })) },
  { key: "phone", label: "Phone", kind: "tel" },
  { key: "mobile", label: "Mobile", kind: "tel" },
  { key: "status", label: "Status", kind: "select",
    options: STATUSES.map((value) => ({ value, label: value.replace(/_/g, " ") })) },
];

export function ContactsPage() {
  const { user } = useAuth();
  const toasts = useToasts();
  const queryClient = useQueryClient();
  const [groupColumns, setGroupColumns, columnFilters, setColumnFilters] = usePersistedGridState("contacts");
  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [dialog, setDialog] = useState<{ mode: RecordFormMode; source?: ContactDetail } | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  function toggleRow(id: string) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  const canManage = canManageMasters(user?.role);

  const contactsQ = useQuery({
    queryKey: ["contacts", search, statusFilter],
    queryFn: () => api.contactsFull({ search: search || undefined, status: statusFilter || undefined }),
    retry: 1,
  });

  const viewQ = useQuery({
    queryKey: ["contacts", "view", selectedId],
    queryFn: () => api.contactView(selectedId as string),
    enabled: !!selectedId,
    retry: 1,
  });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ["contacts"] });
  }

  const deleteMutation = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => api.deleteContact(id, reason),
    onSuccess: () => {
      toasts.push("info", "Contact deleted", "The record was soft-deleted and remains auditable.");
      setSelectedId(null);
      refresh();
    },
    onError: (error) => toasts.push("error", "Contact cannot be deleted",
      error instanceof Error ? error.message : "Delete failed."),
  });

  if (isUnreachable(contactsQ.error)) {
    return <ApiUnreachable onRetry={() => void contactsQ.refetch()} retrying={contactsQ.isFetching} />;
  }

  const activeGroupColumns = selectedGroupColumns(CONTACT_GROUP_COLUMNS, groupColumns);
  const filtered = filterRowsByColumns(contactsQ.data ?? [], CONTACT_GROUP_COLUMNS, columnFilters);
  const contacts = sortByGroups(filtered, activeGroupColumns, (row) => `${row.lastName} ${row.firstName}`);

  /*
   * Owner options come from the rows already loaded rather than a second request.
   * It is not the full user list — only people who already own a contact — which
   * is the honest limit of what this page knows. A dedicated user endpoint would
   * be better and is worth doing when transfer moves beyond this screen.
   */
  const owners = [...new Map((contactsQ.data ?? [])
    .filter((row) => row.ownerId && row.ownerName)
    .map((row) => [row.ownerId as string, { id: row.ownerId as string, name: row.ownerName as string }]))
    .values()].sort((a, b) => a.name.localeCompare(b.name));

  const initialValues = useMemo<Partial<ContactRequest>>(() => {
    if (!dialog) return {};
    const source = dialog.source;
    if (dialog.mode === "create" || !source) return { status: "ACTIVE" };
    return {
      firstName: source.firstName,
      lastName: source.lastName,
      // Blank on clone so the field reads as "needs filling", which is what it is.
      email: dialog.mode === "clone" ? "" : source.email ?? "",
      title: source.title ?? "",
      department: source.department ?? "",
      seniority: source.seniority ?? "",
      phone: source.phone ?? "",
      mobile: source.mobile ?? "",
      status: source.status,
    };
  }, [dialog]);

  async function submitRecord(
    values: Partial<ContactRequest>,
    extra: { acknowledgeDuplicates: boolean; duplicateReason: string | null },
  ) {
    const body: ContactRequest = {
      firstName: String(values.firstName ?? "").trim(),
      lastName: String(values.lastName ?? "").trim(),
      email: blank(values.email),
      title: blank(values.title),
      department: blank(values.department),
      seniority: blank(values.seniority),
      phone: blank(values.phone),
      mobile: blank(values.mobile),
      status: blank(values.status),
      acknowledgeDuplicates: extra.acknowledgeDuplicates,
      duplicateReason: extra.duplicateReason,
    };

    if (dialog?.mode === "edit" && dialog.source) {
      // The version that was loaded, not a refetched one: sending a fresh version
      // would defeat the check by guaranteeing it always matches.
      const saved = await api.updateContact(dialog.source.id, dialog.source.version, body);
      toasts.push("info", "Contact saved", `${saved.firstName} ${saved.lastName} is now at version ${saved.version}.`);
    } else if (dialog?.mode === "clone" && dialog.source) {
      const saved = await api.cloneContact(dialog.source.id, body);
      toasts.push("info", "Contact cloned", `${saved.firstName} ${saved.lastName} was created from an existing record.`);
      setSelectedId(saved.id);
    } else {
      const saved = await api.createContact(body);
      toasts.push("info", "Contact created", `${saved.firstName} ${saved.lastName} was added.`);
      setSelectedId(saved.id);
    }
    setDialog(null);
    refresh();
  }

  function remove(row: ContactDetail) {
    const reason = window.prompt(
      `Delete ${row.firstName} ${row.lastName}? The record is soft-deleted and stays auditable.\n\nReason:`,
      "No longer at this company");
    if (reason === null) return;
    deleteMutation.mutate({ id: row.id, reason });
  }

  let previousGroup = "";

  return <>
    <div className="page-head">
      <div>
        <span className="eyebrow">Relationship intelligence</span>
        <h1>Contacts</h1>
        <p>People, reporting lines, engagement history and ownership.</p>
      </div>
      <div className="inline-actions">
        {contactsQ.isSuccess && <span className="count">{contacts.length} shown</span>}
        {canManage && (
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setDialog({ mode: "create" })}>
            New contact
          </button>
        )}
      </div>
    </div>

    <section className="list-controls" aria-label="Contact search and filters">
      <label>
        <span>Search <InfoTag text="Matches name, email or job title." label="Contact search help" /></span>
        <input value={search} onChange={(event) => setSearch(event.target.value)}
          placeholder="Name, email, or title" />
      </label>
      <label>
        <span>Status <InfoTag text="Show only contacts in one lifecycle status." label="Status filter help" /></span>
        <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
          <option value="">All statuses</option>
          {STATUSES.map((value) => <option key={value} value={value}>{value.replace(/_/g, " ")}</option>)}
        </select>
      </label>
      <button className="btn btn-sm" disabled={!search && !statusFilter}
        onClick={() => { setSearch(""); setStatusFilter(""); }}>Reset</button>
    </section>

    <DataViewFrame
      title="Contact directory"
      actions={<>
        <DataGridToolbar
        gridName="Contact directory"
        grouped={activeGroupColumns.length > 0}
        groupLabel="Account"
        onToggleGroup={() => setGroupColumns((value) => value.length > 0 ? [] : ["account"])}
        groupColumns={CONTACT_GROUP_COLUMNS.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={groupColumns}
        onGroupColumnsChange={setGroupColumns}
        columnFilters={columnFilters}
        auditEntityType="CONTACT"
        exportFilename="contacts-current-view"
        exportRows={contacts.map((row) => ({
          name: `${row.firstName} ${row.lastName}`,
          account: row.accountName ?? "",
          title: row.title ?? "",
          email: row.email ?? "",
          status: row.status,
          owner: row.ownerName ?? "",
        }))}
      />
        <SavedViewBar
          gridKey="contacts"
          currentState={{ groupColumns, columnFilters }}
          onApply={(definition) => {
            setGroupColumns(definition.groupColumns ?? []);
            setColumnFilters(definition.columnFilters ?? {});
          }}
        />
        {canManage && <BulkActionBar
          objectType="CONTACT"
          selectedIds={[...selected]}
          onClearSelection={() => setSelected(new Set())}
          onApplied={() => { setSelected(new Set()); refresh(); }}
          owners={owners}
        />}
      </>}
    >
      {contactsQ.isLoading && <GridLoader label="Reading contact directory" rows={6} columns={6} />}
      {contactsQ.isError && <p className="empty-note">
        Contacts failed to load{contactsQ.error instanceof Error ? `: ${contactsQ.error.message}` : "."}
      </p>}
      {contactsQ.isSuccess && <div className="table-wrap"><table className="data-table">
        <thead>
          <tr>
            {canManage && <th className="select-cell">
              {/* Select-all covers the FILTERED rows, not every record in the
                  workspace. Selecting rows the user cannot see is how a bulk edit
                  reaches records they never intended to touch. */}
              <input
                type="checkbox"
                aria-label={`Select all ${contacts.length} visible contacts`}
                checked={contacts.length > 0 && contacts.every((row) => selected.has(row.id))}
                onChange={(event) => setSelected(event.target.checked
                  ? new Set(contacts.map((row) => row.id))
                  : new Set())}
              />
            </th>}
            <th>Name</th><th>Account</th><th>Title</th><th>Email</th><th>Status</th><th>Owner</th>
            <th className="table-action">Action</th>
          </tr>
          <GridFilterRow
            columns={CONTACT_FILTER_COLUMNS}
            filters={columnFilters}
            onChange={setColumnFilters}
            leading={canManage ? 1 : 0}
            trailing={1}
          />
        </thead>
        <tbody>
          {contacts.map((row) => {
            const group = activeGroupColumns.length > 0 ? groupLabelFor(row, activeGroupColumns) : "";
            const showGroup = activeGroupColumns.length > 0 && group !== previousGroup;
            previousGroup = group;
            return <Fragment key={row.id}>
              {showGroup && <tr className="group-row"><th colSpan={canManage ? 8 : 7}>{group}</th></tr>}
              <tr className={selected.has(row.id) ? "is-selected" : undefined}>
                {canManage && <td className="select-cell">
                  <input
                    type="checkbox"
                    aria-label={`Select ${row.firstName} ${row.lastName}`}
                    checked={selected.has(row.id)}
                    onChange={() => toggleRow(row.id)}
                  />
                </td>}
                <td>{row.firstName} {row.lastName}</td>
                <td>{row.accountName ?? "-"}</td>
                <td>{row.title ?? "-"}</td>
                <td>
                  {row.email ?? "-"}
                  {row.emailBounced && <span className="chip chip-cancelled" title="Mail to this address bounced">BOUNCED</span>}
                </td>
                <td><span className={`chip chip-${row.status.toLowerCase()}`}>{row.status.replace(/_/g, " ")}</span></td>
                <td>{row.ownerName ?? "-"}</td>
                <td className="table-action">
                  <button className="link-btn" onClick={() => setSelectedId(row.id)}>Open</button>
                  {canManage && <button className="link-btn" onClick={() => setDialog({ mode: "edit", source: row })}>Edit</button>}
                  {canManage && <button className="link-btn" onClick={() => setDialog({ mode: "clone", source: row })}>Clone</button>}
                  {canManage && <button className="link-btn danger-link" disabled={deleteMutation.isPending}
                    onClick={() => remove(row)}>Delete</button>}
                </td>
              </tr>
            </Fragment>;
          })}
          {contacts.length === 0 && <tr>
            <td colSpan={canManage ? 8 : 7} className="empty-note">No contacts match the current query.</td>
          </tr>}
        </tbody>
      </table></div>}
    </DataViewFrame>

    <ContactDrawer
      view={viewQ.data}
      loading={viewQ.isLoading}
      error={viewQ.isError}
      onClose={() => setSelectedId(null)}
      onEdit={(contact) => setDialog({ mode: "edit", source: contact })}
      canManage={canManage}
    />

    <RecordFormDialog<ContactRequest>
      open={!!dialog}
      mode={dialog?.mode ?? "create"}
      objectLabel="contact"
      fields={FIELDS}
      initial={initialValues}
      onClose={() => setDialog(null)}
      onSubmit={submitRecord}
    />
  </>;
}

function blank(value: unknown): string | null {
  const text = String(value ?? "").trim();
  return text.length === 0 ? null : text;
}

/**
 * The detail surface: identity, then the related lists that explain the person.
 *
 * <p>Every list renders its own empty state rather than disappearing. A drawer
 * that hides the Timeline heading when there is no engagement is indistinguishable
 * from one that failed to load it, and "no activity recorded" is itself a finding
 * a salesperson wants to see.
 */
function ContactDrawer({ view, loading, error, onClose, onEdit, canManage }: {
  view?: ContactView;
  loading: boolean;
  error: boolean;
  onClose: () => void;
  onEdit: (contact: ContactDetail) => void;
  canManage: boolean;
}) {
  if (!loading && !error && !view) return null;

  return (
    <div className="record-scrim" role="presentation"
      onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <aside className="panel record-drawer" role="dialog" aria-modal="true" aria-label="Contact detail">
        {loading && <PanelLoader label="Reading contact record" />}
        {error && <p className="empty-note">That contact could not be loaded.</p>}
        {view && <>
          <header className="record-drawer-head">
            <div>
              <span className="eyebrow">{view.contact.accountName ?? "No account"}</span>
              <h2>{view.contact.firstName} {view.contact.lastName}</h2>
              <p>{[view.contact.title, view.contact.department].filter(Boolean).join(" · ") || "No title recorded"}</p>
            </div>
            <div className="inline-actions">
              {canManage && <button className="btn btn-sm" onClick={() => onEdit(view.contact)}>Edit</button>}
              <button className="btn btn-sm" onClick={onClose}>Close</button>
            </div>
          </header>

          <dl className="record-facts">
            <div><dt>Email</dt><dd>{view.contact.email ?? "-"}</dd></div>
            <div><dt>Phone</dt><dd>{view.contact.phone ?? "-"}</dd></div>
            <div><dt>Mobile</dt><dd>{view.contact.mobile ?? "-"}</dd></div>
            <div><dt>Owner</dt><dd>{view.contact.ownerName ?? "Unassigned"}</dd></div>
            <div><dt>Reports to</dt><dd>{view.contact.reportsToName ?? "-"}</dd></div>
            <div><dt>Status</dt><dd>{view.contact.status.replace(/_/g, " ")}</dd></div>
            <div><dt>Last engaged</dt><dd>{view.contact.lastEngagedAt ? formatDate(view.contact.lastEngagedAt) : "Never"}</dd></div>
            <div><dt>Version</dt><dd className="mono">{view.contact.version}</dd></div>
          </dl>

          <RelatedList title="Direct reports" count={view.directReports.length}
            empty="Nobody reports to this contact.">
            {view.directReports.map((report) => (
              <li key={report.id}>
                <strong>{report.firstName} {report.lastName}</strong>
                <small>{report.title ?? "No title"}</small>
              </li>
            ))}
          </RelatedList>

          <RelatedList title="Engagement timeline" count={view.timeline.length}
            empty="No calls, emails or meetings are recorded against this contact.">
            {view.timeline.map((entry) => (
              <li key={entry.id}>
                <strong>{entry.subject}</strong>
                <small>
                  {entry.activityType} · {entry.status}
                  {entry.ownerName ? ` · ${entry.ownerName}` : ""}
                  {entry.occurredAt ? ` · ${formatDate(entry.occurredAt)}`
                    : entry.dueAt ? ` · due ${formatDate(entry.dueAt)}` : ""}
                </small>
              </li>
            ))}
          </RelatedList>

          <RelatedList title="Addresses" count={view.addresses.length} empty="No postal address recorded.">
            {view.addresses.map((address) => (
              <li key={address.id}>
                <strong>{[address.line1, address.city, address.countryCode].filter(Boolean).join(", ")}</strong>
                <small>{address.addressType}{address.isPrimary ? " · primary" : ""}</small>
              </li>
            ))}
          </RelatedList>

          <RelatedList title="Communication channels" count={view.channels.length}
            empty="No additional channels recorded.">
            {view.channels.map((channel) => (
              <li key={channel.id}>
                <strong>{channel.value}</strong>
                <small>{channel.channel} · {channel.channelType}{channel.isPrimary ? " · primary" : ""}</small>
              </li>
            ))}
          </RelatedList>
        </>}
      </aside>
    </div>
  );
}

function RelatedList({ title, count, empty, children }: {
  title: string; count: number; empty: string; children: React.ReactNode;
}) {
  return (
    <section className="related-list" aria-label={title}>
      <header>
        <h3>{title}</h3>
        <span className="chip">{count}</span>
      </header>
      {count === 0 ? <p className="empty-note">{empty}</p> : <ul>{children}</ul>}
    </section>
  );
}

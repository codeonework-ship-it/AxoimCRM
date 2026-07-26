import { Fragment, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isUnreachable, type Lead } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataViewFrame } from "../components/DataViewFrame";
import { InfoTag } from "../components/InfoTag";
import { MasterToolbar, canManageMasters } from "../components/MasterToolbar";
import { useToasts } from "../components/Toasts";
import { GridLoader } from "../components/Loaders";
import { filterRowsByColumns, groupLabelFor, selectedGroupColumns, sortByGroups, type GroupColumn } from "../lib/gridGrouping";
import { usePersistedGridState } from "../lib/usePersistedGridState";

const CONVERTIBLE = new Set(["NEW", "QUALIFIED"]);
const STATUSES = ["NEW", "WORKING", "NURTURING", "QUALIFIED", "CONVERTED", "DISQUALIFIED"];
const LEAD_GROUP_COLUMNS: GroupColumn<Lead>[] = [
  { key: "name", label: "Name", value: (row) => row.name },
  { key: "company", label: "Company", value: (row) => row.company },
  { key: "status", label: "Status", value: (row) => row.status },
  { key: "owner", label: "Owner", value: (row) => row.ownerName },
];

export function LeadsPage() {
  const [groupColumns, setGroupColumns, columnFilters, setColumnFilters] = usePersistedGridState("leads");
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const queryClient = useQueryClient();
  const toasts = useToasts();
  const { user } = useAuth();

  const leadsQ = useQuery({
    queryKey: ["leads", page, search, statusFilter],
    queryFn: () => api.leads({ page, search, filter: statusFilter }),
    retry: 1,
  });
  const convertMutation = useMutation({ mutationFn: (leadId: string) => api.convertLead(leadId) });
  const disqualifyMutation = useMutation({
    mutationFn: ({ id, reasonCode, note, recycleDate }: { id: string; reasonCode: string; note?: string | null; recycleDate?: string | null }) =>
      api.disqualifyLead(id, { reasonCode, note, recycleDate }),
    onSuccess: () => {
      toasts.push("info", "Lead disqualified", "Reason and recycle timing were captured for audit.");
      void queryClient.invalidateQueries({ queryKey: ["leads"] });
      void queryClient.invalidateQueries({ queryKey: ["notifications"] });
    },
    onError: (error) => toasts.push("error", "Disqualify failed", error instanceof Error ? error.message : "Update failed."),
  });
  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.deleteMaster("leads", id),
    onSuccess: () => {
      toasts.push("info", "Lead deleted", "The record was soft-deleted and remains auditable.");
      void queryClient.invalidateQueries({ queryKey: ["leads"] });
    },
    onError: (error) => toasts.push("error", "Lead cannot be deleted", error instanceof Error ? error.message : "Delete failed."),
  });

  function convert(lead: Lead) {
    convertMutation.mutate(lead.id, {
      onSuccess: () => {
        toasts.push("info", "Lead converted", `${lead.name} is now an account, contact and opportunity.`);
        ["leads", "accounts", "notifications"].forEach((key) => void queryClient.invalidateQueries({ queryKey: [key] }));
        void queryClient.invalidateQueries({ queryKey: ["pipeline", "board"] });
        void queryClient.invalidateQueries({ queryKey: ["dashboard", "summary"] });
      },
      onError: (error) => toasts.push("error", "Convert failed", isUnreachable(error) ? "API unreachable - lead not converted." : error instanceof Error ? error.message : "Unknown error."),
    });
  }

  function disqualify(lead: Lead) {
    const reasonCode = window.prompt("Disqualification reason code", "NOT_A_FIT");
    if (!reasonCode) return;
    const recycleDate = window.prompt("Recycle date (YYYY-MM-DD, optional)", "");
    const note = window.prompt("Short note (optional)", "");
    disqualifyMutation.mutate({
      id: lead.id,
      reasonCode,
      recycleDate: recycleDate?.trim() ? recycleDate.trim() : null,
      note: note?.trim() ? note.trim() : null,
    });
  }

  if (isUnreachable(leadsQ.error)) return <ApiUnreachable onRetry={() => void leadsQ.refetch()} retrying={leadsQ.isFetching} />;

  const activeGroupColumns = selectedGroupColumns(LEAD_GROUP_COLUMNS, groupColumns);
  const filteredLeads = leadsQ.data ? filterRowsByColumns(leadsQ.data.items, LEAD_GROUP_COLUMNS, columnFilters) : [];
  const leads = sortByGroups(filteredLeads, activeGroupColumns, (row) => row.name);
  const total = leadsQ.data?.total ?? 0;
  const totalPages = leadsQ.data?.totalPages ?? 0;
  let previousGroup = "";

  function updateSearch(value: string) { setSearch(value); setPage(0); }
  function updateStatus(value: string) { setStatusFilter(value); setPage(0); }
  function resetFilters() { setSearch(""); setStatusFilter(""); setPage(0); }

  return <>
    <div className="page-head"><div><span className="eyebrow">Demand operations</span><h1>Leads</h1><p>Qualify intent and convert cleanly into revenue records.</p></div>{leadsQ.isSuccess && <span className="count">{total} total</span>}</div>
    <section className="list-controls" aria-label="Lead search and filters">
      <label><span>Search <InfoTag text="Type a name, company, email, or owner to narrow the lead queue." label="Lead search help" /></span><input value={search} onChange={(event) => updateSearch(event.target.value)} placeholder="Name, company, email, or owner" /></label>
      <label><span>Status filter <InfoTag text="Choose a lead status to focus the list on one stage of work." label="Lead status filter help" /></span><select value={statusFilter} onChange={(event) => updateStatus(event.target.value)}>
        <option value="">All statuses</option>
        {STATUSES.map((status) => <option value={status} key={status}>{status}</option>)}
      </select></label>
      <button className="btn btn-sm" onClick={resetFilters} disabled={!search && !statusFilter}>Reset</button>
    </section>
    <DataViewFrame
      title="Lead queue"
      actions={<MasterToolbar
        master="leads"
        entityType="LEAD"
        search={search}
        filter={statusFilter}
        grouped={activeGroupColumns.length > 0}
        groupLabel="Status"
        onToggleGroup={() => setGroupColumns((value) => value.length > 0 ? [] : ["status"])}
        groupColumns={LEAD_GROUP_COLUMNS.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={groupColumns}
        onGroupColumnsChange={setGroupColumns}
        filterColumns={LEAD_GROUP_COLUMNS.map(({ key, label }) => ({ key, label }))}
        columnFilters={columnFilters}
        onColumnFiltersChange={setColumnFilters}
        exportFilename="leads-current-view"
        exportRows={leads.map((lead) => ({
          name: lead.name,
          company: lead.company ?? "",
          email: lead.email ?? "",
          status: lead.status,
          owner: lead.ownerName ?? "",
        }))}
        onChanged={() => void leadsQ.refetch()}
      />}
    >
      {leadsQ.isLoading && <GridLoader label="Reading lead queue" rows={6} columns={4} />}
      {leadsQ.isError && <p className="empty-note">Leads failed to load{leadsQ.error instanceof Error ? `: ${leadsQ.error.message}` : "."}</p>}
      {leadsQ.isSuccess && leads.length === 0 && <p className="empty-note">No leads match the current query.</p>}
      {leads.map((lead) => { const group = activeGroupColumns.length > 0 ? groupLabelFor(lead, activeGroupColumns) : ""; const showGroup = activeGroupColumns.length > 0 && group !== previousGroup; previousGroup = group; return <Fragment key={lead.id}>
        {showGroup && <div className="lead-group">{group}</div>}<div className="lead-row"><div><div className="lead-name">{lead.name}</div><div className="lead-meta">{[lead.company, lead.email, lead.ownerName].filter(Boolean).join(" - ") || "-"}</div></div>
        <span className={`chip chip-${lead.status.toLowerCase()}`} style={{ marginLeft: "auto" }}>{lead.status}</span>
        {CONVERTIBLE.has(lead.status) && <button className="btn btn-sm" onClick={() => convert(lead)} disabled={convertMutation.isPending && convertMutation.variables === lead.id}>{convertMutation.isPending && convertMutation.variables === lead.id ? "Converting..." : "Convert"}</button>}
        {CONVERTIBLE.has(lead.status) && <button className="btn btn-sm" onClick={() => disqualify(lead)} disabled={disqualifyMutation.isPending}>{disqualifyMutation.isPending && disqualifyMutation.variables?.id === lead.id ? "Disqualifying..." : "Disqualify"}</button>}
        {canManageMasters(user?.role) && <button className="link-btn danger-link" disabled={deleteMutation.isPending} onClick={() => { if (window.confirm(`Delete ${lead.name}? Converted or in-use records will be protected.`)) deleteMutation.mutate(lead.id); }}>Delete</button>}
      </div></Fragment>; })}
      {leadsQ.isSuccess && <footer className="page-controls" aria-label="Lead pagination">
        <span>Showing {leads.length} of {total} records - 100 rows per page</span>
        <div>
          <button className="btn btn-sm" disabled={page === 0 || leadsQ.isFetching} onClick={() => setPage((value) => Math.max(value - 1, 0))}>Previous</button>
          <strong>Page {totalPages === 0 ? 0 : page + 1} of {totalPages}</strong>
          <button className="btn btn-sm" disabled={page + 1 >= totalPages || leadsQ.isFetching} onClick={() => setPage((value) => value + 1)}>Next</button>
        </div>
      </footer>}
    </DataViewFrame>
  </>;
}

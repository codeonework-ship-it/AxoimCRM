import { Fragment, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isUnreachable, type Lead } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { MasterToolbar, canManageMasters } from "../components/MasterToolbar";
import { useToasts } from "../components/Toasts";

const CONVERTIBLE = new Set(["NEW", "QUALIFIED"]);
const STATUSES = ["NEW", "WORKING", "NURTURING", "QUALIFIED", "CONVERTED", "DISQUALIFIED"];

export function LeadsPage() {
  const [grouped, setGrouped] = useState(false);
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

  if (isUnreachable(leadsQ.error)) return <ApiUnreachable onRetry={() => void leadsQ.refetch()} retrying={leadsQ.isFetching} />;

  const leads = leadsQ.data ? [...leadsQ.data.items].sort((a, b) => grouped ? a.status.localeCompare(b.status) || a.name.localeCompare(b.name) : a.name.localeCompare(b.name)) : [];
  const total = leadsQ.data?.total ?? 0;
  const totalPages = leadsQ.data?.totalPages ?? 0;
  let previousGroup = "";

  function updateSearch(value: string) { setSearch(value); setPage(0); }
  function updateStatus(value: string) { setStatusFilter(value); setPage(0); }
  function resetFilters() { setSearch(""); setStatusFilter(""); setPage(0); }

  return <>
    <div className="page-head"><div><span className="eyebrow">Demand operations</span><h1>Leads</h1><p>Qualify intent and convert cleanly into revenue records.</p></div>{leadsQ.isSuccess && <span className="count">{total} total</span>}</div>
    <section className="list-controls" aria-label="Lead search and filters">
      <label><span>Search</span><input value={search} onChange={(event) => updateSearch(event.target.value)} placeholder="Name, company, email, or owner" /></label>
      <label><span>Status filter</span><select value={statusFilter} onChange={(event) => updateStatus(event.target.value)}>
        <option value="">All statuses</option>
        {STATUSES.map((status) => <option value={status} key={status}>{status}</option>)}
      </select></label>
      <button className="btn btn-sm" onClick={resetFilters} disabled={!search && !statusFilter}>Reset</button>
    </section>
    <MasterToolbar master="leads" entityType="LEAD" search={search} filter={statusFilter} grouped={grouped} groupLabel="Status" onToggleGroup={() => setGrouped((value) => !value)} onChanged={() => void leadsQ.refetch()} />
    {leadsQ.isLoading && <p className="loading-note">Loading leads...</p>}
    {leadsQ.isError && <p className="empty-note">Leads failed to load{leadsQ.error instanceof Error ? `: ${leadsQ.error.message}` : "."}</p>}
    {leadsQ.isSuccess && leads.length === 0 && <p className="empty-note">No leads match the current query.</p>}
    {leads.map((lead) => { const showGroup = grouped && lead.status !== previousGroup; previousGroup = lead.status; return <Fragment key={lead.id}>
      {showGroup && <div className="lead-group">{lead.status}</div>}<div className="lead-row"><div><div className="lead-name">{lead.name}</div><div className="lead-meta">{[lead.company, lead.email, lead.ownerName].filter(Boolean).join(" - ") || "-"}</div></div>
      <span className={`chip chip-${lead.status.toLowerCase()}`} style={{ marginLeft: "auto" }}>{lead.status}</span>
      {CONVERTIBLE.has(lead.status) && <button className="btn btn-sm" onClick={() => convert(lead)} disabled={convertMutation.isPending && convertMutation.variables === lead.id}>{convertMutation.isPending && convertMutation.variables === lead.id ? "Converting..." : "Convert"}</button>}
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
  </>;
}

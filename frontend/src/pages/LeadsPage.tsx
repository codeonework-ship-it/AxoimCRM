import { Fragment, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isUnreachable, type Lead, type LeadBatchResult, type LeadIngestRequest } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataViewFrame } from "../components/DataViewFrame";
import { InfoTag } from "../components/InfoTag";
import { MasterToolbar, canManageMasters } from "../components/MasterToolbar";
import { useToasts } from "../components/Toasts";
import { GridLoader } from "../components/Loaders";
import { GridFilterHeader } from "../components/GridFilterRow";
import { filterRowsByColumns, groupLabelFor, selectedGroupColumns, sortByGroups, type GroupColumn } from "../lib/gridGrouping";
import { usePersistedGridState } from "../lib/usePersistedGridState";
import { useAppDialog } from "../components/AppDialog";
import { useGridDataLoad } from "../components/PageDataGate";

const CONVERTIBLE = new Set(["NEW", "QUALIFIED"]);
const STATUSES = ["NEW", "WORKING", "NURTURING", "QUALIFIED", "CONVERTED", "DISQUALIFIED"];
const LEAD_GROUP_COLUMNS: GroupColumn<Lead>[] = [
  { key: "name", label: "Name", value: (row) => row.name },
  { key: "company", label: "Company", value: (row) => row.company },
  { key: "status", label: "Status", value: (row) => row.status },
  { key: "owner", label: "Owner", value: (row) => row.ownerName },
];

export function LeadsPage() {
  const leadsGrid = useGridDataLoad("Lead queue");
  const [groupColumns, setGroupColumns, columnFilters, setColumnFilters] = usePersistedGridState("leads");
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const queryClient = useQueryClient();
  const toasts = useToasts();
  const dialog = useAppDialog();
  const { user } = useAuth();
  const [captureOpen, setCaptureOpen] = useState(false);
  const [leadDraft, setLeadDraft] = useState<LeadIngestRequest>({ firstName: "", lastName: "", company: "", email: "", source: "MANUAL" });
  const [bulkText, setBulkText] = useState("firstName,lastName,company,email,source\n");
  const [bulkResult, setBulkResult] = useState<LeadBatchResult | null>(null);

  const leadsQ = useQuery({
    queryKey: ["leads", page, search, statusFilter],
    queryFn: () => api.leads({ page, search, filter: statusFilter }),
    enabled: leadsGrid.loaded,
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
  const captureMutation = useMutation({
    mutationFn: () => api.captureLead(leadDraft),
    onSuccess: (result) => {
      toasts.push("info", "Lead captured", `${result.status}: ${result.assignment ?? result.message}`);
      setLeadDraft({ firstName: "", lastName: "", company: "", email: "", source: "MANUAL" });
      void queryClient.invalidateQueries({ queryKey: ["leads"] });
      void queryClient.invalidateQueries({ queryKey: ["notifications"] });
    },
    onError: (error) => toasts.push("error", "Lead was not captured", error instanceof Error ? error.message : "Capture failed."),
  });
  const bulkMutation = useMutation({
    mutationFn: (rows: LeadIngestRequest[]) => api.bulkCaptureLeads(rows),
    onSuccess: (result) => {
      setBulkResult(result);
      toasts.push(result.rejected ? "warn" : "info", "Bulk capture completed", result.note);
      void queryClient.invalidateQueries({ queryKey: ["leads"] });
    },
    onError: (error) => toasts.push("error", "Bulk capture failed", error instanceof Error ? error.message : "Upload failed."),
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

  async function disqualify(lead: Lead) {
    const reasonCode = await dialog.prompt({ title: "Disqualify Lead", message: `Choose the reason code for disqualifying ${lead.name}.`, label: "Reason Code", defaultValue: "NOT_A_FIT", required: true, confirmLabel: "Next" });
    if (!reasonCode) return;
    const recycleDate = await dialog.prompt({ title: "Recycle Timing", message: "Optionally enter when this lead can return to the qualification queue.", label: "Recycle Date (YYYY-MM-DD)", placeholder: "YYYY-MM-DD", confirmLabel: "Next" });
    if (recycleDate === null) return;
    const note = await dialog.prompt({ title: "Disqualification Note", message: "Optionally record a short explanation for the lead history.", label: "Note", multiline: true, confirmLabel: "Disqualify Lead", tone: "danger" });
    if (note === null) return;
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
  function submitBulk() {
    try {
      setBulkResult(null);
      bulkMutation.mutate(parseLeadCsv(bulkText));
    } catch (error) {
      toasts.push("error", "CSV needs correction", error instanceof Error ? error.message : "The file text is invalid.");
    }
  }

  return <>
    <div className="page-head"><div><span className="eyebrow">Demand operations</span><h1>Leads</h1><p>Qualify intent and convert cleanly into revenue records.</p></div><div className="inline-actions">
      {leadsQ.isSuccess && <span className="count">{total} total</span>}
      <button type="button" className="btn btn-primary btn-sm" onClick={() => setCaptureOpen((value) => !value)}>{captureOpen ? "Close capture" : "Capture leads"}</button>
    </div></div>
    {captureOpen && <section className="panel lead-capture-panel" aria-label="Lead capture">
      <div><span className="eyebrow">Single lead</span><h2>Capture and route now</h2><p>Scoring, duplicate handling, assignment and response SLA run automatically.</p></div>
      <div className="list-controls">
        <label><span>First name</span><input value={leadDraft.firstName} onChange={(e) => setLeadDraft((v) => ({ ...v, firstName: e.target.value }))} /></label>
        <label><span>Last name</span><input value={leadDraft.lastName} onChange={(e) => setLeadDraft((v) => ({ ...v, lastName: e.target.value }))} /></label>
        <label><span>Company</span><input value={leadDraft.company} onChange={(e) => setLeadDraft((v) => ({ ...v, company: e.target.value }))} /></label>
        <label><span>Work email</span><input type="email" value={leadDraft.email ?? ""} onChange={(e) => setLeadDraft((v) => ({ ...v, email: e.target.value }))} /></label>
        <button type="button" className="btn btn-primary btn-sm" disabled={captureMutation.isPending || !leadDraft.firstName || !leadDraft.lastName || !leadDraft.company}
          onClick={() => captureMutation.mutate()}>{captureMutation.isPending ? "Capturing..." : "Capture lead"}</button>
      </div>
      <div className="lead-bulk-capture"><div><span className="eyebrow">Bulk API</span><h3>Paste up to 1,000 CSV rows</h3>
        <p>Valid rows commit independently. Every rejected row comes back with its own correction message.</p></div>
        <textarea rows={7} value={bulkText} onChange={(e) => setBulkText(e.target.value)} aria-label="Lead CSV rows" />
        <button type="button" className="btn btn-sm" disabled={bulkMutation.isPending} onClick={submitBulk}>{bulkMutation.isPending ? "Processing rows..." : "Process CSV"}</button>
        {bulkResult && <div className="panel inline-result" role="status"><strong>{bulkResult.accepted} accepted · {bulkResult.rejected} rejected</strong><span>{bulkResult.note}</span>
          {bulkResult.rows.filter((row) => row.status === "REJECTED").map((row) => <small key={row.rowNumber}>Row {row.rowNumber}: {row.errors.join(" ")}</small>)}</div>}
      </div>
    </section>}
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
      {/* The queue renders rows as cards, so there is no <thead> to hold the
          filters. This is the grid's header instead: same component, same
          controls, inside the grid frame rather than up in the toolbar. */}
      {leadsQ.isSuccess && <GridFilterHeader
        columns={LEAD_GROUP_COLUMNS.map(({ key, label }) => ({ key, label }))}
        filters={columnFilters}
        onChange={setColumnFilters}
        label="Filter lead columns"
      />}
      {leadsQ.isSuccess && leads.length === 0 && <p className="empty-note">No leads match the current query.</p>}
      {leads.map((lead) => { const group = activeGroupColumns.length > 0 ? groupLabelFor(lead, activeGroupColumns) : ""; const showGroup = activeGroupColumns.length > 0 && group !== previousGroup; previousGroup = group; return <Fragment key={lead.id}>
        {showGroup && <div className="lead-group">{group}</div>}<div className="lead-row"><div><div className="lead-name">{lead.name}</div><div className="lead-meta">{[lead.company, lead.email, lead.ownerName].filter(Boolean).join(" - ") || "-"}</div></div>
        <span className={`chip chip-${lead.status.toLowerCase()}`} style={{ marginLeft: "auto" }}>{lead.status}</span>
        {CONVERTIBLE.has(lead.status) && <button className="btn btn-sm" onClick={() => convert(lead)} disabled={convertMutation.isPending && convertMutation.variables === lead.id}>{convertMutation.isPending && convertMutation.variables === lead.id ? "Converting..." : "Convert"}</button>}
        {CONVERTIBLE.has(lead.status) && <button className="btn btn-sm" onClick={() => disqualify(lead)} disabled={disqualifyMutation.isPending}>{disqualifyMutation.isPending && disqualifyMutation.variables?.id === lead.id ? "Disqualifying..." : "Disqualify"}</button>}
        {canManageMasters(user?.role) && <button className="link-btn danger-link" disabled={deleteMutation.isPending} onClick={async () => {
          const confirmed = await dialog.confirm({ title: "Delete Lead", message: `Delete ${lead.name}? Converted or in-use records will be protected.`, confirmLabel: "Delete Lead", tone: "danger" });
          if (confirmed) deleteMutation.mutate(lead.id);
        }}>Delete</button>}
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

function parseLeadCsv(text: string): LeadIngestRequest[] {
  const lines = text.split(/\r?\n/).filter((line) => line.trim());
  if (lines.length < 2) throw new Error("Keep the header row and add at least one lead row beneath it.");
  const parseLine = (line: string): string[] => {
    const values: string[] = [];
    let value = "";
    let quoted = false;
    for (let index = 0; index < line.length; index += 1) {
      const char = line[index];
      if (char === '"' && quoted && line[index + 1] === '"') { value += '"'; index += 1; }
      else if (char === '"') quoted = !quoted;
      else if (char === "," && !quoted) { values.push(value.trim()); value = ""; }
      else value += char;
    }
    if (quoted) throw new Error("A quoted CSV value is missing its closing quote.");
    values.push(value.trim());
    return values;
  };
  const headers = parseLine(lines[0]).map((header) => header.trim());
  ["firstName", "lastName", "company"].forEach((required) => {
    if (!headers.includes(required)) throw new Error(`The CSV header must include ${required}.`);
  });
  if (lines.length - 1 > 1000) throw new Error("A batch can contain at most 1,000 lead rows.");
  return lines.slice(1).map((line) => {
    const values = parseLine(line);
    const row = Object.fromEntries(headers.map((header, index) => [header, values[index] ?? ""]));
    return {
      firstName: row.firstName,
      lastName: row.lastName,
      company: row.company,
      email: row.email || null,
      phone: row.phone || null,
      title: row.title || null,
      source: row.source || "BULK_API",
      campaignCode: row.campaignCode || null,
      territory: row.territory || null,
      segment: row.segment || null,
      productInterest: row.productInterest || null,
    };
  });
}

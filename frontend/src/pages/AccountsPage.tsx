import { Fragment, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isUnreachable, type Account, type AccountDetail, type AccountHealth, type AccountHierarchy, type AccountRollup } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataViewFrame } from "../components/DataViewFrame";
import { GridFilterRow } from "../components/GridFilterRow";
import { InfoTag } from "../components/InfoTag";
import { MasterToolbar, canManageMasters } from "../components/MasterToolbar";
import { useToasts } from "../components/Toasts";
import { GridLoader } from "../components/Loaders";
import { filterRowsByColumns, groupLabelFor, selectedGroupColumns, sortByGroups, type GroupColumn } from "../lib/gridGrouping";
import { usePersistedGridState } from "../lib/usePersistedGridState";

const ACCOUNT_GROUP_COLUMNS: GroupColumn<Account>[] = [
  { key: "name", label: "Name", value: (row) => row.name },
  { key: "industry", label: "Industry", value: (row) => row.industry },
  { key: "owner", label: "Owner", value: (row) => row.ownerName },
];

export function AccountsPage() {
  const [groupColumns, setGroupColumns, columnFilters, setColumnFilters] = usePersistedGridState("accounts");
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [industryFilter, setIndustryFilter] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const toasts = useToasts();

  const accountsQ = useQuery({
    queryKey: ["accounts", page, search, industryFilter],
    queryFn: () => api.accounts({ page, search, filter: industryFilter }),
    retry: 1,
  });
  const detailQ = useQuery({
    queryKey: ["accounts", selectedId, "detail"],
    queryFn: () => api.account(selectedId as string),
    enabled: !!selectedId,
    retry: 1,
  });
  const hierarchyQ = useQuery({
    queryKey: ["accounts", selectedId, "hierarchy"],
    queryFn: () => api.accountHierarchy(selectedId as string),
    enabled: !!selectedId,
    retry: 1,
  });
  const rollupQ = useQuery({
    queryKey: ["accounts", selectedId, "rollup"],
    queryFn: () => api.accountRollup(selectedId as string),
    enabled: !!selectedId,
    retry: 1,
  });
  const healthQ = useQuery({
    queryKey: ["accounts", selectedId, "health"],
    queryFn: () => api.accountHealth(selectedId as string),
    enabled: !!selectedId,
    retry: 1,
  });
  const recomputeHealth = useMutation({
    mutationFn: (id: string) => api.recomputeAccountHealth(id),
    onSuccess: (health) => {
      queryClient.setQueryData(["accounts", selectedId, "health"], health);
      toasts.push("info", "Account health refreshed", health.changeExplanation);
      void queryClient.invalidateQueries({ queryKey: ["accounts"] });
      void queryClient.invalidateQueries({ queryKey: ["accounts", selectedId, "detail"] });
    },
    onError: (error) => toasts.push("error", "Health could not be refreshed", error instanceof Error ? error.message : "Refresh failed."),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.deleteMaster("accounts", id),
    onSuccess: () => {
      toasts.push("info", "Account deleted", "The record was soft-deleted and remains auditable.");
      void queryClient.invalidateQueries({ queryKey: ["accounts"] });
    },
    onError: (error) => toasts.push("error", "Account cannot be deleted", error instanceof Error ? error.message : "Delete failed."),
  });

  if (isUnreachable(accountsQ.error)) return <ApiUnreachable onRetry={() => void accountsQ.refetch()} retrying={accountsQ.isFetching} />;

  const activeGroupColumns = selectedGroupColumns(ACCOUNT_GROUP_COLUMNS, groupColumns);
  const filteredAccounts = accountsQ.data ? filterRowsByColumns(accountsQ.data.items, ACCOUNT_GROUP_COLUMNS, columnFilters) : [];
  const accounts = sortByGroups(filteredAccounts, activeGroupColumns, (row) => row.name);
  const total = accountsQ.data?.total ?? 0;
  const totalPages = accountsQ.data?.totalPages ?? 0;
  let previousGroup = "";

  function remove(account: Account) {
    if (window.confirm(`Delete ${account.name}? This is a reversible soft delete. Records in use will be protected.`)) deleteMutation.mutate(account.id);
  }

  function updateSearch(value: string) { setSearch(value); setPage(0); }
  function updateIndustry(value: string) { setIndustryFilter(value); setPage(0); }
  function resetFilters() { setSearch(""); setIndustryFilter(""); setPage(0); }

  return <>
    <div className="page-head"><div><span className="eyebrow">Customer intelligence</span><h1>Accounts</h1><p>Organizations, ownership, and relationship context.</p></div>
      {accountsQ.isSuccess && <span className="count">{total} total</span>}</div>
    <section className="list-controls" aria-label="Account search and filters">
      <label><span>Search <InfoTag text="Type part of a name, industry, or owner to narrow the account list." label="Account search help" /></span><input value={search} onChange={(event) => updateSearch(event.target.value)} placeholder="Name, industry, or owner" /></label>
      <label><span>Industry filter <InfoTag text="Enter one industry to show only accounts in that industry." label="Industry filter help" /></span><input value={industryFilter} onChange={(event) => updateIndustry(event.target.value)} placeholder="Exact industry" /></label>
      <button className="btn btn-sm" onClick={resetFilters} disabled={!search && !industryFilter}>Reset</button>
    </section>
    <DataViewFrame
      title="Accounts results"
      actions={<MasterToolbar
        master="accounts"
        entityType="ACCOUNT"
        search={search}
        filter={industryFilter}
        grouped={activeGroupColumns.length > 0}
        groupLabel="Industry"
        onToggleGroup={() => setGroupColumns((value) => value.length > 0 ? [] : ["industry"])}
        groupColumns={ACCOUNT_GROUP_COLUMNS.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={groupColumns}
        onGroupColumnsChange={setGroupColumns}
        columnFilters={columnFilters}
        onColumnFiltersChange={setColumnFilters}
        exportFilename="accounts-current-view"
        exportRows={accounts.map((account) => ({
          name: account.name,
          industry: account.industry ?? "",
          owner: account.ownerName ?? "",
        }))}
        onChanged={() => void accountsQ.refetch()}
      />}
    >
      {accountsQ.isLoading && <GridLoader label="Reading client register" rows={6} columns={4} />}
      {accountsQ.isError && <p className="empty-note">Accounts failed to load{accountsQ.error instanceof Error ? `: ${accountsQ.error.message}` : "."}</p>}
      {accountsQ.isSuccess && <div className="table-wrap"><table className="data-table"><thead><tr><th>Name</th><th>Industry</th><th>Owner</th><th className="table-action">Action</th></tr>
        {/* Filters live in the header, one per column, so the box you type in is
            always the box above the data it narrows. `trailing` accounts for the
            Action column so the cells do not shift left by one. */}
        <GridFilterRow
          columns={ACCOUNT_GROUP_COLUMNS.map(({ key, label }) => ({ key, label }))}
          filters={columnFilters}
          onChange={setColumnFilters}
          trailing={1}
        /></thead>
      <tbody>{accounts.map((account) => {
        const group = activeGroupColumns.length > 0 ? groupLabelFor(account, activeGroupColumns) : "";
        const showGroup = activeGroupColumns.length > 0 && group !== previousGroup; previousGroup = group;
        return <Fragment key={account.id}>{showGroup && <tr className="group-row"><th colSpan={4}>{group}</th></tr>}
          <tr><td>{account.name}</td><td>{account.industry ?? "-"}</td><td>{account.ownerName ?? "-"}</td>
            <td className="table-action"><button className="link-btn" onClick={() => setSelectedId(account.id)}>View 360</button>{canManageMasters(user?.role) && <button className="link-btn danger-link" disabled={deleteMutation.isPending} onClick={() => remove(account)}>Delete</button>}</td></tr></Fragment>;
      })}{accounts.length === 0 && <tr><td colSpan={4} className="empty-note">No accounts match the current query.</td></tr>}</tbody>
    </table></div>}
      {accountsQ.isSuccess && <footer className="page-controls" aria-label="Account pagination">
        <span>Showing {accounts.length} of {total} records - 100 rows per page</span>
        <div>
          <button className="btn btn-sm" disabled={page === 0 || accountsQ.isFetching} onClick={() => setPage((value) => Math.max(value - 1, 0))}>Previous</button>
          <strong>Page {totalPages === 0 ? 0 : page + 1} of {totalPages}</strong>
          <button className="btn btn-sm" disabled={page + 1 >= totalPages || accountsQ.isFetching} onClick={() => setPage((value) => value + 1)}>Next</button>
        </div>
      </footer>}
    </DataViewFrame>
    <AccountDrawer detail={detailQ.data} hierarchy={hierarchyQ.data} rollup={rollupQ.data}
      health={healthQ.data ?? undefined}
      loading={detailQ.isLoading || hierarchyQ.isLoading || rollupQ.isLoading || healthQ.isLoading}
      error={detailQ.isError || hierarchyQ.isError || rollupQ.isError || healthQ.isError}
      refreshing={recomputeHealth.isPending}
      onRefreshHealth={() => selectedId && recomputeHealth.mutate(selectedId)}
      onClose={() => setSelectedId(null)} />
  </>;
}

function AccountDrawer({ detail, hierarchy, rollup, health, loading, error, refreshing, onRefreshHealth, onClose }: {
  detail?: AccountDetail;
  hierarchy?: AccountHierarchy;
  rollup?: AccountRollup;
  health?: AccountHealth;
  loading: boolean;
  error: boolean;
  refreshing: boolean;
  onRefreshHealth: () => void;
  onClose: () => void;
}) {
  if (!loading && !detail && !error) return null;
  return <div className="drawer-scrim" role="presentation" onMouseDown={onClose}>
    <aside className="audit-drawer account-360-drawer" role="dialog" aria-modal="true" aria-label="Account 360" onMouseDown={(event) => event.stopPropagation()}>
      <header className="drawer-head"><div><span className="eyebrow">Account 360</span><h2>{detail?.name ?? "Loading account"}</h2></div><button className="icon-btn" onClick={onClose} aria-label="Close account 360">×</button></header>
      {loading && <p className="loading-note">Loading relationship, health and ownership context...</p>}
      {error && <p className="empty-note">Account 360 failed to load.</p>}
      {detail && <div className="audit-list">
        <article className="audit-event"><strong>Profile</strong><p>{[detail.recordType, detail.industry, detail.segment, detail.status].filter(Boolean).join(" · ") || "Standard account"}</p><small>Owner {detail.ownerName ?? "-"} · territory {detail.territory ?? "-"}</small></article>
        <article className="audit-event"><strong>Health</strong>
          <p>{health?.band ?? detail.healthBand ?? "Unscored"} {health ? `(${health.score})` : detail.healthScore == null ? "" : `(${detail.healthScore})`}</p>
          <small>{health?.changeExplanation ?? (detail.fieldsHiddenByPermission.length > 0 ? `Hidden fields: ${detail.fieldsHiddenByPermission.join(", ")}` : "Compute health to see the contributing factors.")}</small>
          <button type="button" className="btn btn-sm" disabled={refreshing} onClick={onRefreshHealth}>{refreshing ? "Computing..." : "Recompute health"}</button>
          {health && <div className="health-factor-list">{health.factors.map((factor) => <div key={factor.code} className="health-factor">
            <span><strong>{factor.label}</strong><small>{factor.observed}</small></span>
            <span className={`chip ${factor.direction === "NEGATIVE" ? "chip-crit" : "chip-active"}`}>{Math.round(factor.weight * 100)}% · {factor.score}</span>
            <p>{factor.explanation}</p>
          </div>)}</div>}
        </article>
        <article className="audit-event"><strong>Commercial</strong><p>{detail.annualRevenue == null ? "Revenue hidden or unavailable" : `${detail.currencyCode ?? ""} ${detail.annualRevenue.toLocaleString()}`}</p><small>{detail.employeeCount == null ? "Employee count unavailable" : `${detail.employeeCount.toLocaleString()} employees`}</small></article>
        {rollup && <article className="audit-event"><strong>Account and hierarchy roll-up</strong>
          <div className="rollup-compare"><span><small>This account</small><b>{rollup.accountOnly.openPipelineValue.toLocaleString()}</b><em>open pipeline · {rollup.accountOnly.openOpportunityCount} deals</em></span>
            <span><small>Visible hierarchy</small><b>{rollup.hierarchy.openPipelineValue.toLocaleString()}</b><em>open pipeline · {rollup.hierarchy.openOpportunityCount} deals</em></span></div>
          <small>{rollup.restricted ? rollup.restrictionNote : `${rollup.hierarchy.accountsIncluded} account(s) included. Closed won: ${rollup.hierarchy.closedWonRevenue.toLocaleString()}.`}</small>
          {rollup.unavailableMeasures.map((measure) => <p className="empty-note" key={measure.code}>{measure.label}: {measure.reason}</p>)}
        </article>}
        {hierarchy && <article className="audit-event"><strong>Hierarchy</strong><p>{hierarchy.ultimateParentName ?? detail.name}</p><small>{hierarchy.restricted ? hierarchy.restrictionNote : `${hierarchy.nodes.length} visible node(s)`}</small>
          <div className="hierarchy-mini">{hierarchy.nodes.map((node) => <div key={node.id} className={node.isSelf ? "is-self" : ""} style={{ paddingLeft: `${Math.min(node.depth, 4) * 12}px` }}>{node.name} <span>{node.status}</span></div>)}</div>
        </article>}
      </div>}
    </aside>
  </div>;
}

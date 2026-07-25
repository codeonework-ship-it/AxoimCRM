import { Fragment, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isUnreachable, type Account } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataViewFrame } from "../components/DataViewFrame";
import { MasterToolbar, canManageMasters } from "../components/MasterToolbar";
import { useToasts } from "../components/Toasts";
import { GridLoader } from "../components/Loaders";

export function AccountsPage() {
  const [grouped, setGrouped] = useState(false);
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [industryFilter, setIndustryFilter] = useState("");
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const toasts = useToasts();

  const accountsQ = useQuery({
    queryKey: ["accounts", page, search, industryFilter],
    queryFn: () => api.accounts({ page, search, filter: industryFilter }),
    retry: 1,
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

  const accounts = accountsQ.data ? [...accountsQ.data.items].sort((a, b) => grouped
    ? (a.industry ?? "Unclassified").localeCompare(b.industry ?? "Unclassified") || a.name.localeCompare(b.name)
    : a.name.localeCompare(b.name)) : [];
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
      <label><span>Search</span><input value={search} onChange={(event) => updateSearch(event.target.value)} placeholder="Name, industry, or owner" /></label>
      <label><span>Industry filter</span><input value={industryFilter} onChange={(event) => updateIndustry(event.target.value)} placeholder="Exact industry" /></label>
      <button className="btn btn-sm" onClick={resetFilters} disabled={!search && !industryFilter}>Reset</button>
    </section>
    <MasterToolbar master="accounts" entityType="ACCOUNT" search={search} filter={industryFilter} grouped={grouped} groupLabel="Industry" onToggleGroup={() => setGrouped((value) => !value)} onChanged={() => void accountsQ.refetch()} />
    <DataViewFrame title="Accounts results">
      {accountsQ.isLoading && <GridLoader label="Reading client register" rows={6} columns={4} />}
      {accountsQ.isError && <p className="empty-note">Accounts failed to load{accountsQ.error instanceof Error ? `: ${accountsQ.error.message}` : "."}</p>}
      {accountsQ.isSuccess && <div className="table-wrap"><table className="data-table"><thead><tr><th>Name</th><th>Industry</th><th>Owner</th>{canManageMasters(user?.role) && <th className="table-action">Action</th>}</tr></thead>
      <tbody>{accounts.map((account) => {
        const group = account.industry ?? "Unclassified";
        const showGroup = grouped && group !== previousGroup; previousGroup = group;
        return <Fragment key={account.id}>{showGroup && <tr className="group-row"><th colSpan={canManageMasters(user?.role) ? 4 : 3}>{group}</th></tr>}
          <tr><td>{account.name}</td><td>{account.industry ?? "-"}</td><td>{account.ownerName ?? "-"}</td>
            {canManageMasters(user?.role) && <td className="table-action"><button className="link-btn danger-link" disabled={deleteMutation.isPending} onClick={() => remove(account)}>Delete</button></td>}</tr></Fragment>;
      })}{accounts.length === 0 && <tr><td colSpan={canManageMasters(user?.role) ? 4 : 3} className="empty-note">No accounts match the current query.</td></tr>}</tbody>
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
  </>;
}

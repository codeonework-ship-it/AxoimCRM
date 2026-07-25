import { useQuery } from "@tanstack/react-query";
import { api, isUnreachable } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";

export function AccountsPage() {
  const accountsQ = useQuery({
    queryKey: ["accounts"],
    queryFn: api.accounts,
    retry: 1,
  });

  if (isUnreachable(accountsQ.error)) {
    return (
      <ApiUnreachable
        onRetry={() => void accountsQ.refetch()}
        retrying={accountsQ.isFetching}
      />
    );
  }

  return (
    <>
      <div className="page-head">
        <div><span className="eyebrow">Customer intelligence</span><h1>Accounts</h1><p>Organizations, ownership, and relationship context.</p></div>
        {accountsQ.isSuccess && (
          <span className="count">{accountsQ.data.length} total</span>
        )}
      </div>

      {accountsQ.isLoading && <p className="loading-note">Loading accounts…</p>}

      {accountsQ.isError && (
        <p className="empty-note">
          Accounts failed to load
          {accountsQ.error instanceof Error
            ? `: ${accountsQ.error.message}`
            : "."}
        </p>
      )}

      {accountsQ.isSuccess && (
        <div className="table-wrap"><table className="data-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Industry</th>
              <th>Owner</th>
            </tr>
          </thead>
          <tbody>
            {accountsQ.data.map((a) => (
              <tr key={a.id}>
                <td>{a.name}</td>
                <td>{a.industry ?? "—"}</td>
                <td>{a.ownerName ?? "—"}</td>
              </tr>
            ))}
            {accountsQ.data.length === 0 && (
              <tr>
                <td colSpan={3} className="empty-note">
                  No accounts yet.
                </td>
              </tr>
            )}
          </tbody>
        </table></div>
      )}
    </>
  );
}

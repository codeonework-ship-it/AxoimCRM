import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, isUnreachable, type ReferenceEntry, type ReferenceValueSet } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataViewFrame } from "../components/DataViewFrame";
import { canManageMasters } from "../components/MasterToolbar";
import { useToasts } from "../components/Toasts";

export function ReferenceDataPage() {
  const { user } = useAuth();
  const toasts = useToasts();
  const queryClient = useQueryClient();
  const [selectedApiName, setSelectedApiName] = useState<string>("");
  const [draft, setDraft] = useState({ code: "", label: "", sortOrder: 100 });
  const canManage = canManageMasters(user?.role);

  const setsQ = useQuery({ queryKey: ["reference", "value-sets"], queryFn: api.referenceValueSets, retry: 1 });
  const selectedSet = useMemo(() => setsQ.data?.find((set) => set.apiName === selectedApiName), [setsQ.data, selectedApiName]);
  const entriesQ = useQuery({
    queryKey: ["reference", "entries", selectedApiName],
    queryFn: () => api.referenceEntries(selectedApiName, true),
    enabled: !!selectedApiName,
    retry: 1,
  });

  useEffect(() => {
    if (!selectedApiName && setsQ.data?.length) setSelectedApiName(setsQ.data[0].apiName);
  }, [selectedApiName, setsQ.data]);

  const createMutation = useMutation({
    mutationFn: () => api.createReferenceEntry(selectedApiName, {
      code: draft.code,
      label: draft.label,
      sortOrder: draft.sortOrder,
      active: true,
    }),
    onSuccess: () => {
      setDraft({ code: "", label: "", sortOrder: 100 });
      toasts.push("info", "Reference entry added", "The value is active for this tenant.");
      void queryClient.invalidateQueries({ queryKey: ["reference", "entries", selectedApiName] });
    },
    onError: (error) => toasts.push("error", "Reference entry rejected", error instanceof Error ? error.message : "Save failed."),
  });

  const updateMutation = useMutation({
    mutationFn: (entry: ReferenceEntry) => api.updateReferenceEntry(selectedApiName, entry.code, {
      code: entry.code,
      label: entry.label,
      sortOrder: entry.sortOrder,
      active: !entry.active,
      effectiveFrom: entry.effectiveFrom,
      effectiveTo: entry.effectiveTo,
    }),
    onSuccess: () => {
      toasts.push("info", "Reference entry updated", "The value set was updated without deleting history.");
      void queryClient.invalidateQueries({ queryKey: ["reference", "entries", selectedApiName] });
    },
    onError: (error) => toasts.push("error", "Reference update failed", error instanceof Error ? error.message : "Update failed."),
  });

  if (isUnreachable(setsQ.error)) return <ApiUnreachable onRetry={() => void setsQ.refetch()} retrying={setsQ.isFetching} />;

  function createEntry() {
    if (!draft.code.trim() || !draft.label.trim()) {
      toasts.push("error", "Missing reference details", "Code and label are required.");
      return;
    }
    createMutation.mutate();
  }

  return <>
    <div className="page-head">
      <div><span className="eyebrow">Administration</span><h1>Reference Data</h1><p>Governed value sets for CRM modules and master workflows.</p></div>
      {setsQ.isSuccess && <span className="count">{setsQ.data.length} sets</span>}
    </div>

    {setsQ.isLoading && <p className="loading-note">Loading reference data...</p>}
    {setsQ.isError && <p className="empty-note">Reference data failed to load{setsQ.error instanceof Error ? `: ${setsQ.error.message}` : "."}</p>}
    {setsQ.isSuccess && <DataViewFrame title="Reference value-set console"><div className="reference-layout">
      <aside className="reference-sets" aria-label="Value sets">
        {setsQ.data.map((set: ReferenceValueSet) => <button
          className={set.apiName === selectedApiName ? "active" : ""}
          key={set.id}
          onClick={() => setSelectedApiName(set.apiName)}
        >
          <strong>{set.label}</strong>
          <span>{set.module}</span>
        </button>)}
      </aside>
      <section className="reference-panel" aria-label="Reference entries">
        <header>
          <div><span className="eyebrow">{selectedSet?.module ?? "Module"}</span><h2>{selectedSet?.label ?? "Value set"}</h2><p>{selectedSet?.description ?? "Tenant-scoped governed values."}</p></div>
        </header>
        {canManage && <div className="reference-entry-form">
          <input value={draft.code} onChange={(event) => setDraft((value) => ({ ...value, code: event.target.value.toUpperCase() }))} placeholder="CODE" aria-label="Reference code" />
          <input value={draft.label} onChange={(event) => setDraft((value) => ({ ...value, label: event.target.value }))} placeholder="Display label" aria-label="Reference label" />
          <input type="number" value={draft.sortOrder} onChange={(event) => setDraft((value) => ({ ...value, sortOrder: Number(event.target.value) }))} aria-label="Sort order" />
          <button className="btn btn-primary btn-sm" disabled={createMutation.isPending || !selectedApiName} onClick={createEntry}>{createMutation.isPending ? "Saving..." : "Add value"}</button>
        </div>}
        {entriesQ.isLoading && <p className="loading-note">Loading entries...</p>}
        {entriesQ.isError && <p className="empty-note">Entries failed to load{entriesQ.error instanceof Error ? `: ${entriesQ.error.message}` : "."}</p>}
        {entriesQ.isSuccess && <div className="table-wrap"><table className="data-table"><thead><tr><th>Code</th><th>Label</th><th>Order</th><th>Status</th>{canManage && <th className="table-action">Action</th>}</tr></thead>
          <tbody>{entriesQ.data.map((entry) => <tr key={entry.id}>
            <td>{entry.code}</td><td>{entry.label}</td><td>{entry.sortOrder}</td><td>{entry.active ? "Active" : "Inactive"}</td>
            {canManage && <td className="table-action"><button className="link-btn" disabled={updateMutation.isPending || entry.systemManaged} onClick={() => updateMutation.mutate(entry)}>{entry.active ? "Deactivate" : "Activate"}</button></td>}
          </tr>)}
          {entriesQ.data.length === 0 && <tr><td colSpan={canManage ? 5 : 4} className="empty-note">No entries in this value set.</td></tr>}</tbody>
        </table></div>}
      </section>
    </div></DataViewFrame>}
  </>;
}

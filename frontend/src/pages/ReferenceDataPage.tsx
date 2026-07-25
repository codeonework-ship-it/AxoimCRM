import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { api, isUnreachable, type ReferenceEntry, type ReferenceValueSet } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataViewFrame } from "../components/DataViewFrame";
import { canManageMasters } from "../components/MasterToolbar";
import { useToasts } from "../components/Toasts";
import { GridLoader, LoaderStatus } from "../components/Loaders";

/**
 * Master / reference data workspace.
 *
 * One tab per governed value set, because stewards touch several masters in a
 * single sitting and a list that has to be re-navigated each time is friction.
 * The tabs are built from whatever `/reference/value-sets` actually returns for
 * the tenant — there is no hardcoded entity list to drift out of sync with the
 * backend.
 *
 * The active tab lives in the URL (`/reference-data/:setCode`) so a tab is
 * linkable, browser back/forward walks the tabs, and a refresh lands on the
 * same master. `/reference-data` with no code resolves to the first tab.
 */

const EMPTY_DRAFT = { code: "", label: "", sortOrder: 100 };

function tabDomId(apiName: string): string {
  return `master-tab-${apiName}`;
}

function panelDomId(apiName: string): string {
  return `master-panel-${apiName}`;
}

export function ReferenceDataPage() {
  const { user } = useAuth();
  const toasts = useToasts();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const params = useParams<{ setCode?: string }>();
  const [searchParams] = useSearchParams();
  const [draft, setDraft] = useState(EMPTY_DRAFT);
  const [counts, setCounts] = useState<Record<string, number>>({});
  const tabRefs = useRef(new Map<string, HTMLButtonElement>());
  const pendingFocus = useRef<string | null>(null);
  const canManage = canManageMasters(user?.role);

  const setsQ = useQuery({ queryKey: ["reference", "value-sets"], queryFn: api.referenceValueSets, retry: 1 });
  const sets = useMemo<ReferenceValueSet[]>(() => setsQ.data ?? [], [setsQ.data]);

  // `?set=` is accepted so older links keep working; the path form is canonical.
  const requested = (params.setCode ?? searchParams.get("set") ?? "").trim().toLowerCase();
  const matchedSet = sets.find((set) => set.apiName === requested);
  const activeSet = matchedSet ?? sets[0];
  const selectedApiName = activeSet?.apiName ?? "";
  const urlIsCanonical = !!matchedSet && params.setCode === requested;

  const entriesQ = useQuery({
    queryKey: ["reference", "entries", selectedApiName],
    queryFn: () => api.referenceEntries(selectedApiName, true),
    enabled: !!selectedApiName,
    retry: 1,
  });

  // Redirect bare /reference-data, unknown codes and legacy ?set= links onto
  // the canonical path so refresh and back/forward stay coherent.
  useEffect(() => {
    if (!selectedApiName || urlIsCanonical) return;
    navigate(`/reference-data/${selectedApiName}`, { replace: true });
  }, [navigate, selectedApiName, urlIsCanonical]);

  // A half-typed value from one master must not leak into the next.
  useEffect(() => setDraft(EMPTY_DRAFT), [selectedApiName]);

  // Counts are only ever shown for sets whose entries have actually loaded —
  // never estimated, never faked.
  useEffect(() => {
    const rows = entriesQ.data;
    if (!selectedApiName || !rows) return;
    setCounts((current) => (current[selectedApiName] === rows.length
      ? current
      : { ...current, [selectedApiName]: rows.length }));
  }, [entriesQ.data, selectedApiName]);

  // Keyboard activation moves focus with the selection.
  useEffect(() => {
    const wanted = pendingFocus.current;
    if (!wanted || wanted !== selectedApiName) return;
    tabRefs.current.get(wanted)?.focus();
    pendingFocus.current = null;
  }, [selectedApiName]);

  const createMutation = useMutation({
    mutationFn: () => api.createReferenceEntry(selectedApiName, {
      code: draft.code,
      label: draft.label,
      sortOrder: draft.sortOrder,
      active: true,
    }),
    onSuccess: () => {
      setDraft(EMPTY_DRAFT);
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

  function selectTab(apiName: string, moveFocus = false) {
    if (moveFocus) pendingFocus.current = apiName;
    if (apiName === selectedApiName) {
      if (moveFocus) tabRefs.current.get(apiName)?.focus();
      return;
    }
    navigate(`/reference-data/${apiName}`);
  }

  function onTabStripKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (sets.length === 0) return;
    const last = sets.length - 1;
    const current = sets.findIndex((set) => set.apiName === selectedApiName);
    let target: number;
    switch (event.key) {
      case "ArrowRight": target = current >= last ? 0 : current + 1; break;
      case "ArrowLeft": target = current <= 0 ? last : current - 1; break;
      case "Home": target = 0; break;
      case "End": target = last; break;
      default: return;
    }
    event.preventDefault();
    selectTab(sets[target].apiName, true);
  }

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

    {setsQ.isLoading && <LoaderStatus label="Resolving governed value sets" />}
    {setsQ.isError && <p className="empty-note">Reference data failed to load{setsQ.error instanceof Error ? `: ${setsQ.error.message}` : "."}</p>}
    {setsQ.isSuccess && sets.length === 0 && <p className="empty-note">No governed value sets are configured for this tenant.</p>}

    {setsQ.isSuccess && sets.length > 0 && <DataViewFrame title="Reference value-set console">
      <div className="master-tabs" role="tablist" aria-label="Master value sets" aria-orientation="horizontal" onKeyDown={onTabStripKeyDown}>
        {sets.map((set) => {
          const selected = set.apiName === selectedApiName;
          const count = counts[set.apiName];
          return <button
            key={set.id}
            type="button"
            id={tabDomId(set.apiName)}
            className="master-tab"
            role="tab"
            aria-selected={selected}
            aria-controls={panelDomId(set.apiName)}
            tabIndex={selected ? 0 : -1}
            ref={(node) => { if (node) tabRefs.current.set(set.apiName, node); else tabRefs.current.delete(set.apiName); }}
            onClick={() => selectTab(set.apiName)}
          >
            <span className="master-tab-label">{set.label}</span>
            {count !== undefined && <em className="master-tab-count">{count}<span className="sr-only"> entries</span></em>}
          </button>;
        })}
      </div>

      {activeSet && <section
        className="reference-panel"
        id={panelDomId(activeSet.apiName)}
        role="tabpanel"
        aria-labelledby={tabDomId(activeSet.apiName)}
      >
        <header>
          <div><span className="eyebrow">{activeSet.module}</span><h2>{activeSet.label}</h2><p>{activeSet.description ?? "Tenant-scoped governed values."}</p></div>
        </header>
        {canManage && <div className="reference-entry-form">
          <input value={draft.code} onChange={(event) => setDraft((value) => ({ ...value, code: event.target.value.toUpperCase() }))} placeholder="CODE" aria-label="Reference code" />
          <input value={draft.label} onChange={(event) => setDraft((value) => ({ ...value, label: event.target.value }))} placeholder="Display label" aria-label="Reference label" />
          <input type="number" value={draft.sortOrder} onChange={(event) => setDraft((value) => ({ ...value, sortOrder: Number(event.target.value) }))} aria-label="Sort order" />
          <button className="btn btn-primary btn-sm" disabled={createMutation.isPending || !selectedApiName} onClick={createEntry}>{createMutation.isPending ? "Saving..." : "Add value"}</button>
        </div>}
        {entriesQ.isLoading && <GridLoader label="Reading value set" rows={5} columns={5} />}
        {entriesQ.isError && <p className="empty-note">Entries failed to load{entriesQ.error instanceof Error ? `: ${entriesQ.error.message}` : "."}</p>}
        {entriesQ.isSuccess && <div className="table-wrap"><table className="data-table"><thead><tr><th>Code</th><th>Label</th><th>Order</th><th>Status</th>{canManage && <th className="table-action">Action</th>}</tr></thead>
          <tbody>{entriesQ.data.map((entry) => <tr key={entry.id}>
            <td>{entry.code}</td><td>{entry.label}</td><td>{entry.sortOrder}</td><td>{entry.active ? "Active" : "Inactive"}</td>
            {canManage && <td className="table-action"><button className="link-btn" disabled={updateMutation.isPending || entry.systemManaged} onClick={() => updateMutation.mutate(entry)}>{entry.active ? "Deactivate" : "Activate"}</button></td>}
          </tr>)}
          {entriesQ.data.length === 0 && <tr><td colSpan={canManage ? 5 : 4} className="empty-note">No entries in this value set.</td></tr>}</tbody>
        </table></div>}
      </section>}
    </DataViewFrame>}
  </>;
}

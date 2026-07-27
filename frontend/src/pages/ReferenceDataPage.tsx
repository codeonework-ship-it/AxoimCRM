import { Fragment, useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { api, isUnreachable, type ReferenceEntry, type ReferenceEntryMutation, type ReferenceValueSet, type ResolvedReferenceEntry } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { DataGridToolbar } from "../components/DataGridToolbar";
import { DataViewFrame } from "../components/DataViewFrame";
import { GridFilterRow } from "../components/GridFilterRow";
import { InfoTag } from "../components/InfoTag";
import { canManageMasters } from "../components/MasterToolbar";
import { useToasts } from "../components/Toasts";
import { useAppDialog } from "../components/AppDialog";
import { GridLoader, LoaderStatus } from "../components/Loaders";
import { filterRowsByColumns, groupLabelFor, selectedGroupColumns, sortByGroups, type GroupColumn } from "../lib/gridGrouping";
import { usePersistedGridState } from "../lib/usePersistedGridState";
import { useGridDataLoad } from "../components/PageDataGate";

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

type ReferenceEditorMode = "create" | "edit" | "clone";
interface ReferenceDraft {
  code: string;
  label: string;
  sortOrder: number;
  active: boolean;
  effectiveFrom: string;
  effectiveTo: string;
}
const EMPTY_DRAFT: ReferenceDraft = {
  code: "", label: "", sortOrder: 100, active: true, effectiveFrom: "", effectiveTo: "",
};
const REFERENCE_GROUP_COLUMNS: GroupColumn<ReferenceEntry>[] = [
  { key: "code", label: "Code", value: (row) => row.code },
  { key: "label", label: "Label", value: (row) => row.label },
  { key: "order", label: "Order", value: (row) => row.sortOrder },
  { key: "status", label: "Status", value: (row) => row.active ? "Active" : "Inactive" },
  { key: "systemManaged", label: "System managed", value: (row) => row.systemManaged },
];

/**
 * The columns the entries table actually renders, in render order.
 *
 * <p>Deliberately not the same list as above. Grouping can group by a value the
 * table does not show as a column — "System managed" is groupable but has no
 * column of its own — whereas an in-header filter must correspond one-to-one to
 * a rendered column or every cell after the mismatch lands under the wrong
 * heading. Two lists because they answer two different questions.
 */
const REFERENCE_FILTER_COLUMNS = REFERENCE_GROUP_COLUMNS
  .filter((column) => column.key !== "systemManaged")
  .map(({ key, label }) => ({ key, label }));

function tabDomId(apiName: string): string {
  return `master-tab-${apiName}`;
}

function panelDomId(apiName: string): string {
  return `master-panel-${apiName}`;
}

export function ReferenceDataPage() {
  const entriesGrid = useGridDataLoad("Reference value-set console");
  const { user } = useAuth();
  const toasts = useToasts();
  const dialog = useAppDialog();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const params = useParams<{ setCode?: string }>();
  const [searchParams] = useSearchParams();
  const [draft, setDraft] = useState(EMPTY_DRAFT);
  const [editorMode, setEditorMode] = useState<ReferenceEditorMode>("create");
  const [editingCode, setEditingCode] = useState<string | null>(null);
  const [asOf, setAsOf] = useState(() => new Date().toISOString().slice(0, 10));
  const [resolveCode, setResolveCode] = useState("");
  const [resolved, setResolved] = useState<ResolvedReferenceEntry | null>(null);
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
  const [groupColumns, setGroupColumns, columnFilters, setColumnFilters] = usePersistedGridState(`reference-${selectedApiName || "value-set"}`);

  const entriesQ = useQuery({
    queryKey: ["reference", "entries", selectedApiName],
    queryFn: () => api.referenceEntries(selectedApiName, true),
    enabled: !!selectedApiName && entriesGrid.loaded,
    retry: 1,
  });

  // Redirect bare /reference-data, unknown codes and legacy ?set= links onto
  // the canonical path so refresh and back/forward stay coherent.
  useEffect(() => {
    if (!selectedApiName || urlIsCanonical) return;
    navigate(`/reference-data/${selectedApiName}`, { replace: true });
  }, [navigate, selectedApiName, urlIsCanonical]);

  // A half-typed value from one master must not leak into the next.
  useEffect(() => {
    setDraft(EMPTY_DRAFT);
    setEditorMode("create");
    setEditingCode(null);
    setResolveCode("");
    setResolved(null);
  }, [selectedApiName]);

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
      active: draft.active,
      effectiveFrom: draft.effectiveFrom || null,
      effectiveTo: draft.effectiveTo || null,
    }),
    onSuccess: () => {
      setDraft(EMPTY_DRAFT);
      setEditorMode("create");
      setEditingCode(null);
      toasts.push("info", "Reference entry added", "The value is active for this tenant.");
      void queryClient.invalidateQueries({ queryKey: ["reference", "entries", selectedApiName] });
    },
    onError: (error) => toasts.push("error", "Reference entry rejected", error instanceof Error ? error.message : "Save failed."),
  });

  const updateMutation = useMutation({
    mutationFn: ({ code, entry }: { code: string; entry: ReferenceEntryMutation }) =>
      api.updateReferenceEntry(selectedApiName, code, entry),
    onSuccess: () => {
      setDraft(EMPTY_DRAFT);
      setEditorMode("create");
      setEditingCode(null);
      toasts.push("info", "Reference entry updated", "The value set was updated without deleting history.");
      void queryClient.invalidateQueries({ queryKey: ["reference", "entries", selectedApiName] });
    },
    onError: (error) => toasts.push("error", "Reference update failed", error instanceof Error ? error.message : "Update failed."),
  });
  const retireMutation = useMutation({
    mutationFn: ({ entry, reason }: { entry: ReferenceEntry; reason: string }) =>
      api.deleteReferenceEntry(selectedApiName, entry.code, reason),
    onSuccess: () => {
      toasts.push("info", "Reference entry deleted", "The value was retired, not hard-deleted, and remains available to historical records and reports.");
      void queryClient.invalidateQueries({ queryKey: ["reference", "entries", selectedApiName] });
    },
    onError: (error) => toasts.push("error", "Reference entry cannot be deleted", error instanceof Error ? error.message : "Delete failed."),
  });
  const resolveMutation = useMutation({
    mutationFn: () => api.resolveReferenceEntry(selectedApiName, resolveCode, asOf),
    onSuccess: setResolved,
    onError: (error) => {
      setResolved(null);
      toasts.push("error", "No value for that date", error instanceof Error ? error.message : "Resolution failed.");
    },
  });
  const activeGroupColumns = selectedGroupColumns(REFERENCE_GROUP_COLUMNS, groupColumns);
  const visibleEntries = sortByGroups(
    filterRowsByColumns(entriesQ.data ?? [], REFERENCE_GROUP_COLUMNS, columnFilters),
    activeGroupColumns,
    (entry) => entry.label,
  );

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

  function saveEntry() {
    if (!draft.code.trim() || !draft.label.trim()) {
      toasts.push("error", "Missing reference details", "Code and label are required.");
      return;
    }
    if (draft.effectiveFrom && draft.effectiveTo && draft.effectiveTo < draft.effectiveFrom) {
      toasts.push("error", "Invalid effective dates", "Effective To must be on or after Effective From.");
      return;
    }
    if (editorMode === "edit" && editingCode) {
      updateMutation.mutate({ code: editingCode, entry: {
        code: editingCode,
        label: draft.label,
        sortOrder: draft.sortOrder,
        active: draft.active,
        effectiveFrom: draft.effectiveFrom || null,
        effectiveTo: draft.effectiveTo || null,
      } });
    } else createMutation.mutate();
  }

  function beginCreate() {
    setDraft(EMPTY_DRAFT);
    setEditorMode("create");
    setEditingCode(null);
  }

  function beginEdit(entry: ReferenceEntry) {
    setDraft({
      code: entry.code,
      label: entry.label,
      sortOrder: entry.sortOrder,
      active: entry.active,
      effectiveFrom: entry.effectiveFrom ?? "",
      effectiveTo: entry.effectiveTo ?? "",
    });
    setEditorMode("edit");
    setEditingCode(entry.code);
  }

  function beginClone(entry: ReferenceEntry) {
    setDraft({
      code: "",
      label: `${entry.label} Copy`,
      sortOrder: entry.sortOrder + 5,
      active: true,
      effectiveFrom: entry.effectiveFrom ?? "",
      effectiveTo: entry.effectiveTo ?? "",
    });
    setEditorMode("clone");
    setEditingCode(null);
  }

  async function retireEntry(entry: ReferenceEntry) {
    const reason = await dialog.prompt({
      title: "Delete Reference Value",
      message: `Delete ${entry.code} - ${entry.label}? Axiom will retire the value without removing its history. Values used by system workflows or dependent lists are protected.`,
      label: "Retirement reason",
      placeholder: "Why should this value no longer be available?",
      required: true,
      confirmLabel: "Delete Value",
      tone: "danger",
    });
    if (reason?.trim()) retireMutation.mutate({ entry, reason: reason.trim() });
  }

  function restoreEntry(entry: ReferenceEntry) {
    updateMutation.mutate({ code: entry.code, entry: {
      code: entry.code,
      label: entry.label,
      sortOrder: entry.sortOrder,
      active: true,
      effectiveFrom: entry.effectiveFrom,
      effectiveTo: null,
    } });
  }

  return <>
    <div className="page-head">
      <div><span className="eyebrow">Administration</span><h1>Reference Data</h1><p>Governed value sets for CRM modules and master workflows.</p></div>
      {setsQ.isSuccess && <span className="count">{setsQ.data.length} sets</span>}
    </div>

    {setsQ.isLoading && <LoaderStatus label="Resolving governed value sets" />}
    {setsQ.isError && <p className="empty-note">Reference data failed to load{setsQ.error instanceof Error ? `: ${setsQ.error.message}` : "."}</p>}
    {setsQ.isSuccess && sets.length === 0 && <p className="empty-note">No governed value sets are configured for this tenant.</p>}

    {setsQ.isSuccess && sets.length > 0 && <DataViewFrame
      title="Reference value-set console"
      actions={<DataGridToolbar
        gridName="Reference value-set console"
        grouped={activeGroupColumns.length > 0}
        groupLabel="Status"
        onToggleGroup={() => setGroupColumns((value) => value.length > 0 ? [] : ["status"])}
        groupColumns={REFERENCE_GROUP_COLUMNS.map(({ key, label }) => ({ key, label }))}
        selectedGroupColumns={groupColumns}
        onGroupColumnsChange={setGroupColumns}
        columnFilters={columnFilters}
        onColumnFiltersChange={setColumnFilters}
        auditEntityType="REFERENCE_VALUE_SET"
        exportFilename={`reference-${selectedApiName || "value-set"}`}
        exportRows={visibleEntries.map((entry) => ({
          valueSet: activeSet?.label ?? selectedApiName,
          code: entry.code,
          label: entry.label,
          sortOrder: entry.sortOrder,
          status: entry.active ? "Active" : "Inactive",
          systemManaged: entry.systemManaged ? "Yes" : "No",
          effectiveFrom: entry.effectiveFrom ?? "",
          effectiveTo: entry.effectiveTo ?? "",
        }))}
        note="Current value set"
      />}
    >
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
        <header className="reference-panel-head">
          <div><span className="eyebrow">{activeSet.module}</span><h2>{activeSet.label}</h2><p>{activeSet.description ?? "Tenant-scoped governed values."}</p></div>
          {canManage && <button type="button" className="btn btn-primary btn-sm" onClick={beginCreate}>New Value</button>}
        </header>
        <section className="list-controls" aria-label="Resolve a historical reference value">
          <label><span>Value code <InfoTag text="Choose the stored code from a historical record." label="Historical value code help" /></span>
            <select value={resolveCode} onChange={(event) => { setResolveCode(event.target.value); setResolved(null); }}>
              <option value="">Choose a value</option>
              {(entriesQ.data ?? []).map((entry) => <option key={entry.id} value={entry.code}>{entry.code} — {entry.label}</option>)}
            </select>
          </label>
          <label><span>Record date <InfoTag text="Axiom resolves the label that was effective on this business date, even if the value is inactive today." label="As-of date help" /></span>
            <input type="date" value={asOf} onChange={(event) => { setAsOf(event.target.value); setResolved(null); }} />
          </label>
          <button type="button" className="btn btn-sm" disabled={!resolveCode || !asOf || resolveMutation.isPending}
            onClick={() => resolveMutation.mutate()}>{resolveMutation.isPending ? "Resolving..." : "Resolve as of date"}</button>
          {resolved && <div className="panel inline-result" role="status">
            <strong>{resolved.code} — {resolved.label}</strong>
            <span>{resolved.note}</span>
          </div>}
        </section>
        {canManage && <div className="reference-entry-form" aria-label={`${editorMode} reference value`}>
          <div className="reference-editor-heading">
            <span className="eyebrow">{editorMode === "edit" ? "Edit Value" : editorMode === "clone" ? "Clone Value" : "Create Value"}</span>
            <InfoTag text="Create, edit, clone, restore or retire a governed dropdown value. Codes stay stable so reports and historical records never lose their meaning." label="Reference value form help" />
          </div>
          <label><span>Code</span><input title="Short system code for this value, usually uppercase." disabled={editorMode === "edit"} value={draft.code} onChange={(event) => setDraft((value) => ({ ...value, code: event.target.value.toUpperCase() }))} placeholder="CODE" aria-label="Reference code" /></label>
          <label><span>Display Label</span><input title="Friendly label users will see in dropdowns and reports." value={draft.label} onChange={(event) => setDraft((value) => ({ ...value, label: event.target.value }))} placeholder="Display label" aria-label="Reference label" /></label>
          <label><span>Sort Order</span><input title="Lower numbers appear earlier in lists." type="number" min="0" value={draft.sortOrder} onChange={(event) => setDraft((value) => ({ ...value, sortOrder: Number(event.target.value) }))} aria-label="Sort order" /></label>
          <label><span>Effective From</span><input type="date" value={draft.effectiveFrom} onChange={(event) => setDraft((value) => ({ ...value, effectiveFrom: event.target.value }))} aria-label="Effective from" /></label>
          <label><span>Effective To</span><input type="date" value={draft.effectiveTo} onChange={(event) => setDraft((value) => ({ ...value, effectiveTo: event.target.value }))} aria-label="Effective to" /></label>
          <label className="reference-active-check"><input type="checkbox" checked={draft.active} onChange={(event) => setDraft((value) => ({ ...value, active: event.target.checked }))} /><span>Active</span></label>
          <div className="reference-editor-actions">
            <button className="btn btn-primary btn-sm" disabled={createMutation.isPending || updateMutation.isPending || !selectedApiName} onClick={saveEntry}>{createMutation.isPending || updateMutation.isPending ? "Saving..." : editorMode === "edit" ? "Save Changes" : "Create Value"}</button>
            {(editorMode !== "create" || draft.code || draft.label) && <button className="btn btn-sm" disabled={createMutation.isPending || updateMutation.isPending} onClick={beginCreate}>Cancel</button>}
          </div>
        </div>}
        {entriesQ.isLoading && <GridLoader label="Reading value set" rows={5} columns={5} />}
        {entriesQ.isError && <p className="empty-note">Entries failed to load{entriesQ.error instanceof Error ? `: ${entriesQ.error.message}` : "."}</p>}
        {entriesQ.isSuccess && <div className="table-wrap"><table className="data-table"><thead><tr><th>Code</th><th>Label</th><th>Order</th><th>Status</th>{canManage && <th className="table-action">Action</th>}</tr>
          <GridFilterRow
            columns={REFERENCE_FILTER_COLUMNS}
            filters={columnFilters}
            onChange={setColumnFilters}
            trailing={canManage ? 1 : 0}
          /></thead>
          <tbody>{visibleEntries.map((entry, index, all) => {
            const group = entry.active ? "Active" : "Inactive";
            const previous = all[index - 1];
            const groupText = activeGroupColumns.length > 0 ? groupLabelFor(entry, activeGroupColumns) : group;
            const previousGroupText = previous && activeGroupColumns.length > 0 ? groupLabelFor(previous, activeGroupColumns) : "";
            const showGroup = activeGroupColumns.length > 0 && previousGroupText !== groupText;
            return <Fragment key={entry.id}>
              {showGroup && <tr className="group-row"><th colSpan={canManage ? 5 : 4}>{groupText}</th></tr>}
              <tr>
                <td>{entry.code}</td><td>{entry.label}</td><td>{entry.sortOrder}</td><td>{group}</td>
                {canManage && <td className="table-action">
                  <button className="link-btn" disabled={updateMutation.isPending || retireMutation.isPending} onClick={() => beginEdit(entry)}>Edit</button>
                  <button className="link-btn" disabled={updateMutation.isPending || retireMutation.isPending} onClick={() => beginClone(entry)}>Clone</button>
                  {entry.active
                    ? <button className="link-btn danger-link" title={entry.systemManaged ? "System-managed values are protected because workflows depend on them." : "Soft-delete this value while keeping its history."} disabled={retireMutation.isPending || entry.systemManaged} onClick={() => void retireEntry(entry)}>Delete</button>
                    : <button className="link-btn" disabled={updateMutation.isPending} onClick={() => restoreEntry(entry)}>Restore</button>}
                </td>}
              </tr>
            </Fragment>;
          })}
          {visibleEntries.length === 0 && <tr><td colSpan={canManage ? 5 : 4} className="empty-note">No entries match the current value-set filters.</td></tr>}</tbody>
        </table></div>}
      </section>}
    </DataViewFrame>}
  </>;
}

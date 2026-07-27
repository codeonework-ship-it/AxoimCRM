import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  api, isUnreachable,
  type MigrationCheckpoint, type MigrationIssue, type MigrationMapping,
  type MigrationMappingRevision, type MigrationPlan, type MigrationReconciliationLine,
  type MigrationRun,
} from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { useAppDialog } from "../components/AppDialog";
import { DataTable, type Column } from "../components/DataTable";
import { DataViewFrame } from "../components/DataViewFrame";
import { GridLoader, InlineLoader } from "../components/Loaders";
import { useToasts } from "../components/Toasts";

type Tab = "control" | "mapping" | "reconciliation" | "recovery";

function when(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString() : "Not yet";
}

function message(error: unknown) {
  return error instanceof Error ? error.message : "The operation could not be completed.";
}

function chip(status: string) {
  if (["COMPLETED", "IMPORTED", "CONNECTED", "MAPPED", "ACKNOWLEDGED"].includes(status)) return "chip chip-qualified";
  if (["FAILED", "ROLLBACK_BLOCKED"].includes(status)) return "chip chip-lost";
  if (["QUEUED", "RUNNING", "UNMAPPED"].includes(status)) return "chip chip-working";
  return "chip";
}

export function MigrationPage() {
  const client = useQueryClient();
  const toasts = useToasts();
  const dialog = useAppDialog();
  const [tab, setTab] = useState<Tab>("control");
  const [planId, setPlanId] = useState<string | null>(null);
  const [runId, setRunId] = useState<string | null>(null);
  const [issuePage, setIssuePage] = useState(0);
  const [issueSearch, setIssueSearch] = useState("");
  const [issueCategory, setIssueCategory] = useState("");
  const [sourceDraft, setSourceDraft] = useState({ name: "Local migration source", fixtureKey: "acme-legacy" });
  const [planDraft, setPlanDraft] = useState({ name: "CRM migration", connectionId: "", retentionDays: 30 });
  const [editing, setEditing] = useState<MigrationMapping | null>(null);
  const [mappingDraft, setMappingDraft] = useState({ status: "MAPPED", targetEntity: "", targetField: "", note: "" });

  const vendorsQ = useQuery({ queryKey: ["migration", "vendors"], queryFn: api.migrationVendors, retry: 1 });
  const connectionsQ = useQuery({ queryKey: ["migration", "connections"], queryFn: api.migrationConnections, retry: 1 });
  const plansQ = useQuery({ queryKey: ["migration", "plans"], queryFn: api.migrationPlans, retry: 1 });
  const schemaQ = useQuery({ queryKey: ["migration", "target-schema"], queryFn: api.migrationTargetSchema, retry: 1 });
  const runsQ = useQuery({
    queryKey: ["migration", "runs", planId],
    queryFn: () => api.migrationRuns(planId as string), enabled: !!planId, retry: 1,
    refetchInterval: (query) => ((query.state.data as MigrationRun[] | undefined)?.some((r) => ["QUEUED", "RUNNING"].includes(r.status)) ? 1500 : false),
  });
  const mappingQ = useQuery({ queryKey: ["migration", "mapping", planId], queryFn: () => api.migrationMapping(planId as string), enabled: !!planId, retry: 1 });
  const revisionsQ = useQuery({ queryKey: ["migration", "revisions", planId], queryFn: () => api.migrationMappingRevisions(planId as string), enabled: !!planId, retry: 1 });
  const recoveryQ = useQuery({ queryKey: ["migration", "recovery", runId], queryFn: () => api.migrationRecovery(runId as string), enabled: !!runId, retry: 1 });
  const issuesQ = useQuery({ queryKey: ["migration", "issues", runId, issuePage, issueSearch, issueCategory], queryFn: () => api.migrationIssues(runId as string, { page: issuePage, search: issueSearch, category: issueCategory }), enabled: !!runId, retry: 1 });
  const rollbackQ = useQuery({ queryKey: ["migration", "rollback-preview", planId], queryFn: () => api.migrationRollbackPreview(planId as string), enabled: !!planId && tab === "recovery", retry: 1 });
  const checkpointsQ = useQuery({ queryKey: ["migration", "checkpoints", planId], queryFn: () => api.migrationCheckpoints(planId as string), enabled: !!planId, retry: 1 });

  const plans = useMemo(() => plansQ.data ?? [], [plansQ.data]);
  const runs = useMemo(() => runsQ.data ?? [], [runsQ.data]);
  const selectedPlan = plans.find((p) => p.id === planId) ?? null;
  const selectedRun = runs.find((r) => r.runId === runId) ?? null;

  useEffect(() => { if (!planId && plans.length) setPlanId(plans[0].id); }, [planId, plans]);
  useEffect(() => { if (planId && runs.length && !runs.some((r) => r.runId === runId)) setRunId(runs[0].runId); }, [planId, runId, runs]);
  useEffect(() => {
    if (!planDraft.connectionId && connectionsQ.data?.length) {
      setPlanDraft((current) => ({ ...current, connectionId: connectionsQ.data![0].id }));
    }
  }, [connectionsQ.data, planDraft.connectionId]);

  function refresh() { void client.invalidateQueries({ queryKey: ["migration"] }); }
  function notify(title: string, detail: string) { toasts.push("info", title, detail); refresh(); }
  function fail(title: string) { return (error: unknown) => toasts.push("error", title, message(error)); }

  const connect = useMutation({
    mutationFn: () => api.createMigrationConnection({ name: sourceDraft.name.trim(), vendor: "FIXTURE", fixtureKey: sourceDraft.fixtureKey }),
    onSuccess: (row) => notify("Read-only source connected", `${row.name} is ready for schema discovery.`), onError: fail("Source connection rejected"),
  });
  const discover = useMutation({
    mutationFn: api.discoverMigrationConnection,
    onSuccess: () => notify("Schema discovered", "Objects, fields, record counts and custom fields are available for mapping."), onError: fail("Discovery failed"),
  });
  const createPlan = useMutation({
    mutationFn: () => api.createMigrationPlan({ ...planDraft, name: planDraft.name.trim() }),
    onSuccess: (row) => { setPlanId(row.id); notify("Migration plan created", "A proposed mapping revision was recorded."); }, onError: fail("Plan rejected"),
  });
  const queue = useMutation({
    mutationFn: (mode: "DRY_RUN" | "IMPORT" | "DELTA") => api.queueMigrationRun(planId as string, mode),
    onSuccess: (row) => { setRunId(row.runId); notify(`${row.mode.replace("_", " ")} queued`, "The worker will execute this as one recoverable run."); }, onError: fail("Run not queued"),
  });
  const saveMapping = useMutation({
    mutationFn: () => api.saveMigrationMappings(planId as string, [{
      sourceObject: editing!.sourceObject, sourceField: editing!.sourceField, status: mappingDraft.status,
      targetEntity: mappingDraft.status === "MAPPED" ? mappingDraft.targetEntity : null,
      targetField: mappingDraft.status === "MAPPED" ? mappingDraft.targetField : null,
      note: mappingDraft.note || null,
    }]),
    onSuccess: () => { setEditing(null); notify("Mapping saved", "The acknowledgement was reset and a new immutable mapping revision was recorded."); }, onError: fail("Mapping not saved"),
  });
  const acknowledge = useMutation({
    mutationFn: () => api.acknowledgeMigrationMapping(planId as string),
    onSuccess: () => notify("Data-loss list acknowledged", "The current mapping is now eligible for import."), onError: fail("Acknowledgement failed"),
  });
  const restore = useMutation({
    mutationFn: (version: number) => api.restoreMigrationMapping(planId as string, version),
    onSuccess: () => notify("Mapping restored", "The restored state was recorded as a new revision and must be acknowledged again."), onError: fail("Revision not restored"),
  });
  const recover = useMutation({
    mutationFn: async (action: "RETRY" | "CANCEL" | "RECONCILE" | "ROLLBACK") => {
      const reason = await dialog.prompt({ title: `${action.charAt(0)}${action.slice(1).toLowerCase()} Migration`, message: "State why this operator recovery action is required. The reason is written to the audit trail.", label: "Recovery Reason", required: true, multiline: true });
      if (!reason) return null;
      if (action === "RETRY") return api.retryMigrationRun(runId as string, reason);
      if (action === "CANCEL") return api.cancelMigrationRun(runId as string, reason);
      if (action === "RECONCILE") return api.reconcileMigration(planId as string, reason);
      const preview = rollbackQ.data;
      if (!preview?.withinRetention) throw new Error("Rollback is outside the configured retention window.");
      const accepted = await dialog.confirm({ title: "Confirm Exact-Ledger Rollback", message: `Remove ${preview.totalRecords} migration-created record(s). ${preview.untouched.map((x) => `${x.preExistingRecords} ${x.entity}`).join(", ")} pre-existing record(s) remain untouched.`, confirmLabel: "Queue Rollback", tone: "danger" });
      return accepted ? api.rollbackMigration(planId as string, reason) : null;
    },
    onSuccess: (row) => { if (row) { setRunId(row.runId); notify("Recovery action accepted", "A linked run and recovery evidence were created."); } }, onError: fail("Recovery action rejected"),
  });

  if (plansQ.isError && isUnreachable(plansQ.error)) return <ApiUnreachable onRetry={refresh} retrying={plansQ.isFetching} />;

  return <div className="migration-page">
    <div className="page-head">
      <div><span className="eyebrow">Migration And Onboarding</span><h1>Migration Operations</h1><p>Discover, map, validate, import, reconcile, re-sync and recover without silent data loss.</p></div>
      <span className="count">{plans.length} plans</span>
    </div>

    <nav className="migration-tabs" aria-label="Migration workspace">
      {(["control", "mapping", "reconciliation", "recovery"] as Tab[]).map((item) =>
        <button key={item} className={`btn ${tab === item ? "btn-primary" : ""}`} onClick={() => setTab(item)}>{item === "control" ? "Control Centre" : item.charAt(0).toUpperCase() + item.slice(1)}</button>)}
    </nav>

    <div className="migration-selector panel">
      <label><span>Active Plan</span><select value={planId ?? ""} onChange={(e) => { setPlanId(e.target.value || null); setRunId(null); }}><option value="">Choose A Plan</option>{plans.map((p) => <option key={p.id} value={p.id}>{p.name} · {p.status}</option>)}</select></label>
      {selectedPlan && <><span className={chip(selectedPlan.status)}>{selectedPlan.status}</span><span>{selectedPlan.mappedFields} mapped · {selectedPlan.unmappedFields} unmapped · mapping v{selectedPlan.mappingVersion}</span><span>Delta checkpoint: {when(selectedPlan.deltaWatermark)}</span></>}
    </div>

    {tab === "control" && <ControlCentre vendors={vendorsQ.data?.vendors ?? []} connections={connectionsQ.data ?? []} plans={plans} runs={runs}
      sourceDraft={sourceDraft} setSourceDraft={setSourceDraft} planDraft={planDraft} setPlanDraft={setPlanDraft}
      connect={connect} discover={discover} createPlan={createPlan} queue={queue} setPlanId={setPlanId} setRunId={setRunId} />}

    {tab === "mapping" && <>
      {!planId ? <Empty text="Choose a plan to manage its mapping." /> : mappingQ.isLoading ? <GridLoader label="Reading field mapping" columns={7} /> : mappingQ.data && <>
        <section className={`migration-guidance panel ${mappingQ.data.acknowledgementCurrent ? "is-ready" : ""}`}><div><strong>{mappingQ.data.acknowledgementCurrent ? "Mapping acknowledged" : "Review required"}</strong><p>{mappingQ.data.acknowledgementStatement}</p></div><button className="btn btn-primary" disabled={acknowledge.isPending || mappingQ.data.acknowledgementCurrent} onClick={() => acknowledge.mutate()}>{acknowledge.isPending ? "Recording…" : "Acknowledge Current List"}</button></section>
        {editing && <MappingEditor row={editing} draft={mappingDraft} setDraft={setMappingDraft} entities={schemaQ.data ?? []} save={() => saveMapping.mutate()} close={() => setEditing(null)} busy={saveMapping.isPending} />}
        <DataViewFrame title="Field Mapping"><DataTable name="Migration Field Mapping" rows={mappingQ.data.mappings} rowKey={(r) => r.id} columns={mappingColumns()} actions={(row) => <button className="link-btn" onClick={() => { setEditing(row); setMappingDraft({ status: row.status, targetEntity: row.targetEntity ?? "", targetField: row.targetField ?? "", note: row.note ?? "" }); }}>Edit</button>} /></DataViewFrame>
        <DataViewFrame title="Mapping Revision History"><DataTable name="Migration Mapping Revisions" rows={revisionsQ.data ?? []} rowKey={(r) => r.id} columns={revisionColumns()} actions={(row) => <button className="link-btn" disabled={restore.isPending || row.versionNo === selectedPlan?.mappingVersion} onClick={() => restore.mutate(row.versionNo)}>Restore</button>} /></DataViewFrame>
      </>}
    </>}

    {tab === "reconciliation" && <>
      <RunSelector runs={runs} runId={runId} setRunId={setRunId} />
      {recoveryQ.isLoading && <GridLoader label="Reading reconciliation evidence" columns={6} />}
      {recoveryQ.data?.reconciliation ? <>
        <section className="migration-guidance panel"><div><strong>{recoveryQ.data.reconciliation.balanced ? "Source And Target Balance" : "Differences Require Attention"}</strong><p>{recoveryQ.data.nextStep}</p></div><button className="btn" onClick={() => recover.mutate("RECONCILE")}>Run Fresh Reconciliation</button></section>
        <DataViewFrame title="Reconciliation By Object"><DataTable name="Migration Reconciliation" rows={recoveryQ.data.reconciliation.lines} rowKey={(r) => r.sourceObject} columns={reconciliationColumns()} /></DataViewFrame>
      </> : runId && !recoveryQ.isLoading ? <Empty text="This run has no reconciliation report. Choose an import, delta, dry-run or reconciliation run." /> : null}
    </>}

    {tab === "recovery" && <>
      <RunSelector runs={runs} runId={runId} setRunId={setRunId} />
      {selectedRun && recoveryQ.data && <section className="migration-guidance panel"><div><strong>Safe Next Step</strong><p>{recoveryQ.data.nextStep}</p><small>Attempt {selectedRun.attemptNo}{selectedRun.retryOfRun ? ` · retry of ${selectedRun.retryOfRun}` : " · original attempt"}</small></div><div className="migration-actions">{recoveryQ.data.allowedActions.map((a) => <button key={a} className={`btn ${a === "ROLLBACK" ? "btn-danger" : ""}`} disabled={recover.isPending} onClick={() => recover.mutate(a as "RETRY" | "CANCEL" | "RECONCILE" | "ROLLBACK")}>{a}</button>)}</div></section>}
      {rollbackQ.data && <section className="migration-safety panel"><strong>Rollback Safety Boundary</strong><span>{rollbackQ.data.totalRecords} migration-created records in scope</span><span>{rollbackQ.data.modifiedSinceMigration.length} changed since migration</span><span>{rollbackQ.data.withinRetention ? `Available until ${when(rollbackQ.data.retentionExpiresAt)}` : "Retention window closed"}</span></section>}
      <DataViewFrame title="Run Issues"><div className="migration-issue-filter"><input value={issueSearch} onChange={(e) => { setIssueSearch(e.target.value); setIssuePage(0); }} placeholder="Search issue evidence" aria-label="Search issue evidence"/><select value={issueCategory} onChange={(e) => { setIssueCategory(e.target.value); setIssuePage(0); }}><option value="">All Categories</option>{["VALIDATION","DUPLICATE","REFERENTIAL_GAP","UNMAPPED_FIELD","SKIPPED","ROLLBACK_REMOVED","ROLLBACK_BLOCKED","MODIFIED_SINCE_MIGRATION"].map((x) => <option key={x}>{x}</option>)}</select></div><DataTable name="Migration Run Issues" rows={issuesQ.data?.items ?? []} rowKey={(r) => `${r.category}-${r.sourceObject}-${r.sourceRecordId}-${r.reason}`} columns={issueColumns()} /><div className="pagination"><button className="btn btn-sm" disabled={!issuesQ.data || issuePage === 0} onClick={() => setIssuePage((p) => p - 1)}>Previous</button><span>Page {issuePage + 1} · {issuesQ.data?.total ?? 0} issues · 100 per page</span><button className="btn btn-sm" disabled={!issuesQ.data || issuePage + 1 >= issuesQ.data.totalPages} onClick={() => setIssuePage((p) => p + 1)}>Next</button></div></DataViewFrame>
      <DataViewFrame title="Delta Checkpoints"><DataTable name="Migration Delta Checkpoints" rows={checkpointsQ.data ?? []} rowKey={(r) => r.sourceObject} columns={checkpointColumns()} /></DataViewFrame>
    </>}
  </div>;
}

function ControlCentre(props: any) {
  return <div className="migration-control-grid">
    <section className="panel migration-command"><span className="eyebrow">1 · Read-Only Source</span><h2>Connect And Discover</h2><p>The local fixture proves the complete first-party workflow. Live vendor adapters stay visibly deferred until authenticated certification.</p><div className="migration-adapter-status">{props.vendors.map((v: any) => <span key={v.vendor} className={chip(v.liveInteropAvailable ? "COMPLETED" : "QUEUED")} title={v.note}>{v.displayName} · {v.liveInteropAvailable ? "Available" : "Pending Vendor Certification"}</span>)}</div><label><span>Connection Name</span><input value={props.sourceDraft.name} onChange={(e) => props.setSourceDraft({ ...props.sourceDraft, name: e.target.value })}/></label><label><span>Fixture Dataset</span><select value={props.sourceDraft.fixtureKey} onChange={(e) => props.setSourceDraft({ ...props.sourceDraft, fixtureKey: e.target.value })}>{["acme-legacy","axiom-sample"].map((x) => <option key={x}>{x}</option>)}</select></label><button className="btn btn-primary" disabled={props.connect.isPending} onClick={() => props.connect.mutate()}>{props.connect.isPending ? "Connecting…" : "Connect Read-Only Source"}</button><div className="migration-list">{props.connections.map((c: any) => <button key={c.id} onClick={() => props.discover.mutate(c.id)}><strong>{c.name}</strong><small>{c.vendorLabel} · {c.objectCount} objects · {c.status}</small><span>Discover Schema</span></button>)}</div></section>
    <section className="panel migration-command"><span className="eyebrow">2 · Mapping Project</span><h2>Create A Governed Plan</h2><p>Every discovered field receives an explicit mapped, unmapped or ignored decision with revision history.</p><label><span>Plan Name</span><input value={props.planDraft.name} onChange={(e) => props.setPlanDraft({ ...props.planDraft, name: e.target.value })}/></label><label><span>Source</span><select value={props.planDraft.connectionId} onChange={(e) => props.setPlanDraft({ ...props.planDraft, connectionId: e.target.value })}><option value="">Choose A Discovered Source</option>{props.connections.map((c: any) => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label><label><span>Rollback Retention Days</span><input type="number" min="1" max="365" value={props.planDraft.retentionDays} onChange={(e) => props.setPlanDraft({ ...props.planDraft, retentionDays: Number(e.target.value) })}/></label><button className="btn btn-primary" disabled={props.createPlan.isPending || !props.planDraft.connectionId} onClick={() => props.createPlan.mutate()}>Create Plan And Propose Mapping</button><div className="migration-list">{props.plans.map((p: MigrationPlan) => <button key={p.id} onClick={() => props.setPlanId(p.id)}><strong>{p.name}</strong><small>{p.connectionName} · mapping v{p.mappingVersion}</small><span className={chip(p.status)}>{p.status}</span></button>)}</div></section>
    <section className="panel migration-command migration-run-command"><span className="eyebrow">3 · Validate And Execute</span><h2>Run With Evidence</h2><p>Dry run writes no business data. Import and delta runs are atomic. Reconciliation and rollback remain available afterward.</p><div className="migration-actions"><button className="btn" disabled={!props.plans.length || props.queue.isPending} onClick={() => props.queue.mutate("DRY_RUN")}>Dry Run</button><button className="btn btn-primary" disabled={!props.plans.length || props.queue.isPending} onClick={() => props.queue.mutate("IMPORT")}>Import</button><button className="btn" disabled={!props.plans.length || props.queue.isPending} onClick={() => props.queue.mutate("DELTA")}>Delta Re-Sync</button>{props.queue.isPending && <InlineLoader label="Queueing" />}</div><div className="migration-list">{props.runs.map((r: MigrationRun) => <button key={r.runId} onClick={() => props.setRunId(r.runId)}><strong>{r.mode} · attempt {r.attemptNo}</strong><small>{when(r.queuedAt)} · {r.message ?? r.phase ?? "Waiting"}</small><span className={chip(r.status)}>{r.status} · {r.percentComplete}%</span></button>)}</div></section>
  </div>;
}

function RunSelector({ runs, runId, setRunId }: { runs: MigrationRun[]; runId: string | null; setRunId: (id: string) => void }) {
  return <div className="migration-selector panel"><label><span>Evidence Run</span><select value={runId ?? ""} onChange={(e) => setRunId(e.target.value)}><option value="">Choose A Run</option>{runs.map((r) => <option key={r.runId} value={r.runId}>{r.mode} · attempt {r.attemptNo} · {r.status} · {when(r.queuedAt)}</option>)}</select></label></div>;
}

function MappingEditor({ row, draft, setDraft, entities, save, close, busy }: any) {
  const entity = entities.find((e: any) => e.name === draft.targetEntity);
  return <section className="panel migration-editor"><div><span className="eyebrow">Mapping Decision</span><h3>{row.sourceObject}.{row.sourceField}</h3><p>{row.sourceDataType}{row.custom ? " · custom field" : ""}</p></div><label><span>Decision</span><select value={draft.status} onChange={(e) => setDraft({ ...draft, status: e.target.value })}>{["MAPPED","UNMAPPED","IGNORED"].map((x) => <option key={x}>{x}</option>)}</select></label><label><span>Target Entity</span><select disabled={draft.status !== "MAPPED"} value={draft.targetEntity} onChange={(e) => setDraft({ ...draft, targetEntity: e.target.value, targetField: "" })}><option value="">Choose Entity</option>{entities.map((e: any) => <option key={e.name} value={e.name}>{e.label}</option>)}</select></label><label><span>Target Field</span><select disabled={draft.status !== "MAPPED" || !entity} value={draft.targetField} onChange={(e) => setDraft({ ...draft, targetField: e.target.value })}><option value="">Choose Field</option>{(entity?.fields ?? []).map((f: any) => <option key={f.name} value={f.name}>{f.name}{f.required ? " · required" : ""}</option>)}</select></label><label><span>Operator Note</span><input value={draft.note} onChange={(e) => setDraft({ ...draft, note: e.target.value })}/></label><div className="migration-actions"><button className="btn" onClick={close}>Cancel</button><button className="btn btn-primary" disabled={busy || (draft.status === "MAPPED" && (!draft.targetEntity || !draft.targetField))} onClick={save}>Save Decision</button></div></section>;
}

function Empty({ text }: { text: string }) { return <div className="panel empty-note">{text}</div>; }

const mappingColumns = (): Column<MigrationMapping>[] => [
  { key: "sourceObject", header: "Source Object", value: (r) => r.sourceObject, filter: "enum", groupable: true },
  { key: "sourceField", header: "Source Field", value: (r) => r.sourceField, filter: "text" },
  { key: "sourceDataType", header: "Source Type", value: (r) => r.sourceDataType, filter: "enum", groupable: true },
  { key: "targetEntity", header: "Target Entity", value: (r) => r.targetEntity, filter: "enum", groupable: true, blank: "No Target" },
  { key: "targetField", header: "Target Field", value: (r) => r.targetField, filter: "text", blank: "No Target" },
  { key: "status", header: "Decision", value: (r) => r.status, filter: "enum", groupable: true, render: (r) => <span className={chip(r.status)}>{r.status}</span> },
  { key: "note", header: "Explanation", value: (r) => r.note, filter: "text", blank: "No note" },
];
const revisionColumns = (): Column<MigrationMappingRevision>[] => [
  { key: "version", header: "Version", value: (r) => r.versionNo, filter: "enum", cellClass: "num" },
  { key: "reason", header: "Reason", value: (r) => r.reason, filter: "text" },
  { key: "fields", header: "Field Decisions", value: (r) => r.fieldCount, filter: "enum", cellClass: "num" },
  { key: "created", header: "Recorded", value: (r) => when(r.createdAt), filter: "text" },
];
const reconciliationColumns = (): Column<MigrationReconciliationLine>[] => [
  { key: "source", header: "Source Object", value: (r) => r.sourceObject, filter: "text", groupable: true },
  { key: "target", header: "Target Entity", value: (r) => r.targetEntity, filter: "enum", groupable: true },
  { key: "sourceCount", header: "Source Records", value: (r) => r.sourceCount, filter: "enum", cellClass: "num" },
  { key: "targetCount", header: "Target Records", value: (r) => r.targetCount, filter: "enum", cellClass: "num" },
  { key: "missing", header: "Not Migrated", value: (r) => r.notMigratedCount, filter: "enum", cellClass: "num" },
  { key: "sourceAmount", header: "Source Amount", value: (r) => r.sourceAmountSum, filter: "text", cellClass: "num", blank: "Not Applicable" },
  { key: "targetAmount", header: "Target Amount", value: (r) => r.targetAmountSum, filter: "text", cellClass: "num", blank: "Not Applicable" },
  { key: "balanced", header: "Balanced", value: (r) => r.balanced, filter: "boolean", groupable: true, render: (r) => <span className={chip(r.balanced ? "COMPLETED" : "FAILED")}>{r.balanced ? "Yes" : "No"}</span> },
];
const issueColumns = (): Column<MigrationIssue>[] => [
  { key: "severity", header: "Severity", value: (r) => r.severity, filter: "enum", groupable: true },
  { key: "category", header: "Category", value: (r) => r.category, filter: "enum", groupable: true },
  { key: "object", header: "Source Object", value: (r) => r.sourceObject, filter: "text", groupable: true, blank: "Plan Level" },
  { key: "record", header: "Source Record", value: (r) => r.sourceLabel ?? r.sourceRecordId, filter: "text", blank: "Not Applicable" },
  { key: "field", header: "Field", value: (r) => r.fieldName, filter: "text", blank: "Not Applicable" },
  { key: "reason", header: "Recovery Detail", value: (r) => r.reason, filter: "text" },
];
const checkpointColumns = (): Column<MigrationCheckpoint>[] => [
  { key: "object", header: "Source Object", value: (r) => r.sourceObject, filter: "text", groupable: true },
  { key: "watermark", header: "Successful Watermark", value: (r) => when(r.watermark), filter: "text" },
  { key: "created", header: "Created In Last Run", value: (r) => r.recordsCreated, filter: "enum", cellClass: "num" },
  { key: "updated", header: "Updated In Last Run", value: (r) => r.recordsUpdated, filter: "enum", cellClass: "num" },
  { key: "run", header: "Last Successful Run", value: (r) => r.lastSuccessRunId, filter: "text", cellClass: "mono" },
];

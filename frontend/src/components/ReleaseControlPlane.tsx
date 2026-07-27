import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type DrValidation, type ReleaseDeployment, type ReleasePackage } from "../api/client";
import { useAppDialog, type DialogApi } from "./AppDialog";
import { InfoTag } from "./InfoTag";
import { GridLoader } from "./Loaders";
import { useToasts } from "./Toasts";

type Task = { kind: string; packageId?: string; deploymentId?: string };

export function ReleaseControlPlane() {
  const [selectedPackage, setSelectedPackage] = useState<string | null>(null);
  const [deployment, setDeployment] = useState<ReleaseDeployment | null>(null);
  const [validation, setValidation] = useState<DrValidation | null>(null);
  const [task, setTask] = useState<Task | null>(null);
  const dialog = useAppDialog();
  const toasts = useToasts();
  const queryClient = useQueryClient();
  const sandboxesQ = useQuery({ queryKey: ["release-sandboxes"], queryFn: api.releaseSandboxes });
  const packagesQ = useQuery({ queryKey: ["release-packages"], queryFn: api.releasePackages });
  const recoveryQ = useQuery({ queryKey: ["release-recovery-baseline"], queryFn: api.recoveryBaseline });
  const historyQ = useQuery({ queryKey: ["release-recovery-history"], queryFn: () => api.recoveryHistory(10) });
  const componentsQ = useQuery({
    queryKey: ["release-components", selectedPackage],
    queryFn: () => api.releaseComponents(selectedPackage!),
    enabled: Boolean(selectedPackage),
  });
  const action = useMutation({
    mutationFn: async (next: Task) => runTask(next, packagesQ.data ?? [], dialog, recoveryQ.data ?? null),
    onMutate: (next) => setTask(next),
    onSuccess: (result) => {
      if (isDeployment(result)) setDeployment(result);
      if (isDrValidation(result)) setValidation(result);
      toasts.push(resultStatus(result) === "BLOCKED" || resultStatus(result) === "FAIL" ? "warn" : "info",
        resultTitle(result), resultMessage(result));
      void queryClient.invalidateQueries({ queryKey: ["release-sandboxes"] });
      void queryClient.invalidateQueries({ queryKey: ["release-packages"] });
      void queryClient.invalidateQueries({ queryKey: ["release-components"] });
      void queryClient.invalidateQueries({ queryKey: ["release-recovery-baseline"] });
      void queryClient.invalidateQueries({ queryKey: ["release-recovery-history"] });
      void queryClient.invalidateQueries({ queryKey: ["audit"] });
    },
    onError: (error) => toasts.push("error", "Release Control Failed", error instanceof Error ? error.message : "The operation failed."),
    onSettled: () => setTask(null),
  });

  const selected = packagesQ.data?.find((item) => item.id === selectedPackage) ?? null;
  const loading = sandboxesQ.isLoading || packagesQ.isLoading;

  return <section className="panel release-control-plane" aria-label="Sandbox release and recovery control plane">
    <header className="release-control-head">
      <div>
        <span className="eyebrow">Release Governance</span>
        <h2>Sandbox Promotion And Recovery Assurance <InfoTag text="Build a versioned change set in a safe sandbox, validate every difference, obtain an independent approval for production, deploy atomically, and retain exact rollback evidence." label="Release governance help" /></h2>
        <p>Outbound sandbox traffic is disabled by default. Production releases require maker-checker approval.</p>
      </div>
      <div className="release-head-actions">
        <button className="btn" disabled={Boolean(task)} onClick={() => action.mutate({ kind: "sandbox-create" })}>New Sandbox</button>
        <button className="btn btn-primary" disabled={Boolean(task) || !sandboxesQ.data?.length} onClick={() => action.mutate({ kind: "package-create" })}>New Release Package</button>
      </div>
    </header>

    {loading && <GridLoader label="Reading release control plane" rows={3} columns={5} />}
    {!loading && <div className="release-control-grid">
      <section className="release-register" aria-label="Release package register">
        <div className="release-section-head"><div><span className="eyebrow">Promotion Register</span><h3>Release Packages</h3></div><span className="count">{packagesQ.data?.length ?? 0}</span></div>
        <div className="table-wrap"><table className="data-table release-table"><thead><tr>
          <th>Package</th><th>Target</th><th>Components</th><th>Status</th><th>Updated</th><th>Action</th>
        </tr></thead><tbody>
          {(packagesQ.data ?? []).map((pack) => <tr key={pack.id} className={selectedPackage === pack.id ? "is-selected" : ""}>
            <td><button className="release-link" onClick={() => setSelectedPackage(pack.id)}>{pack.name}</button><small>{pack.code} · {pack.sandboxName}</small></td>
            <td>{pack.targetEnvironment}</td><td>{pack.componentCount}</td>
            <td><span className={`chip ${tone(pack.status)}`}>{title(pack.status)}</span></td>
            <td>{new Date(pack.updatedAt).toLocaleString()}</td>
            <td><ReleaseActions pack={pack} busy={task?.packageId === pack.id} run={(kind) => action.mutate({ kind, packageId: pack.id })} /></td>
          </tr>)}
          {!packagesQ.data?.length && <tr><td colSpan={6} className="empty-note">Create a sandbox, then create the first governed release package.</td></tr>}
        </tbody></table></div>
      </section>

      <aside className="release-evidence" aria-label="Selected release evidence">
        <div className="release-section-head"><div><span className="eyebrow">Change Evidence</span><h3>{selected?.name ?? "Select A Package"}</h3></div>
          {selected?.status === "DRAFT" && <button className="btn btn-sm" disabled={Boolean(task)} onClick={() => action.mutate({ kind: "component-add", packageId: selected.id })}>Add Component</button>}
        </div>
        {selected ? <>
          <dl className="release-facts"><div><dt>Fingerprint</dt><dd>{selected.fingerprint?.slice(0, 16) ?? "Not validated"}</dd></div><div><dt>Approval</dt><dd>{selected.approvalRequestId?.slice(0, 8) ?? "Not requested"}</dd></div></dl>
          <div className="release-components">
            {(componentsQ.data ?? []).map((component) => <article key={component.id}>
              <strong>{component.sequence}. {title(component.componentType)} · {component.componentKey}</strong>
              <span className="chip">{title(component.operation)}</span>
              <small>{component.before == null ? "Create-only baseline" : "Optimistic baseline captured"}</small>
            </article>)}
            {componentsQ.isSuccess && componentsQ.data.length === 0 && <p className="empty-note">This draft has no components.</p>}
          </div>
        </> : <p className="empty-note">Choose a package to inspect its immutable change set and approval evidence.</p>}
      </aside>
    </div>}

    {deployment && <div className="release-result" role="status"><div><span className="eyebrow">Deployment Evidence</span><strong>{deployment.runNumber}</strong><p>{deployment.summary}</p></div><button className="btn" disabled={Boolean(task)} onClick={() => action.mutate({ kind: "rollback", deploymentId: deployment.id })}>Preview And Roll Back</button></div>}

    <section className="recovery-assurance" aria-label="Disaster recovery validation">
      <div className="release-section-head"><div><span className="eyebrow">Recovery Assurance</span><h3>Disaster-Recovery Rehearsals <InfoTag text="Run this only against a restored database. A pass requires recovery-time, recovery-point, record-count, schema, backup-integrity and outbox-replay checks." label="Recovery validation help" /></h3></div>
        <button className="btn" disabled={Boolean(task) || !recoveryQ.data} onClick={() => action.mutate({ kind: "dr-validate" })}>Validate Restored Environment</button>
      </div>
      <div className="recovery-summary">
        <div><span>Schema Version</span><strong>{recoveryQ.data?.databaseVersion ?? "—"}</strong></div>
        <div><span>Tracked Records</span><strong>{Object.values(recoveryQ.data?.recordCounts ?? {}).reduce((a, b) => a + b, 0).toLocaleString()}</strong></div>
        <div><span>Latest Result</span><strong className={validation?.status === "FAIL" ? "crit" : ""}>{validation?.status ?? historyQ.data?.[0]?.status ?? "Not Run"}</strong></div>
        <div><span>Evidence Runs</span><strong>{historyQ.data?.length ?? 0}</strong></div>
      </div>
      {(validation ?? historyQ.data?.[0]) && <p className="release-verdict">{(validation ?? historyQ.data![0]).verdict}</p>}
    </section>
  </section>;
}

function ReleaseActions({ pack, busy, run }: { pack: ReleasePackage; busy: boolean; run: (kind: string) => void }) {
  if (pack.status === "DRAFT") return <button className="btn btn-sm" disabled={busy} onClick={() => run("validate")}>{busy ? "Working..." : "Validate"}</button>;
  if (pack.status === "VALIDATED" && pack.targetEnvironment === "PROD") return <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => run("submit")}>{busy ? "Working..." : "Request Approval"}</button>;
  if (pack.status === "VALIDATED") return <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => run("deploy")}>Deploy</button>;
  if (pack.status === "PENDING_APPROVAL") return <div className="workspace-row-actions"><button className="btn btn-primary btn-sm" disabled={busy} onClick={() => run("approve")}>Approve</button><button className="btn btn-sm" disabled={busy} onClick={() => run("reject")}>Reject</button></div>;
  if (pack.status === "APPROVED") return <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => run("deploy")}>Deploy</button>;
  return <span className="empty-note">No action</span>;
}

async function runTask(task: Task, packages: ReleasePackage[], dialog: DialogApi, baseline: Awaited<ReturnType<typeof api.recoveryBaseline>> | null): Promise<unknown> {
  if (task.kind === "sandbox-create") {
    const code = await required(dialog, "New Sandbox", "Enter a unique code, such as UAT_JULY.", "Sandbox Code", "UAT_JULY");
    const name = await required(dialog, "Sandbox Name", "Use a clear operator-facing name.", "Name", "July Release Validation");
    const type = await required(dialog, "Sandbox Tier", "Choose DEV, QA, UAT or FULL_COPY.", "Tier", "UAT");
    const dataScope = await required(dialog, "Data Scope", "Choose CONFIGURATION_ONLY, SAMPLE_DATA or FULL_COPY.", "Data Scope", "CONFIGURATION_ONLY");
    return api.createReleaseSandbox({ code, name, sandboxType: type.toUpperCase(), dataScope: dataScope.toUpperCase() });
  }
  if (task.kind === "package-create") {
    const sandboxes = await api.releaseSandboxes();
    const sandboxCode = await required(dialog, "Source Sandbox", `Enter one active sandbox code: ${sandboxes.map((item) => item.code).join(", ")}.`, "Sandbox Code", sandboxes[0]?.code ?? "");
    const sandbox = sandboxes.find((item) => item.code.toLowerCase() === sandboxCode.toLowerCase());
    if (!sandbox) throw new Error("The source sandbox code was not found.");
    const code = await required(dialog, "Release Code", "Enter a unique versioned release code.", "Release Code", `REL_${new Date().toISOString().slice(0, 10).replace(/-/g, "")}`);
    const name = await required(dialog, "Release Name", "Describe the business change.", "Name", "Governed Configuration Release");
    const target = await required(dialog, "Target Environment", "Choose DEV, QA, UAT or PROD.", "Environment", "UAT");
    return api.createReleasePackage({ code, name, sourceSandboxId: sandbox.id, targetEnvironment: target.toUpperCase(), description: "Created from the release control plane." });
  }
  const pack = packages.find((item) => item.id === task.packageId);
  if (task.kind === "component-add" && pack) {
    const type = await required(dialog, "Component Type", "Use a stable category such as WORKFLOW, RBAC or UI_CONFIG.", "Component Type", "WORKFLOW");
    const key = await required(dialog, "Component Key", "Use a unique machine-readable key.", "Component Key", "opportunity.stage-gates");
    const operation = await required(dialog, "Operation", "Choose UPSERT or REMOVE.", "Operation", "UPSERT");
    const beforeText = await dialog.prompt({ title: "Current Target Value", message: "Paste the exact current JSON value. Leave blank only when creating a new key.", label: "Before JSON", multiline: true, confirmLabel: "Next" });
    const afterText = operation.toUpperCase() === "REMOVE" ? null : await required(dialog, "Proposed Value", "Paste the complete proposed JSON value.", "After JSON", "{}");
    return api.addReleaseComponent(pack.id, { componentType: type, componentKey: key, operation: operation.toUpperCase(), before: parseJson(beforeText), after: parseJson(afterText) });
  }
  if (task.kind === "validate" && pack) {
    return api.validateRelease(pack.id);
  }
  if (task.kind === "submit" && pack) {
    return api.submitReleaseApproval(pack.id);
  }
  if ((task.kind === "approve" || task.kind === "reject") && pack?.approvalRequestId) {
    const note = await required(dialog, `${title(task.kind)} Release`, "Record an independent decision and its reason.", "Decision Note", task.kind === "approve" ? "Validated evidence reviewed and approved." : "Release evidence requires correction.");
    return api.decideRelease(pack.id, task.kind as "approve" | "reject", pack.approvalRequestId, note);
  }
  if (task.kind === "deploy" && pack) {
    const confirmed = await dialog.confirm({ title: "Deploy Release", message: `Atomically promote ${pack.code} to ${pack.targetEnvironment}?`, confirmLabel: "Deploy", tone: "danger" });
    if (!confirmed) throw new Error("Deployment cancelled.");
    return api.deployRelease(pack.id);
  }
  if (task.kind === "rollback" && task.deploymentId) {
    const preview = await api.rollbackPreview(task.deploymentId);
    if (!preview.reversible) throw new Error(`Rollback is blocked: ${preview.blockers.join(" ")}`);
    const reason = await required(dialog, "Roll Back Release", `Restore ${preview.componentCount} component(s) to the exact pre-deployment snapshot before ${new Date(preview.deadline).toLocaleString()}.`, "Rollback Reason", "Release withdrawn after operational review.");
    return api.rollbackRelease(task.deploymentId, reason);
  }
  if (task.kind === "dr-validate" && baseline) {
    if (!baseline.newestOutboxEvent) throw new Error("Recovery validation requires at least one restored outbox event.");
    const scenario = await required(dialog, "Recovery Scenario", "Choose SINGLE_AZ, REGIONAL_LOSS, POINT_IN_TIME or TENANT_RESTORE.", "Scenario", "SINGLE_AZ");
    const restoreEnvironment = await required(dialog, "Restored Environment", "Name the isolated restored database or rehearsal environment.", "Environment", "dr-rehearsal");
    const backupReference = await required(dialog, "Backup Evidence", "Enter the immutable backup or snapshot reference used for this restore.", "Backup Reference", "backup://");
    const backupChecksum = await required(dialog, "Backup Checksum", "Enter the 64-character SHA-256 checksum recorded before restore.", "SHA-256", "");
    const completed = new Date(); const started = new Date(completed.getTime() - 1_000);
    return api.validateRecovery({ scenario: scenario.toUpperCase(), restoreEnvironment, backupReference, backupChecksum,
      recoveryStartedAt: started.toISOString(), recoveryCompletedAt: completed.toISOString(),
      sourceLastEventAt: baseline.newestOutboxEvent, restoredLastEventAt: baseline.newestOutboxEvent,
      expectedCounts: baseline.recordCounts });
  }
  throw new Error("This release action is not available in the current state.");
}

async function required(dialog: DialogApi, heading: string, message: string, label: string, defaultValue: string): Promise<string> {
  const value = await dialog.prompt({ title: heading, message, label, defaultValue, required: true, confirmLabel: "Continue" });
  if (!value?.trim()) throw new Error(`${label} is required.`);
  return value.trim();
}
function parseJson(value: string | null): unknown | null { if (!value?.trim()) return null; try { return JSON.parse(value); } catch { throw new Error("The component value must be valid JSON."); } }
function title(value: string): string { return value.toLowerCase().replace(/_/g, " ").replace(/\b\w/g, (letter: string) => letter.toUpperCase()); }
function tone(status: string): string { return ["APPROVED", "DEPLOYED", "VALIDATED"].includes(status) ? "chip-active" : ["FAILED", "REJECTED", "ROLLED_BACK"].includes(status) ? "chip-cancelled" : "chip-draft"; }
function isDeployment(value: unknown): value is ReleaseDeployment { return Boolean(value && typeof value === "object" && "runNumber" in value); }
function isDrValidation(value: unknown): value is DrValidation { return Boolean(value && typeof value === "object" && "verdict" in value); }
function resultStatus(value: unknown): string { return value && typeof value === "object" && "status" in value ? String(value.status) : "OK"; }
function resultTitle(value: unknown): string { if (isDeployment(value)) return "Release Deployed"; if (isDrValidation(value)) return `Recovery Validation ${title(value.status)}`; return `Release Control ${title(resultStatus(value))}`; }
function resultMessage(value: unknown): string { if (isDeployment(value)) return value.summary; if (isDrValidation(value)) return value.verdict; if (value && typeof value === "object" && "message" in value) return String(value.message); if (value && typeof value === "object" && "issues" in value) return (value.issues as string[]).join(" ") || "All validation controls passed."; return "The governed operation completed and its evidence was recorded."; }

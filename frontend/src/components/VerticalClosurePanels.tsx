import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  api, type BfsiDetail, type CommodityDetail, type OfflineConflict,
  type OfflineSnapshot, type WorkspaceRow,
} from "../api/client";
import { useAppDialog } from "./AppDialog";
import { InfoTag } from "./InfoTag";
import { useToasts } from "./Toasts";
import { formatMoney } from "../lib/format";

function statusClass(status: string) {
  if (["ACTIVE", "APPLIED", "SYNCED", "VERIFIED", "CLEAR", "APPROVED", "ACKNOWLEDGED"].includes(status)) return "good";
  if (["CONFLICT", "HIT", "BLOCKED", "EXCEPTION", "REJECTED", "FAILED", "EXPIRED"].includes(status)) return "crit";
  return "warn";
}

function age(seconds: number) {
  if (seconds < 60) return `${seconds}s old`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m old`;
  return `${Math.floor(seconds / 3600)}h old`;
}

export function MobileOfflinePanel({ devices }: { devices: WorkspaceRow[] }) {
  const [deviceId, setDeviceId] = useState("");
  const [packageId, setPackageId] = useState("");
  const dialog = useAppDialog();
  const toasts = useToasts();
  const queryClient = useQueryClient();
  useEffect(() => { if (!deviceId && devices[0]) setDeviceId(devices[0].id); }, [deviceId, devices]);
  const packagesQ = useQuery({ queryKey: ["offline-packages", deviceId], queryFn: () => api.offlinePackages(deviceId), enabled: !!deviceId });
  const packages = packagesQ.data ?? [];
  useEffect(() => { if (packages[0] && !packages.some((item) => item.id === packageId)) setPackageId(packages[0].id); }, [packageId, packages]);
  const snapshotsQ = useQuery({ queryKey: ["offline-snapshots", packageId], queryFn: () => api.offlineSnapshots(packageId), enabled: !!packageId });
  const conflictsQ = useQuery({ queryKey: ["offline-conflicts", deviceId], queryFn: () => api.offlineConflicts(deviceId), enabled: !!deviceId });
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["offline-packages", deviceId] });
    void queryClient.invalidateQueries({ queryKey: ["offline-snapshots", packageId] });
    void queryClient.invalidateQueries({ queryKey: ["offline-conflicts", deviceId] });
    void queryClient.invalidateQueries({ queryKey: ["workspace", "mobile"] });
    void queryClient.invalidateQueries({ queryKey: ["audit"] });
  };
  const action = useMutation({
    mutationFn: (task: () => Promise<unknown>) => task(),
    onSuccess: () => { refresh(); toasts.push("info", "Offline operation complete", "The device package and conflict evidence are up to date."); },
    onError: (error) => toasts.push("error", "Offline operation refused", error instanceof Error ? error.message : "Operation failed."),
  });

  async function edit(snapshot: OfflineSnapshot) {
    const allowed: Record<string, string[]> = {
      ACCOUNT: ["name", "industry"], CONTACT: ["firstName", "lastName", "email", "title"],
      LEAD: ["firstName", "lastName", "company", "email"], OPPORTUNITY: ["name", "amount", "closeDate"],
    };
    const field = await dialog.prompt({ title: "Offline Edit", message: `Editable fields: ${(allowed[snapshot.entityType] ?? []).join(", ")}.`, label: "Field", defaultValue: allowed[snapshot.entityType]?.[0] ?? "", required: true, confirmLabel: "Next" });
    if (!field || !allowed[snapshot.entityType]?.includes(field)) { if (field) toasts.push("error", "Field not editable offline", "Choose one of the listed fields."); return; }
    const original = snapshot.payload[field];
    const raw = await dialog.prompt({ title: "Offline Value", message: `This change is queued against server version ${snapshot.recordVersion}.`, label: "New Value", defaultValue: String(original ?? ""), required: true, confirmLabel: "Synchronize" });
    if (raw === null) return;
    const value = field === "amount" ? Number(raw) : raw;
    action.mutate(() => api.synchronizeOffline(packageId, [{ clientMutationId: crypto.randomUUID(), entityType: snapshot.entityType, recordId: snapshot.recordId, baseVersion: snapshot.recordVersion, patch: { [field]: value } }]));
  }

  async function resolve(conflict: OfflineConflict, resolution: "SERVER_WINS" | "CLIENT_WINS" | "MERGED") {
    const reason = await dialog.prompt({ title: "Resolve Sync Conflict", message: `${conflict.entityType} changed from version ${conflict.baseVersion} to ${conflict.serverVersion}. The decision is audited.`, label: "Resolution Reason", defaultValue: resolution === "SERVER_WINS" ? "Keep the newer server record." : "Reviewed both versions and approved the offline values.", required: true, multiline: true, confirmLabel: "Resolve" });
    if (!reason) return;
    let merged: Record<string, unknown> | undefined;
    if (resolution === "MERGED") {
      const payload = await dialog.prompt({ title: "Merged Values", message: "Provide only the editable fields that should be applied.", label: "JSON Patch", defaultValue: JSON.stringify(conflict.clientPatch, null, 2), required: true, multiline: true, confirmLabel: "Apply Merge" });
      if (!payload) return;
      try { merged = JSON.parse(payload) as Record<string, unknown>; } catch { toasts.push("error", "Invalid JSON", "The merged values must be a JSON object."); return; }
    }
    action.mutate(() => api.resolveOfflineConflict(conflict.id, resolution, reason, merged));
  }

  return <section className="release-control-plane closure-plane" aria-label="Offline data and synchronization control plane">
    <header className="release-control-head">
      <div><span className="eyebrow">Offline control plane</span><h2>Data Packages And Conflict Resolution <InfoTag text="Packages contain only records the device user can read. Every edit is re-authorized and version-checked when connectivity returns." label="Offline synchronization help" /></h2><p>Cache age is visible. Server changes never disappear behind silent last-write-wins.</p></div>
      <button className="btn" disabled={!deviceId || action.isPending} onClick={() => action.mutate(() => api.createOfflinePackage(deviceId, ["ACCOUNT", "CONTACT", "LEAD", "OPPORTUNITY"]))}>Generate Fresh Package</button>
    </header>
    <div className="release-grid">
      <div className="release-card">
        <label><span>Device</span><select value={deviceId} onChange={(event) => { setDeviceId(event.target.value); setPackageId(""); }}>{devices.map((device) => <option key={device.id} value={device.id}>{device.code} · {device.status}</option>)}</select></label>
        <h3>Offline Packages</h3>
        <div className="evidence-list">{packages.map((pack) => <button className={`release-package-row ${pack.id === packageId ? "selected" : ""}`} key={pack.id} onClick={() => setPackageId(pack.id)}><strong>{pack.packageNumber}</strong><span>{pack.objectCount} records · {age(pack.cacheAgeSeconds)}</span><span className={`chip ${statusClass(pack.status)}`}>{pack.status}</span></button>)}{!packages.length && <p className="empty-note">No offline package has been generated for this device.</p>}</div>
      </div>
      <div className="release-card">
        <h3>Cached Records</h3><p className="muted">Select an editable record to simulate a field change made without connectivity.</p>
        <div className="release-table-wrap"><table className="release-table"><thead><tr><th>Object</th><th>Record</th><th>Version</th><th>Cached</th><th>Action</th></tr></thead><tbody>{(snapshotsQ.data ?? []).map((item) => <tr key={`${item.entityType}-${item.recordId}`}><td>{item.entityType}</td><td>{String(item.payload.name ?? item.payload.company ?? item.payload.email ?? item.recordId)}</td><td>{item.recordVersion}</td><td>{new Date(item.cachedAt).toLocaleTimeString()}</td><td><button className="btn btn-sm" disabled={action.isPending} onClick={() => void edit(item)}>Edit Offline</button></td></tr>)}</tbody></table></div>
      </div>
    </div>
    <div className="release-card closure-wide-card"><h3>Conflicts Needing A Person</h3><p className="muted">Both versions remain visible until an explicit, reasoned decision is made.</p>{(conflictsQ.data ?? []).map((conflict) => <article className="conflict-card" key={conflict.id}><div><strong>{conflict.entityType} · {conflict.recordId}</strong><p>Offline v{conflict.baseVersion} / server v{conflict.serverVersion} · fields {conflict.conflictingFields.join(", ")}</p></div><pre>{JSON.stringify({ offline: conflict.clientPatch, server: conflict.serverPayload }, null, 2)}</pre><div className="button-row"><button className="btn btn-sm" onClick={() => void resolve(conflict, "SERVER_WINS")}>Keep Server</button><button className="btn btn-sm" onClick={() => void resolve(conflict, "CLIENT_WINS")}>Apply Offline</button><button className="btn btn-sm" onClick={() => void resolve(conflict, "MERGED")}>Merge Fields</button></div></article>)}{!conflictsQ.data?.length && <p className="empty-note">No open conflicts.</p>}</div>
  </section>;
}

export function BfsiOperationsPanel() {
  const [selectedId, setSelectedId] = useState("");
  const dialog = useAppDialog(); const toasts = useToasts(); const queryClient = useQueryClient();
  const listQ = useQuery({ queryKey: ["bfsi-lifecycle-list"], queryFn: () => api.bfsiOnboardings() });
  useEffect(() => { if (!selectedId && listQ.data?.[0]) setSelectedId(listQ.data[0].id); }, [listQ.data, selectedId]);
  const detailQ = useQuery({ queryKey: ["bfsi-lifecycle-detail", selectedId], queryFn: () => api.bfsiDetail(selectedId), enabled: !!selectedId });
  const refresh = () => { void queryClient.invalidateQueries({ queryKey: ["bfsi-lifecycle"] }); void queryClient.invalidateQueries({ queryKey: ["workspace", "bfsi"] }); void queryClient.invalidateQueries({ queryKey: ["audit"] }); };
  const action = useMutation({ mutationFn: (task: () => Promise<unknown>) => task(), onSuccess: () => { refresh(); toasts.push("info", "BFSI workflow updated", "Governed evidence and next-step status were refreshed."); }, onError: (error) => toasts.push("error", "BFSI gate refused the action", error instanceof Error ? error.message : "Action failed.") });
  const data: BfsiDetail | undefined = detailQ.data;

  async function verifyKyc(itemId: string) { const evidence = await dialog.prompt({ title: "Verify KYC Item", message: "Reference the immutable document or evidence record.", label: "Evidence Reference", defaultValue: `vault://kyc/${itemId}`, required: true, confirmLabel: "Verify" }); if (!evidence) return; const expiry = new Date(); expiry.setFullYear(expiry.getFullYear() + 1); action.mutate(() => api.updateBfsiKyc(selectedId, itemId, { status: "VERIFIED", evidenceReference: evidence, expiresAt: expiry.toISOString().slice(0, 10) })); }
  async function runScreening() { const type = await dialog.prompt({ title: "Record Screening Run", message: "Use SANCTIONS, PEP or ADVERSE_MEDIA.", label: "Screening Type", defaultValue: "SANCTIONS", required: true, confirmLabel: "Next" }); if (!type) return; const hits = await dialog.prompt({ title: "Screening Results", message: "Enter the number of hits returned by the screening source.", label: "Hit Count", defaultValue: "0", required: true, confirmLabel: "Record Run" }); if (hits === null) return; action.mutate(() => api.runBfsiScreening(selectedId, { screeningType: type, hitCount: Number(hits), sourceSystem: "FIRST_PARTY_MANUAL", result: { operatorRecorded: true } })); }
  async function rateRisk() { const geography = await dialog.prompt({ title: "Risk Factors", message: "Score geography risk from 0 to 100.", label: "Geography Score", defaultValue: "35", required: true, confirmLabel: "Next" }); if (geography === null) return; const ownership = await dialog.prompt({ title: "Risk Factors", message: "Score ownership-complexity risk from 0 to 100.", label: "Ownership Score", defaultValue: "45", required: true, confirmLabel: "Calculate" }); if (ownership === null) return; action.mutate(() => api.rateBfsiRisk(selectedId, [{ factor: "Geography", weight: 50, score: Number(geography), evidence: "Operator assessment" }, { factor: "Ownership complexity", weight: 50, score: Number(ownership), evidence: "KYC ownership evidence" }], "Weighted review completed in the BFSI operator workspace.")); }
  async function createException() { const type = await dialog.prompt({ title: "Raise BFSI Exception", message: "Exception types: KYC, SCREENING, RISK, SUITABILITY or HOLDING.", label: "Exception Type", defaultValue: "KYC", required: true, confirmLabel: "Next" }); if (!type) return; const reason = await dialog.prompt({ title: "Exception Rationale", message: "This is submitted for independent maker-checker approval.", label: "Reason", required: true, multiline: true, confirmLabel: "Submit" }); if (reason) action.mutate(() => api.createBfsiException(selectedId, type, reason)); }

  return <section className="release-control-plane closure-plane" aria-label="BFSI lifecycle control plane"><header className="release-control-head"><div><span className="eyebrow">Regulated client lifecycle</span><h2>BFSI Onboarding And Exceptions <InfoTag text="Relationship activation is blocked until every named KYC owner, screening hit and risk gate is resolved. Suitability overrides require another approver." label="BFSI workflow help" /></h2><p>One operator view for KYC, screening, defensible risk, holdings, whitespace and exceptions.</p></div><button className="btn" disabled={!selectedId || action.isPending} onClick={() => action.mutate(() => api.activateBfsiRelationship(selectedId, "All prerequisites reviewed in the BFSI workspace."))}>Activate Relationship</button></header>
    <div className="release-grid"><div className="release-card"><h3>Client Book</h3><div className="evidence-list">{(listQ.data ?? []).map((item) => <button key={item.id} className={`release-package-row ${selectedId === item.id ? "selected" : ""}`} onClick={() => setSelectedId(item.id)}><strong>{item.accountName}</strong><span>{item.number} · {item.owner}</span><span className={`chip ${statusClass(item.relationshipStatus)}`}>{item.relationshipStatus}</span></button>)}</div></div>
      <div className="release-card"><h3>Gate Summary</h3>{data && <div className="recovery-summary"><div><span>Missing KYC</span><strong>{data.onboarding.missingKyc}</strong></div><div><span>Open Hits</span><strong>{data.onboarding.openHits}</strong></div><div><span>Risk</span><strong>{data.onboarding.riskRating} {data.onboarding.riskScore ?? ""}</strong></div><div><span>Exceptions</span><strong>{data.onboarding.openExceptions}</strong></div></div>}<div className="button-row"><button className="btn btn-sm" onClick={() => void runScreening()}>Run Screening</button><button className="btn btn-sm" onClick={() => void rateRisk()}>Calculate Risk</button><button className="btn btn-sm" onClick={() => void createException()}>Raise Exception</button></div></div></div>
    {data && <div className="closure-columns"><div className="release-card"><h3>KYC Checklist</h3>{data.kycItems.map((item) => <div className="closure-list-row" key={item.id}><div><strong>{item.name}</strong><span>{item.owner} · {item.expiresAt ?? "No expiry"}</span></div><span className={`chip ${statusClass(item.status)}`}>{item.status}</span>{item.status !== "VERIFIED" && <button className="btn btn-sm" onClick={() => void verifyKyc(item.id)}>Verify</button>}</div>)}</div>
      <div className="release-card"><h3>Screenings</h3>{data.screenings.map((item) => <div className="closure-list-row" key={item.id}><div><strong>{item.type}</strong><span>{item.source} · {item.hitCount} hits</span></div><span className={`chip ${statusClass(item.status)}`}>{item.status}</span>{item.status === "HIT" && !item.disposition && <button className="btn btn-sm" onClick={() => action.mutate(() => api.dispositionBfsiScreening(item.id, "FALSE_POSITIVE", "Reviewed the evidence and documented a false-positive disposition."))}>Disposition</button>}</div>)}</div>
      <div className="release-card"><h3>Holdings And Whitespace</h3>{data.holdings.map((item) => <div className="closure-list-row" key={item.id}><div><strong>{item.productName ?? item.family}</strong><span>{formatMoney(item.balanceAmount)} · {item.status}</span></div></div>)}{data.whitespace.map((gap) => <div className="closure-list-row" key={gap.productId}><div><strong>{gap.productName}</strong><span>Whitespace · minimum {gap.minimumSuitability}</span></div><button className="btn btn-sm" onClick={() => action.mutate(() => api.addBfsiHolding(selectedId, { productId: gap.productId, status: "PROPOSED", balanceAmount: 0 }))}>Propose</button><button className="btn btn-sm" onClick={() => action.mutate(() => api.recommendBfsiProduct(selectedId, gap.productId, gap.minimumSuitability === "BASIC" || gap.minimumSuitability === "STANDARD" ? undefined : "Client need documented; request independent suitability override."))}>Recommend</button></div>)}<button className="btn btn-sm" onClick={() => { const expires = new Date(Date.now() + 365 * 86400000).toISOString(); action.mutate(() => api.assessBfsiSuitability(selectedId, "STANDARD", { objectives: "Capital preservation", knowledge: "Standard" }, expires)); }}>Record Standard Suitability</button></div>
      <div className="release-card"><h3>Approvals And Exceptions</h3>{data.recommendations.map((item) => <div className="closure-list-row" key={item.id}><div><strong>{item.productName}</strong><span>{item.outsideSuitability ? "Override required" : "Within suitability"}</span></div><span className={`chip ${statusClass(item.status)}`}>{item.status}</span>{item.status === "PENDING_APPROVAL" && item.approvalRequestId && <button className="btn btn-sm" onClick={() => action.mutate(() => api.decideBfsiRecommendation(item.id, "approve", item.approvalRequestId!, "Independent suitability review approved."))}>Approve</button>}</div>)}{data.exceptions.map((item) => <div className="closure-list-row" key={item.id}><div><strong>{item.type} exception</strong><span>{item.reason}</span></div><span className={`chip ${statusClass(item.status)}`}>{item.status}</span>{item.status === "PENDING_APPROVAL" && item.approvalRequestId && <button className="btn btn-sm" onClick={() => action.mutate(() => api.decideBfsiException(item.id, "approve", item.approvalRequestId!, "Independent exception review approved with controls."))}>Approve</button>}</div>)}</div></div>}
  </section>;
}

export function CommodityOperationsPanel() {
  const [selectedId, setSelectedId] = useState(""); const dialog = useAppDialog(); const toasts = useToasts(); const queryClient = useQueryClient();
  const listQ = useQuery({ queryKey: ["commodity-lifecycle-list"], queryFn: () => api.commodityEnquiries() });
  useEffect(() => { if (!selectedId && listQ.data?.[0]) setSelectedId(listQ.data[0].id); }, [listQ.data, selectedId]);
  const detailQ = useQuery({ queryKey: ["commodity-lifecycle-detail", selectedId], queryFn: () => api.commodityDetail(selectedId), enabled: !!selectedId });
  const refresh = () => { void queryClient.invalidateQueries({ queryKey: ["commodity-lifecycle"] }); void queryClient.invalidateQueries({ queryKey: ["workspace", "commodity"] }); void queryClient.invalidateQueries({ queryKey: ["audit"] }); };
  const action = useMutation({ mutationFn: (task: () => Promise<unknown>) => task(), onSuccess: () => { refresh(); toasts.push("info", "Commodity workflow updated", "Origination, approval and execution evidence were refreshed."); }, onError: (error) => toasts.push("error", "Commodity gate refused the action", error instanceof Error ? error.message : "Action failed.") });
  const data: CommodityDetail | undefined = detailQ.data;
  async function addPrice() { const indexName = await dialog.prompt({ title: "Indicative Price", message: "No settlement price or mark-to-market is computed by Axiom.", label: "Index", defaultValue: "LME Cash", required: true, confirmLabel: "Next" }); if (!indexName) return; const differential = await dialog.prompt({ title: "Differential", message: "Use a human-readable premium or discount expression.", label: "Differential", defaultValue: "+ USD 25/MT", required: true, confirmLabel: "Record Indication" }); if (differential) action.mutate(() => api.priceCommodity(selectedId, { indexName, differential, quotationPeriod: "Monthly average", settlementConvention: "As agreed in final contract" })); }
  async function createTerm() { const basis = await dialog.prompt({ title: "Create Term Sheet", message: "The term stays draft until independent approval.", label: "Pricing Basis", defaultValue: "LME Cash + agreed differential", required: true, confirmLabel: "Create" }); if (basis && data) action.mutate(() => api.createCommodityTerm(selectedId, { incoterm: data.enquiry.incoterm, pricingBasis: basis, terms: { quantity: data.enquiry.quantity, tolerancePct: data.enquiry.tolerancePct, deliveryWindow: [data.enquiry.deliveryStart, data.enquiry.deliveryEnd] } })); }
  return <section className="release-control-plane closure-plane" aria-label="Commodity origination control plane"><header className="release-control-head"><div><span className="eyebrow">Trading origination</span><h2>Commodity Pricing, Approval And Execution <InfoTag text="Axiom captures origination and displays source-mastered credit. It never computes settlement or exposure. Missing/stale source data fails closed." label="Commodity workflow help" /></h2><p>Relationship work remains available during CTRM outages; approved handoffs wait safely in the execution queue.</p></div><button className="btn" disabled={action.isPending} onClick={() => action.mutate(() => api.sweepCommodityTenders())}>Run Tender Sweep</button></header>
    <div className="release-grid"><div className="release-card"><h3>Origination Register</h3><div className="evidence-list">{(listQ.data ?? []).map((item) => <button key={item.id} className={`release-package-row ${selectedId === item.id ? "selected" : ""}`} onClick={() => setSelectedId(item.id)}><strong>{item.number}</strong><span>{item.type} · {item.commodity} · {item.counterparty.code}</span><span className={`chip ${statusClass(item.status)}`}>{item.status}</span></button>)}</div></div>
      <div className="release-card"><h3>Fail-Closed Gates</h3>{data && <><div className="recovery-summary"><div><span>Agreement</span><strong>{data.enquiry.counterparty.agreementStatus}</strong></div><div><span>Credit</span><strong>{data.enquiry.counterparty.creditFresh ? "Fresh" : "Stale"}</strong></div><div><span>Headroom</span><strong>{formatMoney(data.enquiry.counterparty.headroom ?? 0)}</strong></div><div><span>Execution</span><strong>{data.enquiry.executionStatus}</strong></div></div>{data.gates.length ? <ul className="blocker-list">{data.gates.map((gate) => <li key={gate}>{gate}</li>)}</ul> : <p className="success-note">Master agreement and source credit gates are ready.</p>}</>}</div></div>
    {data && <div className="closure-columns"><div className="release-card"><h3>Enquiry Attributes</h3><dl className="evidence-dl"><dt>Commodity / grade</dt><dd>{data.enquiry.commodity} / {data.enquiry.grade}</dd><dt>Quantity</dt><dd>{data.enquiry.quantity} {data.enquiry.unit} ± {data.enquiry.tolerancePct}%</dd><dt>Delivery</dt><dd>{data.enquiry.locationFrom} → {data.enquiry.locationTo} · {data.enquiry.incoterm}</dd><dt>Source credit</dt><dd>{data.enquiry.counterparty.source ?? "Unavailable"} · {data.enquiry.counterparty.asOf ? new Date(data.enquiry.counterparty.asOf).toLocaleString() : "No timestamp"}</dd></dl><div className="button-row"><button className="btn btn-sm" onClick={() => void addPrice()}>Add Indicative Price</button><button className="btn btn-sm" onClick={() => void createTerm()}>Create Term Sheet</button><button className="btn btn-sm" onClick={() => action.mutate(() => api.refreshCommoditySource(data.enquiry.counterparty.id, { sourceSystem: "AXIOM_LOCAL_CTRM", sourceAsOf: new Date().toISOString(), creditLimit: data.enquiry.counterparty.creditLimit, exposure: data.enquiry.counterparty.exposure, headroom: data.enquiry.counterparty.headroom ?? 0, agreementStatus: data.enquiry.counterparty.agreementStatus, agreementReference: data.enquiry.counterparty.agreementReference ?? undefined, agreementExpiresAt: data.enquiry.counterparty.agreementExpiresAt ?? undefined }))}>Refresh Source Evidence</button></div></div>
      <div className="release-card"><h3>Indicative Pricing</h3>{data.prices.map((price) => <div className="closure-list-row" key={price.id}><div><strong>{price.expression}</strong><span>{price.label}</span></div><span className={`chip ${statusClass(price.status)}`}>{price.status}</span></div>)}{!data.prices.length && <p className="empty-note">No price indication. Axiom will not invent one.</p>}</div>
      <div className="release-card"><h3>Term Approval</h3>{data.termSheets.map((term) => <div className="closure-list-row" key={term.id}><div><strong>{term.number}</strong><span>{term.pricingBasis} · v{term.version}</span></div><span className={`chip ${statusClass(term.status)}`}>{term.status}</span>{term.status === "DRAFT" && <button className="btn btn-sm" onClick={() => action.mutate(() => api.submitCommodityTerm(term.id, "Commercial terms ready for independent review."))}>Submit</button>}{term.status === "IN_REVIEW" && term.approvalRequestId && <button className="btn btn-sm" onClick={() => action.mutate(() => api.decideCommodityTerm(term.id, "approve", term.approvalRequestId!, "Independent term review approved."))}>Approve</button>}</div>)}<button className="btn btn-sm" disabled={data.enquiry.status === "OFFERED" || action.isPending} onClick={() => action.mutate(() => api.releaseCommodityOffer(selectedId))}>Release Offer</button></div>
      <div className="release-card"><h3>Execution Handoff</h3>{data.handoffs.map((handoff) => <div className="closure-list-row" key={handoff.id}><div><strong>{handoff.idempotencyKey}</strong><span>{handoff.attempts}/{handoff.maxAttempts} attempts · {handoff.tradeReference ?? "No trade reference"}</span></div><span className={`chip ${statusClass(handoff.status)}`}>{handoff.status}</span>{handoff.status === "QUEUED" && <button className="btn btn-sm" onClick={() => action.mutate(() => api.recordCommodityHandoffAttempt(handoff.id, true))}>Record Delivery</button>}{handoff.status === "DELIVERED" && <button className="btn btn-sm" onClick={async () => { const ref = await dialog.prompt({ title: "Acknowledge Trade", message: "Store the trade reference returned by the CTRM adapter.", label: "Trade Reference", required: true, confirmLabel: "Acknowledge" }); if (ref) action.mutate(() => api.acknowledgeCommodityHandoff(handoff.id, ref)); }}>Acknowledge</button>}</div>)}{data.enquiry.status === "OFFERED" && <button className="btn btn-sm" onClick={() => action.mutate(() => api.closeCommodityWon(selectedId, "Commercial terms agreed; queue connector-neutral execution handoff."))}>Close Won And Queue</button>}{data.exceptions.map((item) => <div className="exception-note" key={item.id}><strong>{item.type}</strong><span>{item.reason}</span></div>)}</div></div>}
  </section>;
}

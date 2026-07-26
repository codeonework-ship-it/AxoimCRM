import { Fragment, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  integrationApi,
  type Connector,
  type ConnectorHealth,
  type ConnectorType,
  type DeadLetter,
  type Delivery,
} from "../api/integration";
import { useAuth } from "../auth/AuthContext";
import { canManageMasters } from "../components/MasterToolbar";
import { GridLoader, InlineLoader, LoaderStatus, PanelLoader } from "../components/Loaders";
import { useToasts } from "../components/Toasts";

/**
 * Integration dispatch workspace.
 *
 * Four surfaces, because an administrator debugging a broken integration asks
 * four questions in order: is it working, what did it send, what got lost, and
 * which key is it using. Anything that hides the third question is the silent
 * failure FR-INT-009 calls a defect, so the dead-letter list is a tab of its
 * own rather than a detail buried in a connector.
 */

type TabKey = "connectors" | "deliveries" | "dead-letters" | "credentials";

const TABS: { key: TabKey; label: string }[] = [
  { key: "connectors", label: "Connectors" },
  { key: "deliveries", label: "Dispatch log" },
  { key: "dead-letters", label: "Dead letters" },
  { key: "credentials", label: "Credentials" },
];

const CONNECTOR_TYPES: ConnectorType[] = ["WEBHOOK", "ERP", "ESIGN", "MARKETING", "ENRICHMENT", "CTRM"];

/** Maps a status onto the chip variants the theme already defines — no new CSS. */
function statusChip(status: string): string {
  switch (status) {
    case "HEALTHY":
    case "SUCCESS":
    case "SUCCEEDED":
      return "chip chip-qualified";
    case "DEGRADED":
    case "PROBING":
    case "PENDING":
    case "IN_FLIGHT":
    case "RETRYABLE_FAILURE":
      return "chip chip-working";
    case "PAUSED":
    case "DEAD_LETTERED":
    case "PERMANENT_FAILURE":
    case "BLOCKED_BY_BREAKER":
      return "chip chip-lost";
    case "IDLE":
    case "DISABLED":
      return "chip chip-disqualified";
    default:
      return "chip";
  }
}

function when(value: string | null | undefined): string {
  if (!value) return "-";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? "-" : parsed.toLocaleString();
}

const EMPTY_CONNECTOR = {
  connectorType: "WEBHOOK" as ConnectorType,
  vendor: "GENERIC_WEBHOOK",
  displayName: "",
  url: "",
  timeoutMs: "5000",
  credentialRef: "",
};

const EMPTY_CREDENTIAL = { name: "", credentialType: "WEBHOOK_SIGNING_SECRET", secret: "", description: "" };

export function IntegrationDispatchPage() {
  const { user } = useAuth();
  const toasts = useToasts();
  const queryClient = useQueryClient();
  const canManage = canManageMasters(user?.role);

  const [tab, setTab] = useState<TabKey>("connectors");
  const [selectedConnectorId, setSelectedConnectorId] = useState<string | null>(null);
  const [openDeliveryId, setOpenDeliveryId] = useState<string | null>(null);
  const [openDeadLetterId, setOpenDeadLetterId] = useState<string | null>(null);
  const [connectorDraft, setConnectorDraft] = useState(EMPTY_CONNECTOR);
  const [subscriptionDraft, setSubscriptionDraft] = useState({ pattern: "", filter: "" });
  const [credentialDraft, setCredentialDraft] = useState(EMPTY_CREDENTIAL);
  const [rotating, setRotating] = useState<{ name: string; secret: string } | null>(null);

  const healthQ = useQuery({ queryKey: ["integration", "health"], queryFn: integrationApi.health, retry: 1 });
  const summaryQ = useQuery({ queryKey: ["integration", "summary"], queryFn: integrationApi.healthSummary, retry: 1 });
  const connectorsQ = useQuery({ queryKey: ["integration", "connectors"], queryFn: integrationApi.connectors, retry: 1 });
  const adaptersQ = useQuery({ queryKey: ["integration", "adapters"], queryFn: integrationApi.adapters, retry: 1 });
  const credentialsQ = useQuery({ queryKey: ["integration", "credentials"], queryFn: integrationApi.credentials, retry: 1 });
  const deliveriesQ = useQuery({
    queryKey: ["integration", "deliveries", selectedConnectorId],
    queryFn: () => integrationApi.deliveries(selectedConnectorId ?? undefined),
    retry: 1,
  });
  const deadLettersQ = useQuery({
    queryKey: ["integration", "dead-letters", selectedConnectorId],
    queryFn: () => integrationApi.deadLetters(selectedConnectorId ?? undefined, true),
    retry: 1,
  });
  const subscriptionsQ = useQuery({
    queryKey: ["integration", "subscriptions", selectedConnectorId],
    queryFn: () => integrationApi.subscriptions(selectedConnectorId as string),
    enabled: !!selectedConnectorId,
    retry: 1,
  });
  const attemptsQ = useQuery({
    queryKey: ["integration", "attempts", openDeliveryId],
    queryFn: () => integrationApi.attempts(openDeliveryId as string),
    enabled: !!openDeliveryId,
    retry: 1,
  });

  const connectors = useMemo<Connector[]>(() => connectorsQ.data ?? [], [connectorsQ.data]);
  const health = useMemo<ConnectorHealth[]>(() => healthQ.data ?? [], [healthQ.data]);
  const selectedConnector = connectors.find((c) => c.id === selectedConnectorId) ?? null;

  function refreshAll() {
    void queryClient.invalidateQueries({ queryKey: ["integration"] });
  }

  const runWorker = useMutation({
    mutationFn: integrationApi.runWorker,
    onSuccess: (result) => {
      toasts.push("info", "Dispatch run complete",
        `${result.queued} queued, ${result.attempted} attempted, ${result.succeeded} delivered, ${result.failed} failed.`);
      refreshAll();
    },
    onError: (error) => toasts.push("error", "Dispatch run failed", messageOf(error)),
  });

  const createConnector = useMutation({
    mutationFn: () => integrationApi.createConnector({
      connectorType: connectorDraft.connectorType,
      vendor: connectorDraft.vendor.trim(),
      displayName: connectorDraft.displayName.trim(),
      enabled: true,
      config: connectorDraft.url.trim()
        ? { url: connectorDraft.url.trim(), timeoutMs: Number(connectorDraft.timeoutMs) || 5000 }
        : {},
      credentialRef: connectorDraft.credentialRef.trim() || null,
    }),
    onSuccess: (created) => {
      setConnectorDraft(EMPTY_CONNECTOR);
      setSelectedConnectorId(created.id);
      toasts.push("info", "Connector registered", `${created.displayName} is enabled and ready for subscriptions.`);
      refreshAll();
    },
    onError: (error) => toasts.push("error", "Connector rejected", messageOf(error)),
  });

  const toggleConnector = useMutation({
    mutationFn: (input: { id: string; enabled: boolean }) =>
      integrationApi.setConnectorEnabled(input.id, input.enabled),
    onSuccess: (row) => {
      toasts.push("info", row.enabled ? "Connector resumed" : "Connector paused",
        row.enabled ? "New events will queue again." : "No new events will be queued for this connector.");
      refreshAll();
    },
    onError: (error) => toasts.push("error", "Change rejected", messageOf(error)),
  });

  const addSubscription = useMutation({
    mutationFn: () => integrationApi.addSubscription(selectedConnectorId as string, {
      eventTypePattern: subscriptionDraft.pattern.trim(),
      filterExpression: subscriptionDraft.filter.trim() || null,
      active: true,
    }),
    onSuccess: () => {
      setSubscriptionDraft({ pattern: "", filter: "" });
      toasts.push("info", "Subscription added", "Matching events will be dispatched from now on.");
      refreshAll();
    },
    onError: (error) => toasts.push("error", "Subscription rejected", messageOf(error)),
  });

  const toggleSubscription = useMutation({
    mutationFn: (input: { id: string; pattern: string; filter: string | null; active: boolean }) =>
      integrationApi.updateSubscription(selectedConnectorId as string, input.id, {
        eventTypePattern: input.pattern,
        filterExpression: input.filter,
        active: input.active,
      }),
    onSuccess: () => refreshAll(),
    onError: (error) => toasts.push("error", "Change rejected", messageOf(error)),
  });

  const removeSubscription = useMutation({
    mutationFn: (id: string) => integrationApi.deleteSubscription(selectedConnectorId as string, id),
    onSuccess: () => {
      toasts.push("info", "Subscription removed", "No further events will be queued for it.");
      refreshAll();
    },
    onError: (error) => toasts.push("error", "Removal rejected", messageOf(error)),
  });

  const replayOne = useMutation({
    mutationFn: (id: string) => integrationApi.replay(id),
    onSuccess: (outcome) => {
      toasts.push("info", outcome.requeued ? "Queued for retry" : "Already queued", outcome.detail);
      refreshAll();
    },
    onError: (error) => toasts.push("error", "Retry rejected", messageOf(error)),
  });

  const replayMany = useMutation({
    mutationFn: (ids: string[]) => integrationApi.replayMany(ids),
    onSuccess: (outcomes) => {
      const requeued = outcomes.filter((o) => o.requeued).length;
      toasts.push("info", "Retry requested",
        `${requeued} of ${outcomes.length} message(s) queued again. The rest were already queued.`);
      refreshAll();
    },
    onError: (error) => toasts.push("error", "Retry rejected", messageOf(error)),
  });

  const createCredential = useMutation({
    mutationFn: () => integrationApi.createCredential({
      name: credentialDraft.name.trim(),
      credentialType: credentialDraft.credentialType,
      secret: credentialDraft.secret,
      description: credentialDraft.description.trim() || null,
    }),
    onSuccess: (row) => {
      setCredentialDraft(EMPTY_CREDENTIAL);
      toasts.push("info", "Credential stored",
        `${row.name} is encrypted. Axiom will not show the value again — keep your own copy.`);
      refreshAll();
    },
    onError: (error) => toasts.push("error", "Credential rejected", messageOf(error)),
  });

  const rotateCredential = useMutation({
    mutationFn: (input: { name: string; secret: string }) =>
      integrationApi.rotateCredential(input.name, input.secret),
    onSuccess: (row) => {
      setRotating(null);
      toasts.push("info", "Credential rotated",
        `Every connector referencing ${row.name} now uses the new value.`);
      refreshAll();
    },
    onError: (error) => toasts.push("error", "Rotation rejected", messageOf(error)),
  });

  const summary = summaryQ.data;
  const openDeadLetters = (deadLettersQ.data ?? []).filter((row) => !row.replayedAt);

  return <>
    <div className="page-head">
      <div>
        <span className="eyebrow">Administration</span>
        <h1>Integration Dispatch</h1>
        <p>Outbound connectors, event subscriptions, delivery attempts and undelivered messages.</p>
      </div>
      {summary && <span className="count">{summary.connectors} connectors</span>}
    </div>

    {summaryQ.isLoading && <LoaderStatus label="Reading integration health" />}
    {summaryQ.isError && <p className="empty-note">Integration health failed to load: {messageOf(summaryQ.error)}</p>}

    {summary && <div className="panel" style={{ padding: "12px 16px", marginBottom: 14 }}>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 18, alignItems: "center" }}>
        <span className="chip chip-qualified">{summary.succeededLast24h} delivered · 24h</span>
        <span className={summary.failedLast24h > 0 ? "chip chip-working" : "chip"}>{summary.failedLast24h} failed attempts · 24h</span>
        <span className={summary.pendingDepth > 0 ? "chip chip-working" : "chip"}>{summary.pendingDepth} waiting</span>
        <span className={summary.deadLetterDepth > 0 ? "chip chip-lost" : "chip"}>{summary.deadLetterDepth} undelivered</span>
        <span className={summary.openBreakers > 0 ? "chip chip-lost" : "chip"}>{summary.openBreakers} paused by failure</span>
        <span style={{ marginLeft: "auto", display: "flex", gap: 8, alignItems: "center" }}>
          {runWorker.isPending && <InlineLoader label="Dispatching" />}
          <button className="btn btn-sm" onClick={() => refreshAll()}>Refresh</button>
          {canManage && <button className="btn btn-primary btn-sm" disabled={runWorker.isPending}
            onClick={() => runWorker.mutate()}>Send waiting messages now</button>}
        </span>
      </div>
    </div>}

    <div className="master-tabs" role="tablist" aria-label="Integration dispatch sections">
      {TABS.map((entry) => <button
        key={entry.key}
        type="button"
        className="master-tab"
        role="tab"
        aria-selected={tab === entry.key}
        onClick={() => setTab(entry.key)}
      >
        <span className="master-tab-label">{entry.label}</span>
        {entry.key === "dead-letters" && openDeadLetters.length > 0
          && <em className="master-tab-count">{openDeadLetters.length}<span className="sr-only"> undelivered</span></em>}
      </button>)}
    </div>

    {/* ---------------------------------------------------------------- */}
    {tab === "connectors" && <section className="panel" style={{ padding: 16 }}>
      {healthQ.isLoading && <GridLoader label="Reading connector health" rows={4} columns={7} />}
      {healthQ.isError && <p className="empty-note">Connector health failed to load: {messageOf(healthQ.error)}</p>}
      {healthQ.isSuccess && health.length === 0
        && <p className="empty-note">No connectors are registered yet. Add one below to start sending events out of Axiom.</p>}

      {healthQ.isSuccess && health.length > 0 && <div className="table-wrap"><table className="data-table">
        <thead><tr>
          <th>Connector</th><th>Type</th><th>State</th><th>Breaker</th>
          <th>Last success</th><th>Last failure</th><th className="num">Waiting</th><th className="num">Undelivered</th>
          {canManage && <th className="table-action">Action</th>}
        </tr></thead>
        <tbody>{health.map((row) => <Fragment key={row.connectorId}>
          <tr>
            <td>
              <button className="link-btn" onClick={() => setSelectedConnectorId(
                selectedConnectorId === row.connectorId ? null : row.connectorId)}>{row.connectorName}</button>
              {row.lastError && <div className="lead-meta">{row.lastError}</div>}
            </td>
            <td>{row.connectorType} · {row.vendor}</td>
            <td><span className={statusChip(row.status)}>{row.status}</span></td>
            <td>
              <span className={statusChip(row.breakerState === "OPEN" ? "PAUSED"
                : row.breakerState === "HALF_OPEN" ? "PROBING" : "HEALTHY")}>{row.breakerState}</span>
              {row.consecutiveFailures > 0 && <span className="lead-meta"> {row.consecutiveFailures} in a row</span>}
            </td>
            <td>{when(row.lastSuccessAt)}</td>
            <td>{when(row.lastFailureAt)}</td>
            <td className="num">{row.pendingDepth}</td>
            <td className="num">{row.deadLetterDepth}</td>
            {canManage && <td className="table-action">
              <button className="link-btn" disabled={toggleConnector.isPending}
                onClick={() => toggleConnector.mutate({ id: row.connectorId, enabled: !row.enabled })}>
                {row.enabled ? "Pause" : "Resume"}
              </button>
            </td>}
          </tr>
        </Fragment>)}</tbody>
      </table></div>}

      {selectedConnector && <div style={{ marginTop: 18, borderTop: "1px solid var(--line)", paddingTop: 14 }}>
        <span className="eyebrow">Connector detail</span>
        <h2>{selectedConnector.displayName}</h2>
        <p>
          {selectedConnector.connectorType} · {selectedConnector.vendor} ·{" "}
          {selectedConnector.enabled ? "enabled" : "paused"}
        </p>
        <table className="data-table"><tbody>
          <tr><th>Credential</th><td>
            {selectedConnector.credentialRef
              ? <>referenced by name: <span className="mono">{selectedConnector.credentialRef}</span>{" "}
                <span className={selectedConnector.credentialStatus === "SET" ? "chip chip-qualified" : "chip chip-lost"}>
                  {selectedConnector.credentialStatus === "SET" ? "stored" : "missing"}</span></>
              : <span className="chip chip-disqualified">none</span>}
            <div className="lead-meta">Saved secrets are never shown again, not even here.</div>
          </td></tr>
          <tr><th>Configuration</th><td>
            <pre className="mono" style={{ margin: 0, whiteSpace: "pre-wrap", overflowX: "auto" }}>
              {JSON.stringify(selectedConnector.config, null, 2)}
            </pre>
          </td></tr>
        </tbody></table>

        <h3 style={{ marginTop: 16 }}>Subscriptions</h3>
        {subscriptionsQ.isLoading && <GridLoader label="Reading subscriptions" rows={2} columns={4} />}
        {subscriptionsQ.isSuccess && <div className="table-wrap"><table className="data-table">
          <thead><tr><th>Event pattern</th><th>Filter</th><th>State</th>{canManage && <th className="table-action">Action</th>}</tr></thead>
          <tbody>
            {subscriptionsQ.data.map((row) => <tr key={row.id}>
              <td className="mono">{row.eventTypePattern}</td>
              <td className="mono">{row.filterExpression ?? "-"}</td>
              <td><span className={row.active ? "chip chip-qualified" : "chip chip-disqualified"}>
                {row.active ? "ACTIVE" : "PAUSED"}</span></td>
              {canManage && <td className="table-action">
                <button className="link-btn" onClick={() => toggleSubscription.mutate({
                  id: row.id, pattern: row.eventTypePattern, filter: row.filterExpression, active: !row.active,
                })}>{row.active ? "Pause" : "Activate"}</button>
                {" · "}
                <button className="link-btn" onClick={() => removeSubscription.mutate(row.id)}>Remove</button>
              </td>}
            </tr>)}
            {subscriptionsQ.data.length === 0 && <tr>
              <td colSpan={canManage ? 4 : 3} className="empty-note">
                No subscriptions. This connector will receive nothing until an event pattern is added.
              </td></tr>}
          </tbody>
        </table></div>}

        {canManage && <div className="reference-entry-form" style={{ marginTop: 12 }}>
          <input value={subscriptionDraft.pattern} placeholder="Event pattern, e.g. opportunity.*"
            aria-label="Event type pattern"
            onChange={(event) => setSubscriptionDraft((v) => ({ ...v, pattern: event.target.value }))} />
          <input value={subscriptionDraft.filter} placeholder="Optional filter, e.g. stage=CLOSED_WON"
            aria-label="Filter expression"
            onChange={(event) => setSubscriptionDraft((v) => ({ ...v, filter: event.target.value }))} />
          <button className="btn btn-primary btn-sm" disabled={addSubscription.isPending || !subscriptionDraft.pattern.trim()}
            onClick={() => addSubscription.mutate()}>
            {addSubscription.isPending ? "Saving..." : "Add subscription"}
          </button>
        </div>}
      </div>}

      {canManage && <div style={{ marginTop: 18, borderTop: "1px solid var(--line)", paddingTop: 14 }}>
        <span className="eyebrow">Register</span>
        <h3>New connector</h3>
        <div className="reference-entry-form">
          <select value={connectorDraft.connectorType} aria-label="Connector type"
            onChange={(event) => setConnectorDraft((v) => ({ ...v, connectorType: event.target.value as ConnectorType }))}>
            {CONNECTOR_TYPES.map((type) => <option key={type} value={type}>{type}</option>)}
          </select>
          <input value={connectorDraft.vendor} placeholder="Vendor key" aria-label="Vendor"
            onChange={(event) => setConnectorDraft((v) => ({ ...v, vendor: event.target.value }))} />
          <input value={connectorDraft.displayName} placeholder="Display name" aria-label="Display name"
            onChange={(event) => setConnectorDraft((v) => ({ ...v, displayName: event.target.value }))} />
          <input value={connectorDraft.url} placeholder="Endpoint URL" aria-label="Endpoint URL"
            onChange={(event) => setConnectorDraft((v) => ({ ...v, url: event.target.value }))} />
          <input value={connectorDraft.credentialRef} placeholder="Credential name" aria-label="Credential name"
            onChange={(event) => setConnectorDraft((v) => ({ ...v, credentialRef: event.target.value }))} />
          <button className="btn btn-primary btn-sm"
            disabled={createConnector.isPending || !connectorDraft.displayName.trim()}
            onClick={() => createConnector.mutate()}>
            {createConnector.isPending ? "Saving..." : "Register connector"}
          </button>
        </div>
        {adaptersQ.isSuccess && <p className="lead-meta" style={{ marginTop: 8 }}>
          Available adapters: {adaptersQ.data.map((a) => `${a.connectorType}/${a.vendor}${a.live ? "" : " (local stand-in)"}`).join(", ")}
        </p>}
      </div>}
    </section>}

    {/* ---------------------------------------------------------------- */}
    {tab === "deliveries" && <section className="panel" style={{ padding: 16 }}>
      {deliveriesQ.isLoading && <GridLoader label="Reading dispatch log" rows={6} columns={6} />}
      {deliveriesQ.isError && <p className="empty-note">Dispatch log failed to load: {messageOf(deliveriesQ.error)}</p>}
      {deliveriesQ.isSuccess && <div className="table-wrap"><table className="data-table">
        <thead><tr>
          <th>Event</th><th>Connector</th><th>State</th><th className="num">Attempts</th>
          <th>Next attempt</th><th>Last result</th><th className="table-action">Trace</th>
        </tr></thead>
        <tbody>{(deliveriesQ.data as Delivery[]).map((row) => <Fragment key={row.id}>
          <tr>
            <td><span className="mono">{row.eventType}</span><div className="lead-meta">{row.aggregateType}</div></td>
            <td>{row.connectorName}</td>
            <td><span className={statusChip(row.status)}>{row.status}</span></td>
            <td className="num">{row.attemptCount}</td>
            <td>{row.status === "SUCCEEDED" ? when(row.succeededAt) : when(row.nextAttemptAt)}</td>
            <td>{row.lastHttpStatus ? `HTTP ${row.lastHttpStatus}` : ""} {row.lastError ?? ""}</td>
            <td className="table-action">
              <button className="link-btn" onClick={() => setOpenDeliveryId(openDeliveryId === row.id ? null : row.id)}>
                {openDeliveryId === row.id ? "Hide" : "Attempts"}
              </button>
            </td>
          </tr>
          {openDeliveryId === row.id && <tr><td colSpan={7}>
            {attemptsQ.isLoading && <PanelLoader label="Reading attempt trace" />}
            {attemptsQ.isSuccess && <table className="data-table">
              <thead><tr><th className="num">#</th><th>Result</th><th className="num">HTTP</th>
                <th className="num">Duration</th><th>At</th><th>Detail</th></tr></thead>
              <tbody>
                {attemptsQ.data.map((attempt) => <tr key={attempt.id}>
                  <td className="num">{attempt.attemptNo}</td>
                  <td><span className={statusChip(attempt.status)}>{attempt.status}</span></td>
                  <td className="num">{attempt.httpStatus ?? "-"}</td>
                  <td className="num">{attempt.durationMs} ms</td>
                  <td>{when(attempt.attemptedAt)}</td>
                  <td className="mono">{attempt.error ?? attempt.responseExcerpt ?? "-"}</td>
                </tr>)}
                {attemptsQ.data.length === 0 && <tr><td colSpan={6} className="empty-note">No attempts recorded yet.</td></tr>}
              </tbody>
            </table>}
          </td></tr>}
        </Fragment>)}
        {deliveriesQ.data.length === 0 && <tr>
          <td colSpan={7} className="empty-note">Nothing has been dispatched yet. Events appear here once a subscription matches one.</td>
        </tr>}</tbody>
      </table></div>}
    </section>}

    {/* ---------------------------------------------------------------- */}
    {tab === "dead-letters" && <section className="panel" style={{ padding: 16 }}>
      <p className="lead-meta">
        Messages Axiom could not deliver after every retry. Nothing here has been thrown away — fix the
        receiving system, then retry.
      </p>
      {canManage && openDeadLetters.length > 0 && <div style={{ margin: "10px 0" }}>
        <button className="btn btn-primary btn-sm" disabled={replayMany.isPending}
          onClick={() => replayMany.mutate(openDeadLetters.map((row) => row.id))}>
          {replayMany.isPending ? "Queueing..." : `Retry all ${openDeadLetters.length}`}
        </button>
      </div>}
      {deadLettersQ.isLoading && <GridLoader label="Reading undelivered messages" rows={4} columns={6} />}
      {deadLettersQ.isError && <p className="empty-note">Dead letters failed to load: {messageOf(deadLettersQ.error)}</p>}
      {deadLettersQ.isSuccess && <div className="table-wrap"><table className="data-table">
        <thead><tr>
          <th>Event</th><th>Connector</th><th className="num">Attempts</th><th>Why it failed</th>
          <th>State</th><th className="table-action">Action</th>
        </tr></thead>
        <tbody>{(deadLettersQ.data as DeadLetter[]).map((row) => <Fragment key={row.id}>
          <tr>
            <td>
              <button className="link-btn" onClick={() => setOpenDeadLetterId(openDeadLetterId === row.id ? null : row.id)}>
                <span className="mono">{row.eventType}</span>
              </button>
              <div className="lead-meta">{when(row.createdAt)}</div>
            </td>
            <td>{row.connectorName}</td>
            <td className="num">{row.attempts}</td>
            <td>{row.failureReason}</td>
            <td><span className={row.replayedAt ? "chip chip-converted" : "chip chip-lost"}>
              {row.replayedAt ? `RETRIED x${row.replayCount}` : "UNDELIVERED"}</span></td>
            <td className="table-action">
              {canManage && <button className="link-btn" disabled={replayOne.isPending}
                onClick={() => replayOne.mutate(row.id)}>Retry</button>}
            </td>
          </tr>
          {openDeadLetterId === row.id && <tr><td colSpan={6}>
            <pre className="mono" style={{ margin: 0, whiteSpace: "pre-wrap", overflowX: "auto" }}>
              {JSON.stringify(row.envelope, null, 2)}
            </pre>
          </td></tr>}
        </Fragment>)}
        {deadLettersQ.data.length === 0 && <tr>
          <td colSpan={6} className="empty-note">Nothing has been left undelivered.</td>
        </tr>}</tbody>
      </table></div>}
    </section>}

    {/* ---------------------------------------------------------------- */}
    {tab === "credentials" && <section className="panel" style={{ padding: 16 }}>
      <p className="lead-meta">
        Keys and tokens used by connectors. Axiom encrypts them on save and never shows them again —
        if you lose one, replace it with a new value rather than looking it up.
      </p>
      {credentialsQ.isLoading && <GridLoader label="Reading credentials" rows={3} columns={5} />}
      {credentialsQ.isError && <p className="empty-note">Credentials failed to load: {messageOf(credentialsQ.error)}</p>}
      {credentialsQ.isSuccess && <div className="table-wrap"><table className="data-table">
        <thead><tr><th>Name</th><th>Type</th><th>Stored value</th><th>Last rotated</th><th>Last used</th>
          {canManage && <th className="table-action">Action</th>}</tr></thead>
        <tbody>
          {credentialsQ.data.map((row) => <Fragment key={row.id}>
            <tr>
              <td><span className="mono">{row.name}</span>
                {row.description && <div className="lead-meta">{row.description}</div>}</td>
              <td>{row.credentialType}</td>
              <td className="mono">{row.secretMasked}</td>
              <td>{when(row.rotatedAt)}</td>
              <td>{when(row.lastUsedAt)}</td>
              {canManage && <td className="table-action">
                <button className="link-btn"
                  onClick={() => setRotating(rotating?.name === row.name ? null : { name: row.name, secret: "" })}>
                  Replace value
                </button>
              </td>}
            </tr>
            {rotating?.name === row.name && <tr><td colSpan={6}>
              <div className="reference-entry-form">
                <input type="password" value={rotating.secret} placeholder="New value" aria-label="New credential value"
                  onChange={(event) => setRotating({ name: row.name, secret: event.target.value })} />
                <button className="btn btn-primary btn-sm" disabled={rotateCredential.isPending || rotating.secret.length < 8}
                  onClick={() => rotateCredential.mutate({ name: row.name, secret: rotating.secret })}>
                  {rotateCredential.isPending ? "Saving..." : "Rotate"}
                </button>
                <button className="btn btn-sm" onClick={() => setRotating(null)}>Cancel</button>
              </div>
            </td></tr>}
          </Fragment>)}
          {credentialsQ.data.length === 0 && <tr>
            <td colSpan={canManage ? 6 : 5} className="empty-note">No credentials stored.</td></tr>}
        </tbody>
      </table></div>}

      {canManage && <div style={{ marginTop: 18, borderTop: "1px solid var(--line)", paddingTop: 14 }}>
        <span className="eyebrow">Store</span>
        <h3>New credential</h3>
        <div className="reference-entry-form">
          <input value={credentialDraft.name} placeholder="Name, e.g. ops-webhook-secret" aria-label="Credential name"
            onChange={(event) => setCredentialDraft((v) => ({ ...v, name: event.target.value }))} />
          <select value={credentialDraft.credentialType} aria-label="Credential type"
            onChange={(event) => setCredentialDraft((v) => ({ ...v, credentialType: event.target.value }))}>
            <option value="WEBHOOK_SIGNING_SECRET">WEBHOOK_SIGNING_SECRET</option>
            <option value="BEARER_TOKEN">BEARER_TOKEN</option>
            <option value="API_KEY">API_KEY</option>
            <option value="BASIC_AUTH">BASIC_AUTH</option>
            <option value="MTLS_KEYPAIR">MTLS_KEYPAIR</option>
          </select>
          <input type="password" value={credentialDraft.secret} placeholder="Value" aria-label="Credential value"
            onChange={(event) => setCredentialDraft((v) => ({ ...v, secret: event.target.value }))} />
          <input value={credentialDraft.description} placeholder="What it is for" aria-label="Description"
            onChange={(event) => setCredentialDraft((v) => ({ ...v, description: event.target.value }))} />
          <button className="btn btn-primary btn-sm"
            disabled={createCredential.isPending || credentialDraft.secret.length < 8 || !credentialDraft.name.trim()}
            onClick={() => createCredential.mutate()}>
            {createCredential.isPending ? "Saving..." : "Store credential"}
          </button>
        </div>
      </div>}
    </section>}
  </>;
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : "Request failed.";
}

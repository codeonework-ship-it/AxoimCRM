import { useQuery } from "@tanstack/react-query";
import { api, isUnreachable } from "../api/client";
import { ApiUnreachable } from "../components/ApiUnreachable";
import { formatDate, formatMoney } from "../lib/format";
import { useAuth } from "../auth/AuthContext";
import { Link } from "react-router-dom";
import { ArrowIcon, SparkIcon } from "../components/icons";

export function HomePage() {
  const { user } = useAuth();

  const summaryQ = useQuery({
    queryKey: ["dashboard", "summary"],
    queryFn: api.dashboardSummary,
    retry: 1,
  });

  const boardQ = useQuery({
    queryKey: ["pipeline", "board"],
    queryFn: api.pipelineBoard,
    retry: 1,
  });

  if (isUnreachable(summaryQ.error)) {
    return (
      <ApiUnreachable
        onRetry={() => {
          void summaryQ.refetch();
          void boardQ.refetch();
        }}
        retrying={summaryQ.isFetching}
      />
    );
  }

  const summary = summaryQ.data;
  const firstName = user?.displayName.split(" ")[0] ?? "Operator";
  const today = new Intl.DateTimeFormat(undefined, {
    weekday: "long", month: "long", day: "numeric",
  }).format(new Date());

  // Open opportunities lacking a confirmed economic buyer, from board data.
  const needsAttention =
    boardQ.data?.stages
      .filter((s) => !s.isClosed)
      .flatMap((s) =>
        s.opportunities
          .filter((o) => !o.hasEconomicBuyer)
          .map((o) => ({ ...o, stageName: s.name })),
      ) ?? [];

  return (
    <>
      <section className="command-hero">
        <div className="hero-copy">
          <span className="eyebrow">Revenue command · {today}</span>
          <h1>Good day, {firstName}.<br /><span>Keep the machine moving.</span></h1>
          <p>Your live commercial posture, exceptions, and next actions in one operator view.</p>
        </div>
        <div className="hero-status" aria-label="Workspace status">
          <span className={`status-pip${summaryQ.isError ? " status-warn" : ""}`} />
          <div><strong>{summaryQ.isError ? "Telemetry degraded" : "Workspace online"}</strong><small>{summaryQ.isFetching ? "Synchronizing live data" : "Last scan complete"}</small></div>
        </div>
        <div className="hero-geometry" aria-hidden><span /><span /><span /></div>
      </section>

      <div className="section-heading">
        <div><span className="eyebrow">Commercial telemetry</span><h2>Revenue posture</h2></div>
        <Link className="text-action" to="/pipeline">Open pipeline <ArrowIcon size={15} /></Link>
      </div>

      <div className="kpi-row">
        <div className="kpi">
          <span className="label">Open pipeline</span>
          <div className="kpi-value money">
            {summaryQ.isLoading ? "…" : formatMoney(summary?.openPipeline)}
          </div>
          <div className="kpi-sub"><span className="metric-pip" /> Total unclosed value</div>
        </div>

        <div className="kpi">
          <span className="label">Open deals</span>
          <div className="kpi-value money">
            {summaryQ.isLoading ? "…" : (summary?.openCount ?? "—")}
          </div>
          <div className="kpi-sub">Active opportunities in motion</div>
        </div>

        <div className="kpi">
          <span className="label">At risk</span>
          <div
            className={`kpi-value money${(summary?.atRiskCount ?? 0) > 0 ? " crit" : ""}`}
          >
            {summaryQ.isLoading ? "…" : (summary?.atRiskCount ?? "—")}
          </div>
          <div className="kpi-sub">Missing buying-group coverage</div>
        </div>

        <div className="kpi">
          <span className="label">By stage</span>
          {summaryQ.isLoading ? (
            <div className="kpi-value money">…</div>
          ) : (
            <ul className="kpi-stage-list stage-meter-list">
              {(summary?.byStage ?? []).slice(0, 4).map((s) => (
                <li key={s.stageId}>
                  <div><span>{s.stageName}</span><span className="money">{formatMoney(s.amount)}</span></div>
                  <span className="stage-meter"><i style={{ width: `${Math.max(4, (s.amount / Math.max(summary?.openPipeline ?? 1, 1)) * 100)}%` }} /></span>
                </li>
              ))}
              {(summary?.byStage ?? []).length === 0 && (
                <li>
                  <span>No stage data</span>
                </li>
              )}
            </ul>
          )}
        </div>
      </div>

      {summaryQ.isError && !summaryQ.isLoading && (
        <p className="empty-note">
          Dashboard summary failed to load
          {summaryQ.error instanceof Error ? `: ${summaryQ.error.message}` : "."}
        </p>
      )}

      <div className="home-grid">
        <section className="card">
          <div className="card-head">
            <div><span className="eyebrow">Exception queue</span><h2>Needs your attention</h2></div>
            <span className="count">
              {boardQ.isLoading ? "" : `${needsAttention.length} open`}
            </span>
          </div>

          {boardQ.isLoading && <p className="loading-note">Scanning board…</p>}
          {isUnreachable(boardQ.error) && (
            <p className="empty-note">Board data unavailable — API unreachable.</p>
          )}
          {boardQ.isSuccess && needsAttention.length === 0 && (
            <p className="empty-note">
              Every open deal has a confirmed economic buyer. Nothing pending.
            </p>
          )}
          {needsAttention.map((o) => (
            <Link className="attn-item" key={o.id} to="/pipeline">
              <span className="attn-stripe" aria-hidden />
              <div>
                <div className="attn-name">{o.name}</div>
                <div className="attn-meta">
                  {o.accountName} · {o.stageName} · Economic buyer missing · closes{" "}
                  {formatDate(o.closeDate)}
                </div>
              </div>
              <span className="attn-amount money">{formatMoney(o.amount)}</span>
              <ArrowIcon size={15} />
            </Link>
          ))}
        </section>

        <aside className="ai-panel">
          <div className="ai-head"><span className="ai-orb"><SparkIcon /></span><div><span className="eyebrow">Axiom intelligence</span><h2>Briefing preview</h2></div></div>
          <div className="ai-stub"><strong>Gold is a promise, not decoration.</strong> Future AI suggestions will be cited, explainable, and require your approval before action.</div>
          <div className="ai-proof"><span>Provenance</span><strong>Human controlled</strong></div>
        </aside>
      </div>

      <section className="quick-actions" aria-label="Quick actions">
        <div><span className="eyebrow">Fast lane</span><h2>Continue operating</h2></div>
        <Link to="/leads">Qualify leads <ArrowIcon size={15} /></Link>
        <Link to="/accounts">Review accounts <ArrowIcon size={15} /></Link>
        <Link to="/pipeline">Advance opportunities <ArrowIcon size={15} /></Link>
      </section>
    </>
  );
}

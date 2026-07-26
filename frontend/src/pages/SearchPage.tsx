import { useEffect, useMemo, useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearchParams } from "react-router-dom";
import { highlightSegments, searchApi, type SearchHit } from "../api/search";
import { useAuth } from "../auth/AuthContext";
import { GridLoader, InlineLoader, LoaderStatus, PanelLoader } from "../components/Loaders";
import { useToasts } from "../components/Toasts";

/**
 * Global search (FR-ADM-004).
 *
 * <p>Three things on this screen are deliberate and would be easy to "improve"
 * into dishonesty:
 *
 * 1. **The result count is the post-authorization count.** When the index matched
 *    twelve records and the authoritative re-check refused two, this page says
 *    ten, and says why. It does not quietly fetch two more to make the page look
 *    full — that would put the index back in charge of who sees what.
 * 2. **The freshness line is always shown**, not only when something is wrong. A
 *    user who cannot find the record they saved four seconds ago needs to know
 *    the index is a few seconds behind; hiding that makes eventual consistency
 *    look like data loss.
 * 3. **A withheld field is shown as withheld**, not as blank. Absence and
 *    emptiness are different facts (FR-SEC-007) and the UI keeps them different.
 *
 * <p>Written as its own page rather than by rewiring `CommandPalette`: that
 * component is a synchronous filter over a hard-coded navigation list, and
 * turning it into a debounced remote search would mean restructuring it — which
 * is exactly what this change was asked not to do.
 */

const TYPE_LABELS: Record<string, string> = {
  ACCOUNT: "Accounts",
  CONTACT: "Contacts",
  LEAD: "Leads",
  OPPORTUNITY: "Opportunities",
};

/** Mirrors CrmRole.requireMasterAdmin on the backend — the gate the API actually applies. */
const REINDEX_ROLES = new Set(["SUPER_ADMIN", "TENANT_ADMIN", "DATA_STEWARD"]);

function label(entityType: string): string {
  return TYPE_LABELS[entityType] ?? entityType;
}

function singular(entityType: string): string {
  const plural = label(entityType);
  return plural.endsWith("s") ? plural.slice(0, -1) : plural;
}

function relative(iso: string | null): string {
  if (!iso) return "never";
  const seconds = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)}h ago`;
  return `${Math.round(seconds / 86400)}d ago`;
}

function freshness(lagSeconds: number | null, pendingEvents: number): string {
  if (lagSeconds === null) return "Index empty — nothing has been indexed yet";
  if (pendingEvents > 0) {
    return `${pendingEvents} change${pendingEvents === 1 ? "" : "s"} still to index`;
  }
  if (lagSeconds < 120) return "Index current";
  if (lagSeconds < 3600) return `Newest indexed record is ${Math.round(lagSeconds / 60)}m old`;
  return `Newest indexed record is ${Math.round(lagSeconds / 3600)}h old`;
}

export function SearchPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const toasts = useToasts();
  const [params, setParams] = useSearchParams();

  const submitted = (params.get("q") ?? "").trim();
  const selectedTypes = useMemo(
    () => (params.get("types") ?? "").split(",").map((t) => t.trim()).filter(Boolean),
    [params],
  );
  const [draft, setDraft] = useState(submitted);
  const [reindexType, setReindexType] = useState("");
  const [watchedRun, setWatchedRun] = useState<string | null>(null);

  // The URL is the state: a search is linkable, back/forward walks the history,
  // and a refresh lands on the same result page.
  useEffect(() => setDraft(submitted), [submitted]);

  const canReindex = REINDEX_ROLES.has(user?.role ?? "");

  const typesQ = useQuery({ queryKey: ["search", "types"], queryFn: searchApi.types, retry: 1 });
  const statusQ = useQuery({
    queryKey: ["search", "status"],
    queryFn: searchApi.status,
    retry: 1,
    refetchInterval: 15000,
  });
  const resultsQ = useQuery({
    queryKey: ["search", "results", submitted, selectedTypes.join(",")],
    queryFn: () => searchApi.search(submitted, selectedTypes, 20),
    enabled: submitted.length > 0,
    retry: 1,
  });

  // A run in flight is polled so the progress figure is the server's, not an animation.
  const runQ = useQuery({
    queryKey: ["search", "reindex", watchedRun],
    queryFn: () => searchApi.reindexRun(watchedRun as string),
    enabled: !!watchedRun,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === "COMPLETED" || status === "FAILED" ? false : 1500;
    },
  });

  useEffect(() => {
    const status = runQ.data?.status;
    if (status === "COMPLETED" || status === "FAILED") {
      queryClient.invalidateQueries({ queryKey: ["search", "status"] });
      queryClient.invalidateQueries({ queryKey: ["search", "results"] });
    }
  }, [runQ.data?.status, queryClient]);

  const reindexM = useMutation({
    mutationFn: () => searchApi.reindex(reindexType || null, "Requested from the search workspace"),
    onSuccess: (run) => {
      setWatchedRun(run.id);
      toasts.push("info", "Search rebuild queued", "Progress will update here while records are indexed.");
    },
    onError: (error) => toasts.push(
      "error",
      "Search rebuild could not start",
      error instanceof Error ? error.message : "Please retry the rebuild.",
    ),
  });

  const activeRun = runQ.data ?? statusQ.data?.activeRun ?? null;
  const status = statusQ.data;
  const results = resultsQ.data;
  const allTypes = typesQ.data ?? Object.keys(TYPE_LABELS);

  function submit(event: FormEvent) {
    event.preventDefault();
    const next = new URLSearchParams(params);
    const trimmed = draft.trim();
    if (trimmed) next.set("q", trimmed);
    else next.delete("q");
    setParams(next, { replace: false });
  }

  function toggleType(entityType: string) {
    const next = new URLSearchParams(params);
    const chosen = selectedTypes.includes(entityType)
      ? selectedTypes.filter((t) => t !== entityType)
      : [...selectedTypes, entityType];
    if (chosen.length) next.set("types", chosen.join(","));
    else next.delete("types");
    setParams(next, { replace: true });
  }

  return (
    <section aria-labelledby="search-heading">
      <header className="page-head">
        <div>
          <span className="eyebrow">Global search</span>
          <h1 id="search-heading">Find anything</h1>
        </div>
        <div>
          {statusQ.isLoading
            ? <InlineLoader label="Reading index freshness" />
            : status && (
              <span className="chip" title={
                `Newest indexed record: ${status.newestIndexedUpdatedAt ?? "none"} · `
                + `last index write ${relative(status.lastIndexedAt)} · `
                + `${status.pendingEvents} event(s) pending`
              }>
                {freshness(status.lagSeconds, status.pendingEvents)}
              </span>
            )}
        </div>
      </header>

      <form className="panel" onSubmit={submit} role="search">
        <label className="eyebrow" htmlFor="search-input">Search accounts, contacts, leads and opportunities</label>
        <div style={{ display: "flex", gap: 8, marginTop: 8, flexWrap: "wrap" }}>
          <input
            id="search-input"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder="Company, person, deal…"
            aria-label="Search text"
            style={{ flex: "1 1 260px", minWidth: 0 }}
          />
          <button type="submit" className="btn btn-primary">Search</button>
          {submitted && (
            <button
              type="button"
              className="btn btn-sm"
              onClick={() => { setDraft(""); setParams(new URLSearchParams(), { replace: false }); }}
            >
              Clear
            </button>
          )}
        </div>
      </form>

      <div className="master-tabs" role="tablist" aria-label="Result types">
        <button
          type="button"
          role="tab"
          className="master-tab"
          aria-selected={selectedTypes.length === 0}
          onClick={() => { const next = new URLSearchParams(params); next.delete("types"); setParams(next, { replace: true }); }}
        >
          <span className="master-tab-label">Everything</span>
        </button>
        {allTypes.map((entityType) => (
          <button
            key={entityType}
            type="button"
            role="tab"
            className="master-tab"
            aria-selected={selectedTypes.includes(entityType)}
            onClick={() => toggleType(entityType)}
          >
            <span className="master-tab-label">{label(entityType)}</span>
            {status?.documentsByType?.[entityType] !== undefined && (
              <em className="master-tab-count">
                {status.documentsByType[entityType]}
                <span className="sr-only"> indexed</span>
              </em>
            )}
          </button>
        ))}
      </div>

      {!submitted && (
        <div className="panel">
          <p>
            Type a company, a person or a deal name and press Search. Results are ranked so a
            match on a record&apos;s own name beats a match buried in its detail.
          </p>
          <p style={{ color: "var(--muted)" }}>
            Every result is re-checked against your live permissions before it is shown, so this
            page can only ever show you records you are allowed to open right now.
          </p>
        </div>
      )}

      {submitted && resultsQ.isLoading && <GridLoader label="Searching the index" rows={5} columns={4} />}

      {submitted && resultsQ.isError && (
        <div className="panel" role="alert">
          <p>Search is unavailable right now.</p>
          <button type="button" className="btn btn-sm" onClick={() => resultsQ.refetch()}>Try again</button>
        </div>
      )}

      {submitted && results && (
        <>
          <div className="panel">
            <p className="eyebrow">
              {results.returned} result{results.returned === 1 ? "" : "s"} for “{results.query}”
            </p>
            <p style={{ color: "var(--muted)", marginTop: 6 }}>
              <span className="num">{results.indexMatches}</span> matched the index ·{" "}
              <span className="num">{results.returned}</span> you may read
              {results.droppedByRecheck > 0 && (
                <> · <span className="num">{results.droppedByRecheck}</span> withheld by the permission re-check</>
              )}
              {results.droppedByFieldSecurity > 0 && (
                <> · <span className="num">{results.droppedByFieldSecurity}</span> withheld by field security</>
              )}
              {" · index "}<span className="num">{results.indexQueryMillis}</span>{" ms, re-check "}
              <span className="num">{results.recheckMillis}</span>{" ms"}
            </p>
            {results.typesDenied.length > 0 && (
              <p style={{ color: "var(--muted)", marginTop: 6 }}>
                Not searched, because your profile has no read access:{" "}
                {results.typesDenied.map(label).join(", ")}.
              </p>
            )}
          </div>

          {results.hits.length === 0 ? (
            <div className="panel">
              <p>Nothing you can read matches “{results.query}”.</p>
              <p style={{ color: "var(--muted)" }}>
                {results.droppedByRecheck > 0
                  ? "Some records matched but are not shared with you."
                  : "Try fewer or different words, or widen the type filter."}
                {status && status.pendingEvents > 0
                  && " Very recent edits may not be indexed yet."}
              </p>
            </div>
          ) : (
            <div className="panel">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Type</th>
                    <th scope="col">Result</th>
                    <th scope="col">Match</th>
                    <th scope="col">Updated</th>
                  </tr>
                </thead>
                <tbody>
                  {results.hits.map((hit) => (
                    <ResultRow key={`${hit.entityType}:${hit.entityId}`} hit={hit} onOpen={navigate} />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {canReindex && (
        <div className="panel" style={{ marginTop: 14 }}>
          <span className="eyebrow">Index administration</span>
          <p style={{ color: "var(--muted)", marginTop: 6 }}>
            The index updates itself from change events. Rebuild it after a restore, or after a
            release that changes what is indexed. A rebuild runs in the background in batches and
            never blocks anyone&apos;s save.
          </p>
          <div style={{ display: "flex", gap: 8, marginTop: 10, alignItems: "center", flexWrap: "wrap" }}>
            <label className="eyebrow" htmlFor="reindex-scope">Scope</label>
            <select
              id="reindex-scope"
              value={reindexType}
              onChange={(event) => setReindexType(event.target.value)}
            >
              <option value="">Everything</option>
              {allTypes.map((entityType) => (
                <option key={entityType} value={entityType}>{label(entityType)}</option>
              ))}
            </select>
            <button
              type="button"
              className="btn btn-primary btn-sm"
              disabled={reindexM.isPending || activeRun?.status === "RUNNING" || activeRun?.status === "QUEUED"}
              onClick={() => reindexM.mutate()}
            >
              Rebuild index
            </button>
            {reindexM.isPending && <InlineLoader label="Queueing rebuild" />}
          </div>

          {reindexM.isError && (
            <p role="alert" style={{ color: "var(--amber)", marginTop: 8 }}>
              {(reindexM.error as Error).message}
            </p>
          )}

          {activeRun && (
            <div style={{ marginTop: 12 }}>
              {(activeRun.status === "QUEUED" || activeRun.status === "RUNNING")
                ? <LoaderStatus
                    label={`Rebuilding ${label(activeRun.entityType === "ALL" ? "Everything" : activeRun.entityType)}`}
                    detail={`${activeRun.processedUnits} of ${activeRun.totalUnits} records · ${activeRun.percentComplete}%`}
                  />
                : (
                  <p>
                    <span className="chip">{activeRun.status}</span>{" "}
                    <span className="num">{activeRun.documentsWritten}</span> document(s) written,{" "}
                    <span className="num">{activeRun.documentsRemoved}</span> removed
                    {activeRun.message ? ` — ${activeRun.message}` : ""}
                  </p>
                )}
            </div>
          )}
        </div>
      )}

      {statusQ.isLoading && !status && <PanelLoader label="Reading index status" />}
    </section>
  );
}

function ResultRow({ hit, onOpen }: { hit: SearchHit; onOpen: (to: string) => void }) {
  return (
    <tr>
      <td><span className="chip">{singular(hit.entityType)}</span></td>
      <td>
        <button type="button" className="link-btn" onClick={() => onOpen(hit.urlPath)}>
          {hit.title}
        </button>
        <div style={{ color: "var(--muted)", fontSize: "0.85em" }}>
          {/* Absent, not blank: the difference is the point of FR-SEC-007. */}
          {hit.subtitle !== undefined
            ? hit.subtitle
            : <em>Restricted for your profile</em>}
        </div>
      </td>
      <td>
        {highlightSegments(hit.snippet).map((segment, i) =>
          segment.match
            ? <strong key={i} style={{ color: "var(--cyan)" }}>{segment.text}</strong>
            : <span key={i}>{segment.text}</span>,
        )}
        {hit.withheldFields?.length ? (
          <div style={{ color: "var(--muted)", fontSize: "0.8em", marginTop: 4 }}>
            Withheld: {hit.withheldFields.join(", ")}
          </div>
        ) : null}
      </td>
      <td className="mono">{relative(hit.updatedAt)}</td>
    </tr>
  );
}

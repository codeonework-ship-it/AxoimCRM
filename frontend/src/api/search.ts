/**
 * Axiom CRM — global search client.
 *
 * A module of its own rather than an addition to `client.ts`, because the search
 * engine is a separate module on the backend too and coupling the two files would
 * mean every search change touched the one file every other screen depends on.
 *
 * The bearer token is read from the same session key `AuthContext` writes. That is
 * a small duplication and it is deliberate: `client.ts` keeps its token in a module
 * private and does not expose it, and reaching into that file to add an export
 * would be a change to shared code for the convenience of one feature.
 */

const DEFAULT_API_BASE_URL = import.meta.env.DEV ? "http://localhost:8080/api/v1" : "/api/v1";

const BASE_URL: string =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? DEFAULT_API_BASE_URL;

/** Written by AuthContext. Read-only here. */
const SESSION_STORAGE_KEY = "axiom.session";

function bearerToken(): string | null {
  try {
    const raw = localStorage.getItem(SESSION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { token?: string };
    return parsed?.token ?? null;
  } catch {
    return null;
  }
}

export class SearchApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = "SearchApiError";
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = bearerToken();
  const response = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  });
  if (!response.ok) {
    let message = `Request failed with status ${response.status}`;
    try {
      const body = (await response.json()) as { message?: string; detail?: string };
      message = body?.message ?? body?.detail ?? message;
    } catch {
      /* a non-JSON error body is still an error; keep the status message */
    }
    throw new SearchApiError(response.status, message);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

/* ------------------------------------------------------------------ */
/* Types — mirror of com.axiom.search                                  */
/* ------------------------------------------------------------------ */

export interface SearchHit {
  entityType: string;
  entityId: string;
  title: string;
  /**
   * ABSENT — not null — when the caller's profile hides the field it is built
   * from (FR-SEC-007). Treat `undefined` as "withheld", never as "empty".
   */
  subtitle?: string;
  snippet: string;
  urlPath: string;
  rank: number;
  updatedAt: string;
  /** Field names withheld from this hit, when any were. */
  withheldFields?: string[];
}

export interface ReindexRun {
  id: string;
  entityType: string;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
  reason: string | null;
  totalUnits: number;
  processedUnits: number;
  documentsWritten: number;
  documentsRemoved: number;
  phase: string | null;
  queuedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  message: string | null;
  percentComplete: number;
}

export interface IndexStatus {
  documentCount: number;
  documentsByType: Record<string, number>;
  newestIndexedUpdatedAt: string | null;
  lastIndexedAt: string | null;
  consumerCheckpointAt: string | null;
  /** Seconds between the newest indexed record's own timestamp and now. Null on an empty index. */
  lagSeconds: number | null;
  pendingEvents: number;
  activeRun: ReindexRun | null;
}

export interface SearchResponse {
  query: string;
  types: string[];
  limit: number;
  hits: SearchHit[];
  /** How many the index matched, before authorization was re-checked. */
  indexMatches: number;
  returned: number;
  /** Matched the index but the authoritative store refused them. */
  droppedByRecheck: number;
  /** Matched only through a field this caller may not read. */
  droppedByFieldSecurity: number;
  typesDenied: string[];
  indexQueryMillis: number;
  recheckMillis: number;
  index: IndexStatus;
}

/* ------------------------------------------------------------------ */
/* Calls                                                              */
/* ------------------------------------------------------------------ */

export const searchApi = {
  search(query: string, types: string[], limit = 20): Promise<SearchResponse> {
    const params = new URLSearchParams({ q: query, limit: String(limit) });
    if (types.length) params.set("types", types.join(","));
    return request<SearchResponse>(`/search?${params.toString()}`);
  },

  status(): Promise<IndexStatus> {
    return request<IndexStatus>("/search/status");
  },

  types(): Promise<string[]> {
    return request<{ types: string[] }>("/search/types").then((body) => body.types);
  },

  reindex(entityType: string | null, reason: string): Promise<ReindexRun> {
    return request<ReindexRun>("/search/reindex", {
      method: "POST",
      body: JSON.stringify({ entityType, reason }),
    });
  },

  reindexRun(id: string): Promise<ReindexRun> {
    return request<ReindexRun>(`/search/reindex/${id}`);
  },
};

/* ------------------------------------------------------------------ */
/* Snippet highlighting                                                */
/* ------------------------------------------------------------------ */

const HIGHLIGHT = /\[\[(.+?)\]\]/g;

/**
 * Split a `ts_headline` snippet into plain and highlighted segments.
 *
 * The API returns markers rather than HTML on purpose, so an account whose name
 * contains markup cannot become markup here. This turns the markers into data the
 * component renders as elements — no `dangerouslySetInnerHTML` anywhere.
 */
export function highlightSegments(snippet: string): Array<{ text: string; match: boolean }> {
  const segments: Array<{ text: string; match: boolean }> = [];
  let cursor = 0;
  for (const found of snippet.matchAll(HIGHLIGHT)) {
    const at = found.index ?? 0;
    if (at > cursor) segments.push({ text: snippet.slice(cursor, at), match: false });
    segments.push({ text: found[1], match: true });
    cursor = at + found[0].length;
  }
  if (cursor < snippet.length) segments.push({ text: snippet.slice(cursor), match: false });
  return segments;
}

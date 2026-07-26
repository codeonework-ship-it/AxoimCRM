import { useEffect, useRef } from "react";
import { useLocation } from "react-router-dom";
import { api } from "../api/client";

/**
 * Client-side activity reporting.
 *
 * <h2>What this is for</h2>
 * The server records one activity row per API request, which covers everything
 * with a server effect. It cannot see what happens purely in the browser: moving
 * between two already-loaded screens fires no request at all, so "which screens
 * did this user open" — the first question an access review asks — had no answer.
 * This reports those events so the audit trail is complete.
 *
 * <h2>Batched, and never in the way</h2>
 * Screen views arrive in bursts (a user clicking through four modules in ten
 * seconds), so events are queued and flushed together. Three rules keep this from
 * ever degrading the app:
 *
 *   1. Failures are swallowed. An audit ping that surfaces an error toast, or
 *      worse retries a failing endpoint in a loop, has turned an observability
 *      feature into a user-visible fault. If the flush fails, the events are
 *      dropped and the app carries on.
 *   2. Nothing blocks navigation. The flush is fire-and-forget; no screen waits
 *      on it.
 *   3. The queue is capped. A tab left open on a flaky connection accumulates
 *      events; without a cap that is an unbounded array in a long-lived tab.
 *
 * <h2>Why the last flush uses sendBeacon</h2>
 * The most interesting event is often the last one before the tab closes, and a
 * normal fetch started during `pagehide` is cancelled as the document tears down.
 * `sendBeacon` is the one transport the browser promises to deliver after the
 * page is gone. It cannot set an Authorization header, so it is used only when a
 * cookie-authenticated fallback exists; otherwise the queue is flushed eagerly on
 * `visibilitychange` instead, which fires BEFORE `pagehide` and while fetch still
 * works. See flush().
 */

/** The closed vocabulary, mirroring UiEventService.ALLOWED_ACTIONS on the server. */
export type UiAction =
  | "SCREEN_VIEW"
  | "SIGN_OUT"
  | "SESSION_RESUME"
  | "VIEW_APPLIED"
  | "THEME_CHANGED"
  | "LOCALE_CHANGED"
  | "EXPORT_STARTED"
  | "RECORD_OPENED"
  | "SEARCH_SUBMITTED";

interface QueuedEvent {
  action: UiAction;
  screen: string;
  objectType?: string;
  objectId?: string;
  /** When the client observed it, used to compute ageMs at flush time. */
  at: number;
}

/**
 * Matches the server's MAX_BATCH. Kept at the same number deliberately: a larger
 * queue here would mean every flush of a backlog is rejected as an oversized
 * batch, so the backlog would never drain.
 */
const MAX_QUEUE = 50;
const FLUSH_AFTER_MS = 4000;

let queue: QueuedEvent[] = [];
let timer: ReturnType<typeof setTimeout> | null = null;
let listenersBound = false;

/*
 * Circuit breaker.
 *
 * Swallowing the error was not enough on its own. If the ingest endpoint is
 * unavailable — an older server, a proxy that does not know the route, a rolling
 * deploy — the queue keeps refilling on every navigation and flushing every four
 * seconds, forever. The app stays correct, but the browser console fills with
 * failed requests and the network tab fills with retries, which is exactly what
 * an operator sees and reports as a bug. It also makes a genuinely broken
 * endpoint indistinguishable from noise.
 *
 * So after three consecutive failures the reporter gives up for the rest of the
 * session and says so once. Screen-view telemetry is the least important traffic
 * the app generates; it does not get to be the loudest.
 */
const FAILURE_LIMIT = 3;
let consecutiveFailures = 0;
let disabled = false;

function scheduleFlush() {
  if (timer !== null) return;
  timer = setTimeout(() => {
    timer = null;
    void flush();
  }, FLUSH_AFTER_MS);
}

async function flush() {
  if (disabled || queue.length === 0) return;
  // Taken before the await so events reported during the request are not lost
  // and are not sent twice.
  const batch = queue;
  queue = [];
  if (timer !== null) {
    clearTimeout(timer);
    timer = null;
  }

  const now = Date.now();
  try {
    await api.reportUiEvents(batch.map((event) => ({
      action: event.action,
      screen: event.screen,
      objectType: event.objectType ?? null,
      objectId: event.objectId ?? null,
      // The server timestamps on receipt and treats this as evidence only, so a
      // skewed client clock cannot reorder the audit trail.
      ageMs: Math.max(0, now - event.at),
    })));
    consecutiveFailures = 0;
  } catch {
    /*
     * Deliberately dropped, not requeued. Requeuing on failure turns an outage
     * into a growing queue that retries forever and flushes a thousand stale
     * events the moment connectivity returns. Losing a screen-view row is a far
     * smaller problem than that, and every security-relevant action is recorded
     * server-side regardless of whether this succeeds.
     */
    consecutiveFailures += 1;
    if (consecutiveFailures >= FAILURE_LIMIT) {
      disabled = true;
      queue = [];
      // One line, once — not one per attempt. An operator reading the console
      // should be told the feature switched itself off and why, rather than
      // scrolling past a hundred identical network failures.
      console.warn(
        "[axiom] UI activity reporting disabled for this session after "
        + `${FAILURE_LIMIT} consecutive failures. Screen-view telemetry is paused; `
        + "all server-side actions are still recorded. Reload once the API is reachable.",
      );
    }
  }
}

/** Queue one event. Safe to call from anywhere, including during render effects. */
export function reportUiEvent(action: UiAction, screen: string,
                              object?: { type?: string; id?: string }) {
  if (queue.length >= MAX_QUEUE) {
    // Drop the OLDEST. A full queue means flushing is failing, and in that state
    // the recent events are the ones worth keeping.
    queue.shift();
  }
  queue.push({
    action,
    screen,
    objectType: object?.type,
    objectId: object?.id,
    at: Date.now(),
  });
  scheduleFlush();
}

/**
 * Flush now, for events that must not wait — signing out, in particular, since
 * the token is about to be discarded and an unflushed queue would be unsendable.
 */
export function flushUiEvents() {
  return flush();
}

function bindLifecycleListeners() {
  if (listenersBound) return;
  listenersBound = true;

  /*
   * visibilitychange, not pagehide, is the primary hook. It fires when the tab is
   * backgrounded — well before teardown — so a normal authenticated fetch still
   * works. By the time pagehide fires, fetch is unreliable and only sendBeacon is
   * guaranteed, and sendBeacon cannot carry the bearer token this API requires.
   * Flushing at the earlier moment is what makes the last events actually arrive.
   */
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") void flush();
  });

  // Belt and braces for the case where the tab is closed without ever being
  // hidden first (some desktop shells do this).
  window.addEventListener("pagehide", () => { void flush(); });
}

/**
 * Reports a SCREEN_VIEW on every route change, and mounts the lifecycle flush
 * hooks. Call once, inside the authenticated shell — there is no token before
 * that, and reporting activity for an anonymous visitor would have no actor to
 * attribute it to.
 */
export function useScreenViewTracking() {
  const location = useLocation();
  const lastReported = useRef<string | null>(null);

  useEffect(() => {
    bindLifecycleListeners();
  }, []);

  useEffect(() => {
    const screen = location.pathname;
    /*
     * React Router re-runs this effect on a search-string or state change, and in
     * StrictMode dev it runs effects twice. Both would produce duplicate rows for
     * one navigation, so the path is compared against the last one reported —
     * the pathname is also all the server stores, since query strings are
     * stripped for carrying filter values and personal data.
     */
    if (lastReported.current === screen) return;
    lastReported.current = screen;
    reportUiEvent("SCREEN_VIEW", screen);
  }, [location.pathname]);
}

package com.axiom.activity;

import com.axiom.common.CorrelationIdFilter;
import com.axiom.tenancy.TenantContext;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ingest for activity that never reaches the API on its own.
 *
 * <h2>The gap this closes</h2>
 * {@link UserActivityFilter} captures one row per HTTP request, which covers
 * every action with a server effect. It cannot capture what happens entirely in
 * the browser: moving between two already-loaded screens, signing out locally,
 * applying a saved view to a grid the client already holds. Those are real user
 * activity — "which screens did this user open, and when" is the question an
 * access review asks first — and until now none of it was recorded anywhere.
 *
 * <h2>Why the client is not trusted with any of the important fields</h2>
 * The client supplies WHAT happened. It supplies none of WHO, WHERE FROM, or
 * WHEN:
 *
 * <ul>
 *   <li><b>Identity</b> comes from {@link TenantContext}, i.e. from the verified
 *       token, never from the payload. A client-supplied actor id would let any
 *       authenticated user write activity rows attributed to a colleague, which
 *       turns the audit log into a place to plant evidence.</li>
 *   <li><b>Timestamp</b> is the database's {@code now()}. A client clock can be
 *       wrong by hours or set deliberately; an audit trail that can be reordered
 *       by the subject of the audit is not one. The client's own idea of how old
 *       the event is arrives as {@code durationMs} in detail, where it is
 *       evidence rather than the ordering key.</li>
 *   <li><b>Client IP</b> comes from the request that delivered the batch.</li>
 *   <li><b>Action</b> must be one of {@link #ALLOWED_ACTIONS}. The action column
 *       is the greppable index over this table and the thing dashboards group
 *       by; letting a client write arbitrary strings into it would let one
 *       release's typo fragment a year of history.</li>
 * </ul>
 *
 * <h2>Outcome is always SUCCESS here</h2>
 * These are observations of things that already happened in the UI, not results
 * of an authorization decision. A DENIED row means a server refused something,
 * and a client cannot be the source of that claim — the refusals are recorded by
 * the filter, at the point the refusal was actually issued.
 */
@Service
public class UiEventService {

    /**
     * The closed vocabulary of client-reportable actions.
     *
     * <p>Deliberately small and screen-shaped. It is not "every click": logging
     * every control interaction would multiply this table by two orders of
     * magnitude and bury the security-relevant rows inside filter twiddling. Each
     * entry here answers a question somebody actually asks of an audit log.
     */
    public static final Set<String> ALLOWED_ACTIONS = Set.of(
            "UI SCREEN_VIEW",      // opened a screen
            "UI SIGN_OUT",         // ended the session from the client
            "UI SESSION_RESUME",   // returned to a still-valid session in a new tab
            "UI VIEW_APPLIED",     // applied a saved list view
            "UI THEME_CHANGED",    // changed appearance
            "UI LOCALE_CHANGED",   // changed language
            "UI EXPORT_STARTED",   // began a client-side download
            "UI RECORD_OPENED",    // opened a record detail or drawer
            "UI SEARCH_SUBMITTED");// ran a command-palette or global search

    /** One event as the client reports it. */
    public record UiEvent(String action, String screen, String objectType, UUID objectId,
                          Integer ageMs) {}

    /**
     * A batch is capped, and the cap is enforced rather than trimmed silently.
     * Fifty covers a slow tab flushing a backlog; more than that is either a bug
     * or someone testing how much they can write.
     */
    public static final int MAX_BATCH = 50;

    private final UserActivityService activity;

    public UiEventService(UserActivityService activity) {
        this.activity = activity;
    }

    /**
     * @return how many events were accepted. Unknown actions are skipped rather
     *         than failing the batch: a client one release ahead of the server
     *         will send an action this build has never heard of, and rejecting
     *         the whole batch for it would lose the eight valid events beside it.
     */
    public int record(java.util.List<UiEvent> events, String clientIp, String userAgent) {
        TenantContext.Principal principal = TenantContext.get();
        UUID impersonatorId = TenantContext.isImpersonating()
                ? TenantContext.impersonator().userId() : null;
        String impersonatorEmail = TenantContext.isImpersonating()
                ? TenantContext.impersonator().email() : null;

        int accepted = 0;
        for (UiEvent event : events) {
            String action = normaliseAction(event.action());
            if (action == null) continue;

            Map<String, Object> detail = new LinkedHashMap<>();
            // durationMs is already on the allowlist, so it survives sanitise().
            // It is the client's claim about the event's age, kept as evidence
            // beside the server timestamp rather than replacing it.
            if (event.ageMs() != null && event.ageMs() >= 0) {
                detail.put("durationMs", event.ageMs());
            }
            if (event.objectType() != null) detail.put("objectType", event.objectType());

            activity.record(new UserActivityService.ActivityEvent(
                    principal.tenantId(),
                    principal.userId(),
                    principal.email(),
                    principal.role(),
                    impersonatorId,
                    impersonatorEmail,
                    action,
                    null,                       // no HTTP verb: this was not a request
                    screenPath(event.screen()),
                    event.objectType(),
                    event.objectId(),
                    "UI",
                    UserActivityService.SUCCESS,
                    null,                       // no status code, for the same reason
                    null,
                    MDC.get(CorrelationIdFilter.MDC_KEY),
                    clientIp,
                    userAgent,
                    detail));
            accepted++;
        }
        return accepted;
    }

    /** Uppercased and matched against the closed set; anything else is dropped. */
    static String normaliseAction(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String candidate = raw.trim().toUpperCase(Locale.ROOT);
        if (!candidate.startsWith("UI ")) candidate = "UI " + candidate;
        return ALLOWED_ACTIONS.contains(candidate) ? candidate : null;
    }

    /**
     * A client route, reduced to a path.
     *
     * <p>The query string is dropped before storage — {@code record()} strips it
     * too, but doing it here as well means a route with a query never travels any
     * further than this method. Per FR-AUD-014 and the column comment on
     * {@code request_path}, query strings carry filter values and in practice
     * carry personal data.
     *
     * <p>UUID segments are collapsed to {@code {id}} for the same reason the
     * filter does it: otherwise "opened an account" becomes one distinct value
     * per account and stops aggregating. The specific record still arrives, in
     * {@code object_id}, where it is queryable.
     */
    static String screenPath(String screen) {
        if (screen == null || screen.isBlank()) return null;
        String path = screen.trim();
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        if (!path.startsWith("/")) path = "/" + path;
        if (path.length() > 300) path = path.substring(0, 300);

        StringBuilder out = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            out.append('/').append(isUuid(part) ? "{id}" : part);
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    private static boolean isUuid(String value) {
        if (value.length() != 36) return false;
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

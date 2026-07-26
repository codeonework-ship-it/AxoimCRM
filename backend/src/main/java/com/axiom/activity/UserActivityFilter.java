package com.axiom.activity;

import com.axiom.auth.JwtService;
import com.axiom.common.CorrelationIdFilter;
import com.axiom.tenancy.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Structural user-activity capture: one row per API request, whatever the
 * outcome.
 *
 * <h2>Why a filter and not per-controller</h2>
 * A per-controller or per-service approach captures exactly the endpoints
 * somebody remembered, and silently misses every endpoint added afterwards —
 * which is the same as having no coverage guarantee at all. A filter is the only
 * place where "every request" is a property of the code rather than a promise.
 *
 * <h2>Why order 5, in front of JwtAuthFilter</h2>
 * The most valuable rows are the refusals, and the majority of refusals in this
 * product are issued by {@code JwtAuthFilter} itself: an expired session, a
 * revoked session, a read-only auditor attempting a mutation, an impersonating
 * operator attempting to escalate. A filter ordered <i>after</i> it would never
 * run for any of those, because {@code JwtAuthFilter} returns without calling
 * the chain. Sitting in front of it means this filter observes the final status
 * code of every request, denied or not.
 *
 * <p>The cost of that position is that {@link TenantContext} has already been
 * cleared by the time control returns here ({@code JwtAuthFilter} clears it in a
 * {@code finally}, correctly — a leaked principal on a pooled thread is a
 * cross-tenant bug). So the identity for the row is re-derived from the same
 * verified token, using the same {@link JwtService}. It is a second signature
 * verification per request, and it is worth it: the alternative is either losing
 * every filter-level denial or reaching into another team's file.
 *
 * <h2>What is never captured</h2>
 * No request body, no headers, no query string. See {@link UserActivityService}
 * for the FR-AUD-014 argument. Recording failures are swallowed: an access log
 * that can 500 a working request has turned an observability feature into an
 * availability incident.
 */
@Component
@Order(5)
public class UserActivityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UserActivityFilter.class);

    /**
     * Paths that are pure noise. Health probes and static assets say nothing
     * about who did what, and at probe frequency they would bury the rows that
     * matter.
     */
    private static final List<String> IGNORED_PREFIXES = List.of(
            "/actuator", "/api/v1/i18n/", "/api/v1/branding/");

    /**
     * Path segments that name a securable object, so {@code object_type} on the
     * row is the domain's vocabulary rather than a URL fragment.
     */
    private static final Map<String, String> OBJECT_SEGMENTS = Map.ofEntries(
            Map.entry("accounts", "ACCOUNT"),
            Map.entry("contacts", "CONTACT"),
            Map.entry("leads", "LEAD"),
            Map.entry("opportunities", "OPPORTUNITY"),
            Map.entry("pipeline", "OPPORTUNITY"),
            Map.entry("quotes", "QUOTE"),
            Map.entry("activities", "ACTIVITY"),
            Map.entry("users", "USER"),
            Map.entry("roles", "ROLE"),
            Map.entry("profiles", "PROFILE"),
            Map.entry("permission-sets", "PERMISSION_SET"),
            Map.entry("sharing-rules", "SHARING_RULE"),
            Map.entry("sod", "SOD_CONFLICT"),
            Map.entry("org-wide-defaults", "ORG_WIDE_DEFAULT"));

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final UserActivityService activity;
    private final JwtService jwtService;

    public UserActivityFilter(UserActivityService activity, JwtService jwtService) {
        this.activity = activity;
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || IGNORED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            try {
                capture(request, response, (int) ((System.nanoTime() - startedAt) / 1_000_000L));
            } catch (RuntimeException e) {
                log.warn("User activity not recorded for {} {}", request.getMethod(),
                        request.getRequestURI(), e);
            } finally {
                // The pending denial is request-scoped. Leaving it behind would
                // attribute one request's refusal to the next one on this thread.
                activity.clearPendingDenial();
            }
        }
    }

    private void capture(HttpServletRequest request, HttpServletResponse response, int durationMs) {
        UserActivityService.PendingDenial pending = activity.takePendingDenial();
        Claims claims = claimsOf(request);
        if (claims == null) return;   // no verified tenant: see UserActivityService.record

        UUID tenantId = uuid(claims.get("tid", String.class));
        if (tenantId == null) return;

        int status = response.getStatus();
        String outcome = outcomeOf(status);
        String path = request.getRequestURI();

        String objectType = pending != null && pending.objectType() != null
                ? pending.objectType() : objectTypeOf(path);
        UUID objectId = pending != null && pending.objectId() != null
                ? pending.objectId() : firstUuidIn(path);

        String denialReason = pending != null ? pending.reason()
                : (UserActivityService.DENIED.equals(outcome) ? defaultDenialReason(status) : null);

        UUID actorId = uuid(claims.get("uid", String.class));
        String actorRole = claims.get("role", String.class);

        // TenantSessionAspect binds app.tenant_id from TenantContext, and
        // JwtAuthFilter cleared it on the way back out. Without rebinding, the
        // RLS WITH CHECK on activity.user_activity would reject our own insert.
        // Re-bound from the same verified claims, and cleared immediately.
        TenantContext.set(new TenantContext.Principal(
                tenantId, actorId, actorRole, claims.get("name", String.class), claims.getSubject()));
        try {
            activity.record(new UserActivityService.ActivityEvent(
                    tenantId,
                    actorId,
                    claims.getSubject(),
                    actorRole,
                    uuid(claims.get("imp_uid", String.class)),
                    claims.get("imp_email", String.class),
                    actionOf(request.getMethod(), path),
                    request.getMethod(),
                    path,
                    objectType,
                    objectId,
                    sourceOf(request),
                    outcome,
                    status,
                    denialReason,
                    MDC.get(CorrelationIdFilter.MDC_KEY),
                    clientIp(request),
                    request.getHeader("User-Agent"),
                    Map.of("durationMs", durationMs)));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Parse the bearer token if there is one. A malformed, expired or forged
     * token yields null and the request goes unrecorded here — it is already
     * recorded, with more detail, in {@code identity.login_attempt} and the
     * authentication audit. Binding an unverified tenant id from a client-
     * supplied token would breach ADR-001 rule 4 for the sake of a log line.
     */
    private Claims claimsOf(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        try {
            return jwtService.parse(header.substring("Bearer ".length()).trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * SUCCESS below 400; DENIED for the authorization statuses; ERROR for
     * everything else. 404 counts as an error rather than a denial because this
     * product returns 404 for "you may not see it" as well as "it is not there"
     * — calling every one of those a denial would drown the real ones.
     */
    static String outcomeOf(int status) {
        if (status < 400) return UserActivityService.SUCCESS;
        if (status == 401 || status == 403) return UserActivityService.DENIED;
        return UserActivityService.ERROR;
    }

    private static String defaultDenialReason(int status) {
        return status == 401
                ? "Authentication was refused or the session was not accepted."
                : "Authorization was refused for this request.";
    }

    /**
     * A stable, greppable action name: the method plus the path with identifiers
     * collapsed. {@code DELETE /api/v1/security/rbac/roles/{id}} aggregates,
     * where the raw path would give one distinct action per record touched.
     */
    static String actionOf(String method, String path) {
        StringBuilder out = new StringBuilder(method.toUpperCase(Locale.ROOT)).append(' ');
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            out.append('/').append(isUuid(part) ? "{id}" : part);
        }
        return out.toString();
    }

    static String objectTypeOf(String path) {
        String[] parts = path.split("/");
        String candidate = null;
        for (String part : parts) {
            String mapped = OBJECT_SEGMENTS.get(part.toLowerCase(Locale.ROOT));
            if (mapped != null) candidate = mapped;
        }
        return candidate;
    }

    static UUID firstUuidIn(String path) {
        for (String part : path.split("/")) {
            if (isUuid(part)) return UUID.fromString(part);
        }
        return null;
    }

    /**
     * A browser session is UI; anything else calling the same API is API. The
     * distinction matters to a reviewer: the same action taken by a script and
     * by a person in a screen are different events.
     */
    private static String sourceOf(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent == null || agent.isBlank()) return "API";
        String lower = agent.toLowerCase(Locale.ROOT);
        boolean browser = lower.contains("mozilla") || lower.contains("chrome")
                || lower.contains("safari") || lower.contains("firefox") || lower.contains("edg/");
        return browser ? "UI" : "API";
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
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

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Exposed for the unit test that asserts read requests are still captured. */
    static boolean isRead(String method) {
        return READ_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }
}

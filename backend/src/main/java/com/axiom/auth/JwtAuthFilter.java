package com.axiom.auth;

import com.axiom.common.ApiError;
import com.axiom.common.CorrelationIdFilter;
import com.axiom.identity.SessionService;
import com.axiom.identity.TenantLifecycleService;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Plain servlet filter (no spring-security-web on the classpath) that turns a
 * Bearer JWT into the ambient {@link TenantContext}. The tenant id comes ONLY
 * from the verified token signature — never from a client-controllable header
 * (ADR-001 rule 4).
 *
 * <p>Ordered after CorrelationIdFilter so 401 envelopes carry the correlation id.
 * TenantContext is cleared in finally: request threads are pooled, and a
 * leaked principal on a recycled thread would be a cross-tenant bug.
 *
 * <p>Beyond signature verification this filter is the single choke point for four
 * E01 controls, deliberately placed here rather than scattered across controllers:
 *
 * <ol>
 *   <li><b>Session revocation is effective</b> (FR-TEN-010). The token's {@code jti}
 *       is looked up in {@code identity.user_session} on every request; a revoked,
 *       expired or idled-out session is refused with 401 immediately, without
 *       waiting for the token to expire.</li>
 *   <li><b>A suspended workspace blocks business writes</b> (FR-TEN-002) while
 *       still permitting administrator access and export. Enforced on the request
 *       path so a new controller inherits it instead of having to remember it.</li>
 *   <li><b>An impersonating operator cannot escalate</b> (FR-TEN-011): mutations to
 *       user, role and identity administration are refused while impersonating.</li>
 *   <li><b>An MFA challenge token is not an access token.</b> It is accepted only by
 *       the verification endpoint, which this filter does not guard.</li>
 * </ol>
 */
@Component
@Order(10)
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Endpoints that must work before, or without, an access token. */
    private static final Set<String> UNGUARDED = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/mfa/verify",
            "/api/v1/auth/service-token");

    /** Unauthenticated prefixes: sign-in branding is read before anyone signs in. */
    private static final List<String> UNGUARDED_PREFIXES = List.of("/api/v1/branding/");

    /**
     * OPTIONALLY authenticated prefixes — a distinct category from
     * {@link #UNGUARDED_PREFIXES}, not a synonym.
     *
     * The translation registry must answer before a token exists, or the login
     * screen cannot be shown in the user's language. But a blanket skip would
     * also discard a token that IS present, and then the tenant relabelling in
     * {@code i18n.tenant_translation_override} could never resolve on any
     * request — the whole override table would be dead weight.
     *
     * So the skip applies only when there is no Authorization header. No header:
     * anonymous, base product strings only, because with no tenant bound the RLS
     * policy on the override table admits nothing (correct — a tenant's private
     * vocabulary is not public data). Header present: the normal verified path,
     * including session-revocation checks, with TenantContext bound so the
     * tenant's own overrides apply. Token verification itself is unchanged and
     * the tenant id still comes only from the signature (ADR-001 rule 4).
     */
    private static final List<String> OPTIONAL_AUTH_PREFIXES = List.of("/api/v1/i18n/");

    /**
     * Prefixes whose mutations remain available while a workspace is suspended:
     * authentication, administration and security governance. FR-TEN-002 requires
     * administrator login and export to keep working, and an administrator who
     * cannot re-authenticate or lift the suspension has no path back.
     */
    private static final List<String> SUSPENDED_WRITE_ALLOWED = List.of(
            "/api/v1/auth/",
            "/api/v1/admin/",
            "/api/v1/platform/",
            "/api/v1/security/",
            "/api/v1/identity/");

    /** Prefixes an impersonating operator may not mutate (self-escalation guard). */
    private static final List<String> IMPERSONATION_FORBIDDEN = List.of(
            "/api/v1/admin/users",
            "/api/v1/rbac",
            "/api/v1/identity/",
            "/api/v1/security/",
            "/api/v1/platform/");

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final SessionService sessions;

    public JwtAuthFilter(JwtService jwtService, ObjectMapper objectMapper, SessionService sessions) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.sessions = sessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return UNGUARDED.contains(path)
                || UNGUARDED_PREFIXES.stream().anyMatch(path::startsWith)
                || (request.getHeader("Authorization") == null
                        && OPTIONAL_AUTH_PREFIXES.stream().anyMatch(path::startsWith))
                || path.startsWith("/actuator")
                || !path.startsWith("/api/")                       // only the API surface is guarded
                || "OPTIONS".equalsIgnoreCase(request.getMethod()); // CORS preflight carries no token
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            reject(response, "Missing bearer token");
            return;
        }
        String jti;
        UUID tenantId;
        try {
            Claims claims = jwtService.parse(header.substring("Bearer ".length()).trim());
            if (JwtService.TYPE_MFA_CHALLENGE.equals(claims.get("typ", String.class))) {
                reject(response, "Finish the second-factor step before using this token");
                return;
            }
            tenantId = UUID.fromString(claims.get("tid", String.class));
            jti = claims.getId();
            TenantContext.set(new TenantContext.Principal(
                    tenantId,
                    UUID.fromString(claims.get("uid", String.class)),
                    claims.get("role", String.class),
                    claims.get("name", String.class),
                    claims.getSubject()));
            TenantContext.setSessionJti(jti);
            TenantContext.setClientIp(AuthController.clientIp(request));
            String impersonatorId = claims.get("imp_uid", String.class);
            if (impersonatorId != null) {
                TenantContext.setImpersonator(new TenantContext.Impersonator(
                        UUID.fromString(impersonatorId),
                        claims.get("imp_email", String.class),
                        claims.get("imp_name", String.class)));
            }
        } catch (JwtException | IllegalArgumentException e) {
            reject(response, "Invalid or expired token");
            return;
        }
        try {
            if (jti == null) {
                // Every token this build issues carries a jti backed by a session
                // row. One without cannot be revoked, so it is not honoured.
                reject(response, "This session is not recognised. Sign in again.");
                return;
            }
            SessionService.SessionState state = sessions.validate(tenantId, jti);
            if (!state.valid()) {
                reject(response, switch (state.verdict()) {
                    case REVOKED -> "This session was ended by an administrator. Sign in again.";
                    case EXPIRED -> "This session has expired. Sign in again.";
                    case IDLE_TIMEOUT -> "This session ended after a period of inactivity. Sign in again.";
                    default -> "This session is not recognised. Sign in again.";
                });
                return;
            }

            CrmRole role = CrmRole.current(TenantContext.get().role());
            String path = request.getRequestURI();
            boolean mutation = !(request.getMethod().equalsIgnoreCase("GET")
                    || request.getMethod().equalsIgnoreCase("HEAD")
                    || request.getMethod().equalsIgnoreCase("OPTIONS"));
            boolean tenantSwitch = path.equals("/api/v1/auth/switch-tenant");
            if (role.readOnly() && mutation && !tenantSwitch) {
                rejectForbidden(response, "This audit role is read-only across every surface");
                return;
            }
            if (mutation && TenantLifecycleService.blocksBusinessWrites(state.tenantStatus())
                    && SUSPENDED_WRITE_ALLOWED.stream().noneMatch(path::startsWith)) {
                rejectForbidden(response, "This workspace is " + state.tenantStatus()
                        + ", so changes to business data are blocked. Reading and exporting still work. "
                        + "An administrator can lift the suspension from workspace administration.");
                return;
            }
            // Ending the impersonation is the one identity-surface mutation an
            // impersonating session must be able to make. Without this exemption the
            // escalation guard would trap the operator inside the session it is
            // meant to constrain.
            boolean endingImpersonation = path.startsWith("/api/v1/identity/impersonation/")
                    && path.endsWith("/stop");
            if (mutation && TenantContext.isImpersonating() && !endingImpersonation
                    && IMPERSONATION_FORBIDDEN.stream().anyMatch(path::startsWith)) {
                rejectForbidden(response, "Permission, role and security changes are refused while "
                        + "impersonating a user. End the impersonation and act as yourself.");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void rejectForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ApiError.of("FORBIDDEN", message, MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ApiError.of("UNAUTHORIZED", message, MDC.get(CorrelationIdFilter.MDC_KEY)));
    }
}

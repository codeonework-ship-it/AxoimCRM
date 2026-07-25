package com.axiom.auth;

import com.axiom.common.ApiError;
import com.axiom.common.CorrelationIdFilter;
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
import java.util.UUID;

/**
 * Plain servlet filter (no spring-security-web on the classpath) that turns a
 * Bearer JWT into the ambient {@link TenantContext}. The tenant id comes ONLY
 * from the verified token signature — never from a client-controllable header
 * (ADR-001 rule 4).
 *
 * Ordered after CorrelationIdFilter so 401 envelopes carry the correlation id.
 * TenantContext is cleared in finally: request threads are pooled, and a
 * leaked principal on a recycled thread would be a cross-tenant bug.
 */
@Component
@Order(10)
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/v1/auth/login")
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
        try {
            Claims claims = jwtService.parse(header.substring("Bearer ".length()).trim());
            TenantContext.set(new TenantContext.Principal(
                    UUID.fromString(claims.get("tid", String.class)),
                    UUID.fromString(claims.get("uid", String.class)),
                    claims.get("role", String.class),
                    claims.get("name", String.class),
                    claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            reject(response, "Invalid or expired token");
            return;
        }
        try {
            CrmRole role = CrmRole.current(TenantContext.get().role());
            boolean mutation = !(request.getMethod().equalsIgnoreCase("GET")
                    || request.getMethod().equalsIgnoreCase("HEAD")
                    || request.getMethod().equalsIgnoreCase("OPTIONS"));
            boolean tenantSwitch = request.getRequestURI().equals("/api/v1/auth/switch-tenant");
            if (role.readOnly() && mutation && !tenantSwitch) {
                rejectForbidden(response, "This audit role is read-only across every surface");
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

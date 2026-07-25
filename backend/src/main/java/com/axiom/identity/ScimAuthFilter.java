package com.axiom.identity;

import com.axiom.auth.CrmRole;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates the SCIM surface with its own bearer token (FR-TEN-007).
 *
 * <p>SCIM is deliberately <em>not</em> guarded by {@code JwtAuthFilter}: a
 * directory connector must not hold a user's access token. This filter verifies an
 * {@code identity.scim_token} instead and binds a synthetic {@code INTEGRATION}
 * principal for the workspace the credential belongs to, so every downstream query
 * runs under the same tenant scoping and RLS as any other request.
 *
 * <p>The tenant comes from the credential, never from the URL or a header — the
 * token embeds its own workspace slug and the secret is verified inside that
 * workspace's rows.
 */
@Component
@Order(12)
public class ScimAuthFilter extends OncePerRequestFilter {

    private static final String PATH_PREFIX = "/scim/";

    private final ScimTokenService tokens;
    private final ObjectMapper objectMapper;

    public ScimAuthFilter(ScimTokenService tokens, ObjectMapper objectMapper) {
        this.tokens = tokens;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorized(response, "A SCIM provisioning token is required in the Authorization header");
            return;
        }
        ScimTokenService.AuthenticatedToken token;
        try {
            token = tokens.authenticate(header.substring("Bearer ".length()).trim());
        } catch (RuntimeException e) {
            unauthorized(response, "Invalid provisioning token");
            return;
        }
        boolean mutation = !(request.getMethod().equalsIgnoreCase("GET")
                || request.getMethod().equalsIgnoreCase("HEAD"));
        if (mutation && !token.scopes().contains("users:write")) {
            forbidden(response, "This provisioning token has read-only scope ("
                    + String.join(", ", token.scopes()) + "). Issue one with users:write to make changes.");
            return;
        }
        try {
            // A synthetic principal, not a real user: the credential is the actor.
            // INTEGRATION is a non-interactive role by design (CrmRole).
            TenantContext.set(new TenantContext.Principal(token.tenantId(), token.id(),
                    CrmRole.INTEGRATION.name(), "SCIM: " + token.name(),
                    "scim-token+" + token.id() + "@axiom.local"));
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void unauthorized(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/scim+json");
        objectMapper.writeValue(response.getWriter(), ScimUserService.error(401, detail, null));
    }

    private void forbidden(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/scim+json");
        objectMapper.writeValue(response.getWriter(), ScimUserService.error(403, detail, null));
    }
}

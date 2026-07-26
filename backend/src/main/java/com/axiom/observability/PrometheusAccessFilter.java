package com.axiom.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Protects the Prometheus scrape endpoint without involving tenant JWTs.
 *
 * <p>A Prometheus server is a machine principal, not a CRM user. Reusing a user
 * JWT here would create an expiring operational dependency and would bind a
 * platform scrape to one tenant. A dedicated, independently rotated bearer
 * secret gives the scraper the one capability it needs and nothing else.
 */
@Component
@Order(1)
public class PrometheusAccessFilter extends OncePerRequestFilter {

    static final String SCRAPE_PATH = "/actuator/prometheus";
    static final String DEVELOPMENT_TOKEN = "axiom-prometheus-dev-token-change-before-use";

    private final byte[] expectedToken;
    private final boolean enabled;

    public PrometheusAccessFilter(
            @Value("${axiom.observability.prometheus.scrape-token:" + DEVELOPMENT_TOKEN + "}") String token,
            @Value("${axiom.observability.prometheus.enabled:true}") boolean enabled,
            @Value("${axiom.environment.name:dev}") String environment) {
        this.enabled = enabled;
        String value = token == null ? "" : token.trim();
        if (enabled && value.length() < 32) {
            throw new IllegalStateException("AXIOM_PROMETHEUS_SCRAPE_TOKEN must contain at least 32 characters");
        }
        String env = environment == null ? "dev" : environment.toLowerCase(Locale.ROOT);
        if (enabled && !(env.equals("dev") || env.equals("test")) && DEVELOPMENT_TOKEN.equals(value)) {
            throw new IllegalStateException("The development Prometheus scrape token is forbidden outside dev/test");
        }
        this.expectedToken = value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SCRAPE_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        byte[] supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        if (!MessageDigest.isEqual(expectedToken, supplied)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer realm=\"axiom-metrics\"");
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        chain.doFilter(request, response);
    }
}

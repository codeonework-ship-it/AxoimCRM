package com.axiom.common;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS for browser clients, with trusted origins supplied by
 * {@code axiom.cors.allowed-origins}.
 *
 * <p>This is deliberately a servlet filter rather than an MVC-only mapping.
 * Authentication and activity filters can terminate a request before it ever
 * reaches MVC (for example an expired token returning {@code 401}). CORS must
 * run first so those security responses remain readable by an allowed browser
 * and are not incorrectly presented as a network outage.
 */
@Configuration
public class CorsConfig {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${axiom.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @PostConstruct
    void validateOrigins() {
        if (allowedOrigins.length == 0 || Arrays.stream(allowedOrigins).anyMatch(origin -> origin == null || origin.isBlank() || "*".equals(origin.trim()))) {
            throw new IllegalStateException("axiom.cors.allowed-origins must contain explicit trusted origins; wildcard CORS is not allowed");
        }
    }

    @org.springframework.context.annotation.Bean
    FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT,
                CorrelationIdFilter.HEADER,
                "X-Requested-With"));
        configuration.setExposedHeaders(List.of(CorrelationIdFilter.HEADER, HttpHeaders.CONTENT_DISPOSITION));
        // Federation state is bound to an HttpOnly cookie. Wildcards are rejected
        // above, so credentialed CORS remains limited to explicitly trusted UIs.
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setName("axiomCorsFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}

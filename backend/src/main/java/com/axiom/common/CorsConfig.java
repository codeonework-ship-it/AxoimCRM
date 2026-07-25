package com.axiom.common;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/** CORS for the browser clients, origins from axiom.cors.allowed-origins. */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

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

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT,
                        CorrelationIdFilter.HEADER, "X-Requested-With")
                .exposedHeaders(CorrelationIdFilter.HEADER, HttpHeaders.CONTENT_DISPOSITION)
                .allowCredentials(false)
                .maxAge(3600);
    }
}

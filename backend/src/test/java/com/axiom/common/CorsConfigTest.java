package com.axiom.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void allowedDevelopmentOriginsCanReadAuthenticationRejection() throws Exception {
        CorsConfig config = new CorsConfig(new String[]{"http://localhost:5173", "http://localhost:4280"});
        config.validateOrigins();
        FilterRegistrationBean<CorsFilter> registration = config.corsFilterRegistration();

        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        for (String origin : new String[]{"http://localhost:5173", "http://localhost:4280"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/dashboard/summary");
            request.addHeader(HttpHeaders.ORIGIN, origin);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain rejectingAuthenticationChain = (req, res) ->
                    ((HttpServletResponse) res).setStatus(HttpServletResponse.SC_UNAUTHORIZED);

            registration.getFilter().doFilter(request, response, rejectingAuthenticationChain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(origin);
        }
    }

    @Test
    void untrustedOriginNeverReceivesCorsAccess() throws Exception {
        CorsConfig config = new CorsConfig(new String[]{"http://localhost:4280"});
        config.validateOrigins();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/dashboard/summary");
        request.addHeader(HttpHeaders.ORIGIN, "https://untrusted.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        config.corsFilterRegistration().getFilter().doFilter(request, response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }
}

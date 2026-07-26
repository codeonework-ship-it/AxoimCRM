package com.axiom.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PrometheusAccessFilterTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    void validMachineTokenAllowsTheScrape() throws Exception {
        PrometheusAccessFilter filter = new PrometheusAccessFilter(TOKEN, true, "prod");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void missingOrWrongTokenFailsClosedWithoutRevealingTheSecret() throws Exception {
        PrometheusAccessFilter filter = new PrometheusAccessFilter(TOKEN, true, "prod");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("Authorization", "Bearer wrong-token-that-is-long-enough");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).contains("axiom-metrics");
        assertThat(response.getContentAsString()).doesNotContain(TOKEN);
    }

    @Test
    void healthProbeIsNotIntercepted() throws Exception {
        PrometheusAccessFilter filter = new PrometheusAccessFilter(TOKEN, true, "prod");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void unsafeTokensAndDevelopmentTokenOutsideDevelopmentAreRejectedAtStartup() {
        assertThatThrownBy(() -> new PrometheusAccessFilter("short", true, "prod"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PrometheusAccessFilter(
                PrometheusAccessFilter.DEVELOPMENT_TOKEN, true, "prod"))
                .isInstanceOf(IllegalStateException.class);
    }
}

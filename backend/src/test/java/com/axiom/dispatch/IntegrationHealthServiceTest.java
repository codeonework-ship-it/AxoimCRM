package com.axiom.dispatch;

import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-INT-009. The health view has to answer "is this working?" with numbers,
 * including the one nobody wants to publish: how many messages were lost.
 */
class IntegrationHealthServiceTest {

    private JdbcTemplate jdbc;
    private IntegrationHealthService health;
    private final List<Map<String, Object>> rows = new ArrayList<>();

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        health = new IntegrationHealthService(jdbc);
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(), "TENANT_ADMIN",
                "Ops Admin", "ops@example.com"));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            List<Object> mapped = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                mapped.add(mapper.mapRow(FakeRows.row(rows.get(i)), i));
            }
            return mapped;
        });
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    private Map<String, Object> connectorRow(String name, String breaker, boolean enabled,
                                             long success, long failure, int consecutive,
                                             long pending, long deadLetters) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", UUID.randomUUID());
        values.put("display_name", name);
        values.put("connector_type", "WEBHOOK");
        values.put("vendor", "GENERIC_WEBHOOK");
        values.put("enabled", enabled);
        values.put("breaker_state", breaker);
        values.put("consecutive_failures", consecutive);
        values.put("total_success", success);
        values.put("total_failure", failure);
        values.put("last_success_at", success > 0 ? Instant.parse("2026-07-25T09:00:00Z") : null);
        values.put("last_failure_at", failure > 0 ? Instant.parse("2026-07-25T09:30:00Z") : null);
        values.put("last_error", failure > 0 ? "Endpoint returned HTTP 503" : null);
        values.put("opened_at", "OPEN".equals(breaker) ? Instant.parse("2026-07-25T09:30:00Z") : null);
        values.put("pending_depth", pending);
        values.put("dead_letter_depth", deadLetters);
        values.put("oldest_pending_at", pending > 0 ? Instant.parse("2026-07-25T09:25:00Z") : null);
        return values;
    }

    @Test void healthReportsDeadLetterDepthAndQueueDepthPerConnector() {
        rows.add(connectorRow("Ops webhook", "OPEN", true, 12, 5, 3, 4, 6));
        rows.add(connectorRow("ERP posting", "CLOSED", true, 40, 0, 0, 0, 0));

        List<IntegrationHealthService.HealthRow> result = health.connectors();

        assertEquals(2, result.size());
        IntegrationHealthService.HealthRow broken = result.get(0);
        assertEquals(6, broken.deadLetterDepth(), "the DLQ depth must be reported, not hidden");
        assertEquals(4, broken.pendingDepth());
        assertEquals(3, broken.consecutiveFailures());
        assertEquals("OPEN", broken.breakerState());
        assertEquals("PAUSED", broken.status());
        assertEquals(Instant.parse("2026-07-25T09:30:00Z"), broken.lastFailureAt());
        assertEquals("Endpoint returned HTTP 503", broken.lastError());

        assertEquals("HEALTHY", result.get(1).status());
        assertEquals(0, result.get(1).deadLetterDepth());
    }

    @Test void aConnectorThatHasNeverDeliveredIsIdleRatherThanHealthy() {
        rows.add(connectorRow("Never fired", "CLOSED", true, 0, 0, 0, 0, 0));

        assertEquals("IDLE", health.connectors().get(0).status(),
                "a connector that has never delivered is untested, not healthy");
    }

    @Test void anyOpenDeadLetterMakesAConnectorDegradedEvenWithAClosedBreaker() {
        rows.add(connectorRow("Mostly fine", "CLOSED", true, 100, 1, 0, 0, 2));

        assertEquals("DEGRADED", health.connectors().get(0).status());
    }

    @Test void aDisabledConnectorIsReportedAsDisabledRatherThanHealthy() {
        rows.add(connectorRow("Paused by admin", "CLOSED", false, 10, 0, 0, 0, 0));

        assertEquals("DISABLED", health.connectors().get(0).status());
    }

    @Test void theSummaryAggregatesQueueAndDeadLetterDepthAcrossConnectors() {
        rows.add(connectorRow("Ops webhook", "OPEN", true, 12, 5, 3, 4, 6));
        rows.add(connectorRow("ERP posting", "CLOSED", true, 40, 0, 0, 1, 2));

        IntegrationHealthService.HealthSummary summary = health.summary();

        assertEquals(2, summary.connectors());
        assertEquals(2, summary.enabledConnectors());
        assertEquals(1, summary.openBreakers());
        assertEquals(5, summary.pendingDepth());
        assertEquals(8, summary.deadLetterDepth());
        assertEquals(7, summary.succeededLast24h());
    }
}

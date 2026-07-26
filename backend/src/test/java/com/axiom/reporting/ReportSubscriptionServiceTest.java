package com.axiom.reporting;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ReportSubscriptionServiceTest {
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void scheduleAdvanceIsDeterministic() {
        Instant start = Instant.parse("2026-07-26T00:00:00Z");
        assertEquals(start.plus(1, ChronoUnit.DAYS), ReportSubscriptionService.advance(start, "DAILY"));
        assertEquals(start.plus(7, ChronoUnit.DAYS), ReportSubscriptionService.advance(start, "WEEKLY"));
        assertEquals(start.plus(30, ChronoUnit.DAYS), ReportSubscriptionService.advance(start, "MONTHLY"));
    }

    @Test void nonAdministratorCannotCreateReportSubscription() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SALES", "Seller", "seller@example.test"));
        ReportSubscriptionService service = new ReportSubscriptionService(mock(JdbcTemplate.class),
                mock(ReportService.class), mock(AuditService.class));
        assertThrows(ForbiddenException.class, () -> service.create(new ReportSubscriptionService.CreateRequest(
                "pipeline_snapshot", "Weekly", ReportService.ReportFormat.PDF, "WEEKLY",
                List.of("ops@example.test"), Instant.now())));
    }
}

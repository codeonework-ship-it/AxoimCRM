package com.axiom.reporting;

import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ReportServiceTest {
    private ReportService service;

    @BeforeEach void setUp() {
        service = new ReportService(mock(JdbcTemplate.class), mock(ResourceLoader.class));
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "INTEGRATION", "Bot", "bot@example.com"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void integrationRoleCannotExportReports() {
        assertThrows(ForbiddenException.class, () -> service.export("tenant_summary", ReportService.ReportFormat.PDF));
    }
}

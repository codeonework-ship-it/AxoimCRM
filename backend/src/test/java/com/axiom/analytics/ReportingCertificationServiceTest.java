package com.axiom.analytics;

import com.axiom.audit.AuditService;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportingCertificationServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private JdbcTemplate jdbc;
    private ReconciliationService reconciliation;
    private KpiReconciliationService kpis;
    private ReportingCertificationService service;

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        reconciliation = mock(ReconciliationService.class);
        kpis = mock(KpiReconciliationService.class);
        service = new ReportingCertificationService(jdbc, mock(ProjectionBackfillService.class),
                reconciliation, kpis, mock(AuditService.class), new ObjectMapper());
        TenantContext.set(new TenantContext.Principal(TENANT, UUID.randomUUID(), "SUPER_ADMIN",
                "Axiom Admin", "admin@axiomcrm.com"));
        when(reconciliation.reconcile(TENANT)).thenReturn(new ReconciliationService.ReconciliationReport(
                Instant.now(), 9, 9, 0, BigDecimal.ZERO, List.of(), "zero drift"));
        when(kpis.reconcile(TENANT)).thenReturn(new KpiReconciliationService.KpiReport(
                UUID.randomUUID(), Instant.now(), 6, 6, 0, List.of(), "matched"));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void productionCertificatePassesOnlyWithScaleLatencyAndFreshReconciliationEvidence() {
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
                "projected_rows", 1_250_000L, "executions", 75L,
                "p95_ms", 740, "maximum_ms", 1190, "timeouts", 0L));

        ReportingCertificationService.Certification result = service.certifyProduction();

        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.minimumRows()).isEqualTo(1_000_000L);
        assertThat(result.maximumP95Ms()).isEqualTo(3_000);
        verify(reconciliation).reconcile(TENANT);
        verify(kpis).reconcile(TENANT);
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("performance_certification_run"),
                any(Object[].class));
    }

    @Test void smallDemoDatasetIsNeverMisrepresentedAsProductionCertified() {
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
                "projected_rows", 250L, "executions", 5L,
                "p95_ms", 20, "maximum_ms", 30, "timeouts", 0L));

        ReportingCertificationService.Certification result = service.certifyProduction();

        assertThat(result.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.verdict()).contains("Certification withheld");
    }
}

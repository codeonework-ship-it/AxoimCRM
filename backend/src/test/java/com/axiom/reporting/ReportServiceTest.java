package com.axiom.reporting;

import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test void crmPortfolioHasAnImplementedQueryContractForEverySeededReport() {
        assertEquals(21, ReportService.supportedReportCodes().size());
        assertEquals(java.util.Set.of(
                "tenant_summary", "pipeline_snapshot", "forecast_commitment", "pipeline_aging_risk",
                "win_loss_analysis", "lead_conversion_funnel", "lead_source_conversion",
                "sales_activity_productivity", "account_health_portfolio", "customer_service_sla",
                "quote_conversion_margin", "campaign_roi", "data_quality_exceptions",
                "quota_attainment", "forecast_accuracy_bias", "stage_conversion_velocity",
                "renewal_arr_bridge", "pipeline_movement_waterfall", "account_whitespace",
                "customer_360_brief", "discount_approval_governance"
        ), ReportService.supportedReportCodes());
    }

    @Test void crmInsightJasperTemplateCompiles() throws Exception {
        var resource = new ClassPathResource("reports/crm-insight-report.jrxml");
        assertNotNull(net.sf.jasperreports.engine.JasperCompileManager.compileReport(resource.getInputStream()));
        var summary = new ClassPathResource("reports/tenant-summary.jrxml");
        assertNotNull(net.sf.jasperreports.engine.JasperCompileManager.compileReport(summary.getInputStream()));
    }

    @Test void reportFiltersSearchEveryColumnAndCombineColumnCriteria() {
        List<ReportService.ReportRow> rows = List.of(
                new ReportService.ReportRow("North", "120", "Enterprise renewals", "AT RISK"),
                new ReportService.ReportRow("South", "95", "New business", "CURRENT"),
                new ReportService.ReportRow("West", "120", "Enterprise expansion", "CURRENT")
        );

        assertEquals(2, ReportService.filterRows(rows,
                new ReportService.ReportFilters("enterprise", null, null, null, null)).size());
        assertEquals(List.of("North"), ReportService.filterRows(rows,
                        new ReportService.ReportFilters(null, null, "120", "renewals", "risk"))
                .stream().map(ReportService.ReportRow::getMetric).toList());
    }

    @Test void gridAndDocumentUseTheSameOrderSensitiveDatasetFingerprint() {
        List<ReportService.ReportRow> rows = List.of(
                new ReportService.ReportRow("North", "120", "Enterprise renewals", "AT RISK"),
                new ReportService.ReportRow("South", "95", "New business", "CURRENT"));

        String gridFingerprint = ReportService.datasetFingerprint(rows);
        String documentFingerprint = ReportService.datasetFingerprint(List.copyOf(rows));
        assertEquals(64, gridFingerprint.length());
        assertEquals(gridFingerprint, documentFingerprint);
        assertNotNull(gridFingerprint);
        org.junit.jupiter.api.Assertions.assertNotEquals(gridFingerprint,
                ReportService.datasetFingerprint(List.of(rows.get(1), rows.get(0))));
    }
}

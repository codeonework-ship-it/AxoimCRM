package com.axiom.audit;

import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditControllerTest {
    private ExportAuditService exportAudit;
    private AuditController controller;

    @BeforeEach
    void setUp() {
        exportAudit = mock(ExportAuditService.class);
        controller = new AuditController(
                mock(AuditService.class),
                mock(AuditChainService.class),
                mock(FieldHistoryService.class),
                mock(ReadAuditService.class),
                exportAudit,
                mock(AuthenticationAuditService.class),
                mock(AuditRetentionService.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void governanceReaderCanRecordClientSideExportEvidence() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SUPER_AUDIT", "Axiom Super Auditor", "audit@example.com"));
        Map<String, Object> criteria = Map.of(
                "grid", "RBAC policies",
                "filters", "Role: SUPER_AUDIT",
                "groups", "Module");

        controller.recordExport(new AuditController.ClientExportAuditRequest(
                "RBAC_TABLE", criteria, 7L, null, "PDF"));

        verify(exportAudit).recordExport("RBAC_TABLE", criteria, 7L, "CURRENT_VIEW_DOWNLOAD", "PDF");
    }

    @Test
    void businessUserCanRecordClientSideExportEvidenceWithoutGovernanceRead() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SALES", "Sales User", "sales@example.com"));
        Map<String, Object> criteria = Map.of("grid", "Accounts", "filters", "Owner: me");

        controller.recordExport(new AuditController.ClientExportAuditRequest(
                "ACCOUNT", criteria, 1L, "CURRENT_VIEW_DOWNLOAD", "XLSX"));

        verify(exportAudit).recordExport("ACCOUNT", criteria, 1L, "CURRENT_VIEW_DOWNLOAD", "XLSX");
    }
}

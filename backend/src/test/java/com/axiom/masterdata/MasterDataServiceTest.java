package com.axiom.masterdata;

import com.axiom.audit.AuditService;
import com.axiom.common.BulkValidationException;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MasterDataServiceTest {
    private JdbcTemplate jdbc;
    private AuditService audit;
    private MasterDataService service;

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        service = new MasterDataService(jdbc, audit);
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "TENANT_ADMIN", "Admin", "admin@example.com"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void templateUsesGovernedColumns() {
        String csv = new String(service.template("contacts").bytes(), StandardCharsets.UTF_8);
        assertEquals("first_name,last_name,email,title,account_name\r\n", csv);
    }

    @Test void invalidImportIsRejectedAtomically() {
        byte[] csv = "name,industry\n,Technology\n".getBytes(StandardCharsets.UTF_8);
        BulkValidationException error = assertThrows(BulkValidationException.class,
                () -> service.importCsv("accounts", csv));
        assertTrue(error.details().getFirst().contains("name is required"));
    }

    @Test void readOnlyAuditorCannotDelete() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SUPER_AUDIT", "Auditor", "audit@example.com"));
        assertThrows(ForbiddenException.class, () -> service.softDelete("accounts", UUID.randomUUID()));
    }

    @Test void validAccountImportWritesAndAudits() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        MasterDataService.ImportResult result = service.importCsv("accounts",
                "name,industry\nNova Labs,Technology\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.imported());
        verify(jdbc).update(anyString(), any(), any(), any(), any());
        verify(audit).record(eq("BULK_IMPORT"), eq("ACCOUNT"), eq(null), anyString(), any());
    }
}

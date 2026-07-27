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
        service = new MasterDataService(jdbc, audit, mock(com.axiom.identity.StepUpService.class));
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

    @Test void importRejectsMoreThanFiveThousandRowsBeforeWriting() {
        StringBuilder csv = new StringBuilder("name,industry\n");
        for (int row = 1; row <= 5_001; row++) csv.append("Account ").append(row).append(",Technology\n");

        BulkValidationException error = assertThrows(BulkValidationException.class,
                () -> service.importCsv("accounts", csv.toString().getBytes(StandardCharsets.UTF_8)));

        assertTrue(error.getMessage().contains("5,000 rows"));
    }

    @Test void importRejectsFilesLargerThanFiveMebibytesBeforeParsing() {
        byte[] oversized = new byte[(5 * 1024 * 1024) + 1];
        BulkValidationException error = assertThrows(BulkValidationException.class,
                () -> service.importCsv("accounts", oversized));
        assertTrue(error.getMessage().contains("5 MB"));
    }

    @Test void quotedCommaIsParsedAsOneField() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        MasterDataService.ImportResult result = service.importCsv("accounts",
                "name,industry\n\"North, West\",Technology\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.imported());
        verify(jdbc).update(anyString(), any(), eq("North, West"), eq("Technology"), any());
    }

    @Test void unterminatedQuotedValueIsRejected() {
        BulkValidationException error = assertThrows(BulkValidationException.class,
                () -> service.importCsv("accounts",
                        "name,industry\n\"Broken,Technology\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(error.getMessage().contains("unterminated quoted value"));
    }
}

package com.axiom.reference;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ReferenceDataServiceTest {
    private ReferenceDataService service;

    @BeforeEach void setUp() {
        service = new ReferenceDataService(mock(JdbcTemplate.class), mock(AuditService.class));
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "AUDITOR", "Audit User", "audit@example.com"));
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    @Test void tenantAuditorCannotCreateReferenceEntries() {
        EntryMutation request = new EntryMutation("TEST", "Test", 10, true, null, null);
        assertThrows(ForbiddenException.class, () -> service.createEntry("lead_status", request));
    }

    @Test void tenantAuditorCannotUpdateReferenceEntries() {
        EntryMutation request = new EntryMutation("TEST", "Test", 10, false, null, null);
        assertThrows(ForbiddenException.class, () -> service.updateEntry("lead_status", "TEST", request));
    }
}

package com.axiom.activities;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class EmailTemplateServiceTest {
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void auditorCannotCreateOrReviseTemplateVersions() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SUPER_AUDIT", "Auditor", "audit@example.test"));
        EmailTemplateService service = new EmailTemplateService(mock(JdbcTemplate.class), new ObjectMapper(),
                mock(AuditService.class), mock(OutboxWriter.class));
        assertThrows(ForbiddenException.class, () -> service.create(new EmailTemplateService.CreateRequest(
                "renewal_follow_up", "Renewal follow-up", "Sales", null, "TENANT",
                "Renewal", "Hello", List.of("first_name"), "Initial")));
        assertThrows(ForbiddenException.class, () -> service.revise(UUID.randomUUID(),
                new EmailTemplateService.ReviseRequest("Renewal", "Hello again", List.of(), "Reviewed")));
    }
}

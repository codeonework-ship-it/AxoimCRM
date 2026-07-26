package com.axiom.cpq;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class QuoteRevisionServiceTest {
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void readOnlyRoleCannotCreateACommercialRevision() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "AUDITOR", "Auditor", "audit@example.test"));
        QuoteRevisionService service = new QuoteRevisionService(mock(JdbcTemplate.class),
                mock(AuditService.class), mock(OutboxWriter.class));
        assertThrows(ForbiddenException.class, () -> service.revise(UUID.randomUUID(),
                new QuoteRevisionService.RevisionRequest("Changed terms")));
    }
}

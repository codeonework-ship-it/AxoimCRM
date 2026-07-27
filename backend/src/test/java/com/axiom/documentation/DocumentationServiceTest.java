package com.axiom.documentation;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DocumentationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final DocumentationService service = new DocumentationService(jdbc, new ObjectMapper(), audit, outbox);

    @AfterEach void clearContext() { TenantContext.clear(); }

    @Test void tenantAuditorCannotChangeDocumentationMaster() {
        bind("AUDITOR");
        assertThrows(ForbiddenException.class, () -> service.updateDrawer(new DocumentationService.DrawerUpdate(
                Map.of("en", new DocumentationService.DrawerText("Field manual", "User Manual")), true, "Attempted edit")));
        verifyNoInteractions(jdbc, audit, outbox);
    }

    @Test void platformAuditorCannotCreateDocumentationSection() {
        bind("SUPER_AUDIT");
        assertThrows(ForbiddenException.class, () -> service.createSection(new DocumentationService.SectionRequest(
                "NEW_SECTION", "STEPS", 100, true, Map.of("en", "New section"), "Attempted create")));
        verifyNoInteractions(jdbc, audit, outbox);
    }

    private static void bind(String role) {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(), role,
                "Audit User", "audit@axiomcrm.com"));
    }
}

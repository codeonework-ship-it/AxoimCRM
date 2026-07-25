package com.axiom.activities;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.notifications.NotificationWriter;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ActivityServiceTest {
    private ActivityService service;

    @BeforeEach void setUp() {
        service = new ActivityService(mock(JdbcTemplate.class), mock(AuditService.class),
                mock(OutboxWriter.class), mock(NotificationWriter.class));
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SALES", "Sales", "sales@example.com"));
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    @Test void taskRequiresDueDate() {
        assertThrows(ConflictException.class, () -> service.create(new ActivityService.ActivityRequest(
                "TASK", "Follow up", null, "NORMAL", "ACCOUNT", UUID.randomUUID(),
                null, null, null, null, null, null, null)));
    }

    @Test void callRequiresDirectionDurationAndDisposition() {
        assertThrows(ConflictException.class, () -> service.create(new ActivityService.ActivityRequest(
                "CALL", "Discovery call", null, "NORMAL", "ACCOUNT", UUID.randomUUID(),
                null, OffsetDateTime.now(), null, null, null, null, null)));
    }

    @Test void auditorCannotCompleteActivities() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "AUDITOR", "Audit", "audit@example.com"));

        assertThrows(ForbiddenException.class, () -> service.complete(UUID.randomUUID(),
                new ActivityService.CompleteRequest("Done")));
    }
}

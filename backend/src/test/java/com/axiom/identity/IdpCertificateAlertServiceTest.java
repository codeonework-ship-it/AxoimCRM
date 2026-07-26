package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.notifications.NotificationWriter;
import com.axiom.security.SystemTaskRunner;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class IdpCertificateAlertServiceTest {
    private IdpCertificateAlertService service;

    @BeforeEach void setUp() {
        service = new IdpCertificateAlertService(mock(JdbcTemplate.class), mock(SystemTaskRunner.class),
                mock(NotificationWriter.class), mock(AuditService.class));
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SUPER_AUDIT", "Read-only auditor", "audit@example.test"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void auditorCannotTriggerAControlThatWritesNotifications() {
        assertThrows(ForbiddenException.class, service::sweepNow);
    }

    @Test void invalidCertificateNeverProducesAFabricatedExpiryDate() {
        assertNull(IdpCertificateAlertService.notAfter("not a certificate"));
    }
}

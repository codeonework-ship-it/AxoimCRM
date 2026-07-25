package com.axiom.alerts;

import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AlertServiceTest {
    private AlertService service;

    @BeforeEach void setUp() {
        service = new AlertService(mock(JdbcTemplate.class), mock(OutboxWriter.class));
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "TENANT_ADMIN", "Admin", "admin@example.com"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void emailAlertRejectsMalformedRecipient() {
        AlertService.EmailAlertRequest request = new AlertService.EmailAlertRequest(
                "Bad Alert", "Subject", "<p>Body</p>", List.of("not-an-email"), List.of(), List.of(), true);

        assertThrows(IllegalArgumentException.class, () -> service.createEmailAlert(request));
    }
}

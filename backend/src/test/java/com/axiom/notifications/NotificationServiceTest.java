package com.axiom.notifications;

import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new NotificationService(jdbc);
        TenantContext.set(new TenantContext.Principal(tenantId, userId, "SALES", "Test User"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void markReadScopesUpdateToTenantAndRecipient() {
        UUID notificationId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.markRead(notificationId);

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), arguments.capture());
        assertEquals(notificationId, arguments.getValue()[0]);
        assertEquals(tenantId, arguments.getValue()[1]);
        assertEquals(userId, arguments.getValue()[2]);
    }

    @Test
    void markUnreadDoesNotRevealAnotherUsersNotification() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThrows(NotFoundException.class, () -> service.markUnread(UUID.randomUUID()));
    }

    @Test
    void markAllReadReturnsAffectedRowCount() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(3);

        assertEquals(3, service.markAllRead());
    }
}

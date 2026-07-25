package com.axiom.notifications;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationControllerTest {

    @Test
    void unreadViewDelegatesToScopedUnreadQuery() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = new NotificationController(service);
        NotificationService.NotificationRow row = new NotificationService.NotificationRow(
                UUID.randomUUID(), "ACTION", "URGENT", "Approval required", "Review quote",
                "/pipeline", "You are the assigned approver.", true, false, false, Instant.now());
        when(service.list(true)).thenReturn(List.of(row));

        List<NotificationService.NotificationRow> response = controller.list(NotificationController.View.unread);

        assertEquals(List.of(row), response);
        verify(service).list(true);
    }

    @Test
    void unreadCountUsesDedicatedCountContract() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = new NotificationController(service);
        when(service.unreadCount()).thenReturn(7L);

        assertEquals(7L, controller.unreadCount().count());
    }
}

package com.axiom.notifications;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    public enum View { all, unread }

    @GetMapping
    public List<NotificationService.NotificationRow> list(
            @RequestParam(defaultValue = "all") View view) {
        return notifications.list(view == View.unread);
    }

    public record UnreadCount(long count) {}

    @GetMapping("/unread-count")
    public UnreadCount unreadCount() {
        return new UnreadCount(notifications.unreadCount());
    }

    @PatchMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable UUID id) {
        notifications.markRead(id);
    }

    @PatchMapping("/{id}/unread")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markUnread(@PathVariable UUID id) {
        notifications.markUnread(id);
    }

    public record UpdatedCount(int updated) {}

    @PostMapping("/read-all")
    public UpdatedCount markAllRead() {
        return new UpdatedCount(notifications.markAllRead());
    }
}

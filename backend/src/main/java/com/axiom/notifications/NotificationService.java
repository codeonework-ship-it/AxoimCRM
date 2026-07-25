package com.axiom.notifications;

import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class NotificationService {

    private static final int FEED_LIMIT = 50;
    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record NotificationRow(
            UUID id,
            String kind,
            String priority,
            String title,
            String body,
            String href,
            String reason,
            boolean actionRequired,
            boolean actionCompleted,
            boolean read,
            Instant occurredAt) {}

    @Transactional(readOnly = true)
    public List<NotificationRow> list(boolean unreadOnly) {
        TenantContext.Principal principal = TenantContext.get();
        String unreadClause = unreadOnly ? " and read_at is null" : "";
        return jdbc.query("""
                        select id, kind, priority, title, body, href, reason,
                               action_required, action_completed, read_at, occurred_at
                        from notification
                        where tenant_id = ? and recipient_user_id = ?
                        """ + unreadClause + " order by occurred_at desc limit ?",
                (rs, rowNum) -> new NotificationRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("priority"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("href"),
                        rs.getString("reason"),
                        rs.getBoolean("action_required"),
                        rs.getBoolean("action_completed"),
                        rs.getTimestamp("read_at") != null,
                        rs.getTimestamp("occurred_at").toInstant()),
                principal.tenantId(), principal.userId(), FEED_LIMIT);
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        TenantContext.Principal principal = TenantContext.get();
        Long count = jdbc.queryForObject("""
                        select count(*) from notification
                        where tenant_id = ? and recipient_user_id = ? and read_at is null
                        """, Long.class, principal.tenantId(), principal.userId());
        return count == null ? 0 : count;
    }

    public void markRead(UUID id) {
        TenantContext.Principal principal = TenantContext.get();
        int updated = jdbc.update("""
                        update notification set read_at = coalesce(read_at, now())
                        where id = ? and tenant_id = ? and recipient_user_id = ?
                        """, id, principal.tenantId(), principal.userId());
        requireOwnedNotification(updated, id);
    }

    public void markUnread(UUID id) {
        TenantContext.Principal principal = TenantContext.get();
        int updated = jdbc.update("""
                        update notification set read_at = null
                        where id = ? and tenant_id = ? and recipient_user_id = ?
                        """, id, principal.tenantId(), principal.userId());
        requireOwnedNotification(updated, id);
    }

    public int markAllRead() {
        TenantContext.Principal principal = TenantContext.get();
        return jdbc.update("""
                        update notification set read_at = now()
                        where tenant_id = ? and recipient_user_id = ? and read_at is null
                        """, principal.tenantId(), principal.userId());
    }

    private static void requireOwnedNotification(int updated, UUID id) {
        if (updated == 0) {
            throw new NotFoundException("Notification not found: " + id);
        }
    }
}

package com.axiom.notifications;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Writes an in-app notification inside the caller's business transaction. */
@Component
public class NotificationWriter {

    private final JdbcTemplate jdbc;

    public NotificationWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void notifyCurrentUser(String kind, String priority, String title, String body,
                                  String href, String reason, boolean actionRequired) {
        TenantContext.Principal principal = TenantContext.get();
        notifyUser(principal.tenantId(), principal.userId(), kind, priority, title, body,
                href, reason, actionRequired);
    }

    /**
     * Writes a notification for a named recipient rather than the caller.
     *
     * <p>Needed by controls that must tell someone <em>other</em> than the actor
     * what happened — break-glass access notifies the tenant's administrators
     * (FR-TEN-012), and the actor there is deliberately not one of them.
     */
    public void notifyUser(java.util.UUID tenantId, java.util.UUID recipientUserId, String kind,
                          String priority, String title, String body, String href, String reason,
                          boolean actionRequired) {
        jdbc.update("""
                        insert into notification
                          (tenant_id, recipient_user_id, kind, priority, title, body, href, reason, action_required)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                tenantId, recipientUserId, kind, priority, title, body,
                href, reason, actionRequired);
    }
}

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
        jdbc.update("""
                        insert into notification
                          (tenant_id, recipient_user_id, kind, priority, title, body, href, reason, action_required)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                principal.tenantId(), principal.userId(), kind, priority, title, body,
                href, reason, actionRequired);
    }
}

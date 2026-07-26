package com.axiom.notifications;

import com.axiom.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Writes in-app notifications.
 *
 * <h2>A notification never fails the thing it is reporting</h2>
 * This used to insert inside the caller's business transaction, and a failure
 * therefore rolled the caller back. That turned a cosmetic problem into an outage:
 * {@code notification.recipient_user_id} references {@code identity.app_user}, but
 * platform operators live in {@code platform.platform_user} — so a SUPER_ADMIN
 * advancing an opportunity hit a foreign-key violation and <em>the stage change was
 * undone</em>. The user saw a 500 and the deal did not move.
 *
 * <p>The rule now: telling someone what happened is a side effect of the thing
 * happening, and a side effect must not be able to veto it. Each notification is
 * written in its own transaction and any failure is logged and swallowed. Losing a
 * notification is a nuisance; losing the stage change is data loss.
 *
 * <h2>Recipients that cannot exist are skipped, not attempted</h2>
 * A platform user has no row in {@code identity.app_user} by design — they are not
 * a member of the tenant they are acting in. Checking first means the normal path
 * for an operator is a clean skip with a log line, rather than an exception caught
 * on every single action.
 */
@Component
public class NotificationWriter {

    private static final Logger log = LoggerFactory.getLogger(NotificationWriter.class);

    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate isolated;

    public NotificationWriter(JdbcTemplate jdbc,
                              org.springframework.transaction.PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.isolated = new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.isolated.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
    public void notifyUser(UUID tenantId, UUID recipientUserId, String kind,
                           String priority, String title, String body, String href, String reason,
                           boolean actionRequired) {
        if (tenantId == null || recipientUserId == null) return;
        try {
            isolated.execute((status) -> {
                /*
                 * Re-bind app.tenant_id. TenantSessionAspect binds it with SET LOCAL,
                 * which is scoped to the transaction it was bound in — a new
                 * transaction starts without it and RLS refuses the insert. Same
                 * lesson as the bulk-operation row log.
                 */
                jdbc.query("select set_config('app.tenant_id', ?, true)", (rs) -> null, tenantId.toString());

                if (!isTenantUser(tenantId, recipientUserId)) {
                    // Normal for a platform operator acting inside a tenant. Not an
                    // error, and not worth an exception on every action they take.
                    log.debug("Skipping notification \"{}\": {} is not a member of tenant {} "
                            + "(platform users have no identity.app_user row)", title, recipientUserId, tenantId);
                    return 0;
                }
                return jdbc.update("""
                        insert into notification
                          (tenant_id, recipient_user_id, kind, priority, title, body, href, reason, action_required)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, tenantId, recipientUserId, kind, priority, title, body,
                        href, reason, actionRequired);
            });
        } catch (RuntimeException ex) {
            // Deliberately swallowed. The caller's work has already happened and is
            // correct; failing it now because we could not write a bell icon would
            // be the worse outcome by a wide margin. Logged at warn so it is still
            // visible rather than silent.
            log.warn("Notification \"{}\" for user {} in tenant {} could not be written: {}",
                    title, recipientUserId, tenantId, ex.getMessage());
        }
    }

    private boolean isTenantUser(UUID tenantId, UUID userId) {
        List<Integer> found = jdbc.queryForList("""
                select 1 from identity.app_user where tenant_id = ? and id = ?
                """, Integer.class, tenantId, userId);
        return !found.isEmpty();
    }
}

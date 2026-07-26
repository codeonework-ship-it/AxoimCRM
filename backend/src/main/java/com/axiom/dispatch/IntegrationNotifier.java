package com.axiom.dispatch;

import com.axiom.audit.AuditService;
import com.axiom.notifications.NotificationWriter;
import com.axiom.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Makes an integration failure reach a person (FR-INT-009: "an integration
 * failing silently is a defect").
 *
 * <p>Two events qualify, and both are conditions rather than individual
 * failures — a notification per failed delivery would be noise that gets
 * filtered, which is the same outcome as no notification:
 * <ul>
 *   <li>a breaker <b>opening</b>, i.e. the connector has stopped working, and</li>
 *   <li>the dead-letter queue for a connector <b>crossing a threshold</b>,
 *       i.e. deliveries are being lost and nobody has looked.</li>
 * </ul>
 *
 * <p>Recipients are the tenant's administrators, not the actor: the actor is a
 * scheduled task, and telling a scheduled task about a broken integration is the
 * silent failure the requirement prohibits. Re-alerting is rate-limited by
 * {@code connector_health.dlq_alert_at} so a persistent condition does not
 * re-notify on every tick.
 *
 * <p>This class only <em>calls</em> {@link NotificationWriter} and
 * {@link AuditService}; neither is modified.
 */
@Component
public class IntegrationNotifier {

    private static final Logger log = LoggerFactory.getLogger(IntegrationNotifier.class);
    private static final int MAX_RECIPIENTS = 25;

    private final JdbcTemplate jdbc;
    private final NotificationWriter notifications;
    private final AuditService audit;

    public IntegrationNotifier(JdbcTemplate jdbc, NotificationWriter notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.audit = audit;
    }

    public void breakerOpened(UUID connectorId, String connectorName, int consecutiveFailures, String lastError) {
        String title = "Integration paused: " + connectorName;
        String body = "Axiom stopped sending to " + connectorName + " after " + consecutiveFailures
                + " consecutive failures. Last error: " + shorten(lastError)
                + ". Deliveries are being held and will be retried automatically once the endpoint responds.";
        notifyAdministrators("ACTION", "URGENT", title, body,
                "Circuit breaker opened for connector " + connectorName, true);
        audit.record("INTEGRATION_BREAKER_OPENED", "CONNECTOR", connectorId,
                "Circuit breaker opened for " + connectorName,
                Map.of("connectorId", connectorId.toString(), "consecutiveFailures", consecutiveFailures,
                        "lastError", shorten(lastError)));
    }

    public void deadLetterThresholdCrossed(UUID connectorId, String connectorName, long depth, int threshold) {
        String title = "Undelivered integration messages: " + connectorName;
        String body = depth + " message(s) for " + connectorName
                + " could not be delivered and are waiting in the dead-letter list."
                + " Review them and retry once the receiving system is fixed.";
        notifyAdministrators("ACTION", "URGENT", title, body,
                "Dead-letter depth " + depth + " crossed the alert threshold of " + threshold, true);
        audit.record("INTEGRATION_DEAD_LETTER_ALERT", "CONNECTOR", connectorId,
                "Dead-letter queue for " + connectorName + " reached " + depth,
                Map.of("connectorId", connectorId.toString(), "depth", depth, "threshold", threshold));
    }

    private void notifyAdministrators(String kind, String priority, String title, String body,
                                      String reason, boolean actionRequired) {
        UUID tenantId = TenantContext.get().tenantId();
        List<UUID> admins = jdbc.query("""
                select id from identity.app_user
                where tenant_id = ? and active = true and role in ('TENANT_ADMIN','OPERATIONS')
                order by role, created_at
                limit ?
                """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, MAX_RECIPIENTS);
        if (admins.isEmpty()) {
            // Nobody to tell is itself worth a log line — a tenant with no
            // administrator is a condition, not a reason to stay quiet.
            log.warn("Integration alert for tenant {} has no administrator recipient: {}", tenantId, title);
            return;
        }
        for (UUID recipient : admins) {
            notifications.notifyUser(tenantId, recipient, kind, priority, title, body,
                    "/integrations/dispatch", reason, actionRequired);
        }
    }

    private static String shorten(String value) {
        if (value == null || value.isBlank()) return "not reported";
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}

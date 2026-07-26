package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.notifications.NotificationWriter;
import com.axiom.security.RbacAccess;
import com.axiom.security.SystemTaskRunner;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Proactive SAML signing-certificate warnings for US-E01-03.
 *
 * <p>The identity-provider screen already displayed an expiry date when an
 * administrator happened to open it.  That is not a warning system: the common
 * failure mode is that nobody opens the screen until sign-in is broken.  This
 * service evaluates every enabled SAML provider daily and writes an in-app
 * notification to every active tenant administrator.  The database uniqueness
 * key makes repeated scheduler runs harmless for the same certificate.</p>
 */
@Service
public class IdpCertificateAlertService {

    static final Duration WARNING_WINDOW = Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final SystemTaskRunner tasks;
    private final NotificationWriter notifications;
    private final AuditService audit;

    public IdpCertificateAlertService(JdbcTemplate jdbc, SystemTaskRunner tasks,
                                      NotificationWriter notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.notifications = notifications;
        this.audit = audit;
    }

    public record AlertRow(UUID id, UUID providerId, String providerName, Instant certificateNotAfter,
                           String severity, Instant notifiedAt, int recipientCount) {}

    private record Candidate(UUID id, String name, String certificate) {}

    @Scheduled(cron = "${axiom.identity.certificate-alert-cron:0 20 6 * * *}")
    public void scheduledSweep() {
        tasks.forEachTenant("identity-provider certificate warning", ignored -> sweepCurrentTenant());
    }

    /** Allows an administrator to run the same idempotent control on demand. */
    @Transactional
    public int sweepNow() {
        RbacAccess.requireWrite("run the certificate-expiry control");
        return sweepCurrentTenant();
    }

    @Transactional(readOnly = true)
    public List<AlertRow> history() {
        RbacAccess.requireRead();
        return jdbc.query("""
                select a.id, a.idp_config_id, c.display_name, a.certificate_not_after,
                       a.severity, a.notified_at, a.recipient_count
                from identity.idp_certificate_alert a
                join identity.idp_config c on c.tenant_id = a.tenant_id and c.id = a.idp_config_id
                where a.tenant_id = ?
                order by a.notified_at desc
                limit 100
                """, (rs, i) -> new AlertRow(rs.getObject("id", UUID.class),
                rs.getObject("idp_config_id", UUID.class), rs.getString("display_name"),
                rs.getTimestamp("certificate_not_after").toInstant(), rs.getString("severity"),
                rs.getTimestamp("notified_at").toInstant(), rs.getInt("recipient_count")),
                TenantContext.get().tenantId());
    }

    @Transactional
    int sweepCurrentTenant() {
        UUID tenantId = TenantContext.get().tenantId();
        List<Candidate> candidates = jdbc.query("""
                select id, display_name, certificate
                from identity.idp_config
                where tenant_id = ? and enabled = true and protocol = 'SAML2'
                  and certificate is not null
                """, (rs, i) -> new Candidate(rs.getObject("id", UUID.class),
                rs.getString("display_name"), rs.getString("certificate")), tenantId);

        int created = 0;
        for (Candidate candidate : candidates) {
            Instant expiresAt = notAfter(candidate.certificate());
            if (expiresAt == null || expiresAt.isAfter(Instant.now().plus(WARNING_WINDOW))) continue;
            String severity = expiresAt.isAfter(Instant.now()) ? "WARNING" : "EXPIRED";
            int inserted = jdbc.update("""
                    insert into identity.idp_certificate_alert
                      (tenant_id, idp_config_id, certificate_not_after, severity)
                    values (?, ?, ?, ?)
                    on conflict (tenant_id, idp_config_id, certificate_not_after, severity) do nothing
                    """, tenantId, candidate.id(), Timestamp.from(expiresAt), severity);
            if (inserted == 0) continue;

            List<UUID> recipients = jdbc.queryForList("""
                    select id from identity.app_user
                    where tenant_id = ? and active = true and role in ('TENANT_ADMIN','SUPER_ADMIN')
                    order by email
                    """, UUID.class, tenantId);
            String title = severity.equals("EXPIRED")
                    ? "Single sign-on certificate has expired"
                    : "Single sign-on certificate expires within 30 days";
            String body = candidate.name() + " uses a signing certificate that expires on " + expiresAt
                    + ". Replace the certificate and run Test connection before enabling it for users.";
            for (UUID recipient : recipients) {
                notifications.notifyUser(tenantId, recipient, "SYSTEM", "URGENT", title, body,
                        "/access", "A SAML signing certificate is near or past its expiry date.", true);
            }
            jdbc.update("""
                    update identity.idp_certificate_alert set recipient_count = ?
                    where tenant_id = ? and idp_config_id = ? and certificate_not_after = ? and severity = ?
                    """, recipients.size(), tenantId, candidate.id(), Timestamp.from(expiresAt), severity);
            audit.record("IDP_CERTIFICATE_ALERT", "IDP_CONFIG", candidate.id(), title,
                    Map.of("provider", candidate.name(), "certificateNotAfter", expiresAt.toString(),
                            "severity", severity, "recipientCount", recipients.size()));
            created++;
        }
        return created;
    }

    static Instant notAfter(String certificate) {
        try {
            String body = certificate.replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            return parsed.getNotAfter().toInstant();
        } catch (Exception ignored) {
            // Invalid certificates are already surfaced by Test connection.  The
            // expiry control does not create a misleading date for unparsable data.
            return null;
        }
    }
}

package com.axiom.dispatch;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.integration.AdapterRegistry;
import com.axiom.integration.ConnectorTarget;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Connector registry and outbound event subscriptions (FR-INT-008).
 *
 * <p>The read shape carries no secret anywhere: {@code credentialRef} is a
 * name, {@code config} is passed through {@link ConfigSanitiser}, and there is
 * no field of any kind that a secret could occupy.
 */
@Service
public class ConnectorService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final NamedCredentialService credentials;
    private final AdapterRegistry adapters;
    private final AuditService audit;

    public ConnectorService(JdbcTemplate jdbc, ObjectMapper json, NamedCredentialService credentials,
                            AdapterRegistry adapters, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.credentials = credentials;
        this.adapters = adapters;
        this.audit = audit;
    }

    public record ConnectorRow(UUID id, String connectorType, String vendor, String displayName,
                               boolean enabled, Map<String, Object> config, String credentialRef,
                               String credentialStatus, int subscriptionCount,
                               Instant createdAt, Instant updatedAt) {}

    public record SubscriptionRow(UUID id, UUID connectorId, String eventTypePattern,
                                  String filterExpression, boolean active,
                                  Instant createdAt, Instant updatedAt) {}

    /* ------------------------------------------------------------------ */
    /* Connectors                                                          */
    /* ------------------------------------------------------------------ */

    @Transactional(readOnly = true)
    public List<ConnectorRow> list() {
        return jdbc.query("""
                select k.id, k.connector_type, k.vendor, k.display_name, k.enabled, k.config::text as config,
                       k.credential_ref, k.created_at, k.updated_at,
                       (select count(*) from dispatch.event_subscription s
                         where s.tenant_id = k.tenant_id and s.connector_id = k.id) as subscription_count,
                       exists (select 1 from dispatch.named_credential c
                                where c.tenant_id = k.tenant_id and c.name = k.credential_ref) as credential_present
                from dispatch.connector k
                where k.tenant_id = ?
                order by k.display_name
                """, this::mapConnector, TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public ConnectorRow get(UUID connectorId) {
        return list().stream().filter(row -> row.id().equals(connectorId)).findFirst()
                .orElseThrow(() -> new NotFoundException("No connector " + connectorId));
    }

    @Transactional
    public ConnectorRow create(ConnectorRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        Map<String, Object> config = ConfigSanitiser.forStorage(request.config());
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into dispatch.connector
                      (tenant_id, connector_type, vendor, display_name, enabled, config, credential_ref, created_by)
                    values (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    returning id
                    """, UUID.class, TenantContext.get().tenantId(),
                    request.connectorType().toUpperCase(Locale.ROOT), request.vendor().trim(),
                    request.displayName().trim(), request.enabled() == null || request.enabled(),
                    write(config), blankToNull(request.credentialRef()), TenantContext.get().userId());
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("A connector named '" + request.displayName() + "' already exists");
        }
        // Health row exists from creation so a connector that has never fired
        // reports "no activity yet" rather than disappearing from the health list.
        jdbc.update("""
                insert into dispatch.connector_health (connector_id, tenant_id)
                values (?, ?) on conflict (connector_id) do nothing
                """, id, TenantContext.get().tenantId());
        audit.record("INTEGRATION_CONNECTOR_CREATED", "CONNECTOR", id,
                "Registered connector " + request.displayName(),
                Map.of("connectorType", request.connectorType(), "vendor", request.vendor(),
                        "credentialRef", String.valueOf(blankToNull(request.credentialRef()))));
        return get(id);
    }

    @Transactional
    public ConnectorRow update(UUID connectorId, ConnectorRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        ConnectorRow before = get(connectorId);
        Map<String, Object> config = ConfigSanitiser.forStorage(request.config());
        jdbc.update("""
                update dispatch.connector
                   set connector_type = ?, vendor = ?, display_name = ?, enabled = ?,
                       config = ?::jsonb, credential_ref = ?, updated_at = now()
                 where tenant_id = ? and id = ?
                """, request.connectorType().toUpperCase(Locale.ROOT), request.vendor().trim(),
                request.displayName().trim(), request.enabled() == null || request.enabled(),
                write(config), blankToNull(request.credentialRef()),
                TenantContext.get().tenantId(), connectorId);
        ConnectorRow after = get(connectorId);
        audit.record("INTEGRATION_CONNECTOR_UPDATED", "CONNECTOR", connectorId,
                "Updated connector " + after.displayName(),
                Map.of("before", Map.of("enabled", before.enabled(), "vendor", before.vendor(),
                                "credentialRef", String.valueOf(before.credentialRef())),
                       "after", Map.of("enabled", after.enabled(), "vendor", after.vendor(),
                                "credentialRef", String.valueOf(after.credentialRef()))));
        return after;
    }

    /**
     * Pausing a connector is a first-class action, not a delete: an
     * administrator whose only lever is deletion loses the delivery history and
     * the dead letters along with the configuration.
     */
    @Transactional
    public ConnectorRow setEnabled(UUID connectorId, boolean enabled) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        int updated = jdbc.update("""
                update dispatch.connector set enabled = ?, updated_at = now()
                 where tenant_id = ? and id = ?
                """, enabled, TenantContext.get().tenantId(), connectorId);
        if (updated == 0) throw new NotFoundException("No connector " + connectorId);
        audit.record(enabled ? "INTEGRATION_CONNECTOR_ENABLED" : "INTEGRATION_CONNECTOR_DISABLED",
                "CONNECTOR", connectorId, (enabled ? "Enabled" : "Paused") + " connector",
                Map.of("enabled", enabled));
        return get(connectorId);
    }

    @Transactional
    public void delete(UUID connectorId) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        ConnectorRow row = get(connectorId);
        jdbc.update("delete from dispatch.connector where tenant_id = ? and id = ?",
                TenantContext.get().tenantId(), connectorId);
        audit.record("INTEGRATION_CONNECTOR_DELETED", "CONNECTOR", connectorId,
                "Deleted connector " + row.displayName(), Map.of("displayName", row.displayName()));
    }

    /* ------------------------------------------------------------------ */
    /* Subscriptions                                                       */
    /* ------------------------------------------------------------------ */

    @Transactional(readOnly = true)
    public List<SubscriptionRow> subscriptions(UUID connectorId) {
        return jdbc.query("""
                select id, connector_id, event_type_pattern, filter_expression, active, created_at, updated_at
                from dispatch.event_subscription
                where tenant_id = ? and (?::uuid is null or connector_id = ?::uuid)
                order by event_type_pattern
                """, (rs, i) -> new SubscriptionRow(
                        rs.getObject("id", UUID.class), rs.getObject("connector_id", UUID.class),
                        rs.getString("event_type_pattern"), rs.getString("filter_expression"),
                        rs.getBoolean("active"), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("updated_at"))),
                TenantContext.get().tenantId(), connectorId, connectorId);
    }

    @Transactional
    public SubscriptionRow addSubscription(UUID connectorId, SubscriptionRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        get(connectorId);
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into dispatch.event_subscription
                      (tenant_id, connector_id, event_type_pattern, filter_expression, active)
                    values (?, ?, ?, ?, ?)
                    returning id
                    """, UUID.class, TenantContext.get().tenantId(), connectorId,
                    request.eventTypePattern().trim(), blankToNull(request.filterExpression()),
                    request.active() == null || request.active());
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("This connector already subscribes to '"
                    + request.eventTypePattern() + "'");
        }
        audit.record("INTEGRATION_SUBSCRIPTION_CREATED", "EVENT_SUBSCRIPTION", id,
                "Subscribed connector to " + request.eventTypePattern(),
                Map.of("connectorId", connectorId.toString(), "eventTypePattern", request.eventTypePattern()));
        return subscriptions(connectorId).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public SubscriptionRow updateSubscription(UUID connectorId, UUID subscriptionId, SubscriptionRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        int updated = jdbc.update("""
                update dispatch.event_subscription
                   set event_type_pattern = ?, filter_expression = ?, active = ?, updated_at = now()
                 where tenant_id = ? and id = ? and connector_id = ?
                """, request.eventTypePattern().trim(), blankToNull(request.filterExpression()),
                request.active() == null || request.active(),
                TenantContext.get().tenantId(), subscriptionId, connectorId);
        if (updated == 0) throw new NotFoundException("No subscription " + subscriptionId);
        audit.record("INTEGRATION_SUBSCRIPTION_UPDATED", "EVENT_SUBSCRIPTION", subscriptionId,
                "Updated subscription to " + request.eventTypePattern(),
                Map.of("connectorId", connectorId.toString(), "active",
                        request.active() == null || request.active()));
        return subscriptions(connectorId).stream().filter(s -> s.id().equals(subscriptionId)).findFirst()
                .orElseThrow();
    }

    @Transactional
    public void deleteSubscription(UUID connectorId, UUID subscriptionId) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        int deleted = jdbc.update("""
                delete from dispatch.event_subscription
                 where tenant_id = ? and id = ? and connector_id = ?
                """, TenantContext.get().tenantId(), subscriptionId, connectorId);
        if (deleted == 0) throw new NotFoundException("No subscription " + subscriptionId);
        audit.record("INTEGRATION_SUBSCRIPTION_DELETED", "EVENT_SUBSCRIPTION", subscriptionId,
                "Removed subscription", Map.of("connectorId", connectorId.toString()));
    }

    /* ------------------------------------------------------------------ */
    /* Dispatch-time resolution                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Resolve a connector into a dispatchable target, decrypting its named
     * credential. Package-visible: only the engine calls it, so no request path
     * can reach a plaintext secret.
     */
    ConnectorTarget resolveTarget(UUID connectorId) {
        List<ConnectorTarget> rows = jdbc.query("""
                select id, connector_type, vendor, display_name, config::text as config, credential_ref
                from dispatch.connector
                where tenant_id = ? and id = ?
                """, (rs, i) -> {
                    Map<String, Object> config = read(rs.getString("config"));
                    String ref = rs.getString("credential_ref");
                    Map<String, Object> withRef = new LinkedHashMap<>(config);
                    withRef.put("credentialRef", ref == null ? "" : ref);
                    return new ConnectorTarget(rs.getObject("id", UUID.class), rs.getString("connector_type"),
                            rs.getString("vendor"), rs.getString("display_name"), withRef,
                            credentials.resolveSecret(ref));
                }, TenantContext.get().tenantId(), connectorId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    void markCredentialUsed(String credentialRef) {
        credentials.markUsed(credentialRef);
    }

    /** FR-INT-008: what this deployment can actually talk to, and what is a stand-in. */
    public List<AdapterRegistry.AdapterDescriptor> adapterCatalogue() {
        return adapters.catalogue();
    }

    /* ------------------------------------------------------------------ */

    private ConnectorRow mapConnector(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        String ref = rs.getString("credential_ref");
        String status = ref == null || ref.isBlank()
                ? "NONE"
                : (rs.getBoolean("credential_present") ? "SET" : "MISSING");
        return new ConnectorRow(
                rs.getObject("id", UUID.class), rs.getString("connector_type"), rs.getString("vendor"),
                rs.getString("display_name"), rs.getBoolean("enabled"),
                ConfigSanitiser.forDisplay(read(rs.getString("config"))), ref, status,
                rs.getInt("subscription_count"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private String write(Map<String, Object> config) {
        try {
            return json.writeValueAsString(config == null ? Map.of() : config);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Connector configuration is not serializable", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String text) {
        if (text == null || text.isBlank()) return Map.of();
        try {
            return json.readValue(text, Map.class);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

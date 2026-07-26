package com.axiom.migration;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.common.SecretCipher;
import com.axiom.migration.SourceContract.SourceAdapter;
import com.axiom.migration.SourceContract.SourceField;
import com.axiom.migration.SourceContract.SourceHandshake;
import com.axiom.migration.SourceContract.SourceObject;
import com.axiom.migration.SourceContract.SourceSession;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Source connections and schema discovery (FR-MIG-001, FR-MIG-002).
 *
 * <h2>Credentials</h2>
 * Only READ_ONLY scope is ever stored, and the column CHECK refuses anything
 * else. Secrets are encrypted with {@link SecretCipher} on the way in and are
 * never returned by any read on this service — the API exposes whether a
 * credential is present, not what it is.
 *
 * <h2>Discovery is a snapshot, and dated</h2>
 * {@code migration.source_object} and {@code migration.source_field} are the
 * source's schema <em>as it was when we looked</em>, with the timestamp kept. A
 * mapping is only meaningful against a known schema version, and a source that
 * grows a field after discovery must show up as a re-discovery rather than
 * silently changing what a saved mapping means.
 */
@Service
public class MigrationConnectionService {

    private final JdbcTemplate jdbc;
    private final SourceAdapterRegistry adapters;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final ObjectMapper json;

    public MigrationConnectionService(JdbcTemplate jdbc, SourceAdapterRegistry adapters,
                                      SecretCipher cipher, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.adapters = adapters;
        this.cipher = cipher;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ requests and rows

    public record ConnectRequest(@NotBlank @Size(max = 120) String name,
                                 @NotBlank @Size(max = 32) String vendor,
                                 @Size(max = 240) String instanceUrl,
                                 @Size(max = 240) String clientId,
                                 @Size(max = 512) String clientSecret,
                                 @Size(max = 2048) String refreshToken,
                                 @Size(max = 120) String fixtureKey) {}

    public record ConnectionRow(UUID id, String name, String vendor, String vendorLabel, String scope,
                                String status, String instanceUrl, boolean credentialStored,
                                String fixtureKey, int fixtureWave, Instant discoveredAt,
                                Instant lastVerifiedAt, String message, long objectCount, Instant createdAt) {}

    public record VendorRow(String vendor, String displayName, boolean liveInteropAvailable,
                            List<String> requestedScopes, String note) {}

    public record DiscoveredField(String apiName, String label, String dataType, boolean custom,
                                  boolean nullable, String sampleValue) {}

    public record DiscoveredObject(String apiName, String label, boolean custom, long recordCount,
                                   String proposedTarget, List<DiscoveredField> fields) {}

    public record DiscoveryResult(UUID connectionId, String vendor, String connectedAs, Instant discoveredAt,
                                  List<DiscoveredObject> objects, long totalRecords,
                                  long customObjectCount, long customFieldCount) {}

    // ------------------------------------------------------------------ vendors

    public List<VendorRow> vendors() {
        return adapters.all().stream()
                .map(a -> new VendorRow(a.vendor(), a.displayName(), a.liveInteropAvailable(),
                        a.requestedScopes(),
                        a.liveInteropAvailable()
                                ? "Available."
                                : "DEFERRED: contract, read-only scopes and object catalogue are implemented; "
                                  + "no authenticated round-trip has been performed against a live org in this build."))
                .toList();
    }

    /** Fixture datasets this build ships, for the local source. */
    public List<String> fixtureKeys() {
        return FixtureSourceAdapter.shippedKeys();
    }

    // ------------------------------------------------------------------ connect

    @Transactional
    public ConnectionRow connect(ConnectRequest request) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requireImport(principal.role());

        String vendor = request.vendor().toUpperCase(Locale.ROOT);
        SourceAdapter adapter = adapters.require(vendor);

        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into migration.source_connection
                      (tenant_id, name, vendor, instance_url, credential_ref, fixture_key, status, created_by)
                    values (?, ?, ?, ?, ?, ?, 'PENDING', ?)
                    returning id
                    """, UUID.class, principal.tenantId(), request.name().trim(), vendor,
                    request.instanceUrl(), encodeCredentials(request), request.fixtureKey(), principal.userId());
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("A migration source named \"" + request.name().trim()
                    + "\" already exists in this tenant");
        }

        SourceHandshake handshake;
        try {
            handshake = adapter.connect(session(id, vendor, request.instanceUrl(), request.fixtureKey(), 1, request));
        } catch (RuntimeException ex) {
            jdbc.update("""
                    update migration.source_connection set status = 'FAILED', message = ?, updated_at = now()
                    where tenant_id = ? and id = ?
                    """, ex.getMessage(), principal.tenantId(), id);
            throw ex;
        }

        jdbc.update("""
                update migration.source_connection
                   set status = 'CONNECTED', last_verified_at = now(), message = ?, updated_at = now()
                 where tenant_id = ? and id = ?
                """, "Connected as " + handshake.connectedAs() + " with " + handshake.scope() + " scope",
                principal.tenantId(), id);

        audit.record("MIGRATION_SOURCE_CONNECTED", "MIGRATION_SOURCE", id,
                "Connected " + vendor + " migration source \"" + request.name().trim() + "\" with READ_ONLY scope",
                Map.of("vendor", vendor, "scope", "READ_ONLY",
                        "objects", String.valueOf(handshake.objects().size())));

        return connection(id);
    }

    /**
     * Advance the fixture source's simulated clock. Fixture connections only —
     * a live vendor's records change on their own and there is nothing here to
     * simulate. Exposed so a parallel-run cutover can be demonstrated end to end.
     */
    @Transactional
    public ConnectionRow advanceFixtureWave(UUID connectionId, @Min(1) int wave) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requireImport(principal.role());
        ConnectionRow row = connection(connectionId);
        if (!FixtureSourceAdapter.VENDOR.equals(row.vendor())) {
            throw new ConflictException("Fixture waves apply only to the local fixture source. Connection \""
                    + row.name() + "\" is a " + row.vendor() + " source, whose records change on their own.");
        }
        jdbc.update("update migration.source_connection set fixture_wave = ?, updated_at = now() "
                + "where tenant_id = ? and id = ?", wave, principal.tenantId(), connectionId);
        return connection(connectionId);
    }

    // ------------------------------------------------------------------ discovery

    @Transactional
    public DiscoveryResult discover(UUID connectionId) {
        TenantContext.Principal principal = TenantContext.get();
        CrmRole.requireImport(principal.role());
        ConnectionRow row = connection(connectionId);
        SourceAdapter adapter = adapters.require(row.vendor());
        SourceSession session = session(row);

        jdbc.update("delete from migration.source_field where tenant_id = ? and connection_id = ?",
                principal.tenantId(), connectionId);
        jdbc.update("delete from migration.source_object where tenant_id = ? and connection_id = ?",
                principal.tenantId(), connectionId);

        List<SourceObject> objects = adapter.objects(session);
        List<DiscoveredObject> discovered = new java.util.ArrayList<>();
        long totalRecords = 0;
        long customFields = 0;

        for (SourceObject object : objects) {
            jdbc.update("""
                    insert into migration.source_object
                      (tenant_id, connection_id, api_name, label, is_custom, record_count, proposed_target)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, principal.tenantId(), connectionId, object.apiName(), object.label(),
                    object.custom(), object.recordCount(), object.proposedTarget());

            List<SourceField> fields = adapter.fields(session, object.apiName());
            List<DiscoveredField> mapped = new java.util.ArrayList<>();
            for (SourceField field : fields) {
                jdbc.update("""
                        insert into migration.source_field
                          (tenant_id, connection_id, object_api_name, api_name, label, data_type,
                           is_custom, nullable, sample_value)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, principal.tenantId(), connectionId, object.apiName(), field.apiName(),
                        field.label(), field.dataType(), field.custom(), field.nullable(), field.sampleValue());
                if (field.custom()) customFields++;
                mapped.add(new DiscoveredField(field.apiName(), field.label(), field.dataType(),
                        field.custom(), field.nullable(), field.sampleValue()));
            }
            totalRecords += object.recordCount();
            discovered.add(new DiscoveredObject(object.apiName(), object.label(), object.custom(),
                    object.recordCount(), object.proposedTarget(), mapped));
        }

        jdbc.update("update migration.source_connection set discovered_at = now(), updated_at = now() "
                + "where tenant_id = ? and id = ?", principal.tenantId(), connectionId);

        audit.record("MIGRATION_SCHEMA_DISCOVERED", "MIGRATION_SOURCE", connectionId,
                "Discovered " + discovered.size() + " object(s) and " + totalRecords + " record(s) on "
                + row.vendor() + " source \"" + row.name() + "\"",
                Map.of("objects", String.valueOf(discovered.size()),
                        "records", String.valueOf(totalRecords),
                        "customFields", String.valueOf(customFields)));

        return new DiscoveryResult(connectionId, row.vendor(), row.name(), Instant.now(), discovered,
                totalRecords, discovered.stream().filter(DiscoveredObject::custom).count(), customFields);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<ConnectionRow> list() {
        return jdbc.query(CONNECTION_SELECT + " where c.tenant_id = ? order by c.created_at desc",
                connectionMapper(), TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public ConnectionRow connection(UUID id) {
        List<ConnectionRow> rows = jdbc.query(CONNECTION_SELECT + " where c.tenant_id = ? and c.id = ?",
                connectionMapper(), TenantContext.get().tenantId(), id);
        if (rows.isEmpty()) throw new NotFoundException("No migration source connection " + id);
        return rows.get(0);
    }

    private static final String CONNECTION_SELECT = """
            select c.id, c.name, c.vendor, c.scope, c.status, c.instance_url, c.credential_ref,
                   c.fixture_key, c.fixture_wave, c.discovered_at, c.last_verified_at, c.message, c.created_at,
                   (select count(*) from migration.source_object o
                     where o.tenant_id = c.tenant_id and o.connection_id = c.id) as object_count
            from migration.source_connection c
            """;

    /**
     * Built per call rather than held in an instance field: a field initialiser
     * runs before the constructor assigns {@code adapters}, which the compiler
     * rejects outright.
     */
    private org.springframework.jdbc.core.RowMapper<ConnectionRow> connectionMapper() {
        return (rs, i) -> new ConnectionRow(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("vendor"),
                adapters.find(rs.getString("vendor")).map(SourceAdapter::displayName).orElse(rs.getString("vendor")),
                rs.getString("scope"), rs.getString("status"), rs.getString("instance_url"),
                rs.getString("credential_ref") != null, rs.getString("fixture_key"), rs.getInt("fixture_wave"),
                rs.getTimestamp("discovered_at") == null ? null : rs.getTimestamp("discovered_at").toInstant(),
                rs.getTimestamp("last_verified_at") == null ? null : rs.getTimestamp("last_verified_at").toInstant(),
                rs.getString("message"), rs.getLong("object_count"),
                rs.getTimestamp("created_at").toInstant());
    }

    // ------------------------------------------------------------------ sessions

    /** Rebuild an adapter session from the stored connection, decrypting on the way. */
    SourceSession session(ConnectionRow row) {
        Map<String, String> credentials = decodeCredentials(row.id());
        return new SourceSession(row.id(), row.vendor(), row.instanceUrl(), row.fixtureKey(),
                row.fixtureWave(),
                new SourceContract.SourceCredentials(row.instanceUrl(),
                        credentials.get("clientId"), credentials.get("clientSecret"),
                        credentials.get("refreshToken"), row.fixtureKey()));
    }

    private SourceSession session(UUID id, String vendor, String instanceUrl, String fixtureKey,
                                  int wave, ConnectRequest request) {
        return new SourceSession(id, vendor, instanceUrl, fixtureKey, wave,
                new SourceContract.SourceCredentials(instanceUrl, request.clientId(),
                        request.clientSecret(), request.refreshToken(), fixtureKey));
    }

    private String encodeCredentials(ConnectRequest request) {
        Map<String, String> secrets = new LinkedHashMap<>();
        if (request.clientId() != null) secrets.put("clientId", request.clientId());
        if (request.clientSecret() != null) secrets.put("clientSecret", request.clientSecret());
        if (request.refreshToken() != null) secrets.put("refreshToken", request.refreshToken());
        if (secrets.isEmpty()) return null;
        try {
            return cipher.encrypt(json.writeValueAsString(secrets));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("Source credentials could not be serialised", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> decodeCredentials(UUID connectionId) {
        List<String> refs = jdbc.query("select credential_ref from migration.source_connection "
                        + "where tenant_id = ? and id = ?", (rs, i) -> rs.getString(1),
                TenantContext.get().tenantId(), connectionId);
        if (refs.isEmpty() || refs.get(0) == null) return Map.of();
        try {
            return json.readValue(cipher.decrypt(refs.get(0)), Map.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored source credentials could not be read", ex);
        }
    }
}

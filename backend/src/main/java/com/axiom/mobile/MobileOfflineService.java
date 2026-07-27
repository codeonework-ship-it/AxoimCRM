package com.axiom.mobile;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.security.AuthorizationService;
import com.axiom.security.SecurableObject;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * E21 offline command boundary.
 *
 * <p>Every cached row carries the server version from the moment it was read.
 * A submitted mutation either applies against that exact version or becomes an
 * explicit conflict containing both client and server values. There is no
 * last-write-wins path. Authorization is re-evaluated at sync and resolution
 * time, so losing access while offline fails closed.</p>
 */
@Service
public class MobileOfflineService {
    private static final int PACKAGE_ROW_LIMIT_PER_TYPE = 100;
    private static final Map<SecurableObject, Map<String, FieldSpec>> WRITABLE_FIELDS = Map.of(
            SecurableObject.ACCOUNT, Map.of(
                    "name", new FieldSpec("name", FieldType.TEXT),
                    "industry", new FieldSpec("industry", FieldType.TEXT)),
            SecurableObject.CONTACT, Map.of(
                    "firstName", new FieldSpec("first_name", FieldType.TEXT),
                    "lastName", new FieldSpec("last_name", FieldType.TEXT),
                    "email", new FieldSpec("email", FieldType.TEXT),
                    "title", new FieldSpec("title", FieldType.TEXT)),
            SecurableObject.LEAD, Map.of(
                    "firstName", new FieldSpec("first_name", FieldType.TEXT),
                    "lastName", new FieldSpec("last_name", FieldType.TEXT),
                    "company", new FieldSpec("company", FieldType.TEXT),
                    "email", new FieldSpec("email", FieldType.TEXT)),
            SecurableObject.OPPORTUNITY, Map.of(
                    "name", new FieldSpec("name", FieldType.TEXT),
                    "amount", new FieldSpec("amount", FieldType.DECIMAL),
                    "closeDate", new FieldSpec("close_date", FieldType.DATE)));

    private enum FieldType { TEXT, DECIMAL, DATE }
    private record FieldSpec(String column, FieldType type) {}
    private record CurrentRecord(long version, JsonNode payload) {}

    public record PackageRequest(@NotNull UUID deviceSessionId,
                                 @NotEmpty @Size(max = 4) List<@NotBlank String> entityTypes) {}
    public record ChangeRequest(@NotBlank @Size(max = 100) String clientMutationId,
                                @NotBlank String entityType, @NotNull UUID recordId,
                                long baseVersion, @NotNull JsonNode patch) {}
    public record SyncRequest(@NotEmpty @Size(max = 500) List<ChangeRequest> changes) {}
    public record ResolveRequest(@NotBlank String resolution, JsonNode mergedPayload,
                                 @NotBlank @Size(max = 500) String reason) {}
    public record OfflinePackage(UUID id, UUID deviceSessionId, String packageNumber, String status,
                                 int objectCount, int payloadBytes, Instant generatedAt,
                                 Instant expiresAt, String checksum, long cacheAgeSeconds) {}
    public record OfflineSnapshot(String entityType, UUID recordId, long recordVersion,
                                  JsonNode payload, Instant cachedAt) {}
    public record ChangeResult(UUID id, String clientMutationId, String entityType, UUID recordId,
                               String status, Long appliedVersion, String reason, UUID conflictId) {}
    public record SyncResult(UUID runId, String status, int submitted, int applied,
                             int conflicts, int rejected, List<ChangeResult> changes) {}
    public record ConflictView(UUID id, UUID changeId, String entityType, UUID recordId,
                               long baseVersion, long serverVersion, List<String> conflictingFields,
                               JsonNode clientPatch, JsonNode serverPayload, String status,
                               String resolution, String resolutionReason, Instant detectedAt) {}

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final ObjectMapper json;

    public MobileOfflineService(JdbcTemplate jdbc, AuthorizationService authorization,
                                AuditService audit, OutboxWriter outbox, ObjectMapper json) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public OfflinePackage createPackage(PackageRequest request) {
        Map<String, Object> device = device(request.deviceSessionId(), true);
        requireDeviceOwnerOrAdmin(device);
        if (!"ACTIVE".equals(device.get("status"))) throw new ConflictException("Only an active device can receive offline data");
        List<SecurableObject> types = request.entityTypes().stream()
                .map(SecurableObject::of).distinct().sorted(Comparator.comparing(Enum::name)).toList();
        UUID packageId = UUID.randomUUID();
        String number = "OFF-" + Instant.now().toEpochMilli() + "-" + packageId.toString().substring(0, 6).toUpperCase(Locale.ROOT);
        jdbc.update("""
                insert into mobile.offline_sync_package
                  (id,tenant_id,device_session_id,package_number,status,object_count,payload_bytes,
                   generated_at,cache_generated_at,cache_expires_at,base_cursor,created_by)
                values (?,?,?,?,'QUEUED',0,0,now(),now(),now()+interval '7 days',?,?)
                """, packageId, tenant(), request.deviceSessionId(), number, currentCursor(), actor());
        int objects = 0;
        int bytes = 0;
        List<String> checksums = new ArrayList<>();
        for (SecurableObject type : types) {
            for (CurrentWithId row : visible(type)) {
                String payload = write(row.payload());
                String checksum = sha256(payload);
                jdbc.update("""
                        insert into mobile.offline_record_snapshot
                          (tenant_id,sync_package_id,entity_type,record_id,record_version,payload,payload_checksum)
                        values (?,?,?,?,?,?::jsonb,?)
                        """, tenant(), packageId, type.name(), row.id(), row.version(), payload, checksum);
                objects++;
                bytes += payload.getBytes(StandardCharsets.UTF_8).length;
                checksums.add(type.name() + ":" + row.id() + ":" + checksum);
            }
        }
        String packageChecksum = sha256(String.join("|", checksums));
        jdbc.update("""
                update mobile.offline_sync_package set object_count=?,payload_bytes=?,package_checksum=?
                 where tenant_id=? and id=?
                """, objects, bytes, packageChecksum, tenant(), packageId);
        Map<String, Object> evidence = Map.of("deviceSessionId", request.deviceSessionId(), "entityTypes",
                types.stream().map(Enum::name).toList(), "objectCount", objects, "payloadBytes", bytes,
                "checksum", packageChecksum);
        audit.record("OFFLINE_PACKAGE_CREATED", "OFFLINE_SYNC_PACKAGE", packageId,
                "Created permission-filtered offline package " + number, evidence);
        outbox.write("offline_sync_package", packageId, "mobile.offline.package.created", evidence);
        return packageById(packageId);
    }

    @Transactional(readOnly = true)
    public List<OfflinePackage> packages(UUID deviceId) {
        Map<String, Object> device = device(deviceId, false);
        requireDeviceOwnerOrAdmin(device);
        return jdbc.query("""
                select id,device_session_id,package_number,status,object_count,payload_bytes,
                       coalesce(cache_generated_at,generated_at) generated_at,cache_expires_at,package_checksum
                  from mobile.offline_sync_package where tenant_id=? and device_session_id=?
                 order by generated_at desc limit 50
                """, (rs, i) -> packageRow(rs), tenant(), deviceId);
    }

    @Transactional(readOnly = true)
    public List<OfflineSnapshot> snapshots(UUID packageId) {
        Map<String, Object> pack = packageMap(packageId, false);
        requireDeviceOwnerOrAdmin(device((UUID) pack.get("device_session_id"), false));
        return jdbc.query("""
                select entity_type,record_id,record_version,payload::text,cached_at
                  from mobile.offline_record_snapshot where tenant_id=? and sync_package_id=?
                 order by entity_type,record_id
                """, (rs, i) -> new OfflineSnapshot(rs.getString("entity_type"),
                        rs.getObject("record_id", UUID.class), rs.getLong("record_version"),
                        read(rs.getString("payload")), rs.getTimestamp("cached_at").toInstant()), tenant(), packageId);
    }

    @Transactional
    public SyncResult synchronize(UUID packageId, SyncRequest request) {
        Map<String, Object> pack = packageMap(packageId, true);
        UUID deviceId = (UUID) pack.get("device_session_id");
        Map<String, Object> device = device(deviceId, true);
        requireDeviceOwnerOrAdmin(device);
        if (!"ACTIVE".equals(device.get("status"))) throw new ConflictException("Device is not active; queued changes were not applied");
        if (((Timestamp) pack.get("cache_expires_at")).toInstant().isBefore(Instant.now())) {
            throw new ConflictException("Offline cache expired. Download a fresh package before synchronizing changes.");
        }
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                insert into mobile.sync_run(id,tenant_id,device_session_id,sync_package_id,status,submitted_count,started_by)
                values (?,?,?,?,'RUNNING',?,?)
                """, runId, tenant(), deviceId, packageId, request.changes().size(), actor());
        List<ChangeResult> results = new ArrayList<>();
        int applied = 0, conflicts = 0, rejected = 0;
        for (ChangeRequest change : request.changes()) {
            ChangeResult result = processChange(packageId, deviceId, change);
            results.add(result);
            if ("APPLIED".equals(result.status())) applied++;
            else if ("CONFLICT".equals(result.status())) conflicts++;
            else if ("REJECTED".equals(result.status())) rejected++;
        }
        String status = conflicts > 0 ? "NEEDS_RESOLUTION" : rejected > 0 && applied == 0 ? "FAILED" : "COMPLETED";
        String packageStatus = conflicts > 0 ? "CONFLICT" : rejected > 0 && applied == 0 ? "FAILED" : "SYNCED";
        jdbc.update("""
                update mobile.sync_run set status=?,applied_count=?,conflict_count=?,rejected_count=?,
                       result_cursor=?,completed_at=now() where tenant_id=? and id=?
                """, status, applied, conflicts, rejected, currentCursor(), tenant(), runId);
        jdbc.update("update mobile.offline_sync_package set status=?,applied_at=case when ?='SYNCED' then now() else applied_at end where tenant_id=? and id=?",
                packageStatus, packageStatus, tenant(), packageId);
        jdbc.update("update mobile.device_session set last_sync_at=now(),offline_queue_count=? where tenant_id=? and id=?",
                conflicts + rejected, tenant(), deviceId);
        Map<String, Object> evidence = Map.of("packageId", packageId, "submitted", request.changes().size(),
                "applied", applied, "conflicts", conflicts, "rejected", rejected, "status", status);
        audit.record("OFFLINE_SYNC_COMPLETED", "SYNC_RUN", runId,
                "Offline sync completed with explicit conflict handling", evidence);
        outbox.write("sync_run", runId, "mobile.offline.sync.completed", evidence);
        return new SyncResult(runId, status, request.changes().size(), applied, conflicts, rejected, List.copyOf(results));
    }

    @Transactional(readOnly = true)
    public List<ConflictView> conflicts(UUID deviceId, String status) {
        Map<String, Object> device = device(deviceId, false);
        requireDeviceOwnerOrAdmin(device);
        String state = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        return jdbc.query("""
                select c.id,c.offline_change_id,c.entity_type,c.record_id,c.base_version,c.server_version,
                       c.conflicting_fields,c.client_patch::text,c.server_payload::text,c.status,c.resolution,
                       c.resolution_reason,c.detected_at
                  from mobile.sync_conflict c join mobile.offline_change ch
                    on ch.tenant_id=c.tenant_id and ch.id=c.offline_change_id
                 where c.tenant_id=? and ch.device_session_id=? and (?::text is null or c.status=?)
                 order by c.detected_at desc limit 100
                """, (rs, i) -> new ConflictView(rs.getObject("id", UUID.class),
                        rs.getObject("offline_change_id", UUID.class), rs.getString("entity_type"),
                        rs.getObject("record_id", UUID.class), rs.getLong("base_version"),
                        rs.getLong("server_version"), List.of((String[]) rs.getArray("conflicting_fields").getArray()),
                        read(rs.getString("client_patch")), read(rs.getString("server_payload")),
                        rs.getString("status"), rs.getString("resolution"), rs.getString("resolution_reason"),
                        rs.getTimestamp("detected_at").toInstant()), tenant(), deviceId, state, state);
    }

    @Transactional
    public ConflictView resolve(UUID conflictId, ResolveRequest request) {
        String decision = enumValue(request.resolution(), Set.of("SERVER_WINS", "CLIENT_WINS", "MERGED"), "resolution");
        Map<String, Object> row = jdbc.query("""
                select c.*,ch.device_session_id,ch.patch::text change_patch
                  from mobile.sync_conflict c join mobile.offline_change ch
                    on ch.tenant_id=c.tenant_id and ch.id=c.offline_change_id
                 where c.tenant_id=? and c.id=? for update of c,ch
                """, (rs, i) -> {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int col=1; col<=rs.getMetaData().getColumnCount(); col++) map.put(rs.getMetaData().getColumnLabel(col), rs.getObject(col));
            return map;
        }, tenant(), conflictId).stream().findFirst().orElseThrow(() -> new NotFoundException("Sync conflict not found"));
        if (!"OPEN".equals(row.get("status"))) throw new ConflictException("This sync conflict was already resolved");
        requireDeviceOwnerOrAdmin(device((UUID) row.get("device_session_id"), true));
        SecurableObject object = SecurableObject.of(String.valueOf(row.get("entity_type")));
        UUID recordId = (UUID) row.get("record_id");
        if (!authorization.canEdit(object, recordId)) throw new ForbiddenException("Record access changed while offline; this conflict cannot alter the server record");
        JsonNode payload = null;
        Long appliedVersion = null;
        if (!"SERVER_WINS".equals(decision)) {
            payload = "MERGED".equals(decision) ? request.mergedPayload() : read(String.valueOf(row.get("change_patch")));
            validatePatch(object, payload);
            CurrentRecord current = current(object, recordId, true);
            appliedVersion = apply(object, recordId, current.version(), payload).version();
        }
        jdbc.update("""
                update mobile.sync_conflict set status=?,resolution=?,resolution_payload=?::jsonb,
                       resolution_reason=?,resolved_by=?,resolved_at=now() where tenant_id=? and id=?
                """, "SERVER_WINS".equals(decision) ? "DISCARDED" : "RESOLVED", decision,
                payload == null ? null : write(payload), request.reason().trim(), actor(), tenant(), conflictId);
        jdbc.update("""
                update mobile.offline_change set status=?,applied_version=?,applied_at=case when ?='APPLIED' then now() else applied_at end
                 where tenant_id=? and id=?
                """, "SERVER_WINS".equals(decision) ? "DISCARDED" : "APPLIED", appliedVersion,
                "SERVER_WINS".equals(decision) ? "DISCARDED" : "APPLIED", tenant(), row.get("offline_change_id"));
        Map<String, Object> evidence = Map.of("entityType", object.name(), "recordId", recordId,
                "resolution", decision, "reason", request.reason().trim());
        audit.recordWithReason("OFFLINE_CONFLICT_RESOLVED", object.name(), recordId,
                "Resolved offline edit conflict using " + decision, request.reason().trim(), evidence);
        outbox.write("sync_conflict", conflictId, "mobile.offline.conflict.resolved", evidence);
        return conflict(conflictId);
    }

    private ChangeResult processChange(UUID packageId, UUID deviceId, ChangeRequest request) {
        List<ChangeResult> existing = jdbc.query("""
                select ch.id,ch.client_mutation_id,ch.entity_type,ch.record_id,ch.status,ch.applied_version,
                       ch.conflict_reason,c.id conflict_id
                  from mobile.offline_change ch left join mobile.sync_conflict c
                    on c.tenant_id=ch.tenant_id and c.offline_change_id=ch.id
                 where ch.tenant_id=? and ch.device_session_id=? and ch.client_mutation_id=?
                """, (rs, i) -> changeRow(rs), tenant(), deviceId, request.clientMutationId());
        if (!existing.isEmpty()) return existing.getFirst();
        SecurableObject object = SecurableObject.of(request.entityType());
        validatePatch(object, request.patch());
        UUID changeId = UUID.randomUUID();
        if (!authorization.canRead(object, request.recordId()) || !authorization.canEdit(object, request.recordId())) {
            String reason = "Record access changed while offline; the server refused this edit.";
            insertChange(changeId, packageId, deviceId, request, object, "REJECTED", reason, null);
            return new ChangeResult(changeId, request.clientMutationId(), object.name(), request.recordId(),
                    "REJECTED", null, reason, null);
        }
        CurrentRecord current = current(object, request.recordId(), true);
        if (current.version() != request.baseVersion()) {
            String reason = "Server version " + current.version() + " differs from offline base version " + request.baseVersion() + ".";
            insertChange(changeId, packageId, deviceId, request, object, "CONFLICT", reason, null);
            UUID conflictId = UUID.randomUUID();
            jdbc.update("""
                    insert into mobile.sync_conflict
                      (id,tenant_id,offline_change_id,entity_type,record_id,base_version,server_version,
                       conflicting_fields,client_patch,server_payload,status)
                    values (?,?,?,?,?,?,?,?::text[],?::jsonb,?::jsonb,'OPEN')
                    """, conflictId, tenant(), changeId, object.name(), request.recordId(), request.baseVersion(),
                    current.version(), pgArray(fieldNames(request.patch())), write(request.patch()), write(current.payload()));
            return new ChangeResult(changeId, request.clientMutationId(), object.name(), request.recordId(),
                    "CONFLICT", null, reason, conflictId);
        }
        CurrentRecord applied = apply(object, request.recordId(), request.baseVersion(), request.patch());
        insertChange(changeId, packageId, deviceId, request, object, "APPLIED", null, applied.version());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("clientMutationId", request.clientMutationId());
        evidence.put("baseVersion", request.baseVersion());
        evidence.put("appliedVersion", applied.version());
        evidence.put("before", current.payload());
        evidence.put("after", applied.payload());
        audit.record("OFFLINE_CHANGE_APPLIED", object.name(), request.recordId(),
                "Applied authorized offline edit", evidence);
        outbox.write(object.name().toLowerCase(Locale.ROOT), request.recordId(), "mobile.offline.change.applied", evidence);
        return new ChangeResult(changeId, request.clientMutationId(), object.name(), request.recordId(),
                "APPLIED", applied.version(), null, null);
    }

    private void insertChange(UUID id, UUID packageId, UUID deviceId, ChangeRequest request,
                              SecurableObject object, String status, String reason, Long appliedVersion) {
        jdbc.update("""
                insert into mobile.offline_change
                  (id,tenant_id,sync_package_id,device_session_id,client_mutation_id,entity_type,record_id,
                   operation,base_version,patch,status,conflict_reason,applied_version,submitted_by,applied_at)
                values (?,?,?,?,?, ?,?,'UPDATE',?,?::jsonb,?,?,?, ?,case when ?='APPLIED' then now() end)
                """, id, tenant(), packageId, deviceId, request.clientMutationId(), object.name(), request.recordId(),
                request.baseVersion(), write(request.patch()), status, reason, appliedVersion, actor(), status);
    }

    private CurrentRecord apply(SecurableObject object, UUID id, long expectedVersion, JsonNode patch) {
        validatePatch(object, patch);
        List<String> assignments = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        Map<String, FieldSpec> allowed = WRITABLE_FIELDS.get(object);
        patch.fields().forEachRemaining(entry -> {
            FieldSpec spec = allowed.get(entry.getKey());
            assignments.add(spec.column() + "=?");
            args.add(value(entry.getValue(), spec.type()));
        });
        assignments.add("version=version+1");
        assignments.add("updated_at=now()");
        if (Set.of(SecurableObject.ACCOUNT, SecurableObject.CONTACT, SecurableObject.LEAD).contains(object)) {
            assignments.add("updated_by=?");
            args.add(actor());
        }
        args.add(tenant());
        args.add(id);
        args.add(expectedVersion);
        int changed = jdbc.update("update " + object.qualifiedTable() + " set " + String.join(",", assignments)
                + " where tenant_id=? and id=? and version=?", args.toArray());
        if (changed != 1) throw new ConflictException("Server record changed during synchronization; retry with a fresh package");
        return current(object, id, false);
    }

    static void validatePatch(SecurableObject object, JsonNode patch) {
        if (patch == null || !patch.isObject() || patch.isEmpty()) throw new ConflictException("Offline edit patch must contain at least one field");
        Map<String, FieldSpec> allowed = WRITABLE_FIELDS.get(object);
        List<String> invalid = new ArrayList<>();
        patch.fieldNames().forEachRemaining(field -> { if (!allowed.containsKey(field)) invalid.add(field); });
        if (!invalid.isEmpty()) throw new ConflictException("Offline editing is not allowed for fields: " + String.join(", ", invalid));
    }

    private record CurrentWithId(UUID id, long version, JsonNode payload) {}

    private List<CurrentWithId> visible(SecurableObject object) {
        AuthorizationService.RecordPredicate predicate = authorization.visibleRecordPredicate(object, "r");
        List<Object> args = new ArrayList<>();
        args.add(tenant());
        args.addAll(predicate.args());
        String deleted = object.softDeleted() ? " and r.deleted_at is null" : "";
        return jdbc.query("select r.id,r.version," + payloadExpression(object, "r") + "::text payload from "
                        + object.qualifiedTable() + " r where r.tenant_id=? and (" + predicate.sql() + ")"
                        + deleted + " order by r.updated_at desc limit " + PACKAGE_ROW_LIMIT_PER_TYPE,
                (rs, i) -> new CurrentWithId(rs.getObject("id", UUID.class), rs.getLong("version"),
                        read(rs.getString("payload"))), args.toArray());
    }

    private CurrentRecord current(SecurableObject object, UUID id, boolean lock) {
        String deleted = object.softDeleted() ? " and r.deleted_at is null" : "";
        List<CurrentRecord> rows = jdbc.query("select r.version," + payloadExpression(object, "r")
                        + "::text payload from " + object.qualifiedTable()
                        + " r where r.tenant_id=? and r.id=?" + deleted + (lock ? " for update" : ""),
                (rs, i) -> new CurrentRecord(rs.getLong("version"), read(rs.getString("payload"))), tenant(), id);
        return rows.stream().findFirst().orElseThrow(() -> new NotFoundException("Record is no longer available"));
    }

    private String payloadExpression(SecurableObject object, String alias) {
        return switch (object) {
            case ACCOUNT -> "jsonb_build_object('id',r.id,'name',r.name,'industry',r.industry,'version',r.version)";
            case CONTACT -> "jsonb_build_object('id',r.id,'firstName',r.first_name,'lastName',r.last_name,'email',r.email,'title',r.title,'version',r.version)";
            case LEAD -> "jsonb_build_object('id',r.id,'firstName',r.first_name,'lastName',r.last_name,'company',r.company,'email',r.email,'version',r.version)";
            case OPPORTUNITY -> "jsonb_build_object('id',r.id,'name',r.name,'amount',r.amount,'closeDate',r.close_date,'version',r.version)";
        };
    }

    private Object value(JsonNode node, FieldType type) {
        if (node == null || node.isNull()) return null;
        return switch (type) {
            case TEXT -> node.asText().trim();
            case DECIMAL -> new BigDecimal(node.asText());
            case DATE -> Date.valueOf(LocalDate.parse(node.asText()));
        };
    }

    private Map<String, Object> device(UUID id, boolean lock) {
        return one("select id,user_id,device_label,status from mobile.device_session where tenant_id=? and id=?"
                + (lock ? " for update" : ""), id, "Device session not found");
    }

    private Map<String, Object> packageMap(UUID id, boolean lock) {
        return one("select id,device_session_id,status,cache_expires_at from mobile.offline_sync_package where tenant_id=? and id=?"
                + (lock ? " for update" : ""), id, "Offline package not found");
    }

    private Map<String, Object> one(String sql, UUID id, String message) {
        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c=1; c<=rs.getMetaData().getColumnCount(); c++) row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
            return row;
        }, tenant(), id).stream().findFirst().orElseThrow(() -> new NotFoundException(message));
    }

    private void requireDeviceOwnerOrAdmin(Map<String, Object> device) {
        if (actor().equals(device.get("user_id")) || CrmRole.current(TenantContext.get().role()).masterAdmin()) return;
        throw new NotFoundException("Device session not found");
    }

    private OfflinePackage packageById(UUID id) {
        return jdbc.query("""
                select id,device_session_id,package_number,status,object_count,payload_bytes,
                       coalesce(cache_generated_at,generated_at) generated_at,cache_expires_at,package_checksum
                  from mobile.offline_sync_package where tenant_id=? and id=?
                """, (rs, i) -> packageRow(rs), tenant(), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Offline package not found"));
    }

    private OfflinePackage packageRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Instant generated = rs.getTimestamp("generated_at").toInstant();
        Timestamp expiry = rs.getTimestamp("cache_expires_at");
        return new OfflinePackage(rs.getObject("id", UUID.class), rs.getObject("device_session_id", UUID.class),
                rs.getString("package_number"), rs.getString("status"), rs.getInt("object_count"),
                rs.getInt("payload_bytes"), generated, expiry == null ? null : expiry.toInstant(),
                rs.getString("package_checksum"), Math.max(0, java.time.Duration.between(generated, Instant.now()).getSeconds()));
    }

    private ChangeResult changeRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        long version = rs.getLong("applied_version");
        return new ChangeResult(rs.getObject("id", UUID.class), rs.getString("client_mutation_id"),
                rs.getString("entity_type"), rs.getObject("record_id", UUID.class), rs.getString("status"),
                rs.wasNull() ? null : version, rs.getString("conflict_reason"), rs.getObject("conflict_id", UUID.class));
    }

    private ConflictView conflict(UUID id) {
        return jdbc.query("""
                select id,offline_change_id,entity_type,record_id,base_version,server_version,
                       conflicting_fields,client_patch::text,server_payload::text,status,resolution,
                       resolution_reason,detected_at from mobile.sync_conflict where tenant_id=? and id=?
                """, (rs, i) -> new ConflictView(rs.getObject("id", UUID.class),
                        rs.getObject("offline_change_id", UUID.class), rs.getString("entity_type"),
                        rs.getObject("record_id", UUID.class), rs.getLong("base_version"), rs.getLong("server_version"),
                        List.of((String[]) rs.getArray("conflicting_fields").getArray()), read(rs.getString("client_patch")),
                        read(rs.getString("server_payload")), rs.getString("status"), rs.getString("resolution"),
                        rs.getString("resolution_reason"), rs.getTimestamp("detected_at").toInstant()), tenant(), id)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Sync conflict not found"));
    }

    private long currentCursor() {
        Long value = jdbc.queryForObject("select coalesce(max(sequence_no),0) from governance.audit_event where tenant_id=?", Long.class, tenant());
        return value == null ? 0 : value;
    }

    private String[] fieldNames(JsonNode patch) {
        List<String> values = new ArrayList<>();
        patch.fieldNames().forEachRemaining(values::add);
        return values.toArray(String[]::new);
    }

    private String pgArray(String[] values) {
        return "{" + String.join(",", values) + "}";
    }

    private String enumValue(String value, Set<String> supported, String label) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!supported.contains(normalized)) throw new ConflictException("Unsupported " + label + ": " + value);
        return normalized;
    }

    private String write(JsonNode value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Offline payload is not valid JSON", ex); }
    }

    private JsonNode read(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Stored offline evidence is invalid", ex); }
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    private UUID tenant() { return TenantContext.get().tenantId(); }
    private UUID actor() { return TenantContext.get().userId(); }
}

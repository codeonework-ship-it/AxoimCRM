package com.axiom.compliance;

import com.axiom.audit.AuditService;
import com.axiom.audit.ExportAuditService;
import com.axiom.audit.FieldHistoryService;
import com.axiom.audit.GovernanceAccess;
import com.axiom.audit.ReadAuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.accounts.ConsentService;
import com.axiom.compliance.ErasableStoreRegistry.ErasableStore;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FR-AUD-008 — access, rectification, portability and erasure for an identified
 * data subject, inside a configurable service window.
 *
 * <p>The design decision worth defending is that erasure <b>iterates the registry</b>
 * ({@link ErasableStoreRegistry}) instead of running a hand-written list of
 * statements. A hand-written list is correct on the day it is written and silently
 * wrong every time the platform grows a store; the registry makes an unreached
 * store a visible row rather than an absent statement.
 *
 * <p>Every store produces a {@code dsr_store_result} — including the ones that
 * could not be reached, which are recorded as {@code UNREACHABLE} with the reason.
 * The request as a whole then completes as
 * {@code COMPLETED_WITH_UNREACHABLE_STORES}, never plain {@code COMPLETED}, so a
 * partial erasure cannot be mistaken for a finished one.
 *
 * <p>The audit record of the erasure is deliberately non-personal: it names the
 * request reference, the subject type and the counts, and never the subject's email
 * or name. Writing the address into an append-only store while erasing it
 * everywhere else would be self-defeating — and the audit store, by design, has no
 * DELETE path to fix it afterwards.
 */
@Service
public class DataSubjectRequestService {

    public record StoreResult(String storeKey, String storeLabel, String storeKind, String status,
                              long recordsAffected, String detail, Instant ranAt) {}

    public record DsrView(UUID id, String reference, String requestType, String subjectType,
                          UUID subjectId, String subjectEmail, String subjectName, String status,
                          String requestedByName, Instant receivedAt, Instant dueAt, Instant completedAt,
                          int serviceWindowDays, int storesReached, int storesUnreachable,
                          long recordsAffected, String outcomeSummary, boolean overdue,
                          List<StoreResult> storeResults, Map<String, Object> payload) {}

    public record DsrPolicy(int accessWindowDays, int rectificationWindowDays,
                            int portabilityWindowDays, int erasureWindowDays, String contactEmail) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ErasableStoreRegistry registry;
    private final AuditService audit;
    private final ReadAuditService readAudit;
    private final ExportAuditService exportAudit;
    private final FieldHistoryService fieldHistory;
    private final ConsentService consent;

    public DataSubjectRequestService(JdbcTemplate jdbc, ObjectMapper json, ErasableStoreRegistry registry,
                                    AuditService audit, ReadAuditService readAudit,
                                    ExportAuditService exportAudit, FieldHistoryService fieldHistory,
                                    ConsentService consent) {
        this.jdbc = jdbc;
        this.json = json;
        this.registry = registry;
        this.audit = audit;
        this.readAudit = readAudit;
        this.exportAudit = exportAudit;
        this.fieldHistory = fieldHistory;
        this.consent = consent;
    }

    /* ---------------------------------------------------------------- policy */

    @Transactional(readOnly = true)
    public DsrPolicy policy() {
        GovernanceAccess.requireRead();
        return readPolicy();
    }

    private DsrPolicy readPolicy() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select access_window_days, rectification_window_days, portability_window_days,
                       erasure_window_days, contact_email
                from compliance.dsr_policy where tenant_id = ?
                """, TenantContext.get().tenantId());
        if (rows.isEmpty()) return new DsrPolicy(30, 30, 30, 30, null);
        Map<String, Object> row = rows.getFirst();
        return new DsrPolicy(((Number) row.get("access_window_days")).intValue(),
                ((Number) row.get("rectification_window_days")).intValue(),
                ((Number) row.get("portability_window_days")).intValue(),
                ((Number) row.get("erasure_window_days")).intValue(),
                (String) row.get("contact_email"));
    }

    @Transactional
    public DsrPolicy updatePolicy(DsrRequests.PolicyUpdate request) {
        GovernanceAccess.requireWrite();
        DsrPolicy before = readPolicy();
        TenantContext.Principal p = TenantContext.get();
        jdbc.update("""
                insert into compliance.dsr_policy(tenant_id, access_window_days, rectification_window_days,
                       portability_window_days, erasure_window_days, contact_email, updated_by, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, now())
                on conflict (tenant_id) do update
                  set access_window_days = excluded.access_window_days,
                      rectification_window_days = excluded.rectification_window_days,
                      portability_window_days = excluded.portability_window_days,
                      erasure_window_days = excluded.erasure_window_days,
                      contact_email = excluded.contact_email,
                      updated_by = excluded.updated_by,
                      updated_at = now()
                """, p.tenantId(), request.accessWindowDays(), request.rectificationWindowDays(),
                request.portabilityWindowDays(), request.erasureWindowDays(), request.contactEmail(),
                p.userId());
        audit.record("DSR_POLICY_UPDATE", "DSR_POLICY", p.tenantId(), "Data subject request service window updated",
                Map.of("before", Map.of("access", before.accessWindowDays(), "erasure", before.erasureWindowDays()),
                        "after", Map.of("access", request.accessWindowDays(), "erasure", request.erasureWindowDays())));
        return readPolicy();
    }

    /* ----------------------------------------------------------------- raise */

    @Transactional
    public DsrView raise(DsrRequests.Raise request) {
        GovernanceAccess.requireWrite();
        TenantContext.Principal p = TenantContext.get();
        DsrPolicy policy = readPolicy();
        String type = request.requestType().toUpperCase(Locale.ROOT);
        int window = switch (type) {
            case "ACCESS" -> policy.accessWindowDays();
            case "RECTIFICATION" -> policy.rectificationWindowDays();
            case "PORTABILITY" -> policy.portabilityWindowDays();
            default -> policy.erasureWindowDays();
        };
        String reference = nextReference();
        UUID id = jdbc.queryForObject("""
                insert into compliance.data_subject_request
                  (tenant_id, reference, request_type, subject_type, subject_id, subject_email,
                   subject_name, requested_by, requested_by_name, due_at, service_window_days, correlation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, now() + make_interval(days => ?), ?, ?)
                returning id
                """, UUID.class, p.tenantId(), reference, type, request.subjectType().toUpperCase(Locale.ROOT),
                request.subjectId(), request.subjectEmail(), request.subjectName(), p.userId(),
                p.displayName(), window, window, MDC.get("correlationId"));

        // Non-personal audit event: the reference identifies the request, the
        // subject's address stays out of the append-only store.
        audit.record("DSR_RAISED", "DATA_SUBJECT_REQUEST", id,
                type + " request " + reference + " raised for a " + request.subjectType() + " data subject",
                Map.of("reference", reference, "requestType", type,
                        "subjectType", request.subjectType(), "serviceWindowDays", window));
        return detail(id);
    }

    private String nextReference() {
        Long ordinal = jdbc.queryForObject(
                "select count(*) + 1 from compliance.data_subject_request where tenant_id = ?",
                Long.class, TenantContext.get().tenantId());
        return "DSR-" + java.time.LocalDate.now().getYear() + "-"
                + String.format("%04d", ordinal == null ? 1 : ordinal);
    }

    /* ------------------------------------------------------------------ read */

    @Transactional(readOnly = true)
    public List<DsrView> list() {
        GovernanceAccess.requireRead();
        List<UUID> ids = jdbc.queryForList("""
                select id from compliance.data_subject_request
                where tenant_id = ? order by received_at desc limit 200
                """, UUID.class, TenantContext.get().tenantId());
        return ids.stream().map(this::readView).toList();
    }

    @Transactional(readOnly = true)
    public DsrView detail(UUID id) {
        GovernanceAccess.requireRead();
        return readView(id);
    }

    private DsrView readView(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, reference, request_type, subject_type, subject_id, subject_email, subject_name,
                       status, requested_by_name, received_at, due_at, completed_at, service_window_days,
                       stores_reached, stores_unreachable, records_affected, outcome_summary, payload::text
                from compliance.data_subject_request where tenant_id = ? and id = ?
                """, TenantContext.get().tenantId(), id);
        if (rows.isEmpty()) throw new NotFoundException("Data subject request not found");
        Map<String, Object> row = rows.getFirst();
        List<StoreResult> results = jdbc.query("""
                select store_key, store_label, store_kind, status, records_affected, detail, ran_at
                from compliance.dsr_store_result
                where tenant_id = ? and request_id = ?
                order by store_kind, store_key
                """, (rs, i) -> new StoreResult(rs.getString("store_key"), rs.getString("store_label"),
                rs.getString("store_kind"), rs.getString("status"), rs.getLong("records_affected"),
                rs.getString("detail"), rs.getTimestamp("ran_at").toInstant()),
                TenantContext.get().tenantId(), id);
        Instant dueAt = ((java.sql.Timestamp) row.get("due_at")).toInstant();
        Instant completedAt = row.get("completed_at") == null ? null
                : ((java.sql.Timestamp) row.get("completed_at")).toInstant();
        Map<String, Object> payload = Map.of();
        if (row.get("payload") != null) {
            try {
                payload = json.readValue((String) row.get("payload"), Map.class);
            } catch (JsonProcessingException ex) {
                payload = Map.of("error", "payload could not be read");
            }
        }
        String status = (String) row.get("status");
        boolean overdue = completedAt == null && dueAt.isBefore(Instant.now())
                && !"REJECTED".equals(status);
        return new DsrView((UUID) row.get("id"), (String) row.get("reference"),
                (String) row.get("request_type"), (String) row.get("subject_type"),
                (UUID) row.get("subject_id"), (String) row.get("subject_email"),
                (String) row.get("subject_name"), status, (String) row.get("requested_by_name"),
                ((java.sql.Timestamp) row.get("received_at")).toInstant(), dueAt, completedAt,
                ((Number) row.get("service_window_days")).intValue(),
                ((Number) row.get("stores_reached")).intValue(),
                ((Number) row.get("stores_unreachable")).intValue(),
                ((Number) row.get("records_affected")).longValue(),
                (String) row.get("outcome_summary"), overdue, results, payload);
    }

    /* ---------------------------------------------------------------- access */

    /**
     * Access and portability both assemble the subject's personal data from every
     * reachable store. The read is itself audited (FR-AUD-003) and the assembly is
     * recorded as an export with its row count (FR-AUD-005) — the package leaves
     * the platform, so pretending it is only a read would understate it.
     */
    @Transactional
    public DsrView fulfil(UUID id) {
        GovernanceAccess.requireWrite();
        DsrView request = readView(id);
        if (request.completedAt() != null) {
            throw new ConflictException("Request " + request.reference() + " is already completed");
        }
        return switch (request.requestType()) {
            case "ACCESS", "PORTABILITY" -> assemblePackage(request);
            case "ERASURE" -> erase(request);
            case "RECTIFICATION" -> throw new ConflictException(
                    "A rectification request needs the corrected values. Submit them to /rectify.");
            default -> throw new ConflictException("Unknown request type " + request.requestType());
        };
    }

    private DsrView assemblePackage(DsrView request) {
        UUID tenantId = TenantContext.get().tenantId();
        Map<String, Object> pack = new LinkedHashMap<>();
        List<String> unreachable = new ArrayList<>();
        long rows = 0;
        int reached = 0;
        for (ErasableStore store : registry.forSubjectType(request.subjectType())) {
            if (!store.erasable()) {
                if (!store.reachable()) unreachable.add(store.storeKey());
                continue;
            }
            SubjectMatch match = subjectMatch(store, request);
            if (match == null) continue;
            List<Map<String, Object>> data;
            try {
                data = jdbc.queryForList("select " + columnList(store) + " from " + store.qualifiedTable()
                        + " where tenant_id = ? and " + match.predicate(), tenantId, match.value());
            } catch (DataAccessException ex) {
                unreachable.add(store.storeKey());
                continue;
            }
            reached++;
            if (!data.isEmpty()) {
                pack.put(store.storeKey(), data);
                rows += data.size();
            }
        }
        pack.put("_manifest", Map.of(
                "reference", request.reference(),
                "requestType", request.requestType(),
                "subjectType", request.subjectType(),
                "storesRead", reached,
                "storesUnreachable", unreachable,
                "rowsIncluded", rows,
                "assembledAt", Instant.now().toString(),
                "format", "RFC 8259 JSON, one array of objects per source store"));

        readAudit.recordRead(request.subjectType(), request.subjectId(),
                List.of("email", "first_name", "last_name", "display_name"),
                "POST /api/v1/compliance/dsr/" + request.id() + "/fulfil",
                request.requestType() + " request " + request.reference(), (int) Math.min(rows, Integer.MAX_VALUE));

        exportAudit.recordExport("DATA_SUBJECT_PACKAGE",
                Map.of("reference", request.reference(), "subjectType", request.subjectType(),
                        "storesRead", reached, "storesUnreachable", unreachable.size()),
                rows, "DATA_SUBJECT", "JSON");

        String status = unreachable.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_UNREACHABLE_STORES";
        String summary = "Assembled " + rows + " record(s) from " + reached + " store(s)."
                + (unreachable.isEmpty() ? "" : " Could not read: " + String.join(", ", unreachable) + ".");
        try {
            jdbc.update("""
                    update compliance.data_subject_request
                       set status = ?, completed_at = now(), stores_reached = ?, stores_unreachable = ?,
                           records_affected = ?, outcome_summary = ?, payload = ?::jsonb
                     where tenant_id = ? and id = ?
                    """, status, reached, unreachable.size(), rows, summary,
                    json.writeValueAsString(pack), tenantId, request.id());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Subject access package could not be serialized", ex);
        }
        audit.record("DSR_" + request.requestType() + "_FULFILLED", "DATA_SUBJECT_REQUEST", request.id(),
                request.requestType() + " request " + request.reference() + " fulfilled",
                Map.of("reference", request.reference(), "storesRead", reached,
                        "storesUnreachable", unreachable, "rowCount", rows));
        return readView(request.id());
    }

    /* ----------------------------------------------------------- rectification */

    @Transactional
    public DsrView rectify(UUID id, DsrRequests.Rectify corrections) {
        GovernanceAccess.requireWrite();
        DsrView request = readView(id);
        if (!"RECTIFICATION".equals(request.requestType())) {
            throw new ConflictException("Request " + request.reference() + " is not a rectification request");
        }
        if (request.completedAt() != null) {
            throw new ConflictException("Request " + request.reference() + " is already completed");
        }
        if (request.subjectId() == null) {
            throw new ConflictException("A rectification request must identify the subject record");
        }
        ErasableStore primary = registry.forSubjectType(request.subjectType()).stream()
                .filter(store -> "PRIMARY_TABLE".equals(store.storeKind()) && "ID".equals(store.subjectMatch()))
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        "No primary record store is registered for subject type " + request.subjectType()));

        Map<String, String> requested = corrections.corrections() == null ? Map.of() : corrections.corrections();
        Map<String, String> applicable = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        requested.forEach((field, value) -> {
            if (primary.personalColumns().contains(field)) applicable.put(field, value);
            else rejected.add(field);
        });
        if (applicable.isEmpty()) {
            throw new ConflictException("None of the requested fields are rectifiable on "
                    + primary.qualifiedTable() + ". Rectifiable fields: "
                    + String.join(", ", primary.personalColumns()));
        }

        UUID tenantId = TenantContext.get().tenantId();
        Map<String, Object> before = jdbc.queryForList("select " + String.join(", ", applicable.keySet())
                + " from " + primary.qualifiedTable() + " where tenant_id = ? and id = ?",
                tenantId, request.subjectId()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Subject record not found"));

        List<Object> args = new ArrayList<>(applicable.values());
        args.add(tenantId);
        args.add(request.subjectId());
        String setClause = String.join(", ", applicable.keySet().stream().map(c -> c + " = ?").toList());
        jdbc.update("update " + primary.qualifiedTable() + " set " + setClause
                + " where tenant_id = ? and id = ?", args.toArray());

        UUID eventId = audit.recordEvent("DSR_RECTIFICATION_FULFILLED", request.subjectType(),
                request.subjectId(), "Rectification " + request.reference() + " applied to "
                        + applicable.size() + " field(s)", null,
                Map.of("reference", request.reference(), "before", before, "after", applicable,
                        "rejectedFields", rejected));
        fieldHistory.recordChanges(request.subjectType(), request.subjectId(), eventId, before, applicable);

        String summary = "Corrected " + applicable.size() + " field(s) on " + primary.qualifiedTable()
                + (rejected.isEmpty() ? "" : ". Not rectifiable and left unchanged: " + String.join(", ", rejected));
        jdbc.update("""
                update compliance.data_subject_request
                   set status = 'COMPLETED', completed_at = now(), stores_reached = 1,
                       records_affected = ?, outcome_summary = ?
                 where tenant_id = ? and id = ?
                """, applicable.size(), summary, tenantId, id);
        return readView(id);
    }

    /* --------------------------------------------------------------- erasure */

    private DsrView erase(DsrView request) {
        UUID tenantId = TenantContext.get().tenantId();
        Pseudonymiser pseudonymiser = new Pseudonymiser();
        String token = pseudonymiser.token(
                request.subjectId() != null ? request.subjectId() : request.subjectEmail());

        int reached = 0;
        int unreachableCount = 0;
        long affected = 0;
        List<String> unreachableKeys = new ArrayList<>();

        for (ErasableStore store : registry.forSubjectType(request.subjectType())) {
            if (!store.reachable() || "NOT_DEPLOYED".equals(store.adapter())
                    || "OPERATIONS_RUNBOOK".equals(store.adapter())) {
                if ("RETAIN_NON_PERSONAL".equals(store.strategy())) {
                    recordStore(request.id(), store, "RETAINED_NON_PERSONAL", 0,
                            "Retained by design. " + retentionRationale(store));
                    reached++;
                } else {
                    recordStore(request.id(), store, "UNREACHABLE", 0,
                            store.unreachableReason() == null
                                    ? "No erasure adapter is registered for this store."
                                    : store.unreachableReason());
                    unreachableCount++;
                    unreachableKeys.add(store.storeKey());
                }
                continue;
            }

            SubjectMatch match = subjectMatch(store, request);
            if (match == null) {
                recordStore(request.id(), store, "NOT_APPLICABLE", 0,
                        "This store matches subjects by " + store.subjectMatch()
                        + ", which this request does not provide.");
                reached++;
                continue;
            }

            try {
                int rows = pseudonymise(store, match, pseudonymiser, token, tenantId);
                affected += rows;
                reached++;
                recordStore(request.id(), store, rows == 0 ? "NOT_APPLICABLE" : "PSEUDONYMISED", rows,
                        rows == 0
                                ? "Reached; no rows in this store referenced the subject."
                                : "Irreversibly pseudonymised " + rows + " row(s): "
                                  + String.join(", ", store.personalColumns())
                                  + ". The salt used is not retained, so the mapping cannot be reversed.");
            } catch (DataAccessException ex) {
                unreachableCount++;
                unreachableKeys.add(store.storeKey());
                recordStore(request.id(), store, "FAILED", 0,
                        "Erasure statement failed against " + store.qualifiedTable() + ": "
                        + rootMessage(ex) + ". Reported rather than skipped (FR-AUD-008).");
            }
        }

        // A withdrawal is appended for every consent still granted. The consent
        // register itself is append-only evidence of lawful basis and is retained.
        int withdrawn = consent.withdrawAllForSubject(request.subjectType(), request.subjectId(),
                request.subjectEmail(), "Erasure request " + request.reference());

        String status = unreachableCount == 0 ? "COMPLETED" : "COMPLETED_WITH_UNREACHABLE_STORES";
        String summary = "Pseudonymised " + affected + " row(s) across " + reached + " store(s); "
                + withdrawn + " consent(s) withdrawn."
                + (unreachableCount == 0
                    ? " Every registered store was reached."
                    : " " + unreachableCount + " store(s) could NOT be reached and are reported individually: "
                      + String.join(", ", unreachableKeys) + ".");

        jdbc.update("""
                update compliance.data_subject_request
                   set status = ?, completed_at = now(), stores_reached = ?, stores_unreachable = ?,
                       records_affected = ?, outcome_summary = ?, payload = null
                 where tenant_id = ? and id = ?
                """, status, reached, unreachableCount, affected, summary, tenantId, request.id());

        // FR-AUD-008: a non-personal record that the erasure occurred is retained.
        // Reference and counts only — no email, no name, nothing re-identifying.
        audit.recordWithReason("DSR_ERASURE_COMPLETED", "DATA_SUBJECT_REQUEST", request.id(),
                "Erasure " + request.reference() + " completed across " + reached + " store(s)",
                "Data subject erasure request " + request.reference(),
                Map.of("reference", request.reference(), "subjectType", request.subjectType(),
                        "storesReached", reached, "storesUnreachable", unreachableCount,
                        "unreachableStores", unreachableKeys, "rowsPseudonymised", affected,
                        "consentsWithdrawn", withdrawn,
                        "personalDataInThisRecord", false));
        return readView(request.id());
    }

    private static String retentionRationale(ErasableStore store) {
        return switch (store.storeKey()) {
            case "governance.audit_event" -> "The audit trail is append-only at storage level and holds the "
                    + "non-personal record that this erasure occurred (FR-AUD-001, FR-AUD-008).";
            case "compliance.consent_event" -> "The consent register is the evidence of lawful basis and of "
                    + "withdrawal; it is retained under the legal-obligation basis and a withdrawal is appended "
                    + "rather than the history being rewritten.";
            default -> "Marked RETAIN_NON_PERSONAL in the erasable store registry.";
        };
    }

    private int pseudonymise(ErasableStore store, SubjectMatch match, Pseudonymiser pseudonymiser,
                             String token, UUID tenantId) {
        Map<String, String> types = columnTypes(store);
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (String column : store.personalColumns()) {
            String type = types.get(column);
            if (type == null) continue;
            String value = pseudonymiser.replacement(column, type, token);
            if ("jsonb".equalsIgnoreCase(type) || "json".equalsIgnoreCase(type)) {
                sets.add(column + " = ?::jsonb");
            } else {
                sets.add(column + " = ?");
            }
            args.add(value);
        }
        if (sets.isEmpty()) return 0;
        args.add(tenantId);
        args.add(match.value());
        return jdbc.update("update " + store.qualifiedTable() + " set " + String.join(", ", sets)
                + " where tenant_id = ? and " + match.predicate(), args.toArray());
    }

    private Map<String, String> columnTypes(ErasableStore store) {
        Map<String, String> types = new LinkedHashMap<>();
        jdbc.queryForList("""
                select column_name, data_type from information_schema.columns
                where table_schema = ? and table_name = ?
                """, store.targetSchema(), store.targetTable())
                .forEach(row -> types.put((String) row.get("column_name"), (String) row.get("data_type")));
        return types;
    }

    private record SubjectMatch(String predicate, Object value) {}

    private SubjectMatch subjectMatch(ErasableStore store, DsrView request) {
        return switch (store.subjectMatch()) {
            case "ID" -> request.subjectId() == null ? null : new SubjectMatch("id = ?", request.subjectId());
            case "RELATED_ENTITY" -> request.subjectId() == null ? null
                    : new SubjectMatch(store.subjectColumn() + " = ?", request.subjectId());
            case "EMAIL" -> new SubjectMatch("lower(" + store.subjectColumn() + ") = lower(?)",
                    request.subjectEmail());
            default -> null;
        };
    }

    private String columnList(ErasableStore store) {
        List<String> columns = new ArrayList<>(store.personalColumns());
        if (columns.isEmpty()) columns.add("id");
        return String.join(", ", columns);
    }

    private void recordStore(UUID requestId, ErasableStore store, String status, long records, String detail) {
        jdbc.update("""
                insert into compliance.dsr_store_result
                  (tenant_id, request_id, store_key, store_label, store_kind, status, records_affected, detail)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (request_id, store_key) do nothing
                """, TenantContext.get().tenantId(), requestId, store.storeKey(), store.label(),
                store.storeKind(), status, records, detail);
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        String message = cursor.getMessage();
        return message == null ? cursor.getClass().getSimpleName()
                : message.lines().findFirst().orElse(message);
    }

    /** FR-AUD-014 SLI input: share of open requests still inside their window. */
    @Transactional(readOnly = true)
    public double withinWindowPercent(UUID tenantId) {
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) as open_count,
                       count(*) filter (where due_at >= now()) as in_window
                from compliance.data_subject_request
                where tenant_id = ? and completed_at is null and status <> 'REJECTED'
                """, tenantId);
        long open = ((Number) row.get("open_count")).longValue();
        if (open == 0) return 100d;
        return (((Number) row.get("in_window")).longValue() * 100d) / open;
    }

    /** Days remaining before the request breaches its window; negative when overdue. */
    public static long daysRemaining(DsrView view) {
        return ChronoUnit.DAYS.between(Instant.now(), view.dueAt());
    }
}

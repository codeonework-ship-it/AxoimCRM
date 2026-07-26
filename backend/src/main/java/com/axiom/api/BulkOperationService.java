package com.axiom.api;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mass field edit, reassignment and delete across the record grids
 * (FR-BULK-001..012).
 *
 * <h2>Per-row outcomes, not a count</h2>
 * The contract here is that every selected record comes back with its own
 * outcome and, when it was not applied, the reason. "42 records updated" out of
 * 50 selected is unauditable — it cannot say which 42, what each held before, or
 * why eight were refused, so the operator has no way to finish the job. Each row
 * is written to {@code crm.bulk_operation_row} including the refusals.
 *
 * <h2>Each row commits independently</h2>
 * {@code REQUIRES_NEW} per record, deliberately. One record failing a check
 * constraint must not roll back the forty that succeeded — a bulk edit that is
 * all-or-nothing across fifty records means one bad row costs the whole
 * operation, and the operator cannot tell which row to fix because the evidence
 * rolled back too. The trade-off is accepted and stated: a partially applied
 * batch is a real outcome here, which is exactly why the per-row log exists.
 *
 * <h2>What can be edited in bulk is a fixed list</h2>
 * Not every column may be mass-assigned. Anything that carries identity (name,
 * email), provenance, or a version is refused, because setting the same value
 * across a selection is either meaningless or destructive for those. The
 * allow-list is per object and lives here rather than in the UI, so a crafted
 * request cannot reach a column the UI never offered.
 */
@Service
public class BulkOperationService {

    /**
     * Mass-assignable fields per object. Identity and provenance columns are
     * absent on purpose: setting fifty contacts to the same email is not a bulk
     * edit, it is data loss.
     */
    private static final Map<String, Set<String>> EDITABLE = Map.of(
            "CONTACT", Set.of("status", "department", "seniority", "title", "accountId"),
            "ACCOUNT", Set.of("status", "industry", "segment", "territory", "businessUnit", "recordType"),
            "LEAD", Set.of("status", "source", "ownerId"));

    private static final Map<String, String> TABLES = Map.of(
            "CONTACT", "crm.contact",
            "ACCOUNT", "crm.account",
            "LEAD", "crm.lead");

    /** camelCase request field -> physical column. Keeps the API free of schema names. */
    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("status", "status"),
            Map.entry("department", "department"),
            Map.entry("seniority", "seniority"),
            Map.entry("title", "title"),
            Map.entry("accountId", "account_id"),
            Map.entry("industry", "industry"),
            Map.entry("segment", "segment"),
            Map.entry("territory", "territory"),
            Map.entry("businessUnit", "business_unit"),
            Map.entry("recordType", "record_type"),
            Map.entry("source", "source"),
            Map.entry("ownerId", "owner_id"));

    private static final int MAX_ROWS = 500;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;

    /**
     * Per-row transactions are run through a template, not an annotation.
     *
     * <p>{@code @Transactional(REQUIRES_NEW)} on a method this class calls on
     * itself does nothing at all: Spring's transaction support is proxy-based, and
     * a self-invocation never crosses the proxy. Written that way, every row would
     * quietly join the caller's transaction and one bad record would roll back the
     * whole batch — the exact behaviour the per-row design exists to avoid, with
     * documentation claiming otherwise. A TransactionTemplate is explicit and
     * cannot be defeated by how the method happens to be called.
     *
     * <p>Used for the batch header as well as the rows. The header has to be
     * committed before any row can reference it: a REQUIRES_NEW row transaction
     * cannot see an uncommitted parent, so the foreign key fails. Committing the
     * header first is also the better audit outcome — the batch exists as evidence
     * even if every row in it is then refused.
     */
    private final TransactionTemplate inOwnTransaction;

    public BulkOperationService(JdbcTemplate jdbc, AuditService audit, ObjectMapper json,
                                PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.inOwnTransaction = new TransactionTemplate(transactions);
        this.inOwnTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // --------------------------------------------------------------- contracts

    public record BulkFieldUpdate(@NotEmpty List<UUID> recordIds,
                                  @Size(max = 40) String field,
                                  String value,
                                  @Size(max = 500) String reason) {}

    public record BulkReassign(@NotEmpty List<UUID> recordIds, UUID ownerId,
                               @Size(max = 500) String reason) {}

    public record BulkDelete(@NotEmpty List<UUID> recordIds, @Size(max = 500) String reason) {}

    public record RowOutcome(UUID recordId, String label, String outcome, String detail,
                             String beforeValue, String afterValue) {}

    public record BulkResult(UUID operationId, String objectType, String operation,
                             int total, int succeeded, int failed, int skipped,
                             List<RowOutcome> rows, String note) {}

    // ------------------------------------------------------------------ entry

    @Transactional
    public BulkResult updateField(String objectType, BulkFieldUpdate request) {
        String object = object(objectType);
        CrmRole.requireWrite(TenantContext.get().role());
        List<UUID> ids = ids(request.recordIds());
        String field = field(object, request.field());
        String column = COLUMNS.get(field);

        UUID operationId = openOperation(object, "FIELD_UPDATE",
                Map.of("field", field, "value", String.valueOf(request.value())), request.reason(), ids.size());

        List<RowOutcome> rows = new ArrayList<>();
        for (UUID id : ids) {
            rows.add(applyOne(object, id, column, field, request.value(), operationId));
        }
        return close(operationId, object, "FIELD_UPDATE", rows,
                "Each record was applied independently; a refusal on one does not undo the others.");
    }

    @Transactional
    public BulkResult reassign(String objectType, BulkReassign request) {
        String object = object(objectType);
        CrmRole.requireWrite(TenantContext.get().role());
        List<UUID> ids = ids(request.recordIds());
        if (request.ownerId() == null) {
            throw new IllegalArgumentException("A new owner is required to transfer records");
        }
        assertActiveUser(request.ownerId());

        UUID operationId = openOperation(object, "REASSIGN",
                Map.of("ownerId", request.ownerId().toString()), request.reason(), ids.size());

        List<RowOutcome> rows = new ArrayList<>();
        for (UUID id : ids) {
            rows.add(applyOne(object, id, "owner_id", "ownerId",
                    request.ownerId().toString(), operationId));
        }
        return close(operationId, object, "REASSIGN", rows,
                "Ownership transferred. Records the caller could not see were reported as skipped, not silently omitted.");
    }

    // ------------------------------------------------------------------ engine

    /**
     * One record, its own transaction.
     *
     * <p>{@code REQUIRES_NEW} is what makes a partial batch possible and is the
     * point: a constraint violation on record 12 must not discard records 1-11,
     * and — just as importantly — must not discard the log rows that explain what
     * happened, which a shared transaction would roll back along with the change.
     */
    private RowOutcome applyOne(String object, UUID id, String column, String field,
                                String value, UUID operationId) {
        return inOwnTransaction.execute((status) -> applyOneInTransaction(object, id, column, field, value, operationId));
    }

    private RowOutcome applyOneInTransaction(String object, UUID id, String column, String field,
                                             String value, UUID operationId) {
        UUID tenant = TenantContext.get().tenantId();

        /*
         * Re-bind app.tenant_id, and it is not redundant.
         *
         * TenantSessionAspect binds it on entry to any @Transactional method, but
         * it binds with SET LOCAL — scoped to that transaction by design, so a
         * pooled connection carries no residual tenant identity. This body runs in
         * a REQUIRES_NEW transaction started from a TransactionTemplate callback,
         * which is neither an annotated method nor the outer transaction, so it
         * begins with app.tenant_id unset and every RLS policy correctly refuses
         * the write. Two correct designs meeting: the fix belongs here, at the
         * boundary that created the new transaction.
         */
        jdbc.query("select set_config('app.tenant_id', ?, true)", (rs) -> null, tenant.toString());
        String table = TABLES.get(object);

        List<Map<String, Object>> found = jdbc.queryForList(
                "select " + column + " as before_value, id from " + table
                        + " where tenant_id = ? and id = ? and deleted_at is null", tenant, id);
        if (found.isEmpty()) {
            // Not an error: the row may belong to another tenant, be deleted, or
            // be outside this user's sharing scope. All three read the same from
            // here, and all three are honestly "skipped", not "failed".
            return log(operationId, id, null, "SKIPPED",
                    "Not found, already deleted, or not visible to you", null, null);
        }
        String before = String.valueOf(found.get(0).get("before_value"));
        String label = labelOf(object, id, tenant);

        if (java.util.Objects.equals(before, value)) {
            return log(operationId, id, label, "SKIPPED", "Already set to this value", before, value);
        }

        try {
            int updated = jdbc.update("update " + table + " set " + column + " = ?, updated_at = now(), "
                            + "updated_by = ?, version = version + 1 where tenant_id = ? and id = ? "
                            + "and deleted_at is null",
                    castFor(column, value), TenantContext.get().userId(), tenant, id);
            if (updated == 0) {
                return log(operationId, id, label, "SKIPPED", "The record changed before this could apply",
                        before, value);
            }
            audit.record("BULK_" + object + "_UPDATE", object, id,
                    "Bulk set " + field + " on " + label,
                    Map.of("field", field, "from", before, "to", String.valueOf(value),
                            "operationId", operationId.toString()));
            return log(operationId, id, label, "APPLIED", null, before, value);
        } catch (RuntimeException ex) {
            // NestedExceptionUtils, not ex.getMessage(): a DataIntegrityViolation
            // wraps the PSQLException that actually says which constraint failed,
            // and the outer message is boilerplate the operator cannot act on.
            Throwable root = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(ex);
            String detail = root.getMessage();
            return log(operationId, id, label, "FAILED", detail, before, value);
        }
    }

    /** account_id and owner_id are uuid columns; everything else is text. */
    private Object castFor(String column, String value) {
        if (value == null || value.isBlank()) return null;
        if (column.endsWith("_id")) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("\"" + value + "\" is not a valid record id for " + column);
            }
        }
        return value;
    }

    private String labelOf(String object, UUID id, UUID tenant) {
        String expression = switch (object) {
            case "CONTACT" -> "trim(coalesce(first_name,'') || ' ' || coalesce(last_name,''))";
            case "LEAD" -> "coalesce(company, '')";
            default -> "name";
        };
        List<String> found = jdbc.queryForList("select " + expression + " from " + TABLES.get(object)
                + " where tenant_id = ? and id = ?", String.class, tenant, id);
        return found.isEmpty() ? id.toString() : found.get(0);
    }

    private RowOutcome log(UUID operationId, UUID recordId, String label, String outcome,
                           String detail, String before, String after) {
        jdbc.update("""
                insert into crm.bulk_operation_row
                  (tenant_id, operation_id, record_id, record_label, outcome, detail, before_value, after_value)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, TenantContext.get().tenantId(), operationId, recordId, label, outcome, detail,
                before, after);
        return new RowOutcome(recordId, label, outcome, detail, before, after);
    }

    private UUID openOperation(String object, String operation, Map<String, Object> request,
                               String reason, int total) {
        return inOwnTransaction.execute((status) ->
                insertOperation(object, operation, request, reason, total));
    }

    private UUID insertOperation(String object, String operation, Map<String, Object> request,
                                 String reason, int total) {
        // Same reason as applyOneInTransaction: a new transaction begins with
        // app.tenant_id unset, and this table is under RLS.
        jdbc.query("select set_config('app.tenant_id', ?, true)", (rs) -> null,
                TenantContext.get().tenantId().toString());
        String encoded;
        try {
            encoded = json.writeValueAsString(request);
        } catch (Exception e) {
            encoded = "{}";
        }
        return jdbc.queryForObject("""
                insert into crm.bulk_operation
                  (tenant_id, object_type, operation, requested_by, request, reason, total, correlation_id)
                values (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                returning id
                """, UUID.class, TenantContext.get().tenantId(), object, operation,
                TenantContext.get().userId(), encoded,
                reason == null || reason.isBlank() ? null : reason.trim(), total,
                org.slf4j.MDC.get("correlationId"));
    }

    private BulkResult close(UUID operationId, String object, String operation,
                             List<RowOutcome> rows, String note) {
        int applied = (int) rows.stream().filter((row) -> "APPLIED".equals(row.outcome())).count();
        int failed = (int) rows.stream().filter((row) -> "FAILED".equals(row.outcome())).count();
        int skipped = rows.size() - applied - failed;
        final int appliedCount = applied;
        final int failedCount = failed;
        inOwnTransaction.execute((status) -> {
            jdbc.query("select set_config('app.tenant_id', ?, true)", (rs) -> null,
                    TenantContext.get().tenantId().toString());
            return jdbc.update(
                    "update crm.bulk_operation set succeeded = ?, failed = ? where tenant_id = ? and id = ?",
                    appliedCount, failedCount, TenantContext.get().tenantId(), operationId);
        });
        audit.record("BULK_OPERATION", object, operationId,
                operation + " across " + rows.size() + " record(s)",
                Map.of("total", rows.size(), "applied", applied, "failed", failed, "skipped", skipped));
        return new BulkResult(operationId, object, operation, rows.size(), applied, failed, skipped, rows, note);
    }

    // ------------------------------------------------------------------ guards

    private static String object(String objectType) {
        String normalized = objectType == null ? "" : objectType.trim().toUpperCase(Locale.ROOT);
        if (!TABLES.containsKey(normalized)) {
            throw new NotFoundException("Bulk operations are not available for \"" + objectType
                    + "\". Supported: " + String.join(", ", TABLES.keySet().stream().sorted().toList()));
        }
        return normalized;
    }

    private static String field(String object, String field) {
        String normalized = field == null ? "" : field.trim();
        Set<String> allowed = EDITABLE.getOrDefault(object, Set.of());
        if (!allowed.contains(normalized) || !COLUMNS.containsKey(normalized)) {
            throw new IllegalArgumentException("\"" + field + "\" cannot be mass-edited on " + object
                    + ". Fields that carry identity or provenance are excluded on purpose. Editable here: "
                    + String.join(", ", allowed.stream().sorted().toList()) + ".");
        }
        return normalized;
    }

    private static List<UUID> ids(List<UUID> recordIds) {
        List<UUID> distinct = recordIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) throw new IllegalArgumentException("Select at least one record");
        if (distinct.size() > MAX_ROWS) {
            throw new IllegalArgumentException("Bulk operations are limited to " + MAX_ROWS
                    + " records at a time; " + distinct.size() + " were selected. Narrow the selection "
                    + "with a column filter and run it in batches.");
        }
        return distinct;
    }

    private void assertActiveUser(UUID ownerId) {
        Long count = jdbc.queryForObject(
                "select count(*) from identity.app_user where tenant_id = ? and id = ? and active",
                Long.class, TenantContext.get().tenantId(), ownerId);
        if (count == null || count == 0) {
            throw new NotFoundException("That owner is not an active user in this workspace");
        }
    }

    /** The editable field list, so the UI offers exactly what the server accepts. */
    public Map<String, List<String>> editableFields() {
        return EDITABLE.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, (entry) -> entry.getValue().stream().sorted().toList()));
    }
}

package com.axiom.security;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sensitive-field masking (FR-SEC-008).
 *
 * <p>Masking is a <b>third state</b>, not a variant of denial. Read access shows a
 * partial value; the full value needs a separate permission; and every full reveal
 * writes a read-audit event. The model document's example is the right one to hold
 * in mind — for a counterparty account number or a client tax id, "can see it
 * exists and roughly which one it is" and "can see the actual digits" are
 * genuinely different authorization levels, and a product that only has
 * read/no-read forces administrators to pick the wrong one.
 *
 * <p><b>Why masked reads are not audited row-by-row.</b> A list of 100 accounts
 * would write 100 audit rows per page view, which drowns the events that matter.
 * The audit obligation in FR-SEC-008 is on the <em>reveal</em>, and that is what
 * is recorded — together with denied reveal attempts, which are the other event a
 * reviewer actually wants to find.
 */
@Service
public class SensitiveFieldService {

    public record SensitiveField(String objectType, String fieldName, String maskStyle,
                                 String revealPermission, String reason) {}

    private final JdbcTemplate jdbc;
    private final PermissionResolver resolver;
    private final AuthorizationService authorization;
    private final AuditService audit;

    public SensitiveFieldService(JdbcTemplate jdbc, PermissionResolver resolver,
                                 AuthorizationService authorization, AuditService audit) {
        this.jdbc = jdbc;
        this.resolver = resolver;
        this.authorization = authorization;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Map<String, SensitiveField> registry(UUID tenantId, SecurableObject object) {
        Map<String, SensitiveField> out = new LinkedHashMap<>();
        jdbc.query("""
                select object_type, field_name, mask_style, reveal_permission, reason
                from security.sensitive_field where tenant_id = ? and object_type = ?
                """, rs -> {
                    out.put(rs.getString("field_name"), new SensitiveField(
                            rs.getString("object_type"), rs.getString("field_name"),
                            rs.getString("mask_style"), rs.getString("reveal_permission"),
                            rs.getString("reason")));
                }, tenantId, object.name());
        return out;
    }

    @Transactional(readOnly = true)
    public java.util.List<SensitiveField> all() {
        return jdbc.query("""
                select object_type, field_name, mask_style, reveal_permission, reason
                from security.sensitive_field where tenant_id = ? order by object_type, field_name
                """, (rs, i) -> new SensitiveField(rs.getString("object_type"), rs.getString("field_name"),
                        rs.getString("mask_style"), rs.getString("reveal_permission"), rs.getString("reason")),
                com.axiom.tenancy.TenantContext.get().tenantId());
    }

    /**
     * Partial reveal. The shape of the mask matters: LAST4 on a tax id leaves
     * enough to confirm you are looking at the right record without leaving enough
     * to use it elsewhere, which is the entire point of a partial value.
     */
    public String mask(String maskStyle, String value) {
        if (value == null || value.isBlank()) return value;
        return switch (maskStyle) {
            case "LAST4" -> value.length() <= 4
                    ? "••••"
                    : "••••" + value.substring(value.length() - 4);
            case "FIRST_LAST" -> value.length() <= 2
                    ? "••••"
                    : value.charAt(0) + "••••" + value.charAt(value.length() - 1);
            case "EMAIL" -> maskEmail(value);
            default -> "••••";
        };
    }

    private String maskEmail(String value) {
        int at = value.indexOf('@');
        if (at <= 0) return "••••";
        String local = value.substring(0, at);
        return local.charAt(0) + "••••" + value.substring(at);
    }

    public boolean canReveal(AccessContext ctx, SecurableObject object, SensitiveField field) {
        return ctx.access(object).canReveal() || ctx.holds(field.revealPermission());
    }

    public record RevealResult(String objectType, UUID recordId, String fieldName,
                               String value, boolean revealed, String auditNote) {}

    /**
     * Full reveal of one sensitive field on one record.
     *
     * <p>Record access is rechecked here rather than assumed from the caller having
     * just listed the record: a reveal is a separate request and the share that
     * made the record visible may have expired between them (FR-SEC-012).
     */
    @Transactional
    public RevealResult reveal(String objectType, UUID recordId, String fieldName) {
        SecurableObject object = SecurableObject.of(objectType);
        AccessContext ctx = resolver.current();
        SensitiveField field = registry(ctx.tenantId(), object).get(fieldName);
        if (field == null) {
            throw new NotFoundException("Field '" + fieldName + "' is not registered as a sensitive field of "
                    + object.name());
        }
        authorization.requireRead(object, recordId);

        if (!canReveal(ctx, object, field)) {
            writeReadAudit(ctx, object, recordId, fieldName, "DENIED");
            audit.record("SENSITIVE_FIELD_REVEAL_DENIED", object.name(), recordId,
                    "Reveal of " + fieldName + " refused",
                    Map.of("field", fieldName, "requiredPermission", field.revealPermission(),
                            "profile", ctx.profileCode()));
            throw new ForbiddenException("Revealing " + fieldName + " requires "
                    + field.revealPermission() + ". You can see the masked value; ask an administrator "
                    + "for a permission set that grants the reveal right.");
        }

        String value = jdbc.query("select " + object.column(fieldName) + " from " + object.qualifiedTable()
                        + " where tenant_id = ? and id = ?",
                rs -> rs.next() ? rs.getString(1) : null, ctx.tenantId(), recordId);

        writeReadAudit(ctx, object, recordId, fieldName, "REVEAL");
        audit.record("SENSITIVE_FIELD_REVEALED", object.name(), recordId,
                "Revealed " + fieldName,
                Map.of("field", fieldName, "maskStyle", field.maskStyle(), "profile", ctx.profileCode()));
        return new RevealResult(object.name(), recordId, fieldName, value, true,
                "This reveal was recorded in the field read audit.");
    }

    void writeReadAudit(AccessContext ctx, SecurableObject object, UUID recordId,
                        String fieldName, String kind) {
        jdbc.update("""
                insert into security.field_read_audit
                  (tenant_id, actor_id, actor_email, object_type, record_id, field_name,
                   access_kind, correlation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, ctx.tenantId(), ctx.userId(),
                com.axiom.tenancy.TenantContext.get().email(), object.name(), recordId, fieldName,
                kind, MDC.get("correlationId"));
    }

    public record ReadAuditRow(UUID id, String actorEmail, String objectType, UUID recordId,
                               String fieldName, String accessKind, java.time.Instant occurredAt) {}

    @Transactional(readOnly = true)
    public java.util.List<ReadAuditRow> readAudit(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return jdbc.query("""
                select id, actor_email, object_type, record_id, field_name, access_kind, occurred_at
                from security.field_read_audit where tenant_id = ?
                order by occurred_at desc limit ?
                """, (rs, i) -> new ReadAuditRow(rs.getObject("id", UUID.class),
                        rs.getString("actor_email"), rs.getString("object_type"),
                        rs.getObject("record_id", UUID.class), rs.getString("field_name"),
                        rs.getString("access_kind"), rs.getTimestamp("occurred_at").toInstant()),
                com.axiom.tenancy.TenantContext.get().tenantId(), safe);
    }
}

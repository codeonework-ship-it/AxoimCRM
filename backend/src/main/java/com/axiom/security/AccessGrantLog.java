package com.axiom.security;

import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only history of every grant and revocation — the {@code ACCESS_GRANT_LOG}
 * of data model §3.3.
 *
 * <p>It duplicates some of what {@code governance.audit_event} records, and that is
 * deliberate. A compliance reviewer asking "what access did this person have in
 * March, and who approved it" should not have to filter the whole audit stream by
 * guessing action names. The two stores answer different questions: the audit event
 * is the narrative of what happened, this is the ledger of who could do what. The
 * grant path writes both.
 *
 * <p>{@code axiom_app} holds only SELECT and INSERT on this table (see V13). An
 * append-only table the application can update is not append-only.
 */
@Service
public class AccessGrantLog {

    /** Kept as constants because they are matched by the review and reporting queries. */
    public static final String GRANT = "GRANT";
    public static final String REVOKE = "REVOKE";
    public static final String MODIFY = "MODIFY";
    public static final String EXPIRE = "EXPIRE";
    public static final String AUTO_REVOKE = "AUTO_REVOKE";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AccessGrantLog(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void write(UUID subjectUserId, String grantType, UUID grantRef, String operation,
                      Map<String, ?> before, Map<String, ?> after, UUID approverId,
                      String approverEmail, String reason) {
        TenantContext.Principal p = TenantContext.get();
        jdbc.update("""
                insert into security.access_grant_log
                  (tenant_id, subject_user_id, grant_type, grant_ref, operation,
                   before_state, after_state, actor_id, actor_email,
                   approver_id, approver_email, reason, correlation_id)
                values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """, p.tenantId(), subjectUserId, grantType, grantRef, operation,
                write(before), write(after), p.userId(), p.email(),
                approverId, approverEmail, reason, MDC.get("correlationId"));
    }

    /**
     * Variant used by the scheduled sweeps, which have no interactive principal but
     * still must attribute the change. The tenant and a system actor are supplied
     * explicitly rather than read from ambient context.
     */
    @Transactional
    public void writeSystem(UUID tenantId, UUID subjectUserId, String grantType, UUID grantRef,
                            String operation, Map<String, ?> before, Map<String, ?> after,
                            String reason) {
        jdbc.update("""
                insert into security.access_grant_log
                  (tenant_id, subject_user_id, grant_type, grant_ref, operation,
                   before_state, after_state, actor_id, actor_email, reason, correlation_id)
                values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                """, tenantId, subjectUserId, grantType, grantRef, operation,
                write(before), write(after), subjectUserId == null ? tenantId : subjectUserId,
                "system@axiom", reason, MDC.get("correlationId"));
    }

    private String write(Map<String, ?> value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Access grant log state could not be serialized", ex);
        }
    }

    public record Entry(UUID id, UUID subjectUserId, String grantType, UUID grantRef, String operation,
                        String beforeState, String afterState, String actorEmail,
                        String approverEmail, String reason, Instant occurredAt) {}

    @Transactional(readOnly = true)
    public List<Entry> history(UUID subjectUserId, int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return jdbc.query("""
                select id, subject_user_id, grant_type, grant_ref, operation,
                       before_state::text, after_state::text, actor_email, approver_email,
                       reason, occurred_at
                from security.access_grant_log
                where tenant_id = ? and (? is null or subject_user_id = ?)
                order by occurred_at desc limit ?
                """, (rs, i) -> new Entry(rs.getObject("id", UUID.class),
                        rs.getObject("subject_user_id", UUID.class), rs.getString("grant_type"),
                        rs.getObject("grant_ref", UUID.class), rs.getString("operation"),
                        rs.getString("before_state"), rs.getString("after_state"),
                        rs.getString("actor_email"), rs.getString("approver_email"),
                        rs.getString("reason"), rs.getTimestamp("occurred_at").toInstant()),
                TenantContext.get().tenantId(), subjectUserId, subjectUserId, safe);
    }
}

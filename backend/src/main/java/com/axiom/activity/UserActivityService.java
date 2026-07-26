package com.axiom.activity;

import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * User-activity capture and query (FR-AUD-014, and the "all user activities are
 * tracked" requirement).
 *
 * <h2>Why this is not governance.audit_event</h2>
 * {@code AuditService} already records business <i>changes</i>: hash-chained,
 * append-only, one per-tenant sequence. Two things make it the wrong home for
 * request-level activity. First, a denied request changes nothing, so it
 * produces no audit event at all — and a refused permission check is precisely
 * the event a security review wants. Second, the chain assigns a sequence under
 * a per-tenant advisory lock; one row per HTTP request would serialise the whole
 * tenant behind it.
 *
 * <p>So this is the access log <i>beside</i> the audit log. It calls
 * {@code AuditService} for nothing and duplicates none of its rows.
 *
 * <h2>FR-AUD-014 — the allowlist</h2>
 * "Logs and metrics must never contain credentials, tokens or unmasked personal
 * data." That is enforced by construction rather than by review:
 *
 * <ul>
 *   <li>There is no request-body column and no header column, so a bearer token
 *       or a password has nowhere to land even by accident.</li>
 *   <li>{@code request_path} is stored without its query string. Query strings
 *       carry filter values and, in this product, email addresses.</li>
 *   <li>{@link #sanitise} keeps only {@link #ALLOWED_DETAIL_KEYS}. Anything else
 *       a caller passes is dropped here, and a database trigger rejects it
 *       outright if some other code path ever writes the table directly.</li>
 * </ul>
 *
 * <p>The actor's own email <i>is</i> recorded. That is not a leak; it is the
 * subject of the log. What must not appear is the personal data of the people in
 * the records the actor touched, and the shape above makes that unreachable.
 *
 * <h2>The pending-denial handoff</h2>
 * A service that refuses something knows <i>why</i>; the servlet filter that
 * sees the resulting 403 does not. {@link #markDenied} lets the service leave
 * the reason on the request thread, and {@link UserActivityFilter} folds it into
 * the single row for that request. Without it we would either lose the reason or
 * write two rows for one refusal.
 */
@Service
public class UserActivityService {

    private static final Logger log = LoggerFactory.getLogger(UserActivityService.class);

    /**
     * The only keys permitted in {@code user_activity.detail}. Mirrors
     * {@code activity.detail_allowlist} in V310 — the database is the backstop,
     * this is the gate. Adding a key means changing both, which is the point:
     * it puts a reviewable change between convenience and a new class of value
     * entering the log.
     */
    public static final Set<String> ALLOWED_DETAIL_KEYS = Set.of(
            "objectType", "objectId", "permission", "denialReason", "accessLevel",
            "cause", "ruleCode", "roleCode", "targetUserId", "targetRole",
            "durationMs", "resultCount", "constraintName");

    /** Outcome vocabulary, matching the CHECK constraint on the table. */
    public static final String SUCCESS = "SUCCESS";
    public static final String DENIED = "DENIED";
    public static final String ERROR = "ERROR";

    private static final ThreadLocal<PendingDenial> PENDING = new ThreadLocal<>();

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public UserActivityService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // -----------------------------------------------------------------------
    // Types
    // -----------------------------------------------------------------------

    /** A refusal recorded by a service, waiting to be folded into the request's row. */
    public record PendingDenial(String reason, String objectType, UUID objectId) {}

    /** One captured activity. {@code detail} has already passed the allowlist. */
    public record ActivityEvent(
            UUID tenantId,
            UUID actorId,
            String actorEmail,
            String actorRole,
            UUID impersonatorId,
            String impersonatorEmail,
            String action,
            String httpMethod,
            String requestPath,
            String objectType,
            UUID objectId,
            String source,
            String outcome,
            Integer statusCode,
            String denialReason,
            String correlationId,
            String clientIp,
            String userAgent,
            Map<String, ?> detail) {}

    /** One row as the admin screen reads it. */
    public record ActivityRow(
            UUID id,
            UUID actorId,
            String actorEmail,
            String actorRole,
            String impersonatorEmail,
            String action,
            String httpMethod,
            String requestPath,
            String objectType,
            UUID objectId,
            String source,
            String outcome,
            Integer statusCode,
            String denialReason,
            String correlationId,
            String clientIp,
            String userAgent,
            Map<String, Object> detail,
            Instant occurredAt) {}

    /** Counts for the filter bar, so an empty result reads as "none" not "broken". */
    public record ActivitySummary(long total, long denied, long errors, long distinctActors) {}

    /** One user's timeline plus the counts that frame it. */
    public record UserTimeline(UUID userId, String email, String displayName, String crmRole,
                               boolean active, ActivitySummary summary, List<ActivityRow> events) {}

    /** Filter for the admin screen. Every field is optional. */
    public record ActivityQuery(UUID actorId, String action, String objectType, String outcome,
                                OffsetDateTime from, OffsetDateTime to, Integer limit) {}

    // -----------------------------------------------------------------------
    // Capture
    // -----------------------------------------------------------------------

    /**
     * Leave a refusal reason on this request thread for the filter to pick up.
     * Called by a service at the moment it decides to refuse, because that is
     * the only place the reason exists.
     */
    public void markDenied(String reason, String objectType, UUID objectId) {
        PENDING.set(new PendingDenial(reason, objectType, objectId));
    }

    /** Read and clear the pending denial. Null when the request was not refused. */
    public PendingDenial takePendingDenial() {
        PendingDenial pending = PENDING.get();
        PENDING.remove();
        return pending;
    }

    public void clearPendingDenial() {
        PENDING.remove();
    }

    /**
     * Write one activity row.
     *
     * <p>REQUIRES_NEW: the capture must survive the rollback of whatever it is
     * describing. A denial that rolls back the caller's transaction would
     * otherwise erase the record of the denial — the one row a security review
     * most needs.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ActivityEvent event) {
        if (event.tenantId() == null) {
            // No verified tenant means no RLS binding, so the row could not be
            // read back by anyone anyway. Unauthenticated failures are already
            // covered by identity.login_attempt.
            return;
        }
        Map<String, Object> detail = sanitise(event.detail());
        String payload;
        try {
            payload = json.writeValueAsString(detail);
        } catch (Exception e) {
            payload = "{}";
        }
        jdbc.update("""
                insert into activity.user_activity(
                    tenant_id, actor_id, actor_email, actor_role,
                    impersonator_id, impersonator_email,
                    action, http_method, request_path,
                    object_type, object_id, source, outcome, status_code, denial_reason,
                    correlation_id, client_ip, user_agent, detail)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, cast(? as jsonb))
                """,
                event.tenantId(), event.actorId(), event.actorEmail(), event.actorRole(),
                event.impersonatorId(), event.impersonatorEmail(),
                event.action(), event.httpMethod(), stripQuery(event.requestPath()),
                event.objectType(), event.objectId(),
                event.source() == null ? "API" : event.source(),
                event.outcome(), event.statusCode(), truncate(event.denialReason(), 1000),
                event.correlationId(), event.clientIp(), truncate(event.userAgent(), 500),
                payload);
    }

    /**
     * Drop every key that is not allowlisted, and every value that is not a
     * scalar. A nested object is a container, and containers are how personal
     * data arrives somewhere nobody looked.
     */
    public Map<String, Object> sanitise(Map<String, ?> detail) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (detail == null) return out;
        detail.forEach((key, value) -> {
            if (!ALLOWED_DETAIL_KEYS.contains(key)) return;
            if (value == null) return;
            if (value instanceof Map || value instanceof Iterable || value.getClass().isArray()) return;
            out.put(key, value instanceof Number || value instanceof Boolean ? value
                    : truncate(String.valueOf(value), 500));
        });
        return out;
    }

    private static String stripQuery(String path) {
        if (path == null) return null;
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    // -----------------------------------------------------------------------
    // Query
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ActivityRow> search(ActivityQuery query) {
        ActivityAccess.requireRead();
        StringBuilder sql = new StringBuilder("""
                select id, actor_id, actor_email, actor_role, impersonator_email,
                       action, http_method, request_path, object_type, object_id,
                       source, outcome, status_code, denial_reason,
                       correlation_id, client_ip, user_agent, detail, occurred_at
                  from activity.user_activity
                 where tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        if (query.actorId() != null) { sql.append(" and actor_id = ?"); args.add(query.actorId()); }
        if (notBlank(query.action())) { sql.append(" and action ilike ?"); args.add("%" + query.action().trim() + "%"); }
        if (notBlank(query.objectType())) { sql.append(" and object_type = ?"); args.add(query.objectType().trim().toUpperCase()); }
        if (notBlank(query.outcome())) { sql.append(" and outcome = ?"); args.add(query.outcome().trim().toUpperCase()); }
        if (query.from() != null) { sql.append(" and occurred_at >= ?"); args.add(query.from()); }
        if (query.to() != null) { sql.append(" and occurred_at <= ?"); args.add(query.to()); }
        sql.append(" order by occurred_at desc limit ?");
        args.add(clamp(query.limit(), 200, 1000));
        return jdbc.query(sql.toString(), this::mapRow, args.toArray());
    }

    @Transactional(readOnly = true)
    public ActivitySummary summary(ActivityQuery query) {
        ActivityAccess.requireRead();
        StringBuilder sql = new StringBuilder("""
                select count(*) as total,
                       count(*) filter (where outcome = 'DENIED') as denied,
                       count(*) filter (where outcome = 'ERROR') as errors,
                       count(distinct actor_id) as actors
                  from activity.user_activity
                 where tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        if (query.actorId() != null) { sql.append(" and actor_id = ?"); args.add(query.actorId()); }
        if (notBlank(query.action())) { sql.append(" and action ilike ?"); args.add("%" + query.action().trim() + "%"); }
        if (notBlank(query.objectType())) { sql.append(" and object_type = ?"); args.add(query.objectType().trim().toUpperCase()); }
        if (notBlank(query.outcome())) { sql.append(" and outcome = ?"); args.add(query.outcome().trim().toUpperCase()); }
        if (query.from() != null) { sql.append(" and occurred_at >= ?"); args.add(query.from()); }
        if (query.to() != null) { sql.append(" and occurred_at <= ?"); args.add(query.to()); }
        return jdbc.queryForObject(sql.toString(), (rs, i) -> new ActivitySummary(
                rs.getLong("total"), rs.getLong("denied"), rs.getLong("errors"), rs.getLong("actors")),
                args.toArray());
    }

    /** One user's story, which is the shape a reviewer actually asks for. */
    @Transactional(readOnly = true)
    public UserTimeline timeline(UUID userId, int limit) {
        ActivityAccess.requireRead();
        UUID tenantId = TenantContext.get().tenantId();
        List<UserTimeline> found = jdbc.query("""
                select u.id, u.email, u.display_name, u.role, u.active
                  from identity.app_user u
                 where u.tenant_id = ? and u.id = ?
                """, (rs, i) -> new UserTimeline(
                rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                rs.getString("role"), rs.getBoolean("active"),
                new ActivitySummary(0, 0, 0, 0), List.of()), tenantId, userId);
        if (found.isEmpty()) {
            throw new com.axiom.common.NotFoundException("No such user in this workspace");
        }
        UserTimeline user = found.get(0);
        ActivityQuery scoped = new ActivityQuery(userId, null, null, null, null, null, limit);
        return new UserTimeline(user.userId(), user.email(), user.displayName(), user.crmRole(),
                user.active(), summary(scoped), search(scoped));
    }

    /** Distinct actions seen in this tenant, so the filter offers real values. */
    @Transactional(readOnly = true)
    public List<String> knownActions() {
        ActivityAccess.requireRead();
        return jdbc.queryForList("""
                select distinct action from activity.user_activity
                 where tenant_id = ? order by action limit 500
                """, String.class, TenantContext.get().tenantId());
    }

    /** The allowlist itself, so the screen can state the FR-AUD-014 guarantee honestly. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> detailAllowlist() {
        ActivityAccess.requireRead();
        return jdbc.query("select detail_key, rationale from activity.detail_allowlist order by detail_key",
                (rs, i) -> Map.<String, Object>of(
                        "detailKey", rs.getString("detail_key"),
                        "rationale", rs.getString("rationale")));
    }

    private ActivityRow mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Map<String, Object> detail = Map.of();
        String raw = rs.getString("detail");
        if (raw != null && !raw.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = json.readValue(raw, Map.class);
                // Belt and braces: a row written before an allowlist change is
                // re-filtered on the way out, so the guarantee holds for reads
                // of historic rows too.
                detail = sanitise(parsed);
            } catch (Exception e) {
                log.debug("Unreadable activity detail on row {}", rs.getObject("id"), e);
            }
        }
        Object occurred = rs.getObject("occurred_at", OffsetDateTime.class);
        return new ActivityRow(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_email"),
                rs.getString("actor_role"),
                rs.getString("impersonator_email"),
                rs.getString("action"),
                rs.getString("http_method"),
                rs.getString("request_path"),
                rs.getString("object_type"),
                rs.getObject("object_id", UUID.class),
                rs.getString("source"),
                rs.getString("outcome"),
                (Integer) rs.getObject("status_code"),
                rs.getString("denial_reason"),
                rs.getString("correlation_id"),
                rs.getString("client_ip"),
                rs.getString("user_agent"),
                detail,
                occurred == null ? null : ((OffsetDateTime) occurred).toInstant());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static int clamp(Integer requested, int fallback, int max) {
        if (requested == null) return fallback;
        return Math.max(1, Math.min(requested, max));
    }

    /**
     * Who may read the activity log. Deliberately wider than who may change
     * security configuration — an auditor exists to read this and nothing else,
     * and a log only the administrator being reviewed can read is not a control.
     */
    public static final class ActivityAccess {
        private static final Set<String> READ = Set.of(
                "SUPER_ADMIN", "SUPER_AUDIT", "TENANT_ADMIN", "AUDITOR", "OPERATIONS");

        private ActivityAccess() {}

        public static void requireRead() {
            String role = TenantContext.get().role();
            if (!READ.contains(role)) {
                throw new ForbiddenException("Reading the user activity log requires an administrator, "
                        + "auditor or operations role.");
            }
        }
    }
}

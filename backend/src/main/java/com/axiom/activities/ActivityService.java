package com.axiom.activities;

import com.axiom.api.PageResult;
import com.axiom.api.QueryService;
import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.notifications.NotificationWriter;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ActivityService {
    private static final List<String> TYPES = List.of("TASK", "EVENT", "CALL", "EMAIL_LOG", "NOTE");
    private static final List<String> STATUSES = List.of("OPEN", "COMPLETED", "CANCELLED");
    private static final List<String> PRIORITIES = List.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final List<String> RELATED_TYPES = List.of("ACCOUNT", "CONTACT", "LEAD", "OPPORTUNITY");
    private static final List<String> DIRECTIONS = List.of("INBOUND", "OUTBOUND");
    private static final List<String> READ_ONLY_ROLES = List.of("SUPER_AUDIT", "AUDITOR", "INTEGRATION");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final NotificationWriter notifications;

    public ActivityService(JdbcTemplate jdbc, AuditService audit, OutboxWriter outbox, NotificationWriter notifications) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
        this.notifications = notifications;
    }

    public record ActivityRow(UUID id, String activityType, String subject, String body, String status,
                              String priority, String relatedEntityType, UUID relatedEntityId,
                              String relatedLabel, UUID ownerId, String ownerName, OffsetDateTime dueAt,
                              OffsetDateTime reminderAt, OffsetDateTime occurredAt, OffsetDateTime completedAt,
                              String outcome, String direction, Integer durationMinutes, String disposition,
                              long participantCount) {}

    public record ActivitySummary(long openCount, long overdueCount, long completedLast7Days,
                                  OffsetDateTime lastContactedAt, Long daysSinceLastActivity) {}

    public record ActivityRequest(@NotBlank String activityType, @NotBlank String subject, String body,
                                  String priority, @NotBlank String relatedEntityType,
                                  @NotNull UUID relatedEntityId, UUID ownerId,
                                  OffsetDateTime dueAt, OffsetDateTime reminderAt,
                                  OffsetDateTime occurredAt, String direction,
                                  Integer durationMinutes, String disposition) {}

    public record CompleteRequest(String outcome) {}

    @Transactional(readOnly = true)
    public PageResult<ActivityRow> list(String search, String type, String status,
                                        String relatedEntityType, UUID relatedEntityId, int page) {
        int safePage = Math.max(0, page);
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        String where = where(search, type, status, relatedEntityType, relatedEntityId, args);
        Long total = jdbc.queryForObject("select count(*) from engagement.activity a " + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(QueryService.PAGE_SIZE);
        pageArgs.add(safePage * QueryService.PAGE_SIZE);
        List<ActivityRow> rows = jdbc.query("""
                select a.id, a.activity_type, a.subject, a.body, a.status, a.priority,
                       a.related_entity_type, a.related_entity_id,
                       case a.related_entity_type
                         when 'ACCOUNT' then (select name from crm.account r where r.tenant_id = a.tenant_id and r.id = a.related_entity_id)
                         when 'CONTACT' then (select first_name || ' ' || last_name from crm.contact r where r.tenant_id = a.tenant_id and r.id = a.related_entity_id)
                         when 'LEAD' then (select first_name || ' ' || last_name from crm.lead r where r.tenant_id = a.tenant_id and r.id = a.related_entity_id)
                         when 'OPPORTUNITY' then (select name from sales.opportunity r where r.tenant_id = a.tenant_id and r.id = a.related_entity_id)
                       end as related_label,
                       a.owner_id, u.display_name as owner_name, a.due_at, a.reminder_at,
                       a.occurred_at, a.completed_at, a.outcome, a.direction,
                       a.duration_minutes, a.disposition,
                       (select count(*) from engagement.activity_participant p
                        where p.tenant_id = a.tenant_id and p.activity_id = a.id) as participant_count
                from engagement.activity a
                join identity.app_user u on u.tenant_id = a.tenant_id and u.id = a.owner_id
                """ + where + "\n" + """
                order by coalesce(a.due_at, a.occurred_at) desc, a.created_at desc
                limit ? offset ?
                """, this::mapRow, pageArgs.toArray());
        return PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public ActivitySummary summary() {
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) filter (where status = 'OPEN') as open_count,
                       count(*) filter (where status = 'OPEN' and due_at < now()) as overdue_count,
                       count(*) filter (where status = 'COMPLETED' and completed_at >= now() - interval '7 days') as completed_7,
                       max(occurred_at) filter (where activity_type in ('CALL','EMAIL_LOG','EVENT')) as last_contacted
                from engagement.activity
                where tenant_id = ? and deleted_at is null
                """, TenantContext.get().tenantId());
        OffsetDateTime last = toOffsetDateTime(row.get("last_contacted"));
        Long days = last == null ? null : ChronoUnit.DAYS.between(last, OffsetDateTime.now());
        return new ActivitySummary(
                ((Number) row.get("open_count")).longValue(),
                ((Number) row.get("overdue_count")).longValue(),
                ((Number) row.get("completed_7")).longValue(),
                last,
                days
        );
    }

    @Transactional
    public ActivityRow create(ActivityRequest request) {
        requireWrite();
        String type = normalize(request.activityType(), TYPES, "activity type");
        String relatedType = normalize(request.relatedEntityType(), RELATED_TYPES, "related entity type");
        String priority = normalize(defaulted(request.priority(), "NORMAL"), PRIORITIES, "priority");
        String direction = clean(request.direction()) == null ? null : normalize(request.direction(), DIRECTIONS, "call direction");
        String subject = clean(request.subject());
        if (subject == null) throw new ConflictException("Activity subject is required");
        if ("TASK".equals(type) && request.dueAt() == null) throw new ConflictException("Tasks require a due date");
        if ("CALL".equals(type)) {
            if (direction == null) throw new ConflictException("Calls require INBOUND or OUTBOUND direction");
            if (request.durationMinutes() == null || request.durationMinutes() < 0) throw new ConflictException("Calls require a non-negative duration");
            if (clean(request.disposition()) == null) throw new ConflictException("Calls require a disposition");
        }
        ensureRelatedExists(relatedType, request.relatedEntityId());
        UUID ownerId = resolveOwner(request.ownerId());
        UUID id = UUID.randomUUID();
        OffsetDateTime occurredAt = request.occurredAt() == null ? OffsetDateTime.now() : request.occurredAt();
        String initialStatus = List.of("CALL", "EMAIL_LOG", "NOTE").contains(type) ? "COMPLETED" : "OPEN";
        OffsetDateTime completedAt = "COMPLETED".equals(initialStatus) ? OffsetDateTime.now() : null;
        jdbc.update("""
                insert into engagement.activity
                  (id, tenant_id, activity_type, subject, body, status, priority,
                   related_entity_type, related_entity_id, owner_id, created_by,
                   due_at, reminder_at, occurred_at, completed_at, direction,
                   duration_minutes, disposition)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, TenantContext.get().tenantId(), type, subject, clean(request.body()),
                initialStatus, priority, relatedType, request.relatedEntityId(), ownerId,
                TenantContext.get().userId(), request.dueAt(), request.reminderAt(), occurredAt,
                completedAt, direction, request.durationMinutes(), clean(request.disposition()));

        audit.record("CREATE", "ACTIVITY", id, "Created " + type.toLowerCase(Locale.ROOT).replace('_', ' ') + " activity",
                Map.of("type", type, "subject", subject, "relatedEntityType", relatedType));
        outbox.write("activity", id, "activity.created", Map.of(
                "activityId", id.toString(), "type", type, "relatedEntityType", relatedType,
                "relatedEntityId", request.relatedEntityId().toString()));
        if ("TASK".equals(type) && request.reminderAt() != null) {
            notifications.notifyCurrentUser("ACTION", priority, "Activity reminder configured", subject,
                    "/activities", "You created a task with a reminder.", true);
        }
        return one(id);
    }

    @Transactional
    public ActivityRow complete(UUID id, CompleteRequest request) {
        requireWrite();
        int updated = jdbc.update("""
                update engagement.activity
                set status = 'COMPLETED', completed_at = now(), outcome = ?, updated_at = now()
                where tenant_id = ? and id = ? and deleted_at is null and status <> 'COMPLETED'
                """, clean(request.outcome()), TenantContext.get().tenantId(), id);
        if (updated == 0) throw new NotFoundException("Open activity not found");
        audit.record("COMPLETE", "ACTIVITY", id, "Completed activity", Map.of("outcome", defaulted(request.outcome(), "")));
        outbox.write("activity", id, "activity.completed", Map.of("activityId", id.toString()));
        return one(id);
    }

    private ActivityRow one(UUID id) {
        List<ActivityRow> rows = jdbc.query("""
                select a.id, a.activity_type, a.subject, a.body, a.status, a.priority,
                       a.related_entity_type, a.related_entity_id,
                       case a.related_entity_type
                         when 'ACCOUNT' then (select name from crm.account r where r.tenant_id = a.tenant_id and r.id = a.related_entity_id)
                         when 'CONTACT' then (select first_name || ' ' || last_name from crm.contact r where r.tenant_id = a.tenant_id and r.id = a.related_entity_id)
                         when 'LEAD' then (select first_name || ' ' || last_name from crm.lead r where r.tenant_id = a.tenant_id and r.id = a.related_entity_id)
                         when 'OPPORTUNITY' then (select name from sales.opportunity r where r.tenant_id = a.tenant_id and r.id = a.related_entity_id)
                       end as related_label,
                       a.owner_id, u.display_name as owner_name, a.due_at, a.reminder_at,
                       a.occurred_at, a.completed_at, a.outcome, a.direction,
                       a.duration_minutes, a.disposition,
                       (select count(*) from engagement.activity_participant p
                        where p.tenant_id = a.tenant_id and p.activity_id = a.id) as participant_count
                from engagement.activity a
                join identity.app_user u on u.tenant_id = a.tenant_id and u.id = a.owner_id
                where a.tenant_id = ? and a.id = ? and a.deleted_at is null
                """, this::mapRow, TenantContext.get().tenantId(), id);
        return rows.stream().findFirst().orElseThrow(() -> new NotFoundException("Activity not found"));
    }

    private ActivityRow mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ActivityRow(
                rs.getObject("id", UUID.class),
                rs.getString("activity_type"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getString("status"),
                rs.getString("priority"),
                rs.getString("related_entity_type"),
                rs.getObject("related_entity_id", UUID.class),
                rs.getString("related_label"),
                rs.getObject("owner_id", UUID.class),
                rs.getString("owner_name"),
                rs.getObject("due_at", OffsetDateTime.class),
                rs.getObject("reminder_at", OffsetDateTime.class),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getString("outcome"),
                rs.getString("direction"),
                (Integer) rs.getObject("duration_minutes"),
                rs.getString("disposition"),
                rs.getLong("participant_count")
        );
    }

    private String where(String search, String type, String status, String relatedEntityType, UUID relatedEntityId, List<Object> args) {
        StringBuilder where = new StringBuilder(" where a.tenant_id = ? and a.deleted_at is null");
        String q = clean(search);
        if (q != null) {
            where.append(" and (lower(a.subject) like ? or lower(coalesce(a.body,'')) like ? or lower(coalesce(a.outcome,'')) like ?)");
            String pattern = "%" + q.toLowerCase(Locale.ROOT) + "%";
            args.add(pattern); args.add(pattern); args.add(pattern);
        }
        String t = clean(type);
        if (t != null) { where.append(" and a.activity_type = ?"); args.add(normalize(t, TYPES, "activity type")); }
        String s = clean(status);
        if (s != null) { where.append(" and a.status = ?"); args.add(normalize(s, STATUSES, "status")); }
        String rt = clean(relatedEntityType);
        if (rt != null) { where.append(" and a.related_entity_type = ?"); args.add(normalize(rt, RELATED_TYPES, "related entity type")); }
        if (relatedEntityId != null) { where.append(" and a.related_entity_id = ?"); args.add(relatedEntityId); }
        return where.toString();
    }

    private void requireWrite() {
        if (READ_ONLY_ROLES.contains(TenantContext.get().role())) {
            throw new ForbiddenException("Your role cannot create or complete activities");
        }
    }

    private void ensureRelatedExists(String relatedType, UUID id) {
        String table = switch (relatedType) {
            case "ACCOUNT" -> "crm.account";
            case "CONTACT" -> "crm.contact";
            case "LEAD" -> "crm.lead";
            case "OPPORTUNITY" -> "sales.opportunity";
            default -> throw new ConflictException("Unsupported related entity type");
        };
        Integer count = jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ? and id = ?",
                Integer.class, TenantContext.get().tenantId(), id);
        if (count == null || count == 0) throw new NotFoundException("Related " + relatedType.toLowerCase(Locale.ROOT) + " record not found");
    }

    private UUID resolveOwner(UUID requested) {
        UUID tenantId = TenantContext.get().tenantId();
        UUID owner = requested == null ? TenantContext.get().userId() : requested;
        Integer count = jdbc.queryForObject("""
                select count(*) from identity.app_user
                where tenant_id = ? and id = ? and active = true
                """, Integer.class, tenantId, owner);
        if (count != null && count > 0) return owner;
        List<UUID> fallback = jdbc.query("""
                select id from identity.app_user
                where tenant_id = ? and active = true
                order by case when role = 'TENANT_ADMIN' then 0 else 1 end, display_name
                limit 1
                """, (rs, i) -> rs.getObject("id", UUID.class), tenantId);
        if (fallback.isEmpty()) throw new ConflictException("No active tenant user can own the activity");
        return fallback.getFirst();
    }

    private String normalize(String value, List<String> allowed, String label) {
        String cleaned = clean(value);
        if (cleaned == null) throw new ConflictException("Missing " + label);
        String upper = cleaned.toUpperCase(Locale.ROOT);
        if (!allowed.contains(upper)) throw new ConflictException("Unsupported " + label + ": " + value);
        return upper;
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String defaulted(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned == null ? fallback : cleaned;
    }

    private OffsetDateTime toOffsetDateTime(Object value) {
        return switch (value) {
            case null -> null;
            case OffsetDateTime dateTime -> dateTime;
            case Timestamp timestamp -> timestamp.toInstant().atOffset(ZoneOffset.UTC);
            case LocalDateTime localDateTime -> localDateTime.atOffset(ZoneOffset.UTC);
            default -> throw new IllegalStateException("Unsupported timestamp type: " + value.getClass().getName());
        };
    }
}

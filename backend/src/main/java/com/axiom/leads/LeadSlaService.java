package com.axiom.leads;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.notifications.NotificationWriter;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The speed-to-lead response clock (FR-LED-009).
 *
 * <p>Three properties this class exists to guarantee:
 *
 * <ol>
 *   <li>The clock starts <b>on assignment</b>, not on creation. An unassigned
 *       lead has nobody to hold to a deadline.</li>
 *   <li>It <b>pauses outside business hours</b>, which is why the deadline is
 *       computed by walking a working calendar rather than adding minutes.</li>
 *   <li>{@code first_response_due_at} is <b>computed once and stored</b>. Editing
 *       business hours tomorrow must not move a deadline given today — otherwise
 *       last week's breach report changes every time an administrator adds a
 *       holiday, and nobody can be held to a target that moves.</li>
 * </ol>
 */
@Service
public class LeadSlaService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final NotificationWriter notifications;

    public LeadSlaService(JdbcTemplate jdbc, AuditService audit, OutboxWriter outbox,
                          NotificationWriter notifications) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
        this.notifications = notifications;
    }

    /** The resolved policy for a lead: how long, on whose calendar, escalating to whom. */
    public record ResolvedPolicy(UUID policyId, String policyName, int firstResponseMinutes,
                                 UUID escalationUserId, BusinessHoursCalculator.Calendar calendar) {}

    public record BreachRow(UUID leadId, String leadName, String company, UUID ownerId, String ownerName,
                            OffsetDateTime dueAt, OffsetDateTime breachedAt, long minutesOver,
                            String escalatedTo) {}

    /**
     * Resolves the policy to apply for a lead being assigned to {@code ownerId}.
     *
     * @param preferredPolicyId the SLA policy named by the matched assignment
     *                          rule, if it named one
     */
    @Transactional(readOnly = true)
    public ResolvedPolicy resolvePolicy(UUID preferredPolicyId, UUID ownerId) {
        UUID tenantId = TenantContext.get().tenantId();
        List<Map<String, Object>> policies = preferredPolicyId == null
                ? jdbc.queryForList("""
                        select id, name, first_response_minutes, business_hours_id, escalation_user_id
                        from leads.sla_policy where tenant_id = ? and active = true
                        order by is_default desc, name limit 1
                        """, tenantId)
                : jdbc.queryForList("""
                        select id, name, first_response_minutes, business_hours_id, escalation_user_id
                        from leads.sla_policy where tenant_id = ? and id = ? and active = true
                        """, tenantId, preferredPolicyId);
        if (policies.isEmpty()) {
            return null;
        }
        Map<String, Object> policy = policies.get(0);

        // The owner's own working pattern wins over the policy default: "the
        // owner's business hours" is what the story asks for, and a rep in another
        // region is not on headquarters' clock.
        UUID businessHoursId = (UUID) policy.get("business_hours_id");
        if (ownerId != null) {
            List<UUID> ownerHours = jdbc.queryForList("""
                    select business_hours_id from leads.owner_work_profile
                    where tenant_id = ? and user_id = ? and business_hours_id is not null
                    """, UUID.class, tenantId, ownerId);
            if (!ownerHours.isEmpty()) {
                businessHoursId = ownerHours.get(0);
            }
        }
        return new ResolvedPolicy((UUID) policy.get("id"), (String) policy.get("name"),
                ((Number) policy.get("first_response_minutes")).intValue(),
                (UUID) policy.get("escalation_user_id"), calendar(businessHoursId));
    }

    /** Loads a working calendar, falling back to the tenant default then to 24/7. */
    @Transactional(readOnly = true)
    public BusinessHoursCalculator.Calendar calendar(UUID businessHoursId) {
        UUID tenantId = TenantContext.get().tenantId();
        List<Map<String, Object>> rows = businessHoursId == null
                ? jdbc.queryForList("""
                        select id, time_zone from leads.business_hours
                        where tenant_id = ? and active = true order by is_default desc, name limit 1
                        """, tenantId)
                : jdbc.queryForList("select id, time_zone from leads.business_hours where tenant_id = ? and id = ?",
                        tenantId, businessHoursId);
        if (rows.isEmpty()) {
            // No calendar at all means round-the-clock rather than never: a
            // configuration gap must not silently create impossible deadlines.
            return new BusinessHoursCalculator.Calendar(ZoneId.of("UTC"), Map.of(), Set.of());
        }
        UUID id = (UUID) rows.get(0).get("id");
        ZoneId zone;
        try {
            zone = ZoneId.of((String) rows.get(0).get("time_zone"));
        } catch (RuntimeException ex) {
            zone = ZoneId.of("UTC");
        }
        Map<DayOfWeek, BusinessHoursCalculator.Interval> days = new EnumMap<>(DayOfWeek.class);
        jdbc.query("""
                select day_of_week, open_time, close_time from leads.business_hours_day
                where tenant_id = ? and business_hours_id = ? order by day_of_week
                """, (RowCallbackHandler) rs -> days.put(DayOfWeek.of(rs.getInt("day_of_week")),
                new BusinessHoursCalculator.Interval(rs.getObject("open_time", LocalTime.class),
                        rs.getObject("close_time", LocalTime.class))), tenantId, id);
        Set<LocalDate> holidays = new HashSet<>(jdbc.queryForList("""
                select holiday_date from leads.business_hours_holiday
                where tenant_id = ? and business_hours_id = ?
                """, LocalDate.class, tenantId, id));
        return new BusinessHoursCalculator.Calendar(zone, days, holidays);
    }

    /**
     * Computes and STORES the first-response deadline for a lead at the moment it
     * is assigned. Returns null when the tenant has no active SLA policy, in
     * which case no clock runs and the lead simply carries no deadline.
     */
    @Transactional
    public Instant startClock(UUID leadId, UUID ownerId, UUID preferredPolicyId, Instant assignedAt) {
        ResolvedPolicy policy = resolvePolicy(preferredPolicyId, ownerId);
        if (policy == null) {
            return null;
        }
        Instant dueAt = BusinessHoursCalculator.dueAt(assignedAt, policy.firstResponseMinutes(), policy.calendar());
        jdbc.update("""
                update crm.lead
                set assigned_at = ?, sla_policy_id = ?, first_response_due_at = ?,
                    sla_breached_at = null, sla_escalated_at = null, updated_at = now()
                where tenant_id = ? and id = ?
                """, java.sql.Timestamp.from(assignedAt), policy.policyId(), java.sql.Timestamp.from(dueAt),
                TenantContext.get().tenantId(), leadId);
        jdbc.update("delete from leads.sla_breach where tenant_id = ? and lead_id = ?",
                TenantContext.get().tenantId(), leadId);
        return dueAt;
    }

    /**
     * Records the first response to a lead, stopping its clock. Idempotent: the
     * first response is the one that counts, and a rep clicking twice must not
     * rewrite history.
     */
    @Transactional
    public OffsetDateTime recordFirstResponse(UUID leadId) {
        UUID tenantId = TenantContext.get().tenantId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select first_responded_at, first_response_due_at, owner_id, first_name, last_name
                from crm.lead where tenant_id = ? and id = ? and deleted_at is null
                """, tenantId, leadId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Lead not found: " + leadId);
        }
        Object existing = rows.get(0).get("first_responded_at");
        if (existing != null) {
            return ((java.sql.Timestamp) existing).toInstant().atOffset(OffsetDateTime.now().getOffset());
        }
        Instant now = Instant.now();
        jdbc.update("update crm.lead set first_responded_at = ?, updated_at = now() where tenant_id = ? and id = ?",
                java.sql.Timestamp.from(now), tenantId, leadId);
        Object due = rows.get(0).get("first_response_due_at");
        boolean withinTarget = due == null || !now.isAfter(((java.sql.Timestamp) due).toInstant());
        audit.record("LEAD_FIRST_RESPONSE", "LEAD", leadId,
                "First response logged " + (withinTarget ? "within" : "after") + " the response target",
                Map.of("withinTarget", withinTarget, "respondedAt", now.toString()));
        outbox.write("lead", leadId, "lead.first_responded",
                Map.of("leadId", leadId.toString(), "respondedAt", now.toString(), "withinTarget", withinTarget));
        return now.atOffset(OffsetDateTime.now().getOffset());
    }

    /**
     * Finds leads whose response deadline has passed with no response logged,
     * records the breach and escalates once to the configured recipient.
     *
     * <p>Runs inside a tenant context — called both by the scheduled sweep (which
     * binds one tenant at a time) and on demand from the API.
     *
     * @return the breaches escalated by this pass
     */
    @Transactional
    public List<BreachRow> sweepBreaches() {
        UUID tenantId = TenantContext.get().tenantId();
        List<BreachRow> escalated = new ArrayList<>();
        List<Map<String, Object>> due = jdbc.queryForList("""
                select l.id, l.first_name, l.last_name, l.company, l.owner_id, l.first_response_due_at,
                       l.sla_policy_id, u.display_name as owner_name
                from crm.lead l
                left join identity.app_user u on u.tenant_id = l.tenant_id and u.id = l.owner_id
                left join leads.lead_status s on s.tenant_id = l.tenant_id and s.code = l.status
                where l.tenant_id = ?
                  and l.deleted_at is null
                  and l.first_response_due_at is not null
                  and l.first_responded_at is null
                  and l.sla_breached_at is null
                  and l.first_response_due_at < now()
                  and coalesce(s.category, 'OPEN') = 'OPEN'
                order by l.first_response_due_at
                limit 500
                """, tenantId);

        for (Map<String, Object> row : due) {
            UUID leadId = (UUID) row.get("id");
            Instant dueAt = ((java.sql.Timestamp) row.get("first_response_due_at")).toInstant();
            UUID policyId = (UUID) row.get("sla_policy_id");
            UUID ownerId = (UUID) row.get("owner_id");
            ResolvedPolicy policy = resolvePolicy(policyId, ownerId);
            UUID escalationUserId = policy == null ? null : policy.escalationUserId();
            if (escalationUserId == null) {
                escalationUserId = queueEscalationUser(leadId);
            }
            long minutesOver = policy == null
                    ? java.time.Duration.between(dueAt, Instant.now()).toMinutes()
                    : BusinessHoursCalculator.workingMinutesBetween(dueAt, Instant.now(), policy.calendar());

            jdbc.update("""
                    update crm.lead set sla_breached_at = now(), sla_escalated_at = now(), updated_at = now()
                    where tenant_id = ? and id = ?
                    """, tenantId, leadId);
            jdbc.update("""
                    insert into leads.sla_breach (tenant_id, lead_id, due_at, escalated_to_user_id,
                                                  escalated_at, minutes_over)
                    values (?, ?, ?, ?, now(), ?)
                    on conflict (tenant_id, lead_id) do nothing
                    """, tenantId, leadId, java.sql.Timestamp.from(dueAt), escalationUserId, (int) minutesOver);

            String leadName = row.get("first_name") + " " + row.get("last_name");
            String company = (String) row.get("company");
            if (escalationUserId != null) {
                notifications.notifyUser(tenantId, escalationUserId, "SYSTEM", "HIGH",
                        "First-response target missed",
                        leadName + " at " + company + " has had no first response and is "
                                + minutesOver + " working minutes past its target.",
                        "/leads", "You are the escalation contact for the lead response SLA.", true);
            }
            audit.record("LEAD_SLA_BREACH", "LEAD", leadId,
                    "First-response target missed for " + leadName,
                    Map.of("dueAt", dueAt.toString(), "minutesOver", minutesOver,
                            "escalatedTo", escalationUserId == null ? "nobody configured" : escalationUserId.toString()));
            Map<String, Object> payload = new HashMap<>();
            payload.put("leadId", leadId.toString());
            payload.put("dueAt", dueAt.toString());
            payload.put("minutesOver", minutesOver);
            payload.put("ownerId", ownerId == null ? null : ownerId.toString());
            payload.put("escalatedToUserId", escalationUserId == null ? null : escalationUserId.toString());
            outbox.write("lead", leadId, "lead.sla.breached", payload);

            escalated.add(new BreachRow(leadId, leadName, company, ownerId, (String) row.get("owner_name"),
                    dueAt.atOffset(OffsetDateTime.now().getOffset()),
                    OffsetDateTime.now(), minutesOver,
                    escalationUserId == null ? null : escalationUserId.toString()));
        }
        return escalated;
    }

    /** Recorded breaches, so a breach is reportable rather than only notified. */
    @Transactional(readOnly = true)
    public List<BreachRow> breaches(int limit) {
        return jdbc.query("""
                select b.lead_id, b.due_at, b.breached_at, b.minutes_over,
                       l.first_name, l.last_name, l.company, l.owner_id,
                       o.display_name as owner_name, e.display_name as escalated_to
                from leads.sla_breach b
                join crm.lead l on l.tenant_id = b.tenant_id and l.id = b.lead_id
                left join identity.app_user o on o.tenant_id = b.tenant_id and o.id = l.owner_id
                left join identity.app_user e on e.tenant_id = b.tenant_id and e.id = b.escalated_to_user_id
                where b.tenant_id = ?
                order by b.breached_at desc
                limit ?
                """, (rs, i) -> new BreachRow(rs.getObject("lead_id", UUID.class),
                rs.getString("first_name") + " " + rs.getString("last_name"), rs.getString("company"),
                rs.getObject("owner_id", UUID.class), rs.getString("owner_name"),
                rs.getObject("due_at", OffsetDateTime.class), rs.getObject("breached_at", OffsetDateTime.class),
                rs.getLong("minutes_over"), rs.getString("escalated_to")),
                TenantContext.get().tenantId(), Math.max(1, Math.min(limit, 200)));
    }

    /**
     * Recycles disqualified leads whose re-engagement date has arrived
     * (FR-LED-012): they re-enter the working queue rather than waiting for
     * somebody to remember.
     */
    @Transactional
    public int sweepRecycling(String workingStatusCode) {
        UUID tenantId = TenantContext.get().tenantId();
        List<UUID> ready = jdbc.queryForList("""
                select l.id from crm.lead l
                join leads.lead_status s on s.tenant_id = l.tenant_id and s.code = l.status
                where l.tenant_id = ? and l.deleted_at is null
                  and s.category = 'RECYCLED' and l.recycle_date is not null and l.recycle_date <= current_date
                limit 500
                """, UUID.class, tenantId);
        for (UUID leadId : ready) {
            jdbc.update("""
                    update crm.lead
                    set status = ?, recycled_at = now(), recycle_date = null,
                        disqualified_at = null, updated_at = now()
                    where tenant_id = ? and id = ?
                    """, workingStatusCode, tenantId, leadId);
            audit.record("LEAD_RECYCLED", "LEAD", leadId,
                    "Re-engagement date reached; lead returned to the working queue as " + workingStatusCode,
                    Map.of("status", workingStatusCode));
            outbox.write("lead", leadId, "lead.recycled",
                    Map.of("leadId", leadId.toString(), "status", workingStatusCode));
        }
        return ready.size();
    }

    private UUID queueEscalationUser(UUID leadId) {
        List<UUID> users = jdbc.queryForList("""
                select q.escalation_user_id
                from crm.lead l
                join leads.lead_queue q on q.tenant_id = l.tenant_id and q.id = l.queue_id
                where l.tenant_id = ? and l.id = ? and q.escalation_user_id is not null
                """, UUID.class, TenantContext.get().tenantId(), leadId);
        if (!users.isEmpty()) {
            return users.get(0);
        }
        List<UUID> fallback = jdbc.queryForList("""
                select escalation_user_id from leads.lead_queue
                where tenant_id = ? and is_fallback and escalation_user_id is not null
                """, UUID.class, TenantContext.get().tenantId());
        return fallback.isEmpty() ? null : fallback.get(0);
    }

    /** Guard used by the lifecycle service when a status change is attempted. */
    static void refuseIfReadOnly(boolean converted, String action) {
        if (converted) {
            throw new ConflictException("This lead has been converted and is read-only. " + action
                    + " the account, contact or opportunity it became instead.");
        }
    }
}

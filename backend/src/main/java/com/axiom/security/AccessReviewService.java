package com.axiom.security;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Access recertification over the effective RBAC grants already held in E02.
 *
 * <p>A campaign is a point-in-time snapshot.  New grants made afterwards are
 * reviewed by the next campaign rather than silently appearing in a review an
 * auditor already started.  A REVOKED decision changes the authoritative grant
 * in the same transaction as the decision, so the review cannot say access was
 * removed while the resolver still serves it.</p>
 */
@Service
public class AccessReviewService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public AccessReviewService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Campaign(UUID id, String code, String name, String scopeNote, Instant deadlineAt,
                           String status, Instant createdAt, int totalItems, int pendingItems,
                           int confirmedItems, int revokedItems, boolean overdue) {}

    public record Item(UUID id, UUID campaignId, String grantType, UUID grantRef,
                       UUID subjectUserId, String subjectEmail, String description,
                       UUID reviewerId, String reviewerName, String decision,
                       UUID decidedBy, Instant decidedAt, String note, Instant createdAt) {}

    public record CreateRequest(String code, String name, String scopeNote, Instant deadlineAt) {}
    public record DecisionRequest(String decision, String note) {}

    @Transactional(readOnly = true)
    public List<Campaign> campaigns() {
        RbacAccess.requireRead();
        return jdbc.query("""
                select c.id, c.code, c.name, c.scope_note, c.deadline_at, c.status, c.created_at,
                       count(i.id) as total_items,
                       count(i.id) filter (where i.decision = 'PENDING') as pending_items,
                       count(i.id) filter (where i.decision = 'CONFIRMED') as confirmed_items,
                       count(i.id) filter (where i.decision in ('REVOKED','AUTO_REVOKED')) as revoked_items
                from security.access_review_campaign c
                left join security.access_review_item i
                  on i.tenant_id = c.tenant_id and i.campaign_id = c.id
                where c.tenant_id = ?
                group by c.id
                order by c.created_at desc
                """, (rs, i) -> new Campaign(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("scope_note"),
                rs.getTimestamp("deadline_at").toInstant(), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getInt("total_items"),
                rs.getInt("pending_items"), rs.getInt("confirmed_items"), rs.getInt("revoked_items"),
                "OPEN".equals(rs.getString("status"))
                        && rs.getTimestamp("deadline_at").toInstant().isBefore(Instant.now())),
                TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public List<Item> items(UUID campaignId) {
        RbacAccess.requireRead();
        return jdbc.query("""
                select i.id, i.campaign_id, i.grant_type, i.grant_ref, i.subject_user_id,
                       u.email as subject_email, i.description, i.reviewer_id,
                       r.display_name as reviewer_name, i.decision, i.decided_by,
                       i.decided_at, i.note, i.created_at
                from security.access_review_item i
                join identity.app_user u on u.tenant_id = i.tenant_id and u.id = i.subject_user_id
                left join identity.app_user r on r.tenant_id = i.tenant_id and r.id = i.reviewer_id
                where i.tenant_id = ? and i.campaign_id = ?
                order by case i.decision when 'PENDING' then 0 else 1 end,
                         u.email, i.grant_type, i.description
                """, (rs, i) -> new Item(rs.getObject("id", UUID.class),
                rs.getObject("campaign_id", UUID.class), rs.getString("grant_type"),
                rs.getObject("grant_ref", UUID.class), rs.getObject("subject_user_id", UUID.class),
                rs.getString("subject_email"), rs.getString("description"),
                rs.getObject("reviewer_id", UUID.class), rs.getString("reviewer_name"),
                rs.getString("decision"), rs.getObject("decided_by", UUID.class),
                instant(rs.getTimestamp("decided_at")), rs.getString("note"),
                rs.getTimestamp("created_at").toInstant()),
                TenantContext.get().tenantId(), campaignId);
    }

    @Transactional
    public Campaign create(CreateRequest request) {
        RbacAccess.requireWrite("create an access review");
        String code = required(request.code(), "Give the review a short code.")
                .toUpperCase(Locale.ROOT).replace('-', '_');
        if (!code.matches("^[A-Z][A-Z0-9_]*$")) {
            throw new ConflictException("The review code must start with a letter and contain only letters, numbers or underscores.");
        }
        String name = required(request.name(), "Give the review a name administrators will recognise.");
        if (request.deadlineAt() == null || !request.deadlineAt().isAfter(Instant.now())) {
            throw new ConflictException("Choose a review deadline in the future.");
        }
        UUID tenantId = TenantContext.get().tenantId();
        UUID actor = TenantContext.get().userId();
        UUID campaignId = UUID.randomUUID();
        jdbc.update("""
                insert into security.access_review_campaign
                  (id, tenant_id, code, name, scope_note, deadline_at, status, created_by)
                values (?, ?, ?, ?, ?, ?, 'OPEN', ?)
                """, campaignId, tenantId, code, name, clean(request.scopeNote()),
                Timestamp.from(request.deadlineAt()), actor);

        int items = 0;
        items += jdbc.update("""
                insert into security.access_review_item
                  (tenant_id, campaign_id, grant_type, grant_ref, subject_user_id,
                   description, reviewer_id)
                select ups.tenant_id, ?, 'PERMISSION_SET', ups.id, ups.user_id,
                       'Permission ' || coalesce(ps.name, g.name, 'bundle') ||
                       coalesce(' until ' || ups.expires_at::date::text, ''), ?
                from security.user_permission_set ups
                left join security.permission_set ps
                  on ps.tenant_id = ups.tenant_id and ps.id = ups.permission_set_id
                left join security.permission_set_group g
                  on g.tenant_id = ups.tenant_id and g.id = ups.permission_set_group_id
                where ups.tenant_id = ? and ups.revoked_at is null
                  and (ups.expires_at is null or ups.expires_at > now())
                on conflict do nothing
                """, campaignId, actor, tenantId);
        items += jdbc.update("""
                insert into security.access_review_item
                  (tenant_id, campaign_id, grant_type, grant_ref, subject_user_id,
                   description, reviewer_id)
                select a.tenant_id, ?, 'ROLE', a.role_node_id, a.user_id,
                       'Role ' || r.name || coalesce(' until ' || a.expires_at::date::text, ''), ?
                from security.user_role_assignment a
                join security.role_node r on r.tenant_id = a.tenant_id and r.id = a.role_node_id
                where a.tenant_id = ? and (a.expires_at is null or a.expires_at > now())
                on conflict do nothing
                """, campaignId, actor, tenantId);
        items += jdbc.update("""
                insert into security.access_review_item
                  (tenant_id, campaign_id, grant_type, grant_ref, subject_user_id,
                   description, reviewer_id)
                select s.tenant_id, ?, 'RECORD_SHARE', s.id, s.grantee_user_id,
                       'Manual ' || s.access_level || ' access to ' || s.object_type ||
                       ' record ' || s.record_id::text, ?
                from security.record_share s
                where s.tenant_id = ? and s.cause = 'manual' and s.revoked_at is null
                  and (s.expires_at is null or s.expires_at > now())
                on conflict do nothing
                """, campaignId, actor, tenantId);
        items += jdbc.update("""
                insert into security.access_review_item
                  (tenant_id, campaign_id, grant_type, grant_ref, subject_user_id,
                   description, reviewer_id)
                select s.tenant_id, ?, 'DELEGATED_ADMIN', s.id, s.user_id,
                       'Delegated administration of role branch ' || r.name, ?
                from security.delegated_admin_scope s
                join security.role_node r on r.tenant_id = s.tenant_id and r.id = s.role_node_id
                where s.tenant_id = ? and (s.expires_at is null or s.expires_at > now())
                on conflict do nothing
                """, campaignId, actor, tenantId);

        audit.record("ACCESS_REVIEW_CREATED", "ACCESS_REVIEW", campaignId,
                "Created access review " + name,
                Map.of("code", code, "deadlineAt", request.deadlineAt().toString(), "itemCount", items));
        return campaigns().stream().filter(c -> c.id().equals(campaignId)).findFirst().orElseThrow();
    }

    @Transactional
    public Item decide(UUID itemId, DecisionRequest request) {
        RbacAccess.requireWrite("decide an access review item");
        String decision = required(request.decision(), "Choose Confirm or Revoke.").toUpperCase(Locale.ROOT);
        if (!List.of("CONFIRMED", "REVOKED").contains(decision)) {
            throw new ConflictException("The decision must be CONFIRMED or REVOKED.");
        }
        UUID tenantId = TenantContext.get().tenantId();
        Item item;
        try {
            item = jdbc.queryForObject("""
                    select i.id, i.campaign_id, i.grant_type, i.grant_ref, i.subject_user_id,
                           u.email as subject_email, i.description, i.reviewer_id,
                           r.display_name as reviewer_name, i.decision, i.decided_by,
                           i.decided_at, i.note, i.created_at
                    from security.access_review_item i
                    join security.access_review_campaign c
                      on c.tenant_id = i.tenant_id and c.id = i.campaign_id
                    join identity.app_user u on u.tenant_id = i.tenant_id and u.id = i.subject_user_id
                    left join identity.app_user r on r.tenant_id = i.tenant_id and r.id = i.reviewer_id
                    where i.tenant_id = ? and i.id = ? and c.status = 'OPEN'
                    for update of i
                    """, (rs, i) -> new Item(rs.getObject("id", UUID.class),
                    rs.getObject("campaign_id", UUID.class), rs.getString("grant_type"),
                    rs.getObject("grant_ref", UUID.class), rs.getObject("subject_user_id", UUID.class),
                    rs.getString("subject_email"), rs.getString("description"),
                    rs.getObject("reviewer_id", UUID.class), rs.getString("reviewer_name"),
                    rs.getString("decision"), rs.getObject("decided_by", UUID.class),
                    instant(rs.getTimestamp("decided_at")), rs.getString("note"),
                    rs.getTimestamp("created_at").toInstant()), tenantId, itemId);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("That review item is not part of an open campaign.");
        }
        if (!"PENDING".equals(item.decision())) {
            throw new ConflictException("This access item was already decided and is immutable.");
        }
        if (item.subjectUserId().equals(TenantContext.get().userId())) {
            throw new ConflictException("You cannot certify or revoke your own access. Ask another tenant administrator to review it.");
        }
        if ("REVOKED".equals(decision)) revokeGrant(item, clean(request.note()));

        jdbc.update("""
                update security.access_review_item
                set decision = ?, decided_by = ?, decided_at = now(), note = ?
                where tenant_id = ? and id = ? and decision = 'PENDING'
                """, decision, TenantContext.get().userId(), clean(request.note()), tenantId, itemId);
        audit.recordWithReason("ACCESS_REVIEW_DECISION", "APP_USER", item.subjectUserId(),
                decision + " access: " + item.description(), clean(request.note()),
                Map.of("campaignId", item.campaignId().toString(), "itemId", item.id().toString(),
                        "grantType", item.grantType(), "decision", decision));
        closeIfComplete(item.campaignId());
        return items(item.campaignId()).stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
    }

    private void revokeGrant(Item item, String note) {
        UUID tenantId = TenantContext.get().tenantId();
        UUID actor = TenantContext.get().userId();
        switch (item.grantType()) {
            case "PERMISSION_SET" -> jdbc.update("""
                    update security.user_permission_set
                    set revoked_at = now(), revoked_by = ?, revoked_reason = ?
                    where tenant_id = ? and id = ? and revoked_at is null
                    """, actor, note == null ? "Revoked by access review" : note, tenantId, item.grantRef());
            case "ROLE" -> jdbc.update("""
                    delete from security.user_role_assignment
                    where tenant_id = ? and user_id = ? and role_node_id = ?
                    """, tenantId, item.subjectUserId(), item.grantRef());
            case "RECORD_SHARE" -> jdbc.update("""
                    update security.record_share set revoked_at = now(), revoked_by = ?
                    where tenant_id = ? and id = ? and revoked_at is null and cause = 'manual'
                    """, actor, tenantId, item.grantRef());
            case "DELEGATED_ADMIN" -> jdbc.update("""
                    delete from security.delegated_admin_scope
                    where tenant_id = ? and id = ?
                    """, tenantId, item.grantRef());
            default -> throw new ConflictException("This grant type cannot be revoked by the access-review engine.");
        }
    }

    private void closeIfComplete(UUID campaignId) {
        Integer pending = jdbc.queryForObject("""
                select count(*) from security.access_review_item
                where tenant_id = ? and campaign_id = ? and decision = 'PENDING'
                """, Integer.class, TenantContext.get().tenantId(), campaignId);
        if (pending != null && pending == 0) {
            jdbc.update("""
                    update security.access_review_campaign
                    set status = 'CLOSED', closed_at = now(), closed_by = ?
                    where tenant_id = ? and id = ? and status = 'OPEN'
                    """, TenantContext.get().userId(), TenantContext.get().tenantId(), campaignId);
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new ConflictException(message);
        return value.trim();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

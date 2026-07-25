package com.axiom.accounts;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-ACC-006 — a named buying group per account or opportunity, whose members
 * carry role, influence and engagement status.
 *
 * <p>{@link #readiness(UUID)} answers the exit-criterion question in US-E04-04
 * ("advancement is blocked naming the missing role"). It is exposed as a query
 * this module owns rather than wired into stage advancement here: the stage gate
 * belongs to E06's opportunity service, and two modules writing the same gate is
 * how a rule ends up enforced in one path and not the other.
 */
@Service
public class BuyingGroupService {

    /** The roles a fully-formed group is expected to name, in business language. */
    private static final Map<String, String> EXPECTED_ROLES = Map.of(
            "ECONOMIC_BUYER", "the person who can approve the spend");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ActorSession actor;

    public BuyingGroupService(JdbcTemplate jdbc, AuditService audit, ActorSession actor) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.actor = actor;
    }

    // --------------------------------------------------------------- contracts

    public record GroupRequest(@NotBlank String name, UUID accountId, UUID opportunityId,
                               String description) {}

    public record MemberRequest(@NotNull UUID contactId, @NotBlank String role, String influence,
                                String engagementStatus, Instant lastEngagedAt, String notes) {}

    public record MemberRow(UUID id, UUID contactId, String contactName, String title, String role,
                            String influence, String engagementStatus, Instant lastEngagedAt,
                            String engagementRecency, String notes, long version) {}

    /**
     * @param missingRoles roles the group has not named, each with why it matters
     */
    public record GroupView(UUID id, String name, UUID accountId, String accountName,
                            UUID opportunityId, String opportunityName, String description,
                            String status, List<MemberRow> members, boolean complete,
                            List<String> missingRoles, String readinessNote) {}

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<GroupView> forAccount(UUID accountId) {
        return groups("g.account_id = ?", accountId);
    }

    @Transactional(readOnly = true)
    public List<GroupView> forOpportunity(UUID opportunityId) {
        return groups("g.opportunity_id = ?", opportunityId);
    }

    private List<GroupView> groups(String predicate, UUID anchorId) {
        List<GroupView> shells = jdbc.query("""
                select g.id, g.name, g.account_id, a.name as account_name, g.opportunity_id,
                       o.name as opportunity_name, g.description, g.status
                from crm.buying_group g
                left join crm.account a on a.tenant_id = g.tenant_id and a.id = g.account_id
                left join sales.opportunity o on o.tenant_id = g.tenant_id and o.id = g.opportunity_id
                where g.tenant_id = ? and g.deleted_at is null and """ + predicate + """
                order by g.name
                """, (rs, i) -> new GroupView(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("account_id", UUID.class), rs.getString("account_name"),
                rs.getObject("opportunity_id", UUID.class), rs.getString("opportunity_name"),
                rs.getString("description"), rs.getString("status"), List.of(), false, List.of(), null),
                TenantContext.get().tenantId(), anchorId);

        List<GroupView> complete = new ArrayList<>();
        for (GroupView shell : shells) {
            List<MemberRow> members = members(shell.id());
            complete.add(withMembers(shell, members));
        }
        return complete;
    }

    private GroupView withMembers(GroupView shell, List<MemberRow> members) {
        List<String> missing = EXPECTED_ROLES.entrySet().stream()
                .filter(entry -> members.stream().noneMatch(m -> m.role().equals(entry.getKey())))
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .toList();
        String note = missing.isEmpty()
                ? "This group names an economic buyer, so a stage gate that requires one will pass."
                : "This group has not named " + String.join(", ", missing)
                  + ". A stage gate that requires that role will block advancement until it is filled.";
        return new GroupView(shell.id(), shell.name(), shell.accountId(), shell.accountName(),
                shell.opportunityId(), shell.opportunityName(), shell.description(), shell.status(),
                members, missing.isEmpty(), missing, note);
    }

    @Transactional(readOnly = true)
    public List<MemberRow> members(UUID groupId) {
        return jdbc.query("""
                select m.id, m.contact_id, trim(c.first_name || ' ' || c.last_name) as contact_name,
                       c.title, m.role, m.influence, m.engagement_status, m.last_engaged_at,
                       m.notes, m.version
                from crm.buying_group_member m
                join crm.contact c on c.tenant_id = m.tenant_id and c.id = m.contact_id
                where m.tenant_id = ? and m.buying_group_id = ?
                order by case m.role
                           when 'ECONOMIC_BUYER' then 1 when 'CHAMPION' then 2
                           when 'TECHNICAL_EVALUATOR' then 3 when 'INFLUENCER' then 4 else 5 end,
                         c.last_name
                """, (rs, i) -> {
                    Instant engaged = rs.getTimestamp("last_engaged_at") == null
                            ? null : rs.getTimestamp("last_engaged_at").toInstant();
                    Integer days = engaged == null ? null
                            : (int) java.time.Duration.between(engaged, Instant.now()).toDays();
                    return new MemberRow(rs.getObject("id", UUID.class),
                            rs.getObject("contact_id", UUID.class), rs.getString("contact_name"),
                            rs.getString("title"), rs.getString("role"), rs.getString("influence"),
                            rs.getString("engagement_status"), engaged,
                            RelationshipService.describeRecency(days), rs.getString("notes"),
                            rs.getLong("version"));
                }, TenantContext.get().tenantId(), groupId);
    }

    /** US-E04-04 exit criterion: which required roles are still unfilled. */
    @Transactional(readOnly = true)
    public GroupView readiness(UUID groupId) {
        GroupView shell = jdbc.query("""
                select g.id, g.name, g.account_id, a.name as account_name, g.opportunity_id,
                       o.name as opportunity_name, g.description, g.status
                from crm.buying_group g
                left join crm.account a on a.tenant_id = g.tenant_id and a.id = g.account_id
                left join sales.opportunity o on o.tenant_id = g.tenant_id and o.id = g.opportunity_id
                where g.tenant_id = ? and g.id = ? and g.deleted_at is null
                """, (rs, i) -> new GroupView(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("account_id", UUID.class), rs.getString("account_name"),
                rs.getObject("opportunity_id", UUID.class), rs.getString("opportunity_name"),
                rs.getString("description"), rs.getString("status"), List.of(), false, List.of(), null),
                TenantContext.get().tenantId(), groupId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Buying group not found"));
        return withMembers(shell, members(groupId));
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public GroupView create(GroupRequest request) {
        actor.bind();
        if ((request.accountId() == null) == (request.opportunityId() == null)) {
            throw new IllegalArgumentException("A buying group belongs to exactly one of an account or an "
                    + "opportunity. Supply accountId or opportunityId, not both and not neither.");
        }
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into crm.buying_group
                      (tenant_id, name, account_id, opportunity_id, description, created_by, updated_by)
                    values (?, ?, ?, ?, ?, ?, ?)
                    returning id
                    """, UUID.class, TenantContext.get().tenantId(),
                    AccountService.require(request.name(), "Buying group name is required"),
                    request.accountId(), request.opportunityId(),
                    AccountService.blankToNull(request.description()),
                    TenantContext.get().userId(), TenantContext.get().userId());
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(AccountService.databaseMessage(ex,
                    "A buying group with that name already exists in this tenant."));
        }
        audit.record("BUYING_GROUP_CREATE", request.accountId() != null ? "ACCOUNT" : "OPPORTUNITY",
                request.accountId() != null ? request.accountId() : request.opportunityId(),
                "Created buying group " + request.name(),
                Map.of("buyingGroupId", id.toString(), "name", request.name()));
        return readiness(id);
    }

    @Transactional
    public GroupView upsertMember(UUID groupId, MemberRequest request) {
        actor.bind();
        readiness(groupId);
        String role = AccountService.upper(request.role());
        try {
            jdbc.update("""
                    insert into crm.buying_group_member
                      (tenant_id, buying_group_id, contact_id, role, influence, engagement_status,
                       last_engaged_at, notes, created_by, updated_by)
                    values (?, ?, ?, ?, coalesce(?, 'MEDIUM'), coalesce(?, 'NOT_ENGAGED'), ?, ?, ?, ?)
                    on conflict (tenant_id, buying_group_id, contact_id) do update
                      set role = excluded.role,
                          influence = excluded.influence,
                          engagement_status = excluded.engagement_status,
                          last_engaged_at = coalesce(excluded.last_engaged_at, crm.buying_group_member.last_engaged_at),
                          notes = excluded.notes,
                          updated_at = now(),
                          updated_by = excluded.updated_by,
                          version = crm.buying_group_member.version + 1
                    """, TenantContext.get().tenantId(), groupId, request.contactId(), role,
                    AccountService.upper(request.influence()), AccountService.upper(request.engagementStatus()),
                    request.lastEngagedAt() == null ? null : java.sql.Timestamp.from(request.lastEngagedAt()),
                    AccountService.blankToNull(request.notes()),
                    TenantContext.get().userId(), TenantContext.get().userId());
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(AccountService.databaseMessage(ex,
                    "That buying-group member could not be saved. Check the contact, role, influence "
                    + "and engagement status values."));
        }
        GroupView view = readiness(groupId);
        audit.record("BUYING_GROUP_MEMBER_SAVE", "BUYING_GROUP", groupId,
                "Recorded " + role + " in buying group " + view.name(),
                Map.of("contactId", request.contactId().toString(), "role", role,
                        "influence", AccountService.nullSafe(AccountService.upper(request.influence()), "MEDIUM"),
                        "engagementStatus", AccountService.nullSafe(AccountService.upper(request.engagementStatus()), "NOT_ENGAGED"),
                        "economicBuyerPresent", view.complete()));
        return view;
    }
}

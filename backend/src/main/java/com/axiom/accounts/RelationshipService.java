package com.axiom.accounts;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-ACC-005 — one contact related to many accounts, each relationship carrying
 * a role, an influence level, an active flag and its own dates.
 * FR-ACC-007 — the same data shaped as a relationship/reporting map.
 *
 * <p>The map returns nodes and edges rather than a rendered tree: reporting lines
 * in real organizations are not a clean tree (dotted lines, vacancies, two people
 * reporting to a contact who has left), and a server that insists on a tree has
 * to invent a parent to produce one.
 */
@Service
public class RelationshipService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ActorSession actor;

    public RelationshipService(JdbcTemplate jdbc, AuditService audit, ActorSession actor) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.actor = actor;
    }

    // --------------------------------------------------------------- contracts

    public record RelationRequest(@NotNull UUID contactId, @NotNull UUID accountId,
                                  @NotBlank String role, String influenceLevel, Boolean isActive,
                                  boolean isPrimaryEmployer, LocalDate startDate, LocalDate endDate,
                                  String notes) {}

    public record RelationRow(UUID id, UUID contactId, String contactName, UUID accountId,
                              String accountName, String role, String influenceLevel, boolean isActive,
                              boolean isPrimaryEmployer, LocalDate startDate, LocalDate endDate,
                              String notes, long version) {}

    public record MapNode(UUID contactId, String name, String title, String seniority,
                          UUID reportsToContactId, String influenceLevel, String relationshipRole,
                          String buyingGroupRole, String engagementStatus, Instant lastEngagedAt,
                          Integer daysSinceEngagement, String engagementRecency) {}

    public record MapEdge(UUID fromContactId, UUID toContactId, String kind, String label) {}

    public record RelationshipMap(UUID accountId, List<MapNode> nodes, List<MapEdge> edges,
                                 boolean restricted, String restrictionNote) {}

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<RelationRow> relationsForAccount(UUID accountId) {
        return jdbc.query(relationSql() + """
                where r.tenant_id = ? and r.account_id = ?
                order by r.is_active desc, c.last_name, c.first_name
                """, relationMapper(), TenantContext.get().tenantId(), accountId);
    }

    @Transactional(readOnly = true)
    public List<RelationRow> relationsForContact(UUID contactId) {
        return jdbc.query(relationSql() + """
                where r.tenant_id = ? and r.contact_id = ?
                order by r.is_active desc, a.name
                """, relationMapper(), TenantContext.get().tenantId(), contactId);
    }

    private String relationSql() {
        return """
                select r.id, r.contact_id,
                       trim(c.first_name || ' ' || c.last_name) as contact_name,
                       r.account_id, a.name as account_name, r.role, r.influence_level,
                       r.is_active, r.is_primary_employer, r.start_date, r.end_date, r.notes, r.version
                from crm.account_contact_relation r
                join crm.contact c on c.tenant_id = r.tenant_id and c.id = r.contact_id
                join crm.account a on a.tenant_id = r.tenant_id and a.id = r.account_id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<RelationRow> relationMapper() {
        return (rs, i) -> new RelationRow(rs.getObject("id", UUID.class),
                rs.getObject("contact_id", UUID.class), rs.getString("contact_name"),
                rs.getObject("account_id", UUID.class), rs.getString("account_name"),
                rs.getString("role"), rs.getString("influence_level"), rs.getBoolean("is_active"),
                rs.getBoolean("is_primary_employer"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), rs.getString("notes"), rs.getLong("version"));
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public List<RelationRow> upsertRelation(RelationRequest request) {
        actor.bind();
        String role = AccountService.upper(request.role());
        boolean active = request.isActive() == null || request.isActive();
        if (!active && request.endDate() == null) {
            throw new IllegalArgumentException("An inactive relationship needs an end date: record when "
                    + "the person stopped acting in this role.");
        }
        try {
            jdbc.update("""
                    insert into crm.account_contact_relation
                      (tenant_id, contact_id, account_id, role, influence_level, is_active,
                       is_primary_employer, start_date, end_date, notes, created_by, updated_by)
                    values (?, ?, ?, ?, coalesce(?, 'MEDIUM'), ?, ?, ?, ?, ?, ?, ?)
                    on conflict (tenant_id, contact_id, account_id, role) do update
                      set influence_level = excluded.influence_level,
                          is_active = excluded.is_active,
                          is_primary_employer = excluded.is_primary_employer,
                          start_date = excluded.start_date,
                          end_date = excluded.end_date,
                          notes = excluded.notes,
                          updated_at = now(),
                          updated_by = excluded.updated_by,
                          version = crm.account_contact_relation.version + 1
                    """, TenantContext.get().tenantId(), request.contactId(), request.accountId(), role,
                    AccountService.upper(request.influenceLevel()), active, request.isPrimaryEmployer(),
                    request.startDate(), request.endDate(), AccountService.blankToNull(request.notes()),
                    TenantContext.get().userId(), TenantContext.get().userId());
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(AccountService.databaseMessage(ex,
                    "That relationship could not be saved. Check the contact, the account and the date range."));
        }
        audit.record("ACCOUNT_CONTACT_RELATION_SAVE", "ACCOUNT", request.accountId(),
                "Recorded relationship " + role + " for contact " + request.contactId(),
                Map.of("contactId", request.contactId().toString(), "role", role,
                        "influenceLevel", AccountService.nullSafe(AccountService.upper(request.influenceLevel()), "MEDIUM"),
                        "isActive", active));
        return relationsForAccount(request.accountId());
    }

    // ---------------------------------------------------------------------- map

    /**
     * FR-ACC-007 — hierarchy, influence and engagement recency in one payload.
     * Engagement recency is expressed as a business phrase as well as a day
     * count: "no contact for over a quarter" is actionable where "112" is not.
     */
    @Transactional(readOnly = true)
    public RelationshipMap map(UUID accountId) {
        RecordAccess.Scope scope = RecordAccess.current();
        List<MapNode> nodes = jdbc.query("""
                select * from (
                  select distinct on (c.id)
                         c.id, trim(c.first_name || ' ' || c.last_name) as name, c.title, c.seniority,
                         c.reports_to_contact_id,
                         coalesce(r.influence_level, 'MEDIUM') as influence_level,
                         r.role as relationship_role,
                         m.role as buying_group_role, m.engagement_status,
                         greatest(c.last_engaged_at, m.last_engaged_at) as engaged_at
                  from crm.contact c
                  left join crm.account_contact_relation r
                         on r.tenant_id = c.tenant_id and r.contact_id = c.id
                        and r.account_id = ? and r.is_active
                  left join crm.buying_group_member m
                         on m.tenant_id = c.tenant_id and m.contact_id = c.id
                        and m.buying_group_id in (
                            select g.id from crm.buying_group g
                            where g.tenant_id = c.tenant_id and g.account_id = ? and g.deleted_at is null)
                  where c.tenant_id = ? and c.deleted_at is null and c.status <> 'MERGED'
                    and (c.account_id = ? or r.account_id = ?)
                  order by c.id, m.role nulls last, r.role nulls last
                ) node
                order by node.name
                """, (rs, i) -> {
                    Instant engaged = rs.getTimestamp("engaged_at") == null
                            ? null : rs.getTimestamp("engaged_at").toInstant();
                    Integer days = engaged == null ? null
                            : (int) java.time.Duration.between(engaged, Instant.now()).toDays();
                    return new MapNode(rs.getObject("id", UUID.class), rs.getString("name"),
                            rs.getString("title"), rs.getString("seniority"),
                            rs.getObject("reports_to_contact_id", UUID.class),
                            rs.getString("influence_level"), rs.getString("relationship_role"),
                            rs.getString("buying_group_role"), rs.getString("engagement_status"),
                            engaged, days, describeRecency(days));
                },
                accountId, accountId, TenantContext.get().tenantId(), accountId, accountId);

        List<MapEdge> edges = new java.util.ArrayList<>();
        java.util.Set<UUID> present = nodes.stream().map(MapNode::contactId)
                .collect(java.util.stream.Collectors.toSet());
        for (MapNode node : nodes) {
            if (node.reportsToContactId() != null && present.contains(node.reportsToContactId())) {
                edges.add(new MapEdge(node.contactId(), node.reportsToContactId(), "REPORTS_TO", "reports to"));
            }
        }
        return new RelationshipMap(accountId, nodes, List.copyOf(edges),
                scope.restricted(), scope.restricted() ? scope.restrictionNote() : null);
    }

    static String describeRecency(Integer days) {
        if (days == null) return "Never engaged";
        if (days <= 14) return "Engaged in the last two weeks";
        if (days <= 45) return "Engaged in the last six weeks";
        if (days <= 90) return "Engaged this quarter";
        if (days <= 180) return "No contact for over a quarter";
        return "No contact for over six months";
    }
}

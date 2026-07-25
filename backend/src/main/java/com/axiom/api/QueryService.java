package com.axiom.api;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-side queries via JdbcTemplate. Class-level @Transactional so
 * TenantSessionAspect binds app.tenant_id on the connection — the RLS
 * policies scope every join below; explicit tenant_id predicates are the
 * belt-and-braces application layer on top (ADR-001).
 */
@Service
@Transactional(readOnly = true)
public class QueryService {

    private final JdbcTemplate jdbc;

    public QueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static UUID tenantId() {
        return TenantContext.get().tenantId();
    }

    // ------------------------------------------------------------------ accounts

    public record AccountRow(UUID id, String name, String industry, UUID ownerId, String ownerName) {}

    public List<AccountRow> listAccounts() {
        return jdbc.query("""
                select a.id, a.name, a.industry, a.owner_id, u.display_name as owner_name
                from account a
                left join app_user u on u.id = a.owner_id and u.tenant_id = a.tenant_id
                where a.tenant_id = ?
                order by a.name
                """,
                (rs, i) -> new AccountRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("industry"),
                        rs.getObject("owner_id", UUID.class),
                        rs.getString("owner_name")),
                tenantId());
    }

    // ------------------------------------------------------------------ contacts

    public record ContactRow(UUID id, UUID accountId, String accountName,
                             String firstName, String lastName, String email, String title) {}

    public List<ContactRow> listContacts(UUID accountId) {
        StringBuilder sql = new StringBuilder("""
                select c.id, c.account_id, a.name as account_name,
                       c.first_name, c.last_name, c.email, c.title
                from contact c
                left join account a on a.id = c.account_id and a.tenant_id = c.tenant_id
                where c.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (accountId != null) {
            sql.append(" and c.account_id = ?");
            args.add(accountId);
        }
        sql.append(" order by c.last_name, c.first_name");
        return jdbc.query(sql.toString(),
                (rs, i) -> new ContactRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("account_name"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("email"),
                        rs.getString("title")),
                args.toArray());
    }

    // ------------------------------------------------------------------ leads

    public record LeadRow(UUID id, String firstName, String lastName, String company, String email,
                          String status, String ownerName,
                          UUID convertedAccountId, UUID convertedContactId, UUID convertedOpportunityId) {}

    public List<LeadRow> listLeads() {
        return jdbc.query("""
                select l.id, l.first_name, l.last_name, l.company, l.email, l.status,
                       u.display_name as owner_name,
                       l.converted_account_id, l.converted_contact_id, l.converted_opportunity_id
                from lead l
                left join app_user u on u.id = l.owner_id and u.tenant_id = l.tenant_id
                where l.tenant_id = ?
                order by l.created_at desc
                """,
                (rs, i) -> new LeadRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("company"),
                        rs.getString("email"),
                        rs.getString("status"),
                        rs.getString("owner_name"),
                        rs.getObject("converted_account_id", UUID.class),
                        rs.getObject("converted_contact_id", UUID.class),
                        rs.getObject("converted_opportunity_id", UUID.class)),
                tenantId());
    }

    // ------------------------------------------------------------------ pipeline board

    public record BoardOpportunity(UUID id, String name, String accountName, BigDecimal amount,
                                   LocalDate closeDate, String ownerName, boolean hasEconomicBuyer) {}

    public record BoardStage(UUID id, String name, int sortOrder, boolean isClosed, boolean isWon,
                             boolean requiresEconomicBuyer, List<BoardOpportunity> opportunities) {}

    public List<BoardStage> pipelineBoard() {
        UUID tid = tenantId();
        List<BoardStage> board = jdbc.query("""
                select id, name, sort_order, is_closed, is_won, requires_economic_buyer
                from pipeline_stage where tenant_id = ? order by sort_order
                """,
                (rs, i) -> new BoardStage(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getInt("sort_order"),
                        rs.getBoolean("is_closed"),
                        rs.getBoolean("is_won"),
                        rs.getBoolean("requires_economic_buyer"),
                        new ArrayList<>()),
                tid);

        Map<UUID, BoardStage> byId = new LinkedHashMap<>();
        board.forEach(s -> byId.put(s.id(), s));

        jdbc.query("""
                select o.id, o.name, o.stage_id, o.amount, o.close_date,
                       a.name as account_name, u.display_name as owner_name,
                       exists (select 1 from opportunity_contact_role r
                               where r.tenant_id = o.tenant_id
                                 and r.opportunity_id = o.id
                                 and r.role = 'ECONOMIC_BUYER') as has_eb
                from opportunity o
                join account a on a.id = o.account_id and a.tenant_id = o.tenant_id
                left join app_user u on u.id = o.owner_id and u.tenant_id = o.tenant_id
                where o.tenant_id = ? and o.is_closed = false
                order by o.close_date nulls last, o.name
                """,
                rs -> {
                    BoardStage stage = byId.get(rs.getObject("stage_id", UUID.class));
                    if (stage != null) {
                        java.sql.Date d = rs.getDate("close_date");
                        stage.opportunities().add(new BoardOpportunity(
                                rs.getObject("id", UUID.class),
                                rs.getString("name"),
                                rs.getString("account_name"),
                                rs.getBigDecimal("amount"),
                                d == null ? null : d.toLocalDate(),
                                rs.getString("owner_name"),
                                rs.getBoolean("has_eb")));
                    }
                },
                tid);

        return board;
    }

    // ------------------------------------------------------------------ dashboard

    public record StageSummary(String stage, BigDecimal sum, long count) {}

    public record DashboardSummary(BigDecimal openPipeline, long openCount,
                                   List<StageSummary> byStage, long atRiskCount) {}

    public DashboardSummary dashboardSummary() {
        UUID tid = tenantId();

        List<StageSummary> byStage = jdbc.query("""
                select s.name as stage, coalesce(sum(o.amount), 0) as total, count(o.id) as cnt
                from pipeline_stage s
                left join opportunity o
                       on o.stage_id = s.id and o.tenant_id = s.tenant_id and o.is_closed = false
                where s.tenant_id = ? and s.is_closed = false
                group by s.name, s.sort_order
                order by s.sort_order
                """,
                (rs, i) -> new StageSummary(rs.getString("stage"), rs.getBigDecimal("total"), rs.getLong("cnt")),
                tid);

        Map<String, Object> totals = jdbc.queryForMap("""
                select coalesce(sum(amount), 0) as open_pipeline,
                       count(*) as open_count,
                       count(*) filter (where not exists (
                           select 1 from opportunity_contact_role r
                           where r.tenant_id = o.tenant_id
                             and r.opportunity_id = o.id
                             and r.role = 'ECONOMIC_BUYER')) as at_risk
                from opportunity o
                where o.tenant_id = ? and o.is_closed = false
                """, tid);

        return new DashboardSummary(
                (BigDecimal) totals.get("open_pipeline"),
                ((Number) totals.get("open_count")).longValue(),
                byStage,
                ((Number) totals.get("at_risk")).longValue());
    }
}

package com.axiom.api;

import com.axiom.security.AuthorizationService;
import com.axiom.security.SecurableObject;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    public static final int PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;

    public QueryService(JdbcTemplate jdbc, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    private static UUID tenantId() {
        return TenantContext.get().tenantId();
    }

    // ------------------------------------------------------------------ accounts

    public record AccountRow(UUID id, String name, String industry, UUID ownerId, String ownerName) {}

    public PageResult<AccountRow> listAccounts(String search, String industry, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        AuthorizationService.RecordPredicate visible = authorization.visibleRecordPredicate(
                SecurableObject.ACCOUNT, "a");
        args.addAll(visible.args());
        String where = accountWhere(search, industry, visible.sql(), args);
        long total = total("select count(*) from account a left join app_user u on u.id = a.owner_id and u.tenant_id = a.tenant_id " + where, args);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(PAGE_SIZE);
        pageArgs.add(safePage * PAGE_SIZE);
        List<AccountRow> items = jdbc.query("""
                select a.id, a.name, a.industry, a.owner_id, u.display_name as owner_name
                from account a
                left join app_user u on u.id = a.owner_id and u.tenant_id = a.tenant_id
                """ + where + "\n" + """
                order by a.name
                limit ? offset ?
                """,
                (rs, i) -> new AccountRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("industry"),
                        rs.getObject("owner_id", UUID.class),
                        rs.getString("owner_name")),
                pageArgs.toArray());
        return PageResult.of(items, safePage, PAGE_SIZE, total);
    }

    // ------------------------------------------------------------------ contacts

    public record ContactRow(UUID id, UUID accountId, String accountName,
                             String firstName, String lastName, String email, String title) {}

    public List<ContactRow> listContacts(UUID accountId) {
        StringBuilder sql = new StringBuilder("""
                select c.id, c.account_id, a.name as account_name,
                       c.first_name, c.last_name, c.email, c.title
                from contact c
                left join account a on a.id = c.account_id and a.tenant_id = c.tenant_id and a.deleted_at is null
                where c.tenant_id = ? and c.deleted_at is null
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        AuthorizationService.RecordPredicate visible = authorization.visibleRecordPredicate(
                SecurableObject.CONTACT, "c");
        sql.append(" and (").append(visible.sql()).append(")");
        args.addAll(visible.args());
        if (accountId != null) {
            sql.append(" and c.account_id = ?");
            args.add(accountId);
        }
        sql.append(" order by c.last_name, c.first_name limit 100");
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

    public PageResult<LeadRow> listLeads(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        AuthorizationService.RecordPredicate visible = authorization.visibleRecordPredicate(
                SecurableObject.LEAD, "l");
        args.addAll(visible.args());
        String where = leadWhere(search, status, visible.sql(), args);
        long total = total("select count(*) from lead l left join app_user u on u.id = l.owner_id and u.tenant_id = l.tenant_id " + where, args);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(PAGE_SIZE);
        pageArgs.add(safePage * PAGE_SIZE);
        List<LeadRow> items = jdbc.query("""
                select l.id, l.first_name, l.last_name, l.company, l.email, l.status,
                       u.display_name as owner_name,
                       l.converted_account_id, l.converted_contact_id, l.converted_opportunity_id
                from lead l
                left join app_user u on u.id = l.owner_id and u.tenant_id = l.tenant_id
                """ + where + "\n" + """
                order by l.created_at desc
                limit ? offset ?
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
                pageArgs.toArray());
        return PageResult.of(items, safePage, PAGE_SIZE, total);
    }

    private String accountWhere(String search, String industry, String visibleSql, List<Object> args) {
        StringBuilder where = new StringBuilder(" where a.tenant_id = ? and a.deleted_at is null and (")
                .append(visibleSql).append(")");
        String q = searchPattern(search);
        if (q != null) {
            where.append(" and (lower(a.name) like ? or lower(coalesce(a.industry,'')) like ? or lower(coalesce(u.display_name,'')) like ?)");
            args.add(q); args.add(q); args.add(q);
        }
        String f = clean(industry);
        if (f != null) {
            where.append(" and lower(coalesce(a.industry,'')) = ?");
            args.add(f.toLowerCase(Locale.ROOT));
        }
        return where.toString();
    }

    private String leadWhere(String search, String status, String visibleSql, List<Object> args) {
        StringBuilder where = new StringBuilder(" where l.tenant_id = ? and l.deleted_at is null and (")
                .append(visibleSql).append(")");
        String q = searchPattern(search);
        if (q != null) {
            where.append(" and (lower(l.first_name) like ? or lower(l.last_name) like ? or lower(l.company) like ? or lower(coalesce(l.email,'')) like ? or lower(coalesce(u.display_name,'')) like ?)");
            args.add(q); args.add(q); args.add(q); args.add(q); args.add(q);
        }
        String f = clean(status);
        if (f != null) {
            where.append(" and upper(l.status) = ?");
            args.add(f.toUpperCase(Locale.ROOT));
        }
        return where.toString();
    }

    private long total(String sql, List<Object> args) {
        Long value = jdbc.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private String searchPattern(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : "%" + cleaned.toLowerCase(Locale.ROOT) + "%";
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
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
                from pipeline_stage where tenant_id = ? and deleted_at is null order by sort_order
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
                where s.tenant_id = ? and s.is_closed = false and s.deleted_at is null
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

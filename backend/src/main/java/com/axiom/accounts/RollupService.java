package com.axiom.accounts;

import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FR-ACC-004 — pipeline value, closed revenue, open cases and activity recency,
 * for an account alone and rolled up across its hierarchy.
 *
 * <p>Two properties are non-negotiable and both are visible in the response.
 *
 * <p><b>Roll-ups respect the viewer's record access.</b> Every input is filtered
 * by {@link RecordAccess}, and the number of records excluded is never computed
 * — because a count of what you cannot see is still a disclosure that it exists.
 * What the caller gets instead is {@code restricted: true} and a sentence saying
 * so, which is the difference between a narrower answer and a wrong one.
 *
 * <p><b>A measure with no source says so.</b> Open cases and SLA breaches belong
 * to the service module (E12), which is not built. Rather than reporting zero —
 * the one answer guaranteed to be misread as good news — the measure comes back
 * null and named in {@code unavailableMeasures}.
 */
@Service
@Transactional(readOnly = true)
public class RollupService {

    private final JdbcTemplate jdbc;
    private Boolean opportunityHasTombstone;

    public RollupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Figures(int accountsIncluded,
                          BigDecimal openPipelineValue, int openOpportunityCount,
                          BigDecimal closedWonRevenue, int closedWonCount,
                          BigDecimal openCases, BigDecimal slaBreaches,
                          String caseSignalSource, Instant caseSignalAsOf,
                          Instant lastActivityAt, int activityCount90d) {}

    public record UnavailableMeasure(String code, String label, String reason) {}

    public record RollupView(UUID accountId, String accountName, UUID ultimateParentId,
                             String ultimateParentName, int hierarchyDepth,
                             Figures accountOnly, Figures hierarchy,
                             boolean restricted, String restrictionNote,
                             List<UnavailableMeasure> unavailableMeasures) {}

    public RollupView rollup(UUID accountId) {
        RecordAccess.Scope scope = RecordAccess.current();
        List<Object> anchorArgs = new ArrayList<>();
        anchorArgs.add(TenantContext.get().tenantId());
        anchorArgs.add(accountId);
        String anchorOwnerFilter = scope.ownerPredicate("a.owner_id", anchorArgs);
        Anchor anchor;
        try {
            anchor = jdbc.queryForObject("""
                    select a.name, a.hierarchy_path, a.hierarchy_depth, a.ultimate_parent_id,
                           up.name as ultimate_parent_name
                    from crm.account a
                    left join crm.account up on up.tenant_id = a.tenant_id and up.id = a.ultimate_parent_id
                    where a.tenant_id = ? and a.id = ? and a.deleted_at is null
                    """ + anchorOwnerFilter, (rs, i) -> new Anchor(rs.getString("name"), rs.getString("hierarchy_path"),
                            rs.getInt("hierarchy_depth"), rs.getObject("ultimate_parent_id", UUID.class),
                            rs.getString("ultimate_parent_name")),
                    anchorArgs.toArray());
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Account not found");
        }

        Figures self = figures(anchor.path(), false, scope);
        Figures family = figures(anchor.path(), true, scope);

        List<UnavailableMeasure> unavailable = new ArrayList<>();
        if (family.openCases() == null) {
            unavailable.add(new UnavailableMeasure("OPEN_CASES", "Open support cases",
                    "No case signal has been supplied for this account family. Case management is a "
                    + "later epic (E12); until it lands this figure is fed by an external service "
                    + "signal, and an absent signal is reported as unknown rather than as zero."));
        }
        if (family.slaBreaches() == null) {
            unavailable.add(new UnavailableMeasure("SLA_BREACHES", "Support promises missed",
                    "No SLA signal has been supplied for this account family."));
        }
        return new RollupView(accountId, anchor.name(), anchor.ultimateParentId(),
                anchor.ultimateParentName(), anchor.depth(), self, family,
                scope.restricted(), scope.restricted() ? scope.restrictionNote() : null,
                List.copyOf(unavailable));
    }

    private record Anchor(String name, String path, int depth, UUID ultimateParentId, String ultimateParentName) {}

    private Figures figures(String selfPath, boolean includeDescendants, RecordAccess.Scope scope) {
        UUID tenantId = TenantContext.get().tenantId();
        String pathPattern = includeDescendants ? selfPath + "%" : selfPath;
        List<Object> args = new ArrayList<>();

        args.add(tenantId);
        args.add(pathPattern);
        String accountOwnerFilter = scope.ownerPredicate("a.owner_id", args);
        args.add(tenantId);
        String oppOwnerFilter = scope.ownerPredicate("o.owner_id", args);
        args.add(tenantId);
        String activityOwnerFilter = scope.ownerPredicate("act.owner_id", args);
        args.add(tenantId);

        String sql = """
                with scope as (
                  select a.id
                  from crm.account a
                  where a.tenant_id = ? and a.deleted_at is null and a.hierarchy_path like ?
                """ + accountOwnerFilter + """
                ),
                opps as (
                  select o.amount, o.is_closed, coalesce(o.is_won, false) as is_won
                  from sales.opportunity o
                  where o.tenant_id = ? and o.account_id in (select id from scope)
                """ + oppOwnerFilter + opportunityTombstoneFilter() + """
                ),
                acts as (
                  select act.occurred_at
                  from engagement.activity act
                  where act.tenant_id = ? and act.deleted_at is null
                    and act.related_entity_type = 'ACCOUNT'
                    and act.related_entity_id in (select id from scope)
                """ + activityOwnerFilter + """
                ),
                sig as (
                  select s.signal_code, s.numeric_value, s.as_of, s.source_system
                  from crm.account_signal s
                  where s.tenant_id = ? and s.account_id in (select id from scope)
                )
                select
                  (select count(*) from scope)                                            as accounts_included,
                  coalesce((select sum(amount) from opps where not is_closed), 0)         as open_pipeline,
                  (select count(*) from opps where not is_closed)                         as open_count,
                  coalesce((select sum(amount) from opps where is_closed and is_won), 0)  as won_revenue,
                  (select count(*) from opps where is_closed and is_won)                  as won_count,
                  (select sum(numeric_value) from sig where signal_code = 'OPEN_CASES')   as open_cases,
                  (select sum(numeric_value) from sig where signal_code = 'SLA_BREACHES') as sla_breaches,
                  (select max(as_of) from sig where signal_code in ('OPEN_CASES','SLA_BREACHES')) as case_as_of,
                  (select min(source_system) from sig where signal_code in ('OPEN_CASES','SLA_BREACHES')) as case_source,
                  (select max(occurred_at) from acts)                                     as last_activity_at,
                  (select count(*) from acts where occurred_at >= now() - interval '90 days') as activity_90d
                """;

        Figures figures = jdbc.queryForObject(sql, (rs, i) -> new Figures(
                rs.getInt("accounts_included"),
                rs.getBigDecimal("open_pipeline"), rs.getInt("open_count"),
                rs.getBigDecimal("won_revenue"), rs.getInt("won_count"),
                rs.getBigDecimal("open_cases"), rs.getBigDecimal("sla_breaches"),
                rs.getString("case_source"), instant(rs.getTimestamp("case_as_of")),
                instant(rs.getTimestamp("last_activity_at")), rs.getInt("activity_90d")),
                args.toArray());
        return figures;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * E06 adds a tombstone column to {@code sales.opportunity} in a later
     * migration than this module's. Rather than reach into another epic's schema
     * or silently include tombstones once it arrives, the filter is added when
     * the column is actually there. Checked once and cached.
     */
    private String opportunityTombstoneFilter() {
        if (opportunityHasTombstone == null) {
            Boolean present = jdbc.query("""
                    select 1 from information_schema.columns
                    where table_schema = 'sales' and table_name = 'opportunity'
                      and column_name = 'deleted_at'
                    """, (ResultSetExtractor<Boolean>) rs -> rs.next());
            opportunityHasTombstone = Boolean.TRUE.equals(present);
        }
        return opportunityHasTombstone ? " and o.deleted_at is null" : "";
    }
}

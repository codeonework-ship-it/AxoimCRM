package com.axiom.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Maintains the read model: turns authoritative rows into fact rows.
 *
 * <h2>Projecting from the table, not from the event payload</h2>
 * A consumer hands this class a set of ids and gets the current authoritative
 * state written into the fact table. That single choice does most of the ADR-003
 * compliance work for free:
 * <ul>
 *   <li><b>Duplicate delivery</b> re-reads the same rows and writes the same
 *       values through {@code on conflict do update}. One fact row, every time —
 *       never two, and never a doubled measure.</li>
 *   <li><b>Out-of-order delivery</b> cannot move a projection backwards. The
 *       upsert carries {@code where excluded.source_updated_at &gt;
 *       fact.source_updated_at}, so an event that arrives behind a newer write is
 *       silently ignored rather than resurrecting stale state. ADR-003 guarantees
 *       ordering per {@code (tenant_id, entity_id)} but not globally, and a
 *       redelivery after a relay restart legitimately arrives late.</li>
 *   <li><b>Deletion</b> needs no event of its own: an id with no live source row
 *       yields no fact row, and {@link #prune} removes what is left. A soft delete
 *       and a hard delete therefore behave identically.</li>
 * </ul>
 *
 * <h2>Read-only against the domain, and outside the domain packages</h2>
 * This class reads {@code sales.opportunity}, {@code crm.lead}, {@code crm.account}
 * and {@code engagement.activity} with plain SQL. It never writes to them, never
 * imports their services and never depends on their DTOs — the same boundary
 * discipline {@code SearchProjector} keeps, and for the same ADR-006 reason.
 *
 * <h2>Where the derived measures are computed, and why here</h2>
 * {@code weighted_amount}, {@code acv}, {@code age_days} and {@code cycle_days}
 * are computed once, at projection time, rather than in each report's SQL. That
 * is what makes FR-RPT-009 hold in practice: two reports cannot disagree about
 * ACV if neither of them is allowed to compute it.
 */
@Component
public class ReadModelProjector {

    private final JdbcTemplate jdbc;

    public ReadModelProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ source paging

    /** Live source count, so backfill progress is a real number rather than a spinner. */
    @Transactional(readOnly = true)
    public long sourceCount(UUID tenantId, AnalyticsDataset dataset) {
        Long count = jdbc.queryForObject(
                "select count(*) from " + dataset.sourceTable() + " t where t.tenant_id = ?"
                        + (dataset.sourceSoftDeleted() ? " and t.deleted_at is null" : ""),
                Long.class, tenantId);
        return count == null ? 0L : count;
    }

    /**
     * Keyset paging on the primary key rather than OFFSET: a backfill must be
     * resumable from a stored cursor after a restart, and must not take a lock or
     * a long snapshot that a concurrent business write would queue behind.
     */
    @Transactional(readOnly = true)
    public List<UUID> sourceIdsAfter(UUID tenantId, AnalyticsDataset dataset, UUID afterId, int limit) {
        return jdbc.query(
                "select t.id from " + dataset.sourceTable() + " t where t.tenant_id = ?"
                        + (dataset.sourceSoftDeleted() ? " and t.deleted_at is null" : "")
                        + " and t.id > coalesce(?, '00000000-0000-0000-0000-000000000000'::uuid)"
                        + " order by t.id limit ?",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, afterId, limit);
    }

    // ------------------------------------------------------------------ projection

    /**
     * Project the given ids into the fact table.
     *
     * @param ids null or empty projects nothing; pass {@code null} to {@link #projectAll}
     * @return rows actually written — a row refused by the watermark guard is not counted,
     *         which is what makes "an older event changed nothing" observable rather than
     *         indistinguishable from success
     */
    @Transactional
    public int project(UUID tenantId, AnalyticsDataset dataset, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return jdbc.update(upsertSql(dataset, true), ps -> {
            ps.setObject(1, tenantId);
            ps.setArray(2, uuidArray(ps, ids));
        });
    }

    /** Full re-projection of one dataset for one tenant — the backfill path. */
    @Transactional
    public int projectAll(UUID tenantId, AnalyticsDataset dataset) {
        return jdbc.update(upsertSql(dataset, false), tenantId);
    }

    /**
     * Remove fact rows whose source record has gone.
     *
     * <p>A rebuild that only ever wrote would leave a record hard-deleted during
     * an outage visible in reports forever, so the prune is part of the projection
     * contract rather than a chore someone has to remember.
     */
    @Transactional
    public int prune(UUID tenantId, AnalyticsDataset dataset) {
        return jdbc.update("""
                delete from %s f
                 where f.tenant_id = ?
                   and not exists (select 1 from %s t
                                    where t.tenant_id = f.tenant_id and t.id = f.%s%s)
                """.formatted(dataset.factTable(), dataset.sourceTable(), dataset.idColumn(),
                dataset.sourceSoftDeleted() ? " and t.deleted_at is null" : ""), tenantId);
    }

    /**
     * Project stage occupancy from {@code sales.stage_history}.
     *
     * <p>Driven by the opportunity projection rather than by its own consumer: a
     * stage transition is only ever created alongside an opportunity event, so a
     * separate cursor would be a second thing to keep in step for no independent
     * gain. {@code exited_forward} — the numerator of stage conversion — is decided
     * here, once, so no report is in a position to decide it differently.
     *
     * @param ids null re-projects every transition for the tenant
     */
    @Transactional
    public int projectStageTransitions(UUID tenantId, List<UUID> opportunityIds) {
        boolean scoped = opportunityIds != null && !opportunityIds.isEmpty();
        String sql = """
                insert into analytics.stage_transition_fact (
                  transition_id, tenant_id, opportunity_id, owner_id, from_stage_id, to_stage_id,
                  to_stage_name, to_stage_order, transition_kind, entered_at, entered_on, exited_at,
                  duration_seconds, exited_forward, amount, source_updated_at, projected_at)
                select h.id, h.tenant_id, h.opportunity_id, o.owner_id, h.from_stage_id, h.to_stage_id,
                       st.name, st.sort_order, h.transition_kind, h.entered_at, h.entered_at::date,
                       h.exited_at, h.duration_seconds,
                       -- Forward exit: the occupancy has ended and the opportunity is now
                       -- in a stage at least as far along, or has closed won. A deal that
                       -- moved backwards, or closed lost out of this stage, did not convert.
                       (h.exited_at is not null
                        and (coalesce(cur.sort_order, -1) > coalesce(st.sort_order, 0)
                             or coalesce(o.is_won, false))),
                       o.amount, greatest(h.created_at, coalesce(h.exited_at, h.entered_at)), now()
                  from sales.stage_history h
                  join sales.opportunity o on o.tenant_id = h.tenant_id and o.id = h.opportunity_id
                  left join crm.pipeline_stage st on st.tenant_id = h.tenant_id and st.id = h.to_stage_id
                  left join crm.pipeline_stage cur on cur.tenant_id = o.tenant_id and cur.id = o.stage_id
                 where h.tenant_id = ?%s
                on conflict (transition_id) do update set
                  owner_id = excluded.owner_id, from_stage_id = excluded.from_stage_id,
                  to_stage_id = excluded.to_stage_id, to_stage_name = excluded.to_stage_name,
                  to_stage_order = excluded.to_stage_order, transition_kind = excluded.transition_kind,
                  entered_at = excluded.entered_at, entered_on = excluded.entered_on,
                  exited_at = excluded.exited_at, duration_seconds = excluded.duration_seconds,
                  exited_forward = excluded.exited_forward, amount = excluded.amount,
                  source_updated_at = excluded.source_updated_at, projected_at = now()
                 -- >= rather than > here, and deliberately: exited_forward depends on
                 -- the opportunity's CURRENT stage, which moves without the history row
                 -- changing at all. A strict watermark would freeze the flag at whatever
                 -- it was when the transition was written. Re-applying is still
                 -- idempotent — the recomputed row is a function of current state.
                 where excluded.source_updated_at >= stage_transition_fact.source_updated_at
                """.formatted(scoped ? " and h.opportunity_id = any(?)" : "");
        if (!scoped) return jdbc.update(sql, tenantId);
        return jdbc.update(sql, ps -> {
            ps.setObject(1, tenantId);
            ps.setArray(2, uuidArray(ps, opportunityIds));
        });
    }

    /**
     * Roll the derived account measures up from the other projections.
     *
     * <p>Runs after the opportunity and activity projections rather than inside
     * them: an account fact carries counts over its children, and recomputing
     * every account on every child event would make one stage change cost a full
     * scan. This is the one place the read model is derived from itself, and it is
     * covered by its own reconciliation check.
     */
    @Transactional
    public int refreshAccountRollups(UUID tenantId) {
        return jdbc.update("""
                update analytics.account_fact f set
                  open_opportunity_count = coalesce((
                      select count(*) from analytics.opportunity_fact ofact
                       where ofact.tenant_id = f.tenant_id and ofact.account_id = f.account_id
                         and ofact.is_closed = false), 0),
                  open_pipeline_amount = coalesce((
                      select sum(ofact.amount) from analytics.opportunity_fact ofact
                       where ofact.tenant_id = f.tenant_id and ofact.account_id = f.account_id
                         and ofact.is_closed = false), 0),
                  won_amount = coalesce((
                      select sum(ofact.amount) from analytics.opportunity_fact ofact
                       where ofact.tenant_id = f.tenant_id and ofact.account_id = f.account_id
                         and ofact.is_won = true), 0),
                  activity_count = coalesce((
                      select count(*) from analytics.activity_fact af
                       where af.tenant_id = f.tenant_id and af.account_id = f.account_id), 0),
                  last_activity_at = (
                      select max(af.occurred_at) from analytics.activity_fact af
                       where af.tenant_id = f.tenant_id and af.account_id = f.account_id),
                  projected_at = now()
                where f.tenant_id = ?
                """, tenantId);
    }

    // ------------------------------------------------------------------ the SQL

    /**
     * One upsert per dataset. {@code byIds} selects between the incremental form
     * (an id array bound as parameter 2) and the whole-tenant form.
     *
     * <p>The tail is identical in every branch and is the heart of the idempotence
     * guarantee, so it is appended once, in {@link #conflictTail}, rather than
     * copied four times where one copy could silently lose the watermark guard.
     */
    private String upsertSql(AnalyticsDataset dataset, boolean byIds) {
        String idFilter = byIds ? " and t.id = any(?)" : "";
        return switch (dataset) {
            case OPPORTUNITY -> """
                    insert into analytics.opportunity_fact (
                      opportunity_id, tenant_id, name, account_id, account_name, account_industry,
                      account_segment, account_territory, owner_id, owner_name, pipeline_id, stage_id,
                      stage_name, stage_sort_order, stage_is_closed, stage_is_won, forecast_category,
                      record_type, currency_code, amount, weighted_amount, recurring_amount,
                      one_time_amount, term_months, acv, arr, tcv, probability, close_date,
                      original_close_date, created_on, closed_at, is_closed, is_won, close_outcome,
                      slip_count, cumulative_slip_days, stage_entered_at, age_days, cycle_days,
                      source_updated_at, projected_at)
                    select t.id, t.tenant_id, t.name, t.account_id, acc.name, acc.industry,
                           acc.segment, acc.territory, t.owner_id, u.display_name, t.pipeline_id, t.stage_id,
                           st.name, st.sort_order, coalesce(st.is_closed, false), coalesce(st.is_won, false),
                           coalesce(t.forecast_category, st.forecast_category),
                           t.record_type, t.currency_code, t.amount,
                           round(t.amount * coalesce(t.probability, st.probability, 0) / 100.0, 4),
                           t.recurring_amount, t.one_time_amount, t.term_months,
                           -- ACV (doc 14 §3): total recurring value / term in years. A deal with
                           -- no term or no recurring component contributes nothing rather than
                           -- being counted at its total, which would overstate annualized value.
                           case when t.recurring_amount is not null and coalesce(t.term_months, 0) > 0
                                then round(t.recurring_amount / (t.term_months / 12.0), 4) end,
                           t.arr, t.tcv, coalesce(t.probability, st.probability),
                           t.close_date, t.original_close_date, t.created_at::date, t.closed_at,
                           t.is_closed, t.is_won,
                           case when not t.is_closed then null
                                when t.is_won then 'WON' else 'LOST' end,
                           t.slip_count, t.cumulative_slip_days, t.stage_entered_at,
                           greatest(0, (current_date - t.created_at::date))::int,
                           case when t.is_closed and t.closed_at is not null
                                then greatest(0, (t.closed_at::date - t.created_at::date))::int end,
                           t.updated_at, now()
                      from sales.opportunity t
                      left join crm.account acc on acc.tenant_id = t.tenant_id and acc.id = t.account_id
                      left join crm.pipeline_stage st on st.tenant_id = t.tenant_id and st.id = t.stage_id
                      left join identity.app_user u on u.tenant_id = t.tenant_id and u.id = t.owner_id
                     where t.tenant_id = ?%s
                    on conflict (opportunity_id) do update set
                      name = excluded.name, account_id = excluded.account_id,
                      account_name = excluded.account_name, account_industry = excluded.account_industry,
                      account_segment = excluded.account_segment, account_territory = excluded.account_territory,
                      owner_id = excluded.owner_id, owner_name = excluded.owner_name,
                      pipeline_id = excluded.pipeline_id, stage_id = excluded.stage_id,
                      stage_name = excluded.stage_name, stage_sort_order = excluded.stage_sort_order,
                      stage_is_closed = excluded.stage_is_closed, stage_is_won = excluded.stage_is_won,
                      forecast_category = excluded.forecast_category, record_type = excluded.record_type,
                      currency_code = excluded.currency_code, amount = excluded.amount,
                      weighted_amount = excluded.weighted_amount, recurring_amount = excluded.recurring_amount,
                      one_time_amount = excluded.one_time_amount, term_months = excluded.term_months,
                      acv = excluded.acv, arr = excluded.arr, tcv = excluded.tcv,
                      probability = excluded.probability, close_date = excluded.close_date,
                      original_close_date = excluded.original_close_date, created_on = excluded.created_on,
                      closed_at = excluded.closed_at, is_closed = excluded.is_closed,
                      is_won = excluded.is_won, close_outcome = excluded.close_outcome,
                      slip_count = excluded.slip_count, cumulative_slip_days = excluded.cumulative_slip_days,
                      stage_entered_at = excluded.stage_entered_at, age_days = excluded.age_days,
                      cycle_days = excluded.cycle_days, source_updated_at = excluded.source_updated_at,
                      projected_at = now()
                    """.formatted(idFilter) + conflictTail("analytics.opportunity_fact");

            case LEAD -> """
                    insert into analytics.lead_fact (
                      lead_id, tenant_id, full_name, company, owner_id, owner_name, status,
                      status_category, rating, source, campaign_code, territory, segment, score,
                      created_on, converted_at, converted_opportunity_id, disqualified_at,
                      disqualification_reason, is_converted, is_disqualified, sla_breached,
                      first_response_minutes, source_updated_at, projected_at)
                    select t.id, t.tenant_id,
                           -- A lead captured from a form may have no name at all. The fact
                           -- table's NOT NULL is what stops a report rendering a blank row
                           -- that looks like a data-loss bug; the placeholder says which it is.
                           coalesce(nullif(btrim(concat_ws(' ', t.first_name, t.last_name)), ''),
                                    coalesce(t.company, '(unnamed lead)')),
                           t.company, t.owner_id, u.display_name, t.status,
                           coalesce(ls.category, 'OPEN'), t.rating, t.source, t.campaign_code,
                           t.territory, t.segment, t.score, t.created_at::date,
                           t.converted_at, t.converted_opportunity_id, t.disqualified_at,
                           coalesce(t.disqualification_reason_code, t.disqualify_reason),
                           t.converted_at is not null, t.disqualified_at is not null,
                           t.sla_breached_at is not null,
                           case when t.first_responded_at is not null
                                then greatest(0, (extract(epoch from (t.first_responded_at - t.created_at)) / 60))::int end,
                           coalesce(t.updated_at, t.created_at), now()
                      from crm.lead t
                      left join identity.app_user u on u.tenant_id = t.tenant_id and u.id = t.owner_id
                      left join leads.lead_status ls on ls.tenant_id = t.tenant_id and ls.code = t.status
                     where t.tenant_id = ? and t.deleted_at is null%s
                    on conflict (lead_id) do update set
                      full_name = excluded.full_name, company = excluded.company,
                      owner_id = excluded.owner_id, owner_name = excluded.owner_name,
                      status = excluded.status, status_category = excluded.status_category,
                      rating = excluded.rating, source = excluded.source,
                      campaign_code = excluded.campaign_code, territory = excluded.territory,
                      segment = excluded.segment, score = excluded.score,
                      created_on = excluded.created_on, converted_at = excluded.converted_at,
                      converted_opportunity_id = excluded.converted_opportunity_id,
                      disqualified_at = excluded.disqualified_at,
                      disqualification_reason = excluded.disqualification_reason,
                      is_converted = excluded.is_converted, is_disqualified = excluded.is_disqualified,
                      sla_breached = excluded.sla_breached,
                      first_response_minutes = excluded.first_response_minutes,
                      source_updated_at = excluded.source_updated_at, projected_at = now()
                    """.formatted(idFilter) + conflictTail("analytics.lead_fact");

            case ACTIVITY -> """
                    insert into analytics.activity_fact (
                      activity_id, tenant_id, subject, activity_type, status, direction, outcome,
                      owner_id, owner_name, related_entity_type, related_entity_id, account_id,
                      account_name, occurred_on, occurred_at, completed_at, duration_minutes,
                      is_completed, source_updated_at, projected_at)
                    select t.id, t.tenant_id, coalesce(nullif(t.subject, ''), '(no subject)'),
                           t.activity_type, t.status, t.direction, t.outcome,
                           t.owner_id, u.display_name, t.related_entity_type, t.related_entity_id,
                           case when t.related_entity_type = 'ACCOUNT' then t.related_entity_id
                                when t.related_entity_type = 'CONTACT' then c.account_id
                                when t.related_entity_type = 'OPPORTUNITY' then o.account_id end,
                           acc.name,
                           coalesce(t.occurred_at, t.completed_at, t.due_at, t.created_at)::date,
                           coalesce(t.occurred_at, t.completed_at, t.due_at, t.created_at),
                           t.completed_at, t.duration_minutes,
                           t.status = 'COMPLETED', t.updated_at, now()
                      from engagement.activity t
                      left join identity.app_user u on u.tenant_id = t.tenant_id and u.id = t.owner_id
                      left join crm.contact c on c.tenant_id = t.tenant_id and c.id = t.related_entity_id
                                             and t.related_entity_type = 'CONTACT'
                      left join sales.opportunity o on o.tenant_id = t.tenant_id and o.id = t.related_entity_id
                                             and t.related_entity_type = 'OPPORTUNITY'
                      left join crm.account acc on acc.tenant_id = t.tenant_id and acc.id =
                           case when t.related_entity_type = 'ACCOUNT' then t.related_entity_id
                                when t.related_entity_type = 'CONTACT' then c.account_id
                                when t.related_entity_type = 'OPPORTUNITY' then o.account_id end
                     where t.tenant_id = ? and t.deleted_at is null%s
                    on conflict (activity_id) do update set
                      subject = excluded.subject, activity_type = excluded.activity_type,
                      status = excluded.status, direction = excluded.direction,
                      outcome = excluded.outcome, owner_id = excluded.owner_id,
                      owner_name = excluded.owner_name,
                      related_entity_type = excluded.related_entity_type,
                      related_entity_id = excluded.related_entity_id,
                      account_id = excluded.account_id, account_name = excluded.account_name,
                      occurred_on = excluded.occurred_on, occurred_at = excluded.occurred_at,
                      completed_at = excluded.completed_at, duration_minutes = excluded.duration_minutes,
                      is_completed = excluded.is_completed,
                      source_updated_at = excluded.source_updated_at, projected_at = now()
                    """.formatted(idFilter) + conflictTail("analytics.activity_fact");

            case ACCOUNT -> """
                    insert into analytics.account_fact (
                      account_id, tenant_id, name, industry, segment, territory, business_unit,
                      status, owner_id, owner_name, health_score, health_band, annual_revenue,
                      employee_count, contact_count, created_on, source_updated_at, projected_at)
                    select t.id, t.tenant_id, t.name, t.industry, t.segment, t.territory,
                           t.business_unit, t.status, t.owner_id, u.display_name,
                           t.health_score, t.health_band, t.annual_revenue, t.employee_count,
                           (select count(*) from crm.contact c
                             where c.tenant_id = t.tenant_id and c.account_id = t.id
                               and c.deleted_at is null),
                           t.created_at::date, t.updated_at, now()
                      from crm.account t
                      left join identity.app_user u on u.tenant_id = t.tenant_id and u.id = t.owner_id
                     where t.tenant_id = ? and t.deleted_at is null%s
                    on conflict (account_id) do update set
                      name = excluded.name, industry = excluded.industry, segment = excluded.segment,
                      territory = excluded.territory, business_unit = excluded.business_unit,
                      status = excluded.status, owner_id = excluded.owner_id,
                      owner_name = excluded.owner_name, health_score = excluded.health_score,
                      health_band = excluded.health_band, annual_revenue = excluded.annual_revenue,
                      employee_count = excluded.employee_count, contact_count = excluded.contact_count,
                      created_on = excluded.created_on, source_updated_at = excluded.source_updated_at,
                      projected_at = now()
                    """.formatted(idFilter) + conflictTail("analytics.account_fact");
        };
    }

    /**
     * THE WATERMARK GUARD.
     *
     * <p>Appended to every upsert in this class, and the single line that makes
     * out-of-order delivery harmless. Without it a redelivered event carrying an
     * older read of the row would overwrite a newer projection and the report would
     * disagree with the record page until the next organic edit — a class of bug
     * that is close to undiagnosable from a support ticket.
     *
     * <p>Strictly greater-than, not greater-or-equal: re-applying the identical
     * version is a no-op, which is what makes the redelivery case cost nothing.
     */
    private static String conflictTail(String factTable) {
        // The ON CONFLICT target is referenced by its bare relation name, which is
        // the alias PostgreSQL binds for the existing row.
        String relation = factTable.substring(factTable.indexOf('.') + 1);
        return " where excluded.source_updated_at > " + relation + ".source_updated_at";
    }

    private static Array uuidArray(PreparedStatement ps, List<UUID> values) throws SQLException {
        return ps.getConnection().createArrayOf("uuid", values.toArray());
    }
}

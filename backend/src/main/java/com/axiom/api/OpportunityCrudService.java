package com.axiom.api;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.pipeline.PipelineQueries;
import com.axiom.security.AuthorizationService;
import com.axiom.security.SecurableObject;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Complete record-form lifecycle for E06; governed stage/closure commands remain in the lifecycle engine. */
@Service
public class OpportunityCrudService {
    private static final int PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final PipelineQueries pipelines;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final OutboxWriter outbox;

    public OpportunityCrudService(JdbcTemplate jdbc, PipelineQueries pipelines,
                                  AuthorizationService authorization, AuditService audit, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.pipelines = pipelines;
        this.authorization = authorization;
        this.audit = audit;
        this.outbox = outbox;
    }

    public record OpportunityRequest(@NotBlank String name, @NotNull UUID accountId,
                                     UUID pipelineId, UUID stageId, @PositiveOrZero BigDecimal amount,
                                     UUID ownerId, LocalDate closeDate, String currencyCode,
                                     BigDecimal probability, String forecastCategory, String nextStep,
                                     String recordType) {}

    public record OpportunityUpdateRequest(@NotBlank String name, @NotNull UUID accountId,
                                           @PositiveOrZero BigDecimal amount, UUID ownerId,
                                           LocalDate closeDate, String currencyCode, BigDecimal probability,
                                           String forecastCategory, String nextStep, long expectedVersion) {}

    public record OpportunityDetail(UUID id, String name, UUID accountId, String accountName,
                                    UUID pipelineId, String pipelineName, UUID stageId, String stageName,
                                    BigDecimal amount, UUID ownerId, String ownerName, LocalDate closeDate,
                                    String currencyCode, BigDecimal probability, String forecastCategory,
                                    String nextStep, boolean closed, Boolean won, int slipCount,
                                    int reopenCount, Instant createdAt, Instant updatedAt, long version) {}

    @Transactional(readOnly = true)
    public PageResult<OpportunityDetail> list(String search, UUID pipelineId, UUID stageId, int requestedPage) {
        int page = Math.max(0, requestedPage);
        AuthorizationService.RecordPredicate predicate = authorization.visibleRecordPredicate(
                SecurableObject.OPPORTUNITY, "o");
        if (predicate.deniesEverything()) return PageResult.of(List.of(), page, PAGE_SIZE, 0);
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        args.addAll(predicate.args());
        StringBuilder where = new StringBuilder(" where o.tenant_id = ? and (").append(predicate.sql()).append(")");
        if (search != null && !search.isBlank()) {
            where.append(" and (lower(o.name) like ? or lower(a.name) like ?)");
            String like = "%" + search.trim().toLowerCase() + "%";
            args.add(like); args.add(like);
        }
        if (pipelineId != null) { where.append(" and o.pipeline_id = ?"); args.add(pipelineId); }
        if (stageId != null) { where.append(" and o.stage_id = ?"); args.add(stageId); }
        long total = jdbc.queryForObject("select count(*) from sales.opportunity o join crm.account a on a.tenant_id=o.tenant_id and a.id=o.account_id" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(PAGE_SIZE); pageArgs.add(page * PAGE_SIZE);
        List<OpportunityDetail> rows = jdbc.query(SELECT + where + " order by o.updated_at desc, o.id limit ? offset ?",
                OpportunityCrudService::map, pageArgs.toArray());
        return PageResult.of(rows, page, PAGE_SIZE, total);
    }

    @Transactional(readOnly = true)
    public OpportunityDetail get(UUID id) {
        authorization.requireRead(SecurableObject.OPPORTUNITY, id);
        List<OpportunityDetail> rows = jdbc.query(SELECT + " where o.tenant_id = ? and o.id = ?",
                OpportunityCrudService::map, TenantContext.get().tenantId(), id);
        if (rows.isEmpty()) throw new NotFoundException("Opportunity not found");
        return rows.get(0);
    }

    @Transactional
    public OpportunityDetail create(OpportunityRequest request) {
        authorization.requireCreate(SecurableObject.OPPORTUNITY);
        authorization.requireRead(SecurableObject.ACCOUNT, request.accountId());
        UUID pipelineId = request.pipelineId() == null ? pipelines.defaultPipelineId() : request.pipelineId();
        UUID stageId = request.stageId();
        if (stageId == null) stageId = pipelines.firstOpenStage(pipelineId).id();
        PipelineQueries.StageRow stage = pipelines.stage(stageId);
        if (!stage.pipelineId().equals(pipelineId) || stage.closed()) {
            throw new ConflictException("Choose an open stage belonging to the selected pipeline");
        }
        verifyOwner(request.ownerId());
        UUID id = jdbc.queryForObject("""
                insert into sales.opportunity
                  (tenant_id, name, account_id, pipeline_id, stage_id, amount, owner_id, close_date,
                   currency_code, probability, forecast_category, next_step, record_type)
                values (?, ?, ?, ?, ?, ?, ?, ?, coalesce(?, 'USD'), coalesce(?, ?),
                        coalesce(?, ?), ?, coalesce(?, 'STANDARD')) returning id
                """, UUID.class, TenantContext.get().tenantId(), request.name().trim(), request.accountId(),
                pipelineId, stageId, request.amount() == null ? BigDecimal.ZERO : request.amount(),
                request.ownerId() == null ? TenantContext.get().userId() : request.ownerId(), request.closeDate(),
                clean(request.currencyCode()), request.probability(), stage.probability(),
                clean(request.forecastCategory()), stage.forecastCategory(), clean(request.nextStep()),
                clean(request.recordType()));
        audit.record("OPPORTUNITY_CREATE", "OPPORTUNITY", id, "Created opportunity " + request.name().trim(),
                Map.of("accountId", request.accountId().toString(), "pipelineId", pipelineId.toString(),
                        "stageId", stageId.toString()));
        outbox.write("opportunity", id, "opportunity.created", Map.of("opportunityId", id.toString(),
                "accountId", request.accountId().toString(), "stageId", stageId.toString()));
        return get(id);
    }

    @Transactional
    public OpportunityDetail update(UUID id, OpportunityUpdateRequest request) {
        authorization.requireEdit(SecurableObject.OPPORTUNITY, id);
        authorization.requireRead(SecurableObject.ACCOUNT, request.accountId());
        OpportunityDetail before = get(id);
        if (before.closed()) throw new ConflictException("Closed opportunities are read-only. Reopen it before editing.");
        verifyOwner(request.ownerId());
        int changed = jdbc.update("""
                update sales.opportunity set name=?, account_id=?, amount=?, owner_id=coalesce(?, owner_id),
                    close_date=?, currency_code=coalesce(?, currency_code), probability=coalesce(?, probability),
                    forecast_category=coalesce(?, forecast_category), next_step=?, updated_at=now(), version=version+1
                where tenant_id=? and id=? and version=? and is_closed=false
                """, request.name().trim(), request.accountId(),
                request.amount() == null ? BigDecimal.ZERO : request.amount(), request.ownerId(),
                request.closeDate(), clean(request.currencyCode()), request.probability(),
                clean(request.forecastCategory()), clean(request.nextStep()), TenantContext.get().tenantId(), id,
                request.expectedVersion());
        if (changed == 0) throw new ConflictException("This opportunity changed while you were editing it. Reload and try again.");
        audit.record("OPPORTUNITY_UPDATE", "OPPORTUNITY", id, "Updated opportunity " + before.name(),
                Map.of("fromVersion", request.expectedVersion(), "before", Map.of("name", before.name()),
                        "after", Map.of("name", request.name().trim())));
        outbox.write("opportunity", id, "opportunity.updated", Map.of("opportunityId", id.toString(),
                "fromVersion", request.expectedVersion()));
        return get(id);
    }

    private void verifyOwner(UUID ownerId) {
        if (ownerId == null) return;
        Integer count = jdbc.queryForObject("select count(*) from identity.app_user where tenant_id=? and id=? and active",
                Integer.class, TenantContext.get().tenantId(), ownerId);
        if (count == null || count == 0) throw new NotFoundException("Active opportunity owner not found");
    }

    private static final String SELECT = """
            select o.id,o.name,o.account_id,a.name account_name,o.pipeline_id,p.name pipeline_name,
                   o.stage_id,s.name stage_name,o.amount,o.owner_id,u.display_name owner_name,o.close_date,
                   o.currency_code,o.probability,o.forecast_category,o.next_step,o.is_closed,o.is_won,
                   o.slip_count,o.reopen_count,o.created_at,o.updated_at,o.version
            from sales.opportunity o
            join crm.account a on a.tenant_id=o.tenant_id and a.id=o.account_id
            join pipeline.pipeline p on p.tenant_id=o.tenant_id and p.id=o.pipeline_id
            join crm.pipeline_stage s on s.tenant_id=o.tenant_id and s.id=o.stage_id
            left join identity.app_user u on u.tenant_id=o.tenant_id and u.id=o.owner_id
            """;

    private static OpportunityDetail map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new OpportunityDetail(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("account_id", UUID.class), rs.getString("account_name"),
                rs.getObject("pipeline_id", UUID.class), rs.getString("pipeline_name"),
                rs.getObject("stage_id", UUID.class), rs.getString("stage_name"), rs.getBigDecimal("amount"),
                rs.getObject("owner_id", UUID.class), rs.getString("owner_name"),
                rs.getObject("close_date", LocalDate.class), rs.getString("currency_code"),
                rs.getBigDecimal("probability"), rs.getString("forecast_category"), rs.getString("next_step"),
                rs.getBoolean("is_closed"), rs.getObject("is_won") == null ? null : rs.getBoolean("is_won"),
                rs.getInt("slip_count"), rs.getInt("reopen_count"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}

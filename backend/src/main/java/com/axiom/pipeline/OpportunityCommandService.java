package com.axiom.pipeline;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes to the opportunity's children: line items, splits, competitors,
 * qualification answers, contact roles and approval state.
 *
 * <p>Every method loads the opportunity through
 * {@link OpportunityLifecycleService#requireOpen} first, so FR-OPP-012's
 * read-only-when-closed rule holds for all of them without being restated.
 */
@Service
public class OpportunityCommandService {

    private static final String ECONOMIC_BUYER = "ECONOMIC_BUYER";
    private static final Set<String> CONTACT_ROLES =
            Set.of(ECONOMIC_BUYER, "CHAMPION", "TECHNICAL_EVALUATOR", "INFLUENCER", "BLOCKER");
    private static final Set<String> POSITIONS =
            Set.of("LEADING", "THREAT", "TRAILING", "ELIMINATED", "UNKNOWN");
    private static final Set<String> APPROVAL_STATES = Set.of("REQUESTED", "APPROVED", "REJECTED");
    private static final BigDecimal HUNDRED = new BigDecimal("100.000");

    private final JdbcTemplate jdbc;
    private final PipelineQueries queries;
    private final OpportunityLifecycleService lifecycle;
    private final AuditService audit;

    public OpportunityCommandService(JdbcTemplate jdbc, PipelineQueries queries,
                                     OpportunityLifecycleService lifecycle, AuditService audit) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.lifecycle = lifecycle;
        this.audit = audit;
    }

    private static UUID tenantId() {
        return TenantContext.get().tenantId();
    }

    private OpportunityLifecycleService.OpportunityState open(UUID opportunityId, Long expectedVersion) {
        PipelinePermissions.requireWrite(TenantContext.get().role());
        OpportunityLifecycleService.OpportunityState opp = lifecycle.load(opportunityId);
        lifecycle.requireOpen(opp);
        lifecycle.requireVersion(opp, expectedVersion);
        return opp;
    }

    // ---------------------------------------------------------- FR-OPP-005 lines

    public record LineRequest(@NotNull UUID priceBookEntryId,
                              @NotNull BigDecimal quantity,
                              BigDecimal discountPct,
                              BigDecimal overrideTotal,
                              String overrideReason,
                              Integer sortOrder,
                              Long expectedVersion) {}

    public record LineRow(UUID id, UUID priceBookEntryId, String productCode, String productName,
                          String unitOfMeasure, BigDecimal quantity, BigDecimal listPrice,
                          BigDecimal discountPct, BigDecimal salePrice, BigDecimal computedTotal,
                          BigDecimal total, boolean totalIsOverridden, String overrideReason,
                          BigDecimal cost, BigDecimal margin, int sortOrder) {}

    @Transactional
    public LineRow addLine(UUID opportunityId, LineRequest request) {
        OpportunityLifecycleService.OpportunityState opp = open(opportunityId, request.expectedVersion());
        PipelineQueries.PriceBookEntryRow entry = priceBookEntry(request.priceBookEntryId());
        LineMath.Totals totals = LineMath.compute(request.quantity(), entry.listPrice(), request.discountPct(),
                request.overrideTotal(), request.overrideReason(), entry.unitCost());

        UUID id = jdbc.queryForObject("""
                insert into sales.opportunity_line
                  (tenant_id, opportunity_id, price_book_entry_id, product_code, product_name, unit_of_measure,
                   quantity, list_price, discount_pct, sale_price, computed_total, total, total_is_overridden,
                   override_reason, unit_cost, cost, margin, sort_order)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, tenantId(), opportunityId, entry.id(), entry.productCode(), entry.productName(),
                entry.unitOfMeasure(), request.quantity(), entry.listPrice(),
                request.discountPct() == null ? BigDecimal.ZERO : request.discountPct(),
                totals.salePrice(), totals.computedTotal(), totals.total(), totals.overridden(),
                totals.overridden() ? request.overrideReason().trim() : null,
                entry.unitCost(), totals.cost(), totals.margin(),
                request.sortOrder() == null ? nextSortOrder(opportunityId) : request.sortOrder());

        BigDecimal amount = resyncAmount(opportunityId);
        audit.record("OPPORTUNITY_LINE_ADD", "OPPORTUNITY", opportunityId,
                "Added line " + entry.productName() + " to " + opp.name(),
                map("productCode", entry.productCode(), "quantity", request.quantity(),
                        "computedTotal", totals.computedTotal(), "total", totals.total(),
                        "totalIsOverridden", totals.overridden(), "opportunityAmount", amount));
        return line(opportunityId, id);
    }

    @Transactional
    public LineRow updateLine(UUID opportunityId, UUID lineId, LineRequest request) {
        OpportunityLifecycleService.OpportunityState opp = open(opportunityId, request.expectedVersion());
        LineRow existing = line(opportunityId, lineId);
        PipelineQueries.PriceBookEntryRow entry = priceBookEntry(request.priceBookEntryId());
        LineMath.Totals totals = LineMath.compute(request.quantity(), entry.listPrice(), request.discountPct(),
                request.overrideTotal(), request.overrideReason(), entry.unitCost());

        jdbc.update("""
                update sales.opportunity_line
                set price_book_entry_id = ?, product_code = ?, product_name = ?, unit_of_measure = ?,
                    quantity = ?, list_price = ?, discount_pct = ?, sale_price = ?, computed_total = ?,
                    total = ?, total_is_overridden = ?, override_reason = ?, unit_cost = ?, cost = ?,
                    margin = ?, sort_order = ?, updated_at = now()
                where tenant_id = ? and opportunity_id = ? and id = ?
                """, entry.id(), entry.productCode(), entry.productName(), entry.unitOfMeasure(),
                request.quantity(), entry.listPrice(),
                request.discountPct() == null ? BigDecimal.ZERO : request.discountPct(),
                totals.salePrice(), totals.computedTotal(), totals.total(), totals.overridden(),
                totals.overridden() ? request.overrideReason().trim() : null,
                entry.unitCost(), totals.cost(), totals.margin(),
                request.sortOrder() == null ? existing.sortOrder() : request.sortOrder(),
                tenantId(), opportunityId, lineId);

        BigDecimal amount = resyncAmount(opportunityId);
        audit.record("OPPORTUNITY_LINE_UPDATE", "OPPORTUNITY", opportunityId,
                "Updated line " + entry.productName() + " on " + opp.name(),
                map("lineId", lineId.toString(), "computedTotal", totals.computedTotal(),
                        "total", totals.total(), "totalIsOverridden", totals.overridden(),
                        "previousTotal", existing.total(), "opportunityAmount", amount));
        return line(opportunityId, lineId);
    }

    @Transactional
    public void deleteLine(UUID opportunityId, UUID lineId) {
        OpportunityLifecycleService.OpportunityState opp = open(opportunityId, null);
        LineRow existing = line(opportunityId, lineId);
        jdbc.update("delete from sales.opportunity_line where tenant_id = ? and opportunity_id = ? and id = ?",
                tenantId(), opportunityId, lineId);
        BigDecimal amount = resyncAmount(opportunityId);
        audit.record("OPPORTUNITY_LINE_DELETE", "OPPORTUNITY", opportunityId,
                "Removed line " + existing.productName() + " from " + opp.name(),
                map("lineId", lineId.toString(), "total", existing.total(), "opportunityAmount", amount));
    }

    private PipelineQueries.PriceBookEntryRow priceBookEntry(UUID id) {
        return queries.priceBookEntries().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Price book entry not found or inactive: " + id
                        + ". Pick a product from the active price book."));
    }

    private int nextSortOrder(UUID opportunityId) {
        Integer max = jdbc.queryForObject("""
                select coalesce(max(sort_order), 0) + 10 from sales.opportunity_line
                where tenant_id = ? and opportunity_id = ?
                """, Integer.class, tenantId(), opportunityId);
        return max == null ? 10 : max;
    }

    LineRow line(UUID opportunityId, UUID lineId) {
        List<LineRow> rows = jdbc.query(LINE_SELECT + " and l.id = ?", OpportunityCommandService::mapLine,
                tenantId(), opportunityId, lineId);
        if (rows.isEmpty()) throw new NotFoundException("Opportunity line not found: " + lineId);
        return rows.get(0);
    }

    static final String LINE_SELECT = """
            select l.id, l.price_book_entry_id, l.product_code, l.product_name, l.unit_of_measure,
                   l.quantity, l.list_price, l.discount_pct, l.sale_price, l.computed_total, l.total,
                   l.total_is_overridden, l.override_reason, l.cost, l.margin, l.sort_order
            from sales.opportunity_line l
            where l.tenant_id = ? and l.opportunity_id = ?
            """;

    static LineRow mapLine(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new LineRow(
                rs.getObject("id", UUID.class),
                rs.getObject("price_book_entry_id", UUID.class),
                rs.getString("product_code"),
                rs.getString("product_name"),
                rs.getString("unit_of_measure"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("list_price"),
                rs.getBigDecimal("discount_pct"),
                rs.getBigDecimal("sale_price"),
                rs.getBigDecimal("computed_total"),
                rs.getBigDecimal("total"),
                rs.getBoolean("total_is_overridden"),
                rs.getString("override_reason"),
                rs.getBigDecimal("cost"),
                rs.getBigDecimal("margin"),
                rs.getInt("sort_order"));
    }

    /**
     * With line items present the amount is the sum of their totals — including
     * any override, because the override is what the business will invoice.
     * `computed_total` remains on each line as the evidence of the calculation.
     */
    private BigDecimal resyncAmount(UUID opportunityId) {
        Integer lineCount = jdbc.queryForObject(
                "select count(*) from sales.opportunity_line where tenant_id = ? and opportunity_id = ?",
                Integer.class, tenantId(), opportunityId);
        if (lineCount == null || lineCount == 0) {
            jdbc.update("update sales.opportunity set updated_at = now(), version = version + 1 "
                    + "where tenant_id = ? and id = ?", tenantId(), opportunityId);
            lifecycle.recordState(opportunityId, "LINES_CHANGED");
            return jdbc.queryForObject("select amount from sales.opportunity where tenant_id = ? and id = ?",
                    BigDecimal.class, tenantId(), opportunityId);
        }
        BigDecimal amount = jdbc.queryForObject("""
                update sales.opportunity o
                set amount = coalesce((select sum(l.total) from sales.opportunity_line l
                                        where l.tenant_id = o.tenant_id and l.opportunity_id = o.id), 0),
                    updated_at = now(), version = version + 1
                where o.tenant_id = ? and o.id = ?
                returning o.amount
                """, BigDecimal.class, tenantId(), opportunityId);
        resyncSplitAmounts(opportunityId);
        lifecycle.recordState(opportunityId, "LINES_CHANGED");
        return amount;
    }

    // --------------------------------------------------------- FR-OPP-006 splits

    public record SplitEntry(@NotNull UUID userId, @NotNull BigDecimal percentage, String note) {}

    public record SplitRequest(@NotBlank String splitType, @Valid List<SplitEntry> splits, Long expectedVersion) {}

    public record SplitRow(UUID id, UUID userId, String userName, String splitType,
                           BigDecimal percentage, BigDecimal amount, String note) {}

    @Transactional
    public List<SplitRow> replaceSplits(UUID opportunityId, SplitRequest request) {
        OpportunityLifecycleService.OpportunityState opp = open(opportunityId, request.expectedVersion());
        String type = request.splitType() == null ? "" : request.splitType().trim().toUpperCase(Locale.ROOT);
        if (!type.equals("REVENUE") && !type.equals("OVERLAY")) {
            throw new ConflictException("Split type must be REVENUE or OVERLAY, not '" + request.splitType() + "'.");
        }
        List<SplitEntry> entries = request.splits() == null ? List.of() : request.splits();

        BigDecimal total = BigDecimal.ZERO;
        List<UUID> seen = new ArrayList<>();
        for (SplitEntry entry : entries) {
            if (entry.percentage() == null || entry.percentage().signum() <= 0
                    || entry.percentage().compareTo(HUNDRED) > 0) {
                throw new ConflictException("Each split percentage must be above 0% and at most 100%. "
                        + "Fix the entry for user " + entry.userId() + ".");
            }
            if (seen.contains(entry.userId())) {
                throw new ConflictException("User " + entry.userId() + " appears twice in the "
                        + type.toLowerCase(Locale.ROOT) + " splits. Combine their share into a single entry.");
            }
            seen.add(entry.userId());
            total = total.add(entry.percentage());
        }

        // FR-OPP-006 — revenue splits must total exactly 100%; overlay splits are
        // deliberately unconstrained (overlay credit is additive recognition, not
        // a division of the same revenue).
        if (type.equals("REVENUE") && !entries.isEmpty()) {
            BigDecimal scaled = total.setScale(3, RoundingMode.HALF_UP);
            int comparison = scaled.compareTo(HUNDRED);
            if (comparison != 0) {
                BigDecimal gap = HUNDRED.subtract(scaled).abs();
                throw new ConflictException("Revenue splits on '" + opp.name() + "' total "
                        + scaled.toPlainString() + "% — that is " + gap.toPlainString() + "% "
                        + (comparison < 0 ? "short of" : "over") + " the required 100%. "
                        + (comparison < 0
                            ? "Increase a contributor's share by " + gap.toPlainString() + "% or add the missing contributor."
                            : "Reduce a contributor's share by " + gap.toPlainString() + "%."));
            }
        }

        jdbc.update("delete from sales.opportunity_split where tenant_id = ? and opportunity_id = ? and split_type = ?",
                tenantId(), opportunityId, type);
        BigDecimal amount = opp.amount() == null ? BigDecimal.ZERO : opp.amount();
        for (SplitEntry entry : entries) {
            try {
                jdbc.update("""
                        insert into sales.opportunity_split
                          (tenant_id, opportunity_id, user_id, split_type, percentage, amount, note)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """, tenantId(), opportunityId, entry.userId(), type, entry.percentage(),
                        share(amount, entry.percentage()), OpportunityLifecycleService.trimToNull(entry.note()));
            } catch (DataIntegrityViolationException ex) {
                throw new NotFoundException("User " + entry.userId()
                        + " is not a member of this workspace, so revenue cannot be credited to them.");
            }
        }

        audit.record("OPPORTUNITY_SPLIT_REPLACE", "OPPORTUNITY", opportunityId,
                type + " splits updated on " + opp.name(),
                map("splitType", type, "entryCount", entries.size(), "totalPercentage", total));
        return splits(opportunityId, type);
    }

    private void resyncSplitAmounts(UUID opportunityId) {
        jdbc.update("""
                update sales.opportunity_split s
                set amount = round(o.amount * s.percentage / 100, 2)
                from sales.opportunity o
                where o.tenant_id = s.tenant_id and o.id = s.opportunity_id
                  and s.tenant_id = ? and s.opportunity_id = ?
                """, tenantId(), opportunityId);
    }

    static BigDecimal share(BigDecimal amount, BigDecimal percentage) {
        return amount.multiply(percentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public List<SplitRow> splits(UUID opportunityId, String splitType) {
        String type = splitType == null || splitType.isBlank() ? null
                : splitType.trim().toUpperCase(Locale.ROOT);
        return jdbc.query("""
                select s.id, s.user_id, u.display_name as user_name, s.split_type, s.percentage, s.amount, s.note
                from sales.opportunity_split s
                left join identity.app_user u on u.tenant_id = s.tenant_id and u.id = s.user_id
                where s.tenant_id = ? and s.opportunity_id = ? and (? is null or s.split_type = ?)
                order by s.split_type, s.percentage desc
                """, (rs, i) -> new SplitRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("user_name"),
                rs.getString("split_type"),
                rs.getBigDecimal("percentage"),
                rs.getBigDecimal("amount"),
                rs.getString("note")), tenantId(), opportunityId, type, type);
    }

    // ---------------------------------------------------- FR-OPP-007 competitors

    public record CompetitorRequest(@NotNull UUID competitorId, @NotBlank String position,
                                    Boolean incumbent, String notes) {}

    public record OpportunityCompetitorRow(UUID id, UUID competitorId, String name, String position,
                                           boolean incumbent, String notes) {}

    @Transactional
    public OpportunityCompetitorRow upsertCompetitor(UUID opportunityId, CompetitorRequest request) {
        OpportunityLifecycleService.OpportunityState opp = open(opportunityId, null);
        String position = request.position() == null ? "" : request.position().trim().toUpperCase(Locale.ROOT);
        if (!POSITIONS.contains(position)) {
            throw new ConflictException("Competitor position must be one of " + POSITIONS
                    + ", not '" + request.position() + "'.");
        }
        PipelineQueries.CompetitorRow competitor = queries.competitors().stream()
                .filter(c -> c.id().equals(request.competitorId())).findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "Competitor not found in this workspace: " + request.competitorId()));

        UUID id = jdbc.queryForObject("""
                insert into sales.opportunity_competitor
                  (tenant_id, opportunity_id, competitor_id, position, is_incumbent, notes)
                values (?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, opportunity_id, competitor_id) do update
                  set position = excluded.position, is_incumbent = excluded.is_incumbent,
                      notes = excluded.notes, updated_at = now()
                returning id
                """, UUID.class, tenantId(), opportunityId, request.competitorId(), position,
                Boolean.TRUE.equals(request.incumbent()), OpportunityLifecycleService.trimToNull(request.notes()));

        audit.record("OPPORTUNITY_COMPETITOR_UPSERT", "OPPORTUNITY", opportunityId,
                competitor.name() + " recorded on " + opp.name() + " as " + position,
                map("competitor", competitor.name(), "position", position,
                        "incumbent", Boolean.TRUE.equals(request.incumbent())));
        return new OpportunityCompetitorRow(id, request.competitorId(), competitor.name(), position,
                Boolean.TRUE.equals(request.incumbent()), OpportunityLifecycleService.trimToNull(request.notes()));
    }

    @Transactional
    public void removeCompetitor(UUID opportunityId, UUID competitorId) {
        OpportunityLifecycleService.OpportunityState opp = open(opportunityId, null);
        int removed = jdbc.update("""
                delete from sales.opportunity_competitor
                where tenant_id = ? and opportunity_id = ? and competitor_id = ?
                """, tenantId(), opportunityId, competitorId);
        if (removed == 0) {
            throw new NotFoundException("That competitor is not recorded on this opportunity");
        }
        audit.record("OPPORTUNITY_COMPETITOR_REMOVE", "OPPORTUNITY", opportunityId,
                "Competitor removed from " + opp.name(), map("competitorId", competitorId.toString()));
    }

    // -------------------------------------------------- FR-OPP-008 qualification

    public record QualificationAnswer(@NotBlank String itemCode, Boolean answered, String value) {}

    public record QualificationRequest(@Valid List<QualificationAnswer> answers, Long expectedVersion) {}

    public record QualificationResult(UUID opportunityId, String frameworkCode, String frameworkName,
                                      BigDecimal score, int answered, int total) {}

    @Transactional
    public QualificationResult saveQualification(UUID opportunityId, QualificationRequest request) {
        OpportunityLifecycleService.OpportunityState opp = open(opportunityId, request.expectedVersion());
        Map<String, Object> framework = frameworkFor(opportunityId);
        UUID frameworkId = (UUID) framework.get("id");

        for (QualificationAnswer answer : request.answers() == null ? List.<QualificationAnswer>of() : request.answers()) {
            String code = answer.itemCode() == null ? "" : answer.itemCode().trim().toUpperCase(Locale.ROOT);
            List<UUID> itemIds = jdbc.queryForList("""
                    select id from pipeline.qualification_item
                    where tenant_id = ? and framework_id = ? and code = ?
                    """, UUID.class, tenantId(), frameworkId, code);
            if (itemIds.isEmpty()) {
                throw new NotFoundException("'" + answer.itemCode() + "' is not an item of the "
                        + framework.get("code") + " framework configured on this opportunity.");
            }
            boolean answered = Boolean.TRUE.equals(answer.answered());
            String value = OpportunityLifecycleService.trimToNull(answer.value());
            if (answered && value == null) {
                throw new ConflictException("Marking '" + code + "' as complete needs the evidence written down. "
                        + "Enter what you learned, or leave the item incomplete.");
            }
            jdbc.update("""
                    insert into sales.opportunity_qualification
                      (tenant_id, opportunity_id, item_id, answered, value, updated_by)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (tenant_id, opportunity_id, item_id) do update
                      set answered = excluded.answered, value = excluded.value,
                          updated_at = now(), updated_by = excluded.updated_by
                    """, tenantId(), opportunityId, itemIds.get(0), answered, value,
                    TenantContext.get().userId());
        }

        BigDecimal score = recomputeQualificationScore(opportunityId, frameworkId);
        Map<String, Object> counts = jdbc.queryForMap("""
                select count(*) filter (where q.answered) as answered, count(i.id) as total
                from pipeline.qualification_item i
                left join sales.opportunity_qualification q
                       on q.tenant_id = i.tenant_id and q.item_id = i.id and q.opportunity_id = ?
                where i.tenant_id = ? and i.framework_id = ?
                """, opportunityId, tenantId(), frameworkId);

        audit.record("OPPORTUNITY_QUALIFICATION_SAVE", "OPPORTUNITY", opportunityId,
                "Qualification updated on " + opp.name(),
                map("framework", framework.get("code"), "score", score));
        return new QualificationResult(opportunityId, (String) framework.get("code"),
                (String) framework.get("name"), score,
                ((Number) counts.get("answered")).intValue(), ((Number) counts.get("total")).intValue());
    }

    Map<String, Object> frameworkFor(UUID opportunityId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select f.id, f.code, f.name, f.kind
                from sales.opportunity o
                join pipeline.qualification_framework f
                  on f.tenant_id = o.tenant_id and f.id = o.qualification_framework_id
                where o.tenant_id = ? and o.id = ?
                """, tenantId(), opportunityId);
        if (rows.isEmpty()) {
            throw new NotFoundException("No qualification framework is enabled for this opportunity. "
                    + "Ask an administrator to enable MEDDICC, SPICED or a custom framework.");
        }
        return rows.get(0);
    }

    private BigDecimal recomputeQualificationScore(UUID opportunityId, UUID frameworkId) {
        BigDecimal score = jdbc.queryForObject("""
                update sales.opportunity o
                set qualification_score = coalesce((
                      select round(100 * sum(case when q.answered then i.weight else 0 end) / sum(i.weight), 2)
                      from pipeline.qualification_item i
                      left join sales.opportunity_qualification q
                             on q.tenant_id = i.tenant_id and q.item_id = i.id and q.opportunity_id = o.id
                      where i.tenant_id = o.tenant_id and i.framework_id = ?), 0),
                    updated_at = now(), version = version + 1
                where o.tenant_id = ? and o.id = ?
                returning o.qualification_score
                """, BigDecimal.class, frameworkId, tenantId(), opportunityId);
        return score == null ? BigDecimal.ZERO : score;
    }

    // ------------------------------------------------------------- contact roles

    public record ContactRoleRequest(@NotNull UUID contactId, @NotBlank String role) {}

    @Transactional
    public UUID addContactRole(UUID opportunityId, UUID contactId, String role) {
        OpportunityLifecycleService.OpportunityState opp = open(opportunityId, null);
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!CONTACT_ROLES.contains(normalized)) {
            throw new ConflictException("Unknown contact role: " + role + " (expected one of " + CONTACT_ROLES + ")");
        }
        Integer existing = jdbc.queryForObject("""
                select count(*) from sales.opportunity_contact_role
                where tenant_id = ? and opportunity_id = ? and contact_id = ? and role = ?
                """, Integer.class, tenantId(), opportunityId, contactId, normalized);
        if (existing != null && existing > 0) {
            throw new ConflictException("This contact already holds role " + normalized + " on the opportunity");
        }
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into sales.opportunity_contact_role (tenant_id, opportunity_id, contact_id, role)
                    values (?, ?, ?, ?) returning id
                    """, UUID.class, tenantId(), opportunityId, contactId, normalized);
        } catch (DataIntegrityViolationException ex) {
            throw new NotFoundException("Contact not found in this workspace: " + contactId);
        }
        audit.record("OPPORTUNITY_CONTACT_ROLE_ADD", "OPPORTUNITY", opportunityId,
                normalized + " role recorded on " + opp.name(),
                map("contactId", contactId.toString(), "role", normalized));
        return id;
    }

    @Transactional
    public void removeContactRole(UUID opportunityId, UUID roleId) {
        OpportunityLifecycleService.OpportunityState opp = open(opportunityId, null);
        int removed = jdbc.update("""
                delete from sales.opportunity_contact_role
                where tenant_id = ? and opportunity_id = ? and id = ?
                """, tenantId(), opportunityId, roleId);
        if (removed == 0) throw new NotFoundException("Contact role not found on this opportunity: " + roleId);
        audit.record("OPPORTUNITY_CONTACT_ROLE_REMOVE", "OPPORTUNITY", opportunityId,
                "Contact role removed from " + opp.name(), map("roleId", roleId.toString()));
    }

    // ---------------------------------------------------------------- approvals

    public record ApprovalRequest(@NotBlank String approvalType, @NotBlank String state, String notes) {}

    @Transactional
    public void recordApproval(UUID opportunityId, ApprovalRequest request) {
        OpportunityLifecycleService.OpportunityState opp = open(opportunityId, null);
        String state = request.state() == null ? "" : request.state().trim().toUpperCase(Locale.ROOT);
        if (!APPROVAL_STATES.contains(state)) {
            throw new ConflictException("Approval state must be one of " + APPROVAL_STATES
                    + ", not '" + request.state() + "'.");
        }
        String type = request.approvalType().trim().toUpperCase(Locale.ROOT);
        jdbc.update("""
                insert into sales.opportunity_approval
                  (tenant_id, opportunity_id, approval_type, state, requested_by, decided_by, decided_at, notes)
                values (?, ?, ?, ?, ?, ?, case when ? = 'REQUESTED' then null else now() end, ?)
                on conflict (tenant_id, opportunity_id, approval_type) do update
                  set state = excluded.state, decided_by = excluded.decided_by,
                      decided_at = excluded.decided_at, notes = excluded.notes
                """, tenantId(), opportunityId, type, state, TenantContext.get().userId(),
                "REQUESTED".equals(state) ? null : TenantContext.get().userId(), state,
                OpportunityLifecycleService.trimToNull(request.notes()));
        audit.record("OPPORTUNITY_APPROVAL", "OPPORTUNITY", opportunityId,
                type + " approval " + state.toLowerCase(Locale.ROOT) + " on " + opp.name(),
                map("approvalType", type, "state", state));
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }
}

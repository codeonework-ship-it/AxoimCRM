package com.axiom.contracting;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sales orders and their lines (FR-ORD-001..020).
 *
 * <p>The header table already existed with a real state machine; what it never
 * had was lines, or the {@code version} column an editable commitment needs.
 * V332 added both. This service owns the two rules the schema cannot express.
 *
 * <h2>Totals are recalculated on write and stored</h2>
 * Never derived on read. A line's extended amount is the number the customer
 * agreed to — recomputing it later from quantity × price would silently restate
 * history the first time a price book changes, and an order is evidence of a
 * commitment, not a live query.
 *
 * <h2>Lines are immutable once the order is booked</h2>
 * A BOOKED order has been promised to a customer and may already be in
 * fulfilment; changing what was ordered underneath that is how a warehouse ships
 * the wrong thing. Amendments are a new revision, which is the same stance the
 * CPQ quote model already takes. DRAFT is freely editable.
 */
@Service
public class SalesOrderService {

    /** States in which the order's contents may still change. */
    private static final Set<String> EDITABLE_STATES = Set.of("DRAFT");

    /**
     * Allowed transitions. Held here rather than as a CHECK because a CHECK sees
     * only the new row and cannot know what the previous state was.
     */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("BOOKED", "CANCELLED"),
            "BOOKED", Set.of("FULFILMENT", "CANCELLED"),
            "FULFILMENT", Set.of("PARTIALLY_FULFILLED", "FULFILLED", "CANCELLED"),
            "PARTIALLY_FULFILLED", Set.of("FULFILLED", "CANCELLED"),
            "FULFILLED", Set.of(),
            "CANCELLED", Set.of());

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public SalesOrderService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // --------------------------------------------------------------- contracts

    public record OrderRequest(UUID accountId, UUID contractId, UUID ownerId,
                               @Size(max = 40) String currencyCode, LocalDate orderDate,
                               Instant fulfilmentDueAt, @Size(max = 500) String notes) {}

    public record LineRequest(UUID productId, @Size(max = 60) String productCode,
                              @NotBlank @Size(max = 240) String productName,
                              @Size(max = 20) String unitOfMeasure,
                              BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPct) {}

    public record OrderLine(UUID id, int lineNumber, UUID productId, String productCode,
                            String productName, String unitOfMeasure, BigDecimal quantity,
                            BigDecimal unitPrice, BigDecimal discountPct, BigDecimal extendedAmount,
                            BigDecimal quantityFulfilled, UUID sourceQuoteLineId) {}

    public record OrderDetail(UUID id, String orderNumber, UUID accountId, String accountName,
                              UUID contractId, String status, LocalDate orderDate,
                              String currencyCode, BigDecimal totalAmount, Instant fulfilmentDueAt,
                              UUID ownerId, String ownerName, Instant bookedAt, String cancelledReason,
                              Instant createdAt, Instant updatedAt, long version,
                              List<OrderLine> lines, boolean editable) {}

    public record TransitionRequest(@NotBlank String status, @Size(max = 500) String reason) {}

    public record FulfilRequest(UUID lineId, BigDecimal quantity) {}

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<OrderDetail> list(UUID accountId, String status) {
        StringBuilder sql = new StringBuilder(HEADER_SELECT)
                .append(" where o.tenant_id = ? and o.deleted_at is null");
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        if (accountId != null) { sql.append(" and o.account_id = ?"); args.add(accountId); }
        String state = upper(status);
        if (state != null) { sql.append(" and o.status = ?"); args.add(state); }
        sql.append(" order by o.order_date desc, o.order_number desc");
        // Headers only: a list of forty orders does not need four hundred lines,
        // and loading them would make the grid slower for data it never renders.
        return jdbc.query(sql.toString(), (rs, i) -> header(rs, List.of()), args.toArray());
    }

    @Transactional(readOnly = true)
    public OrderDetail get(UUID id) {
        try {
            OrderDetail header = jdbc.queryForObject(
                    HEADER_SELECT + " where o.tenant_id = ? and o.id = ? and o.deleted_at is null",
                    (rs, i) -> header(rs, List.of()), TenantContext.get().tenantId(), id);
            return withLines(header);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("Sales order not found, or it has been deleted");
        }
    }

    private OrderDetail withLines(OrderDetail header) {
        List<OrderLine> lines = jdbc.query("""
                select id, line_number, product_id, product_code, product_name, unit_of_measure,
                       quantity, unit_price, discount_pct, extended_amount, quantity_fulfilled,
                       source_quote_line_id
                from contracting.order_line
                where tenant_id = ? and order_id = ?
                order by line_number
                """, (rs, i) -> new OrderLine(
                        rs.getObject("id", UUID.class), rs.getInt("line_number"),
                        rs.getObject("product_id", UUID.class), rs.getString("product_code"),
                        rs.getString("product_name"), rs.getString("unit_of_measure"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("discount_pct"), rs.getBigDecimal("extended_amount"),
                        rs.getBigDecimal("quantity_fulfilled"),
                        rs.getObject("source_quote_line_id", UUID.class)),
                TenantContext.get().tenantId(), header.id());
        return new OrderDetail(header.id(), header.orderNumber(), header.accountId(), header.accountName(),
                header.contractId(), header.status(), header.orderDate(), header.currencyCode(),
                header.totalAmount(), header.fulfilmentDueAt(), header.ownerId(), header.ownerName(),
                header.bookedAt(), header.cancelledReason(), header.createdAt(), header.updatedAt(),
                header.version(), lines, EDITABLE_STATES.contains(header.status()));
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public OrderDetail create(OrderRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        if (request.accountId() == null) {
            throw new IllegalArgumentException("An order must name the account it is for");
        }
        UUID tenant = TenantContext.get().tenantId();
        UUID me = TenantContext.get().userId();
        String number = nextNumber(tenant);

        UUID id = jdbc.queryForObject("""
                insert into contracting.order_record
                  (tenant_id, order_number, contract_id, account_id, status, order_date,
                   currency_code, total_amount, fulfilment_due_at, owner_id, created_by, updated_by)
                values (?, ?, ?, ?, 'DRAFT', coalesce(?, current_date), coalesce(?, 'INR'), 0, ?, ?, ?, ?)
                returning id
                """, UUID.class, tenant, number, request.contractId(), request.accountId(),
                request.orderDate(), upper(request.currencyCode()),
                request.fulfilmentDueAt() == null ? null : java.sql.Timestamp.from(request.fulfilmentDueAt()),
                request.ownerId() == null ? me : request.ownerId(), me, me);

        audit.record("ORDER_CREATE", "SALES_ORDER", id, "Created sales order " + number,
                Map.of("orderNumber", number, "accountId", request.accountId().toString()));
        return get(id);
    }

    /**
     * Replaces the order's lines wholesale.
     *
     * <p>Replace rather than patch, deliberately: a partial line update API needs
     * per-line versions to be safe under concurrency, and an order's lines are
     * edited as a set in every real workflow. The whole set is rewritten inside
     * one transaction and the header total recalculated from it, so the total can
     * never disagree with the lines that produced it.
     */
    @Transactional
    public OrderDetail replaceLines(UUID id, long expectedVersion, List<LineRequest> lines) {
        CrmRole.requireWrite(TenantContext.get().role());
        OrderDetail before = get(id);
        assertEditable(before, "change what was ordered");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line. To void the order, "
                    + "cancel it instead — that keeps the record and its reason.");
        }
        UUID tenant = TenantContext.get().tenantId();

        jdbc.update("delete from contracting.order_line where tenant_id = ? and order_id = ?", tenant, id);

        BigDecimal total = BigDecimal.ZERO;
        int lineNumber = 1;
        for (LineRequest line : lines) {
            BigDecimal quantity = positive(line.quantity(), "quantity");
            BigDecimal unitPrice = nonNegative(line.unitPrice(), "unit price");
            BigDecimal discount = line.discountPct() == null ? BigDecimal.ZERO : line.discountPct();
            if (discount.signum() < 0 || discount.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("A line discount must be between 0 and 100 percent");
            }
            BigDecimal extended = extended(quantity, unitPrice, discount);
            jdbc.update("""
                    insert into contracting.order_line
                      (tenant_id, order_id, line_number, product_id, product_code, product_name,
                       unit_of_measure, quantity, unit_price, discount_pct, extended_amount, currency_code)
                    values (?, ?, ?, ?, ?, ?, coalesce(?, 'EA'), ?, ?, ?, ?, ?)
                    """, tenant, id, lineNumber++, line.productId(), blank(line.productCode()),
                    require(line.productName(), "Each line needs a product name"),
                    upper(line.unitOfMeasure()), quantity, unitPrice, discount, extended,
                    before.currencyCode());
            total = total.add(extended);
        }

        int updated = jdbc.update("""
                update contracting.order_record
                set total_amount = ?, updated_at = now(), updated_by = ?, version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null and version = ?
                """, total, TenantContext.get().userId(), tenant, id, expectedVersion);
        if (updated == 0) {
            throw new ConflictException("This order changed while you were editing it (you had version "
                    + expectedVersion + ", the stored order is version " + before.version()
                    + "). Reload it and re-apply your changes.");
        }

        audit.record("ORDER_LINES_REPLACED", "SALES_ORDER", id,
                "Replaced the lines on " + before.orderNumber(),
                Map.of("lineCount", lines.size(), "previousTotal", String.valueOf(before.totalAmount()),
                        "newTotal", total.toPlainString()));
        return get(id);
    }

    /**
     * Moves the order through its state machine.
     *
     * <p>The transition map is checked before the write so the refusal can name
     * both states and what is reachable. A CHECK constraint sees only the row
     * being written and cannot say "you cannot go from FULFILLED back to DRAFT".
     */
    @Transactional
    public OrderDetail transition(UUID id, TransitionRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        OrderDetail before = get(id);
        String target = upper(require(request.status(), "A target status is required"));
        Set<String> allowed = TRANSITIONS.getOrDefault(before.status(), Set.of());

        if (target.equals(before.status())) {
            throw new ConflictException(before.orderNumber() + " is already " + target + ".");
        }
        if (!allowed.contains(target)) {
            throw new ConflictException(before.orderNumber() + " is " + before.status()
                    + " and cannot move to " + target + ". "
                    + (allowed.isEmpty() ? "This is a final state." : "Allowed from here: "
                    + String.join(", ", allowed.stream().sorted().toList()) + "."));
        }
        if ("BOOKED".equals(target) && before.lines().isEmpty()) {
            throw new ConflictException("An order cannot be booked with no lines — there would be "
                    + "nothing to fulfil or invoice.");
        }
        if ("CANCELLED".equals(target) && blank(request.reason()) == null) {
            throw new IllegalArgumentException("Cancelling an order requires a reason. It stays on the "
                    + "record, so whoever asks later can see why.");
        }

        jdbc.update("""
                update contracting.order_record
                set status = ?, booked_at = case when ? = 'BOOKED' then now() else booked_at end,
                    cancelled_reason = case when ? = 'CANCELLED' then ? else cancelled_reason end,
                    updated_at = now(), updated_by = ?, version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null
                """, target, target, target, blank(request.reason()),
                TenantContext.get().userId(), TenantContext.get().tenantId(), id);

        audit.record("ORDER_" + target, "SALES_ORDER", id,
                before.orderNumber() + ": " + before.status() + " -> " + target,
                Map.of("from", before.status(), "to", target,
                        "reason", request.reason() == null ? "not stated" : request.reason()));
        return get(id);
    }

    /**
     * Records fulfilment against one line and derives the header state from the
     * lines rather than trusting the caller to say which it is.
     */
    @Transactional
    public OrderDetail fulfil(UUID id, FulfilRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        OrderDetail before = get(id);
        if (!Set.of("BOOKED", "FULFILMENT", "PARTIALLY_FULFILLED").contains(before.status())) {
            throw new ConflictException(before.orderNumber() + " is " + before.status()
                    + "; only a booked or in-fulfilment order can be fulfilled.");
        }
        OrderLine line = before.lines().stream()
                .filter((candidate) -> candidate.id().equals(request.lineId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("That line is not on this order"));

        BigDecimal quantity = positive(request.quantity(), "fulfilled quantity");
        BigDecimal newTotal = line.quantityFulfilled().add(quantity);
        if (newTotal.compareTo(line.quantity()) > 0) {
            throw new ConflictException("Line " + line.lineNumber() + " ordered "
                    + line.quantity().toPlainString() + " and " + line.quantityFulfilled().toPlainString()
                    + " is already fulfilled, so " + quantity.toPlainString() + " more would over-fulfil it. "
                    + "At most " + line.quantity().subtract(line.quantityFulfilled()).toPlainString()
                    + " can be recorded.");
        }

        jdbc.update("""
                update contracting.order_line
                set quantity_fulfilled = ?, updated_at = now()
                where tenant_id = ? and id = ?
                """, newTotal, TenantContext.get().tenantId(), line.id());

        // Derived, not declared: the header follows the lines.
        OrderDetail after = get(id);
        boolean all = after.lines().stream()
                .allMatch((candidate) -> candidate.quantityFulfilled().compareTo(candidate.quantity()) >= 0);
        boolean any = after.lines().stream()
                .anyMatch((candidate) -> candidate.quantityFulfilled().signum() > 0);
        String derived = all ? "FULFILLED" : any ? "PARTIALLY_FULFILLED" : after.status();
        if (!derived.equals(after.status())) {
            jdbc.update("""
                    update contracting.order_record set status = ?, updated_at = now(),
                        updated_by = ?, version = version + 1
                    where tenant_id = ? and id = ?
                    """, derived, TenantContext.get().userId(), TenantContext.get().tenantId(), id);
        }

        audit.record("ORDER_FULFIL", "SALES_ORDER", id,
                "Fulfilled " + quantity.toPlainString() + " on line " + line.lineNumber()
                        + " of " + before.orderNumber(),
                Map.of("lineNumber", line.lineNumber(), "quantity", quantity.toPlainString(),
                        "status", derived));
        return get(id);
    }

    // ------------------------------------------------------------------ helpers

    private void assertEditable(OrderDetail order, String action) {
        if (!EDITABLE_STATES.contains(order.status())) {
            throw new ConflictException(order.orderNumber() + " is " + order.status()
                    + ", so you cannot " + action + ". A booked order has been promised to the customer "
                    + "and may already be in fulfilment; raise an amendment instead.");
        }
    }

    /** Two decimal places, HALF_UP — the rounding a customer sees on a document. */
    static BigDecimal extended(BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPct) {
        BigDecimal gross = quantity.multiply(unitPrice);
        BigDecimal factor = BigDecimal.ONE.subtract(discountPct.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
        return gross.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Order numbers are allocated from the count of existing orders for the
     * tenant, then retried on collision by the unique index. Sequences are not
     * used because a per-tenant sequence would mean DDL per tenant.
     */
    private String nextNumber(UUID tenant) {
        Integer year = LocalDate.now().getYear();
        for (int attempt = 0; attempt < 20; attempt++) {
            Long count = jdbc.queryForObject(
                    "select count(*) from contracting.order_record where tenant_id = ?", Long.class, tenant);
            String candidate = String.format("SO-%d-%05d", year, (count == null ? 0 : count) + 1 + attempt);
            Long taken = jdbc.queryForObject("""
                    select count(*) from contracting.order_record where tenant_id = ? and order_number = ?
                    """, Long.class, tenant, candidate);
            if (taken != null && taken == 0) return candidate;
        }
        throw new ConflictException("Could not allocate an order number after 20 attempts. Retry.");
    }

    private static final String HEADER_SELECT = """
            select o.id, o.order_number, o.account_id, a.name as account_name, o.contract_id,
                   o.status, o.order_date, o.currency_code, o.total_amount, o.fulfilment_due_at,
                   o.owner_id, u.display_name as owner_name, o.booked_at, o.cancelled_reason,
                   o.created_at, o.updated_at, o.version
            from contracting.order_record o
            left join crm.account a on a.tenant_id = o.tenant_id and a.id = o.account_id
            left join identity.app_user u on u.tenant_id = o.tenant_id and u.id = o.owner_id
            """;

    private OrderDetail header(java.sql.ResultSet rs, List<OrderLine> lines) throws java.sql.SQLException {
        String status = rs.getString("status");
        return new OrderDetail(
                rs.getObject("id", UUID.class), rs.getString("order_number"),
                rs.getObject("account_id", UUID.class), rs.getString("account_name"),
                rs.getObject("contract_id", UUID.class), status,
                rs.getObject("order_date", LocalDate.class), rs.getString("currency_code"),
                rs.getBigDecimal("total_amount"),
                rs.getTimestamp("fulfilment_due_at") == null ? null : rs.getTimestamp("fulfilment_due_at").toInstant(),
                rs.getObject("owner_id", UUID.class), rs.getString("owner_name"),
                rs.getTimestamp("booked_at") == null ? null : rs.getTimestamp("booked_at").toInstant(),
                rs.getString("cancelled_reason"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"), lines, EDITABLE_STATES.contains(status));
    }

    static BigDecimal positive(BigDecimal value, String what) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("A line " + what + " must be greater than zero");
        }
        return value;
    }

    static BigDecimal nonNegative(BigDecimal value, String what) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("A line " + what + " cannot be negative");
        }
        return value;
    }

    static String require(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String upper(String value) {
        String trimmed = blank(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}

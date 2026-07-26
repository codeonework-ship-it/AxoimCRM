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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Customer receivables: invoices, their lines, and payments against them
 * (FR-INV-001..018).
 *
 * <p>This is the ledger a tenant uses to bill <em>its own customers</em>. It is
 * deliberately not {@code billing.invoice}, which is how Axiom bills the tenant.
 * Two ledgers with different audiences, owners and retention rules; merging them
 * would let a customer-facing AR query read platform revenue.
 *
 * <h2>An issued invoice is immutable</h2>
 * Once ISSUED the document has gone to a customer and may be in their accounts
 * payable. Editing its lines afterwards means the copy they hold and the copy we
 * hold disagree, which is the one thing an invoice must never do. Corrections are
 * a credit note, which is why CREDITED is a state rather than an edit.
 *
 * <h2>paid_amount is maintained, not derived</h2>
 * Every payment updates the header in the same transaction. An ageing report that
 * re-derives paid amounts by summing payments disagrees with the ledger the
 * moment a payment is voided, and both numbers then look authoritative.
 */
@Service
public class ReceivablesService {

    private static final Set<String> EDITABLE = Set.of("DRAFT");
    private static final Set<String> PAYABLE = Set.of("ISSUED", "PART_PAID", "OVERDUE");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ReceivablesService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // --------------------------------------------------------------- contracts

    public record InvoiceRequest(UUID accountId, UUID orderId, @Size(max = 40) String currencyCode,
                                 LocalDate issueDate, LocalDate dueDate, @Size(max = 500) String notes) {}

    public record LineRequest(@NotBlank @Size(max = 240) String description,
                              BigDecimal quantity, BigDecimal unitPrice, BigDecimal taxPct) {}

    public record InvoiceLine(UUID id, int lineNumber, String description, BigDecimal quantity,
                              BigDecimal unitPrice, BigDecimal taxPct, BigDecimal extendedAmount,
                              UUID sourceOrderLineId) {}

    public record PaymentRow(UUID id, BigDecimal amount, String currencyCode, LocalDate receivedAt,
                             String method, String reference, Instant voidedAt, String voidReason) {}

    public record InvoiceDetail(UUID id, String invoiceNumber, UUID accountId, String accountName,
                                UUID orderId, String orderNumber, String status,
                                LocalDate issueDate, LocalDate dueDate, String currencyCode,
                                BigDecimal subtotalAmount, BigDecimal taxAmount, BigDecimal totalAmount,
                                BigDecimal paidAmount, BigDecimal outstandingAmount,
                                Integer daysOverdue, String notes, String cancelledReason,
                                Instant createdAt, Instant updatedAt, long version,
                                List<InvoiceLine> lines, List<PaymentRow> payments, boolean editable) {}

    public record PaymentRequest(BigDecimal amount, LocalDate receivedAt, @Size(max = 40) String method,
                                 @Size(max = 120) String reference) {}

    public record TransitionRequest(@NotBlank String status, @Size(max = 500) String reason) {}

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<InvoiceDetail> list(UUID accountId, String status, boolean overdueOnly) {
        StringBuilder sql = new StringBuilder(SELECT)
                .append(" where i.tenant_id = ? and i.deleted_at is null");
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        if (accountId != null) { sql.append(" and i.account_id = ?"); args.add(accountId); }
        String state = SalesOrderService.upper(status);
        if (state != null) { sql.append(" and i.status = ?"); args.add(state); }
        if (overdueOnly) {
            // Overdue is a fact about today and the balance, not a stored flag that
            // has to be swept. Asking the question at read time cannot go stale.
            sql.append(" and i.due_date < current_date and i.paid_amount < i.total_amount")
               .append(" and i.status not in ('PAID','CANCELLED','WRITTEN_OFF','CREDITED')");
        }
        sql.append(" order by i.due_date nulls last, i.invoice_number desc");
        return jdbc.query(sql.toString(), (rs, i) -> header(rs, List.of(), List.of()), args.toArray());
    }

    @Transactional(readOnly = true)
    public InvoiceDetail get(UUID id) {
        try {
            InvoiceDetail header = jdbc.queryForObject(
                    SELECT + " where i.tenant_id = ? and i.id = ? and i.deleted_at is null",
                    (rs, i) -> header(rs, List.of(), List.of()), TenantContext.get().tenantId(), id);
            return hydrate(header);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("Invoice not found, or it has been deleted");
        }
    }

    private InvoiceDetail hydrate(InvoiceDetail header) {
        UUID tenant = TenantContext.get().tenantId();
        List<InvoiceLine> lines = jdbc.query("""
                select id, line_number, description, quantity, unit_price, tax_pct,
                       extended_amount, source_order_line_id
                from receivables.invoice_line where tenant_id = ? and invoice_id = ? order by line_number
                """, (rs, i) -> new InvoiceLine(rs.getObject("id", UUID.class), rs.getInt("line_number"),
                        rs.getString("description"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_price"), rs.getBigDecimal("tax_pct"),
                        rs.getBigDecimal("extended_amount"),
                        rs.getObject("source_order_line_id", UUID.class)), tenant, header.id());
        List<PaymentRow> payments = jdbc.query("""
                select id, amount, currency_code, received_at, method, reference, voided_at, void_reason
                from receivables.payment where tenant_id = ? and invoice_id = ? order by received_at, created_at
                """, (rs, i) -> new PaymentRow(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"),
                        rs.getString("currency_code"), rs.getObject("received_at", LocalDate.class),
                        rs.getString("method"), rs.getString("reference"),
                        rs.getTimestamp("voided_at") == null ? null : rs.getTimestamp("voided_at").toInstant(),
                        rs.getString("void_reason")), tenant, header.id());
        return rebuild(header, lines, payments);
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public InvoiceDetail create(InvoiceRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        if (request.accountId() == null) {
            throw new IllegalArgumentException("An invoice must name the account it is billed to");
        }
        UUID tenant = TenantContext.get().tenantId();
        UUID me = TenantContext.get().userId();
        String number = nextNumber(tenant);

        UUID id = jdbc.queryForObject("""
                insert into receivables.invoice
                  (tenant_id, invoice_number, account_id, order_id, status, issue_date, due_date,
                   currency_code, notes, created_by, updated_by)
                values (?, ?, ?, ?, 'DRAFT', ?, ?, coalesce(?, 'INR'), ?, ?, ?)
                returning id
                """, UUID.class, tenant, number, request.accountId(), request.orderId(),
                request.issueDate(), request.dueDate(), SalesOrderService.upper(request.currencyCode()),
                SalesOrderService.blank(request.notes()), me, me);

        audit.record("INVOICE_CREATE", "INVOICE", id, "Created invoice " + number,
                Map.of("invoiceNumber", number, "accountId", request.accountId().toString()));
        return get(id);
    }

    /**
     * Builds an invoice from a booked order's lines.
     *
     * <p>Only from a booked order: invoicing a DRAFT would bill a customer for
     * something not yet committed. Lines are copied with their agreed prices and
     * carry {@code source_order_line_id}, so the invoice can be traced to the
     * order without depending on the order still saying the same thing.
     */
    @Transactional
    public InvoiceDetail fromOrder(UUID orderId, InvoiceRequest overrides) {
        CrmRole.requireWrite(TenantContext.get().role());
        UUID tenant = TenantContext.get().tenantId();
        List<Map<String, Object>> found = jdbc.queryForList("""
                select id, order_number, account_id, status, currency_code
                from contracting.order_record
                where tenant_id = ? and id = ? and deleted_at is null
                """, tenant, orderId);
        if (found.isEmpty()) throw new NotFoundException("That order does not exist in this workspace");
        Map<String, Object> order = found.get(0);
        String status = String.valueOf(order.get("status"));
        if (!Set.of("BOOKED", "FULFILMENT", "PARTIALLY_FULFILLED", "FULFILLED").contains(status)) {
            throw new ConflictException(order.get("order_number") + " is " + status
                    + ". Only a booked order can be invoiced — billing a draft would charge for a "
                    + "commitment that has not been made.");
        }

        List<Map<String, Object>> orderLines = jdbc.queryForList("""
                select id, line_number, product_name, quantity, unit_price, extended_amount
                from contracting.order_line where tenant_id = ? and order_id = ? order by line_number
                """, tenant, orderId);
        if (orderLines.isEmpty()) {
            throw new ConflictException("That order has no lines, so there is nothing to invoice.");
        }

        LocalDate issue = overrides != null && overrides.issueDate() != null
                ? overrides.issueDate() : LocalDate.now();
        InvoiceDetail invoice = create(new InvoiceRequest(
                (UUID) order.get("account_id"), orderId,
                String.valueOf(order.get("currency_code")), issue,
                overrides != null && overrides.dueDate() != null ? overrides.dueDate() : issue.plusDays(30),
                overrides == null ? null : overrides.notes()));

        BigDecimal subtotal = BigDecimal.ZERO;
        int lineNumber = 1;
        for (Map<String, Object> line : orderLines) {
            BigDecimal extended = (BigDecimal) line.get("extended_amount");
            jdbc.update("""
                    insert into receivables.invoice_line
                      (tenant_id, invoice_id, line_number, description, quantity, unit_price,
                       tax_pct, extended_amount, source_order_line_id)
                    values (?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """, tenant, invoice.id(), lineNumber++, line.get("product_name"),
                    line.get("quantity"), line.get("unit_price"), extended, line.get("id"));
            subtotal = subtotal.add(extended);
        }
        retotal(tenant, invoice.id(), subtotal, BigDecimal.ZERO);

        audit.record("INVOICE_FROM_ORDER", "INVOICE", invoice.id(),
                "Invoiced order " + order.get("order_number") + " as " + invoice.invoiceNumber(),
                Map.of("orderId", orderId.toString(), "lineCount", orderLines.size(),
                        "subtotal", subtotal.toPlainString()));
        return get(invoice.id());
    }

    @Transactional
    public InvoiceDetail replaceLines(UUID id, long expectedVersion, List<LineRequest> lines) {
        CrmRole.requireWrite(TenantContext.get().role());
        InvoiceDetail before = get(id);
        if (!EDITABLE.contains(before.status())) {
            throw new ConflictException(before.invoiceNumber() + " is " + before.status()
                    + ", so its lines cannot change. The customer holds this document; issue a credit "
                    + "note instead so both copies still agree.");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("An invoice needs at least one line");
        }
        UUID tenant = TenantContext.get().tenantId();
        jdbc.update("delete from receivables.invoice_line where tenant_id = ? and invoice_id = ?", tenant, id);

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        int lineNumber = 1;
        for (LineRequest line : lines) {
            BigDecimal quantity = SalesOrderService.positive(line.quantity(), "quantity");
            BigDecimal unitPrice = SalesOrderService.nonNegative(line.unitPrice(), "unit price");
            BigDecimal taxPct = line.taxPct() == null ? BigDecimal.ZERO : line.taxPct();
            BigDecimal extended = SalesOrderService.extended(quantity, unitPrice, BigDecimal.ZERO);
            BigDecimal lineTax = extended.multiply(taxPct)
                    .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
            jdbc.update("""
                    insert into receivables.invoice_line
                      (tenant_id, invoice_id, line_number, description, quantity, unit_price,
                       tax_pct, extended_amount)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenant, id, lineNumber++,
                    SalesOrderService.require(line.description(), "Each line needs a description"),
                    quantity, unitPrice, taxPct, extended);
            subtotal = subtotal.add(extended);
            tax = tax.add(lineTax);
        }

        int updated = jdbc.update("""
                update receivables.invoice
                set subtotal_amount = ?, tax_amount = ?, total_amount = ?, updated_at = now(),
                    updated_by = ?, version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null and version = ?
                """, subtotal, tax, subtotal.add(tax), TenantContext.get().userId(),
                tenant, id, expectedVersion);
        if (updated == 0) {
            throw new ConflictException("This invoice changed while you were editing it (you had version "
                    + expectedVersion + ", the stored invoice is version " + before.version()
                    + "). Reload it and re-apply your changes.");
        }
        audit.record("INVOICE_LINES_REPLACED", "INVOICE", id,
                "Replaced the lines on " + before.invoiceNumber(),
                Map.of("lineCount", lines.size(), "total", subtotal.add(tax).toPlainString()));
        return get(id);
    }

    /**
     * Records a payment and lets the invoice status follow the balance.
     *
     * <p>The status is derived from what is now outstanding rather than supplied
     * by the caller: an invoice marked PAID with a balance remaining, or PART_PAID
     * with nothing outstanding, is a ledger that contradicts itself.
     */
    @Transactional
    public InvoiceDetail recordPayment(UUID id, PaymentRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        InvoiceDetail before = get(id);
        if (!PAYABLE.contains(before.status())) {
            throw new ConflictException(before.invoiceNumber() + " is " + before.status()
                    + " and cannot take a payment. Only an issued, part-paid or overdue invoice can.");
        }
        BigDecimal amount = SalesOrderService.positive(request.amount(), "payment amount");
        BigDecimal outstanding = before.totalAmount().subtract(before.paidAmount());
        if (amount.compareTo(outstanding) > 0) {
            throw new ConflictException("That payment of " + amount.toPlainString() + " exceeds the "
                    + outstanding.toPlainString() + " outstanding on " + before.invoiceNumber()
                    + ". Record the exact amount, or raise a credit note for the difference — an "
                    + "overpayment recorded here would misstate the receivable.");
        }
        UUID tenant = TenantContext.get().tenantId();

        jdbc.update("""
                insert into receivables.payment
                  (tenant_id, invoice_id, amount, currency_code, received_at, method, reference, created_by)
                values (?, ?, ?, ?, coalesce(?, current_date), coalesce(?, 'BANK_TRANSFER'), ?, ?)
                """, tenant, id, amount, before.currencyCode(), request.receivedAt(),
                SalesOrderService.upper(request.method()), SalesOrderService.blank(request.reference()),
                TenantContext.get().userId());

        BigDecimal paid = before.paidAmount().add(amount);
        String derived = paid.compareTo(before.totalAmount()) >= 0 ? "PAID" : "PART_PAID";
        jdbc.update("""
                update receivables.invoice
                set paid_amount = ?, status = ?, updated_at = now(), updated_by = ?, version = version + 1
                where tenant_id = ? and id = ?
                """, paid, derived, TenantContext.get().userId(), tenant, id);

        audit.record("INVOICE_PAYMENT", "INVOICE", id,
                "Recorded " + amount.toPlainString() + " against " + before.invoiceNumber(),
                Map.of("amount", amount.toPlainString(), "paidTotal", paid.toPlainString(),
                        "status", derived, "method",
                        request.method() == null ? "BANK_TRANSFER" : request.method()));
        return get(id);
    }

    @Transactional
    public InvoiceDetail transition(UUID id, TransitionRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        InvoiceDetail before = get(id);
        String target = SalesOrderService.upper(
                SalesOrderService.require(request.status(), "A target status is required"));

        if ("ISSUED".equals(target)) {
            if (!"DRAFT".equals(before.status())) {
                throw new ConflictException(before.invoiceNumber() + " is already " + before.status()
                        + " and cannot be issued again.");
            }
            if (before.lines().isEmpty()) {
                throw new ConflictException("An invoice cannot be issued with no lines — the customer "
                        + "would receive a document that bills for nothing.");
            }
            if (before.dueDate() == null) {
                throw new IllegalArgumentException("An issued invoice needs a due date, otherwise it can "
                        + "never be overdue and will never appear in an ageing report.");
            }
        } else if (Set.of("CANCELLED", "WRITTEN_OFF", "CREDITED").contains(target)) {
            if (SalesOrderService.blank(request.reason()) == null) {
                throw new IllegalArgumentException(target + " requires a reason; it stays on the record.");
            }
            if (before.paidAmount().signum() > 0 && "CANCELLED".equals(target)) {
                throw new ConflictException(before.invoiceNumber() + " has "
                        + before.paidAmount().toPlainString() + " paid against it and cannot simply be "
                        + "cancelled. Credit it instead, so the payment stays explainable.");
            }
        } else {
            throw new IllegalArgumentException("Invoices are moved to ISSUED, CANCELLED, WRITTEN_OFF or "
                    + "CREDITED. Paid status follows payments and is not set directly.");
        }

        jdbc.update("""
                update receivables.invoice
                set status = ?, issue_date = case when ? = 'ISSUED' then coalesce(issue_date, current_date)
                                                 else issue_date end,
                    cancelled_reason = case when ? in ('CANCELLED','WRITTEN_OFF','CREDITED') then ?
                                            else cancelled_reason end,
                    updated_at = now(), updated_by = ?, version = version + 1
                where tenant_id = ? and id = ?
                """, target, target, target, SalesOrderService.blank(request.reason()),
                TenantContext.get().userId(), TenantContext.get().tenantId(), id);

        audit.record("INVOICE_" + target, "INVOICE", id,
                before.invoiceNumber() + ": " + before.status() + " -> " + target,
                Map.of("from", before.status(), "to", target,
                        "reason", request.reason() == null ? "not stated" : request.reason()));
        return get(id);
    }

    // ------------------------------------------------------------------ helpers

    private void retotal(UUID tenant, UUID id, BigDecimal subtotal, BigDecimal tax) {
        jdbc.update("""
                update receivables.invoice
                set subtotal_amount = ?, tax_amount = ?, total_amount = ?, updated_at = now(),
                    version = version + 1
                where tenant_id = ? and id = ?
                """, subtotal, tax, subtotal.add(tax), tenant, id);
    }

    private String nextNumber(UUID tenant) {
        int year = LocalDate.now().getYear();
        for (int attempt = 0; attempt < 20; attempt++) {
            Long count = jdbc.queryForObject(
                    "select count(*) from receivables.invoice where tenant_id = ?", Long.class, tenant);
            String candidate = String.format("INV-%d-%05d", year, (count == null ? 0 : count) + 1 + attempt);
            Long taken = jdbc.queryForObject(
                    "select count(*) from receivables.invoice where tenant_id = ? and invoice_number = ?",
                    Long.class, tenant, candidate);
            if (taken != null && taken == 0) return candidate;
        }
        throw new ConflictException("Could not allocate an invoice number after 20 attempts. Retry.");
    }

    private static final String SELECT = """
            select i.id, i.invoice_number, i.account_id, a.name as account_name, i.order_id,
                   o.order_number, i.status, i.issue_date, i.due_date, i.currency_code,
                   i.subtotal_amount, i.tax_amount, i.total_amount, i.paid_amount, i.notes,
                   i.cancelled_reason, i.created_at, i.updated_at, i.version
            from receivables.invoice i
            left join crm.account a on a.tenant_id = i.tenant_id and a.id = i.account_id
            left join contracting.order_record o on o.tenant_id = i.tenant_id and o.id = i.order_id
            """;

    private InvoiceDetail header(java.sql.ResultSet rs, List<InvoiceLine> lines,
                                 List<PaymentRow> payments) throws java.sql.SQLException {
        BigDecimal total = rs.getBigDecimal("total_amount");
        BigDecimal paid = rs.getBigDecimal("paid_amount");
        LocalDate due = rs.getObject("due_date", LocalDate.class);
        String status = rs.getString("status");
        BigDecimal outstanding = total.subtract(paid);
        Integer daysOverdue = due != null && outstanding.signum() > 0 && due.isBefore(LocalDate.now())
                ? (int) java.time.temporal.ChronoUnit.DAYS.between(due, LocalDate.now())
                : null;
        return new InvoiceDetail(
                rs.getObject("id", UUID.class), rs.getString("invoice_number"),
                rs.getObject("account_id", UUID.class), rs.getString("account_name"),
                rs.getObject("order_id", UUID.class), rs.getString("order_number"), status,
                rs.getObject("issue_date", LocalDate.class), due, rs.getString("currency_code"),
                rs.getBigDecimal("subtotal_amount"), rs.getBigDecimal("tax_amount"), total, paid,
                outstanding, daysOverdue, rs.getString("notes"), rs.getString("cancelled_reason"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"), lines, payments, EDITABLE.contains(status));
    }

    private InvoiceDetail rebuild(InvoiceDetail h, List<InvoiceLine> lines, List<PaymentRow> payments) {
        return new InvoiceDetail(h.id(), h.invoiceNumber(), h.accountId(), h.accountName(), h.orderId(),
                h.orderNumber(), h.status(), h.issueDate(), h.dueDate(), h.currencyCode(),
                h.subtotalAmount(), h.taxAmount(), h.totalAmount(), h.paidAmount(), h.outstandingAmount(),
                h.daysOverdue(), h.notes(), h.cancelledReason(), h.createdAt(), h.updatedAt(),
                h.version(), lines, payments, h.editable());
    }
}

package com.axiom.contracting;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Quote to order conversion (FR-CPQ-040..046).
 *
 * <p>{@code cpq.quote_order} has existed since the CPQ migration and was
 * referenced by no code at all — the table recorded a conversion nothing could
 * perform. This service is what finally writes to it.
 *
 * <h2>The converted order copies the quote, it does not reference it</h2>
 * Every line is copied with the price that was quoted. The alternative — an
 * order that joins back to {@code cpq.quote_line} for its numbers — means a later
 * quote revision silently restates a booked order. The quote is an offer; the
 * order is what was agreed. {@code source_quote_line_id} preserves the lineage
 * without making the order depend on the offer still saying the same thing.
 *
 * <h2>Conversion happens once</h2>
 * Enforced by a unique index on {@code order_line.source_quote_line_id} and
 * checked here first so the refusal can name the order that already exists. A
 * second conversion would double-count revenue against one customer commitment.
 */
@Service
public class QuoteConversionService {

    /** Only an accepted quote may become an order. */
    private static final Set<String> CONVERTIBLE = Set.of("ACCEPTED");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final SalesOrderService orders;

    public QuoteConversionService(JdbcTemplate jdbc, AuditService audit, SalesOrderService orders) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.orders = orders;
    }

    public record ConvertRequest(LocalDate orderDate, UUID contractId,
                                 @Size(max = 500) String note) {}

    public record ConversionResult(UUID orderId, String orderNumber, UUID quoteId, String quoteNumber,
                                   int linesCopied, BigDecimal totalAmount, String currencyCode,
                                   String status, String note) {}

    @Transactional
    public ConversionResult convert(UUID quoteId, ConvertRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        UUID tenant = TenantContext.get().tenantId();
        UUID me = TenantContext.get().userId();

        Map<String, Object> quote = quote(tenant, quoteId);
        String quoteNumber = String.valueOf(quote.get("quote_number"));
        String status = String.valueOf(quote.get("status"));
        if (!CONVERTIBLE.contains(status)) {
            throw new ConflictException(quoteNumber + " is " + status + ". Only an ACCEPTED quote can "
                    + "become an order — converting anything earlier would book revenue the customer "
                    + "has not agreed to.");
        }
        if (Boolean.FALSE.equals(quote.get("is_active_version"))) {
            throw new ConflictException(quoteNumber + " is a superseded revision. Convert the current "
                    + "version instead, or the order will carry prices that were replaced.");
        }

        assertNotAlreadyConverted(tenant, quoteId, quoteNumber);

        List<Map<String, Object>> lines = jdbc.queryForList("""
                select id, line_number, product_id, product_code, product_name, unit_of_measure,
                       quantity, net_unit_price, discount_pct, extended_amount
                from cpq.quote_line
                where tenant_id = ? and quote_id = ?
                order by line_number
                """, tenant, quoteId);
        if (lines.isEmpty()) {
            throw new ConflictException(quoteNumber + " has no lines, so there is nothing to order.");
        }

        UUID accountId = (UUID) quote.get("account_id");
        String currency = String.valueOf(quote.get("currency_code"));

        SalesOrderService.OrderDetail order = orders.create(new SalesOrderService.OrderRequest(
                accountId, request == null ? null : request.contractId(), me, currency,
                request == null ? null : request.orderDate(), null, null));

        BigDecimal total = BigDecimal.ZERO;
        int lineNumber = 1;
        for (Map<String, Object> line : lines) {
            BigDecimal quantity = (BigDecimal) line.get("quantity");
            // net_unit_price is the price after CPQ's own discounting, so the
            // order stores it at 0% further discount. Carrying the percentage
            // across as well would apply the same discount twice.
            BigDecimal netUnit = (BigDecimal) line.get("net_unit_price");
            BigDecimal extended = (BigDecimal) line.get("extended_amount");
            if (extended == null) {
                extended = SalesOrderService.extended(quantity, netUnit, BigDecimal.ZERO);
            }
            jdbc.update("""
                    insert into contracting.order_line
                      (tenant_id, order_id, line_number, product_id, product_code, product_name,
                       unit_of_measure, quantity, unit_price, discount_pct, extended_amount,
                       currency_code, source_quote_line_id)
                    values (?, ?, ?, ?, ?, ?, coalesce(?, 'EA'), ?, ?, 0, ?, ?, ?)
                    """, tenant, order.id(), lineNumber++, line.get("product_id"),
                    line.get("product_code"), line.get("product_name"), line.get("unit_of_measure"),
                    quantity, netUnit, extended, currency, line.get("id"));
            total = total.add(extended);
        }

        jdbc.update("""
                update contracting.order_record
                set total_amount = ?, updated_at = now(), updated_by = ?, version = version + 1
                where tenant_id = ? and id = ?
                """, total, me, tenant, order.id());

        /*
         * The link row cpq.quote_order was designed for, finally written.
         *
         * The corporate-currency figures are carried across from the quote rather
         * than recomputed. quote_order.corporate_total_amount is NOT NULL, and the
         * rate that applied when the customer accepted is the rate the conversion
         * must record — re-reading today's rate would restate an agreed commitment
         * every time the currency moved.
         */
        BigDecimal corporateTotal = (BigDecimal) quote.get("corporate_grand_total");
        BigDecimal fxRate = (BigDecimal) quote.get("fx_rate");
        jdbc.update("""
                insert into cpq.quote_order
                  (tenant_id, quote_id, order_number, account_id, currency_code, total_amount,
                   corporate_total_amount, fx_rate, fx_rate_date, status, created_by)
                -- PENDING_FULFILMENT, not an invented 'CONVERTED': the table's own CHECK
                -- defines the vocabulary (PENDING_FULFILMENT / HANDED_OFF / CANCELLED)
                -- and a converted order is precisely one awaiting fulfilment.
                values (?, ?, ?, ?, ?, ?, ?, coalesce(?, 1), ?, 'PENDING_FULFILMENT', ?)
                """, tenant, quoteId, order.orderNumber(), accountId, currency, total,
                corporateTotal == null ? total : corporateTotal, fxRate,
                quote.get("fx_rate_date"), me);

        audit.record("QUOTE_CONVERTED", "QUOTE", quoteId,
                "Converted " + quoteNumber + " into order " + order.orderNumber(),
                Map.of("orderId", order.id().toString(), "orderNumber", order.orderNumber(),
                        "linesCopied", lines.size(), "totalAmount", total.toPlainString(),
                        "note", request == null || request.note() == null ? "not stated" : request.note()));

        return new ConversionResult(order.id(), order.orderNumber(), quoteId, quoteNumber,
                lines.size(), total, currency, "DRAFT",
                "The order was created as DRAFT with the quoted prices copied, not referenced. "
                        + "Book it when you are ready to commit to fulfilment.");
    }

    private Map<String, Object> quote(UUID tenant, UUID quoteId) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                select id, quote_number, account_id, status, currency_code, is_active_version,
                       corporate_grand_total, fx_rate, fx_rate_date
                from cpq.quote
                where tenant_id = ? and id = ?
                """, tenant, quoteId);
        if (found.isEmpty()) throw new NotFoundException("That quote does not exist in this workspace");
        return found.get(0);
    }

    /**
     * Checked here as well as by the unique index, because the index can only
     * answer "no" — this can say which order already holds the conversion, which
     * is the thing the person clicking Convert actually needs.
     */
    private void assertNotAlreadyConverted(UUID tenant, UUID quoteId, String quoteNumber) {
        List<String> existing = jdbc.queryForList("""
                select o.order_number
                from contracting.order_line l
                join contracting.order_record o on o.tenant_id = l.tenant_id and o.id = l.order_id
                join cpq.quote_line ql on ql.tenant_id = l.tenant_id and ql.id = l.source_quote_line_id
                where l.tenant_id = ? and ql.quote_id = ? and o.deleted_at is null
                limit 1
                """, String.class, tenant, quoteId);
        if (!existing.isEmpty()) {
            throw new ConflictException(quoteNumber + " has already been converted into order "
                    + existing.get(0) + ". Converting it again would book the same commitment twice. "
                    + "Amend that order, or raise a new quote.");
        }
    }
}

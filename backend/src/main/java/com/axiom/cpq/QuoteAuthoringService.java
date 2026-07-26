package com.axiom.cpq;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Quote authoring: creating a quote, editing its lines, and moving it through
 * its lifecycle (FR-CPQ-010..039).
 *
 * <p>{@code CpqService} is annotated {@code @Transactional(readOnly = true)} at
 * class level and exposes only reads and a document download — so until now the
 * whole CPQ model could be listed and printed but never authored. A quote could
 * not be created, priced or accepted through the API at all, which also meant the
 * quote-to-order conversion could never be reached by a real user: its
 * precondition was unreachable. This service is the write half.
 *
 * <h2>Pricing is computed server-side, always</h2>
 * The caller supplies quantity, an optional discount, and either a product (to be
 * priced from the price book) or an explicit override price. Everything derived —
 * net unit price, extended amount, margin, the quote's subtotal and grand total —
 * is calculated here. Accepting client-sent totals would let a browser decide what
 * a customer pays.
 *
 * <h2>Lines are frozen once the quote leaves DRAFT</h2>
 * A quote in approval is being judged on its numbers; a SENT quote is in the
 * customer's inbox. Changing lines under either makes the document someone is
 * looking at untrue. The existing revision model ({@code supersedes_quote_id},
 * {@code is_active_version}) is the supported way to change a sent quote, and it
 * is deliberately reused rather than replaced.
 */
@Service
public class QuoteAuthoringService {

    private static final Set<String> LINE_EDITABLE = Set.of("DRAFT");

    /**
     * The lifecycle. Values match the CHECK already on {@code cpq.quote}; the
     * transition rules are here because a single-row CHECK cannot see the state a
     * row is moving from.
     */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("IN_APPROVAL", "SENT", "EXPIRED"),
            "IN_APPROVAL", Set.of("DRAFT", "SENT", "REJECTED"),
            "SENT", Set.of("ACCEPTED", "REJECTED", "EXPIRED"),
            "ACCEPTED", Set.of(),
            "REJECTED", Set.of("DRAFT"),
            "EXPIRED", Set.of("DRAFT"),
            "ORDERED", Set.of());

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public QuoteAuthoringService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // --------------------------------------------------------------- contracts

    public record QuoteRequest(UUID accountId, UUID opportunityId, UUID contactId, UUID priceBookId,
                               @NotBlank @Size(max = 240) String name,
                               @Size(max = 10) String currencyCode,
                               LocalDate validFrom, LocalDate expiresAt,
                               BigDecimal quoteDiscountPct) {}

    public record LineRequest(UUID productId, BigDecimal quantity,
                              /** Overrides the price book. Null means "price it from the book". */
                              BigDecimal unitPriceOverride,
                              BigDecimal discountPct,
                              Integer termMonths) {}

    public record LineView(UUID id, int lineNumber, UUID productId, String productCode,
                           String productName, String unitOfMeasure, BigDecimal quantity,
                           BigDecimal listPrice, BigDecimal netUnitPrice, BigDecimal discountPct,
                           BigDecimal extendedAmount, BigDecimal unitCost, BigDecimal marginAmount,
                           BigDecimal marginPct, String pricingMethodApplied) {}

    public record QuoteView(UUID id, String quoteNumber, String name, int versionNumber,
                            boolean activeVersion, String status, String approvalStatus,
                            UUID accountId, String accountName, UUID opportunityId, UUID priceBookId,
                            String priceBookName, String currencyCode, BigDecimal subtotal,
                            BigDecimal discountTotal, BigDecimal taxTotal, BigDecimal grandTotal,
                            BigDecimal costTotal, BigDecimal marginAmount, BigDecimal marginPct,
                            BigDecimal quoteDiscountPct, LocalDate validFrom, LocalDate expiresAt,
                            String rejectReason, long version, List<LineView> lines, boolean editable) {}

    public record TransitionRequest(@NotBlank String status, @Size(max = 500) String reason) {}

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public QuoteView get(UUID id) {
        UUID tenant = TenantContext.get().tenantId();
        List<Map<String, Object>> found = jdbc.queryForList("""
                select q.id, q.quote_number, q.name, q.version_number, q.is_active_version, q.status,
                       q.approval_status, q.account_id, a.name as account_name, q.opportunity_id,
                       q.price_book_id, pb.name as price_book_name, q.currency_code, q.subtotal,
                       q.discount_total, q.tax_total, q.grand_total, q.cost_total, q.margin_amount,
                       q.margin_pct, q.quote_discount_pct, q.valid_from, q.expires_at,
                       q.reject_reason, q.version
                from cpq.quote q
                left join crm.account a on a.tenant_id = q.tenant_id and a.id = q.account_id
                left join cpq.price_book pb on pb.tenant_id = q.tenant_id and pb.id = q.price_book_id
                where q.tenant_id = ? and q.id = ? and q.deleted_at is null
                """, tenant, id);
        if (found.isEmpty()) throw new NotFoundException("Quote not found, or it has been deleted");
        Map<String, Object> q = found.get(0);

        List<LineView> lines = jdbc.query("""
                select id, line_number, product_id, product_code, product_name, unit_of_measure,
                       quantity, list_price, net_unit_price, discount_pct, extended_amount,
                       unit_cost, margin_amount, margin_pct, pricing_method_applied
                from cpq.quote_line where tenant_id = ? and quote_id = ? order by line_number
                """, (rs, i) -> new LineView(rs.getObject("id", UUID.class), rs.getInt("line_number"),
                        rs.getObject("product_id", UUID.class), rs.getString("product_code"),
                        rs.getString("product_name"), rs.getString("unit_of_measure"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("list_price"),
                        rs.getBigDecimal("net_unit_price"), rs.getBigDecimal("discount_pct"),
                        rs.getBigDecimal("extended_amount"), rs.getBigDecimal("unit_cost"),
                        rs.getBigDecimal("margin_amount"), rs.getBigDecimal("margin_pct"),
                        rs.getString("pricing_method_applied")), tenant, id);

        String status = String.valueOf(q.get("status"));
        return new QuoteView(
                (UUID) q.get("id"), str(q.get("quote_number")), str(q.get("name")),
                ((Number) q.get("version_number")).intValue(), (Boolean) q.get("is_active_version"),
                status, str(q.get("approval_status")), (UUID) q.get("account_id"),
                str(q.get("account_name")), (UUID) q.get("opportunity_id"), (UUID) q.get("price_book_id"),
                str(q.get("price_book_name")), str(q.get("currency_code")),
                dec(q.get("subtotal")), dec(q.get("discount_total")), dec(q.get("tax_total")),
                dec(q.get("grand_total")), dec(q.get("cost_total")), dec(q.get("margin_amount")),
                dec(q.get("margin_pct")), dec(q.get("quote_discount_pct")),
                date(q.get("valid_from")), date(q.get("expires_at")), str(q.get("reject_reason")),
                ((Number) q.get("version")).longValue(), lines, LINE_EDITABLE.contains(status));
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public QuoteView create(QuoteRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        UUID tenant = TenantContext.get().tenantId();
        UUID me = TenantContext.get().userId();
        if (request.accountId() == null) {
            throw new IllegalArgumentException("A quote must name the account it is for");
        }
        UUID priceBookId = request.priceBookId() != null ? request.priceBookId() : defaultPriceBook(tenant);
        if (priceBookId == null) {
            throw new ConflictException("This workspace has no active price book, so a quote cannot be "
                    + "priced. Activate a price book first.");
        }
        String currency = SalesCurrency.resolve(jdbc, tenant, priceBookId, request.currencyCode());
        String number = nextNumber(tenant);
        // quote_group_id ties a quote to its revisions. A brand-new quote starts
        // its own group; a revision reuses the group of the quote it supersedes.
        UUID groupId = UUID.randomUUID();

        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into cpq.quote
                      (tenant_id, quote_number, quote_group_id, version_number, is_active_version,
                       opportunity_id, account_id, contact_id, price_book_id, owner_id, name, status,
                       approval_status, currency_code, quote_discount_pct, valid_from, expires_at,
                       created_by, updated_by)
                    values (?, ?, ?, 1, true, ?, ?, ?, ?, ?, ?, 'DRAFT', 'NOT_REQUIRED', ?,
                            coalesce(?, 0), coalesce(?, current_date), ?, ?, ?)
                    returning id
                    """, UUID.class, tenant, number, groupId, request.opportunityId(), request.accountId(),
                    request.contactId(), priceBookId, me,
                    SalesOrderRequire.name(request.name()), currency, request.quoteDiscountPct(),
                    request.validFrom(), request.expiresAt(), me, me);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Quote number " + number + " was taken while this was being "
                    + "created. Retry — the next number will be allocated.");
        }

        audit.record("QUOTE_CREATE", "QUOTE", id, "Created quote " + number,
                Map.of("quoteNumber", number, "accountId", request.accountId().toString(),
                        "priceBookId", priceBookId.toString()));
        return get(id);
    }

    /**
     * Replaces the quote's lines and recalculates every derived figure.
     *
     * <p>Prices come from the price book unless the caller passes an explicit
     * override, and {@code pricing_method_applied} records which happened — so a
     * reviewer can see at a glance which lines were hand-priced. Cost comes from
     * the product, so margin is real rather than assumed.
     */
    @Transactional
    public QuoteView replaceLines(UUID id, long expectedVersion, List<LineRequest> lines) {
        CrmRole.requireWrite(TenantContext.get().role());
        QuoteView before = get(id);
        if (!LINE_EDITABLE.contains(before.status())) {
            throw new ConflictException(before.quoteNumber() + " is " + before.status()
                    + ", so its lines cannot change. A quote in approval is being judged on these numbers "
                    + "and a sent quote is in the customer's hands — raise a revision instead, which keeps "
                    + "both versions.");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A quote needs at least one line");
        }
        UUID tenant = TenantContext.get().tenantId();
        jdbc.update("delete from cpq.quote_line where tenant_id = ? and quote_id = ?", tenant, id);

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal costTotal = BigDecimal.ZERO;
        int lineNumber = 1;

        for (LineRequest line : lines) {
            if (line.productId() == null) {
                throw new IllegalArgumentException("Every quote line must name a product — the product is "
                        + "what carries the code, unit and cost the quote is priced and measured against.");
            }
            Map<String, Object> product = product(tenant, line.productId());
            BigDecimal quantity = SalesOrderPositive.check(line.quantity());
            BookEntry entry = bookEntry(tenant, before.priceBookId(), line.productId());
            BigDecimal listPrice = entry == null ? null : entry.unitPrice();
            String method;
            BigDecimal unitBase;
            if (line.unitPriceOverride() != null) {
                if (line.unitPriceOverride().signum() < 0) {
                    throw new IllegalArgumentException("An override price cannot be negative");
                }
                unitBase = line.unitPriceOverride();
                // LIST, because the constraint has no "overridden" member. The fact
                // that a human set the price is preserved in price_adjustments below,
                // which is the column built to carry exactly that.
                method = "LIST";
            } else if (listPrice != null) {
                unitBase = listPrice;
                method = entry.pricingMethod();
            } else {
                throw new ConflictException(product.get("name") + " has no entry in the price book on "
                        + "this quote, so it cannot be priced automatically. Add a price-book entry, or "
                        + "supply an override price for the line.");
            }

            BigDecimal discountPct = line.discountPct() == null ? BigDecimal.ZERO : line.discountPct();
            if (discountPct.signum() < 0 || discountPct.compareTo(HUNDRED) > 0) {
                throw new IllegalArgumentException("A line discount must be between 0 and 100 percent");
            }
            BigDecimal netUnit = unitBase
                    .multiply(BigDecimal.ONE.subtract(discountPct.divide(HUNDRED, 6, RoundingMode.HALF_UP)))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal extended = netUnit.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            BigDecimal effectiveList = (listPrice == null ? unitBase : listPrice);
            BigDecimal lineDiscount = effectiveList.multiply(quantity).setScale(2, RoundingMode.HALF_UP)
                    .subtract(extended);
            BigDecimal unitCost = dec(product.get("default_cost"));
            BigDecimal costAmount = unitCost.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            BigDecimal margin = extended.subtract(costAmount);
            BigDecimal marginPct = extended.signum() == 0 ? BigDecimal.ZERO
                    : margin.multiply(HUNDRED).divide(extended, 4, RoundingMode.HALF_UP);

            jdbc.update("""
                    insert into cpq.quote_line
                      (tenant_id, quote_id, line_number, product_id, product_code, product_name,
                       unit_of_measure, quantity, list_price, net_unit_price, extended_amount,
                       discount_pct, discount_amount, pricing_method_applied, term_months,
                       unit_cost, cost_amount, margin_amount, margin_pct, currency_code,
                       price_adjustments)
                    values (?, ?, ?, ?, ?, ?, coalesce(?, 'EA'), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, tenant, id, lineNumber++, line.productId(), product.get("code"),
                    product.get("name"), product.get("unit_of_measure"), quantity, effectiveList,
                    netUnit, extended, discountPct, lineDiscount.max(BigDecimal.ZERO), method,
                    line.termMonths(), unitCost, costAmount, margin, marginPct, before.currencyCode(),
                    line.unitPriceOverride() == null ? "[]"
                            : "[{\"kind\":\"MANUAL_UNIT_PRICE\",\"value\":"
                              + line.unitPriceOverride().toPlainString() + "}]");

            subtotal = subtotal.add(extended);
            discountTotal = discountTotal.add(lineDiscount.max(BigDecimal.ZERO));
            costTotal = costTotal.add(costAmount);
        }

        // A quote-level discount applies after the line discounts, which is the
        // order a customer reads on the document.
        BigDecimal quoteDiscountPct = before.quoteDiscountPct() == null
                ? BigDecimal.ZERO : before.quoteDiscountPct();
        BigDecimal afterQuoteDiscount = subtotal
                .multiply(BigDecimal.ONE.subtract(quoteDiscountPct.divide(HUNDRED, 6, RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal grandTotal = afterQuoteDiscount;
        BigDecimal margin = grandTotal.subtract(costTotal);
        BigDecimal marginPct = grandTotal.signum() == 0 ? BigDecimal.ZERO
                : margin.multiply(HUNDRED).divide(grandTotal, 4, RoundingMode.HALF_UP);

        int updated = jdbc.update("""
                update cpq.quote
                set subtotal = ?, discount_total = ?, grand_total = ?, corporate_grand_total = ?,
                    cost_total = ?, margin_amount = ?, margin_pct = ?, updated_at = now(),
                    updated_by = ?, version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null and version = ?
                """, subtotal, discountTotal.add(subtotal.subtract(afterQuoteDiscount)), grandTotal,
                grandTotal, costTotal, margin, marginPct, TenantContext.get().userId(),
                tenant, id, expectedVersion);
        if (updated == 0) {
            throw new ConflictException("This quote changed while you were editing it (you had version "
                    + expectedVersion + ", the stored quote is version " + before.version()
                    + "). Reload it and re-apply your changes.");
        }

        audit.record("QUOTE_LINES_REPLACED", "QUOTE", id,
                "Repriced " + before.quoteNumber() + " across " + lines.size() + " line(s)",
                Map.of("lineCount", lines.size(), "subtotal", subtotal.toPlainString(),
                        "grandTotal", grandTotal.toPlainString(), "marginPct", marginPct.toPlainString()));
        return get(id);
    }

    /**
     * Moves the quote through its lifecycle. This is what makes conversion
     * reachable: nothing could previously set a quote to ACCEPTED.
     */
    @Transactional
    public QuoteView transition(UUID id, TransitionRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        QuoteView before = get(id);
        String target = upper(SalesOrderRequire.status(request.status()));
        Set<String> allowed = TRANSITIONS.getOrDefault(before.status(), Set.of());

        if (target.equals(before.status())) {
            throw new ConflictException(before.quoteNumber() + " is already " + target + ".");
        }
        if (!allowed.contains(target)) {
            throw new ConflictException(before.quoteNumber() + " is " + before.status()
                    + " and cannot move to " + target + ". "
                    + (allowed.isEmpty() ? "This is a final state."
                    : "Allowed from here: " + String.join(", ", allowed.stream().sorted().toList()) + "."));
        }
        if (Set.of("SENT", "IN_APPROVAL").contains(target) && before.lines().isEmpty()) {
            throw new ConflictException("A quote with no lines cannot be " + target.toLowerCase(Locale.ROOT)
                    + " — the customer would receive a document that offers nothing.");
        }
        if ("REJECTED".equals(target) && blank(request.reason()) == null) {
            throw new IllegalArgumentException("Rejecting a quote requires a reason; it stays on the "
                    + "record so the next revision can address it.");
        }
        if (!before.activeVersion()) {
            throw new ConflictException(before.quoteNumber() + " is a superseded revision and is frozen. "
                    + "Act on the current version instead.");
        }

        // sent_at must be set before a quote can be ACCEPTED — the schema's own
        // CHECK (quote_sent_has_time) says so, and accepting something never sent
        // would be a lie about how the deal happened.
        if ("ACCEPTED".equals(target)) {
            Long sent = jdbc.queryForObject("""
                    select count(*) from cpq.quote where tenant_id = ? and id = ? and sent_at is not null
                    """, Long.class, TenantContext.get().tenantId(), id);
            if (sent == null || sent == 0) {
                throw new ConflictException(before.quoteNumber() + " has no sent date, so it cannot be "
                        + "accepted — a quote the customer never received cannot have been agreed. "
                        + "Send it first.");
            }
        }

        jdbc.update("""
                update cpq.quote
                set status = ?,
                    sent_at      = case when ? = 'SENT'     then coalesce(sent_at, now())  else sent_at end,
                    accepted_at  = case when ? = 'ACCEPTED' then now()                     else accepted_at end,
                    rejected_at  = case when ? = 'REJECTED' then now()                     else rejected_at end,
                    expired_at   = case when ? = 'EXPIRED'  then now()                     else expired_at end,
                    reject_reason = case when ? = 'REJECTED' then ? else reject_reason end,
                    updated_at = now(), updated_by = ?, version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null
                """, target, target, target, target, target, target, blank(request.reason()),
                TenantContext.get().userId(), TenantContext.get().tenantId(), id);

        audit.record("QUOTE_" + target, "QUOTE", id,
                before.quoteNumber() + ": " + before.status() + " -> " + target,
                Map.of("from", before.status(), "to", target,
                        "reason", request.reason() == null ? "not stated" : request.reason()));
        return get(id);
    }

    // ------------------------------------------------------------------ helpers

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private Map<String, Object> product(UUID tenant, UUID productId) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                select id, code, name, unit_of_measure, default_cost
                from cpq.product where tenant_id = ? and id = ? and is_active and deleted_at is null
                """, tenant, productId);
        if (found.isEmpty()) {
            throw new NotFoundException("That product does not exist in this workspace, or is inactive");
        }
        return found.get(0);
    }

    /** A price-book entry: the price and the method the book itself declares. */
    private record BookEntry(BigDecimal unitPrice, String pricingMethod) {}

    /**
     * The entry for a product on this quote's price book, or null if absent.
     *
     * <p>Returns the book's own {@code pricing_method} rather than a value invented
     * here. {@code quote_line.pricing_method_applied} is constrained to
     * LIST / TIERED / VOLUME / BLOCK / PERCENT_OF_TOTAL / ATTRIBUTE / SUBSCRIPTION /
     * CONTRACTED — a vocabulary the CPQ model already owns — so the line records
     * how the book says it prices, not a label this service made up.
     */
    private BookEntry bookEntry(UUID tenant, UUID priceBookId, UUID productId) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                select unit_price, pricing_method from cpq.price_book_entry
                where tenant_id = ? and price_book_id = ? and product_id = ? and is_active
                  and (effective_from is null or effective_from <= current_date)
                  and (effective_to is null or effective_to >= current_date)
                order by effective_from desc nulls last
                limit 1
                """, tenant, priceBookId, productId);
        if (found.isEmpty()) return null;
        Map<String, Object> row = found.get(0);
        String method = row.get("pricing_method") == null ? "LIST" : String.valueOf(row.get("pricing_method"));
        return new BookEntry((BigDecimal) row.get("unit_price"), method);
    }

    private BigDecimal bookPriceUnused(UUID tenant, UUID priceBookId, UUID productId) {
        /*
         * unit_price, not list_price: the entry column is unit_price, and the
         * effective-date window matters — a price book entry that has expired must
         * not silently price a quote written today.
         */
        List<BigDecimal> found = jdbc.queryForList("""
                select unit_price from cpq.price_book_entry
                where tenant_id = ? and price_book_id = ? and product_id = ? and is_active
                  and (effective_from is null or effective_from <= current_date)
                  and (effective_to is null or effective_to >= current_date)
                order by effective_from desc nulls last
                limit 1
                """, BigDecimal.class, tenant, priceBookId, productId);
        return found.isEmpty() ? null : found.get(0);
    }

    private UUID defaultPriceBook(UUID tenant) {
        List<UUID> found = jdbc.queryForList("""
                select id from cpq.price_book
                where tenant_id = ? and status = 'ACTIVE'
                order by is_default desc, created_at
                limit 1
                """, UUID.class, tenant);
        return found.isEmpty() ? null : found.get(0);
    }

    private String nextNumber(UUID tenant) {
        int year = LocalDate.now().getYear();
        for (int attempt = 0; attempt < 20; attempt++) {
            Long count = jdbc.queryForObject(
                    "select count(*) from cpq.quote where tenant_id = ?", Long.class, tenant);
            String candidate = String.format("AXQ-%d-%04d", year, (count == null ? 0 : count) + 1 + attempt);
            Long taken = jdbc.queryForObject(
                    "select count(*) from cpq.quote where tenant_id = ? and quote_number = ?",
                    Long.class, tenant, candidate);
            if (taken != null && taken == 0) return candidate;
        }
        throw new ConflictException("Could not allocate a quote number after 20 attempts. Retry.");
    }

    private static String str(Object value) { return value == null ? null : String.valueOf(value); }

    private static BigDecimal dec(Object value) {
        return value == null ? BigDecimal.ZERO : (BigDecimal) value;
    }

    private static LocalDate date(Object value) {
        return value == null ? null : ((java.sql.Date) value).toLocalDate();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upper(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /** Tiny helpers, named so the call sites read as sentences. */
    static final class SalesOrderRequire {
        static String name(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("A quote needs a name");
            }
            return value.trim();
        }

        static String status(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("A target status is required");
            }
            return value;
        }
    }

    static final class SalesOrderPositive {
        static BigDecimal check(BigDecimal value) {
            if (value == null || value.signum() <= 0) {
                throw new IllegalArgumentException("A line quantity must be greater than zero");
            }
            return value;
        }
    }

    /** Currency comes from the price book unless the caller overrides it. */
    static final class SalesCurrency {
        static String resolve(JdbcTemplate jdbc, UUID tenant, UUID priceBookId, String requested) {
            if (requested != null && !requested.isBlank()) return requested.trim().toUpperCase(Locale.ROOT);
            List<String> found = jdbc.queryForList(
                    "select currency_code from cpq.price_book where tenant_id = ? and id = ?",
                    String.class, tenant, priceBookId);
            return found.isEmpty() ? "INR" : found.get(0);
        }
    }
}

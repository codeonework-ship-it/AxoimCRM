package com.axiom.contracting;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Vendors and purchase orders (FR-PRC-001..024).
 *
 * <h2>Why vendors are not accounts</h2>
 * An account is somebody you sell to; a vendor is somebody you buy from. They
 * carry different identifiers, different payment terms, and — the reason that
 * actually forces two tables — different visibility. A salesperson should not be
 * able to read supplier pricing, and one table with a "type" column cannot express
 * that without every query in the system remembering to filter on it. The same
 * legal entity can be both, which {@code linked_account_id} records.
 *
 * <h2>Segregation of duties on approval</h2>
 * A purchase order commits money outward, so whoever approves it must not be
 * whoever raised it. The database enforces the inequality
 * ({@code po_approver_is_not_requester}) and this service produces the message,
 * because a CHECK cannot say "you raised this one — ask someone else".
 *
 * <h2>Receipt is per line</h2>
 * A two-line PO can receive one line and backorder the other, and the header
 * status is derived from the lines rather than declared. An order-level flag
 * cannot express a partial delivery, which is the normal case in procurement.
 */
@Service
public class ProcurementService {

    private static final Set<String> PO_EDITABLE = Set.of("DRAFT");
    private static final Set<String> PO_RECEIVABLE = Set.of("SENT", "PARTIALLY_RECEIVED");

    /** Transitions a caller may request. APPROVED and the receipt states are derived. */
    private static final Map<String, Set<String>> PO_TRANSITIONS = Map.of(
            "DRAFT", Set.of("PENDING_APPROVAL", "CANCELLED"),
            "PENDING_APPROVAL", Set.of("APPROVED", "DRAFT", "CANCELLED"),
            "APPROVED", Set.of("SENT", "CANCELLED"),
            "SENT", Set.of("CANCELLED"),
            "PARTIALLY_RECEIVED", Set.of("CANCELLED"),
            "RECEIVED", Set.of(),
            "CANCELLED", Set.of());

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ProcurementService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // --------------------------------------------------------------- contracts

    public record VendorRequest(@Size(max = 60) String vendorCode,
                                @NotBlank @Size(max = 240) String name,
                                @Size(max = 240) String legalName,
                                @Size(max = 40) String status,
                                @Size(max = 80) String category,
                                @Size(max = 80) String taxRegistration,
                                @Size(max = 40) String paymentTerms,
                                @Size(max = 40) String currencyCode,
                                @Email @Size(max = 240) String primaryEmail,
                                @Size(max = 60) String primaryPhone,
                                @Size(max = 240) String addressLine1,
                                @Size(max = 120) String city,
                                @Size(max = 10) String countryCode,
                                UUID linkedAccountId, UUID ownerId,
                                @Size(max = 1000) String notes) {}

    public record Vendor(UUID id, String vendorCode, String name, String legalName, String status,
                         String category, String taxRegistration, String paymentTerms,
                         String currencyCode, String primaryEmail, String primaryPhone,
                         String addressLine1, String city, String countryCode,
                         UUID linkedAccountId, String linkedAccountName, UUID ownerId, String ownerName,
                         String notes, long openPurchaseOrders, BigDecimal openCommitment,
                         Instant createdAt, Instant updatedAt, long version) {}

    public record PoRequest(UUID vendorId, LocalDate orderDate, LocalDate expectedAt,
                            @Size(max = 40) String currencyCode, @Size(max = 1000) String notes) {}

    public record PoLineRequest(UUID productId, @NotBlank @Size(max = 240) String description,
                                @Size(max = 20) String unitOfMeasure, BigDecimal quantity,
                                BigDecimal unitPrice, BigDecimal taxPct) {}

    public record PoLine(UUID id, int lineNumber, UUID productId, String description,
                         String unitOfMeasure, BigDecimal quantity, BigDecimal unitPrice,
                         BigDecimal taxPct, BigDecimal extendedAmount, BigDecimal quantityReceived) {}

    public record PurchaseOrder(UUID id, String poNumber, UUID vendorId, String vendorName,
                                String status, LocalDate orderDate, LocalDate expectedAt,
                                String currencyCode, BigDecimal subtotalAmount, BigDecimal taxAmount,
                                BigDecimal totalAmount, UUID requestedBy, String requestedByName,
                                UUID approvedBy, String approvedByName, Instant approvedAt,
                                String approvalNote, String cancelledReason, String notes,
                                Instant createdAt, Instant updatedAt, long version,
                                List<PoLine> lines, boolean editable) {}

    public record PoTransition(@NotBlank String status, @Size(max = 500) String reason) {}

    public record ReceiveRequest(UUID lineId, BigDecimal quantity) {}

    // ------------------------------------------------------------------ vendors

    @Transactional(readOnly = true)
    public List<Vendor> listVendors(String search, String status) {
        StringBuilder sql = new StringBuilder(VENDOR_SELECT)
                .append(" where v.tenant_id = ? and v.deleted_at is null");
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        String needle = SalesOrderService.blank(search);
        if (needle != null) {
            sql.append(" and (v.name ilike ? or v.vendor_code ilike ? or v.primary_email ilike ?)");
            String like = "%" + needle + "%";
            args.add(like); args.add(like); args.add(like);
        }
        String state = SalesOrderService.upper(status);
        if (state != null) { sql.append(" and v.status = ?"); args.add(state); }
        sql.append(" order by v.name");
        return jdbc.query(sql.toString(), (rs, i) -> vendor(rs), args.toArray());
    }

    @Transactional(readOnly = true)
    public Vendor getVendor(UUID id) {
        try {
            return jdbc.queryForObject(VENDOR_SELECT + " where v.tenant_id = ? and v.id = ? and v.deleted_at is null",
                    (rs, i) -> vendor(rs), TenantContext.get().tenantId(), id);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("Vendor not found, or it has been deleted");
        }
    }

    @Transactional
    public Vendor createVendor(VendorRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        UUID tenant = TenantContext.get().tenantId();
        UUID me = TenantContext.get().userId();
        String name = SalesOrderService.require(request.name(), "A vendor needs a name");
        String code = SalesOrderService.blank(request.vendorCode()) != null
                ? SalesOrderService.upper(request.vendorCode()) : nextVendorCode(tenant);

        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into procurement.vendor
                      (tenant_id, vendor_code, name, legal_name, status, category, tax_registration,
                       payment_terms, currency_code, primary_email, primary_phone, address_line1,
                       city, country_code, linked_account_id, owner_id, notes, created_by, updated_by)
                    values (?, ?, ?, ?, coalesce(?, 'ACTIVE'), ?, ?, coalesce(?, 'NET30'),
                            coalesce(?, 'INR'), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    returning id
                    """, UUID.class, tenant, code, name, SalesOrderService.blank(request.legalName()),
                    SalesOrderService.upper(request.status()), SalesOrderService.blank(request.category()),
                    SalesOrderService.blank(request.taxRegistration()),
                    SalesOrderService.upper(request.paymentTerms()),
                    SalesOrderService.upper(request.currencyCode()),
                    SalesOrderService.blank(request.primaryEmail()),
                    SalesOrderService.blank(request.primaryPhone()),
                    SalesOrderService.blank(request.addressLine1()),
                    SalesOrderService.blank(request.city()), SalesOrderService.upper(request.countryCode()),
                    request.linkedAccountId(), request.ownerId() == null ? me : request.ownerId(),
                    SalesOrderService.blank(request.notes()), me, me);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("A vendor with code \"" + code + "\" already exists in this "
                    + "workspace. Vendor codes are how purchase orders and invoices reference a supplier, "
                    + "so they cannot repeat.");
        }
        audit.record("VENDOR_CREATE", "VENDOR", id, "Created vendor " + name,
                Map.of("vendorCode", code, "name", name));
        return getVendor(id);
    }

    @Transactional
    public Vendor updateVendor(UUID id, long expectedVersion, VendorRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        Vendor before = getVendor(id);
        String name = SalesOrderService.require(request.name(), "A vendor needs a name");

        int updated = jdbc.update("""
                update procurement.vendor
                set name = ?, legal_name = ?, status = coalesce(?, status), category = ?,
                    tax_registration = ?, payment_terms = coalesce(?, payment_terms),
                    currency_code = coalesce(?, currency_code), primary_email = ?, primary_phone = ?,
                    address_line1 = ?, city = ?, country_code = ?, linked_account_id = ?,
                    owner_id = coalesce(?, owner_id), notes = ?, updated_at = now(),
                    updated_by = ?, version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null and version = ?
                """, name, SalesOrderService.blank(request.legalName()),
                SalesOrderService.upper(request.status()), SalesOrderService.blank(request.category()),
                SalesOrderService.blank(request.taxRegistration()),
                SalesOrderService.upper(request.paymentTerms()),
                SalesOrderService.upper(request.currencyCode()),
                SalesOrderService.blank(request.primaryEmail()),
                SalesOrderService.blank(request.primaryPhone()),
                SalesOrderService.blank(request.addressLine1()),
                SalesOrderService.blank(request.city()), SalesOrderService.upper(request.countryCode()),
                request.linkedAccountId(), request.ownerId(), SalesOrderService.blank(request.notes()),
                TenantContext.get().userId(), TenantContext.get().tenantId(), id, expectedVersion);
        if (updated == 0) {
            throw new ConflictException("This vendor changed while you were editing it (you had version "
                    + expectedVersion + ", the stored vendor is version " + before.version()
                    + "). Reload it and re-apply your changes.");
        }
        audit.record("VENDOR_UPDATE", "VENDOR", id, "Updated vendor " + name,
                Map.of("previousName", before.name(), "name", name, "fromVersion", expectedVersion));
        return getVendor(id);
    }

    /**
     * Soft delete, refused while the vendor still has open purchase orders.
     *
     * <p>Deleting a supplier with money committed to it leaves POs pointing at a
     * record that no longer resolves, and the commitment disappears from any
     * spend report that joins through the vendor.
     */
    @Transactional
    public void deleteVendor(UUID id, String reason) {
        CrmRole.requireWrite(TenantContext.get().role());
        Vendor before = getVendor(id);
        if (before.openPurchaseOrders() > 0) {
            throw new ConflictException(before.name() + " has " + before.openPurchaseOrders()
                    + " open purchase order(s) worth " + before.openCommitment().toPlainString()
                    + ". Close or cancel them first — deleting the vendor now would leave that "
                    + "commitment unattributable.");
        }
        jdbc.update("""
                update procurement.vendor set deleted_at = now(), deleted_by = ?, updated_at = now(),
                    updated_by = ?, version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null
                """, TenantContext.get().userId(), TenantContext.get().userId(),
                TenantContext.get().tenantId(), id);
        audit.record("VENDOR_DELETE", "VENDOR", id, "Deleted vendor " + before.name(),
                Map.of("reason", reason == null || reason.isBlank() ? "not stated" : reason.trim()));
    }

    // ---------------------------------------------------------- purchase orders

    @Transactional(readOnly = true)
    public List<PurchaseOrder> listPurchaseOrders(UUID vendorId, String status) {
        StringBuilder sql = new StringBuilder(PO_SELECT)
                .append(" where p.tenant_id = ? and p.deleted_at is null");
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        if (vendorId != null) { sql.append(" and p.vendor_id = ?"); args.add(vendorId); }
        String state = SalesOrderService.upper(status);
        if (state != null) { sql.append(" and p.status = ?"); args.add(state); }
        sql.append(" order by p.order_date desc, p.po_number desc");
        return jdbc.query(sql.toString(), (rs, i) -> po(rs, List.of()), args.toArray());
    }

    @Transactional(readOnly = true)
    public PurchaseOrder getPurchaseOrder(UUID id) {
        try {
            PurchaseOrder header = jdbc.queryForObject(
                    PO_SELECT + " where p.tenant_id = ? and p.id = ? and p.deleted_at is null",
                    (rs, i) -> po(rs, List.of()), TenantContext.get().tenantId(), id);
            return withLines(header);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("Purchase order not found, or it has been deleted");
        }
    }

    private PurchaseOrder withLines(PurchaseOrder header) {
        List<PoLine> lines = jdbc.query("""
                select id, line_number, product_id, description, unit_of_measure, quantity,
                       unit_price, tax_pct, extended_amount, quantity_received
                from procurement.purchase_order_line
                where tenant_id = ? and purchase_order_id = ? order by line_number
                """, (rs, i) -> new PoLine(rs.getObject("id", UUID.class), rs.getInt("line_number"),
                        rs.getObject("product_id", UUID.class), rs.getString("description"),
                        rs.getString("unit_of_measure"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_price"), rs.getBigDecimal("tax_pct"),
                        rs.getBigDecimal("extended_amount"), rs.getBigDecimal("quantity_received")),
                TenantContext.get().tenantId(), header.id());
        return rebuild(header, lines);
    }

    @Transactional
    public PurchaseOrder createPurchaseOrder(PoRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        if (request.vendorId() == null) {
            throw new IllegalArgumentException("A purchase order must name the vendor it is placed with");
        }
        Vendor vendor = getVendor(request.vendorId());
        if (Set.of("BLOCKED", "INACTIVE").contains(vendor.status())) {
            throw new ConflictException(vendor.name() + " is " + vendor.status()
                    + " and cannot be raised against. Reactivate the vendor first, or choose another.");
        }
        UUID tenant = TenantContext.get().tenantId();
        UUID me = TenantContext.get().userId();
        String number = nextPoNumber(tenant);

        UUID id = jdbc.queryForObject("""
                insert into procurement.purchase_order
                  (tenant_id, po_number, vendor_id, status, order_date, expected_at, currency_code,
                   requested_by, notes, created_by, updated_by)
                values (?, ?, ?, 'DRAFT', coalesce(?, current_date), ?,
                        coalesce(?, ?), ?, ?, ?, ?)
                returning id
                """, UUID.class, tenant, number, request.vendorId(), request.orderDate(),
                request.expectedAt(), SalesOrderService.upper(request.currencyCode()),
                vendor.currencyCode(), me, SalesOrderService.blank(request.notes()), me, me);

        audit.record("PO_CREATE", "PURCHASE_ORDER", id, "Raised purchase order " + number,
                Map.of("poNumber", number, "vendorId", request.vendorId().toString(),
                        "vendorName", vendor.name()));
        return getPurchaseOrder(id);
    }

    @Transactional
    public PurchaseOrder replaceLines(UUID id, long expectedVersion, List<PoLineRequest> lines) {
        CrmRole.requireWrite(TenantContext.get().role());
        PurchaseOrder before = getPurchaseOrder(id);
        if (!PO_EDITABLE.contains(before.status())) {
            throw new ConflictException(before.poNumber() + " is " + before.status()
                    + ", so its lines cannot change. An approved or sent PO is a commitment the vendor "
                    + "has seen; raise an amendment instead.");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A purchase order needs at least one line. To void it, "
                    + "cancel it instead — that keeps the record and its reason.");
        }
        UUID tenant = TenantContext.get().tenantId();
        jdbc.update("delete from procurement.purchase_order_line where tenant_id = ? and purchase_order_id = ?",
                tenant, id);

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        int lineNumber = 1;
        for (PoLineRequest line : lines) {
            BigDecimal quantity = SalesOrderService.positive(line.quantity(), "quantity");
            BigDecimal unitPrice = SalesOrderService.nonNegative(line.unitPrice(), "unit price");
            BigDecimal taxPct = line.taxPct() == null ? BigDecimal.ZERO : line.taxPct();
            if (taxPct.signum() < 0) throw new IllegalArgumentException("A tax rate cannot be negative");
            BigDecimal extended = SalesOrderService.extended(quantity, unitPrice, BigDecimal.ZERO);
            BigDecimal lineTax = extended.multiply(taxPct)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            jdbc.update("""
                    insert into procurement.purchase_order_line
                      (tenant_id, purchase_order_id, line_number, product_id, description,
                       unit_of_measure, quantity, unit_price, tax_pct, extended_amount)
                    values (?, ?, ?, ?, ?, coalesce(?, 'EA'), ?, ?, ?, ?)
                    """, tenant, id, lineNumber++, line.productId(),
                    SalesOrderService.require(line.description(), "Each line needs a description"),
                    SalesOrderService.upper(line.unitOfMeasure()), quantity, unitPrice, taxPct, extended);
            subtotal = subtotal.add(extended);
            tax = tax.add(lineTax);
        }

        int updated = jdbc.update("""
                update procurement.purchase_order
                set subtotal_amount = ?, tax_amount = ?, total_amount = ?, updated_at = now(),
                    updated_by = ?, version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null and version = ?
                """, subtotal, tax, subtotal.add(tax), TenantContext.get().userId(),
                tenant, id, expectedVersion);
        if (updated == 0) {
            throw new ConflictException("This purchase order changed while you were editing it (you had "
                    + "version " + expectedVersion + ", the stored order is version " + before.version()
                    + "). Reload it and re-apply your changes.");
        }
        audit.record("PO_LINES_REPLACED", "PURCHASE_ORDER", id,
                "Replaced the lines on " + before.poNumber(),
                Map.of("lineCount", lines.size(), "total", subtotal.add(tax).toPlainString()));
        return getPurchaseOrder(id);
    }

    /**
     * Moves a PO through its lifecycle, enforcing segregation of duties on the
     * approval step.
     */
    @Transactional
    public PurchaseOrder transition(UUID id, PoTransition request) {
        CrmRole.requireWrite(TenantContext.get().role());
        PurchaseOrder before = getPurchaseOrder(id);
        String target = SalesOrderService.upper(
                SalesOrderService.require(request.status(), "A target status is required"));
        Set<String> allowed = PO_TRANSITIONS.getOrDefault(before.status(), Set.of());

        if (target.equals(before.status())) {
            throw new ConflictException(before.poNumber() + " is already " + target + ".");
        }
        if (!allowed.contains(target)) {
            throw new ConflictException(before.poNumber() + " is " + before.status()
                    + " and cannot move to " + target + ". "
                    + (allowed.isEmpty() ? "This is a final state."
                    : "Allowed from here: " + String.join(", ", allowed.stream().sorted().toList()) + "."));
        }
        if ("PENDING_APPROVAL".equals(target) && before.lines().isEmpty()) {
            throw new ConflictException("A purchase order cannot be sent for approval with no lines — "
                    + "there would be nothing to approve.");
        }

        UUID me = TenantContext.get().userId();
        UUID approvedBy = before.approvedBy();
        if ("APPROVED".equals(target)) {
            // The database CHECK guarantees the inequality; this produces the
            // message a CHECK cannot, naming the conflict rather than the column.
            if (before.requestedBy() != null && before.requestedBy().equals(me)) {
                throw new ForbiddenException("You raised " + before.poNumber()
                        + ", so you cannot also approve it. A purchase order commits money outward and "
                        + "needs a second pair of eyes — ask another authorised approver.");
            }
            approvedBy = me;
        }
        if ("CANCELLED".equals(target) && SalesOrderService.blank(request.reason()) == null) {
            throw new IllegalArgumentException("Cancelling a purchase order requires a reason. The vendor "
                    + "may already have seen it, so the record has to say why it was withdrawn.");
        }

        jdbc.update("""
                update procurement.purchase_order
                set status = ?,
                    approved_by = case when ? = 'APPROVED' then ? else approved_by end,
                    approved_at = case when ? = 'APPROVED' then now() else approved_at end,
                    approval_note = case when ? = 'APPROVED' then ? else approval_note end,
                    cancelled_reason = case when ? = 'CANCELLED' then ? else cancelled_reason end,
                    updated_at = now(), updated_by = ?, version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null
                """, target, target, approvedBy, target, target, SalesOrderService.blank(request.reason()),
                target, SalesOrderService.blank(request.reason()), me,
                TenantContext.get().tenantId(), id);

        audit.record("PO_" + target, "PURCHASE_ORDER", id,
                before.poNumber() + ": " + before.status() + " -> " + target,
                Map.of("from", before.status(), "to", target,
                        "approvedBy", String.valueOf(approvedBy),
                        "reason", request.reason() == null ? "not stated" : request.reason()));
        return getPurchaseOrder(id);
    }

    /** Records receipt against one line; the header status follows the lines. */
    @Transactional
    public PurchaseOrder receive(UUID id, ReceiveRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        PurchaseOrder before = getPurchaseOrder(id);
        if (!PO_RECEIVABLE.contains(before.status())) {
            throw new ConflictException(before.poNumber() + " is " + before.status()
                    + "; goods can only be received against a sent or partially received order.");
        }
        PoLine line = before.lines().stream()
                .filter((candidate) -> candidate.id().equals(request.lineId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("That line is not on this purchase order"));

        BigDecimal quantity = SalesOrderService.positive(request.quantity(), "received quantity");
        BigDecimal newReceived = line.quantityReceived().add(quantity);
        if (newReceived.compareTo(line.quantity()) > 0) {
            throw new ConflictException("Line " + line.lineNumber() + " ordered "
                    + line.quantity().toPlainString() + " and " + line.quantityReceived().toPlainString()
                    + " is already received, so " + quantity.toPlainString() + " more would exceed it. "
                    + "At most " + line.quantity().subtract(line.quantityReceived()).toPlainString()
                    + " can be recorded — raise a separate order for anything extra the vendor sent.");
        }

        jdbc.update("""
                update procurement.purchase_order_line
                set quantity_received = ?, updated_at = now() where tenant_id = ? and id = ?
                """, newReceived, TenantContext.get().tenantId(), line.id());

        PurchaseOrder after = getPurchaseOrder(id);
        boolean all = after.lines().stream()
                .allMatch((c) -> c.quantityReceived().compareTo(c.quantity()) >= 0);
        String derived = all ? "RECEIVED" : "PARTIALLY_RECEIVED";
        if (!derived.equals(after.status())) {
            jdbc.update("""
                    update procurement.purchase_order set status = ?, updated_at = now(),
                        updated_by = ?, version = version + 1
                    where tenant_id = ? and id = ?
                    """, derived, TenantContext.get().userId(), TenantContext.get().tenantId(), id);
        }
        audit.record("PO_RECEIVE", "PURCHASE_ORDER", id,
                "Received " + quantity.toPlainString() + " on line " + line.lineNumber()
                        + " of " + before.poNumber(),
                Map.of("lineNumber", line.lineNumber(), "quantity", quantity.toPlainString(),
                        "status", derived));
        return getPurchaseOrder(id);
    }

    // ------------------------------------------------------------------ helpers

    private static final String VENDOR_SELECT = """
            select v.id, v.vendor_code, v.name, v.legal_name, v.status, v.category,
                   v.tax_registration, v.payment_terms, v.currency_code, v.primary_email,
                   v.primary_phone, v.address_line1, v.city, v.country_code,
                   v.linked_account_id, a.name as linked_account_name, v.owner_id,
                   u.display_name as owner_name, v.notes, v.created_at, v.updated_at, v.version,
                   (select count(*) from procurement.purchase_order p
                     where p.tenant_id = v.tenant_id and p.vendor_id = v.id and p.deleted_at is null
                       and p.status not in ('RECEIVED','CANCELLED')) as open_pos,
                   coalesce((select sum(p.total_amount) from procurement.purchase_order p
                     where p.tenant_id = v.tenant_id and p.vendor_id = v.id and p.deleted_at is null
                       and p.status not in ('RECEIVED','CANCELLED')), 0) as open_commitment
            from procurement.vendor v
            left join crm.account a on a.tenant_id = v.tenant_id and a.id = v.linked_account_id
            left join identity.app_user u on u.tenant_id = v.tenant_id and u.id = v.owner_id
            """;

    private Vendor vendor(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Vendor(rs.getObject("id", UUID.class), rs.getString("vendor_code"),
                rs.getString("name"), rs.getString("legal_name"), rs.getString("status"),
                rs.getString("category"), rs.getString("tax_registration"), rs.getString("payment_terms"),
                rs.getString("currency_code"), rs.getString("primary_email"), rs.getString("primary_phone"),
                rs.getString("address_line1"), rs.getString("city"), rs.getString("country_code"),
                rs.getObject("linked_account_id", UUID.class), rs.getString("linked_account_name"),
                rs.getObject("owner_id", UUID.class), rs.getString("owner_name"), rs.getString("notes"),
                rs.getLong("open_pos"), rs.getBigDecimal("open_commitment"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }

    private static final String PO_SELECT = """
            select p.id, p.po_number, p.vendor_id, v.name as vendor_name, p.status, p.order_date,
                   p.expected_at, p.currency_code, p.subtotal_amount, p.tax_amount, p.total_amount,
                   p.requested_by, r.display_name as requested_by_name, p.approved_by,
                   ap.display_name as approved_by_name, p.approved_at, p.approval_note,
                   p.cancelled_reason, p.notes, p.created_at, p.updated_at, p.version
            from procurement.purchase_order p
            left join procurement.vendor v on v.tenant_id = p.tenant_id and v.id = p.vendor_id
            left join identity.app_user r on r.tenant_id = p.tenant_id and r.id = p.requested_by
            left join identity.app_user ap on ap.tenant_id = p.tenant_id and ap.id = p.approved_by
            """;

    private PurchaseOrder po(java.sql.ResultSet rs, List<PoLine> lines) throws java.sql.SQLException {
        String status = rs.getString("status");
        return new PurchaseOrder(rs.getObject("id", UUID.class), rs.getString("po_number"),
                rs.getObject("vendor_id", UUID.class), rs.getString("vendor_name"), status,
                rs.getObject("order_date", LocalDate.class), rs.getObject("expected_at", LocalDate.class),
                rs.getString("currency_code"), rs.getBigDecimal("subtotal_amount"),
                rs.getBigDecimal("tax_amount"), rs.getBigDecimal("total_amount"),
                rs.getObject("requested_by", UUID.class), rs.getString("requested_by_name"),
                rs.getObject("approved_by", UUID.class), rs.getString("approved_by_name"),
                rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant(),
                rs.getString("approval_note"), rs.getString("cancelled_reason"), rs.getString("notes"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"), lines, PO_EDITABLE.contains(status));
    }

    private PurchaseOrder rebuild(PurchaseOrder p, List<PoLine> lines) {
        return new PurchaseOrder(p.id(), p.poNumber(), p.vendorId(), p.vendorName(), p.status(),
                p.orderDate(), p.expectedAt(), p.currencyCode(), p.subtotalAmount(), p.taxAmount(),
                p.totalAmount(), p.requestedBy(), p.requestedByName(), p.approvedBy(),
                p.approvedByName(), p.approvedAt(), p.approvalNote(), p.cancelledReason(), p.notes(),
                p.createdAt(), p.updatedAt(), p.version(), lines, p.editable());
    }

    private String nextVendorCode(UUID tenant) {
        for (int attempt = 0; attempt < 20; attempt++) {
            Long count = jdbc.queryForObject(
                    "select count(*) from procurement.vendor where tenant_id = ?", Long.class, tenant);
            String candidate = String.format("VND-%05d", (count == null ? 0 : count) + 1 + attempt);
            Long taken = jdbc.queryForObject(
                    "select count(*) from procurement.vendor where tenant_id = ? and vendor_code = ?",
                    Long.class, tenant, candidate);
            if (taken != null && taken == 0) return candidate;
        }
        throw new ConflictException("Could not allocate a vendor code after 20 attempts. Retry.");
    }

    private String nextPoNumber(UUID tenant) {
        int year = LocalDate.now().getYear();
        for (int attempt = 0; attempt < 20; attempt++) {
            Long count = jdbc.queryForObject(
                    "select count(*) from procurement.purchase_order where tenant_id = ?", Long.class, tenant);
            String candidate = String.format("PO-%d-%05d", year, (count == null ? 0 : count) + 1 + attempt);
            Long taken = jdbc.queryForObject(
                    "select count(*) from procurement.purchase_order where tenant_id = ? and po_number = ?",
                    Long.class, tenant, candidate);
            if (taken != null && taken == 0) return candidate;
        }
        throw new ConflictException("Could not allocate a PO number after 20 attempts. Retry.");
    }
}

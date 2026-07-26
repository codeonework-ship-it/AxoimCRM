package com.axiom.cpq;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Byte-preserving quote revision creation for FR-CPQ-004. */
@Service
public class QuoteRevisionService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final OutboxWriter outbox;

    public QuoteRevisionService(JdbcTemplate jdbc, AuditService audit, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
    }

    public record RevisionRequest(@NotBlank @Size(max = 1000) String reason) {}
    public record RevisionResult(UUID previousQuoteId, UUID quoteId, UUID quoteGroupId,
                                 String quoteNumber, int versionNumber, String status,
                                 int copiedLines, String message) {}
    private record QuoteLock(UUID id, UUID groupId, String number, int version, String status, boolean active) {}
    private record LineKey(UUID id, UUID parentId) {}

    @Transactional
    public RevisionResult revise(UUID quoteId, RevisionRequest request) {
        requireWrite();
        String reason = request.reason().trim();
        QuoteLock old = lock(quoteId);
        if (!old.active()) throw new ConflictException("Only the active quote version can be revised.");
        if ("ORDERED".equals(old.status())) {
            throw new ConflictException("An ordered quote is locked. Amend the resulting contract instead.");
        }
        int nextVersion = old.version() + 1;
        String baseNumber = jdbc.queryForObject("""
                select quote_number from cpq.quote
                where tenant_id = ? and quote_group_id = ? and version_number = 1
                """, String.class, tenantId(), old.groupId());
        String nextNumber = baseNumber + "-V" + nextVersion;
        UUID nextId = UUID.randomUUID();

        // The superseded-by FK is deferred. Retiring the old row first is what
        // allows the one-active-version index to remain true at every statement.
        jdbc.update("""
                update cpq.quote
                set is_active_version = false, superseded_by_quote_id = ?, superseded_at = now(),
                    updated_at = now(), updated_by = ?, version = version + 1
                where tenant_id = ? and id = ?
                """, nextId, userId(), tenantId(), old.id());
        jdbc.update("""
                insert into cpq.quote
                  (id, tenant_id, quote_number, quote_group_id, version_number, is_active_version,
                   supersedes_quote_id, opportunity_id, account_id, contact_id, price_book_id, owner_id,
                   name, status, approval_status, currency_code, subtotal, discount_total, tax_total,
                   grand_total, corporate_currency_code, corporate_grand_total, fx_rate, fx_rate_date,
                   cost_total, margin_amount, margin_pct, quote_discount_pct, valid_from, expires_at,
                   change_reason, created_by, updated_by)
                select ?, tenant_id, ?, quote_group_id, ?, true, id, opportunity_id, account_id,
                       contact_id, price_book_id, owner_id, name, 'DRAFT', 'NOT_REQUIRED', currency_code,
                       subtotal, discount_total, tax_total, grand_total, corporate_currency_code,
                       corporate_grand_total, fx_rate, fx_rate_date, cost_total, margin_amount,
                       margin_pct, quote_discount_pct, current_date, expires_at, ?, ?, ?
                from cpq.quote where tenant_id = ? and id = ?
                """, nextId, nextNumber, nextVersion, reason, userId(), userId(), tenantId(), old.id());

        List<LineKey> lines = jdbc.query("""
                select id, bundle_parent_line_id from cpq.quote_line
                where tenant_id = ? and quote_id = ? order by line_number
                """, (rs, i) -> new LineKey(rs.getObject("id", UUID.class),
                rs.getObject("bundle_parent_line_id", UUID.class)), tenantId(), old.id());
        Map<UUID, UUID> ids = new LinkedHashMap<>();
        lines.forEach(line -> ids.put(line.id(), UUID.randomUUID()));
        for (LineKey line : lines) {
            jdbc.update("""
                    insert into cpq.quote_line
                      (id, tenant_id, quote_id, line_number, product_id, product_code, product_name,
                       unit_of_measure, bundle_parent_line_id, is_required_component, quantity, list_price,
                       net_unit_price, extended_amount, discount_pct, discount_amount,
                       pricing_method_applied, price_adjustments, term_months, subscription_start,
                       subscription_end, proration_factor, unit_cost, cost_amount, margin_amount, margin_pct,
                       currency_code, corporate_extended_amount, fx_rate, fx_rate_date,
                       price_book_entry_id, contracted_price_id)
                    select ?, tenant_id, ?, line_number, product_id, product_code, product_name,
                           unit_of_measure, ?, is_required_component, quantity, list_price, net_unit_price,
                           extended_amount, discount_pct, discount_amount, pricing_method_applied,
                           price_adjustments, term_months, subscription_start, subscription_end,
                           proration_factor, unit_cost, cost_amount, margin_amount, margin_pct,
                           currency_code, corporate_extended_amount, fx_rate, fx_rate_date,
                           price_book_entry_id, contracted_price_id
                    from cpq.quote_line where tenant_id = ? and id = ?
                    """, ids.get(line.id()), nextId,
                    line.parentId() == null ? null : ids.get(line.parentId()), tenantId(), line.id());
            jdbc.update("""
                    insert into cpq.quote_line_adjustment
                      (tenant_id, quote_line_id, sequence_no, adjustment_type, label, basis_unit_price,
                       amount, resulting_unit_price, source_ref, detail)
                    select tenant_id, ?, sequence_no, adjustment_type, label, basis_unit_price,
                           amount, resulting_unit_price, source_ref, detail
                    from cpq.quote_line_adjustment
                    where tenant_id = ? and quote_line_id = ?
                    """, ids.get(line.id()), tenantId(), line.id());
        }

        Map<String, Object> evidence = Map.of("previousQuoteId", old.id().toString(),
                "previousVersion", old.version(), "version", nextVersion, "lineCount", lines.size(),
                "reason", reason);
        audit.recordWithReason("QUOTE_REVISED", "QUOTE", nextId,
                "Created " + nextNumber + " from " + old.number(), reason, evidence);
        outbox.write("quote", nextId, "quote.revised", evidence);
        return new RevisionResult(old.id(), nextId, old.groupId(), nextNumber, nextVersion,
                "DRAFT", lines.size(), "New draft revision created; the previous version remains immutable.");
    }

    private QuoteLock lock(UUID id) {
        List<QuoteLock> rows = jdbc.query("""
                select id, quote_group_id, quote_number, version_number, status, is_active_version
                from cpq.quote where tenant_id = ? and id = ? and deleted_at is null for update
                """, (rs, i) -> new QuoteLock(rs.getObject("id", UUID.class),
                rs.getObject("quote_group_id", UUID.class), rs.getString("quote_number"),
                rs.getInt("version_number"), rs.getString("status"), rs.getBoolean("is_active_version")),
                tenantId(), id);
        return rows.stream().findFirst().orElseThrow(() -> new NotFoundException("Quote not found"));
    }

    private void requireWrite() {
        if (CrmRole.current(TenantContext.get().role()).readOnly()) {
            throw new ForbiddenException("Your role can read quotes but cannot create revisions.");
        }
    }

    private static UUID tenantId() { return TenantContext.get().tenantId(); }
    private static UUID userId() { return TenantContext.get().userId(); }
}

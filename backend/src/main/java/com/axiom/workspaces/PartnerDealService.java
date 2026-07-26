package com.axiom.workspaces;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PartnerDealService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public PartnerDealService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record RegisterRequest(UUID opportunityId, BigDecimal amount, String idempotencyKey) {}
    public record Registration(UUID id, String registrationNumber, String customerName, String dealName,
                               String status, String conflictStatus, int conflictCount, Instant protectionExpiresAt,
                               boolean replayed) {}

    @Transactional
    public Registration register(UUID partnerId, RegisterRequest request) {
        CrmRole.requireWrite(TenantContext.get().role());
        if (request == null || request.opportunityId() == null) {
            throw new IllegalArgumentException("Choose an opportunity to register.");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("An idempotency key is required for safe retry.");
        }
        List<Registration> prior = findByKey(request.idempotencyKey().trim());
        if (!prior.isEmpty()) return replay(prior.getFirst());

        Map<String, Object> partner = one("""
                select id, partner_code, territory_scope from channel.partner_account
                where tenant_id = ? and id = ? and status = 'ACTIVE' and deleted_at is null
                """, partnerId, "Only active partners can register deals.");
        Map<String, Object> opportunity = one("""
                select o.id, o.name, o.amount, a.name as customer_name
                from sales.opportunity o join crm.account a
                  on a.tenant_id = o.tenant_id and a.id = o.account_id
                where o.tenant_id = ? and o.id = ? and o.is_closed = false
                """, request.opportunityId(), "Only open opportunities can be registered.");
        UUID id = UUID.randomUUID();
        String number = "REG-" + id.toString().substring(0, 8).toUpperCase();
        BigDecimal amount = request.amount() == null ? (BigDecimal) opportunity.get("amount") : request.amount();
        if (amount.signum() < 0) throw new IllegalArgumentException("Registration amount cannot be negative.");
        try {
            jdbc.update("""
                    insert into channel.deal_registration
                      (id, tenant_id, partner_account_id, opportunity_id, registration_number,
                       customer_name, deal_name, status, amount, approval_sla_due_at, idempotency_key)
                    values (?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, now() + interval '2 days', ?)
                    """, id, TenantContext.get().tenantId(), partnerId, request.opportunityId(), number,
                    opportunity.get("customer_name"), opportunity.get("name"), amount, request.idempotencyKey().trim());
        } catch (DuplicateKeyException ex) {
            List<Registration> concurrent = findByKey(request.idempotencyKey().trim());
            if (!concurrent.isEmpty()) return replay(concurrent.getFirst());
            throw new ConflictException("This registration could not be created because its key is already in use.");
        }
        int conflicts = jdbc.update("""
                insert into channel.channel_conflict
                  (tenant_id, deal_registration_id, conflict_type, status, reason)
                select ?, ?, 'PARTNER_OVERLAP', 'OPEN',
                       'Another protected partner registration exists for ' || r.customer_name
                from channel.deal_registration r
                where r.tenant_id = ? and r.id <> ? and r.partner_account_id <> ?
                  and lower(r.customer_name) = lower(?)
                  and r.status in ('SUBMITTED','APPROVED')
                  and (r.protection_expires_at is null or r.protection_expires_at > now())
                limit 1
                """, TenantContext.get().tenantId(), id, TenantContext.get().tenantId(), id, partnerId,
                opportunity.get("customer_name"));
        Instant protection = conflicts == 0 ? Instant.now().plus(90, ChronoUnit.DAYS) : null;
        jdbc.update("""
                update channel.deal_registration
                set conflict_checked_at = now(), conflict_status = ?, status = ?, approved_at = ?, protection_expires_at = ?
                where tenant_id = ? and id = ?
                """, conflicts == 0 ? "CLEAR" : "CONFLICT", conflicts == 0 ? "APPROVED" : "SUBMITTED",
                conflicts == 0 ? Timestamp.from(Instant.now()) : null,
                protection == null ? null : Timestamp.from(protection), TenantContext.get().tenantId(), id);
        audit.record("PARTNER_DEAL_REGISTERED", "PARTNER_ACCOUNT", partnerId,
                "Registered " + number + " for " + partner.get("partner_code"),
                Map.of("registrationId", id, "conflictCount", conflicts));
        return get(id, false);
    }

    @Transactional(readOnly = true)
    public List<Registration> list(UUID partnerId) {
        return query("where r.tenant_id = ? and r.partner_account_id = ? order by r.submitted_at desc",
                TenantContext.get().tenantId(), partnerId);
    }

    private Map<String, Object> one(String sql, UUID id, String missing) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, TenantContext.get().tenantId(), id);
        if (rows.isEmpty()) throw new NotFoundException(missing);
        return rows.getFirst();
    }

    private List<Registration> findByKey(String key) {
        return query("where r.tenant_id = ? and r.idempotency_key = ?", TenantContext.get().tenantId(), key);
    }

    private Registration get(UUID id, boolean replayed) {
        List<Registration> rows = query("where r.tenant_id = ? and r.id = ?", TenantContext.get().tenantId(), id);
        if (rows.isEmpty()) throw new NotFoundException("Deal registration not found");
        Registration r = rows.getFirst();
        return replayed ? replay(r) : r;
    }

    private Registration replay(Registration r) {
        return new Registration(r.id(), r.registrationNumber(), r.customerName(), r.dealName(), r.status(),
                r.conflictStatus(), r.conflictCount(), r.protectionExpiresAt(), true);
    }

    private List<Registration> query(String where, Object... args) {
        return jdbc.query("""
                select r.id, r.registration_number, r.customer_name, r.deal_name, r.status,
                       r.conflict_status, r.protection_expires_at,
                       (select count(*) from channel.channel_conflict c
                         where c.tenant_id = r.tenant_id and c.deal_registration_id = r.id and c.status = 'OPEN') conflicts
                from channel.deal_registration r
                """ + where, (rs, i) -> new Registration(rs.getObject("id", UUID.class),
                rs.getString("registration_number"), rs.getString("customer_name"), rs.getString("deal_name"),
                rs.getString("status"), rs.getString("conflict_status"), rs.getInt("conflicts"),
                rs.getTimestamp("protection_expires_at") == null ? null : rs.getTimestamp("protection_expires_at").toInstant(),
                false), args);
    }
}

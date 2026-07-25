package com.axiom.audit;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * FR-AUD-007 — walks a tenant's audit chain and reports the first break.
 *
 * <p>The recomputation runs inside PostgreSQL ({@code governance.verify_audit_chain}),
 * not here. That is not laziness: the writer's hash is produced by a database
 * trigger over a canonical byte string that includes a {@code timestamptz} and a
 * {@code jsonb} value. A Java re-implementation would eventually disagree with
 * Postgres about microsecond formatting or key ordering and report a tamper that
 * never happened — the worst possible failure mode for this feature, because it
 * destroys trust in the one control that exists to create it.
 *
 * <p>Three distinct findings are possible and are reported separately:
 * <ul>
 *   <li>{@code SEQUENCE_GAP} — an event was removed. Detected even though the
 *       event itself is gone, because {@code sequence_no} is monotonic per tenant.</li>
 *   <li>{@code CONTENT_TAMPERED} — an event's stored hash no longer matches its
 *       content.</li>
 *   <li>{@code CHAIN_LINK_MISMATCH} — an event no longer links to its predecessor.</li>
 * </ul>
 */
@Service
public class AuditChainService {

    public record ChainVerification(String status, long eventsChecked, Long firstSequence, Long lastSequence,
                                    String breakType, Long breakSequence, UUID breakEventId,
                                    String detail, Instant verifiedAt) {
        public boolean intact() { return "OK".equals(status); }
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public AuditChainService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Verifies the current tenant's chain and audits the verification itself. */
    @Transactional
    public ChainVerification verify() {
        ChainVerification result = verifyOnly();
        Map<String, Object> details = new HashMap<>();
        details.put("status", result.status());
        details.put("eventsChecked", result.eventsChecked());
        details.put("firstSequence", result.firstSequence());
        details.put("lastSequence", result.lastSequence());
        details.put("breakType", result.breakType());
        details.put("breakSequence", result.breakSequence());
        audit.record("AUDIT_CHAIN_VERIFY", "AUDIT_EVENT", null,
                result.intact()
                        ? "Audit chain verified intact (" + result.eventsChecked() + " events)"
                        : "Audit chain verification FAILED: " + result.breakType(),
                details);
        return result;
    }

    /**
     * Verification without writing an audit event. Used by the SLI evaluator,
     * which runs on a schedule and would otherwise grow the trail it measures.
     */
    @Transactional(readOnly = true)
    public ChainVerification verifyOnly() {
        return verifyFor(TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public ChainVerification verifyFor(UUID tenantId) {
        return jdbc.queryForObject("""
                select status, events_checked, first_sequence, last_sequence,
                       break_type, break_sequence, break_event_id, detail
                from governance.verify_audit_chain(?)
                """, (rs, i) -> new ChainVerification(
                        rs.getString("status"),
                        rs.getLong("events_checked"),
                        (Long) rs.getObject("first_sequence"),
                        (Long) rs.getObject("last_sequence"),
                        rs.getString("break_type"),
                        (Long) rs.getObject("break_sequence"),
                        rs.getObject("break_event_id", UUID.class),
                        rs.getString("detail"),
                        Instant.now()),
                tenantId);
    }
}

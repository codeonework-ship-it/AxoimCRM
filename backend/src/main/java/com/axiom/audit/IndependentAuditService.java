package com.axiom.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Writes an audit event in its own transaction, so it survives the rollback of
 * the business transaction that triggered it.
 *
 * <p>This exists for refusals. {@code FR-TEN-009} requires that a failed step-up
 * is audited <em>and</em> that the action does not occur — and the natural way to
 * stop the action is to throw, which rolls the transaction back and takes an
 * audit row written inside it along with it. Refusals are exactly the events a
 * security review asks for, so they cannot be the ones that quietly vanish.
 *
 * <p>Separate bean rather than a method on {@link AuditService} because
 * {@code REQUIRES_NEW} is honoured through the Spring proxy only — a self-call
 * would silently join the caller's transaction and reintroduce the bug.
 */
@Service
public class IndependentAuditService {

    private final AuditService audit;

    public IndependentAuditService(AuditService audit) {
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String entityType, UUID entityId, String summary,
                       String reason, Map<String, ?> details) {
        audit.recordWithReason(action, entityType, entityId, summary, reason, details);
    }
}

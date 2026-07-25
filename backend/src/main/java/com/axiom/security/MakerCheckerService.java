package com.axiom.security;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Maker-checker (FR-SEC-010): for a designated controlled action, the user who
 * initiates it cannot be the user who approves it.
 *
 * <h2>Transitivity through delegation is the whole requirement</h2>
 * The naive rule — {@code approver != initiator} — is a one-hop bypass. Submit as
 * yourself, arrange for the approval authority to be delegated to you, approve.
 * FR-SEC-010 therefore extends the constraint through the delegation chain, and
 * this service walks that chain transitively.
 *
 * <p><b>The chain is walked in both directions, deliberately.</b> The model document
 * gives one case: "if approver A delegates to B, and the initiator is B, the
 * approval is still refused". The mirror case — the initiator delegated their
 * authority to the approver — is the same conflict of interest seen from the other
 * end, and both are one edge in the same graph. Treating delegation as undirected
 * for this check costs nothing and closes the case where the bypass is built by
 * delegating outward rather than inward. A directed reading would leave exactly one
 * of the two arrangements exploitable, and it would be the easier one to set up.
 *
 * <p>Refusals are audited as segregation violations, not merely rejected. An
 * attempted self-approval is a signal about intent, and it is the event a reviewer
 * looks for after an incident.
 */
@Service
public class MakerCheckerService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;

    public MakerCheckerService(JdbcTemplate jdbc, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    public record ApprovalRequest(UUID id, String actionCode, String entityType, UUID entityId,
                                  String summary, String payload, UUID initiatedBy,
                                  String initiatedByEmail, Instant initiatedAt, String status,
                                  UUID decidedBy, String decidedByEmail, Instant decidedAt,
                                  String decisionNote) {}

    public record SubmitRequest(@NotBlank String actionCode, @NotBlank String entityType,
                                UUID entityId, @NotBlank String summary, Map<String, Object> payload) {}

    public record DelegationRequest(java.util.UUID delegateId, String note, Instant expiresAt) {}

    // ------------------------------------------------------------------ submit

    @Transactional
    public ApprovalRequest submit(SubmitRequest request) {
        TenantContext.Principal p = TenantContext.get();
        requireControlledAction(request.actionCode());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into security.approval_request
                  (id, tenant_id, action_code, entity_type, entity_id, summary, payload, initiated_by)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, id, p.tenantId(), request.actionCode(), request.entityType(), request.entityId(),
                request.summary(), payload(request.payload()), p.userId());
        audit.record("CONTROLLED_ACTION_SUBMITTED", request.entityType(), request.entityId(),
                request.summary(),
                Map.of("actionCode", request.actionCode(), "approvalRequestId", id.toString()));
        return find(id);
    }

    private String payload(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Approval payload could not be serialized", ex);
        }
    }

    private void requireControlledAction(String actionCode) {
        Boolean exists = jdbc.queryForObject(
                "select exists (select 1 from security.controlled_action where action_code = ?)",
                Boolean.class, actionCode);
        if (!Boolean.TRUE.equals(exists)) {
            throw new NotFoundException("'" + actionCode + "' is not a designated controlled action. "
                    + "Register it in security.controlled_action before submitting one for approval.");
        }
    }

    // ------------------------------------------------------------------ decide

    @Transactional
    public ApprovalRequest approve(UUID requestId, String note) {
        return decide(requestId, "APPROVED", note);
    }

    @Transactional
    public ApprovalRequest reject(UUID requestId, String note) {
        return decide(requestId, "REJECTED", note);
    }

    private ApprovalRequest decide(UUID requestId, String decision, String note) {
        TenantContext.Principal p = TenantContext.get();
        ApprovalRequest request = find(requestId);
        if (!"PENDING".equals(request.status())) {
            throw new ConflictException("This request was already " + request.status().toLowerCase()
                    + " and cannot be decided again.");
        }

        assertApproverIsPermitted(request, p.userId());

        jdbc.update("""
                update security.approval_request
                set status = ?, decided_by = ?, decided_at = now(), decision_note = ?
                where tenant_id = ? and id = ? and status = 'PENDING'
                """, decision, p.userId(), note, p.tenantId(), requestId);

        audit.record("CONTROLLED_ACTION_" + decision, request.entityType(), request.entityId(),
                decision.charAt(0) + decision.substring(1).toLowerCase() + " " + request.summary(),
                Map.of("actionCode", request.actionCode(), "approvalRequestId", requestId.toString(),
                        "initiatedBy", request.initiatedByEmail(),
                        "note", note == null ? "" : note));
        return find(requestId);
    }

    /**
     * The control itself. Public so a caller that approves through another path can
     * apply the same rule instead of reimplementing it.
     *
     * @throws ForbiddenException audited as a segregation violation before it is thrown
     */
    @Transactional
    public void assertApproverIsPermitted(ApprovalRequest request, UUID approverId) {
        if (approverId.equals(request.initiatedBy())) {
            refuse(request, approverId, "the initiator of a controlled action cannot approve it");
        }
        Set<UUID> chain = delegationChain(request.initiatedBy());
        if (chain.contains(approverId)) {
            refuse(request, approverId, "approval authority is delegated between the initiator ("
                    + request.initiatedByEmail() + ") and you, so the maker-checker separation would be "
                    + "bypassed. FR-SEC-010 applies transitively through delegation.");
        }
    }

    private void refuse(ApprovalRequest request, UUID approverId, String because) {
        audit.recordWithReason("SEGREGATION_VIOLATION", request.entityType(), request.entityId(),
                "Approval refused: " + because, "MAKER_CHECKER",
                Map.of("actionCode", request.actionCode(),
                        "approvalRequestId", request.id().toString(),
                        "initiatedBy", request.initiatedByEmail(),
                        "attemptedApproverId", approverId.toString(),
                        "control", "FR-SEC-010"));
        throw new ForbiddenException("Approval refused: " + because
                + ". Ask a colleague outside this delegation chain to approve.");
    }

    /**
     * Every user connected to {@code userId} by an active delegation, transitively.
     *
     * <p>Breadth-first over an undirected view of the delegation graph — see the class
     * comment for why direction is ignored. Expired and deactivated delegations are
     * excluded in SQL, so a delegation that lapsed at noon stops widening the chain at
     * noon rather than at the next sweep.
     */
    @Transactional(readOnly = true)
    public Set<UUID> delegationChain(UUID userId) {
        UUID tenantId = TenantContext.get().tenantId();
        Set<UUID> seen = new LinkedHashSet<>();
        Set<UUID> visited = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(userId);
        visited.add(userId);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            List<UUID> neighbours = jdbc.query("""
                    select delegate_id from security.approval_delegation
                    where tenant_id = ? and delegator_id = ? and active = true
                      and starts_at <= now() and (expires_at is null or expires_at > now())
                    union
                    select delegator_id from security.approval_delegation
                    where tenant_id = ? and delegate_id = ? and active = true
                      and starts_at <= now() and (expires_at is null or expires_at > now())
                    """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, current, tenantId, current);
            for (UUID next : neighbours) {
                if (visited.add(next)) {
                    seen.add(next);
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    // ------------------------------------------------------------------ delegation admin

    public record Delegation(UUID id, UUID delegatorId, String delegatorEmail,
                             UUID delegateId, String delegateEmail,
                             Instant startsAt, Instant expiresAt, boolean active) {}

    @Transactional(readOnly = true)
    public List<Delegation> delegations() {
        return jdbc.query("""
                select d.id, d.delegator_id, du.email as delegator_email,
                       d.delegate_id, eu.email as delegate_email, d.starts_at, d.expires_at, d.active
                from security.approval_delegation d
                join identity.app_user du on du.tenant_id = d.tenant_id and du.id = d.delegator_id
                join identity.app_user eu on eu.tenant_id = d.tenant_id and eu.id = d.delegate_id
                where d.tenant_id = ?
                order by d.created_at desc
                """, (rs, i) -> new Delegation(rs.getObject("id", UUID.class),
                        rs.getObject("delegator_id", UUID.class), rs.getString("delegator_email"),
                        rs.getObject("delegate_id", UUID.class), rs.getString("delegate_email"),
                        rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getBoolean("active")),
                TenantContext.get().tenantId());
    }

    /** A user delegates their own approval authority. Nobody delegates on another's behalf. */
    @Transactional
    public Delegation delegate(UUID delegateId, Instant expiresAt) {
        TenantContext.Principal p = TenantContext.get();
        if (p.userId().equals(delegateId)) {
            throw new ConflictException("You cannot delegate approval authority to yourself.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into security.approval_delegation
                  (id, tenant_id, delegator_id, delegate_id, expires_at, created_by)
                values (?, ?, ?, ?, ?, ?)
                """, id, p.tenantId(), p.userId(), delegateId,
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt), p.userId());
        audit.record("APPROVAL_DELEGATED", "APPROVAL_DELEGATION", id,
                "Delegated approval authority",
                Map.of("delegateId", delegateId.toString(),
                        "expiresAt", expiresAt == null ? "never" : expiresAt.toString()));
        return delegations().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void revokeDelegation(UUID delegationId) {
        TenantContext.Principal p = TenantContext.get();
        int updated = jdbc.update("""
                update security.approval_delegation set active = false
                where tenant_id = ? and id = ? and delegator_id = ?
                """, p.tenantId(), delegationId, p.userId());
        if (updated == 0) {
            throw new NotFoundException("No delegation of yours with that id is open");
        }
        audit.record("APPROVAL_DELEGATION_REVOKED", "APPROVAL_DELEGATION", delegationId,
                "Revoked a delegation of approval authority", Map.of());
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<ApprovalRequest> pending() {
        return list("PENDING");
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequest> list(String status) {
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        String filter = "";
        if (status != null && !status.isBlank()) {
            filter = " and r.status = ?";
            args.add(status.toUpperCase());
        }
        return jdbc.query("""
                select r.id, r.action_code, r.entity_type, r.entity_id, r.summary, r.payload::text,
                       r.initiated_by, iu.email as initiator_email, r.initiated_at, r.status,
                       r.decided_by, du.email as decider_email, r.decided_at, r.decision_note
                from security.approval_request r
                join identity.app_user iu on iu.tenant_id = r.tenant_id and iu.id = r.initiated_by
                left join identity.app_user du on du.tenant_id = r.tenant_id and du.id = r.decided_by
                where r.tenant_id = ?""" + filter + """

                order by r.initiated_at desc
                """, (rs, i) -> map(rs), args.toArray());
    }

    @Transactional(readOnly = true)
    public ApprovalRequest find(UUID id) {
        List<ApprovalRequest> rows = jdbc.query("""
                select r.id, r.action_code, r.entity_type, r.entity_id, r.summary, r.payload::text,
                       r.initiated_by, iu.email as initiator_email, r.initiated_at, r.status,
                       r.decided_by, du.email as decider_email, r.decided_at, r.decision_note
                from security.approval_request r
                join identity.app_user iu on iu.tenant_id = r.tenant_id and iu.id = r.initiated_by
                left join identity.app_user du on du.tenant_id = r.tenant_id and du.id = r.decided_by
                where r.tenant_id = ? and r.id = ?
                """, (rs, i) -> map(rs), TenantContext.get().tenantId(), id);
        if (rows.isEmpty()) throw new NotFoundException("No approval request with that id");
        return rows.getFirst();
    }

    private ApprovalRequest map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApprovalRequest(rs.getObject("id", UUID.class), rs.getString("action_code"),
                rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                rs.getString("summary"), rs.getString("payload"),
                rs.getObject("initiated_by", UUID.class), rs.getString("initiator_email"),
                rs.getTimestamp("initiated_at").toInstant(), rs.getString("status"),
                rs.getObject("decided_by", UUID.class), rs.getString("decider_email"),
                rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
                rs.getString("decision_note"));
    }
}

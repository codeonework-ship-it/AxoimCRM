package com.axiom.automation;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.notifications.NotificationWriter;
import com.axiom.security.MakerCheckerService;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Multi-step approvals (FR-AUT-007), delegation (FR-AUT-008) and maker-checker
 * (FR-SEC-010) on every step.
 *
 * <h2>Serial and parallel from one column</h2>
 * A step carries a {@code parallel_group}. Groups run in ascending order; every
 * step inside a group is dispatched at once. Serial is then "one step per group"
 * and parallel is "several steps in one group" — one shape, so there is no
 * second code path that can disagree about which step is current.
 *
 * <h2>Maker-checker is checked at the decision, not at assignment</h2>
 * The dynamic approver rules can perfectly legitimately resolve to the submitter
 * (they own the record, they are top of the hierarchy). Refusing at assignment
 * would silently drop the step and let the approval complete with one fewer
 * check than the model requires. Refusing at the decision keeps the step
 * outstanding and forces a human to route it to someone else — which is what
 * separation of duties is for.
 *
 * <h2>Delegation is transitive, and both directions count</h2>
 * The chain is walked over an undirected view of the delegation graph, and it is
 * unioned with {@link MakerCheckerService#delegationChain(UUID)} so a delegation
 * recorded against the platform-wide control also blocks an approval here. A
 * bypass built by delegating outward is the same conflict of interest as one
 * built by delegating inward.
 */
@Service
public class ApprovalService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectMetadataService metadata;
    private final AuditService audit;
    private final NotificationWriter notifications;
    private final MakerCheckerService makerChecker;

    @Autowired
    public ApprovalService(JdbcTemplate jdbc, ObjectMapper json, ObjectMetadataService metadata,
                           AuditService audit, NotificationWriter notifications,
                           MakerCheckerService makerChecker) {
        this.jdbc = jdbc;
        this.json = json;
        this.metadata = metadata;
        this.audit = audit;
        this.notifications = notifications;
        this.makerChecker = makerChecker;
    }

    // ------------------------------------------------------------------ contracts

    public record SubmitRequest(@NotBlank String processCode, @NotBlank String objectType,
                                UUID recordId, String comment) {}

    public record DecisionRequest(String comment) {}

    public record StepDefinition(UUID id, int stepNo, String name, int parallelGroup,
                                 String decisionPolicy, String approverType,
                                 Map<String, Object> approverConfig) {}

    public record ProcessView(UUID id, String processCode, String name, String objectType,
                              String entryCondition, String amountField, String status,
                              List<StepDefinition> steps) {}

    public record TaskView(UUID id, UUID instanceId, int stepNo, String stepName, int parallelGroup,
                           String decisionPolicy, UUID approverId, String approverEmail,
                           UUID onBehalfOf, String onBehalfOfEmail, String assignedVia,
                           String status, String comment, Instant decidedAt) {}

    public record ApprovalInstanceView(UUID id, String processCode, String processName,
                                       String objectType, UUID recordId, String subject,
                                       BigDecimal amount, UUID submittedBy, String submittedByEmail,
                                       Instant submittedAt, String status, int currentGroup,
                                       Instant decidedAt, String rejectionReason,
                                       UUID resubmissionOf, int submissionNo, List<TaskView> tasks) {}

    /** What a dry run reports for a SUBMIT_FOR_APPROVAL action, without creating anything. */
    public record ApprovalPreview(String processCode, List<String> stepNames, List<String> approverEmails) {}

    public record DelegationView(UUID id, UUID delegatorId, String delegatorEmail,
                                 UUID delegateId, String delegateEmail, Instant startsAt,
                                 Instant endsAt, String reason, boolean active) {}

    public record DelegationRequest(UUID delegateId, Instant startsAt, Instant endsAt, String reason) {}

    // ------------------------------------------------------------------ process reads

    @Transactional(readOnly = true)
    public List<ProcessView> processes() {
        AutomationAccess.requireRead();
        return jdbc.query("""
                select id, process_code, name, object_type, entry_condition, amount_field, status
                from automation.approval_process where tenant_id = ? order by process_code
                """, (rs, i) -> new ProcessView(rs.getObject("id", UUID.class),
                        rs.getString("process_code"), rs.getString("name"), rs.getString("object_type"),
                        rs.getString("entry_condition"), rs.getString("amount_field"),
                        rs.getString("status"), steps(rs.getObject("id", UUID.class))),
                TenantContext.get().tenantId());
    }

    private List<StepDefinition> steps(UUID processId) {
        return jdbc.query("""
                select id, step_no, name, parallel_group, decision_policy, approver_type,
                       approver_config::text
                from automation.approval_step
                where tenant_id = ? and approval_process_id = ?
                order by parallel_group, step_no
                """, (rs, i) -> new StepDefinition(rs.getObject(1, UUID.class), rs.getInt(2),
                        rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6),
                        readMap(rs.getString(7))),
                TenantContext.get().tenantId(), processId);
    }

    private ProcessRow processByCode(String processCode) {
        List<ProcessRow> rows = jdbc.query("""
                select id, process_code, name, object_type, entry_condition, amount_field, status
                from automation.approval_process where tenant_id = ? and process_code = ?
                """, (rs, i) -> new ProcessRow(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7)),
                TenantContext.get().tenantId(), processCode);
        if (rows.isEmpty()) {
            throw new NotFoundException("No approval process with code " + processCode);
        }
        return rows.getFirst();
    }

    private record ProcessRow(UUID id, String processCode, String name, String objectType,
                              String entryCondition, String amountField, String status) {}

    // ------------------------------------------------------------------ submit

    @Transactional(readOnly = true)
    public ApprovalPreview preview(String processCode, String objectType, UUID recordId) {
        ProcessRow process = processByCode(processCode);
        List<StepDefinition> steps = steps(process.id());
        Map<String, Object> record = metadata.readRecord(objectType, recordId);
        int firstGroup = steps.stream().mapToInt(StepDefinition::parallelGroup).min().orElse(0);
        LinkedHashSet<String> approvers = new LinkedHashSet<>();
        for (StepDefinition step : steps) {
            if (step.parallelGroup() != firstGroup) continue;
            for (ResolvedApprover approver : resolveApprovers(step, process, record,
                    TenantContext.get().userId())) {
                approvers.add(emailOf(approver.approverId()));
            }
        }
        return new ApprovalPreview(processCode,
                steps.stream().map(s -> "g" + s.parallelGroup() + " " + s.name()).toList(),
                List.copyOf(approvers));
    }

    @Transactional
    public ApprovalInstanceView submit(SubmitRequest request) {
        AutomationAccess.requireParticipant("submit records for approval");
        ProcessRow process = processByCode(request.processCode());
        if (!"ACTIVE".equals(process.status())) {
            throw new ConflictException("Approval process " + process.processCode()
                    + " is " + process.status() + " and cannot accept submissions.");
        }
        Map<String, Object> record = metadata.readRecord(request.objectType(), request.recordId());
        if (record.isEmpty()) {
            throw new NotFoundException("No " + request.objectType() + " with id " + request.recordId());
        }
        if (process.entryCondition() != null && !process.entryCondition().isBlank()) {
            boolean eligible = ExpressionEvaluator.condition(process.entryCondition(),
                    ExpressionEvaluator.Context.of(record, Map.of()));
            if (!eligible) {
                throw new ConflictException("This record does not meet the entry condition of "
                        + process.processCode() + ": " + process.entryCondition());
            }
        }
        Boolean open = jdbc.queryForObject("""
                select exists (select 1 from automation.approval_instance
                  where tenant_id = ? and object_type = ? and record_id = ? and status = 'PENDING')
                """, Boolean.class, TenantContext.get().tenantId(),
                request.objectType().toUpperCase(Locale.ROOT), request.recordId());
        if (Boolean.TRUE.equals(open)) {
            throw new ConflictException("This record already has an approval in flight. "
                    + "Recall it before submitting again.");
        }
        return create(process, record, request.objectType(), request.recordId(), null, 1);
    }

    private ApprovalInstanceView create(ProcessRow process, Map<String, Object> record,
                                        String objectType, UUID recordId, UUID resubmissionOf,
                                        int submissionNo) {
        TenantContext.Principal p = TenantContext.get();
        BigDecimal amount = process.amountField() == null ? null
                : ExpressionEvaluator.number(record.get(process.amountField()), "amount");
        String subject = String.valueOf(record.getOrDefault("name",
                record.getOrDefault("subject", objectType + " " + recordId)));

        UUID instanceId = UUID.randomUUID();
        jdbc.update("""
                insert into automation.approval_instance
                  (id, tenant_id, approval_process_id, object_type, record_id, subject, amount,
                   submitted_by, status, current_group, resubmission_of, submission_no)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                """, instanceId, p.tenantId(), process.id(), objectType.toUpperCase(Locale.ROOT),
                recordId, subject, amount, p.userId(), firstGroup(process.id()), resubmissionOf,
                submissionNo);

        dispatchGroup(instanceId, process, record, firstGroup(process.id()), p.userId());
        audit.record("APPROVAL_SUBMITTED", objectType, recordId,
                "Submitted " + subject + " to approval process " + process.processCode(),
                Map.of("approvalInstanceId", instanceId.toString(),
                        "processCode", process.processCode(),
                        "submissionNo", submissionNo));
        return get(instanceId);
    }

    private int firstGroup(UUID processId) {
        Integer group = jdbc.queryForObject("""
                select coalesce(min(parallel_group), 0) from automation.approval_step
                where tenant_id = ? and approval_process_id = ?
                """, Integer.class, TenantContext.get().tenantId(), processId);
        return group == null ? 0 : group;
    }

    private Integer nextGroup(UUID processId, int afterGroup) {
        return jdbc.queryForObject("""
                select min(parallel_group) from automation.approval_step
                where tenant_id = ? and approval_process_id = ? and parallel_group > ?
                """, Integer.class, TenantContext.get().tenantId(), processId, afterGroup);
    }

    /** Creates the tasks for one parallel group, applying delegation as it goes. */
    private void dispatchGroup(UUID instanceId, ProcessRow process, Map<String, Object> record,
                               int group, UUID submitterId) {
        List<StepDefinition> steps = steps(process.id()).stream()
                .filter(s -> s.parallelGroup() == group).toList();
        if (steps.isEmpty()) return;
        for (StepDefinition step : steps) {
            List<ResolvedApprover> approvers = resolveApprovers(step, process, record, submitterId);
            if (approvers.isEmpty()) {
                throw new ConflictException("Approval step '" + step.name()
                        + "' resolved to nobody. Fix the approver rule before submitting.");
            }
            for (ResolvedApprover approver : approvers) {
                Delegation delegation = activeDelegationFor(approver.approverId());
                UUID assignee = delegation == null ? approver.approverId() : delegation.delegateId();
                UUID onBehalfOf = delegation == null ? null : approver.approverId();
                jdbc.update("""
                        insert into automation.approval_task
                          (id, tenant_id, instance_id, step_id, step_no, parallel_group, approver_id,
                           on_behalf_of, delegation_id, assigned_via, status)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                        """, UUID.randomUUID(), TenantContext.get().tenantId(), instanceId, step.id(),
                        step.stepNo(), group, assignee, onBehalfOf,
                        delegation == null ? null : delegation.id(),
                        delegation == null ? approver.via()
                                : approver.via() + "_DELEGATED_FROM_" + emailOf(approver.approverId()));
                notifications.notifyUser(TenantContext.get().tenantId(), assignee, "APPROVAL", "HIGH",
                        "Approval requested: " + step.name(),
                        "An approval is waiting for your decision"
                                + (onBehalfOf == null ? "." : " on behalf of " + emailOf(onBehalfOf) + "."),
                        "/automation/studio", "Approval step " + step.name(), true);
            }
        }
        jdbc.update("update automation.approval_instance set current_group = ? where tenant_id = ? and id = ?",
                group, TenantContext.get().tenantId(), instanceId);
    }

    // ------------------------------------------------------------------ approver resolution

    private record ResolvedApprover(UUID approverId, String via) {}

    /** The four dynamic determinations FR-AUT-007 names, plus an explicit user. */
    private List<ResolvedApprover> resolveApprovers(StepDefinition step, ProcessRow process,
                                                    Map<String, Object> record, UUID submitterId) {
        UUID tenantId = TenantContext.get().tenantId();
        Map<String, Object> config = step.approverConfig();
        return switch (step.approverType()) {
            case "USER" -> {
                UUID userId = uuid(config.get("userId"));
                if (userId == null && config.get("email") != null) {
                    userId = userIdByEmail(String.valueOf(config.get("email")));
                }
                yield userId == null ? List.of() : List.of(new ResolvedApprover(userId, "USER"));
            }
            case "FIELD" -> {
                String field = String.valueOf(config.getOrDefault("field", "owner_id"));
                UUID userId = uuid(record.get(field));
                yield userId == null ? List.of() : List.of(new ResolvedApprover(userId, "FIELD:" + field));
            }
            case "HIERARCHY" -> {
                // The submitter's manager: every user assigned to the parent of the
                // submitter's role node. Reading the role hierarchy, never writing it.
                List<UUID> managers = jdbc.query("""
                        select distinct ura.user_id
                        from security.user_role_assignment mine
                        join security.role_node child
                          on child.tenant_id = mine.tenant_id and child.id = mine.role_node_id
                        join security.role_node parent
                          on parent.tenant_id = child.tenant_id and parent.id = child.parent_id
                        join security.user_role_assignment ura
                          on ura.tenant_id = parent.tenant_id and ura.role_node_id = parent.id
                        where mine.tenant_id = ? and mine.user_id = ?
                        """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, submitterId);
                yield managers.stream().map(m -> new ResolvedApprover(m, "HIERARCHY")).toList();
            }
            case "AMOUNT_MATRIX" -> {
                BigDecimal amount = process.amountField() == null ? BigDecimal.ZERO
                        : ExpressionEvaluator.number(record.get(process.amountField()), "amount");
                List<UUID> approvers = jdbc.query("""
                        select approver_id from automation.approval_amount_band
                        where tenant_id = ? and step_id = ?
                          and min_amount <= ? and (max_amount is null or max_amount > ?)
                        order by min_amount
                        """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, step.id(), amount, amount);
                yield approvers.stream()
                        .map(a -> new ResolvedApprover(a, "AMOUNT_MATRIX:" + amount.toPlainString()))
                        .toList();
            }
            case "QUEUE" -> {
                String queueCode = String.valueOf(config.getOrDefault("queueCode", ""));
                List<UUID> members = jdbc.query("""
                        select m.user_id from automation.approval_queue_member m
                        join automation.approval_queue q on q.tenant_id = m.tenant_id and q.id = m.queue_id
                        where m.tenant_id = ? and q.queue_code = ?
                        order by m.user_id
                        """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, queueCode);
                yield members.stream().map(m -> new ResolvedApprover(m, "QUEUE:" + queueCode)).toList();
            }
            default -> throw new IllegalArgumentException("Unknown approver type " + step.approverType());
        };
    }

    // ------------------------------------------------------------------ decide

    @Transactional
    public ApprovalInstanceView approve(UUID taskId, String comment) {
        return decide(taskId, true, comment);
    }

    @Transactional
    public ApprovalInstanceView reject(UUID taskId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A rejection needs a reason. "
                    + "The submitter cannot act on a refusal that does not say why.");
        }
        return decide(taskId, false, reason);
    }

    private ApprovalInstanceView decide(UUID taskId, boolean approved, String comment) {
        AutomationAccess.requireParticipant("decide approvals");
        TenantContext.Principal p = TenantContext.get();
        TaskRow task = taskRow(taskId);
        InstanceRow instance = instanceRow(task.instanceId());

        if (!"PENDING".equals(task.status())) {
            throw new ConflictException("That approval step was already " + task.status().toLowerCase());
        }
        if (!"PENDING".equals(instance.status())) {
            throw new ConflictException("This approval is " + instance.status().toLowerCase()
                    + " and cannot be decided again.");
        }
        if (!task.approverId().equals(p.userId())) {
            throw new ForbiddenException("This approval step is assigned to "
                    + emailOf(task.approverId()) + ", not to you.");
        }

        assertMakerChecker(instance, task, p.userId());

        jdbc.update("""
                update automation.approval_task
                set status = ?, comment = ?, decided_at = now()
                where tenant_id = ? and id = ? and status = 'PENDING'
                """, approved ? "APPROVED" : "REJECTED", comment, p.tenantId(), taskId);

        if (!approved) {
            jdbc.update("""
                    update automation.approval_task set status = 'CANCELLED'
                    where tenant_id = ? and instance_id = ? and status = 'PENDING'
                    """, p.tenantId(), instance.id());
            jdbc.update("""
                    update automation.approval_instance
                    set status = 'REJECTED', rejection_reason = ?, decided_at = now()
                    where tenant_id = ? and id = ?
                    """, comment, p.tenantId(), instance.id());
            audit.record("APPROVAL_REJECTED", instance.objectType(), instance.recordId(),
                    "Rejected " + instance.subject(),
                    Map.of("approvalInstanceId", instance.id().toString(), "reason", comment));
            notifySubmitter(instance, "Approval rejected", "Your submission was rejected: " + comment);
            return get(instance.id());
        }

        audit.record("APPROVAL_STEP_APPROVED", instance.objectType(), instance.recordId(),
                "Approved step " + task.stepNo() + " of " + instance.subject(),
                Map.of("approvalInstanceId", instance.id().toString(), "taskId", taskId.toString(),
                        "onBehalfOf", task.onBehalfOf() == null ? "" : emailOf(task.onBehalfOf())));

        GroupDecision group = groupDecision(instance.id(), task.parallelGroup());
        if (group.satisfied()) {
            if (group.firstResponseWinner()) {
                skipRemainingFirstResponseTasks(instance.id(), task.parallelGroup());
            }
            Integer next = nextGroup(instance.approvalProcessId(), task.parallelGroup());
            if (next == null) {
                jdbc.update("""
                        update automation.approval_instance
                        set status = 'APPROVED', decided_at = now()
                        where tenant_id = ? and id = ?
                        """, p.tenantId(), instance.id());
                audit.record("APPROVAL_COMPLETED", instance.objectType(), instance.recordId(),
                        "Approved " + instance.subject(),
                        Map.of("approvalInstanceId", instance.id().toString()));
                notifySubmitter(instance, "Approval complete", "Your submission was approved.");
            } else {
                ProcessRow process = processById(instance.approvalProcessId());
                Map<String, Object> record = metadata.readRecord(instance.objectType(), instance.recordId());
                dispatchGroup(instance.id(), process, record, next, instance.submittedBy());
            }
        }
        return get(instance.id());
    }

    /**
     * FR-SEC-010 applied to every step, transitively through delegation.
     *
     * <p>Three separate cases, all of them the same conflict of interest:
     * the approver is the submitter; the approver is connected to the submitter by
     * a delegation; or the approver is exercising authority delegated <em>by</em>
     * the submitter, which is the case a naive {@code approver != submitter} check
     * lets straight through.
     */
    void assertMakerChecker(InstanceRow instance, TaskRow task, UUID approverId) {
        if (approverId.equals(instance.submittedBy())) {
            refuse(instance, approverId, "the user who submitted this record for approval cannot "
                    + "also approve it");
        }
        if (task.onBehalfOf() != null && task.onBehalfOf().equals(instance.submittedBy())) {
            refuse(instance, approverId, "you would be exercising approval authority delegated by "
                    + emailOf(instance.submittedBy()) + ", who submitted this record");
        }
        Set<UUID> chain = delegationChain(instance.submittedBy());
        if (chain.contains(approverId)) {
            refuse(instance, approverId, "approval authority is delegated between the submitter ("
                    + emailOf(instance.submittedBy()) + ") and you, so the maker-checker separation "
                    + "would be bypassed. FR-SEC-010 applies transitively through delegation");
        }
    }

    private void refuse(InstanceRow instance, UUID approverId, String because) {
        audit.recordWithReason("SEGREGATION_VIOLATION", instance.objectType(), instance.recordId(),
                "Approval refused: " + because, "MAKER_CHECKER",
                Map.of("approvalInstanceId", instance.id().toString(),
                        "submittedBy", emailOf(instance.submittedBy()),
                        "attemptedApproverId", approverId.toString(),
                        "control", "FR-SEC-010"));
        throw new ForbiddenException("Approval refused: " + because
                + ". Ask a colleague outside this delegation chain to approve.");
    }

    /**
     * Breadth-first over an undirected view of the delegation graph, unioned with
     * the platform-wide chain so both delegation registries bind here.
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
                    select delegate_id from automation.approval_delegation
                    where tenant_id = ? and delegator_id = ? and active
                      and starts_at <= now() and ends_at > now()
                    union
                    select delegator_id from automation.approval_delegation
                    where tenant_id = ? and delegate_id = ? and active
                      and starts_at <= now() and ends_at > now()
                    """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, current, tenantId, current);
            for (UUID next : neighbours) {
                if (visited.add(next)) {
                    seen.add(next);
                    queue.add(next);
                }
            }
        }
        seen.addAll(makerChecker.delegationChain(userId));
        seen.remove(userId);
        return seen;
    }

    private record GroupDecision(boolean satisfied, boolean firstResponseWinner) {}

    private GroupDecision groupDecision(UUID instanceId, int group) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.id as step_id, s.decision_policy,
                       count(*) filter (where t.status = 'APPROVED') as approved,
                       count(*) filter (where t.status = 'PENDING') as pending
                from automation.approval_task t
                join automation.approval_step s on s.tenant_id = t.tenant_id and s.id = t.step_id
                where t.tenant_id = ? and t.instance_id = ? and t.parallel_group = ?
                group by s.id, s.decision_policy
                """, TenantContext.get().tenantId(), instanceId, group);
        if (rows.isEmpty()) return new GroupDecision(false, false);
        boolean firstResponseWinner = false;
        for (Map<String, Object> row : rows) {
            long approved = ((Number) row.get("approved")).longValue();
            long pending = ((Number) row.get("pending")).longValue();
            if ("FIRST_RESPONSE".equals(row.get("decision_policy"))) {
                if (approved < 1) return new GroupDecision(false, false);
                firstResponseWinner = true;
            } else if (pending > 0) {
                return new GroupDecision(false, false);
            }
        }
        return new GroupDecision(true, firstResponseWinner);
    }

    /** Once a first-response step has its answer, the other approvers are off the hook. */
    private void skipRemainingFirstResponseTasks(UUID instanceId, int group) {
        jdbc.update("""
                update automation.approval_task t
                set status = 'SKIPPED'
                from automation.approval_step s
                where s.tenant_id = t.tenant_id and s.id = t.step_id
                  and t.tenant_id = ? and t.instance_id = ? and t.parallel_group = ?
                  and t.status = 'PENDING' and s.decision_policy = 'FIRST_RESPONSE'
                """, TenantContext.get().tenantId(), instanceId, group);
    }

    // ------------------------------------------------------------------ recall / resubmit

    @Transactional
    public ApprovalInstanceView recall(UUID instanceId, String reason) {
        AutomationAccess.requireParticipant("recall approvals");
        TenantContext.Principal p = TenantContext.get();
        InstanceRow instance = instanceRow(instanceId);
        if (!instance.submittedBy().equals(p.userId())) {
            throw new ForbiddenException("Only the submitter may recall an approval. "
                    + "This one was submitted by " + emailOf(instance.submittedBy()) + ".");
        }
        if (!"PENDING".equals(instance.status())) {
            throw new ConflictException("This approval is " + instance.status().toLowerCase()
                    + " and can no longer be recalled.");
        }
        jdbc.update("""
                update automation.approval_task set status = 'CANCELLED'
                where tenant_id = ? and instance_id = ? and status = 'PENDING'
                """, p.tenantId(), instanceId);
        jdbc.update("""
                update automation.approval_instance set status = 'RECALLED', decided_at = now()
                where tenant_id = ? and id = ?
                """, p.tenantId(), instanceId);
        audit.record("APPROVAL_RECALLED", instance.objectType(), instance.recordId(),
                "Recalled " + instance.subject(),
                Map.of("approvalInstanceId", instanceId.toString(),
                        "reason", reason == null ? "" : reason));
        return get(instanceId);
    }

    /** A resubmission is a new instance that remembers the one it replaces. */
    @Transactional
    public ApprovalInstanceView resubmit(UUID instanceId, String comment) {
        AutomationAccess.requireParticipant("resubmit approvals");
        InstanceRow previous = instanceRow(instanceId);
        if ("PENDING".equals(previous.status())) {
            throw new ConflictException("That approval is still in flight; recall it before resubmitting.");
        }
        if (!previous.submittedBy().equals(TenantContext.get().userId())) {
            throw new ForbiddenException("Only the original submitter may resubmit this record.");
        }
        ProcessRow process = processById(previous.approvalProcessId());
        Map<String, Object> record = metadata.readRecord(previous.objectType(), previous.recordId());
        if (record.isEmpty()) {
            throw new NotFoundException("The record this approval refers to no longer exists.");
        }
        return create(process, record, previous.objectType(), previous.recordId(), instanceId,
                previous.submissionNo() + 1);
    }

    // ------------------------------------------------------------------ delegation

    @Transactional(readOnly = true)
    public List<DelegationView> delegations() {
        AutomationAccess.requireRead();
        return jdbc.query("""
                select d.id, d.delegator_id, du.email, d.delegate_id, eu.email,
                       d.starts_at, d.ends_at, d.reason, d.active
                from automation.approval_delegation d
                join identity.app_user du on du.tenant_id = d.tenant_id and du.id = d.delegator_id
                join identity.app_user eu on eu.tenant_id = d.tenant_id and eu.id = d.delegate_id
                where d.tenant_id = ?
                order by d.created_at desc
                """, (rs, i) -> new DelegationView(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(),
                        rs.getString(8), rs.getBoolean(9)),
                TenantContext.get().tenantId());
    }

    /**
     * A user delegates their own authority for a bounded period (FR-AUT-008).
     * Nobody delegates on another's behalf, and the end is mandatory — an
     * open-ended delegation is a permanent transfer wearing a delegation's name.
     */
    @Transactional
    public DelegationView delegate(DelegationRequest request) {
        AutomationAccess.requireParticipant("delegate approval authority");
        TenantContext.Principal p = TenantContext.get();
        if (request.delegateId() == null) {
            throw new IllegalArgumentException("A delegation needs a delegate.");
        }
        if (p.userId().equals(request.delegateId())) {
            throw new ConflictException("You cannot delegate approval authority to yourself.");
        }
        if (request.endsAt() == null) {
            throw new IllegalArgumentException("A delegation must have an end date; "
                    + "FR-AUT-008 requires it to be for a bounded period.");
        }
        Instant startsAt = request.startsAt() == null ? Instant.now() : request.startsAt();
        if (!request.endsAt().isAfter(startsAt)) {
            throw new IllegalArgumentException("The delegation must end after it starts.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into automation.approval_delegation
                  (id, tenant_id, delegator_id, delegate_id, starts_at, ends_at, reason, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, p.tenantId(), p.userId(), request.delegateId(),
                java.sql.Timestamp.from(startsAt), java.sql.Timestamp.from(request.endsAt()),
                request.reason(), p.userId());
        audit.record("APPROVAL_AUTHORITY_DELEGATED", "APPROVAL_DELEGATION", id,
                emailOf(p.userId()) + " delegated approval authority to "
                        + emailOf(request.delegateId()),
                Map.of("delegatorId", p.userId().toString(),
                        "delegateId", request.delegateId().toString(),
                        "startsAt", startsAt.toString(), "endsAt", request.endsAt().toString()));
        return delegations().stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Delegation was not created"));
    }

    @Transactional
    public void revokeDelegation(UUID delegationId) {
        AutomationAccess.requireParticipant("revoke approval delegations");
        int updated = jdbc.update("""
                update automation.approval_delegation set active = false
                where tenant_id = ? and id = ? and delegator_id = ?
                """, TenantContext.get().tenantId(), delegationId, TenantContext.get().userId());
        if (updated == 0) {
            throw new NotFoundException("No open delegation of yours with that id");
        }
        audit.record("APPROVAL_DELEGATION_REVOKED", "APPROVAL_DELEGATION", delegationId,
                "Revoked a delegation of approval authority", Map.of());
    }

    private record Delegation(UUID id, UUID delegatorId, UUID delegateId) {}

    private Delegation activeDelegationFor(UUID approverId) {
        List<Delegation> rows = jdbc.query("""
                select id, delegator_id, delegate_id from automation.approval_delegation
                where tenant_id = ? and delegator_id = ? and active
                  and starts_at <= now() and ends_at > now()
                order by created_at desc limit 1
                """, (rs, i) -> new Delegation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class)),
                TenantContext.get().tenantId(), approverId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<TaskView> inbox() {
        AutomationAccess.requireRead();
        return jdbc.query("""
                select t.id, t.instance_id, t.step_no, s.name, t.parallel_group, s.decision_policy,
                       t.approver_id, au.email, t.on_behalf_of, bu.email, t.assigned_via, t.status,
                       t.comment, t.decided_at
                from automation.approval_task t
                join automation.approval_step s on s.tenant_id = t.tenant_id and s.id = t.step_id
                join identity.app_user au on au.tenant_id = t.tenant_id and au.id = t.approver_id
                left join identity.app_user bu on bu.tenant_id = t.tenant_id and bu.id = t.on_behalf_of
                where t.tenant_id = ? and t.approver_id = ? and t.status = 'PENDING'
                order by t.created_at
                """, (rs, i) -> mapTask(rs), TenantContext.get().tenantId(),
                TenantContext.get().userId());
    }

    @Transactional(readOnly = true)
    public List<ApprovalInstanceView> instances(String status, UUID recordId) {
        AutomationAccess.requireRead();
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        StringBuilder filter = new StringBuilder();
        if (status != null && !status.isBlank()) {
            filter.append(" and i.status = ?");
            args.add(status.toUpperCase(Locale.ROOT));
        }
        if (recordId != null) { filter.append(" and i.record_id = ?"); args.add(recordId); }
        List<UUID> ids = jdbc.query("""
                select i.id from automation.approval_instance i
                where i.tenant_id = ?""" + filter + """

                order by i.submitted_at desc limit 100
                """, (rs, x) -> rs.getObject(1, UUID.class), args.toArray());
        return ids.stream().map(this::get).toList();
    }

    @Transactional(readOnly = true)
    public ApprovalInstanceView get(UUID instanceId) {
        List<ApprovalInstanceView> rows = jdbc.query("""
                select i.id, p.process_code, p.name as process_name, i.object_type, i.record_id,
                       i.subject, i.amount, i.submitted_by, u.email as submitter_email, i.submitted_at,
                       i.status, i.current_group, i.decided_at, i.rejection_reason, i.resubmission_of,
                       i.submission_no
                from automation.approval_instance i
                join automation.approval_process p on p.tenant_id = i.tenant_id and p.id = i.approval_process_id
                join identity.app_user u on u.tenant_id = i.tenant_id and u.id = i.submitted_by
                where i.tenant_id = ? and i.id = ?
                """, (rs, i) -> new ApprovalInstanceView(rs.getObject("id", UUID.class),
                        rs.getString("process_code"), rs.getString("process_name"),
                        rs.getString("object_type"), rs.getObject("record_id", UUID.class),
                        rs.getString("subject"), rs.getBigDecimal("amount"),
                        rs.getObject("submitted_by", UUID.class), rs.getString("submitter_email"),
                        rs.getTimestamp("submitted_at").toInstant(), rs.getString("status"),
                        rs.getInt("current_group"),
                        rs.getTimestamp("decided_at") == null ? null
                                : rs.getTimestamp("decided_at").toInstant(),
                        rs.getString("rejection_reason"),
                        rs.getObject("resubmission_of", UUID.class), rs.getInt("submission_no"),
                        tasks(instanceId)),
                TenantContext.get().tenantId(), instanceId);
        if (rows.isEmpty()) throw new NotFoundException("No approval with that id");
        return rows.getFirst();
    }

    private List<TaskView> tasks(UUID instanceId) {
        return jdbc.query("""
                select t.id, t.instance_id, t.step_no, s.name, t.parallel_group, s.decision_policy,
                       t.approver_id, au.email, t.on_behalf_of, bu.email, t.assigned_via, t.status,
                       t.comment, t.decided_at
                from automation.approval_task t
                join automation.approval_step s on s.tenant_id = t.tenant_id and s.id = t.step_id
                join identity.app_user au on au.tenant_id = t.tenant_id and au.id = t.approver_id
                left join identity.app_user bu on bu.tenant_id = t.tenant_id and bu.id = t.on_behalf_of
                where t.tenant_id = ? and t.instance_id = ?
                order by t.parallel_group, t.step_no, t.created_at
                """, (rs, i) -> mapTask(rs), TenantContext.get().tenantId(), instanceId);
    }

    private TaskView mapTask(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TaskView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                rs.getString(4), rs.getInt(5), rs.getString(6), rs.getObject(7, UUID.class),
                rs.getString(8), rs.getObject(9, UUID.class), rs.getString(10), rs.getString(11),
                rs.getString(12), rs.getString(13),
                rs.getTimestamp(14) == null ? null : rs.getTimestamp(14).toInstant());
    }

    // ------------------------------------------------------------------ plumbing

    record TaskRow(UUID id, UUID instanceId, UUID stepId, int stepNo, int parallelGroup,
                   UUID approverId, UUID onBehalfOf, String status) {}

    record InstanceRow(UUID id, UUID approvalProcessId, String objectType, UUID recordId,
                       String subject, UUID submittedBy, String status, int submissionNo) {}

    private TaskRow taskRow(UUID taskId) {
        List<TaskRow> rows = jdbc.query("""
                select id, instance_id, step_id, step_no, parallel_group, approver_id, on_behalf_of, status
                from automation.approval_task where tenant_id = ? and id = ?
                """, (rs, i) -> new TaskRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getInt(4), rs.getInt(5),
                        rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getString(8)),
                TenantContext.get().tenantId(), taskId);
        if (rows.isEmpty()) throw new NotFoundException("No approval step with that id");
        return rows.getFirst();
    }

    InstanceRow instanceRow(UUID instanceId) {
        List<InstanceRow> rows = jdbc.query("""
                select id, approval_process_id, object_type, record_id, subject, submitted_by,
                       status, submission_no
                from automation.approval_instance where tenant_id = ? and id = ?
                """, (rs, i) -> new InstanceRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getObject(6, UUID.class), rs.getString(7), rs.getInt(8)),
                TenantContext.get().tenantId(), instanceId);
        if (rows.isEmpty()) throw new NotFoundException("No approval with that id");
        return rows.getFirst();
    }

    private ProcessRow processById(UUID id) {
        List<ProcessRow> rows = jdbc.query("""
                select id, process_code, name, object_type, entry_condition, amount_field, status
                from automation.approval_process where tenant_id = ? and id = ?
                """, (rs, i) -> new ProcessRow(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7)), TenantContext.get().tenantId(), id);
        if (rows.isEmpty()) throw new NotFoundException("No approval process with that id");
        return rows.getFirst();
    }

    private void notifySubmitter(InstanceRow instance, String title, String body) {
        notifications.notifyUser(TenantContext.get().tenantId(), instance.submittedBy(),
                "APPROVAL", "NORMAL", title, body, null, "Approval " + instance.id(), false);
    }

    private String emailOf(UUID userId) {
        if (userId == null) return "(nobody)";
        List<String> rows = jdbc.query(
                "select email from identity.app_user where tenant_id = ? and id = ?",
                (rs, i) -> rs.getString(1), TenantContext.get().tenantId(), userId);
        return rows.isEmpty() ? userId.toString() : rows.getFirst();
    }

    private UUID userIdByEmail(String email) {
        List<UUID> rows = jdbc.query(
                "select id from identity.app_user where tenant_id = ? and email = ?",
                (rs, i) -> rs.getObject(1, UUID.class), TenantContext.get().tenantId(), email);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static UUID uuid(Object value) {
        if (value == null) return null;
        if (value instanceof UUID u) return u;
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try {
            return json.readValue(value, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return new LinkedHashMap<>();
        }
    }
}

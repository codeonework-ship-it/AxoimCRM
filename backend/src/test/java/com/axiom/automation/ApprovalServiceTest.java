package com.axiom.automation;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.notifications.NotificationWriter;
import com.axiom.security.MakerCheckerService;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-AUT-007 approvals and FR-AUT-008 delegation, with FR-SEC-010 maker-checker
 * on every step.
 */
class ApprovalServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SUBMITTER = UUID.randomUUID();
    private static final UUID OTHER_APPROVER = UUID.randomUUID();
    private static final UUID DELEGATE_OF_SUBMITTER = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID STEP = UUID.randomUUID();
    private static final UUID PROCESS = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private ObjectMetadataService metadata;
    private AuditService audit;
    private NotificationWriter notifications;
    private MakerCheckerService makerChecker;
    private ApprovalService service;

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        metadata = mock(ObjectMetadataService.class);
        audit = mock(AuditService.class);
        notifications = mock(NotificationWriter.class);
        makerChecker = mock(MakerCheckerService.class);
        when(makerChecker.delegationChain(any())).thenReturn(Set.of());
        service = new ApprovalService(jdbc, new ObjectMapper(), metadata, audit, notifications,
                makerChecker);
        signInAs(SUBMITTER);
        stub("select email from identity.app_user", List.of("someone@meridianfab.com"));
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    private static void signInAs(UUID userId) {
        TenantContext.clear();
        TenantContext.set(new TenantContext.Principal(TENANT, userId, "TENANT_ADMIN",
                "User", "user@meridianfab.com"));
    }

    @SuppressWarnings("unchecked")
    private void stub(String fragment, List<?> result) {
        doReturn(result).when(jdbc).query(contains(fragment), any(RowMapper.class),
                any(Object[].class));
    }

    private ApprovalService.TaskRow task(UUID approverId, UUID onBehalfOf) {
        return new ApprovalService.TaskRow(TASK, INSTANCE, STEP, 1, 1, approverId, onBehalfOf, "PENDING");
    }

    private ApprovalService.InstanceRow instance() {
        return new ApprovalService.InstanceRow(INSTANCE, PROCESS, "OPPORTUNITY", RECORD,
                "Northbrook Health Systems", SUBMITTER, "PENDING", 1);
    }

    private void stubDecisionPath(UUID approverId, UUID onBehalfOf) {
        stub("from automation.approval_task where tenant_id = ? and id = ?",
                List.of(task(approverId, onBehalfOf)));
        stub("from automation.approval_instance where tenant_id = ? and id = ?", List.of(instance()));
        stub("select delegate_id from automation.approval_delegation", List.of());
        stub("select min(parallel_group)", List.of());
        stub("from automation.approval_instance i", List.of(view("PENDING")));
        stub("from automation.approval_task t", List.of());
    }

    private static ApprovalService.ApprovalInstanceView view(String status) {
        return new ApprovalService.ApprovalInstanceView(INSTANCE, "APR-OPP-DISCOUNT", "Large deal",
                "OPPORTUNITY", RECORD, "Northbrook", new BigDecimal("540000"), SUBMITTER,
                "raj@meridianfab.com", Instant.now(), status, 1, null, null, null, 1, List.of());
    }

    // ------------------------------------------------------------------ maker-checker

    @Test void theSubmitterCannotApproveTheirOwnSubmission() {
        signInAs(SUBMITTER);
        stubDecisionPath(SUBMITTER, null);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> service.approve(TASK, "looks fine to me"));
        assertTrue(ex.getMessage().contains("cannot"), ex.getMessage());
        assertTrue(ex.getMessage().contains("submitted"), ex.getMessage());
        verify(audit).recordWithReason(eq("SEGREGATION_VIOLATION"), anyString(), any(), anyString(),
                eq("MAKER_CHECKER"), any());
        verify(jdbc, never()).update(contains("update automation.approval_task"), any(Object[].class));
    }

    @Test void theSubmittersDelegateCannotApproveEither() {
        signInAs(DELEGATE_OF_SUBMITTER);
        stubDecisionPath(DELEGATE_OF_SUBMITTER, null);
        // The submitter has delegated to this user; the chain is walked transitively.
        stub("select delegate_id from automation.approval_delegation",
                List.of(DELEGATE_OF_SUBMITTER));

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> service.approve(TASK, "on their behalf"));
        assertTrue(ex.getMessage().contains("delegated"), ex.getMessage());
        assertTrue(ex.getMessage().contains("FR-SEC-010"), ex.getMessage());
    }

    @Test void aDelegationRecordedInThePlatformControlAlsoBlocksTheApproval() {
        signInAs(DELEGATE_OF_SUBMITTER);
        stubDecisionPath(DELEGATE_OF_SUBMITTER, null);
        when(makerChecker.delegationChain(SUBMITTER)).thenReturn(Set.of(DELEGATE_OF_SUBMITTER));

        assertThrows(ForbiddenException.class, () -> service.approve(TASK, "x"));
    }

    @Test void exercisingAuthorityDelegatedBySubmitterIsAlsoRefused() {
        signInAs(OTHER_APPROVER);
        // The task is held by another user, but on behalf of the submitter.
        stubDecisionPath(OTHER_APPROVER, SUBMITTER);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> service.approve(TASK, "x"));
        assertTrue(ex.getMessage().contains("delegated by"), ex.getMessage());
    }

    @Test void anIndependentApproverMayApprove() {
        signInAs(OTHER_APPROVER);
        stubDecisionPath(OTHER_APPROVER, null);
        stubGroup("UNANIMOUS", 1, 0);
        stub("from automation.approval_instance i", List.of(view("APPROVED")));

        ApprovalService.ApprovalInstanceView result = service.approve(TASK, "approved");
        assertEquals("APPROVED", result.status());
        verify(jdbc).update(contains("update automation.approval_task"), any(Object[].class));
        verify(jdbc).update(contains("set status = 'APPROVED'"), any(Object[].class));
    }

    @Test void aTaskAssignedToSomeoneElseCannotBeDecided() {
        signInAs(OTHER_APPROVER);
        stubDecisionPath(UUID.randomUUID(), null);
        ForbiddenException ex = assertThrows(ForbiddenException.class, () -> service.approve(TASK, "x"));
        assertTrue(ex.getMessage().contains("assigned to"), ex.getMessage());
    }

    // ------------------------------------------------------------------ serial / parallel

    private void stubGroup(String policy, int approved, int pending) {
        doReturn(List.of(Map.of("step_id", STEP, "decision_policy", policy,
                "approved", (long) approved, "pending", (long) pending)))
                .when(jdbc).queryForList(contains("count(*) filter"), any(Object[].class));
    }

    @Test void aUnanimousGroupWaitsForEveryOutstandingApprover() {
        signInAs(OTHER_APPROVER);
        stubDecisionPath(OTHER_APPROVER, null);
        stubGroup("UNANIMOUS", 1, 1);

        service.approve(TASK, "mine is in");
        // Still pending: the instance was not completed and no next group was dispatched.
        verify(jdbc, never()).update(contains("set status = 'APPROVED'"), any(Object[].class));
    }

    @Test void aFirstResponseGroupCompletesOnTheFirstApprovalAndSkipsTheRest() {
        signInAs(OTHER_APPROVER);
        stubDecisionPath(OTHER_APPROVER, null);
        stubGroup("FIRST_RESPONSE", 1, 2);
        stub("from automation.approval_instance i", List.of(view("APPROVED")));

        service.approve(TASK, "first in wins");
        verify(jdbc).update(contains("set status = 'SKIPPED'"), any(Object[].class));
        verify(jdbc).update(contains("set status = 'APPROVED'"), any(Object[].class));
    }

    @Test void aSecondGroupIsDispatchedRatherThanCompletingTheApproval() {
        signInAs(OTHER_APPROVER);
        stubDecisionPath(OTHER_APPROVER, null);
        stubGroup("UNANIMOUS", 1, 0);
        doReturn(2).when(jdbc).queryForObject(contains("select min(parallel_group)"), eq(Integer.class),
                any(Object[].class));
        stub("from automation.approval_process where tenant_id = ? and id = ?",
                List.of(processRow()));
        stub("from automation.approval_step", List.of(stepDefinition("QUEUE", "UNANIMOUS", 2)));
        stub("from automation.approval_queue_member m", List.of(OTHER_APPROVER));
        stub("from automation.approval_delegation\n", List.of());
        when(metadata.readRecord(eq("OPPORTUNITY"), any())).thenReturn(Map.of("amount",
                new BigDecimal("540000")));

        service.approve(TASK, "step one done");
        verify(jdbc, never()).update(contains("set status = 'APPROVED'"), any(Object[].class));
        verify(jdbc).update(contains("insert into automation.approval_task"), any(Object[].class));
    }

    private static ApprovalService.StepDefinition stepDefinition(String approverType, String policy,
                                                                 int group) {
        Map<String, Object> config = "USER".equals(approverType)
                ? Map.of("email", "finance@meridianfab.com")
                : Map.of("queueCode", "QUE-FINANCE");
        return new ApprovalService.StepDefinition(STEP, group, "Finance sign-off", group, policy,
                approverType, config);
    }

    private Object processRow() {
        // ProcessRow is a private record; the row mapper is stubbed away so the
        // service only needs an object of the right runtime type back. Reflection
        // keeps the production type private rather than widening it for a test.
        try {
            Class<?> type = Class.forName("com.axiom.automation.ApprovalService$ProcessRow");
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return constructor.newInstance(PROCESS, "APR-OPP-DISCOUNT", "Large deal", "OPPORTUNITY",
                    null, "amount", "ACTIVE");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------------ rejection

    @Test void aRejectionWithoutAReasonIsRefused() {
        signInAs(OTHER_APPROVER);
        assertThrows(IllegalArgumentException.class, () -> service.reject(TASK, "  "));
    }

    @Test void aRejectionWithAReasonCancelsTheOutstandingSteps() {
        signInAs(OTHER_APPROVER);
        stubDecisionPath(OTHER_APPROVER, null);
        stub("from automation.approval_instance i", List.of(view("REJECTED")));

        ApprovalService.ApprovalInstanceView result = service.reject(TASK, "Margin is below floor");
        assertEquals("REJECTED", result.status());
        verify(jdbc).update(contains("set status = 'CANCELLED'"), any(Object[].class));
        verify(jdbc).update(contains("set status = 'REJECTED'"), any(Object[].class));
        verify(audit).record(eq("APPROVAL_REJECTED"), anyString(), any(), anyString(), any());
    }

    // ------------------------------------------------------------------ recall and resubmit

    @Test void onlyTheSubmitterMayRecall() {
        signInAs(OTHER_APPROVER);
        stub("from automation.approval_instance where tenant_id = ? and id = ?", List.of(instance()));
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> service.recall(INSTANCE, "changed my mind"));
        assertTrue(ex.getMessage().contains("Only the submitter"), ex.getMessage());
    }

    @Test void theSubmitterCanRecallAndThenResubmit() {
        signInAs(SUBMITTER);
        stub("from automation.approval_instance where tenant_id = ? and id = ?", List.of(instance()));
        stub("from automation.approval_instance i", List.of(view("RECALLED")));

        assertEquals("RECALLED", service.recall(INSTANCE, "fixing the amount").status());
        verify(jdbc).update(contains("set status = 'CANCELLED'"), any(Object[].class));
        verify(jdbc).update(contains("set status = 'RECALLED'"), any(Object[].class));

        // Now resubmit: a NEW instance that remembers the one it replaces.
        stub("from automation.approval_instance where tenant_id = ? and id = ?",
                List.of(new ApprovalService.InstanceRow(INSTANCE, PROCESS, "OPPORTUNITY", RECORD,
                        "Northbrook", SUBMITTER, "RECALLED", 1)));
        stub("from automation.approval_process where tenant_id = ? and id = ?", List.of(processRow()));
        stub("from automation.approval_step", List.of(stepDefinition("USER", "UNANIMOUS", 1)));
        stub("from automation.approval_delegation\n", List.of());
        when(metadata.readRecord(eq("OPPORTUNITY"), any()))
                .thenReturn(Map.of("amount", new BigDecimal("540000"), "name", "Northbrook"));
        doReturn(1).when(jdbc).queryForObject(contains("min(parallel_group)"), eq(Integer.class),
                any(Object[].class));
        stub("select id from identity.app_user where tenant_id = ? and email = ?",
                List.of(OTHER_APPROVER));

        service.resubmit(INSTANCE, "amount corrected");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(contains("insert into automation.approval_instance"),
                args.capture());
        Object[] captured = args.getValue();
        assertEquals(INSTANCE, captured[captured.length - 2], "resubmission_of points at the original");
        assertEquals(2, captured[captured.length - 1], "submission_no increments");
    }

    @Test void resubmittingWhileStillInFlightIsRefused() {
        signInAs(SUBMITTER);
        stub("from automation.approval_instance where tenant_id = ? and id = ?", List.of(instance()));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.resubmit(INSTANCE, "again"));
        assertTrue(ex.getMessage().contains("recall it"), ex.getMessage());
    }

    // ------------------------------------------------------------------ delegation (FR-AUT-008)

    @Test void delegationMustBeBoundedAndRecordsBothIdentities() {
        signInAs(SUBMITTER);
        assertThrows(IllegalArgumentException.class, () -> service.delegate(
                new ApprovalService.DelegationRequest(OTHER_APPROVER, null, null, "holiday")));
        assertThrows(ConflictException.class, () -> service.delegate(
                new ApprovalService.DelegationRequest(SUBMITTER, null,
                        Instant.now().plus(3, ChronoUnit.DAYS), "myself")));

        Instant ends = Instant.now().plus(7, ChronoUnit.DAYS);
        stub("from automation.approval_delegation d", List.of(new ApprovalService.DelegationView(
                UUID.randomUUID(), SUBMITTER, "raj@x", OTHER_APPROVER, "priya@x",
                Instant.now(), ends, "holiday", true)));

        assertThrows(com.axiom.common.NotFoundException.class, () -> service.delegate(
                new ApprovalService.DelegationRequest(OTHER_APPROVER, null, ends, "holiday")));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into automation.approval_delegation"), args.capture());
        Object[] captured = args.getValue();
        assertEquals(SUBMITTER, captured[2], "the delegating authority is recorded");
        assertEquals(OTHER_APPROVER, captured[3], "the delegate is recorded");
        verify(audit).record(eq("APPROVAL_AUTHORITY_DELEGATED"), anyString(), any(), anyString(), any());
    }

    @Test void aTaskCreatedWhileADelegationIsOpenNamesBothPeople() {
        signInAs(SUBMITTER);
        stub("from automation.approval_process where tenant_id = ? and process_code = ?",
                List.of(processRow()));
        stub("from automation.approval_step", List.of(stepDefinition("USER", "UNANIMOUS", 1)));
        stub("select id, delegator_id, delegate_id from automation.approval_delegation",
                List.of(delegationRow()));
        stub("select id from identity.app_user where tenant_id = ? and email = ?",
                List.of(OTHER_APPROVER));
        stub("from automation.approval_instance i", List.of(view("PENDING")));
        stub("from automation.approval_task t", List.of());
        when(metadata.readRecord(eq("OPPORTUNITY"), any()))
                .thenReturn(Map.of("amount", new BigDecimal("540000"), "name", "Northbrook"));
        doReturn(false).when(jdbc).queryForObject(contains("exists"), eq(Boolean.class),
                any(Object[].class));
        doReturn(1).when(jdbc).queryForObject(contains("min(parallel_group)"), eq(Integer.class),
                any(Object[].class));

        // approver_config carries an explicit user; that user has delegated to another.
        service.submit(new ApprovalService.SubmitRequest("APR-OPP-DISCOUNT", "OPPORTUNITY",
                RECORD, null));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into automation.approval_task"), args.capture());
        Object[] captured = args.getValue();
        assertEquals(DELEGATE_OF_SUBMITTER, captured[6], "the task goes to the delegate");
        assertEquals(OTHER_APPROVER, captured[7], "on_behalf_of records the delegating authority");
        assertTrue(String.valueOf(captured[9]).contains("DELEGATED_FROM"));
    }

    private Object delegationRow() {
        try {
            Class<?> type = Class.forName("com.axiom.automation.ApprovalService$Delegation");
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return constructor.newInstance(UUID.randomUUID(), OTHER_APPROVER, DELEGATE_OF_SUBMITTER);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------------ dynamic approvers

    @Test void theAmountMatrixPicksTheApproverForTheRecordsAmount() {
        signInAs(SUBMITTER);
        stub("from automation.approval_process where tenant_id = ? and process_code = ?",
                List.of(processRow()));
        stub("from automation.approval_step", List.of(
                new ApprovalService.StepDefinition(STEP, 1, "Sales sign-off", 1, "FIRST_RESPONSE",
                        "AMOUNT_MATRIX", Map.of())));
        stub("from automation.approval_amount_band", List.of(OTHER_APPROVER));
        when(metadata.readRecord(eq("OPPORTUNITY"), any()))
                .thenReturn(Map.of("amount", new BigDecimal("540000")));

        ApprovalService.ApprovalPreview preview =
                service.preview("APR-OPP-DISCOUNT", "OPPORTUNITY", RECORD);

        assertEquals(1, preview.approverEmails().size());
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(1)).query(contains("from automation.approval_amount_band"),
                any(RowMapper.class), args.capture());
        assertEquals(new BigDecimal("540000"), args.getValue()[2],
                "the band is selected by the record's amount, not a constant");
    }
}

package com.axiom.automation;

import com.axiom.notifications.NotificationWriter;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The engine: entry conditions over old and new values, every action verb, the
 * loop and branch node types, and — the load-bearing one — that a dry run writes
 * absolutely nothing (FR-AUT-010).
 */
class RuleEngineTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID OPPORTUNITY_ID = UUID.fromString("66666666-6666-6666-6666-666666666608");
    private static final UUID OWNER_ID = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private ObjectMetadataService metadata;
    private NotificationWriter notifications;
    private OutboxWriter outbox;
    private ApprovalService approvals;
    private RecordChangeDispatcher dispatcher;
    private ActionExecutor actions;
    private RuleEngine engine;

    private static final ObjectMetadataService.ObjectDescriptor OPPORTUNITY =
            new ObjectMetadataService.ObjectDescriptor(UUID.randomUUID(), "OPPORTUNITY", "Opportunity",
                    "sales", "opportunity", "id", "owner_id", null,
                    List.of("id", "tenant_id", "created_at", "version"), "ACCOUNT", "account_id",
                    Map.ofEntries(Map.entry("id", "uuid"), Map.entry("tenant_id", "uuid"),
                            Map.entry("name", "text"), Map.entry("amount", "numeric"),
                            Map.entry("close_date", "date"), Map.entry("forecast_category", "text"),
                            Map.entry("next_step", "text"), Map.entry("owner_id", "uuid"),
                            Map.entry("account_id", "uuid"),
                            Map.entry("updated_at", "timestamp with time zone")));

    private static final ObjectMetadataService.ObjectDescriptor ACTIVITY =
            new ObjectMetadataService.ObjectDescriptor(UUID.randomUUID(), "ACTIVITY", "Activity",
                    "engagement", "activity", "id", "owner_id", "deleted_at",
                    List.of("id", "tenant_id", "created_at"), null, null,
                    Map.ofEntries(Map.entry("id", "uuid"), Map.entry("tenant_id", "uuid"),
                            Map.entry("activity_type", "text"), Map.entry("subject", "text"),
                            Map.entry("status", "text"), Map.entry("priority", "text"),
                            Map.entry("related_entity_type", "text"), Map.entry("related_entity_id", "uuid"),
                            Map.entry("owner_id", "uuid"), Map.entry("created_by", "uuid"),
                            Map.entry("source", "text"),
                            Map.entry("due_at", "timestamp with time zone")));

    private static final ObjectMetadataService.ObjectDescriptor CONTACT =
            new ObjectMetadataService.ObjectDescriptor(UUID.randomUUID(), "CONTACT", "Contact",
                    "crm", "contact", "id", null, "deleted_at",
                    List.of("id", "tenant_id"), "ACCOUNT", "account_id",
                    Map.of("id", "uuid", "tenant_id", "uuid", "first_name", "text",
                            "last_name", "text", "email", "text", "account_id", "uuid"));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        metadata = mock(ObjectMetadataService.class);
        notifications = mock(NotificationWriter.class);
        outbox = mock(OutboxWriter.class);
        approvals = mock(ApprovalService.class);
        dispatcher = mock(RecordChangeDispatcher.class);

        ObjectProvider<RecordChangeDispatcher> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(dispatcher);
        when(dispatcher.dispatch(anyString(), any(), anyString(), any(), any(), anyInt()))
                .thenReturn(new RecordChangeDispatcher.DispatchResult("OPPORTUNITY", OPPORTUNITY_ID,
                        "UPDATE", 0, false, null, List.of()));

        when(metadata.describe("OPPORTUNITY")).thenReturn(OPPORTUNITY);
        when(metadata.describe("ACTIVITY")).thenReturn(ACTIVITY);
        when(metadata.describe("CONTACT")).thenReturn(CONTACT);
        when(metadata.readRecord(any(ObjectMetadataService.ObjectDescriptor.class), any(UUID.class)))
                .thenReturn(record());
        when(metadata.requireWritableColumn(any(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(metadata.requireColumn(any(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        actions = new ActionExecutor(jdbc, metadata, notifications, outbox, approvals, provider);
        engine = new RuleEngine(actions, metadata);

        TenantContext.set(new TenantContext.Principal(TENANT, USER, "TENANT_ADMIN", "Admin",
                "admin@example.com"));
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
        RecursionGuard.clear();
    }

    private static Map<String, Object> record() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", OPPORTUNITY_ID);
        record.put("tenant_id", TENANT);
        record.put("name", "Northbrook Health Systems");
        record.put("amount", new BigDecimal("540000"));
        record.put("close_date", java.sql.Date.valueOf("2026-07-30"));
        record.put("forecast_category", "COMMIT");
        record.put("next_step", "");
        record.put("owner_id", OWNER_ID);
        record.put("account_id", UUID.randomUUID());
        return record;
    }

    private RunContext context(RuleModel.Mode mode, Map<String, Object> oldValues) {
        return new RunContext(UUID.randomUUID(), "AUT-TEST", "Test rule", 1, OPPORTUNITY,
                OPPORTUNITY_ID, record(), oldValues, "RECORD_CHANGE", "UPDATE", 0, mode);
    }

    /** Every one of the eight verbs, in one definition. */
    private static RuleModel.Definition everyAction() {
        return new RuleModel.Definition(
                new RuleModel.TriggerSpec("RECORD_CHANGE", List.of("UPDATE"), null),
                "ISCHANGED(amount) AND NEW.amount > 500000",
                List.of(
                        step("a1", "UPDATE_FIELDS", s -> s.withFields(Map.of("next_step", "'Exec review'"))),
                        step("a2", "CREATE_RECORD", s -> s.withCreate("CONTACT",
                                Map.of("first_name", "'Auto'", "last_name", "'Generated'"))),
                        step("a3", "CREATE_TASK", s -> s.withTask("CONCAT('Review ', NEW.name)")),
                        step("a4", "SEND_EMAIL", s -> s.withEmail("'ops@example.com'")),
                        step("a5", "SEND_NOTIFICATION", s -> s.withNotification("owner_id")),
                        step("a6", "SUBMIT_FOR_APPROVAL", s -> s.withApproval("APR-OPP-DISCOUNT")),
                        step("a7", "INVOKE_WEBHOOK", s -> s.withWebhook("https://hooks.example.com/x")),
                        step("a8", "CALL_INTEGRATION", s -> s.withIntegration("sap-erp"))));
    }

    // A tiny builder so the eight action steps stay readable.
    private record StepBuilder(String key, String actionType) {
        RuleModel.Step withFields(Map<String, String> fields) {
            return base(null, null, fields, null, null, null, null, null, null, null, null);
        }
        RuleModel.Step withCreate(String objectType, Map<String, String> values) {
            return base(objectType, values, null, null, null, null, null, null, null, null, null);
        }
        RuleModel.Step withTask(String subject) {
            return base(null, null, null, subject, 3, null, null, null, null, null, null);
        }
        RuleModel.Step withEmail(String to) {
            return base(null, null, null, "'Large deal'", null, to, null, "'body'", null, null, null);
        }
        RuleModel.Step withNotification(String recipientField) {
            return base(null, null, null, null, null, null, recipientField, "'body'", null, null, null);
        }
        RuleModel.Step withApproval(String processCode) {
            return base(null, null, null, null, null, null, null, null, processCode, null, null);
        }
        RuleModel.Step withWebhook(String url) {
            return base(null, null, null, null, null, null, null, null, null, url, null);
        }
        RuleModel.Step withIntegration(String name) {
            return base(null, null, null, null, null, null, null, null, null, null, name);
        }
        private RuleModel.Step base(String objectType, Map<String, String> values,
                                    Map<String, String> fields, String subject, Integer dueInDays,
                                    String emailTo, String recipientField, String message,
                                    String approvalProcessCode, String webhookUrl,
                                    String integrationName) {
            return new RuleModel.Step(key, "ACTION", actionType + " step", null, null, null,
                    null, null, null, null, null,
                    actionType, "TRIGGERING", null, null, fields,
                    objectType, values,
                    subject, "HIGH", dueInDays, null,
                    recipientField, emailTo, "Automation", message,
                    approvalProcessCode, webhookUrl, integrationName, Map.of());
        }
    }

    private static RuleModel.Step step(String key, String actionType,
                                       java.util.function.Function<StepBuilder, RuleModel.Step> f) {
        return f.apply(new StepBuilder(key, actionType));
    }

    // ------------------------------------------------------------------ FR-AUT-010

    @Test void dryRunPerformsZeroWritesAndStillListsEveryWouldBeAction() {
        when(approvals.preview(anyString(), anyString(), any()))
                .thenReturn(new ApprovalService.ApprovalPreview("APR-OPP-DISCOUNT",
                        List.of("g1 Sales"), List.of("priya.nair@meridianfab.com")));

        Map<String, Object> before = new LinkedHashMap<>(record());
        before.put("amount", new BigDecimal("100000"));

        RuleModel.ExecutionTrace trace =
                engine.run(context(RuleModel.Mode.DRY_RUN, before), everyAction());

        assertTrue(trace.entryConditionMet(), trace.entryConditionDetail());
        assertEquals("SUCCEEDED", trace.status());
        assertEquals(0, trace.actionsExecuted(), "a dry run executes nothing");

        List<RuleModel.StepTrace> wouldBe = trace.steps().stream()
                .filter(s -> "WOULD_EXECUTE".equals(s.outcome())).toList();
        assertEquals(8, wouldBe.size(), "every one of the eight actions must be reported");
        assertTrue(wouldBe.stream().anyMatch(s -> s.stepType().equals("ACTION:UPDATE_FIELDS")));
        assertTrue(wouldBe.stream().anyMatch(s -> s.stepType().equals("ACTION:SUBMIT_FOR_APPROVAL")));
        assertTrue(wouldBe.stream().allMatch(s -> s.detail().get("description") != null));

        // The guarantee, asserted from outside the code that makes it.
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).update(anyString());
        verify(jdbc, never()).execute(anyString());
        verify(jdbc, never()).batchUpdate(anyString(), any(List.class));
        verifyNoInteractions(outbox);
        verifyNoInteractions(notifications);
        verifyNoInteractions(dispatcher);
        verify(approvals, never()).submit(any());
    }

    @Test void dryRunAppliesItsOwnUpdatesInMemorySoLaterStepsSeeThem() {
        RuleModel.Definition definition = new RuleModel.Definition(
                new RuleModel.TriggerSpec("RECORD_CHANGE", List.of("UPDATE"), null), null,
                List.of(step("a1", "UPDATE_FIELDS", s -> s.withFields(Map.of("next_step", "'Exec review'"))),
                        new RuleModel.Step("c1", "CONDITION", "Next step is set now",
                                "NEW.next_step = 'Exec review'", List.of(), List.of(),
                                null, null, null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null, null, null, null,
                                null, null, null)));

        RuleModel.ExecutionTrace trace = engine.run(context(RuleModel.Mode.DRY_RUN, record()), definition);
        assertEquals("TRUE", trace.steps().stream()
                .filter(s -> "CONDITION".equals(s.stepType())).findFirst().orElseThrow().outcome());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    // ------------------------------------------------------------------ FR-AUT-006

    @Test void everyActionTypeExecutesInLiveMode() {
        when(approvals.submit(any())).thenReturn(new ApprovalService.ApprovalInstanceView(
                UUID.randomUUID(), "APR-OPP-DISCOUNT", "Large deal", "OPPORTUNITY", OPPORTUNITY_ID,
                "Northbrook", new BigDecimal("540000"), USER, "admin@example.com",
                java.time.Instant.now(), "PENDING", 1, null, null, null, 1, List.of()));

        Map<String, Object> before = new LinkedHashMap<>(record());
        before.put("amount", new BigDecimal("100000"));

        RuleModel.ExecutionTrace trace =
                engine.run(context(RuleModel.Mode.LIVE, before), everyAction());

        assertEquals("SUCCEEDED", trace.status(), failures(trace));
        assertEquals(8, trace.actionsExecuted());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeast(3)).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().stream().anyMatch(s -> s.startsWith("update sales.opportunity")));
        assertTrue(sql.getAllValues().stream().anyMatch(s -> s.startsWith("insert into crm.contact")));
        assertTrue(sql.getAllValues().stream().anyMatch(s -> s.startsWith("insert into engagement.activity")));

        verify(outbox).write(eq("AUTOMATION_EMAIL"), any(), eq("automation.email.requested"), any());
        verify(outbox).write(eq("AUTOMATION_WEBHOOK"), any(), eq("automation.webhook.requested"), any());
        verify(outbox).write(eq("AUTOMATION_INTEGRATION"), any(), eq("automation.integration.requested"), any());
        verify(notifications).notifyUser(eq(TENANT), eq(OWNER_ID), eq("AUTOMATION"), anyString(),
                anyString(), anyString(), any(), anyString(), eq(false));
        verify(approvals).submit(any());
    }

    private static String failures(RuleModel.ExecutionTrace trace) {
        return trace.steps().stream().filter(s -> "FAILED".equals(s.outcome()))
                .map(s -> s.label() + ": " + s.detail()).toList().toString();
    }

    // ------------------------------------------------------------------ entry conditions

    @Test void entryConditionOverOldAndNewValuesSkipsWhenItDoesNotHold() {
        RuleModel.ExecutionTrace trace =
                engine.run(context(RuleModel.Mode.LIVE, record()), everyAction());
        assertFalse(trace.entryConditionMet());
        assertEquals("SKIPPED", trace.status());
        assertEquals(0, trace.actionsExecuted());
        verifyNoInteractions(outbox);
    }

    // ------------------------------------------------------------------ branches and loops

    @Test void conditionStepBranchesAndLoopStepIteratesRelatedRecords() {
        when(metadata.readRelated(eq("CONTACT"), eq("account_id"), any(), anyInt()))
                .thenReturn(List.of(Map.of("id", UUID.randomUUID(), "email", "a@example.com"),
                        Map.of("id", UUID.randomUUID(), "email", "b@example.com")));

        RuleModel.Step notifyInLoop = step("n1", "SEND_EMAIL", s -> s.withEmail("email"));
        RuleModel.Step loop = new RuleModel.Step("loop", "LOOP", "Each contact", null,
                List.of(), List.of(), "CONTACT", "account_id", "contact", 10, List.of(notifyInLoop),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, Map.of());
        RuleModel.Step branch = new RuleModel.Step("c1", "CONDITION", "Large?",
                "NEW.amount > 500000", List.of(loop), List.of(), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, Map.of());

        RuleModel.ExecutionTrace trace = engine.run(context(RuleModel.Mode.LIVE, record()),
                new RuleModel.Definition(null, null, List.of(branch)));

        assertEquals("SUCCEEDED", trace.status(), failures(trace));
        assertTrue(trace.steps().stream().anyMatch(s -> "LOOP".equals(s.stepType())
                && ((Number) s.detail().get("matched")).intValue() == 2));
        // The loop body used the CHILD's email, once per child.
        verify(outbox, org.mockito.Mockito.times(2))
                .write(eq("AUTOMATION_EMAIL"), any(), anyString(), any());
    }

    @Test void anUnknownActionTypeFailsTheStepInsteadOfThrowing() {
        RuleModel.Step bogus = step("x", "TELEPORT", s -> s.withFields(Map.of("next_step", "'x'")));
        RuleModel.ExecutionTrace trace = engine.run(context(RuleModel.Mode.LIVE, record()),
                new RuleModel.Definition(null, null, List.of(bogus)));
        assertEquals("FAILED", trace.status());
        assertTrue(trace.steps().getFirst().detail().get("description").toString()
                .contains("not a supported action"));
    }
}

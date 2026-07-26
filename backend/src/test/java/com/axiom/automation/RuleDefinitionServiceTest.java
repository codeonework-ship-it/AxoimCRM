package com.axiom.automation;

import com.axiom.audit.AuditService;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FR-AUT-013 versioning and restore, FR-AUT-014 the absence of any rule cap. */
class RuleDefinitionServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID RULE = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private ObjectMetadataService metadata;
    private AuditService audit;
    private RuleDefinitionService service;

    private static final ObjectMetadataService.ObjectDescriptor OPPORTUNITY =
            new ObjectMetadataService.ObjectDescriptor(UUID.randomUUID(), "OPPORTUNITY", "Opportunity",
                    "sales", "opportunity", "id", "owner_id", null,
                    List.of("id", "tenant_id", "created_at", "version"), null, null,
                    Map.of("id", "uuid", "name", "text", "amount", "numeric", "next_step", "text",
                            "forecast_category", "text"));

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        metadata = mock(ObjectMetadataService.class);
        audit = mock(AuditService.class);
        when(metadata.describe(anyString())).thenReturn(OPPORTUNITY);
        when(metadata.requireWritableColumn(any(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(metadata.requireColumn(any(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        service = new RuleDefinitionService(jdbc, new ObjectMapper(), metadata, audit);
        TenantContext.set(new TenantContext.Principal(TENANT, UUID.randomUUID(), "TENANT_ADMIN",
                "Admin", "admin@example.com"));
        stubRuleView(1);
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private void stubRuleView(int activeVersion) {
        doReturn(List.of(new RuleDefinitionService.RuleView(RULE, "AUT-TEST", "Test", null,
                "OPPORTUNITY", "RECORD_CHANGE", "DRAFT", activeVersion, 100, Instant.now(),
                Instant.now(), activeVersion, definition("'v" + activeVersion + "'"))))
                .when(jdbc).query(contains("from automation.rule_definition r"), any(RowMapper.class),
                        any(Object[].class));
    }

    private static RuleModel.Definition definition(String value) {
        RuleModel.Step step = new RuleModel.Step("s1", "ACTION", "Set next step", null, null, null,
                null, null, null, null, null, "UPDATE_FIELDS", "TRIGGERING", null, null,
                Map.of("next_step", value), null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        return new RuleModel.Definition(
                new RuleModel.TriggerSpec("RECORD_CHANGE", List.of("UPDATE"), null),
                "NEW.amount > 1", List.of(step));
    }

    private static RuleDefinitionService.RuleMutation mutation(RuleModel.Definition definition) {
        return new RuleDefinitionService.RuleMutation("AUT-TEST", "Test", null, "OPPORTUNITY",
                "RECORD_CHANGE", 100, definition, null);
    }

    // ------------------------------------------------------------------ FR-AUT-014

    @Test void thereIsNoNumericCapOnRulesPerObjectOrPerTenant() {
        // Five hundred rules, none refused, and no count query is ever issued.
        for (int i = 0; i < 500; i++) {
            int n = i;
            assertDoesNotThrow(() -> service.create(new RuleDefinitionService.RuleMutation(
                    "AUT-BULK-" + n, "Bulk " + n, null, "OPPORTUNITY", "RECORD_CHANGE", 100,
                    definition("'x'"), null)));
        }
        verify(jdbc, times(500)).update(contains("insert into automation.rule_definition"),
                any(Object[].class));

        verify(jdbc, never()).queryForObject(contains("count("), eq(Integer.class),
                any(Object[].class));
    }

    // ------------------------------------------------------------------ FR-AUT-013

    @Test void savingAnEditAppendsAVersionRatherThanOverwritingOne() {
        doReturn(3).when(jdbc).queryForObject(contains("max(version_no)"), eq(Integer.class),
                any(Object[].class));

        service.saveVersion(RULE, mutation(definition("'v4'")));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into automation.rule_version"), args.capture());
        assertEquals(4, args.getValue()[3], "the new version number is max + 1");
        verify(jdbc).update(contains("update automation.rule_definition"), any(Object[].class));
        verify(jdbc, never()).update(contains("update automation.rule_version"), any(Object[].class));
    }

    @Test void aPriorVersionIsRestorableAndKeepsItsProvenance() {
        String stored = "{\"entryCondition\":\"NEW.amount > 1\",\"steps\":[]}";
        doReturn(stored).when(jdbc).query(contains("select definition::text from automation.rule_version"),
                any(ResultSetExtractor.class), any(Object[].class));
        doReturn(4).when(jdbc).queryForObject(contains("max(version_no)"), eq(Integer.class),
                any(Object[].class));

        service.restoreVersion(RULE, 2);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into automation.rule_version"), args.capture());
        Object[] captured = args.getValue();
        assertEquals(5, captured[3], "restoring appends version 5, it does not rewind to 2");
        assertEquals(stored, captured[4], "the restored document is the stored one, byte for byte");
        assertEquals("Restored from version 2", captured[5]);
        assertEquals(2, captured[6], "restored_from_version_no keeps the provenance");
        verify(audit).record(eq("AUTOMATION_RULE_RESTORED"), anyString(), any(), anyString(), any());
    }

    @Test void restoringAVersionThatDoesNotExistIsANotFound() {
        doReturn(null).when(jdbc).query(contains("select definition::text from automation.rule_version"),
                any(ResultSetExtractor.class), any(Object[].class));
        assertThrows(com.axiom.common.NotFoundException.class, () -> service.restoreVersion(RULE, 99));
    }

    // ------------------------------------------------------------------ save-time validation

    @Test void anUnparseableEntryConditionIsRejectedAtSaveTimeWithItsPosition() {
        RuleModel.Definition bad = new RuleModel.Definition(null, "amount > * 3", List.of());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(mutation(bad)));
        assertTrue(ex.getMessage().contains("position 10"), ex.getMessage());
    }

    @Test void anUnsupportedActionIsNamedAlongWithTheStepItIsIn() {
        RuleModel.Step bogus = new RuleModel.Step("s9", "ACTION", "Teleport", null, null, null,
                null, null, null, null, null, "TELEPORT", "TRIGGERING", null, null, Map.of(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(mutation(new RuleModel.Definition(null, null, List.of(bogus)))));
        assertTrue(ex.getMessage().contains("step[0] (s9)"), ex.getMessage());
        assertTrue(ex.getMessage().contains("TELEPORT"), ex.getMessage());
    }

    @Test void aProtectedColumnCannotBeWrittenByARule() {
        when(metadata.requireWritableColumn(any(), eq("tenant_id")))
                .thenThrow(new IllegalArgumentException("Automation may not write OPPORTUNITY.tenant_id; "
                        + "it is a protected column."));
        RuleModel.Definition bad = definition("'x'");
        RuleModel.Step step = new RuleModel.Step("s1", "ACTION", "Hijack", null, null, null,
                null, null, null, null, null, "UPDATE_FIELDS", "TRIGGERING", null, null,
                Map.of("tenant_id", "'other'"), null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(mutation(new RuleModel.Definition(bad.trigger(), null,
                        List.of(step)))));
        assertTrue(ex.getMessage().contains("protected column"), ex.getMessage());
    }
}

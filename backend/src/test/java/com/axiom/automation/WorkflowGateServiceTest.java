package com.axiom.automation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowGateServiceTest {

    @Test void plainFieldTurnsTechnicalColumnIntoLaymanLabel() {
        assertEquals("Next step", WorkflowGateService.plainField("next_step"));
        assertEquals("Close date", WorkflowGateService.plainField("closeDate"));
    }

    @Test void conditionTextUsesFriendlyBusinessWords() {
        assertEquals("filled in", WorkflowGateService.conditionText(
                new ProcessService.TransitionCondition("next_step", "NOT_BLANK", null, null)));
        assertEquals("at least 100000", WorkflowGateService.conditionText(
                new ProcessService.TransitionCondition("amount", "GTE", "100000", null)));
        assertEquals("one of APPROVED, READY", WorkflowGateService.conditionText(
                new ProcessService.TransitionCondition("status", "IN", "APPROVED|READY", null)));
    }

    @Test void firstActionNamesTheNextFix() {
        assertEquals("Fill Close date before moving to COMMIT.",
                WorkflowGateService.firstAction(List.of(new WorkflowGateService.GateIssue(
                        "MISSING_COMMIT_close_date",
                        "Commit",
                        "close_date",
                        "Close date is required for Commit.",
                        "Fill Close date before moving to COMMIT.",
                        "COMMIT"))));
    }

    @Test void transitionCheckUsesTheExactTargetPrerequisites() {
        UUID processId = UUID.randomUUID();
        ProcessService.StateView draft = new ProcessService.StateView(
                UUID.randomUUID(), "DRAFT", "Draft", 10, true, false, List.of(), null);
        ProcessService.StateView active = new ProcessService.StateView(
                UUID.randomUUID(), "ACTIVE", "Active", 20, false, false,
                List.of("signed_document_ref"), null);
        ProcessService.TransitionView activate = new ProcessService.TransitionView(
                UUID.randomUUID(), "DRAFT", "ACTIVE", "Activate signed contract",
                List.of(new ProcessService.TransitionCondition(
                        "signed_document_ref", "NOT_BLANK", "", "a signed document reference")),
                null);
        ProcessService.ProcessView process = new ProcessService.ProcessView(
                processId, "PRC-CONTRACT-LIFECYCLE", "Contract lifecycle", "CONTRACT",
                "status", "ACTIVE", null, List.of(draft, active), List.of(activate));

        List<WorkflowGateService.GateIssue> missing = WorkflowGateService.transitionIssues(
                process, activate, Map.of("status", "ACTIVE", "signed_document_ref", ""));
        List<WorkflowGateService.GateIssue> ready = WorkflowGateService.transitionIssues(
                process, activate, Map.of("status", "ACTIVE", "signed_document_ref", "signed://C-1"));

        assertEquals(2, missing.size());
        assertEquals("Update Signed document ref so the record can move to ACTIVE.",
                missing.getFirst().nextAction());
        assertEquals(List.of(), ready);
    }

    @Test void booleanBackedProcessStateKeepsItsDatabaseSpelling() {
        ProcessService.ProcessView process = new ProcessService.ProcessView(
                UUID.randomUUID(), "PRC-AUTOMATION-SIMULATION", "Simulation readiness",
                "AUTOMATION_RULE", "simulation_passed", "ACTIVE", null,
                List.of(new ProcessService.StateView(UUID.randomUUID(), "false", "Required", 10,
                                true, false, List.of(), null),
                        new ProcessService.StateView(UUID.randomUUID(), "true", "Passed", 20,
                                false, false, List.of(), null)),
                List.of());

        assertEquals("true", WorkflowGateService.canonicalTarget(process, "TRUE"));
        assertEquals("UNKNOWN", WorkflowGateService.canonicalTarget(process, "unknown"));
    }
}

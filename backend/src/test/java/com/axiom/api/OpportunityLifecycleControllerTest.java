package com.axiom.api;

import com.axiom.pipeline.OpportunityLifecycleService;
import com.axiom.pipeline.OpportunityCommandService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpportunityLifecycleControllerTest {
    @Test void stagePreflightUsesTheSameLifecycleEngineAsTheCommand() {
        OpportunityLifecycleService lifecycle = mock(OpportunityLifecycleService.class);
        OpportunityController controller = new OpportunityController(mock(OpportunityService.class), lifecycle,
                mock(OpportunityCrudService.class), mock(OpportunityCommandService.class));
        UUID opportunity = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        OpportunityLifecycleService.GatePreview preview = new OpportunityLifecycleService.GatePreview(
                opportunity, target, "Proposal", "FORWARD", true, false,
                null, 0, List.of(), null);
        when(lifecycle.previewGate(opportunity, target)).thenReturn(preview);

        assertSame(preview, controller.previewStage(opportunity, target));
        verify(lifecycle).previewGate(opportunity, target);
    }
}

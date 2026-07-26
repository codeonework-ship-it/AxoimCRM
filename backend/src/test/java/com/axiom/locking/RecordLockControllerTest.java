package com.axiom.locking;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordLockControllerTest {

    @Test
    void freeRecordHasAnExplicitUnlockedResponseRatherThanAnAmbiguousNullBody() {
        RecordLockService service = mock(RecordLockService.class);
        RecordLockController controller = new RecordLockController(service);
        UUID id = UUID.randomUUID();
        when(service.status("CONTACT", id)).thenReturn(null);

        RecordLockController.LockStatus status = controller.status("CONTACT", id);

        assertFalse(status.locked());
        assertNull(status.lock());
        verify(service).status("CONTACT", id);
    }

    @Test
    void releaseIsIdempotentlyDelegatedToTheLeaseOwnerService() {
        RecordLockService service = mock(RecordLockService.class);
        RecordLockController controller = new RecordLockController(service);
        UUID id = UUID.randomUUID();

        controller.release("CONTACT", id);

        verify(service).release("CONTACT", id);
    }
}

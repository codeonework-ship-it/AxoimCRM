package com.axiom.locking;

import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RecordLockServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RecordLockService service = new RecordLockService(jdbc);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void lockTypesAreCanonicalAndUnknownTypesCannotCreateParallelLockNamespaces() {
        assertEquals("CONTACT", RecordLockService.normalise(" contact "));
        assertThrows(ConflictException.class, () -> RecordLockService.normalise("ContactCard"));
    }

    @Test
    void superAdminCanForceReleaseAWorkspaceLock() {
        UUID tenantId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(),
                "SUPER_ADMIN", "Platform Admin", "admin@example.com"));

        service.forceRelease("contact", recordId);

        verify(jdbc).update(contains("delete from crm.record_lock"),
                eq(tenantId), eq("CONTACT"), eq(recordId));
    }

    @Test
    void ordinaryUserCannotForceReleaseAWorkspaceLock() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SALES", "Sales User", "sales@example.com"));

        assertThrows(ForbiddenException.class,
                () -> service.forceRelease("CONTACT", UUID.randomUUID()));
        verify(jdbc, never()).update(contains("delete from crm.record_lock"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}

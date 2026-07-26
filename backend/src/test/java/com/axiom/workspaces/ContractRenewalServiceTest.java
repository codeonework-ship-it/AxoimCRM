package com.axiom.workspaces;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ContractRenewalServiceTest {
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void renewalStartsNextDayAndPreservesTheOriginalTerm() {
        ContractRenewalService.RenewalWindow result = ContractRenewalService.renewalWindow(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertEquals(LocalDate.of(2027, 1, 1), result.start());
        assertEquals(LocalDate.of(2027, 12, 31), result.end());
    }

    @Test void auditorCannotGenerateARenewalDraft() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SUPER_AUDIT", "Auditor", "audit@example.test"));
        ContractRenewalService service = new ContractRenewalService(mock(JdbcTemplate.class),
                mock(AuditService.class), mock(OutboxWriter.class));
        assertThrows(ForbiddenException.class, () -> service.prepare(UUID.randomUUID(),
                new ContractRenewalService.RenewalRequest("Renewal due")));
    }
}

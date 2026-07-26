package com.axiom.workspaces;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class CustomerOperationsClosureTest {
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void campaignRoiUsesNetReturnAndStableCommercialRounding() {
        assertEquals(new BigDecimal("150.00"), MarketingPerformanceService.roi(
                new BigDecimal("250"), new BigDecimal("100")));
        assertNull(MarketingPerformanceService.roi(BigDecimal.TEN, BigDecimal.ZERO));
    }

    @Test void readOnlyAuditorCannotRunCaseEscalationSweep() {
        principal("AUDITOR");
        CaseSlaService service = new CaseSlaService(mock(JdbcTemplate.class), mock(AuditService.class));
        assertThrows(ForbiddenException.class, () -> service.sweep(UUID.randomUUID()));
    }

    @Test void partnerRegistrationRequiresAnOpportunityAndRetryKey() {
        principal("SALES_MANAGER");
        PartnerDealService service = new PartnerDealService(mock(JdbcTemplate.class), mock(AuditService.class));
        assertThrows(IllegalArgumentException.class, () -> service.register(UUID.randomUUID(),
                new PartnerDealService.RegisterRequest(null, BigDecimal.TEN, "retry-1")));
        assertThrows(IllegalArgumentException.class, () -> service.register(UUID.randomUUID(),
                new PartnerDealService.RegisterRequest(UUID.randomUUID(), BigDecimal.TEN, " ")));
    }

    private static void principal(String role) {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                role, "Operator", "operator@example.test"));
    }
}

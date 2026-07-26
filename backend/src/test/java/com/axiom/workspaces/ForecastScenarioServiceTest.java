package com.axiom.workspaces;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ForecastScenarioServiceTest {
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void scenarioMoneyUsesStableCommercialRounding() {
        assertEquals(new BigDecimal("123.46"), ForecastScenarioService.money(new BigDecimal("123.455")));
    }

    @Test void auditorCannotSaveAScenario() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "AUDITOR", "Auditor", "audit@example.test"));
        ForecastScenarioService service = new ForecastScenarioService(mock(JdbcTemplate.class),
                new ObjectMapper(), mock(AuditService.class));
        assertThrows(ForbiddenException.class, () -> service.create(UUID.randomUUID(),
                new ForecastScenarioService.ScenarioRequest("Upside", BigDecimal.TEN,
                        new BigDecimal("80"), 1)));
    }
}

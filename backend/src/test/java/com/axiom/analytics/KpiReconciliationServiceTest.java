package com.axiom.analytics;

import com.axiom.audit.AuditService;
import com.axiom.security.SystemTaskRunner;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KpiReconciliationServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private JdbcTemplate jdbc;
    private KpiReconciliationService service;

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new KpiReconciliationService(jdbc, mock(SystemTaskRunner.class), mock(AuditService.class));
        TenantContext.set(new TenantContext.Principal(TENANT, UUID.randomUUID(), "SUPER_ADMIN",
                "Axiom Admin", "admin@axiomcrm.com"));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void everyIndependentKpiCheckMatchesAndLeavesDurableEvidence() {
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(new BigDecimal("0.62500000"));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        KpiReconciliationService.KpiReport report = service.reconcile(TENANT);

        assertThat(report.checksRun()).isEqualTo(6);
        assertThat(report.drifted()).isZero();
        assertThat(report.verdict()).contains("matched");
        verify(jdbc, org.mockito.Mockito.times(6)).update(anyString(), any(Object[].class));
    }

    @Test void authoritativeKpiSqlDoesNotReuseTheProjection() {
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(BigDecimal.ONE);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        service.reconcile(TENANT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .queryForObject(sql.capture(), eq(BigDecimal.class), any(Object[].class));
        List<String> statements = sql.getAllValues();
        assertThat(statements).anySatisfy(s -> assertThat(s).contains("analytics.opportunity_fact"));
        assertThat(statements).anySatisfy(s -> assertThat(s).contains("sales.opportunity"));
        assertThat(statements).allSatisfy(s -> assertThat(
                s.contains("analytics.opportunity_fact") && s.contains("sales.opportunity")).isFalse());
    }
}

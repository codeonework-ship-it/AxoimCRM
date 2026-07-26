package com.axiom.reference;

import com.axiom.audit.AuditService;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReferenceResolutionTest {
    private JdbcTemplate jdbc;
    private ReferenceDataService service;

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new ReferenceDataService(jdbc, mock(AuditService.class));
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "DATA_STEWARD", "Data steward", "steward@example.test"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void missingHistoricalWindowNamesTheCodeAndDate() {
        when(jdbc.queryForObject(anyString(), eq(UUID.class), any(), any())).thenReturn(UUID.randomUUID());
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ReferenceDataService.ResolvedEntry>>any(),
                any(), any(), any(), any(), any())).thenThrow(new EmptyResultDataAccessException(1));
        NotFoundException error = assertThrows(NotFoundException.class,
                () -> service.resolve("lead_status", "OLD_VALUE", LocalDate.of(2024, 1, 1)));
        assertTrue(error.getMessage().contains("OLD_VALUE"));
        assertTrue(error.getMessage().contains("2024-01-01"));
    }
}

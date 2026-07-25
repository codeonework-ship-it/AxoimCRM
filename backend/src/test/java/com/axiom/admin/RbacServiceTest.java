package com.axiom.admin;

import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RbacServiceTest {
    private JdbcTemplate jdbc;
    private RbacService service;

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new RbacService(jdbc);
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SUPER_ADMIN", "Admin", "admin@example.com"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void canWriteUsesCurrentRoleAndScreenPolicy() {
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Boolean.class),
                org.mockito.ArgumentMatchers.eq("SUPER_ADMIN"),
                org.mockito.ArgumentMatchers.eq("BILLING"))).thenReturn(true);

        assertTrue(service.canWrite("BILLING"));
    }
}

package com.axiom.admin;

import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.PlatformSession;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AdminServiceTest {
    private AdminService service;

    @BeforeEach void setUp() {
        // A real PlatformSession over a mocked JdbcTemplate: the role guard must still
        // run for real, otherwise billingRequiresPlatformRole would pass vacuously.
        service = new AdminService(mock(JdbcTemplate.class), new PlatformSession(mock(JdbcTemplate.class)),
                mock(com.axiom.identity.StepUpService.class), mock(com.axiom.identity.SessionService.class),
                mock(com.axiom.identity.PasswordPolicyService.class));
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SALES", "Sales", "sales@example.com"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void billingRequiresPlatformRole() {
        assertThrows(ForbiddenException.class, () -> service.billing());
    }

    @Test void tenantUserCreationCannotCreatePlatformRole() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "TENANT_ADMIN", "Admin", "admin@example.com"));

        assertThrows(ForbiddenException.class, () -> service.createUser(
                new AdminService.CreateUserRequest("Platform User", "platform@example.com", "SUPER_ADMIN", "axiom-demo")));
    }
}

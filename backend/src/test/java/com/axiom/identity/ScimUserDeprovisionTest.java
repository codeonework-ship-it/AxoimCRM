package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScimUserDeprovisionTest {
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void deprovisionDeactivatesRevokesAndNeverHardDeletes() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        TenantContext.set(new TenantContext.Principal(tenant, UUID.randomUUID(),
                "INTEGRATION", "SCIM", "scim@axiom.local"));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SessionService sessions = mock(SessionService.class);
        Map<String, Object> row = new HashMap<>();
        row.put("id", user); row.put("email", "leaver@example.com");
        row.put("display_name", "Leaver"); row.put("role", "SALES"); row.put("active", true);
        row.put("created_at", Timestamp.from(Instant.now())); row.put("updated_at", Timestamp.from(Instant.now()));
        row.put("external_id", "external-1"); row.put("scim_version", 1L);
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(row);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(7);
        when(sessions.revokeAllForUserSystem(tenant, user, "SCIM delete: user removed in the directory"))
                .thenReturn(2);

        new ScimUserService(jdbc, sessions, mock(AuditService.class)).deprovisionUser(user);

        verify(sessions).revokeAllForUserSystem(tenant, user,
                "SCIM delete: user removed in the directory");
        assertFalse(org.mockito.Mockito.mockingDetails(jdbc).getInvocations().stream()
                .anyMatch(call -> java.util.Arrays.stream(call.getArguments())
                        .filter(String.class::isInstance).map(String.class::cast)
                        .anyMatch(sql -> sql.trim().toLowerCase().startsWith("delete"))),
                "SCIM deprovisioning must preserve the user and owned records");
    }
}

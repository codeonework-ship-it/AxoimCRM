package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-TEN-010: the property that matters is that a revoked session stops working
 * on the next request, not when the token expires.
 */
class SessionServiceTest {

    private JdbcTemplate jdbc;
    private SessionService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new SessionService(jdbc, mock(AuditService.class));
        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(),
                "TENANT_ADMIN", "Admin", "admin@example.com"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    private Map<String, Object> row(Instant revokedAt, Instant expiresAt, Instant lastSeenAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("revoked_at", revokedAt == null ? null : Timestamp.from(revokedAt));
        row.put("expires_at", Timestamp.from(expiresAt));
        row.put("last_seen_at", Timestamp.from(lastSeenAt));
        row.put("step_up_at", null);
        row.put("tenant_status", "active");
        row.put("idle_minutes", 120);
        row.put("step_up_seconds", 300);
        return row;
    }

    @Test void aLiveSessionIsAccepted() {
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(row(null, Instant.now().plus(Duration.ofHours(4)), Instant.now()));
        SessionService.SessionState state = service.validate(tenantId, "jti-live");
        assertTrue(state.valid());
        assertEquals(SessionService.Verdict.VALID, state.verdict());
    }

    @Test void aRevokedSessionIsRefusedEvenThoughItsTokenHasNotExpired() {
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(row(Instant.now().minus(Duration.ofSeconds(2)),
                        Instant.now().plus(Duration.ofHours(4)), Instant.now()));
        SessionService.SessionState state = service.validate(tenantId, "jti-revoked");
        assertFalse(state.valid(), "revocation must not wait for token expiry");
        assertEquals(SessionService.Verdict.REVOKED, state.verdict());
    }

    @Test void anExpiredSessionIsRefused() {
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(row(null, Instant.now().minus(Duration.ofMinutes(1)),
                        Instant.now().minus(Duration.ofMinutes(2))));
        assertEquals(SessionService.Verdict.EXPIRED, service.validate(tenantId, "jti-old").verdict());
    }

    @Test void anIdleSessionIsEndedAndRefused() {
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(row(null, Instant.now().plus(Duration.ofHours(4)),
                        Instant.now().minus(Duration.ofHours(3))));
        assertEquals(SessionService.Verdict.IDLE_TIMEOUT, service.validate(tenantId, "jti-idle").verdict());
    }

    @Test void anUnknownJtiIsRefusedRatherThanTrusted() {
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        assertEquals(SessionService.Verdict.UNKNOWN, service.validate(tenantId, "jti-forged").verdict());
    }

    @Test void theTenantLifecycleStatusTravelsWithTheSessionCheck() {
        Map<String, Object> suspended = row(null, Instant.now().plus(Duration.ofHours(4)), Instant.now());
        suspended.put("tenant_status", "suspended");
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(suspended);
        SessionService.SessionState state = service.validate(tenantId, "jti-live");
        assertTrue(state.valid());
        assertEquals("suspended", state.tenantStatus(),
                "the write block needs the status on the same probe, not a second round trip");
    }

    @Test void revokingSomeoneElsesSessionRequiresAnAdministrator() {
        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(),
                "SALES", "Priya", "priya@example.com"));
        assertThrows(ForbiddenException.class,
                () -> service.revoke(UUID.randomUUID(), "Laptop was stolen"));
    }

    @Test void revocationDemandsAReasonForTheAuditTrail() {
        assertThrows(IllegalArgumentException.class, () -> service.revoke(UUID.randomUUID(), "x"));
    }
}

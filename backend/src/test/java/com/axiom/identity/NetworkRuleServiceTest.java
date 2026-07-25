package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FR-TEN-014: sign-in restriction by IP range. */
class NetworkRuleServiceTest {

    private JdbcTemplate jdbc;
    private NetworkRuleService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new NetworkRuleService(jdbc, mock(AuditService.class));
        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(),
                "TENANT_ADMIN", "Admin", "admin@example.com"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void anAddressInsideTheRangeMatches() {
        assertTrue(NetworkRuleService.contains("203.0.113.0/24", "203.0.113.42"));
    }

    @Test void anAddressOutsideTheRangeDoesNotMatch() {
        assertFalse(NetworkRuleService.contains("203.0.113.0/24", "203.0.114.42"));
    }

    @Test void prefixLengthIsHonouredRatherThanTreatedAsAStringPrefix() {
        // 10.16.0.1 shares the textual prefix "10.1" with 10.0.0.0/12 but is outside it.
        assertTrue(NetworkRuleService.contains("10.0.0.0/12", "10.15.255.254"));
        assertFalse(NetworkRuleService.contains("10.0.0.0/12", "10.16.0.1"));
    }

    @Test void aSingleHostRuleMatchesOnlyThatHost() {
        assertTrue(NetworkRuleService.contains("192.168.1.7/32", "192.168.1.7"));
        assertFalse(NetworkRuleService.contains("192.168.1.7/32", "192.168.1.8"));
    }

    @Test void aBareAddressWithNoPrefixIsTreatedAsASingleHost() {
        assertTrue(NetworkRuleService.contains("198.51.100.5", "198.51.100.5"));
        assertFalse(NetworkRuleService.contains("198.51.100.5", "198.51.100.6"));
    }

    @Test void ipv6RangesAreSupported() {
        assertTrue(NetworkRuleService.contains("2001:db8::/32", "2001:db8:1234::1"));
        assertFalse(NetworkRuleService.contains("2001:db8::/32", "2001:db9::1"));
    }

    @Test void mixedAddressFamiliesNeverMatchEachOther() {
        assertFalse(NetworkRuleService.contains("203.0.113.0/24", "2001:db8::1"));
        assertFalse(NetworkRuleService.contains("2001:db8::/32", "203.0.113.1"));
    }

    @Test void unparseableInputIsRefusedRatherThanMatchingEverything() {
        assertFalse(NetworkRuleService.contains("not-a-cidr", "203.0.113.1"));
        assertFalse(NetworkRuleService.contains("203.0.113.0/24", "not-an-ip"));
    }

    @Test void noActiveRulesPermitsEveryAddress() {
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of());
        assertTrue(service.isPermitted(tenantId, "198.51.100.9"),
                "an empty allowlist must not lock an existing workspace out");
    }

    @Test void anActiveAllowlistPermitsOnlyListedRanges() {
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("203.0.113.0/24", "10.0.0.0/8"));
        assertTrue(service.isPermitted(tenantId, "203.0.113.7"));
        assertTrue(service.isPermitted(tenantId, "10.4.4.4"));
        assertFalse(service.isPermitted(tenantId, "198.51.100.9"));
    }

    @Test void anUnknownClientAddressIsRefusedWhenAnAllowlistIsActive() {
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("203.0.113.0/24"));
        assertFalse(service.isPermitted(tenantId, null),
                "an active allowlist that cannot be evaluated must fail closed");
    }
}

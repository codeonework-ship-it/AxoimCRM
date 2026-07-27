package com.axiom.i18n;

import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class I18nServiceTest {
    private JdbcTemplate jdbc;
    private I18nService service;

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new I18nService(jdbc);
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    /** The mock returns null for the exists() probe, i.e. "no such locale row". */
    @Test void unknownLocaleIsNotFound() {
        NotFoundException ex = assertThrows(NotFoundException.class, () -> service.bundle("zz"));
        assertEquals("Unsupported locale: zz", ex.getMessage());
    }

    @Test void nullLocaleIsNotFound() {
        assertThrows(NotFoundException.class, () -> service.bundle(null));
    }

    /**
     * The English tail of the fallback chain is a query-shape guarantee, so the
     * test asserts the bound parameters: requested locale twice (override,
     * base) then the default locale for the fallback join.
     */
    @Test void bundleResolvesRequestedLocaleWithEnglishFallback() {
        stubKnownLocale();
        stubBundleRows(List.of(
                Map.entry("nav.group.sell", "Verkauf"),
                Map.entry("nav.module.forecast", "Forecast")));

        Map<String, String> bundle = service.bundle("DE");

        assertEquals("Verkauf", bundle.get("nav.group.sell"));
        assertEquals("Forecast", bundle.get("nav.module.forecast"));
        verify(jdbc).query(anyString(), any(RowMapper.class), eq("de"), isNull(), eq("de"), eq("en"));
    }

    /**
     * Anonymous callers (the login screen, before a token exists) must bind a
     * definite null tenant rather than leaning on an unset session variable.
     */
    @Test void anonymousCallerBindsNoTenant() {
        stubKnownLocale();
        stubBundleRows(List.of(Map.entry("shell.signOut", "Abmelden")));

        service.bundle("de");

        verify(jdbc).query(anyString(), any(RowMapper.class), eq("de"), isNull(), eq("de"), eq("en"));
    }

    @Test void authenticatedCallerBindsItsOwnTenantForOverrides() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(new TenantContext.Principal(tenantId, UUID.randomUUID(),
                "TENANT_ADMIN", "Admin User", "admin@example.com"));
        stubKnownLocale();
        stubBundleRows(List.of(Map.entry("nav.module.accounts", "Clients")));

        Map<String, String> bundle = service.bundle("en");

        assertEquals("Clients", bundle.get("nav.module.accounts"));
        verify(jdbc).query(anyString(), any(RowMapper.class),
                eq("en"), eq(tenantId.toString()), eq("en"), eq("en"));
    }

    @Test void localesQueriesActiveOnlyInSortOrder() {
        service.locales();
        verify(jdbc).query(org.mockito.ArgumentMatchers.contains("where active = true"),
                any(RowMapper.class));
    }

    @Test void phraseBundleMapsEnglishSourceToTenantAwareTranslation() {
        stubKnownLocale();
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn((List) List.of(
                        Map.entry("Accounts", "Kunden"),
                        Map.entry("Accounts", "Konten"),
                        Map.entry("Export PDF", "PDF exportieren")));

        Map<String, String> phrases = service.phraseBundle("DE");

        assertEquals("Kunden", phrases.get("Accounts"));
        assertEquals("PDF exportieren", phrases.get("Export PDF"));
        assertEquals(2, phrases.size());
        verify(jdbc).query(anyString(), any(RowMapper.class),
                eq("en"), eq("de"), isNull(), eq("de"));
    }

    private void stubKnownLocale() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenReturn(Boolean.TRUE);
    }

    @SuppressWarnings("unchecked")
    private void stubBundleRows(List<Map.Entry<String, String>> rows) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn((List<Object>) (List<?>) rows);
    }
}

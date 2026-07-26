package com.axiom.ui;

import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The resolution rules around the theme catalogue.
 *
 * <p>The SQL itself is verified against the live database — the catalogue reads
 * back five seeded rows and a preference survives a browser with its storage
 * cleared. What is pinned here is the DECISION LOGIC layered on top of it, which
 * is where a plausible-looking implementation goes wrong quietly: what happens
 * when a theme is retired under a user who had chosen it, what a refusal says,
 * and whether clearing a preference is distinguishable from never having had one.
 */
class ThemeServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private JdbcTemplate jdbc;
    private ThemeService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new ThemeService(jdbc);
        TenantContext.set(new TenantContext.Principal(TENANT, USER,
                "SALES_MANAGER", "Raj", "raj@example.test"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** The catalogue as the query would return it: two active themes. */
    @SuppressWarnings("unchecked")
    private void catalogueReturns(ThemeService.Theme... themes) {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(themes));
    }

    private void preferenceIs(String code) {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(TENANT), eq(USER)))
                .thenReturn(code == null ? List.of() : List.of(code));
    }

    private static ThemeService.Theme theme(String code, boolean isDefault) {
        return new ThemeService.Theme(code, code.toUpperCase(), "blurb",
                List.of("#000000", "#111111", "#222222"), "DARK", isDefault, 10);
    }

    @Test
    void withNoStoredChoiceTheEffectiveThemeIsTheCatalogueDefault() {
        catalogueReturns(theme("dark", true), theme("meridian", false));
        preferenceIs(null);

        ThemeService.ThemeState state = service.state();

        assertNull(state.selected(), "never chose, so nothing is selected");
        assertEquals("dark", state.defaultCode());
        assertEquals("dark", state.effective());
    }

    /**
     * The distinction that makes the nullable column worth having: "cleared" and
     * "never chose" both resolve to the default, so a user who clears their
     * choice starts following the product default again rather than being frozen
     * on whatever it was the day they cleared it.
     */
    @Test
    void aStoredChoiceWins() {
        catalogueReturns(theme("dark", true), theme("meridian", false));
        preferenceIs("meridian");

        ThemeService.ThemeState state = service.state();

        assertEquals("meridian", state.selected());
        assertEquals("meridian", state.effective());
        assertEquals("dark", state.defaultCode(), "the default is still reported");
    }

    /**
     * Retiring a theme has to actually retire it. A user sitting on the retired
     * theme resolves to the default — otherwise is_active means nothing for
     * exactly the people who were using it, which is everyone who matters.
     *
     * <p>And the stored row is deliberately NOT rewritten, so reactivating the
     * theme gives them their choice back.
     */
    @Test
    void aRetiredThemeFallsBackToTheDefaultWithoutErasingTheChoice() {
        catalogueReturns(theme("dark", true));   // meridian no longer active
        preferenceIs("meridian");

        ThemeService.ThemeState state = service.state();

        assertEquals("meridian", state.selected(), "the stored choice is reported as-is");
        assertEquals("dark", state.effective(), "but what gets painted is the default");
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    /**
     * is_default = false on every row is representable — the unique partial index
     * only stops TWO defaults, not zero. Sort order decides rather than returning
     * null and letting the client paint nothing.
     */
    @Test
    void aCatalogueWithNoDefaultFallsBackToTheFirstBySortOrder() {
        catalogueReturns(theme("meridian", false), theme("tron", false));
        preferenceIs(null);

        assertEquals("meridian", service.state().defaultCode());
    }

    /**
     * An empty catalogue is an unrun migration, not an empty picker. The message
     * has to say that, because the symptom the developer sees is a blank menu and
     * the cause is three layers away.
     */
    @Test
    void anEmptyCatalogueSaysTheMigrationIsMissing() {
        catalogueReturns();

        NotFoundException thrown = assertThrows(NotFoundException.class, () -> service.state());

        assertTrue(thrown.getMessage().contains("V336"),
                "name the migration: " + thrown.getMessage());
    }

    /**
     * The foreign key would also refuse this, but its message names a constraint.
     * This one names the themes that would have worked — and it catches the case
     * the FK cannot see, a theme that exists but is deactivated.
     */
    @Test
    void choosingAnUnknownThemeIsRefusedAndListsWhatIsAvailable() {
        catalogueReturns(theme("dark", true), theme("meridian", false));

        NotFoundException thrown = assertThrows(NotFoundException.class,
                () -> service.choose("cleanroom"));

        assertTrue(thrown.getMessage().contains("cleanroom"));
        assertTrue(thrown.getMessage().contains("dark"));
        assertTrue(thrown.getMessage().contains("meridian"),
                "list the valid codes: " + thrown.getMessage());
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    /**
     * Clearing is a first-class action, not a delete. Blank is treated as null so
     * a client that sends "" gets the same behaviour as one that sends null,
     * rather than tripping the unknown-theme refusal on an empty string.
     */
    @Test
    void clearingTheChoiceWritesNullAndSkipsCatalogueValidation() {
        catalogueReturns(theme("dark", true));
        preferenceIs(null);

        service.choose("   ");

        // Validation is skipped for a blank code, so no refusal, and the upsert
        // stores null rather than the empty string.
        verify(jdbc).update(anyString(), eq(TENANT), eq(USER), eq((String) null));
    }

    @Test
    void choosingTrimsTheCodeBeforeStoringIt() {
        catalogueReturns(theme("dark", true), theme("meridian", false));
        preferenceIs("meridian");

        service.choose(" meridian ");

        verify(jdbc).update(anyString(), eq(TENANT), eq(USER), eq("meridian"));
    }
}

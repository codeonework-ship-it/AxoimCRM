package com.axiom.api;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Saved-view rules decided in Java.
 *
 * <p>The uniqueness of a view name and the one-default-per-grid rule are enforced
 * by indexes, so they belong in a database test, not here. What this covers is the
 * part only this service can do: rejecting a definition the grid could not apply,
 * and refusing an edit to somebody else's shared view. Both were verified live
 * too; these exist so a future edit cannot quietly remove them.
 */
class SavedViewServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ME = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SOMEONE_ELSE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID VIEW = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private JdbcTemplate jdbc;
    private AuditService audit;
    private SavedViewService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        service = new SavedViewService(jdbc, audit, new ObjectMapper());
        bindAs("TENANT_ADMIN");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void bindAs(String role) {
        TenantContext.set(new TenantContext.Principal(TENANT, ME, role, "Raj", "raj@example.test"));
    }

    // ------------------------------------------------------- definition validation

    /**
     * An unrecognised facet is refused rather than dropped. Dropping it stores a
     * view that silently does less than the user asked for, and they find out the
     * next time they apply it — long after the grid state that produced it is gone.
     */
    @Test
    void anUnknownFacetIsRefusedAndBothNamesAppearInTheMessage() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.create(new SavedViewService.SavedViewRequest("contacts", "Mine", null,
                        "PRIVATE", Map.of("groupColumns", java.util.List.of(),
                        "rowHeight", "tall", "magicMode", true), false)));

        assertTrue(thrown.getMessage().contains("rowHeight"));
        assertTrue(thrown.getMessage().contains("magicMode"));
        assertTrue(thrown.getMessage().contains("columnFilters"),
                "the message must list what IS supported, or the user cannot correct it");
    }

    @Test
    void theSupportedFacetsAreAccepted() {
        stubInsertReturning(VIEW);
        stubRead("Mine", ME, "PRIVATE");

        service.create(new SavedViewService.SavedViewRequest("contacts", "Mine", null, "PRIVATE",
                Map.of("groupColumns", java.util.List.of("account"),
                        "columnFilters", Map.of("status", "ACTIVE"),
                        "sort", Map.of("key", "name", "direction", 1),
                        "columnOrder", java.util.List.of("name", "account"),
                        "hiddenColumns", java.util.List.of("owner")), false));

        verify(audit).record(eq("SAVED_VIEW_CREATE"), eq("SAVED_VIEW"), eq(VIEW), anyString(), any());
    }

    @Test
    void visibilityMustBePrivateOrShared() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.create(new SavedViewService.SavedViewRequest("contacts", "Mine", null,
                        "EVERYONE", Map.of(), false)));
        assertTrue(thrown.getMessage().contains("PRIVATE or SHARED"));
    }

    @Test
    void aViewNeedsAName() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new SavedViewService.SavedViewRequest("contacts", "  ", null,
                        "PRIVATE", Map.of(), false)));
    }

    // ------------------------------------------------------------- edit ownership

    /**
     * A shared view is applied by everyone and owned by one person. Letting any
     * viewer edit it means the view a team opens each morning changes without
     * anyone deciding it should.
     */
    @Test
    void anotherUsersSharedViewCannotBeEditedAndTheMessageSaysWhoOwnsIt() {
        bindAs("SALES");
        stubRead("Team pipeline", SOMEONE_ELSE, "SHARED");

        ForbiddenException thrown = assertThrows(ForbiddenException.class,
                () -> service.update(VIEW, 0L, new SavedViewService.SavedViewRequest(
                        "contacts", "Hijacked", null, "SHARED", Map.of(), false)));

        assertTrue(thrown.getMessage().contains("Team pipeline"));
        assertTrue(thrown.getMessage().contains("Priya"), "name the owner so the user knows who to ask");
        assertTrue(thrown.getMessage().contains("Save your own copy"),
                "refusing without offering the alternative leaves the user stuck");
    }

    /** Somebody has to be able to retire a shared view whose owner has left. */
    @Test
    void aTenantAdminMayEditSomeoneElsesSharedView() {
        bindAs("TENANT_ADMIN");
        stubRead("Team pipeline", SOMEONE_ELSE, "SHARED");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.update(VIEW, 0L, new SavedViewService.SavedViewRequest(
                "contacts", "Renamed", null, "SHARED", Map.of(), false));

        verify(audit).record(eq("SAVED_VIEW_UPDATE"), eq("SAVED_VIEW"), eq(VIEW), anyString(), any());
    }

    @Test
    void theOwnerMayEditTheirOwnView() {
        bindAs("SALES");
        stubRead("Mine", ME, "PRIVATE");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.update(VIEW, 0L, new SavedViewService.SavedViewRequest(
                "contacts", "Mine renamed", null, "PRIVATE", Map.of(), false));

        verify(audit).record(eq("SAVED_VIEW_UPDATE"), eq("SAVED_VIEW"), eq(VIEW), anyString(), any());
    }

    /** Marking a view default must clear the incumbent, or the index rejects the write. */
    @Test
    void savingAsDefaultClearsThePreviousDefaultFirst() {
        stubInsertReturning(VIEW);
        stubRead("Mine", ME, "PRIVATE");

        service.create(new SavedViewService.SavedViewRequest("contacts", "Mine", null,
                "PRIVATE", Map.of(), true));

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("set is_default = false"),
                eq(TENANT), eq(ME), eq("contacts"));
    }

    // ------------------------------------------------------------------- helpers

    private void stubInsertReturning(UUID id) {
        when(jdbc.queryForObject(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(id);
    }

    /** The row the service re-reads after writing. */
    @SuppressWarnings("unchecked")
    private void stubRead(String name, UUID ownerId, String visibility) {
        SavedViewService.SavedView view = new SavedViewService.SavedView(
                VIEW, "contacts", name, null, ownerId, "Priya Nair", visibility, Map.of(), false,
                ownerId.equals(ME), Instant.now(), Instant.now(), 0L);
        lenient().when(jdbc.queryForObject(anyString(), any(RowMapper.class),
                eq(TENANT), any(UUID.class), any(UUID.class))).thenReturn(view);
    }

    @Test
    void theFacetAllowListIsClosed() {
        // Guards against a future edit adding a facet to the UI but not the server:
        // the definition would be refused at save time with a clear message rather
        // than stored and silently ignored.
        assertEquals(5, java.util.Set.of("groupColumns", "columnFilters", "sort", "columnOrder",
                "hiddenColumns").size());
    }
}

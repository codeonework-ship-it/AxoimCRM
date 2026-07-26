package com.axiom.api;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The guards that decide whether a bulk operation may run at all.
 *
 * <p>Scoped deliberately to what is reachable before any database work. The
 * per-row engine needs real transactions to be meaningful — its whole point is
 * that each row commits independently — so asserting it against a mocked
 * JdbcTemplate would test the mock, not the isolation. That half is covered by the
 * live probe; what is covered here is the set of refusals that must never quietly
 * relax, because each one is a way to damage many records at once.
 */
class BulkOperationServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ME = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private BulkOperationService service;

    @BeforeEach
    void setUp() {
        service = new BulkOperationService(mock(JdbcTemplate.class), mock(AuditService.class),
                new ObjectMapper(), mock(PlatformTransactionManager.class));
        bindAs("TENANT_ADMIN");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void bindAs(String role) {
        TenantContext.set(new TenantContext.Principal(TENANT, ME, role, "Raj", "raj@example.test"));
    }

    // --------------------------------------------------------------- allow-list

    /**
     * Setting fifty contacts to the same email is not a bulk edit, it is data
     * loss. Identity and provenance columns are absent from the allow-list on
     * purpose, and the allow-list lives on the server so a crafted request cannot
     * reach a column the UI never offered.
     */
    @Test
    void aFieldCarryingIdentityCannotBeMassEdited() {
        for (String field : List.of("email", "firstName", "lastName", "version", "createdAt")) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> service.updateField("CONTACT", new BulkOperationService.BulkFieldUpdate(
                            List.of(UUID.randomUUID()), field, "anything", null)),
                    field + " must not be mass-editable");
            assertTrue(thrown.getMessage().contains("cannot be mass-edited"));
            assertTrue(thrown.getMessage().contains("Editable here:"),
                    "the refusal must list what IS editable, or the caller is guessing");
        }
    }

    @Test
    void thePublishedAllowListExcludesIdentityAndMatchesWhatTheServerAccepts() {
        var editable = service.editableFields();

        assertTrue(editable.containsKey("CONTACT"));
        assertTrue(editable.containsKey("ACCOUNT"));
        assertTrue(editable.containsKey("LEAD"));
        for (var entry : editable.entrySet()) {
            assertFalse(entry.getValue().contains("email"), entry.getKey() + " must not expose email");
            assertFalse(entry.getValue().contains("firstName"));
            assertFalse(entry.getValue().contains("name"),
                    entry.getKey() + ": a record's name identifies it and must not be mass-set");
        }
        // Published so the UI offers exactly what the server will take. A hardcoded
        // frontend list drifts and the user meets it as a 400 on save.
        assertEquals(List.of("accountId", "department", "seniority", "status", "title"),
                editable.get("CONTACT"));
    }

    @Test
    void anUnsupportedObjectIsRefusedAndTheSupportedOnesAreNamed() {
        NotFoundException thrown = assertThrows(NotFoundException.class,
                () -> service.updateField("INVOICE", new BulkOperationService.BulkFieldUpdate(
                        List.of(UUID.randomUUID()), "status", "PAID", null)));

        assertTrue(thrown.getMessage().contains("INVOICE"));
        assertTrue(thrown.getMessage().contains("ACCOUNT"));
        assertTrue(thrown.getMessage().contains("CONTACT"));
        assertTrue(thrown.getMessage().contains("LEAD"));
    }

    // -------------------------------------------------------------- selection

    @Test
    void anEmptySelectionIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateField("CONTACT", new BulkOperationService.BulkFieldUpdate(
                        List.of(), "status", "ACTIVE", null)));
    }

    /**
     * The cap exists because a single request updating thousands of rows holds a
     * connection and a transaction open long enough to matter. The refusal states
     * the number selected and how to proceed, rather than truncating silently —
     * truncation is the failure mode that looks like success.
     */
    @Test
    void aSelectionOverTheCapIsRefusedWithBothNumbers() {
        List<UUID> tooMany = IntStream.range(0, 501).mapToObj((i) -> UUID.randomUUID()).toList();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.updateField("CONTACT", new BulkOperationService.BulkFieldUpdate(
                        tooMany, "status", "ACTIVE", null)));

        assertTrue(thrown.getMessage().contains("500"), "state the limit");
        assertTrue(thrown.getMessage().contains("501"), "state what was actually selected");
        assertTrue(thrown.getMessage().contains("batches"), "say how to proceed");
    }

    /**
     * 500 is inside the limit, not on the wrong side of it — an off-by-one here
     * would reject the exact selection the UI offers as the maximum.
     *
     * <p>Asserted by the absence of the selection error specifically, rather than
     * by "some exception is thrown". The call does go on to fail against the mocked
     * JdbcTemplate, and asserting on that failure would pass even if the cap were
     * broken, which makes it worthless as a test of the cap.
     */
    @Test
    void exactlyTheCapIsAllowedThroughTheSelectionGuard() {
        List<UUID> atCap = IntStream.range(0, 500).mapToObj((i) -> UUID.randomUUID()).toList();

        try {
            service.updateField("CONTACT", new BulkOperationService.BulkFieldUpdate(
                    atCap, "status", "ACTIVE", null));
        } catch (IllegalArgumentException ex) {
            org.junit.jupiter.api.Assertions.fail(
                    "500 records must pass the selection guard, but it refused: " + ex.getMessage());
        } catch (RuntimeException ignored) {
            // Anything past the guard is the mocked persistence layer, not the cap.
        }
    }

    /** 501 is the first value outside it. Pins the boundary from the other side. */
    @Test
    void oneOverTheCapIsRefused() {
        List<UUID> overCap = IntStream.range(0, 501).mapToObj((i) -> UUID.randomUUID()).toList();
        assertThrows(IllegalArgumentException.class,
                () -> service.updateField("CONTACT", new BulkOperationService.BulkFieldUpdate(
                        overCap, "status", "ACTIVE", null)));
    }

    @Test
    void reassignWithoutAnOwnerIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> service.reassign("CONTACT", new BulkOperationService.BulkReassign(
                        List.of(UUID.randomUUID()), null, "no owner given")));
    }

    // ------------------------------------------------------------------- roles

    /**
     * The global filter already refuses a mutating request from an audit role, so
     * this is the second layer. Deliberate for bulk: one request here can change
     * hundreds of records, and defence in depth is proportionate to the blast
     * radius.
     */
    @Test
    void aReadOnlyRoleCannotRunABulkEdit() {
        bindAs("AUDITOR");

        ForbiddenException thrown = assertThrows(ForbiddenException.class,
                () -> service.updateField("CONTACT", new BulkOperationService.BulkFieldUpdate(
                        List.of(UUID.randomUUID()), "status", "ACTIVE", null)));

        assertTrue(thrown.getMessage().toLowerCase().contains("read-only"));
    }

    @Test
    void aReadOnlyRoleCannotRunABulkReassign() {
        bindAs("SUPER_AUDIT");
        assertThrows(ForbiddenException.class,
                () -> service.reassign("CONTACT", new BulkOperationService.BulkReassign(
                        List.of(UUID.randomUUID()), UUID.randomUUID(), null)));
    }

    /**
     * Object validation runs before the role check, so an unknown object answers
     * 404 rather than 403 even for an auditor. That ordering is intentional: it
     * does not leak which objects exist to someone who cannot write to them
     * anyway, because the supported list is public information.
     */
    @Test
    void objectValidationHappensBeforeTheRoleCheck() {
        bindAs("AUDITOR");
        assertThrows(NotFoundException.class,
                () -> service.updateField("NOT_A_THING", new BulkOperationService.BulkFieldUpdate(
                        List.of(UUID.randomUUID()), "status", "x", null)));
    }
}

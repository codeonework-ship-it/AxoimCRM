package com.axiom.accounts;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Contact authoring rules that are decided in Java, not in SQL.
 *
 * <p>Scoped deliberately. Optimistic locking is enforced by a {@code version}
 * predicate in an UPDATE and self-reporting by a CHECK constraint — a mocked
 * {@link JdbcTemplate} cannot execute either, so asserting them here would only
 * test the mock. What is tested is what this service actually decides: which
 * fields a clone carries, that a clone acknowledges its own duplicate, how a
 * cycle is detected before the database sees it, and that a manager with reports
 * cannot be deleted. Those are all reachable with a stubbed JdbcTemplate and all
 * of them are behaviour a future edit could silently break.
 */
class ContactServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONTACT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MANAGER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private JdbcTemplate jdbc;
    private AuditService audit;
    private DuplicateService duplicates;
    private ActorSession actor;
    private ContactService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        duplicates = mock(DuplicateService.class);
        actor = mock(ActorSession.class);
        service = new ContactService(jdbc, audit, duplicates, actor);
        TenantContext.set(new TenantContext.Principal(TENANT, USER, "TENANT_ADMIN", "Raj", "raj@example.test"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ clone

    /**
     * The email must not survive a clone. Copying it produces a record the
     * duplicate engine considers the same person — so the clone would either be
     * refused or, worse, create exactly the duplicate the guard exists to stop.
     */
    @Test
    void cloneDropsEmailAndProvenance() {
        stubExistingContact("Nadia", "Fernandes", "nadia@example.test", "ACME-CRM", "EXT-9");
        stubCreateReturning(UUID.randomUUID());
        when(duplicates.assess(any())).thenReturn(assessment(false, false));

        service.clone(CONTACT, null);

        // Arrays.stream, not List.of: the argument array is full of nulls — that
        // is the property under test — and List.of rejects them outright.
        List<Object> args = java.util.Arrays.stream(capturedInsertArgs())
                .filter(java.util.Objects::nonNull).toList();

        // Asserted by absence rather than by position. Indexing into the argument
        // array would encode the current column order into the test, so adding a
        // column to the INSERT would break it for a reason that has nothing to do
        // with cloning. "The source email is never passed" is the actual rule.
        assertFalse(args.contains("nadia@example.test"),
                "clone must not carry the source contact's email");
        assertFalse(args.contains("ACME-CRM"),
                "a clone did not come from the source system");
        assertFalse(args.contains("EXT-9"),
                "a clone has no external reference of its own");

        // The expensive-to-retype values are carried over.
        assertTrue(args.contains("Nadia"));
        assertTrue(args.contains("Fernandes"));
        assertTrue(args.contains("Head of Procurement"));
        assertTrue(args.contains("Procurement"));
    }

    /**
     * A clone is by definition a near-copy, so the fuzzy-name rule matches the
     * source every time. Without acknowledging, Clone returned 409 on the first
     * click for every contact — the feature was unusable.
     */
    @Test
    void cloneAcknowledgesItsOwnDuplicateAndRecordsTheDecision() {
        stubExistingContact("Nadia", "Fernandes", "nadia@example.test", null, null);
        UUID created = UUID.randomUUID();
        stubCreateReturning(created);
        when(duplicates.assess(any())).thenReturn(assessment(false, true));

        service.clone(CONTACT, null);

        // Acknowledged, so guard() did not throw and the decision was logged
        // rather than suppressed.
        verify(duplicates).recordDecision(eq("CONTACT"), eq(created), eq("CREATE"),
                eq("PROCEEDED"), any(), anyString());
        // The id is whatever the stubbed re-read returns, so it is not the
        // interesting part — that a CONTACT_CLONE event is written at all is.
        verify(audit).record(eq("CONTACT_CLONE"), eq("CONTACT"), any(UUID.class), anyString(), any());
    }

    // ---------------------------------------------------------- reporting line

    @Test
    void selfReportingIsRefusedWithProseNotAConstraintName() {
        stubExistingContact("Nadia", "Fernandes", "nadia@example.test", null, null);
        when(duplicates.assess(any())).thenReturn(assessment(false, false));

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> service.update(CONTACT, 0L, request(CONTACT)));

        assertTrue(thrown.getMessage().contains("cannot report to themselves"));
        assertFalse(thrown.getMessage().contains("check constraint"),
                "the API must not leak an internal constraint name");
        assertFalse(thrown.getMessage().contains("relation \""),
                "the API must not leak a table name");
    }

    /**
     * A single-row CHECK can only see the row being written, so A→B→A passes it.
     * This is the case the recursive walk exists for.
     */
    @Test
    void longerCycleIsRefusedAndNamesTheContactClosingTheLoop() {
        stubExistingContact("Nadia", "Fernandes", "nadia@example.test", null, null);
        when(duplicates.assess(any())).thenReturn(assessment(false, false));
        when(jdbc.queryForList(anyString(), eq(TENANT), eq(MANAGER), eq(TENANT), eq(CONTACT)))
                .thenReturn(List.of(Map.of("name", "Priya Nair")));

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> service.update(CONTACT, 0L, request(MANAGER)));

        assertTrue(thrown.getMessage().contains("circular"));
        assertTrue(thrown.getMessage().contains("Priya Nair"),
                "the message must name the contact that closes the loop");
    }

    @Test
    void anAcyclicReportingLineIsAllowedThrough() {
        stubExistingContact("Nadia", "Fernandes", "nadia@example.test", null, null);
        when(duplicates.assess(any())).thenReturn(assessment(false, false));
        when(jdbc.queryForList(anyString(), eq(TENANT), eq(MANAGER), eq(TENANT), eq(CONTACT)))
                .thenReturn(List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        // No ConflictException: an acyclic line must pass straight through to the
        // UPDATE. Asserting the absence of the throw is the whole point — a guard
        // that refuses everything would satisfy the two rejection tests above.
        service.update(CONTACT, 0L, request(MANAGER));

        verify(jdbc).queryForList(anyString(), eq(TENANT), eq(MANAGER), eq(TENANT), eq(CONTACT));
        verify(audit).record(eq("CONTACT_UPDATE"), eq("CONTACT"), eq(CONTACT), anyString(), any());
    }

    // ----------------------------------------------------------------- delete

    /**
     * Deleting a manager would leave every direct report pointing at a row that
     * no longer resolves, and the org chart would lose a branch silently.
     */
    @Test
    void deletingAManagerWithReportsIsRefusedAndStatesTheCount() {
        stubExistingContact("Nadia", "Fernandes", "nadia@example.test", null, null);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(TENANT), eq(CONTACT))).thenReturn(3L);

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> service.delete(CONTACT, "left the company"));

        assertTrue(thrown.getMessage().contains("3 other contact(s)"),
                "the message must say how much work clearing the way involves");
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void deletingAContactWithNoReportsSoftDeletesAndAudits() {
        stubExistingContact("Nadia", "Fernandes", "nadia@example.test", null, null);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(TENANT), eq(CONTACT))).thenReturn(0L);

        service.delete(CONTACT, "left the company");

        verify(audit).record(eq("CONTACT_DELETE"), eq("CONTACT"), eq(CONTACT), anyString(), any());
    }

    // ----------------------------------------------------------------- helpers

    private ContactService.ContactRequest request(UUID reportsTo) {
        return new ContactService.ContactRequest("Nadia", "Fernandes", null, null, null, null,
                reportsTo, null, "nadia@example.test", null, null, null, null, null, false, null);
    }

    @SuppressWarnings("unchecked")
    private void stubExistingContact(String first, String last, String email,
                                     String sourceSystem, String externalRef) {
        ContactService.ContactDetail detail = new ContactService.ContactDetail(
                CONTACT, null, null, first, last, "Head of Procurement", "Procurement", "DIRECTOR",
                null, null, USER, "Raj", email, "+91 22 5550 1188", null, "ACTIVE", false, null,
                sourceSystem, externalRef, null, java.time.Instant.now(), java.time.Instant.now(), 0L);
        // Any id, not just the source: create() ends with get(newId), and a stub
        // bound to the source id alone makes that re-read return null.
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(TENANT), any(UUID.class)))
                .thenReturn(detail);
    }

    private void stubCreateReturning(UUID id) {
        when(jdbc.queryForObject(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(id);
    }

    private Object[] capturedInsertArgs() {
        org.mockito.ArgumentCaptor<Object[]> captor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(anyString(), eq(UUID.class), captor.capture());
        return captor.getValue();
    }

    /**
     * A real Assessment, not a mock. It is a record — final, so Mockito cannot
     * mock it, and stubbing one inside the argument list of another {@code when()}
     * is what produced the UnfinishedStubbingException. Constructing the value
     * directly is both legal and clearer about what the fixture represents.
     */
    private DuplicateService.Assessment assessment(boolean blocked, boolean warned) {
        return new DuplicateService.Assessment(blocked, warned, 0d, List.of(), List.of(), List.of());
    }
}

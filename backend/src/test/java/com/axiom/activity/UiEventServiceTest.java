package com.axiom.activity;

import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The trust boundary on client-reported activity.
 *
 * <p>Everything asserted here is a property that stops a client from writing a
 * misleading audit row. That is the whole risk of accepting activity from the
 * browser: the log is evidence, and evidence a suspect can author is worthless.
 * The happy path — a screen view becoming a row — is verified against the live
 * database, where a real navigation in a real browser produced real rows.
 */
class UiEventServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private UserActivityService activity;
    private UiEventService service;

    @BeforeEach
    void setUp() {
        activity = mock(UserActivityService.class);
        service = new UiEventService(activity);
        TenantContext.set(new TenantContext.Principal(TENANT, USER,
                "SALES_MANAGER", "Raj", "raj@example.test"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private UserActivityService.ActivityEvent recordOne(UiEventService.UiEvent event) {
        service.record(List.of(event), "203.0.113.7", "Mozilla/5.0");
        ArgumentCaptor<UserActivityService.ActivityEvent> captor =
                ArgumentCaptor.forClass(UserActivityService.ActivityEvent.class);
        verify(activity).record(captor.capture());
        return captor.getValue();
    }

    /**
     * The identity on the row comes from the verified token, not the payload.
     * Without this, any authenticated user could write activity attributed to a
     * colleague — the log becomes a place to plant evidence rather than to find
     * it. Note the request record has no actor field at all, which is the
     * strongest form of this guarantee: it is unrepresentable, not merely
     * ignored.
     */
    @Test
    void theActorComesFromTheTokenAndTheRowCarriesNoHttpVerb() {
        UserActivityService.ActivityEvent row = recordOne(
                new UiEventService.UiEvent("SCREEN_VIEW", "/accounts", null, null, 100));

        assertEquals(TENANT, row.tenantId());
        assertEquals(USER, row.actorId());
        assertEquals("raj@example.test", row.actorEmail());
        assertEquals("UI", row.source());
        assertEquals(UserActivityService.SUCCESS, row.outcome());
        // No verb and no status: this was an observation, not a request. That is
        // how a reviewer tells client-reported rows from captured ones.
        assertNull(row.httpMethod());
        assertNull(row.statusCode());
    }

    /**
     * Query strings are stripped before storage. They carry filter values, and in
     * practice filter values carry personal data — FR-AUD-014, and the reason the
     * request_path column comment says "path only".
     */
    @Test
    void theQueryStringIsStrippedIncludingAnythingPersonalInside() {
        UserActivityService.ActivityEvent row = recordOne(new UiEventService.UiEvent(
                "SCREEN_VIEW", "/accounts?owner=raj&email=someone@example.com", null, null, 1));

        assertEquals("/accounts", row.requestPath());
        assertFalse(row.requestPath().contains("someone@example.com"));
    }

    @Test
    void theFragmentIsStrippedToo() {
        assertEquals("/reports", UiEventService.screenPath("/reports#section-3"));
    }

    /**
     * Record ids in the path collapse to {id} so the action aggregates. The
     * specific record is not lost — it travels in object_id, where it is indexed
     * and queryable.
     */
    @Test
    void recordIdsInThePathCollapseSoTheRouteAggregates() {
        UUID account = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");
        UserActivityService.ActivityEvent row = recordOne(new UiEventService.UiEvent(
                "RECORD_OPENED", "/accounts/" + account, "ACCOUNT", account, 5));

        assertEquals("/accounts/{id}", row.requestPath());
        assertEquals(account, row.objectId(), "the record is still recoverable");
        assertEquals("ACCOUNT", row.objectType());
    }

    /**
     * An action outside the closed vocabulary is dropped, and dropping it does not
     * fail the batch. Both halves matter: an open action column would let a
     * client's typo fragment a year of history, and rejecting the whole batch
     * would throw away the valid events beside the bad one — which is what a
     * client one release ahead of the server always sends.
     */
    @Test
    void anUnknownActionIsSkippedWithoutLosingTheRestOfTheBatch() {
        int accepted = service.record(List.of(
                new UiEventService.UiEvent("SCREEN_VIEW", "/leads", null, null, 1),
                new UiEventService.UiEvent("MOUSE_JIGGLED", "/leads", null, null, 1),
                new UiEventService.UiEvent("SIGN_OUT", "/leads", null, null, 1)),
                "127.0.0.1", "agent");

        assertEquals(2, accepted, "two valid, one dropped");
    }

    @Test
    void actionsAreNormalisedSoAClientNeedNotKnowThePrefix() {
        assertEquals("UI SCREEN_VIEW", UiEventService.normaliseAction("screen_view"));
        assertEquals("UI SCREEN_VIEW", UiEventService.normaliseAction("UI SCREEN_VIEW"));
        assertEquals("UI SIGN_OUT", UiEventService.normaliseAction("  sign_out  "));
        assertNull(UiEventService.normaliseAction("DROP TABLE"));
        assertNull(UiEventService.normaliseAction(""));
        assertNull(UiEventService.normaliseAction(null));
    }

    /**
     * A client cannot report a DENIED outcome. A denial is a claim about what a
     * server decided, and only the server that decided it can make that claim —
     * the filter records those at the point the refusal is issued.
     */
    @Test
    void aClientCannotClaimADenial() {
        UserActivityService.ActivityEvent row = recordOne(
                new UiEventService.UiEvent("SCREEN_VIEW", "/admin", null, null, 1));

        assertEquals(UserActivityService.SUCCESS, row.outcome());
        assertNull(row.denialReason());
    }

    /**
     * The client's idea of when the event happened arrives as durationMs in
     * detail, and nowhere else. occurred_at is the database's now(): an audit
     * trail whose ordering the subject can set is not an audit trail.
     */
    @Test
    void theClientClockLandsInDetailNotInTheTimestamp() {
        UserActivityService.ActivityEvent row = recordOne(
                new UiEventService.UiEvent("SCREEN_VIEW", "/leads", null, null, 4321));

        assertEquals(4321, row.detail().get("durationMs"));
        // There is no occurredAt on ActivityEvent to set — the column defaults to
        // now(). Asserting the absence of the field is the point.
        assertFalse(row.detail().containsKey("occurredAt"));
    }

    @Test
    void aNegativeClientAgeIsIgnoredRatherThanStored() {
        UserActivityService.ActivityEvent row = recordOne(
                new UiEventService.UiEvent("SCREEN_VIEW", "/leads", null, null, -900));

        assertFalse(row.detail().containsKey("durationMs"),
                "a negative age is a broken clock, not data");
    }

    @Test
    void anEmptyBatchWritesNothing() {
        assertEquals(0, service.record(List.of(), "127.0.0.1", "agent"));
        verify(activity, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aBlankScreenBecomesNullRatherThanAnEmptyPath() {
        assertNull(UiEventService.screenPath("   "));
        assertNull(UiEventService.screenPath(null));
        assertEquals("/", UiEventService.screenPath("/"));
    }

    @Test
    void aRelativeScreenIsNormalisedToAnAbsolutePath() {
        assertEquals("/accounts", UiEventService.screenPath("accounts"));
    }

    /** An absurdly long route is truncated rather than stored whole. */
    @Test
    void anOverlongScreenIsTruncated() {
        String long_ = "/" + "a".repeat(500);
        assertTrue(UiEventService.screenPath(long_).length() <= 301);
    }
}

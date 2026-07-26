package com.axiom.cpq;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Quote lifecycle rules.
 *
 * <p>The arithmetic is covered against the live database by the order-to-cash
 * probe, because pricing reads a real price book and a real product cost — a
 * mocked version would assert my own stubs. What is pinned here is the lifecycle:
 * which transitions exist, and the two refusals that stop a quote telling a lie
 * (accepting something never sent, and editing a document the customer holds).
 */
class QuoteAuthoringServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ME = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID QUOTE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private JdbcTemplate jdbc;
    private QuoteAuthoringService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new QuoteAuthoringService(jdbc, mock(AuditService.class));
        bindAs("SALES_MANAGER");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void bindAs(String role) {
        TenantContext.set(new TenantContext.Principal(TENANT, ME, role, "Raj", "raj@example.test"));
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * A quote goes to the customer before it can be agreed. Skipping SENT would
     * record an acceptance for a document nobody received.
     */
    @Test
    void aDraftQuoteCannotJumpStraightToAccepted() {
        stubQuote("DRAFT", true, 1);

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> service.transition(QUOTE, new QuoteAuthoringService.TransitionRequest("ACCEPTED", null)));

        assertTrue(thrown.getMessage().contains("cannot move to ACCEPTED"));
        assertTrue(thrown.getMessage().contains("SENT"),
                "the message must name SENT as the route: " + thrown.getMessage());
    }

    /**
     * The schema's own CHECK requires sent_at before ACCEPTED. This produces the
     * reason instead of a constraint name.
     */
    @Test
    void aQuoteWithNoSentDateCannotBeAccepted() {
        stubQuote("SENT", true, 1);
        // sent_at is absent
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(TENANT), eq(QUOTE))).thenReturn(0L);

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> service.transition(QUOTE, new QuoteAuthoringService.TransitionRequest("ACCEPTED", null)));

        assertTrue(thrown.getMessage().contains("never received"));
    }

    @Test
    void acceptedIsAFinalState() {
        stubQuote("ACCEPTED", true, 1);

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> service.transition(QUOTE, new QuoteAuthoringService.TransitionRequest("SENT", null)));

        assertTrue(thrown.getMessage().contains("final state"));
    }

    @Test
    void aQuoteWithNoLinesCannotBeSent() {
        stubQuote("DRAFT", true, 1, List.of());

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> service.transition(QUOTE, new QuoteAuthoringService.TransitionRequest("SENT", null)));

        assertTrue(thrown.getMessage().contains("offers nothing"));
    }

    @Test
    void rejectingRequiresAReasonSoTheNextRevisionCanAddressIt() {
        stubQuote("SENT", true, 1);

        assertThrows(IllegalArgumentException.class,
                () -> service.transition(QUOTE,
                        new QuoteAuthoringService.TransitionRequest("REJECTED", "  ")));
    }

    /**
     * A superseded revision is history. Acting on it would change a version that
     * has already been replaced, and the numbers on it are no longer the offer.
     */
    @Test
    void aSupersededRevisionIsFrozen() {
        stubQuote("SENT", false, 1);

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> service.transition(QUOTE, new QuoteAuthoringService.TransitionRequest("ACCEPTED", null)));

        assertTrue(thrown.getMessage().contains("superseded"));
        assertTrue(thrown.getMessage().contains("current version"));
    }

    @Test
    void movingToTheStatusItAlreadyHasIsRefusedClearly() {
        stubQuote("SENT", true, 1);

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> service.transition(QUOTE, new QuoteAuthoringService.TransitionRequest("SENT", null)));

        assertTrue(thrown.getMessage().contains("already SENT"));
    }

    // ------------------------------------------------------------ line editing

    /**
     * A SENT quote is in the customer's inbox and a quote in approval is being
     * judged on its numbers. Changing lines under either makes the document
     * somebody is looking at untrue.
     */
    @Test
    void linesAreFrozenOnceTheQuoteLeavesDraft() {
        for (String status : List.of("IN_APPROVAL", "SENT", "ACCEPTED", "REJECTED")) {
            stubQuote(status, true, 1);
            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> service.replaceLines(QUOTE, 0L, List.of(
                            new QuoteAuthoringService.LineRequest(UUID.randomUUID(), BigDecimal.ONE,
                                    null, null, null))),
                    status + " must freeze the lines");
            assertTrue(thrown.getMessage().contains("revision"),
                    status + ": name the supported alternative — " + thrown.getMessage());
        }
    }

    @Test
    void aQuoteNeedsAtLeastOneLine() {
        stubQuote("DRAFT", true, 1);

        assertThrows(IllegalArgumentException.class, () -> service.replaceLines(QUOTE, 0L, List.of()));
    }

    /**
     * A line without a product cannot be priced, measured or ordered: the product
     * is what carries the code, unit and cost everything else derives from.
     */
    @Test
    void aLineWithoutAProductIsRefused() {
        stubQuote("DRAFT", true, 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.replaceLines(QUOTE, 0L, List.of(
                        new QuoteAuthoringService.LineRequest(null, BigDecimal.ONE, null, null, null))));

        assertTrue(thrown.getMessage().contains("must name a product"));
    }

    @Test
    void aQuoteMustNameItsAccount() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(new QuoteAuthoringService.QuoteRequest(
                        null, null, null, null, "No account", "INR", null, null, null)));
    }

    @Test
    void aReadOnlyRoleCannotAuthorAQuote() {
        bindAs("AUDITOR");
        assertThrows(ForbiddenException.class,
                () -> service.create(new QuoteAuthoringService.QuoteRequest(
                        UUID.randomUUID(), null, null, null, "Nope", "INR", null, null, null)));
    }

    // -------------------------------------------------------------------- stubs

    private void stubQuote(String status, boolean activeVersion, int versionNumber) {
        stubQuote(status, activeVersion, versionNumber, List.of(Map.of()));
    }

    /**
     * `get()` reads the header through queryForList and the lines through query,
     * so both are stubbed. The line list only needs to be empty or non-empty for
     * these assertions, which is why its contents are not modelled.
     */
    @SuppressWarnings("unchecked")
    private void stubQuote(String status, boolean activeVersion, int versionNumber, List<?> lines) {
        Map<String, Object> header = new java.util.HashMap<>();
        header.put("id", QUOTE);
        header.put("quote_number", "AXQ-2026-0001");
        header.put("name", "Probe quote");
        header.put("version_number", versionNumber);
        header.put("is_active_version", activeVersion);
        header.put("status", status);
        header.put("approval_status", "NOT_REQUIRED");
        header.put("account_id", UUID.randomUUID());
        header.put("account_name", "Arcstone");
        header.put("opportunity_id", null);
        header.put("price_book_id", UUID.randomUUID());
        header.put("price_book_name", "Standard Price Book");
        header.put("currency_code", "INR");
        header.put("subtotal", new BigDecimal("1824.00"));
        header.put("discount_total", BigDecimal.ZERO);
        header.put("tax_total", BigDecimal.ZERO);
        header.put("grand_total", new BigDecimal("1824.00"));
        header.put("cost_total", new BigDecimal("540.00"));
        header.put("margin_amount", new BigDecimal("1284.00"));
        header.put("margin_pct", new BigDecimal("70.3947"));
        header.put("quote_discount_pct", BigDecimal.ZERO);
        header.put("valid_from", null);
        header.put("expires_at", null);
        header.put("reject_reason", null);
        header.put("version", 0L);

        lenient().when(jdbc.queryForList(anyString(), eq(TENANT), eq(QUOTE)))
                .thenReturn(List.of(header));
        lenient().when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                        eq(TENANT), eq(QUOTE)))
                .thenReturn((List) lines);
        // Default: the quote HAS been sent, so the sent_at guard is not the thing
        // under test unless a case overrides it.
        lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), eq(TENANT), eq(QUOTE)))
                .thenReturn(1L);
    }
}

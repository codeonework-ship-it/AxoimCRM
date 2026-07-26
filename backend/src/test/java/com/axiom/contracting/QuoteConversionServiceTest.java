package com.axiom.contracting;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Quote-to-order conversion guards.
 *
 * <p>Each of these prevents a specific way of booking revenue that was never
 * agreed: converting an offer the customer has not accepted, converting a
 * superseded revision whose prices were replaced, or converting the same
 * commitment twice. The happy path is proven end to end against the live database
 * by the probe, because it writes across four tables and a mocked version would
 * only assert my own stubs.
 */
class QuoteConversionServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ME = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID QUOTE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private JdbcTemplate jdbc;
    private QuoteConversionService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new QuoteConversionService(jdbc, mock(AuditService.class),
                mock(SalesOrderService.class));
        bindAs("SALES_MANAGER");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void bindAs(String role) {
        TenantContext.set(new TenantContext.Principal(TENANT, ME, role, "Raj", "raj@example.test"));
    }

    /** Converting before the customer agrees books revenue that does not exist. */
    @Test
    void onlyAnAcceptedQuoteMayBecomeAnOrder() {
        for (String status : List.of("DRAFT", "IN_APPROVAL", "SENT", "REJECTED", "EXPIRED")) {
            stubQuote(status, true);

            ConflictException thrown = assertThrows(ConflictException.class,
                    () -> service.convert(QUOTE, null), status + " must not be convertible");

            assertTrue(thrown.getMessage().contains(status));
            assertTrue(thrown.getMessage().contains("has not agreed"),
                    status + ": say why, not just no — " + thrown.getMessage());
        }
    }

    /**
     * A superseded revision's prices were replaced by a later version. Converting
     * it would put numbers on an order that no longer represent the offer.
     */
    @Test
    void aSupersededRevisionCannotBeConverted() {
        stubQuote("ACCEPTED", false);

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> service.convert(QUOTE, null));

        assertTrue(thrown.getMessage().contains("superseded"));
        assertTrue(thrown.getMessage().contains("prices that were replaced"));
    }

    /**
     * The unique index on source_quote_line_id can only answer "no". This check
     * names the order that already holds the conversion, which is what the person
     * clicking Convert actually needs to know.
     */
    @Test
    void convertingTwiceIsRefusedAndNamesTheExistingOrder() {
        stubQuote("ACCEPTED", true);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(TENANT), eq(QUOTE)))
                .thenReturn(List.of("SO-2026-00009"));

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> service.convert(QUOTE, null));

        assertTrue(thrown.getMessage().contains("SO-2026-00009"));
        assertTrue(thrown.getMessage().contains("twice"));
        assertTrue(thrown.getMessage().contains("Amend that order"),
                "offer the way forward: " + thrown.getMessage());
    }

    @Test
    void aQuoteWithNoLinesHasNothingToOrder() {
        stubQuote("ACCEPTED", true);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(TENANT), eq(QUOTE)))
                .thenReturn(List.of());
        // No lines on the quote.
        when(jdbc.queryForList(anyString(), eq(TENANT), eq(QUOTE)))
                .thenReturn(List.of(quoteRow("ACCEPTED", true)), List.of());

        ConflictException thrown = assertThrows(ConflictException.class,
                () -> service.convert(QUOTE, null));

        assertTrue(thrown.getMessage().contains("nothing to order"));
    }

    @Test
    void anUnknownQuoteIsANotFound() {
        when(jdbc.queryForList(anyString(), eq(TENANT), eq(QUOTE))).thenReturn(List.of());

        assertThrows(NotFoundException.class, () -> service.convert(QUOTE, null));
    }

    @Test
    void aReadOnlyRoleCannotConvert() {
        bindAs("AUDITOR");
        assertThrows(ForbiddenException.class, () -> service.convert(QUOTE, null));
    }

    // -------------------------------------------------------------------- stubs

    private void stubQuote(String status, boolean activeVersion) {
        lenient().when(jdbc.queryForList(anyString(), eq(TENANT), eq(QUOTE)))
                .thenReturn(List.of(quoteRow(status, activeVersion)));
        lenient().when(jdbc.queryForList(anyString(), eq(String.class), eq(TENANT), eq(QUOTE)))
                .thenReturn(List.of());
    }

    private Map<String, Object> quoteRow(String status, boolean activeVersion) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", QUOTE);
        row.put("quote_number", "AXQ-2026-0004");
        row.put("account_id", UUID.randomUUID());
        row.put("status", status);
        row.put("currency_code", "USD");
        row.put("is_active_version", activeVersion);
        row.put("corporate_grand_total", new java.math.BigDecimal("513000.00"));
        row.put("fx_rate", java.math.BigDecimal.ONE);
        row.put("fx_rate_date", null);
        return row;
    }
}

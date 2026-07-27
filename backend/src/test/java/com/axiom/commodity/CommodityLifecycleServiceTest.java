package com.axiom.commodity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommodityLifecycleServiceTest {
    @Test
    void offerGateFailsClosedForStaleCreditAndMissingAgreement() {
        var counterparty = new CommodityLifecycleService.Counterparty(UUID.randomUUID(), "CP-1", "Acme", "ACTIVE",
                "MISSING", null, null, new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"),
                "CTRM", Instant.now().minus(2, ChronoUnit.HOURS), Instant.now(), false);
        var enquiry = enquiry(counterparty, new BigDecimal("500"));
        var blockers = CommodityLifecycleService.gates(enquiry);
        assertTrue(blockers.stream().anyMatch(value -> value.contains("master agreement")));
        assertTrue(blockers.stream().anyMatch(value -> value.contains("older than 60 minutes")));
    }

    @Test
    void offerGateUsesReceivedHeadroomWithoutCalculatingIt() {
        var counterparty = new CommodityLifecycleService.Counterparty(UUID.randomUUID(), "CP-1", "Acme", "ACTIVE",
                "EXECUTED", "MSA-1", LocalDate.now().plusDays(30), new BigDecimal("1000"), new BigDecimal("50"),
                new BigDecimal("99"), "CTRM", Instant.now(), Instant.now(), true);
        var blockers = CommodityLifecycleService.gates(enquiry(counterparty, new BigDecimal("100")));
        assertEquals(1, blockers.size());
        assertTrue(blockers.getFirst().contains("headroom"));
    }

    @Test
    void offerGateRejectsFutureDatedCreditEvidence() {
        var counterparty = new CommodityLifecycleService.Counterparty(UUID.randomUUID(), "CP-1", "Acme", "ACTIVE",
                "EXECUTED", "MSA-1", LocalDate.now().plusDays(30), new BigDecimal("1000"), BigDecimal.ZERO,
                new BigDecimal("1000"), "CTRM", Instant.now().plus(5, ChronoUnit.MINUTES), Instant.now(), false);
        var blockers = CommodityLifecycleService.gates(enquiry(counterparty, new BigDecimal("100")));
        assertTrue(blockers.stream().anyMatch(value -> value.contains("future-dated")));
    }

    private static CommodityLifecycleService.Enquiry enquiry(CommodityLifecycleService.Counterparty counterparty,
                                                               BigDecimal notional) {
        return new CommodityLifecycleService.Enquiry(UUID.randomUUID(), "ENQ-1", "SPOT_CARGO", "Copper", "A",
                "PRICING", BigDecimal.ONE, "MT", BigDecimal.ZERO, notional, LocalDate.now(), LocalDate.now().plusDays(1),
                "Mumbai", "Chennai", "FOB", null, null, 1, "NOT_QUEUED", null, counterparty);
    }
}

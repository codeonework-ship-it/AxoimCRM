package com.axiom.access;

import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.PlatformSession;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The public intake guards. This endpoint is the only unauthenticated write in
 * the product, so each guard is pinned by a test that would fail loudly if
 * someone loosened it.
 */
class TrialRequestServiceTest {

    private JdbcTemplate jdbc;
    private TrialRequestService service;

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new TrialRequestService(jdbc, new PlatformSession(mock(JdbcTemplate.class)));
        when(jdbc.queryForObject(contains("nextval"), eq(Long.class))).thenReturn(1L);
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    private TrialRequestService.Submission submission(String email) {
        return new TrialRequestService.Submission("Halden Marine Works", email, "Ingrid Halvorsen",
                "Director of Operations", "251–1000", "Norway", "Evaluating pipeline governance");
    }

    // ---------------------------------------------------------------- free mail

    @Test void aFreeMailDomainIsRefusedWithAdviceAndNoRowIsWritten() {
        TrialRequestService.Decision decision = service.submit(submission("ingrid@gmail.com"), "203.0.113.7", "curl");

        assertEquals(400, decision.httpStatus());
        assertEquals("TRIAL_WORK_EMAIL_REQUIRED", decision.code());
        assertNull(decision.reference());
        assertTrue(decision.message().contains("work email address"), decision.message());
        assertTrue(decision.message().contains("gmail.com"), decision.message());
        verify(jdbc, never()).update(contains("insert into platform.trial_request\n"), any(Object[].class));
    }

    @Test void aDisposableMailboxIsDeclinedInItsOwnWords() {
        TrialRequestService.Decision decision =
                service.submit(submission("ingrid@mailinator.com"), "203.0.113.7", "curl");

        assertEquals("TRIAL_WORK_EMAIL_REQUIRED", decision.code());
        assertTrue(decision.message().contains("disposable mailbox"), decision.message());
    }

    // ---------------------------------------------------------------- validation

    @Test void aMalformedEmailIsRejectedBeforeAnythingElseHappens() {
        TrialRequestService.Decision decision =
                service.submit(submission("ingrid-at-haldenmarine"), "203.0.113.7", "curl");

        assertEquals(400, decision.httpStatus());
        assertEquals("TRIAL_VALIDATION_FAILED", decision.code());
        assertTrue(decision.message().contains("valid email address"), decision.message());
        assertNull(decision.reference());
    }

    @Test void aMissingCompanyNameIsRefusedInPlainWords() {
        TrialRequestService.Decision decision = service.submit(
                new TrialRequestService.Submission("  ", "ingrid@haldenmarine.example", "Ingrid Halvorsen",
                        null, null, null, null), "203.0.113.7", "curl");

        assertEquals("TRIAL_VALIDATION_FAILED", decision.code());
        assertTrue(decision.message().contains("company name"), decision.message());
    }

    @Test void anEmptyBodyIsRefusedRatherThanCrashing() {
        TrialRequestService.Decision decision = service.submit(
                new TrialRequestService.Submission(null, null, null, null, null, null, null), null, null);

        assertEquals(400, decision.httpStatus());
        assertEquals("TRIAL_VALIDATION_FAILED", decision.code());
    }

    @Test void notesLongerThanTheLimitAreRefused() {
        TrialRequestService.Decision decision = service.submit(
                new TrialRequestService.Submission("Halden Marine Works", "ingrid@haldenmarine.example",
                        "Ingrid Halvorsen", null, null, null, "x".repeat(2001)), "203.0.113.7", "curl");

        assertEquals("TRIAL_VALIDATION_FAILED", decision.code());
        assertTrue(decision.message().contains("2000 characters"), decision.message());
    }

    // ---------------------------------------------------------------- duplicates

    @Test void aDuplicatePendingRequestReturnsTheOriginalReferenceAndWritesNoSecondRow() {
        when(jdbc.queryForList(contains("from platform.trial_request"), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("TRL-2026-0001"));

        TrialRequestService.Decision decision =
                service.submit(submission("ingrid@haldenmarine.example"), "203.0.113.7", "curl");

        assertEquals("TRL-2026-0001", decision.reference());
        assertEquals(201, decision.httpStatus());
        assertNull(decision.code());
        // No new request row, and the reference sequence was never advanced.
        verify(jdbc, never()).update(contains("insert into platform.trial_request\n"), any(Object[].class));
        verify(jdbc, never()).queryForObject(contains("nextval"), eq(Long.class));
    }

    @Test void aDuplicateAnswersWithTheSameShapeAsAFirstSubmissionSoNothingLeaks() {
        TrialRequestService.Decision fresh =
                service.submit(submission("first@haldenmarine.example"), "203.0.113.7", "curl");

        when(jdbc.queryForList(contains("from platform.trial_request"), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("TRL-2026-0001"));
        TrialRequestService.Decision repeat =
                service.submit(submission("ingrid@haldenmarine.example"), "203.0.113.7", "curl");

        assertEquals(fresh.httpStatus(), repeat.httpStatus());
        assertEquals(fresh.status(), repeat.status());
        assertEquals(fresh.trialDays(), repeat.trialDays());
        assertEquals(fresh.code(), repeat.code());
        // Same wording, differing only by the reference each caller owns.
        assertEquals(fresh.message().replace(fresh.reference(), "X"),
                repeat.message().replace(repeat.reference(), "X"));
    }

    // ---------------------------------------------------------------- rate limits

    @Test void tooManyRequestsFromOneAddressAreRefusedPolitely() {
        when(jdbc.queryForObject(contains("source_ip = ?"), eq(Long.class), any(Object[].class)))
                .thenReturn((long) TrialRequestService.MAX_PER_IP_PER_HOUR);

        TrialRequestService.Decision decision =
                service.submit(submission("ingrid@haldenmarine.example"), "203.0.113.7", "curl");

        assertEquals(429, decision.httpStatus());
        assertEquals("TRIAL_RATE_LIMITED", decision.code());
        assertTrue(decision.message().contains("try again"), decision.message());
        assertTrue(decision.message().contains("email sales"), decision.message());
        verify(jdbc, never()).update(contains("insert into platform.trial_request\n"), any(Object[].class));
    }

    @Test void tooManyRequestsFromOneCompanyDomainAreRefusedPolitely() {
        when(jdbc.queryForObject(contains("email_domain = ?"), eq(Long.class), any(Object[].class)))
                .thenReturn((long) TrialRequestService.MAX_PER_DOMAIN_PER_DAY);

        TrialRequestService.Decision decision =
                service.submit(submission("ingrid@haldenmarine.example"), "203.0.113.7", "curl");

        assertEquals(429, decision.httpStatus());
        assertTrue(decision.message().contains("your organisation"), decision.message());
    }

    @Test void aRefusedSubmissionIsStillRecordedSoAbusePatternsAreVisible() {
        when(jdbc.queryForObject(contains("source_ip = ?"), eq(Long.class), any(Object[].class)))
                .thenReturn((long) TrialRequestService.MAX_PER_IP_PER_HOUR);

        service.submit(submission("ingrid@haldenmarine.example"), "203.0.113.7", "curl");

        verify(jdbc).update(contains("insert into platform.trial_request_event"), any(Object[].class));
    }

    // ---------------------------------------------------------------- references

    @Test void referencesAreSequentialAndUnique() {
        assertEquals("TRL-2026-0001", TrialRequestService.formatReference(2026, 1));
        assertEquals("TRL-2026-0002", TrialRequestService.formatReference(2026, 2));
        assertEquals("TRL-2026-0042", TrialRequestService.formatReference(2026, 42));
        assertEquals("TRL-2027-0001", TrialRequestService.formatReference(2027, 1));
        assertNotEquals(TrialRequestService.formatReference(2026, 1),
                TrialRequestService.formatReference(2026, 2));
        // Beyond four digits it grows rather than wrapping, so it never collides.
        assertEquals("TRL-2026-12345", TrialRequestService.formatReference(2026, 12345));
    }

    @Test void theReferenceComesFromASequenceNotAMaxPlusOneRead() {
        when(jdbc.queryForObject(contains("nextval"), eq(Long.class))).thenReturn(7L);

        TrialRequestService.Decision decision =
                service.submit(submission("ingrid@haldenmarine.example"), "203.0.113.7", "curl");

        assertTrue(decision.reference().endsWith("-0007"), decision.reference());
        verify(jdbc).update(contains("insert into platform.trial_request\n"), any(Object[].class));
    }

    @Test void anAcceptedSubmissionQuotesItsReferenceBackToTheRequester() {
        TrialRequestService.Decision decision =
                service.submit(submission("ingrid@haldenmarine.example"), "203.0.113.7", "curl");

        assertEquals(201, decision.httpStatus());
        assertEquals("PENDING", decision.status());
        assertEquals(TrialRequestService.TRIAL_DAYS, decision.trialDays());
        assertTrue(decision.message().contains(decision.reference()), decision.message());
    }

    // ---------------------------------------------------------------- review actions

    @Test void rejectingWithoutAReasonIsRefused() {
        platformOperator();
        assertThrows(IllegalArgumentException.class, () -> service.reject(UUID.randomUUID(), "   "));
        assertThrows(IllegalArgumentException.class, () -> service.reject(UUID.randomUUID(), null));
    }

    @Test void aTenantAdministratorCannotReviewTrialRequests() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "TENANT_ADMIN", "Tenant Admin", "admin@meridianfab.com"));
        assertThrows(ForbiddenException.class, () -> service.list(null));
        assertThrows(ForbiddenException.class, () -> service.reject(UUID.randomUUID(), "Not a fit"));
    }

    @Test void aReadOnlyPlatformRoleCannotRejectOrExpire() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SUPER_AUDIT", "Platform Auditor", "audit@axiomcrm.com"));
        assertThrows(ForbiddenException.class, () -> service.reject(UUID.randomUUID(), "Not a fit"));
        assertThrows(ForbiddenException.class, () -> service.expireStale());
    }

    @Test void expirySweepTransitionsOnlyPendingRequestsAndRecordsEachOne() {
        platformOperator();
        UUID id = UUID.randomUUID();
        when(jdbc.query(contains("status = 'PENDING'"), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class)))
                .thenReturn(List.of(new TrialRequestService.TrialRequestRow(id, "TRL-2026-0001",
                        "Halden Marine Works", "ingrid@haldenmarine.example", "haldenmarine.example",
                        "Ingrid Halvorsen", null, null, null, null, "PENDING", 30, null, null, null,
                        null, null, null, "203.0.113.7")));

        assertEquals(1, service.expireStale());
        verify(jdbc).update(contains("set status = 'EXPIRED'"), any(Object[].class));
        verify(jdbc).update(contains("insert into platform.trial_request_event"), any(Object[].class));
    }

    @Test void theOpenStatusesAreTheOnesAReviewerStillOwns() {
        assertTrue(TrialRequestService.isOpen("PENDING"));
        assertTrue(TrialRequestService.isOpen("APPROVED"));
        org.junit.jupiter.api.Assertions.assertFalse(TrialRequestService.isOpen("PROVISIONED"));
        org.junit.jupiter.api.Assertions.assertFalse(TrialRequestService.isOpen("REJECTED"));
        org.junit.jupiter.api.Assertions.assertFalse(TrialRequestService.isOpen("EXPIRED"));
    }

    @Test void theIntakePathBindsItsOwnSessionFlagRatherThanRunningUnbound() {
        service.submit(submission("ingrid@haldenmarine.example"), "203.0.113.7", "curl");
        verify(jdbc).query(contains("app.trial_intake"),
                any(org.springframework.jdbc.core.ResultSetExtractor.class));
    }

    @Test void aDomainIsTakenFromTheLastAtSignSoPlusAddressingCannotSpoofIt() {
        assertEquals("haldenmarine.example",
                TrialRequestService.domainOf("ingrid+gmail.com@haldenmarine.example"));
        assertNull(TrialRequestService.domainOf("nope"));
        assertNull(TrialRequestService.domainOf(null));
    }

    private void platformOperator() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SUPER_ADMIN", "Platform Operator", "ops@axiomcrm.com"));
    }
}

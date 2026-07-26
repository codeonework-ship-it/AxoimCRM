package com.axiom.activity;

import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * FR-AUD-014: "logs and metrics must never contain credentials, tokens or
 * unmasked personal data."
 *
 * <p>These tests assert <b>absence</b>. A test that checks the log contains the
 * right things would still pass while it also contained a bearer token, which is
 * the failure mode that matters.
 */
class UserActivityServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private JdbcTemplate jdbc;
    private UserActivityService service;

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new UserActivityService(jdbc, new ObjectMapper());
        TenantContext.set(new TenantContext.Principal(TENANT, ACTOR, "TENANT_ADMIN",
                "Raj Malhotra", "raj.malhotra@meridianfab.com"));
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
        service.clearPendingDenial();
    }

    private UserActivityService.ActivityEvent event(String outcome, Map<String, ?> detail) {
        return new UserActivityService.ActivityEvent(
                TENANT, ACTOR, "raj.malhotra@meridianfab.com", "TENANT_ADMIN", null, null,
                "GET /api/v1/accounts", "GET", "/api/v1/accounts", "ACCOUNT",
                UUID.randomUUID(), "UI", outcome, outcome.equals("DENIED") ? 403 : 200,
                outcome.equals("DENIED") ? "Refused by object permission" : null,
                "corr-1", "203.0.113.9", "Mozilla/5.0", detail);
    }

    /** The insert's positional arguments, so absence can be asserted across all of them. */
    private Object[] capturedInsert() {
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), args.capture(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture(), args.capture(), args.capture(),
                args.capture());
        return args.getAllValues().toArray();
    }

    private static String joined(Object[] args) {
        StringBuilder out = new StringBuilder();
                for (Object arg : args) out.append(arg).append((char) 0);
        return out.toString();
    }

    // ------------------------------------------------------ denials are recorded

    @Test void aDeniedAccessAttemptIsWrittenToActivity() {
        service.record(event(UserActivityService.DENIED, Map.of(
                "denialReason", "Refused by object permission",
                "permission", "ACCOUNT.READ")));

        Object[] args = capturedInsert();
        String all = joined(args);
        assertTrue(all.contains(UserActivityService.DENIED),
                "a refused permission check is exactly the event a security review needs");
        assertTrue(all.contains("ACCOUNT.READ"));
        assertTrue(all.contains("403"));
    }

    @Test void aSuccessIsAlsoRecordedSoDenialsAreComparable() {
        service.record(event(UserActivityService.SUCCESS, Map.of()));
        assertTrue(joined(capturedInsert()).contains(UserActivityService.SUCCESS));
    }

    @Test void anEventWithNoVerifiedTenantIsNotWritten() {
        service.record(new UserActivityService.ActivityEvent(
                null, null, null, null, null, null, "GET /api/v1/accounts", "GET",
                "/api/v1/accounts", null, null, "API", UserActivityService.DENIED, 401,
                "no token", null, "203.0.113.9", null, Map.of()));
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    // ------------------------------------------------------------- FR-AUD-014

    @Test void aTokenIsAbsentFromTheRecordedActivity() {
        Map<String, Object> hostile = new LinkedHashMap<>();
        hostile.put("permission", "ACCOUNT.READ");
        hostile.put("token", "eyJhbGciOiJIUzI1NiJ9.SHOULD_NEVER_BE_LOGGED.signature");
        hostile.put("authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.SHOULD_NEVER_BE_LOGGED");
        hostile.put("password", "axiom-demo");
        hostile.put("clientSecret", "s3cr3t-value");

        service.record(event(UserActivityService.DENIED, hostile));

        String all = joined(capturedInsert());
        assertFalse(all.contains("SHOULD_NEVER_BE_LOGGED"), "a token reached the activity log");
        assertFalse(all.contains("axiom-demo"), "a password reached the activity log");
        assertFalse(all.contains("s3cr3t-value"), "a client secret reached the activity log");
        assertTrue(all.contains("ACCOUNT.READ"), "the allowlisted key must survive");
    }

    @Test void anUnmaskedSensitiveFieldIsAbsentFromTheRecordedActivity() {
        Map<String, Object> hostile = new LinkedHashMap<>();
        hostile.put("objectType", "ACCOUNT");
        hostile.put("taxId", "GSTIN27AAECS1234F1Z5");
        hostile.put("email", "priya.nair@meridianfab.com");
        hostile.put("annualRevenue", 42_000_000);

        service.record(event(UserActivityService.SUCCESS, hostile));

        String all = joined(capturedInsert());
        assertFalse(all.contains("GSTIN27AAECS1234F1Z5"), "a masked field's raw value reached the log");
        assertFalse(all.contains("priya.nair@meridianfab.com"),
                "a record subject's email reached the log");
        assertFalse(all.contains("42000000"));
        assertTrue(all.contains("ACCOUNT"));
    }

    @Test void sanitiseKeepsOnlyAllowlistedScalarKeys() {
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("permission", "LEAD.EDIT");
        mixed.put("resultCount", 12);
        mixed.put("secret", "nope");
        mixed.put("objectType", Map.of("nested", "container"));   // container: dropped
        mixed.put("cause", null);                                  // null: dropped

        Map<String, Object> clean = service.sanitise(mixed);

        assertEquals(Map.of("permission", "LEAD.EDIT", "resultCount", 12), clean);
        assertFalse(clean.containsKey("secret"));
        assertFalse(clean.containsKey("objectType"));
        assertFalse(clean.containsKey("cause"));
    }

    @Test void everyAllowlistedKeyIsScalarOnly() {
        for (String key : UserActivityService.ALLOWED_DETAIL_KEYS) {
            Map<String, Object> nested = Map.of(key, Map.of("hidden", "payload"));
            assertTrue(service.sanitise(nested).isEmpty(),
                    key + " accepted a container, which is how personal data arrives unnoticed");
        }
    }

    @Test void theQueryStringIsStrippedFromTheRecordedPath() {
        service.record(new UserActivityService.ActivityEvent(
                TENANT, ACTOR, "raj@example.com", "TENANT_ADMIN", null, null,
                "GET /api/v1/accounts", "GET",
                "/api/v1/accounts?search=priya.nair%40meridianfab.com&taxId=GSTIN27AAECS1234F1Z5",
                "ACCOUNT", null, "UI", UserActivityService.SUCCESS, 200, null,
                "corr-1", "203.0.113.9", "Mozilla/5.0", Map.of()));

        String all = joined(capturedInsert());
        assertFalse(all.contains("priya.nair"), "a query string carried personal data into the log");
        assertFalse(all.contains("GSTIN27AAECS1234F1Z5"));
        assertTrue(all.contains("/api/v1/accounts"));
    }

    // ------------------------------------------------- pending-denial handoff

    @Test void aPendingDenialIsHandedOverExactlyOnce() {
        UUID target = UUID.randomUUID();
        service.markDenied("Refused: last TENANT_ADMIN", "USER", target);

        UserActivityService.PendingDenial first = service.takePendingDenial();
        assertEquals("Refused: last TENANT_ADMIN", first.reason());
        assertEquals("USER", first.objectType());
        assertEquals(target, first.objectId());

        assertNull(service.takePendingDenial(),
                "a denial left on the thread would be attributed to the next request");
    }

    // -------------------------------------------------------------- read gate

    @Test void aSalesUserCannotReadTheActivityLog() {
        TenantContext.set(new TenantContext.Principal(TENANT, ACTOR, "SALES", "Priya", "p@example.com"));
        assertThrows(ForbiddenException.class, () -> service.search(
                new UserActivityService.ActivityQuery(null, null, null, null, null, null, 10)));
    }

    @Test void anAuditorCanReadTheActivityLog() {
        TenantContext.set(new TenantContext.Principal(TENANT, ACTOR, "AUDITOR", "Asha", "a@example.com"));
        service.search(new UserActivityService.ActivityQuery(null, null, null, null, null, null, 10));
        // No throw: an activity log the auditor cannot read is not a control.
    }

    // -------------------------------------------------------- outcome mapping

    @Test void statusCodesMapToOutcomes() {
        assertEquals(UserActivityService.SUCCESS, UserActivityFilter.outcomeOf(200));
        assertEquals(UserActivityService.SUCCESS, UserActivityFilter.outcomeOf(302));
        assertEquals(UserActivityService.DENIED, UserActivityFilter.outcomeOf(401));
        assertEquals(UserActivityService.DENIED, UserActivityFilter.outcomeOf(403));
        assertEquals(UserActivityService.ERROR, UserActivityFilter.outcomeOf(500));
    }

    @Test void actionNamesCollapseIdentifiersSoTheyAggregate() {
        assertEquals("GET /api/v1/security/rbac/roles/{id}",
                UserActivityFilter.actionOf("get", "/api/v1/security/rbac/roles/"
                        + "11111111-1111-1111-1111-111111111111"));
        assertEquals("POST /api/v1/security/rbac/users/active",
                UserActivityFilter.actionOf("POST", "/api/v1/security/rbac/users/active"));
    }
}

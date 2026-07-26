package com.axiom.security;

import com.axiom.activity.UserActivityService;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-SEC-013: "For any user–record pair, an authorized administrator must be
 * able to see exactly why access is or is not granted, enumerating every rule
 * that contributes."
 *
 * <p>Two properties are asserted, and the second is the one that is usually
 * missing from an implementation: every layer is <b>enumerated</b> even when it
 * contributes nothing (otherwise the reader cannot tell an evaluated-and-silent
 * layer from an unevaluated one), and the <b>negative case has a reason</b>
 * ("no access" alone is not something an administrator can act on).
 */
class AccessExplainerServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CALLER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SUBJECT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OWNER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID RECORD = UUID.fromString("55555555-5555-5555-5555-555555555555");

    /** Every cause value {@code security.record_share.cause} permits. */
    private static final Set<String> SHARE_CAUSES =
            Set.of("owner", "role_hierarchy", "sharing_rule", "team", "territory", "manual");

    private JdbcTemplate jdbc;
    private AuthorizationService authorization;
    private PermissionResolver resolver;
    private UserActivityService activity;
    private AccessExplainerService explainer;

    private UUID recordOwner = OWNER;
    private String subjectRolePath = "/EXEC/SALES_LEADERSHIP/APAC_WEST/";
    private String ownerRolePath = "/EXEC/SALES_LEADERSHIP/APAC_EAST/";
    private boolean recordExists = true;
    private boolean predicateMatches = false;
    private List<String> territories = List.of();
    private List<AccessExplainerService.MaterializedShare> shares = List.of();

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        authorization = mock(AuthorizationService.class);
        resolver = mock(PermissionResolver.class);
        activity = mock(UserActivityService.class);
        explainer = new AccessExplainerService(jdbc, authorization, resolver, activity);

        TenantContext.set(new TenantContext.Principal(TENANT, CALLER, "TENANT_ADMIN",
                "Raj Malhotra", "raj.malhotra@meridianfab.com"));

        when(resolver.forUser(SUBJECT)).thenAnswer(inv -> subjectContext());
        when(authorization.recordExists(any(), any())).thenAnswer(inv -> recordExists);
        when(authorization.orgWideDefault(any(), any()))
                .thenReturn(new AuthorizationService.OrgWideDefault("private", true));
        when(authorization.applicableRules(any(), any())).thenReturn(List.of());
        when(authorization.visibleRecordPredicateFor(any(), any(), anyString(), any()))
                .thenReturn(new AuthorizationService.RecordPredicate("t.owner_id = ?", List.of(SUBJECT)));

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(this::extractorQuery);
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenAnswer(inv -> territories);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenAnswer(inv -> predicateMatches);
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    // --------------------------------------------------------------- fixtures

    private AccessContext subjectContext() {
        return new AccessContext(TENANT, SUBJECT, "SALES", false, false,
                UUID.randomUUID(), "SALES", 2000, List.of(), Set.of("ACCOUNT.READ"),
                Map.of(SecurableObject.ACCOUNT, new AccessContext.ObjectAccess(
                        true, true, true, false, false, false, false, false)),
                Map.of(SecurableObject.ACCOUNT, Set.of("industry")),
                Map.of(), UUID.randomUUID(), "APAC_WEST", subjectRolePath, List.of(), List.of());
    }

    private Object extractorQuery(org.mockito.invocation.InvocationOnMock inv) throws Throwable {
        String sql = inv.getArgument(0);
        ResultSetExtractor<?> extractor = inv.getArgument(1);
        if (sql.contains("security.user_role_assignment")) return ownerRolePath;
        if (sql.contains("select email from identity.app_user")) return "sanjay.iyer@meridianfab.com";
        if (sql.contains("select owner_id from crm.account")) return recordOwner;
        // record_team_member and the manual-share lookup run their real lambda
        // against an empty result set, so the "nothing here" branch is the code
        // under test rather than a stub of it.
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        return extractor.extractData(empty);
    }

    private AccessExplainerService.AccessExplanation explain() {
        // The subject lookup and the materialized-share lookup share a JdbcTemplate
        // overload, so they are routed by SQL here rather than by argument order.
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("from identity.app_user")) {
                return List.of(subjectRow());
            }
            if (sql.contains("from security.record_share")) {
                return shares;
            }
            return List.of();
        });
        return explainer.explain(SUBJECT, "ACCOUNT", RECORD);
    }

    private AccessExplainerService.Subject subjectRow() {
        return new AccessExplainerService.Subject(
                SUBJECT, "priya.nair@meridianfab.com", "Priya Nair", "SALES");
    }

    // ------------------------------------------------------------------ tests

    @Test void everyCauseInTheRecordShareVocabularyIsEnumerated() {
        AccessExplainerService.AccessExplanation explanation = explain();

        Set<String> reported = explanation.causes().stream()
                .map(AccessExplainerService.AccessCause::cause)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        for (String cause : SHARE_CAUSES) {
            assertTrue(reported.contains(cause),
                    "the explainer must enumerate the '" + cause + "' layer even when it grants "
                            + "nothing — a silent layer is indistinguishable from an unevaluated one. "
                            + "Reported: " + reported);
        }
    }

    @Test void everyLayerCarriesAVerdictAndAHumanReadableDetail() {
        AccessExplainerService.AccessExplanation explanation = explain();

        assertFalse(explanation.causes().isEmpty());
        for (AccessExplainerService.AccessCause cause : explanation.causes()) {
            assertTrue(Set.of("GRANT", "DENY", "NOT_APPLICABLE").contains(cause.verdict()),
                    cause.layer() + " has no verdict");
            assertNotNull(cause.detail(), cause.layer() + " has no explanation");
            assertFalse(cause.detail().isBlank(), cause.layer() + " has a blank explanation");
        }
    }

    @Test void theDenialReasonIsReturnedWhenAccessIsAbsent() {
        predicateMatches = false;

        AccessExplainerService.AccessExplanation explanation = explain();

        assertEquals("NO_ACCESS", explanation.verdict());
        assertFalse(explanation.canRead());
        assertNotNull(explanation.denialReason(), "FR-SEC-013 requires the negative case be explained");
        assertTrue(explanation.denialReason().contains("priya.nair@meridianfab.com"));
        assertTrue(explanation.denialReason().contains("Org-wide default"),
                "the private default is the first reason access is absent: " + explanation.denialReason());
        assertTrue(explanation.denialReason().contains("Role hierarchy"),
                "each declining layer must appear in the reason: " + explanation.denialReason());
    }

    @Test void siblingRolesDoNotSeeEachOtherAndTheExplainerSaysSo() {
        AccessExplainerService.AccessExplanation explanation = explain();

        AccessExplainerService.AccessCause roleLayer = layer(explanation, "role_hierarchy");
        assertEquals("DENY", roleLayer.verdict());
        assertTrue(roleLayer.detail().contains("not beneath"), roleLayer.detail());
        assertTrue(roleLayer.detail().toLowerCase(Locale.ROOT).contains("upward only"),
                roleLayer.detail());
    }

    @Test void ownershipIsReportedAsTheOwnerCauseAndGrantsReadWrite() {
        recordOwner = SUBJECT;
        predicateMatches = true;

        AccessExplainerService.AccessExplanation explanation = explain();

        AccessExplainerService.AccessCause owner = layer(explanation, "owner");
        assertEquals("GRANT", owner.verdict());
        assertEquals("READ_WRITE", owner.accessLevel());
        assertEquals("READ_WRITE", explanation.verdict());
        assertNull(explanation.denialReason());
    }

    @Test void aRollUpFromASubordinateRoleIsReportedAsRoleHierarchy() {
        ownerRolePath = "/EXEC/SALES_LEADERSHIP/APAC_WEST/DEEPER/";
        predicateMatches = true;

        AccessExplainerService.AccessCause role = layer(explain(), "role_hierarchy");

        assertEquals("GRANT", role.verdict());
        assertEquals("READ", role.accessLevel());
        assertTrue(role.detail().contains("rolls up"), role.detail());
    }

    @Test void aMissingRecordIsExplainedRatherThanReportedAsADenial() {
        recordExists = false;

        AccessExplainerService.AccessExplanation explanation = explain();

        assertEquals("NO_SUCH_RECORD", explanation.verdict());
        assertFalse(explanation.recordExists());
        assertTrue(explanation.denialReason().contains("no account with that id exists")
                        || explanation.denialReason().contains("No account with that id exists"),
                explanation.denialReason());
    }

    @Test void materializedSharesAreReportedWithTheirStoredCause() {
        shares = List.of(new AccessExplainerService.MaterializedShare(
                UUID.randomUUID(), "sharing_rule", "APAC_DEALS", "criteria region=APAC",
                "READ_WRITE", null, java.time.Instant.now()));
        predicateMatches = true;

        AccessExplainerService.AccessExplanation explanation = explain();

        assertEquals(1, explanation.materializedShares().size());
        assertEquals("sharing_rule", explanation.materializedShares().get(0).cause());
        assertEquals("APAC_DEALS", explanation.materializedShares().get(0).causeRef());
        assertTrue(explanation.materializationMatchesLiveEvaluation());
    }

    @Test void fieldLevelSecurityIsReportedAsPartOfTheExplanation() {
        AccessExplainerService.AccessExplanation explanation = explain();

        assertEquals(List.of("industry"), explanation.unreadableFields());
        assertTrue(explanation.causes().stream().anyMatch(c ->
                        "Field-level security".equals(c.layer()) && c.detail().contains("absent, not null")),
                "the explainer must say which fields disappear, and why");
    }

    @Test void explainingAccessIsItselfRecordedAsActivity() {
        explain();
        verify(activity).record(any());
    }

    @Test void theExplainerRequiresItsOwnPermission() {
        org.mockito.Mockito.doThrow(new ForbiddenException("Explain access is required"))
                .when(authorization).requirePermission(eq("SYS.VIEW_ACCESS_EXPLAINER"), anyString());

        assertThrows(ForbiddenException.class, () -> explainer.explain(SUBJECT, "ACCOUNT", RECORD));
    }

    @Test void aSalesUserCannotOpenTheExplainer() {
        TenantContext.set(new TenantContext.Principal(TENANT, CALLER, "SALES", "Priya", "p@example.com"));
        assertThrows(ForbiddenException.class, () -> explainer.explain(SUBJECT, "ACCOUNT", RECORD));
    }

    private AccessExplainerService.AccessCause layer(
            AccessExplainerService.AccessExplanation explanation, String cause) {
        return explanation.causes().stream()
                .filter(c -> cause.equals(c.cause()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cause '" + cause + "' in the explanation"));
    }
}

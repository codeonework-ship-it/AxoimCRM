package com.axiom.migration;

import com.axiom.migration.MigrationModel.Issue;
import com.axiom.migration.MigrationModel.ObjectOutcome;
import com.axiom.migration.MigrationModel.ObjectPlan;
import com.axiom.migration.MigrationModel.PlanContext;
import com.axiom.migration.MigrationModel.PreFlightReport;
import com.axiom.migration.SourceContract.SourceField;
import com.axiom.migration.SourceContract.SourceSession;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * The dry run (FR-MIG-003) and the properties it is supposed to prove before a
 * single record is written.
 *
 * <p>Every test runs the real {@link FixtureSourceAdapter} against the shipped
 * {@code acme-legacy} fixture and the real {@link MappingProposer}, so what is
 * under test is the engine, not a stub of it. The database is a Mockito mock —
 * which is exactly what makes the headline assertion possible: a mock records
 * every call, so "this code performed no write" can be checked rather than
 * asserted in a comment.
 */
class MigrationAnalyzerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PLAN = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID EXISTING_KESTREL = UUID.fromString("44444444-4444-4444-4444-444444444444");

    /** JdbcTemplate methods that change data. A dry run may call none of them. */
    private static final Set<String> MUTATORS = Set.of("update", "batchUpdate", "execute");

    private JdbcTemplate jdbc;
    private FixtureSourceAdapter fixture;
    private MigrationAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        fixture = new FixtureSourceAdapter(new ObjectMapper());
        analyzer = new MigrationAnalyzer(jdbc);
        TenantContext.set(new TenantContext.Principal(TENANT, USER, "TENANT_ADMIN",
                "Raj Malhotra", "raj.malhotra@meridianfab.com"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ FR-MIG-003

    @Test
    @DisplayName("a dry run performs ZERO writes and still returns the full pre-flight report")
    void dryRunWritesNothing() {
        PreFlightReport report = analyzer.analyse(plan(1), fixture, session(1), null);

        List<String> called = mockingDetails(jdbc).getInvocations().stream()
                .map(i -> i.getMethod().getName()).toList();
        assertThat(called)
                .as("the dry run must not call any mutating JdbcTemplate method; it called %s", called)
                .doesNotContainAnyElementsOf(MUTATORS);
        assertThat(called).as("it must still have read the target tenant").contains("query");

        // ...and the report is complete, not a placeholder produced by doing nothing.
        assertThat(report.objects()).extracting(ObjectOutcome::sourceObject)
                .containsExactlyInAnyOrder("Account", "Contact", "Lead", "Opportunity", "LegacyServiceNote__c");
        assertThat(report.totalToCreate()).isEqualTo(13);
        assertThat(report.totalToSkip()).isEqualTo(4);
        assertThat(report.validationFailures()).isPositive();
        assertThat(report.referentialGaps()).isPositive();
        assertThat(report.unmappedFields()).isNotEmpty();
    }

    @Test
    @DisplayName("every unmapped source field is listed on the report, object-qualified")
    void reportListsUnmappedFields() {
        PreFlightReport report = analyzer.analyse(plan(1), fixture, session(1), null);

        assertThat(report.unmappedFields()).contains(
                "Account.LegacyRegionCode__c", "Account.SicCode", "Account.BillingCity",
                "Account.AccountHealthLegacy__c", "Contact.LinkedInHandle__c",
                "Contact.PreferredPronoun__c", "Opportunity.LegacyDealScore__c",
                "Lead.LegacyCampaignTag__c", "LegacyServiceNote__c.LegacyChannel__c");
        assertThat(report.issues()).filteredOn(i -> "UNMAPPED_FIELD".equals(i.category()))
                .allSatisfy(i -> assertThat(i.reason()).contains("will NOT be migrated"));
    }

    // ------------------------------------------------------------------ FR-GLOBAL-003

    @Test
    @DisplayName("a validation failure is reported per record, naming the record and the field")
    void validationFailureIsReportedPerRecord() {
        PreFlightReport report = analyzer.analyse(plan(1), fixture, session(1), null);

        List<Issue> failures = report.issues().stream()
                .filter(i -> "VALIDATION".equals(i.category())).toList();

        assertThat(failures).anySatisfy(issue -> {
            assertThat(issue.sourceObject()).isEqualTo("Contact");
            assertThat(issue.sourceRecordId()).isEqualTo("CON-4");
            assertThat(issue.fieldName()).isEqualTo("lastName");
            assertThat(issue.reason()).contains("Required field lastName is empty");
        });
        // ...and that record is counted as skipped, not quietly created without a surname.
        assertThat(report.issues()).anySatisfy(issue -> {
            assertThat(issue.category()).isEqualTo("SKIPPED");
            assertThat(issue.sourceRecordId()).isEqualTo("CON-4");
        });
    }

    // ------------------------------------------------------------------ duplicates

    @Test
    @DisplayName("duplicates are detected against existing tenant data and named on both sides")
    void duplicatesAgainstExistingTenantData() {
        // The tenant already holds an account called Kestrel Manufacturing; the
        // source export contains one too (ACC-4).
        stubExistingAccounts(Map.of("Kestrel Manufacturing", EXISTING_KESTREL));

        PreFlightReport report = analyzer.analyse(plan(1), fixture, session(1), null);

        assertThat(report.duplicates()).isEqualTo(1);
        assertThat(report.issues()).anySatisfy(issue -> {
            assertThat(issue.category()).isEqualTo("DUPLICATE");
            assertThat(issue.sourceRecordId()).isEqualTo("ACC-4");
            assertThat(issue.relatedRecordId()).isEqualTo(EXISTING_KESTREL.toString());
            assertThat(issue.reason()).contains("Matches an existing tenant record");
        });
        // One fewer account is created than the source holds, and the report says why.
        assertThat(report.objects()).filteredOn(o -> o.sourceObject().equals("Account"))
                .first().satisfies(account -> {
                    assertThat(account.sourceCount()).isEqualTo(5);
                    assertThat(account.toCreate()).isEqualTo(3);
                    assertThat(account.toSkip()).isEqualTo(2);
                });
    }

    // ------------------------------------------------------------------ FR-MIG-004

    @Test
    @DisplayName("an unresolvable relationship is reported with BOTH endpoints named")
    void unresolvableRelationshipNamesBothEndpoints() {
        PreFlightReport report = analyzer.analyse(plan(1), fixture, session(1), null);

        List<Issue> gaps = report.issues().stream()
                .filter(i -> "REFERENTIAL_GAP".equals(i.category())).toList();

        // Required end: an opportunity whose account is not in the export.
        assertThat(gaps).anySatisfy(gap -> {
            assertThat(gap.sourceObject()).isEqualTo("Opportunity");
            assertThat(gap.sourceRecordId()).isEqualTo("OPP-3");           // this end
            assertThat(gap.relatedObject()).isEqualTo("Account");           // ...and the other end
            assertThat(gap.relatedRecordId()).isEqualTo("ACC-902");
            assertThat(gap.severity()).isEqualTo("ERROR");
            assertThat(gap.reason()).contains("OPP-3").contains("ACC-902").contains("will NOT be migrated");
        });

        // Optional end: a contact whose account is missing still lands, and the
        // broken link is reported rather than dropped.
        assertThat(gaps).anySatisfy(gap -> {
            assertThat(gap.sourceRecordId()).isEqualTo("CON-3");
            assertThat(gap.relatedObject()).isEqualTo("Account");
            assertThat(gap.relatedRecordId()).isEqualTo("ACC-404");
            assertThat(gap.severity()).isEqualTo("WARNING");
            assertThat(gap.reason()).contains("reported, not dropped");
        });
        assertThat(gaps).allSatisfy(gap -> {
            assertThat(gap.sourceRecordId()).isNotBlank();
            assertThat(gap.relatedRecordId()).isNotBlank();
        });
    }

    @Test
    @DisplayName("a two-level account hierarchy resolves; the parent link is not reported as a gap")
    void twoLevelHierarchyIsPreserved() {
        PreFlightReport report = analyzer.analyse(plan(1), fixture, session(1), null);

        // ACC-2's parent is ACC-1, both in the same export and the same object.
        assertThat(report.issues())
                .filteredOn(i -> "REFERENTIAL_GAP".equals(i.category()))
                .extracting(Issue::sourceRecordId)
                .doesNotContain("ACC-2");
        // ...while a parent that genuinely is not there is still reported, with both ends.
        assertThat(report.issues()).anySatisfy(issue -> {
            assertThat(issue.sourceRecordId()).isEqualTo("ACC-5");
            assertThat(issue.fieldName()).isEqualTo("parentAccountId");
            assertThat(issue.relatedRecordId()).isEqualTo("ACC-901");
        });
        assertThat(report.objects()).filteredOn(o -> o.sourceObject().equals("Account"))
                .first().extracting(ObjectOutcome::toCreate).isEqualTo(4L);
    }

    // ------------------------------------------------------------------ FR-MIG-008

    @Test
    @DisplayName("delta re-sync updates already-migrated records instead of duplicating them")
    void deltaResyncDoesNotDuplicate() {
        // Everything wave 1 produced is already in the ledger.
        stubRecordMap(List.of(
                "Account:ACC-1", "Account:ACC-2", "Account:ACC-3", "Account:ACC-5",
                "Contact:CON-1", "Contact:CON-2", "Contact:CON-3",
                "Lead:LEAD-1", "Lead:LEAD-2",
                "Opportunity:OPP-1", "Opportunity:OPP-2",
                "LegacyServiceNote__c:NOTE-1", "LegacyServiceNote__c:NOTE-2"));

        Instant watermark = Instant.parse("2026-01-10T12:00:00Z");
        PreFlightReport report = analyzer.analyse(plan(2), fixture, session(2), watermark);

        // Wave 2 adds three records and edits one. Nothing already migrated is recreated.
        assertThat(report.totalToCreate()).isEqualTo(3);
        assertThat(report.totalToUpdate()).isEqualTo(1);

        assertThat(report.objects()).filteredOn(o -> o.sourceObject().equals("Account"))
                .first().satisfies(account -> {
                    assertThat(account.sourceCount()).as("only changed source records are read").isEqualTo(1);
                    assertThat(account.toCreate()).isEqualTo(1);
                });
        assertThat(report.objects()).filteredOn(o -> o.sourceObject().equals("Contact"))
                .first().satisfies(contact -> {
                    assertThat(contact.toCreate()).as("CON-5 is new").isEqualTo(1);
                    assertThat(contact.toUpdate()).as("CON-2 was edited in the source").isEqualTo(1);
                });
        // A second delta with nothing new produces nothing at all.
        assertThat(report.totalToSkip()).isZero();
    }

    // ------------------------------------------------------------------ reconciliation inputs

    @Test
    @DisplayName("monetary sums are taken from the source, including records that will not migrate")
    void monetarySumsCoverEverySourceRecord() {
        PreFlightReport report = analyzer.analyse(plan(1), fixture, session(1), null);

        ObjectOutcome opportunities = report.objects().stream()
                .filter(o -> o.sourceObject().equals("Opportunity")).findFirst().orElseThrow();

        // 250000.00 + 87500.50 + 42000.00 — the rejected OPP-3 is INCLUDED in the
        // source sum on purpose: that is what makes the target sum fail to tie out
        // and forces the reason onto the reconciliation report.
        assertThat(opportunities.sourceAmountSum()).isEqualByComparingTo(new BigDecimal("379500.50"));
        assertThat(opportunities.sourceCount()).isEqualTo(3);
        assertThat(opportunities.toCreate()).isEqualTo(2);
        assertThat(opportunities.toSkip()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private SourceSession session(int wave) {
        return new SourceSession(UUID.randomUUID(), "FIXTURE", null, "acme-legacy", wave, null);
    }

    /** Build the executable plan the way MigrationPlanService does, through the real proposer. */
    private PlanContext plan(int wave) {
        SourceSession session = session(wave);
        List<ObjectPlan> objects = new ArrayList<>();
        for (SourceContract.SourceObject object : fixture.objects(session)) {
            List<SourceField> fields = fixture.fields(session, object.apiName());
            Map<String, String> references = fixture.references(session, object.apiName());
            Map<String, String> mapped = new LinkedHashMap<>();
            List<String> unmapped = new ArrayList<>();
            for (MappingProposer.ProposedMapping proposal :
                    MappingProposer.propose(object.apiName(), object.proposedTarget(), fields, references)) {
                if ("MAPPED".equals(proposal.status())) mapped.put(proposal.sourceField(), proposal.targetField());
                else unmapped.add(proposal.sourceField());
            }
            objects.add(new ObjectPlan(object.apiName(), object.proposedTarget(), mapped, unmapped,
                    List.of(), references, fixture.moneyFields(session, object.apiName())));
        }
        return new PlanContext(PLAN, "Acme legacy cutover", session.connectionId(), "FIXTURE",
                false, null, objects);
    }

    @SuppressWarnings("unchecked")
    private void stubExistingAccounts(Map<String, UUID> byName) {
        List<Object[]> rows = byName.entrySet().stream()
                .map(e -> new Object[]{e.getValue().toString(), e.getKey()}).toList();
        when(jdbc.query(contains("from crm.account"), any(RowMapper.class), eq(TENANT)))
                .thenReturn((List) rows);
    }

    @SuppressWarnings("unchecked")
    private void stubRecordMap(List<String> sourceKeys) {
        List<Object[]> rows = sourceKeys.stream()
                .map(key -> new Object[]{key, "ACCOUNT", UUID.randomUUID()}).toList();
        when(jdbc.query(contains("migration.record_map"), any(RowMapper.class), eq(TENANT), eq(PLAN)))
                .thenReturn((List) rows);
    }

    @SuppressWarnings("unused")
    private void unusedMatchersGuard() {
        // Keeps the anyString import meaningful if the stubs above are reworked.
        anyString();
    }
}

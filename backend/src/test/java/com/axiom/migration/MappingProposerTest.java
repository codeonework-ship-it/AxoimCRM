package com.axiom.migration;

import com.axiom.migration.SourceContract.SourceField;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema discovery and mapping proposal (FR-MIG-002).
 *
 * <p>The clause under test is the one that separates this from an import script:
 * <em>unmapped source fields must be listed explicitly; silent omission of source
 * data is not acceptable.</em> So these tests care less about how many fields the
 * proposer places than about whether every field it cannot place comes back named,
 * with a reason.
 */
class MappingProposerTest {

    private final FixtureSourceAdapter fixture = new FixtureSourceAdapter(new ObjectMapper());

    private SourceContract.SourceSession session() {
        return new SourceContract.SourceSession(UUID.randomUUID(), "FIXTURE", null, "acme-legacy", 1, null);
    }

    @Test
    @DisplayName("discovery enumerates objects, custom objects and record counts")
    void discoveryEnumeratesTheSource() {
        List<SourceContract.SourceObject> objects = fixture.objects(session());

        assertThat(objects).extracting(SourceContract.SourceObject::apiName)
                .containsExactlyInAnyOrder("Account", "Contact", "Lead", "Opportunity", "LegacyServiceNote__c");
        assertThat(objects).filteredOn(SourceContract.SourceObject::custom)
                .extracting(SourceContract.SourceObject::apiName)
                .containsExactly("LegacyServiceNote__c");
        assertThat(objects).filteredOn(o -> o.apiName().equals("Account"))
                .first().extracting(SourceContract.SourceObject::recordCount).isEqualTo(5L);
    }

    @Test
    @DisplayName("proposes a mapping for the fields it recognises")
    void proposesAMapping() {
        List<MappingProposer.ProposedMapping> proposals = propose("Account", TargetSchema.ACCOUNT);

        assertThat(proposals).filteredOn(p -> p.status().equals("MAPPED"))
                .extracting(MappingProposer.ProposedMapping::sourceField, MappingProposer.ProposedMapping::targetField)
                .contains(java.util.Map.entry("Name", "name") == null ? null : org.assertj.core.groups.Tuple.tuple("Name", "name"),
                        org.assertj.core.groups.Tuple.tuple("AnnualRevenue", "annualRevenue"),
                        org.assertj.core.groups.Tuple.tuple("NumberOfEmployees", "employeeCount"),
                        org.assertj.core.groups.Tuple.tuple("ParentId", "parentAccountId"));
    }

    @Test
    @DisplayName("lists every unmapped source field explicitly, with a reason naming the field")
    void listsUnmappedFieldsExplicitly() {
        List<MappingProposer.ProposedMapping> proposals = propose("Account", TargetSchema.ACCOUNT);
        List<MappingProposer.ProposedMapping> unmapped =
                proposals.stream().filter(p -> p.status().equals("UNMAPPED")).toList();

        assertThat(unmapped).extracting(MappingProposer.ProposedMapping::sourceField)
                .containsExactlyInAnyOrder("SicCode", "BillingCity",
                        "LegacyRegionCode__c", "AccountHealthLegacy__c");
        assertThat(unmapped).allSatisfy(p -> {
            assertThat(p.note()).contains(p.sourceField());
            assertThat(p.note()).contains("will NOT be migrated");
        });
    }

    @Test
    @DisplayName("a custom object's custom fields are reported, not quietly dropped")
    void customObjectFieldsAreReported() {
        List<MappingProposer.ProposedMapping> proposals =
                propose("LegacyServiceNote__c", TargetSchema.ACTIVITY);

        assertThat(proposals).filteredOn(p -> p.status().equals("MAPPED"))
                .extracting(MappingProposer.ProposedMapping::sourceField)
                .contains("Subject", "Body", "LoggedAt", "LoggedByName", "RelatedAccountId");
        assertThat(proposals).filteredOn(p -> p.status().equals("UNMAPPED"))
                .extracting(MappingProposer.ProposedMapping::sourceField)
                .containsExactly("LegacyChannel__c");
    }

    @Test
    @DisplayName("an object with no Axiom target loses every field, and says so per field")
    void unmappedObjectReportsEveryField() {
        List<MappingProposer.ProposedMapping> proposals = propose("LegacyServiceNote__c", null);

        assertThat(proposals).isNotEmpty();
        assertThat(proposals).allMatch(p -> p.status().equals("UNMAPPED"));
        assertThat(proposals).allSatisfy(p ->
                assertThat(p.note()).contains("has no Axiom target entity"));
    }

    @Test
    @DisplayName("a reference field never proposes onto a plain text field")
    void referenceFieldsStayReferences() {
        List<MappingProposer.ProposedMapping> proposals = propose("Contact", TargetSchema.CONTACT);

        MappingProposer.ProposedMapping accountId = proposals.stream()
                .filter(p -> p.sourceField().equals("AccountId")).findFirst().orElseThrow();
        assertThat(accountId.status()).isEqualTo("MAPPED");
        assertThat(accountId.targetField()).isEqualTo("accountId");

        MappingProposer.ProposedMapping reportsTo = proposals.stream()
                .filter(p -> p.sourceField().equals("ReportsToId")).findFirst().orElseThrow();
        assertThat(reportsTo.targetField()).isEqualTo("reportsToContactId");
    }

    private List<MappingProposer.ProposedMapping> propose(String object, String targetEntity) {
        SourceContract.SourceSession session = session();
        List<SourceField> fields = fixture.fields(session, object);
        Map<String, String> references = fixture.references(session, object);
        return MappingProposer.propose(object, targetEntity, fields, references);
    }
}

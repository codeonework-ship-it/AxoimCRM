package com.axiom.migration;

import com.axiom.migration.SourceContract.SourceField;
import com.axiom.migration.TargetSchema.TargetEntity;
import com.axiom.migration.TargetSchema.TargetField;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Proposes a field mapping, and — the part that matters — names every source
 * field it could not place (FR-MIG-002).
 *
 * <h2>The rule this class exists to enforce</h2>
 * "Unmapped source fields must be listed explicitly. Silent omission of source
 * data is not acceptable." An ad-hoc import script maps what it recognises and
 * drops the rest; the drop is invisible because an absence has no row. So this
 * proposer emits a {@link ProposedMapping} for <em>every</em> discovered source
 * field, and an unplaceable one gets {@code status = UNMAPPED} with a reason —
 * a row, in a table, that the mapping review must acknowledge before the plan
 * can be imported.
 *
 * <h2>Deliberately conservative</h2>
 * The proposer only claims a mapping on an exact normalised-alias hit or an
 * exact normalised name hit. Fuzzy matching would produce mappings that look
 * plausible and are wrong, and a wrong mapping is worse than an unmapped field:
 * the unmapped field is on the list the operator reads, the wrong one is not.
 * Custom fields therefore almost always arrive as UNMAPPED, which is the honest
 * answer — Axiom has no column for {@code LegacyRegionCode__c} and pretending
 * otherwise would lose the data quietly.
 *
 * <p>Pure and static: no database, no tenant context, so it is directly testable
 * and gives the same proposal for the same schema every time.
 */
public final class MappingProposer {

    private MappingProposer() {}

    public record ProposedMapping(String sourceObject, String sourceField, String sourceDataType,
                                  boolean custom, String targetEntity, String targetField,
                                  String status, String note) {}

    /** Source fields that are structural rather than data — never proposed, never listed as lost. */
    private static final Set<String> STRUCTURAL = Set.of("id", "recordid", "systemmodstamp",
            "createddate", "lastmodifieddate", "isdeleted");

    /**
     * @param objectApiName the source object being mapped
     * @param targetEntity  the Axiom entity chosen for it, or null when the object
     *                      itself has no target — in which case every one of its
     *                      fields is reported unmapped, object and all
     * @param references    source field name to referenced source object api name
     */
    public static List<ProposedMapping> propose(String objectApiName,
                                                String targetEntity,
                                                List<SourceField> sourceFields,
                                                java.util.Map<String, String> references) {
        List<ProposedMapping> out = new ArrayList<>();
        TargetEntity entity = TargetSchema.entity(targetEntity).orElse(null);
        Set<String> claimedTargets = new HashSet<>();

        for (SourceField field : sourceFields) {
            String normalised = TargetSchema.normalise(field.apiName());
            if (STRUCTURAL.contains(normalised)) continue;

            if (entity == null) {
                out.add(new ProposedMapping(objectApiName, field.apiName(), field.dataType(), field.custom(),
                        null, null, "UNMAPPED",
                        "Source object " + objectApiName + " has no Axiom target entity, so none of its fields "
                        + "will be stored."));
                continue;
            }

            TargetField match = match(entity, normalised, references.get(field.apiName()));
            if (match == null || !claimedTargets.add(match.name())) {
                String reason = match == null
                        ? "No Axiom field corresponds to " + field.apiName()
                          + (field.custom() ? " (custom field)" : "")
                          + ". This value will NOT be migrated."
                        : "Axiom field " + match.name() + " is already mapped from another source field; "
                          + field.apiName() + " will NOT be migrated.";
                out.add(new ProposedMapping(objectApiName, field.apiName(), field.dataType(), field.custom(),
                        null, null, "UNMAPPED", reason));
                continue;
            }

            out.add(new ProposedMapping(objectApiName, field.apiName(), field.dataType(), field.custom(),
                    entity.name(), match.name(), "MAPPED", null));
        }
        return out;
    }

    private static TargetField match(TargetEntity entity, String normalisedSource, String referencedObject) {
        for (TargetField candidate : entity.fields()) {
            // A reference field only ever proposes onto a reference field. Mapping
            // Salesforce's AccountId onto a text column would migrate an opaque
            // foreign id as a string and call it success.
            boolean sourceIsReference = referencedObject != null;
            boolean targetIsReference = candidate.referenceTo() != null;
            if (sourceIsReference != targetIsReference) continue;

            if (TargetSchema.normalise(candidate.name()).equals(normalisedSource)) return candidate;
            for (String alias : candidate.aliases()) {
                if (alias.equals(normalisedSource)) return candidate;
            }
        }
        return null;
    }
}

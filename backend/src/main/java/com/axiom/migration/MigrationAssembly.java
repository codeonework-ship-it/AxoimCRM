package com.axiom.migration;

import com.axiom.migration.MigrationModel.Issue;
import com.axiom.migration.MigrationModel.ObjectPlan;
import com.axiom.migration.SourceContract.SourceRecord;
import com.axiom.migration.TargetSchema.TargetEntity;
import com.axiom.migration.TargetSchema.TargetField;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns one source record into a candidate Axiom record, or into the reasons it
 * cannot become one.
 *
 * <h2>Why this is pure and shared</h2>
 * FR-MIG-003 requires the dry run to apply "the same validation the real import
 * will", so its report is a prediction rather than an estimate. The cheapest way
 * to guarantee that is for there to be only one implementation: both
 * {@link MigrationAnalyzer} and {@link MigrationImporter} call this class, so a
 * validation rule cannot exist on one path and not the other. It touches no
 * database and no tenant context, which also makes every rule directly testable.
 *
 * <p>Relationships are deliberately NOT resolved here. This class records that a
 * field wants source record {@code ACC-901} of object {@code Account}; whether
 * that resolves is a question about the target tenant, answered by the caller
 * who can read it. Keeping the two apart is what makes it structurally possible
 * to report both endpoints when resolution fails (FR-MIG-004): the wanted
 * endpoint is still in hand at the moment the lookup comes back empty.
 */
public final class MigrationAssembly {

    private MigrationAssembly() {}

    /**
     * A relationship this record needs.
     *
     * @param targetField      the Axiom field that will hold it
     * @param sourceObject     the source object the reference points at — the OTHER endpoint's type
     * @param sourceRecordId   the OTHER endpoint's id, as the source states it
     * @param required         a missing required reference rejects the record; a missing
     *                         optional one is reported and the record still lands
     */
    public record Reference(String targetField, String targetEntity, String sourceObject,
                            String sourceRecordId, boolean required) {}

    public record Assembled(String targetEntity,
                            Map<String, String> values,
                            List<Reference> references,
                            List<Issue> failures) {

        public boolean valid() { return failures.isEmpty(); }
    }

    public static Assembled assemble(ObjectPlan plan, SourceRecord record) {
        TargetEntity entity = TargetSchema.entity(plan.targetEntity()).orElse(null);
        List<Issue> failures = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        List<Reference> references = new ArrayList<>();

        if (entity == null) {
            failures.add(Issue.validation(plan.sourceObject(), record.sourceId(), record.label(), null,
                    "Source object " + plan.sourceObject() + " has no Axiom target entity"));
            return new Assembled(plan.targetEntity(), values, references, failures);
        }

        for (Map.Entry<String, String> mapping : plan.mappedFields().entrySet()) {
            String sourceField = mapping.getKey();
            String targetFieldName = mapping.getValue();
            TargetField target = entity.field(targetFieldName).orElse(null);
            if (target == null) {
                failures.add(Issue.validation(plan.sourceObject(), record.sourceId(), record.label(), targetFieldName,
                        "Mapping points at Axiom field " + targetFieldName + ", which does not exist on "
                        + entity.name() + ". Correct the mapping before importing."));
                continue;
            }
            String raw = record.values().get(sourceField);
            String value = raw == null ? null : raw.trim();

            if (target.referenceTo() != null) {
                if (value != null && !value.isEmpty()) {
                    references.add(new Reference(target.name(), target.referenceTo(),
                            plan.references().getOrDefault(sourceField, target.referenceTo()),
                            value, target.required()));
                } else if (target.required()) {
                    failures.add(Issue.validation(plan.sourceObject(), record.sourceId(), record.label(),
                            target.name(), "Required relationship " + target.name()
                            + " is empty in the source record"));
                }
                continue;
            }

            if (value == null || value.isEmpty()) {
                if (target.required()) {
                    failures.add(Issue.validation(plan.sourceObject(), record.sourceId(), record.label(),
                            target.name(), "Required field " + target.name() + " is empty in source field "
                            + sourceField));
                }
                continue;
            }

            String problem = typeProblem(target, value);
            if (problem != null) {
                failures.add(Issue.validation(plan.sourceObject(), record.sourceId(), record.label(),
                        target.name(), problem));
                continue;
            }
            values.put(target.name(), value);
        }

        // A required field with no mapping at all fails here rather than at the
        // database, so the report names the field instead of a constraint.
        for (TargetField required : entity.required()) {
            if (required.referenceTo() != null) continue;
            if (!values.containsKey(required.name())
                    && failures.stream().noneMatch(i -> required.name().equals(i.fieldName()))) {
                failures.add(Issue.validation(plan.sourceObject(), record.sourceId(), record.label(),
                        required.name(), "Required Axiom field " + required.name()
                        + " has no mapped source field on object " + plan.sourceObject()));
            }
        }
        for (TargetField required : entity.required()) {
            if (required.referenceTo() == null) continue;
            boolean present = references.stream().anyMatch(r -> r.targetField().equals(required.name()));
            boolean alreadyReported = failures.stream().anyMatch(i -> required.name().equals(i.fieldName()));
            if (!present && !alreadyReported) {
                failures.add(Issue.validation(plan.sourceObject(), record.sourceId(), record.label(),
                        required.name(), "Required relationship " + required.name()
                        + " has no mapped source field on object " + plan.sourceObject()));
            }
        }

        return new Assembled(entity.name(), values, references, failures);
    }

    /** Mirrors the crm.contact contact_seniority_known CHECK. */
    private static final java.util.Set<String> SENIORITY = java.util.Set.of(
            "C_LEVEL", "VP", "DIRECTOR", "MANAGER", "INDIVIDUAL_CONTRIBUTOR", "OTHER");

    private static String typeProblem(TargetField target, String value) {
        try {
            switch (target.type()) {
                case "MONEY", "NUMBER" -> new BigDecimal(value.replace(",", ""));
                case "INTEGER" -> Integer.parseInt(value.replace(",", "").trim());
                case "SENIORITY" -> {
                    if (!SENIORITY.contains(value.toUpperCase(java.util.Locale.ROOT))) {
                        return "Value \"" + value + "\" is not a known seniority for " + target.name()
                                + ". Permitted: " + SENIORITY;
                    }
                    return null;
                }
                case "DATE" -> LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
                case "DATETIME" -> Instant.parse(value);
                case "CURRENCY_CODE" -> {
                    // crm.account and sales.opportunity both CHECK ^[A-Z]{3}$. Catching
                    // it here means the report names the field; catching it at the
                    // database means the operator reads a constraint name.
                    if (!value.matches("[A-Za-z]{3}")) {
                        return "Value \"" + value + "\" is not a three-letter ISO currency code for "
                                + target.name();
                    }
                    return null;
                }
                default -> { return null; }
            }
        } catch (NumberFormatException | DateTimeParseException ex) {
            return "Value \"" + value + "\" is not a valid " + target.type() + " for " + target.name();
        }
        return null;
    }

    public static BigDecimal money(String value) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * A stable digest of what the migration wrote.
     *
     * <p>Taken at write time and compared at rollback time. If it still matches,
     * the record is exactly as the migration left it; if it does not, a user has
     * edited it since and the rollback preview says so before anything is
     * removed. Sorted keys so the digest depends on the data, not on map order.
     */
    public static String fingerprint(Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(values).forEach((k, v) -> sb.append(k).append('=').append(v == null ? "" : v).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", ex);
        }
    }
}

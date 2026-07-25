package com.axiom.security;

import com.axiom.common.NotFoundException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The registry of objects the authorization layer is allowed to build SQL
 * against, and the only place a field name is translated into a column name.
 *
 * <p><b>Why an enum and not the {@code security.securable_object} table.</b>
 * Every predicate this package builds interpolates a table name, an owner column
 * and (for criteria-based sharing rules) a criteria column directly into SQL —
 * those cannot be bound as parameters. Reading them from a tenant-writable table
 * would put SQL injection one admin screen away. The table exists so the model is
 * inspectable in SQL and so a sweep can enumerate objects; this enum is what the
 * query builder trusts. If they disagree, the enum wins and the mismatch is a
 * migration defect.
 *
 * <p>Field names in this registry are the <em>API</em> names (camelCase, as they
 * appear in JSON), not column names. One vocabulary, used by field permissions,
 * sharing-rule criteria and the field-read audit alike — so an administrator who
 * hides {@code industry} on a profile and an administrator who writes a sharing
 * rule on {@code industry} are talking about the same thing.
 */
public enum SecurableObject {

    ACCOUNT("crm.account", "owner_id", null, null, true, fields(
            "id", "id",
            "name", "name",
            "industry", "industry",
            "taxId", "tax_id",
            "ownerId", "owner_id")),

    CONTACT("crm.contact", null, "account_id", "ACCOUNT", true, fields(
            "id", "id",
            "accountId", "account_id",
            "firstName", "first_name",
            "lastName", "last_name",
            "email", "email",
            "title", "title")),

    LEAD("crm.lead", "owner_id", null, null, true, fields(
            "id", "id",
            "firstName", "first_name",
            "lastName", "last_name",
            "company", "company",
            "email", "email",
            "status", "status",
            "ownerId", "owner_id")),

    OPPORTUNITY("sales.opportunity", "owner_id", "account_id", "ACCOUNT", false, fields(
            "id", "id",
            "name", "name",
            "accountId", "account_id",
            "amount", "amount",
            "closeDate", "close_date",
            "stageId", "stage_id",
            "ownerId", "owner_id"));

    /**
     * Fields that can never be hidden. A response without an identifier is not a
     * redacted record, it is an unusable one — the caller cannot even ask for an
     * explanation of why the rest is missing. Hiding the primary key would also
     * defeat the point of FR-SEC-007, which is that the user learns the record
     * exists and that some of it is withheld.
     */
    private static final Set<String> ALWAYS_READABLE = Set.of("id");

    private final String qualifiedTable;
    private final String ownerColumn;
    private final String parentColumn;
    private final String parentObject;
    private final boolean softDeleted;
    private final Map<String, String> fieldColumns;

    SecurableObject(String qualifiedTable, String ownerColumn, String parentColumn,
                    String parentObject, boolean softDeleted, Map<String, String> fieldColumns) {
        this.qualifiedTable = qualifiedTable;
        this.ownerColumn = ownerColumn;
        this.parentColumn = parentColumn;
        this.parentObject = parentObject;
        this.softDeleted = softDeleted;
        this.fieldColumns = fieldColumns;
    }

    public String qualifiedTable() { return qualifiedTable; }

    /** Null for objects with no owner of their own — see {@link #parent()}. */
    public String ownerColumn() { return ownerColumn; }

    public boolean hasOwner() { return ownerColumn != null; }

    public String parentColumn() { return parentColumn; }

    public boolean softDeleted() { return softDeleted; }

    /**
     * The object this one inherits record visibility from when it has no owner.
     * {@code CONTACT} has no {@code owner_id} in the schema: a contact's
     * visibility follows its account, which is both the correct CRM semantic and
     * an honest statement of the table shape rather than a special case buried in
     * the query builder.
     */
    public Optional<SecurableObject> parent() {
        return parentObject == null ? Optional.empty() : Optional.of(valueOf(parentObject));
    }

    public Set<String> fieldNames() { return fieldColumns.keySet(); }

    public boolean alwaysReadable(String fieldName) { return ALWAYS_READABLE.contains(fieldName); }

    /** @throws IllegalArgumentException if the field is not registered — never interpolate an unknown name. */
    public String column(String fieldName) {
        String column = fieldColumns.get(fieldName);
        if (column == null) {
            throw new IllegalArgumentException("Field '" + fieldName + "' is not a queryable field of "
                    + name() + ". Queryable fields: " + String.join(", ", fieldColumns.keySet()));
        }
        return column;
    }

    public boolean hasColumn(String fieldName) { return fieldColumns.containsKey(fieldName); }

    public static SecurableObject of(String objectType) {
        if (objectType == null || objectType.isBlank()) {
            throw new NotFoundException("An object type is required");
        }
        try {
            return valueOf(objectType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException("Unknown object type: " + objectType);
        }
    }

    private static Map<String, String> fields(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return Map.copyOf(map);
    }
}

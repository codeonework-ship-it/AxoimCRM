package com.axiom.search;

import com.axiom.common.NotFoundException;
import com.axiom.security.SecurableObject;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The registry of business objects the search engine indexes, and the only place
 * an entity type is translated into a table, a route or a set of field names.
 *
 * <p><b>Why an enum rather than configuration.</b> The same reason
 * {@link SecurableObject} is an enum: the projector interpolates schema-qualified
 * table names into SQL, and reading those from a tenant-writable table would put
 * SQL injection one admin screen away. This enum is deliberately a small, closed
 * set that a code review can check.
 *
 * <p><b>Field attribution is the field-level-security contract.</b>
 * {@code titleFields} and {@code subtitleFields} name the {@link SecurableObject}
 * API fields that the title and subtitle are built from. The query path uses them
 * to decide, per caller, whether the title or subtitle may be shown at all —
 * FR-SEC-007 requires a hidden field to be <em>absent</em>, and a search result is
 * one of the surfaces the requirement enumerates ("UI, API, reports, exports,
 * search and AI grounding").
 *
 * <p>An empty attribution list means "derived text, not a securable field of this
 * object" — an opportunity's account name, for instance, is a label, not a field
 * of the opportunity.
 */
public enum IndexedEntity {

    /** Accounts. {@code taxId} is a registered field and is deliberately NOT indexed — see {@link #securedFields()}. */
    ACCOUNT(SecurableObject.ACCOUNT, "account", "crm.account", true,
            List.of("name"), List.of("industry"), List.of(), "/accounts?focus="),

    /** Contacts have no owner column of their own; visibility follows the account (see {@link SecurableObject#parent()}). */
    CONTACT(SecurableObject.CONTACT, "contact", "crm.contact", true,
            List.of("firstName", "lastName"), List.of("title"), List.of("email"), "/accounts?contact="),

    LEAD(SecurableObject.LEAD, "lead", "crm.lead", true,
            List.of("firstName", "lastName"), List.of("company"), List.of("email", "status"), "/leads?focus="),

    OPPORTUNITY(SecurableObject.OPPORTUNITY, "opportunity", "sales.opportunity", false,
            List.of("name"), List.of(), List.of(), "/pipeline?focus=");

    private final SecurableObject securable;
    private final String aggregateType;
    private final String qualifiedTable;
    private final boolean softDeleted;
    private final List<String> titleFields;
    private final List<String> subtitleFields;
    private final List<String> securedFields;
    private final String routePrefix;

    IndexedEntity(SecurableObject securable, String aggregateType, String qualifiedTable, boolean softDeleted,
                  List<String> titleFields, List<String> subtitleFields, List<String> securedFields,
                  String routePrefix) {
        this.securable = securable;
        this.aggregateType = aggregateType;
        this.qualifiedTable = qualifiedTable;
        this.softDeleted = softDeleted;
        this.titleFields = titleFields;
        this.subtitleFields = subtitleFields;
        this.securedFields = securedFields;
        this.routePrefix = routePrefix;
    }

    public SecurableObject securable() { return securable; }

    /** The {@code outbox_event.aggregate_type} this entity's domain events carry (ADR-003). */
    public String aggregateType() { return aggregateType; }

    public String qualifiedTable() { return qualifiedTable; }

    public boolean softDeleted() { return softDeleted; }

    public List<String> titleFields() { return titleFields; }

    public List<String> subtitleFields() { return subtitleFields; }

    /**
     * Securable fields whose values are stored per-field so they stay searchable
     * while remaining individually withholdable at query time.
     *
     * <p>Note what is <em>not</em> here: {@code taxId}. A tax identifier in a
     * full-text index is a liability with no search value — nobody looks a company
     * up by its tax id in a global search box, and indexing it would put it one
     * mis-scoped query away from a caller who may not read it. Leaving it out of
     * the index entirely is a stronger guarantee than filtering it on the way out.
     */
    public List<String> securedFields() { return securedFields; }

    public String urlPath(UUID recordId) { return routePrefix + recordId; }

    public static Optional<IndexedEntity> forAggregateType(String aggregateType) {
        if (aggregateType == null) return Optional.empty();
        String normalized = aggregateType.trim().toLowerCase(Locale.ROOT);
        for (IndexedEntity entity : values()) {
            if (entity.aggregateType.equals(normalized)) return Optional.of(entity);
        }
        return Optional.empty();
    }

    /** @throws NotFoundException for an unknown type — callers pass user input here. */
    public static IndexedEntity of(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            throw new NotFoundException("An entity type is required");
        }
        try {
            return valueOf(entityType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException("Unknown searchable entity type: " + entityType
                    + ". Searchable types: " + String.join(", ", names()));
        }
    }

    public static List<String> names() {
        return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
}

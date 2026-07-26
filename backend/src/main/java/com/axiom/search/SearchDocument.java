package com.axiom.search;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One indexable record, as projected from the authoritative tables.
 *
 * @param entity        which business object this is
 * @param entityId      the authoritative record id — the thing the recheck will re-read
 * @param title         weight A text, built from {@link IndexedEntity#titleFields()}
 * @param subtitle      weight B text, built from {@link IndexedEntity#subtitleFields()}
 * @param body          weight C text; descriptive columns that are not field-secured
 * @param securedFields weight D values, kept per field so each can be withheld individually
 * @param ownerId       the owner whose access the index pre-filter keys on
 * @param sharingKeys   principals and territories that could grant access — a deliberate
 *                      superset of true access, never a substitute for it
 * @param urlPath       where the UI should navigate on click
 * @param updatedAt     the SOURCE record's {@code updated_at}; the out-of-order guard
 */
public record SearchDocument(IndexedEntity entity, UUID entityId, String title, String subtitle,
                             String body, Map<String, String> securedFields, UUID ownerId,
                             List<UUID> sharingKeys, String urlPath, Instant updatedAt) {

    public SearchDocument {
        securedFields = securedFields == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(securedFields));
        sharingKeys = sharingKeys == null ? List.of() : List.copyOf(sharingKeys);
    }

    /**
     * The searchable-but-securable values flattened into one string, purely so
     * PostgreSQL can put them in the tsvector at weight D. The authoritative copy
     * for display and field-level security stays in {@link #securedFields()}.
     */
    public String securedTerms() {
        if (securedFields.isEmpty()) return null;
        String joined = String.join(" ", securedFields.values());
        return joined.isBlank() ? null : joined;
    }
}

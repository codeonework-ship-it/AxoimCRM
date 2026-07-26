package com.axiom.search;

import com.axiom.common.NotFoundException;
import com.axiom.security.SystemTaskRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The consumer's contract with ADR-003, at the level a unit test can reach: what it
 * does with an id, not how the id arrived. The SQL-level guarantees — one row under
 * duplicate delivery, no regression under out-of-order delivery — are asserted
 * against a real PostgreSQL in {@code SearchIndexSqlIT}, because they are database
 * behaviours and mocking them would test the mock.
 */
class SearchIndexerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private SearchIndex index;
    private SearchProjector projector;
    private SearchIndexer indexer;

    @BeforeEach
    void setUp() {
        index = mock(SearchIndex.class);
        projector = mock(SearchProjector.class);
        indexer = new SearchIndexer(mock(JdbcTemplate.class), index, projector,
                mock(SystemTaskRunner.class), 200, true);
    }

    @Test
    @DisplayName("a record with no live source row is removed from the index — soft and hard deletes behave alike")
    void missingSourceRecordIsDeletedFromTheIndex() {
        UUID gone = UUID.randomUUID();
        when(projector.project(eq(TENANT), eq(IndexedEntity.ACCOUNT), any())).thenReturn(List.of());
        when(index.delete(eq(TENANT), eq(IndexedEntity.ACCOUNT), eq(gone))).thenReturn(1);

        SearchIndexer.Applied applied = indexer.apply(TENANT, touched(IndexedEntity.ACCOUNT, gone));

        assertThat(applied.removed()).isEqualTo(1);
        assertThat(applied.written()).isZero();
        verify(index).delete(TENANT, IndexedEntity.ACCOUNT, gone);
    }

    @Test
    @DisplayName("a live record is upserted, and never also deleted")
    void liveRecordIsUpsertedOnly() {
        UUID id = UUID.randomUUID();
        when(projector.project(eq(TENANT), eq(IndexedEntity.ACCOUNT), any()))
                .thenReturn(List.of(document(IndexedEntity.ACCOUNT, id)));
        when(index.upsert(eq(TENANT), any())).thenReturn(true);

        SearchIndexer.Applied applied = indexer.apply(TENANT, touched(IndexedEntity.ACCOUNT, id));

        assertThat(applied.written()).isEqualTo(1);
        assertThat(applied.removed()).isZero();
        verify(index, org.mockito.Mockito.never()).delete(any(), any(), any());
    }

    @Test
    @DisplayName("an upsert refused as stale is reported as no work done, not as a write")
    void staleUpsertIsNotCountedAsAWrite() {
        UUID id = UUID.randomUUID();
        when(projector.project(eq(TENANT), eq(IndexedEntity.LEAD), any()))
                .thenReturn(List.of(document(IndexedEntity.LEAD, id)));
        when(index.upsert(eq(TENANT), any())).thenReturn(false);

        SearchIndexer.Applied applied = indexer.apply(TENANT, touched(IndexedEntity.LEAD, id));

        assertThat(applied.written()).isZero();
        assertThat(applied.removed()).isZero();
    }

    @Test
    @DisplayName("outbox aggregate types map onto indexable entities, and unknown ones are ignored rather than failing the batch")
    void aggregateTypeMapping() {
        assertThat(IndexedEntity.forAggregateType("account")).contains(IndexedEntity.ACCOUNT);
        assertThat(IndexedEntity.forAggregateType("OPPORTUNITY")).contains(IndexedEntity.OPPORTUNITY);
        assertThat(IndexedEntity.forAggregateType("commodity_enquiry")).isEmpty();
        assertThat(IndexedEntity.forAggregateType(null)).isEmpty();
    }

    @Test
    @DisplayName("an unknown entity type from a caller is a 404, not a silent empty result")
    void unknownEntityTypeIsRefused() {
        assertThrows(NotFoundException.class, () -> IndexedEntity.of("INVOICE"));
    }

    @Test
    @DisplayName("secured field values are searchable but stay individually addressable")
    void securedTermsAreDerivedFromTheFieldMap() {
        SearchDocument document = new SearchDocument(IndexedEntity.CONTACT, UUID.randomUUID(),
                "Anita Rao", "Head of Procurement", "Procurement",
                Map.of("email", "anita.rao@example.com"), UUID.randomUUID(), List.of(),
                "/accounts?contact=x", Instant.now());

        assertThat(document.securedTerms()).isEqualTo("anita.rao@example.com");
        assertThat(document.securedFields()).containsEntry("email", "anita.rao@example.com");
    }

    @Test
    @DisplayName("a document with no secured fields carries no secured terms at all")
    void noSecuredFieldsMeansNoSecuredTerms() {
        assertThat(document(IndexedEntity.ACCOUNT, UUID.randomUUID()).securedTerms()).isNull();
    }

    private static Map<IndexedEntity, Set<UUID>> touched(IndexedEntity entity, UUID id) {
        Map<IndexedEntity, Set<UUID>> touched = new LinkedHashMap<>();
        touched.put(entity, new LinkedHashSet<>(List.of(id)));
        return touched;
    }

    private static SearchDocument document(IndexedEntity entity, UUID id) {
        return new SearchDocument(entity, id, "Meridian Fabrication Group", "Metal fabrication",
                "Pune Maharashtra", Map.of(), UUID.randomUUID(), List.of(), entity.urlPath(id),
                Instant.now());
    }
}

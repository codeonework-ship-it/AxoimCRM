package com.axiom.search;

import com.axiom.security.AccessContext;
import com.axiom.security.AuthorizationService;
import com.axiom.security.SecurableObject;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The behaviour that makes this engine correct rather than merely fast.
 *
 * <p>Every test here is about the same sentence in system-design §8.2: the index is
 * not authoritative for authorization. The mocked {@link SearchIndex} is deliberately
 * generous — it returns candidates the caller must not see — because that is exactly
 * the state a real index reaches whenever access changes faster than it is rebuilt.
 * What is asserted is that the layer above notices.
 */
class SearchServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222221");
    private static final UUID ALLOWED_ACCOUNT = UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID REVOKED_ACCOUNT = UUID.fromString("33333333-3333-3333-3333-333333333332");

    private JdbcTemplate jdbc;
    private SearchIndex index;
    private AuthorizationService authorization;
    private SearchBackfillService backfill;
    private SearchService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        index = mock(SearchIndex.class);
        authorization = mock(AuthorizationService.class);
        backfill = mock(SearchBackfillService.class);
        service = new SearchService(jdbc, index, authorization, backfill);

        TenantContext.set(new TenantContext.Principal(TENANT, USER, "SALES", "Priya Nair",
                "priya.nair@meridianfab.com"));

        when(authorization.context()).thenReturn(context(Map.of()));
        when(authorization.visibleRecordPredicate(any(SecurableObject.class), anyString()))
                .thenReturn(new AuthorizationService.RecordPredicate("t.owner_id = ?", List.of(USER)));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);
        when(index.freshness(TENANT))
                .thenReturn(new SearchIndex.IndexFreshness(0, null, null, null, 0));
        when(index.documentCounts(TENANT)).thenReturn(new EnumMap<>(IndexedEntity.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ the headline behaviour

    @Test
    @DisplayName("a candidate the authoritative store refuses is dropped, even though the index matched it")
    void staleIndexEntryIsDroppedByTheAuthoritativeRecheck() {
        indexReturns(candidate(IndexedEntity.ACCOUNT, ALLOWED_ACCOUNT, "Meridian Fabrication", 0.9),
                     candidate(IndexedEntity.ACCOUNT, REVOKED_ACCOUNT, "Meridian Castings", 0.8));
        // The authoritative store says only one of the two is still readable — the
        // other one's sharing key in the index is stale.
        authoritativeStoreAllows(ALLOWED_ACCOUNT);
        snippetsMatchEverything();

        SearchService.SearchResponse response = service.search("meridian", List.of("ACCOUNT"), 20);

        assertThat(response.indexMatches()).isEqualTo(2);
        assertThat(response.droppedByRecheck()).isEqualTo(1);
        assertThat(response.returned()).isEqualTo(1);
        assertThat(response.hits()).singleElement()
                .extracting(hit -> hit.get("entityId"))
                .isEqualTo(ALLOWED_ACCOUNT.toString());
    }

    @Test
    @DisplayName("the page is not back-filled from the index to hide a drop")
    void droppedRowIsNotReplacedByAnotherIndexRow() {
        indexReturns(candidate(IndexedEntity.ACCOUNT, ALLOWED_ACCOUNT, "Meridian Fabrication", 0.9),
                     candidate(IndexedEntity.ACCOUNT, REVOKED_ACCOUNT, "Meridian Castings", 0.8));
        authoritativeStoreAllows(ALLOWED_ACCOUNT);
        snippetsMatchEverything();

        SearchService.SearchResponse response = service.search("meridian", List.of("ACCOUNT"), 20);

        assertThat(response.hits()).hasSize(1);
        // Exactly one trip to the index. Fetching more to top the page back up would
        // make the index the authority on who may see what, one row further down.
        verify(index).query(eq(TENANT), any(), any());
        assertThat(response.returned() + response.droppedByRecheck()).isEqualTo(response.indexMatches());
    }

    @Test
    @DisplayName("an object type the caller cannot read at all is never queried, and is named in the response")
    void deniedObjectTypeIsExcludedFromTheIndexQuery() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.LEAD), anyString()))
                .thenReturn(new AuthorizationService.RecordPredicate("false", List.of()));
        indexReturns();
        snippetsMatchEverything();

        SearchService.SearchResponse response = service.search("meridian", List.of("LEAD"), 20);

        assertThat(response.typesDenied()).containsExactly("LEAD");
        verify(index, never()).query(any(), any(), any());
    }

    // ------------------------------------------------------------------ field-level security

    @Test
    @DisplayName("a field the caller may not read is ABSENT from the hit, not null")
    void withheldSubtitleIsAbsentRatherThanNull() {
        when(authorization.context()).thenReturn(context(Map.of(SecurableObject.ACCOUNT, Set.of("industry"))));
        indexReturns(candidate(IndexedEntity.ACCOUNT, ALLOWED_ACCOUNT, "Meridian Fabrication", 0.9));
        authoritativeStoreAllows(ALLOWED_ACCOUNT);
        snippetsMatchEverything();

        SearchService.SearchResponse response = service.search("meridian", List.of("ACCOUNT"), 20);

        Map<String, Object> hit = response.hits().get(0);
        assertThat(hit).doesNotContainKey("subtitle");
        assertThat(hit.get("withheldFields")).isEqualTo(List.of("industry"));
    }

    @Test
    @DisplayName("a hit that matched only through a hidden field is dropped — the hit itself would leak it")
    void matchOnHiddenFieldOnlyIsDropped() {
        indexReturns(candidate(IndexedEntity.CONTACT, ALLOWED_ACCOUNT, "Anita Rao", 0.5));
        authoritativeStoreAllows(ALLOWED_ACCOUNT);
        // The readable text no longer contains the query term: the index matched on
        // secured_terms, which this caller may not read.
        when(index.snippets(anyList(), anyString()))
                .thenReturn(List.of(new SearchIndex.Snippet(false, "Anita Rao")));

        SearchService.SearchResponse response = service.search("anita@example.com", List.of("CONTACT"), 20);

        assertThat(response.droppedByFieldSecurity()).isEqualTo(1);
        assertThat(response.hits()).isEmpty();
    }

    // ------------------------------------------------------------------ the index filter

    @Test
    @DisplayName("view-all widens the index filter instead of guessing a key set")
    void viewAllTypesAreMarkedUnrestricted() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.ACCOUNT), anyString()))
                .thenReturn(new AuthorizationService.RecordPredicate("true", List.of()));
        indexReturns();
        snippetsMatchEverything();

        service.search("meridian", List.of("ACCOUNT"), 20);

        ArgumentCaptor<SearchIndex.IndexFilter> filter = ArgumentCaptor.forClass(SearchIndex.IndexFilter.class);
        verify(index).query(eq(TENANT), any(), filter.capture());
        assertThat(filter.getValue().unrestrictedTypes()).containsExactly(IndexedEntity.ACCOUNT);
    }

    @Test
    @DisplayName("an active sharing rule widens the filter — a live criteria predicate cannot be a static key")
    void activeSharingRuleWidensTheFilter() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
        indexReturns();
        snippetsMatchEverything();

        service.search("meridian", List.of("OPPORTUNITY"), 20);

        ArgumentCaptor<SearchIndex.IndexFilter> filter = ArgumentCaptor.forClass(SearchIndex.IndexFilter.class);
        verify(index).query(eq(TENANT), any(), filter.capture());
        assertThat(filter.getValue().unrestrictedTypes()).containsExactly(IndexedEntity.OPPORTUNITY);
    }

    @Test
    @DisplayName("the caller's own key set is what the filter carries — user, role node, groups, territories")
    void principalKeysMirrorWhatTheProjectorWrites() {
        UUID roleNode = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        UUID territory = UUID.randomUUID();
        when(authorization.context()).thenReturn(context(Map.of(), roleNode, List.of(group), List.of(territory)));
        indexReturns();
        snippetsMatchEverything();

        service.search("meridian", List.of("ACCOUNT"), 20);

        ArgumentCaptor<SearchIndex.IndexFilter> filter = ArgumentCaptor.forClass(SearchIndex.IndexFilter.class);
        verify(index).query(eq(TENANT), any(), filter.capture());
        assertThat(filter.getValue().principalKeys()).containsExactly(USER, roleNode, group, territory);
    }

    // ------------------------------------------------------------------ input handling

    @Test
    @DisplayName("a blank query does not reach the index at all")
    void blankQueryShortCircuits() {
        SearchService.SearchResponse response = service.search("   ", List.of(), 20);

        assertThat(response.hits()).isEmpty();
        assertThat(response.indexMatches()).isZero();
        verify(index, never()).query(any(), any(), any());
    }

    @Test
    @DisplayName("the page size is capped server-side, whatever the caller asks for")
    void limitIsCapped() {
        indexReturns();
        snippetsMatchEverything();

        service.search("meridian", List.of("ACCOUNT"), 5000);

        ArgumentCaptor<SearchIndex.IndexQuery> query = ArgumentCaptor.forClass(SearchIndex.IndexQuery.class);
        verify(index).query(eq(TENANT), query.capture(), any());
        assertThat(query.getValue().limit()).isEqualTo(50);
    }

    @Test
    @DisplayName("comma-separated type filters resolve, and an unknown one is refused rather than ignored")
    void typeFilterParsing() {
        indexReturns();
        snippetsMatchEverything();

        service.search("meridian", List.of("ACCOUNT,OPPORTUNITY"), 20);

        ArgumentCaptor<SearchIndex.IndexQuery> query = ArgumentCaptor.forClass(SearchIndex.IndexQuery.class);
        verify(index).query(eq(TENANT), query.capture(), any());
        assertThat(query.getValue().types())
                .containsExactly(IndexedEntity.ACCOUNT, IndexedEntity.OPPORTUNITY);

        org.junit.jupiter.api.Assertions.assertThrows(com.axiom.common.NotFoundException.class,
                () -> service.search("meridian", List.of("NOT_A_TYPE"), 20));
    }

    // ------------------------------------------------------------------ staleness

    @Test
    @DisplayName("index lag is reported from the newest indexed source timestamp, not from a guess")
    void lagIsReportedInSeconds() {
        Instant newest = Instant.now().minusSeconds(90);
        when(index.freshness(TENANT)).thenReturn(
                new SearchIndex.IndexFreshness(42, newest, Instant.now(), Instant.now(), 7));

        SearchService.IndexStatus status = service.status();

        assertThat(status.documentCount()).isEqualTo(42);
        assertThat(status.pendingEvents()).isEqualTo(7);
        assertThat(status.lagSeconds()).isBetween(89L, 95L);
    }

    @Test
    @DisplayName("an empty index reports no lag rather than a fabricated one")
    void emptyIndexReportsNullLag() {
        SearchService.IndexStatus status = service.status();

        assertThat(status.documentCount()).isZero();
        assertThat(status.lagSeconds()).isNull();
    }

    // ------------------------------------------------------------------ fixtures

    private void indexReturns(SearchIndex.Candidate... candidates) {
        when(index.query(eq(TENANT), any(), any())).thenReturn(List.of(candidates));
    }

    /** The authoritative recheck query — only these ids come back. */
    private void authoritativeStoreAllows(UUID... ids) {
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(ids));
    }

    private void snippetsMatchEverything() {
        when(index.snippets(anyList(), anyString())).thenAnswer(invocation -> {
            List<?> texts = invocation.getArgument(0);
            return texts.stream().map(text -> new SearchIndex.Snippet(true, String.valueOf(text))).toList();
        });
    }

    private static SearchIndex.Candidate candidate(IndexedEntity entity, UUID id, String title, double rank) {
        return new SearchIndex.Candidate(entity, id, title, "Fabricated metals", "body text",
                Map.of(), entity.urlPath(id), Instant.now(), rank);
    }

    private static AccessContext context(Map<SecurableObject, Set<String>> unreadable) {
        return context(unreadable, null, List.of(), List.of());
    }

    private static AccessContext context(Map<SecurableObject, Set<String>> unreadable, UUID roleNodeId,
                                         List<UUID> groups, List<UUID> territories) {
        return new AccessContext(TENANT, USER, "SALES", false, false, UUID.randomUUID(), "SALES",
                null, List.of(), Set.of(), Map.of(), unreadable, Map.of(),
                roleNodeId, roleNodeId == null ? null : "SALES_APAC",
                roleNodeId == null ? null : "/EXEC/SALES/APAC/", groups, territories);
    }
}

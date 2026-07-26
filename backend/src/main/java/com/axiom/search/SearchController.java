package com.axiom.search;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Global search API (FR-ADM-004).
 *
 * <p>Every route here is behind {@code JwtAuthFilter}, so the tenant comes from the
 * verified session and never from a parameter the caller controls (FR-GLOBAL-001).
 * There is no tenant argument anywhere in this class on purpose.
 */
@RestController
@RequestMapping("/api/v1/search")
@Validated
public class SearchController {

    private final SearchService search;
    private final SearchBackfillService backfill;

    public SearchController(SearchService search, SearchBackfillService backfill) {
        this.search = search;
        this.backfill = backfill;
    }

    /**
     * @param q     the query text
     * @param types optional entity types, repeated or comma-separated
     * @param limit page size, capped server-side
     */
    @GetMapping
    public SearchService.SearchResponse search(@RequestParam(name = "q", required = false) String q,
                                               @RequestParam(name = "types", required = false) List<String> types,
                                               @RequestParam(name = "limit", required = false) Integer limit) {
        return search.search(q, types, limit);
    }

    /** Index freshness and backlog, so the UI can state the staleness rather than imply the index is live. */
    @GetMapping("/status")
    public SearchService.IndexStatus status() {
        return search.status();
    }

    /** The entity types this engine indexes — drives the type filter chips without hard-coding them in the UI. */
    @GetMapping("/types")
    public Map<String, List<String>> types() {
        return Map.of("types", backfill.indexableTypes());
    }

    /** Queue a backfill. Administrator-gated in {@link SearchBackfillService#request}, and audited. */
    @PostMapping("/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SearchBackfillService.ReindexRun reindex(
            @RequestBody(required = false) @Valid SearchBackfillService.ReindexRequest request) {
        return backfill.request(request);
    }

    @GetMapping("/reindex")
    public List<SearchBackfillService.ReindexRun> reindexRuns(
            @RequestParam(defaultValue = "10") int limit) {
        return backfill.recentRuns(limit);
    }

    @GetMapping("/reindex/{id}")
    public SearchBackfillService.ReindexRun reindexRun(@PathVariable UUID id) {
        return backfill.run(id);
    }
}

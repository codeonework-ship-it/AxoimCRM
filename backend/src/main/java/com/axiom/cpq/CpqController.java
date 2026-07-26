package com.axiom.cpq;

import com.axiom.api.PageResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cpq")
public class CpqController {
    private final CpqService cpq;
    private final QuoteAuthoringService authoring;

    public CpqController(CpqService cpq, QuoteAuthoringService authoring) {
        this.cpq = cpq;
        this.authoring = authoring;
    }

    /* ---------------------------------------------------------- authoring ----
       CpqService is @Transactional(readOnly = true) at class level and exposes
       only reads plus a document download, so the whole CPQ model could be listed
       and printed but never authored. These four routes are the write half; they
       are also what makes quote-to-order conversion reachable, since nothing could
       previously move a quote to ACCEPTED. */

    @GetMapping("/quotes/{id}")
    public QuoteAuthoringService.QuoteView quote(@org.springframework.web.bind.annotation.PathVariable UUID id) {
        return authoring.get(id);
    }

    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteAuthoringService.QuoteView createQuote(
            @RequestBody @Valid QuoteAuthoringService.QuoteRequest request) {
        return authoring.create(request);
    }

    @PutMapping("/quotes/{id}/lines")
    public QuoteAuthoringService.QuoteView replaceQuoteLines(
            @org.springframework.web.bind.annotation.PathVariable UUID id,
            @RequestParam long version,
            @RequestBody @Valid java.util.List<QuoteAuthoringService.LineRequest> lines) {
        return authoring.replaceLines(id, version, lines);
    }

    @PostMapping("/quotes/{id}/status")
    public QuoteAuthoringService.QuoteView transitionQuote(
            @org.springframework.web.bind.annotation.PathVariable UUID id,
            @RequestBody @Valid QuoteAuthoringService.TransitionRequest request) {
        return authoring.transition(id, request);
    }

    @GetMapping("/products")
    public PageResult<CpqService.ProductRow> products(@RequestParam(required = false) String search,
                                                      @RequestParam(required = false) String category,
                                                      @RequestParam(defaultValue = "0") int page) {
        return cpq.products(search, category, page);
    }

    @GetMapping("/price-books")
    public PageResult<CpqService.PriceBookRow> priceBooks(@RequestParam(required = false) String search,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(defaultValue = "0") int page) {
        return cpq.priceBooks(search, status, page);
    }

    @GetMapping("/quotes")
    public PageResult<CpqService.QuoteRow> quotes(@RequestParam(required = false) String search,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "0") int page) {
        return cpq.quotes(search, status, page);
    }

    @GetMapping("/quotes/summary")
    public CpqService.QuoteSummary quoteSummary() {
        return cpq.quoteSummary();
    }

    @GetMapping("/quotes/{id}/download")
    public ResponseEntity<byte[]> quoteDocument(@PathVariable UUID id,
                                                @RequestParam CpqService.QuoteDocumentFormat format) {
        CpqService.FilePayload file = cpq.quoteDocument(id, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }
}

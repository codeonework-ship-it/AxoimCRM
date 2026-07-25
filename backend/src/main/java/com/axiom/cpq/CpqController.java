package com.axiom.cpq;

import com.axiom.api.PageResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cpq")
public class CpqController {
    private final CpqService cpq;

    public CpqController(CpqService cpq) {
        this.cpq = cpq;
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

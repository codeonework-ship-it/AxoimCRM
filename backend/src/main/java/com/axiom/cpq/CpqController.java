package com.axiom.cpq;

import com.axiom.api.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}

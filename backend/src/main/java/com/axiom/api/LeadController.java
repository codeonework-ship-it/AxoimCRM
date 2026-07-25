package com.axiom.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leads")
public class LeadController {

    private final QueryService queries;
    private final LeadService leadService;

    public LeadController(QueryService queries, LeadService leadService) {
        this.queries = queries;
        this.leadService = leadService;
    }

    @GetMapping
    public List<QueryService.LeadRow> list() {
        return queries.listLeads();
    }

    public record ConvertRequest(String accountName, String opportunityName, BigDecimal amount) {}

    @PostMapping("/{id}/convert")
    @ResponseStatus(HttpStatus.CREATED)
    public LeadService.ConversionResult convert(@PathVariable UUID id,
                                                @RequestBody(required = false) ConvertRequest request) {
        ConvertRequest r = request == null ? new ConvertRequest(null, null, null) : request;
        return leadService.convert(id, r.accountName(), r.opportunityName(), r.amount());
    }
}

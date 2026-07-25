package com.axiom.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final QueryService queries;

    public DashboardController(QueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/summary")
    public QueryService.DashboardSummary summary() {
        return queries.dashboardSummary();
    }
}

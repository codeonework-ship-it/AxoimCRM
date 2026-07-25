package com.axiom.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final QueryService queries;

    public AccountController(QueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public PageResult<QueryService.AccountRow> list(@RequestParam(required = false) String search,
                                                    @RequestParam(required = false) String industry,
                                                    @RequestParam(defaultValue = "0") int page) {
        return queries.listAccounts(search, industry, page);
    }
}

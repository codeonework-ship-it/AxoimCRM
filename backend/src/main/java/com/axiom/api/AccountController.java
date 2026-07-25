package com.axiom.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final QueryService queries;

    public AccountController(QueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public List<QueryService.AccountRow> list() {
        return queries.listAccounts();
    }
}

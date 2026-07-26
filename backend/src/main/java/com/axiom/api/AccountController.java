package com.axiom.api;

import com.axiom.accounts.AccountService;
import com.axiom.accounts.AccountHealthService;
import com.axiom.accounts.RollupService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final QueryService queries;
    private final AccountService accounts;
    private final RollupService rollups;
    private final AccountHealthService health;

    public AccountController(QueryService queries, AccountService accounts,
                             RollupService rollups, AccountHealthService health) {
        this.queries = queries;
        this.accounts = accounts;
        this.rollups = rollups;
        this.health = health;
    }

    @GetMapping
    public PageResult<QueryService.AccountRow> list(@RequestParam(required = false) String search,
                                                    @RequestParam(required = false) String industry,
                                                    @RequestParam(defaultValue = "0") int page) {
        return queries.listAccounts(search, industry, page);
    }

    @GetMapping("/{id}")
    public AccountService.AccountDetail detail(@PathVariable UUID id) {
        return accounts.get(id);
    }

    @GetMapping("/{id}/hierarchy")
    public AccountService.HierarchyView hierarchy(@PathVariable UUID id) {
        return accounts.hierarchy(id);
    }

    @GetMapping("/{id}/rollup")
    public RollupService.RollupView rollup(@PathVariable UUID id) {
        return rollups.rollup(id);
    }

    @GetMapping("/{id}/health")
    public AccountHealthService.Health health(@PathVariable UUID id) {
        return health.current(id);
    }

    @PostMapping("/{id}/health/recompute")
    public AccountHealthService.Health recomputeHealth(@PathVariable UUID id) {
        return health.recompute(id);
    }
}

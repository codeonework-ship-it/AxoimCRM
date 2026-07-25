package com.axiom.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/contacts")
public class ContactController {

    private final QueryService queries;

    public ContactController(QueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public List<QueryService.ContactRow> list(@RequestParam(required = false) UUID accountId) {
        return queries.listContacts(accountId);
    }
}

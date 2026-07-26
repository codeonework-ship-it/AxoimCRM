package com.axiom.cpq;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cpq/quotes")
public class QuoteRevisionController {
    private final QuoteRevisionService revisions;

    public QuoteRevisionController(QuoteRevisionService revisions) {
        this.revisions = revisions;
    }

    @PostMapping("/{id}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteRevisionService.RevisionResult revise(@PathVariable UUID id,
                                                      @RequestBody @Valid QuoteRevisionService.RevisionRequest request) {
        return revisions.revise(id, request);
    }
}

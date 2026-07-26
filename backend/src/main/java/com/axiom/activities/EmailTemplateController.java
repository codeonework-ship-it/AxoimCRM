package com.axiom.activities;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/engagement/email-templates")
public class EmailTemplateController {
    private final EmailTemplateService templates;

    public EmailTemplateController(EmailTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    public List<EmailTemplateService.TemplateRow> list() {
        return templates.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmailTemplateService.TemplateRow create(@RequestBody @Valid EmailTemplateService.CreateRequest request) {
        return templates.create(request);
    }

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public EmailTemplateService.TemplateRow revise(@PathVariable UUID id,
                                                    @RequestBody @Valid EmailTemplateService.ReviseRequest request) {
        return templates.revise(id, request);
    }
}

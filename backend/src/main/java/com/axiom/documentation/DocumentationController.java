package com.axiom.documentation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documentation")
public class DocumentationController {
    private final DocumentationService documentation;

    public DocumentationController(DocumentationService documentation) { this.documentation = documentation; }

    @GetMapping("/drawer")
    public DocumentationService.DrawerView drawer(@RequestParam(defaultValue = "en") String locale) {
        return documentation.drawer(locale);
    }

    @GetMapping("/master")
    public DocumentationService.MasterView master(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return documentation.master(includeInactive);
    }

    @PatchMapping("/master")
    public DocumentationService.MasterView update(@RequestBody @Valid DocumentationService.DrawerUpdate request) {
        return documentation.updateDrawer(request);
    }

    @PostMapping("/master/sections")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentationService.MasterView createSection(@RequestBody @Valid DocumentationService.SectionRequest request) {
        return documentation.createSection(request);
    }

    @PatchMapping("/master/sections/{id}")
    public DocumentationService.MasterView updateSection(@PathVariable UUID id,
            @RequestBody @Valid DocumentationService.SectionRequest request) {
        return documentation.updateSection(id, request);
    }

    @PostMapping("/master/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentationService.MasterView createEntry(@RequestBody @Valid DocumentationService.EntryRequest request) {
        return documentation.createEntry(request);
    }

    @PatchMapping("/master/entries/{id}")
    public DocumentationService.MasterView updateEntry(@PathVariable UUID id,
            @RequestBody @Valid DocumentationService.EntryRequest request) {
        return documentation.updateEntry(id, request);
    }
}

package com.axiom.reference;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reference")
@Validated
public class ReferenceDataController {
    private final ReferenceDataService service;

    public ReferenceDataController(ReferenceDataService service) {
        this.service = service;
    }

    @GetMapping("/value-sets")
    public List<ReferenceDataService.ValueSetRow> valueSets() {
        return service.listValueSets();
    }

    @GetMapping("/value-sets/{apiName}/entries")
    public List<ReferenceDataService.EntryRow> entries(@PathVariable String apiName,
                                                       @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.listEntries(apiName, includeInactive);
    }

    @PostMapping("/value-sets/{apiName}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public ReferenceDataService.EntryRow createEntry(@PathVariable String apiName,
                                                     @RequestBody @Valid EntryMutation request) {
        return service.createEntry(apiName, request);
    }

    @PatchMapping("/value-sets/{apiName}/entries/{code}")
    public ReferenceDataService.EntryRow updateEntry(@PathVariable String apiName,
                                                     @PathVariable String code,
                                                     @RequestBody @Valid EntryMutation request) {
        return service.updateEntry(apiName, code, request);
    }
}

package com.axiom.automation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The enforced business process designer and its runtime view (FR-AUT-004). */
@RestController
@RequestMapping("/api/v1/automation/processes")
@Validated
public class ProcessController {

    private final ProcessService processes;

    public ProcessController(ProcessService processes) {
        this.processes = processes;
    }

    @GetMapping
    public List<ProcessService.ProcessView> list() {
        return processes.list();
    }

    @GetMapping("/{id}")
    public ProcessService.ProcessView get(@PathVariable UUID id) {
        AutomationAccess.requireRead();
        return processes.get(id);
    }

    @GetMapping("/active/{objectType}")
    public Map<String, Object> active(@PathVariable String objectType) {
        AutomationAccess.requireRead();
        ProcessService.ProcessView process = processes.activeFor(objectType);
        return process == null
                ? Map.of("objectType", objectType.toUpperCase(java.util.Locale.ROOT), "active", false)
                : Map.of("objectType", process.objectType(), "active", true, "process", process);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProcessService.ProcessView create(@RequestBody @Valid ProcessService.ProcessMutation request) {
        return processes.create(request);
    }

    @PutMapping("/{id}")
    public ProcessService.ProcessView update(@PathVariable UUID id,
                                             @RequestBody @Valid ProcessService.ProcessMutation request) {
        return processes.update(id, request);
    }

    @PostMapping("/{id}/status")
    public ProcessService.ProcessView status(@PathVariable UUID id,
                                             @RequestBody Map<String, String> body) {
        return processes.setStatus(id, body.get("status"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        processes.delete(id);
    }

    /** Where records currently sit in their process, with per-state SLA status. */
    @GetMapping("/instances")
    public List<ProcessService.InstanceView> instances(
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) UUID recordId) {
        return processes.instances(objectType, recordId);
    }
}

package com.axiom.reference;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * FR-MDM-006 / FR-MDM-007 API surface.
 *
 * <p>Note the deliberate split between {@code /selectable} and {@code /resolve}:
 * the first is what a form may offer, the second is what a stored value means.
 * A deactivated value disappears from the first and never from the second.
 */
@RestController
@RequestMapping("/api/v1/reference")
@Validated
public class PicklistGovernanceController {

    private final PicklistGovernanceService service;

    public PicklistGovernanceController(PicklistGovernanceService service) {
        this.service = service;
    }

    @GetMapping("/value-sets/{apiName}/selectable")
    public List<PicklistGovernanceService.ValueOption> selectable(
            @PathVariable String apiName,
            @RequestParam(required = false) LocalDate asOf) {
        return service.selectable(apiName, asOf);
    }

    @GetMapping("/value-sets/{apiName}/resolve/{code}")
    public PicklistGovernanceService.ValueOption resolve(
            @PathVariable String apiName, @PathVariable String code,
            @RequestParam(required = false) LocalDate asOf) {
        return service.resolve(apiName, code, asOf);
    }

    @GetMapping("/dependencies")
    public List<PicklistGovernanceService.DependencyRow> dependencies() {
        return service.dependencies();
    }

    @PostMapping("/dependencies")
    @ResponseStatus(HttpStatus.CREATED)
    public PicklistGovernanceService.DependencyRow createDependency(
            @RequestBody @Valid PicklistGovernanceService.DependencyRequest request) {
        return service.createDependency(request);
    }

    @GetMapping("/value-sets/{apiName}/dependent-options")
    public List<PicklistGovernanceService.ValueOption> dependentOptions(
            @PathVariable String apiName,
            @RequestParam String controllingCode,
            @RequestParam(required = false) LocalDate asOf) {
        return service.dependentOptions(apiName, controllingCode, asOf);
    }

    @GetMapping("/value-sets/{apiName}/validate-combination")
    public PicklistGovernanceService.CombinationVerdict validateCombination(
            @PathVariable String apiName,
            @RequestParam String controllingCode,
            @RequestParam String dependentCode,
            @RequestParam(required = false) LocalDate asOf) {
        return service.validateCombination(apiName, controllingCode, dependentCode, asOf);
    }

    @GetMapping("/value-sets/{apiName}/mapping")
    public Map<String, List<String>> mapping(@PathVariable String apiName) {
        return service.mappingMatrix(apiName);
    }

    @PutMapping("/value-sets/{apiName}/mapping")
    public List<PicklistGovernanceService.ValueOption> setMapping(
            @PathVariable String apiName,
            @RequestBody @Valid PicklistGovernanceService.MappingRequest request) {
        return service.setMapping(apiName, request);
    }
}

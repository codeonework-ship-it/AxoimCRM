package com.axiom.workspaces;

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
@RequestMapping("/api/v1/workspaces/contracts")
public class ContractRenewalController {
    private final ContractRenewalService renewals;

    public ContractRenewalController(ContractRenewalService renewals) {
        this.renewals = renewals;
    }

    @PostMapping("/{id}/renewal")
    @ResponseStatus(HttpStatus.CREATED)
    public ContractRenewalService.RenewalResult prepare(@PathVariable UUID id,
                                                        @RequestBody @Valid ContractRenewalService.RenewalRequest request) {
        return renewals.prepare(id, request);
    }
}

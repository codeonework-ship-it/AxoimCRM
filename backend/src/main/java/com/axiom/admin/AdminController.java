package com.axiom.admin;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/users")
    public List<AdminService.UserRow> users() {
        return admin.users();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminService.UserRow createUser(@RequestBody @Valid AdminService.CreateUserRequest request) {
        return admin.createUser(request);
    }

    @PatchMapping("/users/{id}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setUserActive(@PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        admin.setUserActive(id, Boolean.TRUE.equals(body.get("active")));
    }

    @GetMapping("/companies")
    public List<AdminService.CompanyAccountRow> companies() {
        return admin.companies();
    }

    @PostMapping("/trials/{tenantId}/extend")
    public AdminService.CompanyAccountRow extendTrial(@PathVariable UUID tenantId,
                                                      @RequestBody @Valid AdminService.TrialExtensionRequest request) {
        return admin.extendTrial(tenantId, request);
    }

    @PatchMapping("/companies/{tenantId}/status")
    public AdminService.CompanyAccountRow setCompanyStatus(@PathVariable UUID tenantId,
                                                           @RequestBody @Valid AdminService.CompanyStatusRequest request) {
        return admin.setCompanyStatus(tenantId, request);
    }

    @GetMapping("/billing")
    public List<AdminService.BillingRow> billing() {
        return admin.billing();
    }
}

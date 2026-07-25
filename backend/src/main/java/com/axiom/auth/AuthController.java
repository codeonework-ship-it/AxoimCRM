package com.axiom.auth;

import com.axiom.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) { this.authService = authService; }

    public record LoginRequest(@NotBlank String tenantSlug, @NotBlank String email,
                               @NotBlank String password) {}
    public record SwitchTenantRequest(@NotBlank String tenantSlug) {}
    public record UserDto(UUID id, String displayName, String email, String role, boolean platformUser) {}
    public record TenantDto(UUID id, String slug, String name) {}
    public record LoginResponse(String token, UserDto user, TenantDto tenant) {}

    @PostMapping("/login")
    public LoginResponse login(@RequestBody @Valid LoginRequest request) {
        return response(authService.login(request.tenantSlug(), request.email(), request.password()));
    }

    @GetMapping("/tenants")
    public List<AuthService.TenantOption> tenants() { return authService.tenants(); }

    @PostMapping("/switch-tenant")
    public LoginResponse switchTenant(@RequestBody @Valid SwitchTenantRequest request) {
        return response(authService.switchTenant(request.tenantSlug()));
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        TenantContext.Principal p = TenantContext.get();
        return Map.of("userId", p.userId(), "tenantId", p.tenantId(), "role", p.role(),
                "displayName", p.displayName(), "email", p.email(),
                "platformUser", CrmRole.current(p.role()).platform());
    }

    private static LoginResponse response(AuthService.AuthResult r) {
        return new LoginResponse(r.token(),
                new UserDto(r.userId(), r.displayName(), r.email(), r.role(), r.platformUser()),
                new TenantDto(r.tenantId(), r.tenantSlug(), r.tenantName()));
    }
}
